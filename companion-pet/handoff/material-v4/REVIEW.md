# 素材收件箱与受限工具：独立复核及修复

审查基线：`ae19ab9cd974b658d9459c530064b42adb823287`，分支 `pet-v15/deepseek-material-v3`。修复分支：`pet-v15/codex-material-review-v4`，版本 **0.3.4 / versionCode 7**。仅修改 `companion-pet/`。

**基线结论：需要先修。修复后：A 可进入下一轮设备分享链路验证；B 的执行 API 可继续接入 UI，但不能据此声称用户已能使用完整工具链。**

## 已核实的证据

- GitHub API 核实：运行 [40046420404](https://github.com/aozhenyu666-cmyk/test/actions/runs/40046420404) 对应 `ae19ab9`，构建任务 success，JDK 17 步骤及测试/lint/APK 步骤成功；没有设备执行步骤。
- 本地基线第一次运行：217 个测试，其中 `cancellationPropagates` 在 MockWebServer 的 `@After shutdown` 超时报错。保留日志与 XML；未改源码或断言重跑，217/217 通过。
- 在独立、干净的 `ae19ab9` worktree 追加 11 个回归：**11 项失败，0 errors**。源码与 init 脚本作为附件保留，能够在基线重跑。
- 修复后：**246 个 JVM 测试，0 failures / errors / skipped**。新增 29 个测试，原有 217 个测试文件和断言未修改。
- JDK 17 / SDK 35 下四项任务成功：`testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`；lint **0 errors / 20 warnings**。
- 原有升级脚本保护测试 3/3 通过；本环境重建的 0.3.3 与 0.3.4 包名/签名一致，versionCode 6 → 7。
- **没有连接设备或可用 KVM，没有运行仪器测试、真机分享、打开 AI App、真实 TTS 或设备覆盖升级。**

下面行号全部指向原始 `ae19ab9`。Java 路径前缀统一为 `companion-pet/app/src/main/java/cn/companion/pet/`，测试路径另列。

## Blocker：0

## High：5，均已修复对应路径

### H1 正文与证据等级可能错配

- **位置：** `core/companion/MaterialInbox.kt:172`、`:174`。
- **证据：** 同链接更新分别取“较长正文”和“更高证据等级”。先存长摘录，再提交短字幕，结果把旧摘录标成 TRANSCRIPT，实际字幕没有保存。基线 `shorterTranscriptCannotRelabelAnUnrelatedLongExcerpt` 失败。
- **修法及结果：** 非空新正文与其类型一起更新；空正文重复分享不会擦掉旧内容。刷新未分享话题，不增加重复卡片或话题。回归通过。

### H2 删除/过期没有覆盖所有派生与在途结果

- **位置：** `core/companion/MaterialInbox.kt:215`；`service/PetBrain.kt:1111`；`core/memory/NightlyStore.kt:173`。
- **证据：** `all()` 仅隐藏过期卡片，源事件仍可见，较晚生成的话题能继续引用它；对话输入版本不包含素材变化；夜间提交可丢掉被删除事件后，仍提交基于旧整批输入生成的日记/信。旧日记回退上下文也没有来源失效检查。
- **修法及结果：** 新来源事件携带可选 expiresAt，EventVisibility 按调用时间使来源及派生失效；读取仍保持只读，显式清理负责落盘 FORGET。素材变化进入对话版本；夜间提交/恢复要求整批来源仍有效；有撤回来源的当天日记退出模型上下文。新增过期、版本、夜间提交和恢复回归通过。
- **边界：** 未绑定来源的历史自由文本不能进行通用语义溯源。本轮不宣称对所有历史文本进行了物理擦除。

### H3 记忆工具及读缓存可绕过遗忘规则

- **位置：** `service/AndroidActionAdapters.kt:34`；`core/action/ActionRunner.kt:55`。
- **证据：** READ_MEMORY 直接返回 live facts，未核对 tombstone/失效来源，且去掉了猜测标签；同 ID 读操作还会永久返回第一次缓存的数据。回归将可见数据从版本 1 改成版本 2，原执行器仍返回版本 1。
- **修法及结果：** 新增 VisibleMemoryReader，统一来源、删除与确认标签，按码点限制输出；读动作不缓存内容，重复读重新取当前快照。写动作仍进行进程内幂等。相关回归通过。

### H4 取消没有送达执行器，等待者也可能一直阻塞

- **位置：** `core/action/ActionRunner.kt:74`、`:91`、`:98`。
- **证据：** cancel 只加一个标记，既不完成共享 future，也不通知 adapter。一个已进入 future.get 的重复调用仍需等待底层返回；执行器没有机会在真正动作前检查取消。已强化测试同步，确认等待线程确实进入等待后再取消，基线失败。
- **修法及结果：** 引入 ActionCancellation 和兼容重载；取消立即结束等待并通知执行器。系统动作排到主线程后执行前再次检查；锁屏即使未启动 PetService 也可取消，关闭 TTS 可只取消播报。
- **边界：** 这是协作式取消，不回滚已开始的原子文件写入或已被系统接受的打开操作。四态结果中的 reason 明确说明这一点；不能解释成“取消后一定从未发生任何效果”。

### H5 TTS 尚未就绪就返回 SUCCESS，且反复创建引擎

- **位置：** `service/AndroidActionAdapters.kt:92`、`:93`；`service/Speech.kt:14`。
- **证据：** 每次创建 Speech 后调用异步 say，随即返回成功；初始化失败或系统拒绝提交时也无法得到失败回执，新实例没有对应关闭路径。
- **修法及结果：** 共用惰性 Speech 实例，增加 SpeechPort 回执。初始化/语音数据不可用返回 UNAVAILABLE，超时撤回待播报内容，取消通知 stop。只有系统引擎接受提交才返回 SUCCESS，reason 明确“不代表已播放完成”。5 个回执协议测试通过。
- **证据等级：** 回执协议使用 fake port 验证；原生实现完成 SDK 编译，真实引擎仍待设备验证。

## Medium：6，均已修复对应路径

### M1 执行 ID 没绑定请求，参数也未冻结

- **位置：** `core/action/ActionRunner.kt:55`、`:129`；`service/AndroidActionAdapters.kt:73`。
- **证据：** 同 ID 换文本/动作会拿到旧结果；可变 args 可在校验后被改写；非法 limit 被静默回退，未知 target 会变成 Gemini；实际长度检查放大了四倍。
- **修法：** 冻结参数，保留请求指纹，拒绝同 ID 串单、非法范围/目标、越界码点数和不匹配的 adapter 回执。身份记录设置上限，读内容不进入缓存。基线及扩展回归通过。

### M2 默认关闭连续性时，材料不进入对话

- **位置：** `service/PetBrain.kt:1100`。
- **证据：** 只有连续性分支使用含 MaterialInbox 的组装器；默认旧路径没有材料块。用户能保存素材，但默认交流路径用不到。
- **修法：** 旧路径也在 USER 数据段加入有界证据块；人设保持不变，不自动打开连续性开关。接线经过构建核对，真实对话表现待设备/模型体验验证。

### M3 UTF-16 截断及长链接挤掉证据提示

- **位置：** `core/companion/MaterialInbox.kt:131`、`:246`。
- **证据：** take 按 UTF-16 单元裁剪；合法 emoji 正文可能被截断或留半个代理项。长 URL 又能占满上下文，挤掉末尾“不能假装知道内容”的提示。基线两项回归失败。
- **修法：** 保存与渲染按 Unicode 码点裁剪；类型、证据等级和限制提示优先保留，空间不足则整项不放入。回归通过。

### M4 存储失败可能部分删除，坏文件可能被覆盖成新库

- **位置：** `core/companion/MaterialInbox.kt:221`、`:297`。
- **证据：** 先删除卡片再追加 FORGET，追加失败时卡片已没了而来源仍有效；读 JSON 失败被当成空列表，下次保存会覆盖原文件。故障回归使用合成文件，不读取用户数据。
- **修法：** 先持久化撤回，再移除卡片；读写失败不覆盖坏文件、不宣称保存成功；消费端安全返回空内容，界面显示错误。删除失败与坏文件保护回归通过。此处不是跨所有文件的通用数据库事务。

### M5 分享与收件箱的 UI 线程、恢复和输入兼容缺口

- **位置：** `MaterialShareActivity.kt:54`、`:125`；`ui/MaterialInboxSection.kt:49`、`:58`、`:104`。
- **证据：** getStringExtra 无法覆盖合法 CharSequence 分享；保存/删除在主线程同步写文件；恢复页面不重新加载列表；编辑状态只 remember；类型按钮使用固定 Row。
- **修法：** 接受 CharSequence；写操作转 IO，增加忙碌状态、错误反馈和防止保存中编辑；分享草稿使用 rememberSaveable；收件箱按 resumeTick 刷新，按钮使用 FlowRow。SDK 构建通过；旋转、大字体和实际分享仍未设备验证。

### M6 Planner 的“真实来源”实际只检查非空

- **位置：** `core/action/ActionRunner.kt:169`。
- **证据：** 任意非空、甚至不存在的 ID 都满足来源条件。
- **修法：** 增加 sourceAvailable 端口；生产 `PetApp.actionPlanner` 按事件是否存在及当前可见性检查，纯逻辑测试验证不存在来源不能通过。接 UI 时应使用生产 planner，不能自行传一个永远为 true 的适配器。

## Low：1，保留为观察项

`app/src/test/java/cn/companion/pet/net/DeepSeekClientTest.kt:46` 的 MockWebServer 关闭曾出现一次超时。测试体不是产品网络失败证据；未改原测试文件重跑通过，最终全量也通过。日志保留。若再次复现，应改进 fixture 的确定性清理，不能增加随意 sleep、吞异常或删除取消断言。

## 尚未交付的部分

- 工具 UI 入口、页面退出后的 action scope、执行中状态和结果展示。
- 跨进程重启的写动作去重及中断状态记录；当前幂等只覆盖同一进程，身份记录有界。
- 模型工具调用与每次尝试的预算预留；当前未新增模型调用。
- 真正的 B站/浏览器分享、系统 TTS、外部 AI App 打开、锁屏和设备升级。

下一轮先做可操作的工具 UI 和持久化写动作账本，再考虑模型驱动。不能把“存在 PetApp.actions”当成用户可用的完整“大脑与手”。
