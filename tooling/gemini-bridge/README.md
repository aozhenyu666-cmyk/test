# 大肥鱼 · Gemini 桥

让 Gemini（网页版 / App 里的「自定义应用」，即 MCP）连上大肥鱼：

- **能看**：你今天的时间轴验收点（开始 / 截止 / 保底 / 做到多少）、明天已封的、是不是锁着、正在跑的局、娱乐余额、截止日、公约；电脑上的 Obsidian 笔记（日记 / 私密目录不读）。
- **能写**：交回执（问过至少 2 个问题、判「过」的才算进验收点）、加验收点（只能加）、提议规矩（你在手机「公约」页批了才生效）。
- **不能**：解锁、加娱乐额度、豁免、改小、删除。桥上根本没有这些工具。

```
Gemini ──HTTPS──► Tailscale Funnel ──► 电脑 127.0.0.1:8787（这个桥）◄──HTTPS 每 ~20 秒── 手机 DSH Go 引擎
```

手机引擎只听 127.0.0.1，手机上 NekoBox 和 Tailscale 又不能同时开（安卓同一时间只能有一个 VPN），
所以不是电脑去连手机，而是**手机主动来同步**：上行「今天」的快照，下行 Gemini 写的命令。
手机通过公网访问 `*.ts.net`，开着 NekoBox 也行。

## 一、电脑上准备（一次）

1. 装 **Node.js 20 或更新**（`node -v` 能看到版本）。
2. 装 **Tailscale** 并登录。在 Tailscale 管理后台打开 **HTTPS 证书** 和 **Funnel**
   （Access controls 里允许这台机器用 funnel；第一次跑 `tailscale funnel` 时它会给你一个开通链接）。
3. 记下你的 Obsidian 笔记库路径（例：`D:\Obsidian\我的库`）。

## 二、启动桥

```powershell
cd 仓库目录
node tooling\gemini-bridge\server.mjs --public-url https://你的电脑名.你的tailnet.ts.net --vault "D:\Obsidian\我的库"
```

启动时会打出两行，**抄下来**：

- `配对口令`：Gemini 授权页输一次。
- `手机密钥`：填进手机「Gemini 桥」页。

忘了就再看：`node tooling\gemini-bridge\server.mjs --show-secrets`。
数据存在 `%USERPROFILE%\.dafeiyu-bridge\bridge-state.json`（令牌只存哈希）。

另开一个终端，把端口开到公网：

```powershell
tailscale funnel --bg 8787
tailscale funnel status      # 看公网地址，应该就是上面 --public-url 那个
```

浏览器打开 `https://你的电脑名.你的tailnet.ts.net/`，能看到「大肥鱼 · Gemini 桥 运行中」就对了。

## 三、Gemini 里加自定义应用

1. 打开 gemini.google.com → **设置** → **应用（Connected apps）** → **添加自定义应用**。
2. 名字：`大肥鱼`；地址：`https://你的电脑名.你的tailnet.ts.net/mcp`。
3. Client ID / Secret **留空**（桥支持动态注册，Gemini 会自己注册）。
4. 点连接 → 跳到桥的授权页 → 输**配对口令** → 授权。口令错 5 次会锁 15 分钟。
5. 回到 Gemini 说一句「看看我今天」，它应该会调 `dafeiyu_today`。

## 四、手机上

DSH Go → 伴侣中枢 → 开口与连麦 → **Gemini 桥**：

- 地址：`https://你的电脑名.你的tailnet.ts.net`（**不带** `/mcp`）
- 手机密钥：电脑上打出来的那串 `dfy_…`
- 点「保存并连接」，看到「连着 / 在线」就好了。

连上以后，大肥鱼拉起 Gemini 时给它的背景里会多一段「你连着大肥鱼应用：先看今天、写验收点、最后交回执」。

## 五、开机自动跑（Windows）

把 `start-bridge.example.cmd` 复制一份成 `start-bridge.cmd`，改里面的地址和笔记库路径，
然后按 `Win+R` 输 `shell:startup`，把它的快捷方式放进去。`tailscale funnel --bg` 是持久的，重启后还在。

电脑关机时 Gemini 连不上桥（工具会报错），手机那边只是同步失败，什么都不会被放松。

## 六、出问题

| 现象 | 看哪里 |
|---|---|
| 手机页「最近一次没连上：手机密钥不对（401）」 | 密钥抄错了，`--show-secrets` 再看一遍 |
| 手机页「连不上（10 秒超时）」 | 电脑没开 / 桥没跑 / funnel 没开；浏览器打开公网地址试试 |
| Gemini 说连不上 / 注册失败 | 地址要带 `/mcp`；看桥的终端输出有没有 `register 400` |
| Gemini 一直说「手机 N 分钟没同步」 | 手机引擎没跑（看通知栏常驻通知），或手机断网 |
| 想让 Gemini 全部重新授权 | `node tooling\gemini-bridge\server.mjs --revoke` |
| 想换配对口令 / 手机密钥 | 删掉 `%USERPROFILE%\.dafeiyu-bridge\bridge-state.json` 重启（Gemini 和手机都要重配） |

## 七、技术细节（给以后改代码的人）

- MCP：Streamable HTTP，`POST /mcp`，JSON 应答（不开 SSE）；协议版本 2025-11-25 / 2025-06-18 / 2025-03-26 / 2024-11-05。
- OAuth 2.1：受保护资源元数据（RFC 9728）、授权服务器元数据（RFC 8414，`/.well-known/openid-configuration` 也答）、
  动态注册（RFC 7591）、PKCE S256、refresh_token 轮换。
  Gemini 一次注册 6 个回调（`oauth-redirect` / `-sandbox` / `-test` `.googleusercontent.com` × `/r/`、`/a/`），全收；
  上限 10 个。第三方实测来源：
  [android-remote-control-mcp#235](https://github.com/danielealbano/android-remote-control-mcp/issues/235)、
  [tutor-mcp#199](https://github.com/ArnaudGuiovanna/tutor-mcp/issues/199)、
  [litellm#46640](https://github.com/BerriAI/litellm/issues/46640)。
- 手机同步：`POST /bridge/sync`（Bearer 手机密钥）`{snapshot, results}` → `{commands}`；手机处理完马上再同步一次回结果，
  写工具最多等 20 秒拿结果，等不到就告诉 Gemini「已排队」。命令按 id 去重，重放不会重复处理。
- 测试：`cd tooling/gemini-bridge && node --test test/*.test.mjs`（含插件 ↔ 桥 ↔ 模拟 Gemini 的整条链）。
