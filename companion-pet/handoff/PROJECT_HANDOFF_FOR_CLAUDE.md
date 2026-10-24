# 桌宠 / Companion Pet Android Agent：给 Claude 的全局交接

更新：2026-10-24。仓库：https://github.com/aozhenyu666-cmyk/test 。只改 companion-pet/。
当前分支：deepseek/moling-b4-e2e-release；HEAD：868aedf（代码 bd85013）。
版本：0.5.0 / versionCode 18。B0-B4 源码、JVM、构建、提交、推送、CI 已完成；真机/真实 API/真实 Shizuku 全部 NOT_RUN。

## 启动提示（复制给 Claude）

你是接手桌宠 / Companion Pet Android Agent 的 Claude Code。先读本文件、ALL_STAGES_SUMMARY.md 和各阶段 STATE.md，再读源码。不要 reset/force push、不要覆盖其他分支、不要改 companion-pet/ 之外工程。
先 git fetch --prune，核对 origin/deepseek/moling-b4-e2e-release 和 git status。无设备时只做代码审查、JVM/CI、自动化脚本；所有设备/真实 API/Shizuku 项标 NOT_RUN，不得写成通过。
禁止：打印/上传 API Key、截图、Base64、完整聊天、密码/验证码；启用任意 Shell、root/Sui、静默支付/删除/发送。
重点审查：GoalStore 原子写入、DevicePolicy 前台/节点校验、PendingAction 一次性批准、Shizuku 固定能力、GoalAgentCoordinator 不把 UNVERIFIED 当成功。
改代码时从最新 B4 新建独立分支，保留全部 323 个 JVM 用例，跑四项 Gradle，给出 commit/CI/APK SHA-256。

## 目标与主链路

把定时搭话桌宠升级为可观察、理解、选工具、执行、核验、记忆、追踪目标的 Android Agent。
链路：事件/用户输入 -> 上下文/记忆 -> 模型决策 -> 结构化工具 -> 授权检查 -> 执行 -> 重新观察/回执 -> 记忆/目标状态。人格与能力解耦。

## 阶段与版本

| 阶段 | 分支 | 代码提交 | 版本 | JVM | CI |
|---|---|---|---:|---:|---|
| A1.5 | deepseek/moling-agent-fix-v1.5 | a34adf9 | 0.3.7/10 | 289 | 40069214726 |
| A2 | deepseek/moling-vision-a2 | b97627a | 0.3.8/11 | 296 | 40071424890 |
| A3 | deepseek/moling-device-a3 | 88eaf04 | 0.3.9/12 | 301 | 40072393435 |
| A4 | deepseek/moling-continuity-a4 | 58e9231 | 0.4.0/13 | 306 | 40072929254 |
| B0 | deepseek/moling-b0-integrity | af71cc1 | 0.4.1/14 | 313 | 40079898443 |
| B1 | deepseek/moling-b1-goal-agent | 03f05bc | 0.4.2/15 | 317 | 40080607565 |
| B2 | deepseek/moling-b2-confirmation | 3be7d0a | 0.4.3/16 | 320 | 40081413757 |
| B3 | deepseek/moling-b3-shizuku-real | c430a1f | 0.4.4/17 | 323 | 40081846891 |
| B4 | deepseek/moling-b4-e2e-release | bd85013 | 0.5.0/18 | 323 | 40082053011 |

CI 前缀：https://github.com/aozhenyu666-cmyk/test/actions/runs/ + 运行号。
最终 APK：28,927,907 bytes，SHA-256 76726046D96ACCD6F9945D3A59A696617A63F91CB2B7E74DB55342BA0D93F3DB。

## 架构入口

- 事件/记忆：core/companion/EventLog.kt、FactStore.kt、MaterialInbox.kt、TopicQueue.kt、CompanionContextAssembler.kt；core/memory/MemoryStore.kt、NightlyStore.kt。
- 动作：core/action/ActionRunner.kt、ActionSession.kt、FileActionJournal.kt、PendingAction.kt、DeviceActionAdapter.kt；service/AndroidActionAdapters.kt。
- Agent：core/agent/AgentLoop.kt、AgentModels.kt、AgentPrompt.kt、AgentEventScanner.kt、AgentEventLedger.kt、AgentTraceStore.kt、ModelAgentDecisionSource.kt。
- 视觉：a11y/ScreenCaptureGateway.kt、core/vision/ScreenObservation.kt、VisionAnalyzer.kt；net/ChatTypes.kt（图片 content blocks）。
- 设备：core/device/DeviceController.kt、ShizukuBridge.kt、ShizukuCapability.kt；a11y/AndroidDeviceController.kt、AndroidShizukuProbe.kt、AndroidShizukuCommandPort.kt。
- 目标：core/goal/GoalModels.kt、GoalStore.kt、GoalSupervisor.kt、GoalAgentCoordinator.kt。
- 接线：service/PetBrain.kt（事件监听、1.2s 防抖、空闲补扫、锁屏取消、IO 执行）；PetApp.kt。
- UI：ui/SettingsTab.kt、ui/CapabilityPanel.kt、CapabilityActivity.kt。

## 已完成能力（含边界）

A2 视觉：原生无障碍截图（API 30+，API 34+ 优先窗口截图）、超时/资源释放/缩放上限；ScreenObservation 三状态；图片 content blocks；视觉上传默认关；观察摘要进 AgentContext。没有自动高频截图。
A3 设备：原生 Intent/无障碍节点/手势；DeviceController 状态、观察、打开、点击/滚动/输入/返回；DevicePolicy 检查锁屏/白名单/敏感应用/过期观察/前台切换/节点可编辑/边界；UNVERIFIED 不升 SUCCESS。Shizuku 仅状态/授权。
A4/B1 目标：GoalStore 持久 Goal/Task/FollowUp/Episode；GoalSupervisor 七态；GoalAgentCoordinator 只在真实 task 时调 AgentLoop，只读回执写 WAITING/BLOCKED+episode，不把动作已发出当完成；COMPLETE 仍需成功标准/用户确认。
B2 确认：PendingAction 持久、TTL 5 分钟、一次性 nonce、最多 20；approve 原子 PENDING->APPROVED，第二次 null；前台切换/过期拒绝；设置页允许/拒绝，执行走 ActionRunner。已知缺口：APPROVED 后执行前崩溃，UI 无“继续已批准项”按钮，同 ID 不会双执行但需人工核对。
B3 Shizuku：官方 13.1.5 provider/API；状态 NOT_INSTALLED/NOT_RUNNING/NO_PERMISSION/READY/UNSUPPORTED；唯一固定能力是通过 Binder 读 Shizuku 版本，无 shell/参数拼接/任意命令；READY 只表示授权/Binder 通过。
B4：事件监听+1.2s 防抖+空闲 5s 补扫；视觉手动、上传默认关；WorkManager 不保证秒级/强制停止恢复；6 个设备用例全部 NOT_RUN。

## 明确未验证/风险

- 无真机：设备、视觉、Shizuku、跨 App、覆盖升级全部 NOT_RUN；无真实 API Key 的真实模型请求。
- GoalStore 只做 JVM 故障注入，不是突然断电/硬件认证。
- B1 写动作未自动执行；B2 崩溃恢复缺口；B3 只读版本不是通用系统能力；B4 无通知/常驻。
- 不要声称“持续自主思考”“Shizuku 已集成”“AI 已操作成功”“真机通过”，除非有设备/API/Binder 证据。

## 设备测试最小命令

~~~text
adb devices -l
adb -s <serial> install -r -g _pet-reports/b4-release/app-debug.apk
adb -s <serial> shell am start -W -n cn.companion.pet/.MainActivity
adb -s <serial> shell dumpsys package cn.companion.pet | findstr versionName versionCode
adb -s <serial> shell run-as cn.companion.pet cat files/memory/agent/traces.jsonl
adb -s <serial> shell run-as cn.companion.pet cat files/memory/goals/state.json
adb -s <serial> shell run-as cn.companion.pet cat files/memory/actions/pending.json
~~~

清单：handoff/b4-release/STATE.md、vision-a2/DEVICE_CHECKLIST.md、device-a3/DEVICE_CHECKLIST.md、continuity-a4/DEVICE_CHECKLIST.md、b0-integrity/DEVICE_CHECKLIST.md。证据只记步骤/状态/截图路径，不导出 Key/截图原文/私人聊天。

## 接手建议顺序

1. 审 B0：GoalStore 写入失败/损坏/容量/并发、DevicePolicy 前台/节点绑定、AgentEventLedger 高水位。
2. 审 B1/B2：GoalAgentCoordinator 只写 WAITING/BLOCKED；PendingAction 一次性/TTL/前台校验/执行回写。
3. 审 B3：ShizukuCapabilityRunner 固定能力、无 exec(String)、READY 与结果分离。
4. 审 A2/A3 交叉：observation ID 是否贯穿 AgentContext -> DeviceCommand；视觉摘要只作不可信材料。
5. 跑 B4 四项 Gradle，以 B4 为下一轮基线。
6. 有设备按各阶段 DEVICE_CHECKLIST 跑；无设备保持 NOT_RUN。

## 不要做

- 不卸载/清数据让升级测试通过；不伪造仪器测试执行。
- 不把 fake adapter 成功当真实 App/TTS/点击/Shizuku 成功。
- 不让模型文本直接变 Shell/坐标/包名/节点 ID；必须经 DevicePolicy + 观察 ID + 白名单。
- 不在锁屏/后台/权限撤销后重放旧动作或显示迟到气泡。
- 不删除旧测试/放宽断言凑通过；不 force push/覆盖其他 AI 分支。

## 下一步最值得补

- B2：APPROVED 后崩溃恢复/继续已批准项 UI；参数哈希/来源二次校验。
- B1/B2：写任务经 PendingAction 完整串到 GoalTask/Episode；成功标准/用户确认才 COMPLETE。
- B3：真机验证固定 Binder 能力；如扩展 Shizuku，先证明必要性并固定白名单，不做通用 shell。
- B4：逐 APP 事件观察开关、间隔/每日次数/token 上限、指数退避、通知/WorkManager 交接。
- A2/A3：真机截图/视觉/节点点击/输入，补 instrumentation 与证据包。
