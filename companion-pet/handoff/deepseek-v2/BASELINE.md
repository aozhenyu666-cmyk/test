# D0 基线审计（deepseek-continuity-v2）

核查日期：2026-10-23（Asia/Shanghai）
执行器：DeepSeek Harness
工作分支：`pet-v15/deepseek-continuity-v2`
基线提交：`a9cbe7cb55e02ab44d3d64d2245b18fe0da2f24e`（`origin/ccr-d35383d9-k0acmh` 的当前 tip，已用 `git merge-base --is-ancestor` 核对）
工作目录：独立 worktree `companion-pet-deepseek`，未改动原 clone 与其它 worktree。
命令包：`companion-pet-command-pack-v2`（D0 → D5）。

> 本文件只记录本次实际做过的构建/测试，以及从源码读出的风险位置。除明确写“已复现”的项目外，其它都是**静态审计线索**，没有伪造成手机上已经发生的故障。

## 1. 结论摘要

1. 基线可独立构建、可跑 JVM 单元测试；本次本地实测 `:app:testDebugUnitTest` 为 **111 个测试、0 失败**。`VALIDATION.md` 里的“87 个通过”是更早的记录，本轮不沿用这个数字。
2. 现有实现已经覆盖：常驻服务、悬浮角色、无障碍感知/场景、开口节奏、被忽略升级、DeepSeek 探针、气泡两轮交流、Gemini/ChatGPT/千问桥接与外部会话守护、想一想/念头、Markdown 档案/日记/信、每晚整理、设置/统计、Keystore 存 Key、开机自启与看门狗。
3. 现有“连续交流”的实际形态是：**稳定 persona + 整份 profile + 最近 20 条 today.md + 昨天日记开头 200 字**。没有事件 ID、没有按相关性召回、没有用户纠正/遗忘的稳定事实层、没有防止旧来源重放的企业级约束。
4. 记录可靠性存在明确的结构性缺口：跨午夜归属、夜间请求期间新增交流/档案编辑的并发窗口、`commitNightly` 的逐文件替换、预算检查与计数分离。四项都能用假时钟/假客户端在 JVM 里复现，见第 5 节。
5. 设备测试仍是空白：本机 `adb` 无法启动守护进程（Temp 目录权限），也没有连接真机或模拟器。所有仪器测试只做到“可编译”，不能写成“已执行”。

## 2. 实际构建结果

### 2.1 环境

| 项 | 值 |
|---|---|
| OS | Windows（中文用户目录，路径含非 ASCII） |
| JDK | Microsoft OpenJDK 22.0.2（本地实际使用；CI 固定 Temurin 17） |
| Gradle | 8.10.2（本地解压发行版；CI 用 wrapper 同版本） |
| Android SDK | platform 35、build-tools 35.0.0、platform-tools（本地路径经 junction 转为 ASCII） |
| AGP / Kotlin | 8.7.3 / 2.0.21 |
| 项目 | `companion-pet/` 独立 Gradle 工程，minSdk 30 / compileSdk 35 / targetSdk 35 |
| 构造方式 | 把工作树镜像到 ASCII 路径 `C:\pet-build-deepseek\companion-pet` 后构建；只读源工作树，构建副本与源分离 |

说明：用户目录与 Android SDK 路径含中文时，aapt2 与 Kotlin/Gradle worker 的 @argfile 会失败。本机沿用仓库外构建脚本 `_pet-local/build.ps1`（ASCII junction + `android.aapt2FromMavenOverride` + in-process Kotlin）。这属于**本机环境适配**，不提交进仓库，也不改变 CI。

### 2.2 命令与结果

在 `companion-pet/` 内等价执行：

```text
:app:testDebugUnitTest   PASS（111 tests, 0 failures, 0 skipped）
:app:lintDebug           PASS（见 2.3）
:app:assembleDebug       PASS（debug APK 产出）
:app:assembleDebugAndroidTest PASS（仪器测试 APK 只编译，未执行）
```

- 单测由 10 个测试类组成，覆盖：场景分类、开口节奏、忽略升级、探针提示词/解析/每日上限、记忆提交与删除、文字清洗、桥接模板、外部会话、网络客户端（超时/重定向/取消/超限）。
- 仪器测试只有 `BootstrapSmokeTest`（应用启动冒烟），没有设备可执行。
- CI（`.github/workflows/companion-pet-android.yml`）在 push 时执行同样四类任务；本分支的远端 CI 状态以推送后的 Actions 记录为准，不把基线提交的绿色结果挪给新提交。

### 2.3 产物

- APK：`app/build/outputs/apk/debug/app-debug.apk`（构建成功；SHA-256 在 D5 交付时重新计算并记录）。
- lint：`:app:lintDebug` 通过，**0 errors，4 warnings**（依赖版本提示 2 条、`PerceptionService` 的 `SwitchIntDef` 1 条、其余 1 条；都不阻断构建）。D1 起新增代码保持不新增 error。
- 仪器测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`（约 2.3 MB，仅编译）。
- debug APK：约 27.7 MB，未签名发布用途，仅是调试包。

## 3. 已有能力地图

| 能力 | 主要位置 | 现状 |
|---|---|---|
| 前台服务/常驻/开机/看门狗 | `service/PetService.kt`、`BootReceiver.kt`、`work/NightlyWorker.kt`（Watchdog） | 完成；依靠系统与 WorkManager 尽力保活 |
| 悬浮角色与气泡 | `overlay/PetOverlay.kt`、`PetView.kt`、`BubbleView.kt` | Canvas 占位形象；气泡支持输入、按住说话、动作入口 |
| 感知与场景 | `a11y/PerceptionService.kt`、`core/scene/*` | 只订阅窗口事件；学习场景按需读一次节点树；游荡不读正文 |
| 开口节奏/忽略升级 | `core/rhythm/SpeakScheduler.kt`、`IgnoreEscalation.kt` | 亮屏 1–2 分钟首开口；6–12 分钟间隔；安静时段；忽略 3 级 |
| 探针与回复 | `core/probe/ProbePrompt.kt`、`ProbeReply.kt`、`service/PetBrain.kt` | 每次开口一次非流式请求；JSON 解析失败静默；两轮收尾 |
| 记忆 | `core/memory/MemoryStore.kt`、`Nightly.kt`、`work/NightlyWorker.kt` | persona.md / profile.md / today.md / diary / letters / archive / deleted.txt |
| 念头 | `core/think/ThoughtNotes.kt`、PetBrain | 用户主动保存原句；不自动扩展为全量记忆 |
| 外部 AI App 会话守护 | `core/session/ChatSession.kt`、`bridge/*` | 与本轮“伙伴持续会话”无关；D5 只做兼容回归 |
| 网络 | `net/DeepSeekClient.kt` | OpenAI 兼容；不重试、不跟随重定向、256 KiB 上限 |
| 设置/统计 | `data/Settings.kt`、`Stats.kt`、`ui/*` | SharedPreferences；按天计数 |
| 密钥 | `data/SecretStore.kt` | Android Keystore AES-GCM |

## 4. 现有测试与覆盖

- 111 个 JVM 测试全部通过；`:app:lintDebug` 通过。
- 现有测试对“前缀稳定、注入隔离、删除不复活、归档保留 30 天、网络降级”有覆盖，但这些测试是围绕**旧 Markdown 整文件提交**写的，不能证明事件级可靠性。
- 没有覆盖：跨午夜、请求期间新增事件、请求期间用户改档案、提交步骤中断、同一 jobId 重放、预算并发、按相关召回、事实纠正/遗忘传播、状态衰减、主动话题去重。
- 仪器测试未执行（无设备）。

## 5. 审计线索（源码位置 + 机制 + 复现设计）

以下编号对应本任务命令文档 D0 的核查线索，先记“机制”和“怎么复现”，不写成已发生事故。

### L1 跨午夜：`appendToday` 只在文件缺失时写日期头

- 位置：`core/memory/MemoryStore.kt:81-88`；调用点 `service/PetBrain.kt:553`（`endConversation`）、`:715`（会话结束）、`:881`（想一想回答）、`:964`（念头）。
- 机制：`appendToday(today, line)` 只有当 `todayFile` 不存在或长度为 0 时才写 `# today`；之后无论传入哪个 `today` 都直接追加。进程跨午夜连续运行时，新一天的交流会被追加到旧日期文件，`todayDate()` 仍返回旧日期，夜间整理会把两天的内容当成同一天。
- 复现设计（JVM）：固定 `today=2026-10-22` 写一行，再以 `2026-10-23` 写一行，断言 `todayDate()==10-22` 且两行同文件 → 当前实现失败；D1a 后应按日分文件/分批次，`todayDate` 与事件 `day` 都不串日。
- 补充：`PetBrain` 记录时使用 `LocalDate.now()`（系统时钟），没有可注入时间源；跨午夜前后一次对话的 `shownAt` 与落盘日期可能不同。

### L2 夜间整理：请求前快照、请求后提交整个当前 today

- 位置：`work/NightlyWorker.kt:39-68`（`prepared` 在 `synchronized` 内构造；网络请求在锁外；返回后 `commitNightly(day, output)` 再进锁）。
- 机制：
  1. 请求发出后、返回前新增的交流仍写进 `today.md`；
  2. 提交时 `archiveToday(day)` 读取的是**当前** `today.md`（含新增内容），然后删除文件；
  3. 新增交流没有被模型处理，却被归档并从“今天”移除；
  4. 用户在这 60 秒内通过“记忆”页编辑/删除 `profile.md`，提交会用模型输出整份覆盖，用户改动丢失。
- 复现设计（JVM，假客户端阻塞在请求中）：prepare 后追加一条新交流/修改 profile，再完成请求并 commit；断言新交流仍待处理、用户编辑仍在 → 当前实现失败；D1a 用固定批次（事件 ID 列表 + profile 版本）只提交批次内事件，版本冲突重算。

### L3 `commitNightly` 逐文件替换后归档，没有事务与幂等

- 位置：`core/memory/MemoryStore.kt:139-160`；`archiveToday` 追加后删除 today；`writeAtomic`/`replace` 只保证单文件。
- 机制：profile、diary、letter 三个 rename 之间崩溃，会留下部分新文件；归档追加与删除 today 之间崩溃会重复归档或丢内容；同一 job 重试会再次覆盖/追加。没有持久化提交状态或 journal。
- 复现设计（故障注入）：在第 1/2/3 个 rename、归档写入、删除 today 各步骤前抛异常；恢复后校验三份输出与归档一致、重试同 job 不重复 → 当前实现无法保证；D1b 用 journal + 稳定 jobId 实现“恢复或回滚”。

### L4 预算：检查与计数分离，计数是 SharedPreferences 读改写

- 位置：`data/Stats.kt:23-28`（`getInt` + `putInt`，非原子）；检查点 `service/PetBrain.kt:245-250`（speak）、`:424`（reply）、`:810`（想一想）、`:866`（回答）、`ui/MemoryTab.kt:135-136`、`work/NightlyWorker.kt:41,56`。
- 机制：
  1. `get(...) >= dailyLimit` 与 `add(...)` 之间没有预留；两个协程可同时通过检查，最后一个配额被用两次；
  2. `SharedPreferences.edit().apply()` 异步落盘，进程被杀可能少计；跨进程/多入口没有统一序列化；
  3. 计数口径不统一：探针在请求前 `add`；夜间整理在请求前 `add`；MemoryTab 摘要直接 `add`；失败/超时也计数。文档要求的“一次实际网络尝试计数、重试受限”没有统一入口。
- 复现设计（JVM）：两个线程/协程同时 `reserve` 最后 1 个配额，断言只成功一次 → 当前实现可能成功两次；重启（重建 Stats）后计数不丢。
- 建议口径（写入本文件，D1b 实现）：**真正的 HTTP 尝试才计数**；配置无效/地址非法不计；一次调用最多一次尝试（客户端本身不重试）；WorkManager 的 `Result.retry()` 算新的尝试并重新预留；跨日按设备时区 00:00 归零；重启从持久化恢复。

### L5 上下文与表达：整份 profile + 最近流水 + 日记开头；回复强制收尾；作息随机

- 位置：`core/probe/ProbePrompt.kt:51-82`、`core/probe/ProbeReply.kt:87-94`、`core/life/PetLife.kt:25-49`。
- 机制：
  1. `systemMessage` 把整份 `profile.md` 拼进 system；`userMessage` 放最近 20 条 today + 昨天日记 200 字；没有相关性选择、没有上限（除 profile 3000 字）；
  2. `ProbePrompt.reply` 明确要求“接他一句，然后自然收尾，别再抛新问题”，因此当前不可能出现真正的连续多轮；
  3. `PetLife.tick` 每 40–150 秒随机换状态，仅按小时调权重；状态与真实交流、时间衰减无关；
  4. `DEFAULT_PERSONA` 允许“我昨天在日记里写……”，但没有素材来源校验，模型可能编造浏览经历（D3 要在结构上限制）。
- 复现设计：构造一天 30 条交流 + 一份 3000 字 profile，断言请求里只出现与当前话题相关的 ≤6 条、总动态 ≤5000 码点、来源标注清楚 → 当前实现不满足；D2 用本地关键词/二元组召回 + 统一组装器。

### L6 其它静态发现（进入 D1/D2 范围）

- `deleteProfileLine` 用**整行文本相等**删除；`deleted.txt` 只按归一化文本比对。模型换一种说法重写同一事实时可以复活。现有测试只覆盖“换标签/加空格”。D2 需要 `factKey` + 版本/替代关系，历史自由文本仍保守处理。
- `MemoryStore.saveProfileByUser` 是整份覆盖，UI 的“整段编辑”与夜间提交共用同一文件，没有版本号；见 L2。
- `Nightly.parse` 的档案校验只要求行首标记与长度；没有核对批次的 `sourceEventIds`，模型可以凭空写事实。
- `Stats.days` 从 key 前缀解析日期；预算恢复依赖 `API_CALLS`，与 L4 的并发问题叠加。
- `PetBrain.buildOpeningSync` 在用户点角色时同步读整份 profile/最近流水（主线程），文件大时有卡顿风险；D2 组装器需要预计算/缓存。
- 现有“念头”保存选中原句属于用户明确操作；本轮不把它扩成自动全量记忆。若与原 V1.5 文档冲突，以命令包为准并在此记录：**保留该功能，新增连续性存储不接收原始屏幕/剪贴板/完整 prompt**。
- `core/session/ChatSession.kt` 是外部 AI App 守护，不是本轮“伙伴的持续会话”；D2 不修改它的语义。

## 6. 数据与迁移现状

- 现有数据目录：`filesDir/memory/` 下 `persona.md`、`profile.md`、`today.md`、`diary/`、`letters/`、`archive/`、`deleted.txt`、`letter-read.txt`、`notes/`。
- 格式：纯 Markdown/文本，无 schema 版本、无事件 ID、无来源 ID。
- UI 依赖：`ui/MemoryTab.kt` 直接读 `profileLines()`、`todayLines()`、日记/信/念头；D1a 的迁移必须保留这些读取入口与“整段编辑”，不能为了新结构删掉旧目录。
- D1a 计划的新增格式（v1）：`events/YYYY-MM.jsonl`（每行一个事件，含 `id/occurredAt/day/kind/text/source`）、`facts.json`（稳定事实，v2 使用）、`nightly/batch-*.json`、`nightly/journal-*.json`、`nightly/archived-<day>.json`。迁移只从旧 `today.md` 生成 `LEGACY_LINE` 事件，先备份，幂等标记 `migrations/events-v1.done`；旧文件保留可读/可编辑，不删除。

## 7. 设备空白

- 本机没有可用设备：`adb` 守护进程因 Temp 目录权限无法启动，且未连接 iQOO 12/模拟器。
- `:app:assembleDebugAndroidTest` 只证明仪器测试 APK **已编译**；`BootstrapSmokeTest` **未执行**。
- 需要真机才能确认：前台服务保活、悬浮窗、无障碍开关、输入法/键盘、vivo 后台弹出界面、锁屏心跳、通知、安装与升级。D5 会保留明确的“待测”列表，不用模拟器结果冒称 iQOO 12。

## 8. 本轮后续计划（只列 D1 入口，后续阶段另有 STATE）

- **D1a**：事件日志（稳定 ID/发生时间/归属日）、固定夜间批次（事件边界 + profile 版本）、只提交批次内事件、用户编辑优先/版本冲突重算、旧 Markdown 兼容与幂等迁移；测试覆盖跨午夜、请求中新增交流、请求中改/删档案、空记录与历史积压。
- **D1b**：稳定 jobId + 持久化提交状态 + journal 恢复；统一预算预留（探针/回复/想一想/夜间/摘要），按 HTTP 尝试计数、跨日与重启可测；故障注入覆盖每个提交步骤、重复 jobId、恢复时新交流已到达、两调用争最后一个配额。
- 完成 D1 验收（针对性测试 + 全量 JVM + lint/APK）后再进入 D2，不用新特性掩盖存储失败。
