package com.hnu.backend.conversation.entity;

/** 会话消息的生成状态。 */
public enum MessageStatus {
  /** 回答已创建，等待生成。 */
  PENDING,
  /** 回答正在生成。 */
  STREAMING,
  /** 消息已完成。 */
  COMPLETED,
  /** 回答生成失败。 */
  FAILED,
  /** 回答已取消。 */
  CANCELLED
}
