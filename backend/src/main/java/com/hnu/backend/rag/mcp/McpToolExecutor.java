package com.hnu.backend.rag.mcp;

import com.hnu.backend.configuration.RagProperties;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 对 MCP 只读工具执行授权复核、超时限制、敏感字段掩码和输出截断。 */
@Component
public class McpToolExecutor {
  private static final Logger log = LoggerFactory.getLogger(McpToolExecutor.class);
  private static final Set<String> DEFAULT_SENSITIVE_FIELDS =
      Set.of("authorization", "password", "secret", "token", "apikey", "api_key");

  private final McpToolRegistry registry;
  private final RagProperties config;
  private final JsonMapper json = JsonMapper.builder().build();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public McpToolExecutor(McpToolRegistry registry, RagProperties config) {
    this.registry = registry;
    this.config = config;
  }

  /** 执行经过路由产生的调用；任何拒绝结果都保证不会触达外部网关。 */
  public ToolObservation execute(McpToolCall call) {
    long startedAt = System.nanoTime();
    McpToolRegistry.RoutingCheck check = registry.check(call.toolName(), call.arguments());
    if (check != McpToolRegistry.RoutingCheck.ALLOWED) {
      return observation(
          call.toolName(), ToolObservation.Status.REJECTED, check.name(), "", false, startedAt);
    }
    McpToolRegistry.RegisteredTool registered = registry.resolve(call).orElseThrow();
    Future<Object> future = executor.submit(() -> registered.gateway().invoke(call));
    try {
      Object raw = future.get(config.getPipeline().getMcp().getTimeoutMs(), TimeUnit.MILLISECONDS);
      String safeOutput = serializeRedacted(raw, registered.definition().sensitiveFields());
      int limit = config.getPipeline().getMcp().getMaxOutputChars();
      boolean truncated = safeOutput.length() > limit;
      String content = truncated ? safeSubstring(safeOutput, limit) : safeOutput;
      ToolObservation observation =
          observation(
              call.toolName(),
              ToolObservation.Status.SUCCESS,
              "TOOL_COMPLETED",
              content,
              truncated,
              startedAt);
      log.info(
          "mcp tool completed tool={} status={} truncated={} elapsedMs={}",
          call.toolName(),
          observation.status(),
          observation.truncated(),
          observation.elapsedMs());
      return observation;
    } catch (TimeoutException e) {
      future.cancel(true);
      return observation(
          call.toolName(), ToolObservation.Status.TIMEOUT, "TOOL_TIMEOUT", "", false, startedAt);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      return observation(
          call.toolName(), ToolObservation.Status.FAILED, "TOOL_INTERRUPTED", "", false, startedAt);
    } catch (ExecutionException | RuntimeException e) {
      return observation(
          call.toolName(), ToolObservation.Status.FAILED, "TOOL_FAILED", "", false, startedAt);
    }
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  private String serializeRedacted(Object value, Set<String> toolSensitiveFields) {
    Object jsonValue = json.convertValue(value, Object.class);
    Set<String> sensitive = new java.util.HashSet<>(DEFAULT_SENSITIVE_FIELDS);
    toolSensitiveFields.stream().map(name -> name.toLowerCase(Locale.ROOT)).forEach(sensitive::add);
    return json.writeValueAsString(redact(jsonValue, sensitive));
  }

  private Object redact(Object value, Set<String> sensitiveFields) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach(
          (key, nested) -> {
            String name = String.valueOf(key);
            copy.put(
                name,
                sensitiveFields.contains(name.toLowerCase(Locale.ROOT))
                    ? "[REDACTED]"
                    : redact(nested, sensitiveFields));
          });
      return copy;
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      list.forEach(item -> copy.add(redact(item, sensitiveFields)));
      return copy;
    }
    return value;
  }

  private String safeSubstring(String value, int limit) {
    int end = limit;
    if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
    return value.substring(0, end);
  }

  private ToolObservation observation(
      String toolName,
      ToolObservation.Status status,
      String reasonCode,
      String content,
      boolean truncated,
      long startedAt) {
    return new ToolObservation(
        toolName, status, reasonCode, content, truncated, elapsedMillis(startedAt));
  }

  private long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }
}
