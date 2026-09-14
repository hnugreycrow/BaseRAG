package com.hnu.backend.rag.execution;

import com.hnu.backend.configuration.RagProperties;

/**
 * 单次执行使用的不可变检索预算，避免运行期间配置对象变化导致同一请求前后行为不一致。
 *
 * @param maxSubQuestions 本次允许调度的最大子问题数，同时也是并发任务上限
 * @param defaultTopK 当前没有重排器时最终进入回答上下文的候选数
 * @param recallBudget 每个子问题在整个向量通道中最多保留的召回数
 * @param channelTimeoutMs 单个向量检索子问题的超时时间
 * @param rerankCandidateLimit 融合后为后续重排保留的候选上限，小于等于零表示不截断
 * @param rrfK RRF 平滑常数，数值越小越强调头部名次
 * @param vectorWeight 向量通道参与 RRF 融合时的权重
 * @param vectorEnabled 是否启用向量检索通道
 */
public record RagBudgetSnapshot(
    int maxSubQuestions,
    int defaultTopK,
    int recallBudget,
    int channelTimeoutMs,
    int rerankCandidateLimit,
    int rrfK,
    double vectorWeight,
    boolean vectorEnabled) {

  public static RagBudgetSnapshot from(RagProperties properties) {
    RagProperties.Search search = properties.getSearch();
    return new RagBudgetSnapshot(
        properties.getPipeline().getMaxSubQuestions(),
        search.getDefaultTopK(),
        search.effectiveRecallBudget(),
        search.getChannels().getTimeoutMs(),
        search.getFusion().getRerankCandidateLimit(),
        search.getFusion().getRrfK(),
        search.getFusion().getChannelWeights().getVector(),
        search.getChannels().getVector().isEnabled());
  }

  public StageBudget forSubQuestion(String subQuestionId) {
    return new StageBudget(
        subQuestionId, recallBudget, channelTimeoutMs, rrfK, vectorWeight, vectorEnabled);
  }
}
