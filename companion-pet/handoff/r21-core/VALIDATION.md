# 验证记录：R2.1 核心接续子集

2026-10-26 UTC。完整需求没有全部实现；下一轮范围见 DEEPSEEK_NEXT.md。

| 项目 | 层级 | 结果 | 证据/边界 |
|---|---|---|---|
| 全量 JVM 测试 | 纯逻辑/本地文件存储集成 | PASS | 504 tests，0 failures/errors/skipped |
| 新增接续回归 | 同上 | PASS | 12 tests，evidence/training-regression.xml |
| Lint Debug | 静态分析 | PASS | 0 errors，31 warnings，保留完整 XML |
| 主 APK | 构建 | PASS | versionCode 32 / 0.7.2-r21-core-preview |
| 测试 APK | 构建 | PASS | 仅编译打包，没有执行 instrumentation |
| APK 签名 | 构建产物验证 | PASS | apksigner verify；沿用公开调试证书 |
| 手机操作/进程回收/强停/设备重启 | 真机 | NOT_RUN | adb devices -l 无设备 |
| 材料导入后自主发问 S6 | 功能验收 | NOT_RUN | 本轮未实现 |
| 输入/录音有限宽限 A27 | 功能验收 | NOT_RUN | 本轮未完成 |
| 五题换休息 S4 | 功能验收 | NOT_RUN | 本轮未接完整结算链 |
| 48 条人工黄金集/模型准确率 | 人工基准 | NOT_RUN | AI 编写的回归用例不能代替人工标签 |
| 无障碍/Shizuku/哨兵/跨应用抢占 | 真机/对端集成 | NOT_RUN | 本轮未进行，不宣称设备监管通过 |

执行了 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`；最终一次构建为 BUILD SUCCESSFUL（1m 8s）。为最后的评分收紧与通用提示修复重新执行，非仅引用修改前结果。机器使用 JDK 17、Gradle 8.10.2、Android SDK 35；代理与 CA 信任配置只在环境中，未写进工程。

按原需求矩阵，本轮仅覆盖 A03/A06/A07/A08/A11/A26/A28/A30/A31/A33/A34/A36 中与本地训练状态、第一道固定示例评分相关的子项。不能把这些子项的通过升级成整条场景的真机通过，更不能声称 S1–S8 全部达标。

主 APK 的大小、SHA-256、签名指纹，以及构建时每个改动源文件的 SHA-256 见 verification.json。源码构建后仅增加交接与证据文件。

候选包为 debuggable 调试包，未做正式发行安全验收。安装覆盖需核对当前安装版本和签名；不得卸载清用户数据来绕过版本或兼容问题。
