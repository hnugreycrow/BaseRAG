package com.hnu.backend.observability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.RagStageStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

/** 单次问答中的一个阶段或模型尝试。 */
@Data
@TableName("rag_stage_runs")
public class RagStageRun {
  @TableId(type = IdType.INPUT)
  private UUID id;

  private UUID ragRunId;
  private RagStageName stageName;
  private String subQuestionId;
  private int sequenceNo;
  private RagStageStatus status;
  private Integer inputCount;
  private Integer outputCount;
  private String modelId;
  private String provider;
  private String model;
  private String reasonCode;
  private String errorCode;
  private OffsetDateTime startedAt;
  private OffsetDateTime firstTokenAt;
  private OffsetDateTime completedAt;
  private long elapsedMs;
  private Long ttftMs;
}
