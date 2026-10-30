# 总控 · 架构审计 v2（基线 349e181f）

审计日期：2026-10-30　基线：`349e181f`（`zongkong-latest` 发布构建）
范围：`zongkong/app`、`zongkong/sentinel`、`yishou`

---

## 0. 分支现状（先纠正一个前提）

| 分支 | 最新提交 | 日期 | 与基线关系 |
|---|---|---|---|
| `deepseek/zk-client-v2` | `e3fbbe8c` | 10-25 | 基线**领先 10 个提交**，无独有提交 |
| `deepseek/sentinel-v2` | `59117afe` | 10-25 | 基线**领先 13 个提交**，无独有提交 |
| `ccr-a5172662-j1jcik`（一手） | `158d25df` | 10-28 | 一手独立线，仍是活跃开发线 |

结论：两个 v2 分支在 `f4c7bdd6`（10-26「Merge zk-client-v2」）已并入，之后 `0febee9e`、`6054c21e` 继续修。**审计与后续开发一律以基线为准，不再回到分支上二次开发。**

---

## 1. 四象限总表

| # | 链路 | 判定 | 一句话理由 |
|---|---|---|---|
| 1 | v2 健康探测（dispatcherAck） | **已实现** | 挑战真投到 a11y 线程，ack 有硬判据，两端真落盘 |
| 2 | 事件通道失效判定 | **未接线** | 判据方向反了：静置必误报，真卡死必沉默 |
| 3 | 异常恢复（接管/回切） | **未验证** | 主干闭合，但两条"恢复后永久失联"路径仍成立 |
| 4 | 紧急暂停 / 解除闸门 | **未接线** | 哨兵侧有到期，客户端侧无到期；闸门挡得住拦截、挡不住锁屏 |
| 5 | 真实执行回执 | **未接线** | 生成诚实，但上游锁屏分支生产不可达，下游只做展示 |
| 6 | 一手训练结果回传 | **已实现（判定过粗）** | 链路通，但只传一个 `doneSets` 整数，无原始依据 |
| 7 | 防"单次随机即有效" | **有作弊面** | 三道闸全部悬空，存在完整可走路径 |
| 8 | 统一策略仲裁 | **未实现** | 一个中央 + 6 条旁路，优先级靠 if 顺序 |
| 9 | ActionSession 断点续接 | **未实现** | 只有 5 字段单槽 `Session`，断点靠用户手写 |
| 10 | 主动交互分级 | **未实现** | 9 条独立通道，无分级、无预算、静默覆盖不全 |
| 11 | 无障碍被系统标记故障 | **NOT_RUN** | 无真机，`exit-info`/`dropbox` 未取到，REASON 未知 |

---

## 2. P0（真实执行链路，先修）

### P0-1 本地兜底判据自相矛盾 → 整条执行链在生产上不可达

- `healthwire/LocalPolicy.kt:114-120` 要求 `selfHealthy && !sentinelHealthy` 才走本地兜底。
- `selfHealthy` 需要 ≤30s 内的真实 ack（`health/HealthCore.kt:120,191-197`）。
- `sentinelHealthy` 的缓存窗口是 60s（`guard/Sentinel.kt:430`）。
- 两者都由「哨兵活着」产生，且 **30s < 60s ⇒ `selfHealthy=true` 必然蕴含 `sentinelHealthy=true`**。
- 后果：`LocalGuardian.tick` → `GuardTickCore.tick` → `LocalLockExecutor.lock` → `LockReceipt` 整条链**永远不会执行**。第五节的回执"未接线"就是这个原因。
- 旁证：接线测试直接注入 `selfHealthy=true, sentinelHealthy=false`（`RepairGraceWiringTest.kt:53-54`）——这个组合在生产上不存在。

**判定：逻辑死锁。这是"真实执行链路"最大的洞——兜底是假的。**

### P0-2 事件通道判据方向反了

- `sentinel/watch/EventProgress.kt:186-201` 拿「哨兵最后一次事件时刻」当参照，但**不要求参照事件新鲜**。
- `:157-158`：只要有 20s 没比对过、且客户端时刻早于参照，就判 `ABNORMAL`。用户静置 20 秒即满足 → **静置必误报**。
- `:259` `dispatcherHealthy` 依赖 `lastAckAtMono`，而 `:148` 在 `!dispatcherHealthy` 时直接返回 `PENDING` → **a11y 真卡死时永远判不出来**。
- 两侧都在包名/overlay 过滤**之前**计数（`GuardService.kt:181` vs `:185`；`SentinelService.kt:76` vs `:77`），与 `EventProgress.kt:26-27` 注释「已剔除系统界面」不符。

### P0-3 恢复后永久失联（两条独立路径）

1. `sentinel/watch/ClientProbe.kt:40,100-114`：每客户端最多 2 条常驻线程，**卡住不回收**；两条都卡死后 `dispatch` 永久失败。
2. `healthwire/HealthLedger.kt:164` `resetRecoveries()` **全仓零调用** → 到达 ESCALATE 后，该进程生命周期内不再自愈。

### P0-4 紧急放行被总控时间弹回（违反"不得取消即时紧急暂停"）

- `guard/GuardService.kt:213-239` `lockdown()` 只看 `pausedUntil`（`:216`），**不看 `emergencyUntil`、不看 `status.strict`**。
- `enforce()`（`:268-290`）会尊重 `strict=false`（紧急放行时），但 `lockdown()` 在同一条链上先执行（`:202`）→ 买了紧急放行，打开被拦应用**照样被弹回总控时间**。
- 同一类问题：`guard/Watch.kt:151-157` 只用 `strict` 抑制答题页，紧急放行时答题页照样接管。

### P0-5 低信誉三出口死锁

- `core/Engine.kt:133-136`：`blocking` 关卡从开放一直拦到当天结束，无上限、无自动降级。
- `data/Actions.kt:229`：`stance == STRICT` 时**直接拒绝紧急放行**。
- `core/Policy.kt:269-274`：休假要 24 小时冷静期才生效。
- 三者叠加：**交不了、放不了、休不了**。用户唯一出路是卸载或关无障碍。

### P0-6 紧急暂停在客户端不过期 + 落盘失败伪装成"已确认"

- `healthwire/ClientPolicyStore.kt:57` `emergencyStopped()` 只看"有没有 STOPPED 记录"，**不看窗口、不看时长** → 客户端永久暂停，只有手动 resume 能解。
- `:82-94,124-137`：落盘失败只写 `lastError`，**仍返回新 revision**；`HealthWireProvider.kt:92-96` 照样回 `ok=true` → 哨兵记成 `confirmed`。**"落盘失败"被记成"客户端已确认停止"。**
- `sentinel/watch/WatchService.kt:325-333` `cancelEmergency` **不通知客户端**（`SENTINEL-V2-NOTES.md:348` 自认）→ 取消后客户端永久停用本地兜底。

### P0-7 一手「一次随机蒙对即可视为有效训练」

完整可走路径：

1. 入口思考页 `CardMode.GATE` 组大小强制为 1（`card/CardSession.kt:87`）；
2. 该模式读秒锁被显式关掉（`ui/CardRound.kt:278` `if (mode==GATE||bullseye) 0L`）；
3. "连快连错"也被显式排除（`CardSession.kt:224` `mode != GATE`）；
4. 四选一蒙对 → `firstCorrect=true, help=0` → `effective=true`（`:248`）；
5. `LinkProvider.kt:72-73` 统计**全部来源**的 effective，不分 source；
6. `training/Training.kt:30` `doneSets = effectiveToday / setSize`，而 `setSize` 用户可设 1（`settings/AppPrefs.kt:49,232`）；
7. 总控 `data/Actions.kt:119` 把 `doneSets` 直接当"证据条数"，`core/Verifier.kt:60` 只判 `n >= evidenceMin`（`act_train` 默认 2）。

→ **蒙对 2 次即自动打勾。** 另外 `AntiRandom.tooFast()` 全仓零调用，纯死代码。

附带两处口径错位：
- `targetSets` 白算：一手要求 8 组时，总控第 2 组就放行（`Link.hitToday` 只有测试在用）。
- `absorbYishou` 把昨晚的 `summaryDate` 写成今天的结论（`Actions.kt:163` + `WorkActions.kt:148`），给今天的 `think_one` 关卡自动打勾。

---

## 3. P1

| # | 缺口 | 证据 |
|---|---|---|
| P1-1 | 无 ActionSession：`work/Work.kt:183-191` 的 `Session` 只有 5 字段、全局单槽（`:206`），`start` 直接覆盖（`WorkOps.kt:79`）；断点靠用户手写 `F.BREAK`（`WorkOps.kt:91-108`） | 多步行动无法续接 |
| P1-2 | `left()` 只写一个 `leftAt` 时间戳（`WorkOps.kt:85`），且被 `GuardService.kt:190` 与 `MainActivity.kt:92-95` 双写无节流；只有 >3 分钟才提示，`session` 永不过期 | 状态失真 |
| P1-3 | 证据双粒度：交付按 `actionKey`（`WorkActions.kt:252-268`），关卡验收按"今天"+全局组数（`Focus.kt:95-113`） | 可跨行动冒领 |
| P1-4 | 6 条策略旁路（`GuardService.enforce/lockdown`、`Watch.ruleFor`、`Actions.emergency`、`Decide.shouldBlock`、`LocalFallback.decide`），无单一裁决入口 | 任何新策略要改 5+ 处 |
| P1-5 | 哨兵持第二份硬编码拦截名单 `Decide.kt:23-30`，与 `config.blocked` 可长期背离 | 同一 App 两套结论 |
| P1-6 | 双 UI 双通知：两个 `BlockActivity`、两条离线通知，去重仅进程内 1.5s（`GuardService.kt:281` / `SentinelService.kt:156`） | 重复打扰、状态自相矛盾 |
| P1-7 | 总控→一手 `call` 无超时无重试（`link/YishouClient.kt:54`），反向却有 5s 超时（`ZongkongLink.kt:40`） | 一手卡死会拖住自动验收 |
| P1-8 | 协议 `v` 字段只写不读（`Contract.kt:51` / `core/Link.kt:149-174`） | 版本差异不可检测 |
| P1-9 | 解除闸门 `released()` 只挡拦截（`GuardService.kt:196-200`），**不挡锁屏**（`WatchService.kt:241-278` 不读 `releaseGranted`） | 解除后仍被锁 |
| P1-10 | `Release.phase()` 只用墙钟（`Release.kt:143-151`），回拨系统时间可无限续期解除窗口 | 闸门可绕过 |

---

## 4. P2

| # | 缺口 | 证据 |
|---|---|---|
| P2-1 | 主动通道 9 条，无分级；唯一"级"是 `Buzz.Level`（`Buzz.kt:24`），只作用于震动 | 打扰不可控 |
| P2-2 | 静默覆盖不全：`alert/hqCall/advisor` 无 quiet 判断，`proactive` 的通知在 quiet 判断**之前**发（`Chat.kt:426` vs `:429`），`Pet.say` 同理 | 深夜必响 |
| P2-3 | `alerts` 的 OVERDUE/DUE_SOON 不判 `awake()`（`GuardService.kt:444,446`），`hqCall` 不判熄屏 | 睡眠中高优先级弹出 |
| P2-4 | `firstAlert` 按逻辑日单字符串存（`Store.kt:452-461`），key 无命名空间、无上限 | 跨模块去重不可推演 |
| P2-5 | `FORCE` 失联强制开场不判 `doneToday`/`endsAt`（`GuardService.kt:218-221`），可无限重进 | 无限兜圈 |
| P2-6 | `GateActivity` 判分失败无次数上限（`:180-184`） | 放大死锁 |
| P2-7 | `LocalFallback.forget()` 零调用（`LocalPolicy.kt:139`） | 兜底能力衰减 |
| P2-8 | `AutoBlock.apply` 直接改 `blocked`，绕过 `Policy.setBlocked`（`AutoBlock.kt:42-55`） | 规则账本无来源 |

---

## 5. 明确「已有、不要重做」的部分

避免重复开发，以下已经有生产调用链，**不要再写一套**：

- v2 挑战/ack 探测（`A11yChallenge` + `HealthLedger` + `HealthScheduler` + `ClientProbe`）——只需修 P0-2/P0-3，不要新建健康协议。
- 哨兵独立进程接管与回切（`SentinelService.watch` + `Decide` + `watch/WatchService`）——主干可用。
- 锁屏执行与观测分类（`LocalLockExecutor` + `LockClassifier`：`OBSERVED_LOCKED / API_CALLED / UNVERIFIED`）——生成侧诚实，别改判定口径。
- 解除闸门两关（`Release.kt`：手写 + 冷静期 + 二次核验）——只补双钟校验。
- 一手 `status/setTask/startWindow` 三个 method 与双向证书鉴权——别重做接口。
- Compose 界面骨架、`Store` 的原子写（`writeAtomic`）、`Diag` 日志——别动。

---

## 6. 修复顺序与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| S1 | P0-1、P0-2、P0-3 | 单元测试覆盖「selfHealthy 组合可达」「静置不误报/卡死必报」「线程回收与恢复预算复位」 |
| S2 | P0-4、P0-5、P0-6 | 紧急放行下 `lockdown` 必须 return false；三出口至少留一条；紧急暂停到期自动解除 |
| S3 | P0-7 | 一手侧有效训练必须带重复性判据；总控侧不得只信 `doneSets` |
| S4 | P1-1、P1-4 | ActionSession 持久化 + 单一裁决入口 |
| S5 | P1-5..P1-10、P2 | 按需 |

**真机验收项（当前 NOT_RUN）**：
- 无障碍掉线的 `REASON`（需 `adb shell dumpsys activity exit-info com.zongkong.app`）
- 后台 `startActivity` 拉起 `BlockActivity` 是否真的可见（Android 10+ 限制）
- 哨兵接管时 `BlockActivity` 与总控 `BlockActivity` 是否互相打架
- 解除闸门生效时锁屏是否真的不执行

单元测试通过**不替代**上述任何一项。

---

## 7. 本轮（S1/S2 部分）修复记录

分支：`fix/p0-execution-path`（基于 `349e181f`）

| 编号 | 改动 | 文件 | 为什么这么改 |
|---|---|---|---|
| P0-1 | 新增本地自检，让「自己健康」不再依赖哨兵发起挑战 | `healthwire/LocalSelfCheck.kt`（新增）、`healthwire/GuardHealth.kt`、`healthwire/LocalGuardian.kt` | 原判据只看 dispatcherAck，哨兵沉默 → 必然 false；叠加 `sentinelHealthy` 60s 缓存 > ack 30s 窗口，`selfHealthy=true ⇒ sentinelHealthy=true`，兜底链生产不可达。自检测的是同一条线程，但发起方是自己 |
| P0-2 | 事件通道加时间抖动容差 + 参照事件可信年龄 | `sentinel/.../watch/EventProgress.kt` | 两个无障碍服务分别派发同一次切换，两端打点天然差几百毫秒；旧判据把这个当「落后」，静置 20s 必误报。另加 120s 上限，长期静置不再判异常 |
| P0-3 | 新实例连上时重置恢复预算 | `healthwire/HealthLedger.kt` | `resetRecoveries()` 全仓零调用，到上限后进程余生不再自愈 —— 真机表现「关开一次好一会儿，之后永久失联」 |
| P0-4 | 总控时间对紧急放行让路 | `guard/GuardService.kt` | `lockdown()` 只判 `pausedUntil`，漏了 `emergencyUntil`，紧急放行期间仍被弹回总控时间 = 取消了即时紧急暂停 |

### 测试

- `app:testDebugUnitTest`：**314 passed / 0 failed**
- `sentinel:testDebugUnitTest`：**104 passed / 0 failed**
- 新增用例：
  - `LocalSelfCheckTest`（11 条）：投递口缺失 / 投递被拒 / 线程跑完 / **投递成功但线程不跑（超时）** / 缓存命中与过期 / fresh 语义 / 失败覆盖成功 / 不认任何外部健康断言
  - `RecoveryBudgetResetTest`（4 条）：新实例重置预算 / 服务没起来不重置（防无限重启）/ 断开重连也重置 / 重置不影响「恢复中」
  - `EventChannelSkewTest`（8 条）：毫秒抖动不误报 / 容差边界 / 超容差才算落后 / 落后数分钟必报 / 静置超上限不判 / 观察窗口不抢跑 / 容差可注入
- 修正既有测试 2 条：`SentinelWatchV2Test` 里用 **500ms** 的差模拟「卡死」，与派发抖动同量级，测不出判据真正的边界 → 参照点改为 10000，落后 9.5s。

### 构建环境（本机踩到的坑，供复现）

1. `GRADLE_USER_HOME` 若在含中文的用户目录下，Gradle 的 test worker 会 `ClassNotFoundException: GradleWorkerMain`（classpath 里的 jar 路径被错误解码）。必须指到纯 ASCII 路径。
2. 工程的 `local.properties` 需要 `sdk.dir`，中文路径要转义。
3. 可用的跑法：

```bash
cd <ascii-path>/zongkong
env -i PATH="<jdk>/bin:/usr/bin:/bin" JAVA_HOME="<jdk>" SYSTEMROOT="C:\\Windows" \
    TEMP="D:\\Temp" TMP="D:\\Temp" ANDROID_HOME="D:\\code\\android-sdk" \
    GRADLE_USER_HOME="D:\\Temp\\gradlehome" <ascii-path>/gradle-8.9/bin/gradle \
    :app:testDebugUnitTest :sentinel:testDebugUnitTest
```

### 仍未做（本轮明确未覆盖）

| 项 | 状态 |
|---|---|
| P0-5 低信誉三出口死锁 | **未修**，涉及产品决策（紧急放行在 STRICT 下被拒 vs 关卡无上限） |
| P0-6 客户端紧急暂停不过期 / 落盘失败伪装成已确认 | **未修** |
| P0-7 一手「一次蒙对即有效」 | **未修**，需改一手侧判定 + 总控侧证据口径 |
| P0-3 第二条：`ClientProbe` 每客户端 2 线程卡死不可回收 | **未修**（哨兵侧线程池策略） |
| 真机验证全部项 | **NOT_RUN**（无设备） |

