package com.hnu.backend.rag.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次问答的不可变输入，不携带持久化实体。
 *
 * @param ownerId 调用者标识
 * @param question 原始问题
 * @param conversationId 会话标识；兼容单轮入口为空
 * @param turn 当前轮次；兼容单轮入口为零
 * @param knowledgeBaseIds 限定知识库；null 表示原有全范围语义
 * @param thinkingEnabled 是否输出思考内容
 * @param mode 原有完整或单轮兼容流程
 */
public record RagRequest(
    UUID ownerId,
    String question,
    UUID conversationId,
    int turn,
    List<UUID> knowledgeBaseIds,
    boolean thinkingEnabled,
    Mode mode) {
  /** 固定集合快照并校验执行模式。 */
  public RagRequest {
    Objects.requireNonNull(mode, "mode");
    knowledgeBaseIds = knowledgeBaseIds == null ? null : List.copyOf(knowledgeBaseIds);
    if (mode == Mode.CONVERSATION && (conversationId == null || turn < 1)) {
      throw new IllegalArgumentException("Conversation execution requires conversation and turn");
    }
  }

  /** 选择保持兼容的执行顺序。 */
  public enum Mode {
    /** 会话完整链路。 */
    CONVERSATION,
    /** 旧单轮检索与回答链路。 */
    LEGACY
  }
}
