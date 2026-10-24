# 素材与工具修复交接

基础：`ae19ab9cd974b658d9459c530064b42adb823287`。分支：`pet-v15/codex-material-review-v4`。版本：**0.3.4 / versionCode 7**。

## 已完成

- 材料正文/证据等级同步更新；Unicode 码点上限；限制提示不再被长链接截掉。
- 新来源事件带可选 expiresAt。旧事件兼容读取；现有旧素材在启动、显式清理及上下文消费时处理过期。读取列表不偷偷改变显式清理计数。
- 过期、删除和内容变更进入来源与对话版本；夜间提交、恢复和日记上下文检查来源有效性。
- 普通记忆模式也能消费材料，不需要先打开连续性开关。
- 记忆工具采用可见性规则并保留猜测标签；读动作不返回旧缓存内容。
- 写动作在同进程绑定执行 ID 与冻结参数；拒绝非法参数/目标/回执，身份记录有界。
- 协作式取消能通知执行器并及时释放等待；系统动作按线程要求调度；锁屏接收不依赖 PetService 已启动。
- 单一惰性 TTS 实例与系统提交回执、超时撤回、停止及关闭处理。
- 分享 CharSequence、草稿状态恢复、后台保存/删除、恢复后列表刷新与按钮换行。
- 生产 ActionPlanner 校验来源实际存在且可用。

## 验证

JDK 17.0.21 / Android SDK platform 35 / Gradle 8.10.2。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

| 项目 | 结果 |
|---|---|
| 原基线 | 217 项通过；初次网络 fixture 关闭失败保留记录，原断言未变 |
| 基线独立复现 | 追加 11 项，11 项失败，0 errors |
| 修复版 JVM | 246 项通过，0 failures / errors / skipped |
| 新增 | ActionBoundary 11、MaterialBoundary 11、SpeechActionAdapter 5、VisibleMemoryReader 2，共 29 项 |
| 原有 217 个测试 | 测试文件与断言均未修改 |
| lint | 0 errors / 20 warnings；主要为依赖/插件版本提示 |
| debug APK / instrumentation APK | 均构建成功；仪器测试未执行 |
| 升级保护脚本 | 3/3，通过 |
| APK 对 | 本环境重建的 0.3.3 → 0.3.4 包名一致、签名一致、versionCode 6 → 7 |
| 设备 | 未连接；无可用 KVM；真机、模拟器、覆盖安装均未执行 |

原 `ae19ab9` 的 GitHub CI success 已核实；不把它算成本次新提交的 CI 结果。本轮独立构建的 APK SHA-256 与统计见 `verification-summary.json`。

## 结果边界

1. TTS 的 SUCCESS 是系统引擎接受提交，不代表用户听到了或播放已结束。
2. 取消停止后续协作步骤及待提交动作；不回滚已经开始的原子写入或系统已接受的操作。
3. ActionRunner 当前为同步 API，安卓调用方必须放后台任务，并在页面退出时取消相应 action ID。没有新增工具 UI；下一轮按 `DEEPSEEK_C.md` 接入。
4. 幂等记录目前在进程内、有容量上限。跨重启需要持久化写动作账本，不能直接开启无人值守的写入循环。
5. 本轮未增加任何模型工具调用、站点登录、Cookie 访问、公开评论、私信或权限。
6. 本次 APK 对的签名一致不代表与手机现有旧包一致。保留用户原应用数据，应在原签名环境重建更新，不能靠卸载旧包解决签名冲突。

## 后续

- 先按 `DEVICE_CHECKLIST.md` 验证 A 的分享与材料使用链路。
- DeepSeek 做 C：工具 UI、作用域取消、持久化写入账本；独立提交后再审查。
- 详细发现及原始行号见 `REVIEW.md`。
