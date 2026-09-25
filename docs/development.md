# 开发与配置

## 基础设施集成测试

以下命令从仓库根目录执行。

基础设施集成测试需先启动 Docker 服务，并首次创建独立的 `baserag_test` 数据库：

```powershell
docker compose --env-file .env -f deploy/compose.yml exec -T postgres createdb -U baserag baserag_test
cd backend
$env:RAG_INTEGRATION = 'true'
./mvnw.cmd test
Remove-Item Env:RAG_INTEGRATION
```

数据库已存在时跳过 `createdb`。命令中的 `baserag` 是示例数据库用户；修改示例凭据后，需为集成测试设置相应环境变量。测试使用独立数据库和对象存储 bucket，不调用真实生成模型。

## 模型与检索超时

默认配置面向交互式问答，各项单位为毫秒：

| 配置                                 | 默认值 | 作用范围                                           |
| ------------------------------------ | -----: | -------------------------------------------------- |
| `ai.connect-timeout-ms`              |   5000 | 模型 HTTP 建立连接                                 |
| `ai.request-timeout-ms`              |  30000 | Embedding、重排的单次 HTTP 请求                    |
| `ai.chat.tiers.standard.timeout-ms`  |  30000 | 对话模型单次 HTTP 请求，不等于完整流式回答总时限   |
| `ai.stream.first-content-timeout-ms` |  10000 | 每个候选等待首段有效内容，内部重试和退避不重置     |
| `ai.stream.idle-timeout-ms`          |  15000 | 首段有效内容后的最大输出空闲时间                   |
| `ai.stream.total-timeout-ms`         | 180000 | 一次流式模型生成的总预算，覆盖备用切换、重试和退避 |
| `rag.pipeline.routing.timeout-ms`    |  10000 | 意图判断等待预算，超时回退知识检索                 |
| `rag.search.channels.timeout-ms`     |  15000 | 每个子问题的数据库召回预算，不含 Embedding         |
| `rag.pipeline.mcp.timeout-ms`        |   3000 | MCP 工具执行，目前默认关闭                         |

`ai.selection.max-retries` 默认为 1，适用于正常态下的 5xx 和非超时 I/O 异常；半开探测不重试。429 直接进入限流冷却并尝试后续候选。普通与流式请求超时均不重试同一候选：Chat 尝试备用模型，重排尝试下一候选或 `noop`，Embedding 保持知识库绑定并直接报错。每个候选最终服务故障只记录一次熔断失败；用户取消、线程中断、下游回调异常及流式总预算耗尽不记为模型故障。
Embedding 与重排目前共用请求超时，文档批量向量化也受此值影响。批量任务若频繁超时，应结合实际批次大小和耗时调整。

流式有效内容包括实际转发给用户的非空正文和思考文本，角色事件、空 delta 与心跳不刷新内容时限。未输出时可透明切换，已输出正文或思考内容后发生超时则保留部分回答并结束，不清空或拼接备用回答。首内容预算从当前候选开始，备用候选获得自己的首内容预算，但所有候选共享剩余总预算。

总预算从每次 `ChatClient.stream` 开始，不包括前置检索；引用修复是独立调用，获得新的预算，因此此配置不是整次问答的截止时间。连接与响应头等待同时受原 HTTP 超时及剩余流式预算约束。日志记录超时阶段 `FIRST_CONTENT`、`IDLE`、`TOTAL` 或 `RESPONSE_HEADERS`、模型 ID 与耗时；现有 trace 保留失败尝试和切换原因。

SSE 仍立即发送任务级 `started`，前端取得 generation ID 后即可停止生成，首次内容前保持等待状态。连接每 15 秒发送注释心跳，候选切换不发送中间错误或 `reset`；引用修复和规范化继续使用 `reset`。全部候选失败时发送最终错误并关闭连接。修改超时配置后需重启后端。

### 模型熔断与恢复

熔断按模型 ID、地址、接口和模型名称隔离，状态仅保存在当前应用进程。连续服务故障达到 `ai.selection.failure-threshold`（默认 2）后熔断，经过 `ai.selection.open-duration-ms`（默认 30000）进入半开。半开只允许一个请求探测，其他请求直接尝试备用候选；探测成功恢复，失败重新冷却。探测取消或其他不计数结果会释放名额，允许下一请求探测。各应用实例独立计数。

每次候选调用持有独立周期许可，内部重试共享一次计数。旧周期的迟到成功或失败不能覆盖新状态；重试退避结束后再次检查许可。冷却使用单调时钟，避免系统时间调整影响恢复。

连接失败、上游 5xx、连接/首内容/空闲超时，以及无效 JSON、缺失结束标记、无效 Chat/Embedding/Rerank 协议结果计入失败。仅在模型客户端完成响应校验后清空失败次数。生成长度限制、内容过滤等业务结果不计入服务故障，也不重置已有计数。400/422 不计数；401/403 不计数并记录模型 ID 与状态码，供排查配置或权限。

429 不占用失败次数，按 `Retry-After`（秒数或 HTTP 日期）单独冷却；缺失/非法值使用 `open-duration-ms`，有效值限制为 1 毫秒到 24 小时。冷却期间直接返回限流错误，不等待、不重试同一候选。状态切换日志包含模型 ID、前后状态和原因，不记录 API Key 或供应商响应体。
