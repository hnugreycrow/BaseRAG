# Dashboard 统计口径

管理员首页 `/admin` 使用 ECharts 与 vue-echarts 展示按日趋势。ECharts 按需加载折线图、坐标轴、提示、图例与 SVG 渲染器；vue-echarts 管理 Vue 数据更新和容器尺寸变化。

- 问答请求量：每条 `rag_runs` 记录计一次，包括成功、失败、取消、中断和运行中状态。重新生成产生新的运行；运行内部重试不重复计数。请求在创建运行记录前被拒绝时不包含在统计中。
- 失败量：仅 `FAILED`；失败率为失败量除以全部运行量，零请求时显示 `—`。
- 耗时：仅 `COMPLETED` 且对应耗时非空的记录。首字耗时包括首次思考或正文。PostgreSQL `percentile_cont` 计算 P50/P95，毫秒四舍五入后由前端转换为秒。
- 所有图表按 `Asia/Shanghai` 的请求开始日期分组。7/30 天范围包含今天，今天尚未结束；空日期数量补零，耗时保持空值。
- 环比对照上一等长自然日周期。上期数量为零时不计算增长百分比；上期超出配置的链路保留期限时返回空值，隐藏环比。修改保留期限不能恢复已经删除的记录。

## 接口

`GET /api/observability/rag-runs/trend?from=2026-09-19&to=2026-09-25`

两个日期边界均包含，范围限制为 1–30 天，沿用观测控制器的管理员权限。返回 `days`、`previousRequestCount` 和 `previousFailureCount`。每个日期包含数量、两种耗时各自的有效样本量与 P50/P95。无需数据库迁移。

## 验证

- 前端测试覆盖北京时间日期边界、零分母、缺失耗时、请求竞态、失败重试及保留期显示。
- 后端服务与控制器测试覆盖日期校验、用户隔离、补齐空日期、上期数量和过期环比。
- `InfrastructureIntegrationTest#dashboardAggregatesBeijingDaysAndSuccessfulLatencyOnly` 验证真实数据库的日期边界、状态筛选与分位数，并回滚写入；按 README 准备测试基础设施后设置 `RAG_INTEGRATION=true` 执行。
