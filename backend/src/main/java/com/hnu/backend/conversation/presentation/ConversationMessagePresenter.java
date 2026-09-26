package com.hnu.backend.conversation.presentation;

import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.vo.ConversationResponses;
import com.hnu.backend.rag.vo.ModelInfoResponse;
import com.hnu.backend.rag.vo.SourceResponse;
import com.hnu.backend.rag.vo.SourceSnapshotDecoder;
import com.hnu.backend.shared.json.JsonCodecs;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** 将持久化回答快照映射为对外响应。 */
public final class ConversationMessagePresenter {
  private final JsonMapper json = JsonCodecs.snapshots();

  /** 创建供会话查询与生成结果共用的无状态响应映射器。 */
  public ConversationMessagePresenter() {}

  /**
   * 将消息实体中的 JSON 快照转换为前端回答结构。
   *
   * @param message 回答消息实体
   * @return 前端回答版本
   */
  public ConversationResponses.AssistantMessage assistantResponse(Message message) {
    List<SourceResponse> sources = new SourceSnapshotDecoder().decode(message.getSourcesJson());
    List<String> citations = readArray(message.getCitationsJson(), String[].class);
    ModelInfoResponse modelInfo =
        message.getModelInfoJson() == null || message.getModelInfoJson().isBlank()
            ? null
            : json.readValue(message.getModelInfoJson(), ModelInfoResponse.class);
    return new ConversationResponses.AssistantMessage(
        message.getId(),
        message.getReplyToId(),
        message.getTurnIndex(),
        message.getVariantIndex(),
        message.isActive(),
        message.getStatus().name(),
        message.getContent(),
        message.isThinkingEnabled(),
        message.getReasoningContent(),
        message.getRetrievalQuery(),
        sources,
        citations,
        modelInfo,
        message.getErrorCode(),
        message.getErrorMessage(),
        message.getCreatedAt(),
        message.getUpdatedAt(),
        message.getCompletedAt());
  }

  /**
   * 将可空 JSON 数组字段读取为不可变列表。
   *
   * @param encoded JSON 数组文本
   * @param type 数组运行时类型
   * @param <T> 数组元素类型
   * @return 空列表或反序列化后的不可变列表
   */
  private <T> List<T> readArray(String encoded, Class<T[]> type) {
    if (encoded == null || encoded.isBlank()) {
      return List.of();
    }
    T[] values = json.readValue(encoded, type);
    return values == null ? List.of() : List.of(values);
  }
}
