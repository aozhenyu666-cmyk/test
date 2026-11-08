# 第三轮核心审核：结论与修复

审核对象：集成分支 `feat/proactive-integration` @ `4ed7d5c`，D5 单提交 `aa8876b`（`review/d5-loop-to-kernel`）。
修复分支：`review/r3-core-fixes`（基于 `4ed7d5c`，先原样摘入 D5，再逐项修复）。**集成分支和 D5 分支都没有被改动。**

标注：PASS = 在写明的环境里验证通过；FAIL = 源码确认缺陷；NOT_TESTED = 没有该环境的证据。

## 结论总表

| 项 | 审核结论（原代码） | 修复后 | 证据 |
|---|---|---|---|
| A D5 双状态机 | **FAIL** | PASS（JVM） / 真机 NOT_TESTED | 见 A |
| B 双开关语义 | **FAIL** | PASS（JVM） / 真机 NOT_TESTED | 见 B |
| C 权限页保护依赖测试开关 | **FAIL** | PASS（JVM，含编译检查） / 真机 NOT_TESTED | 见 C |
| D APK 内插件 | **FAIL（未证明，且按构建方式不可能包含）** | 给出 APK 内容校验工具；需在 Linux 上重建运行时后复验 → NOT_TESTED | 见 D |
| E1 工具执行层限制 | **FAIL**（只有提示词） | **PASS，真实引擎验证** | 见 E |
| E2 竞态 | 2 处 FAIL、其余 PASS | 已修 | 见 E |

## A. D5 双状态机 —— FAIL，已修

**缺陷（源码依据，`aa8876b` 的 `LoopRuntime.kt`）**

- 第 166 行：内核返回 `Queued` / `Merged` 时只写日志，**没有任何代码回写**第一轮 `LoopEngine` 里那条 `TRIGGERED` 事件。
- 原来负责关闭它的 `InteractionHub.show`（`4ed7d5c` 的 `InteractionHub.kt:54`，3 秒看门狗在 `:73`）在第 211 行被替换后**不再被调用**。
- `EventScheduler.kt:93`：`hasOpenEvent -> Suppress("interaction_open")`。
- 结果：第一次进入测试 APP 后，这条事件永远保持 open，**之后每一次进入都被判 `interaction_open` 抑制**，直到进程重启才被 `recover()` 关成 `ABANDONED`；而内核那边对同一个 eventId 可能已经记了 `RESPONDED`。同一 eventId 两份互相矛盾的回执。
- 复现测试：`KernelMirrorTest.defectA_withoutWriteBackTheFirstRoundEventStaysOpenAndBlocksEveryLaterVisit`（按 D5 原样调用，断言第二次进入被 `interaction_open` 抑制）。

**修复：单一所有者 = 内核；第一轮只留一个"委托孪生记录"**

- 新文件 `loop/KernelMirror.kt`：
  - 提交结果 `Queued` → 孪生记录标记为委托（`surface = "kernel"`），保持 open，"同时只有一个交互"的规则继续生效；
  - `Merged` → 立即关成 `SUPPRESSED(kernel_merged:<id>)`；`Rejected` → 立即关成 `UNAVAILABLE(kernel_<reason>)`；
  - 内核 `SHOWN` → 孪生 `SHOWN`；内核关闭 → 按固定映射**只写一次**（`LoopEngine.answer` 本身幂等，旧页面再回答返回 null）；
  - 重启：`LoopEngine.recover()` **不再自行关闭委托记录**；两边都恢复后 `reconcile()` 以内核记录为准，内核没有记录则关成 `ABANDONED(kernel_lost)`。
- `LoopEngine`：新增 `delegate()` / `openDelegated()`；`recover()` 和 `closeOpenLocked()`（改配置时）跳过委托记录。
- 运行时接线：`ProactiveRuntime.dispatch` 每关闭一个事件就回写；`onSurface(Shown)` 回写 SHOWN；`ensure()` 恢复后对账；`LoopRuntime.engine()` 创建时也对账（两种启动顺序都覆盖）。
- 映射：RESPONDED→RETURNED，DISMISSED→DISMISSED，TIMEOUT→TIMEOUT，ABANDONED→ABANDONED，UNAVAILABLE→UNAVAILABLE，FAILED→ERROR；CANCELLED / YIELDED / EXPIRED / DROPPED / MERGED（从没到用户面前）→SUPPRESSED。原因字段统一为 `kernel_<outcome>[:<reason>]`。

**回归测试（`KernelMirrorTest`，全部 PASS）**：回写一次且下一次进入能触发；各种结局映射一致且超时端到端；拒绝/合并立即关闭；内核还排着队时进程重启两边保持一致；页面在屏时重启两边都是 ABANDONED；内核先恢复、第一轮后恢复的对账；内核丢失记录；关掉/换掉触发测试时孪生记录由内核关闭且原因一致。

**回答 DeepSeek 的三点**

1. `dedupeKey = app_enter:$pkg`：保留。修复后同一 App 的上一条事件未结束前，第一轮自己的"同时只有一个"规则就会挡住新的进入，合并基本只会在边界情况发生，且会被如实记成 `SUPPRESSED(kernel_merged)`。
2. TTL：改为固定 `APP_ENTER_TTL_MS = 120 s`。第一轮的 `timeoutMs` 是"页面等回答多久"，那由内核自己的交互超时管；"你刚打开了 X"排队超过两分钟就不该再弹。
3. 两份回执：按上面的单一所有者处理——内核回执是权威，第一轮回执是同 eventId 的镜像，两者结果一一对应，测试逐项断言。

## B. 双开关 —— FAIL，已修

**缺陷**：`4ed7d5c` 的 `ProactiveRuntime.kt:335` `featureEnabled = scheduler?.config?.enabled == true`。只开"触发测试"、不开"主动搭话"时，内核闸门整体关闭，D5 提交的进入事件被 `Rejected("disabled")`，第一轮回执是 `UNAVAILABLE(kernel_disabled)`——**触发测试完全不能用**。

**修复：职责表（`loop/core/Switches.kt`）**

| 职责 | 何时运行 | 归属 |
|---|---|---|
| 前台观察（只读包名） | 任一功能开启 | LoopRuntime |
| 进入测试 APP 触发 | 触发测试开启 | LoopEngine 访问状态机 + TriggerRules |
| 随机主动尝试 | 主动搭话开启 | ProactiveScheduler |
| 交互仲裁（结果唯一所有者） | 任一功能开启 | Coordinator |

- 闸门 `featureEnabled = triggerTest || proactive`；
- 新增内核 `Coordinator.cancelSource(source, reason)`：关掉哪个功能，只取消那个来源的事件（`trigger_test_off` / `proactive_off`），另一个功能不受影响；换测试 APP 时取消旧目标的进入事件（`target_changed`）。
- 规则不重复：免打扰、每日上限只在 ProactiveScheduler；TriggerRules 只管进入事件（冷却、DSH 忙、锁屏）。DeepSeek 的 B4（保留 TriggerRules）是对的。
- 测试：`triggerTestWorksWithProactiveChatOffAndEachSwitchCancelsOnlyItsOwnEvents`、`switchesTable`、`changingOrDisablingTheTriggerLeavesDelegatedTwinsToTheKernel`。

## C. 权限页保护 —— FAIL，已修

**缺陷**

- `aa8876b` 的 `LoopRuntime.kt:117`（`onEvent`）和 `:187`（`verifyNow`）：`if (!e.config().enabled) return`。触发测试关闭时**不再读前台**，`lastForeground` 停在旧值。
- `ProactiveRuntime.kt:338` 用这个旧值判断 `protectedForeground`：只开主动搭话时，进入权限页不会让路；如果最后一次读到的恰好是权限页，又会一直卡住。
- 另外它只认 `ProtectedPages.packages`（整包保护的安装器、权限控制器）。**系统设置里的无障碍 / 权限页**在 DSH 原驱动里是靠读页面标题识别的，内核不读页面内容，所以完全识别不到。

**修复**

- 观察由 `Switches.observeForeground`（任一功能开启）决定；只开主动搭话时仍复核前台，但不产生进入事件；无障碍断开时清空 `lastForeground`；开启主动搭话时立刻重读一次前台。
- 新文件 `loop/ProtectedForeground.kt`：整包保护的页面 **加上整个系统设置 App**（只看包名，宁可多让路）。
- 测试：`ProtectedForegroundTest`；运行时改动随全部 `loop/**` 一起通过编译检查。

## D. APK 是否包含插件 —— FAIL（未证明，且按构建方式不可能包含）

**依据**

- `app/android/app/build.gradle.kts:16,63-64`：APK 的运行时资产只来自 `runtime/out/<abi>/assets` 和 `jniLibs`。
- `.gitignore:9`：`runtime/out/` 不入库，**只能**由 `runtime/build-runtime.sh` 在 Linux 上生成。
- DeepSeek 的 `BUILD_REPORT.md` §6、`VALIDATION_REPORT.md` §6、`TASK_STATUS.md` D4 都写明：完整 rootfs 构建**没有跑过**（Windows 环境）。
- 所以那个 SHA-256 为 `5ce608df…` 的 APK 里，运行时要么不存在（`runtime/out` 为空），要么是别处来的旧 rootfs；两种情况都**不可能**含有 D4 打包的插件，更不会含本轮的工具守卫。`BUILD SUCCESSFUL` 只证明 Kotlin/Dart 编译通过。APK 二进制没有提交，我无法打开它，所以结论是"未证明"，而不是"已证明不含"。

**本轮交付**

- `tooling/verify_apk_runtime.py`：直接打开 APK，逐项检查：运行时 manifest 存在且声明 `dshGoProactive`；启动命令带 `--patch /etc/dsh-app/dsh-web.patch.yml`；rootfs 在 APK 内且 SHA-256 与 manifest 一致；rootfs 内有插件 `package.json`、`lib/index.js`、`lib/core.js`；`dsh-web.patch.yml` 有加载该插件的行；**没有**打包 `test/`；加 `--plugin` 时与仓库版本逐字节比对，并确认带工具守卫。测试 `tooling/test_verify_apk_runtime.py` 5/5（完整运行时、没有运行时、上游旧 rootfs、打包了 test 或旧插件、rootfs 被篡改）。
- `runtime/tests/test_proactive_packaging.py`：`rtbuild.py` 打包配方的单元测试 4/4（patch 行恰好一次、不开时不出现、只打包代码、启动命令带 patch）。
- **要关闭 D**：在 Linux 上 `runtime/build-runtime.sh` → `flutter build apk` → `python3 tooling/verify_apk_runtime.py <apk> --plugin runtime/proactive/dsh-go-proactive`，把输出贴进 VALIDATION_REPORT。在此之前 D 为 NOT_TESTED。

## E. 核心安全边界

### E1 工具执行层 —— FAIL，已修，真实引擎 PASS

**缺陷**：插件只在提示词里写"不要调用任何工具"。模型可以不听；一旦调用手机工具，主动回合就会真的去操作手机。

**依据（真实引擎源码 `@deepseek-ai/dsh-tools` 0.2.1-rc.4）**：每次工具执行前都会走 `ctx.waterfall(carrier, 'tools/pre-execute', exec, …)`，监听者返回 `{kind:'deny', reason}` 时工具不执行，结果以 `isError` 写回会话；cordis 的 `waterfall` 里不调用 `next()` 即否决整条链；注册时需 `global: true`，否则会被按 agent 作用域过滤掉。

**修复**：插件在 `tools/pre-execute` 上以 `{global: true, prepend: true}` 注册守卫。凡是由主动消息开启的回合（以我们消息被认领为准，直到该回合 `turn/end`），**一切工具调用都被拒**；即使记录已取消或超时，只要那一轮还在跑也照拒。其他会话、用户自己的回合不受影响。

**验证（真实 DSH 0.2.1-rc.4 + 本地假模型，`test/e2e-live.mjs` 32/32）**：

- 假模型在主动回合里真的发起工具调用 → `toolDenied 0 → 1`，模型收到的工具结果是 `error=true`，内容为守卫的拒绝说明，回合照常结束并生成文字；
- 对照组：用户自己发消息触发同一个工具调用 → 计数不变，工具**真的执行了**（`is_error=false`）。
- 插件单元测试 12/12（新增：回合内全拒、取消后仍拒、回合结束解除、未认领就取消的消息被认领后仍拒、agent 消失清除）。

### E2 竞态审查

| 位置 | 结论 | 说明 |
|---|---|---|
| PromptArbiter 覆盖 | PASS | `PhoneRegistry.promptGate` 在 `PhoneControl` 构造时设置（`PhoneControl.kt:138`），`NotificationGate`/`ControlOverlay` 的 confirm/handoff 都先转给它，所有提问都经过 `schedule()` 里的 `onBusyChanged` |
| PhoneTasks 开始 → 页面撤下 | PASS（有已知窗口） | 监听在工具线程触发，post 到主线程再撤下页面；中间一个主线程周期内 Agent 可能读到 DSH 自己的页面，DSH 原有规则拒绝操作自己的界面，不会误点 |
| 启动顺序（Flutter 先于无障碍） | **FAIL → 已修** | 内核恢复时 LoopEngine 还没加载，触发测试被读成"关"，待处理的进入事件会以错误原因 `trigger_test_off` 被取消。修复：`ensure()` 先加载 LoopEngine |
| 改配置时的双重关闭 | **FAIL → 已修** | `LoopEngine.setConfig` 先把委托记录关成 `DISMISSED(config_changed)`，内核随后关成 `CANCELLED`，同一 eventId 结果不一致。修复：委托记录只由内核关闭 |
| 超时回执 vs 迟到的模型结果 | PASS | 内核超时发 `CancelAgent`，迟到回复只计入 `lateResults`；插件侧取消后文字不交出（真实引擎验证） |
| 进程重启 + Agent 端口未就绪 | PASS | DeepSeek 的暂存重放（A2）只在期限内重放，同一 requestId 插件不会重发；同意放在适配层，内核不改 |
| 主动回合里用户插话 | 已知取舍 | 用户在主动回合进行中发的消息若被并入同一回合，那一轮剩余步骤也禁用工具；宁可少做，不误操作 |

## 对 DeepSeek 其余问题的答复

- **A2**（Agent 端口暂存）：认可，留在适配层。内核语义是"发出命令"，传输就绪与否是适配层的事。
- **B1**（`AtomicJsonFile` 备份恢复对内核静默）：认可修复本身。建议下一轮把 `recoveredFrom` 写进 `Coordinator.recoveryIssue`，本轮不改。
- **B2**（`Shown` 严格判定）：认可。漏报的代价是一次记成 DISMISSED，比把"没画出来"记成"看到了却没理"（会触发退避和暂停）安全。
- **B3**（第一轮通知兜底）：D5 之后 `InteractionHub.show` 已不可达。建议单独一个补丁删除，本轮不动，避免与本修复混在一起。
- **B4**（TriggerRules）：保留，它就是"进入事件"的规则集，见 B 的职责表。

## 测试汇总（本沙箱，2026-10-31）

| 层 | 结果 |
|---|---|
| `loop/**` 全部 JVM 测试（DeepSeek 96 + 本轮 12） | **108/108**（kotlinc + JUnit；Android 文件对 Robolectric android-all API 35 + Flutter embedding 编译，DSH 内部类用照抄签名的桩；≠ Gradle） |
| 插件单元测试 | **12/12** |
| 插件 × 真实 DSH 引擎（假模型） | **32/32** |
| APK 校验工具 | **5/5** |
| rtbuild 打包配方 | **4/4** |
| Gradle `testArm64DebugUnitTest` / `assembleArm64Debug` | NOT_TESTED（本沙箱无 Android SDK）——请 DeepSeek 在构建机上跑 |
| 所有真机行为 | NOT_TESTED |

## 合并

1. 在构建机上：`gradlew :app:testArm64DebugUnitTest --tests "io.github.ccch1mneyyy.dsh_app.loop.*"` 和 `assembleArm64Debug`，确认通过。
2. 合并顺序：`review/r3-core-fixes` 包含 D5 原提交 + 本轮修复，**直接以它替代 `review/d5-loop-to-kernel`**，不要单独合入 D5。
3. 补丁：`PATCHES/0005-r3-core-fixes.patch`（基于 `4ed7d5c`，含 D5）。
4. D 项在 Linux 重建运行时并用 `verify_apk_runtime.py` 验证之前，不要把任何 APK 描述成"包含主动搭话插件"。
