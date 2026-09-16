package com.hnu.backend.conversation.entity;

/** 会话消息的发送方。 */
public enum MessageRole {
  /** 用户提出的问题。 */
  USER,
  /** 助手生成的回答。 */
  ASSISTANT
}
