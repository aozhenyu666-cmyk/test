# 关系光谱

一个帮你看清人际关系和利益博弈的 App。它不是记账本，也不教人算计。它做四件事：

- **冷静阀**：想发火的时候，先把想发的话放进来。AI 会告诉你风险，并给三档可以直接复制的回复
- **潜台词解码**：对方推脱、冷淡、话里有话时，列出几种可能的解释和概率，告诉你怎么低成本验证
- **博弈拆解**：老板画饼、导师压榨、相亲拉扯、商业谈判，拆出各方的真实诉求、筹码和底牌
- **关系提醒**：父母的生日和关怀倒计时，朋友的软拒绝信号，单向付出预警

设计思路和算法见 [docs/DESIGN.md](docs/DESIGN.md)。

| 看板 | 记一笔 | 关系详情 | 博弈沙盘 |
|---|---|---|---|
| ![](docs/screenshots/dashboard.png) | ![](docs/screenshots/log.png) | ![](docs/screenshots/person.png) | ![](docs/screenshots/sandbox.png) |

## 快速开始

需要 Node.js 18 或更高版本。

```bash
cd relation-spectrum
npm install
cp .env.example .env    # 填上 ANTHROPIC_API_KEY（也可以稍后在网页“设置”里填）
npm start
```

打开 http://127.0.0.1:8787。第一次打开可以点“先看看示例数据”。

**用手机访问**：在 `.env` 里设置 `HOST=0.0.0.0` 和 `ACCESS_TOKEN=随便一串口令`，手机和电脑连同一个 Wi-Fi，打开 `http://电脑的IP:8787`，在设置页填同样的口令。浏览器菜单里选“添加到主屏幕”，就能像 App 一样用。

## 大模型

| 服务商 | 配置 |
|---|---|
| Claude（默认） | `ANTHROPIC_API_KEY`，模型默认 `claude-opus-5-5`，可改成 `claude-sonnet-5-5` 或 `claude-haiku-5-5` 省钱 |
| OpenAI 兼容（DeepSeek、Kimi、通义等） | `LLM_PROVIDER=openai`，再填 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_MODEL` |

Claude 用结构化输出保证返回合法 JSON，并开启了拒答时自动换模型重试（`fallbacks: "default"`）。

## Notion 同步（可选）

1. 在 https://www.notion.so/my-integrations 新建一个 Integration，复制 Token
2. 在 Notion 里建一个空页面，右上角菜单 → 连接 → 选你的 Integration
3. 在 App 的设置页填 Token 和这个页面的链接，点“一键创建三张表”，再点“立即同步”

也可以用命令行建表：`npm run notion:init`（需要在 `.env` 里填 `NOTION_TOKEN` 和 `NOTION_PARENT_PAGE_ID`）。

同步是双向的：先拉取 Notion 上的改动，再推送本地改动；两边都改过的记录，以后改的为准。在 Notion 里删除的页面不会删掉本地记录。

## 目录

```
server/
  server.js         本地服务器：网页、/api/analyze、Notion 转发
  llm.js            Claude / OpenAI 兼容接口
  notion-init.js    命令行建 Notion 表
web/js/
  core/models.js        数据结构和枚举
  core/scoring.js       关系温度、失衡度、预警算法
  core/prompts.js       三个 AI 引擎的提示词和 JSON Schema
  core/notion-schema.js Notion 字段定义和映射
  store/db.js           IndexedDB 本地存储
  services/             API 调用、Notion 同步、设置
  views/                各个页面
  data/                 示例数据和参考案例
test/core.test.js   算法、提示词、Notion 映射的测试
```

## 测试

```bash
npm test
```

## 隐私

数据默认只存在浏览器里。做 AI 分析时，只会把你选中的那个人的档案和最近 10 条记录发给大模型。建议给真人起代号。
