package com.hnu.backend.rag.pipeline;

import com.hnu.backend.rag.mcp.ToolObservation;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import java.util.List;
import java.util.Objects;

/**
 * 单个子问题的隔离执行结果。
 *
 * @param subQuestionId 对应问题规划中的子问题 ID
 * @param intent 实际执行的主路由类型
 * @param status 执行终态；单个 FAILED 或 TIMEOUT 不会中断其他子问题
 * @param candidates 该知识型子问题在通道预算内保留的候选
 * @param toolObservation MCP 子问题的安全观察结果，非工具路由时为空
 * @param reasonCode 稳定结果码，用于日志和后续回答阶段解释部分失败
 * @param elapsedMs 本子问题从任务开始到终态的耗时
 */
public record SubQuestionExecution(
    String subQuestionId,
    IntentType intent,
    Status status,
    List<EvidenceCandidate> candidates,
    ToolObservation toolObservation,
    String reasonCode,
    long elapsedMs) {
  public SubQuestionExecution {
    subQuestionId = Objects.requireNonNull(subQuestionId, "subQuestionId");
    intent = Objects.requireNonNull(intent, "intent");
    status = Objects.requireNonNull(status, "status");
    candidates = List.copyOf(candidates);
    reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    if (subQuestionId.isBlank() || reasonCode.isBlank() || elapsedMs < 0) {
      throw new IllegalArgumentException("Invalid sub-question execution result");
    }
  }

  public enum Status {
    SUCCESS,
    EMPTY,
    SKIPPED,
    TIMEOUT,
    FAILED
  }
}
