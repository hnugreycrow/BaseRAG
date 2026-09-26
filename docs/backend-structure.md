# 后端目录结构规范

## 1. 设计结论

后端保持单个 Spring Boot / Maven 模块，采用“按业务能力分包，包内使用直白职责目录”的模块化单体结构。RAG 内部按流水线阶段聚合相关模型、端口和实现，其他模块继续使用 Controller / Service / Mapper 等职责目录。不为每个模块套用 `api/application/domain/infrastructure` 四层模板；存在实际跨模块依赖时，通过模块内 `api/` 定义窄接口，由顶层 `application/service/` 编排跨模块事务。

顶层模块围绕 RAG 主链路划分：知识库、文档与索引、RAG 检索生成、会话交付、模型接入。Controller / Service / Mapper 分层仍是强制规则，DTO、VO、Entity 必须分离。

## 2. 当前目录

```text
com.hnu.backend
├─ BackendApplication.java
├─ shared/
│  ├─ web/                    # 统一响应、ResponseBodyAdvice、请求 ID
│  ├─ error/                  # 业务异常与全局异常处理
│  ├─ json/                   # 协议、历史快照与模型交互的 JSON 配置
│  └─ persistence/            # 通用 MyBatis 类型处理
├─ application/
│  └─ service/                # 知识库与文档的跨模块删除编排
├─ knowledgebase/
│  ├─ api/                    # 访问校验、不可变模型绑定与记录删除
│  ├─ controller/
│  ├─ dto/
│  ├─ vo/
│  ├─ service/
│  ├─ entity/
│  └─ mapper/
├─ document/
│  ├─ configuration/          # 文档处理并发和队列配置
│  ├─ api/                    # 对外提供关联文档清理能力
│  ├─ controller/
│  ├─ dto/
│  ├─ vo/
│  ├─ service/
│  ├─ entity/
│  ├─ mapper/
│  ├─ parser/
│  └─ storage/
├─ rag/
│  ├─ configuration/          # RAG 流水线预算与策略参数
│  ├─ controller/             # /api/questions 兼容入口
│  ├─ dto/
│  ├─ vo/
│  ├─ memory/                 # 会话记忆读取
│  ├─ service/                # 单轮问答兼容编排与检索设置
│  ├─ snapshot/               # 历史来源快照读取与兼容
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
│  ├─ configuration/          # 会话记忆和流式检查点参数
│  ├─ controller/
│  ├─ dto/
│  ├─ vo/
│  ├─ service/                # 会话管理与上下文查询
│  ├─ generation/             # 生成协调、状态、准备、执行、终态写入与 SSE 通道
│  ├─ presentation/           # 查询与生成共用的回答响应映射
│  ├─ adapter/                # 对接 RAG 的会话记忆实现
│  ├─ entity/
│  └─ mapper/
├─ model/
│  ├─ client/                 # Chat、Embedding 与 Rerank 能力
│  ├─ http/                   # OpenAI Compatible HTTP 实现
│  ├─ configuration/          # 模型提供方参数与 Embedding 协议
│  ├─ controller/
│  ├─ service/
│  └─ vo/
├─ intent/
│  ├─ controller/             # 意图树管理 HTTP 入口
│  ├─ dto/                    # 节点编辑请求
│  ├─ entity/                 # 数据库节点与绑定记录
│  ├─ mapper/
│  ├─ service/                # 意图树管理与业务校验
│  ├─ model/                  # 非持久化节点模型
│  ├─ snapshot/               # 路由快照构建与缓存
│  └─ event/                  # 意图树变更事件
├─ auth/                      # 认证、账户管理及所属配置
├─ observability/
│  ├─ configuration/          # Trace 保留与清理参数
│  ├─ controller/
│  ├─ entity/
│  ├─ mapper/
│  ├─ service/
│  ├─ trace/
│  └─ vo/
└─ configuration/             # 全局 JSON 配置和本地启动限制
```

资源目录按消费模块归档：

```text
src/main/resources
├─ application.yaml
├─ db/migration/
├─ prompts/                   # 各模型阶段的 UTF-8 Markdown 提示词
└─ mapper/
   ├─ knowledgebase/
   ├─ observability/
   └─ rag/
      └─ retrieval/
```

测试目录镜像主代码包；真实 PostgreSQL、pgvector 与 RustFS 测试集中在 `integration/`。

会话生成内部协作类集中在 `conversation/generation/`，包级可见的状态和通道不对外开放。`service/` 通过生成服务入口发起任务和执行会话删除前的活动检查；共用的消息映射放在 `presentation/`，避免生成内部类依赖会话查询实现。生成服务仍负责执行器关闭，跨包只暴露必要操作。

业务参数统一放在所属模块的 `configuration/`；根 `configuration/` 只放全局装配。包移动不改变 `@ConfigurationProperties` 前缀，仍由启动类从 `com.hnu.backend` 根包扫描。意图节点模型和数据库实体分开，快照缓存通过 `event/` 中的事件失效；此次分层不改变跨模块调用关系。

`rag/service/RagService` 编排旧单轮问答兼容流程，`rag/answer/` 保留最终生成与引用处理。历史来源快照的解析逻辑位于 `rag/snapshot/SourceSnapshotDecoder`，`rag/vo/` 保留对外响应类型；目录调整不改变 HTTP 接口和快照格式。

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

目录迁移不改变 REST 路径、数据库字段或 SSE 协议。已提取的职责包括：

1. `DocumentIndexService` 负责解析、分块、向量化和索引切换；同步调用与后台任务共用该流程。`DocumentImportService` 保留上传、并发限流、任务入队与中断恢复。入队事务提前抢占版本，后台处理不重复抢占。
2. 会话 CRUD、生成协调、流水线执行和 SSE 通道分别由对应服务承担。`ConversationGenerationPreparation` 负责新一轮消息与回答版本的事务准备，以及重试、重新生成条件校验；`ConversationGenerationService` 保留所有权、幂等、空闲检查和会话锁，在准备成功后启动任务。
3. `ExecutionStage` 负责路由执行、检索策略和候选合并；`SubQuestionScheduler` 管理并发任务提交、结果等待、超时、取消与子任务 Trace 终态。线程池容量与生命周期仍由阶段入口配置和触发。

MCP 超时从任务提交开始计时，包含排队；知识检索的通道预算仍由检索服务控制。调度器优先读取已完成结果，按结果自身耗时判断超时，避免等待前序任务影响后序结果；外部取消与任务提交失败会清理本批已提交任务。观测耗时仍以工作线程实际执行区间为准。

会话准备沿用原有事务边界：新用户消息、待生成回答、Trace 和会话更新时间一起写入；重试时停用旧回答与创建新版本一起完成。准备异常继续向上传播，不启动生成。事务测试使用 Spring JDBC 事务管理器与模拟连接验证提交和回滚调用，不替代真实数据库集成测试。

文档索引处理将模型调用放在切换事务之外；删除旧分块、写入新分块和激活版本在同一事务内完成。处理失败时，重建恢复为 READY，首次处理标记 FAILED。此拆分保持原有事务和队列行为，不增加数据库迁移或外部依赖。

只有出现独立部署、不同团队独立发布或稳定模块边界等真实需求后，才评估 Maven 多模块或微服务。

## 知识库与文档的协作边界

- 文档模块只依赖 `knowledgebase.api.KnowledgeBaseAccess`。访问校验返回创建者标识或不返回值，模型绑定返回不可变的 `EmbeddingBinding`；不传递知识库持久化实体。
- `KnowledgeBaseController` 将删除请求交给 `application.service.KnowledgeBaseDeletionService`，其他知识库操作仍由知识库服务处理。
- 删除编排只调用 `KnowledgeBaseAccess`、`KnowledgeBaseRemoval` 和 `document.api.DocumentCleanup`。知识库核心不反向依赖文档或应用编排。
- 文档记录与知识库记录在同一事务内删除。正在处理的文档会阻止删除；任何数据库异常都会触发回滚。
- 意图树失效事件在事务内发布，由现有 `AFTER_COMMIT` 监听器处理；对象存储清理注册提交回调。加入外层事务时，两者等待实际提交，回滚不清理文件。文件删除失败只记录日志，并继续处理其他文件。

`ModuleBoundaryTest` 使用仅在测试阶段引入的 ArchUnit 1.4.2 检查 Controller/Mapper 隔离、核心不依赖 Controller、上述公开接口边界，以及知识库、文档、应用编排核心之间的依赖环。HTTP Controller 属于入口，不计入核心环检测。其他模块既有依赖环尚未纳入治理，不能把此测试通过解释为整个项目已无环。

运行专项回归：

```powershell
cd backend
./mvnw.cmd '-Dtest=ModuleBoundaryTest,KnowledgeBaseDeletionServiceTest,DocumentCleanupServiceTest,KnowledgeBaseServiceTest' test
```

删除编排测试使用真实 Spring JDBC 事务管理器和模拟 JDBC 连接，验证提交、回滚与外层事务回调顺序；它不替代真实 PostgreSQL 集成测试。

## JSON 配置策略

`shared.json.JsonCodecs` 集中提供三个独立、可复用的 Jackson 配置，不新增依赖：

| 入口 | 使用范围 | 规则 |
| --- | --- | --- |
| `protocol()` | HTTP/SSE 协议 | 时间输出为 ISO 字符串，读取时允许额外字段；Spring 通过 `JsonConfiguration` 复用同一配置规则 |
| `snapshots()` | 数据库 JSON 快照 | 允许额外字段，历史缺省值由对应读取器解释 |
| `models()` | 模型和工具交互 | 拒绝尾随的额外 JSON 值，业务字段仍由各阶段校验 |

业务代码禁止自行调用 `JsonMapper.builder()`，由 `ModuleBoundaryTest` 约束。不要跨用途借用 mapper，以免后续修改快照兼容策略时影响模型响应校验。

来源快照保留旧版编号与顺序，不在读取时改写数据库。null、空白和 JSON null 表示未保存来源；旧版缺失 heading 允许为空。未知显式版本、错误结构和损坏 JSON 仍报错，不静默变成空来源。模型信息和引用编号快照也覆盖历史空值情况。
