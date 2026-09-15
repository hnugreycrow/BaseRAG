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
 * @param channelTimeoutMs 单个子问题向量通道的数据库召回预算，不包含 Embedding 耗时
 * @param rrfK RRF 融合平滑常数
 * @param rerankCandidateLimit 去重后为专用重排模型保留的候选上限
 * @param vectorEnabled 向量通道是否启用
 * @param maxQuestionChars 用户问题最大字符数
 * @param maxSubQuestions 最大子问题数
 * @param routingConfidenceThreshold 意图路由置信度阈值
 * @param routingTimeoutMs 意图路由超时时间
 * @param mcpEnabled MCP 工具路由总开关
 * @param deduplicationOverlapThreshold 相邻分块近似去重的重叠率阈值
 * @param rerankEnabled 专用重排模型是否启用
 * @param selectedEvidence 最终进入回答上下文的证据数上限
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
    boolean mcpEnabled,
    double deduplicationOverlapThreshold,
    boolean rerankEnabled,
    int selectedEvidence) {}
