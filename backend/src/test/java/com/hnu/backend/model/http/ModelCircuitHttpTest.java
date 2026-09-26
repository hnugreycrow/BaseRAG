package com.hnu.backend.model.http;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.model.configuration.AiProperties;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelCircuitHttpTest {
  private HttpServer server;
  private ModelHttpClient http;
  private AiProperties config;
  private AiProperties.ModelTarget target;
  private final AtomicInteger calls = new AtomicInteger();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    config = new AiProperties();
    config.getSelection().setMaxRetries(1);
    target =
        new AiProperties.ModelTarget(
            "test",
            "test",
            "test",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "/test",
            "test-only",
            3000,
            0,
            false);
    http = new ModelHttpClient(config);
  }

  @AfterEach
  void stop() {
    http.close();
    server.stop(0);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 422})
  void clientErrorsDoNotOpenCircuit(int status) {
    respond(status, "{}");
    for (int i = 0; i < 3; i++) {
      assertEquals("MODEL_HTTP_ERROR", failure().code());
    }
    assertEquals(3, calls.get());
  }

  @Test
  void retriesCountOncePerCandidateAndOpenCircuitMakesNoHttpCall() {
    respond(503, "{}");
    assertEquals("MODEL_HTTP_ERROR", failure().code());
    assertEquals("MODEL_HTTP_ERROR", failure().code());
    assertEquals("MODEL_CIRCUIT_OPEN", failure().code());
    assertEquals(4, calls.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"60", "date", "invalid", ""})
  void rateLimitCooldownSkipsFurtherNetworkRequests(String retryAfter) {
    server.createContext(
        "/test",
        exchange -> {
          calls.incrementAndGet();
          if (!retryAfter.isEmpty()) {
            exchange
                .getResponseHeaders()
                .set(
                    "Retry-After",
                    retryAfter.equals("date")
                        ? ZonedDateTime.now(java.time.ZoneOffset.UTC)
                            .plusMinutes(1)
                            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
                        : retryAfter);
          }
          exchange.sendResponseHeaders(429, -1);
          exchange.close();
        });
    for (int i = 0; i < 3; i++) {
      assertEquals("MODEL_RATE_LIMITED", failure().code());
    }
    assertEquals(1, calls.get());
  }

  @Test
  void decodingFailureIsNotPrematurelyResetByHttpSuccess() {
    respond(200, "{}");
    for (int i = 0; i < 2; i++) {
      assertEquals(
          "EMBEDDING_INVALID_RESPONSE",
          assertThrows(
                  ApiException.class,
                  () ->
                      http.post(
                          target,
                          Map.of(),
                          node -> {
                            throw ApiException.upstream(
                                ErrorCode.EMBEDDING_INVALID_RESPONSE, "invalid");
                          }))
              .code());
    }
    assertEquals("MODEL_CIRCUIT_OPEN", failure().code());
    assertEquals(2, calls.get());
  }

  @Test
  void localDecoderErrorsDoNotTripCircuit() {
    respond(200, "{}");
    for (int i = 0; i < 3; i++) {
      assertThrows(
          IllegalStateException.class,
          () ->
              http.post(
                  target,
                  Map.of(),
                  node -> {
                    throw new IllegalStateException("local bug");
                  }));
    }
    assertEquals(3, calls.get());
  }

  @Test
  void completionValidationFailureTripsStreamCircuit() {
    respond(200, "data: [DONE]\n\n");
    for (int i = 0; i < 2; i++) {
      assertEquals(
          "MODEL_INVALID_RESPONSE",
          assertThrows(
                  ApiException.class,
                  () ->
                      http.stream(
                          target,
                          Map.of(),
                          event -> {},
                          new ModelHttpClient.StreamControl(),
                          System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
                          event -> false,
                          () -> {
                            throw ApiException.upstream(
                                ErrorCode.MODEL_INVALID_RESPONSE, "empty stream");
                          }))
              .code());
    }
    assertEquals("MODEL_CIRCUIT_OPEN", failure().code());
    assertEquals(2, calls.get());
  }

  @Test
  void totalBudgetExhaustionDoesNotTripCircuit() {
    respond(200, "{}");
    for (int i = 0; i < 3; i++) {
      assertEquals(
          "MODEL_TIMEOUT",
          assertThrows(
                  ApiException.class,
                  () ->
                      http.stream(
                          target,
                          Map.of(),
                          event -> {},
                          new ModelHttpClient.StreamControl(),
                          System.nanoTime() - 1,
                          event -> false))
              .code());
    }
    assertNotNull(http.post(target, Map.of()));
    assertEquals(1, calls.get());
  }

  @Test
  void downstreamCallbackFailureDoesNotTripCircuit() {
    respond(200, "data: {}\n\ndata: [DONE]\n\n");
    for (int i = 0; i < 3; i++) {
      assertThrows(
          IllegalStateException.class,
          () ->
              http.stream(
                  target,
                  Map.of(),
                  event -> {
                    throw new IllegalStateException("downstream disconnected");
                  },
                  new ModelHttpClient.StreamControl()));
    }
    assertEquals(3, calls.get());
  }

  private ApiException failure() {
    return assertThrows(ApiException.class, () -> http.post(target, Map.of()));
  }

  private void respond(int status, String content) {
    server.createContext(
        "/test",
        exchange -> {
          calls.incrementAndGet();
          byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }
}
