# 测试报告与取证需求

报告日期：2026-10-23（Asia/Shanghai）
代码分支：`pet-v15/deepseek-continuity-v2`
最终代码提交：`0fdb6cfd9e5d7608e01d37e31f5a376cda11d0a0`
基线：`a9cbe7cb55e02ab44d3d64d2245b18fe0da2f24e`
执行器：DeepSeek Harness

> 这份报告分成两半：**已经拿到的证据** 和 **还必须获取的东西**。没有拿到的证据，一律写“未执行/待测”，不写成通过。

---

## 1. 结论摘要

| 项目 | 结论 | 证据 |
|---|---|---|
| 代码可编译 | 通过 | 本机 Gradle 构建 + CI |
| JVM 单元测试 | **162 / 162 通过，0 失败** | `test-results/testDebugUnitTest/*.xml` |
| lint | **0 errors / 5 warnings** | `lint-results-debug.txt` |
| debug APK | 构建成功 | 28,100,993 bytes，SHA-256 `54B71E5EC9C294BA49DAB6CCD16E5ECB03BC111CFE8FD29B377C0BA5EFBF9220` |
| 仪器测试 APK | 构建成功 | 只编译，未执行 |
| 远端 CI | **success** | https://github.com/aozhenyu666-cmyk/test/actions/runs/39968655136 |
| 真机功能/体验 | **未验证** | 本机 adb 不可用、无设备；不能写成通过 |

一句话：**自动化能证明的已经通过了；真机才能证明的部分，目前没有证据。** 第 5 节列出我需要你提供/授权的数据，才能把“待测”改成“通过/失败”。

---

## 2. 已获取的自动化证据

### 2.1 环境

| 项 | 值 |
|---|---|
| OS | Windows，用户目录含非 ASCII |
| JDK | Microsoft OpenJDK 22.0.2（本地）；CI 用 Temurin 17 |
| Gradle | 8.10.2 |
| Android SDK | platform 35、build-tools 35.0.0 |
| AGP / Kotlin | 8.7.3 / 2.0.21 |
| 构建方式 | ASCII 镜像副本 + `android.aapt2FromMavenOverride` + in-process Kotlin（仅本机适配，不提交） |

### 2.2 命令结果

```text
:app:testDebugUnitTest          PASS   162 tests / 0 failures / 0 skipped
:app:lintDebug                  PASS   0 errors / 5 warnings
:app:assembleDebug              PASS   app-debug.apk
:app:assembleDebugAndroidTest   PASS   app-debug-androidTest.apk（仅编译）
:app:connectedDebugAndroidTest  未运行（无设备，adb 不可用）
```

### 2.3 按测试类的分布

| 测试类 | 数量 | 覆盖重点 |
|---|---:|---|
| `core.bridge.GeminiBridgeTextBuilderTest` | 9 | 桥接文本模板 |
| `core.companion.CompanionContextAssemblerTest` | 5 | 来源标注、素材指令隔离、5000 码点、emoji 截断、不补满 |
| `core.companion.EventLogTest` | 6 | 写入时归属日、坏尾行、幂等归档、旧 today 迁移 |
| `core.companion.FactStoreTest` | 6 | 猜测不覆盖确认、纠正替代、忘记后重放不复活、档案同步过滤 |
| `core.companion.MaterialFixtureTest` | 3 | 无字幕提示、无素材不编造浏览、来源可见 |
| `core.companion.MemoryRecallTest` | 5 | 昨天兴趣今天召回、不相关不注入、纠正/删除、上限 |
| `core.companion.PetStateTest` | 4 | 三维有界、半衰期、跨重启、忽略不惩罚 |
| `core.companion.TopicQueueTest` | 5 | 来源必需、过期、一次性、忽略不重复、闸门 |
| `core.memory.MemoryStoreTest` | 15 | 档案/日记/信、删除不复活、跨午夜归档、旧行为 |
| `core.memory.NightlyBatchTest` | 8 | 只归档批次、请求中新增/改/删、幂等、遗忘不进整理、积压顺序 |
| `core.memory.NightlyRecoveryTest` | 4 | 每个提交步骤故障恢复、恢复幂等、恢复时新事件、用户改动优先 |
| `core.probe.AtomicBudgetTest` | 4 | 并发最后名额、重启恢复、跨日、零上限 |
| `core.probe.ProbeTest` | 12 | 提示词前缀、解析、注入隔离、调用上限 |
| `core.rhythm.SpeakSchedulerTest` | 9 | 节奏、安静时段、忽略后提前 |
| `core.scene.SceneClassifierTest` | 13 | 学习/游荡/普通/排除 |
| `core.session.ChatSessionTest` | 12 | 外部 AI 会话守护 |
| `core.text.ScreenTextCleanerTest` | 13 | 屏幕文字清洗 |
| `core.text.UnicodeTextTest` | 7 | 码点计数/截断 |
| `core.think.ThinkTest` | 12 | 想一想/念头 |
| `net.DeepSeekClientTest` | 10 | 超时、重定向、取消、超限、不重试 |
| **合计** | **162** | |

### 2.4 新增关键用例（节选）

- 跨午夜：`dayAttributionUsesDeviceZoneAtWriteTime`、`appendAcrossMidnightArchivesOldDay`
- 夜间并发：`commitArchivesOnlyThePreparedBatch`、`userProfileEditDuringRequestWinsAndEventsStayPending`、`userProfileDeletionDuringRequestWins`
- 崩溃恢复：`everyCommitStepCanBeRecovered`、`recoverIsIdempotent`、`newEventDuringRecoveryStaysPending`
- 预算：`onlyOneCallerGetsTheLastSlot`、`restartRestoresUsedCount`
- 事实：`correctionSupersedesOldVersion`、`forgottenFactStaysDeletedAcrossReloadAndCannotBeReadded`
- 召回/上下文：`yesterdayInterestIsRecalledByTodayTopic`、`unrelatedTopicDoesNotInjectAnything`、`dynamicContextStaysUnder5000CodePoints`
- 遗忘传播：`forgottenContentIsNotSentToNightly`
- 素材：`fixtureWithoutSubtitlesSaysSo`、`noMaterialMeansNoBrowsingClaim`

---

## 3. 需求 → 证据矩阵

“自动”= 有 JVM 测试证据；“设备”= 必须真机/模拟器；“当前结论”只写现在能证明的。

| # | 要验证的效果 | 自动证据 | 设备证据 | 当前结论 |
|---|---|---|---|---|
| R1 | 昨天聊过的兴趣，今天相关话题能接上，并指出来源 | 召回/组装测试 | 真机两次交流 + logcat/请求内容 | 逻辑已验证；体验待测 |
| R2 | 用户纠正后采用新信息 | 事实纠正测试 | 真机改一条事实后重启再聊 | 逻辑已验证；体验待测 |
| R3 | 删除后旧记录不从摘要/重放复活 | 删除/重放/夜间过滤测试 | 真机忘记后重启、整理 | 逻辑已验证；体验待测 |
| R4 | 主动开口有具体缘由；没材料就安静 | 话题闸门/素材测试 | 真机安静时段、无来源观察 | 逻辑已验证；体验待测 |
| R5 | 不回应时减少打扰，不施压 | 状态/闸门/节奏测试 | 真机忽略 5 分钟观察 | 逻辑已验证；体验待测 |
| R6 | 阅读素材与自己的意见区分；无字幕不装看完 | 素材/提示词测试 | 真机学习场景 | 逻辑已验证；体验待测 |
| R7 | 重启/跨午夜/超时/被杀后不混日、不重复、不丢 | 事件/批次/恢复/预算测试 | 真机强杀、跨午夜、飞行模式 | 逻辑已验证；故障注入待设备 |
| R8 | 记录可通过事件回放/请求记录/真机核对 | 事件日志/报告 | 真机导出 memory 目录 | 机制已具备；证据待采集 |
| D1 | 日期、批次、事务、恢复、预算 | 全部通过 | 设备观察 | 代码与单测完成 |
| D2 | 有界召回、事实、开关、回退 | 全部通过 | 设备体验 | 代码与单测完成 |
| D3 | 状态、话题、素材、无网络增量 | 全部通过 | 设备体验 | 代码与单测完成 |
| D4 | tokens/四页/48dp/深浅色/字体/无障碍 | 编译通过 | 截图 + TalkBack | 代码完成；视觉未验收 |
| D5 | APK、CI、文档、待测清单 | 已产出 | 安装/升级/回退 | 构建完成；设备未验收 |

---

## 4. 真机测试用例（当前全部“未执行”）

每条都给出：场景 → 步骤 → 期望 → **必须回传的证据**。没有证据就不算通过。

| ID | 场景 | 步骤 | 期望 | 需要的证据 |
|---|---|---|---|---|
| DT-01 | 安装与启动 | `adb install -r app-debug.apk`，打开 App | 不崩溃，四页可见 | 安装输出、启动截图、logcat 首 30 秒 |
| DT-02 | 权限引导 | 逐个开悬浮窗/无障碍/电池/自启动/后台弹出界面 | 状态刷新为已开启 | 每步截图、`dumpsys package` 权限片段 |
| DT-03 | 悬浮窗与拖动 | 启动桌宠，拖到四边 | 角色显示、吸附、不挡中央操作 | 录屏或 3 张截图 |
| DT-04 | 两轮短交流 | 等它开口，回一句，看收尾 | 最多两轮，5 秒后收起 | logcat + 截图 |
| DT-05 | 跨午夜 | 23:59 与 00:01 各交流一次 | 两条记录分属两天，归档日期正确 | 时间戳、当天/归档文件截图或 tar |
| DT-06 | 夜间请求中新增交流 | 点“现在整理一次”，请求中再聊一句 | 新交流不被归档，仍在待处理 | logcat 时间线、事件文件 tar |
| DT-07 | 请求中改档案 | 整理请求中在记忆页改一行 | 完成后用户改动仍在 | 改前/改后截图、profile 文件 |
| DT-08 | 强杀恢复 | 整理过程中 `am force-stop`，再打开 | 同一批不重复归档，不丢新事件 | 两次 logcat、归档文件 |
| DT-09 | 预算上限 | 每日上限改 1，同时触发探针/回复 | 最多 1 次真实请求 | logcat 请求计数、设置截图 |
| DT-10 | 相关召回 | 昨天聊“猫”，今天提“猫” | 回复能接上；请求上下文含该记忆和来源 | 请求日志（脱敏）、两次对话截图 |
| DT-11 | 不相关不注入 | 今天聊完全无关话题 | 请求里没有“猫”的记忆 | 请求日志 |
| DT-12 | 纠正 | 记忆页更正一条事实 | 只保留新版本；下次回复用新事实 | 改前/改后截图、facts.json |
| DT-13 | 忘记 | 记忆页“忘记”一条 | 列表消失；重启/整理后不复活 | 截图、facts.json、events 文件 |
| DT-14 | 主动话题 | 有念头/日记来源后等待；再忽略一次 | 只分享一次；忽略后不反复追问；安静时段零请求 | logcat、话题文件、时间截图 |
| DT-15 | 素材 | 本地 fixture / 学习场景；无字幕 fixture | 有来源；无字幕不装看完；无素材不编“刚刷到” | 请求日志、回复截图 |
| DT-16 | UI 适配 | 360dp/430dp、浅/深色、字体 1.0/1.3 | 不裁字、不重叠、可滚动、键盘不挡输入 | 每种组合截图（至少 8 张） |
| DT-17 | TalkBack/返回 | 开 TalkBack 走四页；系统返回 | 控件有语义、可聚焦、返回不丢状态 | TalkBack 朗读录屏、导航录屏 |
| DT-18 | 仪器测试 | `./gradlew :app:connectedDebugAndroidTest` | 3 个设备测试类通过 | 测试 XML 报告 + logcat |

---

## 5. 我需要获取什么

### 5.1 最小必需（没有这些，真机项只能保持“待测”）

1. **一台可用的 Android 设备或模拟器**
   - 首选：vivo iQOO 12 / OriginOS（型号、Android 版本、系统 build 号）。
   - 或者：本机可用的模拟器（我可以安装 Android 35 system image，约 1.5–2 GB；需要你同意磁盘和下载）。
   - 或者：你在自己电脑上跑，把结果文件发回来。

2. **ADB 访问权**
   - USB 调试授权后的 `adb devices -l` 输出；或无线调试地址/配对码（不长期保存）。
   - 如果你不希望给 ADB，我给出逐条命令和录屏步骤，你执行后回传输出。
   - 需要能执行：`adb install`、`adb logcat`、`adb shell run-as`、`adb shell am force-stop`、`adb exec-out screencap`。

3. **设备基本信息**
   - `adb shell getprop ro.product.model`
   - `adb shell getprop ro.build.version.release`
   - `adb shell getprop ro.build.display.id`
   - 安装方式（adb / 文件管理器）、电池管理设置、自启动设置。

4. **一次 30–90 分钟的可测试时间窗**
   - 跨午夜用例最好在 23:58–00:05；否则需要你授权改设备时间（部分系统不允许）或用模拟器。
   - 其余用例可分段做，但每段要有开始/结束时间。

5. **每个用例的回传材料**
   - 结果：通过/失败/阻塞。
   - 时间戳与操作步骤。
   - 截图/录屏（UI、权限、记忆页）。
   - logcat（从操作前 10 秒到操作后 30 秒）。
   - 失败时的原始报错，不要只写“没成功”。

6. **数据目录快照（涉及记忆/事件/事实的用例）**
   - debug 包可用：`adb exec-out run-as cn.companion.pet tar -c files/memory > memory.tar`。
   - 只需要测试用的合成语句；不要包含你的私人聊天。API Key 不在这个目录，不需要给我。

7. **网络与系统开关的操作权**
   - 飞行模式开关（超时/断网用例）。
   - 字体大小、深色模式、TalkBack 开关（UI 用例）。
   - 通知/电池优化设置（保活用例）。

### 5.2 可选，但能显著提高报告质量

- 设备端直接跑 `:app:connectedDebugAndroidTest` 的 XML 报告。
- `adb bugreport` 全量包（保活/崩溃问题）。
- 一段 3–5 分钟的屏幕录制（真实使用节奏）。
- 你实际使用的 persona/设置导出（脱敏后），用于验证“像不像同一个角色”。
- 你希望的回归重点（例如“只关心跨午夜和预算”），可以裁剪用例，节省时间。

### 5.3 不需要提供

- API Key 明文、账号密码、Cookie。
- 你的私人聊天全文、通讯录、相册。
- B站/QQ 等第三方账号（本轮不联网发布）。

### 5.4 我这边能自动做的

- 生成逐条 `adb` 命令脚本和截图命名规范。
- 解析 logcat/测试 XML，把结果填回本报告。
- 对失败项定位到源码并补测试/修复。
- 重新构建 APK、计算 SHA-256、跑 CI。
- 安装模拟器（需你同意下载）并跑非 vivo 专属用例。

---

## 6. 回传模板（复制填写）

```text
用例：DT-05 跨午夜
设备：iQOO 12 / Android 15 / OriginOS build xxx
时间：2026-10-23 23:58 – 2026-10-24 00:03
步骤：
  1. 23:59 发送“测试跨日 A”
  2. 00:01 发送“测试跨日 B”
观察：
  - 记忆页显示 ...
  - 归档文件显示 ...
结果：PASS / FAIL / BLOCKED
证据：
  - screenshot-01.png
  - screenshot-02.png
  - logcat-05.txt
  - memory.tar
备注：
```

结果判定规则：

- **PASS**：有步骤、有可核对证据、与期望一致。
- **FAIL**：有证据表明与期望不一致；保留原始日志，不只写结论。
- **BLOCKED**：缺设备/权限/时间窗，写清缺什么，不猜测通过。
- **不把**“看起来正常”“应该没问题”写成 PASS。

---

## 7. 现在不能下的结论

- 不能说“真机已通过”“iQOO 12 已验证”“常驻 24 小时没问题”。
- 不能说“跨午夜/强杀恢复已在设备上复现通过”——只有 JVM 故障注入。
- 不能说“UI 已验收”——没有截图/TalkBack/字体放大证据。
- 不能说“APK 可长期使用”——debug 包、Canvas 占位角色、未签名发布。
- 不能把 CI 的“仪器测试 APK 编译成功”当成“仪器测试执行成功”。

---

## 8. 最短下一步

如果你希望最快把报告从“自动化通过 + 设备未测”推进到“有真机结论”，给我这三样即可开始：

1. `adb devices -l` 能看到的设备（或同意我装模拟器）；
2. 30–90 分钟时间窗 + 可改飞行模式/字体/深色模式的权限；
3. 回传：截图 + logcat + `memory.tar`（用合成测试语句）。

我会按 DT-01 → DT-18 逐条跑/收证，失败项直接定位源码修复并重跑，不会把未执行的项写成通过。
