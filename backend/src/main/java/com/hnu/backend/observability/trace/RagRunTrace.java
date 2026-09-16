package com.hnu.backend.observability.trace;

import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.RagStageStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 显式跨线程传递的单次问答 Trace。
 *
 * <p>该对象只接受安全元数据。所有变更都在同一把锁内完成，使并发子问题、SSE 回调和取消终态可以共享它， 且不依赖无法跨虚拟线程传播的 {@link ThreadLocal}。
 */
public final class RagRunTrace {
  private static final RagRunTrace NOOP = new RagRunTrace();

  private final boolean enabled;
  private final UUID runId;
  private final OffsetDateTime startedAt;
  private final long startedNanos;
  private final List<StageSnapshot> stages = new ArrayList<>();
  private final Set<Span> openSpans = new LinkedHashSet<>();
  private int nextSequence = 1;
  private boolean sealed;
  private boolean degraded;
  private Long firstDeltaNanos;
  private RagExecutionMode executionMode = RagExecutionMode.FULL_PIPELINE;
  private int candidateCount;
  private int evidenceCount;
  private String modelId;
  private String provider;
  private String model;
  private Span finalAnswerSpan;
  private RunSnapshot terminalSnapshot;

  /** 创建无副作用 Trace。 */
  private RagRunTrace() {
    enabled = false;
    runId = null;
    startedAt = null;
    startedNanos = 0;
  }

  /**
   * 创建需要持久化的 Trace。
   *
   * @param runId 运行标识
   * @param startedAt HTTP 请求进入应用的墙钟时间
   * @param startedNanos 与开始时间同时捕获的单调时钟值
   */
  public RagRunTrace(UUID runId, OffsetDateTime startedAt, long startedNanos) {
    this.enabled = true;
    this.runId = runId;
    this.startedAt = startedAt;
    this.startedNanos = startedNanos;
  }

  /**
   * 返回不采集数据的 Trace，供兼容入口和无观测调用复用。
   *
   * @return 单例空 Trace
   */
  public static RagRunTrace noop() {
    return NOOP;
  }

  /** 返回当前问答运行标识；空 Trace 返回 null。 */
  public UUID runId() {
    return runId;
  }

  /**
   * 判断当前对象是否会采集数据。
   *
   * @return 持久化 Trace 为 true，空 Trace 为 false
   */
  public boolean enabled() {
    return enabled;
  }

  /**
   * 开始一个阶段。
   *
   * @param name 稳定阶段名
   * @param subQuestionId 可选子问题标识
   * @param inputCount 可选输入项数量
   * @return 可接收阶段结果的句柄
   */
  public synchronized Span start(RagStageName name, String subQuestionId, Integer inputCount) {
    if (!enabled || sealed) return Span.noop();
    Span span = new Span(this, name, subQuestionId, nextSequence++, inputCount, System.nanoTime());
    openSpans.add(span);
    return span;
  }

  /**
   * 记录未执行的阶段。
   *
   * @param name 阶段名
   * @param subQuestionId 可选子问题标识
   * @param reasonCode 跳过原因
   */
  public void skipped(RagStageName name, String subQuestionId, String reasonCode) {
    start(name, subQuestionId, 0).skipped(0, reasonCode);
  }

  /**
   * 设置本轮问答最终采用的执行模式。
   *
   * @param mode 执行模式
   */
  public synchronized void executionMode(RagExecutionMode mode) {
    if (enabled && !sealed) executionMode = mode;
  }

  /**
   * 保存进入去重前的全局候选数量。
   *
   * @param count 候选数量
   */
  public synchronized void candidateCount(int count) {
    if (enabled && !sealed) candidateCount = Math.max(0, count);
  }

  /**
   * 保存最终进入回答上下文的证据数量。
   *
   * @param count 证据数量
   */
  public synchronized void evidenceCount(int count) {
    if (enabled && !sealed) evidenceCount = Math.max(0, count);
  }

  /** 将本次运行标记为发生过可恢复降级。 */
  public synchronized void markDegraded() {
    if (enabled && !sealed) degraded = true;
  }

  /** 在第一条非空思考或正文增量成功写入 SSE 后记录端到端首内容。 */
  public synchronized void endToEndDeltaSent() {
    if (enabled && !sealed && firstDeltaNanos == null) firstDeltaNanos = System.nanoTime();
  }

  /**
   * 选择最终通过引用校验的回答模型尝试。
   *
   * @param span 对应的回答模型阶段
   * @param modelId 本地模型配置标识
   * @param provider 模型供应商
   * @param model 模型名称
   */
  public synchronized void finalAnswer(Span span, String modelId, String provider, String model) {
    if (!enabled || sealed || span == null || span.trace != this) return;
    this.finalAnswerSpan = span;
    this.modelId = modelId;
    this.provider = provider;
    this.model = model;
  }

  /**
   * 封存 Trace，并将尚未结束的阶段转换为与 run 一致的终态。
   *
   * @param status 运行终态
   * @param errorCode 可选稳定错误码
   * @return 可一次性持久化的不可变快照
   */
  public synchronized RunSnapshot finish(RagRunStatus status, String errorCode) {
    if (!enabled) return RunSnapshot.noop();
    if (terminalSnapshot != null) return terminalSnapshot;
    long completedNanos = System.nanoTime();
    RagStageStatus unfinishedStatus =
        status == RagRunStatus.CANCELLED ? RagStageStatus.CANCELLED : RagStageStatus.FAILED;
    String unfinishedCode =
        errorCode == null
            ? (status == RagRunStatus.CANCELLED ? "GENERATION_CANCELLED" : "RUN_TERMINATED")
            : errorCode;
    // 先在锁内关闭遗留 span，再封存集合，避免迟到的并发任务写入终态快照。
    for (Span span : List.copyOf(openSpans)) {
      finishSpan(span, unfinishedStatus, null, null, unfinishedCode, completedNanos);
    }
    sealed = true;
    OffsetDateTime completedAt = wallTime(completedNanos);
    Long endToEndTtft =
        firstDeltaNanos == null ? null : elapsedMillis(startedNanos, firstDeltaNanos);
    Long modelTtft =
        finalAnswerSpan == null || finalAnswerSpan.firstContentNanos == null
            ? null
            : elapsedMillis(finalAnswerSpan.startedNanos, finalAnswerSpan.firstContentNanos);
    terminalSnapshot =
        new RunSnapshot(
            runId,
            status,
            executionMode,
            modelId,
            provider,
            model,
            candidateCount,
            evidenceCount,
            degraded,
            errorCode,
            firstDeltaNanos == null ? null : wallTime(firstDeltaNanos),
            completedAt,
            elapsedMillis(startedNanos, completedNanos),
            endToEndTtft,
            modelTtft,
            List.copyOf(stages));
    return terminalSnapshot;
  }

  /** 完成一个阶段；调用方必须已经持有当前 Trace 的监视器。 */
  private void finishSpan(
      Span span,
      RagStageStatus status,
      Integer outputCount,
      String reasonCode,
      String errorCode,
      long completedNanos) {
    if (span.finished || !openSpans.remove(span)) return;
    span.finished = true;
    if (status == RagStageStatus.DEGRADED) degraded = true;
    stages.add(
        new StageSnapshot(
            UUID.randomUUID(),
            runId,
            span.name,
            span.subQuestionId,
            span.sequence,
            status,
            span.inputCount,
            outputCount,
            span.modelId,
            span.provider,
            span.model,
            reasonCode,
            errorCode,
            wallTime(span.startedNanos),
            span.firstContentNanos == null ? null : wallTime(span.firstContentNanos),
            wallTime(completedNanos),
            elapsedMillis(span.startedNanos, completedNanos),
            span.firstContentNanos == null
                ? null
                : elapsedMillis(span.startedNanos, span.firstContentNanos)));
  }

  /** 将单调时钟偏移映射到请求开始时捕获的墙钟时间。 */
  private OffsetDateTime wallTime(long nanos) {
    return startedAt.plusNanos(Math.max(0, nanos - startedNanos));
  }

  /** 返回两个单调时钟读数之间向下取整的非负毫秒数。 */
  private static long elapsedMillis(long start, long end) {
    return Math.max(0, (end - start) / 1_000_000);
  }

  /** 可由一个阶段的执行线程更新并且只能结束一次的句柄。 */
  public static final class Span {
    private static final Span NOOP_SPAN = new Span();
    private final RagRunTrace trace;
    private final RagStageName name;
    private final String subQuestionId;
    private final int sequence;
    private final Integer inputCount;
    private final long startedNanos;
    private String modelId;
    private String provider;
    private String model;
    private Long firstContentNanos;
    private boolean finished;

    /** 创建无副作用阶段句柄。 */
    private Span() {
      trace = null;
      name = null;
      subQuestionId = null;
      sequence = 0;
      inputCount = null;
      startedNanos = 0;
      finished = true;
    }

    /** 创建绑定到所属 Trace 的阶段。 */
    private Span(
        RagRunTrace trace,
        RagStageName name,
        String subQuestionId,
        int sequence,
        Integer inputCount,
        long startedNanos) {
      this.trace = trace;
      this.name = name;
      this.subQuestionId = subQuestionId;
      this.sequence = sequence;
      this.inputCount = inputCount;
      this.startedNanos = startedNanos;
    }

    /** 返回无副作用阶段句柄。 */
    private static Span noop() {
      return NOOP_SPAN;
    }

    /**
     * 为阶段附加非敏感模型标识。
     *
     * @param modelId 本地模型配置标识
     * @param provider 供应商
     * @param model 模型名称
     * @return 当前句柄
     */
    public Span model(String modelId, String provider, String model) {
      if (trace == null) return this;
      synchronized (trace) {
        if (!finished && !trace.sealed) {
          this.modelId = modelId;
          this.provider = provider;
          this.model = model;
        }
      }
      return this;
    }

    /** 记录模型返回第一段非空内容的时刻。 */
    public void firstContent() {
      if (trace == null) return;
      synchronized (trace) {
        if (!finished && !trace.sealed && firstContentNanos == null) {
          firstContentNanos = System.nanoTime();
        }
      }
    }

    /**
     * 将阶段标记为成功。
     *
     * @param outputCount 可选输出项数量
     */
    public void success(Integer outputCount) {
      finish(RagStageStatus.SUCCESS, outputCount, null, null);
    }

    /**
     * 将阶段标记为成功并保留非错误业务原因。
     *
     * @param outputCount 可选输出项数量
     * @param reasonCode 稳定业务原因
     */
    public void success(Integer outputCount, String reasonCode) {
      finish(RagStageStatus.SUCCESS, outputCount, reasonCode, null);
    }

    /**
     * 将阶段标记为降级成功。
     *
     * @param outputCount 可选输出项数量
     * @param reasonCode 稳定降级原因
     */
    public void degraded(Integer outputCount, String reasonCode) {
      finish(RagStageStatus.DEGRADED, outputCount, reasonCode, null);
    }

    /**
     * 将阶段标记为失败。
     *
     * @param errorCode 稳定错误码
     */
    public void failed(String errorCode) {
      finish(RagStageStatus.FAILED, null, null, errorCode);
    }

    /**
     * 将阶段标记为取消。
     *
     * @param errorCode 稳定取消码
     */
    public void cancelled(String errorCode) {
      finish(RagStageStatus.CANCELLED, null, null, errorCode);
    }

    /**
     * 将阶段标记为未执行。
     *
     * @param outputCount 输出数量
     * @param reasonCode 跳过原因
     */
    public void skipped(Integer outputCount, String reasonCode) {
      finish(RagStageStatus.SKIPPED, outputCount, reasonCode, null);
    }

    /** 按指定字段原子结束阶段。 */
    private void finish(
        RagStageStatus status, Integer outputCount, String reasonCode, String errorCode) {
      if (trace == null) return;
      synchronized (trace) {
        if (!trace.sealed) {
          trace.finishSpan(this, status, outputCount, reasonCode, errorCode, System.nanoTime());
        }
      }
    }
  }

  /**
   * Run 终态及其阶段批量快照。
   *
   * @param runId 运行标识
   * @param status 运行终态
   * @param executionMode 执行模式
   * @param modelId 最终模型配置标识
   * @param provider 最终模型供应商
   * @param model 最终模型名称
   * @param candidateCount 全局候选数
   * @param evidenceCount 最终证据数
   * @param degraded 是否发生降级
   * @param errorCode 可选运行错误码
   * @param firstTokenAt 首个成功发送思考或正文增量的时间
   * @param completedAt 终态时间
   * @param totalMs 总耗时
   * @param endToEndTtftMs 端到端首 Token 耗时
   * @param modelTtftMs 最终有效模型尝试首内容耗时
   * @param stages 阶段快照
   */
  public record RunSnapshot(
      UUID runId,
      RagRunStatus status,
      RagExecutionMode executionMode,
      String modelId,
      String provider,
      String model,
      int candidateCount,
      int evidenceCount,
      boolean degraded,
      String errorCode,
      OffsetDateTime firstTokenAt,
      OffsetDateTime completedAt,
      long totalMs,
      Long endToEndTtftMs,
      Long modelTtftMs,
      List<StageSnapshot> stages) {
    /** 创建空 Trace 的占位快照。 */
    private static RunSnapshot noop() {
      return new RunSnapshot(
          null,
          RagRunStatus.COMPLETED,
          RagExecutionMode.FULL_PIPELINE,
          null,
          null,
          null,
          0,
          0,
          false,
          null,
          null,
          null,
          0,
          null,
          null,
          List.of());
    }
  }

  /**
   * 单个阶段的不可变持久化快照。
   *
   * @param id 阶段标识
   * @param runId 所属运行标识
   * @param name 稳定阶段名
   * @param subQuestionId 可选子问题标识
   * @param sequence 开始顺序
   * @param status 阶段终态
   * @param inputCount 可选输入数量
   * @param outputCount 可选输出数量
   * @param modelId 可选模型配置标识
   * @param provider 可选模型供应商
   * @param model 可选模型名称
   * @param reasonCode 可选业务原因码
   * @param errorCode 可选错误码
   * @param startedAt 开始时间
   * @param firstTokenAt 可选首内容时间
   * @param completedAt 完成时间
   * @param elapsedMs 阶段耗时
   * @param ttftMs 可选阶段首内容耗时
   */
  public record StageSnapshot(
      UUID id,
      UUID runId,
      RagStageName name,
      String subQuestionId,
      int sequence,
      RagStageStatus status,
      Integer inputCount,
      Integer outputCount,
      String modelId,
      String provider,
      String model,
      String reasonCode,
      String errorCode,
      OffsetDateTime startedAt,
      OffsetDateTime firstTokenAt,
      OffsetDateTime completedAt,
      long elapsedMs,
      Long ttftMs) {}
}
