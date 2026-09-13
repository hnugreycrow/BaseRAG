package com.hnu.backend.conversation.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public class GenerationAttempt {
  private UUID id;
  private UUID assistantMessageId;
  private int attemptIndex;
  private String reason;
  private String modelId;
  private String provider;
  private String model;
  private String status;
  private String content;
  private String finishReason;
  private String errorCode;
  private String errorMessage;
  private OffsetDateTime startedAt;
  private OffsetDateTime completedAt;
}
