# Super Productivity 手机端 AI 助手插件

在 Super Productivity（SP）**安卓 / iOS 客户端**里用自然语言管理任务：“明天下午 3 点写周报，预估 30 分钟”“今天还剩什么没做完”“把买牛奶标记完成”。

- 插件直接调用任何 **OpenAI 兼容**的大模型 API（DeepSeek、通义千问、Kimi、智谱、硅基流动、OpenRouter、OpenAI、Claude……）
- 任务工具在**手机本机**执行，直接读写 SP 数据，再由 SP 自带的同步（WebDAV / Dropbox / SuperSync）同步到电脑
- 远程 **MCP 服务器**可选：填了就把它的工具也交给模型用，连不上也不影响聊天

安装包：[`dist/sp-ai-assistant-0.2.0.zip`](dist/sp-ai-assistant-0.2.0.zip)

---

## 旧版（sp-mcp-assistant 0.1.2）为什么能连上但不能对话

对照 SP 最新源码（v19.1.0，`src/app/plugins/`）排查，问题出在架构上，改参数修不好：

| # | 问题 | 源码依据 | 后果 |
|---|------|----------|------|
| 1 | `PluginAPI.request` **硬性禁止局域网 IP**（192.168.x、10.x、127.x…），只有内置插件能放开 | `plugin-http.service.ts` 的 `_validateUrl`；`allowPrivateNetwork` 只给 `_isPluginBundled` | 连 `192.168.1.8` 的网关必然被宿主拒绝，只能退回 iframe 自己的 `fetch` |
| 2 | iframe 里每次 `PluginAPI` 调用都有 **30 秒硬超时** | `plugin-iframe.util.ts` 的 `callApi` → `'API call timeout'` | 大模型带工具的请求经常超过 30 秒 |
| 3 | iframe 的 `fetch` 受 **CORS** 约束 | 插件 iframe 与 SP 同源（`https://localhost`），访问网关属于跨域 | `/healthz` 是 GET，网关放行了；`/mcp`、`/v1/chat/completions` 是带 `Authorization` 的 POST，预检一旦不过就失败。这就是“能连接但不能对话” |
| 4 | 拿不到 `Mcp-Session-Id` | 代码里读的是 `raw.__sid`，它从来没被赋值；宿主代理本来也读不到响应头 | 有状态的 MCP 服务器会对 `tools/list` 返回 `400 No valid session ID`，**整轮对话直接报错**（旧版把 MCP 当成对话的前置条件） |
| 5 | 不认 SSE 响应 | 请求头写了 `Accept: text/event-stream`，但只会 `JSON.parse` | 服务器用 SSE 回复时报“网关返回非 JSON” |
| 6 | iframe 里**不存在** `getSecret/setSecret`、`getAllSimpleCounters`、`setSimpleCounterDate` | `plugin-iframe.util.ts` 的 `ALLOWED_IFRAME_API_METHODS` | Token 从来没存下来，重开后 401；习惯工具全部不可用 |
| 7 | 用了不存在的任务字段 | `currentTimestamp`、`plannedAt` 不是 SP 的 Task 字段 | “开始计时”“排到今天”实际没有效果 |
| 8 | 历史裁剪可能把 `tool` 消息和它对应的 `assistant.tool_calls` 拆开 | — | 聊久了以后模型接口返回 400 |

## 新版的做法

```
手机 SP 客户端
 └─ 插件 iframe（index.html）
     ├─ 大模型 API（HTTPS，公网）  ← 通过“原生 HTTP”通道直连，不用网关
     ├─ 本机工具 → PluginAPI → 手机上的 SP 数据 ──(SP 自带同步)──> 电脑
     └─ 可选：远程 MCP 服务器（Streamable HTTP）
```

关键点是**网络通道**。插件 iframe 带 `allow-same-origin`，与 SP 宿主同源，所以插件可以直接用宿主窗口的 `fetch`。安卓/iOS 版 SP 开启了 `CapacitorHttp`，宿主的 `fetch` 实际走原生 HTTP：**没有 CORS、能访问局域网、没有 30 秒限制、能读响应头**。插件会按顺序自动选择：

1. **原生 HTTP**（手机上默认）：宿主窗口的 `fetch`，也就是 CapacitorHttp
2. **浏览器 fetch**（桌面 / 网页版默认）：需要服务端允许 CORS
3. **SP 宿主代理** `PluginAPI.request`：只作最后的退路，只能访问 `manifest.json` 里 `allowedHosts` 列出的公网域名

只有“连不上”才会换下一个通道；只要拿到 HTTP 状态码（包括 4xx/5xx），就直接把错误原文显示出来。

## 安装与配置

1. 手机上下载 `dist/sp-ai-assistant-0.2.0.zip`
2. SP → 设置 → 插件 → 上传插件，选这个 zip，然后启用（旧的 “SP MCP Assistant” 可以卸载）
3. 打开侧边栏里的 “AI 助手” → 设置：
   - **服务商**：选一个，API 地址会自动填好
   - **API Key**：只保存在这台手机的 localStorage 里，不参与同步
   - **模型**：可以点“获取模型列表”从列表里选
4. 点“保存”会自动测试连接，看到 `✓ 大模型可用（原生 HTTP）` 就可以用了

### 本机工具

`list_tasks` · `get_task` · `create_task` · `update_task` · `complete_task` · `delete_task`（会弹确认框） · `start_timer` / `stop_timer` · `today_summary` · `list_projects` / `create_project` · `list_tags` · `list_habits` / `check_habit`

### 继续用你自己的网关 / MCP 服务器

- 网关提供 `/v1/chat/completions` 的话，服务商选“自定义 / 自建网关”，API 地址填 `http://192.168.1.8:8787/v1`，Key 填网关 token。手机上走原生 HTTP，局域网地址可以直接访问。
- MCP 地址填 `http://192.168.1.8:8787/mcp`。插件支持 JSON 和 SSE 两种响应，会带上 `Mcp-Session-Id`，会话过期后自动重连。
- 如果要在**桌面 / 网页版**里用浏览器 fetch 访问 MCP，服务器需要返回：
  ```
  Access-Control-Allow-Origin: *
  Access-Control-Allow-Headers: Content-Type, Accept, Authorization, Mcp-Session-Id, MCP-Protocol-Version
  Access-Control-Expose-Headers: Mcp-Session-Id
  ```
  注意 `Access-Control-Allow-Headers: *` **不包括** `Authorization`，必须显式写出来。

### 服务商不在列表里

要走“SP 宿主代理”通道的话，主机必须在 `allowedHosts` 里。重新打包时加上：

```bash
node scripts/pack.mjs --host api.example.com
```

手机上默认走原生 HTTP，通常不需要这一步。

## 开发

```
plugin/            插件源码（manifest.json、index.html、icon.svg）
scripts/pack.mjs   打包成 dist/*.zip（无依赖）
dev/               模拟 SP 宿主 + 模拟大模型 + 模拟 MCP 服务器，以及端到端测试
```

```bash
node scripts/pack.mjs     # 打包
node dev/e2e.mjs          # 端到端测试（需要 Playwright）
PORT=8799 node dev/mock-server.mjs   # 手动调试：打开 http://127.0.0.1:8799/harness.html?native=1
```

端到端测试在 Chromium 里跑两种宿主：模拟安卓（原生 HTTP）和模拟桌面（CORS 失败后退回宿主代理）。覆盖的场景有：带时间的建任务、自动建标签、先查询再完成的多轮工具调用、今日概况、经 SSE + 会话 id 调用远程 MCP 工具，以及重开后恢复对话。

**尚未在真机上验证**：模拟环境复现了 SP 的 iframe 注入方式和 sandbox 参数，但 CapacitorHttp 在真机上的行为只能在手机上确认。遇到问题时，把设置页“测试连接”的输出发出来，就能看出卡在哪一层。
