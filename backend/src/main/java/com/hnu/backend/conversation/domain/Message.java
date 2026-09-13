package com.hnu.backend.conversation.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public class Message {
  private UUID id;
  private UUID conversationId;
  private UUID clientRequestId;
  private String role;
  private int turnIndex;
  private int variantIndex;
  private boolean active;
  private UUID replyToId;
  private String status;
  private String content;
  private String retrievalQuery;
  private String sourcesJson;
  private String citationsJson;
  private String modelInfoJson;
  private String errorCode;
  private String errorMessage;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private OffsetDateTime completedAt;
}
