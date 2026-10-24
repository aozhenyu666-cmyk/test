# 墨灵 A1.5 → A2 → A3 → A4 总交接

日期：2026-10-24。仓库 aozhenyu666-cmyk/test，仅 companion-pet/。
结论：三批源码、JVM 测试、构建、提交、推送、CI 都完成；真机/真实 API 全部未验证。

## 阶段关系

| 阶段 | 分支 | 基线 | 完成 HEAD | 版本 | JVM | lint | CI | APK SHA-256 |
|---|---|---|---|---|---:|---|---|---|
| A1.5 | deepseek/moling-agent-fix-v1.5 | a067710 | a34adf9 | 0.3.7 / 10 | 289 | 0/5 | 40069214726 | 31C605... |
| A2 | deepseek/moling-vision-a2 | a34adf9 | b97627a | 0.3.8 / 11 | 296 | 0/10 | 40071424890 | 74F21A1524195F7E6C574639144C368E44B986CE40F98952BBC9102E9E768A94 |
| A3 | deepseek/moling-device-a3 | b97627a | 88eaf04 | 0.3.9 / 12 | 301 | 0/13 | 40072393435 | 1ECCAF16C858D823BFD9F89332D638B8BBEAB40F59F4F4CBC4E7A56F626E2E63 |
| A4 | deepseek/moling-continuity-a4 | 88eaf04 | 58e9231 | 0.4.0 / 13 | 306 | 0/13 | 40072929254 | EE21C6135F47F66F2DB520B52E85804875EFD9F6DF3BFD384E4E0122C70DB828 |

每阶段独立分支/提交，没有覆盖旧分支或默认分支。

## 已完成的最小纵切片

### A2 视觉
- 原生无障碍截图（API 30+，API 34+ 优先窗口截图）、超时、HardwareBuffer 释放、缩放/JPEG 上限、错误码。
- ScreenObservation 三状态分离；AgentEventLedger 501 条事件账本；事件监听 + 空闲补扫。
- ChatMessage 图片 content blocks；独立视觉模型配置、上传开关默认关、预算、画面内容不可信提示；观察摘要进入 AgentContext。

### A3 设备
- 官方 Shizuku 13.1.5 provider/API，状态/授权薄层；不执行任意 Shell、不 root。
- DeviceController：状态、节点观察、Intent 打开、节点点击/滚动/输入/返回、最近观察；观察 ID/过期/包名/bounds。
- 8 个 ActionKind 接入现有 ActionRunner/ActionSession/账本；policy 检查锁屏、白名单、敏感应用、过期节点、输入长度；UNVERIFIED 不冒充 SUCCESS。
- 能力面板与“允许操作的应用”名单；空名单拒绝全部。

### A4 长期目标
- Goal/Task/FollowUp/AgentEpisode 持久化、原子替换、损坏不覆盖、删除墓碑、重启保留。
- GoalSupervisor 七态本地计划；GoalEventRouter 只路由，不执行；设置页目标添加/暂停/恢复/删除。
- 不复用旧的 EventLog/FactStore 作为第二套档案；未实现通知/后台常驻/自动高风险执行。

## 三个可演示场景

1. 看题记住薄弱点：A2 观察 → 用户确认 → 视觉摘要 → Goal/Task/FollowUp；第二天条件满足时 Supervisor 提示回访。设备未验证。
2. 受限现实动作：A3 选择测试 App 节点 → 点击/输入 → 重新观察核对；不是仅看 API true。设备未验证。
3. 等待后继续：目标等待用户回答/权限/下次进入 App；重启后 GoalStore 保留状态，条件满足才由 Supervisor 提议。JVM 状态机验证；端到端未验证。

## 真机验证缺口

- A2-T1/T2/T3：截图、视觉模型识别、Agent 使用观察，全部 NOT_RUN。
- A3-T1/T2/T3/T4：真实点击/输入/Shizuku 状态/断线/重放，全部 NOT_RUN 或 PARTIAL。
- A4：目标 UI/重启/通知/场景 1-4 NOT_RUN；Supervisor 未接入真实 AgentLoop 自动执行。
- 没有真实 API Key 的视觉请求；没有 Shizuku 安装设备；没有仪器测试实际执行。

## 风险

- A3 的 Shizuku 只做状态/授权，未用 Shizuku 执行系统能力；原生无障碍/Intent 是实际执行路径。
- A4 GoalSupervisor 只是本地计划器，不是完整“连续大脑”；自动执行仍需后续接入。
- 截图上传需用户显式开关；受保护窗口失败不绕过；轨迹不存原图/Base64/Key。
- 所有设备项必须按各阶段 DEVICE_CHECKLIST 重跑，不能把 CI/JVM 当设备通过。

## 给审查 AI 的文件

- core/vision/ScreenObservation.kt、VisionAnalyzer.kt、a11y/ScreenCaptureGateway.kt
- core/agent/AgentEventLedger.kt、AgentLoop.kt、AgentPrompt.kt、service/PetBrain.kt
- core/device/DeviceController.kt、DeviceActionAdapter.kt、ShizukuBridge.kt、a11y/AndroidDeviceController.kt
- core/goal/GoalModels.kt、GoalStore.kt、GoalSupervisor.kt
- 各阶段 handoff/*/STATE.md 与 DEVICE_CHECKLIST.md
## B0-B4 施工结果

| 阶段 | 分支 | 提交 | 版本 | JVM | CI |
|---|---|---|---|---:|---|
| B0 | deepseek/moling-b0-integrity | af71cc1 | 0.4.1 / 14 | 313 | 40079898443 |
| B1 | deepseek/moling-b1-goal-agent | 03f05bc | 0.4.2 / 15 | 317 | 40080607565 |
| B2 | deepseek/moling-b2-confirmation | 3be7d0a | 0.4.3 / 16 | 320 | 40081413757 |
| B3 | deepseek/moling-b3-shizuku-real | c430a1f | 0.4.4 / 17 | 323 | 40081846891 |
| B4 | deepseek/moling-b4-e2e-release | bd85013 | 0.5.0 / 18 | 323 | 40082053011 |

B0 修好 GoalStore 假成功/损坏覆盖、设备前台绑定、事件高水位；B1 接通 Goal→AgentLoop→ActionRunner→episode；B2 加持久 PendingAction 与确认 UI；B3 加固定 Shizuku Binder 版本读取（无 shell）；B4 加设备验收清单与最终 APK（76726046D96ACCD6F9945D3A59A696617A63F91CB2B7E74DB55342BA0D93F3DB）。

真机、真实 API、真实 Shizuku 全部 NOT_RUN；不能把 JVM/CI 当设备通过。
