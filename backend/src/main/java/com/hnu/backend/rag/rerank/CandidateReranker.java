package com.hnu.backend.rag.rerank;

import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import java.util.List;
import java.util.UUID;

/** RAG 核心使用的候选重排端口，不暴露供应商请求或响应结构。 */
public interface CandidateReranker {
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
