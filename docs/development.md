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

`ai.selection.max-retries` 默认为 1，适用于 429、5xx 和非超时 I/O 异常。普通与流式请求超时均不重试同一候选：Chat 尝试备用模型，重排尝试下一候选或 `noop`，Embedding 保持知识库绑定并直接报错。每个候选最终失败只记录一次熔断失败；用户取消和线程中断不记为模型故障。
Embedding 与重排目前共用请求超时，文档批量向量化也受此值影响。批量任务若频繁超时，应结合实际批次大小和耗时调整。

流式有效内容包括实际转发给用户的非空正文和思考文本，角色事件、空 delta 与心跳不刷新内容时限。未输出时可透明切换，已输出正文或思考内容后发生超时则保留部分回答并结束，不清空或拼接备用回答。首内容预算从当前候选开始，备用候选获得自己的首内容预算，但所有候选共享剩余总预算。

总预算从每次 `ChatClient.stream` 开始，不包括前置检索；引用修复是独立调用，获得新的预算，因此此配置不是整次问答的截止时间。连接与响应头等待同时受原 HTTP 超时及剩余流式预算约束。日志记录超时阶段 `FIRST_CONTENT`、`IDLE`、`TOTAL` 或 `RESPONSE_HEADERS`、模型 ID 与耗时；现有 trace 保留失败尝试和切换原因。

SSE 仍立即发送任务级 `started`，前端取得 generation ID 后即可停止生成，首次内容前保持等待状态。连接每 15 秒发送注释心跳，候选切换不发送中间错误或 `reset`；引用修复和规范化继续使用 `reset`。全部候选失败时发送最终错误并关闭连接。修改超时配置后需重启后端。
