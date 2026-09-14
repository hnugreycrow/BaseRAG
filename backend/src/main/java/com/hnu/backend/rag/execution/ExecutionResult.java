package com.hnu.backend.rag.execution;

import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import java.util.List;

/**
 * 阶段四的完整输出。
 *
 * @param candidates 已合并且不超过重排候选上限的全局候选池
 * @param subQuestions 按问题规划顺序排列的每子问题执行记录
 * @param budget 本次执行实际冻结的检索预算快照
 */
public record ExecutionResult(
    List<EvidenceCandidate> candidates,
    List<SubQuestionExecution> subQuestions,
    RagBudgetSnapshot budget) {
  public ExecutionResult {
    candidates = List.copyOf(candidates);
    subQuestions = List.copyOf(subQuestions);
  }
}
