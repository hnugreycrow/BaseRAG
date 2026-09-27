package com.hnu.backend.rag.retrieval;

import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import com.hnu.backend.rag.pipeline.CancellationToken;
import com.hnu.backend.rag.pipeline.StageBudget;
import java.util.List;
import java.util.UUID;

/** 在授权知识库范围和检索预算内召回证据候选。 */
public interface EvidenceRetriever {
  /**
   * 使用各知识库当前绑定的模型分别生成查询向量，再按模型内名次融合结果。
   *
   * @param ownerId 提问者标识；知识库范围由 SQL 中的管理员创建者条件限定
   * @param question 已规范化的检索问题
   * @return 按 RRF 融合分排列且不超过最终 Top K 的候选分块
   */
  List<SearchHit> retrieve(UUID ownerId, String question);

  /**
   * 在指定知识库集合中检索；未指定集合时保持全库检索行为。
   *
   * @param ownerId 所属用户标识
   * @param question 已规范化的检索问题
   * @param knowledgeBaseIds 允许检索的知识库；null 表示全部知识库
   * @return 按 RRF 融合分排列且不超过最终 Top K 的候选分块
   */
  List<SearchHit> retrieve(UUID ownerId, String question, List<UUID> knowledgeBaseIds);

  /**
   * 为一个子问题执行完整向量通道召回，并保留各 Embedding 模型内的名次归因。
   *
   * <p>每个模型最多查询 recallBudget 条，随后先按 RRF 合并并再次截断到整个向量通道的 recallBudget， 因此模型数量增加不会线性放大该子问题进入全局融合的候选数。
   *
   * @param ownerId 提问者标识；外部知识库 ID 仍会与公共范围取交集
   * @param subQuestionId 子问题标识
   * @param question 子问题正文
   * @param knowledgeBaseIds 可选知识库范围；{@code null} 表示全部公共知识库
   * @param budget 当前子问题的检索预算
   * @param cancellationToken 异步取消信号
   * @return 可供全局合并的证据候选
   */
  List<EvidenceCandidate> retrieveCandidates(
      UUID ownerId,
      String subQuestionId,
      String question,
      List<UUID> knowledgeBaseIds,
      StageBudget budget,
      CancellationToken cancellationToken);

  /**
   * 为一个子问题召回候选，并分别记录每个模型绑定的向量化与数据库查询。
   *
   * @param ownerId 所属用户标识
   * @param subQuestionId 子问题标识
   * @param question 子问题正文，仅用于模型调用，不进入 Trace
   * @param knowledgeBaseIds 可选知识库范围
   * @param budget 当前检索预算
   * @param cancellationToken 取消信号
   * @param trace 当前问答 Trace
   * @return 可供全局合并的证据候选
   */
  List<EvidenceCandidate> retrieveCandidates(
      UUID ownerId,
      String subQuestionId,
      String question,
      List<UUID> knowledgeBaseIds,
      StageBudget budget,
      CancellationToken cancellationToken,
      TraceContext trace);

  /** 绑定库与其余公共库分配 75%/25% 召回预算，每个模型只生成一次查询向量。 */
  List<EvidenceCandidate> retrieveDirectedCandidates(
      UUID ownerId,
      String subQuestionId,
      String question,
      List<UUID> primaryKnowledgeBaseIds,
      StageBudget budget,
      CancellationToken cancellationToken,
      TraceContext trace);

  /** 兼容根 Trace 入口；内部显式传递父节点上下文。 */
  List<EvidenceCandidate> retrieveCandidates(
      UUID ownerId,
      String subQuestionId,
      String question,
      List<UUID> knowledgeBaseIds,
      StageBudget budget,
      CancellationToken cancellationToken,
      RagRunTrace trace);

  /** 兼容根 Trace 入口；内部显式传递父节点上下文。 */
  List<EvidenceCandidate> retrieveDirectedCandidates(
      UUID ownerId,
      String subQuestionId,
      String question,
      List<UUID> primaryKnowledgeBaseIds,
      StageBudget budget,
      CancellationToken cancellationToken,
      RagRunTrace trace);
}
