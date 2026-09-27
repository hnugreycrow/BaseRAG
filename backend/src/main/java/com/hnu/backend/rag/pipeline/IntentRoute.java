package com.hnu.backend.rag.pipeline;

import com.hnu.backend.rag.JsonValues;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 单个子问题经模型分类和服务端安全策略归一化后的路由结果。
 *
 * @param subQuestionId 与查询计划中的子问题一一对应的标识
 * @param intent 安全策略确认后的最终意图
 * @param confidence 模型置信度，范围为 0 到 1
 * @param toolHint 工具路由建议；非工具路由可为空
 * @param toolArguments 工具参数快照，空值会归一化为空映射
 * @param reasonCode 最终路由原因码
 * @param intentNodeId 命中的意图节点；降级路由可为空
 * @param secondCandidateId 次优意图节点；没有时为空
 * @param secondCandidateScore 次优模型分数；没有时为空
 * @param knowledgeBaseIds 允许检索的知识库范围；空值表示不限制该范围
 */
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
  /** 固定模型参数与知识库范围，并确保置信度位于闭区间 0 到 1。 */
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
