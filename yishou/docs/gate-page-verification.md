# 入口页面显示验证

这次修复把“已调用启动”和“页面真的到前台”分开。只有 GateActivity.onResume 才记录 GATE_SHOWN；启动后 1 秒仍未显示，就给出全屏无障碍处理入口和通知。用户可以打开处理页、去系统设置或回桌面。关闭服务、离开目标应用或规则取消后移除入口。

本地已用 Kotlin 1.9.24、Android 14 API 类库编译检查新增 PageDelivery/PagePrompt，并通过 PageDeliveryTest 的 3 个 JVM 测试。另与哨兵的 96 个测试一同运行，共 99 项通过。本机没有 Android SDK；Gradle 标准命令在下载分发包时因 Java 未使用会话代理而连接失败，未完成应用构建或真机测试。

CI 应运行 `./gradlew testMainlineDebugUnitTest assembleMainlineDebug assembleChaluDebug`。真机应运行已有 GateIntegrationTest，核验：选中 APP 自动打开做题页；连续打开 20 次；通行到期重新拦截；系统拦下自动跳转时仍有全屏处理入口；关闭无障碍后入口撤除。页面统计应只在真实页面显示时增加。

本改动不会自动启用系统权限。无障碍服务本身断开后无法自己弹页，需已授权且仍运行的独立哨兵处理。没有取得手机日志，尚未确定用户这次无障碍断开的具体原因。
