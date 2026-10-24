# C1：设备诊断与验收工具

基线：deepseek/moling-c0-audit-fixes / e0e6103。分支 deepseek/moling-c1-diagnostics。
版本 0.5.2 / versionCode 20。

## 已完成

- 新增 DiagnosticReport/DiagnosticInputs：版本、诊断时间、无障碍、截图状态/原因、视觉模型是否配置、上传开关、剩余预算、前台包与白名单、Shizuku 状态与固定能力结果、自主模式、目标存储状态、待确认数量、最近动作。
- 设置页“设备诊断 / 验收”：点击生成脱敏报告，可复制；不包含 API Key、截图/Base64、聊天原文、输入文本、密码。
- 截图本地预览与视觉上传仍是两个动作；上传需显式开关并二次确认（B4/A2 已实现）。
- 复用现有 Settings/CapabilityPanel/轨迹，不重复造面板；没有自动点击支付/删除/发送/登录。

## 验证

:app:testDebugUnitTest          PASS   325 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 14 warnings
:app:assembleDebug              PASS   28,944,291 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译

APK SHA-256：C01D7CB72535073A0532B96A2445C1695B1F6170914FD8F1D861155C5272581B

新增 1 个 DiagnosticReport 用例：包含状态字段、不含 data:image/ sk-/聊天正文。基线 324 保留。

## 边界

- 无真机：诊断页只在 JVM 验证生成逻辑；截图/视觉/Shizuku/白名单真实状态全部 NOT_RUN。
- 没有导出文件；只提供本地复制，避免误上传。
- C2 端到端验收与 C3 主动性未完成。