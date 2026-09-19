package com.hnu.backend.observability.mapper;

import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

/** 运行表与安全用户展示字段的查询投影。 */
@Data
public class RagRunViewRow {
  private UUID id;
  private UUID ownerId;
  private String username;
  private String displayName;
  private String requestId;
  private UUID conversationId;
  private UUID userMessageId;
  private UUID assistantMessageId;
  private String question;
  private RagRunStatus status;
  private RagExecutionMode executionMode;
  private String modelId;
  private String provider;
  private String model;
  private int candidateCount;
  private int evidenceCount;
  private boolean degraded;
  private String errorCode;
  private OffsetDateTime startedAt;
  private OffsetDateTime firstTokenAt;
  private OffsetDateTime completedAt;
  private Long totalMs;
  private Long endToEndTtftMs;
  private Long modelTtftMs;
}
