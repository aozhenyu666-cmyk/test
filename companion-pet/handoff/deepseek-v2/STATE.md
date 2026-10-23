# DeepSeek V2 状态记录

- 分支：`pet-v15/deepseek-continuity-v2`
- 基线 SHA：`a9cbe7cb55e02ab44d3d64d2245b18fe0da2f24e`
- 当前阶段：**D0 完成**（基线审计已提交）
- 数据格式版本：沿用旧 Markdown 格式；D1a 将引入 `events-v1`
- 实现文件：无（D0 只读审计）
- 真实测试命令与结果：
  - `:app:testDebugUnitTest` → PASS，111 tests / 0 failures / 0 skipped
  - `:app:lintDebug` → PASS，0 errors / 4 warnings
  - `:app:assembleDebug` → PASS，`app-debug.apk` 27.7 MB
  - `:app:assembleDebugAndroidTest` → PASS（仅编译，未执行）
- 未运行：`:app:connectedDebugAndroidTest`（本机 adb 不可用、无设备）
- 已知待办：D1a → D1b → D2 → D3 → D4a → D4b → D5
- 下一条动作：D1a 事件日志 + 固定夜间批次 + 跨日/并发/版本冲突测试
- 本检查点：D0 审计文件 `handoff/deepseek-v2/BASELINE.md`
