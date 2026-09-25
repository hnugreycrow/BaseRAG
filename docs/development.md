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

| 配置 | 默认值 | 作用范围 |
| --- | ---: | --- |
| `ai.connect-timeout-ms` | 5000 | 模型 HTTP 建立连接 |
| `ai.request-timeout-ms` | 30000 | Embedding、重排的单次 HTTP 请求 |
| `ai.chat.tiers.standard.timeout-ms` | 30000 | 对话模型单次 HTTP 请求，不等于完整流式回答总时限 |
| `rag.pipeline.routing.timeout-ms` | 10000 | 意图判断等待预算，超时回退知识检索 |
| `rag.search.channels.timeout-ms` | 15000 | 每个子问题的数据库召回预算，不含 Embedding |
| `rag.pipeline.mcp.timeout-ms` | 3000 | MCP 工具执行，目前默认关闭 |

`ai.selection.max-retries` 默认为 1，即首次请求失败后最多重试一次；候选模型切换还会增加总耗时。
Embedding 与重排目前共用请求超时，文档批量向量化也受此值影响。批量任务若频繁超时，应结合实际批次大小和耗时调整。
上述配置尚未提供整次问答截止时间或流式首内容、空闲读取的独立超时。修改配置后需重启后端。
