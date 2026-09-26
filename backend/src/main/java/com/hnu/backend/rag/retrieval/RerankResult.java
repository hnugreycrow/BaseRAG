package com.hnu.backend.rag.retrieval;

import java.util.List;

/**
 * 阶段五重排及证据截取结果。
 *
 * @param selectedCandidates 按最终相关性顺序进入回答上下文的证据
 * @param decisions 所有重排输入候选的名次、分数和入选状态
 * @param status 模型重排是否成功、禁用、降级或没有输入
 * @param reasonCode 稳定状态码，供日志和后续 trace 使用
 * @param modelId 实际命中的本地模型候选 ID
 * @param provider 实际供应商或 noop
 * @param model 实际模型名称
 * @param requestId 供应商请求 ID，未提供时为空
 * @param totalTokens 供应商报告的输入 Token 数
 */
public record RerankResult(
    List<EvidenceCandidate> selectedCandidates,
    List<RerankDecision> decisions,
    Status status,
    String reasonCode,
    String modelId,
    String provider,
    String model,
    String requestId,
    long totalTokens) {
  public RerankResult {
    selectedCandidates = List.copyOf(selectedCandidates);
    decisions = List.copyOf(decisions);
  }

  /** 重排阶段终态，明确区分模型收益、确定性降级、配置关闭和空输入。 */
  public enum Status {
    SUCCESS,
    DEGRADED,
    DISABLED,
    EMPTY
  }
}
