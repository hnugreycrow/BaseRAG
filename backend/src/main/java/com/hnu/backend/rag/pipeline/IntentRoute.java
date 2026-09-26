package com.hnu.backend.rag.pipeline;

import com.hnu.backend.rag.JsonValues;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 单个子问题经模型分类和服务端安全策略归一化后的路由结果。 */
public record IntentRoute(
    String subQuestionId,
    IntentType intent,
    double confidence,
    String toolHint,
    Map<String, Object> toolArguments,
    RoutingReasonCode reasonCode,
    UUID intentNodeId,
    UUID secondCandidateId,
    Double secondCandidateScore,
    List<UUID> knowledgeBaseIds) {
  public IntentRoute {
    subQuestionId = Objects.requireNonNull(subQuestionId, "subQuestionId");
    intent = Objects.requireNonNull(intent, "intent");
    toolArguments = JsonValues.immutableObject(toolArguments == null ? Map.of() : toolArguments);
    reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    knowledgeBaseIds = knowledgeBaseIds == null ? null : List.copyOf(knowledgeBaseIds);
    if (subQuestionId.isBlank()
        || !Double.isFinite(confidence)
        || confidence < 0
        || confidence > 1) {
      throw new IllegalArgumentException("Invalid intent route identity or confidence");
    }
  }

  /** 保留历史路由记录和简化调用使用的六字段构造方式。 */
  public IntentRoute(
      String subQuestionId,
      IntentType intent,
      double confidence,
      String toolHint,
      Map<String, Object> toolArguments,
      RoutingReasonCode reasonCode) {
    this(
        subQuestionId,
        intent,
        confidence,
        toolHint,
        toolArguments,
        reasonCode,
        null,
        null,
        null,
        null);
  }

  /** 创建不携带工具信息的知识检索降级路由。 */
  public static IntentRoute knowledgeFallback(
      String subQuestionId, double confidence, RoutingReasonCode reasonCode) {
    return new IntentRoute(
        subQuestionId, IntentType.KNOWLEDGE_RETRIEVAL, confidence, null, Map.of(), reasonCode);
  }
}
