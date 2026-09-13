package com.hnu.backend.conversation.domain;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public class Conversation {
  private UUID id;
  private String title;
  private String summaryJson;
  private int summarizedThroughTurn;
  private int summaryRevision;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
