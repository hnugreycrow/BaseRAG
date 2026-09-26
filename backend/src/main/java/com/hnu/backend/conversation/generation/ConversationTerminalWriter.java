package com.hnu.backend.conversation.generation;

import com.hnu.backend.conversation.entity.GenerationAttemptStatus;
import com.hnu.backend.conversation.entity.MessageStatus;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.GenerationAttemptMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.service.RagTraceManager;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.shared.error.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 将消息、模型尝试与 Trace 的回答终态写入同一个事务。 */
@Service
public class ConversationTerminalWriter {
  private final ConversationMapper conversations;
  private final MessageMapper messages;
  private final GenerationAttemptMapper attempts;
  private final TransactionTemplate tx;
  private final RagTraceManager traces;

  /**
   * 创建终态持久化服务。
   *
   * @param conversations 会话持久化接口
   * @param messages 回答消息持久化接口
   * @param attempts 模型尝试持久化接口
   * @param tx 原子写入终态与 Trace 的事务模板
   * @param traces Trace 持久化服务
   */
  public ConversationTerminalWriter(
      ConversationMapper conversations,
      MessageMapper messages,
      GenerationAttemptMapper attempts,
      TransactionTemplate tx,
      RagTraceManager traces) {
    this.conversations = conversations;
    this.messages = messages;
    this.attempts = attempts;
    this.tx = tx;
    this.traces = traces;
  }

  /** 持久化一次活动生成的终态快照。 */
  record Context(
      UUID ownerId,
      UUID generationId,
      UUID conversationId,
      String content,
      String reasoning,
      UUID currentAttemptId,
      RagRunTrace trace) {}

  /** 保存成功回答与 Trace。 */
  void complete(Context context, String citationsJson, String modelInfoJson) {
    RagRunTrace.Span span = context.trace().start(RagStageName.RESULT_PERSISTENCE, null, 1);
    try {
      tx.executeWithoutResult(
          ignored -> {
            int changed =
                messages.complete(
                    context.ownerId(),
                    context.generationId(),
                    context.content(),
                    citationsJson,
                    modelInfoJson);
            if (changed > 0) {
              messages.saveReasoning(
                  context.ownerId(), context.generationId(), context.reasoning());
              conversations.touch(context.ownerId(), context.conversationId());
            }
            span.success(changed);
            traces.finish(context.trace(), RagRunStatus.COMPLETED, null);
          });
    } catch (RuntimeException error) {
      span.failed(ErrorCode.TRACE_OR_RESULT_PERSISTENCE_FAILED.code());
      throw error;
    }
  }

  /** 保存取消终态；进程内与已落库任务使用相同的状态条件。 */
  void cancel(Context context) {
    RagRunTrace.Span span = context.trace().start(RagStageName.RESULT_PERSISTENCE, null, 1);
    try {
      tx.executeWithoutResult(
          ignored -> {
            int changed =
                messages.cancelRunning(
                    context.ownerId(),
                    context.generationId(),
                    context.conversationId(),
                    context.content());
            if (changed > 0) {
              messages.saveReasoning(
                  context.ownerId(), context.generationId(), context.reasoning());
              conversations.touch(context.ownerId(), context.conversationId());
            }
            attempts.cancelRunning(context.ownerId(), context.generationId());
            span.success(changed);
            traces.finish(
                context.trace(), RagRunStatus.CANCELLED, ErrorCode.GENERATION_CANCELLED.code());
          });
    } catch (RuntimeException error) {
      span.failed(ErrorCode.TRACE_OR_RESULT_PERSISTENCE_FAILED.code());
      throw error;
    }
  }

  /** 保存失败终态与当前模型尝试。 */
  void fail(Context context, String code, String message) {
    RagRunTrace.Span span = context.trace().start(RagStageName.RESULT_PERSISTENCE, null, 1);
    try {
      tx.executeWithoutResult(
          ignored -> {
            int changed =
                messages.terminalFailure(
                    context.ownerId(),
                    context.generationId(),
                    MessageStatus.FAILED,
                    context.content(),
                    code,
                    message);
            if (changed > 0) {
              messages.saveReasoning(
                  context.ownerId(), context.generationId(), context.reasoning());
              if (context.currentAttemptId() != null) {
                attempts.fail(
                    context.ownerId(),
                    context.currentAttemptId(),
                    GenerationAttemptStatus.FAILED,
                    context.content(),
                    code,
                    message);
                attempts.saveReasoning(
                    context.ownerId(), context.currentAttemptId(), context.reasoning());
              }
              conversations.touch(context.ownerId(), context.conversationId());
            }
            span.success(changed);
            traces.finish(context.trace(), RagRunStatus.FAILED, code);
          });
    } catch (RuntimeException error) {
      span.failed(ErrorCode.TRACE_OR_RESULT_PERSISTENCE_FAILED.code());
      throw error;
    }
  }

  /** 取消仅在数据库中存在的运行中回答。 */
  void cancelStored(UUID ownerId, UUID conversationId, UUID generationId, String content) {
    tx.executeWithoutResult(
        ignored -> {
          int changed = messages.cancelRunning(ownerId, generationId, conversationId, content);
          attempts.cancelRunning(ownerId, generationId);
          traces.cancelStored(generationId);
          if (changed > 0) {
            conversations.touch(ownerId, conversationId);
          }
        });
  }
}
