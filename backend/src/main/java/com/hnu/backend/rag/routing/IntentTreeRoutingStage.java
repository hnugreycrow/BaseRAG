package com.hnu.backend.rag.routing;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.intent.IntentNode;
import com.hnu.backend.intent.IntentTreeSnapshot;
import com.hnu.backend.intent.IntentTreeSnapshotProvider;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.observability.RagDecisionLog;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.prompt.IntentTreeRoutingPrompts;
import com.hnu.backend.shared.error.ApiException;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

/** 一次模型调用对所有子问题和启用叶子分类，按子问题隔离可恢复的分类失败。 */
@Component
public class IntentTreeRoutingStage {
  private static final Logger log = LoggerFactory.getLogger(IntentTreeRoutingStage.class);
  private final IntentTreeSnapshotProvider snapshots;
  private final ChatClient chat;
  private final McpToolRegistry tools;
  private final RagProperties config;
  private final JsonMapper json = JsonMapper.builder().build();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public IntentTreeRoutingStage(
      IntentTreeSnapshotProvider snapshots,
      ChatClient chat,
      McpToolRegistry tools,
      RagProperties config) {
    this.snapshots = snapshots;
    this.chat = chat;
    this.tools = tools;
    this.config = config;
  }

  /** 读取树快照并分类；单个子问题无效时只将该子问题降级到全公共库检索。 */
  public RoutingPlan execute(QueryPlan plan, RagRunTrace trace) {
    RagRunTrace.Span span =
        trace.start(RagStageName.INTENT_ROUTING, null, plan.subQuestions().size());
    if (Thread.currentThread().isInterrupted()) {
      span.cancelled("GENERATION_CANCELLED");
      throw ApiException.cancelled();
    }
    try {
      IntentTreeSnapshot snapshot = snapshots.snapshot();
      List<IntentNode> leaves = snapshot.activeLeaves();
      if (leaves.isEmpty()) {
        String reason =
            snapshot.nodes().isEmpty() ? "INTENT_TREE_EMPTY" : "INTENT_TREE_NO_VALID_LEAVES";
        return knowledgeFallback(plan, span, trace, reason);
      }
      if (leaves.size() > 32) {
        return knowledgeFallback(plan, span, trace, "INTENT_TREE_NO_VALID_LEAVES");
      }
      Map<UUID, IntentNode> byId = snapshot.activeLeavesById();
      Map<UUID, String> paths = snapshot.paths();
      Map<String, Object> input = new LinkedHashMap<>();
      input.put("standaloneQuestion", plan.standaloneQuestion());
      input.put("subQuestions", plan.subQuestions());
      input.put("leaves", snapshot.promptLeaves());
      Future<ChatClient.Generation> future =
          executor.submit(
              () ->
                  chat.generate(IntentTreeRoutingPrompts.system(), json.writeValueAsString(input)));
      ChatClient.Generation generation;
      try {
        generation =
            future.get(config.getPipeline().getRouting().getTimeoutMs(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException error) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        span.cancelled("GENERATION_CANCELLED");
        throw ApiException.cancelled();
      } catch (TimeoutException error) {
        future.cancel(true);
        return knowledgeFallback(plan, span, trace, "INTENT_TREE_TIMEOUT");
      } catch (ExecutionException error) {
        future.cancel(true);
        if (isCancelled(error.getCause())) {
          span.cancelled("GENERATION_CANCELLED");
          throw ApiException.cancelled();
        }
        return knowledgeFallback(plan, span, trace, "INTENT_TREE_CLASSIFICATION_FAILED");
      }
      span.model(generation.id(), generation.provider(), generation.model());
      ParsedRouting parsed;
      try {
        parsed = parse(plan, byId, generation.content());
      } catch (RuntimeException error) {
        if (isCancelled(error)) {
          span.cancelled("GENERATION_CANCELLED");
          throw ApiException.cancelled();
        }
        return knowledgeFallback(plan, span, trace, "INTENT_TREE_INVALID_OUTPUT");
      }
      if (Thread.currentThread().isInterrupted()) {
        span.cancelled("GENERATION_CANCELLED");
        throw ApiException.cancelled();
      }
      RoutingPlan routed = parsed.plan();
      if (parsed.fallbacks().isEmpty()) {
        span.success(routed.routes().size());
      } else {
        span.degraded(routed.routes().size(), parsed.traceReason());
      }
      RagDecisionLog.emit(
          () ->
              log.info(
                  "intent routing completed runId={} fallbackReasons={} routes={}",
                  trace.runId(),
                  parsed.fallbacks(),
                  routeSummary(routed, paths)));
      return routed;
    } catch (ApiException error) {
      if ("GENERATION_CANCELLED".equals(error.code())) {
        span.cancelled(error.code());
        throw error;
      }
      return knowledgeFallback(plan, span, trace, "INTENT_TREE_CLASSIFICATION_FAILED");
    } catch (RuntimeException error) {
      if (Thread.currentThread().isInterrupted()) {
        span.cancelled("GENERATION_CANCELLED");
        throw ApiException.cancelled();
      }
      return knowledgeFallback(plan, span, trace, "INTENT_TREE_CLASSIFICATION_FAILED");
    }
  }

  private RoutingPlan knowledgeFallback(
      QueryPlan plan, RagRunTrace.Span span, RagRunTrace trace, String reason) {
    if (Thread.currentThread().isInterrupted()) {
      span.cancelled("GENERATION_CANCELLED");
      throw ApiException.cancelled();
    }
    RoutingPlan routed =
        new RoutingPlan(
            plan.subQuestions().stream()
                .map(
                    question ->
                        IntentRoute.knowledgeFallback(
                            question.id(), 0, RoutingReasonCode.INTENT_TREE_FALLBACK))
                .toList());
    span.degraded(routed.routes().size(), reason);
    RagDecisionLog.emit(
        () ->
            log.warn(
                "intent routing fallback runId={} reason={} routes={}",
                trace.runId(),
                reason,
                routeSummary(routed, Map.of())));
    return routed;
  }

  private String routeSummary(RoutingPlan plan, Map<UUID, String> paths) {
    return json.writeValueAsString(
        plan.routes().stream()
            .map(
                route -> {
                  Map<String, Object> result = new LinkedHashMap<>();
                  result.put("subQuestionId", route.subQuestionId());
                  result.put("intent", route.intent());
                  result.put("intentNodeId", route.intentNodeId());
                  result.put(
                      "intentPath",
                      route.intentNodeId() == null ? null : paths.get(route.intentNodeId()));
                  result.put("confidence", route.confidence());
                  result.put("reasonCode", route.reasonCode());
                  return result;
                })
            .toList());
  }

  private boolean isCancelled(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof InterruptedException
          || cause instanceof ApiException api && "GENERATION_CANCELLED".equals(api.code())) {
        return true;
      }
    }
    return false;
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  private ParsedRouting parse(QueryPlan plan, Map<UUID, IntentNode> nodes, String content) {
    JsonNode root = json.readTree(content);
    JsonNode routes = validateEnvelope(plan, root);
    List<IntentRoute> resolved = new ArrayList<>();
    List<RouteFallback> fallbacks = new ArrayList<>();
    for (int index = 0; index < routes.size(); index++) {
      String subQuestionId = plan.subQuestions().get(index).id();
      try {
        resolved.add(parseRoute(subQuestionId, nodes, routes.get(index)));
      } catch (LowConfidenceException error) {
        addRouteFallback(resolved, fallbacks, subQuestionId, "INTENT_TREE_LOW_CONFIDENCE");
      } catch (ToolValidationException error) {
        addRouteFallback(resolved, fallbacks, subQuestionId, error.reason());
      } catch (RuntimeException error) {
        if (isCancelled(error)) {
          throw error;
        }
        addRouteFallback(resolved, fallbacks, subQuestionId, "INTENT_TREE_INVALID_OUTPUT");
      }
    }
    return new ParsedRouting(new RoutingPlan(resolved), List.copyOf(fallbacks));
  }

  private JsonNode validateEnvelope(QueryPlan plan, JsonNode root) {
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("Invalid intent tree response");
    }
    JsonNode routes = root.path("routes");
    if (!routes.isArray() || routes.size() != plan.subQuestions().size()) {
      throw new IllegalArgumentException("Invalid intent tree response");
    }
    for (int index = 0; index < routes.size(); index++) {
      JsonNode item = routes.get(index);
      String expectedId = plan.subQuestions().get(index).id();
      if (!item.isObject() || !expectedId.equals(item.path("subQuestionId").asString())) {
        throw new IllegalArgumentException("Invalid sub-question ID");
      }
    }
    return routes;
  }

  private IntentRoute parseRoute(String subQuestionId, Map<UUID, IntentNode> nodes, JsonNode item) {
    ModelReasonCode reason = ModelReasonCode.parse(item.path("reasonCode").asString());
    JsonNode candidates = item.path("candidates");
    if (!candidates.isArray() || candidates.isEmpty() || candidates.size() > 2) {
      throw new IllegalArgumentException("Invalid intent candidates");
    }
    List<ScoredNode> ranked = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    for (JsonNode candidate : candidates) {
      UUID nodeId = UUID.fromString(candidate.path("nodeId").asString());
      double score = candidate.path("score").asDouble(Double.NaN);
      if (!nodes.containsKey(nodeId)
          || !seen.add(nodeId)
          || !Double.isFinite(score)
          || score < 0
          || score > 1) {
        throw new IllegalArgumentException("Invalid candidate");
      }
      ranked.add(new ScoredNode(nodes.get(nodeId), score));
    }
    if (ranked.size() == 2 && ranked.get(0).score() < ranked.get(1).score()) {
      throw new IllegalArgumentException("Candidates are not ranked");
    }
    ScoredNode best = ranked.getFirst();
    if (best.score() < config.getPipeline().getRouting().getConfidenceThreshold()) {
      throw new LowConfidenceException();
    }
    IntentNode node = best.node();
    ScoredNode second = ranked.size() == 2 ? ranked.get(1) : null;
    RoutingReasonCode code =
        reason == ModelReasonCode.AMBIGUOUS
            ? RoutingReasonCode.AMBIGUOUS
            : switch (node.kind()) {
              case KB -> RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED;
              case MCP -> RoutingReasonCode.EXTERNAL_SOURCE_REQUIRED;
              case SYSTEM -> RoutingReasonCode.GENERAL_CHAT;
            };
    Map<String, Object> arguments = Map.of();
    if (node.kind() == IntentNode.Kind.MCP) {
      JsonNode values = item.path("toolArguments");
      if (!values.isObject()) {
        throw new IllegalArgumentException("Invalid tool arguments");
      }
      arguments = json.convertValue(values, Map.class);
      McpToolRegistry.RoutingCheck check = tools.check(node.toolName(), arguments);
      if (check != McpToolRegistry.RoutingCheck.ALLOWED) {
        throw new ToolValidationException(toolFallbackReason(check));
      }
    }
    return new IntentRoute(
        subQuestionId,
        switch (node.kind()) {
          case KB -> IntentType.KNOWLEDGE_RETRIEVAL;
          case MCP -> IntentType.MCP_TOOL;
          case SYSTEM -> IntentType.SYSTEM_CHAT;
        },
        best.score(),
        node.toolName(),
        arguments,
        code,
        node.id(),
        second == null ? null : second.node().id(),
        second == null ? null : second.score(),
        node.kind() == IntentNode.Kind.KB ? node.knowledgeBaseIds() : null);
  }

  private void addRouteFallback(
      List<IntentRoute> routes,
      List<RouteFallback> fallbacks,
      String subQuestionId,
      String reason) {
    routes.add(
        IntentRoute.knowledgeFallback(subQuestionId, 0, RoutingReasonCode.INTENT_TREE_FALLBACK));
    fallbacks.add(new RouteFallback(subQuestionId, reason));
  }

  private String toolFallbackReason(McpToolRegistry.RoutingCheck check) {
    return switch (check) {
      case MCP_DISABLED -> "INTENT_TREE_MCP_DISABLED";
      case TOOL_NOT_ALLOWED -> "INTENT_TREE_TOOL_NOT_ALLOWED";
      case TOOL_NOT_READ_ONLY -> "INTENT_TREE_TOOL_NOT_READ_ONLY";
      case INVALID_ARGUMENTS -> "INTENT_TREE_INVALID_TOOL_ARGUMENTS";
      case ALLOWED -> throw new IllegalArgumentException("Allowed tool cannot be a fallback");
    };
  }

  private static final class LowConfidenceException extends RuntimeException {}

  private static final class ToolValidationException extends RuntimeException {
    private final String reason;

    private ToolValidationException(String reason) {
      this.reason = reason;
    }

    private String reason() {
      return reason;
    }
  }

  private record RouteFallback(String subQuestionId, String reason) {}

  private record ParsedRouting(RoutingPlan plan, List<RouteFallback> fallbacks) {
    private String traceReason() {
      Set<String> reasons = new LinkedHashSet<>();
      for (RouteFallback fallback : fallbacks) {
        reasons.add(fallback.reason());
      }
      return reasons.size() == 1 ? reasons.iterator().next() : "INTENT_TREE_PARTIAL_FALLBACK";
    }
  }

  /** 分类模型输出的原因码，与最终路由决策的 {@link RoutingReasonCode} 区分。 */
  private enum ModelReasonCode {
    /** 模型确定了匹配意图。 */
    MATCHED,
    /** 模型认为存在多个候选意图。 */
    AMBIGUOUS;

    static ModelReasonCode parse(String value) {
      try {
        return valueOf(value);
      } catch (IllegalArgumentException error) {
        throw new IllegalArgumentException("Invalid reason code", error);
      }
    }
  }

  private record ScoredNode(IntentNode node, double score) {}
}
