package com.hnu.backend.rag.routing;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.shared.error.ApiException;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 对问题规划执行结构化意图识别，并应用服务端安全路由策略。 */
@Component
public class IntentRoutingStage {
  private static final Logger log = LoggerFactory.getLogger(IntentRoutingStage.class);

  private final IntentClassifier classifier;
  private final McpToolRegistry tools;
  private final RagProperties config;
  private final JsonMapper json = JsonMapper.builder().build();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * 创建意图路由阶段。
   *
   * @param classifier 意图分类模型端口
   * @param tools MCP 工具注册表
   * @param config RAG 路由配置
   */
  public IntentRoutingStage(
      IntentClassifier classifier, McpToolRegistry tools, RagProperties config) {
    this.classifier = classifier;
    this.tools = tools;
    this.config = config;
  }

  /**
   * 对每个子问题生成唯一有效路由，异常时整体降级到知识检索。
   *
   * @param plan 查询计划
   * @return 与子问题顺序对齐的路由计划
   */
  public RoutingPlan execute(QueryPlan plan) {
    return execute(plan, RagRunTrace.noop());
  }

  /**
   * 生成安全路由并记录模型、耗时和服务端归一化降级。
   *
   * @param plan 查询计划
   * @param trace 当前问答 Trace
   * @return 与子问题对齐的路由计划
   */
  public RoutingPlan execute(QueryPlan plan, RagRunTrace trace) {
    long startedAt = System.nanoTime();
    RagRunTrace.Span span =
        trace.start(RagStageName.INTENT_ROUTING, null, plan.subQuestions().size());
    IntentClassifier.ClassificationOutput output = null;
    try {
      output = classifyWithinTimeout(plan);
      RoutingPlan routing = parseAndNormalize(plan, output.content());
      log.info(
          "intent routing completed routes={} intents={} provider={} model={} routingMs={}",
          routing.routes().size(),
          intentCounts(routing),
          output.provider(),
          output.model(),
          elapsedMillis(startedAt));
      span.model(output.modelId(), output.provider(), output.model());
      String degradedReason = normalizedFallbackReason(routing);
      if (degradedReason == null) span.success(routing.routes().size());
      else span.degraded(routing.routes().size(), degradedReason);
      return routing;
    } catch (RoutingValidationException e) {
      if (output != null) span.model(output.modelId(), output.provider(), output.model());
      span.degraded(plan.subQuestions().size(), e.reason.name());
      log.warn(
          "intent routing degraded reason={} modelId={} routingMs={}",
          e.reason,
          output == null ? null : output.modelId(),
          elapsedMillis(startedAt));
    } catch (RuntimeException e) {
      DegradedReason reason = failureReason(e);
      span.degraded(plan.subQuestions().size(), reason.name());
      log.warn(
          "intent routing degraded reason={} exceptionType={} routingMs={}",
          reason,
          e.getClass().getSimpleName(),
          elapsedMillis(startedAt));
    }
    return fallback(plan, RoutingReasonCode.CLASSIFIER_DEGRADED);
  }

  /** 返回模型成功后由服务端安全策略触发的首个降级原因。 */
  private String normalizedFallbackReason(RoutingPlan routing) {
    return routing.routes().stream()
        .map(IntentRoute::reasonCode)
        .filter(
            reason ->
                reason == RoutingReasonCode.LOW_CONFIDENCE_FALLBACK
                    || reason == RoutingReasonCode.MCP_DISABLED_FALLBACK
                    || reason == RoutingReasonCode.TOOL_NOT_ALLOWED_FALLBACK
                    || reason == RoutingReasonCode.TOOL_NOT_READ_ONLY_FALLBACK
                    || reason == RoutingReasonCode.INVALID_TOOL_ARGUMENTS_FALLBACK)
        .map(Enum::name)
        .findFirst()
        .orElse(null);
  }

  /** 停止路由超时控制使用的虚拟线程执行器。 */
  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  private IntentClassifier.ClassificationOutput classifyWithinTimeout(QueryPlan plan) {
    Future<IntentClassifier.ClassificationOutput> future =
        executor.submit(() -> classifier.classify(plan, tools.availableReadOnlyTools()));
    try {
      return future.get(config.getPipeline().getRouting().getTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw invalid(DegradedReason.MODEL_TIMEOUT);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw ApiException.upstream("REQUEST_INTERRUPTED", "请求已中断");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException(cause);
    }
  }

  private RoutingPlan parseAndNormalize(QueryPlan plan, String rawContent) {
    if (rawContent == null || rawContent.isBlank()) throw invalid(DegradedReason.EMPTY_OUTPUT);
    JsonNode root;
    try {
      root = json.readTree(stripFence(rawContent));
    } catch (RuntimeException e) {
      throw invalid(DegradedReason.INVALID_JSON);
    }
    if (root == null
        || !root.isObject()
        || root.size() != 1
        || !root.path("routes").isArray()
        || root.path("routes").size() != plan.subQuestions().size()) {
      throw invalid(DegradedReason.INVALID_SCHEMA);
    }

    List<IntentRoute> routes = new ArrayList<>();
    int index = 0;
    for (JsonNode node : root.path("routes")) {
      if (!node.isObject()
          || node.size() != 6
          || !node.path("subQuestionId").isTextual()
          || !node.path("intent").isTextual()
          || !node.path("confidence").isNumber()
          || !(node.path("toolHint").isNull() || node.path("toolHint").isTextual())
          || !node.path("toolArguments").isObject()
          || !node.path("reasonCode").isTextual()) {
        throw invalid(DegradedReason.INVALID_SCHEMA);
      }
      String expectedId = plan.subQuestions().get(index).id();
      if (!expectedId.equals(node.path("subQuestionId").asString())) {
        throw invalid(DegradedReason.INVALID_SUBQUESTION_ID);
      }
      IntentType intent = enumValue(IntentType.class, node.path("intent").asString());
      double confidence = node.path("confidence").asDouble();
      if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
        throw invalid(DegradedReason.INVALID_CONFIDENCE);
      }
      RoutingReasonCode reason =
          enumValue(RoutingReasonCode.class, node.path("reasonCode").asString());
      if (!modelReasonAllowed(reason) || !reasonMatchesIntent(reason, intent)) {
        throw invalid(DegradedReason.INVALID_REASON_CODE);
      }
      String toolHint =
          node.path("toolHint").isNull() ? null : node.path("toolHint").asString().strip();
      Map<String, Object> arguments = objectValue(node.path("toolArguments"));
      if (intent == IntentType.MCP_TOOL) {
        if (toolHint == null || toolHint.isEmpty()) {
          throw invalid(DegradedReason.INVALID_TOOL_HINT);
        }
      } else if (toolHint != null || !arguments.isEmpty()) {
        throw invalid(DegradedReason.INVALID_TOOL_HINT);
      }
      routes.add(normalize(expectedId, intent, confidence, toolHint, arguments, reason));
      index++;
    }
    return new RoutingPlan(routes);
  }

  private IntentRoute normalize(
      String id,
      IntentType intent,
      double confidence,
      String toolHint,
      Map<String, Object> arguments,
      RoutingReasonCode reason) {
    if (confidence < config.getPipeline().getRouting().getConfidenceThreshold()) {
      return IntentRoute.knowledgeFallback(
          id, confidence, RoutingReasonCode.LOW_CONFIDENCE_FALLBACK);
    }
    if (intent != IntentType.MCP_TOOL) {
      return new IntentRoute(id, intent, confidence, null, Map.of(), reason);
    }
    McpToolRegistry.RoutingCheck check = tools.check(toolHint, arguments);
    if (check == McpToolRegistry.RoutingCheck.ALLOWED) {
      return new IntentRoute(id, intent, confidence, toolHint, arguments, reason);
    }
    RoutingReasonCode fallbackReason =
        switch (check) {
          case MCP_DISABLED -> RoutingReasonCode.MCP_DISABLED_FALLBACK;
          case TOOL_NOT_ALLOWED -> RoutingReasonCode.TOOL_NOT_ALLOWED_FALLBACK;
          case TOOL_NOT_READ_ONLY -> RoutingReasonCode.TOOL_NOT_READ_ONLY_FALLBACK;
          case INVALID_ARGUMENTS -> RoutingReasonCode.INVALID_TOOL_ARGUMENTS_FALLBACK;
          case ALLOWED -> throw new IllegalStateException("Unexpected allowed MCP route");
        };
    return IntentRoute.knowledgeFallback(id, confidence, fallbackReason);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> objectValue(JsonNode node) {
    return json.convertValue(node, Map.class);
  }

  private boolean modelReasonAllowed(RoutingReasonCode reason) {
    return reason == RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED
        || reason == RoutingReasonCode.EXTERNAL_SOURCE_REQUIRED
        || reason == RoutingReasonCode.GENERAL_CHAT
        || reason == RoutingReasonCode.AMBIGUOUS;
  }

  private boolean reasonMatchesIntent(RoutingReasonCode reason, IntentType intent) {
    return reason == RoutingReasonCode.AMBIGUOUS
        || (intent == IntentType.KNOWLEDGE_RETRIEVAL
            && reason == RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED)
        || (intent == IntentType.MCP_TOOL && reason == RoutingReasonCode.EXTERNAL_SOURCE_REQUIRED)
        || (intent == IntentType.SYSTEM_CHAT && reason == RoutingReasonCode.GENERAL_CHAT);
  }

  private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw invalid(DegradedReason.INVALID_ENUM);
    }
  }

  private RoutingPlan fallback(QueryPlan plan, RoutingReasonCode reason) {
    return new RoutingPlan(
        plan.subQuestions().stream()
            .map(question -> IntentRoute.knowledgeFallback(question.id(), 0, reason))
            .toList());
  }

  private Map<IntentType, Long> intentCounts(RoutingPlan routing) {
    Map<IntentType, Long> counts = new EnumMap<>(IntentType.class);
    routing.routes().forEach(route -> counts.merge(route.intent(), 1L, Long::sum));
    return counts;
  }

  private String stripFence(String rawContent) {
    String content = rawContent.strip();
    if (!content.startsWith("```")) return content;
    int firstLine = content.indexOf('\n');
    if (firstLine < 0 || !content.endsWith("```")) {
      throw invalid(DegradedReason.INVALID_JSON);
    }
    String opener = content.substring(0, firstLine).strip();
    if (!("```".equals(opener) || "```json".equalsIgnoreCase(opener))) {
      throw invalid(DegradedReason.INVALID_JSON);
    }
    String body = content.substring(firstLine + 1, content.length() - 3).strip();
    if (body.isEmpty() || body.contains("```")) {
      throw invalid(DegradedReason.INVALID_JSON);
    }
    return body;
  }

  private DegradedReason failureReason(RuntimeException error) {
    if (error instanceof ApiException api && "MODEL_TIMEOUT".equals(api.code())) {
      return DegradedReason.MODEL_TIMEOUT;
    }
    if (error instanceof RoutingValidationException validation) return validation.reason;
    return DegradedReason.MODEL_ERROR;
  }

  private long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }

  private RoutingValidationException invalid(DegradedReason reason) {
    return new RoutingValidationException(reason);
  }

  private enum DegradedReason {
    MODEL_ERROR,
    MODEL_TIMEOUT,
    EMPTY_OUTPUT,
    INVALID_JSON,
    INVALID_SCHEMA,
    INVALID_SUBQUESTION_ID,
    INVALID_CONFIDENCE,
    INVALID_REASON_CODE,
    INVALID_TOOL_HINT,
    INVALID_ENUM
  }

  private static final class RoutingValidationException extends RuntimeException {
    private final DegradedReason reason;

    private RoutingValidationException(DegradedReason reason) {
      this.reason = reason;
    }
  }
}
