# 交给 DeepSeek 的小任务

这些任务边界清楚、只动边缘文件，适合交给 DeepSeek 写。核心（`engine/companion.py`、`gate.py`、`state.py`、`mind.py`、N.E.K.O. 插件与补丁）由 Claude 负责，DeepSeek 不改这些文件。

| 任务 | 文件 | 改哪些文件 | 难度 |
|---|---|---|---|
| D1 ChatGPT 模拟页面和端到端测试 | [D1-chatgpt-e2e.md](D1-chatgpt-e2e.md) | 新建 2 个测试文件 | 中 |
| D2 本场讨论导出成 Markdown 笔记 | [D2-archive-markdown.md](D2-archive-markdown.md) | 新建 `engine/archive.py`、测试；`httpapi.py` 加一个路由；面板加一个链接 | 中 |
| D3 扩展弹窗显示采集诊断 | [D3-popup-diagnostics.md](D3-popup-diagnostics.md) | `capture.js` 末尾加监听；`popup.html`、`popup.js` | 小 |
| D4 面板显示延迟表 | [D4-dashboard-metrics.md](D4-dashboard-metrics.md) | 只改 `static/dashboard.html` | 小 |

四个任务互不依赖，可以同时做。

## 怎么交给 DeepSeek

**方式一：用 Aider（推荐，开源命令行工具，支持 DeepSeek API，会直接改文件）**

```powershell
pip install aider-chat
$env:DEEPSEEK_API_KEY = "你的 DeepSeek Key"
cd <本仓库根目录>
git checkout -b deepseek-d1          # 每个任务一个分支，方便我审核

# D1
aider --model deepseek/deepseek-chat --no-auto-commits `
  --read bansheng/browser_extension/adapters.js --read bansheng/tests/e2e_extension.mjs --read bansheng/tests/fixtures/gemini_sim.html `
  --message-file bansheng/docs/deepseek/D1-chatgpt-e2e.md

# D2
aider --model deepseek/deepseek-chat --no-auto-commits `
  --read bansheng/neko_plugin/bansheng/engine/state.py --read bansheng/neko_plugin/bansheng/engine/events.py `
  bansheng/neko_plugin/bansheng/engine/httpapi.py bansheng/neko_plugin/bansheng/engine/static/dashboard.html `
  --message-file bansheng/docs/deepseek/D2-archive-markdown.md

# D3
aider --model deepseek/deepseek-chat --no-auto-commits `
  bansheng/browser_extension/capture.js bansheng/browser_extension/popup.html bansheng/browser_extension/popup.js `
  --message-file bansheng/docs/deepseek/D3-popup-diagnostics.md

# D4
aider --model deepseek/deepseek-chat --no-auto-commits `
  --read bansheng/neko_plugin/bansheng/engine/companion.py bansheng/neko_plugin/bansheng/engine/static/dashboard.html `
  --message-file bansheng/docs/deepseek/D4-dashboard-metrics.md
```

`--read` 的文件只给它参考，不允许改；不带 `--read` 的才是它要改的文件。D1、D2 要新建的文件，它会询问是否创建，选“是”。

**方式二：DeepSeek 网页对话**：把任务文件全文粘贴进去，再把“需要阅读的文件”逐个贴给它，把它给出的完整文件内容保存到对应位置。

## 做完之后

每个任务文件末尾都有“验收命令”，先在本地跑一遍。然后把分支推上来（或把改动贴给我），我审核后合并。验收没过也可以直接交给我，写明卡在哪里。
