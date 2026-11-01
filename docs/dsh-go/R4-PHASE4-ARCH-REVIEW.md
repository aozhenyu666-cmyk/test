# R4 第四阶段 · 架构审查与修复设计

> 仓库 `aozhenyu666-cmyk/dsh-go` · 分支 `integration/r4-logic-closure-2026-11-01`
> 审查日期 2026-11-01 · 本文只做审查与设计，**未改任何代码、未构建 APK**。
> 读者：第四阶段执行方（DeepSeek Harness）与项目负责人。第 9 节的策略项必须由负责人拍板后再动手。

---

## 0. 审查基线与结论

### 0.1 HEAD 三处不一致（先对齐再动手）

| 来源 | SHA |
|---|---|
| 任务书给出的 HEAD | `5f28c2d`（报告首版提交） |
| 报告 `R4-LOGIC-CLOSURE-REPORT.md` 第 4 行写的 HEAD | `8810464` |
| 远端分支实际 HEAD（`git ls-remote`，审查时） | **`868dc33`** |

`5f28c2d → 868dc33` 之间共 5 个提交：`ea932c9`（L2sMigrationTest 改用 `readAllBytes`，`THIRD_PARTY_NOTICES`）、`8810464`、`b3ec9a0`、`346d9de`、`868dc33`（后四个只改文档）。
**产品代码在 `5f28c2d` 与 `868dc33` 之间没有差异**，所以本审查对两者都成立。审查期间分支被推进了两次，说明有并行写入，第四阶段开工前必须冻结分支。

### 0.2 一句话结论

第三阶段的 191 个内核测试大多测的是"守卫内部的单步裁决"，而真实缺陷都出在**适配层生命周期**和**两个文件之间**。按本轮要求的五条硬约束逐条核对，有 **5 项 P0**：

1. 拦截事件可以**永久悬挂**，悬挂期间**所有目标 APP 都被放行**，直到进程重启。
2. 拦截结束后**不再重新判定当前前台**，用户经"最近任务"切回目标 APP 可以绕过。
3. 页面没展示出来（NOT_SHOWN）**仍然扣冷却**，系统拒绝启动后目标 APP 被放行 2 分钟。
4. guard.json / ledger.json **跨文件写入**，崩溃后会丢白名单、出现"NOT_SHOWN 但 blockedCount 已加"、出现没有回执的 triggerCount。
5. **未展示的页面也能放行**（白名单/规则/暂停/退出/训练题都不检查 `openShownAt`），`openShownAt == 0` 时训练停留时间检查被**跳过**。

报告里的门槛 1、2、7、8 不成立，门槛 5 只在桌面 rc.2 上成立（见第 8 节）。

---

## 1. 缺陷总表

行号以 `868dc33` 为准。路径前缀简写：
- `K/` = `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/`
- `T/` = `app/android/app/src/test/kotlin/io/github/ccch1mneyyy/dsh_app/`

### P0（违反本轮硬约束，必须先修）

| ID | 缺陷 | 位置 |
|---|---|---|
| P0-1 | 拦截事件永久悬挂 → 所有目标 APP 放行 | `K/loop/intercept/InterceptPageActivity.kt:61,78-79,119,143-148,153-160`；`K/loop/intercept/InterceptRuntime.kt:46,118-127`；`K/loop/core/Intercept.kt:452-458` |
| P0-2 | 事件结束后不重新判定前台 → 切回目标 APP 不被拦 | `K/loop/LoopRuntime.kt:122-134,214-265`；`InterceptPageActivity.kt:131-151` |
| P0-3 | NOT_SHOWN 仍扣冷却与每日额度 → 启动失败后放行 | `Intercept.kt:309-311,466-469,589-594`；注释与实现矛盾见 `InterceptRuntime.kt:154-158` |
| P0-4 | guard.json / ledger.json 跨文件写入窗口 | `Intercept.kt:478-479,503-507,544-553,560-561,573-584,591-592,404-420,428-431`；`K/loop/core/Ledgers.kt:247-278` |
| P0-5 | 未展示页面可以走任何一扇门；`openShownAt==0` 跳过训练停留检查 | `Intercept.kt:517-555`（536 行 `shownAt != 0L &&`）、`558-563`、`570-586`、`611-619`；`InterceptPageActivity.kt:339-357` |

### P1（功能虚假或验收证据不成立）

| ID | 缺陷 | 位置 |
|---|---|---|
| P1-1 | `onStop` 记完回执不 `finish()`（已展示退出、未展示 NOT_SHOWN 两条路径都是） | `InterceptPageActivity.kt:137-141,149-150` |
| P1-2 | 屏幕槽位只在 `onDestroy` 释放；Activity 没被创建 / 迟迟不销毁时，槽位长期被占，主动交流页被挡住 | `InterceptRuntime.kt:113,162-164`；`InterceptPageActivity.kt:153-160`；`withdraw/withdrawAll`（387-393）**从未被调用** |
| P1-3 | 删除目标 APP（`setConfig` → `target_removed`）结束了事件，但页面不关，页面上所有按钮都被拒 | `Intercept.kt:424-433`；`InterceptRuntime.kt:75-78` |
| P1-4 | "授权规则"这扇门实际打不开：`decide()` 在窗口内已经直接放行（304 行），页面只会在窗口外出现，而 `RELEASE_RULE` 只在窗口内被接受（540 行）。页面也没调用 `ruleDoorExists()`，Dart 侧 `InterceptConfig` 连 `authorizedWindows` 字段都没有，无法配置 | `Intercept.kt:304,491-495,540`；`InterceptPageActivity.kt:339-342`；`app/lib/intercept/intercept_channel.dart:86-139` |
| P1-5 | 白名单无法撤销（没有入口）；加白名单时还顺带写了一条限时放行（546 行），撤销后仍会放行最多 `releaseMs` | `Intercept.kt:301,546-548`；`app/lib/ui/phone/intercept_page.dart`（只 patch enabled/targets/参数） |
| P1-6 | 主动交流「回应」是假回复：按钮/通知动作直接报 `Answered("reply")` → RESPONDED → `replied+1`，不打开会话，不发送消息；按钮在可见性证明前就能点，Coordinator 还会把 `shownAt` 补成 now | `K/loop/proactive/ProactivePageActivity.kt:181-194`；`K/loop/proactive/NotificationPortImpl.kt:70`；`K/loop/core/Coordinator.kt:194-201`；`Ledgers.kt:107`；`K/loop/proactive/ProactiveRuntime.kt:218` |
| P1-7 | `hostdeps.mjs` 在手机上找不到宿主包：rc.4 锁文件把 schemastery、dsh-tools 提升到 `node_modules/@deepseek-ai/`（和 dsh 平级），手机上装在 `/opt/dsh/node_modules`（直启模式是 `$rootfs/opt/dsh`），这两个根都不在查找表里；`process.resourcesPath` 只有 Electron 才有 | `examples/plugins/dsh-go-partner/lib/hostdeps.mjs:24-56`；`K/runtime/DirectLaunch.kt:314`；`runtime/locks/dsh-0.2.1-rc.4/package-lock.json` |
| P1-8 | M5 端到端只在桌面 Electron + DSH rc.2 上做过，没打 `runtime/patches` 里改 config-editor 的两个补丁；手机运行时是 rc.4 + 补丁 | `examples/plugins/dsh-go-partner/live-host/run.mjs:21,31`；`runtime/build-runtime.sh:40,441-443`；`runtime/patches/zzz-config-editor-path.diff`、`zzzz-plugin-config-readability.diff` |
| P1-9 | `InterceptRecoveryTest` 没重建账本：`ledgerBook(store)` 丢掉参数、新建空存储；"重启"后复用同一个内存账本实例；`cooldownMs=0` 正好掩盖了 P0-3 | `T/loop/core/InterceptRecoveryTest.kt:31-32,38,58,83,106,121` |
| P1-10 | 内核 191 项靠仓库外脚本 `D:\code\R4\ldev-loop.ps1` 运行；仓库内 `:app:testArm64DebugUnitTest` 报 "No tests found"，任何人都无法在仓库里复现这个数字 | `R4-HARDENING-REPORT.md:145-152`；`R4-LOGIC-CLOSURE-REPORT.md` §10.1 |
| P1-11 | PRoot `.101` 专项测试用的是**手搓、且与报告自身更正相矛盾**的布局：测试注释和 `link101()` 写".101 一跳、计数在第一跳、有 `.proot.l2s.` 临时名"，报告 §9.1 说 .101 与 .98 布局完全相同、`.proot.l2s.` 不在官方 deb；§9.2 又写".98 两跳、.101 一跳"，自相矛盾 | `T/runtime/L2sMigrationTest.kt:602-637,640-746`；报告 §9.1/§9.2/§9.3 |
| P1-12 | 报告与仓库事实不符（详见第 8 节）：门槛 8 "文档 SHA 与远端一致 PASS" 不成立；`6120f28` 不在分支历史；`fix/r4-dev-regression-build` 实为 `48a2328`，报告写 `51c0083`；§十一 写 "§10.1 八项"，实际列了 9 项；§5.2 写 lc run2 = −960，§5.4 写 run2 = 971 | `R4-LOGIC-CLOSURE-REPORT.md` 第 4、30、74 行，§5、§十一 |

### P2（不阻塞，但要排进本阶段）

| ID | 缺陷 | 位置 |
|---|---|---|
| P2-1 | `persist()` 吞异常、调用照样返回 true；状态通道只报守卫的 `storageError`，不报账本的 | `Intercept.kt:654-660`；`Ledgers.kt:286-292`；`K/loop/intercept/InterceptPlugin.kt:286-287` |
| P2-2 | 训练停留时间用墙上时钟，把系统时间往前拨就能跳过 | `Intercept.kt:535-537` |
| P2-3 | `configChanges` 没包含 `fontScale`、`colorMode` 等，会触发重建 → `savedInstanceState != null` 直接 finish（并入 P0-1 一起修） | `app/android/app/src/main/AndroidManifest.xml:285`；`InterceptPageActivity.kt:61` |
| P2-4 | `onNewIntent` 不结算旧 eventId；目前靠守卫"同时只开一个"兜着 | `InterceptPageActivity.kt:65-73` |
| P2-5 | 设置页"暂停"生成 `pause-<now>` 回执，和页面暂停混在 `pausedCount` 里 | `Intercept.kt:576-583` |
| P2-6 | 主动交流侧同样是双文件（调度账本与 coordinator 存储），属于同类风险，本阶段只审计不改 | `K/loop/core/ProactiveScheduler.kt:213-222` |
| P2-7 | Termux 索引只剩 `.101`，下次滚动又会让构建卡在 `termux_deb`；应把锁定的 deb 镜像到自有缓存 | `runtime/build-runtime.sh:47-55,92-97` |
| P2-8 | Flutter 失败集合比较方法不可靠（单次全量会浮动），需要固定的 N 次采样法 | 报告 §5 |
| P2-9 | 去重集合上限 1024，每个事件占 3 个键，约 341 个事件后最早的键被淘汰；改成单一快照后改为每事件 1 个键 | `Ledgers.kt:280-283,295` |

---

## 2. M1 拦截生命周期：执行路径与修复

### 2.1 每个 P0/P1 的具体执行路径

**P0-1 永久悬挂**（任一条都能触发）：

| # | 触发 | 代码行为 | 结果 |
|---|---|---|---|
| a | `startActivity` 没抛异常，但 ROM 或后台启动限制把它静默丢掉了 | `start()` 返回 true（`InterceptPageActivity.kt:377-385`），Activity 从未创建 → 不会有 onStop/onDestroy | 守卫 `openEventId` 永远非空 |
| b | 页面已展示 → 按电源键 → `onStop` 看到熄屏直接 return（143-148）→ 在锁屏上点通知/相机直接进抖音 | 页面已经 stop 过，不会再 stop 一次 | 永不结算 |
| c | 系统回收 Activity（低内存、开发者选项"不保留活动"），或未列入 `configChanges` 的配置变化导致重建 | 重建时 `savedInstanceState != null → finish()`，不报告（61 行）；`onDestroy` 只释放槽位、不结算（153-160） | 永不结算 |
| d | Intent 晚到，deadline 已过 | `bind()` 直接 finish，不报告（78-79） | 永不结算 |
| e | 页面被悬浮窗、分屏或输入法抢走焦点，可见性证明 20 次都失败 | 119 行重试用完就停，不报告；页面可能一直处于 started 不 stop | 永不结算 |

悬挂期间，守卫的 `onForeground` 对每个目标 APP 都返回 `Allow(another_open / already_open)`（`Intercept.kt:452-458`）。`PAGE_TTL_MS` 只是作为 extra 传进页面，在 `bind()` 时检查一次（`InterceptRuntime.kt:46,120`），没有任何到期机制。

**P0-2 不重新判定前台**：拦截页展示时，用户用"最近任务"切回 B站（或切到抖音）。
- 情况一：无障碍窗口事件经 350ms 去抖后 `verifyNow` 先跑 → 守卫仍是 open → `Allow(already_open)`；随后页面 `onStop` → `onExited` 结算。之后没有新的窗口事件，B站一直可用。
- 情况二：`onStop` 先跑 → 结算后 `decide()` 命中冷却（`lastTriggerAt` 在触发时盖章，`Intercept.kt:468`；309 行判定）→ `Allow(cooldown)` 2 分钟。

**P0-3 NOT_SHOWN 扣冷却**：守卫一决定 Block 就写 `lastTriggerAt`、`countToday+1`（466-469）。随后 `start_failed`、`surface_busy`、`stopped_before_shown` 走 `onUnavailable`（589-594），只写 NOT_SHOWN 回执，不回滚这两个值。用户仍在 B站，下一次窗口事件 → 冷却 → 放行。`InterceptRuntime.kt:154-158` 的注释写着"no cooldown is charged"，实现与注释相反。

**P0-5 未展示也能放行**：页面画出来了，但焦点被悬浮窗占着，证明失败。此时"授权规则""加入白名单""暂停""紧急退出"四个按钮从一开始就是可点的（`InterceptPageActivity.kt:339-357`，只有训练按钮和题目按钮受 `shown` 控制）。点"加入白名单" → 守卫不查 `openShownAt` → `RELEASED(whitelist)`，写入永久白名单，而 `blockedCount` 仍是 0。守卫的 `tapDrill` 也不查 `openShownAt`，而 536 行在 `openShownAt == 0` 时**跳过**停留检查。注释说守卫才是可信的一方（"the page is the one surface an untrusted build could fake"），但守卫自己没做这个检查。

### 2.2 修复设计（不重写架构，只补终止路径）

**原则**：每一条终止路径都经过**同一个出口**；结算、关页面、释放槽位、重新判定前台，四件事一起做。

**A. 守卫侧（纯逻辑，可 JVM 测）**

1. 新增 `InterceptGuard.expire(now)`，并在每个公开入口（`onForeground` / `onPageShown` / `on*`）开头调用：
   - `openShownAt == 0 && now - openSinceWall >= PROOF_DEADLINE_MS`（建议 5 s）→ `NOT_SHOWN("proof_timeout")`
   - `openShownAt != 0 && now - openShownAt >= pageTtlMs` → 按 **D1** 的结论结算（默认建议 `BLOCKED("page_ttl")`）
   这一条单独就能保证"悬挂最多持续一个 TTL"，不依赖任何 Activity 回调。
2. 新增 `onPageLost(eventId, reason, now)`：已展示 → `BLOCKED(reason)`，未展示 → `NOT_SHOWN(reason)`。给 `onDestroy`、重建、deadline 过期这几条路径用。
3. **所有门**（`onReleased` 三种、`onExited`、带 eventId 的 `onPaused`、`tapDrill`）都要求 `openShownAt != 0`，否则返回 false。设置页的 `onPaused(null)` 不受影响。
4. 536 行改成：`if (shownAt == 0L) return false; if (trainingMs > 0 && elapsedSinceShown < trainingMs) return false`。停留时间改用单调时钟（守卫构造参数加 `elapsed: () -> Long`，状态里存 `openShownElapsed`，不持久化，因为进程死亡本来就会结束事件）。
5. 冷却与额度拆开记账（具体规则见 **D5**）：最少要做到 NOT_SHOWN **不消耗**冷却；另加一个同包重试退避（建议 15 s，防止反复启动页面）。

**B. 适配层（Android）**

6. `InterceptRuntime` 加一个统一出口 `afterGuard(prevOpen)`：每次调用守卫前记下 `openEventId`，调用后如果它变了，就执行
   `VisibleSurface.release(INTERCEPT, prevOpen)` → `InterceptPageActivity.withdraw(prevOpen)`（把这段死代码接上）→ `LoopRuntime.recheckSoon()`（即 `schedule()`）。
   这样 P0-2、P1-1、P1-2、P1-3 都由同一处修好，不用在每个按钮里各写一遍。
7. `show()` 成功后在主线程挂两个定时器：`PROOF_DEADLINE_MS` 与 `pageTtlMs`，到点就调 `guard.expire(now)` + `afterGuard`。这样即使 Activity 根本没被创建，事件也会结束。进程死亡由已有的 `recover()` 兜底。
8. `InterceptPageActivity`：
   - `onStop` 两条报告路径之后都 `finish()`；
   - `onDestroy` 如果 `!reported` 就调 `onPageLost(eventId, "page_destroyed")`；
   - `savedInstanceState != null` 和 deadline 过期时，先调 `onPageLost` 再 finish；
   - `onStart` 时如果守卫的 `openEventId != eventId` 就直接 finish（处理过期页面）；
   - 所有按钮在 `shown` 之前都 `isEnabled = false`；
   - 熄屏 stop 时置 `stoppedWhileScreenOff = true`，`onStart` 时清掉。
9. 前台对账（修 P0-1b 和 P0-2 情况一）：`InterceptRuntime.onForeground(pkg)` 发现守卫有 open 事件，而 `pkg` 不是本 App 的包名、页面也没处于 resumed，且已经过了启动宽限期（≥ `PROOF_DEADLINE_MS`）时，按"用户已离开"结算：已展示 → `EXITED("left")`，未展示 → `NOT_SHOWN("not_in_front")`。结算后立即对这个 `pkg` 重新 `decide()`。拦截页是浮动对话框，正常情况下焦点窗口就是本 App，所以这条规则不会误伤。
10. `AndroidManifest.xml:285` 补 `fontScale|colorMode|density|touchscreen`（P2-3）。

**C. 终止路径一览（修复后，每行都必须有测试）**

| # | 路径 | 回执 | finish | 释放槽位 | 重新判定前台 |
|---|---|---|---|---|---|
| 1 | 训练通过（已解题 + 停留够） | RELEASED(training) | ✓ | ✓ | 打开目标 APP |
| 2 | 授权规则 | 按 **D3** | ✓ | ✓ | — |
| 3 | 加入白名单 | RELEASED(whitelist) | ✓ | ✓ | 打开目标 APP |
| 4 | 暂停 | PAUSED | ✓ | ✓ | 打开目标 APP |
| 5 | 紧急退出 / 返回键 | EXITED(emergency_exit) | ✓ | ✓ | 回桌面 + 重新判定 |
| 6 | 已展示，亮屏时离开（Home / 最近任务 / 切 APP） | EXITED(left) | ✓ | ✓ | ✓ 立即 |
| 7 | 已展示，熄屏 | 不结算 | — | — | 亮屏回到页面 → 继续；锁屏上直接进别的 APP → 走 #6；TTL 到 → 走 #12 |
| 8 | 未展示就 stop | NOT_SHOWN(stopped_before_shown) | ✓ | ✓ | ✓（带退避） |
| 9 | 证明超时 | NOT_SHOWN(proof_timeout) | 撤下页面 | ✓ | ✓（带退避） |
| 10 | 启动被拒（抛异常） | NOT_SHOWN(start_failed) | — | ✓ | ✓（带退避） |
| 11 | 启动被静默丢弃 | 由 #9 的定时器兜住 | — | ✓ | ✓ |
| 12 | 已展示且 TTL 到期 | 按 **D1** | 撤下页面 | ✓ | 按 **D1** |
| 13 | Activity 被系统销毁 / 重建 / deadline 过期 | BLOCKED 或 NOT_SHOWN(page_destroyed) | ✓ | ✓ | ✓ |
| 14 | 目标被移出清单 | RELEASED(target_removed) | 撤下页面 | ✓ | — |
| 15 | 进程死亡 | `recover()`（已有） | — | 内存槽位本来就空 | 首次窗口事件 |

---

## 3. M2 跨文件持久化

### 3.1 每个操作的写入顺序与崩溃窗口

当前每个守卫操作都是：先由 `ledger.on*()` 写 `ledger.json`，再由守卫 `persist()` 写 `guard.json`。两次写各自是原子的，但两次写之间不是。

| 操作 | 写入顺序 | 两次写之间进程死亡后的状态 | 违反的要求 |
|---|---|---|---|
| `onForeground`（Block） | ledger：`trigger:X`、triggerCount+1 → guard：openEventId=X、countToday+1、lastTriggerAt | 账本有触发，守卫没有 open X → `recover()` 什么也不做 → **这个事件永远没有回执** | ② triggerCount 增加却没有对应事件 |
| `onPageShown` | ledger：`blocked:X`、blockedCount+1 → guard：openShownAt | 守卫 openShownAt=0 → `recover()` 写 **NOT_SHOWN**，而 blockedCount 已经加过 | ③ |
| `onReleased`（白名单/训练/规则） | ledger：`end:X` RELEASED、userReleasedCount+1 → guard：whitelist+pkg、releases、open=null | 守卫仍有 open X → `recover()` 想写 BLOCKED，但 `end:X` 已存在被去重丢掉 → 守卫清掉 open。**白名单丢失、限时放行丢失**，回执却写着 RELEASED(whitelist) | ④ |
| `onPaused`（页面） | ledger：PAUSED 回执（含 releasedUntilWall）→ guard：pauseUntilWall、open=null | 暂停丢失，回执却说已暂停 | 一致性 |
| `onPaused(null)`（设置页） | ledger：`pause-<now>` 回执 → guard：pauseUntilWall | 暂停丢失 | 一致性 |
| `setConfig`（移除目标） | ledger：RELEASED(target_removed) → guard：新 config | 配置修改丢失，回执说已释放 | 一致性 |
| `onExited` / `onUnavailable` | ledger 回执 → guard open=null | 重启时 `recover()` 的回执被去重，守卫清掉 open | 基本无害 |
| `recover()` | ledger 回执 → guard open=null | 再次 `recover()` 被去重 | 幂等 ✓ |

另有两处：
- `AtomicJsonFile.read()` 在主文件缺失时读 `.bak`，两个文件可能各自恢复到**不同代**。
- 账本写失败、守卫写成功时，调用方拿到 true，计数静默丢失（P2-1）。

### 3.2 方案：合并成一个原子快照（推荐）

在三个候选方案里选**单一原子快照**：不需要新的日志格式和回放代码，文件很小（100 条回执 + 去重键，约 40 KB），而现有 `AtomicJsonFile` 已经提供了 temp → fsync → 原子替换 → `.bak` → 目录 fsync。事件日志和 outbox 都要额外的回放和压缩逻辑，对一个最多 100 条回执的账本来说成本过高。

1. **新文件** `files/loop/intercept/intercept.json`，`version: 2`，内容是 `{config, state, ledger, applied}`。
2. `InterceptLedgerBook` 的 store 改成可空。为 null 时 `persist()` 什么都不做，并新增 `snapshot(): Map` / `restore(Map)`。旧构造函数保留，现有测试不用改。
3. `InterceptGuard.persist()` 把 `ledger.snapshot()` 一起写进去。每个守卫操作只在最后写**一次**（现在的代码结构本来就是先调 `ledger.on*()` 再 `persist()`，改动很小）。
4. **写失败就回滚**：每个操作开始时记下 `(config, state, ledger.snapshot())`；`store.write` 抛异常就恢复这三样并返回 false；页面收到 false 就留在原地，提示"保存失败"。这样"返回 true"就等于"已经落盘"。
5. **v1 → v2 迁移**，在 `recover()` 里、只在 `intercept.json` 不存在时执行：
   1. 读 `guard.json` 和 `ledger.json`（主文件或 `.bak`）。
   2. 对账：
      - 账本里有 `trigger:X`、没有 `end:X`，守卫也没有 open X → 补一条回执：有 `blocked:X` 就记 BLOCKED，否则记 NOT_SHOWN，`reason="migrated_orphan"`。
      - 守卫 open X 且 `openShownAt == 0`，但账本有 `blocked:X` → 先把它当作已展示，再走正常的 `recover()`，结果是 BLOCKED。
      - 守卫 open X，但账本已有 `end:X`：
        - RELEASED(whitelist) → 把 pkg 补进白名单；
        - `releasedUntilWall > now` → 补回 `releases[pkg]`；
        - PAUSED → 补回 `pauseUntilWall`。
        这是在兑现用户已经做出的决定（要求 ④）。
      - 其他无法确定的情况写入 `recoveryIssue`，不猜。
   3. 原子写入 `intercept.json`，然后把旧文件改名为 `*.migrated-v1`。改名前崩溃也没关系：下次启动看到 `intercept.json` 就忽略旧文件。
6. 去重改为每事件一个键：`applied: {eventId: "T|B|E"}`，按插入顺序最多保留 1024 个事件（P2-9）。
7. `InterceptPlugin.status` 同时暴露账本的 `storageError` / `recoveryIssue`（P2-1）。

五条要求的对应关系：① 去重键与计数在同一次原子写里；② ③ 触发、展示、结算各自与守卫状态同一次落盘；④ 白名单和回执同一次落盘，迁移时还会补回；⑤ `recover()` 结算与 open 清除同一次落盘。

---

## 4. M3 训练与放行

| 检查项 | 现状 | 修复 |
|---|---|---|
| 未展示不能训练放行 | ❌ 守卫不查；`openShownAt==0` 跳过停留检查（536 行） | 第 2.2 节 A3、A4 |
| 未解题不能放行 | ✓ 531 行；但 `tapDrill` 在展示前也被守卫接受 | `tapDrill` 要求已展示 |
| 停留不足不能放行 | ✓（有展示证明时）；墙上时钟可以被拨 | 改用单调时钟（P2-2） |
| 未配置授权窗口不能规则放行 | ✓ `hasUsableRule` 要求非空且在窗口内 | 但这扇门在实际中打不开（P1-4）→ **D3** |
| 白名单必须能撤销 | ❌ 没有入口；撤销后限时放行还在 | 新增通道方法 `removeWhitelist(pkg)`：同时删 `whitelist` 和 `releases[pkg]`，同一次原子写，并写一条审计回执 `WHITELIST_REMOVED`（不计入五个计数）。Dart 设置页加白名单列表和移除按钮。是否要二次确认见 **D4** |

---

## 5. M4 主动交流：真正的回复闭环

### 5.1 现状

页面和通知上的「回应」直接报 `Answered("reply")`（`ProactivePageActivity.kt:193`、`NotificationPortImpl.kt:70`）→ `Coordinator.kt:194` 关闭为 RESPONDED → 账本 `replied+1`（`Ledgers.kt:107`）→ 计为用户活动（`ProactiveRuntime.kt:218`）。中间**没有打开会话，也没有发送任何消息**。而且按钮在可见性证明前就能点，Coordinator 会把 `shownAt` 补成 now（195 行），等于绕过了展示证明。

### 5.2 目标闭环（复用已有组件，不新建架构）

```
主动消息 Present(ACTIVITY)
  → Shown（可见性证明，已有）
  → 用户点「我看到了」= Acked（已有，可以跳过）
  → 用户点「去回复」 → 新的 SurfaceReport.ReplyStarted
        Coordinator：顺带补 Acked（点了就说明看到了）；记 replyIntentAt；事件保持打开；
                     replyDeadline 沿用现有回复窗口；发出 Command.OpenSession(eventId, sessionId)
        适配层：startActivity(EntryActivity.openChat(ctx, sessionId, host=null)
                 + extra proactive_event_id)；页面 finish，但事件不关闭
  → Dart：ChatScreen 打开这个 sessionId，输入框聚焦；记住 {eventId, deadline}
  → 用户输入 → chat_state.send() 返回 true（host 已接收，带 requestId）
  → Dart 调 dsh/proactive 的新方法 replied {eventId, sessionId, requestId}
  → ProactiveRuntime.onUserReplied → Coordinator.onReplied：
        校验 eventId 是当前活动事件、replyIntentAt 不为空、sessionId 等于插件配置的会话、
        now ≤ replyDeadline，全部通过 → RESPONDED（answer = "msg:" + requestId）
  → 回复窗口走完仍未发送 → 按现有规则变成 IGNORED_SEEN（具体见 D7）
```

可复用的现有组件：
- `EntryActivity.openChat(context, sessionId, host)`（`K/shortcuts/EntryActivity.kt:79-83`）
- `ShortcutsPlugin.EXTRA_SESSION` 路由
- `chat_state.send()` 返回值：host 接收后为 true（`app/lib/state/chat_state.dart:1116-1167`）
- 插件 `/config` 里的 `sessionId`（`ProactivePlugin.kt:111-114`）

需要改的地方：
- `CoreModel.kt` 增加 `SurfaceReport.ReplyStarted`、`Command.OpenSession`，事件字段增加 `replyIntentAt`，并完成编解码。
- `Coordinator.onSurface` 处理 `ReplyStarted`，新增 `onReplied`。
- `Answered` 不再由任何界面发出：保留枚举只为兼容旧快照。
- 通知上的「回应」动作改成 PendingIntent 打开会话，并上报 `ReplyStarted`。
- 页面所有按钮在 `shown` 之前禁用。
- 没配置会话时隐藏「去回复」。
- Dart 侧把 `{eventId, deadline}` 持久化到 `SharedPreferences`，防止 App 进程重启后丢失。

### 5.3 本轮做不完时的退路（需要负责人选，见 D6）

把「回应」改名为「打开对话」，它只上报 `Acked` 并执行 `OpenSession`，**永远不产生 RESPONDED**。RESPONDED 在界面上不可达，在报告里明确写成"未实现"。

---

## 6. M5 插件真实兼容性：验证方案

### 6.1 rc.2 与 rc.4 的差异必须先列清楚

上一轮 live-host 用的是桌面 Electron（`run.mjs:21` 走 `resources/app.asar`，31 行 `ELECTRON_RUN_AS_NODE=1`）+ DSH rc.2，**没有** `runtime/patches` 里的补丁。手机运行时是 rc.4（`build-runtime.sh:40`），并且打了 `zzz-config-editor-path.diff`、`zzzz-plugin-config-readability.diff`，这两个补丁直接改 `ConfigEditor`。所以门槛 5 只证明了"桌面 rc.2 未打补丁"这一种组合。

**步骤 0**：对 `dsh-config-editor`、`dsh-tools`、`dsh-cordis-host-runner`、`cordis-plugin-loader` 做 `npm diff`（rc.2 ↔ rc.4），再加上两个补丁的 diff，列出所有影响插件加载、配置写入、工具注册的改动。

### 6.2 阶段 A：Linux x86_64 上的非 Electron 宿主（与手机同目录结构）

1. 用 `build-runtime.sh` 里 `install_dsh` 同样的方式（rc.4 锁文件 `npm ci` + `patch-dsh`）装进一个前缀，布局与手机一致：`/opt/dsh/node_modules/...`，`DSH_HOME=/root/.dsh`，profile 为 `web`。最好直接用 `runtime/out/x86_64` 的 rootfs，在 x86_64 Linux 上用 proot 运行。
2. 把 partner、cap-ledger、bili 按 App 安装用户插件的方式装进 `profiles/web`。
3. 断言：
   1. `process.versions.electron === undefined`，`process.resourcesPath === undefined`（防止误用桌面环境）
   2. `hostdeps` 命中的根是 `/opt/dsh/node_modules/@deepseek-ai`（插件打日志）
   3. cordis：`dshGoCapabilityLedger` 被 provide；bili 在两种加载顺序下都能 `ctx.get` 到（用 `cordis.yml` 顺序跑两次）
   4. dsh-tools：工具注册表里能看到 `peek_bili_video` 和 partner 的 4 个工具
   5. `ConfigEditor.edit`：走 App 实际用的路径 `runtime/bridge/dsh-go-bridge/lib/plugin-config.js:289` → 生成 `cordis.patch.yml` → 插件运行时收到新值（volatile，不重启插件）
   6. `kill -9` 后重启：配置还在，行为还在
   7. 直启模式布局（`$rootfs/opt/dsh`，没有 proot 路径翻译）下重复 2–6
4. 阶段 A 的结论只能写成"非 Electron 等价宿主 PASS"，**不能写成手机 PASS**。

### 6.3 `hostdeps.mjs` 最小修复（P1-7）

在 `schemasteryRoots()` 最前面加两类根：
1. `process.env.DSH_GO_HOST_NODE_MODULES`
2. 从 `realpathSync(process.argv[1])` 往上找，遇到的第一个 `node_modules`，拼上 `/@deepseek-ai`。手机上 `argv[1]` 是 `/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js`；如果是经 `/usr/bin` 软链进来的，`realpath` 能还原。

保留原有顺序作为后备。命中哪个根要打一行日志；全都失败时，面板上要明确显示"宿主未提供配置表单"，不能静默降级。

### 6.4 阶段 B：真机（唯一能算"手机运行环境"的证据）

arm64 手机装 rc.4 运行时的 App，重复阶段 A 的 3.2–3.6，另外加 App 设置页"保存" → 引擎生效 → 杀 App → 重开仍生效。证据：`adb logcat` + `run-as` 读取 `cordis.patch.yml`。

---

## 7. M6 PROOT

| 项 | 结论 | 依据 |
|---|---|---|
| `.101` 默认版本 | 已改（`build-runtime.sh:55`），两个 ABI 的 deb 都有哈希（95-96 行）；**用 `.101` 默认值跑双 ABI 运行时构建这一步没做过**（报告 §十三 自己也承认） | 第四阶段必须跑 `runtime/build-runtime.sh`（这是运行时构建，不是 APK），检查 G1′ 与 `test_verify_outputs` |
| `L2sMigration` 遍历 | 逻辑本身成立：只顺着符号链接走到普通文件，不解析名字 | `L2sMigration.kt:218-236` |
| `repair()` | 只在唯一候选且计数 ≥ 名字数时修，用原子改名；在空间检查前就动手，但只是把链接指回已存在的文件，安全 | `L2sMigration.kt:248-270` |
| `compatibilityReason` | 只看 `root/.l2s` 是否为空；与 `PROOT_L2S_DIR={ROOTFS}/root/.l2s`（`runtime/lib/rtbuild.py:1372`）一致 ✓ | `DirectLaunch.kt:104-110` |
| 中断恢复 | `anInterruptionAtAnyStep…` 用 `beforeRename` 注入点覆盖每一步 ✓ | `L2sMigrationTest.kt:306` |
| **测试夹具 vs 真实数据** | 见下表 | |

| 测试 | 性质 |
|---|---|
| `L2sMigrationTest.kt:106-600` 的 23 项 | **手搓夹具**，按源码 `link2symlink.c` 构造 |
| `theStoreAReleaseLeavesBehindIsTheOneThisConverts`（543） | 声称是"从真实 .101 运行逐字抄来的布局"，但仍是用 `Files.createSymbolicLink` 手写的，仓库里没有提交原始采集结果 |
| `.101` 专项 5 项（640-746） | **手搓夹具，而且布局与报告 §9.1 的更正相矛盾**（一跳、计数在第一跳、`.proot.l2s.` 临时名）。它们可以保留为"未知布局下的健壮性测试"，但**不能算 .101 证据** |
| "DEV 用真实二进制复现断链" | 仓库里没有能复现的脚本（`runtime/tests/direct/` 下没有对应内容），属于不可复核的证据 |

**修复**：
1. 把 602-616 行的注释和 `link101` 改名为 `linkHypotheticalOneHop`，测试名去掉 `proot101`。
2. 新增 `runtime/tests/direct/l2s-capture.sh`：在 x86_64 主机上用**真 .101 二进制**（从锁定的 deb 解出）以 `--link2symlink` 建链、删链，用 `find -printf '%y %f -> %l'` 采集，把结果作为 fixture 文本提交，让 `theStoreAReleaseLeavesBehind…` 改为读这个文件。
3. 断链复现用 `strace -e inject=renameat:signal=KILL:when=2` 或 `gdb` 断点在 decrement 的两次改名之间杀进程，脚本入库。
4. 修正报告 §9.2、§9.3 的测试名。
5. 双 ABI：x86_64 用真二进制跑采集；arm64 只做构建验证和真机迁移。

---

## 8. M7 测试与报告

| 项 | 发现 | 处理 |
|---|---|---|
| InterceptRecoveryTest 是否真的重建账本 | **没有**（P1-9）。`ledgerBook(store)` 丢掉参数；"重启"复用同一个内存账本；`guard()` 里已经 `recover` 过，外面又 `.also{recover}` 一次；`cooldownMs=0` 掩盖了 P0-3 | 重写：守卫和账本都从**同一个** `MemorySnapshotStore` 新建实例；使用默认冷却；每次"重启"都 `new` |
| Flutter 失败集合是否与基线一致 | **不能确认**。最新报告自己承认 lc run2 相对 R3 多 1 条（`plugin_config_scope_fresh_test`，单独连跑 5 次挂 2 次）；§5.2 写 run2 = −960，§5.4 写 run2 = 971，互相矛盾；`session_sync` "每次都挂"，却在 run1 里"消失" | 采用固定方法：基线树和候选树各全量跑 3 次，失败名取**并集**做"可能失败"，取**交集**做"稳定失败"；新增失败 = 候选的交集 − 基线的并集，必须为空。日志要入库或作为附件，不能只放在 `D:\` |
| "九项"门槛数量 | §十一 写 "NOT_TESTED §10.1 八项"，§10.1 实际 **9** 行；PASS 表只有 8 项 | 报告重写时逐表核对行数 |
| HEAD 与提交链 | 报告 HEAD `8810464` ≠ 远端 `868dc33`；`6120f28` 不存在；`fix/r4-dev` 报告 `51c0083`，实际 `48a2328`；门槛 8 应判 **FAIL** | 报告不再硬写 SHA，改为附 `git ls-remote` 输出原文 |
| rc.2 / rc.4 验证差异 | 门槛 5 只覆盖桌面 rc.2、未打补丁 | 降级为 PARTIAL，按第 6 节重做 |
| 内核 191 项能否复现 | 依赖仓库外脚本；Gradle 报 No tests found | 把 `ldev-loop.ps1` 的等价 bash 版 `tooling/run-kernel-tests.sh` 入库；同时查清 `testArm64DebugUnitTest` 为什么找不到测试（排查顺序：`--tests` 过滤器 → `src/test/kotlin` 是否被 AGP 收入 → `LoopHardeningTest` 编译失败是否让整个任务跳过） |

**报告门槛的重新判定**

| 门槛 | 报告 | 本审查 |
|---|---|---|
| 1 M1 无未授权放行 | PASS | **FAIL**（P0-1、P0-2、P0-3、P0-5） |
| 2 进程恢复不误记展示 | PASS | **FAIL**（P0-4 第二行；测试无效 P1-9） |
| 3 ACK 语义 | PASS | PASS（"回应"另算，见 P1-6） |
| 4 未执行插件不显示健康 | PASS | 未复核（不在本轮范围） |
| 5 配置影响运行 | PASS | **PARTIAL**（只覆盖桌面 rc.2） |
| 6 Flutter 失败集合 | PASS | **PARTIAL**（多 1 条，方法不可靠） |
| 7 PROOT .101 迁移 | PASS | **PARTIAL**（.101 专项是与事实矛盾的夹具；双 ABI 构建未跑） |
| 8 文档 SHA 一致 | PASS | **FAIL** |

---

## 9. 需要负责人决定的策略（附建议，未做决定）

| ID | 问题 | 选项 | 建议 |
|---|---|---|---|
| **D1** | 页面已展示、用户什么都不做，TTL（10 分钟）到期时怎么办 | A：记 BLOCKED(page_ttl)，撤下页面并**回桌面**，不放行 · B：页面一直留着，没有 TTL · C：到期放行 | **A**。B 会让熄屏/锁屏的悬挂没有上限；C 违反"不得因超时错误放行" |
| **D2** | 可见性证明失败（5 s 内没有证明）时怎么办 | A：NOT_SHOWN，撤下页面，同包 15 s 后可以重试 · B：改为发一条通知 · C：维持现状（不处理） | **A**，并且每个包每分钟最多重试 N 次（N 建议 3），超过后记一次 `recoveryIssue` |
| **D3** | "授权规则：放行 N 分钟"这个按钮的语义 | A：去掉这个按钮（窗口内本来就自动放行，按钮多余） · B：恢复原意，作为用户自我授权按钮（例如必须先解题），这会改变 M1-A 的结论 · C：保留，但只在"页面在窗口开始前打开、此刻已进入窗口"时显示 | **A**，同时在 Dart 设置页补上授权时段的配置（现在完全无法配置）。这会改变用户原先设计的按钮，必须由你确认 |
| **D4** | 白名单撤销要不要二次确认；撤销时是否同时取消限时放行 | A：直接移除，同时取消限时放行 · B：要确认，同时取消 · C：不取消限时放行 | **A** |
| **D5** | 冷却和每日额度按什么计 | A：NOT_SHOWN 不扣冷却也不扣额度；冷却从**已展示事件结束**算起，只在 RELEASED / PAUSED 之后生效；EXITED 之后不给冷却 · B：只修 NOT_SHOWN，其余照旧（EXITED 之后 2 分钟内重开同一个 APP 仍放行） · C：维持现状 | 至少选 **B**（这是 bug）。是否选 A 取决于你对"紧急退出后马上重开"算不算绕过的看法：A 更严，B 保留现有语义 |
| **D6** | 主动回复本轮是否实现真正的闭环 | A：实现第 5.2 节的闭环 · B：改名为"打开对话"，不产生 RESPONDED | **A**，组件都现成，工作量中等；时间不够就先做 B，并在报告里写明 |
| **D7** | 点了"去回复"但没发消息，窗口走完算什么；没点按钮、直接打开 App 在该会话里回复算不算 | A：前者算 IGNORED_SEEN，后者不算 · B：前者算 IGNORED_SEEN，后者也算 RESPONDED（窗口内、同会话、展示之后） | **B**：更接近"用户真的回复了"这个事实 |

---

## 10. 新增测试清单

**内核 JVM（`T/loop/core/`）**

| 文件 | 用例 |
|---|---|
| `InterceptLifecycleTest`（新） | 证明超时 → NOT_SHOWN；TTL 到期按 D1；`onPageLost` 已展示 → BLOCKED、未展示 → NOT_SHOWN；`expire` 幂等；前台对账：open 时看到其他目标包 → 结算并立即重新判定；宽限期内不对账；熄屏后直接进别的 APP 被对账结算 |
| `InterceptReleaseDoorTest`（补） | 未展示时 5 扇门 + `tapDrill` 全部被拒；`openShownAt == 0` 时训练被拒；用单调时钟算停留（拨墙上时钟无效）；`removeWhitelist` 同时删掉限时放行；撤销后重开被拦 |
| `InterceptCooldownTest`（新） | NOT_SHOWN 不消耗冷却（使用**默认**冷却 2 分钟）；按 D5 的结论测 EXITED / RELEASED 之后的行为；同包重试退避 |
| `InterceptRecoveryTest`（重写） | 守卫和账本都从同一个持久化存储**新建实例**；使用默认配置 |
| `InterceptAtomicSnapshotTest`（新） | 用 `FailingStore(failOnWrite = k)` 在每个操作的写入点抛异常 → 内存回滚、返回 false；"进程死亡"后重建 → 五条要求逐条断言；针对 3.1 表的每一行各一个用例 |
| `InterceptMigrationV1Test`（新） | 构造 3.1 表里的每一种半写入 v1 文件对 → 迁移后满足不变量；白名单和暂停被补回；迁移幂等；改名前崩溃 |
| `InterceptInvariants`（测试工具） | 每个 `trigger:X` 都有 `end:X` 或 X 正处于 open；不存在"有 `blocked:X` 却以 NOT_SHOWN 结束"；所有计数 = 回执按类型统计 |
| `ProactiveReplyLoopTest`（新） | `ReplyStarted` → 补 ack、`OpenSession` 命令；`onReplied` 的四项校验（eventId、replyIntentAt、sessionId、deadline）；过期 / 错会话 / 未点按钮时按 D7；`Answered` 不再出现；`replied` 只因 `onReplied` 增加 |

**Android 适配层**（JVM 跑不了 → 需要 Robolectric 或真机，见第 11 节）
- `InterceptRuntimeExitHookTest`：用假守卫驱动 `afterGuard`，断言槽位被释放、`withdraw` 被调用、`recheck` 被调度。把 `afterGuard` 抽成纯函数就可以在 JVM 上测。

**插件**
- `hostdeps.test.mjs`：伪造 `argv[1]` 指向 `/opt/dsh/...` 布局的临时目录，断言命中；软链 argv；`resourcesPath` 不存在；环境变量优先。
- `live-host-rc4/`（新）：第 6.2 节阶段 A 的 7 条断言。

**PROOT**
- `l2s-capture.sh` + 真二进制 fixture；断链注入脚本；重命名 hypothetical 测试。

**Flutter（M4 Dart 侧）**
- 带 `proactive_event_id` 打开会话 → `send` 成功 → 调用 `replied`；`send` 失败不调用；跨 App 重启仍能找到上下文。

---

## 11. 必须真机验收的项目

| # | 项目 | 为什么 JVM / 主机做不了 |
|---|---|---|
| 1 | 拦截页真实可见性证明（焦点、悬浮窗、分屏、输入法） | 窗口系统行为 |
| 2 | 后台启动限制下 `startActivity` 被静默丢弃 → 5 s 内 NOT_SHOWN、槽位释放 | ROM 差异 |
| 3 | 熄屏 → 锁屏 → 直接进别的 APP → 回执 EXITED(left) 且新 APP 被拦 | 锁屏 / 无障碍事件时序 |
| 4 | 最近任务切回目标 APP 被重新拦截（P0-2） | 同上 |
| 5 | 开启"不保留活动"后，`onDestroy` 结算 | 系统回收 |
| 6 | 字体缩放 / 深色模式切换不导致悬挂 | 配置变化 |
| 7 | TTL 到期行为（D1） | 定时器 + 窗口 |
| 8 | 主动交流：去回复 → 打开指定会话 → 发送 → RESPONDED；通知动作同样 | 跨 Activity + Flutter |
| 9 | 主动页与拦截页争用槽位：拦截页残留时主动页不被永久挡住 | 双 Activity |
| 10 | rc.4 运行时里 partner 的原生表单出现、保存生效、重启仍生效（第 6.4 节） | 手机宿主 |
| 11 | `.98` 旧数据在 `.101` 运行时上迁移（需要一台装过旧运行时的机器） | 真实存量数据 |
| 12 | arm64 上 `.101` proot 启动与直启切换 | arm64 二进制 |

---

## 12. 第四阶段实施顺序（交给 DeepSeek Harness）

### 12.0 硬约束（每个任务都适用）

- 只在新分支 `fix/r4-p4-<模块>-2026-11-xx` 上工作，最后合并到新的集成分支 `integration/r4-p4-…`。**不得 force push，不得改动 `main`、`fix/r3-*`、以前的 `integration/*`**。
- 禁止重写架构和无关重构。每个提交只做一件事，提交信息写明对应的缺陷 ID（如 `P0-3`）。
- **先红后绿**：每个 P0/P1 先提交一个在当前代码上失败的测试，再提交修复。两次的测试输出都贴进 PR 描述。
- 第 9 节 D1–D7 没得到负责人回复之前，涉及的分支只做与策略无关的部分；与策略相关的代码不得合并。
- 不构建 APK。运行时构建（`runtime/build-runtime.sh`）只在第 7 步、并得到负责人许可后执行。
- 报告里的每个数字都必须附带可在仓库内复现的命令；仓库外脚本不算证据。

### 12.1 步骤

| 步 | 内容 | 缺陷 | 依赖 | 完成标准 |
|---|---|---|---|---|
| **0** | 冻结 `integration/r4-logic-closure` 并打标签 `r4-p3-frozen`；把内核测试运行器入库为 `tooling/run-kernel-tests.sh`（kotlinc + junit，与 `ldev-loop.ps1` 等价）；查清 Gradle 为什么 No tests found | P1-10 | — | 仓库内一条命令跑出 191 项 |
| **1** | 重写 `InterceptRecoveryTest`；补上第 10 节里 P0-1/2/3/5 的**红**测试（纯守卫层） | P1-9 | 0 | 新测试在当前代码上**失败** |
| **2** | M2 单一原子快照 + v1 迁移 + 写失败回滚 + 账本错误对外暴露 | P0-4、P2-1、P2-9 | 1 | `InterceptAtomicSnapshotTest`、`InterceptMigrationV1Test` 全绿；原有 191 项不回归 |
| **3** | 守卫：`expire`、`onPageLost`、所有门要求已展示、单调时钟停留、NOT_SHOWN 不扣冷却 + 退避（D5 的 B 部分先做）、`removeWhitelist` | P0-1(A)、P0-3、P0-5、P1-5(内核)、P2-2 | 2；D1/D2/D5 | 第 1 步的红测试全部变绿 |
| **4** | 适配层：`afterGuard` 统一出口（接上 `withdraw`）、两个定时器、`onStop` 后 finish、`onDestroy` 结算、重建/过期结算、`onStart` 处理过期页面、按钮在展示前禁用、前台对账、`configChanges` | P0-1(B)、P0-2、P1-1、P1-2、P1-3、P2-3、P2-4 | 3 | `:app:compileArm64DebugKotlin` 通过；`afterGuard` 纯函数测试绿 |
| **5** | 设置页：白名单列表与移除；授权时段配置或去掉规则门（按 D3）；Dart 测试 | P1-4、P1-5 | 3；D3/D4 | `flutter analyze` 无问题；新增 Dart 测试绿 |
| **6** | M4 回复闭环（D6=A）或改名退路（D6=B） | P1-6 | 2（不依赖 3/4） | `ProactiveReplyLoopTest` 绿；Dart 测试绿 |
| **7** | M5：`hostdeps` 修复 + rc.2↔rc.4 差异清单 + 阶段 A 非 Electron 宿主；M6：测试改名、`l2s-capture.sh`、断链注入脚本；经负责人许可后跑双 ABI 运行时构建 | P1-7、P1-8、P1-11、P2-7 | — | 阶段 A 的 7 条断言全过；`test_verify_outputs` 绿；fixture 来自真二进制 |
| **8** | Flutter 失败集合按第 8 节的 3×3 方法重跑并入库 | P2-8 | 4、5、6 | 新增失败集合为空 |
| **9** | 重写报告：不硬写 SHA；附 `git ls-remote` 原文；逐表核对行数；每个门槛的证据都附仓库内可执行的命令 | P1-12 | 全部 | 由独立审查方核对 |
| **10** | 真机验收（第 11 节 12 项）——在 APK 构建获得许可之后进行 | — | 9 | 逐项 PASS / FAIL / NOT_TESTED |

第 2、6、7 步之间没有依赖，可以并行；第 3 → 4 → 5 必须串行。

### 12.2 交付物

每一步都要提交：
1. 分支名和 HEAD（`git ls-remote` 原文）
2. 红 / 绿测试输出原文
3. 改动文件列表和行数
4. 本步骤关闭的缺陷 ID
5. 留下的 NOT_TESTED 项和原因

---

## 附：本审查做了 / 没做什么

**做了**：逐行阅读 `loop/core`、`loop/intercept`、`loop/proactive`、`LoopRuntime`、`SnapshotStore`、`L2sMigration`、`DirectLaunch.compatibilityReason`、`hostdeps.mjs`、`live-host/run.mjs`、`build-runtime.sh`、rc.4 锁文件、Dart 拦截通道与设置页、相关测试；核对远端分支 SHA。

**没做**：没有运行任何测试（本环境没有 Android SDK、Flutter 和 Windows 脚本）；Flutter 日志不在仓库里，无法复核 §5 的数字；M2 cap-ledger（门槛 4）不在本轮审查范围。
