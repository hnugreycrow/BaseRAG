package com.hnu.backend.observability;

/** 单个问答阶段的终态。 */
public enum RagStageStatus {
  SUCCESS,
  DEGRADED,
  FAILED,
  CANCELLED,
  SKIPPED
}
