# 一手 v2 接入说明（YISHOU-V2-NOTES）

分支：`deepseek/yishou-v2`　工作目录：`C:\zkwork\yishou-wt`
版本：**versionCode 10 / versionName `0.10.0-health-v2`**（签名配置未动，仍是 `app/debug.keystore`）
协议版本：**2**　自有证书 SHA-256：`EDCE45B1C655885E106D3A08A76D169E8A50CFA79F364415F8AC997B44D87BCE`

契约依据：`_shared/WIRE-SPEC-v2.md`、`_shared/BUILD.md`、共享内核 `com.yishou.app.health.HealthCore.kt`（**逐字复制，未改逻辑**）、报告 §3.4 / §6 / §8 与 §11 的 T02 / T04 / T18。

---

## 1. 离场条件（本机实测）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 编译 | `.\gradlew.bat :app:assembleDebug --offline` | **PASS** — `app-debug.apk` 12,274,113 字节 |
| 单元测试 | `.\gradlew.bat :app:testDebugUnitTest --offline` | **PASS** — 232 tests / 0 failures / 0 errors |
| 共享内核算例 | `HealthCoreTest` | **PASS** — 36/36 全绿 |
| 新增 healthwire 用例 | 见下表 | **PASS** — 95/95 全绿 |

环境：`JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`、`GRADLE_USER_HOME=C:\guh`、`ANDROID_HOME=C:\Android\sdk`，全程 `--offline`，路径纯 ASCII。

测试明细：

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| `health.HealthCoreTest` | 36 | 共享内核（原样复制） |
| `healthwire.A11yChallengeTest` | 11 | T02：投递到 a11y 线程 / 超时 / 不代答 / 在途上限 / C-1 facts 在执行线程读 |
| `healthwire.StatusCacheTest` | 11 | T04：缓存读 / 后台刷新 / 超期失联 / 单飞 |
| `healthwire.LaunchTrackerTest` | 11 | T18：REQUESTED→API_ACCEPTED→VERIFIED_FOREGROUND |
| `healthwire.LocalPolicyTest` | 11 | §4：字段完整 / 只改自己 / 过期停 / STOPPED |
| `healthwire.LocalGuardTest` | 17 | §5.3：有限兜底五条件 + 去重 + 冷却 + 宽限 |
| `healthwire.RuleBuilderTest` | 8 | §4：不替用户编造时段；不收集屏幕文字 |
| `healthwire.EmergencyStopWireTest` | 17 | §6 跨应用契约：停了不再锁 / 幂等 / 跨窗口跨重启仍生效 / bySentinel 来源 |
| `healthwire.HealthProviderWireTest` | 9 | 回包白名单：ping / emergencyStop 不含屏幕文字与认知数据 |
| 其余既有用例 | 101 | 未回归 |

APK 证据：

```
package: name='com.yishou.app' versionCode='10' versionName='0.10.0-health-v2'
Signer #1 certificate SHA-256 digest: edce45b1c655885e106d3a08a76d169e8a50cfa79f364415f8ac997b44d87bce
provider com.yishou.app.healthwire.HealthProvider authorities="com.yishou.app.health"
receiver com.yishou.app.healthwire.YishouAdmin permission=BIND_DEVICE_ADMIN
```

---

## 2. 改动清单（全部在 `yishou/app/` 内）

### 2.1 新增：健康适配器（T02）

| 文件 | 作用 |
| --- | --- |
| `src/main/java/com/yishou/app/healthwire/HealthProvider.kt` | authority `com.yishou.app.health`，method `challenge`。**只接受哨兵签名**（`Partner.callerIsSentinel`，包名 + 证书双校验） |
| `src/main/java/com/yishou/app/healthwire/A11yChallenge.kt` | 挑战处理器（纯 Kotlin）：`post` 到 a11y 主线程 → `latch.await(budgetMs)` → 超时返回 `dispatcher timeout` |
| `src/main/java/com/yishou/app/healthwire/Facts.kt` | 进程内事实：`instanceId` / `eventSeq` / `receiptState` / `ruleRevision` / `paused` |

**做法（严格照规范 §3.2 的实现要点）**：Binder 线程读参数 → 取 `GateService.postOnA11yThread` →
`Handler(Looper.getMainLooper()).post { 读 facts; 记录 handledAtMono; countDown }` → `latch.await(budgetMs)`。
- 无障碍没连上 → 立刻 `ok=false, error="not connected"`；
- 线程冻结 → `ok=false, error="dispatcher timeout"`；
- **绝不在 Binder 线程上构造 ack**；**不读 `running` 布尔当健康凭据**；在途上限 2，不为阻塞调用堆线程。

**C-1 对齐（verifier t6 交叉审计，2026-10-25）**：`facts()` 快照原先在 Binder 线程读
（旧 `A11yChallenge.kt:139`），现已挪进 `post { }` 的 Runnable 内、与 `handledAtMono` 同一条执行线程、
且先于时刻读取——与总控 `healthwire/A11yChallenge.kt:111`（`captured.set(facts())` 在 Runnable 内）和
同桌 `health/HealthWire.kt:104`（`factsRef.set(readFactsOnExecutor())` 在 Runnable 内）**完全同构**。
Binder 线程现在只剩参数校验与 `latch.await(budgetMs)`；超时路径**仍不读 facts、不构造 ack**。
意义：带回的是「执行线程处理完那一刻」的本地事实，而不是 Binder 线程事后补读的——
线程冻结时（正是 T02 要测的场景）两者可能并不一致。

### 2.2 关键修复：无障碍事件线程不再被跨进程调用拖住（T04/T05）

`gate/GateService.kt` 里两处同步跨进程调用都已移除：

| 原代码 | 修正 |
| --- | --- |
| `check()` 里 `if (ZongkongClient.status(this)?.lockdown == true) return` | 改读 `ZongkongLink.read()`（**纯内存快照**），并 `requestRefresh()` 触发后台刷新 |
| `scope.launch { withContext(IO) { ZongkongClient.status(...) } }` 里等 IPC 再判断 `bounces()` | 改为直接用同一份缓存判断 `zk.bounces(pkg)`，判断本身不阻塞；Room 查询另移进 `Dispatchers.IO` |

新增 `healthwire/StatusCache.kt`（纯逻辑）+ `healthwire/ZongkongLink.kt`（Android 侧）：
- 事件线程只读缓存 → 纳秒级返回；
- 后台**单线程**刷新，`CountDownLatch.await(5s)` 硬超时；只允许一个在途（`AtomicBoolean`）；
- 刷新超期 / 失败 → **失联态**（`StatusMode.LOST`，`status = null`）；
- 失联时 `lockdown=false`、`bounces()=false`，**按本地规则继续走**，而不是「读不到 ⇒ 当没在严管就放行」。

`link/ZongkongClient.kt` 已删掉 `status()`，只保留 `open()`，防止再被事件链路误用。

### 2.3 修 `launchGate` / `bringToFront`：不把调用返回当成功（T18）

新增 `healthwire/LaunchTracker.kt`（纯逻辑）：

```
REQUESTED →（startActivity 返回 true）→ API_ACCEPTED
          →（目标页自己 onResume）→ VERIFIED_FOREGROUND   ✅ 只有这条算成功
          →（3s 内未观测到）      → NOT_FOREGROUND       → 改发通知
          →（抛异常）             → API_REJECTED / FAILED
```

- `GateService.launchGate()` 走 `launchVerified()`：先记 REQUESTED，再记 API_ACCEPTED，
  由 `GateActivity.onResume` 回调 `LaunchTracker.claimAwaiting(...)` 认领；3s 未到位记
  NOT_FOREGROUND + `RunLog.e` + 发「思考页没能自己弹出来」通知。
- `GateService.bringToFrontWithReceipt()` 返回 `launchId`（不再是布尔当真值），
  `WindowViewModel.pullBack()` 据此等 3.5s 再看 `LaunchTracker`：没验证到就发「这一手还在等你」通知。
- `WindowActivity.onResume` 同样回调 `claimAwaiting(TARGET_WINDOW, ...)`。

### 2.4 新增：本地规则 + 本地锁屏 + 有限兜底

| 文件 | 作用 |
| --- | --- |
| `healthwire/LocalPolicy.kt` | 规则 JSON ↔ 数据类（纯逻辑）；§4 的 12 个字段一个不少；`effective()` 过期即停；`merge()` 只接受 `issuer=yishou` 且 revision 更新；`stopAll/resumeAll` |
| `healthwire/PolicyStore.kt` | `filesDir/local-policy.json`，**原子写**（`.tmp` → rename），带 `schemaVersion`；文件坏了退回空规则并记 `lastError`（不静默放行） |
| `healthwire/RuleBuilder.kt` | 把**用户自己配置的陪练窗口**翻译成本地规则。**不替用户编造每日锁屏时间**（规范 §4），也不生成定时锁屏事件（`lockAtWall=null`） |
| `healthwire/LockExecutor.kt` | `YishouAdmin`（DeviceAdminReceiver，**只声明 `<force-lock/>`**）+ `LocalLockExecutor`（`lockNow()` + `LockClassifier` 观测；没设安全锁屏 → UNVERIFIED，不报「已安全锁定」） |
| `healthwire/LocalGuard.kt` | 兜底决策（纯逻辑）：**规则生效 + 哨兵失联 + 自己健康 + 有锁屏能力 + LockArbiter 通过**，五个条件同时成立才动手 |
| `healthwire/YishouGuard.kt` | 编排：自有后台线程每 10s 一拍（`HealthConfig.probePeriodMs`）+ 每 30s ping 哨兵；算健康 → 有限兜底 → 上报回执 |
| `healthwire/SentinelLink.kt` | 客户端连接器：`register` / `syncPolicy` / `reportReceipt` / `ping`，全在后台单线程 + 3s 预算，在途 1 |
| `src/main/res/xml/device_admin.xml` | `<uses-policies><force-lock /></uses-policies>`（原先**没有**这个文件，是按规范 §5.1 新增） |

去重全部用共享内核 `LockArbiter`（`incidentId` = `policyId@窗口#日`），每次决策带
`policyId / incidentId / requestId / source=yishou / reason`。单事件上限 2 次、冷却 3 分钟、
最小间隔 30 秒、解锁后 120 秒修复宽限。

### 2.5 紧急暂停（规范 §6，本地可达、不依赖网络/模型）

`healthwire/EmergencyStop.kt` + `ui/WatchHealthScreen.kt`：
- 一次点击立即执行：规则全部标 `STOPPED` 并**原子落盘** → 清空本地仲裁器 → `Facts.setPaused(true)`；
- 不走网络、不问模型、不要求写理由、不等冷静期；
- 记录使用时间 + 理由，写 `RunLog` + 发一条通知；
- 同步给哨兵失败时**明确显示「暂停未确认（以哨兵回执为准）」**，不宣称「四个应用都停了」；
- 与「正常提前结束本轮陪练窗口」（`window/WindowViewModel.kt` 的 `windowStoppedAt` + 冷静期 +
  二次确认）**完全分开**：那条路径一行未改。

入口：权限页 →「监管与健康」→ `WatchHealthScreen`（`MainActivity` 新增路由 `health`）。

### 2.5b 跨应用契约补齐：`emergencyStop` / `ping`（哨兵 → 一手）

**补齐缺口**：此前 `HealthProvider` 只实现 `challenge`，哨兵 `watch/ClientCall.kt` 的
`emergencyStop` / `ping` 一律落到 `else -> err("不认识的请求：…")`，传播必然失败。
现在三个客户端与哨兵同名同义，闭环成立。

| method | 实现位置 | 语义 |
| --- | --- | --- |
| `emergencyStop` | `healthwire/HealthProvider.kt:72`（分派 `:51`，常量 `:205`） | 立即把本地规则置 `STOPPED` 并原子落盘 + 写独立暂停标记；**写完读回核对**才回 `ok=true` |
| `ping` | `healthwire/HealthProvider.kt:114`（分派 `:52`，常量 `:208`） | 只回 `ok / v=2 / pkg / instanceId / stopped / atMono / providerAlive`；**不是健康凭据** |

共用核心：`healthwire/EmergencyStop.kt:81` `stopLocal(sink, nowWall, reason)`，通过
`StopSink`（`:27`）注入落点 —— 生产是 `PolicyStore`，单测是内存假件（因此可 JVM 验证）。

`emergencyStop` 回包：`ok=true`、`stopped=true`、`alreadyStopped`、`policyIds`（逗号分隔的 id 摘要）、
`stoppedCount`、`ruleRevision`、`atWall`、`atMono`、`note`。哨兵据此显示「已确认停止」。

**幂等**：已全部 `STOPPED` 时不再写规则文件，但仍返回 `ok=true` 且 `alreadyStopped=true`。

**不阻塞事件线程**：`emergencyStop` 是本地文件写、`ping` 只读内存事实，两者都**不碰 a11y 线程**，
也**不发起任何跨进程调用**（T04 的教训：事件线程上做重活会把整条拦截链拖死）。

**鉴权未放宽**：仍只走 `Partner.callerIsSentinel`（`Binder.getCallingUid()` → `getPackagesForUid`
必须含 `com.zongkong.sentinel` + 证书 SHA-256 比对），不看消息体自报身份。

**关键修复（暂停必须真拦住兜底）**：`LocalGuard.kt:67` 的 `facts.emergencyStopped` 是硬前置条件；
`YishouGuard.kt:186` 现在按**三条来源任一成立**计算它：

1. `Facts.paused`（本次进程内按下的暂停）
2. `LocalPolicy.anyStopped(doc)`（规范 §4 的 `emergencyState` 字段）
3. `PolicyStore.emergencyStopped()`（**独立持久化标记** `filesDir/emergency-stop.json`）

为什么需要第 3 条：窗口翻页后 `RuleBuilder` 会按新窗口生成**新 `policyId`** 的规则，
旧 `STOPPED` 规则不再存在；进程重启也会清掉内存事实。只有独立标记能让「暂停」跨这两件事存活——
否则就会出现「暂停了但兜底还在锁」，比不暂停更糟。

回包字段白名单在 `healthwire/ProviderWire.kt`（`PING_KEYS` / `EMERGENCY_KEYS` / `FORBIDDEN_KEYS`），
加字段必须先过白名单，单测逐键核对。

单测证据（17 条，全绿）：

| 测试 | 数量 | 钉住的行为 |
| --- | --- | --- |
| `healthwire.EmergencyStopWireTest` | 17 | emergencyStop 后 `LocalGuard` 不再触发；窗口翻页（新 policyId）仍不触发；重启后仍算暂停；幂等（5 次连调均 ok、不重复写盘）；写盘/标记失败 → `ok=false`；恢复后可重新判断；`bySentinel` 区分来源；method 名与哨兵一致 |
| `healthwire.HealthProviderWireTest` | 9 | ping / emergencyStop 回包键集合 == 白名单；不含 `screenText`/`taskTitle`/`answer`/`memory`/`cognition` 等；`policyIds` 是纯 id 摘要；`v=2` |

### 2.5c 设计决定：紧急暂停跨窗口、跨重启「一直生效」（已裁决，非遗留问题）

**决定**：一手的紧急暂停语义是「**到用户点恢复为止**」，**不随窗口结束自动失效**。
captain 2026-10-25 裁决保留这一保守写法（不改成随窗口失效）。

**理由**：
- 紧急暂停是用户手动按下的安全出口，**宁可少锁、不可多锁**。若跨窗口自动恢复成「会锁」，
  等于用户按了停止、换个窗口又开始锁——这比不锁更危险，也更像故障。
- 报告 §8 只要求「紧急暂停须本地可达、不依赖模型和网络，并传播给其他已连接客户端」，
  **没有**要求它随窗口结束而失效。
- 三条来源（内存 `Facts.paused` ∨ 规则 `STOPPED` ∨ 独立标记文件）恰好关掉了两个真实漏洞：
  窗口翻页换 `policyId`、进程重启清内存。

**唯一判定入口**：`healthwire/EmergencyStop.kt` 的 `isPaused(memoryPaused, doc, storeFlag)`。
`YishouGuard.tick()`（决策）、`YishouGuard.snapshot()`（界面）、单测**全部调它**，
避免几处各写一遍然后漂移——漂移的后果正是「暂停了但兜底还在锁」。

**配套的界面义务（硬要求，报告 §10 口径）**：
`ui/WatchHealthScreen.kt` 在页面**最上方**放了一张 `errorContainer` 横幅，暂停生效时显示：

> ⚠ 紧急暂停生效中，本轮自动锁屏已停止

并逐条写清：不会自动锁屏、**换到下一个时间窗口/重开应用都不会自己恢复**、暂停时间、原因、
来源（哨兵传来 / 用户自己按）、以及「只停一手，不替其他应用担保」，横幅内直接给「恢复自动锁屏」按钮。

这条横幅**不是**只依赖内存记录：`PolicyStore.emergencyRecord()` 从标记文件读回
`atWall / reason / bySentinel`，所以**进程重启后仍能说出是哪一次、为什么**；
标记里 `bySentinel=true` 让界面能如实区分「哨兵传来的暂停」和「我自己按的」。

为什么必须显眼：报告 §10 要求宁可显示「失效/已停」，也不能让用户以为自己在被监管、实际没有。
安静地什么都不做正是这里要避免的失败模式。

对应单测（`EmergencyStopWireTest`，5 条专门钉这一段）：
- `isPaused 三条来源任一成立就算暂停`
- `重启后内存事实清空 但独立标记仍让 isPaused 为 true`
- `暂停跨窗口仍然生效 这是明确的设计决定而不是遗留问题`
- `bySentinel 标记能区分暂停来源 供界面如实说明`
- `暂停标记保留时间与理由 重启后界面能说清是哪一次`

### 2.6 保留「只读窗口包名、不读屏幕正文」

- `res/xml/gate_service.xml` **未修改**（`canRetrieveWindowContent=false` 保持原样）；
- `Facts` 里只记 `pkg`（不进 ack）、事件序号、时刻；`AckFacts` 里没有、也不可能有屏幕文字；
- 新增单测 `RuleBuilderTest.规则里不出现任何屏幕文字或正文内容` 断言规则 JSON 不含 `screen`/`text`/`content` 键。

### 2.7 版本与签名

- `app/build.gradle.kts`：`versionCode 9 → 10`，`versionName 0.9.0 → 0.10.0-health-v2`；
- `signingConfigs.debug` **一行未改**（仍是 `app/debug.keystore` / `androiddebugkey`）；
- 未清任何用户数据、未新生成密钥。

### 2.8 清单与文案

- `AndroidManifest.xml` 新增 `.healthwire.HealthProvider`（authority `com.yishou.app.health`，exported）
  与 `.healthwire.YishouAdmin`（`BIND_DEVICE_ADMIN`，`@xml/device_admin`）；
- `res/values/strings.xml` 新增 `admin_description`（说明只申请锁屏、不碰密码与数据）；
- `YishouApp.onCreate()` 末尾 `YishouGuard.install(this)` + 恢复上次的暂停状态。

### 2.9 改动的既有文件一览

```
app/build.gradle.kts                       版本号
app/src/main/AndroidManifest.xml           health provider + device admin receiver
app/src/main/res/values/strings.xml        admin_description
app/src/main/java/com/yishou/app/YishouApp.kt                install YishouGuard
app/src/main/java/com/yishou/app/MainActivity.kt             health 路由
app/src/main/java/com/yishou/app/gate/GateService.kt         ★ 事件线程去阻塞 + T18 回执 + 挑战投递
app/src/main/java/com/yishou/app/gate/GateActivity.kt        onResume 认领启动回执
app/src/main/java/com/yishou/app/gate/GateViewModel.kt       改走 ZongkongLink.fetchOnce
app/src/main/java/com/yishou/app/window/WindowActivity.kt    onResume 认领启动回执
app/src/main/java/com/yishou/app/window/WindowViewModel.kt   pullBack 按回执判断
app/src/main/java/com/yishou/app/link/ZongkongClient.kt      删除 status()，只留 open()
app/src/main/java/com/yishou/app/link/Partner.kt             新增哨兵包名/证书校验
app/src/main/java/com/yishou/app/ui/ConsoleScreen.kt         改走 ZongkongLink.fetchOnce
app/src/main/java/com/yishou/app/ui/PermissionsScreen.kt     增加「监管与健康」入口
```

新增文件：

```
app/src/main/java/com/yishou/app/healthwire/{HealthProvider,A11yChallenge,Facts,StatusCache,
  ZongkongLink,SentinelLink,LocalPolicy,PolicyStore,RuleBuilder,LockExecutor,LocalGuard,
  YishouGuard,EmergencyStop,LaunchTracker}.kt
app/src/main/java/com/yishou/app/ui/WatchHealthScreen.kt
app/src/main/res/xml/device_admin.xml
app/src/main/java/com/yishou/app/health/HealthCore.kt         （共享内核，仅改第一行 package）
app/src/test/java/com/yishou/app/health/HealthCoreTest.kt     （共享内核测试，原样）
app/src/test/java/com/yishou/app/healthwire/*.kt              66 条新用例
app/YISHOU-V2-NOTES.md                                        本文件
```

---

## 3. NOT_RUN（没有真机，一律不写 PASS）

`adb devices` 为空，本机无可用设备。以下项**只在代码与纯逻辑层面做了**，真机行为未验证：

| 编号 | 项目 | 为什么 NOT_RUN | 现场需要什么 |
| --- | --- | --- | --- |
| T02 | 哨兵 → 一手 `challenge` 真实往返、`handledAtMono` 落到 a11y 线程 | 无真机，无哨兵 v2 装机 | 装两 APK（一手 0.10.0 + 哨兵 v2），哨兵 `:watch` 打 log；`adb shell dumpsys accessibility` 对时间 |
| T04 | 哨兵不响应时一手事件通道照常拦截（不卡死） | 需真实 Binder 卡死场景 | 冻结哨兵进程（`adb shell kill -STOP <pid>`）后反复切前台应用，看思考页仍弹出 |
| T05 | 一手 a11y 冻结时哨兵仍能锁屏 | 跨两个应用 + 设备管理员 | 同上 `SIGSTOP` 一手进程，看哨兵是否在 30s 内锁屏 |
| T07 | 四应用同时不重复锁（真实并发） | 多进程计时无法在 JVM 复现 | 三客户端 + 哨兵同装，一次故障同时发生，数 `reportReceipt` 条数 |
| T09 | 设备管理员 `lockNow()` 真锁上（含无安全锁屏时的「只是休眠」） | 需真实 DevicePolicyManager | 激活一手设备管理员；分别在设/不设 PIN 的手机上各试一次 |
| T10 | 锁屏观测（`isDeviceLocked` / `isKeyguardLocked`）真值 | 同上 | 锁屏后 `adb shell dumpsys window | grep -i keyguard` |
| T14 | 旧实例 / 重复序号 / 过期回应在真机被拒 | 需哨兵侧真实 `AckValidator` 跑起来 | 连点探测、切换无障碍开关制造旧实例 |
| T18 | 后台启动被系统拦下时通知兜底真的发出 | 厂商 ROM 权限差异大（vivo 需「后台弹出界面」） | 关掉后台弹出权限后切前台应用，看是否收到通知 |
| — | 紧急暂停在真机一次点击即停 | 需真机 + 生效中的规则 | 造一条当前生效规则，点暂停，观察是否还锁 |
| — | 「暂停未确认」文案与实际哨兵回执一致 | 需哨兵 v2 装机 | 断网后点暂停，看文案是否显示「未确认」 |
| — | 24h 运行、耗电、休眠唤醒后重建观测 | 无设备 | 一整晚保持供电，第二天看 `RunLog` 与健康快照 |
| — | 无障碍服务真实 `instanceId` 换新 | 需反复开关无障碍 | 系统设置里关开一次，对比 `Facts.instanceId` |

另外：`HealthConfig` 里的周期（10s 探测 / 5s 预算 / 30s 确认 / 30s×2 恢复 / 120s 宽限）
**是工程初值，不是实测值**，未在真机上校准。

---

## 4. 已知边界（如实记录，不宣称能力）

1. **调试签名不是抗伪造方案。** `Partner` 里的证书指纹只挡「别的应用冒充哨兵/总控」，挡不住有 root 的人。
2. **整包被系统强制停止后，一手无法自我恢复**；本应用不承诺任何「无法绕过」。
3. **一手锁屏能力取决于用户是否激活设备管理员**；没有设备管理员、也没有安全锁屏时，`LocalGuard`
   只发通知（`notifyOnly=true`），不假装锁了。
4. **本地兜底是「有限」的**：只在哨兵失联、自己健康、规则生效且仲裁器通过时动一次；不做无限重锁。
5. **一手只停自己**。紧急暂停不会替其他应用担保；界面文案明确写「以哨兵回执为准」。
6. `ZongkongLink` / `SentinelLink` 的「无法取消的阻塞调用」放在独立线程里等，超时后主路径立刻走失联分支；
   该线程自己跑完即结束，不复用去等第二次。在途刷新各限 1 个。
7. **紧急暂停跨窗口的语义（已裁决的设计决定，非遗留问题）**：一手把「暂停」按
   「到用户点恢复为止」理解（独立标记文件），不随窗口翻页自然失效。哨兵侧
   `Emergency.Record.activeFor()` 是「本轮窗口结束即失效」。captain 2026-10-25 裁决
   **保留一手的保守写法**：宁可少锁、不可多锁，跨窗口自动恢复成「会锁」比不锁更危险，
   且报告 §8 未要求暂停随窗口结束失效。界面义务已在 §2.5c 落实（顶部显眼横幅
   「⚠ 紧急暂停生效中，本轮自动锁屏已停止」+ 时间/原因/来源 + 恢复按钮）。详见 §2.5c。

---

## 5. 复现命令

```powershell
cd C:\zkwork\yishou-wt\yishou
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:GRADLE_USER_HOME = "C:\guh"
$env:ANDROID_HOME = "C:\Android\sdk"

.\gradlew.bat :app:assembleDebug --offline
.\gradlew.bat :app:testDebugUnitTest --offline

# 看结果
Get-ChildItem app\build\test-results\testDebugUnitTest\*.xml | ForEach-Object {
  [xml]$x = Get-Content $_.FullName
  "$($_.Name): tests=$($x.testsuite.tests) failures=$($x.testsuite.failures) errors=$($x.testsuite.errors)"
}
```
