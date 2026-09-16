# BaseRAG

BaseRAG 是一个 RAG 知识问答系统。用户可以管理私有文档知识库，通过多轮对话获得流式回答，并查看回答引用的原文来源。

## 功能

- 私有知识库与会话：账号登录、数据隔离、知识库和文档管理。
- 多格式索引：支持 Markdown、文本型 PDF 和 DOCX，上传后手动分块和向量化；支持查看分块、来源位置和重新分块。
- 知识问答：问题改写、向量检索、候选融合与重排、文档版本级引用、引用校验及失败降级。旧回答保留原分块级编号。
- 流式会话：支持停止、重试、重新生成、回答版本切换和运行记录查看。

当前支持 UTF-8 `.md` / `.markdown`（5 MiB）、文本型 PDF 和 DOCX（各 20 MiB）；不支持扫描件 OCR、旧 `.doc`、公开注册或公网部署。完整范围见 [产品需求](REQUIREMENTS.md)。

## 技术栈

后端使用 Java 21、Spring Boot、MyBatis-Plus、PostgreSQL/pgvector、Redis 和 RustFS；前端使用 Vue 3、TypeScript、Vite 和 Element Plus。

## 快速开始

需要 Java 21 或更高版本、Node.js 22.12+、Docker Desktop，以及可运行 PowerShell 脚本的 Windows 环境。

1. 复制本地配置：

   ```powershell
   Copy-Item .env.example .env
   ```

   在 `.env` 中设置非空 `REDIS_PASSWORD`，并修改首次启动使用的管理员密码（至少 12 个字符）。默认 Embedding 和 Rerank 使用百炼，需要填写 `BAILIAN_API_KEY`；如要使用首选 DeepSeek Chat 模型，另填 `DEEPSEEK_API_KEY`。不要提交 `.env`。

2. 启动 PostgreSQL、Redis 和 RustFS：

   ```powershell
   docker compose --env-file .env -f deploy/compose.yml up -d
   ```

3. 启动后端：

   ```powershell
   ./scripts/start-backend.ps1
   ```

4. 在另一个终端启动前端：

   ```powershell
   cd frontend
   npm ci
   npm run dev
   ```

打开 Vite 输出的地址（通常为 [http://127.0.0.1:5173](http://127.0.0.1:5173)），使用管理员账号登录。在 `/admin` 创建知识库、上传 Markdown、PDF 或 DOCX 文件并点击“开始分块”，然后到 `/chat` 提问。上传后不会自动建立索引；只有 `READY` 文档会参与问答。

后端默认运行在 [http://127.0.0.1:8080](http://127.0.0.1:8080)。若本机 5432、6379、9000 或 9001 端口已占用，请调整 Compose 端口映射及对应连接地址。

## 测试

日常测试不需要调用付费模型：

```powershell
cd backend
./mvnw.cmd test
./mvnw.cmd spotless:check

cd ../frontend
npm test
npm run build
npm run format:check
```

基础设施集成测试需先启动 Docker 服务，并首次创建独立的 `baserag_test` 数据库：

```powershell
docker compose --env-file .env -f deploy/compose.yml exec -T postgres createdb -U baserag baserag_test
cd backend
$env:RAG_INTEGRATION = 'true'
./mvnw.cmd test
Remove-Item Env:RAG_INTEGRATION
```

数据库已存在时跳过 `createdb`。命令中的 `baserag` 是示例数据库用户；修改示例凭据后，需为集成测试设置相应环境变量。测试使用独立数据库和对象存储 bucket，不调用真实生成模型。

## 配置与文档

- [`.env.example`](.env.example)：本地凭据和服务连接参数。
- [`backend/src/main/resources/application.yaml`](backend/src/main/resources/application.yaml)：模型候选、RAG 参数与服务配置。
- [产品需求](REQUIREMENTS.md)、[架构说明](docs/architecture.md)、[演示步骤](docs/demo.md)：范围、实现和使用示例。

API 除登录外均要求认证；写请求还需要 `X-CSRF-Token`。本项目仅面向本地环境，示例密码应在个人 `.env` 中修改。
