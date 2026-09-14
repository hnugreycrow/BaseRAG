# 架构决策

## 阶段 2 RAG 流水线决策

- RAG 按 memory → planning → routing → execution → deduplication → rerank → prompt → answer 划分阶段；中间结果使用 RAG 自有不可变对象，最终仍由 conversation 模块映射到既有 SSE 和消息终态。
- 问题规划最多生成 4 个无依赖子问题，非法、超时或过度拆分时回退原问题；首版不实现递归 Agent 计划或思维链持久化。
- 子问题分别路由为知识检索、MCP 工具或系统闲聊。MCP 只保留服务端注册、只读白名单、Schema、超时、脱敏和截断框架，默认关闭且允许列表为空，不视为已开放产品能力。
- 知识型子问题独立生成向量并检索。跨子问题和跨 Embedding 模型使用基于名次的 RRF 融合，原始相似度不解释为正确概率，也不直接跨模型比较。
- 重排前按分块 ID、规范化正文和同文档相邻重叠确定性去重，并保留全部来源与子问题归因；候选截断优先保护每个有效知识型子问题的最低覆盖。
- Reranker 通过独立模型端口调用，只能为输入候选评分。超时、失败、非法映射或 noop 时按 RRF 融合分降级，回答链路不能因重排不可用而整体失败。
- 最终提示词固定分隔会话记忆、问题计划、知识证据、工具观察和原始回答目标，所有历史、文档、规划及工具结果均按不可信数据处理。
- `AnswerStage` 统一流式回答、模型候选回退、取消、S/T 引用校验和一次引用修复。首次非法回答不进入修复提示词，非法的已完成 generation attempt 转为失败记录。
- 当前不新增 RAG 前端协议：sources 只返回实际进入最终提示词的知识证据，citations 继续只保存合法 S 编号，T 工具标记仅在后端校验和预留审计中使用。

## 阶段 2 会话决策

- 会话、逻辑消息和真实模型生成尝试分表保存；assistant 的不同显式回答为可切换版本，引用修复和候选回退作为 generation_attempts 保留。
- 多轮上下文默认最近 8 轮，历史摘要每累计 4 个移出窗口的完整轮次增量更新；摘要失败保留覆盖游标并回退未摘要原文。
- 移除 rag.context-chars，所有聊天、摘要、问题改写和引用修复请求不发送 max_tokens；Top K 与问题长度限制保留。
- 浏览器通过 POST + fetch 读取 SSE，协议 schemaVersion=1。回答按 512 字符或 1 秒检查点保存；断连、停止和启动恢复都有明确终态。
- 本里程碑不提前实现用户身份和知识库授权，仍保持 local 单用户及全 READY 知识库检索。

## 阶段 1 基础决策

- 本次用户要求优先于原规范：数据访问采用 MyBatis-Plus，而非 Spring JDBC。保留 Java 21 编译目标、Spring Boot 4.1.1、单 Maven 模块和直接 HTTP 模型接入。
- MyBatis-Plus 使用 Boot 4 专用 starter 3.5.17；AWS S3 SDK 2.31.64。Spring、Flyway、PostgreSQL 驱动等版本由固定 Spring Boot BOM 管理。
- 文件存储采用已有规范指定的 RustFS；封装 FileStorage，未来可替换其他 S3 服务。本地镜像固定为 1.0.0-rc.4-glibc（候选发布版本）。
- pgvector 镜像固定 0.8.6-pg17-trixie。本阶段精确检索，不为小规模样例提前添加 HNSW，因而不受该索引类型的向量维度上限影响；vector 输入仍限制 1–16000 维。
- 向量列使用无固定维数的 vector，数据库 CHECK 和复合外键约束版本维度，允许通过显式配置选定实际模型维度，不修改 Flyway 历史迁移。
- AI 配置按服务商根地址、能力端点、候选模型和层级拆分。当前仅启用百炼 qwen-plus-latest 与 SiliconFlow Qwen/Qwen3-Embedding-8B，密钥只从环境变量注入。
- Embedding 请求显式发送 dimensions，并严格校验响应数量、索引、维度和数值；知识库继续绑定实际模型名与维度。
- 仅对连接错误、超时、429 与 5xx 做最多 2 次重试；连续失败达到阈值后按配置短暂熔断。引用修复是额外的一次生成。手动分块和向量化可能耗时较长，前端代理不设置短超时。
- 依据用户确认，将简单 Vue 验收页提前到阶段 1，不提前实现流式、多轮和会话。
- 修复项目自带 Windows Maven Wrapper 对非符号链接目录 Target 为空时的崩溃。
- 测试分为日常模型协议/逻辑单测、真实基础设施集成、真实模型验收，分别报告，不能相互替代。

## 依赖依据

- [MyBatis-Plus Boot 4 安装说明](https://baomidou.com/en/getting-started/install/)
- [Spring Boot 构建与 starter](https://docs.spring.io/spring-boot/reference/using/build-systems.html)
- [pgvector 官方仓库](https://github.com/pgvector/pgvector)
- [RustFS 官方安装说明](https://docs.rustfs.com/en/installation)
