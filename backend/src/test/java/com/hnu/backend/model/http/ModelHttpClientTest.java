package com.hnu.backend.model.http;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.shared.error.ApiException;
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
        new float[] {1, 0}, new EmbeddingClient(config, http).embed(List.of("甲", "乙")).getFirst());
    assertTrue(requests.getFirst().contains("\"stream\":false"));
    assertFalse(requests.getFirst().contains("max_tokens"));
    assertTrue(requests.get(1).contains("\"input\":[\"甲\",\"乙\"]"));
    assertTrue(requests.get(1).contains("\"dimensions\":2"));
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
  void retriesRateLimitOnlyToConfiguredLimitAndHidesProviderBody() {
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
    assertEquals(2, calls.get());
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
