# 墨灵 App · 给 DeepSeek 的施工文档（第二批：M4–M6）

> 2026-10-21。第一批（M0–M3）你做得很好，CI 绿、`moling-latest` 已发布。
> 你做第一批的同时，Claude 补了两样东西：**大脑** `core/Brain.kt`（把导演、水道、预言、体系图、权柄串起来）和**总控那边接收墨灵的接口**。
> 这一批把 App 换到大脑上、接上总控、做出“今天”页。和第一批一样：照着一步一步做，不要跳步，不要加文档没写的功能。

---

## 1. 开工前

1. 在你的分支 `deepseek-moling` 上先合并 Claude 的分支：`git pull origin ccr-b3c7616e-xlohkd`。它已经包含你第一批的全部提交，所以是快进，不会有冲突。
   - 签名：`moling/app/debug.keystore` 保留你第一批的那个（它和总控的调试签名是同一个证书），**不要重新生成**——总控就是靠这个证书认墨灵的。
2. 先读 `partner-blueprint/CONTEXT.md` 和 `moling/app/src/main/java/com/moling/core/Brain.kt` 开头的注释。
3. 底线和第一批一样：不改 `core/` 和它的测试（觉得要改就写进 `moling/DEVLOG.md`）；不申请文档没写的权限；用户点“好”之前什么都不执行；网络 / 模型 / 总控出问题时界面不能卡；每步写 DEVLOG、CI 绿了再做下一步。

### 大脑速查（`com.moling.core`）

| 用途 | 调用 |
|---|---|
| 建大脑 | `Brain(phone, ask, store, mindStore, zk, config)` |
| 状态快照 | `brain.state(): BrainState`（`flow / atlas / topicId / predictions / days / talk / proposals / log / trust / userMax`）；`brain.tier()`；`brain.pending(now)` |
| 对话 | `brain.chat(text, now): ChatResult(text, proposals, byModel)` |
| 提议 | `brain.accept(id, now): Outcome(ok, message, opening)`、`brain.decline(id, now)`、`brain.retry(id, now)` |
| 今天 | `brain.planDay(now, force)`、`brain.confirmFlow(now): Proposal?`、`brain.reroute(cause, now, note)` |
| 定时 | `brain.tick(now, foregroundCategory, guard): List<Notice>`、`brain.night(now): String` |
| 设置 | `brain.setUserMax(tier)` |
| 导演 | `brain.director`（小窗、开场白以后用它，不要自己再建 `DirectorEngine`） |

`Proposal.state` 有六种：`PENDING`（等你决定）/ `DONE`（做了）/ `SENT`（交给总控了）/ `FAILED`（没送到总控）/ `DECLINED` / `EXPIRED`。界面上显示 `state.label` 就行。

所有 `brain.*` 方法自己加了锁，在后台协程（`Dispatchers.IO`）里调；调完把 `brain.state()` 推给界面。

---

## 2. M4：换上大脑

**改什么：**
- `BrainHost.kt` 里面换掉，**对外保持一个 `StateFlow<BrainState>`**，界面照旧 `collectAsState()`：
  - `init` 里建 `brain = Brain(phone = PhoneFacts, ask = MolingAsk, store = FileMindStore(File(filesDir, "mind/moling.json")), mindStore = FileMindStore(File(filesDir, "mind/director.json")), zk = ZkBridge, config = { DirectorConfig(persona = 设置里的人格, callUser = 设置里的称呼) })`。
  - 文件路径和第一批一样：`BrainState` 的字段名和你的 `MolingState` 一致（`flow / atlas / topicId / predictions / days / talk`），**旧的 `moling.json` 会被直接读进来**，对话记录不丢。`pending` 字段会被忽略（本来就只在内存里）。
  - 每个 `brain.*` 调用之后：`_state.value = brain.state()`。
  - 删掉你自己写的拼提示词、`Talk.parse`、`accept` 里的分支、`reroute`、`MolingState.kt`、`PendingAction`——这些大脑都做了。
- `PhoneFacts.kt`：只留手机这边的字段（`now`、`minute`、`quiet`）。主线、水道这些大脑会自己补，**删掉 `mainTitle` 那一段**。
- `ChatScreen.kt`：
  - 发送：`brain.chat(text, now)`；回复下面的卡片来自 `ChatResult.proposals`（文字用 `proposal.text`），不再用 `TalkAction`。
  - 卡片也要能在重开 App 后看到：卡片列表 = `state.proposals` 里 `createdAt` 在这条回复之后、下一条之前的那些，按 `state` 显示：`PENDING` → “好 / 不用”；`FAILED` → “重试” + `receipt`；其他 → 灰色的 `state.label`。
  - 点“好”→ `brain.accept(id, now)`，Toast 显示 `Outcome.message`；`Outcome.opening` 不为空时（去聊）弹一个对话框显示 `opening.text`，带“复制”按钮（打开演员下一批再做）。
  - “不用”→ `brain.decline`；“重试”→ `brain.retry`。

**验收：** 升级安装后对话记录还在；发“帮我把下午三点排成写申论”→ 出现一张卡片；点“好”→ Toast 说“没送到总控……”（总控还没接，这是对的），卡片变成“没送到总控”并出现“重试”。

---

## 3. M5：接上总控

**建什么：**
- `app/ZkBridge.kt`：实现 `com.moling.core.ZkPort`（`object ZkBridge`，`init(context)` 时存 applicationContext）：
  - `AndroidManifest.xml` 加 `<queries><package android:name="com.zongkong.app" /></queries>`（Android 11 起不加看不见总控）。
  - `val uri = Uri.parse("content://$ZK_AUTHORITY")`（常量在 `com.moling.core.link`）。
  - `status()`：`contentResolver.call(uri, ZK_STATUS, null, null)` → Bundle 转 `Map<String, String>`（每个键 `getString`）→ 有 `error` 键就返回 null，否则 `ZkWire.status(map)`。
  - `send(command)`：`ZkWire.toMap(command)` 全部 `putString` 进 Bundle → `contentResolver.call(uri, ZK_COMMAND, null, bundle)` → 转 Map → `ZkWire.receipt(map, System.currentTimeMillis())`。
  - 返回 null、抛任何异常（总控没装、被杀、版本太旧）都返回 null，**绝不能让墨灵崩**。
- 定时：App 在前台时每 60 秒调一次 `brain.tick(now)`（`lifecycleScope` + `repeatOnLifecycle(STARTED)`）；打开 App 时也调一次。这一批**不做后台定时、不做无障碍、不读前台应用**（`foregroundCategory` 传空串，`guard` 传 null）。
- 设置页加一行“总控：已连接（严管 / 正常）/ 连不上”，用 `brain.state().lastStatus` 判断（null = 连不上），旁边一个“重新连接”按钮调 `brain.tick(now)`。

**验收：**
1. 手机上装着新版总控（`zongkong-latest`）和墨灵。设置页显示“总控：已连接”。
2. 对话里让她排一个行动，点“好”→ Toast“交给总控了，在总控里确认后执行”；**总控弹出一条“墨灵建议”通知**。
3. 在总控的通知上点“执行”→ 总控里多了这一步（挂在事项「墨灵的水道」下）；点“不用”→ 什么都没发生。
4. 卸掉或强行停止总控 → 墨灵设置页显示“连不上”，点“好”的卡片变“没送到总控”，界面不卡。

---

## 4. M6：今天页

**界面（`ui/TodayScreen.kt`），从上到下：**
1. **提醒条**：`brain.tick` 返回的 `Notice`（只留最近 3 条，可划掉）。`proposalId` 不为空时，条上带“好 / 不用”（就是那条提议）。这一批不读前台应用，所以“水流偏了”的提醒还不会出现，现在主要是预言对账。
2. **今天的水道**：没有今天的（`state.flow?.day` 不是今天）→ 一个“排今天的水道”按钮，调 `brain.planDay(now)`（会调模型，显示“在排…”）。有了 → 显示 `Flow.render(flow)` 的每一行；当前这一条（`Flow.current(flow, Flow.minuteOf(now))`）高亮。下面两个按钮：“重排”（`planDay(now, force = true)`）和“定稿，交给总控”（`brain.confirmFlow(now)`，返回的提议显示成一张卡片，“好”才交）。已定稿（`flow.confirmed`）后“定稿”按钮变灰色“已定稿”。
3. **改道**：四个按钮 卡住了 / 累了 / 晚了 / 情况变了 → `brain.reroute(Cause.X, now)`，Toast 显示返回的那句话（空串时显示“现在没有在流的水道”）。
4. **等你决定的**：`brain.pending(now)` 的卡片（和对话页同一种卡片组件）。
5. **今天的预言**：`state.predictions` 里今天的，每条一行：`text`、把握 `p`（百分比）、对账后显示“对了 / 错了”。最下面一行 `Predict.scoreText(state.predictions)`。
6. **最近做了什么**：`state.log` 最后 10 条，小字。

**验收：** 打开今天页点“排今天的水道”→ 出现水道（填了密钥时还会有一两条预言；没密钥、又不满 3 天记录时没有预言，这是对的）；点“定稿，交给总控”→ 出现卡片，点“好”→ 总控弹通知；点“累了”→ 水道里插进一段“歇一会儿”，后面的往后顺延。

---

## 5. 这一批的底线

1. 不改 `core/` 下的文件。
2. 不申请文档没写的权限（这一批只多一条 `<queries>`）。
3. 提议在用户点“好”之前一律不执行；总控那边还要用户再点一次“执行”。
4. 总控连不上、模型失败时界面不卡，墨灵不崩。
5. 每一步写 DEVLOG，CI 绿了再做下一步。
