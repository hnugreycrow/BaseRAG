package com.hnu.backend.rag.vo;

/**
 * 当前进程的检索与回答配置，不包含存储、工具或模型凭据。
 *
 * @param recallBudget 每个子问题的实际向量召回预算，已解析零值简写
 * @param vectorEnabled 是否启用向量检索
 * @param channelTimeoutMs 数据库召回超时毫秒数，不含向量化耗时
 * @param fusionStrategy 融合策略标识
 * @param rrfK RRF 名次平滑常数
 * @param vectorWeight 向量通道融合权重
 * @param deduplicationOverlapThreshold 相邻分块字符三元组重叠率阈值
 * @param rerankEnabled 是否调用专用重排模型
 * @param rerankInputLimit 去重后提交给重排模型的最大候选数
 * @param selectedEvidenceLimit 最终回答证据数量上限，关闭专用重排时仍生效
 * @param maxSubQuestions 最大子问题数
 * @param planningRecentTurns 问题规划读取的最近完整轮次上限
 * @param routingConfidenceThreshold 意图路由置信度阈值
 * @param routingTimeoutMs 意图路由超时毫秒数
 * @param maxQuestionChars 问题长度上限，按 Java UTF-16 代码单元计数
 * @param recentTurns 回答上下文保留的最近完整轮数
 * @param summaryBatchTurns 摘要与原文重叠轮数及后续摘要更新批次上限
 * @param summaryMaxChars 话题摘要 Unicode 字符数上限
 */
public record RetrievalSettingsResponse(
    int recallBudget,
    boolean vectorEnabled,
    int channelTimeoutMs,
    String fusionStrategy,
    int rrfK,
    double vectorWeight,
    double deduplicationOverlapThreshold,
    boolean rerankEnabled,
    int rerankInputLimit,
    int selectedEvidenceLimit,
    int maxSubQuestions,
    int planningRecentTurns,
    double routingConfidenceThreshold,
    int routingTimeoutMs,
    int maxQuestionChars,
    int recentTurns,
    int summaryBatchTurns,
    int summaryMaxChars) {}
