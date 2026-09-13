# 架构决策

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
