package com.hnu.backend.model.http;

import com.hnu.backend.model.config.AiProperties;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 模型 HTTP 传输、有限重试和按候选隔离的熔断入口。 */
@Component
public class ModelHttpClient {
  private static final Logger log = LoggerFactory.getLogger(ModelHttpClient.class);
  private final ScheduledThreadPoolExecutor streamTimers =
      new ScheduledThreadPoolExecutor(
          2, Thread.ofPlatform().daemon().name("model-stream-timeout-", 0).factory());
  private final AiProperties config;
  private final HttpClient client;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();

  /** 根据模型配置创建共享 HTTP 客户端与流式超时调度器。 */
  public ModelHttpClient(AiProperties config) {
    streamTimers.setRemoveOnCancelPolicy(true);
    this.config = config;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** 校验候选的连接信息与凭据，缺失时抛出配置错误。 */
  public void requireConfigured(AiProperties.ModelTarget target) {
    if (target.baseUrl().isBlank()
        || target.endpoint().isBlank()
        || target.model().isBlank()
        || target.apiKey() == null
        || target.apiKey().isBlank()) {
      throw ApiException.bad(
          ErrorCode.MODEL_NOT_CONFIGURED, "请配置 " + target.provider() + " 的 API Key");
    }
  }

  /** 发送非流式请求；超时直接失败，其他可重试传输错误遵循配置。 */
  public JsonNode post(AiProperties.ModelTarget target, Map<String, Object> payload) {
    requireConfigured(target);
    Circuit circuit = circuits.computeIfAbsent(target.id(), ignored -> new Circuit());
    if (circuit.isOpen()) {
      throw ApiException.upstream(ErrorCode.MODEL_CIRCUIT_OPEN, "模型服务暂时不可用，请稍后重试");
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
            throw ApiException.upstream(ErrorCode.MODEL_INVALID_RESPONSE, "模型服务返回了无效 JSON", e);
          }
        }
        boolean retryable = status == 429 || status >= 500;
        if (retryable && attempt < config.getSelection().getMaxRetries()) {
          pause(attempt);
          continue;
        }
        failure(circuit);
        throw ApiException.upstream(
            status == 429 ? ErrorCode.MODEL_RATE_LIMITED : ErrorCode.MODEL_HTTP_ERROR,
            status == 429 ? "模型服务请求频繁，请稍后重试" : "模型服务请求失败，请检查服务配置");
      } catch (HttpTimeoutException e) {
        failure(circuit);
        throw ApiException.upstream(ErrorCode.MODEL_TIMEOUT, "模型请求超时，请稍后重试", e);
      } catch (IOException e) {
        if (Thread.currentThread().isInterrupted()) {
          throw ApiException.upstream(ErrorCode.REQUEST_INTERRUPTED, "请求已中断", e);
        }
        if (attempt < config.getSelection().getMaxRetries()) {
          pause(attempt);
          continue;
        }
        failure(circuit);
        throw ApiException.upstream(ErrorCode.MODEL_UNAVAILABLE, "无法连接模型服务", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw ApiException.upstream(ErrorCode.REQUEST_INTERRUPTED, "请求已中断", e);
      }
    }
  }

  /** 关闭共享调度器及 HTTP 客户端，终止应用退出时仍在执行的请求。 */
  @PreDestroy
  public void close() {
    streamTimers.shutdownNow();
    client.shutdownNow();
  }

  /** 为直接调用 HTTP 层的流建立独立总预算；正文及返回的思考文本均视为有效内容。 */
  public void stream(
      AiProperties.ModelTarget target,
      Map<String, Object> payload,
      Consumer<JsonNode> events,
      StreamControl control) {
    stream(
        target,
        payload,
        events,
        control,
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getStream().getTotalTimeoutMs()),
        event -> {
          JsonNode delta = event.path("choices").path(0).path("delta");
          return nonEmpty(delta.path("content")) || nonEmpty(delta.path("reasoning_content"));
        });
  }

  /**
   * 在调用方共享的总预算内消费一个候选，首内容预算跨该候选的 HTTP 重试保留。
   *
   * @param target 当前模型候选
   * @param payload 供应商请求体
   * @param events 同步事件接收器；其异常不记为供应商协议故障
   * @param control 整次生成的用户取消控制器
   * @param totalDeadline 使用 {@link System#nanoTime()} 的绝对截止时间
   * @param isContent 判断事件是否包含实际向用户转发的有效内容
   */
  public void stream(
      AiProperties.ModelTarget target,
      Map<String, Object> payload,
      Consumer<JsonNode> events,
      StreamControl control,
      long totalDeadline,
      Predicate<JsonNode> isContent) {
    if (control.cancelled()) {
      throw ApiException.cancelled();
    }
    requireConfigured(target);
    Circuit circuit = circuits.computeIfAbsent(target.id(), ignored -> new Circuit());
    if (circuit.isOpen()) {
      throw ApiException.upstream(ErrorCode.MODEL_CIRCUIT_OPEN, "模型服务暂时不可用，请稍后重试");
    }
    long started = System.nanoTime();
    try (StreamAttempt scope =
        new StreamAttempt(
            streamTimers,
            totalDeadline,
            config.getStream().getFirstContentTimeoutMs(),
            config.getStream().getIdleTimeoutMs())) {
      control.attach(scope);
      try {
        for (int attempt = 0; ; attempt++) {
          boolean emitted = false;
          scope.check();
          try {
            HttpRequest request =
                HttpRequest.newBuilder(URI.create(target.baseUrl() + target.endpoint()))
                    .timeout(
                        Duration.ofNanos(
                            Math.max(
                                1,
                                Math.min(
                                    TimeUnit.MILLISECONDS.toNanos(target.timeoutMs()),
                                    scope.remainingNanos()))))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + target.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            CompletableFuture<HttpResponse<InputStream>> pending =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            scope.attach(pending);
            HttpResponse<InputStream> response = awaitResponse(pending, scope);
            scope.attach(response.body());
            scope.check();
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
              scope.releaseNetwork();
              boolean retryable = status == 429 || status >= 500;
              if (retryable && attempt < config.getSelection().getMaxRetries()) {
                scope.pause(attempt);
                continue;
              }
              scope.fail();
              failure(circuit);
              throw ApiException.upstream(
                  status == 429 ? ErrorCode.MODEL_RATE_LIMITED : ErrorCode.MODEL_HTTP_ERROR,
                  status == 429 ? "模型服务请求频繁，请稍后重试" : "模型服务请求失败，请检查服务配置");
            }
            boolean done = false;
            try (var reader =
                new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
              StringBuilder data = new StringBuilder();
              String line;
              while ((line = reader.readLine()) != null) {
                scope.check();
                if (line.isEmpty()) {
                  if (data.isEmpty()) {
                    continue;
                  }
                  String value = data.toString();
                  data.setLength(0);
                  if ("[DONE]".equals(value)) {
                    done = true;
                    break;
                  }
                  JsonNode node;
                  try {
                    node = json.readTree(value);
                    if (node == null || !node.isObject()) {
                      throw new IllegalArgumentException();
                    }
                  } catch (RuntimeException error) {
                    scope.fail();
                    failure(circuit);
                    throw ApiException.upstream(
                        ErrorCode.MODEL_INVALID_RESPONSE, "模型服务返回了无效流式数据", error);
                  }
                  scope.deliver(isContent.test(node), () -> events.accept(node));
                  emitted = true;
                } else if (line.startsWith("data:")) {
                  if (!data.isEmpty()) {
                    data.append('\n');
                  }
                  data.append(line.substring(5).stripLeading());
                }
              }
            }
            scope.check();
            if (!done) {
              scope.fail();
              failure(circuit);
              throw ApiException.upstream(ErrorCode.MODEL_INVALID_RESPONSE, "模型流式响应未正常结束");
            }
            scope.complete();
            success(circuit);
            return;
          } catch (HttpTimeoutException error) {
            throw error;
          } catch (IOException error) {
            scope.check();
            if (!emitted && attempt < config.getSelection().getMaxRetries()) {
              scope.releaseNetwork();
              scope.pause(attempt);
              continue;
            }
            scope.fail();
            failure(circuit);
            throw ApiException.upstream(ErrorCode.MODEL_UNAVAILABLE, "无法连接模型服务", error);
          } finally {
            scope.releaseNetwork();
          }
        }
      } catch (HttpTimeoutException error) {
        scope.timeout();
        failure(circuit);
        log.warn(
            "model stream timeout modelId={} phase={} elapsedMs={}",
            target.id(),
            scope.timeoutPhase(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        throw ApiException.upstream(ErrorCode.MODEL_TIMEOUT, "模型响应超时，请稍后重试", error);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        if (control.cancelled()) {
          throw ApiException.cancelled();
        }
        throw ApiException.upstream(ErrorCode.REQUEST_INTERRUPTED, "请求已中断", error);
      } finally {
        control.detach(scope);
      }
    }
  }

  /** 还原异步 HTTP 异常，关闭请求导致的异常优先按候选超时或用户取消解释。 */
  private HttpResponse<InputStream> awaitResponse(
      CompletableFuture<HttpResponse<InputStream>> pending, StreamAttempt scope)
      throws IOException, InterruptedException {
    try {
      return pending.get();
    } catch (ExecutionException error) {
      scope.check();
      if (error.getCause() instanceof IOException io) {
        throw io;
      }
      throw new IOException("Model HTTP request failed", error.getCause());
    } catch (CancellationException error) {
      scope.check();
      throw new IOException("Model HTTP request cancelled", error);
    }
  }

  private static boolean nonEmpty(JsonNode node) {
    return node.isString() && !node.asString().isEmpty();
  }

  /** 用户取消覆盖所有候选；候选超时不会将整个控制器标记为取消。 */
  public static final class StreamControl implements Closeable {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private StreamAttempt active;

    /** 返回本次生成是否已被用户取消。 */
    public boolean cancelled() {
      return cancelled.get();
    }

    private synchronized void attach(StreamAttempt value) {
      if (cancelled()) {
        value.cancel();
        throw ApiException.cancelled();
      }
      active = value;
    }

    private synchronized void detach(StreamAttempt value) {
      if (active == value) {
        active = null;
      }
    }

    @Override
    public void close() {
      StreamAttempt current;
      synchronized (this) {
        cancelled.set(true);
        current = active;
      }
      if (current != null) {
        current.cancel();
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
      throw ApiException.upstream(ErrorCode.REQUEST_INTERRUPTED, "请求已中断", e);
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
