package com.hnu.backend.conversation.generation;

import com.hnu.backend.conversation.entity.Conversation;
import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.observability.trace.AnswerTraceObserver;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.rag.answer.AnswerGenerator;
import com.hnu.backend.shared.error.ApiException;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 封装一次回答生成的内存状态与终态竞争。
 *
 * <p>缓冲、尝试和生命周期由同一监视器保护；数据库写入、流关闭和线程中断在锁外执行。 生成线程负责尝试与检查点，取消线程只能请求取消和竞争终态。终态一旦认领便不可重入，
 * 即使持久化失败也由调用方报告错误并清理任务，不重新开放生成。
 */
final class ActiveGeneration {
  private final UUID ownerId;
  private final Conversation conversation;
  private final Message user;
  private final UUID generationId;
  private final int variantIndex;
  private final boolean thinkingEnabled;
  private final String requestId;
  private final ConversationSseChannel channel;
  private final AnswerGenerator.Control control;
  private final RagRunTrace trace;
  private final LongSupplier ticker;
  private final StringBuilder content = new StringBuilder();
  private final StringBuilder reasoning = new StringBuilder();
  private boolean terminal;
  private boolean cancellationRequested;
  private boolean running;
  private Future<?> future;
  private UUID currentAttemptId;
  private int attemptIndex;
  private AnswerTraceObserver answerTrace;
  private int checkpointLength;
  private long checkpointAt;

  /** 创建生成上下文；间隔计时使用单调时钟，不受系统时间校准影响。 */
  ActiveGeneration(
      Conversation conversation,
      Message user,
      Message assistant,
      String requestId,
      ConversationSseChannel channel,
      AnswerGenerator.Control control,
      RagRunTrace trace) {
    this(conversation, user, assistant, requestId, channel, control, trace, System::nanoTime);
  }

  /** 注入返回纳秒值的单调时钟，供检查点边界测试精确推进时间。 */
  ActiveGeneration(
      Conversation conversation,
      Message user,
      Message assistant,
      String requestId,
      ConversationSseChannel channel,
      AnswerGenerator.Control control,
      RagRunTrace trace,
      LongSupplier ticker) {
    this.ownerId = conversation.getOwnerId();
    this.conversation = conversation;
    this.user = user;
    this.generationId = assistant.getId();
    this.variantIndex = assistant.getVariantIndex();
    this.thinkingEnabled = assistant.isThinkingEnabled();
    this.requestId = requestId;
    this.channel = channel;
    this.control = control;
    this.trace = trace;
    this.ticker = ticker;
    checkpointAt = ticker.getAsLong();
  }

  UUID ownerId() {
    return ownerId;
  }

  UUID generationId() {
    return generationId;
  }

  int variantIndex() {
    return variantIndex;
  }

  boolean thinkingEnabled() {
    return thinkingEnabled;
  }

  Conversation conversation() {
    return conversation;
  }

  Message user() {
    return user;
  }

  String requestId() {
    return requestId;
  }

  ConversationSseChannel channel() {
    return channel;
  }

  AnswerGenerator.Control control() {
    return control;
  }

  RagRunTrace trace() {
    return trace;
  }

  /** 标记工作线程已启动；已经取消或终止的任务不再执行流水线。 */
  synchronized void start() {
    ensureWritable();
    running = true;
  }

  /** 注册任务句柄；补偿启动后、句柄注册前发生的取消。 */
  void attachTask(Future<?> task) {
    boolean interrupt;
    synchronized (this) {
      future = task;
      interrupt = running && cancellationRequested;
    }
    if (interrupt) {
      task.cancel(true);
    }
  }

  /** 请求取消；已认领的终态不被后来的取消覆盖，外部关闭操作不持有状态锁。 */
  void cancel() {
    Future<?> task;
    synchronized (this) {
      if (terminal || cancellationRequested) {
        return;
      }
      cancellationRequested = true;
      task = running ? future : null;
    }
    try {
      control.close();
    } finally {
      if (task != null) {
        task.cancel(true);
      }
    }
  }

  synchronized boolean cancelled() {
    return cancellationRequested || control.cancelled();
  }

  synchronized boolean terminal() {
    return terminal;
  }

  /** 认领终态并冻结缓冲；返回 false 表示已有其他路径负责持久化和清理。 */
  synchronized boolean tryFinish() {
    if (terminal) {
      return false;
    }
    terminal = true;
    return true;
  }

  /** 认领成功终态；先到达的取消请求优先，调用方应转入取消终态。 */
  synchronized boolean tryComplete() {
    return !cancelled() && tryFinish();
  }

  /** 在阶段边界和每次内容修改前阻止迟到的回调。 */
  synchronized void ensureWritable() {
    if (terminal || cancelled()) {
      throw ApiException.cancelled();
    }
  }

  /** 开始新的模型尝试；编号与标识在同一个临界区更新。 */
  synchronized int beginAttempt(UUID id) {
    ensureWritable();
    currentAttemptId = id;
    return ++attemptIndex;
  }

  synchronized UUID currentAttemptId() {
    return currentAttemptId;
  }

  synchronized int attemptIndex() {
    return attemptIndex;
  }

  synchronized AnswerTraceObserver answerTrace() {
    return answerTrace;
  }

  /** 注册当前回答的 Trace 观察器。 */
  synchronized void observeAnswer(AnswerTraceObserver observer) {
    ensureWritable();
    answerTrace = observer;
  }

  /** 追加正文；终态快照不会接纳迟到的内容。 */
  synchronized void appendContent(String text) {
    ensureWritable();
    content.append(text);
  }

  /** 追加思考内容，与正文共享快照边界。 */
  synchronized void appendReasoning(String text) {
    ensureWritable();
    reasoning.append(text);
  }

  /** 引用归一化只替换正文，保留当前思考内容。 */
  synchronized void replaceContent(String text) {
    ensureWritable();
    content.setLength(0);
    content.append(text);
  }

  /** 引用修复前清空正文与思考，检查点在数据库写入成功后单独确认。 */
  synchronized void resetContent() {
    ensureWritable();
    content.setLength(0);
    reasoning.setLength(0);
  }

  /** 返回同一时刻的不可变内容与尝试快照，不暴露可变缓冲区。 */
  synchronized Snapshot snapshot() {
    return new Snapshot(
        content.toString(), reasoning.toString(), currentAttemptId, ticker.getAsLong());
  }

  /** 达到字符或毫秒间隔阈值时返回待保存快照；终态不再创建检查点。 */
  synchronized Snapshot checkpointDue(int chars, long intervalMs) {
    if (terminal || cancelled()) {
      return null;
    }
    long now = ticker.getAsLong();
    if (content.length() + reasoning.length() - checkpointLength >= chars
        || now - checkpointAt >= TimeUnit.MILLISECONDS.toNanos(intervalMs)) {
      return new Snapshot(content.toString(), reasoning.toString(), currentAttemptId, now);
    }
    return null;
  }

  /** 仅在快照成功持久化后推进检查点，失败时下一次仍可重试。 */
  synchronized void checkpointSaved(Snapshot value) {
    checkpointLength = value.length();
    checkpointAt = value.atNanos();
  }

  /**
   * 用于检查点和终态持久化的一致快照。
   *
   * @param content 已接收正文
   * @param reasoning 已接收思考内容
   * @param attemptId 当前尝试标识，尚未开始模型尝试时为 null
   * @param atNanos 单调时钟的纳秒值，仅用于计算间隔
   */
  record Snapshot(String content, String reasoning, UUID attemptId, long atNanos) {
    int length() {
      return content.length() + reasoning.length();
    }
  }
}
