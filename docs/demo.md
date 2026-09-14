# 阶段 1 演示与验收

## 使用真实服务

1. 按 README 配置生成模型、Embedding 模型（含实际维度）和 RustFS，启动后端与 Vue。
2. 在 /admin 上传 evaluation/datasets/employee-handbook.md；确认状态先显示“待分块”，点击“开始分块”，完成后状态变为“可检索”。分块数由当前语义分块配置决定。
3. 通过“返回问答”进入 /chat，提问“试用期员工能申请年假吗？”。应根据“年假资格”说明不可以。点击回答下的“检索来源”，侧栏应出现该节原文与第 9–11 行。具体排名和其他候选取决于实际 Embedding 模型。
4. 点击回答引用编号可展开对应来源，检查完整文字。回答不能把试用期和转正员工混淆。
5. 提问“试用期员工能申请年假和远程办公吗？”，检查是否检索到两节对应依据。
6. 提问“公司的 CEO 叫什么名字？”，应说明资料不足，不能编造姓名。
7. 执行 scripts/evaluate.ps1，保存 20 个问题的实际响应；逐条人工检查 referenceAnswer、实际来源和回答。16 条有答案、4 条无答案。

## 验收记录口径

- 上传成功：RustFS 保存的字节与上传文件一致，数据库版本为 UPLOADED 且没有片段；手动分块后有效版本变为 READY。
- 检索真实：sources 的 knowledgeBaseId、chunkId、documentId、versionId、正文与数据库记录一致；仅包含全库中的 READY 有效版本。
- 上下文真实：sources 是实际送入生成模型的完整片段，而非模型编造的资料。
- 回答可用：有答案题能从证据回答，重要事实有来源支持；无答案题明确拒答。编号校验只能验证来源编号，不保证语义支持。
- 评测脚本的 expectedEvidenceMatched 只是原文字符串命中数量，不是 Recall@K、准确率或可信度。
- 使用付费模型前自行确认对应账户配置和额度，脚本会上传文档并发出 20 次问答请求。
- 2026-09-13 已完成一轮真实模型评测，但 20 题中有 2 题返回 502，另有 1 题发生边界条件理解错误，尚未通过阶段关闭门槛。详见[阶段 1 验收分析](phase-1-acceptance.md)和[逐题审阅](evaluation-20260913-165858-review.md)。

## 测试替身页面检查的复现方式

本次浏览器检查使用隔离数据库 baserag_ui_test、bucket baserag-ui-test，后端 8081、前端 5174。普通应用使用 baserag、8080、5173。

测试协议服务位于 backend/src/test/fixtures/model-server.mjs，仅在手动运行 node 时启动，不打包进后端 JAR。固定回答带有“测试替身，非真实模型回答”标识，它只用于检查 HTTP、页面上传与来源交互。

复现时创建独立测试数据库，并按 .env.example 制作单独的测试配置：
- DB_URL 指向 baserag_ui_test，SERVER_PORT=8081。
- BAILIAN_URL 和 SILICONFLOW_URL 都设为 http://127.0.0.1:18090。
- BAILIAN_CHAT_ENDPOINT=/v1/chat/completions，SILICONFLOW_EMBEDDING_ENDPOINT=/v1/embeddings。
- BAILIAN_API_KEY 与 SILICONFLOW_API_KEY 可填写任意非空测试值，EMBEDDING_DIMENSIONS=11。
- RUSTFS_BUCKET=baserag-ui-test，凭据与本地容器一致。
- 单独运行 node backend/src/test/fixtures/model-server.mjs。
- 通过 scripts/start-backend.ps1 -EnvFile 指向测试配置启动后端。
- 在 frontend 终端设置 API_TARGET=http://127.0.0.1:8081，运行 npm run dev -- --port 5174。

测试结束后停止这些测试进程，正常使用时恢复真实模型配置。切勿将测试替身用于回答质量评测。

