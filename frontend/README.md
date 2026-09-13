# JAgent 前端

Vue 3 + TypeScript + Vite + Vue Router。npm 包名为 jagent-web。

## 页面

- /chat：ChatGPT 风格问答页面，左侧会话列表、中央消息记录、底部输入框；支持 Enter 发送、Shift+Enter 换行、失败重试、复制回答和来源侧栏。
- /admin：唯一后台入口，通过知识库、文档、分块三级表格管理知识资产。创建知识库时选择后端 YAML 配置的向量模型；上传文档后手动触发分块和向量化，READY 文档也可确认后重新分块并替换旧索引。
- /admin/documents：兼容旧地址，自动跳转到 /admin。

运行 npm ci、npm run dev；构建 npm run build。开发服务绑定 127.0.0.1，/api 代理到 http://127.0.0.1:8080；隔离测试可通过进程环境变量 API_TARGET 指向其他后端。

前端使用 HTML5 history 路由。生产静态托管需将前端页面地址回退到 index.html，并保留 /api 后端代理规则。当前后端仅支持 local 开发环境，管理界面尚无登录或权限认证。

## 数据与交互

api.ts 定义共享响应类型和请求处理。stores/workspace.ts 保存知识库和文档列表，stores/conversations.ts 保存当前页面访问的会话记录。路由切换后会话仍可查看，刷新页面后清空；每次请求只发送本次问题，不向模型发送历史消息。

所有回答、文档名称与片段按文本显示，不执行 HTML。点击回答的来源按钮打开侧栏，sources 是实际送入模型的上下文，citations 是回答引用的子集。来源侧栏使用原生 dialog，支持 Escape 与焦点恢复。

后台在窄屏下收起侧栏文字，并保持三级资产路径和表格的可操作性。完整服务配置见根目录 README.md；后端主配置为 backend/src/main/resources/application.yaml。
