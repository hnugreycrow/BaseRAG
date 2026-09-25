# BaseRAG

本地运行的 RAG 知识问答系统。管理员维护公共知识库，用户通过多轮对话获取带原文引用的流式回答，会话按用户隔离。

支持 Markdown、文本型 PDF、DOCX 文档索引，以及意图路由、检索重排、回答重试与版本切换、链路追踪。

**技术栈：** Java 21 / Spring Boot / MyBatis-Plus / PostgreSQL + pgvector / Redis / RustFS；Vue 3 / TypeScript / Vite / Element Plus。

## 快速开始

环境：Windows + PowerShell、Java 21+、Node.js 22.12+、Docker Desktop。

1. 复制配置文件：

   ```powershell
   Copy-Item .env.example .env
   ```

   设置非空 `REDIS_PASSWORD`、至少 12 位的管理员密码，以及 `BAILIAN_API_KEY`；使用首选 DeepSeek 对话模型还需配置 `DEEPSEEK_API_KEY`。不要提交 `.env`。

2. 启动基础设施和后端：

   ```powershell
   docker compose --env-file .env -f deploy/compose.yml up -d
   ./scripts/start-backend.ps1
   ```

3. 在另一个终端启动前端：

   ```powershell
   cd frontend
   npm ci
   npm run dev
   ```

访问 Vite 输出的地址，通常为 [localhost:5173](http://localhost:5173)，使用配置的管理员账号登录。创建知识库 → 上传文档 → 点击“开始分块” → 到 `/chat` 提问。只有 `READY` 文档参与问答。

后端默认端口为 `8080`。项目仅面向本地使用，不支持扫描件 OCR 或公开注册。

## 测试与构建

从仓库根目录执行，日常测试无需调用付费模型：

```powershell
cd backend
./mvnw.cmd test
./mvnw.cmd spotless:check

cd ../frontend
npm test
npm run build
npm run format:check
```

基础设施集成测试需启动 Docker 服务、创建独立测试库并设置 `RAG_INTEGRATION=true`，详见[开发与配置](docs/development.md)。

## 文档

- [环境变量](.env.example) · [后端配置](backend/src/main/resources/application.yaml)
- [产品需求](REQUIREMENTS.md) · [系统架构](docs/architecture.md)
- [演示步骤](docs/demo.md) · [开发与配置](docs/development.md)
