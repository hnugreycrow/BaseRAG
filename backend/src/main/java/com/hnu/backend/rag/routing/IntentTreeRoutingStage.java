package com.hnu.backend.rag.routing;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.intent.IntentNode;
import com.hnu.backend.intent.IntentTreeService;
import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.observability.RagDecisionLog;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.mcp.McpToolDefinition;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.prompt.IntentTreeRoutingPrompts;
import com.hnu.backend.shared.error.ApiException;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

/** 一次模型调用对所有子问题和启用叶子分类，失败时直接检索全部公共知识库。 */
@Component
public class IntentTreeRoutingStage {
  private static final Logger log = LoggerFactory.getLogger(IntentTreeRoutingStage.class);
  private final IntentTreeService tree;
  private final ChatClient chat;
  private final McpToolRegistry tools;
  private final RagProperties config;
  private final JsonMapper json = JsonMapper.builder().build();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public IntentTreeRoutingStage(
      IntentTreeService tree, ChatClient chat, McpToolRegistry tools, RagProperties config) {
    this.tree = tree;
    this.chat = chat;
    this.tools = tools;
    this.config = config;
  }

  /** 读取树快照并分类；可恢复失败直接为全部子问题建立全公共库检索路由。 */
  public RoutingPlan execute(QueryPlan plan, RagRunTrace trace) {
    RagRunTrace.Span span =
        trace.start(RagStageName.INTENT_ROUTING, null, plan.subQuestions().size());
    if (Thread.currentThread().isInterrupted()) {
      span.cancelled("GENERATION_CANCELLED");
      throw ApiException.cancelled();
    }
    try {
      List<IntentNode> leaves = tree.activeLeaves();
      if (leaves.isEmpty()) {
        String reason = tree.list().isEmpty() ? "INTENT_TREE_EMPTY" : "INTENT_TREE_NO_VALID_LEAVES";
        return knowledgeFallback(plan, span, trace, reason);
      }
      if (leaves.size() > 32) {
        return knowledgeFallback(plan, span, trace, "INTENT_TREE_NO_VALID_LEAVES");
      }
      Map<UUID, IntentNode> byId = new HashMap<>();
      leaves.forEach(node -> byId.put(node.id(), node));
      Map<UUID, String> paths = paths(tree.list());
      List<McpToolDefinition> availableTools = tools.availableReadOnlyTools();
      Map<String, Object> input = new LinkedHashMap<>();
      input.put("standaloneQuestion", plan.standaloneQuestion());
      input.put("subQuestions", plan.subQuestions());
      input.put(
          "leaves",
          leaves.stream()
              .map(node -> leafPrompt(node, paths.get(node.id()), availableTools))
              .toList());
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
      RoutingPlan routed;
      try {
        routed = parse(plan, byId, generation.content());
      } catch (LowConfidenceException error) {
        return knowledgeFallback(plan, span, trace, "INTENT_TREE_LOW_CONFIDENCE");
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
      span.success(routed.routes().size());
      RagDecisionLog.emit(
          () ->
              log.info(
                  "intent routing completed runId={} routes={}",
                  trace.runId(),
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
          || cause instanceof ApiException api && "GENERATION_CANCELLED".equals(api.code()))
        return true;
    }
    return false;
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  private RoutingPlan parse(QueryPlan plan, Map<UUID, IntentNode> nodes, String content) {
    JsonNode root = json.readTree(content);
    JsonNode routes = root.path("routes");
    if (!routes.isArray() || routes.size() != plan.subQuestions().size()) {
      throw new IllegalArgumentException("Invalid intent tree response");
    }
    List<IntentRoute> resolved = new ArrayList<>();
    for (int index = 0; index < routes.size(); index++) {
      JsonNode item = routes.get(index);
      String expectedId = plan.subQuestions().get(index).id();
      if (!expectedId.equals(item.path("subQuestionId").asString())) {
        throw new IllegalArgumentException("Invalid sub-question ID");
      }
      String reason = item.path("reasonCode").asString();
      if (!reason.equals("MATCHED") && !reason.equals("AMBIGUOUS")) {
        throw new IllegalArgumentException("Invalid reason code");
      }
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
            || score > 1) throw new IllegalArgumentException("Invalid candidate");
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
          reason.equals("AMBIGUOUS")
              ? RoutingReasonCode.AMBIGUOUS
              : switch (node.kind()) {
                case KB -> RoutingReasonCode.KNOWLEDGE_SOURCE_REQUIRED;
                case MCP -> RoutingReasonCode.EXTERNAL_SOURCE_REQUIRED;
                case SYSTEM -> RoutingReasonCode.GENERAL_CHAT;
              };
      Map<String, Object> arguments = Map.of();
      if (node.kind() == IntentNode.Kind.MCP) {
        JsonNode values = item.path("toolArguments");
        if (!values.isObject()) throw new IllegalArgumentException("Invalid tool arguments");
        arguments = json.convertValue(values, Map.class);
        if (tools.check(node.toolName(), arguments) != McpToolRegistry.RoutingCheck.ALLOWED) {
          throw new IllegalArgumentException("Tool is not executable");
        }
      }
      resolved.add(
          new IntentRoute(
              expectedId,
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
              node.kind() == IntentNode.Kind.KB ? node.knowledgeBaseIds() : null));
    }
    return new RoutingPlan(resolved);
  }

  private Map<String, Object> leafPrompt(
      IntentNode node, String path, List<McpToolDefinition> availableTools) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", node.id());
    value.put("path", path);
    value.put("type", node.kind());
    value.put("description", node.description());
    value.put("examples", node.examples());
    if (node.kind() == IntentNode.Kind.MCP) {
      availableTools.stream()
          .filter(tool -> tool.name().equals(node.toolName()))
          .findFirst()
          .ifPresent(
              tool -> {
                value.put("toolName", tool.name());
                value.put("toolDescription", tool.description());
                value.put("inputSchema", tool.inputSchema());
              });
    }
    return value;
  }

  private Map<UUID, String> paths(List<IntentNode> nodes) {
    Map<UUID, IntentNode> byId = new HashMap<>();
    nodes.forEach(node -> byId.put(node.id(), node));
    Map<UUID, String> result = new HashMap<>();
    for (IntentNode node : nodes) {
      List<String> names = new ArrayList<>();
      IntentNode current = node;
      while (current != null) {
        names.add(current.name());
        current = current.parentId() == null ? null : byId.get(current.parentId());
      }
      java.util.Collections.reverse(names);
      result.put(node.id(), String.join(" > ", names));
    }
    return result;
  }

  private static final class LowConfidenceException extends RuntimeException {}

  private record ScoredNode(IntentNode node, double score) {}
}
