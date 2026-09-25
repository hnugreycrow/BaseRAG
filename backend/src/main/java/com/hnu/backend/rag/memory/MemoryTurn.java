package com.hnu.backend.rag.memory;

import java.util.Objects;

/**
 * 可注入模型上下文的一轮历史对话。
 *
 * @param turnIndex 会话内轮次序号，从 1 开始
 * @param userContent 用户消息正文
 * @param assistantContent 当前生效的助手回答正文
 */
public record MemoryTurn(int turnIndex, String userContent, String assistantContent) {
  /** 校验并创建一轮会话记忆。 */
  public MemoryTurn {
    if (turnIndex < 1) {
      throw new IllegalArgumentException("turnIndex must be positive");
    }
    userContent = Objects.requireNonNull(userContent, "userContent");
    assistantContent = Objects.requireNonNull(assistantContent, "assistantContent");
  }
}
