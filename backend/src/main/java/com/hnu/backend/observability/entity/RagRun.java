package com.hnu.backend.observability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

/** 单次会话回答的观测摘要，仅保存原始问题快照，不保存回答或提示词正文。 */
@Data
@TableName("rag_runs")
public class RagRun {
  @TableId(type = IdType.INPUT)
  private UUID id;

  private UUID ownerId;
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
