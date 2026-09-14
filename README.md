# BaseRAG

BaseRAG 是一个本地运行的单用户 RAG 知识问答系统。当前版本支持 Markdown 知识库、手动分块与向量化、问题改写与子问题拆分、分预算向量检索、候选去重与模型重排、多轮会话、SSE 流式回答和来源审计。后端采用 Java 21、Spring Boot 4、MyBatis-Plus、PostgreSQL/pgvector 与 RustFS，前端采用 Vue 3。

> 项目原名为 JAgent。为兼容已有本地数据，数据库、对象存储 bucket 和 Docker volume 的默认内部标识暂时保留为 `jagent`；这些标识不再作为产品名称使用。

## 当前范围

- 支持创建知识库并绑定配置中的 Embedding 模型。
- 仅支持不超过 5 MiB 的 UTF-8 Markdown；上传后由用户手动开始分块。
- 问答默认检索全部知识库中当前生效的 READY 文档，不支持用户指定检索范围。
- 支持历史摘要、独立问题改写、有限子问题拆分、安全意图路由、RRF 候选融合、三级去重、模型重排及失败降级。
- 支持结构化提示词、流式回答、模型候选回退、停止、一次引用修复、重试、重新生成和回答版本切换。
- 当前没有登录、多用户隔离、PDF、异步索引、关键词混合检索、Agent、可用的 MCP 工具或公网部署能力；MCP 内部安全框架默认关闭且允许列表为空。

当前产品范围和验收标准以 [REQUIREMENTS.md](REQUIREMENTS.md) 为唯一来源；技术实现以 [架构文档](docs/architecture.md) 为准。

## 启动

准备 Java 21 或更高版本、Node.js 22.12+、Docker Desktop。项目编译目标为 Java 21。

1. 在项目根目录复制配置并填入模型服务参数：

   ```powershell
   Copy-Item .env.example .env
   ```

   编辑 `.env`：填写 `application.yaml` 当前启用服务商所需的 API Key（如 `DEEPSEEK_API_KEY`、`BAILIAN_API_KEY`、`SILICONFLOW_API_KEY`）以及 `EMBEDDING_DIMENSIONS`。Chat 与 Embedding 的候选模型、顺序和超时都以 `backend/src/main/resources/application.yaml` 为准；不要在文档或代码中依赖某个固定模型名称。为兼容已有本地配置，暂时仍接受旧的 `CHAT_API_KEY` 与 `EMBEDDING_API_KEY`。

2. 启动存储：

   ```powershell
   docker compose --env-file .env -f deploy/compose.yml up -d
   docker compose --env-file .env -f deploy/compose.yml ps
   ```

   PostgreSQL 使用 5432，RustFS API 使用 9000、控制台使用 9001，均仅绑定本机。后端首次上传时初始化私有 bucket；原文件和数据库持久化到 Docker volumes。

3. 启动后端：

   ```powershell
   ./scripts/start-backend.ps1
   ```

   脚本仅按 KEY=VALUE 读取 .env，不执行其内容；值不要添加引号。也可以在 IDE 中配置相同环境变量后运行 BackendApplication。Spring Boot 本身不会自动加载 .env。后端默认 http://127.0.0.1:8080，Flyway 自动迁移。

4. 另开终端启动前端：

   ```powershell
   cd frontend
   npm ci
   npm run dev
   ```

   打开终端输出的本地地址（通常 http://127.0.0.1:5173）。在 /admin 进入默认知识库并上传 evaluation/datasets/employee-handbook.md，点击文档行的“开始分块”，再回到 /chat 提问“试用期员工能申请年假吗？”，点击回答下的“检索来源”核对原文与行号。

当前版本仅用于本地开发，不提供登录、用户权限或正式部署。默认 local profile；其他 profile 启动会拒绝运行。密钥不得提交到仓库。

后端主配置使用 backend/src/main/resources/application.yaml，按 `spring`、`server`、`mybatis-plus`、`ai`、`rag` 分组。`ai` 负责 Chat、Embedding、Rerank 的服务商、端点、候选模型、层级、超时和熔断参数；`rag` 负责分块、存储、子问题上限、路由、MCP 开关、去重、重排、检索通道和 RRF 预算。.env 仍只用于本地凭据注入和 Docker Compose，不替代 YAML 主配置。

## 前端页面

- /chat/:conversationId?：持久化问答界面，支持流式回答、刷新恢复、会话搜索/重命名/删除、停止、失败重试、最新回答重新生成、版本切换和来源审计。
- /admin：唯一后台入口，以三级表格管理知识库、文档和当前生效版本的分块；新建知识库时从 YAML 候选中选择向量模型，文档上传后由用户手动开始分块，READY 文档也可重新分块并替换旧索引。
- /admin/documents：兼容旧地址，自动跳转到 /admin。

前端名称为 BaseRAG，npm 包名为 baserag-web。会话、消息、回答版本和当时的 sources/citations/modelInfo 保存到 PostgreSQL；URL 保留当前会话，刷新后可以恢复。多轮问答使用持久化摘要、未摘要历史和最近 8 轮准备会话记忆，再执行问题规划、路由、检索、去重、重排、提示词组装和回答阶段。后台沿用本地开发接口，尚未加入登录或管理权限。

路由使用 HTML5 history。Vite 开发服务支持直接访问和刷新上述地址；将构建产物托管到其他 Web 服务时，需要将前端页面路由回退到 index.html，同时将 /api 请求转发到后端。

## 测试

日常测试不调用付费模型，也不需要 Docker：

```powershell
cd backend
./mvnw.cmd test
cd ../frontend
npm test
npm run build
```

真实 PostgreSQL/pgvector + RustFS 集成测试：

```powershell
# 根目录，先按启动步骤运行基础设施
docker compose --env-file .env -f deploy/compose.yml exec -T postgres createdb -U jagent jagent_test
cd backend
$env:RAG_INTEGRATION = 'true'
./mvnw.cmd test
Remove-Item Env:RAG_INTEGRATION
```

createdb 仅需首次执行；已存在时无需重建。测试默认连接独立 jagent_test 数据库、jagent-test bucket，使用 .env.example 中的本地演示凭据；若修改了凭据，需先在当前终端设置 POSTGRES_USER、POSTGRES_PASSWORD、RUSTFS_ACCESS_KEY、RUSTFS_SECRET_KEY、RUSTFS_ENDPOINT；数据库可通过 TEST_DB_URL 指定。测试不删除数据，只创建带随机 UUID 的测试记录。生成与 Embedding 在集成测试中是明确的 Mockito 替身，RustFS 文件写入及读回、PostgreSQL 和 pgvector 为真实服务。

真实模型验收另执行：

```powershell
# 后端已配置并运行；PowerShell 7+
./scripts/evaluate.ps1
```

脚本上传样例并逐条执行 20 个问题，保存实际响应及错误到 .artifacts/evaluation-*.json。它不将来源字符串命中自动等同于答案正确，人工检查回答忠实性与引用支持。再次执行会导入新文档；建议在干净的开发知识库验收。参考 [演示与验收](docs/demo.md)。

业务仿真基准使用独立知识库和显式检索范围：

```powershell
# 默认运行 40 道开发题，完成后删除临时知识库
./scripts/evaluate-business.ps1

# 运行 20 道留出测试题并保留知识库，便于后续复用
./scripts/evaluate-business.ps1 -Split test -KeepKnowledgeBase

# 复用已完成索引的评测知识库
./scripts/evaluate-business.ps1 -Split development -KnowledgeBaseId <UUID>
```

结果保存到 `.artifacts/business-evaluation-<split>-<timestamp>.json`，包含导入记录、原始回答、requestId、错误阶段、逐项必要证据命中、知识库范围校验、P50/P95 延迟和待人工评分字段。自动证据命中不是回答正确率，仍需人工检查答案正确性、引用支持和拒答。

## 接口

| 方法 | 路径 | 输入 / 输出 |
| --- | --- | --- |
| GET / POST | /api/knowledge-bases | 查询或新建知识库 |
| GET | /api/knowledge-bases/embedding-models | 查询 YAML 中配置的可选向量模型 |
| GET | /api/evaluation/config | local profile 下查询非敏感 RAG 评测参数快照 |
| GET / PATCH / DELETE | /api/knowledge-bases/{id} | 查询、重命名或删除知识库 |
| POST | /api/knowledge-bases/{id}/documents | multipart file → documentId、status、chunkCount |
| GET | /api/knowledge-bases/{id}/documents | 文档 id、name、status、errorCode、chunkCount、createdAt |
| PATCH / DELETE | /api/knowledge-bases/{id}/documents/{documentId} | 重命名或删除文档 |
| GET / POST | /api/knowledge-bases/{id}/documents/{documentId}/chunks | 查询分块 / 手动启动分块与向量化 |
| GET | /api/knowledge-bases/{id}/documents/{documentId}/chunks/{chunkId} | 分块完整内容与元数据 |
| POST | /api/questions | 单轮检索；JSON question 与可选 knowledgeBaseIds → answer、sources、citations、modelInfo |
| GET / POST | /api/conversations | 标题搜索/列表或创建会话 |
| GET / PATCH / DELETE | /api/conversations/{id} | 恢复、重命名或删除会话 |
| POST (SSE) | /api/conversations/{id}/messages | 创建用户消息并流式生成回答 |
| POST (SSE) | /api/conversations/{id}/messages/{assistantMessageId}/retry | 重试失败或已停止回答 |
| POST (SSE) | /api/conversations/{id}/messages/{assistantMessageId}/regenerate | 重新生成最后一轮成功回答 |
| POST | /api/conversations/{id}/generations/{generationId}/cancel | 幂等停止生成 |

SSE v1 事件为 started、delta、reset、complete、cancelled、error，每条数据都包含 schemaVersion。sources 包含 citationId、knowledgeBaseId、knowledgeBaseName、chunkId、documentId、versionId、documentName、heading、lineStart、lineEnd、similarity、content；与 citations、modelInfo 一起按回答版本保存。/api/questions 保留为阶段 1 评测兼容入口。

除 SSE 和 `204 No Content` 外，JSON 接口统一返回：

```json
{
  "code": "SUCCESS",
  "message": "请求成功",
  "data": {},
  "requestId": "请求追踪 ID"
}
```

失败响应保持对应的 HTTP 4xx/5xx 状态，`code` 为稳定的业务错误码，`message` 为可展示说明，`data` 通常为 `null`；参数校验失败时包含字段错误列表。`requestId` 同时写入 `X-Request-Id` 响应头。前端 HTTP 客户端会自动解包 `data`，流式事件协议不变。

## 限制与配置

- 只支持 UTF-8 .md / .markdown，最大 5 MiB、最多 1000 块。上传状态为 `UPLOADED`，手动分块失败后可在原文档上重试。
- 分块先按 Markdown 结构和自然句界识别语义块，再合并短章节；默认目标 1400、最小 500、最大 2000、超长段落重叠 180 字符。问题最多 2000 字符，最多规划 4 个子问题；每个子问题默认保留 20 个召回候选，去重后最多向 Reranker 提交 40 个候选，最终选择 10 条证据。聊天请求不发送 max_tokens；实际模型上下文上限仍由服务商决定。
- 知识库绑定的模型从配置中移除或维度变化时会拒绝分块和问答，不能静默混用向量。恢复原配置可继续使用；需要换模型时保留旧数据并新建知识库后重新分块。该阶段没有自动迁移命令。
- 失败版本可能保留 RustFS 文件，进程中断可能留 PROCESSING 记录；都不参与检索。通过管理界面删除文档或知识库时会清理其数据库记录，并尽力清理 RustFS 原文件。
- 同一文件重复上传会产生新文档；尚无 PDF、文件级重复检测、版本替换或权限。当前会话仍是 local 单用户数据空间。
- 当前只有向量检索通道；RRF 已作为稳定融合机制使用，但关键词通道尚未实现。Reranker 默认开启，失败、超时或 noop 时按确定性融合分继续回答。
- MCP 注册、Schema 校验、只读白名单、超时和输出截断代码已经存在，但默认关闭、允许列表为空且没有面向用户的工具配置，不构成当前产品能力。
- 本机已有 5432/9000 端口占用时，修改 Compose 映射及对应连接地址。
- 默认数据库与 RustFS 密码只是本地示例，应在个人 .env 中修改。已有 PostgreSQL volume 修改密码不会自动改变数据库用户密码。

当前范围见 [REQUIREMENTS.md](REQUIREMENTS.md)，架构和目录约束见 [architecture.md](docs/architecture.md) 与 [backend-structure.md](docs/backend-structure.md)，历史决策见 [decisions.md](docs/decisions.md)。实际执行的检查见 [validation.md](docs/validation.md)，历史阶段结论见 [阶段 1 验收分析](docs/phase-1-acceptance.md)。[阶段 2 计划](docs/phase-2-plan.md) 是部分已落地的历史路线图，不覆盖当前需求基线。


