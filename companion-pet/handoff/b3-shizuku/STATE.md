# B3：Shizuku 真实窄能力

基线：deepseek/moling-b2-confirmation / 3be7d0a。分支 deepseek/moling-b3-shizuku-real。
版本 0.4.4 / versionCode 17。

## 已完成

- 官方 Shizuku 13.1.5 provider/API 已有；新增 ShizukuCapabilityRunner + ShizukuCommandPort。
- 唯一固定能力：通过官方 Binder 读取 Shizuku 版本（Shizuku.getVersion），无 shell、无参数拼接、无模型输入，不接受任意命令。
- 状态：NOT_INSTALLED/NOT_RUNNING/NO_PERMISSION/READY/UNSUPPORTED；READY 只表示 Binder/授权通过，实际调用结果单独返回 READY/FAILED。
- 设置页“读取 Shizuku 版本”按钮；失败显示短原因，不伪造系统 SDK 或系统命令。
- 没有 root/Sui、没有 exec(String) 入口；Shizuku 不可用时原生 Intent/无障碍仍可用。

## 验证

:app:testDebugUnitTest          PASS   323 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 13 warnings
:app:assembleDebug              PASS   28,927,911 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译

APK SHA-256：E200100214D738DE0747EE32C1B53FECDDADCF277EADFE8CF6AFB5517B677709

新增 3 个 JVM 用例：READY 才调用固定端口、非 READY 不调用、调用失败如实返回。基线 320 保留。

## B3 验收状态

| 项 | 状态 |
|---|---|
| 真实 Shizuku Binder 版本读取 | **NOT_RUN**（无设备/未装 Shizuku） |
| 权限/未运行/断线/重启诊断 | 状态映射与 UI 完成；真实 Binder **NOT_RUN** |
| 受限能力调用结果 | fake port JVM 验证；真实 **NOT_RUN** |
| 无危险 shell | 代码层确认：无 exec(String)、无 root/Sui |

## 边界

- 只读 Shizuku 版本，不是通用系统能力；没有为了凑数引入 shell。
- 无真机，不能声称 Shizuku 已真实调用成功。
- B4 事件触发/成本/6 个设备用例/最终 APK 验收未完成。