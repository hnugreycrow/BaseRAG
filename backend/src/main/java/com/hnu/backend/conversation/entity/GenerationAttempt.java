package com.hnu.backend.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
@TableName("generation_attempts")
public class GenerationAttempt {
  @TableId(type = IdType.INPUT)
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
