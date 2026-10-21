# 和 ChatGPT 并肩使用

让 ChatGPT 里的一个自定义 GPT 读写关系光谱的 Notion 数据库。App 和 GPT 用同一份数据：在 ChatGPT 里记下的事，App 同步后能看到；App 里的记录，GPT 也能查到。

最省事的做法是在 App 的「设置 → 和 ChatGPT 并肩使用」里点“复制 GPT 指令”，它会自动带上你的三张表 ID。这个目录里是同样内容的文件版（ID 是占位符，需要手动替换）。

## 步骤

1. 在 App 设置页先完成 Notion 连接（填 Token、一键创建三张表）。
2. 打开 chatgpt.com → 探索 GPT → 创建 → 配置（需要 ChatGPT Plus 或以上）。
3. 名称填“关系光谱搭档”，把“GPT 指令”整段粘贴到 Instructions。
4. 在“操作 / Actions”里新建，把“Action 结构”整段粘贴到 Schema。
5. 身份验证选 API Key → Bearer，粘贴你的 Notion Integration Token（secret_ 或 ntn_ 开头）。
6. 隐私政策链接可以填 https://www.notion.so/notion/Privacy-Policy-3468d120cf614d4c9014c09f6adc9091（只给自己用时可不发布）。
7. 保存后在预览里说“帮我查一下妈妈最近的记录”，第一次调用时点“允许”。

## 文件

- `instructions.txt`：粘贴到 GPT 的 Instructions，把三个 `<…的 ID>` 换成你的数据库 ID
- `notion-action.openapi.json`：粘贴到 GPT 的 Action Schema

## 常见问题

- **GPT 说找不到数据库**：Notion 里的那个父页面没有“连接”到你的 Integration，或者 ID 填错了。
- **写入时报字段错误**：数据库是用 App 的“一键创建三张表”建的吗？字段名必须和 App 建的一致。
- **App 里看不到 GPT 写的内容**：在 App 的设置页点“立即同步”。

由 `npm run gpt:kit` 生成，不要手动修改。
