package com.hnu.backend.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

/** 一条助手消息针对某个模型的一次生成尝试记录。 */
@Data
@TableName("generation_attempts")
public class GenerationAttempt {
  /** 尝试记录标识。 */
  @TableId(type = IdType.INPUT)
  private UUID id;

  /** 所属助手消息标识。 */
  private UUID assistantMessageId;

  /** 同一助手消息内的尝试序号。 */
  private int attemptIndex;

  /** 发起此次尝试的原因，如主模型或故障转移。 */
  private String reason;

  /** 模型配置标识。 */
  private String modelId;

  /** 模型供应商。 */
  private String provider;

  /** 模型名称。 */
  private String model;

  /** 尝试状态。 */
  private String status;

  /** 本次尝试生成的完整或部分内容。 */
  private String content;

  /** 模型返回的生成结束原因。 */
  private String finishReason;

  /** 失败时的稳定错误码。 */
  private String errorCode;

  /** 失败时的错误信息。 */
  private String errorMessage;

  /** 尝试开始时间。 */
  private OffsetDateTime startedAt;

  /** 尝试完成或失败时间。 */
  private OffsetDateTime completedAt;
}
