package com.hnu.backend.ai.infrastructure.http;

import com.hnu.backend.ai.config.AiProperties;
import com.hnu.backend.shared.error.ApiException;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ModelHttpClient {
  private final AiProperties config;
  private final HttpClient client;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();

  public ModelHttpClient(AiProperties config) {
    this.config = config;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public void requireConfigured(AiProperties.ModelTarget target) {
    if (target.baseUrl().isBlank()
        || target.endpoint().isBlank()
        || target.model().isBlank()
        || target.apiKey() == null
        || target.apiKey().isBlank()) {
      throw ApiException.bad("MODEL_NOT_CONFIGURED", "请配置 " + target.provider() + " 的 API Key");
    }
  }

  public JsonNode post(AiProperties.ModelTarget target, Map<String, Object> payload) {
    requireConfigured(target);
    Circuit circuit = circuits.computeIfAbsent(target.id(), ignored -> new Circuit());
    if (circuit.isOpen()) {
      throw ApiException.upstream("MODEL_CIRCUIT_OPEN", "模型服务暂时不可用，请稍后重试");
    }
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(target.baseUrl() + target.endpoint()))
            .timeout(Duration.ofMillis(target.timeoutMs()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
    builder.header("Authorization", "Bearer " + target.apiKey());
    HttpRequest request = builder.build();
    for (int attempt = 0; ; attempt++) {
      try {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
          try {
            JsonNode node = json.readTree(response.body());
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            success(circuit);
            return node;
          } catch (RuntimeException e) {
            failure(circuit);
            throw ApiException.upstream("MODEL_INVALID_RESPONSE", "模型服务返回了无效 JSON");
          }
        }
        boolean retryable = status == 429 || status >= 500;
        if (retryable && attempt < config.getSelection().getMaxRetries()) {
          pause(attempt);
          continue;
        }
        failure(circuit);
        throw ApiException.upstream(
            status == 429 ? "MODEL_RATE_LIMITED" : "MODEL_HTTP_ERROR",
            status == 429 ? "模型服务请求频繁，请稍后重试" : "模型服务请求失败，请检查服务配置");
      } catch (HttpTimeoutException e) {
        if (attempt < config.getSelection().getMaxRetries()) {
          pause(attempt);
          continue;
        }
        failure(circuit);
        throw ApiException.upstream("MODEL_TIMEOUT", "模型请求超时，请稍后重试");
      } catch (IOException e) {
        if (attempt < config.getSelection().getMaxRetries()) {
          pause(attempt);
          continue;
        }
        failure(circuit);
        throw ApiException.upstream("MODEL_UNAVAILABLE", "无法连接模型服务");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw ApiException.upstream("REQUEST_INTERRUPTED", "请求已中断");
      }
    }
  }

  public void stream(
      AiProperties.ModelTarget target,
      Map<String, Object> payload,
      Consumer<JsonNode> events,
      StreamControl control) {
    requireConfigured(target);
    Circuit circuit = circuits.computeIfAbsent(target.id(), ignored -> new Circuit());
    if (circuit.isOpen()) {
      throw ApiException.upstream("MODEL_CIRCUIT_OPEN", "模型服务暂时不可用，请稍后重试");
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(target.baseUrl() + target.endpoint()))
            .timeout(Duration.ofMillis(target.timeoutMs()))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer " + target.apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
            .build();
    for (int attempt = 0; ; attempt++) {
      boolean emitted = false;
      try {
        if (control.cancelled()) throw ApiException.cancelled();
        HttpResponse<InputStream> response =
            client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
          response.body().close();
          boolean retryable = status == 429 || status >= 500;
          if (retryable && attempt < config.getSelection().getMaxRetries()) {
            pause(attempt);
            continue;
          }
          failure(circuit);
          throw ApiException.upstream(
              status == 429 ? "MODEL_RATE_LIMITED" : "MODEL_HTTP_ERROR",
              status == 429 ? "模型服务请求频繁，请稍后重试" : "模型服务请求失败，请检查服务配置");
        }
        control.attach(response.body());
        boolean done = false;
        try (var reader =
            new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
          StringBuilder data = new StringBuilder();
          String line;
          while ((line = reader.readLine()) != null) {
            if (control.cancelled()) throw ApiException.cancelled();
            if (line.isEmpty()) {
              if (data.isEmpty()) continue;
              String value = data.toString();
              data.setLength(0);
              if ("[DONE]".equals(value)) {
                done = true;
                break;
              }
              JsonNode node = json.readTree(value);
              if (node == null || !node.isObject()) throw new IllegalArgumentException();
              events.accept(node);
              emitted = true;
            } else if (line.startsWith("data:")) {
              if (!data.isEmpty()) data.append('\n');
              data.append(line.substring(5).stripLeading());
            }
          }
        } finally {
          control.detach();
        }
        if (!done) {
          failure(circuit);
          throw ApiException.upstream("MODEL_INVALID_RESPONSE", "模型流式响应未正常结束");
        }
        success(circuit);
        return;
      } catch (ApiException e) {
        throw e;
      } catch (HttpTimeoutException e) {
        if (!emitted && attempt < config.getSelection().getMaxRetries()) {
          pause(attempt);
          continue;
        }
        failure(circuit);
        throw ApiException.upstream("MODEL_TIMEOUT", "模型请求超时，请稍后重试");
      } catch (IOException e) {
        if (control.cancelled()) throw ApiException.cancelled();
        if (!emitted && attempt < config.getSelection().getMaxRetries()) {
          pause(attempt);
          continue;
        }
        failure(circuit);
        throw ApiException.upstream("MODEL_UNAVAILABLE", "无法连接模型服务");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        if (control.cancelled()) throw ApiException.cancelled();
        throw ApiException.upstream("REQUEST_INTERRUPTED", "请求已中断");
      } catch (RuntimeException e) {
        if (e instanceof ApiException api) throw api;
        failure(circuit);
        throw ApiException.upstream("MODEL_INVALID_RESPONSE", "模型服务返回了无效流式数据");
      }
    }
  }

  public static final class StreamControl implements Closeable {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile InputStream stream;

    public boolean cancelled() {
      return cancelled.get();
    }

    private void attach(InputStream value) throws IOException {
      if (cancelled()) {
        value.close();
        throw ApiException.cancelled();
      }
      stream = value;
    }

    private void detach() {
      stream = null;
    }

    @Override
    public void close() {
      cancelled.set(true);
      InputStream current = stream;
      if (current != null) {
        try {
          current.close();
        } catch (IOException ignored) {
          // Closing is best effort; the generation thread also observes the cancellation flag.
        }
      }
    }
  }

  private void success(Circuit circuit) {
    circuit.success();
  }

  private void failure(Circuit circuit) {
    circuit.failure(
        config.getSelection().getFailureThreshold(), config.getSelection().getOpenDurationMs());
  }

  private void pause(int attempt) {
    try {
      Thread.sleep(250L << attempt);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.upstream("REQUEST_INTERRUPTED", "请求已中断");
    }
  }

  private static final class Circuit {
    private int failures;
    private long openUntil;

    private synchronized boolean isOpen() {
      return openUntil > System.currentTimeMillis();
    }

    private synchronized void success() {
      failures = 0;
      openUntil = 0;
    }

    private synchronized void failure(int threshold, long openDurationMs) {
      if (++failures >= threshold) {
        failures = 0;
        openUntil = System.currentTimeMillis() + openDurationMs;
      }
    }
  }
}
