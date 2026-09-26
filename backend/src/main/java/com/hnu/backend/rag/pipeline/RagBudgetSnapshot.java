package com.hnu.backend.rag.pipeline;

import com.hnu.backend.rag.config.RagProperties;

/**
 * 单次执行使用的不可变检索预算，避免运行期间配置对象变化导致同一请求前后行为不一致。
 *
 * @param maxSubQuestions 本次允许调度的最大子问题数，同时也是并发任务上限
 * @param defaultTopK 当前没有重排器时最终进入回答上下文的候选数
 * @param recallBudget 每个子问题在整个向量通道中最多保留的召回数
 * @param channelTimeoutMs 单个子问题向量通道的数据库召回预算，不包含 Embedding 耗时
 * @param deduplicationOverlapThreshold 相邻分块被视为近似重复的字符三元组重叠率阈值
 * @param rerankEnabled 本次执行是否调用专用重排模型
 * @param rerankInputLimit 去重后最多提交给重排模型的候选数
 * @param selectedEvidenceLimit 最终允许进入回答上下文的证据数
 * @param rrfK RRF 平滑常数，数值越小越强调头部名次
 * @param vectorWeight 向量通道参与 RRF 融合时的权重
 * @param vectorEnabled 是否启用向量检索通道
 */
public record RagBudgetSnapshot(
    int maxSubQuestions,
    int defaultTopK,
    int recallBudget,
    int channelTimeoutMs,
    double deduplicationOverlapThreshold,
    boolean rerankEnabled,
    int rerankInputLimit,
    int selectedEvidenceLimit,
    int rrfK,
    double vectorWeight,
    boolean vectorEnabled) {

  /**
   * 从当前配置冻结一份完整流水线预算。
   *
   * @param properties RAG 配置
   * @return 本次执行不可变的预算快照
   */
  public static RagBudgetSnapshot from(RagProperties properties) {
    RagProperties.Search search = properties.getSearch();
    return new RagBudgetSnapshot(
        properties.getPipeline().getMaxSubQuestions(),
        search.getDefaultTopK(),
        search.effectiveRecallBudget(),
        search.getChannels().getTimeoutMs(),
        properties.getPipeline().getDeduplication().getOverlapThreshold(),
        properties.getPipeline().getRerank().isEnabled(),
        properties.getPipeline().getRerank().getMaxInputCandidates(),
        properties.getPipeline().getRerank().getSelectedEvidence(),
        search.getFusion().getRrfK(),
        search.getFusion().getChannelWeights().getVector(),
        search.getChannels().getVector().isEnabled());
  }

  /**
   * 为单个子问题派生检索阶段预算。
   *
   * @param subQuestionId 子问题稳定 ID
   * @return 只包含该子问题检索所需参数的预算
   */
  public StageBudget forSubQuestion(String subQuestionId) {
    return new StageBudget(
        subQuestionId, recallBudget, channelTimeoutMs, rrfK, vectorWeight, vectorEnabled);
  }
}
