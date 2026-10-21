# 墨灵 App · 给 DeepSeek 的施工文档（第四批：在你身边，P1–P7）

> 2026-10-21。第三批让墨灵在后台醒着；这一批让她**出现在你面前**：小窗、打开娱乐 App 时先答一问、桌面小部件、早上开口、分享进墨灵、从 GPT / Gemini 回来自动接住结论。
> 这一批全是墨灵 App 自己的事，不改总控、不改 `core/`。用到的大脑能力都已经有了（见速查）。
> **前提：第三批（M8–M16）做完并合进 Claude 的分支**——要用到后台心跳（M9）、前台应用（M10）、通知（M11）、导演卡片（M15）。
> 和第五批（`DEEPSEEK-5.md`）可以分开并行，文件不重叠（见第 1 节）。

---

## 1. 开工前

1. 新分支：`git fetch origin && git checkout -b deepseek-presence origin/ccr-b3c7616e-xlohkd`，再合并第三批（`git merge origin/deepseek-moling`，如果第三批还没合进 Claude 的分支）。
2. **只新建 / 改这些**（第五批不碰）：新目录 `app/overlay/`、`app/widget/`、`app/brief/`、新文件 `app/ShareActivity.kt`、`ui/PresenceSettings.kt`；`AndroidManifest.xml`；`BrainHost.kt`（只加函数）；`HeartService.kt`（只加调用）；`MainActivity.kt`（`onResume` 里加一句）；`SettingsScreen.kt` 只加**一行**入口到 `PresenceSettings`。
3. 不改 `core/`。觉得要改写进 DEVLOG。
4. **这一批允许的新权限只有一个：`SYSTEM_ALERT_WINDOW`**（悬浮窗，用户在系统设置里手动打开；没开就降级成通知，不能崩）。
5. 每个功能在 `PresenceSettings` 里都有自己的开关，默认值写在每一节里。

### 速查（都已写好）

| 用途 | 调用 |
|---|---|
| 导演 | `brain.director.currentCard(now)`、`onReply(reply)`、`opening(actor, cardId, now)`、`onSession(log)`、`snapshot()`（接力棒、日记） |
| 水道 | `state.flow`、`Flow.current(flow, Flow.minuteOf(now))`、`Channel.kind`（`MAIN / SIDE / SPRING / POOL / FREE`） |
| 提议 | `brain.suggest(command, now): Proposal?`、`brain.accept(id, now)`；命令：`ZkCommand.StartFocus(minutes, title, reason)`、`ZkCommand.Commit(what, due, check, reason)` |
| 改道 | `brain.reroute(Cause.STUCK, now)` |
| 体系图 | `brain.ingest(text, source, topicName, now)` |
| 总控 | `state.lastStatus`（`focus`、`quiet`、`hqActive`） |
| 第三批的 | `Usage`（前台包名、类别、连续分钟）、`HeartService` 心跳、`Notify` 通知、开场白对话框 |

所有 `brain.*` / `brain.director.*` 都经 `BrainHost` 的 `io(...)` 包装调用（`BrainHost` 里加 `suggest`、`sense`、`session` 等包装函数）。

---

## 2. P1：小窗（默认开，需要悬浮窗权限）

**是什么：** 屏幕边上一个 44dp 的圆点（墨灵的颜色），可以拖到左右边缘吸附；点一下展开成一张卡片，再点外面收回成圆点。

**什么时候出现：**
1. **去聊的时候**：用户在开场白对话框点了“打开 Gemini / ChatGPT”之后，`BrainHost` 记下一次“会话”（`sessionId = opening.sessionId`、`actor`、`startedAt`、`opening.cards`、`minutes`）。只要前台是这个演员（`Usage` 的包名等于 `actor.pkg`），圆点就在；离开演员超过 2 分钟算“离开一次”（`leaves + 1`）。
2. **导演有话说的时候**：心跳里 `brain.director.onSense(...)` 返回了卡片、而墨灵不在前台 → 圆点出现并轻轻跳一下（不展开）。用户 10 分钟不理它就收起，并回 `Reply.Dismissed(cardId, now, timeout = true)`。

**展开后的卡片：**
- 会话中：显示 `opening.cards` 里当前那一张（`title`、`ask`、`sayLines`），顶上一个计时（已聊 X 分钟 / 共 `minutes` 分钟）；按钮“下一问”（换到下一张）、“卡住了”（`Reply.Stuck`）、“收线”。
- 导演卡：和第三批今天页“此刻”卡片同样的按钮和逻辑（`choices` / 默认四个按钮）。
- “收线”：一个输入框（可以按麦克风说），提示“一句话：聊出了什么？下一步几点做？”→ 交给 P6 的收线流程。

**实现：**
- `app/overlay/OverlayService.kt`：普通服务（不需要再做前台服务，心跳服务已经是前台），用 `WindowManager` 加 `TYPE_APPLICATION_OVERLAY` 的 `ComposeView`（要给它设 `ViewTreeLifecycleOwner` / `SavedStateRegistryOwner`，网上标准做法）。
- 显示 / 隐藏由心跳和会话状态驱动：`OverlayService.show(context, what)` / `hide(context)`。
- 没有悬浮窗权限（`Settings.canDrawOverlays` 为 false）：会话中改成一条常驻通知“正在和 Gemini 聊 · 下一问：…”，导演卡片走第三批的通知。

**验收：**
1. 在 `PresenceSettings` 打开悬浮窗权限 → 对话里让她给一条“去聊”，点“好”→ 开场白对话框 → 打开 Gemini → 屏幕边上出现圆点，点开是第一问和计时。
2. 点“下一问”换卡；点“收线”说一句 → 小窗收起，墨灵日志里有“收线”（P6）。
3. 关掉悬浮窗权限再做一次 → 没有圆点，通知栏有“正在和 Gemini 聊”，不崩。

---

## 3. P2：先答一问（默认开，需要悬浮窗权限）

**什么时候：** 心跳发现前台应用类别从别的变成 `ENTERTAINMENT`，并且同时满足：今天的水道已定稿、此刻在 `MAIN / SIDE / SPRING` 水道的时间里、总控没在专注锁 / 总控时间 / 静默时段里、这条水道 20 分钟内没问过。

**怎么问：** 小窗直接展开成一张卡（不拦截、不遮全屏）：
> 现在是「{水道标题}」的时间。
> A　先做 15 分钟　　B　我卡住了　　C　我就玩 10 分钟

- **A** → `brain.suggest(ZkCommand.StartFocus(15, 标题, "先答一问：先做 15 分钟"), now)`；返回的提议如果是 `PENDING` 就**立刻** `brain.accept(id, now)`（他刚选了 A，这就是同意）；然后把墨灵切到前台（打开今天页）。
- **B** → `brain.reroute(Cause.STUCK, now)`，然后打开墨灵对话页，输入框里预填“我卡在”。
- **C** → `brain.suggest(ZkCommand.Commit("玩 10 分钟就回到「标题」", now + 10 分钟, "看前台应用", "先答一问：先玩 10 分钟"), now)`，同样 `PENDING` 就立刻 `accept`；小窗收起。10 分钟后心跳如果还看到娱乐类在前台，小窗再出来一次：“10 分钟到了，回「标题」吗？”（A / C 再 10 分钟，C 最多两次）。
- 30 秒不选就收起，记一条日志“先答一问没回”（`BrainHost` 里一个计数，调试页显示）。

**验收：** 水道定稿后，在主河道时间打开一个总控拦截名单里的应用 → 小窗弹出三选一；选 C → 总控那边多一条约定（总控通知或授权内直接记）；10 分钟后再问一次。

---

## 4. P3：桌面小部件（默认可用，用户自己加到桌面）

- `app/widget/NowWidget.kt`：`AppWidgetProvider` + `RemoteViews`（不引入 Glance）。2×1 和 4×1 两种尺寸：
  - 第一行：此刻水道的时间段和标题（没有水道写“今天还没排水道”）。
  - 第二行（4×1）：接力棒的下一问，或总控状态（“严管中”/“放行”）。
- 点击打开今天页。
- 心跳每次跑完调一次 `NowWidget.update(context)`（没加到桌面时什么都不做）。

**验收：** 长按桌面加“墨灵”小部件 → 显示此刻水道；水道变了，一分钟内小部件跟着变。

---

## 5. P4：早上开口和到点提醒（默认开）

- **晨报**：心跳服务里动态注册 `ACTION_USER_PRESENT`（解锁）。每天 6:00 之后第一次解锁、且设置里“朗读”开着、且不在静默时段 → 用系统 TTS 念一段 60 秒以内的话，同时发一条通知（内容同样）：
  1. 昨天：`brain.director.snapshot().diary` 最后一条的第一句（没有就跳过）；
  2. 今天：`flow.intent`（没有水道就说“今天还没排水道，我先排一个草稿？”）；
  3. 第一问：接力棒的 `nextAsk`（没有就跳过）；
  4. 预言：今天的第一条预言（`text`），没有就跳过。
  拼文字放在 `app/brief/Brief.kt` 里的纯函数里（写单元测试：几种缺字段的情况都能拼出通顺的话）。
- **到点提醒**：心跳发现某条 `MAIN` 或 `SIDE` 水道 5 分钟后开始 → 一条通知“5 分钟后：{标题}（做完 = {doneWhen}）”，朗读开着就念一句。同一条水道只提醒一次。

**验收：** 第二天早上第一次解锁听到晨报、通知栏有同样的文字；主河道开始前 5 分钟收到提醒。

---

## 6. P5：分享进墨灵（默认开）

- `ShareActivity`（透明主题，`exported="true"`，intent-filter：`ACTION_SEND`、`text/plain`）：收到文字后弹一个小对话框：
  - “收进体系图”：主题输入框（默认当前主题名）→ `brain.ingest(text, "分享", 主题, now)` → Toast“收进 N 条”；
  - “跟墨灵说”：打开对话页，输入框预填这段文字（截到 500 字）；
  - “算了”。
- 图片分享这一批不做。

**验收：** 在浏览器或 GPT 里选中一段文字 → 分享 → 墨灵 → 收进体系图 → 体系页出现新论断。

---

## 7. P6：从演员那里回来，自动接住收线（默认开）

**流程：** 有进行中的会话（P1 记下的）时，墨灵回到前台（`MainActivity.onResume`），或在小窗里点“收线”：
1. 前台时读一次剪贴板（`ClipboardManager.primaryClip`，只读文字）。如果文字里有“收线”或“下一步”，或者长度 ≥ 40 且是会话开始之后复制的（`ClipDescription.timestamp`，API 26 起有），就当作这次的收线原文，弹一个确认框“这是刚才聊出来的吗？”（显示前 200 字）——**用户点“是”才用**。
2. 没有可用的剪贴板内容 → 弹输入框“一句话：聊出了什么？下一步几点做？”（可以按麦克风）。
3. 用户给了原文后：
   - `brain.director.onSession(SessionLog(sessionId, actor, startedAt, endedAt = now, presentMs = 在演员前台的总毫秒（Usage 算）, leaves, handoff = "复制" 或 "说", handoffOk = true, conclusion = 原文))`，返回的卡片显示在今天页“此刻”。
   - 原文 ≥ 80 字时，再问一句“要收进体系图吗？”→ `brain.ingest(原文, actor.label, 当前主题, now)`。
   - 会话结束（清掉 `BrainHost` 里的会话），小窗收起。
4. 会话开始 3 小时还没收线：自动结束，`conclusion = ""`（导演会记一条“没收线”）。

**验收：** 去 Gemini 聊几分钟，让它按开场白最后的格式说一句收线，复制 → 回到墨灵 → 弹“这是刚才聊出来的吗？”→ 点是 → 今天页出现导演的下一张卡，体系页多了论断。

---

## 8. P7：设置和调试

- `ui/PresenceSettings.kt`：每个功能一个开关（小窗、先答一问、晨报、到点提醒、分享、自动接住收线）+ 悬浮窗权限状态和“去打开”（`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`）。设置页只加一行入口“在你身边”。
- 调试页（第三批 M8）加一块“在你身边”：悬浮窗权限、当前会话（演员、开始时间、离开次数）、今天先答一问问了几次 / 各选了什么 / 没回几次、晨报今天念过没有。
- DEVLOG 末尾写“第四批真机验收清单”，按一天的顺序排：早上解锁晨报 → 小部件 → 到点提醒 → 打开娱乐先答一问 → 去聊小窗 → 回来接住收线 → 分享。

---

## 9. 底线

1. 不改 `core/`，不改总控。
2. 新权限只有 `SYSTEM_ALERT_WINDOW`；没开就降级成通知。
3. 小窗和先答一问**不拦截、不遮全屏、不阻止任何操作**；拦截只归总控。
4. 先答一问里他选的 A / C 就是同意，可以立即 `accept`；其他任何提议仍然要他点“好”（或由大脑按权柄和授权书处理）。
5. 剪贴板只在墨灵在前台时读一次，只读文字，**用户确认后才用**，不存原始剪贴板历史。
6. 每步写 DEVLOG，CI 绿了再做下一步。
