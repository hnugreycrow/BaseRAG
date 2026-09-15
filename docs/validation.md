# 本次实施验证记录

## 模块化 RAG 流水线（2026-09-14）

- 后端日常测试执行 120 项，111 项通过，9 项基础设施测试按 `RAG_INTEGRATION` 环境条件跳过，0 失败、0 错误；Spotless 格式校验通过。
- 单元测试覆盖会话记忆、问题规划降级、三类意图路由、MCP 注册与安全执行、分子问题预算、RRF 合并、最低覆盖、三级去重、模型重排及确定性降级。
- 提示词测试覆盖不可信数据区隔离、稳定 S/T 编号、无证据决策和不携带错误回答的引用修复输入。
- 回答阶段测试覆盖流式模型适配、provider fallback、S/T 白名单校验、只修复一次、修复前取消，以及无证据时不调用模型。
- 会话回归测试确认引用修复只执行一次 `PENDING → STREAMING`，首次非法 generation attempt 转为 `FAILED/INVALID_CITATIONS`，修复尝试记录为 `CITATION_REPAIR`，最终只保存修复后的正文。
- 本轮没有启用真实模型、PostgreSQL、pgvector 或 RustFS，因此只能证明日常逻辑与协议回归通过，不能替代真实基础设施集成和回答质量评测。

## 持久化会话与 SSE（2026-09-13）

- 后端日常测试 32 项全部通过；启用 RAG_INTEGRATION 后 PostgreSQL/pgvector/RustFS 集成测试 32 项全部通过。
- Flyway 在既有测试库上从 V2 升级到 V3，并在空数据库上从 V1 完整迁移到 V3。
- PostgreSQL 实测通过会话、消息、JSONB sources/citations/modelInfo 快照、摘要乐观更新和级联删除。
- 模型协议测试覆盖 stream=true、delta、DONE、finish_reason，并确认同步与流式请求都没有 max_tokens。
- 摘要测试覆盖 8 轮窗口/4 轮批次、覆盖游标不重复总结，以及摘要失败时保留原始历史。
- 隔离测试库的实际 HTTP 链路观察到 started → delta → delta → complete；失败后 retry 创建新回答版本，刷新查询同时保留旧失败版本和新成功版本。
- 前端 Vitest 2 项通过，覆盖碎片化 UTF-8 SSE 和回答版本切换；TypeScript 与 Vite 生产构建通过。

## 首轮真实模型评测（2026-09-13 16:58）

- 已生成 `.artifacts/evaluation-20260913-165858.json`，记录的生成模型为 qwen-plus-latest。
- 20 题中 18 题获得有效回答，q011、q012 返回 502；系统/API 成功率为 90%。
- 16 道有答案题中，14 道完整返回并召回必要证据；q015 虽召回证据，但把“超过 2 个工作日”错误解释为“正好两天即可催办”。按失败题计 0，回答正确为 13/16，必要证据项命中为 19/22。
- 4 道无答案题全部正确拒答，成功响应中的引用编号均可追踪到返回来源。
- 测试库包含两份重复员工手册和无关 Markdown 文档；18 个成功响应的 72 个来源位置中有 36 个来自无关文档，结果不能代表干净数据集上的检索精度。
- 本轮未达到阶段 1 建议关闭门槛。详细分析见 [phase-1-acceptance.md](phase-1-acceptance.md) 和 [evaluation-20260913-165858-review.md](evaluation-20260913-165858-review.md)。

## AI 服务商配置重构验证（2026-09-13）

- AI 配置已按 provider、endpoint、candidate、tier 和 selection 分层，当前仅保留百炼 qwen-plus-latest 与 SiliconFlow Qwen/Qwen3-Embedding-8B。
- 对话与 Embedding HTTP 协议测试覆盖实际端点拼接、认证头、dimensions 请求字段、响应解析、429 重试、超时与无效响应。
- 后端日常测试 23 项执行，17 项通过、6 项外部基础设施测试按环境条件跳过，0 失败、0 错误。
- 使用本地 PostgreSQL/RustFS 实际启动 Spring Boot，Flyway 与配置绑定成功；当时的 GET /api/knowledge-bases 返回默认知识库后正常关闭。当前版本已取消自动创建，新账号的列表初始为空。
- 前端 TypeScript 检查与 Vite 生产构建通过，并已适配回答中的 modelInfo。未在本轮调用付费模型，真实模型质量仍需单独验收。

## YAML 与前端分区改版验证（2026-09-12）

- 后端配置迁移为 `application.yaml`，清理旧编译输出后，启用基础设施集成测试的 23 项后端测试全部通过。首次运行时 Docker 未启动，恢复服务后通过。
- 前端改名 BaseRAG（包名 `baserag-web`），分为 `/chat`、`/admin` 和 `/admin/documents`；最终 TypeScript 检查与 Vite 构建通过。
- 浏览器历史验证覆盖管理页面上传 Markdown、文件搜索与状态筛选、提问、实际检索上下文、来源展开和 Escape 关闭；上传/手动分块的新交互需按本轮说明复验。
- 390 × 844 测试视口下聊天页面无横向溢出；检查后补充了关闭状态侧栏的可见性样式，该小项仅完成构建验证。
- 本轮浏览器成功链路仍使用明确标记的 HTTP 模型替身，PostgreSQL、pgvector 和 RustFS 为真实服务，不算真实模型验收。
- `.env` 由 `scripts/start-backend.ps1` 加载为进程环境变量；IDEA 直接运行或直接调用 Maven 不会自动执行此加载步骤。

## 首次交付验证记录

日期：2026-09-12。操作系统 Windows，实际运行 JDK 25，编译目标 Java 21。

## 已执行

| 检查 | 结果 |
| --- | --- |
| Maven Wrapper 编译 | 通过；修复原 Wrapper 的 PowerShell Target 空值错误 |
| 后端测试 | 23 项通过，0 失败、0 错误、0 跳过（启用 RAG_INTEGRATION=true） |
| 日常逻辑与 HTTP 单元测试 | 17 项通过 |
| PostgreSQL / pgvector / RustFS 集成测试 | 6 项通过，实际数据库 baserag_test，bucket baserag-test |
| 前端 TypeScript 检查与 Vite 构建 | 通过 |
| 后端 JAR 打包 | 通过，生成 backend/target/backend-0.0.1-SNAPSHOT.jar |
| Docker Compose | PostgreSQL 17.11 / pgvector 0.8.6 与 RustFS 1.0.0-rc.4-glibc 启动成功 |
| 正常应用页面 | 空知识库显示资料不足；手动分块时缺少模型配置显示明确错误和 requestId |
| 隔离 UI 端到端检查 | Markdown 上传后需手动分块；返回真实检索上下文；回答引用标注及来源展开正常 |
| 布局 | 桌面三栏；390 像素测试视口下单栏，读取到的 contentWidth 与 clientWidth 均为 375，无横向溢出 |
| 20 题脚本流程 | 在测试 HTTP 模型替身上执行 20 次、0 接口错误，输出 JSON 成功；不是回答质量评测 |

## 自动化测试覆盖

- 中文标题与来源行号、CRLF、围栏内伪标题、空文档、未闭合围栏、超长内容重叠及 Unicode 代理对。
- Embedding 按服务商 index 重排、数量和维度错误、非法索引、零向量、非数字和数值溢出。
- 实际 HTTP 请求路径、认证头、非流式聊天协议、Embedding 批量响应、429 有限重试、超时、错误 JSON、截断回答。
- 上下文预算、完整片段与去重；无候选不生成；非法引用修复一次，仍非法则报错。
- 真实文件上传与 S3 读回字节一致；片段原文及问答返回来源与数据库一致。
- 真实 pgvector 余弦排序；全库检索按模型与维度分组并合并全局 Top K，模型和维度不会混用。
- Embedding 失败不产生有效版本或片段。
- 数据库第二个片段写入失败时，第一个片段随事务回滚，文档状态 FAILED、有效版本为空。

## 首次交付时尚未完成的验证（历史记录）

首次交付时，真实生成模型和真实 Embedding 联调尚未执行：当时没有对应服务地址、模型名称、向量维度与凭据。单元测试和基础设施集成测试中的模型为测试替身；浏览器成功链路使用独立的本地 HTTP 测试服务，回答明确标注测试替身。

2026-09-13 16:58 已补做首轮真实模型评测，但结果存在 2 个 502 和 1 个语义错误，仍不能宣称质量达标。当前结论以上方“首轮真实模型评测”和阶段 1 验收分析为准。

当前本地容器和正常前后端可供继续使用；测试数据库与测试 bucket 保留用于复核，未删除用户数据。测试模型服务与隔离 UI 进程在检查后停止。

