package com.hnu.backend.observability;

/** 单次会话问答 Trace 的生命周期状态。 */
public enum RagRunStatus {
  /** 回答仍在执行。 */
  RUNNING,
  /** 回答成功持久化。 */
  COMPLETED,
  /** 回答生成或持久化失败。 */
  FAILED,
  /** 回答被用户、断连或账号操作取消。 */
  CANCELLED,
  /** 应用重启中断了回答。 */
  INTERRUPTED
}
