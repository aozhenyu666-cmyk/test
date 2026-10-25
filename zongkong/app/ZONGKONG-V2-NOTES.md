# 总控 com.zongkong.app · v2 改造说明（ZONGKONG-V2-NOTES）

版本：`versionCode=19`，`versionName=0.16.0-health-v2`
协议版本：**2**（`_shared/WIRE-SPEC-v2.md`）
基线：`ba8750e`，分支 `deepseek/zk-client-v2`，工作区 `C:\zkwork\zongkong-wt`
签名：**未改动**（仍用仓库内 `app/debug.keystore`）

APK 实测指纹（`apksigner verify --print-certs`）：
`b742dcd196af01fa96428498f5297d3165f66ab8c8ff6b85dd09185dbc318ac9`
（与哨兵 `sentinel-debug.apk` 同一把：哨兵 `build.gradle.kts` 用 `../app/debug.keystore`。
调试签名**不是**抗伪造方案，只挡"别的应用冒充客户端"。）

---

## 1. 改动清单

### 1.1 新增 · 健康协议（T02 核心）

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/zongkong/app/healthwire/A11yChallenge.kt` | 挑战处理器（纯 Kotlin，可 JVM 单测）。`post` 到 a11y 主线程 → `latch.await(budgetMs)` → 正常带回 ack / 超时 `ok=false + "dispatcher timeout"`。**facts 也在 a11y 线程上读**，Binder 线程只等待不构造。带在途上限（默认 2）与预算上限（20s），不为冻结调用无限建线程。 |
| `app/src/main/java/com/zongkong/app/healthwire/HealthLedger.kt` | 本地健康台账：只记真实事实（实例、真实 ack、真实窗口事件）。恢复只记"请求已发出"，`beginRecovery` 会清掉旧 ack；恢复成功需"新 instanceId + 恢复开始之后的 ack"；最多 2 次，超预算给 `ESCALATE`。全部方法 `@Synchronized`。 |
| `app/src/main/java/com/zongkong/app/healthwire/HealthSnapshot.kt` | 台账 → 共享内核 `HealthObservation` 的映射 + 界面文案表（`HealthLabels`：只有 HEALTHY 才绿）。 |
| `app/src/main/java/com/zongkong/app/healthwire/GuardHealth.kt` | 进程内运行时：持有台账、挂 a11y 投递口（`onA11yConnected(this, Handler(mainLooper))`）、`challenge()` 入口、状态/文案。 |
| `app/src/main/java/com/zongkong/app/healthwire/HealthWireProvider.kt` | **新增 provider**：authority `com.zongkong.app.health`，method `challenge` / `emergencyStop` / `ping` / `status`。只接受哨兵包名 + 证书摘要（`B742DCD…18AC9`，64 位十六进制）。 |
| `app/src/main/java/com/zongkong/app/healthwire/PermissionStatusBridge.kt` | 轻量状态桥，替代各处 `GuardService.running` 判断。 |

### 1.2 新增 · 本地规则与有限兜底

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/zongkong/app/healthwire/LocalPolicy.kt` | `LocalPolicy`（`schemaVersion=2` 字段齐全：policyId/revision/issuer/requiredApps/startsAtWall/endsAtWall/lockAtWall/failureAction/repairGraceMs/emergencyState/enabled）+ `LocalFallback`（用共享内核 `LockArbiter` 去重，`maxAttemptsPerIncident=1`）。 |
| `app/src/main/java/com/zongkong/app/healthwire/ClientPolicyStore.kt` | 规则持久化（`filesDir/zk/policies-local.json`，先写 `.tmp` 再 rename）。读坏文件备份 + 记 `lastError`，不静默重置成"放开"。含紧急暂停记录。 |
| `app/src/main/java/com/zongkong/app/healthwire/LocalLockExecutor.kt` | 设备管理员 `lockNow()`；无权限时**明确**返回 `FAILED + 权限状态**，不伪装。调用后延时观察 `KeyguardManager`，结论交给共享内核 `LockClassifier`（没设安全锁屏 → `UNVERIFIED`，不报成安全锁定）。 |
| `app/src/main/java/com/zongkong/app/healthwire/LocalGuardian.kt` | 接入循环：`tick()` 只投递到后台单线程（不在 a11y 线程做跨进程锁屏，T05）；`emergencyStop()` / `resume()` / `savePolicies()`。 |
| `app/src/main/java/com/zongkong/app/guard/ZkDeviceAdmin.kt` | 设备管理员组件（只为 `force-lock`）。`onPasswordChanged` 明确不读不存。 |
| `app/src/main/res/xml/device_admin.xml` | 只声明 `<force-lock />`。原先缺失。 |

### 1.3 修改

| 文件 | 改了什么 |
| --- | --- |
| `guard/Sentinel.kt` | **重写为 v2**：`register`/`syncPolicy`/`reportReceipt`/`ping` 走 `com.zongkong.sentinel.health`；`registerAsync` 非阻塞（`onServiceConnected` 在 a11y 主线程）；ping 走单线程 executor + 超时；`Link` 五态（NOT_INSTALLED / NOT_GENUINE / DISABLED / UNKNOWN / RESPONDING / SILENT）；`linkCached()` 纯缓存供 a11y 线程用。**`SentinelWakeProvider` 不再返回 `GuardService.running`**，改为回 `healthStatus` + `healthSource=v2-challenge`。`rebind()` 转给 `RebindCoordinator`。 |
| `guard/Sentinel.kt` · `RebindCoordinator` | 新增：跨进程文件锁（`filesDir/zk/rebind.lock`，15s 视为过期）+ 写回前**重新读取**列表、只摘自己这一项、**保留其他已启用服务**（T12）；再读一次后放回。成功只记 `rebind_sent`（"恢复请求已发出"）。 |
| `guard/GuardService.kt` | `connected()` 换新 instanceId + 挂 a11y Handler + 异步注册；`onWindow()` 登记 `noteWindowEvent()`；`beatSentinel` 只做后台 ping（不再推 running）；`offline()` **先**作废台账实例再判 running；`tick()` 加 `LocalGuardian.tick`。 |
| `system/Watchdog.kt` | **不再主要依赖 `running`**：改为共享内核 `HealthEvaluator`（真实 ack 新鲜度）+ 事件进度；`RECOVERING/UNKNOWN/PAUSED` 不误报掉线；状态变化才写诊断。 |
| `system/SelfHeal.kt` | 重绑只记"恢复请求已发出"，要新实例 + 真 ack 才 HEALTHY；最多 2 次，超预算 `ESCALATE` 并明说停止重试；重绑与哨兵串行（`RebindCoordinator`）；哨兵判定改用 v2 五态。`KeepAliveService` 增加 `LocalGuardian.tick`。 |
| `ui/PermissionsScreen.kt` | 如实显示：无障碍（真实 ack，不涂绿）/ **设备管理员**（含系统引导 `ACTION_ADD_DEVICE_ADMIN`）/ **安全锁屏是否设置** / 哨兵五态 / 通知 / 闹钟 / 全屏。新增"健康协议 v2"卡片（本地规则状态 + 紧急暂停按钮）。`PermissionStatus.guardRunning` 改为 `healthStatus == HEALTHY`。 |
| `ui/SettingsScreens.kt` | 新增 `EmergencyStopCard`（紧急暂停与"休假/正常提前结束"分开）；诊断入口文案改为"真实响应中"。 |
| `ui/DiagScreen.kt` | 增加"健康判定 / 执行台账 / 最近一次挑战 / 哨兵"四行如实数据。 |
| `guard/Diag.kt` | `serviceRunning` 改为真实健康（不再是 running 布尔）。 |
| `data/Chat.kt` · `system/HqRunner.kt` · `system/Pet.kt` · `system/PetWidget.kt` | 所有 `GuardService.running` 当健康凭据的地方改为 `PermissionStatusBridge`；小部件只在真实 ack 新鲜时画"醒着"。 |
| `AndroidManifest.xml` | 新增 `HealthWireProvider`（authority `com.zongkong.app.health`，exported）+ `ZkDeviceAdmin` receiver（`BIND_DEVICE_ADMIN`）。 |
| `app/build.gradle.kts` | `versionCode 18→19`，`versionName 0.15.0→0.16.0-health-v2`。签名配置**未动**。 |

### 1.4 测试（新增 40 个 JVM 用例）

| 文件 | 覆盖 |
| --- | --- |
| `test/.../healthwire/A11yChallengeTest.kt`（9） | 没连上不投递；投递后带回 ack（含 handledAtMono）；**冻结超时 → ok=false 且不读 facts**；预算 0 压到上限；在途上限；旧协议拒绝；nonce 为空拒绝。 |
| `test/.../healthwire/HealthLedgerTest.kt`（11） | 无 ack 不健康；真 ack 才健康；DISABLED；断开立刻失效；重连换实例；恢复需新实例 + 真 ack；恢复封顶；预算用完 ESCALATE；双通道矛盾；PAUSED。 |
| `test/.../healthwire/LocalPolicyTest.kt`（13） | 窗口生效；默认不设 lockAtWall；哨兵在岗不抢锁；自己不健康不越权；哨兵失联 + 自己健康 → 兜一次；同事件不重复；每事件上限 1；修复宽限；紧急暂停；NOTIFY_ONLY；多规则取最新版本。 |
| `test/.../healthwire/RecoveryPolicyTest.kt`（5） | 重绑 ≠ 修好；旧实例 ack 不算恢复；封顶两次；预算用完升级；暂停时不恢复。 |
| `test/.../healthwire/CertConstantsTest.kt`（2） | 证书常量必须 `^[0-9A-Fa-f]{64}$`（踩过 63 位导致鉴权永远失败的坑）；总控与哨兵同一把。 |
| `test/.../healthwire/RepairGraceTest.kt`（17） | T15 纯逻辑：宽限期内 `decide` 返回 `fire=false` 且理由含「修复宽限」；对照用例「不传宽限就会重锁」；宽限过期后可再锁；锁屏确认后 `until == now + repairGraceMs`；失败回执不起宽限。 |
| `test/.../healthwire/RepairGraceWiringTest.kt`（13） | **T15 接线层**：驱动生产同一个 `GuardTickCore.tick(...)`。核心用例 `tick 第二次应被修复宽限拦住`（锁屏后 +15s 新事件 → 无新回执、理由含「修复宽限」），**注入旧 bug 会红**（见 §2.2）；另有宽限过期可再锁、宽限清空即重锁（非恒真对照）、解锁重新起算、紧急暂停清空、节流、哨兵在岗不锁、自己不健康不锁、失败回执不起宽限、回执带齐四个 id。 |

---

## 2. 验证结果（本机实跑）

```
cd C:\zkwork\zongkong-wt\zongkong
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:GRADLE_USER_HOME="C:\guh"
$env:ANDROID_HOME="C:\Android\sdk"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --offline
```

| 项 | 结果 |
| --- | --- |
| `:app:assembleDebug --offline` | **通过**（APK 13,565,612 B ≈ 12.9 MB） |
| `:app:testDebugUnitTest --offline` | **通过，299 个用例 0 失败** |
| 其中共享内核 `com.zongkong.app.health.HealthCoreTest` | **36/36 通过**（未改动共享内核逻辑） |
| 其中 v2 新增 `com.zongkong.app.healthwire.*` | **70/70 通过**（含 T15 的 `RepairGraceTest` 17 例 + **接线级 `RepairGraceWiringTest` 13 例**） |
| 版本 | `versionCode=19` / `0.16.0-health-v2`（`aapt2 dump badging` 实测） |
| 签名 | `b742dcd1…18ac9`，与改动前一致（未改签名配置） |
| 清单组件 | `provides-component:'device-admin'`（新增）、`accessibility`、`app-widget` 均已注册 |

---

## 2.1 T15 修复宽限（t13 补齐，2026-10-25）

### 修的是什么

原实现有个**真缺口**：宽限通道全部存在，但生产代码从来没算过那个时刻。

- `healthwire/LocalPolicy.kt` 早就有 `repairGraceUntilMono` 参数并传给共享内核 `LockArbiter`；
- 共享内核 `HealthCore.kt` 也早有分支：`if (repairGraceUntilMono > 0 && nowMono < repairGraceUntilMono) return LockDecision(false, "修复宽限中…")`；
- **但 `LocalGuardian.tickNow()` 调 `fallback.decide(...)` 时没传这个参数**，恒用默认 `0`。
- 结果：`> 0` 永远不成立 → 宽限分支永远进不去 → 锁屏后每 15 秒 tick 又会重新满足条件再锁一次，
  **正好把用户挡在「设置 → 无障碍」的修复界面上**。这正是报告 §8 要防的场景。

> 注意 `LockArbiter` 的次数上限与冷却**不能替代**宽限：它防的是「同一事件反复锁」，
> 宽限防的是「用户正在修权限时别打扰」。两件事，缺一不可。

### 改在哪（文件:行号）

| 位置 | 内容 |
| --- | --- |
| `healthwire/RepairGrace.kt`（新增，全文件 1–160 行） | 纯状态机，可用假时钟单测。`class RepairGrace`(:37) / `active`(:51) / `onLockConfirmed`(:65) / `observe`(:77) / `expire`(:89) / `clear`(:98) / `armAfterReceipt`(:126) |
| `healthwire/LocalGuardian.kt:35` | `private val repairGrace = RepairGrace(defaultGraceMs = HealthConfig().repairGraceMs)` —— 与一手 / 同桌同口径（120s） |
| `healthwire/LocalGuardian.kt:38` | `val repairGraceUntilMono` 只读暴露，便于诊断与断言 |
| `healthwire/LocalGuardian.kt:46` | `observeKeyguard()`：读 `LocalLockExecutor.facts()`，把 `deviceLocked \|\| keyguardShowing` 交给状态机识别 locked→unlocked |
| `healthwire/GuardTickCore.kt:132` / `:136` | tick 里先观测解锁、再 `expire()`：**到期必须清 0**，不能让宽限变成永久豁免 |
| **`healthwire/GuardTickCore.kt:151`** | **关键接线**：`fallback.decide(..., repairGraceUntilMono = grace.untilMono)` —— t13 之前这一行不存在（恒用默认 0） |
| `healthwire/GuardTickCore.kt:110` | `fun tick(ports, nowWall)`：巡检全部逻辑本体。**`LocalGuardian.tickNow()` 与测试驱动的是同一份** |
| `healthwire/GuardTickCore.kt:206` | `armGraceAfterLock(...)`：纯函数，优先用生效规则的 `repairGraceMs`，否则用内核默认值；`armAfterReceipt` 决定哪些回执状态该起宽限 |
| `healthwire/GuardTickCore.kt:125` / `:197`、`LocalGuardian.kt` resume/reset | 紧急暂停生效中 / `resume()` 主动恢复 / reset 时都会 `grace.clear()` |
| `healthwire/LocalPolicy.kt:101,129` | 未改动（本来就把参数透传给 `LockArbiter`） |
| `test/.../healthwire/RepairGraceTest.kt`（17 例） | 纯逻辑层（状态机 / armAfterLock / 时序） |
| `test/.../healthwire/RepairGraceWiringTest.kt`（**13 例**） | **接线层**：驱动生产同一个 `GuardTickCore.tick(...)`，见 2.2 |

### 「解锁信号」的边界（不假装能精确感知）

1. **理想信号**：`KeyguardManager.isKeyguardLocked/isDeviceLocked` 的 `locked → unlocked` 跳变。已在 `observeKeyguard()` 实现，真观测到就**重新起算**一个完整宽限。
2. **现实约束**：总控巡检是 **15 秒一个 tick**，而「锁屏 → 解锁 → 进设置」这一串经常整个落在两个 tick 的间隙里，`locked` 状态**根本采样不到**。因此单靠跳变会漏。
3. **退化策略（已采用）**：只要这次锁屏确实发生过（`OBSERVED_LOCKED` / `API_CALLED` / `UNVERIFIED`），就**在锁屏当时立即**开始计宽限，不等解锁。
   - 代价：本该锁的时候可能少锁一次；
   - 收益：绝不会把用户挡在修复界面外面。
   - 这个取舍是**明确选择**，不是疏漏。
4. **不给宽限的情况**：`FAILED` / `REQUESTED`（连调用都没成功）不起宽限 —— 否则「一直锁不上」会退化成「永远不锁」。
5. **真机必须验**：120s 是否够用户走完 设置→无障碍→关开；以及 `KeyguardManager` 在 vivo/iQOO 上的实际行为。见下方 T15 的 NOT_RUN。

### T15 现在的证据等级

| 层 | 等级 |
| --- | --- |
| 纯逻辑（宽限状态机、`armAfterLock`、时序） | **已覆盖**：`RepairGraceTest` 17 例，假时钟推进，0 失败 |
| **接线（`tickNow` 是否真的把宽限传下去）** | **已覆盖且已验证会叫**：`RepairGraceWiringTest` 13 例，驱动生产同一个 `GuardTickCore.tick(...)`；注入旧 bug 后该用例变红（见 2.2） |
| 真机（Keyguard 实际跳变、120s 是否够、vivo 行为） | **NOT_RUN**（无真机，`adb devices` 为空） |

---

## 2.2 接线级回归测试：为什么 t13 那条是假绿灯（t14 补齐）

### 问题

t13 写的那条「端到端」用例是这样调用的：

```kotlin
val first = fb.decide(policies = ..., repairGraceUntilMono = grace.untilMono)  // ← 自己传的
```

它直接调 `LocalFallback.decide(...)` 并**自己把宽限当参数喂进去**，完全绕过了
`LocalGuardian.tickNow()` 里那行真实接线。所以它证明的是「`decide` 收到宽限时行为正确」
——这本来就对；它**没有**证明「`LocalGuardian` 到底有没有传下去」，而那才是 t13 修的 bug。

**captain 的注入实验证明了这一点**：把那行改回 0（修复前的样子），286 条用例**全绿**。

> 教训与 F-01 同源：**判据/纯函数正确 ≠ 接线正确。**

### 修法：把 tick 本体抽成「生产与测试共用的同一份」

- 新增 `healthwire/GuardTickCore.kt`：`class GuardTickCore`(:75)，`fun tick(ports, nowWall)`(:110)。
  全部判定与副作用编排都在这里，外部依赖走 `GuardTickPorts`（mono / policies / 健康 / 哨兵 / keyguard / lock / report / diag）。
- `LocalGuardian.tickNow()` 现在只做一件事：用 Android 实现填 `ports`，然后 `core.tick(ports, nowWall)`。
- 测试用假 `ports` 驱动**同一个** `core.tick(...)`（经 `LocalGuardian.tickWithPortsForTest` 或直接构造 `GuardTickCore`）。
- **不是测试专用副本**：把 `GuardTickCore.kt:151` 那行改掉，测试必红。

### 注入实验（可复现，原始输出）

把 `GuardTickCore.kt:151` 从 `repairGraceUntilMono = grace.untilMono` 改成 `= 0L`（还原成 t13 的 bug），`--rerun-tasks` 跑全量：

```
RepairGraceWiringTest > tick 第二次应被修复宽限拦住 FAILED
299 tests completed, 1 failed
BUILD FAILED in 57s
```

失败原文：

```
java.lang.AssertionError: 第二次 tick 不许再锁（宽限期内）。实际锁了 2 次；判定理由：允许执行
expected:<1> but was:<2>
  at com.zongkong.app.healthwire.RepairGraceWiringTest.tick 第二次应被修复宽限拦住(RepairGraceWiringTest.kt:151)
```

→ 注入 bug 后宽限消失，第二个 tick **真的又锁了一次**（`允许执行`），且**只有这一条**变红。

还原后：

```
BUILD SUCCESSFUL in 56s
RESTORED: TOTAL tests=299 failures=0
RepairGraceWiringTest=13 failures=0
HealthCoreTest=36 failures=0
```

### 为什么这条用例设计成「第二次换 incidentKey」

第一版主用例沿用了同一个 `incidentId`，注入 bug 后确实也红了，但**红得不对**：

```
java.lang.AssertionError: 第二次 lastDecision 必须包含「修复宽限」，实际：同一事件已确认锁屏，不重复执行
```

拦住它的是 `LockArbiter` 的同事件去重（另一层保护），不是宽限 —— 那样这条用例无法区分「宽限生效」与「仲裁兜住」。
改成第二次用**新事件**（`sentinel-unreachable-next`）后，仲裁会放行，**只有宽限能拦**，
于是断言 `没有新回执` 才真正在测宽限接线；注入 bug 时的失败理由也变成干净的 `允许执行 / 实际锁了 2 次`。

另有一条 `同一事件在宽限期内也不会被重复执行` 单独覆盖「同事件」路径，只是断言「没再锁」，
不把两层保护混为一谈。

---

## 3. NOT_RUN（没跑，不写 PASS）

本机 `adb devices` 为空，**无可用真机**。以下全部 **NOT_RUN**，需要真机现场步骤：

| 编号 | 项 | 为什么没跑 | 需要什么现场步骤 |
| --- | --- | --- | --- |
| T02（真机部分） | 哨兵 → 总控 `challenge` 的真实跨进程往返 | 无真机、哨兵尚未装到设备 | 装总控 + 哨兵，确认无障碍都开；`adb shell content call --uri content://com.zongkong.app.health --method challenge --extra v:i:2 --extra nonce:s:test --extra seq:l:1 --extra budgetMs:l:5000`，期望 `ok=true` 且 `handledAtMono>0` |
| T02（冻结路径） | 无障碍线程冻结时返回 `dispatcher timeout` | 冻结需要真机（或用 `adb shell am` + 长耗时注入） | 在 a11y 线程上制造长阻塞（例如超大布局遍历），再发 challenge，期望 `ok=false, error="dispatcher timeout"` |
| T04 | 单个客户端不响应不阻塞其他客户端 | 需要三个客户端同时在线 | 三机/同机三客户端，让其中一个卡死，验证另两个探测周期不受影响 |
| T05 | 无障碍主线程卡住时 `:watch` 仍能锁屏 | 需真机 + 哨兵 `:watch` 就绪 | 冻结总控 a11y 线程，验证哨兵仍按调度锁屏 |
| T10 / T11 | 设备管理员 `lockNow()` 实际锁屏与”重绑后恢复“ | 无真机，无法启用设备管理员、无法删/加无障碍列表 | 真机启用 `ZkDeviceAdmin`，`Settings → 安全 → 屏幕锁定` 设 PIN；触发一次锁屏，检查回执为 `OBSERVED_LOCKED`；再人为移除无障碍，验证 `rebind_sent` → 新实例 → 真 ack 才 HEALTHY |
| T12 | 重绑保留其他已启用服务 | 需要真机上有多个无障碍服务 | 同时开总控 + 一手 + 哨兵，触发总控重绑，验证另两个仍在 `ENABLED_ACCESSIBILITY_SERVICES` 里 |
| T17 | 哨兵失联时本地规则兜底锁屏 | 需真机 + 已落盘规则 + 设备管理员 | 停掉哨兵，让本地规则生效，验证只锁一次（`LockArbiter` 去重）并生成带 `policyId/incidentId/requestId/source/reason` 的回执 |
| **T15（真机）** | **锁屏后用户去修无障碍，宽限期内不重锁** | 纯逻辑已有 `RepairGraceTest` 17 例、接线已有 `RepairGraceWiringTest` 13 例（含注入验证）覆盖；但 `KeyguardManager` 真实跳变、15s tick 间隙是否漏采样、120s 是否够走完修复流程，都只能在真机上看 | 真机装好并启用设备管理员；让本地规则生效触发一次兜底锁屏；**立刻**解锁并进 设置→无障碍→关开总控服务，全程观察是否被再次锁屏挡回去；`adb logcat -s LocalGuardian Diag` 里应看到 `repair_grace` 记录与「修复宽限中」而不是再次 `local_lock`；如需缩短等待可用短 `repairGraceMs` 的规则验证边界 |
| 紧急暂停（真机） | 一键停掉自动锁屏并上报哨兵 | 无真机 | 在设置页按"立即暂停"，验证后续 `LocalGuardian` 不再锁屏、`policies-local.json` 有 `STOPPED`、哨兵侧收到（或如实显示"哨兵未确认"） |
| 24h 运行 / 耗电 | — | 无真机 | 长跑观测 |
| 与哨兵 v2 端到端 | `register` / `syncPolicy` / `reportReceipt` | 无真机（哨兵侧 `TRUSTED` 的 63 位问题已由 sentinel-dev 修复并加 `TrustedCertTest`，captain 已独立复核） | 两侧合装后逐项验证 |

### 已知边界（写进文档，不隐瞒）

1. **整包被系统强制停止后，总控无法自我保证恢复**（报告 T06）。哨兵也做不到这一点。界面与本文档都不宣称"无法绕过"。
2. **调试签名不是抗伪造方案**。`HealthWireProvider` 校验的是"调用方 UID 属于哨兵包名 + 证书摘要匹配"，只挡普通冒充。
3. 没有设置安全锁屏时，`lockNow()` 可能只是休眠 → 回执只能是 `UNVERIFIED`，界面不会显示成"已锁定"。
4. `lockAtWall` **默认为 0**（没有到期锁屏事件）。用户实际监管时段尚未给出，**没有替用户编造每天几点锁屏**。
5. 用户改系统时间/切时区不能延长监管（共享内核 `ClampedClock`）；本轮总控侧未新增持久化墙钟锚点 —— 规则窗口的 `startsAtWall/endsAtWall` 由调用方给出，重启后重新校验由读取时的 `effective(nowWall)` 保证。
6. **「解锁」只能退化感知**（T15）：`KeyguardManager` 的 locked→unlocked 跳变是理想信号，但 15 秒的 tick 间隔常常整个错过；因此采用「锁屏一旦确认就立即起宽限」的退化策略。代价是可能少锁一次，收益是不会把用户挡在修复界面外。**这是明确取舍，不是疏漏**，真机仍需验证 120s 是否够走完修复流程。
7. 修复宽限**到期即清 0**（`expire`），不会变成永久豁免；`FAILED` 回执**不**起宽限，避免「一直锁不上」退化成「永远不锁」。

---

## 4. 跨组发现（已报 captain）

哨兵 `zongkong/sentinel/.../watch/WatchProvider.kt` 的 `TRUSTED["com.zongkong.app"]` 原为
`B742DCD196AF01FA9642848F5297D3165F66AB8C8FF6B85DD09185DBC318AC9`（**63 个十六进制字符**，少一位 `9`），
会让总控 `register` 永远被鉴权拒绝（现象酷似"客户端没接入"）。
正确值：`B742DCD196AF01FA96428498F5297D3165F66AB8C8FF6B85DD09185DBC318AC9`（64 位，实测 apksigner）。
已在 `CertConstantsTest` 加长度/字符集断言防回归。


---

## 提交记录（captain，2026-10-25 23:2x）

本工作树已提交入库（仅本地，未 push）：

| 分支 | 提交 | 基线 |
| --- | --- | --- |
| `deepseek/zk-client-v2` | `5893f67` | `ba8750e` |

提交后强制重跑 `--rerun-tasks`：**269 tests / 0 failures / 0 errors**。
真机项仍全部 NOT_RUN（本机无设备）。
