package com.hnu.backend.model.http;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.model.client.ChatGenerationRequest;
import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.shared.error.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

@Timeout(10)
class ModelStreamTimeoutTest {
  private HttpServer server;
  private ExecutorService workers;
  private AiProperties config;
  private ModelHttpClient http;
  private final CountDownLatch release = new CountDownLatch(1);
  private final AtomicInteger primaryCalls = new AtomicInteger();
  private final AtomicInteger fallbackCalls = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(workers);
    server.start();
    config = new AiProperties();
    config.getSelection().setMaxRetries(1);
    config.getStream().setFirstContentTimeoutMs(800);
    config.getStream().setIdleTimeoutMs(180);
    config.getStream().setTotalTimeoutMs(5_000);
    for (String id : List.of("primary", "fallback")) {
      var provider = new AiProperties.Provider();
      provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
      provider.setApiKey("test-only");
      provider.getEndpoints().setChat("/" + id);
      config.getProviders().put(id, provider);
      var candidate = new AiProperties.Candidate();
      candidate.setId(id);
      candidate.setProvider(id);
      candidate.setModel(id);
      config.getChat().getCandidates().add(candidate);
    }
    var tier = new AiProperties.Tier();
    tier.setCandidates(List.of("primary", "fallback"));
    config.getChat().getTiers().put("standard", tier);
    server.createContext(
        "/fallback",
        exchange -> {
          fallbackCalls.incrementAndGet();
          headers(exchange);
          write(
              exchange,
              "data: {\"choices\":[{\"delta\":{\"content\":\"备用回答\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
          exchange.close();
        });
    http = new ModelHttpClient(config);
  }

  @AfterEach
  void tearDown() {
    release.countDown();
    http.close();
    server.stop(0);
    workers.shutdownNow();
  }

  @Test
  void noHeadersTimesOutWithoutRetryAndUsesFallback() {
    server.createContext(
        "/primary",
        exchange -> {
          primaryCalls.incrementAndGet();
          hold(exchange);
        });
    var observer = new Observer();
    assertEquals("fallback", chat().stream("s", "u", observer, control()).id());
    assertEquals(1, primaryCalls.get());
    assertEquals(1, fallbackCalls.get());
    assertEquals(List.of("PRIMARY", "PROVIDER_FALLBACK"), observer.reasons);
    assertEquals(List.of("MODEL_TIMEOUT"), observer.errors);
  }

  @Test
  void roleAndHeartbeatDoNotCountAsFirstContent() {
    server.createContext(
        "/primary",
        exchange -> {
          primaryCalls.incrementAndGet();
          headers(exchange);
          try {
            while (release.getCount() > 0) {
              write(
                  exchange,
                  ": ping\n\ndata: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n");
              if (release.await(30, TimeUnit.MILLISECONDS)) {
                break;
              }
            }
          } catch (IOException ignored) {
            // 客户端超时后主动关闭旧流是本用例的预期行为。
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    assertEquals("fallback", chat().stream("s", "u", new Observer(), control()).id());
    assertEquals(1, primaryCalls.get());
  }

  @Test
  void bodyIdleTimeoutPreservesOutputAndDoesNotFallback() {
    hangingContent("content", "部分回答");
    var observer = new Observer();
    assertTimeoutError(() -> chat().stream("s", "u", observer, control()));
    assertEquals("部分回答", observer.content.toString());
    assertEquals(List.of("MODEL_TIMEOUT"), observer.errors);
    assertEquals(1, primaryCalls.get());
    assertEquals(0, fallbackCalls.get());
  }

  @Test
  void visibleReasoningAlsoPreventsFallback() {
    config.getChat().getCandidates().getFirst().setSupportsThinking(true);
    hangingContent("reasoning_content", "正在思考");
    var observer = new Observer();
    ChatClient chat =
        new ChatClient(
            http,
            config,
            List.of(
                new com.hnu.backend.model.client.ThinkingParameterAdapter() {
                  @Override
                  public String provider() {
                    return "primary";
                  }

                  @Override
                  public void apply(Map<String, Object> payload, boolean enabled) {}
                }));
    assertTimeoutError(
        () -> chat.stream(new ChatGenerationRequest("s", "u", true), observer, control()));
    assertEquals("正在思考", observer.reasoning.toString());
    assertEquals(0, fallbackCalls.get());
  }

  @Test
  void hiddenReasoningDoesNotCountAsContentAndCanFallback() {
    hangingContent("reasoning_content", "不展示的思考");
    assertEquals("fallback", chat().stream("s", "u", new Observer(), control()).id());
    assertEquals(1, primaryCalls.get());
  }

  @Test
  void totalBudgetIsSharedAcrossCandidates() {
    config.getStream().setTotalTimeoutMs(1_200);
    server.removeContext("/fallback");
    for (String id : List.of("primary", "fallback")) {
      server.createContext(
          "/" + id,
          exchange -> {
            (id.equals("primary") ? primaryCalls : fallbackCalls).incrementAndGet();
            headers(exchange);
            hold(exchange);
          });
    }
    var observer = new Observer();
    ApiException error = assertTimeoutError(() -> chat().stream("s", "u", observer, control()));
    assertEquals(1, primaryCalls.get());
    assertEquals(1, fallbackCalls.get());
    assertTrue(error.getCause().getMessage().contains("TOTAL"));
  }

  @Test
  void totalBudgetStopsAContinuouslyActiveStream() {
    config.getStream().setTotalTimeoutMs(900);
    server.createContext(
        "/primary",
        exchange -> {
          primaryCalls.incrementAndGet();
          headers(exchange);
          try {
            while (release.getCount() > 0) {
              write(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"字\"}}]}\n\n");
              if (release.await(30, TimeUnit.MILLISECONDS)) {
                break;
              }
            }
          } catch (IOException ignored) {
            // 达到总预算后客户端会关闭输入流。
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    var observer = new Observer();
    ApiException error = assertTimeoutError(() -> chat().stream("s", "u", observer, control()));
    assertFalse(observer.content.isEmpty());
    assertTrue(error.getCause().getMessage().contains("TOTAL"));
    assertEquals(0, fallbackCalls.get());
  }

  @Test
  void cancellationBeforeHeadersDoesNotTripCircuitOrFallback() throws Exception {
    config.getSelection().setFailureThreshold(1);
    var entered = new CountDownLatch(1);
    server.createContext(
        "/primary",
        exchange -> {
          primaryCalls.incrementAndGet();
          entered.countDown();
          hold(exchange);
        });
    var control = control();
    Future<ApiException> result =
        workers.submit(
            () ->
                assertThrows(
                    ApiException.class, () -> chat().stream("s", "u", new Observer(), control)));
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    control.close();
    assertEquals("GENERATION_CANCELLED", result.get(2, TimeUnit.SECONDS).code());
    assertEquals(0, fallbackCalls.get());
    server.removeContext("/primary");
    server.createContext(
        "/primary",
        exchange -> {
          byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    assertNotNull(http.post(config.chatModels().getFirst(), Map.of()));
  }

  @Test
  void retryBackoffDoesNotResetFirstContentBudget() {
    config.getStream().setFirstContentTimeoutMs(200);
    server.createContext(
        "/primary",
        exchange -> {
          primaryCalls.incrementAndGet();
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    assertEquals("fallback", chat().stream("s", "u", new Observer(), control()).id());
    assertEquals(1, primaryCalls.get());
  }

  @Test
  void nonStreamingTimeoutDoesNotRetry() {
    config.getChat().getTiers().get("standard").setTimeoutMs(200);
    server.createContext(
        "/primary",
        exchange -> {
          primaryCalls.incrementAndGet();
          hold(exchange);
        });
    assertTimeoutError(() -> http.post(config.chatModels().getFirst(), Map.of()));
    assertEquals(1, primaryCalls.get());
  }

  @Test
  void eachTimedOutCandidateCountsOnceAndSubsequentCallsSkipOpenCircuit() {
    config.getStream().setFirstContentTimeoutMs(300);
    server.createContext(
        "/primary",
        exchange -> {
          primaryCalls.incrementAndGet();
          headers(exchange);
          hold(exchange);
        });
    for (int call = 0; call < 3; call++) {
      assertEquals("fallback", chat().stream("s", "u", new Observer(), control()).id());
    }
    assertEquals(2, primaryCalls.get());
    assertEquals(3, fallbackCalls.get());
  }

  @Test
  void cancellationDuringBodyReadStopsWithoutFallback() throws Exception {
    hangingContent("content", "部分回答");
    var contentReceived = new CountDownLatch(1);
    var observer =
        new Observer() {
          @Override
          public void delta(String text) {
            super.delta(text);
            contentReceived.countDown();
          }
        };
    var control = control();
    Future<ApiException> result =
        workers.submit(
            () ->
                assertThrows(ApiException.class, () -> chat().stream("s", "u", observer, control)));
    assertTrue(contentReceived.await(2, TimeUnit.SECONDS));
    control.close();
    assertEquals("GENERATION_CANCELLED", result.get(2, TimeUnit.SECONDS).code());
    assertEquals("部分回答", observer.content.toString());
    assertEquals(0, fallbackCalls.get());
  }

  private void hangingContent(String field, String text) {
    server.createContext(
        "/primary",
        exchange -> {
          primaryCalls.incrementAndGet();
          headers(exchange);
          write(
              exchange,
              "data: {\"choices\":[{\"delta\":{\"" + field + "\":\"" + text + "\"}}]}\n\n");
          hold(exchange);
        });
  }

  private void hold(HttpExchange exchange) {
    try {
      release.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  private void headers(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    exchange.getResponseBody().flush();
  }

  private void write(HttpExchange exchange, String text) throws IOException {
    exchange.getResponseBody().write(text.getBytes(StandardCharsets.UTF_8));
    exchange.getResponseBody().flush();
  }

  private ChatClient chat() {
    return new ChatClient(http, config);
  }

  private ModelHttpClient.StreamControl control() {
    return new ModelHttpClient.StreamControl();
  }

  private ApiException assertTimeoutError(org.junit.jupiter.api.function.Executable action) {
    ApiException error = assertThrows(ApiException.class, action);
    assertEquals("MODEL_TIMEOUT", error.code());
    return error;
  }

  private static class Observer implements ChatClient.StreamObserver {
    final List<String> reasons = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final StringBuilder content = new StringBuilder();
    final StringBuilder reasoning = new StringBuilder();

    @Override
    public void started(AiProperties.ModelTarget target, String reason) {
      reasons.add(reason);
    }

    @Override
    public void delta(String text) {
      content.append(text);
    }

    @Override
    public void reasoningDelta(String text) {
      reasoning.append(text);
    }

    @Override
    public void completed(AiProperties.ModelTarget target, String text, String finishReason) {}

    @Override
    public void failed(AiProperties.ModelTarget target, String partial, ApiException error) {
      errors.add(error.code());
    }
  }
}
