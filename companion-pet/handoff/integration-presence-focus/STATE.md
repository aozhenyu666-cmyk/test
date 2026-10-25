# 整合：主动陪伴（C3 presence）+ 专注守护（focus guard）预览

日期：2026-10-25。分支：`integration/tongzhuo-presence-focus-v1`，基于 `claude/c3-presence-layer` @ `166a546`（0.6.0-c3 / 27）。
移植来源：`deepseek/c3-focus-guard` @ `12f7382`（基于旧 0.5.4 / a590239，0.5.5 / 23）。**没有整体合并、没有 cherry-pick 整个提交**，逐文件审计后重写/移植。
版本：**0.6.1-preview / versionCode 28**。未合并主线、未写正式 Release；真机全部 NOT_RUN。

## 阶段 1：审计结论（对照 DeepSeek 源码，均已复现并有回归用例）

| 编号 | 是否存在 | 源码位置（12f7382） | 触发过程 | 回归用例 |
|---|---|---|---|---|
| F-01 | 存在 | FocusGuard.kt:95-101；PetBrain `onFocusForeground` 只在 AWAY_GRACE 才调 onConfirmedLeave | ARMED 收到非目标前台落到 `else -> None`，一直 ARMED；后台启动被拦时没有任何事件，永远不守护 | `f01_leavingBeforeTargetConfirmedStillGuards` |
| F-02 | 存在 | FocusController.kt:92-97、159-165 | 宽限到期只 `overlay.show()`，之后状态 BLOCKING，tick 不再动作；自动返回只能靠手点 | `f02_strictAutoReturnsAndVerifies`、`f02_retriesAreBoundedWithAccurateMessage` |
| F-03 | 存在 | FocusGuard.kt:84-88 返回 None；FocusController.kt:171 None→Unit | 用户自己回到目标，拦截层不收；旧重试回调还会继续 | `f03_manualReturnHidesBlockAndCancelsRetries` |
| F-04 | 存在 | FocusController.kt:67、175-177；FocusStore.load 损坏直接 null；abort 删除文件 | 存盘失败仍打开目标并报“已开始”；写失败被吞；损坏无证据 | `f04_persistenceFailureAndRestore` |
| F-05 | 存在且更严重 | FocusOverlay.kt:71、78 | `TYPE_ACCESSIBILITY_OVERLAY` 只能由无障碍服务自己的窗口添加，原实现用桌宠服务 Context，addView 抛异常被吞 → **STRICT 拦截层在真机上很可能根本不出现**，控制器还以为拦了 | `f05_overlayFailureIsReported`（真机仍需验证） |
| F-06 | 存在 | FocusController.kt:143 `launched = true` 硬编码；168 忽略 launch 返回值；140-156 回调只查 `guard != null` | 启动被拒当成“接受了”；旧会话的延迟回调会作用到新会话 | `f06_launchResultIsRealAndStaleCallbacksIgnored` |
| F-07 | 存在 | FocusGuard.kt:80、92、98、111（每次事件更新 updatedAt）；FocusController.kt:135 用 updatedAt 算截止 | 同类窗口事件不断，宽限无限顺延 | `f07_repeatedEventsDoNotExtendGrace` |
| F-08 | 存在 | FocusGuard.kt:118-120 SOFT 也返回 Block；FocusController.kt:162-165 统一全屏 | SOFT 意外变成全屏拦截 | `f08_softOnlyRemindsAndLiveNeverPulls` |

另外发现：DeepSeek 把 PerceptionService 的所有窗口事件改成“防抖后且 active==expected 才记”，会让主动陪伴依赖的轨迹漏事件、卡壳探头从“按页面”变成“按 App”。整合时**保留原轨迹逻辑**，只另加一条防抖确认给守护用。

## 阶段 2：移植与修复

| 文件 | 来源 | 处理 |
|---|---|---|
| core/focus/ForegroundGate.kt + 测试 | DeepSeek | 原样移植（系统窗口/输入法/自身过滤） |
| core/focus/FocusGuard.kt | DeepSeek 设计 | 重写：固定截止时间、ARMED 超时、SOFT 只提醒、STRICT 立即有界自动返回、generation 令牌、墓碑 |
| core/focus/FocusController.kt | DeepSeek 设计 | 重写：先存后开、真实启动结果、核验前台、会话 ID + generation 校验、拦截层结果回执、持久化错误可见 |
| overlay/FocusOverlay.kt | DeepSeek | 重写窗口宿主：优先无障碍服务的 WindowManager，退到悬浮窗，都不行返回失败原因；保留返回/暂停/紧急退出 |
| a11y/PerceptionService.kt、PerceptionHub.kt | DeepSeek 思路 | 只新增防抖确认回调和 activePackage()，不改原轨迹 |
| service/PetBrain.kt | 新接线 | 见下“协调” |
| data/Settings.kt、ui/SettingsTab.kt | DeepSeek 思路 | 设置 → 陪伴 → 专注守护（STRICT 开关默认关、时长 5–180 分钟）；诊断页“专注守护”状态卡 |
| DeepSeek 的 FocusGuardTest/FocusControllerTest | — | API 已重写，未原样移植；它们的场景被 FocusIntegrationTest 覆盖 |
| DeepSeek 的 Settings `sessionAutoStartSec` 20→10 | — | 未采用（改变既有行为，与本轮目标无关） |

## 两条 C3 的协调（单一拥有者）

- 守护有效时，普通会话 `handleSession()` 直接让位：不倒计时、不拉回、不问结束、不因离开太久自动结束；守护结束后普通会话恢复原行为。
- 拉回只有一个入口：守护有效时“回 Gemini”等按钮都走 `FocusController.userReturn()`（同样核验）。
- 主动陪伴让位：守护有效或拦截层显示时，presence 的忙碌原因为“守护”，不出本机提示/模型搭话；微反应也不做（会话期间 tick 本来就不进 presence）。
- 用户“结束会话”/拦截层“结束守护”= 守护和会话一起停；暂停 5 分钟期间不提醒不拉回；熄屏/锁屏不拉回，亮屏先给一个宽限。
- LIVE（一起看）不拉回；本轮入口只建立 TEXT 守护。
- 恢复成功的判定：重新读活动窗口看到目标包名，不看启动请求返回值。

## 阶段 3：测试与构建

- JVM：**420** 个全过 = Claude C3 线 403 + ForegroundGateTest 6 + FocusIntegrationTest 11。DeepSeek 线是 387（365 基线 + 22 守护用例，其中 ForegroundGate 6 个已移植）。
- CI 四项 Gradle：
  - run 82（f6156af）：Kotlin 编译失败——`focusOverlay` 与 `focusController` 两个 lazy 互相引用导致类型推断递归。补上显式类型。
  - run 83（ad2060c）：**全部通过**（testDebugUnitTest / lintDebug / assembleDebug / assembleDebugAndroidTest）。https://github.com/aozhenyu666-cmyk/test/actions/runs/40165399275
- 预览 APK（只写本分支自己的 tag，没有碰 tongzhuo-latest）：0.6.1-preview / 28，
  SHA-256 `2721de382e3c655df8ac521e7d934685d9ec934476f41f7a474e0d10f9b8a12a`
  https://github.com/aozhenyu666-cmyk/test/releases/download/tongzhuo-preview-integration-tongzhuo-presence-focus-v1/tongzhuo.apk
- CI 构件：Actions 运行页里的 `companion-pet-debug-<sha>`。

## 阶段 4：真机手动测试单（全部 NOT_RUN）

| # | 场景 | 预期 | 状态 |
|---|---|---|---|
| 1 | 点桌宠 → 守护着去 Gemini，打开后立刻切到抖音 | 宽限（默认 10 秒）后：SOFT 气泡提醒；STRICT 全屏拦截 + 自动拉回 | NOT_RUN |
| 2 | 后台启动被 vivo 拦（没打开 Gemini） | 6 秒没确认到目标 → 按离开处理；STRICT 拉回失败显示“系统拒绝/没回到前台”，最多 3 次 | NOT_RUN |
| 3 | 切到允许的 App（笔记等） | 宽限 90 秒；再换到别的 App 缩回 10 秒 | NOT_RUN |
| 4 | STRICT 自动返回成功 | 回到 Gemini 后拦截层自动收起；诊断页“已回到 Gemini” | NOT_RUN |
| 5 | STRICT 拦截层显示/隐藏 | 有无障碍时覆盖全屏；只有悬浮窗权限时也能显示；都没有时诊断页写明没能显示 | NOT_RUN |
| 6 | 拦截中自己点回 Gemini | 拦截层收起，不再重试 | NOT_RUN |
| 7 | SOFT 只提醒 | 只有气泡，最多 3 次，不全屏、不自动拉回 | NOT_RUN |
| 8 | 主动陪伴不干扰守护 | 守护期间没有本机提示/搭话气泡 | NOT_RUN |
| 9 | 暂停 5 分钟 / 紧急退出 | 暂停期间不提醒；结束后立即停止，不会被任何自动逻辑重启 | NOT_RUN |
| 10 | 锁屏、亮屏 | 锁屏不拉回；亮屏先给 10 秒 | NOT_RUN |
| 11 | 关掉无障碍 / 撤销悬浮窗 | 不崩；拉回无法核验时如实显示失败 | NOT_RUN |
| 12 | 杀进程重启 | 未到期守护恢复；返回中被杀的恢复为“被拦截、可再试”；结束/过期的不复活 | NOT_RUN |
| 13 | 从 0.5.8 / 26 覆盖安装 28 | 数据保留；没有旧守护文件时一切正常 | NOT_RUN |
| 14 | 输入法弹出、通知栏下拉、权限弹窗 | 不算离开 | NOT_RUN |
| 15 | 守护期间拦截层、桌宠气泡、输入法、系统权限弹窗的层级 | 记录实际遮挡关系（尤其 vivo/iQOO） | NOT_RUN |

## 剩余风险

- 拦截层窗口类型在 vivo/iQOO 上的真实表现未验证（无障碍覆盖层 vs 悬浮窗）。
- Android 后台启动限制：桌宠拉回 Gemini 依赖“后台弹出界面”权限；无障碍服务在前台时通常能启动，但未在真机验证。
- 拦截层吞返回键，但不阻止电源键、通知栏、最近任务；这是有意的安全设计，也意味着不是“无法逃离”。
