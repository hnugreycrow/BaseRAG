package com.hnu.backend.conversation.entity;

/** 单次模型生成尝试的状态。 */
public enum GenerationAttemptStatus {
  /** 模型正在生成。 */
  STREAMING,
  /** 模型生成完成。 */
  COMPLETED,
  /** 生成或校验失败。 */
  FAILED,
  /** 生成已取消。 */
  CANCELLED
}
