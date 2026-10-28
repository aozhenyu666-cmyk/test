# DeepSeek 执行计划（对 GPT PLAN.md 的落地版）

日期：2026-10-28
分支：`claude/r21-strict-takeover`（唯一工作分支）
基线提交：`7f2de49`，versionCode 45 / 0.8.5-partner-recovery-preview
上游文档：同目录 `PLAN.md`（GPT 制定）、`DEEPSEEK_START.md`（执行指令）

> 本文只做一件事：把 GPT 计划里的「待定位项」变成「已定位的具体行」，并给出可执行的修改顺序。
> 不重复愿景，不新增功能，不用单元测试数量代替真机验收。

---

## 一、先纠正一个会让人走错路的前提

GPT 计划写在 `claude/r21-strict-takeover`，但它没有记录这条分支和我上一轮工作的关系。
我核实了：

| 事实 | 证据 |
|---|---|
| 两条线是**同一条历史**，不是两个项目 | `merge-base(feature/r3-aliveness, claude/r21-strict-takeover) = 47dcce5`，正是我 R3 那轮的 HEAD |
| takeover 在我之后又加了 4430 行 / 改了 65 个文件 | `git diff --stat 47dcce5 7f2de49 -- companion-pet/app/src/main/java` |
| 新增的核心类都是我没有的 | `PartnerConversation.kt`(497行)、`ChatActivity.kt`、`PartnerResponder.kt`、`ProactiveGovernor.kt`(219行)、`ReplyContract.kt`、`ThreadBook.kt`、`PartnerVision.kt` |

**结论：不存在「推倒重做」的选项被打开这件事——takeover 已经是那条分支了。我在它上面修，不动历史、不删代码。**

同时必须记下：`feature/r3-aliveness`（我上一轮 13 个提交）已推送 GitHub，但**没有合进 takeover**。
其中 `core/moment/MomentWatcher.kt`、`core/rhythm/ActivityBudget.kt` 等纯逻辑单元可用，
但**不要**把 R3 那套「意图优先」主链路并进来——takeover 的 `PartnerConversation` 已有自己的
`ProactiveGovernor`，两套调度并存正是 GPT 计划点名要避免的「并行启用另一套临时主动循环」。

---

## 二、已定位的真实断点（不是猜测，都读了代码）

### B1. 主动开口有一个**结构性必输**的路径（最可能就是「它从来不说话」的原因）

`PetBrain.presenceTick`（第 913 行起）第一行：

```kotlin
if (partnerSampleJob?.isActive == true || partner.busy.value) return
```

而 `partner.proactive(...)` 内部**第一步**就是 `if (mutableBusy.value) return`（`PartnerConversation.kt`）。

也就是说：**`presenceTick` 只在 `busy == false` 时才调用 `proactive`，而 `proactive` 又只在这一刻 `busy == false` 时才真正干活。**
这意味着 `proactive` 永远只被调用一次「干净」的，之后一旦有任何路径让 `busy` 停在 `true`，主动开口就**永久关闭**，且界面只会显示「在想」，永远不会自己恢复。

需要核实并钉死的具体泄漏点（按可疑度排序）：
1. `proactive` 的 `finally` 是 `if (expected == revision) { busy = false }`——只要 `revision` 被外部 `cancel()` 撞过，这一轮就不清 `busy`。`cancel()` 自己会清，但**如果 `cancel()` 与 `finally` 交错**（`cancel()` 先清、新的一轮又置 `true`、旧 `finally` 因为 `expected != revision` 跳过），新的一轮可能无人释放。
2. `send()` 在 `mutableBusy.value = true` 之后，若 `job` 被系统回收（Service 重启）而没有走 `finally`，`busy` 只存在内存里，重启后虽然归 false，但 `cadence.nextAttemptAt` 已被写成 `now + 120_000`，会造成「重启后两分钟内必然不说话」。
3. `proactive` 里 `cadence = cadence.copy(nextAttemptAt = now() + 120_000L, lastOutcome = "上次生成尚未完成，等待恢复。")` 是**在启动前就写死 2 分钟**，而 `readyForAttempt` 会读它。若这一轮成功但没走 `ProactiveRetry.success`，就会留下 2 分钟尾巴。

**这是 R2 的第一优先级：先给 `busy` 加一个「必须有终态」的断言与超时自愈，再谈话术。**

### B2. 视觉在主动开口的必经路径上，且`settings.visionUploadEnabled` 默认 false

`presenceTick` 里 `imageCandidates` 用 `withContext(Dispatchers.IO)` 包着，但**它和文字候选在同一个 `scope.launch` 里串行 await**：

```kotlin
val candidates = withContext(Dispatchers.IO) { ... }
val imageCandidates = if (条件) { partnerVision.capture(...) ... } else emptyList()
partner.proactive(signals(now()), candidates + imageCandidates + ..., requested) { ... }
```

`capture` 是网络视觉请求。**即使 `visionUploadEnabled = false`（默认），`candidates` 里的 `PerceptionHub.readScreen()` 仍在 IO 线程上跑**，而 `proactive` 必须等它们全部结束。
这正对上用户说的「看图读半天」「等很久才出字」。

**改法（R1/R2 共用）：主动开口的第一版**只允许 `SMALL_TALK` / `THREAD_FOLLOW_UP` / `OWN_READING` 三种候选，**同步路径上禁止任何 `capture`/`readScreen`**。视觉改成异步旁路：结果写进一个带时间戳的缓存，下一次 `presenceTick` 才可能被当成候选。

### B3. `presenceEnabled` 默认 true，但用户在旧版本里已被写成 false

`Settings.kt` 第 86 行 `presenceEnabled by bool("presence_enabled", true)`。
`bool(default)` 语义是「key 不存在时用 default」，**一旦用户装过旧版本、旧版本默认是 false，就永远读到 false**。
如果手机上的安装版本经历过「默认关」的版本，那它现在就是关着的——而界面没有任何明显提示。

**改法：** 加一个 `presenceEnabledMigratedV45` 迁移位，一次性把「从未主动开过」的用户拉到 true，并在设置页顶部显示当前状态与原因。

### B4. 睡眠时段默认 00:00–07:00，而 `sleepHours` 直接让 `decide` 返回 Silent

`quietStartMin = 0`、`quietEndMin = 7*60`。用户如果习惯熬夜到 1–2 点，这段时间它**一定不说话**，且理由只有设置页里一行字。
这不是 bug，但会强化「它从来不说话」的体感。**R2 验收要在非睡眠时段做，且设置页必须显示「现在为什么不说」。**

### B5. 返回动作没有接到伙伴会话

`ReplyContract.kt` 的 `PartnerReply` 只有说话/记忆/话题字段，`PartnerConversation.send` 里**没有任何 `DeviceCommand.Back` 路由**。
用户说「我想返回去它都做不了」——这不是模型能力不足，是**这条会话根本没有动作出口**。

### B6. 分身识别只比包名

`FocusGuard.FocusContract.targetPackage` / `isTarget` 只比较包名。
Gemini 的主实例与分身是**同一个包名**，所以「守护主实例」与「守护分身」在代码里是同一件事。
用户说的「我点分身它就还是控制着」正是这个。

### B7. `ChatActivity` 缺少「停止生成」

`PartnerConversation.send` 在 `busy && !proactivePending` 时直接 return（吞输入），
`ChatActivity` 对应禁用发送，且没有独立停止按钮 → 用户被锁在等待里，只能退出页面。

---

## 三、执行顺序（严格按 R0 → R4，每阶段单独提交）

### R0：让一次失败可定位（不改行为）

**目标**：现在就能回答「它为什么不说」，而不是靠读源码猜。

改动：
1. `ProactiveState` 增加 `blockedReason`、`blockedAt`、`blockedStreak`；`noteBlocked` 落盘。
2. `presenceTick` 每个 `return` 分支都必须调用 `partner.waiting(具体原因, 下次评估时间)`——当前有多条 `return` 是**裸 return**，界面因此永远显示旧的「在想」。
3. `partner.busy` 加**看门狗**：`busy = true` 起超过 45 秒仍未释放，强制 `cancel()` 并记 `busyWatchdogFired`。
4. 设置页诊断区显示：当前 `presenceEnabled`、`partnerPerHour`、`cadence.nextAttemptAt`（人类可读「还有 X 秒」）、`lastOutcome`、`busy`、`blockedReason`、`shownLastHour`。

**验收**：不接设备也要能在设置页看到「此刻为什么不说」的一句话。

### R1：文字交流与返回动作真正可用

1. `ChatActivity` 增加「停止生成」：调用 `partner.cancel()`，1 秒内恢复可输入。
2. `send()` 遇到 `busy` 不再吞输入：改为「取消当前 → 用新输入继续」，或明确提示「正在回复，点停止」。
3. 整次交互 30 秒端到端上限；5 秒无正文显示「网络慢」，提供停止/重试。
4. `PartnerReply` 增加 `action: PartnerAction?`（`BACK` / `STOP_SPEAK` / `CANCEL_LOOK`），
   `PartnerConversation.send` 里接 `AndroidDeviceController` 执行，且**执行后需要独立观察证据**，
   拿不到证据就显示「已请求，未确认」，不显示「完成」。
5. 视觉与文字彻底解耦：`lookOnce` 独立 `lookJob`，可单独取消，不占用文字输入。

**验收**（GPT 门槛）：真机 10 轮连续对话无卡死；10 次停止后下一句可用；5 次「返回」正确或明确报不支持；禁用视觉后全部仍通过。

### R2：主动说话走独立的短路径（本轮的核心）

1. **修 B1**：`presenceTick` 第一行的 `busy` 检查改为只在 `partner.proactivePending` 时跳过；
   `proactive` 内部保留自己的 `busy` 判断；`finally` 改为**无条件**释放 `busy`（用 `if (expected == revision)` 只管 `proactivePending` 标志）。
2. **修 B2**：主动路径只喂三种不需要网络的候选；`capture` 移出同步链。
3. **修 B3**：加迁移位，把老用户的 `presence_enabled` 拉到 true。
4. `presenceTick` 里所有裸 `return` 都带上 `partner.waiting(...)`。
5. 增加「60 秒调试间隔」开关：仅在设置里显式打开、限时自动恢复，不改变正式上限。

**验收**：正常允许场景 10 分钟内 ≥3 次自动出现真实模型内容；60 分钟记录每次该说/不该说及原因，无无故停摆；打字/通话/安静期间 0 次插话。

### R3：目标实例正确 + 如实的守护能力

1. `FocusContract` 身份从 `package` 改为 `package + userId(可空) + 启动组件`；
   拿不到 `userId` 时标 `身份不确定`，**不把另一个实例算作已返回**。
2. 选定目标时先试启动并观察，确认落在指定实例后才建立守护。
3. 三种能力分开显示：①离开后遮罩/拉回 ②息屏锁屏 ③系统级限制退出（Lock Task）。
   **设备不满足 Lock Task 条件时明确标 `BLOCKED_CAPABILITY`，不把「装上即可强锁」写进文档。**
4. 旧目标快照迁移：无法确定实例的活动守护**不静默迁移**，要求用户重新选定。

**验收**：主实例与分身分别连续切出 10 次，动作落在正确实例；到期/服务中断/权限撤销有确定结果，不留失控遮罩。

### R4：减界面 + 整理代码

用户这次明确要求「代码很乱你也得整理一下」。整理**只删重复、只收敛入口，不改行为**：

1. 悬浮入口只留「聊天」「收起」；守护状态单独小条；其余进二级菜单。
2. **删掉被替代的重复主动入口**：`PetBrain` 里旧的 `scheduler.isDue` + `speak(stuck=...)` 那条路径
   与 `presenceTick` 是两套调度，`presenceEnabled` 打开后旧路径已经是死代码，本阶段移除并记录。
3. `PetBrain.kt` 现有 3347 行，抽出三块到独立文件（**纯移动，不改逻辑**）：
   - 训练/题组（`tickTraining`/`tickBreak`/`showTraining*` 等）
   - 守护（`focusController` 接线）
   - 伙伴展示（`deliver`/`present` 相关）
   目标：`PetBrain` < 1800 行，且每块的公开接口不变。
4. 更新 `handoff/last-rescue/` 下的状态文档，如实写 PASS / FAIL / NOT_RUN / BLOCKED_CAPABILITY。

---

## 四、停止条件（提前写死，避免无限补丁）

1. R0–R4 只做一个候选包；只允许对**固定验收集**发现的失败做**一轮**集中修正与复验。
2. 复验仍不能完成「基础文字对话」或「自动开口」→ **停止在现架构加补丁**。
   按用户决定：保留旧仓库与数据快照，新目录做干净实现，不删除旧文件。
3. 守护在用户权限条件下做不到 → 明确判 `BLOCKED_CAPABILITY`，不以聊天进步抵消。
4. 无设备 → 结论只能是 `NOT_RUN` / `BLOCKED_DEVICE`，不得视作通过。

---

## 五、我需要用户提供的一次性信息（同时我先做不依赖它们的部分）

| 项 | 用途 | 没有它会怎样 |
|---|---|---|
| 手机型号 / Android 版本 / ROM | 判断分身实现方式（工作资料 vs 厂商多开） | R3 只能做到「身份不确定」 |
| 手机上实际安装的 versionCode | 确认反馈是否来自当前 APK | 可能一直在修错误版本 |
| 能否让 `adb` 连上（USB 或无线） | **真机验收的唯一前提** | R1/R2/R3 全部只能 `NOT_RUN` |
| Gemini 分身是怎么来的 | 决定身份字段怎么取 | 同上 |

**没有第 3 项，本轮结论只能是「实现完成但未验证」。** 这一点我不会含糊过去。

---

## 六、我现在就开始的部分（不依赖上面任何信息）

1. R0 全部：看门狗、裸 return 补理由、诊断面板数据源。
2. R1-1/2/3：停止生成、不吞输入、30 秒截止。
3. R1-4：`PartnerReply.action` 与 Back 路由（设备控制器已存在，可先接好，真机再验）。
4. R2-1/2/3：`busy` 终态、视觉移出主动路径、`presenceEnabled` 迁移。
5. 每步单独提交，提交信息写清「删除了哪个旧入口」。
