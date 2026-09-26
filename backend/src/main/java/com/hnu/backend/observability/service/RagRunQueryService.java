package com.hnu.backend.observability.service;

import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.entity.UserRole;
import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.RagStageName;
import com.hnu.backend.observability.RagStageStatus;
import com.hnu.backend.observability.TraceReasonCatalog;
import com.hnu.backend.observability.configuration.ObservabilityProperties;
import com.hnu.backend.observability.entity.RagStageRun;
import com.hnu.backend.observability.mapper.RagRunFilter;
import com.hnu.backend.observability.mapper.RagRunMapper;
import com.hnu.backend.observability.mapper.RagRunSummaryRow;
import com.hnu.backend.observability.mapper.RagRunViewRow;
import com.hnu.backend.observability.mapper.RagStageRunMapper;
import com.hnu.backend.observability.vo.DashboardTrend;
import com.hnu.backend.observability.vo.RagRunResponses;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import com.hnu.backend.shared.web.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 执行用户隔离的问答运行查询和统计。 */
@Service
public class RagRunQueryService {
  private final RagRunMapper ragRunMapper;
  private final RagStageRunMapper ragStageRunMapper;
  private final ObservabilityProperties observability;

  /**
   * 创建观测查询服务。
   *
   * @param ragRunMapper 运行查询接口
   * @param ragStageRunMapper 阶段查询接口
   * @param observability 历史数据保留配置
   */
  public RagRunQueryService(
      RagRunMapper ragRunMapper,
      RagStageRunMapper ragStageRunMapper,
      ObservabilityProperties observability) {
    this.ragRunMapper = ragRunMapper;
    this.ragStageRunMapper = ragStageRunMapper;
    this.observability = observability;
  }

  /**
   * 查询指定自然日范围的趋势及上一等长周期数量。
   *
   * @param actor 当前用户，普通用户只统计自己的请求
   * @param from 北京时间开始日期，包含
   * @param to 北京时间结束日期，包含；范围最多 30 天
   * @return 补齐空日期的趋势，无耗时样本时保留空值
   */
  public DashboardTrend trend(User actor, LocalDate from, LocalDate to) {
    if (from == null || to == null || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= 30) {
      throw ApiException.bad(ErrorCode.INVALID_TIME_RANGE, "日期范围须为 1 到 30 天");
    }
    long length = ChronoUnit.DAYS.between(from, to) + 1;
    var zone = ZoneId.of("Asia/Shanghai");
    var rows =
        ragRunMapper.dailyTrend(
            filter(
                actor,
                from.minusDays(length).atStartOfDay(zone).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                null,
                null,
                null,
                null));
    var byDate = rows.stream().collect(Collectors.toMap(DashboardTrend.Day::date, value -> value));
    var days =
        from.datesUntil(to.plusDays(1))
            .map(
                date ->
                    byDate.getOrDefault(
                        date, new DashboardTrend.Day(date, 0, 0, 0, 0, null, null, null, null)))
            .toList();
    long previousRequests =
        rows.stream()
            .filter(row -> row.date().isBefore(from))
            .mapToLong(DashboardTrend.Day::requestCount)
            .sum();
    long previousFailures =
        rows.stream()
            .filter(row -> row.date().isBefore(from))
            .mapToLong(DashboardTrend.Day::failureCount)
            .sum();
    // 超出保留期的上期数据可能被清理，不能把缺失数据当成零生成环比。
    boolean previousAvailable =
        !from.minusDays(length)
            .atStartOfDay(zone)
            .toOffsetDateTime()
            .isBefore(OffsetDateTime.now(zone).minusDays(observability.getRetentionDays()));
    return new DashboardTrend(
        days,
        previousAvailable ? previousRequests : null,
        previousAvailable ? previousFailures : null);
  }

  /**
   * 分页查询当前权限范围内的运行。
   *
   * @param actor 当前用户
   * @param from 开始时间下界，包含
   * @param to 开始时间上界，不包含
   * @param status 可选运行状态
   * @param model 可选精确模型名
   * @param executionMode 可选执行模式
   * @param userId 管理员可选用户范围
   * @param page 从 1 开始的页码
   * @param pageSize 每页数量
   * @return 安全运行摘要分页
   */
  public PageResponse<RagRunResponses.Summary> list(
      User actor,
      OffsetDateTime from,
      OffsetDateTime to,
      RagRunStatus status,
      String model,
      RagExecutionMode executionMode,
      UUID userId,
      int page,
      int pageSize) {
    validate(from, to, page, pageSize);
    RagRunFilter filter = filter(actor, from, to, status, model, executionMode, userId);
    boolean includeUser = actor.getRole() == UserRole.ADMIN;
    long total = ragRunMapper.count(filter);
    List<RagRunResponses.Summary> items =
        ragRunMapper.list(filter, pageSize, (long) (page - 1) * pageSize).stream()
            .map(value -> summary(value, includeUser))
            .toList();
    return PageResponse.of(items, total, page, pageSize);
  }

  /**
   * 加载当前权限范围内的阶段瀑布。
   *
   * @param actor 当前用户
   * @param id 运行标识
   * @return 运行摘要、阶段及降级原因
   */
  public RagRunResponses.Detail get(User actor, UUID id) {
    UUID ownerScope = actor.getRole() == UserRole.ADMIN ? null : actor.getId();
    RagRunViewRow run = ragRunMapper.findView(id, ownerScope);
    if (run == null) {
      throw ApiException.notFound(ErrorCode.RAG_RUN_NOT_FOUND, "问答运行记录不存在");
    }
    List<RagStageRun> storedStages = ragStageRunMapper.listByRun(id);
    List<RagRunResponses.Stage> stageResponses = storedStages.stream().map(this::stage).toList();
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    storedStages.stream()
        .filter(this::isDegradation)
        .map(RagStageRun::getReasonCode)
        .filter(value -> value != null && !value.isBlank())
        .forEach(reasons::add);
    return new RagRunResponses.Detail(
        summary(run, actor.getRole() == UserRole.ADMIN), stageResponses, List.copyOf(reasons));
  }

  /**
   * 计算当前权限和筛选范围内的聚合指标。
   *
   * @param actor 当前用户
   * @param from 开始时间下界，包含
   * @param to 开始时间上界，不包含
   * @param status 可选运行状态
   * @param model 可选精确模型名
   * @param executionMode 可选执行模式
   * @param userId 管理员可选用户范围
   * @return 请求量、终态比率和耗时分位数
   */
  public RagRunResponses.Aggregate summary(
      User actor,
      OffsetDateTime from,
      OffsetDateTime to,
      RagRunStatus status,
      String model,
      RagExecutionMode executionMode,
      UUID userId) {
    validate(from, to, 1, 20);
    RagRunSummaryRow row =
        ragRunMapper.summary(filter(actor, from, to, status, model, executionMode, userId));
    long terminal = row == null ? 0 : row.getTerminalCount();
    return new RagRunResponses.Aggregate(
        row == null ? 0 : row.getRequestCount(),
        terminal == 0 ? 0 : (double) row.getSuccessCount() / terminal,
        terminal == 0 ? 0 : (double) row.getDegradedCount() / terminal,
        percentiles(
            row == null ? null : row.getTotalP50Ms(), row == null ? null : row.getTotalP95Ms()),
        percentiles(
            row == null ? null : row.getEndToEndTtftP50Ms(),
            row == null ? null : row.getEndToEndTtftP95Ms()),
        percentiles(
            row == null ? null : row.getModelTtftP50Ms(),
            row == null ? null : row.getModelTtftP95Ms()));
  }

  /** 收敛管理员和普通用户的所有权过滤条件。 */
  private RagRunFilter filter(
      User actor,
      OffsetDateTime from,
      OffsetDateTime to,
      RagRunStatus status,
      String model,
      RagExecutionMode executionMode,
      UUID requestedUserId) {
    UUID ownerId;
    if (actor.getRole() == UserRole.ADMIN) {
      ownerId = requestedUserId;
    } else {
      if (requestedUserId != null && !requestedUserId.equals(actor.getId())) {
        throw new ApiException(ErrorCode.FORBIDDEN, "当前账号无权查询其他用户");
      }
      ownerId = actor.getId();
    }
    String normalizedModel = model == null || model.isBlank() ? null : model.strip();
    return new RagRunFilter(from, to, status, normalizedModel, executionMode, ownerId);
  }

  /** 校验时间和分页边界。 */
  private void validate(OffsetDateTime from, OffsetDateTime to, int page, int pageSize) {
    if (from != null && to != null && !from.isBefore(to)) {
      throw ApiException.bad(ErrorCode.INVALID_TIME_RANGE, "开始时间必须早于结束时间");
    }
    if (page < 1 || pageSize < 1 || pageSize > 100) {
      throw ApiException.bad(ErrorCode.INVALID_PAGE, "页码应大于 0，每页数量应为 1 到 100");
    }
  }

  /** 转换运行查询投影。 */
  private RagRunResponses.Summary summary(RagRunViewRow value, boolean includeUser) {
    return new RagRunResponses.Summary(
        value.getId(),
        includeUser ? value.getOwnerId() : null,
        includeUser ? value.getUsername() : null,
        includeUser ? value.getDisplayName() : null,
        value.getRequestId(),
        value.getConversationId(),
        value.getUserMessageId(),
        value.getAssistantMessageId(),
        value.getQuestion(),
        value.getStatus(),
        value.getExecutionMode(),
        value.getModelId(),
        value.getProvider(),
        value.getModel(),
        value.getCandidateCount(),
        value.getEvidenceCount(),
        value.isDegraded(),
        value.getErrorCode(),
        ErrorCode.messageFor(value.getErrorCode()),
        value.getStartedAt(),
        value.getFirstTokenAt(),
        value.getCompletedAt(),
        value.getTotalMs(),
        value.getEndToEndTtftMs(),
        value.getModelTtftMs(),
        value.getFirstReasoningMs(),
        value.getFirstAnswerMs());
  }

  /** 判断阶段原因是否属于 run 级降级口径。 */
  private boolean isDegradation(RagStageRun value) {
    if (value.getStatus() == RagStageStatus.DEGRADED) {
      return true;
    }
    return value.getStageName() == RagStageName.ANSWER_MODEL
        && (TraceReasonCatalog.PROVIDER_FALLBACK.code().equals(value.getReasonCode())
            || TraceReasonCatalog.CITATION_REPAIR.code().equals(value.getReasonCode()));
  }

  /** 转换阶段实体。 */
  private RagRunResponses.Stage stage(RagStageRun value) {
    return new RagRunResponses.Stage(
        value.getId(),
        value.getStageName(),
        value.getSubQuestionId(),
        value.getSequenceNo(),
        value.getStatus(),
        value.getInputCount(),
        value.getOutputCount(),
        value.getModelId(),
        value.getProvider(),
        value.getModel(),
        value.getReasonCode(),
        value.getErrorCode(),
        ErrorCode.messageFor(value.getErrorCode()),
        value.getStartedAt(),
        value.getFirstTokenAt(),
        value.getCompletedAt(),
        value.getElapsedMs(),
        value.getTtftMs(),
        value.getParentStageId(),
        value.getQueueMs(),
        value.getAttemptId(),
        value.getAttemptIndex(),
        value.getFirstReasoningMs(),
        value.getFirstAnswerMs());
  }

  /** 创建延迟分位数组合。 */
  private RagRunResponses.Percentiles percentiles(Long p50, Long p95) {
    return new RagRunResponses.Percentiles(p50, p95);
  }
}
