# DeepSeek V3 素材/工具状态

基线：`pet-v15/codex-foundation-v3` / `cd49e16`；工作分支 `pet-v15/deepseek-material-v3`。
当前阶段：**A 已实现并验证；B 待提交**。

## A 素材收件箱

- 本提交：A；版本 0.3.2 / versionCode 5。
- 测试：209 tests / 0 failures；lint 0 errors / 5 warnings。
- APK：`BDBDE415FC5D62C519A1ED6033C63B6D91CF3538631D52ED8E5A1B5A31CC53EB`（28,190,415 bytes）。
- 设备：分享链路未执行；仪器测试只编译。

## B 受限工具（下一提交）

- 目标：ActionRunner/ToolRegistry，第一批 READ_MEMORY / READ_MATERIALS / SAVE_NOTE / OPEN_ASSISTANT / SPEAK；四态结果、执行 ID 幂等、取消、dry-run 规划。
- 不接无障碍写操作、Shell、评论发送、QQ、私有目录。
