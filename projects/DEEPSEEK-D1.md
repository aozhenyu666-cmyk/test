# DEEPSEEK-D1（v2）：基于 0.17.0 真实能力的差距清单

2026-11-05 · 基线 `integration/all`（一手 0.25.4 + 总控 0.17.0，已装配并推送）

**重要：这份任务书推翻了上一版的三个前提。上一版让做的“合并三条旧线”“搬 0.11 哨兵”“把无障碍拆到 `:guard` 进程”，在 0.17.0 里都已经有更好的实现。照上一版做会把已验证的架构覆盖掉。**

---

## 0. 先读：已经存在的，不要再造

| 能力 | 现状（已核实） | 位置 | 结论 |
|---|---|---|---|
| 无障碍被系统停掉后自愈 | 已有。`SelfHeal` 能区分“系统摘掉”和“用户关掉”，只修前者；重绑要“新实例 + 真 ack”才算修好；最多 3 次，超预算升级提醒；真机实测 41 秒恢复 | `zongkong/app/src/main/java/com/zongkong/app/system/SelfHeal.kt` | **不要重写**，只需在 iQOO 12 上验收 |
| 总控被杀后的兜底拦截 | 已有。哨兵从 0.13 起就是**独立应用** `com.zongkong.sentinel`，不在总控进程里；总控被清、崩溃、更新都碰不到它 | `zongkong/sentinel/`（独立模块，含 `WatchService`、`Decide`、`MainGuard`、`DeviceLock`） | **不要搬 0.11 的同进程哨兵**，那套已被取代 |
| 健康协议 v2 | 已有。`challenge / ack / instanceId`，五种链路状态，证书摘要校验；`RebindCoordinator` 带跨进程文件锁，重绑时保留其他已启用服务 | `zongkong/app/src/main/java/com/zongkong/app/healthwire/`、`guard/Sentinel.kt` | 不要动协议 |
| 总控只有一个无障碍服务 | 已有。Manifest 里只有 `.guard.GuardService` 一个 | `zongkong/app/src/main/AndroidManifest.xml` | 总控侧不需要再删 |
| 总控 ↔ 一手 连接 | 已有。ContentProvider，方法：`status` / `setTask` / `startWindow`，带包名与签名校验 | `zongkong/app/src/main/java/com/zongkong/app/link/YishouClient.kt` | 只需**新增一个方法**，不要重写连接层 |
| 长期统筹 | 大部分已有。`PlanActions`、`HqRunner`、`ChatToday`、`NowScreen`，0.17 已有“早上先问计划 / 追问体系 / 今晚收尾” | `zongkong/app/src/main/java/com/zongkong/app/data/PlanActions.kt`、`system/HqRunner.kt` | 只做差距核对，不重做 |
| 陪练浮窗 / 看屏 / 语音 | 已有。`WindowService`、`ScreenLookService`、`Vad`、`SpeechApi`、`floating/` | `yishou/app/src/main/java/com/yishou/app/{window,look,speech,floating}/` | 只接新规则 |

### 0.1 唯一真正重复的东西

**一手的 `GateService` 还是一个无障碍拦截器。**

```
yishou/app/src/main/AndroidManifest.xml:  .gate.GateService  (BIND_ACCESSIBILITY_SERVICE)
zongkong/app/src/main/AndroidManifest.xml: .guard.GuardService (BIND_ACCESSIBILITY_SERVICE)
```

两个 App 各有一个拦截器，就是“互相抢着弹页面”的原因。**这是本任务书唯一非做不可的结构改动。**

### 0.2 本次已交付的规则模块（不要重写）

```
yishou/app/src/main/java/com/yishou/app/core/
  Stall.kt  NextAction.kt  FinalReview.kt  Weekly.kt
yishou/app/src/test/java/com/yishou/app/core/
  StallTest.kt  NextActionTest.kt  FinalReviewTest.kt  WeeklyTest.kt
```

纯 Kotlin/JVM，45 个测试全过。任务 D 直接调用，不要再写一套。

---

## 任务 A（必做）：停用一手拦截器，改成总控要题

### A.1 关掉一手的拦截

- 从 `yishou/app/src/main/AndroidManifest.xml` 删除 `.gate.GateService`，或加 `android:enabled="false"`。
- `GateActivity` 保留（总控还会用到它的出题 UI），但不再由一手自己的无障碍服务拉起。
- 一手的 `gate_service.xml` 可以保留，不再被引用即可。
- **不要删一手的 `WindowService` / `ScreenLookService` / `floating/`**，那是陪练流要用的。

### A.2 给总控一个“要一道题”的方法

在 `YishouClient` 与一手的 provider 里加一个方法，沿用现有契约风格：

```
method: "ask"
入参: taskId（可空）、reason（"gate" | "return"）、triggerPackage
返回: questionId, prompt, options(JSON), sourceExcerpt, difficulty
```

要求：

- 一手没装 / 版本太旧 / 签名不对 → `Result.Err`，总控改用内置题库，**不允许因此放行**。
- 内置题库放总控，10 道言语题即可，纯本地。
- 题目文本必须带 `sourceExcerpt`（原文片段），供终审用。

### A.3 放行记账全部搬回总控

- `pass` 表：包名组、开始、结束、来源 roundId、题号。
- 一手只返回判卷结果，不写放行。
- 到期由总控重新锁；一手不知道“放行还剩几分钟”。

### A.4 验收

```bash
adb shell settings get secure enabled_accessibility_services
# 只应包含 com.zongkong.app/...GuardService 和 com.zongkong.sentinel/...SentinelService
# 不应包含 com.yishou.app/...GateService

adb shell am start -n com.yishou.app/.MainActivity
# 一手仍可正常陪练、看屏、浮窗

# 切到被拦应用 → 只出现一个拦截页；logcat 只有一条 block 事件
adb logcat -d | grep -c "block"
```

---

## 任务 B（必做）：在 iQOO 12 上验收既有哨兵与自愈，不改架构

上一版说“主进程和哨兵一起掉”，但 0.13 起哨兵已经是独立应用。**先用实测确认它到底行不行，再决定要不要加第三层。**

### B.0 已实测（2026-11-05，Android 14 模拟器 sdk_gphone64_x86_64，API 34）

| 项 | 结果 |
|---|---|
| 两个无障碍服务同时绑定 | 通过（总控 + 总控哨兵） |
| 严格窗口 + blocked=YouTube，打开 YouTube | 5.3 秒内弹出总控 `GateActivity`；日志「严格监管，弹回」 |
| 冻结总控（`kill -STOP`） | 51 秒判定掉线；日志「心跳停了 51 秒，哨兵接管」 |
| 收回哨兵 `WRITE_SECURE_SETTINGS` 后再冻结、打开 YouTube | `com.zongkong.sentinel/.BlockActivity` 到前台；日志「拦下 com.google.android.youtube」 |
| `am force-stop` 总控 | 无障碍从启用列表消失，`SelfHeal` 判为用户关闭，只提醒不重开（符合设计） |

结论：**独立应用哨兵方案成立，不需要 `:guard`。** 调优前是 90 秒阈值 + 30 秒轮询，实测接管约 150 秒；已改为 **45 秒阈值 + 5 秒轮询**，实测 51 秒。

如果要做到 20 秒以内，正确做法是给总控加一个 5 秒一次的轻量心跳（新增 `beat` 方法，只写时间戳），哨兵阈值降到 15 秒；**不要**只把哨兵阈值降到 20 秒以下——总控本身 20 秒才写一次，会误判。

### B.1 安装与授权

```bash
adb install -r zongkong/app/build/outputs/apk/debug/app-debug.apk
adb install -r zongkong/sentinel/build/outputs/apk/debug/sentinel-debug.apk
adb shell pm grant com.zongkong.app android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.zongkong.sentinel android.permission.WRITE_SECURE_SETTINGS
```

在系统设置里打开：总控、总控哨兵 两个无障碍服务。

### B.2 五项实测（逐条记录秒数）

| 场景 | 操作 | 期望 |
|---|---|---|
| 用户手动关掉总控无障碍 | 设置里关掉总控 | **不自愈**（这是用户的意图），哨兵接管拦截 |
| 系统摘掉总控无障碍 | `adb shell settings put secure enabled_accessibility_services ""` | 60 秒内哨兵发现；已授权时总控自愈，记录恢复秒数 |
| 总控进程被杀 | `adb shell am kill com.zongkong.app` | 哨兵 60 秒内接管；打开娱乐应用仍被拦 |
| 总控被强行停止 | 设置 → 应用 → 强行停止 | 哨兵不受影响；能叫回总控 |
| 哨兵被杀 | `adb shell am kill com.zongkong.sentinel` | 总控发现并尝试拉起哨兵 |

### B.3 判定

- 接管 ≤ 60 秒、且哨兵在总控死时仍能拦 → **架构够用，不要拆 `:guard`**（模拟器实测 51 秒，已达标）。
- 接管 > 60 秒，或哨兵也一起掉 → 先查掉线日志和电池优化，再考虑第三层。
- 只有确认“独立应用方案在 iQOO 12 上确实不成立”时，才动 `:guard` 进程；动手前必须保住健康协议的“新实例 + 真 ack”语义，否则会假报健康。

### B.4 掉线日志（补一个缺口）

0.17.0 有 `Diag`，但没有统一的“每次掉线一行”的事故日志。补：

`filesDir/zk/guard/incidents.log`，每行一个 JSON：

```json
{"ts":1762300000000,"kind":"main_dead","beatAgeMs":120000,"battery":37,"charging":false,"screenOn":true,"fgPkg":"com.tencent.mm"}
```

`kind`：`main_dead` / `service_disabled` / `crash` / `frozen` / `sentinel_dead`。

只在状态页显示最近 10 条，不推送、不做图表。

### B.5 iQOO 12 引导页（补一个缺口）

五项复选框（本地记录，不假装能程序化验证）：

1. 允许后台高耗电
2. 允许自启动
3. 允许关联启动
4. 允许后台弹出界面
5. 锁定最近任务

能跳 Intent 就跳（`com.iqoo.secure` / `com.vivo.permissionmanager`），跳不过去写路径。只在检测到掉线时出现一次，不循环弹。

---

## 任务 C：长期统筹的差距核对（不要重写）

0.17.0 已有 `PlanActions` / `HqRunner` / `ChatToday`。只核对下面四个触点，缺哪个补哪个：

| 触点 | 期望 | 现状检查点 |
|---|---|---|
| 周日 | 生成本周草稿，人只改不写 | `PlanActions` 是否有“生成周草稿”入口 |
| 早上解锁 | 3 秒内看到今天三件事 | `ChatToday` / `NowScreen` 首屏 |
| 到点未开始 | 只问一句“现在开始，还是推到几点”，两个按钮 | `HqRunner` 的到点追问 |
| 晚上 | 收三句：做了什么 / 卡在哪 / 明天第一件事 | `HqRunner` 收尾 |

缺口只有一处必须补：**今天三件事的每一条都要过 `NextActionRules.check()`**。现在如果允许“复习行测”这种写法进入日程，整个链条会退化成愿望清单。

```kotlin
val v = NextActionRules.check(text)
if (!v.ok) return askUserToRewrite(v.hint)
```

验收：输入“有空的时候复习行测”会被退回，并提示换成“吃完饭 / 19:30”这类时刻。

---

## 任务 D：陪练流的四条新规则接线

一手的窗口、看屏、VAD、语音都已经存在。只接这四条：

### D.1 每一拍必须引用“此刻”的原词

- 输入三样：屏幕文字、用户刚说的话、当前下一步动作。
- 出题句必须包含一个能在屏幕文字或用户原话里找到的片段。
- 用 `FinalReviewRules.quotableSpan(text)` 取片段（长句自动取最后一段）。
- 引不出来 → **不发这条**，等下一拍。

```kotlin
val span = FinalReviewRules.quotableSpan(screenOrSpeech)
if (span.isBlank() || !question.contains(span)) return   // 不发
```

### D.2 节拍限制

- 用户正在说话（VAD 判定）→ 不插嘴。
- 10 分钟最多说 6 次。
- 连续三拍没接 → 不再追问，交给 `Stall`。

### D.3 三拍没接时用 Stall 分流

```kotlin
val kind = Stall.classify(lastAnswer, tapped = null, silentMinutes = minutes)
val reply = Stall.respond(StallContext(kind, followUps = beatMisses, silentMinutes = minutes))
```

- 太累 / 不想做 → 直接收手，不追。
- 最多追 3 次，之后只要一个回来的时间。

### D.4 不存录音

- 只保留识别后的文本。
- `filesDir` 下不允许出现音频文件。
- 验收：

```bash
adb shell run-as com.yishou.app find files -type f | grep -Ei "\.(wav|m4a|pcm|opus|amr)$"
# 必须为空
```

---

## 任务 E：装配已完成，不要重复做

`integration/all` 已按目录装配并推送：

- `yishou/` = `fix/gate-loop-break`（0.25.4）
- `zongkong/` = `feat/dual-mode-closed-loop`（0.17.0）
- `projects/` 含本任务书与方案

两条历史**没有共同祖先**，不要再 `git merge`。后续只允许：

```bash
git checkout <old-branch> -- <具体文件/目录>
# 或
git cherry-pick <单个 commit>
```

---

## 全局验收

| 项 | 标准 |
|---|---|
| 拦截器数量 | 无障碍设置里只有总控 + 哨兵；一手不再出现 |
| 主进程死 | 2–90 秒内哨兵接管，娱乐应用仍被拦 |
| 自愈 | 系统摘掉（非用户关掉）后 90 秒内恢复 |
| 放行 | 全部由总控记账；一手不持有放行状态 |
| 下一步动作 | “有空的时候”“复习行测”“认真学习”全部被退回 |
| 陪练 | 不引用原词就不发；10 分钟 ≤ 6 次；三拍没接走 Stall |
| 音频 | `filesDir` 下无音频文件 |
| 周报 | 只出现四个数：专注分钟、结算率、正确率/用时、裸考 |

---

## 禁止事项

1. 不要再 `git merge` 旧分支。
2. 不要搬 0.11 的同进程哨兵。
3. 不要为了“独立进程”而拆 `:guard`，除非任务 B 实测不达标。
4. 不要重写 `SelfHeal` / 健康协议 / `RebindCoordinator`。
5. 不要重写已交付的四个 core 规则模块。
6. 不做认知训练、脑力游戏、工作记忆训练、反应抑制训练。
7. 不做连续打卡、排名、进度仪表盘。
8. 不做防卸载。
9. 不做第二个拦截器。
10. 不用长篇输出、说教、重复提醒。

---

## 交付格式

每个任务一个 commit，中文 message，写清“改了什么 + 怎么验的”。

先做任务 A 和 B，出包后在 iQOO 12 上跑完 B.2 的五条实测，把秒数贴进 `zongkong/DEVLOG.md`，再继续 C 和 D。
