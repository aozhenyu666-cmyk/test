# B0：可靠性与授权边界

基线：`deepseek/moling-continuity-a4` / `1bde879`。分支 `deepseek/moling-b0-integrity`。
版本 0.4.1 / versionCode 14。仅修改 `companion-pet/`。

## B0.1 目标存储

- 新增 `GoalStoreStatus`：OK / EMPTY / CORRUPT / READ_ONLY / IO_FAILURE；损坏文件不再伪装成空目标，且不会被下一次写入覆盖。
- 写入返回 Boolean：临时文件 + fsync + ATOMIC_MOVE/REPLACE；失败保留旧文件，不先删除；UI 显示 `lastError`。
- 活动目标/任务/跟进达到上限时**拒绝新增**，不再 `takeLast` 静默逐出；只裁旧 episode。
- 测试：损坏、rename 失败保留旧文件、容量、20 线程并发、episode 上限不裁活动目标。

## B0.2 设备动作绑定当前状态

- `DevicePolicy` 增加前台包必须等于观察包；输入节点必须存在且可编辑；节点边界有效；锁屏/白名单/敏感应用/过期观察继续拒绝。
- `AndroidDeviceController.findById` 重新查找节点并核对包名、clickable/editable、bounds；页面变化返回 null，不按旧坐标点击。
- `UNVERIFIED` 仍只映射为 `UNAVAILABLE`，不提升 SUCCESS。

## B0.3 事件幂等

- `AgentEventLedger` 增加持久化高水位 `event-cursor.txt`；有界 ID 记录被裁后，旧事件低于高水位不会再触发。
- `AgentEventScanner` 同时检查已处理 ID 与高水位；测试 3 条容量 + 高水位跳过旧事件、新事件仍可处理。
- 限制：真正的“待处理队列/用户确认/可重试”状态未单独持久化，B1/B2 继续补。

## 验证

```text
:app:testDebugUnitTest          PASS   313 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 13 warnings
:app:assembleDebug              PASS   28,845,991 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译
```

APK SHA-256：`37324906C260D065D3E775B9CD2AE496D3A064889F78431807889AF63F6A6890`

新增 7 个 JVM 用例（B0 合计）：存储损坏/替换失败/容量/并发/episode、设备前台切换/输入形状、事件高水位。基线 306 保留。

## 边界

- 无真机：设备策略与 Binder/无障碍未实测，全部相关项 NOT_RUN。
- 进程突然断电/文件系统损坏只做确定性 JVM 故障注入，不是硬件认证。
- 未实现 B1 Goal→Agent 自动链路、B2 确认 UI、B3 真实 Shizuku 能力、B4 设备验收。
