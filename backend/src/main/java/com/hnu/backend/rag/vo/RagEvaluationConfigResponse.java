package com.hnu.backend.rag.vo;

/**
 * 本地评测使用的非敏感 RAG 配置快照。
 *
 * @param chunkSize 目标分块字符数
 * @param chunkMinSize 最小分块字符数
 * @param chunkMaxSize 最大分块字符数
 * @param chunkOverlap 相邻分块重叠字符数
 * @param topK 当前最终进入回答上下文的候选数，字段名为兼容既有评测脚本而保留
 * @param recallBudget 每个子问题在整个向量通道保留的召回数
 * @param channelTimeoutMs 单个向量检索子问题的超时时间
 * @param rrfK RRF 融合平滑常数
 * @param rerankCandidateLimit 融合后为重排保留的候选上限
 * @param vectorEnabled 向量通道是否启用
 * @param maxQuestionChars 用户问题最大字符数
 * @param maxSubQuestions 最大子问题数
 * @param routingConfidenceThreshold 意图路由置信度阈值
 * @param routingTimeoutMs 意图路由超时时间
 * @param mcpEnabled MCP 工具路由总开关
 */
public record RagEvaluationConfigResponse(
    int chunkSize,
    int chunkMinSize,
    int chunkMaxSize,
    int chunkOverlap,
    int topK,
    int recallBudget,
    int channelTimeoutMs,
    int rrfK,
    int rerankCandidateLimit,
    boolean vectorEnabled,
    int maxQuestionChars,
    int maxSubQuestions,
    double routingConfidenceThreshold,
    int routingTimeoutMs,
    boolean mcpEnabled) {}
