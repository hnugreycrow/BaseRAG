package com.hnu.backend.observability.controller;

import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.service.RagRunQueryService;
import com.hnu.backend.observability.vo.RagRunResponses;
import com.hnu.backend.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供当前用户隔离、管理员可全局查看的问答观测接口。 */
@RestController
@Profile("local")
@Validated
@RequestMapping("/api/observability/rag-runs")
public class RagRunController {
  private final CurrentUserService currentUsers;
  private final RagRunQueryService queries;

  /**
   * 创建问答观测控制器。
   *
   * @param currentUsers 当前用户解析服务
   * @param queries 安全查询服务
   */
  public RagRunController(CurrentUserService currentUsers, RagRunQueryService queries) {
    this.currentUsers = currentUsers;
    this.queries = queries;
  }

  /**
   * 按时间、状态、模型、执行模式和用户分页查询运行。
   *
   * @param from 开始时间下界，包含
   * @param to 开始时间上界，不包含
   * @param status 可选运行状态
   * @param model 可选精确模型名
   * @param executionMode 可选执行模式
   * @param userId 管理员可选用户范围
   * @param page 从 1 开始的页码
   * @param pageSize 每页数量，最大 100
   * @return 当前权限范围内的运行分页
   */
  @GetMapping
  public PageResponse<RagRunResponses.Summary> list(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(required = false) RagRunStatus status,
      @RequestParam(required = false) @Size(max = 200) String model,
      @RequestParam(required = false) RagExecutionMode executionMode,
      @RequestParam(required = false) UUID userId,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
    User actor = currentUsers.require();
    return queries.list(actor, from, to, status, model, executionMode, userId, page, pageSize);
  }

  /**
   * 查询单次问答运行及其阶段瀑布。
   *
   * @param id 运行标识
   * @return 安全运行详情
   */
  @GetMapping("/{id}")
  public RagRunResponses.Detail get(@PathVariable UUID id) {
    return queries.get(currentUsers.require(), id);
  }

  /**
   * 计算筛选范围内的请求量、比率和延迟分位数。
   *
   * @param from 开始时间下界，包含
   * @param to 开始时间上界，不包含
   * @param status 可选运行状态
   * @param model 可选精确模型名
   * @param executionMode 可选执行模式
   * @param userId 管理员可选用户范围
   * @return 聚合指标
   */
  @GetMapping("/summary")
  public RagRunResponses.Aggregate summary(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime to,
      @RequestParam(required = false) RagRunStatus status,
      @RequestParam(required = false) @Size(max = 200) String model,
      @RequestParam(required = false) RagExecutionMode executionMode,
      @RequestParam(required = false) UUID userId) {
    return queries.summary(currentUsers.require(), from, to, status, model, executionMode, userId);
  }
}
