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

  /** 父阶段标识，根节点为空。 */
  private UUID parentStageId;

  /** 任务排队毫秒。 */
  private Long queueMs;

  /** 模型尝试标识。 */
  private UUID attemptId;

  /** 模型尝试序号。 */
  private Integer attemptIndex;

  /** 首次思考相对毫秒。 */
  private Long firstReasoningMs;

  /** 首次正文相对毫秒。 */
  private Long firstAnswerMs;
}
