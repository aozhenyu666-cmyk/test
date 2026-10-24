# B2：主动提议 → 用户确认 → 执行/拒绝

基线：deepseek/moling-b1-goal-agent / 03f05bc。分支 deepseek/moling-b2-confirmation。
版本 0.4.3 / versionCode 16。

## 已完成

- PendingAction + PendingActionStore：持久化、最多 20 条、TTL 5 分钟、一次性 nonce/status；approve 原子 PENDING→APPROVED，第二次返回 null；拒绝/取消；文件损坏报告错误。
- AgentLoop 在 NEEDS_CONFIRMATION 时写入 PendingAction（kind/args/preview/package/sourceIds/time），不自动执行。
- 设置页“待确认动作”：显示预览和状态；允许本次时重新检查 TTL、前台包名；通过后使用现有 ActionRunner 执行一次并回写 result；拒绝写入 REJECTED。
- 默认不批准；同一 pending 不能双执行；执行仍走 ActionRunner/账本。

## 验证

:app:testDebugUnitTest          PASS   320 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 13 warnings
:app:assembleDebug              PASS   28,911,527 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译

APK SHA-256：8ED61397CB894AC9DBB19DC61FE5F89ED937CC8EBD3D3EC256200E4C0A533E70

新增 3 个 PendingAction 用例：一次性批准/重启状态、前台切换与过期、只对 APPROVED 写回执。基线 317 保留。

## 边界

- 无真机：确认 UI/点击/输入未设备验证，全部相关项 NOT_RUN。
- 进程在 APPROVED 后、执行前崩溃时，当前 UI 不提供“继续已批准项”按钮；同 ID 不会双执行，但需要人工核对。
- 敏感动作仍不能绕过系统/应用保护；没有发送、支付、删除、账号操作。
- B3 真实 Shizuku 能力、B4 设备验收未完成。