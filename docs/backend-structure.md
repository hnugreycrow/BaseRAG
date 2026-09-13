# 后端项目目录结构设计

## 1. 设计结论

当前后端规模适合继续保持一个 Spring Boot / Maven 模块，但 Java 包应从“按技术层横向分包”调整为“按业务能力纵向分包，业务包内再分层”。

目标不是套用复杂的微服务或完整 DDD，而是让一次需求修改尽量集中在同一个业务目录中。例如，修改文档导入时，接口、用例、数据模型、持久化和存储适配代码都能从 `document` 包开始查找，而不必在全局 `controller`、`service`、`entity`、`mapper`、`dto`、`vo` 之间来回跳转。

现阶段不建议拆成多个 Maven 子模块。当前代码量、部署方式和事务边界都仍适合模块化单体；过早拆模块会增加构建、依赖和本地调试成本。

### 实施状态

当前代码已完成业务分包、控制器拆分、请求/响应与配置类型重命名、单实现 Service 接口清理，以及 Mapper XML 按业务归档。检索 SQL 已从文档 Mapper 拆入问答模块，知识库删除通过文档应用服务协调，业务包不再直接访问其他业务包的 Mapper。ArchUnit 依赖规则测试作为后续增强项保留。

## 2. 推荐目录

```text
backend/
├─ pom.xml
├─ mvnw
├─ mvnw.cmd
└─ src/
   ├─ main/
   │  ├─ java/com/hnu/backend/
   │  │  ├─ BackendApplication.java
   │  │  │
   │  │  ├─ shared/                         # 无业务含义、可跨业务复用
   │  │  │  ├─ error/
   │  │  │  │  ├─ ApiException.java
   │  │  │  │  └─ GlobalExceptionHandler.java
   │  │  │  ├─ web/
   │  │  │  │  └─ RequestIdFilter.java
   │  │  │  └─ persistence/
   │  │  │     └─ UuidTypeHandler.java
   │  │  │
   │  │  ├─ knowledgebase/                  # 知识库管理
   │  │  │  ├─ api/
   │  │  │  │  ├─ KnowledgeBaseController.java
   │  │  │  │  ├─ KnowledgeBaseRequest.java
   │  │  │  │  └─ KnowledgeBaseResponse.java
   │  │  │  ├─ application/
   │  │  │  │  └─ KnowledgeBaseService.java
   │  │  │  ├─ domain/
   │  │  │  │  └─ KnowledgeBase.java
   │  │  │  └─ infrastructure/persistence/
   │  │  │     └─ KnowledgeBaseMapper.java
   │  │  │
   │  │  ├─ document/                       # 上传、版本、分块和原文件生命周期
   │  │  │  ├─ api/
   │  │  │  │  ├─ DocumentController.java
   │  │  │  │  ├─ DocumentRequest.java
   │  │  │  │  ├─ DocumentResponse.java
   │  │  │  │  ├─ DocumentImportResponse.java
   │  │  │  │  ├─ DocumentChunkResponse.java
   │  │  │  │  └─ DocumentChunkDetailResponse.java
   │  │  │  ├─ application/
   │  │  │  │  ├─ DocumentService.java
   │  │  │  │  └─ DocumentCleanupService.java
   │  │  │  ├─ domain/
   │  │  │  │  ├─ Document.java
   │  │  │  │  ├─ DocumentVersion.java
   │  │  │  │  └─ DocumentChunk.java
   │  │  │  ├─ parser/
   │  │  │  │  └─ MarkdownChunker.java
   │  │  │  └─ infrastructure/
   │  │  │     ├─ persistence/
   │  │  │     │  ├─ DocumentMapper.java
   │  │  │     │  ├─ DocumentVersionMapper.java
   │  │  │     │  └─ DocumentChunkMapper.java
   │  │  │     └─ storage/
   │  │  │        ├─ FileStorage.java
   │  │  │        └─ S3FileStorage.java
   │  │  │
   │  │  ├─ question/                       # 检索增强问答
   │  │  │  ├─ api/
   │  │  │  │  ├─ QuestionController.java
   │  │  │  │  ├─ QuestionRequest.java
   │  │  │  │  ├─ AnswerResponse.java
   │  │  │  │  ├─ SourceResponse.java
   │  │  │  │  └─ ModelInfoResponse.java
   │  │  │  ├─ application/
   │  │  │  │  ├─ QuestionService.java
   │  │  │  │  └─ RetrievalService.java
   │  │  │  ├─ domain/
   │  │  │  │  └─ SearchHit.java
   │  │  │  ├─ infrastructure/persistence/
   │  │  │  │  └─ RetrievalMapper.java
   │  │  │  └─ support/
   │  │  │     ├─ ContextBuilder.java
   │  │  │     └─ Citations.java
   │  │  │
   │  │  ├─ ai/                             # 被文档与问答共同使用的模型能力
   │  │  │  ├─ config/
   │  │  │  │  └─ AiProperties.java
   │  │  │  ├─ chat/
   │  │  │  │  └─ ChatClient.java
   │  │  │  ├─ embedding/
   │  │  │  │  └─ EmbeddingClient.java
   │  │  │  └─ infrastructure/http/
   │  │  │     └─ ModelHttpClient.java
   │  │  │
   │  │  └─ configuration/                  # 应用装配和跨模块配置
   │  │     ├─ LocalOnly.java
   │  │     └─ RagProperties.java
   │  │
   │  └─ resources/
   │     ├─ application.yaml
   │     ├─ db/migration/
   │     │  └─ V1__knowledge_base.sql
   │     └─ mapper/
   │        ├─ knowledgebase/
   │        │  └─ KnowledgeBaseMapper.xml
   │        ├─ document/
   │        │  └─ DocumentChunkMapper.xml
   │        └─ question/
   │           └─ RetrievalMapper.xml
   │
   └─ test/
      ├─ java/com/hnu/backend/              # 与 main 的业务包结构镜像
      │  ├─ knowledgebase/
      │  ├─ document/
      │  ├─ question/
      │  ├─ ai/
      │  └─ integration/                    # 跨模块/基础设施集成测试
      └─ fixtures/
         └─ model-server.mjs
```

`shared` 必须保持很小。只有错误模型、Web 过滤器、基础类型处理器这类真正无业务含义的代码可以进入；不能把暂时不知道放哪里的类塞进该目录。

## 3. 包依赖规则

每个业务包内部遵守以下依赖方向：

```text
api  →  application  →  domain
              │             ↑
              └→ infrastructure
```

- `api` 只处理 HTTP 协议、参数校验和请求/响应转换，不写业务流程。
- `application` 编排一个完整用例和事务，是业务功能的主要入口。
- `domain` 保存业务数据和规则，不依赖 Spring MVC、MyBatis、S3 或模型服务。
- `infrastructure` 封装数据库、对象存储和外部 HTTP 等技术细节，并且只能被同一业务包的 `application` 使用。
- 一个业务包不能直接使用另一个业务包的 Mapper；跨业务协作通过对方的 `application` 服务完成。
- `shared` 不得反向依赖 `knowledgebase`、`document`、`question` 或 `ai`。
- 只有确实存在多个实现或需要隔离外部系统时才定义接口。MyBatis Mapper、文件存储和模型客户端保留接口有价值；只有单一实现的普通业务 Service 不必机械地创建 `XxxService` + `XxxServiceImpl`。

如果以后需要更严格的六边形架构，可将存储、模型与仓储接口进一步放进 `application/port`，由 `infrastructure` 实现。当前规模先采用上述实用依赖关系，避免为每个 Mapper 再增加一层空转的 Repository 包装。

建议后续用 ArchUnit 固化这些规则，避免目录在多人协作中重新退化。

## 4. 命名约定

- HTTP 输入使用 `CreateKnowledgeBaseRequest`、`RenameDocumentRequest`、`AskQuestionRequest`。
- HTTP 输出使用 `KnowledgeBaseResponse`、`DocumentResponse`、`AnswerResponse`。
- 不再使用语义模糊的全局 `dto`、`vo` 目录；类型放在消费它的业务 `api` 包内。
- 配置绑定类使用 `Properties` 后缀，如 `AiProperties`、`RagProperties`，避免与 Spring `@Configuration` 装配类混淆。
- 业务服务直接使用动作或领域名称，如 `DocumentService`；删除只有一个实现的 `Impl` 后缀。
- 全局异常处理器命名为 `GlobalExceptionHandler`，比 `ApiErrors` 更容易被搜索和识别。

一个请求/响应只被一个控制器使用时，可以先作为控制器的嵌套 `record`；类型增多或被复用时再拆文件，避免产生大量只有一行的文件。

## 5. 本次迁移映射

| 迁移前目录 | 当前目录 |
| --- | --- |
| `controller/KnowledgeBaseController` | 拆为 `knowledgebase/api`、`document/api`、`question/api` 下的三个 Controller |
| `service/KnowledgeBaseService*` | `knowledgebase/application/KnowledgeBaseService` |
| `service/DocumentService*` | `document/application/DocumentService` |
| `service/QuestionService*`、`RetrievalService*` | `question/application` |
| `entity/KnowledgeBase` | `knowledgebase/domain` |
| `entity/Document*` | `document/domain` |
| `mapper/KnowledgeBaseMapper` | `knowledgebase/infrastructure/persistence` |
| `mapper/Document*Mapper` | `document/infrastructure/persistence` |
| `support/rag/MarkdownChunker` | `document/parser` |
| `support/rag/ContextBuilder`、`Citations` | `question/support` |
| `model/SearchHit` | `question/domain` |
| `integration/storage` | `document/infrastructure/storage` |
| `integration/model`、`integration/embedding` | `ai` 对应子包 |
| `common/*` | 按职责拆入 `shared/error` 与 `shared/web` |
| 全局 `dto`、`vo` | 拆入各业务包的 `api` |

## 6. 推荐迁移顺序

为了让每一步都可编译、可回滚，按以下顺序进行：

1. 先移动 `common` 和配置类，只修改包名与 import，不改变行为。
2. 将大控制器拆成三个控制器，保持 URL、请求结构和响应结构完全不变。
3. 逐个迁移 `knowledgebase`、`document`、`question`，每迁移一个业务包就运行全部测试。
4. 最后迁移 `ai`、对象存储和 Mapper XML，检查 XML `namespace`、类型别名与扫描范围。
5. 删除无意义的单实现 Service 接口和 `impl` 目录；这是结构迁移中唯一建议单独提交的代码形态调整。
6. 增加 ArchUnit 规则，禁止跨业务包直接访问 `infrastructure.persistence`。

迁移提交应只包含移动、重命名和必要的 import 修改，不与业务功能修改混在一起。这样 Git 历史更容易审查，也便于出现问题时定位。

## 7. 何时再拆 Maven 多模块

满足以下任意两项后，再评估拆分 `backend-domain`、`backend-application`、`backend-infrastructure` 或独立服务：

- 一个业务能力需要独立部署或独立扩缩容；
- 模块间边界已经稳定，并且 ArchUnit 规则持续通过；
- 单模块构建时间明显影响开发反馈；
- 不同团队需要独立发布；
- 数据所有权或事务边界已经能明确分开。

在此之前，清晰的 Java 包边界比增加 Maven 模块更轻量，也足以改善当前代码管理体验。
