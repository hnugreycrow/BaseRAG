package com.hnu.backend.rag.retrieval;

import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.trace.TraceContext;
import com.hnu.backend.rag.pipeline.CancellationToken;
import com.hnu.backend.rag.pipeline.ExecutionResult;
import com.hnu.backend.rag.pipeline.QueryPlan;
import java.util.List;

/** 选择并重排候选，输出保留评分语义和降级信息的结果。 */
public interface EvidenceReranker {
  /**
   * 重排候选；兼容调用不采集 Trace。
   *
   * @param plan 查询计划
   * @param execution 子问题执行结果
   * @param deduplicatedCandidates 去重候选
   * @param cancellationToken 取消信号
   * @return 重排或确定性降级结果
   */
  RerankResult execute(
      QueryPlan plan,
      ExecutionResult execution,
      List<EvidenceCandidate> deduplicatedCandidates,
      CancellationToken cancellationToken);

  /**
   * 重排证据候选并记录模型信息、候选数量和确定性降级。
   *
   * @param plan 查询计划
   * @param execution 子问题执行结果
   * @param deduplicatedCandidates 去重候选
   * @param cancellationToken 取消信号
   * @param trace 当前问答 Trace
   * @return 重排结果
   */
  RerankResult execute(
      QueryPlan plan,
      ExecutionResult execution,
      List<EvidenceCandidate> deduplicatedCandidates,
      CancellationToken cancellationToken,
      TraceContext trace);

  /** 兼容根 Trace 入口；内部显式传递父节点上下文。 */
  RerankResult execute(
      QueryPlan plan,
      ExecutionResult execution,
      List<EvidenceCandidate> deduplicatedCandidates,
      CancellationToken cancellationToken,
      RagRunTrace trace);
}
