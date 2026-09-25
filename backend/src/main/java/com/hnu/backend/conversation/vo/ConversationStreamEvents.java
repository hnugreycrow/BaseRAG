package com.hnu.backend.conversation.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** 会话流的版本化事件载荷；字段名与现有 SSE 协议保持一致。 */
public final class ConversationStreamEvents {
  /** 当前流协议版本。 */
  public static final int SCHEMA_VERSION = 1;

  private ConversationStreamEvents() {}

  /** 构造开始事件，回答 ID 同时作为生成 ID。 */
  public static Started started(
      UUID conversationId,
      UUID userMessageId,
      UUID assistantMessageId,
      int turnIndex,
      int variantIndex) {
    return new Started(
        SCHEMA_VERSION,
        conversationId,
        userMessageId,
        assistantMessageId,
        assistantMessageId,
        turnIndex,
        variantIndex);
  }

  /** 构造正文或思考内容增量。 */
  public static Text text(String value) {
    return new Text(SCHEMA_VERSION, value);
  }

  /** 构造内容重置事件。 */
  public static Reset reset(String reason) {
    return new Reset(SCHEMA_VERSION, reason);
  }

  /** SSE 事件名。 */
  public enum Kind {
    STARTED("started"),
    DELTA("delta"),
    REASONING_DELTA("reasoning_delta"),
    RESET("reset"),
    COMPLETE("complete"),
    CANCELLED("cancelled"),
    ERROR("error");

    private final String wireName;

    Kind(String wireName) {
      this.wireName = wireName;
    }

    /** 返回 SSE 帧中的事件名。 */
    public String wireName() {
      return wireName;
    }
  }

  /** 生成开始时关联会话、轮次和回答版本的事件。 */
  public record Started(
      int schemaVersion,
      UUID conversationId,
      UUID userMessageId,
      UUID assistantMessageId,
      UUID generationId,
      int turnIndex,
      int variantIndex) {}

  /** 正文或思考内容的增量事件。 */
  public record Text(int schemaVersion, String text) {}

  /** 清空已发送内容的事件。 */
  public record Reset(int schemaVersion, String reason) {}

  /** 从数据库终态快照构造的事件；没有错误时省略错误字段。 */
  public record PersistedTerminal(
      int schemaVersion,
      ConversationResponses.AssistantMessage assistantMessage,
      String requestId,
      boolean retryable,
      @JsonInclude(JsonInclude.Include.NON_NULL) String code,
      @JsonInclude(JsonInclude.Include.NON_NULL) String message) {}

  /** 无法读取终态快照时发送的错误事件。 */
  public record FailureTerminal(
      int schemaVersion, String code, String message, String requestId, boolean retryable) {}
}
