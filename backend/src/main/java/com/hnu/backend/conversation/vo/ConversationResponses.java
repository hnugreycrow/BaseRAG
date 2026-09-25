package com.hnu.backend.conversation.vo;

import com.hnu.backend.rag.vo.ModelInfoResponse;
import com.hnu.backend.rag.vo.SourceResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 会话相关响应结构的命名空间。 */
public final class ConversationResponses {
  /** 禁止实例化仅用于组织响应类型的工具类。 */
  private ConversationResponses() {}

  /**
   * 会话列表项。
   *
   * @param id 会话标识
   * @param title 会话标题
   * @param createdAt 创建时间
   * @param updatedAt 最近更新时间
   */
  public record Summary(
      UUID id,
      String title,
      boolean thinkingEnabled,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 包含全部对话轮次的会话详情。
   *
   * @param id 会话标识
   * @param title 会话标题
   * @param createdAt 创建时间
   * @param updatedAt 最近更新时间
   * @param turns 按轮次排列的消息
   */
  public record Detail(
      UUID id,
      String title,
      boolean thinkingEnabled,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      List<Turn> turns) {}

  /** 有界消息窗口，轮次游标不受新消息插入影响。 */
  public record TurnPage(
      Detail conversation, boolean hasOlder, boolean hasNewer, int latestTurnIndex) {}

  public record Question(UUID id, int turnIndex, String preview) {}

  public record QuestionPage(List<Question> items, boolean hasMore) {}

  /**
   * 一轮用户消息及其所有助手回答版本。
   *
   * @param user 用户消息
   * @param assistantVersions 助手回答的历史版本
   * @param activeAssistantId 当前生效的助手回答标识
   */
  public record Turn(
      UserMessage user, List<AssistantMessage> assistantVersions, UUID activeAssistantId) {}

  /**
   * 用户消息响应。
   *
   * @param id 消息标识
   * @param turnIndex 会话内轮次序号
   * @param content 消息正文
   * @param createdAt 创建时间
   */
  public record UserMessage(UUID id, int turnIndex, String content, OffsetDateTime createdAt) {}

  /**
   * 助手回答版本响应。
   *
   * @param id 消息标识
   * @param replyToId 对应的用户消息标识
   * @param turnIndex 会话内轮次序号
   * @param variantIndex 同一用户消息下的回答版本序号
   * @param active 是否为当前生效版本
   * @param status 生成状态
   * @param content 回答正文或已保存的部分正文
   * @param retrievalQuery 实际用于检索的问题
   * @param sources 检索来源
   * @param citations 回答实际使用的引用标识
   * @param modelInfo 实际使用的模型信息
   * @param errorCode 失败时的错误码
   * @param errorMessage 失败时的错误信息
   * @param createdAt 创建时间
   * @param updatedAt 最近更新时间
   * @param completedAt 生成进入终态的时间
   */
  public record AssistantMessage(
      UUID id,
      UUID replyToId,
      int turnIndex,
      int variantIndex,
      boolean active,
      String status,
      String content,
      boolean thinkingEnabled,
      String reasoningContent,
      String retrievalQuery,
      List<SourceResponse> sources,
      List<String> citations,
      ModelInfoResponse modelInfo,
      String errorCode,
      String errorMessage,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      OffsetDateTime completedAt) {}
}
