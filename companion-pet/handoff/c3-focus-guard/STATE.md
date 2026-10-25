# C3 强约束守护与设备执行闭环

日期：2026-10-25。基础分支：ccr-2899d69f-xlzjh6，HEAD a590239。
新分支：deepseek/c3-focus-guard。版本：0.5.5 / versionCode 23。

## 交付内容

### 纯 Kotlin 核心（可测）
- core/focus/FocusGuard.kt
  - FocusContract：sessionId/targetPackage/startedAt/expiresAt/mode/enforcement/allowedPackages/grace/allowedGrace/pause/maxReturnAttempts/retryBackoff。
  - 状态机：DISARMED/ARMED/IN_TARGET/AWAY_GRACE/BLOCKING/RETURNING/VERIFYING/PAUSED/COMPLETED/USER_ABORTED。
  - TERMINAL 状态不会被 onLeave/onGraceExpired 复活。
  - FocusStore：原子写（ATOMIC_MOVE+REPLACE_EXISTING），跨进程恢复。
- core/focus/ForegroundGate.kt
  - 300ms 防抖 + 实际活动窗口确认；过滤系统 UI、输入法、权限框、自身窗口。
- core/focus/FocusController.kt
  - 端口化：FocusDevicePort（launch/activePackage）、FocusOverlayPort、FocusScheduler。
  - start() 先持久化契约再启动；启动失败 abort+clear，不留虚假会话。
  - verifyReturn()：启动后 1s 重新读活动窗口，只有确认真回目标才解除拦截。
  - 重试有上限 + 退避；STRICT 失败保持 BLOCKING。
  - LIVE 模式不自动拉回。

### Android 接入
- a11y/PerceptionService.kt：窗口事件 300ms 防抖，用 rootInActiveWindow 确认前台；过滤 ignored 包；新增 onForegroundConfirmed。
- a11y/PerceptionHub.kt：activePackageReader、activePackage()、onForegroundConfirmed。
- overlay/FocusOverlay.kt：TYPE_ACCESSIBILITY_OVERLAY 全屏拦截层，显示原因 + 返回目标 + 暂停 5 分钟 + 紧急结束；吞掉返回键。
- service/PetBrain.kt
  - focusController/focusOverlay 接线，tick() 每秒驱动到期与宽限判定。
  - 桌宠菜单新增“开始守护并打开”，先建契约再启动目标。
  - pullBack() 在守护生效时走核验闭环；无契约时也 1s 后核验并如实提示失败。
  - start() 恢复未结束守护；stop() 销毁覆盖层，已结束/过期不复活。
- data/Settings.kt：sessionAutoStartSec 默认 20→10；新增 focusEnforcement（SOFT/STRICT）、focusMinutes。
- ui/SettingsTab.kt：严格守护开关、守护时长、自动识别说明。

## 测试

JVM：387 tests / 0 failures（基线 365 + FocusGuard 9 + ForegroundGate 6 + FocusController 7）
Android 集成（已编译，未在真机执行）：FocusGuardInstrumentedTest 2 项（持久化往返、COMPLETED 终态不复活）
lint：0 errors / 15 warnings
APK：29,206,435 bytes，SHA-256 D46296B238EEAB4D199DD6706EED79650A19C2E5D3EA2EE61C022A11DE816AB2

## 关键调用链

用户点“开始守护并打开” -> PetBrain.startFocusGuard() -> FocusController.start()（先写 focus/session.json）-> launch 目标
-> 成功: ARMED；失败: abort + clear + Toast。
PerceptionService 窗口事件 -> 300ms 防抖 -> rootInActiveWindow 确认 -> PerceptionHub.onForegroundConfirmed
-> PetBrain.onFocusForeground() -> FocusGuard.onTargetForeground/onLeave。
tick() -> 宽限到期 -> onGraceElapsed() -> STRICT: overlay.show；SOFT: 提醒式 Block。
覆盖层“返回目标” -> FocusController.returnNow() -> launch -> 1s 后 activePackage() 核验
-> 是目标: 解除；不是: 保持 BLOCKING + 退避重试（上限 5 次）。

## 仍然不可用 / 未验证

- 真机未验证：所有前台确认、覆盖层、拉回、锁屏、vivo 后台启动限制，均为 NOT_RUN。
- Shizuku：本轮未新增任何能力；仍是状态/固定版本读取，Binder 未连接时 UNAVAILABLE。
- 未使用 Lock Task Mode（需要设备策略所有权），未使用 force-stop。
- 未实现“严格 Live”：LIVE 仍默认不拉回。
- 模型不能修改契约/延长锁定/撤销退出。

## 安全边界

- 全屏层只在本应用悬浮窗/无障碍权限已授予时出现，不覆盖锁屏、不抢系统安全界面。
- 提供“结束守护（紧急退出）”，结束后立即停止自动拉回。
- 不阻断紧急电话；不封设置/卸载/权限撤销。
- 不把模型输出拼成 shell；不 force-stop 目标 App。
