# JAgent Frontend

JAgent 的 Vue 3 + TypeScript 管理端，使用 Vite 构建。

## 技术栈

- Vue 3
- Vue Router
- Pinia
- Axios
- Element Plus
- Prettier

## 本地开发

```powershell
npm install
npm run dev
```

开发服务器会将 `/api` 请求代理到 `http://localhost:8080`。如需修改目标地址，可在前端目录的 `.env.local` 中设置：

```dotenv
VITE_API_PROXY_TARGET=http://localhost:8080
```

若前端需要直接请求完整 API 地址，可设置 `VITE_API_BASE_URL`；未设置时默认使用 `/api`。

## 常用命令

```powershell
npm run build
npm run format
npm run format:check
```

## 目录

- `src/api/`：Axios 实例、统一响应与错误处理
- `src/route/`：页面路由配置
- `src/store/`：Pinia 状态
- `src/layout/`：管理端公共布局
- `src/views/`：路由页面，页面样式与对应 `.vue` 文件放在一起
