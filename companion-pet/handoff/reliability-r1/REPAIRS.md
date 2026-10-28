# 最初审查问题的可靠性修复对照

日期：2026-10-28。主线：`claude/r21-strict-takeover`。
版本：**0.8.4-reliability-preview / versionCode 44**。
源码：`540fc61fe51fc53faa12df5fd23dc1d949328e14`；主体修复 `d398a60`，对话坏文件保护补丁 `8867e7b`，响铃/紧急拨号让出补丁 `540fc61`。

本轮以最初审查发现的缺陷为依据，重点修动作证据、输出争抢和持久化。伙伴 v1 的功能接线记录仍见 [前轮交接](../partner-v1/IMPLEMENTATION.md)。

## 问题与处理

| 原问题 | 修复与可检查行为 | 证据边界 |
|---|---|---|
| P0 阅读不足半小时被每秒 tick 重置；异步写盘过密 | 前轮已修，本轮复核：累计不足一档的余量，没有状态变化不写；一小时秒级 tick 只产生两次进度变化 | 原有 `PartnerIntegrationTest.oneSecondTicksAccumulateReadingWithoutOneWritePerTick` PASS-L |
| P0 未知篇幅永远阻止下一篇 | 前轮 `ReadingEvidence.blocksNextSearch` 已修；未知长度条目超过检索间隔不再永久阻塞。实际只拿到搜索摘要时明确写“尚未读取正文” | 原有回归 PASS-L；真实搜索 API 尚未用 Key 测试 |
| P0 想说的内容在实际说出前就被消费 | 前轮已有成功输出后消费；本轮进一步补悬浮气泡的绘制回执，窗口 API 接受不等于已显示。绘制超时、移除、被新气泡替换均不确认；只有最终措辞进账本 | `PartnerDelivery` 旧回归 PASS-L；Android OnDraw 接线 WIRED，真机 NOT_RUN |
| P1 / D02 最近守护动作回执写死 null | `FocusActionEvidence` 区分请求、API 受理、独立观察、失败、未知。拉回要再次读目标前台并确认遮罩已移除；证据 30 秒过期，重启不复用 | 新增 Focus 集成测试 PASS-L；实际手机效果 NOT_RUN |
| D02 全屏层“addView 成功”冒充拦截有效 | `FocusOverlay.observe` 读取实际附着、绘制、可见范围。绘制回执只说明全屏层完成绘制，明确不代表已经验证触摸拦截 | 绘制/附着状态协议 PASS-L；真实触摸阻断 NOT_RUN |
| 无障碍窗口事件遗漏时守护状态停留在旧前台 | 有效守护期间每两秒独立采样应用窗口；应用窗口选择排除无障碍遮罩与输入法，避免把桌宠自己的遮罩当作目标前台 | 漏事件后由独立采样推进的集成回归 PASS-L；多窗口/ROM 表现 NOT_RUN |
| 锁屏或来电时尚有返回核验、遮罩 | `interruptInteractive` 作废在途返回代数、移除遮罩，不延长契约；响铃、通话、网络通话与紧急拨号界面走非交互路径；实际打开/显示前也检查让出条件 | 中断后不核验、不拉回、不延长回归 PASS-L；真实通话/系统界面 NOT_RUN |
| D07 旧路径直连气泡，当前问题容易被覆盖 | 所有 `PetOverlay.showBubble/updateBubbleText` 经过所有权检查；训练、思考、外部会话、技能使用各自 owner；同一训练使用完整实例 ID | ScopedOutput 回归 PASS-L；各功能组合真机 NOT_RUN |
| 旧提示几秒后把新聊天收起 | 气泡代数绑定延迟收起、打字完成和按钮动作；新内容替换后，旧回调不能再作用于它 | `OutputLifetime` 回归 PASS-L；服务 ticker 不受 UI 定时器影响 |
| 旧键盘窗口迟到答案被送给新题 | `InputBridge` 为每次输入发独立票据；旧提交、旧取消、重复提交均不能消费新票据。普通气泡“打字”也接到正常 InputActivity | 5 条 InputBridge 回归 PASS-L；输入法/返回原 App NOT_RUN |
| 重启同时恢复两个互斥回合 | 恢复训练与严格回合时检查 SessionArbiter 的拒绝结果，保留未显示的原断点；当前回合结束后再尝试接续 | WIRED；真实杀进程恢复组合 NOT_RUN |
| 上轮看图观察不落盘接线遗漏 | 增加 `DialogueRules.addObservation`，实际入口统一标为 ephemeral；存盘与摘要压缩都排除完整视觉观察 | 实际观察入口 + DialogueStore 回归 PASS-L；截图字节一直不落盘 |
| 对话写盘失败被吞掉，用户以为能恢复 | 对话/话题写盘失败显示在聊天页与诊断；坏对话文件先备份，备份失败则禁止覆盖原文件；处理异常保留用户输入并释放请求占用 | 坏文件备份与失败后不得覆盖回归 PASS-L；异常处理/UI 接线 WIRED |
| 后台成本 | 守护快照未变化时不再每秒重写；骑行明确安静或被阻断时释放唤醒锁 | 120 次空闲 tick 不增加写盘回归 PASS-L；实际功耗 NOT_RUN |

## 测试与构建

- 原有 **795** 条测试未修改；本轮新增 **28** 条，总计 **823** 条，0 失败、0 错误、0 跳过（PASS-L）。
- 新增测试文件：`FocusEvidenceIntegrationTest`（11）、`FocusActionEvidenceTest`（3）、`ScopedOutputTest`（5）、`InputBridgeTest`（5）、`ObservationPrivacyTest`（4）。
- 本地已通过单元测试、lint、普通 APK 与 Android 测试 APK 构建；最后的键盘接线与坏文件补丁也已编译并通过全部 823 条单测。
- 第一轮云端完整检查：[40651943589](https://github.com/aozhenyu666-cmyk/test/actions/runs/40651943589)，源码 `d398a60`，成功。
- 补丁云端完整检查：[40652585644](https://github.com/aozhenyu666-cmyk/test/actions/runs/40652585644)，源码 `8867e7b`，成功。
- 最终云端完整检查：[40653298790](https://github.com/aozhenyu666-cmyk/test/actions/runs/40653298790)，源码 `540fc61`。**成功**：`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 与分支预览发布均通过。
- 下载：[主线预览 APK](https://github.com/aozhenyu666-cmyk/test/releases/download/tongzhuo-preview-claude-r21-strict-takeover/tongzhuo.apk)。已独立下载复核：**30,848,627 字节**，SHA-256 **`d4295f9fb7f44f5a327c34bca9c65f860eed623888b894b418e4d432bb498af3`**，与 Release 说明及资产 digest 一致。`aapt` 确认 vc44 / 版本名；`apksigner verify` 通过，证书 SHA-256 `9f3a524c66974aeb04045f498f83b5970589f9fba3ac3772e95c26e3524695e0`，沿用原固定测试签名。

## 真机必须继续核对的部分

1. 严格守护切出目标：遮罩实际显示、触摸是否阻断、返回目标后遮罩是否移除。诊断应分别显示 API 受理与观察回执，不能仅凭绘制回执声称拦截已成功。
2. 返回被系统拒绝、无障碍断开、来电、锁屏、暂停、到期与紧急退出。旧回调不能重新拉起或覆盖当前内容。
3. 训练/严格回合、主动聊天、技能进度、来信等组合切换。旧提示到时不能关闭新气泡，旧键盘答案不能进入新题。
4. 手动看图后在同一会话追问；完整观察可在当前内存对话中使用，重启后不应把它作为已保存的画面继续声称可见。
5. 一小时主动交流、长时间后台存活、耳机语音与真实视觉理解质量。这些仍没有真机证据，全部为 NOT_RUN。

## 本轮没有据此作出的结论

- OnDraw/可见范围不等于已验证 Android 的实际触摸拦截；TTS onStart 也不等于用户确实听到。
- 单次独立回执不证明长期监管可靠，诊断已明确区分；Binder/Shizuku、厂商后台限制仍需设备验收。
- 主调度器仍较大，本轮集中输出边界与观察协议，没有声称已经完成整个调度器的模块化重构。
- Governor 小时统计和说过的话账本仍是进程内状态，重启重新累计。
- 保留固定公开调试签名以兼容现有安装，仍为 debuggable 个人测试预览包。安全正式发行需要独立签名管理、升级迁移和真机发布验收。
