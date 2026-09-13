package com.hnu.backend.conversation.vo;

import com.hnu.backend.rag.vo.ModelInfoResponse;
import com.hnu.backend.rag.vo.SourceResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ConversationResponses {
  private ConversationResponses() {}

  public record Summary(
      UUID id, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

  public record Detail(
      UUID id,
      String title,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      List<Turn> turns) {}

  public record Turn(
      UserMessage user, List<AssistantMessage> assistantVersions, UUID activeAssistantId) {}

  public record UserMessage(UUID id, int turnIndex, String content, OffsetDateTime createdAt) {}

  public record AssistantMessage(
      UUID id,
      UUID replyToId,
      int turnIndex,
      int variantIndex,
      boolean active,
      String status,
      String content,
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
