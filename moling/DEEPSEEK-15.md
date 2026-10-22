# 墨灵 / 总控 · 给 DeepSeek 的施工文档（第十五批：以思代锁的界面 + v13 第二阶段）

> 2026-10-22，Claude（总控者）下发。第八至十四批已全部合入工作分支；PR #13–#20 都已进 `ccr-b3c7616e-xlohkd`。
> **训练兑换的总控核心 Claude 已写好并测过**（提交 `2446a72`，`tools/core-tests` 590 全过，其中墨灵真实客户端 ↔ 总控真实处理的交叉测试 3 个）。
> 本批 A 部分只接界面；B 部分是第十至十三批里程碑 2 中最值得先做的几项。
> 第 0 节裁定照旧有效（见 [DEEPSEEK-14](DEEPSEEK-14.md) 第 0 节）：不读别的应用屏幕、计划不默认生效、总控规则归 Claude。

## 0. 开工

- 从工作分支最新提交开 `deepseek/moling-v13-m2`（A、B 可以分两个分支：`deepseek/zk-training-ui`、`deepseek/moling-v13-m2`）。
- A 部分会改 `zongkong/` 的界面：**推送会触发总控 CI 并覆盖手机上的总控下载链接**，分支必须包含工作分支最新提交，且只在 PR 里推，合入由 Claude 做。
- 每个任务一个提交写任务号；DEVLOG / V13-PROGRESS 只写实际跑出来的结果。

---

## A. 以思代锁的界面

### 已经写好、不要改的（核心）

| 在哪 | 是什么 |
|---|---|
| 总控 `core/moling/Training.kt` | `TrainingPolicy`（默认关）、`Training.blocker(status)`（null = 现在能换）、`issue / submit / usedToday / bonusMinutes`、`TrainingQuiz` |
| 总控 `core/Policy.kt` | `Policy.desiredTraining(config)`（设置页从这里读）、`Policy.setTraining(config, policy, now)`（收紧立即生效，开启 / 放宽 24 小时后） |
| 总控 `app/data/Store.kt` | `store.trainingIssue(pkg)`、`store.trainingSubmit(challengeId, nonce, answers)`、`store.training`（账本 StateFlow）；状态里的娱乐额度已经自动含训练换来的分钟 |
| 总控 `MolingProvider` | v2 方法（只认墨灵签名）：`v2.capabilities / v2.training.policy / v2.challenge.issue / v2.challenge.submit / v2.receipt` |
| 墨灵 `core/redeem/TrainingRedeem.kt` | `TrainingRedeemClient(port)`：`start(pkg, now) / submit(answers, now) / activePass(now) / snapshot()` |
| 墨灵 `app/ZkBridge.kt` | `ZkBridge.gateV2`（端口，直接 `TrainingRedeemClient(ZkBridge.gateV2)`） |

规则（界面上要说清楚，不能写得比这更宽）：只在“今天挣来的娱乐用完了”时能换；关卡、欠着的事、专注锁、墨灵约的严管、每天总上限都不能用训练抵。换来的是**今天的娱乐分钟**（一小时内有效，用完照旧拦），不超过每天总上限。4 道题对 3 道才过；每天有局数，答错歇 3 分钟。

### A1 总控设置页：「以思代锁」一节（Z）
- 读 `Policy.desiredTraining(config)`；开关、应用多选（只列拦截名单里的娱乐应用）、每次分钟（1–15）、每天次数、每天分钟、每天局数。
- 保存走 `Policy.setTraining`，把 `Outcome.message` 原样显示（放宽会写“24 小时后生效，期间可以撤回”）；有排队中的改动时显示它和生效时间。
- 文案：“答几道有标准答案的题，换一点今天的娱乐分钟。只在挣来的娱乐用完时能用；别的拦截（关卡、欠着的事、专注锁……）不能用它抵。”

### A2 总控拦截页：「做一局训练」（Z）
- 只在：策略开着、当前被拦的应用在名单里、`Training.blocker(status) == null` 时显示按钮“做一局训练（换 N 分钟）”。否则不显示（不要显示一个点了才说不行的按钮）。
- 点了 → `store.trainingIssue(pkg)`：失败显示 `message`；成功显示 4 道单选，5 分钟倒计时。**绝不显示 `answer` 字段**（题目对象里有它，只给交卷时用）。
- 交卷 → `store.trainingSubmit(...)`，显示回执 `message`；PASSED 后拦截页按原逻辑重新判断（状态里的额度已经变了，不需要另开放行通道）。
- 「急事」、返回手势等原有出口一律不变。

### A3 墨灵侧入口（I）
- 今天页“精力”那一行下面：总控 `v2.training.policy` 显示 enabled 且有剩余时，一行小字“今天还能用训练换 N 次”+「去换」。
- 「去换」→ 一个底部卡片：`TrainingRedeemClient(ZkBridge.gateV2).start(当前被拦的娱乐应用包名, now)`；包名取不到就让他在策略名单里点一个。
- 题目卡片同 A2（4 道单选、倒计时）；交卷后显示回执。`activePass` 有值时显示“今天多了 N 分钟（一小时内有效）”。
- 总控没装 / 旧总控 / 策略没开：`start` 会返回明确的错误，原样显示，不要装成能用。
- 三手棋、聊天里的分数**不能**接到这里。

### A4 调试页（Z+I）
- 总控调试页：训练账本最近 10 条（局 id、状态、发出 / 截止时间、回执、通行分钟），**不显示答案**。
- 墨灵调试页：`snapshot()` 的 lastMessage、最近回执。

A 部分真机验收：总控开启策略（看到“24 小时后生效”）→ 24 小时后生效 → 把娱乐用到被“额度用完”拦住 → 拦截页出现「做一局训练」→ 答对 3 道 → 能再用 N 分钟 → 用完照旧拦；答错要歇 3 分钟；关卡到点时按钮不出现。

---

## B. v13 第二阶段（按顺序做，每项单独 PR）

### B1 W10 T4：真正联网采蜜（C+I）
- 从案板当前焦点或体系图最大缺口选一个 query，经已合入的 `WebPort` 搜索（没配 Brave 就 NOT_CONFIGURED，绝不返回假结果），最多两路查询，抓取正文进来源库，生成一份 `DISCOVERY`（支持 / 反对材料 + 来源）。至少一条真实结果才能叫“已联网”。
- 只在他点「去查一下」时做；“每天自动一份”要单独开关、默认关、走预算（`AutonomyBudget`）。
- 网页内容一律按外部资料（未核实）进提示词，引用只认来源库里存在的编号。

### B2 W10 T6：候选记忆（C）
- 新增 `MemoryCandidate`（来源、artifactRevision、练习标记、冲突节点）。案板「收进体系图」改为先生成候选列表，他逐条「收 / 不要」；接受 / 拒绝 / 撤销幂等，**练习靶子永远拒收**。
- 只新增 `core/atlas/Candidates.kt` 和必要的 BrainHost 接线；`Brain.keepClaims` 保留给旧路径，不改它的行为。

### B3 W11 R6 + F11-5：桌宠轮盘与曝光（I+C）
- 点桌宠展开轻轮盘：破雾点选 / 当场捉虫 / 只听电台 / 先安静；关闭不抢焦点。
- F11-5：浮层被系统拦、没戴耳机的出现记 UNOBSERVED，不算“他不理我”。

### B4 W12：联络站传输真接上（C+I）
- 现在 RELAY 只是复制并打开。改成真正把进场包推到黑板（复用 `RelaySync`），回来的 `[[外脑:ob-…]]` 结果按会话认领；同步成功只说“推上去了”，不说“外脑已读”。

### B5 W13 G4：系统工具适配器（I）
- 日历：用户选日历后读忙闲；写日程用系统 `ACTION_INSERT` 由他保存。勿扰：只恢复墨灵自己改过的状态。歌单：显式 Intent 打开他选的 URI。每项独立开关、拒绝权限能降级、没回执不写“已完成”。新增权限只在他点开对应开关时申请。

## 交付
每个 PR：任务号清单、`gradle test` 实际结果、两边 CI 链接、真机未测项。A 部分动了 `zongkong/` 的，PR 描述里写清“会更新总控下载包”。全部由 Claude 复审合入。
