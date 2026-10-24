# C 阶段交付状态

2026-10-24，版本 **0.3.6 / versionCode 9**。
工作分支：pet-v15/codex-capabilities-v5。
基线：b1e47e9fbf1824db2ab0d350f6fc37117aea25bb；C1：71970bb87c3fc643cbc2fd50590f52fe3eae5574。C2 为此文档所在提交，可用 git log 查看。

## 完成内容

1. C1：设置 → 能力面板，五个中文操作、保存预览确认、冻结参数、防双击、后台执行、四态结果、只核对原 ID、页面作用域取消。旋转由 ViewModel 保留，离开页面不全局取消。
2. C2：持久化准备/回执记录、SAVE_NOTE 稳定身份与恢复、旧 Markdown 兼容、删除后不复活、最近操作核对。打开应用和朗读中断且无回执时保留未确认，不自动重做。
3. 保留基线全部 246 个 JVM 用例和原断言，新增 18 个，共 264 个；新增 4 个 Android 仪器用例，仅编译。

## 本地验证

JDK 17.0.21+9 / Android compile SDK 35：

| 项目 | 结果 |
| --- | --- |
| :app:testDebugUnitTest | PASS，264 / 0 failures / 0 errors |
| :app:lintDebug | PASS，0 errors / 20 warnings |
| :app:assembleDebug | PASS |
| :app:assembleDebugAndroidTest | PASS，仅编译 |
| python tools/test_verify_upgrade.py | PASS，3 个保护测试 |
| 0.3.5 code8 → 0.3.6 code9 APK 离线检查 | PASS，包名一致、版本递增、当前环境签名相同 |
| 真机/模拟器安装、覆盖升级、TTS、打开应用、锁屏、旋转 | 未执行 |

APK：28,546,581 bytes。
SHA-256：c58b06885861b97d7b9a6ff2a972891fd7c43dd6968676e16c21c0afeb1669ca。

仪器 APK：2,361,938 bytes。
SHA-256：c9c996bd239f4a668525203cf6a9bc4951df486e34d65cd5b7e405d97eb3e940。

机器结果见 BUILD_RESULTS.json、UPGRADE_APK_PAIR.json；恢复细节见 C2_RECOVERY.md；设备待测见 DEVICE_CHECKLIST.md。

## 下一步结论

**可以进入下一轮设备验证。** 当前没有真机通过或 iQOO 已验证结论；也没有进行无人值守自动写动作。设备使用原签名环境的更新包，不能靠卸载/清数据跳过升级检查。现阶段无需再让 DeepSeek 重复实现 C1/C2。
