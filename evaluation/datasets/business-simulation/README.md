# 星澜科技业务仿真文档包

本目录是一套完全虚构的中文内部知识库语料，用于 JAgent RAG 评测。公司、人员、邮箱、编号、价格、日期和制度均为测试数据，不对应现实主体。

## 使用目标

- 验证单事实、精确数字、编号、邮箱和时间边界检索。
- 验证需要组合两份以上文档的多事实问题。
- 验证现行制度与已废止制度冲突时的来源选择。
- 验证相似制度、相似产品名和相似服务等级之间的区分。
- 验证资料不足时拒答，不根据常识补全公司内部规则。
- 验证 Markdown 标题、表格、列表和代码围栏的分块与定位。

## 文档清单

| 文件 | 主题 | 角色 |
| --- | --- | --- |
| `01-company-calendar.md` | 工作日、办公时间和特殊日期 | 现行 |
| `02-attendance.md` | 考勤、迟到、补卡和外勤 | 现行 |
| `03-leave-policy.md` | 年假、病假、事假和审批 | 现行 |
| `04-remote-work-current.md` | 远程办公 | 现行 |
| `05-remote-work-archived.md` | 远程办公旧规则 | 已废止干扰文档 |
| `06-travel-policy.md` | 出差申请、交通和住宿 | 现行 |
| `07-expense-policy.md` | 报销时限、材料和例外 | 现行 |
| `08-procurement-policy.md` | 采购金额分级审批 | 现行 |
| `09-vendor-onboarding.md` | 供应商准入和续期 | 现行 |
| `10-it-asset-loan.md` | 设备借用与归还 | 现行 |
| `11-account-access.md` | 账号、权限和 MFA | 现行 |
| `12-security-incident.md` | 安全事件等级与时限 | 现行 |
| `13-data-classification.md` | 数据分级和传输规则 | 现行 |
| `14-product-plans.md` | 产品套餐、价格和配额 | 现行 |
| `15-billing-refund.md` | 开票、退款与欠费 | 现行 |
| `16-support-intake.md` | 客服渠道和工单字段 | 现行 |
| `17-service-sla.md` | 服务等级响应和恢复目标 | 现行 |
| `18-release-change.md` | 发布窗口、变更审批和回滚 | 现行 |
| `19-project-acceptance.md` | 项目交付和验收 | 现行 |
| `20-training-certification.md` | 入职培训和认证 | 现行 |
| `21-facilities-visitors.md` | 门禁、访客和会议室 | 现行 |
| `22-contacts-directory.md` | 部门邮箱和服务热线 | 现行 |
| `23-record-retention.md` | 业务记录保留期限 | 现行 |
| `24-business-continuity.md` | 灾备、演练和恢复目标 | 现行 |
| `25-internal-newsletter.md` | 非制度性新闻和近似词 | 噪声文档 |
| `26-sales-contract-guide.md` | 商务报价、合同审批和交接 | 长流程文档 |
| `27-customer-incident-playbook.md` | 客户事故全流程处置 | 长流程文档 |
| `28-engineering-operations-handbook.md` | 值班、发布、数据库和密钥操作 | 长流程文档 |

## 建议导入方式

将 28 份编号文档分别上传到一个干净知识库，不要上传 `README.md`、`evaluation-spec.md` 或 `questions.json`，也不要与原来的 `employee-handbook.md` 混用。评测时保存本目录的 Git commit，并记录分块参数、Embedding 模型、生成模型和 Top K。

`05-remote-work-archived.md` 和 `25-internal-newsletter.md` 是有意保留的干扰文档，不应删除。前者用于测试版本判断，后者用于测试非规范性内容是否错误进入答案。

## 标注约定

配套的 `questions.json` 包含 60 道金标准问题，分为 40 道开发题和 20 道留出测试题。字段、评分方式和运行纪律见 `evaluation-spec.md`。

在仓库根目录运行 `./scripts/validate-business-dataset.ps1`，可以检查题号、分组、可回答性以及每条必要证据对应的文件、标题和原文。必要证据应始终引用最小、连续的原文，不要只记录关键词。

## 执行评测

后端和真实模型服务就绪后，在仓库根目录运行：

```powershell
# 调参使用的 40 道开发题
./scripts/evaluate-business.ps1 -Split development

# 形成候选方案后运行的 20 道留出测试题
./scripts/evaluate-business.ps1 -Split test
```

脚本默认创建临时知识库、上传并向量化 28 份文档、限定问答只检索该知识库，并在结束后删除临时库。添加 `-KeepKnowledgeBase` 可以保留索引；后续通过 `-KnowledgeBaseId <UUID>` 复用，但脚本会检查 28 份文档是否齐全且全部为 `READY`。

结果写入 `.artifacts/business-evaluation-<split>-<timestamp>.json`。使用 `-Limit 3` 可以先做三题冒烟检查，使用 `-EmbeddingModelId <模型ID>` 可以选择后端配置的候选 Embedding 模型。
