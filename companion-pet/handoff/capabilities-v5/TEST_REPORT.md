# C1/C2 验证报告

基线 b1e47e9，JDK 17 / Android SDK 35，独立 worktree，只修改 companion-pet/。

## 通过的检查

执行完整命令：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python tools/test_verify_upgrade.py
```

最终 JVM 264 项，0 失败、0 错误。保留基线 246 项源文件与原断言；新增 ActionSessionTest 5 项、DurableActionTest 13 项。lint 0 错误/20 警告；本地应用和 instrumentation APK 均构建成功。

## 新增行为证据

| 用例组 | 可验证的行为 |
| --- | --- |
| ActionSessionTest | 双击只安排一次、原参数被冻结、取消排队执行不触发 adapter、新参数使用新 ID、关闭作用域与晚到结果、恢复只查、不可用、取消历史核对不取消另一所有者 |
| DurableActionTest：回执 | 重建后回放同一回执、换参数拒绝、账本没有正文、冷启动列出未确认操作 |
| DurableActionTest：故障 | 准备后中断不执行、念头已写但事件未写时恢复、写效果后丢回执时核对、外部打开/朗读不重放 |
| DurableActionTest：并发/取消 | 两个执行器争取同一写 ID 不重复保存；念头已落盘时取消不把实际效果说成没发生；关闭排队核对不再启动修复 |
| DurableActionTest：边界 | 容量满保护旧身份、存储不可写不执行、损坏账本不清空、删除/遗忘后只读结果刷新、不持久化只读正文 |
| DurableActionTest：兼容 | 旧 Markdown 与带 ID 念头一起列出/摘要；删除一条不复制或损坏另一格式；删除后正文不复活；悬浮层旧撤销语义保持 |

关键切片先建立失败证据再修复。除接口初建时的预期编译失败外，实际行为失败并修复的案例包括：只核对 ID 时不能恢复已有念头回执、关闭历史核对误取消另一所有者、关闭排队核对后仍开始补写事件。未删除失败用例或放宽断言。故障点与并发栅栏为确定性触发，不用长 sleep。

## Android 与升级的界限

新增仪器测试：CapabilityPanelTest 2 项、CapabilityRotationTest 1 项、DurableActionInstrumentedTest 1 项。前两类使用合成 adapter 验证 UI 接线和宿主重建；后者仅使用随机 cache 目录的合成文件，验证 Android 文件系统上的稳定身份。全部仅编译，未执行。

升级脚本的 3 个保护测试通过。对本环境 C1、C2 APK 使用 --check-apks 验证包名、code8→code9 和证书一致，输出见 UPGRADE_APK_PAIR.json，其中 device_upgrade 为 not_executed。该结果不覆盖用户另一台构建机的签名，也不代表真实覆盖升级成功。

adb 无已连接设备，没有模拟器/真机运行、没有 iQOO 证据。下一步按 DEVICE_CHECKLIST.md 执行；无需把本轮代码任务重新交给 DeepSeek。
