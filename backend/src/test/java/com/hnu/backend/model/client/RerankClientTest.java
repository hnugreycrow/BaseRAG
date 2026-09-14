package com.hnu.backend.model.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.model.http.ModelHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RerankClientTest {
  private HttpServer server;
  private AiProperties config;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    config = configured("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void sendsCompatibleRequestAndMapsCompleteScores() {
    List<String> requests = new ArrayList<>();
    server.createContext(
        "/compatible-api/v1/reranks",
        exchange -> {
          requests.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response =
              ("{\"object\":\"list\",\"results\":["
                      + "{\"index\":1,\"relevance_score\":0.9},"
                      + "{\"index\":0,\"relevance_score\":0.2}],"
                      + "\"model\":\"qwen3-rerank\",\"id\":\"req-1\","
                      + "\"usage\":{\"total_tokens\":17}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });

    RerankClient.Generation result =
        new RerankClient(config, new ModelHttpClient(config)).rerank("年假要求", List.of("甲", "乙"));

    assertEquals(List.of(1, 0), result.ranks().stream().map(RerankClient.Rank::index).toList());
    assertEquals("req-1", result.requestId());
    assertEquals(17, result.totalTokens());
    assertTrue(requests.getFirst().contains("\"query\":\"年假要求\""));
    assertTrue(requests.getFirst().contains("\"documents\":[\"甲\",\"乙\"]"));
    assertTrue(requests.getFirst().contains("\"top_n\":2"));
    assertTrue(requests.getFirst().contains("\"instruct\":"));
  }

  @Test
  void invalidOrHttpErrorResponseFallsBackToNoop() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/compatible-api/v1/reranks",
        exchange -> {
          calls.incrementAndGet();
          byte[] response =
              "{\"results\":[{\"index\":0,\"relevance_score\":2}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });

    RerankClient.Generation result =
        new RerankClient(config, new ModelHttpClient(config)).rerank("问题", List.of("甲"));

    assertTrue(result.noop());
    assertEquals("rerank-noop", result.modelId());
    assertEquals(1, calls.get());
  }

  @Test
  void badRequestFallsBackToNoopWithoutRetrying() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/compatible-api/v1/reranks",
        exchange -> {
          calls.incrementAndGet();
          byte[] response = "request token limit exceeded".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(400, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });

    RerankClient.Generation result =
        new RerankClient(config, new ModelHttpClient(config)).rerank("问题", List.of("甲"));

    assertTrue(result.noop());
    assertEquals(1, calls.get());
  }

  @Test
  void noopOnlyConfigurationNeverUsesHttp() {
    config.getRerank().setDefaultModel("rerank-noop");
    config.getRerank().setCandidates(List.of(candidate("rerank-noop", "noop", "noop", 100)));

    RerankClient.Generation result =
        new RerankClient(config, new ModelHttpClient(config)).rerank("问题", List.of("甲"));

    assertTrue(result.noop());
    assertTrue(result.ranks().isEmpty());
  }

  private AiProperties configured(String baseUrl) {
    AiProperties value = new AiProperties();
    value.getSelection().setMaxRetries(0);
    AiProperties.Provider provider = new AiProperties.Provider();
    provider.setUrl(baseUrl);
    provider.setApiKey("test-only");
    provider.getEndpoints().setRerank("/compatible-api/v1/reranks");
    value.getProviders().put("bailian", provider);
    value.getRerank().setDefaultModel("qwen3-rerank");
    value
        .getRerank()
        .setCandidates(
            List.of(
                candidate("qwen3-rerank", "bailian", "qwen3-rerank", 1),
                candidate("rerank-noop", "noop", "noop", 100)));
    return value;
  }

  private AiProperties.Candidate candidate(String id, String provider, String model, int priority) {
    AiProperties.Candidate value = new AiProperties.Candidate();
    value.setId(id);
    value.setProvider(provider);
    value.setModel(model);
    value.setPriority(priority);
    return value;
  }
}
