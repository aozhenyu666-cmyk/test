# G2 · 一张卡做完剩下的全部接线

给 GPT（Codex）的任务卡。总方案见 [PLAN.md](../PLAN.md)。

## 规矩（最重要，先读）

- **这张卡的 12 步全部做完，再开一个 PR。中途不要停下来问，也不要做完一两步就交。**
- 缺信息时按最保守的做法继续，把疑问写进 PR 的“待确认”一节。
- 某一步实在做不了（比如系统限制）：写清原因，继续做下一步，不要停。
- 基础分支：`claude/yishou-c1-judge`（已经包含 G0、G1、真判卷器、思维线程和触发器的纯逻辑）。新分支：`codex/yishou-g2-all`。PR 的目标分支也填 `claude/yishou-c1-judge`。
- **不要改这些目录里的文件**：`judge/`（除了 `ProbeRecorder.kt`，见第 11 步）、`thread/`、`reflex/`。觉得有问题写进 PR。
- 提交说明、注释、界面文字用中文、口语、短句；不写模型名称。
- 每做完一步提交一次（方便审），最后一起推。

## 已经写好的纯逻辑（直接调，不要重写）

| 用途 | 调用 |
|---|---|
| 问模型 | `yishou.asker("线程出题")` 返回 `suspend (system, user) -> String?` |
| 判卷 | `yishou.judge.judge(q, answer, trace, seen)` → `Verdict` |
| 建线程 | `StrandEngine.proposeItems(goal, ask)`、`StrandEngine.create(goal, doneWhen, items, now)` |
| 出下一问 | `StrandEngine.serve(strand, ask, now)` → `Pending?`，拿到后 `strand.copy(pending = p)` 存库 |
| 交一手 | `StrandEngine.apply(strand, answer, verdict, now, conclusion)` |
| 外部 AI | `Handoff.text(strand)`、`StrandEngine.startReturn(strand, now)`、`Handoff.parse(text)` |
| 拉回时另开线程 | `StrandEngine.splitOff(strand, now)` → `(旧线程, 新目标草稿)` |
| 线程存取 | `strand.toJson()`、`Strand.fromJson(id, json)` |
| 触发器 | `Reflexes.check/create/recalled/afterRecall/pick` |
| 对比卡 | `DiffCards.build(q, answer, trace, verdict)` |
| 退坡 | `FadeState.level/after`、`Fade.askFirstQ(q)`、`Fade.feedHints(q)` |
| 破冰 | `Icebreaker.from(另一道题, seed)` |
| 入口出什么 | `GatePlan.pick(GateInput(...))` → `STRAND / BANK / LOW_ENERGY / WAIT` |
| 早上唤起 | `Morning.shouldWake(cfg, hour, today, lastWakeDay)` |

## 步骤

### 1. 数据库升到 15

新增三张表，写 `MIGRATION_14_15`（建表语句要和 Room 生成的一字不差），加进 `addMigrations`：

- `strand`：`id INTEGER PK 自增`、`json TEXT NOT NULL`、`done INTEGER NOT NULL`、`updatedAt INTEGER NOT NULL`，索引 `done`、`updatedAt`
- `reflex`：`id INTEGER PK 自增`、`signal TEXT`、`action TEXT`、`source TEXT`、`createdAt INTEGER`、`dueAt INTEGER`、`reps INTEGER`（全部 NOT NULL），索引 `dueAt`
- `fade`：`kind TEXT PK`、`wins INTEGER NOT NULL`、`misses INTEGER NOT NULL`

DAO 加：最近一条没完成的线程、按 id 取、插入或更新、全部未完成列表；触发器全部 / 插入 / 更新；退坡按 kind 取 / 存。
新建 `thread/StrandStore.kt` 包一层：`suspend fun open(): Strand?`、`suspend fun save(s: Strand): Strand`（新线程插入后带回 id）。

迁移测试：14 → 15 后旧数据还在，三张新表能读写。

### 2. 思维线程页面 `ui/StrandScreen.kt`

首页加一个大入口“想一件事”（和“来一手”并排）。

**建线程（三屏）**
1. 输入目标（一句话），点“列待定项”→ 调 `proposeItems`，转圈时可以取消。模型不在就让用户自己列。
2. 待定项列表：可以改字、删、加，2–5 项。
3. 用户**自己写**“做到什么程度就算定了”。点“开始”→ `create`，`Bad` 就把原因显示在输入框下面。

**做线程**
- 顶部：目标；下面是待定项列表，已定的打勾并显示结论，当前项高亮。
- 中间：当前这一问（`pending.q.prompt`）。没有 `pending` 就调 `serve`，拿到后立刻存库。`serve` 返回 null 时显示“出题的模型暂时不在”和“重试”。
- 输入框用 `ProbeTextField`（G1 的），提交后调判卷，再调 `apply`，存库。
- 判完显示对比卡（第 4 步）；过了要先填触发器（第 4 步）才出下一问。
- `PULL_BACK` 时多一个按钮“这是另一件事，另开线程”→ `splitOff`，旧线程存库，跳到建线程第 1 屏，目标框预填草稿。
- `FINAL` 过了：显示“定了：……”，线程标完成。
- **按钮“去外部 AI 聊”**：把 `Handoff.text` 复制到剪贴板，提示“已复制”，下面三个按钮打开 Gemini / ChatGPT / Claude（按包名启动，没装就只复制）。
- **按钮“带回总结”**：`startReturn` → 粘贴框（允许粘贴）→ 判卷 → `apply(..., conclusion = Handoff.parse(text)?.conclusion)`。
- 每交一手，照刷着练的写法记进 `DayLedger`（来源记“线程”）。

### 3. 入口按 `GatePlan` 出题

`GateViewModel` 开局时：

```kotlin
GatePlan.pick(GateInput(
    strandReady = 有未完成线程 && (它有 pending || 判卷在),
    bankReady = 有可用的入口题,
    judgeOk = 文字模型已配置,
    hasReflex = 触发器表不为空,
    wrongInRow = state.wrongInRow,
))
```

- **STRAND**：入口显示线程断点那一问，流程和第 2 步一样（共用组件），用 `GateFlow(pending.q)`。过了放行 15 分钟。
  放行在 `PassIssuer` 里新加 `issueVerified(group, minutes, verdict)`：`verdict.passed && !verdict.unavailable` 才发，记账 `DayStats.GATE_PASS`。
  `PULL_BACK` 和 `FINAL` 过了也算；`EXTERNAL` 不在入口出现。
- **BANK**：G1 现在的流程，加第 5 步的退坡和破冰。
- **LOW_ENERGY**：第 6 步。
- **WAIT**：G0 的等待兜底。
- 入口里答错（要点三次没过，或选项错）`wrongInRow + 1`，然后重新 `pick`。

### 4. 对比卡和填触发器 `ui/DiffCardView.kt`

- 判完（线程、入口题都算；拉回不算）显示 `DiffCards.build(...)`：左边“你刚才的路”，右边“最短路径”，下面参考答案。**没过时只显示左边和打勾打叉，不显示右边判据原文和参考答案**（不然下一次照抄就能过）。三次都没过、换题之前，再完整显示。
- 过了以后必须填一句：“下次看到【信号】，我先【动作】。”信号框旁边放题面，点题面里的字可以选中填进去。
- 保存前调 `Reflexes.check(signal, action, q.prompt)`，不合格就显示原因、不能保存。合格 → `Reflexes.create` 存库 → 才放行 / 出下一问。

### 5. 题库题的退坡和破冰

- 退坡按 `card.skill.name` 记 `FadeState`，每次判完 `after(passed)` 存库。
  - `FEED`：答题前先显示 `Fade.feedHints(q)`，这些提示放进判卷的 `seen`。
  - `ASK_FIRST`：先判 `Fade.askFirstQ(q)`，过了才进正题。三次没过就当这题没过。
  - `BARE`：只有题目。
- 破冰：进入 BANK 题之前，随机取**另一道**入口可用题，`Icebreaker.from(那道题, 随机数)`；两个大按钮，5 秒倒计时，点了或到时间就进正题。不判、不记分、不显示哪个对。没有另一道题就跳过。

### 6. 低能量路 `ui/RecallView.kt`

- 显示 `Reflexes.pick(全部, now, exclude = 今天已经默写过的 id)` 的信号和来源题的题面前 60 字，用户写动作。
- `recalled(...)` 对：`afterRecall(true)` 存库，放行 `GatePlan.LOW_PASS_MIN`（5）分钟，记账新加 `DayStats.LOW_PASS`。
- 错：`afterRecall(false)` 存库，显示正确动作，换下一条。同一次入口最多 3 条，都错就进 WAIT。
- “今天默写过”用设置里存的 id 列表加日期，跨天清空。

### 7. 回响：到期触发器先闪一屏

STRAND 或 BANK 开题前，如果有 `dueAt <= now` 的触发器，先闪一屏默写（同第 6 步的界面，但不放行）。对错都更新间隔，然后进正题。每次入口最多一条。

### 8. 早上第一次解锁

- 设置里加“早上唤起”：开关（默认开）、开始和结束的钟点（默认 6–10）。存 `lastWakeDay`。
- 在 `GateService`（无障碍服务，系统允许它从后台拉起界面）里动态注册 `Intent.ACTION_USER_PRESENT`，`onDestroy` 里注销。
- 收到后 `Morning.shouldWake(cfg, 当前小时, LocalDate.now().toEpochDay(), lastWakeDay)` 为真：先写 `lastWakeDay`，再拉起界面。有未完成线程 → 打开线程页的断点；没有 → 打开入口思考页（入口组用“早上”，放行时长照常）。
- 在设置页写清楚：“早上 6–10 点第一次解锁手机时，一手会直接打开。可以在这里关掉。”

### 9. 首页收拢

首页只留：来一手、想一件事、素材池、语音伙伴。其余练法（海龟汤、残局、六骰子、预判本、分组训练、视频陪练等）收进一个折叠的“更多（不再更新）”。不删功能、不改它们的代码。

### 10. 设置项汇总

早上唤起（第 8 步）。不要加别的设置。

### 11. 修 `ProbeRecorder` 可能崩溃的地方

`update()` 里的 `require(old == text)`：输入法或语音回填有时会让两边对不上，入口直接崩溃。改成：对不上时把 `old` 当成 `text`，按整段替换处理，记一条运行日志（`log/`），不抛异常。补一个测试。

### 12. 版本和说明

- 版本 `0.24.0`。
- `yishou/README.md` 顶部加 0.24 的说明（口语、五到八行）：想一件事、入口先出线程断点、对比卡和触发器、默写换 5 分钟、早上唤起、首页收拢。
- 写 `yishou/docs/G2-HANDOFF.md`：做了什么、没做成什么、真机要测什么。

## 测试（全部是 JVM 单元测试）

- 迁移 14 → 15
- `StrandStore`：新线程存库后有 id；重启（新建 store）后断点那一问不变
- 入口出题顺序：有线程走线程；判卷不在走默写；默写三条都错走等待
- 线程过了才放行；判卷不在不放行、不扣次数
- 没填合格的触发器不放行
- 退坡：`ASK_FIRST` 先问，问不准不进正题
- 破冰用的不是当前这道题
- 早上唤起：同一天第二次解锁不再拉起；关掉开关不拉起
- `ProbeRecorder` 对不上时不崩

跑：`./gradlew testMainlineDebugUnitTest assembleMainlineDebug assembleChaluDebug`。本地跑不了就推分支看 CI。

## 验收（PR 里逐条打勾，真机项写“未测”）

- [ ] CI 绿，PR 里贴链接
- [ ] “想一件事”：能建线程、出题、判、拉回、另开线程、交接、带回、定下来
- [ ] 线程中途退出，重新打开，还是同一问
- [ ] 入口第一屏是线程断点；没线程是题库题；断网是默写；默写都错是等待
- [ ] 没过时看不到判据原文和参考答案
- [ ] 过了必须填触发器才放行；“认真读题”存不进去
- [ ] 早上 6–10 点第一次解锁，一手自动打开；第二次不开
- [ ] 首页只剩四个大入口
