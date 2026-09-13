# JAgent 系统架构

## 1. 文档目的

本文描述 JAgent 当前已经实现的系统架构，包括：

- 系统边界
- 技术栈
- 模块职责
- 数据存储
- 文档处理流程
- RAG 问答流程
- 多轮会话与流式生成
- 部署方式
- 已知限制

本文描述当前实现，不把计划中的功能写成已经具备的能力。产品范围和验收标准以 `../REQUIREMENTS.md` 为唯一来源；本文只解释这些能力如何实现。

相关文档：

- 项目需求：`../REQUIREMENTS.md`
- 架构决策：`decisions.md`
- 后端目录规范：`backend-structure.md`
- 历史阶段路线图：`phase-2-plan.md`（部分能力已经落地，不定义当前范围）

---

## 2. 系统定位与当前范围

JAgent 是一个本地运行的 RAG 知识问答系统。

当前支持：

- 创建、修改和删除知识库
- 为知识库选择并绑定 Embedding 模型
- 上传 UTF-8 Markdown 文档
- 手动触发分块与向量化
- 使用 pgvector 检索全部知识库中的 READY 生效版本
- 结合历史摘要和最近对话改写独立检索问题
- 调用 OpenAI Compatible 模型生成回答
- 返回来源、引用和模型信息
- 持久化多轮会话
- 使用 SSE 流式返回回答
- 停止、失败重试、重新生成和回答版本切换
- 普通 JSON 统一响应、请求 ID 和全局异常转换

当前不支持：

- 用户登录和多用户数据隔离
- PDF、TXT、DOCX、OCR
- 异步文档处理任务
- 用户选择一个或多个知识库作为检索范围
- Redis 或消息队列
- 混合检索和 Reranker
- MCP、Tool Calling 和 Agent
- 公网生产部署

---

## 3. 系统上下文

​```mermaid
flowchart LR
    User[用户浏览器]
    Frontend[Vue 前端]
    Backend[Spring Boot 后端]
    PostgreSQL[(PostgreSQL + pgvector)]
    RustFS[(RustFS / S3)]
    ChatModel[Chat Model API]
    EmbeddingModel[Embedding API]

    User --> Frontend
    Frontend -->|REST / SSE| Backend
    Backend --> PostgreSQL
    Backend --> RustFS
    Backend --> ChatModel
    Backend --> EmbeddingModel
​```

前端只与后端通信。模型密钥、数据库连接和对象存储凭据只由后端持有。

---

## 4. 技术栈

### 后端

- Java 21
- Spring Boot 4.1.1
- Spring MVC
- MyBatis-Plus 3.5.17
- Flyway
- PostgreSQL JDBC
- AWS SDK for Java S3 Client
- Maven Wrapper

### 前端

- Vue 3.5
- TypeScript 6
- Vite 8
- Vue Router 5
- Element Plus
- Axios
- Vitest

### 基础设施

- PostgreSQL 17
- pgvector 0.8.6
- RustFS S3 兼容对象存储
- Docker Compose

### AI 接入

系统不依赖 Spring AI 或 LangChain4j，而是通过自有 HTTP 客户端直接调用
OpenAI Compatible Chat 和 Embedding 接口。

模型候选、服务商、超时、重试和熔断参数由
`backend/src/main/resources/application.yaml` 配置。

---

## 5. 仓库结构

​```text
jagent/
├── backend/                 # Spring Boot 后端
├── frontend/                # Vue 前端
├── deploy/                  # Docker Compose
├── docs/                    # 架构、决策和验收文档
├── evaluation/              # 固定评测数据集
├── scripts/                 # 启动和评测脚本
├── REQUIREMENTS.md
├── README.md
└── .env.example
​```

---

## 6. 后端模块划分

后端采用“按业务能力分包，包内使用 Controller / Service / Mapper 等直白职责目录”的单体模块化结构。

​```text
com.hnu.backend
├── knowledgebase
│   ├── controller
│   ├── dto
│   ├── vo
│   ├── service
│   ├── entity
│   └── mapper
├── document
│   ├── controller
│   ├── dto
│   ├── vo
│   ├── service
│   ├── entity
│   ├── mapper
│   ├── parser
│   └── storage
├── rag
│   ├── controller
│   ├── dto
│   ├── vo
│   ├── service
│   ├── model
│   ├── mapper
│   └── support
├── conversation
│   ├── controller
│   ├── dto
│   ├── vo
│   ├── service
│   ├── entity
│   └── mapper
├── model
│   ├── client
│   ├── config
│   └── http
├── configuration
└── shared
​```

模块职责：

| 模块 | 职责 |
| --- | --- |
| knowledgebase | 知识库 CRUD、Embedding 模型绑定 |
| document | 上传、存储、分块、向量化、文档版本切换 |
| rag | 检索、上下文构建、生成、引用校验和单轮兼容问答 |
| conversation | 会话、消息、回答版本、SSE 和生成状态 |
| model | Chat、Embedding、模型选择、重试和熔断 |
| shared | 统一响应、全局异常、请求 ID、公共持久化能力 |

Controller 只负责 HTTP 参数和响应；业务编排位于 service；数据库访问位于 mapper，模型与对象存储访问分别位于 client/http 和 storage。DTO、VO 与 Entity 分目录，跨模块只调用对方 Service。

---

## 7. HTTP 接口基础约定

Controller 使用 RESTful 资源路径并返回具体 DTO/VO，不在每个方法中重复构造外层响应。`ApiResponseAdvice` 对普通 JSON 返回值统一包装：

```json
{
  "code": "SUCCESS",
  "message": "请求成功",
  "data": {},
  "requestId": "请求追踪 ID"
}
```

HTTP 状态码继续表达成功或失败。`ApiException` 携带稳定的业务错误码和状态码；`GlobalExceptionHandler` 统一转换业务异常、参数校验、请求解析、文件大小、404、405、415 和未知异常。参数校验失败时，`data` 包含字段错误列表；未知异常不向客户端暴露内部细节。

`RequestIdFilter` 为每个请求生成 ID，写入请求属性、MDC、响应体和 `X-Request-Id` 响应头。SSE 保持版本化事件协议，`204 No Content` 保持空响应体，二者不使用 JSON 外层包装。前端 Axios 公共层自动解包 `data`，业务 API 客户端继续使用具体响应类型。

---

## 8. 核心数据模型

​```mermaid
erDiagram
    KNOWLEDGE_BASE ||--o{ DOCUMENT : contains
    DOCUMENT ||--o{ DOCUMENT_VERSION : has
    DOCUMENT_VERSION ||--o{ DOCUMENT_CHUNK : produces

    CONVERSATION ||--o{ MESSAGE : contains
    MESSAGE ||--o{ GENERATION_ATTEMPT : generates
​```

### 知识库与文档

- `knowledge_bases`：知识库及绑定的 Embedding 模型、维度。
- `documents`：逻辑文档，`active_version_id` 指向当前生效版本。
- `document_versions`：原始文件、处理状态、解析器和模型快照。
- `document_chunks`：文本片段、来源行号和向量。

只有文档当前生效且状态为 `READY` 的版本可以参与检索。

### 会话

- `conversations`：标题、结构化历史摘要和摘要游标。
- `messages`：用户消息、Assistant 回答版本、状态和来源快照。
- `generation_attempts`：主生成、候选切换和引用修复尝试。

旧回答保存当时的 sources、citations 和 modelInfo，不依赖文档当前状态。

---

## 9. 文档处理流程

​```mermaid
sequenceDiagram
    participant UI as 管理端
    participant API as DocumentController
    participant Service as DocumentService
    participant S3 as RustFS
    participant DB as PostgreSQL
    participant Embedding as Embedding API

    UI->>API: 上传 Markdown
    API->>Service: upload()
    Service->>Service: 校验大小、扩展名和 UTF-8
    Service->>S3: 保存原始文件
    Service->>DB: 创建 Document 和 UPLOADED Version
    Service-->>UI: 返回文档状态

    UI->>API: 手动开始分块
    API->>Service: createChunks()
    Service->>DB: 原子认领为 PROCESSING
    Service->>S3: 读取原文件
    Service->>Service: Markdown 语义分块
    Service->>Embedding: 批量生成向量
    Service->>DB: 事务替换分块并切换 active_version
    Service-->>UI: 返回 READY 或 FAILED
​```

当前处理是同步的，单实例最多并行处理两份文档。进程中断后可能留下
`PROCESSING` 记录，持久化后台任务属于后续改造范围。

---

## 10. RAG 检索与回答流程

​```mermaid
flowchart TD
    Q[用户问题]
    Rewrite[结合历史生成独立检索问题]
    Bindings[读取 READY 文档的模型组合]
    Embed[按模型生成 Query Embedding]
    Search[pgvector 精确余弦检索]
    Merge[合并并选取全局 Top K]
    Context[构建带编号 JSON 上下文]
    Generate[流式调用 Chat Model]
    Validate[校验引用编号]
    Persist[保存回答及来源快照]
    SSE[向前端发送 SSE]

    Q --> Rewrite --> Bindings --> Embed --> Search
    Search --> Merge --> Context --> Generate
    Generate --> Validate
    Validate -->|合法| Persist
    Validate -->|非法| Repair[引用修复一次]
    Repair --> Persist
    Persist --> SSE
​```

检索规则：

- 只检索 READY 的当前生效文档版本。
- 当前没有用户选择或授权范围，检索覆盖本地全部知识库。
- 知识库绑定固定的 Embedding 模型和维度。
- 不同模型分别生成问题向量并检索，再合并为全局 Top K。
- 非首轮问题先结合历史上下文改写；改写失败时回退原问题。
- 当前使用精确余弦检索，没有 HNSW 或 IVFFlat。
- sources 是实际送入模型的上下文。
- citations 是回答中经过编号校验的来源子集。

---

## 11. 多轮会话与流式生成

浏览器通过 `POST` 请求建立 SSE 响应。

SSE v1 事件：

- `started`
- `delta`
- `reset`
- `complete`
- `cancelled`
- `error`

后端使用虚拟线程执行生成任务，并按字符数或时间间隔保存生成检查点。

上下文由三部分组成：

1. 已持久化的历史摘要
2. 尚未进入摘要的旧消息
3. 最近 8 个完整问答轮次

当至少 4 个完整轮次移出最近窗口时，系统增量更新摘要。摘要失败不会推进摘要游标。

同一会话同一时刻只允许一个生成；不同会话可以并行。当前会话锁和运行中任务保存在进程内，因此该并发约束仅适用于单实例部署。

---

## 12. 前端架构

主要页面：

- `/chat/:conversationId?`：多轮聊天和历史恢复
- `/admin`：知识库、文档和分块三级管理
- `/admin/documents`：兼容跳转地址

前端职责：

- `api/`：REST 和 SSE 客户端
- `stores/`：知识库与会话状态
- `views/`：页面级编排
- `components/`：来源抽屉等可复用组件
- `router/`：页面路由

来源内容按普通文本展示，不把文档内容作为可信 HTML 渲染。
普通 REST 响应只在 `api/http.ts` 解包统一外层，页面和 store 不感知传输层包装；SSE 客户端继续直接解析版本化事件。

---

## 13. 配置与部署

本地部署包含：

- Vue Vite 开发服务
- Spring Boot 应用
- PostgreSQL/pgvector
- RustFS
- 外部 Chat 和 Embedding API

数据库和 RustFS 仅绑定 `127.0.0.1`。后端当前也只允许 local profile，
不具备公网生产部署条件。

配置原则：

- `application.yaml` 保存非敏感默认参数。
- `.env` 保存本地凭据，禁止提交。
- `.env.example` 只提供变量名和安全示例值。
- Flyway 负责数据库结构迁移。
- 不修改已经执行过的历史迁移文件，应新增版本迁移。

---

## 14. 质量属性

### 一致性

- 文档分块替换和生效版本切换在数据库事务中完成。
- 数据库外键保证文档、版本、知识库和向量维度的一致性。
- 重分块失败时继续保留旧 READY 索引。

### 可审计性

- 回答保存来源、引用、检索问题和模型快照。
- 文档后续删除或重新分块不会修改旧回答的来源快照。
- 每个普通 HTTP 请求通过响应体、响应头和日志共享同一 request ID。

### 可恢复性

- 流式回答定期保存检查点。
- 应用启动时将遗留的生成中状态转换为可重试失败。
- 文档处理暂未实现可靠的重启恢复。

### 安全性

- 密钥只能通过环境变量注入。
- 日志不得记录密钥或完整敏感正文。
- 当前没有身份认证，不能用于多用户或公网环境。

---

## 15. 当前限制与演进方向

| 当前限制 | 后续方向 |
| --- | --- |
| 仅支持 Markdown | 增加文本型 PDF |
| 同步文档处理 | PostgreSQL 持久化后台任务 |
| 单用户、全库检索 | 身份认证、所有权和授权后的检索范围 |
| 精确向量检索 | 建立基线后评估混合检索和 Reranker |
| 进程内生成锁 | 多实例部署前改为数据库租约或分布式协调 |
| 没有生产运维能力 | 健康检查、指标、备份恢复和内部部署 |
