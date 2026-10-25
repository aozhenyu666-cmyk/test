# 同桌 v2（健康适配器 + 锁屏出口）改动说明

分支：`deepseek/pet-v2`　worktree：`C:\zkwork\pet-wt`　模块：`companion-pet/app`
契约：`_shared/WIRE-SPEC-v2.md`（冻结）　报告：`三APP强制监管与独立哨兵_DeepSeek实施报告.md`
行为基准：报告 §3.5 / §6 / §8 / §11（T02 / T04 / T19）

产物（本机实测）：

| 项 | 值 |
| --- | --- |
| versionCode | **29**（原 28） |
| versionName | **0.7.0-health-v2**（原 0.6.1-preview） |
| 协议版本 | 2 |
| APK | `app/build/outputs/apk/debug/app-debug.apk`，最终 clean 构建后 **29,960,717 字节**（增量构建中曾测到 30,438,423，差异来自资源打包顺序；以最终实测为准） |
| 签名 | 未改签名配置（仍是 `keystore/tongzhuo-debug.jks`，`CN=Tongzhuo Debug, O=companion-pet, C=CN`） |
| 本机证书 SHA-256 | `9f3a524c66974aeb04045f498f83b5970589f9fba3ac3772e95c26e3524695e0` |
| compileSdk / minSdk / targetSdk | 35 / 30 / 35（version catalog 未改结构） |

---

## 1. 核心立场（先说边界，免得被误读）

沿用报告 §3.5，本轮**没有**新造一套专注/拦截能力：

- 专注、拦截、拉回仍然只有 `core/focus/FocusGuard.kt` + `FocusController.kt` + `ForegroundGate.kt` 那一套，**一行逻辑都没改**。
- 新增的东西只有三件：①健康适配器（把挑战投到感知服务的真实执行线程）②本地规则与锁屏出口 ③紧急暂停。
- 健康协议**只统计运行与执行**。它没有任何输入是「任务是否完成」「回答是否合格」，也不读屏幕正文
  （`BoundaryRules.kt` 把这条例外写成断言，越界就先红）。
- 「专注计时结束」≠「思考任务通过」；收到一句回应也**不**自动解除认知任务。这两条在代码里根本没有对应转换。
- 同桌的认知评估（`core/goal/GoalStore`、`core/think/`、`core/probe/`）完全不参与健康协议，运行器不持有它们的引用。

---

## 2. 改动清单

### 2.1 新增文件（全部在 `companion-pet/` 内）

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/cn/companion/pet/health/HealthWire.kt` | 挑战派发：`ChallengeDispatcher`。Binder 线程只负责投递 + 等 latch；事实与时刻必须在执行线程上读。带 `budgetMs` 超时、在途上限（`MAX_IN_FLIGHT=4`，CAS 原子占位）。 |
| `.../health/PetHealthProvider.kt` | `ContentProvider`，authority **`cn.companion.pet.health`**，method `challenge`。含 `SentinelTrust` 鉴权。 |
| `.../health/PetPolicyStore.kt` | 本地规则（规范 §4 JSON 形状，原子写）、`PetReceiptStore`（最近锁屏回执）、`EmergencyPauseStore`（紧急暂停）、`CallerPinStore`（首次配对钉住）。 |
| `.../health/LocalLockExecutor.kt` | `LockGateway` 端口、`LocalLockPlanner`（用共享 `LockArbiter` 去重/冷却/次数上限/修复宽限）、`LocalLockExecutor`（用共享 `LockClassifier` 分五态）、`lockCapability()`。 |
| `.../health/AndroidLockGateway.kt` | 真实锁屏通道：`DevicePolicyManager.lockNow()`（管理员）+ `GLOBAL_ACTION_LOCK_SCREEN`（无障碍）；`KeyguardFacts` 读取。含 `PetDeviceAdmin` 接收器。 |
| `.../health/SentinelLink.kt` | 哨兵连接器：`register` / `syncPolicy` / `reportReceipt` / `ping` / `emergencyStop`，单线程 + 有界队列，绝不从无障碍事件线程或主线程同步调用。 |
| `.../health/SentinelLiveness.kt` | 纯判定「哨兵到底还在不在」：从没接通过 = 未接入（不兜底）；曾经通过但超 30s 无成功回应 = 确认失联。 |
| `.../health/PetGuardRuntime.kt` | 把上面几件接起来：自检、规则同步、周期性本地兜底、紧急暂停、诊断快照、`encodePolicies()`。 |
| `.../health/PetCert.kt` | 同桌自己的证书 SHA-256 常量（实测值）。 |
| `.../health/BoundaryRules.kt` | 边界纪律的白名单/黑名单 + 两条显式 false。 |
| `.../ui/GuardSection.kt` | 设置 → 「监管与锁屏」页：权限状态逐项显示、紧急暂停按钮、哨兵连接与本地兜底的真实状态。 |
| `app/src/main/res/xml/pet_device_admin.xml` | `<uses-policies><force-lock /></uses-policies>`（只有这一项）。 |
| `app/src/test/java/cn/companion/pet/health/PetHealthWireTest.kt` | 新增 40 个 JVM 测试（见 §4）。 |

### 2.2 修改的既有文件

| 文件 | 改动 | 为什么必须改 |
| --- | --- | --- |
| `a11y/PerceptionHub.kt` | 加 `instanceId`（每次连接换新）、`eventSeq`/`eventAtMono`、`healthExecutorHandler`、`markConnected()`/`markDisconnected()`、`noteEvent()`。 | 挑战需要一个**真实的执行线程入口**和一个每次重连都会换新的实例号（规范 §3.2）。 |
| `a11y/PerceptionService.kt` | `onServiceConnected` 里 `markConnected()` + `bindExecutor(handler)`；事件处理里 `noteEvent(elapsedRealtime)`；`disconnect()` 改调 `markDisconnected()`。 | 把「真正承担无障碍执行的那个 Handler」交出去；只有这样才能证明挑战是在执行线程上处理的（T02）。事件进度只记序号和时刻，**不记内容**。 |
| `service/PetBrain.kt` | 新增 `guardTick`（每秒；每 15s 自检、每 10s ping+重注册、每 tick 跑本地兜底）；`start()` 里注册并同步规则；`stop()` 停掉；`onUserPresent()` 给修复宽限。 | 让 v2 跑起来，并且**与专注 ticker 分开**，互不阻塞。 |
| `PetApp.kt` | 加 `guardRuntime`（lazy）与三个只读出口 `policyStore` / `receiptStore` / `callerPinStore`。 | Provider 在被调用时才拿得到这些存储。 |
| `AndroidManifest.xml` | 新增 `<provider android:authorities="cn.companion.pet.health" exported="true">` 与 `<receiver PetDeviceAdmin>`。 | 规范 §3.2 / §5.1。 |
| `ui/Nav.kt`、`ui/SettingsTab.kt` | 新增「监管与锁屏」分区（`SETTINGS_GUARD=4`，诊断顺延为 5）。 | 紧急暂停必须本地一眼可达；权限状态必须能看见。 |
| `app/build.gradle.kts` | `versionCode 28→29`，`versionName 0.6.1-preview→0.7.0-health-v2`。 | 规范 §9。签名配置**未动**。 |

### 2.3 没有改的（特意保留）

- `core/focus/FocusGuard.kt`、`FocusController.kt`、`ForegroundGate.kt`：**零改动**。专注能力仍是原样。
- `health/HealthCore.kt` 与 `health/HealthCoreTest.kt`（共享内核）：与 `_shared/` 权威副本逐行相同
  （只有 `package` 行不同 + 末尾多一个空行），逻辑未改。
- 签名配置、`keystore/` 下的钥匙文件、用户数据：均未动。
- 目标/思考/探针/记忆相关代码：未动，也未接入健康协议。

---

## 3. 关键实现细节（对着 T02 / T04 / T19 说）

### 3.1 T02：挑战必须在真实执行线程上处理

```
PetHealthProvider.call("challenge")            // Binder 线程
  ├─ SentinelTrust.check(getCallingUid())      // 鉴权：UID→包名→证书摘要
  ├─ 执行线程没连上？→ 立刻 ok=false / "not connected"
  ├─ PerceptionHub.healthExecutorHandler.post { // ← 无障碍主线程
  │     facts = readFactsOnExecutor()            //   在这里读规则版本/回执/事件进度
  │     handledAt = elapsedRealtime()            //   时刻也在这里取
  │     latch.countDown()
  │   }
  └─ latch.await(budgetMs)                       // 必须带超时
       ├─ 超时 → ok=false / "dispatcher timeout"  ← 线程冻结时走的正是这条路
       └─ 正常 → 带上 facts 返回
```

- Binder 线程**不构造 ack**：所有返回字段都来自执行线程写进 `AtomicReference` 的对象。
- 不读 `PetService.running`、不读 `PerceptionHub.connected` 当健康凭据；连接状态只用来判断「先决条件」。
- 在途请求用 CAS 原子占位封顶（4），不为不可取消的阻塞调用无限新建线程（T04 的一半）。
- 自检（`requestSelfProbe`）走**完全相同**的通路，在后台线程 await（主线程会死锁），这是「自己是否健康」的唯一凭据。

### 3.2 T04：一个客户端不响应不能拖住别人

- Provider 侧：在途上限 + 超时，Binder 线程最多阻塞 `budgetMs`。
- 哨兵连接侧：`SentinelLink` 单线程 executor + 有界队列；调用带结果判定，失败只记状态。
- PetBrain 的 `guardTick` 与专注 `ticker` 是两个独立 Runnable，任一抛异常都被 `runCatching` 兜住，不影响另一个。
- 主线程上**不做**跨进程调用：`tick()` 只做判断 + 一次设备 API 调用。

### 3.3 T19：结束计时 ≠ 任务通过

- `BoundaryRules.focusExpiryImpliesTaskPassed()` 与 `userReplyResolvesCognitiveTask()` 恒为 `false`，并由测试锁定。
- 健康事实白名单里不含任何认知字段；黑名单（`taskCompleted` / `answerQuality` / `screenText` …）由测试断言不得混入。
- `PetGuardRuntime` 不持有 `GoalStore`；`FocusController` 的结束路径未被健康协议改写。

### 3.4 锁屏出口

- 只有三件事同时成立才动锁屏：**哨兵确认失联**（`SentinelLiveness`）、**自己健康**（真实自检）、**规则仍生效**（`Policy.effective`）。
- 「哨兵从没接通过」不算失联 → 不兜底（那是「未接入」，不是「掉线」）。
- 到点锁屏（`Policy.lockDue`）由共享 `LockArbiter` 去重：同事件一次、全局最小间隔 30s、每事件上限 2 次、重试冷却 3 分钟。
- 回执用共享 `LockClassifier`：`REQUESTED → API_CALLED → OBSERVED_LOCKED / FAILED / UNVERIFIED`。
  - 没设安全锁屏 → `UNVERIFIED` 并写明「可能只是休眠」（T09）。
  - 读不到系统状态 → `UNVERIFIED`，不把「没抛异常」当成功（T10）。
- 锁屏后置 120s 修复宽限，解锁（`USER_PRESENT`）后重新起算，不立刻重锁（T15）。

### 3.5 紧急暂停（与「正常提前结束监管」分开）

- 设置 → 「监管与锁屏」页一个按钮，二次确认后**立刻**调 `EmergencyPauseStore.stop()` 落盘。
- 全程不联网、不问模型、不等冷静期、不要求写理由；理由固定写成「用户在设置页点了紧急暂停」。
- 落盘后重启仍然生效（单测覆盖）。
- 同时尽力通知哨兵；**拿不到确认就显示「暂停未确认」**，不谎称四个都已停止。

### 3.6 版本与签名

- `versionCode 29` / `versionName 0.7.0-health-v2`；协议版本 2。
- 签名配置未改，仍是 `keystore/tongzhuo-debug.jks`。
- **调试签名不是抗伪造方案**：它只让四个 APK 之间有个具体比对对象。

---

## 4. 验证结果（本机实跑）

### 4.1 构建与单测

```
:app:assembleDebug         BUILD SUCCESSFUL   → app/build/outputs/apk/debug/app-debug.apk (29,960,717 B, clean 构建后实测)
:app:testDebugUnitTest     BUILD SUCCESSFUL   → 496 tests / 0 failures / 0 errors（65 个 suite，--rerun-tasks 强制重跑确认）
```

其中：

| Suite | 数量 | 结果 |
| --- | --- | --- |
| `cn.companion.pet.health.HealthCoreTest`（共享内核算例） | 36 | 全绿 |
| `cn.companion.pet.health.PetHealthWireTest`（本轮新增） | 40 | 全绿 |
| 其余既有 63 个 suite | 420 | 全绿（无回归） |

新增用例覆盖：执行线程处理与时序（T02）、执行线程冻结必超时且不代答（T02）、未连接快速失败、
在途上限（T04）、规则 JSON 字段与规范逐字对齐、过期停执行、不能改别人的规则、**改系统时间不延长监管**（T14）、
损坏规则文件隔离、五种锁屏状态（T09/T10）、同事件去重 + 全局间隔 + 修复宽限（T07/T15）、
紧急暂停本地生效/分规则生效/落盘后仍生效/哨兵确认标记（T16）、哨兵在岗三态判定、
能力状态如实显示（不涂绿）、证书摘要必须 64 位小写 hex、边界纪律（T19）。

### 4.2 APK 内实际声明（用 aapt 从 APK 里读回，不是看源码）

```
package: name='cn.companion.pet' versionCode='29' versionName='0.7.0-health-v2' compileSdkVersion='35'
sdkVersion:'30'  targetSdkVersion:'35'

E: provider   android:name="cn.companion.pet.health.PetHealthProvider"
              android:authorities="cn.companion.pet.health"
              android:exported=0xffffffff(true)  android:enabled=true  android:multiprocess=false
E: receiver   android:name="cn.companion.pet.health.PetDeviceAdmin"
              android:permission="android.permission.BIND_DEVICE_ADMIN"  android:exported=true
              meta-data android.app.device_admin=@xml/pet_device_admin
res/xml/pet_device_admin.xml:  device-admin > uses-policies > force-lock
```

### 4.3 证书指纹（实测命令 + 原始输出）

```
> C:\Android\sdk\build-tools\34.0.0\apksigner.bat verify --print-certs app\build\outputs\apk\debug\app-debug.apk

Signer #1 certificate DN: CN=Tongzhuo Debug, O=companion-pet, C=CN
Signer #1 certificate SHA-256 digest: 9f3a524c66974aeb04045f498f83b5970589f9fba3ac3772e95c26e3524695e0
Signer #1 certificate SHA-1 digest: 2540fb3b80d3e7e4aec6c8509c10035ec7f0574b
Signer #1 certificate MD5 digest: 7b0ca72e5a1f702701d122832061bf6a
```

- 该值原样写进 `health/PetCert.kt` 的 `SELF_SHA256`，并有单测断言 `length == 64` 且全为小写 hex
  （哨兵那边出过少一位字符导致鉴权永远失败的问题，这里锁死长度）。
- 重新构建一次后复测，摘要不变（固定 debug 钥匙）。

### 4.4 构建环境上的坑（给后面接手的人）

本机有两处与 `_shared/BUILD.md` 不完全一致，实测结论如下：

1. **Gradle wrapper 8.10.2 的发行包只有 `.part`（0 字节）**，`gradlew.bat` 会尝试联网下载并超时。
   改用已解压好的 `C:\pet-build\gradle-8.10.2`（AGP 8.7.3 需要 Gradle ≥ 8.9，8.9/8.11.1 也可跑）。
2. **`compileSdk 35` 需要 `platforms/android-35`**，本机只有 `C:\AndroidSdk\platforms\android-35` 有
   （`C:\Android\sdk` 只有 android-34）。同时需要 `build-tools/34.0.0`，已把它补进 `C:\AndroidSdk\build-tools\`。
3. **`aapt2` 在 `C:\pet-build\...` 下的那份会报 `error: 数据无效。 (13)`**（同一版本换到别的目录就正常）。
   用 `-Pandroid.aapt2FromMavenOverride=<可用的 aapt2.exe>` 绕开，不要改仓库里的构建脚本。

实际使用的命令：

```powershell
$env:JAVA_HOME        = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:GRADLE_USER_HOME = "C:\pet-build\gradle-home"     # 里面已有 AGP 8.7.3 / Kotlin 2.0.21 / compose 依赖
$env:ANDROID_HOME     = "C:\pet-build\android-sdk"
cd C:\zkwork\pet-wt\companion-pet
> C:\pet-build\gradle-8.10.2\bin\gradle.bat :app:assembleDebug :app:testDebugUnitTest `
    --offline --console=plain "-Pandroid.aapt2FromMavenOverride=C:/zkprobe/a2tool/aapt2.exe"
```

`local.properties`（已在 `.gitignore` 里，不会提交）：
`sdk.dir=C\:\\pet-build\\android-sdk`

---

## 5. NOT_RUN 项及原因（本机无真机，`adb devices` 为空）

以下全部**没有跑过**，不得写成 PASS。写的都是「要做什么才能验」：

| 项 | 状态 | 原因 / 需要什么现场条件 |
| --- | --- | --- |
| 真机锁屏（`lockNow()` 实际锁上） | **NOT_RUN** | 无真机。需要：装 APK → 用户手动激活设备管理员 → 设置系统 PIN → 到点触发 → 观察 `OBSERVED_LOCKED`。 |
| 没设安全锁屏时的行为（是否只休眠） | **NOT_RUN** | 同上；需真机 + 故意不设 PIN，确认回执是 `UNVERIFIED` 而不是 `OBSERVED_LOCKED`（T09）。 |
| 无障碍 `GLOBAL_ACTION_LOCK_SCREEN` 通道 | **NOT_RUN** | 需 API 28+ 真机，且无障碍服务已开。 |
| **T02 真机版**（冻结同桌无障碍线程，看哨兵是否判失败） | **NOT_RUN，但代码级已覆盖** | JVM 测试已证明「执行线程不跑完 → `dispatcher timeout`，不代答」。真机需要：`adb shell` 冻结线程或人为阻塞主线程 + 哨兵同机运行。 |
| **T04 真机版**（IPC 永不返回，其余不受阻） | **NOT_RUN，但代码级已覆盖** | JVM 测试已证明在途上限与超时。真机需要哨兵 + 三个客户端同时跑。 |
| 与哨兵真实对接（register / syncPolicy / reportReceipt / challenge） | **NOT_RUN** | 需要哨兵 v2 装在同一台设备上；还要确认哨兵证书摘要与 `TRUSTED_SENTINEL_CERTS` 里的 `b742dcd1…18ac9` 一致。 |
| 哨兵失联时的本地兜底锁屏 | **NOT_RUN** | 需要两台活着的进程 + 人为停掉哨兵。纯逻辑已在单测里推进时间验证。 |
| 紧急暂停的跨应用传播（哨兵确认） | **NOT_RUN** | 需要哨兵实现 `emergencyStop`；本端已按「拿不到确认就显示暂停未确认」实现。 |
| 24 小时连续运行、耗电、真实窗口事件通道 | **NOT_RUN** | 需要真机长跑（T20）。 |
| 国产 ROM 后台存活 / 自启动 / 后台弹出界面 | **NOT_RUN** | 需要目标机型。 |
| Device Owner 路线 | 不做 | 本轮明确不要求，也未实现。 |
| 防卸载 | 不做 | 报告 §4 明确留待后续；`pet_device_admin.xml` 只声明 `force-lock`。 |

---

## 6. 已知限制（界面与文档都如实写，不粉饰）

1. 同桌被系统**强制停止**后无法自我恢复；只有前台服务被允许继续跑时才能自检与兜底。
2. 普通 APK 做不到「绝对无法绕过」；本轮也不追求。
3. 没有设备管理员权限时只剩无障碍一条通道；无障碍服务本身卡死时那条通道也会失效——所以同桌的兜底
   只在「自己健康 + 哨兵确认失联」时才动，不会在两者都坏的时候假装还能锁。
4. 到点锁屏是「一次」事件：同一 `incidentId` 只锁一次，全局最小间隔 30s，不做「解锁后每秒再锁」。
5. 哨兵只收到健康、权限、规则与执行结果；屏幕正文、录音、认知答案都不在本协议内。
6. 调试签名不是抗伪造方案。

---

## 7. 需要队长/后续确认的两件事

1. `SentinelTrust.TRUSTED_SENTINEL_CERTS` 里填的是队长实测的哨兵摘要
   `b742dcd196af01fa96428498f5297d3165f66ab8c8ff6b85dd09185dbc318ac9`。
   如果哨兵最终换签名，这里必须同步改，否则 `challenge` 会一律被拒（会返回
   `certificate not in trusted list`，不会静默放行）。
2. ~~`SentinelLink` 里的 `emergencyStop` / `ping` 方法名……~~ **已在 F-02 修掉**（见 §8）。

---

## 8. F-02 修复：紧急暂停两端接通（verifier 独立验收发现，captain 复现确认）

verifier 发现两个跨应用契约缺口。**方法名的方向是不对称的**，这是关键：

| 方向 | 方法名 | 为什么 |
| --- | --- | --- |
| 哨兵 → 同桌 | `emergencyStop` | 哨兵 `watch/ClientCall.kt:notifyEmergency()` 发的就是这个名字，客户端必须认。 |
| 同桌 → 哨兵 | **`syncPolicy`** | 哨兵 `WatchProvider` 只认 `register / syncPolicy / reportReceipt / ping`（规范 §3.1 冻结）。**不能**为暂停新增第六个方法名。 |

### 8.1 缺口 1：同桌原先不认哨兵发来的 `emergencyStop`

**修法**：`health/PetHealthProvider.kt` 的 `call()` 改成先鉴权、再按方法分派：

| 方法 | 位置 | 说明 |
| --- | --- | --- |
| `challenge` | `PetHealthProvider.kt:44` → `challenge()` | 原逻辑不变（投递到 a11y 执行线程） |
| `emergencyStop` | `PetHealthProvider.kt:45` → `emergencyStop()` **:100-131** | **新增**，见下 |
| `ping` | `PetHealthProvider.kt:46` → `ping()` **:134-152** | **新增**，只回答「provider 活着 + 是否暂停」 |

`PetHealthProvider.METHOD_EMERGENCY = "emergencyStop"`、`METHOD_PING = "ping"`、`KEY_REASON = "reason"` 与哨兵实际发送的键逐字对齐。

**语义（与一手 `HealthProvider.emergencyStop` 对齐）**：

- 立即把本地规则置 `STOPPED` + 写**独立暂停标记**，都原子落盘；
- **写完读回核对**才回 `ok=true`；写盘失败回 `ok=false` + `error`(note)，让哨兵显示「暂停未确认」，
  **绝不谎称已停**；
- **幂等**：已经是停的直接回 `ok=true`（`alreadyStopped=true`），且不重复写规则文件；
- 不联网、不请求模型、不等冷静期、**不碰无障碍线程**（避免把事件通道拖住）。

关键：哨兵下发的暂停与用户点本地按钮走的是**同一个** `PetGuardRuntime.stopLocal()`
（`PetGuardRuntime.kt:314-333`），所以两条路径的语义不可能漂移。

### 8.2 缺口 2：同桌原先报给哨兵的方法名哨兵不认识

原来的 `SentinelLink.notifyEmergencyStop()` 向 `com.zongkong.sentinel.health` 发 `"emergencyStop"`，
哨兵只认四个方法，会回「不认识的请求」。**该方法已删除**。

现在上报走 `syncPolicy`，把 `emergencyState=STOPPED` 随规则一起同步：

- `SentinelLink.syncPolicy(policyJson)`：`SentinelLink.kt:105-118`；
  只有 `acceptedRevision >= 0` 才算哨兵真的接受了（不把「发出去了」当「确认了」）。
- `PetGuardRuntime.reportPauseToSentinel()`：`PetGuardRuntime.kt:335-346`，
  用 `encodePolicies(..., forcedEmergencyState = "STOPPED")` 编码。
- `PetGuardRuntime.encodePolicies()` 新增 `forcedEmergencyState` 参数：`PetGuardRuntime.kt:416-441`。
- 恢复时同样走 `syncPolicy`，把 `NONE` 同步过去。

哨兵侧已核对（`sentinel/.../PolicyStore.kt`）：它把 `emergencyState` 读进来，
并在 `lockDue` / `failureWindows` 里用 `emergencyState != EMERGENCY_STOPPED` 过滤 ——
也就是说同桌报上去的 STOPPED 会让哨兵**不再为这个窗口锁屏**，这条路是双向通的。

本端当前发给哨兵的方法名只有四个（`SentinelLink.kt` 里逐条可查）：
`register`(:91) / `syncPolicy`(:112) / `reportReceipt`(:149) / `ping`(:156)，全在冻结集合内。

### 8.3 三条来源：暂停必须跨「窗口翻页」和「进程重启」存活

只靠规则文件里的 `STOPPED` **不够**：窗口翻页会生成新 `policyId`、旧规则被删掉；
进程重启会清掉内存事实。所以照一手的做法加了**独立标记文件** `policy/emergency.json`。

判定集中在**唯一一处** `EmergencyGate`（`health/EmergencyStopLocal.kt:64-99`），三条来源取并集：

| # | 来源 | 存在哪 | 能挡住什么 / 挡不住什么 |
| --- | --- | --- | --- |
| 1 | 内存事实 `memoryPaused` | `EmergencyPauseStore` 缓存 | 挡当前进程；**重启就丢** |
| 2 | 规则文件 `emergencyState == "STOPPED"` | `policy/policies.json` | 规范 §4 的字段；**窗口翻页会被顶掉** |
| 3 | **独立标记** `markerStopped` | `policy/emergency.json` | 跨翻页、跨重启都活；是这个功能的真正兜底 |

`PetGuardRuntime.pauseSources()`（`:297-304`）是三条来源的唯一汇总口，
`tick()`（`:211-232`）在**任何锁屏判定之前**先查它，命中就直接 return、连评估都不做：

```
val paused = isPaused()                 // 三条来源并集
if (policies.isEmpty()) { ...; return }
if (paused) {                           // 暂停期间不碰设备、不动仲裁器
    lastPlanReason = "紧急暂停中：..."
    return
}
```

`LocalLockContext.emergencyBlocked` 里**再传一次**同一个判定，防止将来有人调整顺序时漏掉；
`LocalLockPlanner.plan()` 自己在 `emergencyBlocked` 时也直接返回不执行（双保险）。

**设计决定（与一手对齐）**：暂停「到用户点恢复为止」，**不**随窗口结束自动失效。
宁可少锁，也不在用户按了停止之后换个窗口又开始锁。代价是界面有义务显眼地说清「暂停仍在生效」。

### 8.4 新增单测（`app/src/test/java/cn/companion/pet/health/EmergencyStopTest.kt`，20 个）

| 组 | 覆盖 |
| --- | --- |
| 缺口 1 | Provider 认 `emergencyStop` / `ping`；`reason` / `v` 键名与哨兵实际发送的一致 |
| 缺口 2 | 对外方法名必须落在冻结集合内；上报用 `syncPolicy` 且 JSON 里 `emergencyState=STOPPED`；恢复时是 `NONE`；不强制时沿用规则自身值 |
| 三条来源 | 任一条成立即暂停；**「只剩独立标记」也仍然暂停**（窗口翻页后的关键情形）；`describe()` 说明哪条命中 |
| 停止语义 | 两条来源都写、读回核对、幂等（不重复写规则但每次都确认标记）、规则写失败→ok=false、标记写失败→ok=false、空清单也写标记、恢复清干净 |
| **硬门槛** | 暂停时 planner 拒绝执行；**用真文件**模拟「窗口 A 被删 + 进程重启（全部 new）+ 到点 + 哨兵失联 + 自己健康」→ 仍然不许锁屏；显式恢复后同样条件下**可以**再锁（没把暂停写成永久失效） |
| 存储 | 独立标记从磁盘读回核对（不信缓存）、原子替换被拒→`stopped=false`、`stopAll`/`resumeAll` 持久化 |
| 边界 | 暂停回包字段不含认知/任务字段 |

### 8.5 F-02 之后的验证结果

```
:app:assembleDebug       BUILD SUCCESSFUL
:app:testDebugUnitTest   BUILD SUCCESSFUL   516 tests / 0 failures / 0 errors（66 suites）
```

其中 `HealthCoreTest` 36、`PetHealthWireTest` 40、**`EmergencyStopTest` 20**，其余既有 suite 无回归。

---

## 9. F-06 修复（blocker）：同桌的规则以前永远进不了哨兵

verifier 的交叉审计（t6）发现的**真 blocker**，captain 逐行复核确认。这是本轮最严重的跨应用缺陷。

### 9.1 根因：两边说的 issuer 不是同一个词

| 侧 | 位置 | 值 |
| --- | --- | --- |
| 哨兵的期望 | `sentinel/.../watch/PolicyStore.kt:161` `issuerForPackage("cn.companion.pet")` | `"pet"` |
| 哨兵的门 | 同文件 `:97-98` `if (p.issuer != issuer) return Merge(false, error = "叫 X 的调用方不能写 Y 的规则")` | 不等就拒 |
| 同桌实际发 | `PetGuardRuntime.kt` 的 `encodePolicies` 里用的是本地 `PolicyDto.issuer` | `"user"`（`ISSUER_USER`） |

`"pet" != "user"` → **每一次 `syncPolicy` 都被哨兵拒**，同桌的规则从来没有写进过哨兵的 `policies.json`。

**后果**：哨兵的 `lockDue()`(:143) 与 `failureWindows()`(:148) 永不为同桌触发 →
同桌失去**哨兵侧的 lockAt 到期锁屏**和**失联兜底锁**。本地仍执行（`PetGuardRuntime.tick()`），
属**降级**而不是完全不锁，但直接削弱报告 T04 / T06 的兜底设计。

`ISSUER_PET = "pet"` 之前只在本地判断里用到（`ownedByPet()`、`upsert()` 的校验），
**从未写进要发出去的报文**，所以它救不了这个缺陷。

### 9.2 修法：只归一化发出去的报文

新增 `wireIssuer()`（`health/PetPolicyStore.kt:76-84`）：

```kotlin
fun wireIssuer(localIssuer: String): String = when (localIssuer) {
    ISSUER_USER -> ISSUER_PET     // 本地 user → 哨兵认的 pet
    ISSUER_PET  -> ISSUER_PET
    else        -> localIssuer    // 别人的 issuer 不冒名，原样发出让哨兵照规矩拒
}
```

在 `encodePolicies()` 里应用（`health/PetGuardRuntime.kt:430` `val issuer = wireIssuer(dto.issuer)`，
`:435` 写进报文）。

**边界（一处都没越过）**：

- **本地存储仍存 `user`**，本地数据、`policies.json`、`ownedByPet()` 语义全部不动；
- 不动哨兵的 `issuerForPackage`，也**不要求哨兵放宽接受 `user`** ——
  那等于允许同桌往共享的 `user` 命名空间写规则，是越权，且规范 §3 契约冻结；
- 归一化只发生在「同桌 → 哨兵」这一个方向。

### 9.3 实测：发出去的 JSON 里 issuer 到底是什么

用一次性探针用例把真实报文打出来（跑完已删除）：

```
PROBE-LOCAL-ISSUER=user
PROBE-WIRE-JSON={"schemaVersion":2,"policies":[{"policyId":"pet-window","revision":3,"issuer":"pet",
  "requiredApps":["cn.companion.pet"],"startsAtWall":100,"endsAtWall":900,"lockAtWall":500,
  "failureAction":"LOCK","repairGraceMs":120000,"emergencyState":"NONE","enabled":true}]}
PROBE-WIRE-PAUSE={... "emergencyState":"STOPPED" ...}   ← 紧急暂停上报同样是 issuer":"pet"
PROBE-WIRE-ISSUER-USER=pet
PROBE-WIRE-ISSUER-PET=pet
```

**本地 `user`，报文 `pet`** —— 正是哨兵 `PolicyStore.kt:161` 期望的值。

### 9.4 为什么以前的互操作测试是假绿灯（值得记住的教训）

原来那条 `PetHealthWireTest.encoded policy json is interoperable` 是这么写的：

```kotlin
val back = Json.decodeFromString(PolicyFileDto.serializer(), json)
assertEquals("user", back.policies.first().issuer)   // ← 断言的是自己发出去的值
```

它只验证「能被**同桌自己的** `PolicyDto` 读回」。**被测方和验证方是同一个实现**，
所以 `user` 这种哨兵根本不收的值也能读回、也能绿。它验证的是「我能读懂我写的」，
而跨应用契约要验证的是「**对方**能接受我写的」。这条测试永远绿，F-06 因此在两轮验收里都没被抓到。

**正确的做法**：拿对方的期望值当断言依据。已替换为新用例：

| 新测试名 | 断言依据 |
| --- | --- |
| `wire json carries the issuer the sentinel actually accepts` | JSON 里必须是 `"issuer":"pet"`；且**不得**出现 `"issuer":"user"`；用正则取出实际值再与哨兵期望值相等 |
| `sentinel merge gate accepts our wire json but would reject the local issuer` | 把哨兵 `:97` 的 `p.issuer != issuer` 门照抄一份，证明「修后放行、修前被拒」 |
| `wire issuer normalisation keeps local storage untouched` | 本地仍是 `user`、`ownedByPet()` 语义不变、别人的 issuer 不冒名 |
| `wire json round-trips into the sentinel schema shape` | 其余 §4 字段仍然齐全 |

哨兵的期望值 `"pet"` 在测试里硬编码，并注明来源是 `sentinel/.../watch/PolicyStore.kt:161`
（本方仓库里没有哨兵的类可引用）。

### 9.5 顺带修掉的两个审计点

- **C-2 挑战预算上限**（`health/HealthWire.kt:94`）：原来只拒 `<= 0`，哨兵给多大就等多久。
  现在 `budgetMs.coerceIn(MIN_BUDGET_MS, maxBudgetMs)`，生产常量 `MIN=500ms` / `MAX=20s`（`:156-157`），
  与总控、一手对齐。`maxBudgetMs` 做成可注入参数（`:81`），
  好让单测能在毫秒级验证「上限真的生效」，不必真等 20 秒。
  测试：`challenge clamps an absurd budget instead of honouring it`、`budget bounds match the other two clients`。
- **C-3 删掉不可达的 `API_CALLED` 分支**（`health/PetGuardRuntime.kt:280`）：
  `LocalLockExecutor` 走 `LockClassifier`，实际只会产 `FAILED / OBSERVED_LOCKED / UNVERIFIED`，
  所以 `|| LockState.API_CALLED` 是死分支。留着会让人误以为这里「把调用返回当成功」，
  而报告 §8 明令禁止。现在只有 `OBSERVED_LOCKED` 才给 120s 修复宽限。

### 9.6 F-06 之后的验证结果

```
:app:assembleDebug       BUILD SUCCESSFUL
:app:testDebugUnitTest   BUILD SUCCESSFUL   521 tests / 0 failures / 0 errors（66 suites）
```

其中 `HealthCoreTest` 36、`PetHealthWireTest` **45**（新增 5 条）、`EmergencyStopTest` 20，其余既有 suite 无回归。

> 仍未真机验证：哨兵实际接受并落盘同桌的规则（需要哨兵 + 同桌同时装在真机上）。
> 本轮只到「报文内容与哨兵的门在代码级一致」这一层，真机项仍记 NOT_RUN。
