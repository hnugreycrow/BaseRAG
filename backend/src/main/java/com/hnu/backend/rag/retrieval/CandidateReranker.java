package com.hnu.backend.rag.retrieval;

import java.util.List;
import java.util.UUID;

/** RAG 核心使用的候选重排端口，不暴露供应商请求或响应结构。 */
public interface CandidateReranker {
  /**
   * 为候选证据请求重排分数；无模型可用时由实现报告降级或抛出错误。
   *
   * @param standaloneQuestion 不依赖会话上下文的独立问题
   * @param candidates 待重排的候选证据，返回分数通过候选 ID 对应
   * @return 分数及实际模型信息，或确定性降级标记
   */
  Output rerank(String standaloneQuestion, List<EvidenceCandidate> candidates);

  /**
   * 一次重排调用的领域输出。
   *
   * @param scores 候选 ID 与模型相关性分数的对应关系
   * @param modelId 本地模型候选 ID
   * @param provider 模型供应商标识
   * @param model 实际模型名称
   * @param requestId 供应商请求 ID，未提供时为空
   * @param totalTokens 供应商报告的输入 Token 数
   * @param noop 是否应改用确定性融合排序
   */
  record Output(
      List<Score> scores,
      String modelId,
      String provider,
      String model,
      String requestId,
      long totalTokens,
      boolean noop) {
    /** 固定模型分数快照，避免后续排序受到调用方列表修改影响。 */
    public Output {
      scores = List.copyOf(scores);
    }
  }

  /**
   * 一个候选的模型分数。
   *
   * @param candidateId 流水线内稳定候选 ID
   * @param relevanceScore 单次模型请求内的相关性分数
   */
  record Score(UUID candidateId, double relevanceScore) {}
}
