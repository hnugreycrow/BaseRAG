package com.hnu.backend.observability.vo;

import java.time.LocalDate;
import java.util.List;

/**
 * 按北京时间自然日统计的 Dashboard 趋势。
 *
 * @param days 当前周期每日数据，按日期升序排列并补齐空日期
 * @param previousRequestCount 上一等长自然日周期的请求量，超出保留期时为空
 * @param previousFailureCount 上一周期的失败量，超出保留期时为空
 */
public record DashboardTrend(List<Day> days, Long previousRequestCount, Long previousFailureCount) {
  /**
   * 单日请求数量及成功请求的耗时分位数。
   *
   * @param date 北京时间日期
   * @param requestCount 全部状态的请求量，每个运行记录计一次
   * @param failureCount FAILED 请求量，不包含取消或重启中断
   * @param ttftSampleCount 具有首字耗时的成功请求数
   * @param totalSampleCount 具有总耗时的成功请求数
   * @param ttftP50Ms 首字耗时中位数，单位毫秒，无样本时为空
   * @param ttftP95Ms 首字耗时第 95 百分位，单位毫秒，无样本时为空
   * @param totalP50Ms 总耗时中位数，单位毫秒，无样本时为空
   * @param totalP95Ms 总耗时第 95 百分位，单位毫秒，无样本时为空
   */
  public record Day(
      LocalDate date,
      long requestCount,
      long failureCount,
      long ttftSampleCount,
      long totalSampleCount,
      Long ttftP50Ms,
      Long ttftP95Ms,
      Long totalP50Ms,
      Long totalP95Ms) {}
}
