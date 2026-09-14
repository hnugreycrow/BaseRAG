# 后端目录结构规范

## 1. 设计结论

后端保持单个 Spring Boot / Maven 模块，采用“按业务能力分包，包内使用直白职责目录”的模块化单体结构。RAG 内部按流水线阶段聚合相关模型、端口和实现，其他模块继续使用 Controller / Service / Mapper 等职责目录。当前业务规模不使用 `api/application/domain/infrastructure` 四层模板，避免只有一两个文件的抽象目录。

顶层模块围绕 RAG 主链路划分：知识库、文档与索引、RAG 检索生成、会话交付、模型接入。Controller / Service / Mapper 分层仍是强制规则，DTO、VO、Entity 必须分离。

## 2. 当前目录

```text
com.hnu.backend
├─ BackendApplication.java
├─ shared/
│  ├─ web/                    # 统一响应、ResponseBodyAdvice、请求 ID
│  ├─ error/                  # 业务异常与全局异常处理
│  └─ persistence/            # 通用 MyBatis 类型处理
├─ knowledgebase/
│  ├─ controller/
│  ├─ dto/
│  ├─ vo/
│  ├─ service/
│  ├─ entity/
│  └─ mapper/
├─ document/
│  ├─ controller/
│  ├─ dto/
│  ├─ vo/
│  ├─ service/
│  ├─ entity/
│  ├─ mapper/
│  ├─ parser/
│  └─ storage/
├─ rag/
│  ├─ controller/             # /api/questions 兼容入口
│  ├─ dto/
│  ├─ vo/
│  ├─ memory/                 # 会话记忆读取
│  ├─ planning/               # 问题重写与子问题拆分
│  ├─ routing/                # 意图识别与安全路由
│  ├─ execution/              # 冻结预算与分子问题并发执行
│  ├─ mcp/                    # MCP 注册、校验与执行
│  ├─ retrieval/              # 向量检索、RRF 融合及 MyBatis Mapper
│  ├─ deduplication/          # 精确、正文与相邻重叠去重
│  ├─ rerank/                 # 模型重排、证据截取与失败降级
│  ├─ prompt/                 # 结构化提示词与资源加载
│  ├─ answer/                 # 回答模型端口、流式生成与引用校验
│  └─ support/                # 跨阶段通用值处理
├─ conversation/
│  ├─ controller/
│  ├─ dto/
│  ├─ vo/
│  ├─ service/
│  ├─ entity/
│  └─ mapper/
├─ model/
│  ├─ client/                 # Chat、Embedding 与 Rerank 能力
│  ├─ http/                   # OpenAI Compatible HTTP 实现
│  └─ config/
└─ configuration/             # 跨模块运行参数和本地启动限制
```

资源目录按消费模块归档：

```text
src/main/resources
├─ application.yaml
├─ db/migration/
├─ prompts/                   # 各模型阶段的 UTF-8 Markdown 提示词
└─ mapper/
   ├─ knowledgebase/
   ├─ document/
   └─ rag/
      └─ retrieval/
```

测试目录镜像主代码包；真实 PostgreSQL、pgvector 与 RustFS 测试集中在 `integration/`。

## 3. 模块职责

| 模块 | 职责 |
| --- | --- |
| `knowledgebase` | 知识库 CRUD、Embedding 模型绑定 |
| `document` | 原文件、版本、分块、向量化和存储清理 |
| `rag` | 会话记忆、问题规划、路由、执行预算、检索融合、去重、重排、提示词、回答和引用校验 |
| `conversation` | 会话、消息、回答版本、SSE 和生成状态 |
| `model` | Chat/Embedding/Rerank 模型配置、调用、重试和熔断 |
| `shared` | 与具体业务无关的 Web、异常和持久化基础能力 |

## 4. 依赖规则

```text
Controller → Service → Mapper
                    → Client / Storage
RAG Stage → 所属阶段 Port / Model → 外部 Client / Adapter
Mapper → Entity
```

- Controller 只处理 HTTP、DTO 校验和 VO 输出，不编排业务流程。
- Service 负责业务规则、事务和跨模块协作，不得依赖 Controller 或 DTO。
- Mapper 只负责数据库访问，不包含业务判断。
- RAG 阶段包拥有该阶段的模型、端口和实现；跨阶段依赖按 memory → planning → routing → execution → deduplication → rerank → prompt → answer 的流水线方向流动。
- Entity 只描述持久化数据，不直接作为 API 响应。
- 非持久化内部数据使用模块内 `model`，不能随意放入 `shared`。
- 跨模块调用对方 Service；禁止直接访问其他模块 Mapper。
- 模型 HTTP、对象存储等外部边界可以定义接口；单实现业务 Service 不创建空转的 `Impl`。
- MyBatis XML 的目录、`namespace` 和 Java Mapper 包名必须保持一致。

## 5. 命名规范

- HTTP 输入：`CreateKnowledgeBaseRequest`、`MessageRequest`。
- HTTP 输出：`KnowledgeBaseResponse`、`DocumentResponse`。
- 持久化实体：`KnowledgeBase`、`DocumentVersion`、`Message`。
- 业务服务：`RagService`、`RetrievalService`、`DocumentService`。
- 外部能力：`ChatClient`、`EmbeddingClient`、`FileStorage`。
- 不使用全局 `dto`、`vo`、`entity`、`utils` 或 `common` 目录。

## 6. 后续结构收口

目录迁移不改变 REST 路径、数据库字段或 SSE 协议。下一步只在测试保护下处理两个热点：

1. 将 `DocumentService` 的同步索引流程提取为 `DocumentIndexService`。
2. 将 `ConversationService` 拆为会话 CRUD、生成协调和 SSE 适配，避免 Service 长期直接承担所有传输与并发职责。

只有出现独立部署、不同团队独立发布或稳定模块边界等真实需求后，才评估 Maven 多模块或微服务。
