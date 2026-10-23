# companion-pet 独立代码审查

结论：**需要先修**。打开记忆页会执行破坏性写入；事实纠正、遗忘、日志恢复和夜间提交还存在数据一致性问题。

审查对象：`aozhenyu666-cmyk/test`，分支 `pet-v15/deepseek-continuity-v2`，代码严格固定为 `4a19657fefbe2f8d35d10d2a22a6343654a47f30`。只审查 `companion-pet/`。下述行号均属于该提交。

已先阅读指定三份交接文档。指定提交中没有 `GPT_CLOUD_REVIEW.md`；该文档从后续交接文档提交 `2f30fe20611fa7eeabfcf30823a8cc3010acb1bf` 读取，未将后续提交用于构建。`DEVICE_TEST_RESULTS.md` 与 `TEST_REPORT.md` 从指定代码提交读取。后者标注的旧代码提交为 `0fdb6cfd...`，不能据此认定本次基线的设备修复已验证。

本次没有连接或运行真机、模拟器，没有运行 connectedDebugAndroidTest；不作“真机通过”或“iQOO 已验证”的结论。没有安装插件，没有读取 API Key、Cookie 或私人聊天；复现数据全部人工合成。产品源码和原有测试未修改。

## 实际检查结果

执行环境：Linux，Temurin **17.0.21+9**；Gradle **8.10.2**；Android SDK **platforms;android-35（API 35，revision 2）**。项目 compileSdk/targetSdk 均为 35。已安装 Build Tools 35.0.0；项目未指定 buildToolsVersion，AGP 8.7.3 自动安装并使用其默认的 34.0.0，未改项目构建配置。

原始命令（退出码 0，BUILD SUCCESSFUL，5m 55s）：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

| 检查 | 本次独立结果 |
|---|---|
| testDebugUnitTest | 20 个测试类，162 tests，0 failures，0 errors，0 skipped |
| lintDebug | 0 errors，19 warnings |
| assembleDebug | 构建成功；28,010,039 bytes |
| assembleDebugAndroidTest | 构建成功；2,304,305 bytes；仅构建，未执行设备测试 |
| 仓库外独立回归 | 11 tests，9 failures，0 errors，0 skipped；2 个对照用例通过；退出码 1 |
| 产品源码/原有测试 diff | 无；审查结束 git status clean |

lint 的 19 个警告为 ApplySharedPref ×1、AndroidGradlePluginVersion ×3、GradleDependency ×14、SwitchIntDef ×1，其中 17 个属于版本更新提示，不能照搬旧报告的“5 warnings”。也不应为了消除 ApplySharedPref 警告把预算写入改成异步 apply；预算需要检查同步写入是否成功。

Debug APK SHA-256：`869a7f7e32f94885bfee77c9e802b37d6cbe4c037936cbcf11d81103bbe7b0f0`。

Instrumentation APK SHA-256：`791a65b320d98ef0af7f6522ab3de95e8b470aa8853a01959d32c0382fc82b0b`。

独立回归的 9 项失败分别对应 H1、H2、H3（4 项）、H4、M1、M2，不是原有 162 个测试失败。B1、M3、M4 是注明证据边界的静态审查发现。失败 XML、日志和回归源码均保留，未修改任何原断言。

发现计数：**blocker 1 / high 4 / medium 4 / low 0**。

## Blocker

### B1 — 打开记忆页即清空当天事件，还可能形成持续重组/清空循环

- **位置：** `companion-pet/app/src/main/java/cn/companion/pet/ui/MemoryTab.kt:142`；同文件 `:73`、`:90`。
- **证据：** `io { app.events.clearDay(LocalDate.now()); memory.todayFile.delete() }` 直接写在 `@Composable` 的 `Column` 内容体中，没有按钮或用户回调。页面组合立即启动清空任务；`io` 完成后 `reload++`，该状态又作为 `LaunchedEffect` 的键参与页面组合，可能再次执行清空。`EventLog.clearDay` 删除当天所有事件类型及归档标记，不只是屏幕上显示的交流行。该路径不受连续性开关控制。
- **影响：** 查看记忆本身就会删除当天原始记录，破坏后续召回、话题来源及夜间整理。页面停留时新增记录也可能被再次删除。
- **建议修法：** 移入明确的“清空当天记录”操作和确认回调；组合阶段保持只读。增加 Compose 回归：进入记忆页、触发重组、切换返回，都必须保持文件内容和事件数不变；只有确认清空才允许删除。
- **证据等级：** 静态确定性控制流；本次未运行设备 UI 测试。当前 BootstrapSmokeTest 只检查开始页文字，覆盖不到此行为。

## High

### H1 — 夜间提交与用户编辑不共用锁，可把已确认的档案覆盖回旧版本

- **位置：** `companion-pet/app/src/main/java/cn/companion/pet/core/memory/NightlyStore.kt:74`、`:132`、`:141`、`:214`；`companion-pet/app/src/main/java/cn/companion/pet/work/NightlyWorker.kt:58`；`companion-pet/app/src/main/java/cn/companion/pet/ui/MemoryTab.kt:91`。
- **证据：** UI 编辑持有 `app.memoryLock`，NightlyStore 使用自己的 `lock`；Worker 的 `commit`、`prepare`、`recover` 没有持有共享锁。提交先校验 profileVersion，随后重新读取目标文件生成 beforeHash。在两步之间用户修改档案，新的用户内容会被当成允许覆盖的 beforeHash，后续检查因此仍通过。文件级“检查 hash → rename”也不是跨调用方原子操作。
- **复现：** `userConfirmationBetweenVersionCheckAndJournalSnapshotIsNotOverwritten` 在版本检查后、生成 journal 目标前插入真实的 `confirmProfileLine` 操作；最终应为 `[他说过]`，实际被覆盖回 `[我推测]`，提交返回 `COMMITTED`。通过惰性输出列表实现可控交错，不依赖概率竞争；这是 JVM 时序复现，不是设备并发实测。
- **建议修法：** 让所有 MemoryStore 写入与 NightlyStore 的准备快照、校验、journal 落盘、提交、恢复共享同一串行事务边界；网络请求留在锁外。beforeHash 应来自已验证快照，不能把校验后出现的新用户版本当作提交基线。补充真实多线程栅栏测试及共享临时文件写入竞争测试。

### H2 — 用户更正的事实会被下一轮旧档案同步改回去

- **位置：** `companion-pet/app/src/main/java/cn/companion/pet/ui/MemoryTab.kt:128`；`companion-pet/app/src/main/java/cn/companion/pet/core/companion/FactStore.kt:50`、`:68`、`:107`、`:140`。
- **证据：** UI 更正仅修改 facts.json，不更新 profile.md 或来源版本。`correct` 保留旧 factKey，而默认 factKey 是旧文本生成的键。夜间仍以旧 profile 为输入；旧行同步回来时命中同一个 key，反过来 supersede 用户刚确认的新版本。此操作也没有改变 NightlyStore 用来判冲突的 profileVersion。
- **复现：** `userCorrectionSurvivesNextProfileSync`：先从档案同步“喜欢合成测试猫”，更正为“现在喜欢合成测试狗”，再按现有夜间路径同步未变的档案；存活事实变回旧文本，新事实 ID 不再存活。原有测试仅断言更正当下的版本替代，没有继续同步档案。
- **建议修法：** 建立统一的事实身份与版本来源；更正必须同步更新旧档案映射或使旧来源失效，并提升夜间输入版本。明确用户显式更正优先于旧摘要，不能仅靠整段文本 hash 决定替代关系。回归应覆盖更正后整理、重启、召回及开关回退。

### H3 — “忘记”未贯穿上下文、话题及 journal 恢复，旧内容仍会被使用或写回

- **位置：** `companion-pet/app/src/main/java/cn/companion/pet/ui/MemoryTab.kt:131`；`companion-pet/app/src/main/java/cn/companion/pet/core/companion/CompanionContextAssembler.kt:107`；`companion-pet/app/src/main/java/cn/companion/pet/core/companion/MemoryRecall.kt:55`；`companion-pet/app/src/main/java/cn/companion/pet/core/memory/NightlyStore.kt:97`、`:184`；`companion-pet/app/src/main/java/cn/companion/pet/core/companion/TopicQueue.kt:53`。
- **证据：** 忘记只软删单条事实并添加文本 tombstone。最近交流完全不检查 tombstone；召回/夜间只比较整条事件文本，带时间、用户/回复前缀的 EXCHANGE 不会匹配原事实 tombstone。话题队列不检查已忘记的来源。恢复会直接重放旧 journal 的 afterText，没有依据新的删除版本使事务失效；之后的事实同步过滤，无法撤销已经写回的 profile.md。旧开关回退路径 `PetBrain.kt:1092` 也直接使用 profile 和最近交流。
- **复现：** 四个独立用例分别覆盖最近上下文、夜间交流段、待分享话题、journal 写入后忘记再恢复。人工合成的被忘记文本分别仍出现在请求上下文、夜间输入、可分享话题，以及恢复后的 profile 中。夜间输入断言只检查“今天的交流记录”段，未把“已删除条目”提示段误判为失败。
- **建议修法：** 用稳定事实/来源 ID 和删除版本统一过滤所有消费路径，使相关事实版本、派生交流、话题、摘要及活动会话失效。恢复前重新核对删除版本；删除发生后应废弃或安全重算旧 journal。不要仅在最终 facts 同步处过滤，也不要依赖模型遵守删除提示词。

### H4 — JSONL 尾行损坏后，下一条成功追加的事件也会丢失

- **位置：** `companion-pet/app/src/main/java/cn/companion/pet/core/companion/EventLog.kt:79`、`:152`；`companion-pet/app/src/test/java/cn/companion/pet/core/companion/EventLogTest.kt:49`。
- **证据：** appendEvent 无条件在文件末尾追加 JSON 和换行。如果上次崩溃留下未换行的半个 JSON，新完整事件会紧接其后，成为同一条非法 JSONL 行；readFile 将整行静默跳过。append 调用却已返回成功。
- **复现：** `appendAfterTruncatedTailPreservesNextCompleteEvent`：完整事件一 → 无换行坏尾行 → 重建 EventLog → 追加完整事件二。预期读到两个成功事件，实际只读到第一个。原有 `partialLastLineIsIgnored` 只测读坏尾行，没有继续追加；干净文件重启后追加的对照用例可通过。
- **建议修法：** 在同一写锁内检查尾部边界，隔离/修复不完整尾行后再写新事件；保留此前完整事件，不能静默吞掉新的成功写入。追加与重新读取一起纳入故障恢复回归。

## Medium

### M1 — 新事件以未衰减状态为基数，抹掉经过时间造成的衰减

- **位置：** `companion-pet/app/src/main/java/cn/companion/pet/core/companion/CompanionState.kt:48`、`:72`。
- **证据及复现：** `current(now)` 返回衰减值但不落盘；`apply` 却直接 `read()` 加增量并更新时间。`eventAppliesToDecayedStateAfterRestart` 中，亲近值 0.41 放置 24 小时后应衰减到 0.35375；再回复应为 0.41375，实际为 0.47。原有测试分开验证衰减、事件和重启，未验证组合时序。
- **建议修法：** `apply` 先按 now 对存储状态执行同一衰减函数，再施加事件增量并落盘；增加长间隔、重启后第一事件和连续事件回归。

### M2 — 普通搭话不断重置话题冷却，待分享队列可能长期无法出队

- **位置：** `companion-pet/app/src/main/java/cn/companion/pet/service/PetBrain.kt:225`、`:229`、`:334`；`companion-pet/app/src/main/java/cn/companion/pet/core/companion/TopicQueue.kt:104`；`companion-pet/app/src/main/java/cn/companion/pet/core/rhythm/SpeakScheduler.kt:8`。
- **证据：** 话题要求距上次主动搭话至少 30 分钟；普通搭话间隔默认 6–12 分钟。即使没有通过话题门控，服务仍正常 speak，成功后仍更新 lastProactiveAt。持续使用且模型正常开口时，下一次检查又只过去 6–12 分钟，已有队列可能始终被挡住。
- **复现：** `schedulerAndTopicGateDoNotStarveQueuedTopic` 使用真实 SpeakScheduler 和 TopicGate，按 PetBrain 的调用顺序模拟十次成功的普通搭话，队列一直没有机会选中。该用例验证组件接线的时序，不是直接运行 Android Service。
- **建议修法：** 将“上次分享排队话题”与“上次普通开口”区分，或统一调度到明确的下一次话题资格时刻；加入持续活跃场景下话题在有界时间内被选中的回归，仍遵守安静时段与总请求预算。

### M3 — 忽略预算落盘失败，预留可能在重启后丢失

- **位置：** `companion-pet/app/src/main/java/cn/companion/pet/data/BudgetPrefs.kt:14`；`companion-pet/app/src/main/java/cn/companion/pet/core/probe/AtomicBudget.kt:28`。
- **证据：** SharedPreferences.commit() 的 Boolean 返回值被丢弃，AtomicBudget 随后无条件返回 true。若存储写入失败，请求仍可发出；本进程的内存值与重启后磁盘值可能不一致，重启后会再次获得已消费额度。现有预算测试用永远成功的内存持久化实现，不能验证这一故障路径。
- **建议修法：** 持久化端口返回成功状态或抛出可处理错误；确认耐久写入成功才允许发请求，失败时明确阻止调用并保留诊断。增加持久化失败和重启后额度不增加的测试。本项为静态 API 契约审查，未做 Android 磁盘故障注入。

### M4 — 启动页冒烟测试的子串选择器在未授权环境下匹配多个节点

- **位置：** `companion-pet/app/src/androidTest/java/cn/companion/pet/BootstrapSmokeTest.kt:21`；`companion-pet/app/src/main/java/cn/companion/pet/ui/StartTab.kt:82`、`:134`；`companion-pet/app/src/main/java/cn/companion/pet/ui/components/CompanionUi.kt:48`。
- **证据：** 干净安装未授予悬浮窗权限时，页面同时显示“先开悬浮窗权限，它才能出现。”和权限项“悬浮窗”。`onNodeWithText("悬浮窗", substring = true)` 要求单个匹配节点，会得到多个候选；已有权限的设备又可能绕过这个条件。测试没有控制初始权限状态。
- **建议修法：** 用权限项的稳定 testTag 或精确文本/父节点限定进行唯一定位；分别覆盖未授权与已授权状态，并保留可见性及跳转断言，不改成“随便找到一个就算通过”。本次只完成代码审查与仪器 APK 构建，未声称该测试在设备上实测失败。

## Low

没有额外列出证据不足或纯风格类问题。

## 覆盖与证据边界

- **D1：** 事件按日期存储、固定事件批次、归档标记幂等、journal 分步骤恢复、共享预算入口都有实现与原有测试；H1/H4 和 M3 表明并发窗口、崩溃后的下一次追加、失败持久化仍未覆盖。逐一核对了主动开口、回复、想一想、提问、念头整理及夜间整理的模型调用，均存在共享预算预留；这不替代落盘失败验证。
- **D2：** 原有测试侧重独立 FactStore/Recall；缺少 UI 操作 → profile 同步 → 夜间恢复 → 下一次请求的跨层链路。H2/H3 是这些链路中实际可复现的问题。
- **D3：** 有界状态、过期/已分享/已忽略话题规则已有纯函数测试；M1/M2 揭示时间推移与实际调度组合缺口。删除来源的队列失效归入 H3。
- **D4：** 代码中确有气泡最小宽度、StartTab 轮询刷新、FlowRow 和道具先于脸绘制的修复。没有找到覆盖这四项表现的设备断言；BootstrapSmokeTest 也不会打开记忆页。仅有 APK 编译成功不能证明字体、气泡、生命周期或 OEM 表现正确。EventLogInstrumentedTest 重建实例属于同进程文件重读，不等于真实强杀/跨进程验证。

## 建议修复与下一轮验证顺序

1. 先修 B1；在开始其他记忆测试前，确保查看页面不会修改数据。
2. 修 H1/H2/H3/H4，保留原 162 个测试，并把本次复现转为项目内严格回归；不要删除或弱化失败用例。
3. 修状态衰减、话题调度、预算失败处理及仪器测试定位，再跑同一套四项构建检查。
4. 在受控合成数据上进入下一轮真机：页面无副作用、纠正/忘记后整理与重启、坏尾行后追加、用户编辑与夜间提交并发、权限初始状态、字体 1.3、纯文字气泡、后台返回状态和道具遮挡。报告绑定新 commit 和 APK SHA-256，设备结果单独取证。

当前建议为 **需要先修**，不应把本次构建或原有测试通过解读为可以直接进行普通用户数据的真机验收。

## 复现附件

`repro/ReviewRegressionTest.kt` 是仓库外的追加回归，`review-tests.init.gradle` 只向本次 Gradle 调用附加测试源目录，未修改项目配置或既有测试。两个 control 用例验证干净日志追加和未重放时的事实更正正常；其余用例断言期望行为，并保留真实失败。

在解压附件后，将 init 脚本中的测试源路径改为本机 `repro` 目录；使用同一固定提交、JDK 17 和 Android SDK 35，执行：

```sh
./gradlew --init-script /absolute/path/review-tests.init.gradle :app:testDebugUnitTest --tests cn.companion.pet.audit.ReviewRegressionTest
```

这次额外测试会替换 Gradle 当前目录中的测试报告，因此原始 162 个测试的 XML 已提前单独保存。附件分别保存 baseline 与 regression 证据，避免混用。