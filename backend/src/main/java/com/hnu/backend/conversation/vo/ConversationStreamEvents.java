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

  /**
   * 生成开始时关联会话、轮次和回答版本的事件。
   *
   * @param schemaVersion SSE 载荷协议版本
   * @param conversationId 所属会话标识
   * @param userMessageId 当前轮次的用户消息标识
   * @param assistantMessageId 当前回答版本标识
   * @param generationId 生成任务标识；当前协议与回答版本标识相同
   * @param turnIndex 从 1 开始的轮次
   * @param variantIndex 当前轮次的回答版本序号
   */
  public record Started(
      int schemaVersion,
      UUID conversationId,
      UUID userMessageId,
      UUID assistantMessageId,
      UUID generationId,
      int turnIndex,
      int variantIndex) {}

  /**
   * 正文或思考内容的增量事件；具体通道由 SSE 事件名区分。
   *
   * @param schemaVersion SSE 载荷协议版本
   * @param text 本次追加的文本片段
   */
  public record Text(int schemaVersion, String text) {}

  /**
   * 通知客户端清空已发送内容，以接收随后发送的完整新内容。
   *
   * @param schemaVersion SSE 载荷协议版本
   * @param reason 重置原因码
   */
  public record Reset(int schemaVersion, String reason) {}

  /**
   * 从数据库终态快照构造的事件；没有错误时省略错误字段。
   *
   * @param schemaVersion SSE 载荷协议版本
   * @param assistantMessage 已持久化的回答版本
   * @param requestId 用于定位服务端请求日志的标识
   * @param retryable 当前终态是否允许重试
   * @param code 失败错误码，成功时为空
   * @param message 失败提示，成功时为空
   */
  public record PersistedTerminal(
      int schemaVersion,
      ConversationResponses.AssistantMessage assistantMessage,
      String requestId,
      boolean retryable,
      @JsonInclude(JsonInclude.Include.NON_NULL) String code,
      @JsonInclude(JsonInclude.Include.NON_NULL) String message) {}

  /**
   * 无法读取终态快照时发送的错误事件。
   *
   * @param schemaVersion SSE 载荷协议版本
   * @param code 稳定业务错误码
   * @param message 可展示的错误提示
   * @param requestId 用于定位服务端请求日志的标识
   * @param retryable 本次失败是否允许重试
   */
  public record FailureTerminal(
      int schemaVersion, String code, String message, String requestId, boolean retryable) {}
}
