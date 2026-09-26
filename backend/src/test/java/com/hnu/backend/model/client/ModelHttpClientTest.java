package com.hnu.backend.model.client;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.model.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class ModelHttpClientTest {
  private HttpServer server;
  private AiProperties config;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    config = config("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void realHttpChatProtocolAndEmbeddingsBatchOrder() {
    List<String> requests = new ArrayList<>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          assertEquals("Bearer test-only", exchange.getRequestHeaders().getFirst("Authorization"));
          requests.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response =
              "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"答案 [S1]\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          requests.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response =
              "{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    var http = new ModelHttpClient(config);
    assertEquals("答案 [S1]", new ChatClient(http, config).generate("系统", "问题").content());
    assertArrayEquals(
        new float[] {1, 0},
        new EmbeddingClient(config, List.of(new OpenAICompatibleEmbeddingAdapter(http)))
            .embed(List.of("甲", "乙"))
            .getFirst());
    assertTrue(requests.getFirst().contains("\"stream\":false"));
    assertFalse(requests.getFirst().contains("max_tokens"));
    assertTrue(requests.get(1).contains("\"input\":[\"甲\",\"乙\"]"));
    assertTrue(requests.get(1).contains("\"dimensions\":2"));
  }

  @Test
  void aliyunCompatibleEmbeddingUsesItsOwnEndpointAndModel() {
    AiProperties.Provider aliyun = new AiProperties.Provider();
    aliyun.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
    aliyun.setApiKey("aliyun-test-only");
    aliyun.getEndpoints().setEmbedding("/compatible-mode/v1/embeddings");
    config.getProviders().put("bailian", aliyun);
    AiProperties.Candidate model = new AiProperties.Candidate();
    model.setId("bailian-qwen3.7-embedding");
    model.setProvider("bailian");
    model.setModel("qwen3.7-text-embedding");
    model.setDimension(1536);
    config.getEmbedding().getCandidates().add(model);
    List<String> requests = new ArrayList<>();
    server.createContext(
        "/compatible-mode/v1/embeddings",
        exchange -> {
          assertEquals(
              "Bearer aliyun-test-only", exchange.getRequestHeaders().getFirst("Authorization"));
          requests.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String response = "{\"data\":[{\"index\":0,\"embedding\":[1" + ",0".repeat(1535) + "]}]}";
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    var http = new ModelHttpClient(config);
    var embedding =
        new EmbeddingClient(config, List.of(new OpenAICompatibleEmbeddingAdapter(http)));
    assertEquals(
        1536,
        embedding
            .embed(
                "bailian-qwen3.7-embedding",
                "bailian",
                "qwen3.7-text-embedding",
                1536,
                List.of("测试"))
            .getFirst()
            .length);
    assertTrue(requests.getFirst().contains("\"model\":\"qwen3.7-text-embedding\""));
    assertTrue(requests.getFirst().contains("\"dimensions\":1536"));
  }

  @Test
  void streamsOpenAiEventsAndOmitsOutputLimit() {
    List<String> requests = new ArrayList<>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          exchange
              .getResponseBody()
              .write(
                  ("data: {\"choices\":[{\"delta\":{\"content\":\"答\"},\"finish_reason\":null}]}\n\n"
                          + "data: {\"choices\":[{\"delta\":{\"content\":\"案\"},\"finish_reason\":\"stop\"}]}\n\n"
                          + "data: [DONE]\n\n")
                      .getBytes(StandardCharsets.UTF_8));
          exchange.close();
        });
    var chunks = new ArrayList<String>();
    var result =
        new ChatClient(new ModelHttpClient(config), config)
            .stream(
                "系统",
                "问题",
                new ChatClient.StreamObserver() {
                  public void started(AiProperties.ModelTarget target, String reason) {}

                  public void delta(String text) {
                    chunks.add(text);
                  }

                  public void completed(
                      AiProperties.ModelTarget target, String content, String finishReason) {}

                  public void failed(
                      AiProperties.ModelTarget target, String partialContent, ApiException error) {}
                },
                new ModelHttpClient.StreamControl());
    assertEquals("答案", result.content());
    assertEquals(List.of("答", "案"), chunks);
    assertTrue(requests.getFirst().contains("\"stream\":true"));
    assertFalse(requests.getFirst().contains("max_tokens"));
  }

  @Test
  void reportsProviderFallbackAndRequestBoundaryForEveryModelAttempt() {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.createContext(
        "/v1/fallback",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          exchange
              .getResponseBody()
              .write(
                  ("data: {\"choices\":[{\"delta\":{\"content\":\"回答\"},\"finish_reason\":\"stop\"}]}\n\n"
                          + "data: [DONE]\n\n")
                      .getBytes(StandardCharsets.UTF_8));
          exchange.close();
        });
    AiProperties.Provider fallbackProvider = new AiProperties.Provider();
    fallbackProvider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
    fallbackProvider.setApiKey("test-only");
    fallbackProvider.getEndpoints().setChat("/v1/fallback");
    config.getProviders().put("fallback", fallbackProvider);
    AiProperties.Candidate fallback = new AiProperties.Candidate();
    fallback.setId("fallback-chat");
    fallback.setProvider("fallback");
    fallback.setModel("fallback-chat");
    config.getChat().getCandidates().add(fallback);
    config
        .getChat()
        .getTiers()
        .get("standard")
        .setCandidates(List.of("test-chat", "fallback-chat"));
    List<String> reasons = new ArrayList<>();
    List<String> requestedModels = new ArrayList<>();

    var result =
        new ChatClient(new ModelHttpClient(config), config)
            .stream(
                "系统",
                "问题",
                new ChatClient.StreamObserver() {
                  public void started(AiProperties.ModelTarget target, String reason) {
                    reasons.add(reason);
                  }

                  public void requesting(AiProperties.ModelTarget target) {
                    requestedModels.add(target.id());
                  }

                  public void delta(String text) {}

                  public void completed(
                      AiProperties.ModelTarget target, String content, String finishReason) {}

                  public void failed(
                      AiProperties.ModelTarget target, String partialContent, ApiException error) {}
                },
                new ModelHttpClient.StreamControl());

    assertEquals("fallback-chat", result.id());
    assertEquals(List.of("PRIMARY", "PROVIDER_FALLBACK"), reasons);
    assertEquals(List.of("test-chat", "fallback-chat"), requestedModels);
  }

  @Test
  void rateLimitSkipsRetryAndHidesProviderBody() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/v1/test",
        exchange -> {
          calls.incrementAndGet();
          byte[] response = "provider-secret".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(429, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    config.getSelection().setMaxRetries(1);
    AiProperties.ModelTarget target =
        new AiProperties.ModelTarget(
            "test",
            "test",
            "test-chat",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "/v1/test",
            "test-only",
            30_000,
            0,
            false);
    var error =
        assertThrows(ApiException.class, () -> new ModelHttpClient(config).post(target, Map.of()));
    assertEquals(1, calls.get());
    assertEquals("MODEL_RATE_LIMITED", error.code());
    assertFalse(error.getMessage().contains("provider-secret"));
  }

  @Test
  void invalidJsonAndTruncatedAnswerFail() {
    server.createContext(
        "/v1/bad",
        exchange -> {
          exchange.sendResponseHeaders(200, 3);
          exchange.getResponseBody().write("bad".getBytes());
          exchange.close();
        });
    assertEquals(
        "MODEL_INVALID_RESPONSE",
        assertThrows(
                ApiException.class,
                () -> new ModelHttpClient(config).post(target("/v1/bad", 30_000), Map.of()))
            .code());
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] response =
              "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}"
                  .getBytes();
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    assertThrows(
        ApiException.class,
        () -> new ChatClient(new ModelHttpClient(config), config).generate("s", "u"));
  }

  @Test
  void timeoutHasExplicitError() {
    config.getChat().getTiers().get("standard").setTimeoutMs(1_000);
    server.createContext(
        "/v1/slow",
        exchange -> {
          try {
            Thread.sleep(1300);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    assertEquals(
        "MODEL_TIMEOUT",
        assertThrows(
                ApiException.class,
                () -> new ModelHttpClient(config).post(target("/v1/slow", 1_000), Map.of()))
            .code());
  }

  private AiProperties.ModelTarget target(String endpoint, int timeoutMs) {
    return new AiProperties.ModelTarget(
        "test-" + endpoint,
        "test",
        "test-chat",
        "http://127.0.0.1:" + server.getAddress().getPort(),
        endpoint,
        "test-only",
        timeoutMs,
        0,
        false);
  }

  private AiProperties config(String baseUrl) {
    AiProperties value = new AiProperties();
    value.getSelection().setMaxRetries(0);
    AiProperties.Provider provider = new AiProperties.Provider();
    provider.setUrl(baseUrl);
    provider.setApiKey("test-only");
    provider.getEndpoints().setChat("/v1/chat/completions");
    provider.getEndpoints().setEmbedding("/v1/embeddings");
    value.getProviders().put("test", provider);
    AiProperties.Candidate chat = new AiProperties.Candidate();
    chat.setId("test-chat");
    chat.setProvider("test");
    chat.setModel("test-chat");
    value.getChat().getCandidates().add(chat);
    AiProperties.Tier tier = new AiProperties.Tier();
    tier.setCandidates(List.of("test-chat"));
    value.getChat().getTiers().put("standard", tier);
    AiProperties.Candidate embedding = new AiProperties.Candidate();
    embedding.setId("test-embedding");
    embedding.setProvider("test");
    embedding.setModel("test-embedding");
    embedding.setDimension(2);
    value.getEmbedding().setDefaultModel("test-embedding");
    value.getEmbedding().getCandidates().add(embedding);
    return value;
  }
}
