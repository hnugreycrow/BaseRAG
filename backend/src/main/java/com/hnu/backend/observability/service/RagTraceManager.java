package com.hnu.backend.observability.service;

import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.entity.RagRun;
import com.hnu.backend.observability.entity.RagStageRun;
import com.hnu.backend.observability.mapper.RagRunMapper;
import com.hnu.backend.observability.mapper.RagStageRunMapper;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.shared.web.RequestTiming;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 创建、封存和恢复单次会话问答 Trace。 */
@Service
public class RagTraceManager {
  private final RagRunMapper ragRunMapper;
  private final RagStageRunMapper ragStageRunMapper;

  /**
   * 创建 Trace 管理器。
   *
   * @param ragRunMapper 运行记录接口
   * @param ragStageRunMapper 阶段记录接口
   */
  public RagTraceManager(RagRunMapper ragRunMapper, RagStageRunMapper ragStageRunMapper) {
    this.ragRunMapper = ragRunMapper;
    this.ragStageRunMapper = ragStageRunMapper;
  }

  /**
   * 在回答消息创建事务内插入运行起始记录。
   *
   * @param ownerId 所属用户
   * @param conversationId 会话标识
   * @param userMessageId 用户消息标识
   * @param assistantMessageId 回答版本标识
   * @param question 经过规范化和长度校验的原始问题
   * @param timing HTTP 请求起始信息
   * @return 需要显式传入异步阶段的内存 Trace
   */
  public RagRunTrace start(
      UUID ownerId,
      UUID conversationId,
      UUID userMessageId,
      UUID assistantMessageId,
      String question,
      RequestTiming timing) {
    UUID id = UUID.randomUUID();
    RagRun run = new RagRun();
    run.setId(id);
    run.setOwnerId(ownerId);
    run.setRequestId(timing.requestId());
    run.setConversationId(conversationId);
    run.setUserMessageId(userMessageId);
    run.setAssistantMessageId(assistantMessageId);
    run.setQuestion(question);
    run.setStatus(RagRunStatus.RUNNING);
    run.setExecutionMode(RagExecutionMode.FULL_PIPELINE);
    run.setStartedAt(timing.startedAt());
    ragRunMapper.insert(run);
    return new RagRunTrace(id, timing.startedAt(), timing.startedNanos());
  }

  /**
   * 在回答终态事务内批量写入阶段并更新运行摘要。
   *
   * @param trace 内存 Trace
   * @param status 运行终态
   * @param errorCode 可选错误码
   */
  public void finish(RagRunTrace trace, RagRunStatus status, String errorCode) {
    RagRunTrace.RunSnapshot snapshot = trace.finish(status, errorCode);
    if (snapshot.runId() == null) return;
    if (ragRunMapper.finish(snapshot) == 0) return;
    List<RagStageRun> entities = snapshot.stages().stream().map(this::stage).toList();
    if (!entities.isEmpty()) ragStageRunMapper.insertBatch(entities);
  }

  /**
   * 取消仅存在于数据库、没有内存 Trace 的回答运行。
   *
   * @param assistantMessageId 回答消息标识
   */
  public void cancelStored(UUID assistantMessageId) {
    ragRunMapper.cancelByAssistantMessage(assistantMessageId);
  }

  /**
   * 恢复应用重启遗留的运行记录。
   *
   * @return 恢复数量
   */
  public int recoverInterrupted() {
    return ragRunMapper.recoverInterrupted();
  }

  /** 将不可变阶段快照转换为 MyBatis 实体。 */
  private RagStageRun stage(RagRunTrace.StageSnapshot value) {
    RagStageRun stage = new RagStageRun();
    stage.setId(value.id());
    stage.setRagRunId(value.runId());
    stage.setStageName(value.name());
    stage.setSubQuestionId(value.subQuestionId());
    stage.setSequenceNo(value.sequence());
    stage.setStatus(value.status());
    stage.setInputCount(value.inputCount());
    stage.setOutputCount(value.outputCount());
    stage.setModelId(value.modelId());
    stage.setProvider(value.provider());
    stage.setModel(value.model());
    stage.setReasonCode(value.reasonCode());
    stage.setErrorCode(value.errorCode());
    stage.setStartedAt(value.startedAt());
    stage.setFirstTokenAt(value.firstTokenAt());
    stage.setCompletedAt(value.completedAt());
    stage.setElapsedMs(value.elapsedMs());
    stage.setTtftMs(value.ttftMs());
    return stage;
  }
}
