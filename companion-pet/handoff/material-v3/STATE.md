# DeepSeek V3 素材/工具状态

基线：`pet-v15/codex-foundation-v3` / `cd49e16`；工作分支 `pet-v15/deepseek-material-v3`。
当前阶段：**A、B 均已实现并验证**。

## A 素材收件箱（提交 dafe838）

- 版本 0.3.2 / versionCode 5；测试 209 / 0；lint 0/5；APK `BDBDE415FC5D62C519A1ED6033C63B6D91CF3538631D52ED8E5A1B5A31CC53EB`（28,190,415 bytes）。
- 设备分享链路未执行；仪器测试只编译。

## B 受限工具（本提交）

- 版本 0.3.3 / versionCode 6；测试 216 / 0；lint 0/5；APK `463BE1EC0E380FE659368409490114184EA541A0F4697D6655D59C5F0EDB8CD5`（28,223,183 bytes）。
- 5 个第一批动作、四态结果、执行 ID 幂等、取消/cancelAll、参数白名单、dry-run planner。
- 无模型工具调用、无新增权限；真实 App/TTS 未在设备验证，fake 测试不冒充真机。

## 下一步

- 设备：ACTION_SEND 分享链路、收件箱保存/删除、模型上下文；仪器 APK 安装（测试 APK 可能仍被 vivo 拒绝）。
- 若继续 C：工具 UI、模型工具调用（最多 3 次，走 AtomicBudget）、真实 Android adapter 的设备验证。
