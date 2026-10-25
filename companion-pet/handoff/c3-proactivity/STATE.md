# C3：主动性与长期运行（最小纵切片）

基线：ccr-2899d69f-xlzjh6 / a590239，0.5.4 / 22。
分支：deepseek/moling-c3-proactivity。

## 已完成

- 审计后确认主线已有 AgentEventRetry/EventDisposition/retryAt，C3 不另造第二套事件状态机。
- 新增 ObservationPolicy：自动观察默认关；只允许白名单 App、亮屏、5 分钟间隔、每日 20 次；否给出明确短原因。
- 新增 GoalCompletionPolicy：任务全 DONE 只是候选，必须成功标准非空且用户确认，不能凭列表空自动 COMPLETE。
- 设置页增加“自动观察（C3）”状态说明：策略就绪、自动截图执行开关未开放。

## 验证

:app:testDebugUnitTest          PASS   367 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 15 warnings
:app:assembleDebug              PASS
:app:assembleDebugAndroidTest   PASS  仅编译

## 边界

- 没有自动截图/后台常驻/通知；C3 只提供策略和完成判定，不接执行。
- 复用主线事件退避；没有新增第二套状态机。
- 无真机，观察策略/目标完成未设备验证。
- C2 的图片独有视觉、Shizuku、设备动作仍未完成，不因 C3 而视为通过。