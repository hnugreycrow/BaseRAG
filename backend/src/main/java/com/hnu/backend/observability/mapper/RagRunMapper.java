package com.hnu.backend.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.observability.RagRunStatus;
import com.hnu.backend.observability.entity.RagRun;
import com.hnu.backend.observability.trace.RagRunTrace;
import com.hnu.backend.observability.vo.DashboardTrend;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 单次问答运行记录的写入、恢复和安全查询接口。 */
@Mapper
public interface RagRunMapper extends BaseMapper<RagRun> {
  /**
   * 按北京时间聚合每日运行量和成功请求的延迟。
   *
   * @param filter 已收敛权限和时间边界的筛选条件
   * @return 有请求的日期，按日期升序排列
   */
  List<DashboardTrend.Day> dailyTrend(@Param("filter") RagRunFilter filter);

  /**
   * 按回答版本查找运行记录。
   *
   * @param assistantMessageId 回答消息标识
   * @return 对应运行记录，不存在时为空
   */
  default RagRun findByAssistantMessage(UUID assistantMessageId) {
    return selectOne(
        Wrappers.<RagRun>lambdaQuery()
            .eq(RagRun::getAssistantMessageId, assistantMessageId)
            .last("LIMIT 1"));
  }

  /**
   * 原子写入仍处于运行状态的终态摘要。
   *
   * @param snapshot 内存 Trace 终态快照
   * @return 实际更新行数
   */
  default int finish(RagRunTrace.RunSnapshot snapshot) {
    return update(
        Wrappers.<RagRun>lambdaUpdate()
            .eq(RagRun::getId, snapshot.runId())
            .eq(RagRun::getStatus, RagRunStatus.RUNNING)
            .set(RagRun::getStatus, snapshot.status())
            .set(RagRun::getExecutionMode, snapshot.executionMode())
            .set(RagRun::getModelId, snapshot.modelId())
            .set(RagRun::getProvider, snapshot.provider())
            .set(RagRun::getModel, snapshot.model())
            .set(RagRun::getCandidateCount, snapshot.candidateCount())
            .set(RagRun::getEvidenceCount, snapshot.evidenceCount())
            .set(RagRun::isDegraded, snapshot.degraded())
            .set(RagRun::getErrorCode, snapshot.errorCode())
            .set(RagRun::getFirstTokenAt, snapshot.firstTokenAt())
            .set(RagRun::getCompletedAt, snapshot.completedAt())
            .set(RagRun::getTotalMs, snapshot.totalMs())
            .set(RagRun::getEndToEndTtftMs, snapshot.endToEndTtftMs())
            .set(RagRun::getModelTtftMs, snapshot.modelTtftMs())
            .set(RagRun::getFirstReasoningMs, snapshot.firstReasoningMs())
            .set(RagRun::getFirstAnswerMs, snapshot.firstAnswerMs()));
  }

  /**
   * 将应用重启遗留的运行记录恢复为中断终态。
   *
   * @return 恢复数量
   */
  @Update(
      "UPDATE rag_runs SET status = 'INTERRUPTED', error_code = 'GENERATION_INTERRUPTED', "
          + "completed_at = now(), total_ms = GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (now() - started_at)) * 1000)::bigint) "
          + "WHERE status = 'RUNNING'")
  int recoverInterrupted();

  /**
   * 直接取消没有内存 Trace 的运行记录。
   *
   * @param assistantMessageId 回答消息标识
   * @return 更新数量
   */
  @Update(
      "UPDATE rag_runs SET status = 'CANCELLED', error_code = 'GENERATION_CANCELLED', "
          + "completed_at = now(), total_ms = GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (now() - started_at)) * 1000)::bigint) "
          + "WHERE assistant_message_id = #{assistantMessageId} AND status = 'RUNNING'")
  int cancelByAssistantMessage(@Param("assistantMessageId") UUID assistantMessageId);

  /**
   * 删除超过保留期的运行记录，阶段记录由外键级联清理。
   *
   * @param retentionDays 保留天数
   * @return 删除数量
   */
  @Delete("DELETE FROM rag_runs WHERE started_at < now() - (#{retentionDays} * INTERVAL '1 day')")
  int deleteExpired(@Param("retentionDays") int retentionDays);

  /**
   * 按过滤条件分页查询运行摘要。
   *
   * @param filter 已收敛权限的筛选条件
   * @param limit 返回上限
   * @param offset 跳过数量
   * @return 按开始时间和 ID 倒序排列的运行
   */
  List<RagRunViewRow> list(
      @Param("filter") RagRunFilter filter,
      @Param("limit") int limit,
      @Param("offset") long offset);

  /**
   * 按过滤条件统计运行数。
   *
   * @param filter 已收敛权限的筛选条件
   * @return 匹配数量
   */
  long count(@Param("filter") RagRunFilter filter);

  /**
   * 按权限范围查询单次运行。
   *
   * @param id 运行标识
   * @param ownerId 普通用户所有权限制；管理员查询时为空
   * @return 安全运行投影，不存在或越权时为空
   */
  RagRunViewRow findView(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

  /**
   * 按过滤条件计算汇总与延迟分位数。
   *
   * @param filter 已收敛权限的筛选条件
   * @return PostgreSQL 聚合结果
   */
  RagRunSummaryRow summary(@Param("filter") RagRunFilter filter);
}
