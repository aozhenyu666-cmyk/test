# A4：长期目标与持续记忆（源码完成，设备未验证）

基线：`deepseek/moling-device-a3` / `88eaf04054b383b2891749c73008d53558ba18b3`。
分支：`deepseek/moling-continuity-a4`。版本 0.4.0 / versionCode 13。仅修改 `companion-pet/`。

## 已完成

- `GoalStore`：`goals/state.json` 原子替换，保存 Goal/Task/FollowUp/AgentEpisode 与删除墓碑；损坏文件不覆盖、不假称空库；重启保留；episode 上限 200、目标 50、任务/跟进各 100。
- `GoalSupervisor`：本地计划状态机，输出 `NO_ACTION / ASK_USER / READ_TOOL / PROPOSE_WRITE / EXECUTE_ALLOWED / WAIT / COMPLETE`；不自动执行写入，不替代 ActionRunner。
- `GoalEventRouter`：只返回需要重新考虑的活动目标（到期 follow-up 或 wakeCondition 匹配），不直接执行。
- 设置页“长期目标（实验）”：添加、暂停/恢复、删除目标；显示 supervisor 下一步与原因；不做后台常驻调度或通知。
- 删除目标同时移除任务/跟进/episodes，写墓碑，防止旧调用复活；旧 EventLog/FactStore/MemoryRecall 未被替换，兼容原数据。

## 验证

```text
:app:testDebugUnitTest          PASS   306 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 13 warnings
:app:assembleDebug              PASS   28,845,991 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译
```

APK SHA-256：`EE21C6135F47F66F2DB520B52E85804875EFD9F6DF3BFD384E4E0122C70DB828`

新增 5 个 JVM 用例：目标持久化/暂停/删除墓碑、损坏文件不覆盖、supervisor 五态、follow-up 路由、episode 上限与删除清理。基线 301 个测试保留。

## A4 验收状态

| 项 | 状态 |
|---|---|
| 持久 Goal/Task/FollowUp/Episode + 重启 | **JVM PASS** |
| 崩溃/损坏文件保护 | **JVM PASS**（无突然断电认证） |
| Supervisor 自主循环 | **PARTIAL**：状态机完成，未接入真实 AgentLoop 自动执行 |
| UI 待跟进/暂停/删除 | **源码完成，NOT_RUN 设备** |
| 通知/WorkManager 唤醒 | 未实现（不声称后台常驻） |
| 场景 1–4 真机 | **NOT_RUN** |

## 边界

- 没有自动执行目标；supervisor 只给计划。高风险动作仍需逐次确认。
- 没有把模型输出直接变成 Goal/Task（避免幻觉）；当前目标是用户手动添加。
- 没有通知/精确后台唤醒；不声称 24 小时常驻。
- 无真机/真实 API，所有设备行为未验证。
