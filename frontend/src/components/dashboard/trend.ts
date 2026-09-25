import type { DashboardDay } from '../../api'

/** 用 UTC 运算处理北京时间日期，避免浏览器时区和夏令时改变日期范围。 */
export function dashboardRange(days: number, now = new Date()) {
  const today = new Date(now.getTime() + 8 * 3600_000).toISOString().slice(0, 10)
  const start = new Date(`${today}T00:00:00Z`)
  start.setUTCDate(start.getUTCDate() - days + 1)
  return { from: start.toISOString().slice(0, 10), to: today }
}

export function comparison(current: number, previous: number) {
  if (previous === 0) {
    return current === 0 ? '持平' : '上期为 0'
  }
  const percent = ((current - previous) / previous) * 100
  return `${percent > 0 ? '+' : ''}${percent.toFixed(1)}%`
}

export function latencyValues(
  rows: DashboardDay[],
  metric: 'ttft' | 'total',
  percentile: 'P50' | 'P95',
) {
  return rows.map((row) => {
    const value = row[`${metric}${percentile}Ms`]
    return value == null ? null : value / 1000
  })
}
