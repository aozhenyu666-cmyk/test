# dsh-go-bili —— B站速览（bili-peek）

> R4「插件生态与能力扩展」的第一件：让 **Agent 主动调用一个 B 站信息查询工具**，
> 拿到**结构化 JSON**，结果在 dsh-go App 里画成**原生卡片**，并且那个结果能**交回给 Agent 继续分析**。
>
> 同一份代码里带一个最小实现的**能力台账（cap-ledger）**：区分 installed / loaded /
> toolRegistered / lastRunOk 四个状态，失败时把原因也记下来。

## 一句话能力

| 你会说的话 | 发生了什么 |
| --- | --- |
| 「BV1uv411q7Mv 这视频讲了啥？」 | 模型调 `peek_bili_video`，拿到标题/UP/简介/播放/点赞/时长/分P → App 里出卡片 → 模型基于它继续分析 |
| 「看看这个链接 https://www.bilibili.com/video/BV…」 | 同上（链接、b23.tv 短链、av 号都认） |
| 「上次那个视频播放多少？」 | 状态条与面板里有最近查过的记录，模型也能再查一次 |

**只读**：只打 `api.bilibili.com/x/web-interface/view` 这一个公开接口。
不登录、不带 cookie、不碰评论/私信/动态 —— 本轮明确不做任何涉及账号权限的功能。

## 宿主契约（以本仓库源码为准，不是猜的）

插件只用这三样宿主能力：

| 能力 | 真实签名（源码位置） | 本插件怎么用 |
| --- | --- | --- |
| `ctx.tools.register` | `@deepseek-ai/dsh-tools` 0.2.1-rc.2，`lib/index.js` 的 `register(definition)` / `defineTool(options)`。register 会断言「`output.render` 必须是函数、`presentationMeta` 要么是函数要么不存在」，并**返回 disposer** | 注册 `peek_bili_video`；返回值是函数才算 `toolRegistered=true` |
| `ctx.effect` | cordis 4.0.5，`fiber.effect(execute)`：execute 立即跑，返回的函数在 fiber 卸载时执行 | 卸载时落盘台账、注销工具、清空推送者 |
| `ctx.dshGo` | dsh-go 桥接，**必须**写在 `ctx.inject(['dshGo'], …)` 里 | `strip('peek.strip')` + `panel('peek')` |

`defineTool` 的 `output.schema` 用的是它的**作者 DSL**，不是裸 JSON Schema：对象节点必须显式写
`additionalProperties`，property 上的 `required` 只能写 `true`。写错会在注册那一刻被拒。

## 存在性检查先于一切（不崩，也不假装）

| 宿主缺什么 | 本插件的行为 |
| --- | --- |
| 没有 `ctx.tools` | 不注册工具，`toolRegistered=false`，**不抛错**；面板/状态条照常起，面板上明写「否」 |
| `ctx.tools.register` 抛错（schema 被拒/重名） | 接住、记进 `lastRunMessage`、`toolRegistered=false`，插件继续跑 |
| 没有 dsh-go 桥接 | `ctx.inject(['dshGo'])` 的回调永远不执行，插件安静地只做工具 |
| 没有 `ctx.effect` | 跳过清理注册 |
| 没有全局 `fetch` | 工具返回 `ok:false, code:'E_NO_FETCH'`，并告诉用户去哪儿修 |

## 错误码（一个都不许静默）

| 码 | 什么时候 | 卡片上会写什么 |
| --- | --- | --- |
| `E_INPUT` | BV 号 / 链接认不出来 | 认不出的原文 + BV 号长什么样 |
| `E_NO_FETCH` | 宿主没有 fetch，也没注入端口 | 「缺依赖」+ 升级/换主机的提示 |
| `E_TIMEOUT` | 超过 `timeoutMs`（默认 12 s） | 超时 + 「过一会儿再试」 |
| `E_ABORTED` | 调用方取消（与超时**分开**） | 「这次被取消了」 |
| `E_NETWORK` | 连接失败（含 DNS） | 原始报错 + 检查网络 |
| `E_HTTP` | 非 2xx | HTTP 状态码 |
| `E_NOT_JSON` | 返回不是 JSON（网关页/劫持页） | 拿到的那一小段原文 |
| `E_BIZ` | B站业务码 ≠ 0（视频不存在等） | `code=-404 啥都木有` |
| `E_NO_DATA` | 有 `code:0` 但没数据 | 稍后重试 |
| `E_INTERNAL` | 插件自己的 bug | 「请把错误信息反馈」 |

**失败不是异常**：`execute` 不抛，而是返回 `{ ok:false, code, message, hint }`。
抛异常会让模型看不到原因；返回结构化失败，模型才能真正告诉用户「下一步怎么办」。

## 原生界面

| 位置 | 内容 | 预算 |
| --- | --- | --- |
| **工具卡**（`presentationMeta` 里的 `dsh-go/view"`） | 播放/点赞/时长三个数字 + UP/发布日期 + 简介 + 分P；失败时是「错误码 + 原因 + 下一步」的提醒条 | ≤ 8 KiB / 40 节点 / 6 层，只用白名单 8 个组件 |
| **状态条**（输入框上方） | 最近查的那个：`标题 557.7万`；没查过是「待命」；工具没注册是「未就绪」 | text ≤ 24 字 / ≤ 2 KiB，没变化不 emit |
| **面板 · B站速览** | 能力台账（四态）+ 查过/失败次数 + 最近 20 条记录 | ≤ 32 KiB / 120 节点 / 8 层 |

面板放在 `session.tab` / `chat.panel` / `features` 三处，由清单里的 `placement` 决定。

## 能力台账（cap-ledger）

`lib/ledger.mjs` 是**纯 JS、零依赖、可单测**的最小实现：

| 状态 | 谁来定 | 拿不到时 |
| --- | --- | --- |
| `installed` | 插件包在磁盘上、被 profile 列出（本插件起来就为 true） | `null`（写「未知」，不猜） |
| `loaded` | `apply()` 真的跑到了 | `null` |
| `toolRegistered` | `ctx.tools.register` **返回了 disposer** | `null` / `false` |
| `lastRunOk` | 每次 `execute` 结束 | `null` = **还没跑过**（不是失败） |

- 失败时同时记 `lastRunCode` + `lastRunMessage` —— 只记「失败了」等于没记。
- 落盘位置 `$DSH_HOME/cache/dsh-go-bili/ledger.json`（`DSH_HOME` **延迟读**，不是模块级常量）。
- 落盘失败（只读 home / 手机没权限）不改任何行为，只在内存里记 —— 观测本身不该变成故障源。
- `describe()` 在四态任一项不全时判 `healthy=false`：宁可保守，不报假绿。

## 测试

```sh
node --test            # 69 项，纯 Node、零依赖、离线；不碰真网络、不碰真 home
node lib/views.mjs     # 视图预算自测（面板/卡片/状态条）
```

覆盖：

| 组 | 内容 |
| --- | --- |
| 输入解析 | BV / av / 链接 / 短链 / `?p=2`；认不出来 → `E_INPUT` |
| 网络 | 成功、网络失败、超时、取消、HTTP 错、非 JSON、空响应、业务码错、缺 fetch —— **每条都有自己的码** |
| 结果形状 | 缺字段就不给那个键；分P 截到 20；失败也能出卡 |
| 插件加载 | 有/无 `ctx.tools`、register 抛错、无 `ctx.effect`、无桥接 |
| 工具调用 | 注入 fetch 端口、卡片/文字/值三件套、失败不抛、卡片超 8 KiB 返回 `{}` |
| 台账 | 四态彼此独立、失败原因落盘后可读回、坏文件不崩、`normalizeLedger` 不信输入 |
| 视图 | 工具卡白名单、面板四态文字、状态条 ≤24 字、30 条记录不超预算 |
| 桥接 | 用 `sdk/plugin-kit` 的**真测试宿主**跑清单解析 / 视图 / 数据推送 / 动作效果 |

`output.schema` 的 DSL 合规性有两层验证：一层是本仓库自带的逐条规则检查（无 DSH 也能跑），
另一层在找得到真 `@deepseek-ai/dsh-tools` 时用它的 `defineTool` + `validateJsonSchemaValue` 实测。

## 装

```sh
# 手机本机引擎（App › 终端），或 SSH / 中转主机上
dsh plugin --profile web add /绝对路径/dsh-go-bili
```

需要 DSH ≥ 0.2.1-rc.2。改了 `lib/*.js` 要重启内核才会重新加载。

## 文件

| 文件 | 作用 |
| --- | --- |
| `lib/index.js` | 插件本体：宿主探测、工具注册、台账、原生界面注册 |
| `lib/bili.mjs` | 纯逻辑：输入解析、可注入 fetch 的查询、错误码 |
| `lib/views.mjs` | 纯函数：工具卡 / 面板 / 状态条 + 预算校验（与 partner 那份同一套判据） |
| `lib/ledger.mjs` | cap-ledger 最小实现（四态 + 计数 + 规整 + 落盘序列化） |
| `support/dsh-tools.js` | 测试辅助：让 `@deepseek-ai/dsh-tools` 在 DSH 之外可解析（找得到真包就用真的） |
| `test/*.test.mjs` | `node --test` |

## 参考与授权

- 插件骨架照 `examples/plugins/dsh-go-partner`（同仓 R4 M3）与 `examples/plugins/dsh-go-example-weather`
  （同仓官方示例，MIT © DSH-TUI）的写法；**没有**照抄 Windows 专用代码。
- B站接口字段形状参考了 `dsh-bili-subagent` / `dsh-bili-whale`（都是 MIT）的公开知识，
  但**没有复用它们的任何代码**：那两个依赖 Python/uv/登录态/二维码，本插件零依赖、只读公开接口、不登录。
- 数据来源：B站公开 Web 接口，卡片与状态条上标明来源。

## 许可

MIT。
