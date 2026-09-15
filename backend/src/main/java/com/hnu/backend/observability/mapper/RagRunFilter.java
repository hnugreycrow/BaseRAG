package com.hnu.backend.observability.mapper;

import com.hnu.backend.observability.RagExecutionMode;
import com.hnu.backend.observability.RagRunStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 已完成权限收敛的运行查询条件。
 *
 * @param from 开始时间下界，包含
 * @param to 开始时间上界，不包含
 * @param status 可选运行状态
 * @param model 可选精确模型名称
 * @param executionMode 可选执行模式
 * @param ownerId 可选用户范围；管理员全局查询时为空
 */
public record RagRunFilter(
    OffsetDateTime from,
    OffsetDateTime to,
    RagRunStatus status,
    String model,
    RagExecutionMode executionMode,
    UUID ownerId) {}
