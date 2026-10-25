# 哨兵 v2 实施说明（SENTINEL-V2-NOTES）

分支：`deepseek/sentinel-v2`　基线提交：`ba8750e`　工作树：`C:\zkwork\sentinel-wt`
改动范围：**只有 `zongkong/sentinel/`**（未触碰 `zongkong/app`、`yishou`、`companion-pet`）。

---

## 0. 一句话结论

哨兵从「只会看总控心跳的单个无障碍服务」变成**两个进程**：

- 默认进程 `SentinelService`：只做窗口观察、引导拦截、事件进度落盘；
- `:watch` 进程 `WatchService` + `WatchProvider`：健康调度（10s/5s/30s）、策略持久化、
  执行仲裁、**设备管理员 `lockNow()` 锁屏 + 观测回执**、统计落盘、紧急暂停。

调度**不在** `AccessibilityService` 的 Handler 上，所以无障碍主线程冻结时 `:watch` 仍能发现并尝试锁屏（T05）。

---

## 1. 构建与测试结果（本机实测，2026-10-25）

环境：JDK 21.0.12.101、`C:\zkwork\sentinel-wt`（ASCII）、`GRADLE_USER_HOME=C:\guh`、`--offline`。

```
.\gradlew.bat :sentinel:testDebugUnitTest --offline   → BUILD SUCCESSFUL
.\gradlew.bat :sentinel:assembleDebug     --offline   → BUILD SUCCESSFUL
```

| 测试类 | 用例 | 失败 |
| --- | --- | --- |
| `com.zongkong.sentinel.DecideTest`（原有） | 5 | 0 |
| `com.zongkong.sentinel.ReleaseTest`（原有） | 6 | 0 |
| `com.zongkong.sentinel.health.HealthCoreTest`（**共享内核**） | 36 | 0 |
| `com.zongkong.sentinel.watch.SentinelWatchV2Test` | 39 | 0 |
| `com.zongkong.sentinel.watch.TrustedCertTest`（新增） | 6 | 0 |
| **合计** | **92** | **0** |

> SentinelWatchV2Test 从 31 → 39：F-01 修复时补了 8 条**接线级**用例（见 §3.4 与 §8）。

产物：

| 项 | 值 |
| --- | --- |
| APK | `sentinel/build/outputs/apk/debug/sentinel-debug.apk`（约 970 KB） |
| package | `com.zongkong.sentinel` |
| versionCode / versionName | **3 / `1.2.0-health-v2`** |
| 签名证书 SHA-256（apksigner 实测） | `b742dcd196af01fa96428498f5297d3165f66ab8c8ff6b85dd09185dbc318ac9` |
| 协议版本 | 2 |

签名配置**未改**（仍用 `../app/debug.keystore`，与总控同一把），未生成新密钥、未清用户数据。

> **调试签名不是抗伪造方案。** `WatchProvider` 的证书校验只用于「同一个应用边界内的一致性」，
> 不能防御有 root / 能改包的人。

---

## 2. 改了哪些文件

### 新增

| 文件 | 作用 |
| --- | --- |
| `watch/WatchService.kt` | `:watch` 前台服务（`specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE`）。主循环 10s 推进调度、执行到期锁屏、落盘、更新通知。 |
| `watch/WatchProvider.kt` | authority `com.zongkong.sentinel.health`，跑在 `:watch`。`register` / `syncPolicy` / `reportReceipt` / `ping` + 调用方鉴权（UID→packages、证书 SHA-256 白名单）。 |
| `watch/PolicyStore.kt` | `policies.json` 原子写（`.tmp` + fsync + rename）、`schemaVersion=2`、文档级 `revision`、`WatchClock`（`ClampedClock` 接线）。 |
| `watch/HealthStore.kt` | **每个客户端独立记录**（instanceId / 最近真 ack / 连续失败 / 恢复预算 / 事件进度 / 统计），`health.json` 原子写，最多 1 个在途。 |
| `watch/HealthScheduler.kt` | 每 tick 对三个客户端独立评测、独立超时、独立在途；探测派发**非阻塞**；失联只在「亮屏 + 有生效规则」时才升级为锁屏。 |
| `watch/ClientProbe.kt` | 向三个客户端 provider 发 `challenge`（显式 authority），**每客户端独立线程**（上限 2 条，卡死不无限新建）。 |
| `watch/ClientCall.kt` | `emergencyStop` / `ping` 一次性调用，各自独立超时。 |
| `watch/LockExecutor.kt` | `DevicePolicyManager.lockNow()` + `KeyguardManager`/`PowerManager` 观测 → `LockClassifier` 判 `OBSERVED_LOCKED / UNVERIFIED / FAILED`。 |
| `watch/Emergency.kt` | 紧急暂停记录：本地立即、无门槛、无网络/模型；**只写已确认的客户端**，其余显示「暂停未确认」。 |
| `watch/EventProgress.kt` | a11y 进程写 / `:watch` 读的事件进度文件（原子写）+ `EventChannel` 判定（已验证 / 待验证 / 异常）。 |
| `watch/StatusPage.kt` | 统计页口径：三客户端三行 + 哨兵一行、首页四态、统计「数据缺失」不入分母。 |
| `watch/MiniJson.kt` | 极小的纯 Kotlin JSON 读写（`org.json` 在 JVM 单测里是空桩，不可用）。 |
| `watch/TrustedCerts.kt` | 可信证书清单（纯 Kotlin，便于单测守 64 位 hex 格式）。 |
| `Arc.kt` | `:watch` 自己的日志文件（不与默认进程抢 `log.txt`）、**串行**恢复请求、客户端无障碍组件表。 |
| `health/HealthCore.kt` + `test/.../health/HealthCoreTest.kt` | 共享内核原样放入 `com.zongkong.sentinel.health`（只改 package 行，逻辑未动）。 |
| `test/.../watch/SentinelWatchV2Test.kt` | v2 纯逻辑单测 31 个。 |
| `test/.../watch/TrustedCertTest.kt` | 证书常量格式守卫 6 个。 |

### 修改

| 文件 | 改了什么 |
| --- | --- |
| `src/main/AndroidManifest.xml` | 加 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`；加 `:watch` 的 `WatchProvider`（exported）与 `WatchService`（`foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）；`<queries>` 补三个客户端包名。 |
| `src/main/res/xml/device_admin.xml` | 补 `<uses-policies><force-lock /></uses-policies>`（原来空的）。**不**声明 wipe-data / reset-password。 |
| `build.gradle.kts` | `versionCode 2→3`、`versionName 1.1.0→1.2.0-health-v2`。签名配置未动。 |
| `SentinelService.kt` | **移掉 30s 循环计时器与 `Sys.askMain` 探问**（那正是 T02 的假在线来源）。改为：窗口事件写事件进度文件、连接/销毁时拉起 `:watch`、`instanceId` 每次重建换新。保留 v1 的「掉线就拦名单」拦截行为。 |
| `MainActivity.kt` | 首页四态 + 三客户端三行 + 哨兵一行（真实字段）+ 紧急暂停 / 取消按钮 + 锁屏回执 / 统计 / 事件进度 / 策略文件信息 + 正常解除入口（冷静期那条路照旧）。 |
| `DeviceLock.kt` | 加 `ENABLED = false`：**防卸载本轮不启用**，`sync()` / `lockUninstall()` 直接返回。锁屏改由 `watch/LockExecutor` 负责。 |
| `Store.kt` | `Sys.wakeMain()` 改为**返回 Unit**（只拉活总控进程，不再把 `wake` 回来的 `running` 布尔交给调用方）；旧布尔读取降级为 `@Deprecated` 私有函数，无人调用。 |
| `Release.kt` | **未改**（冷静期 + 二次确认保留给「正常提前结束监管」，与紧急暂停分离）。 |

---

## 3. 关键实现点（对应规范的硬要求）

### 3.1 只有真实 dispatcherAck 才算健康（T02）

- 删除哨兵里所有「读 `GuardService.running` 布尔」的路径（`Sys.askMain` 不再被调用）。
- `ClientProbe` 只认完整 ack：缺 `handledAtMono`（客户端在执行线程上处理完的时刻）直接判「代答」失败。
- 回包一律过 `AckValidator`（nonce / seq / instanceId / 协议版本 / 往返超时）。
- `HealthEvaluator` 是唯一判官：从没 ack 过 = `UNKNOWN`，绝不 `HEALTHY`。

### 3.2 一个客户端不返回不阻塞其他（T04）

- 每客户端**独立线程**（`ClientProbe.Worker`），上限 2 条；到上限直接判失败，不无限新建。
- 每客户端**最多 1 个在途**（`HealthStore.beginProbe` 返回 0 = 本轮不发）。
- 每个客户端**独立超时**（5s 预算 + 0.5s 余量）与**独立记录**。
- 单测 `one wedged client never blocks the other two` 用假时钟跑 8 轮：总控永久不回，另一手/同桌持续 `HEALTHY`
  且从不进入锁屏候选。

### 3.3 调度不在无障碍 Handler 上（T05）

- `WatchService` 用自己的 `Thread("sentinel-watch")` + `Thread.sleep` 推进，不 import `Handler`/`Looper`。
- 单测 `scheduler needs no Looper and runs on a plain thread` 在纯 JVM（无 Android Looper）里跑完整一轮。

### 3.4 事件通道失灵单独检测（T03 / T13）

- a11y 进程每条可比对窗口切换写 `events.json`（seq + 单调时刻 + 最近 16 条），`:watch` 读。
- `EventChannel.judge` 只有三种结论；**息屏 / 锁屏 / 暂停 / 刚唤醒 / 哨兵自己也没事件 / 执行线程本身不通** → 一律「待验证」，
  不判异常（不因「没有事件」就误判故障）。
- **判定只有一处接线点**：`EventProgress.kt:240` `EventChannelWiring.refresh()`，
  由 `WatchService.kt:166` 每轮调用（仅此一处），内部走 `EventProgress.kt:176` `EventChannel.evaluate()`
  把 events.json 的真实事实翻译成判定输入。`HealthScheduler` **不算**事件通道（F-01 修复，详见 §8）。

### 3.5 锁屏回执（T09 / T10 / T15）

- `LockExecutor` 区分：管理员未激活 → `FAILED`（写明无权限）；调用返回但没观测到 → `UNVERIFIED`；
  没设安全锁屏 → `UNVERIFIED`（可能只是休眠）；只有 `isDeviceLocked && isKeyguardLocked && isDeviceSecure` 才 `OBSERVED_LOCKED`。
- 同一 `incidentId` 由 `LockArbiter` 去重 + 冷却 + 每事件次数上限（T07）。
- 观察到锁上后设置 120s 修复宽限，宽限内不再重锁（T15）。

### 3.6 紧急暂停与正常解除分离（T16）

| | 正常解除 `Release.kt` | 紧急暂停 `watch/Emergency.kt` |
| --- | --- | --- |
| 门槛 | 手写 100 字 + 冷静期 2h + 二次手写 40 字 | 一键，无门槛 |
| 依赖 | 无网络（本地判题） | 无网络、**无模型** |
| 生效 | 核验通过后 30 分钟 | **立即**，到窗口结束或手动取消 |
| 记录 | 分析文字 | 原因 + 使用时间 + **各客户端确认情况** |

`Emergency.describe` 只写「已确认停止：…」+「暂停未确认：…」，不会谎称四个都已停止。
`Release.kt` 一字未改。

### 3.7 IPC 鉴权（报告 §9）

`WatchProvider.Auth.check` 顺序：`UID → getPackagesForUid` 必须含自报 `pkg` → **实际证书** SHA-256 必须命中
`TrustedCerts.MAP` 且对该包名 → 消息体自报的 `certSha256` 只作对照，**以实际读到的为准**。
不采信消息体自报身份；签名不符时保留鉴权（宁可接不通）。

---

## 4. 验收矩阵状态（T01–T20）

本机 `adb devices` 为空，**无真机**。下表严格区分「代码 + 单测证据」与「必须真机 → NOT_RUN」。

| 编号 | 状态 | 证据 / 缺什么 |
| --- | --- | --- |
| T01 无障碍关闭 | **部分** | 代码：`HealthEvaluator` 优先判 `DISABLED`；`:watch` 每 tick 从系统设置读开关。单测覆盖权限优先级。真机切开关观察 → **NOT_RUN** |
| **T02 假在线** | **代码+单测通过** | 已删除 `Sys.askMain`/`running` 路径；`ClientProbe` 拒绝无 `handledAtMono` 的代答；`AckValidator` 校验。单测 `no ack yet is never healthy`、`real ack makes it healthy…`。真机冻结线程注入 → **NOT_RUN** |
| T03 事件通道 | **代码+JVM 接线级通过（真机 NOT_RUN）** | a11y 写 `events.json`、`:watch` 读；判定唯一接线点 `EventChannelWiring.refresh`。**8 条接线级单测**直接跑真文件 + 真落盘，断言 ABNORMAL 能真实产出、且 `scheduler.tick()` 不覆盖它（F-01 回归）。真机「心跳能回但事件停投」→ **NOT_RUN** |
| **T04 一个客户端不返回** | **代码+单测通过** | 独立线程/独立超时/在途上限 1。单测 `one wedged client never blocks the other two`（8 轮假时钟）。真机用一个永不返回的 provider → **NOT_RUN** |
| **T05 无障碍线程冻结** | **代码+单测通过（结构性）** | 调度全在 `:watch` 自己的线程，不依赖 Looper。单测 `scheduler needs no Looper…`。真机冻结 a11y 线程 + 观察管理员锁屏 → **NOT_RUN**（核心门槛，必须有真机才算 PASS） |
| T06 整包被强停 | **文档+代码** | 界面与注释明确写「无法自我保证恢复」；不做互拉看门狗。真机强停 → **NOT_RUN** |
| T07 锁屏风暴 | **代码+单测通过** | `LockArbiter` 去重/冷却/上限（共享内核单测覆盖）。三 APP 同时触发需真机 → **NOT_RUN** |
| T08 到期锁屏 | **代码** | `PolicyStore.lockDue` + `WatchService.dueLocks`。真机按时锁 → **NOT_RUN** |
| T09 无管理员/无安全锁屏 | **代码+单测通过** | `LockExecutor` 明确区分未激活 / 只休眠 / 已安全锁定；`LockClassifier` 单测覆盖。真机 → **NOT_RUN** |
| T10 调用后无法确认 | **代码+单测通过** | 观测不到即 `UNVERIFIED`，永不记成功。真机 → **NOT_RUN** |
| T11 重绑没重连 | **代码** | 只记「恢复请求已发出」；`HealthEvaluator` 要求「新实例 + ack 晚于恢复起点」才转 HEALTHY。单测覆盖。真机 → **NOT_RUN** |
| T12 两个恢复方同时改 | **代码（部分）** | `Arc.requestRebind` 用锁串行 + `Sys.rebind` 写回前重读列表。跨应用（总控 vs 哨兵）串行需与 zk 侧对齐 → **NOT_RUN** |
| T13 静止/息屏/休眠 | **代码+单测通过** | `EventChannel.Context.suppressed` 与 `activeEnforcement` 双重抑制；`activeEnforcementNow` 检查息屏与修复宽限。真机休眠唤醒 → **NOT_RUN** |
| T14 改时间/重启/迟到回执 | **代码+单测通过** | `ClampedClock` + `WatchClock.reanchorFrom(updatedAtWall)`；`AckValidator` 拒旧实例/重复序号/过期。单测多条覆盖。真机改钟 → **NOT_RUN** |
| T15 解锁后修复宽限 | **代码** | `repairGraceUntilMono = now + 120s`，`LockArbiter.decide(…, repairGraceUntilMono)` 抑制重锁。真机 → **NOT_RUN** |
| T16 紧急暂停/断网/模型故障 | **代码+单测通过** | 紧急暂停纯本地、无网络无模型；逐个通知、独立超时；只写已确认项。单测 3 条。真机断网 → **NOT_RUN** |
| T17 单 APP 未装/旧协议 | **代码+单测通过** | `UNREGISTERED` → 「未接入」；协议版本不符注册被拒。四态里 `PARTIAL_FAILURE`。真机 → **NOT_RUN** |
| T18 startActivity 不算成功 | **不在本轮范围（哨兵）** | 哨兵侧无「启动任务界面」逻辑；属于一手 `launchGate` 的 T18。→ **NOT_RUN（不在本模块）** |
| T19 规则自然结束 | **代码** | `Policy.effective()` 过期即停；不虚构任务完成。真机 → **NOT_RUN** |
| T20 24h 连续运行 | **NOT_RUN** | 无真机，无法测耗电/长稳。需现场：装四 APK、开发时段窗口、跑 ≥24h。 |

**没有真机就一律 NOT_RUN，不写 PASS。** T02 / T04 / T05 是本轮核心门槛，目前只有「代码 + JVM 故障注入」级别证据，
**真机注入仍待现场完成**。

---

## 5. 必须现场完成的步骤（缺真机）

1. 装四个 APK（总控 / 一手 / 同桌 / 哨兵 v2）。
2. 手动激活**设备管理员**（设置 → 安全 → 设备管理员 → 总控哨兵），并确认**已设置安全锁屏**，否则锁屏只会记 `UNVERIFIED`。
3. （可选）`adb shell pm grant com.zongkong.sentinel android.permission.WRITE_SECURE_SETTINGS`
   与总控同权限，哨兵才能串行发「重绑无障碍」请求。
4. 逐项跑 T01–T20 注入，记录机型 / Android 版本 / APK 版本 / 时间戳 / 日志。

---

## 6. 已知限制与不做的事

- **企图中不改锁屏密码、不启用防卸载、不隐藏运行、不做无限重锁**（`DeviceLock.ENABLED = false`）。
  不宣称普通 APK 无法绕过。
- **整包被系统「强行停止」后，哨兵无法自我保证恢复**（T06）。界面已如实写。
- **不加层层互相拉起的无限看门狗**；`START_STICKY` 是系统级重试，不是自建拉活链。
- 前台服务类型如实声明 `specialUse`，不用虚假音频/定位保活。
- 本轮**不要求 Device Owner**。
- 单个客户端卡死时最多 2 条探测线程；到上限直接判失败——**宁可报失联，也不无限新建线程**。
- 共享内核逻辑**未改**（只在复制时改 `package` 行）。

---

## 7. 待确认项（请 verifier / 后续轮次接手）

- ⚠️ **`cn.companion.pet` 的证书摘要待验证**：当前值
  `9F3A524C66974AEB04045F498F83B5970589F9FBA3AC3772E95C26E3524695E0`
  来自仓库提交的 `keystore/tongzhuo-debug.jks`（`keytool -list -v` 读出），
  **尚未用 apksigner 对构建出的同桌 APK 实测确认**。同桌侧构建出 APK 后请执行：
  `apksigner verify --print-certs <pet-debug.apk>`，若与上值不一致需同步改 `watch/TrustedCerts.kt`。
  （`com.zongkong.app` 与 `com.yishou.app` 两条已用 apksigner 实测核对过。）
- 紧急暂停向客户端传播用的是 `emergencyStop` method；三客户端 provider 侧需实现同名 method 才能回「确认」。
  未实现时哨兵会如实显示「暂停未确认」（不会谎称已停止）。
- 自动重绑（`WRITE_SECURE_SETTINGS`）的**跨应用串行**只做了一半：哨兵侧串行，与总控侧仍需协调者约定（T12）。

---

## 8. F-01 修复记录：事件通道（T03）被覆盖成「待验证」

**发现者**：verifier 独立验收；captain 逐行复现确认。**等级**：blocker（仅 T03，不影响 T02/T04/T05）。

### 8.1 缺陷是什么

`:watch` 每轮 `while` 把事件通道结论**算了两遍**，坏的那遍在后面：

| 顺序 | 位置 | 质量 |
| --- | --- | --- |
| 先 | `WatchService.refreshSystemFacts()` | **对**：真读 `events.json`，`compareRefMono = progress.atMono`，`suppressed = !interactive \|\| !activeEnforcementNow()` |
| 后 | `HealthScheduler.tick()` | **错**：`compareRefMono = 0` 硬编码 → `judge()` 里 `compareRefMono <= 0` 无条件命中 → 永远 `PENDING`；`sentinelSawEventAfter` 拿**上次结论**当输入（自我引用） |

后者在同一次迭代中后执行，把正确结论覆盖成「待验证」，落盘永远是 `待验证`。
**后果**：真实链路上 T03 永远得不出「异常」，统计页在说谎。

**为什么单测没抓到**：旧用例直接调 `EventChannel.judge()`，绕开了 `tick()`/`refreshSystemFacts()` 的真实接线。
判据对、接线错，测试仍然全绿。

### 8.2 怎么改的（方案 A：收敛到唯一一处）

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| **删除**事件通道计算 | `HealthScheduler.kt`（原 122–135 行） | 调度器只剩两个职责：健康状态、锁屏事件。原位置留注释说明为什么不算。 |
| **新增唯一入口** `EventChannel.evaluate()` | `EventProgress.kt:176` | 把 `EventProgressFile.State` 的**真实文件事实**翻译成判定输入，无任何自我引用 |
| **新增唯一接线点** `EventChannelWiring.refresh()` | `EventProgress.kt:240` | 遍历三客户端 → `evaluate` → `store.setEventChannel`；`WatchService.kt:166` 是**全仓唯一**调用方 |
| 自我引用修掉 | 同上 | `sentinelSawEventAfter = progress.seq > 0 && progress.atMono > 0`（文件事实），不再是「上次结论」 |

**收敛后的唯一计算位置**：
- 判定逻辑：`zongkong/sentinel/src/main/java/com/zongkong/sentinel/watch/EventProgress.kt:176`（`EventChannel.evaluate`）
- 唯一接线点：`zongkong/sentinel/src/main/java/com/zongkong/sentinel/watch/EventProgress.kt:240`（`EventChannelWiring.refresh`）
- 唯一调用方：`zongkong/sentinel/src/main/java/com/zongkong/sentinel/watch/WatchService.kt:166`

已核对全仓：`EventChannel.judge` 不再被生产代码直接调用，`compareRefMono = 0` 已彻底消失，
`store.setEventChannel` 只剩 `EventChannelWiring.refresh` 一处。

### 8.3 补的接线级测试（8 条，全部走真文件 + 真落盘）

| 测试名 | 断言什么 |
| --- | --- |
| **`wiring produces ABNORMAL when client event progress stalls past grace`** | 真写 `events.json`（哨兵在 mono=1000 收到事件）+ 客户端事件进度停在 500 + 已过 grace → 结论与**落盘的 eventChannel** 都是 ABNORMAL |
| **`scheduler tick does not overwrite the wiring verdict`** | **F-01 回归**：先 `refresh` 得到 ABNORMAL，再 `scheduler.tick()`，落盘必须仍是 ABNORMAL |
| `wiring reports VERIFIED when client event progress caught up` | 客户端进度追上参照点 → VERIFIED |
| `wiring stays PENDING while screen off even if progress stalls` | 息屏/锁屏/暂停时即便进度停滞也不判异常（T13） |
| `wiring stays PENDING when sentinel itself received no event` | 没有 `events.json` → 无法区分责任，不判异常 |
| `wiring stays PENDING while dispatcher is not confirmed` | 执行线程本身没 ack → 先不解判断事件通道 |
| `wiring ignores unregistered clients` | 未接入客户端不参与判定 |
| `evaluate does not self reference the previous verdict` | 输入只来自文件事实，与「上次结论」无关 |

**反向验证（关键）**：把旧的两处计算临时塞回 `HealthScheduler.tick()` 后重跑，
`scheduler tick does not overwrite the wiring verdict` **确实变红**（92 用例 1 失败）；
移除注入后恢复全绿。所以这条回归测试真的能抓住 F-01，不是装饰。

### 8.4 T03 现在的证据等级

| 层级 | 状态 |
| --- | --- |
| 代码路径 | ✅ 唯一一处计算，生产代码与测试走同一条接线 |
| JVM 接线级单测 | ✅ 8 条，含 ABNORMAL 真实产出 + 不覆盖的回归断言 |
| JVM 反向验证 | ✅ 注入旧 bug → 测试变红 |
| **真机（abnormal 事件通道实测）** | ❌ **NOT_RUN**（本机无设备） |

现场需要做的：真机上让某个客户端的无障碍事件投递停住（但 Binder/dispatcher 仍能回挑战），
确认统计页那一行出现「异常」而不是「待验证」，并记录机型/ROM/时间戳。

### 8.5 这次教训

**判据本身对，不代表接线上是对的。** 单测如果只测纯函数、不测「谁调用它、调用顺序如何」，
就会对「同一次迭代里被后写的错误结论覆盖」这类缺陷完全失明。
今后凡是「多处写同一个字段」的地方，要么收敛成唯一写入点，要么补一条覆盖真实调用顺序的测试。

---

## 提交记录（captain，2026-10-25 23:2x）

本工作树已提交入库（仅本地，未 push）：

| 分支 | 提交 | 基线 |
| --- | --- | --- |
| `deepseek/sentinel-v2` | `ea4560b` | `ba8750e` |

提交后强制重跑 `--rerun-tasks`：**92 tests / 0 failures / 0 errors**，与提交前一致。
真机项仍全部 NOT_RUN（本机 `adb devices` 为空）。
