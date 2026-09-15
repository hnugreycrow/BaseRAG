export interface Resource {
  id: number
  name: string
  count: number
  status: string
  date: string
}

export const initialLibraries: Resource[] = [
  { id: 1, name: '产品与使用指南', count: 12, status: '可检索', date: '今天 10:24' },
  { id: 2, name: '研发技术文档', count: 28, status: '可检索', date: '今天 09:16' },
  { id: 3, name: '团队协作规范', count: 8, status: '可检索', date: '昨天 16:40' },
  { id: 4, name: '客户支持与常见问题', count: 16, status: '处理中', date: '昨天 14:12' },
  {
    id: 5,
    name: '季度产品规划与跨团队需求评审记录（2026 年第三季度）',
    count: 6,
    status: '可检索',
    date: '09-12 11:08',
  },
  { id: 6, name: '项目归档', count: 24, status: '可检索', date: '09-10 18:20' },
]

export const documents: Resource[] = [
  { id: 11, name: '知识库使用指南.md', count: 24, status: '可检索', date: '今天 10:24' },
  { id: 12, name: '文档上传与处理规范.md', count: 18, status: '可检索', date: '今天 09:16' },
  { id: 13, name: '检索与引用说明.md', count: 32, status: '可检索', date: '昨天 16:40' },
  { id: 14, name: '常见问题.md', count: 12, status: '处理失败', date: '昨天 14:12' },
]

export const sources = [
  {
    title: '知识库使用指南.md',
    section: '文档处理流程 · 第 3 节',
    text: '上传 Markdown 文档后，系统依次完成解析、分块与向量化。处理完成的文档会标记为可检索，并在后续问答中作为参考来源。',
  },
  {
    title: '文档上传与处理规范.md',
    section: '处理状态与重试 · 第 2 节',
    text: '文档处于处理中时，请等待任务完成。若处理失败，可在文档列表查看错误并重新处理；无需重复创建知识库。',
  },
  {
    title: '检索与引用说明.md',
    section: '回答来源 · 第 1 节',
    text: '回答中的引用编号关联到检索文档的具体分块。展开来源后，可以查看原文片段并核对回答。',
  },
]

export const runs = Array.from({ length: 14 }, (_, index) => ({
  id: `run-20260915-${String(index + 1).padStart(3, '0')}`,
  time: `10:${String(42 - index * 2).padStart(2, '0')}:08`,
  status: index === 3 ? '失败' : index === 5 ? '已取消' : index === 7 ? '生成中' : '成功',
  user: index % 3 === 0 ? '林晓' : '陈默',
  model: index % 3 === 0 ? 'Qwen3' : 'DeepSeek V3',
  mode: index % 2 ? '完整流程' : '快速回答',
  first: index === 3 || index === 7 ? null : 0.86 + index * 0.12,
  total: 3.24 + index * 0.43,
  degraded: index === 2,
}))

export const stages = [
  { name: '加载会话', start: 0, duration: 0.08 },
  { name: '问题规划', start: 0.08, duration: 0.15 },
  { name: '检索资料', start: 0.23, duration: 0.32 },
  { name: '证据重排', start: 0.55, duration: 0.18 },
  { name: '组装上下文', start: 0.73, duration: 0.05 },
  { name: '生成回答', start: 0.78, duration: 2.38 },
  { name: '校验与保存', start: 3.16, duration: 0.08 },
]

export const initialUsers = [
  { id: 1, name: '林晓', username: 'linxiao', role: '管理员', enabled: true, date: '今天 10:42' },
  { id: 2, name: '陈默', username: 'chenmo', role: '普通用户', enabled: true, date: '今天 09:35' },
  { id: 3, name: '许宁', username: 'xuning', role: '普通用户', enabled: true, date: '昨天 17:20' },
  {
    id: 4,
    name: '周远',
    username: 'zhouyuan',
    role: '普通用户',
    enabled: false,
    date: '09-12 14:08',
  },
  { id: 5, name: '李禾', username: 'lihe', role: '普通用户', enabled: true, date: '09-11 11:32' },
  { id: 6, name: '苏青', username: 'suqing', role: '普通用户', enabled: true, date: '09-10 08:45' },
]
