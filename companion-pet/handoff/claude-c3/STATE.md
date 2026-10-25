# C3：本机主动性与活人感（隔离分支，未验收）

日期：2026-10-25。基础：`ccr-2899d69f-xlzjh6` @ `b1a9fcb`（0.5.8 / 26，开工前已 fetch 核对，HEAD 未变）。
分支：`claude/c3-presence-layer`。版本 **0.6.0-c3 / versionCode 27**（只在本分支）。
**未合并主线；C2 真机项仍 NOT_RUN；本分支任何内容都没有真机验证。**

## 三层反应

| 层 | 何时 | 花额度 | 实现 |
|---|---|---|---|
| 微反应 | 有意义的状态变化：从信息流回到学习（点头）、连续专注 25 分钟（开心）、一分钟切 4 个 App（看一眼）、信息流 10 分钟（打哈欠）；冷却 30–90 秒随机 | 否 | 复用 Mood + 已有 attention 动画，2.5 秒恢复；不震动、不算开口 |
| 本机提示 | 一分钟切 ≥5 个 App 且 ≥6 次切换；信息流连续 20 分钟（每段一次）；卡壳但没模型额度；半小时第 3 次从学习切回同一信息流但没额度 | 否 | 走原来的 present()/Conversation：回话、忽略升级、× 关闭、写日志全照旧；不震动 |
| 调模型 | 学习页卡壳；半小时内反复切回信息流；到点 **且** 值得（学习场景 / 有待续话题 / 2 小时没交流） | 是 | 走原来的 speak()：同一预算、内容过滤、失败处理；提示词多两行“这次开口的原因（事实）”“今天的前情” |

到点但不值得 → 安静，并按正常间隔顺延（不再一分钟后反复试）。NOTE/MATERIAL 事件只由 AgentLoop + 事件账本处理，协调器不碰，避免同一事件调两次模型。

## 克制（PresencePolicy，代码而非人设）

- 开关“本机主动陪伴（实验）”默认关；关掉时 tick 完全走旧逻辑。
- 主动开口上限 20/天（10/20/30/50 可选），按本地日期跨午夜归零；用户主动发起、纯动画不计。
- 两次主动开口至少隔 3 分钟。
- 连续 3 次 IGNORED（走完忽略升级）→ 30 分钟冷却，冷却中不换句话追问。
- DISMISSED（点 × 或点桌宠收起）→ 30 分钟安静；PAUSED 同样处理且暂停开关本身硬性静默。用户回话/主动找它只清忽略计数，**不缩短**拒绝冷却。
- 锁屏、切去深聊、开始想一想等中断不算反馈。
- Agent 的普通发言在 C3 开启时也受上限/冷却约束（待确认动作照常弹）。
- 安静时段、熄屏/锁屏、排除 App、深聊、想一想、记念头、技能、看一眼、模型请求中都不主动。

## 心情/状态

复用 PetStateStore（精力/兴趣/亲近）。影响行为：精力 < 0.33 时只做低强度表现（看一眼/开心换成打哈欠，不做 attention 动画）；兴趣低时本机提示门槛提高（×0.8–1.3）。被忽略降兴趣、回话升兴趣/亲近；**点 × 不扣分**。不对用户说“我伤心了”之类的话。
修复：PetStateStore 原来替换失败会先删旧文件再重命名；现在同目录原子替换，失败保留旧文件并记 lastError；损坏文件另存备份。

## 当天短期记忆

TodayDigest：只读现有事件日志、待续话题、技能运行记录，挑最近交流 3 条、今天的念头 2 条、未聊完话题 2 条、今天做过的事 2 条（最多 8 行），遵守遗忘/纠正标记。只在调模型前于 IO 线程组装，不新建数据库、不每秒写盘。EXCHANGE 日志区分“（被忽略）”和“（他关掉了）”。

## 调用链

`PetBrain.tick()`（原有每秒 tick）→ 开关开时 `presenceTick()`（每 5 秒一次，内存信号）→ `LocalPresenceEngine.evaluate()` → `PresencePolicy.check()`
→ 微反应 `playMicro()` / 本机提示 `showLocalNudge()`→`present()` / 模型 `speak(presenceReason)`→现有探针流程
→ 结束 `endConversation()` → `PresencePolicy.feedback(INTERACTED|IGNORED|DISMISSED)`。

## 诊断

设置 → 诊断 → “本机主动陪伴统计”：评估次数、小动作/本机提示/调模型数、今天主动次数/上限、连续没回应、冷却剩余、上次反馈、静默原因（只有计数）。

## 模型调用对比（JVM 模拟，不是真机数据）

两小时、每 5 秒评估、学习 20 分钟 / 信息流 15 分钟 / 一分钟乱切交替、9 分钟一次到点：
旧逻辑 13 次模型调用；C3 **7 次**模型调用 + 3 次本机提示 + 9 次微反应（PresenceSimulationTest）。

## 测试与 CI

- JVM 403 个全过（新增 PresenceTest 14 个、PresenceSimulationTest 1 个；原有用例全部保留，断言未放宽）。
- CI 四项 Gradle（testDebugUnitTest / lintDebug / assembleDebug / assembleDebugAndroidTest）：
  - run 75（27ed007）：Android 编译、lint 通过；`DeepSeekClientTest.cancellationPropagates` 在 tearDown 抛 IOException（与 C3 无关的既有用例）。
    原因：模拟响应挂 5 秒，正好等于 MockWebServer.shutdown() 的 5 秒等待，偶发竞态。改为挂 2 秒（取消在收到请求后立即发生），断言不变。
  - run 76（e05886c）：**全部通过**。https://github.com/aozhenyu666-cmyk/test/actions/runs/40159512101
- 预览 APK：0.6.0-c3 / 27，SHA-256 `cd0e50a7bde006641cd3ce72555867346c309d8c1a78433a2d2438e87f398982`，
  https://github.com/aozhenyu666-cmyk/test/releases/download/tongzhuo-preview/tongzhuo.apk（未验收，不是正式版）。

## CI 发布

注意：DeepSeek 的 `deepseek/moling-c3-proactivity`（基于 0.5.4）用的还是旧工作流，推送后把正式直链覆盖成了 0.5.4 / 22（比手机上的 0.5.8 / 26 低，装不上）。
已手动重跑主线构建恢复正式直链。这个保护只有在各分支都带上新工作流后才完全生效；建议主线也合入这一处工作流改动，DeepSeek 以后从主线开分支。

改了 `.github/workflows/companion-pet-android.yml` 的发布步骤（companion-pet/ 之外唯一改动）：
只有主线 `ccr-2899d69f-xlzjh6` 更新正式直链 `tongzhuo-latest`；其他分支发到 `tongzhuo-preview`（标“预览·未验收”），不覆盖正式版。

## 未真机验证（全部 NOT_RUN）

微反应观感与频率；本机提示的时机和打扰程度；忽略/关闭/暂停反馈在真机上的分类；冷却与上限；卡壳与重复模式在真实 App 里的触发；
精力低时动画变化；今天前情进入提示词后的开口质量；Agent 发言受上限约束；夜间安静时段；长时间运行耗电。
