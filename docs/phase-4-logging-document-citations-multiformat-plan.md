# BaseRAG 第四阶段：链路日志、文档级引用与多格式文档

## 1. 阶段目标

- 将问答链路日志改为可按 `requestId`、`runId` 和 `generationId` 串联的简洁单行日志，详细阶段数据继续以现有 Trace 页面为准。
- 将新回答从“每个分块一个引用”升级为“每个文档版本一个引用”，引用集中放在段落、列表项或结论末尾。
- 在 Markdown 基础上支持文本型 PDF 和 DOCX 的上传、解析、分块、检索与新标签页预览。
- 第 4 项本阶段留空，不加入混合检索、持久化任务或部署运维。

## 2. 核心实现

### 2.1 链路日志

- 定义不可变的问答日志上下文，显式携带 `requestId`、`runId`、`conversationId`、`generationId`、`stage` 和可选 `subQuestionId`；包装问答、子问题、检索和模型异步任务，在进入线程时写入 MDC、结束时强制清理。
- 控制台保持文本单行格式，字段采用稳定键值：`event`、`outcome`、`elapsedMs`、`candidateCount`、`evidenceCount`、`sourceDocumentCount`、`reasonCode` 和 `modelId`。
- 日志级别统一：
  - `INFO`：问答开始、最终结束和关键阶段汇总。
  - `DEBUG`：单个成功阶段的详细计数。
  - `WARN`：超时、降级、模型切换和引用修复。
  - `ERROR`：不可恢复终态及异常栈。
- 超时任务只记录一次权威终态，例如 `outcome=TIMEOUT elapsedMs=15000 timeoutMs=15000`；被取消任务的迟到结果不再输出“完成但耗时 0ms”等冲突日志。
- 问题正文、回答正文、证据内容、Cookie、密钥和认证头不得进入日志；必要时只记录字符数或不可逆哈希。
- 文档后台处理使用 `documentId` 和 `versionId` 关联日志，避免依赖已经结束的上传请求 MDC。

### 2.2 文档解析与存储

- 引入格式无关的 `DocumentParser` 注册表，统一产出已有的 `StructuredBlock` 和 `SourceSpan`：
  - Markdown 使用 `LINE` 定位。
  - PDF 按页提取文本，使用 `PAGE` 定位。
  - DOCX 解析正文标题、段落、列表和表格，使用 `PARAGRAPH` 定位。
- 复用 `StructuredChunkPacker` 完成统一分块；PDFBox 和 POI 只负责解析，不直接承担分块策略。
- 使用 Apache PDFBox 3.0.8 和 Apache POI 5.5.1 的 `poi-ooxml`，版本在 Maven 属性中集中锁定。
- 上传时根据扩展名、文件签名和容器结构共同判定格式，不信任客户端 MIME：
  - Markdown 保持严格 UTF-8 校验。
  - PDF 必须为有效且未加密的文本型 PDF。
  - DOCX 必须包含合法的 OOXML Word 主文档结构。
- 默认文件限制为 Markdown 5 MiB、PDF 和 DOCX 20 MiB；扫描 PDF、无可提取文本 PDF、旧 `.doc`、损坏压缩包返回稳定错误码。
- 新增前向 Flyway 迁移：
  - `document_versions` 增加 `format`、`media_type` 和 `file_size_bytes`，历史数据回填为 Markdown。
  - `document_chunks` 增加 `source_unit`、`source_start` 和 `source_end`，历史数据从现有行号字段回填。
  - 保留原有行号字段用于历史兼容，新接口统一读取通用来源位置。
  - `rag_runs` 增加 `source_document_count`；原 `evidence_count` 明确定义为证据分块数。
- 手动“开始分块”流程保持不变；已有 READY Markdown 不要求重新索引。

### 2.3 文档级引用

- 先按 Rerank 结果截取 Top K，再按 `(documentId, versionId)` 分组；文档之间保持其最高排名分块的相关度顺序，同一文档内按 `chunkIndex` 恢复原文顺序，并稳定分配 `S1…Sn`。
- 给模型的知识证据使用纯文本拼接，不使用 JSON，不发送文档名、内部 ID、相似度、Rerank 分数或分块编号。参考 RAGent 使用类 HTML/XML 标签作为 Prompt 分隔符；这些标签只用于模型输入组织，不作为浏览器 HTML 渲染。每篇文档使用以下固定边界：

  ```text
  <content ref="S1">
  第一段入选内容……

  —— 中间内容省略 ——

  后续入选内容……
  </content>
  ```

- 同一文档内连续分块直接换行拼接；`chunkIndex` 不连续时插入 `—— 中间内容省略 ——`，防止模型把相距较远的章节误认为连续原文。拼接阶段不再增加 passage、重点片段或排名等额外结构，也不摘要、改写证据正文。
- 模型仍使用 `[S1]` 格式，工具 `T*` 协议不变。提示词要求：
  - 仅在结论依赖知识库证据时引用。
  - 引用放在段落、列表项或完整结论末尾。
  - 同一文档在同一内容块中只引用一次。
- 引用通过白名单校验后执行确定性位置归并：跳过代码块，将同一段落或列表项中的知识引用去重并移动到内容块末尾；若正文变化，使用现有 SSE `reset` 机制发送最终规范化正文，再持久化。
- 后端为每轮回答维护唯一的 `citationId → documentId/versionId` 映射，Prompt、SSE、消息持久化和前端来源面板必须复用该映射；`sources` 保存实际进入提示词的文档、拼接正文及原始位置，`citations` 保存回答实际使用的文档编号。
- 模型输入虽然使用纯文本，但 API 和数据库中的来源快照继续使用结构化字段，供所有权校验、历史审计和文档预览使用。来源面板默认只展示已引用文档，观测详情仍可查看未引用的提示词证据计数。
- 历史消息不批量改写：新增来源快照解码器，同时支持旧的平铺分块结构和新的文档结构；旧回答保持原引用编号，新回答使用文档级规则。

## 3. 接口与前端变化

- `DocumentResponse` 增加 `format`、`mediaType`、`fileSizeBytes` 和 `previewAvailable`。
- 新版 `SourceResponse` 和 `AnswerSource` 改为文档级结构，包含 `schemaVersion`、`citationId`、知识库、文档、版本、名称、`format`、拼接后的 `content`、最高相关片段的 `primaryLocation`，以及全部入选分块的 `locations[]`。每个位置保存 `chunkId`、`heading` 和 `{unit,start,end,label}`，但不重复保存分块正文和相关度分数。
- 新增受所有权保护的接口：
  - `GET /api/knowledge-bases/{kbId}/documents/{documentId}/versions/{versionId}/preview`：返回 Markdown 和 DOCX 的安全结构化预览块，或 PDF 预览模式及内容地址。
  - `GET /api/knowledge-bases/{kbId}/documents/{documentId}/versions/{versionId}/content`：返回原文件；PDF 使用 `inline` 并支持 HTTP Range。
- 预览接口必须校验用户、知识库、文档和版本的完整归属关系；跨用户访问与资源不存在统一返回 404。
- 新增独立预览路由，以新标签页打开：
  - PDF 使用浏览器原生查看器，并通过 `#page=N` 定位引用页。
  - Markdown 和 DOCX 由 Vue 按结构化块渲染，不使用不可信原始 HTML，并滚动到对应行或段落锚点。
- 行内 `[S*]` 和来源卡片的“预览文档”均打开新标签页；来源数量显示实际引用文档数，而不是证据分块数。
- 上传对话框接受 `.md`、`.markdown`、`.pdf` 和 `.docx`，显示各格式大小和能力限制；“资料不足”提示不再写死为 Markdown。

## 4. 测试与验收

### 4.1 链路日志

- 覆盖 MDC 跨异步任务传播、任务结束清理和并发请求隔离。
- 验证超时任务只有一个终态，降级和模型切换包含稳定原因码。
- 验证日志不包含问题正文、回答正文、证据内容、Cookie、Token、模型密钥或存储凭据。
- 使用同一 `runId` 可以还原一次问答的关键阶段顺序，并能关联现有 Trace 详情。

### 4.2 文档解析与预览

- 覆盖多页文本 PDF、PDF 页码定位、DOCX 标题、列表、表格和中文内容。
- 覆盖损坏或加密文件、扫描 PDF、伪造扩展名、文件大小限制和压缩包安全限制。
- 分别执行 Markdown、PDF 和 DOCX 的“上传→手动分块→READY→检索→回答→预览”集成链路。
- 验证预览读取当前用户的指定版本，跨用户预览统一返回 404。
- 验证 PDF 内容响应的 MIME、`Content-Disposition` 和 Range 行为，以及 Markdown 和 DOCX 预览不执行不可信 HTML 或脚本。

### 4.3 文档级引用

- 验证先按 Rerank 选择 Top K，再执行文档分组；同一文档的多个分块只生成一个 `S` 编号，多文档编号按各自最高排名分块稳定排序。
- 验证同一文档内按 `chunkIndex` 升序拼接：连续分块直接换行，不连续分块只插入一次 `—— 中间内容省略 ——`。
- 验证模型输入使用 `<content ref="S*">...</content>` 固定边界、不使用 JSON，并且不包含文档名、内部文档 ID、版本 ID、相似度、Rerank 分数或分块编号；标签只作为 Prompt 分隔符，不进入前端 HTML 渲染。
- 验证 Prompt、SSE、持久化来源快照和前端来源面板复用同一份引用编号映射。
- 同一段落或列表项中的重复引用归并到内容块末尾，代码块内容不被改写。
- 非法编号仍只执行一次引用修复，工具 `T*` 标记不受知识引用归并影响。
- 旧来源 JSON 可以正常读取，旧回答的编号和来源快照保持不变。
- 前端来源计数、文档分组、行内引用、新标签页、PDF 页码和 DOCX/Markdown 锚点均有自动化测试。

### 4.4 完整回归

- 运行后端全部测试和 `spotless:check`。
- 运行前端 Vitest、`format:check` 和生产构建。
- 同步更新 `REQUIREMENTS.md`、`docs/architecture.md`、`docs/decisions.md` 和 `README.md` 中的支持格式、引用语义与当前限制。

## 5. 默认边界

- 不支持 OCR、扫描件识别、旧 `.doc`、PDF 表格重建，以及 DOCX 图片、批注、修订、页眉和页脚。
- DOCX 没有稳定页码，引用和预览定位使用标题及段落范围。
- 文档删除后，历史回答仍保留来源快照和片段，但原文件预览返回“文档已不可用”。
- 不部署 Loki 或 ELK，不切换 JSON 控制台日志；数据库 Trace 仍是详细观测的权威来源。
- 不改变当前手动索引和进程内后台任务模型。
