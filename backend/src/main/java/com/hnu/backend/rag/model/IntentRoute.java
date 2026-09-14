package com.hnu.backend.rag.model;

import com.hnu.backend.rag.support.JsonValues;
import java.util.Map;
import java.util.Objects;

/** 单个子问题经模型分类和服务端安全策略归一化后的路由结果。 */
public record IntentRoute(
    String subQuestionId,
    IntentType intent,
    double confidence,
    String toolHint,
    Map<String, Object> toolArguments,
    RoutingReasonCode reasonCode) {
  public IntentRoute {
    subQuestionId = Objects.requireNonNull(subQuestionId, "subQuestionId");
    intent = Objects.requireNonNull(intent, "intent");
    toolArguments = JsonValues.immutableObject(toolArguments == null ? Map.of() : toolArguments);
    reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    if (subQuestionId.isBlank()
        || !Double.isFinite(confidence)
        || confidence < 0
        || confidence > 1) {
      throw new IllegalArgumentException("Invalid intent route identity or confidence");
    }
  }

  /** 创建不携带工具信息的知识检索降级路由。 */
  public static IntentRoute knowledgeFallback(
      String subQuestionId, double confidence, RoutingReasonCode reasonCode) {
    return new IntentRoute(
        subQuestionId, IntentType.KNOWLEDGE_RETRIEVAL, confidence, null, Map.of(), reasonCode);
  }
}
