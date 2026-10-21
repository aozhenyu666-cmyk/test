# 墨灵 App · 给 DeepSeek 的施工文档（第三批：M8–M16）

> 2026-10-21。第二批（M4–M7）做得很好，已经合进 Claude 的分支。
> 这一批比较大：让墨灵**离开 App 也在身边**（后台心跳、看前台应用、通知、早晚自动），再把**总控的结局、体系图、导演卡片**接全，最后补**备份**。
> 照着一步一步做，每一步 CI 绿了再做下一步，不要加文档没写的功能。和前两批一样，每步写 DEVLOG（做了什么 / 为什么 / 没做什么 / 用户怎么验收）。

---

## 1. 开工前

1. 在 `deepseek-moling` 上 `git pull origin ccr-b3c7616e-xlohkd`（快进，没有冲突）。Claude 在大脑里补了这一批要用的几样东西，见下面的速查。
2. 不改 `core/` 和它的测试；觉得要改，写进 DEVLOG，用户会转给 Claude。
3. **这一批允许的权限只有这几个**（别的都不加）：
   - `POST_NOTIFICATIONS`（Android 13 起要用户同意）
   - `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`（常驻心跳）
   - `RECEIVE_BOOT_COMPLETED`（开机后恢复心跳）
   - `PACKAGE_USAGE_STATS`（看前台应用；用户在系统“使用情况访问”里手动打开，加 `tools:ignore="ProtectedPermissions"`）
4. 所有 `brain.*` 和 `brain.director.*` 的调用都走 `BrainHost` 里的 `io(...)` 包装（IO 线程、异常兜底、调完推状态）。

### 大脑这一批新增的（`com.moling.core`）

| 用途 | 调用 |
|---|---|
| 总控结局 | `ZkPort.result(id): ZkResult?`（你在 `ZkBridge` 里实现）；`Proposal.zkIds`、`Proposal.zkOutcome`（空 = 还不知道，例如“总控执行了”“你在总控里点了不用”）；`tick` 会自动去问，问到了返回 `Notice(kind = "zk_result")` |
| 体系图 | `brain.verifyNode(nodeId, result, now): Boolean`、`brain.removeNode(nodeId)`、`brain.synthesize(now): String?`（当前主题的归纳，存在 `state.synthesis[topicId]`）、`brain.askGap(question, actor, now): Proposal?`（缺口 → 一条“去聊”提议） |
| 定时判断 | `brain.needsPlan(now)`（6:00–22:00 且今天还没水道）、`brain.needsNight(now)`（23:30–4:00 且这一夜还没整理） |
| 前台类别 | `BrainFacts.category(pkg, state.lastStatus)` → `ENTERTAINMENT`（总控拦截名单里的）/ `ACTOR`（Gemini、ChatGPT）/ `WORK`（总控、墨灵、Notion）/ `OTHER` / `""`（看不见） |

`Notice.kind` 现在有：`prediction`（预言对账）、`zk_result`（总控结局）、`guard`（总控掉线）、`drift`（水流偏了，带 `proposalId`）。

---

## 2. M8：调试页

先做这个，后面每一步在真机上出问题都靠它看。

**建什么：** 设置页最下面一个“调试”入口 → `ui/DebugScreen.kt`，只读，从上到下：
1. 权柄：`brain.tier().label`、`state.trust` 的六个数。
2. 总控：`state.lastStatus` 每个字段一行（null 时写“连不上”）；最后一次读到的时间（`lastStatus.at` 换成 HH:mm:ss）。
3. 提议：按 `state` 分组计数，再列最近 10 条（`text`、`state.label`、`zkOutcome`、`receipt`）。
4. 导演：`brain.director.snapshot()` 里的接力棒（question / nextAsk）、议程条数、今天打扰了几次（`pushesToday`）、今天模型调用（`costToday(dayKey(now))`）。
5. 后台：心跳上次跑的时间、看前台应用是否有权限、当前前台包名和类别（M9 之后才有，先显示“—”）。
6. `state.log` 全部，最新的在上，等宽小字；顶部一个“复制全部”按钮（复制上面所有文字，方便发给 Claude）。

**验收：** 设置 → 调试，能看到上面六块；点“复制全部”能粘贴出来。

---

## 3. M9：后台心跳

**建什么：**
- `app/HeartService.kt`：前台服务（`foregroundServiceType="specialUse"`，`<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="个人 AI 伙伴：每分钟看一眼总控和今天的安排" />`）。常驻通知一条，低优先级、不出声：标题“墨灵在”，正文是今天水道里此刻该在的那一条（`Flow.current`），没有水道写“今天还没排水道”。点通知打开“今天”页。
- 服务里每 60 秒一次 `BrainHost.tickNow()`（M10 之后这里会多传前台类别和 guard）。屏幕关着时改成每 5 分钟一次（`PowerManager.isInteractive`），省电。
- `MainActivity` 原来的前台心跳保留（打开 App 立刻跳一次），但如果服务在跑，就不再每 60 秒重复跳。
- 设置页加开关“后台陪着（常驻通知）”，默认**开**；关掉就停服务。第一次打开时如果没有通知权限，先请求 `POST_NOTIFICATIONS`。
- `app/BootReceiver.kt`：`BOOT_COMPLETED` 和 `MY_PACKAGE_REPLACED` 时，开关开着就启动服务。

**验收：**
1. 打开墨灵 → 通知栏出现“墨灵在 · …”。
2. 回到桌面 3 分钟，再看调试页“心跳上次跑的时间”是刚才。
3. 设置里关掉开关 → 常驻通知消失。
4. 重启手机 → 常驻通知自己回来（开关开着时）。

---

## 4. M10：看前台应用和总控健康

**建什么：**
- `app/Usage.kt`：用 `UsageStatsManager.queryEvents(now - 10 分钟, now)` 找最后一个 `ACTIVITY_RESUMED`（API 29 以下用 `MOVE_TO_FOREGROUND`）事件的包名，作为当前前台应用；同时算出它连续在前台多少分钟（往前找到这个包第一次进前台、中间没有别的包进前台的那个时刻）。没权限或拿不到 → 包名空串。
- 权限：设置页加一行“看前台应用：已允许 / 去打开”，“去打开”跳 `Settings.ACTION_USAGE_ACCESS_SETTINGS`。判断有没有权限用 `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, ...)`（API 29 以下用 `checkOpNoThrow`）。
- `PhoneFacts`：补上 `foregroundPkg`、`foregroundLabel`（`packageManager.getApplicationLabel`，拿不到用包名）、`foregroundCategory`（`BrainFacts.category(pkg, BrainHost.state.value.lastStatus)`）、`foregroundMinutes`；没权限时把 `"前台应用（没开使用情况访问）"` 写进 `unknown`。
- 心跳（服务和 `MainActivity` 都一样）改成 `brain.tick(now, category, guard)`：
  - `category` = 上面算出的类别。
  - `guard` = `GuardSample(now, zkReachable = 这次读到了总控, secureSettingsOn = lastStatus.accessibilityOn, heartbeatAt = lastStatus.heartbeatAt, userDisabled = lastStatus.userDisabled, screenOn = isInteractive, canRepair = false)`；总控连不上时 `zkReachable = false`、其余填 false / 0。**`canRepair` 永远传 false**（墨灵不修总控）。
- 调试页第 5 块填上：权限、前台包名、类别、连续分钟。

**验收：**
1. 设置里打开“使用情况访问”→ 调试页显示当前前台是墨灵、类别 WORK。
2. 今天的水道定稿后，在主河道时间里打开一个总控拦截名单里的 App 刷 11 分钟 → 回到墨灵，今天页提醒条出现“水该在「…」……”和“好 / 不用”（开专注锁的提议）。
3. 在总控里把无障碍关掉 5 分钟 → 墨灵提醒条出现“总控的无障碍被关了……我不去修、也不冻结 App……”。

---

## 5. M11：通知

**建什么：**
- `app/Notify.kt`：两个通知渠道——“墨灵提醒”（默认重要性）和 M9 的常驻渠道（低）。
- 心跳拿到的每条 `Notice` 都发成一条系统通知（同一 `kind` 的新通知替换旧的：用 `kind.hashCode()` 当通知 id）：
  - 标题：`prediction` →“对账”；`zk_result` →“总控那边”；`guard` →“总控”；`drift` →“水流偏了”。正文是 `notice.text`。
  - `proposalId` 不为空时带“好 / 不用”两个按钮：`app/NotifyReceiver.kt`（`exported="false"`）收到后调 `BrainHost.accept / decline`（`goAsync()` + 协程），再把这条通知更新成 `Outcome.message`。
  - 点通知本身打开“今天”页。
- 安静：`lastStatus.quiet` 为 true，或北京时间 23:30–07:00，通知 `setSilent(true)`（照发，但不响不震）。
- 今天页的提醒条照旧显示（通知是额外的，不替代提醒条）。

**验收：**
1. 让一条交给总控的提议在总控里点“执行”→ 一分钟内墨灵通知“总控那边：总控执行了：……”。
2. M10 第 2 步的偏离提醒也会变成通知，带“好 / 不用”；在通知上点“好”→ 总控弹“墨灵建议”。
3. 晚上 23:30 以后的通知不响。

---

## 6. M12：早晚自动

**建什么（都在心跳里做）：**
- 设置页加开关“早上自动排水道”（默认开）。开着且 `brain.needsPlan(now)` 为 true、手机亮屏时 → `brain.planDay(now)` → 发通知“今天的水道排好了（草稿）”，正文是 `plan.intent`，点开进今天页。**只排草稿，不定稿、不交给总控**（定稿要用户自己点）。
- `brain.needsNight(now)` 为 true → `brain.night(now)` → 返回的那段话不为空就发一条安静的通知“今天的账”（第二天早上看到）。
- 同一个判断一分钟内只做一次（心跳可能叠在一起）：用 `BrainHost` 里一个 `Mutex` 或 `AtomicBoolean` 挡住重入。

**验收：**
1. 早上第一次点亮屏幕后一分钟内，收到“今天的水道排好了（草稿）”，今天页已经有水道、没定稿。
2. 设置里关掉“早上自动排水道”→ 第二天不再自动排。
3. 23:30 之后，调试页的日志里有“夜间整理：……”，第二天早上通知栏有“今天的账”。

---

## 7. M13：总控的结局

**建什么：**
- `ZkBridge.result(id)`：`contentResolver.call(uri, ZK_RESULT, id, null)` → Bundle 转 Map → `ZkWire.result(map)`；返回 null / 抛异常 → null。（`ZK_RESULT` 在 `com.moling.core.link`。）
- `ProposalCard`：状态是 `SENT` 时，下面一行小字：`zkOutcome` 不为空就显示它，否则“等你在总控里确认”。
- 调试页的提议列表也显示 `zkOutcome`（M8 已经留了位置）。

**验收：** 交一条给总控，墨灵卡片显示“交给总控了 · 等你在总控里确认”；在总控通知上点“执行”→ 一分钟内卡片变成“交给总控了 · 总控执行了”；另一条在总控里点“不用”→ 卡片显示“你在总控里点了不用”。

---

## 8. M14：体系页补全

**改什么（`ui/AtlasScreen.kt`）：**
- 每条论断长按（或右边一个“⋯”）弹菜单：
  - “核实”→ 输入框“验证结果是什么？”→ `brain.verifyNode(id, 结果, now)`；成功后这条显示“已验证：…”、把握变高。
  - “删除”→ 二次确认 → `brain.removeNode(id)`。
- 主题下面一个“归纳这个主题”按钮 → `brain.synthesize(now)`（显示“在归纳…”）；结果（`state.synthesis[topicId]`）显示在论断列表上面，一个浅色卡片；模型不可用时 Toast“现在归纳不了（模型没接上）”。
- 每个缺口后面一个“去聊”按钮 → 弹两个选项 Gemini / ChatGPT → `brain.askGap(gap.question, actor, now)` → 返回的提议用 `ProposalCard` 显示在这个缺口下面（点“好”和对话页一样走开场白）。

**验收：** 贴一段材料拆出论断；核实其中一条 → 显示已验证；删一条 → 不见了；点“归纳这个主题”→ 出现主干 / 分支 / 关键链 / 最大缺口；对一个缺口点“去聊 → Gemini”→ 出现卡片，点“好”→ 弹开场白，能直接打开 Gemini。

---

## 9. M15：导演卡片和接力棒

导演（`brain.director`）早就在大脑里，这一步让它说话。

**建什么：**
- 今天页最上面（提醒条下面）一个“此刻”卡片：`brain.director.currentCard(now)`，没有就不显示。显示 `title`、`ask`、`sayLines`（每句一行，旁边“复制”）、`queued`（“还有 N 件”）。
  - `choices` 为空时四个按钮：继续 / 卡住了 / 有结论 / 休息 → `Reply.Continue / Stuck / Conclusion(text) / Rest()`；“有结论”弹输入框（可以用 M3 的语音输入）。
  - `choices` 不为空时按选项显示按钮 → `Reply.Pick(cardId, now, key)`。`key == CHOICE_GO`（去聊）时，再调 `brain.director.opening(card.actor, card.id, now)`，用 M7 的开场白对话框显示、能直接打开演员。
  - 每个回应之后，`onReply` 返回的下一张卡替换当前卡；返回 null 就收起。
  - 卡片右上角“×”→ `Reply.Dismissed(cardId, now)`。
- 接力棒：今天页“此刻”卡片下面一行小字，`brain.director.snapshot().baton` 不为空时显示“没想完的：{question} → 下一问：{nextAsk}”。
- 心跳里喂导演（`BrainHost` 加一个 `sense(sense)` 包装）：
  - 每次心跳：`Sense(SenseKind.TICK, now, "heart")`。
  - 前台应用换了：`Sense(SenseKind.FOREGROUND, now, "usage", mapOf("pkg" to pkg, "label" to label, "category" to category))`。
  - 同一个 `ENTERTAINMENT` 应用连续 15 分钟：`Sense(SenseKind.LONG_ENTERTAINMENT, now, "usage", mapOf("pkg" to pkg, "minutes" to "$min"))`，同一段只发一次。
  - 打开 Gemini / ChatGPT（类别 ACTOR）：`Sense(SenseKind.ACTOR_OPEN, now, "usage", mapOf("actor" to "gemini" 或 "chatgpt"))`。
  - `onSense` 返回卡片时：App 在前台就显示在今天页；不在前台就发通知“墨灵”（正文 `card.ask`），点开进今天页。导演自己有打扰次数上限，不用你再限流。
- **不做**夜间整理的 `SenseKind.NIGHT`：`brain.night` 已经替你发了。

**验收：** 主线过点 15 分钟还在刷总控拦截的 App → 收到“墨灵”通知，点开今天页顶上是一张带选项的卡；点“卡住了”→ 换成下一张；接力棒那一行显示没想完的问题。

---

## 10. M16：备份

**建什么（设置页“记忆”一栏）：**
- “导出”：`ACTION_CREATE_DOCUMENT`（不需要权限），文件名 `moling-备份-yyyyMMdd-HHmm.json`；内容是一个 JSON 对象 `{"v":1,"moling": <mind/moling.json 原文>, "director": <mind/director.json 原文>}`。
- “导入”：`ACTION_OPEN_DOCUMENT` 选文件 → 读出来校验（是 JSON、`v == 1`、两个字段都在）→ 二次确认“会覆盖现在的记忆，导入后 App 会重启”→ 写回两个文件（先写 `.tmp` 再改名）→ `Process.killProcess(Process.myPid())`。校验不过只 Toast，不动现有文件。
- 显示两个文件的大小和最后修改时间。

**验收：** 导出到手机的“下载”里；聊几句；导入刚才那份 → App 重启后对话回到导出时的样子。

---

## 11. 这一批的底线

1. 不改 `core/` 下的文件。
2. 权限只加第 1 节列的五个；不加无障碍、不加悬浮窗、不读通知、不读短信。
3. 提议在用户点“好”之前一律不执行（通知按钮也算“点好”）；总控那边还要用户再点“执行”。早上自动排的只是草稿。
4. 墨灵不修总控、不冻结任何 App（`canRepair` 永远 false）。
5. 后台心跳、通知、UsageStats 任何一处出错都不能让墨灵崩；没权限就降级（少看一样东西），并在调试页写清楚。
6. 每一步写 DEVLOG，CI 绿了再做下一步。全部做完在 DEVLOG 末尾写一段“真机验收清单”，把 M8–M16 的验收步骤按用户一天里会遇到的顺序排好。
