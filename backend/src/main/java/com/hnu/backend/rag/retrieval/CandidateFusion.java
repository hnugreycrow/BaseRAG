package com.hnu.backend.rag.retrieval;

import java.util.List;

/** 按子问题顺序融合候选并执行稳定排序与截取。 */
public interface CandidateFusion {
  /**
   * 合并同一分块的候选归属，按融合分数和稳定次序选出结果。
   *
   * @param input 带稳定分块标识和来源分数的候选
   * @param orderedSubQuestionIds 子问题的原始顺序，用于稳定排序
   * @param limit 最多返回的候选数
   * @return 按原有融合规则排序的候选；空输入返回空列表
   */
  List<EvidenceCandidate> mergeAndSelect(
      List<EvidenceCandidate> input, List<String> orderedSubQuestionIds, int limit);
}
