# 墨灵 · 施工日志

> 施工队：DeepSeek（Android 外壳）。
> 核心逻辑在 `app/src/main/java/com/moling/core/`，由 Claude 写；这一批**一行没改**（不需要改）。
> 本批范围：M0–M3，照 `moling/DEEPSEEK.md`。分支 `deepseek-moling`（从 `ccr-b6e667ac-bsoz44` 拉出）。

---

## M0 · 工程骨架

**做了什么**
- 新建 `moling/` Gradle 工程：AGP 8.5.2 / Kotlin 2.0.21 / Gradle wrapper 8.9 / minSdk 26 / targetSdk 34 / JDK 17，依赖版本照总控（Compose BOM 2024.09.03、material3、serialization-json 1.7.3、coroutines-android 1.8.1、okhttp 4.12.0、junit 4.13.2、coroutines-test 1.8.1，另加 security-crypto 1.0.0 存密钥）。
- 包名 `com.moling.app`，应用名「墨灵」；固定调试签名 `app/debug.keystore`（和总控同一个写法），CI 每次打出的 APK 签名一致，手机上可以覆盖安装、数据不丢。
- `MainActivity`：Compose，底部三个标签 **对话 / 今天 / 体系**，右上角“设置”；M0 时都是空页。
- `.github/workflows/moling-android.yml`：只在 `moling/**` 或该 workflow 变化时触发；checkout → setup-java 17 → `./gradlew testDebugUnitTest assembleDebug`（工作目录 `moling`）→ 发布到 release **`moling-latest`** 的 `moling.apk`。**没有改动总控的任何 workflow。**

**为什么这么做**
- 工具链版本和总控完全一致：总控那边踩过的坑（中文路径、签名、CI）不用重踩一遍。
- 固定签名是为了覆盖安装不丢数据（调试版会天天装）。
- release 固定下载地址沿用总控的套路（`moling-latest`），手机浏览器点开就是最新一次通过测试的构建。

**没做什么**
- 没申请文档以外的权限：这一批只有 `INTERNET`；另外在 manifest 里加了一条 `RECOGNIZE_SPEECH` 的 `<queries>`（只是让 App 能问“这台手机有没有语音识别”，不涉及权限）。
- 没加通知、无障碍、悬浮窗、小部件——都是后面的批次。

**用户在手机上怎么验收（≤5 步）**
1. 手机浏览器打开 `https://github.com/aozhenyu666-cmyk/test/releases/download/moling-latest/moling.apk`。
2. 安装（首次允许“安装未知应用”），打开「墨灵」。
3. 底部能看到三个标签：对话 / 今天 / 体系。
4. 右上角齿轮能进设置页。
5. 退到后台再点开，不闪退。

---

## M1 · 设置和模型

**做了什么**
- `data/Settings.kt`：`ModelSettings`（接口地址默认 `https://api.deepseek.com`、密钥、模型名默认 `deepseek-chat`、人格默认 `DEFAULT_PERSONA`、称呼、是否朗读）；密钥用 `EncryptedSharedPreferences`（系统 Keystore 的 AES256_GCM 主密钥）保存；这个文件打不开时退回普通 SharedPreferences 并记一条日志，不让 App 起不来。`usageToday()/addUsage()` 按北京日期记录每天调用次数与字数，跨天自动归零。
- `data/LlmClient.kt`：OpenAI 兼容的 Chat Completions，连接/读/写各 30 秒、整体也 30 秒封顶；请求与读正文都在 IO 线程；接口地址支持裸域名、`…/v1` 或完整的 `…/chat/completions`；状态码（401/402/404/429/5xx…）翻成中文原因。
- `data/MolingAsk.kt`：实现核心库的 `Ask`。失败、超时一律返回 `null`（导演/对话有本地兜底）；成功按“输入+输出字数”记一次账；另有 `test()` 给设置页“测试连接”用。
- `ui/SettingsScreen.kt`：三项接口配置 + 人格设定（多行）+ 称呼 + 朗读开关 + “测试连接”（测的是屏幕上正在填的这一份）+ 今天调用次数/字数 + `Charter.describe(Tier.PLANNER)` 的权柄说明（只读）+ 保存。

**为什么这么做**
- 密钥是这台手机上唯一的私密配置，用系统 Keystore 加密；但 Keystore 在少数机型系统升级后会坏，坏了宁可退回明文也不能让 App 打不开。
- 网络/模型失败绝不能卡界面：30 秒封顶、全在 IO 线程、只回一句中文原因。
- 权柄等级由核心库按“预言准确率/建议接受率”算（`Charter`），这一批只展示、不开放修改。

**没做什么**
- 没做模型预设下拉、余额查询；接口地址和模型名手填。
- 没改 `DirectorConfig`：人格**每次现读**设置（`BrainHost` 里配置是 lambda），改完人格下一句话就生效。

**用户在手机上怎么验收（≤5 步）**
1. 打开墨灵 → 右上角齿轮进设置。
2. 填 DeepSeek 密钥（地址、模型默认已对），点“测试连接” → 显示“连接成功：…”。
3. 打开飞行模式再点“测试连接” → 30 秒内显示失败原因，按钮恢复可点、页面还能滑。
4. 看到“今天调用 X 次、Y 字”和下方“她是规划者/参谋/管家”的权柄说明。
5. 点“保存”回主界面，杀掉 App 重开 → 密钥还在。

---

## M2 · 存储和大脑宿主

**做了什么**
- `data/MolingState.kt`：`@Serializable MolingState`（`flow / atlas / topicId / predictions / days / talk / pending`）+ `PendingAction` + `MolingCodec`（JSON 编解码；读坏回空状态，不抛异常）。
- `PhoneFacts.kt`：实现 `FactsSource`。这一批只填拿得到的：`now`、`minute`（北京时间一天中的分钟）、`quiet`（23:30–07:00）、`mainTitle`（今天水道里此刻该在的那条）；其他字段留默认，并把名字写进 `unknown`，导演会知道自己“看不见”总控那几块。
- `BrainHost.kt`：App 访问核心逻辑的**唯一入口**。`init` 建好设置、Ask、`PhoneFacts`、`DirectorEngine`（`mind/director.json`）、`MolingState` 存储（`mind/moling.json`）；所有改状态的操作过一个 `Mutex` 串行，界面通过 `StateFlow` 读，写文件在 IO 线程。`send()` 按任务书 M3 第 1–5 步（记下用户这句 → 拼 `Talk.prompt` → 调模型 → `Talk.parse` 拆正文和动作 → 正文入对话、只留 40 条；模型失败用 `Talk.local` 兜底）。`accept()` 按 M3 第 5 步（总控/去聊只记账，记下→进体系图，改道→改今天的水道）。

**为什么这么做**
- 文档要求“以后 Claude 补更完整的大脑，只改这一个文件”：所以界面不碰 `DirectorEngine`，也不直接读写记忆文件。
- 用 `Mutex`：模型慢、写文件慢，两个操作同时改状态会互相覆盖。
- `todayFlow()` 用 `dayKey` 过滤：昨天剩下的水道不能当成今天的主线，`mainTitle` 也不能拿旧计划充数。
- 第一次启动就把 `director.json`、`moling.json` 各落一次盘：这一批还没有感知事件入口，`DirectorEngine` 自己不会写文件，不主动落盘的话“杀进程重开，mind/ 下两个文件还在”这条验收会缺一个。

**没做什么**
- 没接总控：没有总控数据、没有前台应用，所以导演这一批不会主动弹卡，`Facts` 只填 4 项。
- `Zk` / `Summon` 动作按文档**只记进 pending，不执行**（下一批接总控）。
- 没有生成今天的水道（`flow` 为空），所以“改道”现在只会回“今天还没有水道”，这是预期行为。

**用户在手机上怎么验收（≤5 步）**
1. 装上打开，**不填密钥**直接发一句（例如“嗯”）→ 能收到本地回复（说明大脑起来了、兜底链路通）。
2. 发两句不同的话 → 第二句的回复接得上上文（history 生效）。
3. 杀掉 App（最近任务里划掉）再打开 → 对话记录还在。
4. 电脑上 `adb shell run-as com.moling.app ls files/mind` → 看到 `director.json` 和 `moling.json`。
5. 点动作卡“记下｜主题｜结论”的“好”→ 回一句“记进体系图…”，待下一批的体系页里能看到这条判断。

---

## M3 · 对话页

**做了什么**
- `ui/ChatScreen.kt`：聊天列表（他靠右灰底、墨灵靠左白底）、底部输入框 + 麦克风按钮 + 发送；“墨灵在想…”提示、发送中按钮置灰；回复下面按动作渲染小卡片（一句话说清要做什么 + “好 / 不用”）；**点“好”才调 `BrainHost.accept`**，点完卡片变“记下了”、不能重复点；点“不用”只移除卡片、不做任何事；设置里开着朗读时念回复正文。
- `ui/VoiceInput.kt`：系统 `SpeechRecognizer`（`ACTION_RECOGNIZE_SPEECH`，`zh-CN`）。**不申请录音权限**；识别结果只填进输入框、不自动发送；错误码翻成中文；离开页面释放识别器。
- `ui/Speaker.kt`：系统 `TextToSpeech` 的包装，懒初始化、没有引擎就静默跳过；念之前把回复里的 `【动作】` 行清掉（那是给按钮的，念出来是噪音）。

**为什么这么做**
- 动作卡只存在内存、不落盘：符合底线“用户点‘好’之前一律不执行”——重启后没点过的动作也不会被执行。
- 语音结果只填输入框：说错了能改，发送权留给用户。
- 朗读用系统 TTS：不引入云端语音、不产生额外费用；设置里关掉就完全不初始化。
- 发送/点“好”用进程级作用域：切到“今天”再切回来，回复和动作也会做完。

**没做什么**
- 没做按住说话、实时转写、语音唤醒。
- 没做消息时间戳、复制、重发这些聊天细节。
- 真机上语音识别（尤其 vivo）没测；识别不可用时只弹中文提示、不崩。

**用户在手机上怎么验收（≤5 步）**
1. 打开对话，发“嗯” → 她指出这句空，给你一个二选一。
2. 断网再发一句 → 仍有本地回复，界面不卡、能继续发。
3. 联网发“帮我把下午三点排成写申论” → 回复下面出现一张“行动”卡片。
4. 点“不用”→ 卡片消失、什么都没发生；再发一次点“好”→ 弹一句“记下了，这一项下一批再接上”。
5. 设置里打开“朗读”，发一句 → 她念出来；关掉就不再念。

---

## 这一批的底线（自查）

1. 没改 `core/` 下任何文件，也没改它的测试。
2. 没申请文档没写的权限（只有 `INTERNET` + 语音识别的 `<queries>` 声明）。
3. 对话里的动作，用户点“好”之前一律不执行（卡片只在内存里等点击）。
4. 网络或模型失败时界面不卡：30 秒封顶 + 本地回复兜底。
5. 每一步都写了 DEVLOG、本地编译与单元测试通过后才推下一步的改动。

---

## 验证（2026-10-20）

- **本地**：Kotlin 编译通过，`testDebugUnitTest` 的 **29 个用例全过**（ConcludeTest 8 / DirectorEngineTest 20 / FileMindStoreTest 1），打出 debug APK 约 11.2 MB；APK 里包名 `com.moling.app`、minSdk 26、targetSdk 34、应用名「墨灵」、启动 Activity `com.moling.app.MainActivity`，权限只有 `INTERNET`（外加 AndroidX 自动加的内部 dynamic-receiver 权限）。
- **CI**：`moling-android` 通过——单元测试 + 打包 + 发布两个 job 全绿。（第一次跑失败是因为 Windows 上拷来的 `gradlew` 没有可执行位，CI 报 `Permission denied`；补上文件模式后即绿。）
- **发布**：release `moling-latest` 里已有 `moling.apk`，大小 11,177,703 字节；`https://github.com/aozhenyu666-cmyk/test/releases/download/moling-latest/moling.apk` 直接返回 APK（`application/vnd.android.package-archive`），手机浏览器可以直接下。
- **本机环境注记（与代码无关）**：Windows 中文用户名的路径会让 Gradle 测试 worker 的 classpath `@argfile` 读不到，报 `ClassNotFoundException: GradleWorkerMain`；把同一份代码放到 ASCII 路径下跑，29/29 一样通过。CI 在 Linux 上不受影响。以后在本机验证单元测试，建议放在 ASCII 路径（或用 CI）。
- **没验证到的**：没有真机/模拟器可用，**语音输入（尤其 vivo）、朗读、覆盖安装保留数据**这三件只在代码层面保证（不可用时只提示、不崩），需要用户按上面的验收步骤在手机上试。
---

## M4 · 换上大脑

**做了什么**
- `BrainHost` 改成只持有 `Brain`（导演、水道、预言、体系图、权柄、总控都在大脑里），对外只暴露 `StateFlow<BrainState>` 和一组 suspend 方法；每个 `brain.*` 调用都在 `Dispatchers.IO` 里跑，调完把 `brain.state()` 推给界面；失败记日志、用兜底值返回，界面拿不到异常。
- 文件路径不变（`mind/moling.json`、`mind/director.json`）：`BrainState` 的字段是旧 `MolingState` 的超集，升级安装直接读回旧文件，**对话记录不丢**。
- 删掉 App 自己写的 `MolingState` / `PendingAction`，以及拼提示词、`Talk.parse`、`accept` 里的分支、`reroute` —— 这些大脑都做了。
- `PhoneFacts` 只留手机这边拿得到的：`now`、`minute`、`quiet`；主线、水道、总控状态由大脑的 `BrainFacts.merge` 补。
- 新建 `ZkBridge : ZkPort`：用 `ContentResolver.call` 读总控状态 / 交指令，Bundle ↔ Map；没装、被杀、版本旧、签名不认——任何异常都返回 null，绝不让墨灵崩。
- 对话页改到「提议」上：卡片从 `state.proposals` 里按「这条回复到下一句墨灵之间」算出来（所以重开 App 卡片还在）；新建共用 `ui/ProposalCard.kt`（对话页和今天页共用）；「好 / 不用 / 重试」分别调 `brain.accept / decline / retry`；「去聊」的 `Outcome.opening` 弹对话框，可以复制开场白。

**为什么这么做**
- 文档要求 App 只和大脑打交道：以后 Claude 改大脑，App 这边只改 `BrainHost` 一个文件。
- 卡片按时间从存储状态里算、不存本地：提议的「决定状态」必须来自大脑，切页/重启都不丢，也不会出现「界面上还有、大脑里已经过期」。
- 底线：用户点「好」之前，卡片只是显示，什么都不会发生。

**没做什么**
- M4 还没接总控（`<queries>` 和前台心跳在 M5），所以现在点「好」会显示「没送到总控：连不上总控」——这是文档预期的。
- 「体系」页还是空页。

**用户在手机上怎么验收（≤5 步）**
1. 覆盖安装（别清数据）→ 打开「对话」，第一批的聊天记录还在。
2. 联网发「帮我把下午三点排成写申论」→ 等「墨灵在想…」消失，那条回复下面出现一张卡片。
3. 点「不用」→ 卡片变成「不用了」，什么都没发生。
4. 再发一次，点「好」→ Toast「没送到总控：连不上总控。可以稍后重试」，卡片变「没送到总控」并出现「重试」。
5. 切到「今天」页，同一条还在「等你决定的」里（同一个组件）。
---

## M5 · 接上总控

**做了什么**
- `AndroidManifest.xml` 加 `<queries><package android:name="com.zongkong.app" /></queries>`：Android 11 起不声明就看不见总控。
- 大脑心跳：`MainActivity` 在 App 处于前台（`repeatOnLifecycle(STARTED)`）时每 60 秒调一次 `BrainHost.tickNow()`，打开 App 立刻先跳一次。`tick` 会读总控、对账到点的预言、让过期提议变成「过期了」、看总控掉没掉、看水流偏没偏。
- 设置页加「总控」一行：`总控：已连接（严管 / 正常）` 或 `总控：连不上`，旁边「重新连接」（调一次 `tickNow`）；状态来自 `BrainState.lastStatus`。
- 连接状态按时间戳判断新鲜度：总控掉线时大脑会保留上一次读到的状态，所以超过 2 分钟没刷新也显示「连不上」——不然强行停止总控后设置页还会假装连着。
- 新增 `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7` 依赖（`lifecycleScope` + `repeatOnLifecycle`）。
- CI 里单元测试那一步的名字改成了「单元测试（core，含 Brain）」，因为这一批多了 `BrainTest` / `ZkLinkTest`。

**为什么这么做**
- 这一批按文档只做「App 在前台时」的心跳：不做后台常驻定时、不做无障碍、不读前台应用（所以 `brain.tick` 的 `foregroundCategory` 传空串、`guard` 传 null）。
- 连接状态只读展示：总控连不上时墨灵照常聊天，只是交不了建议（卡片会写「没送到总控」，可以重试）。

**没做什么**
- 没做后台定时 / 无障碍 / 读前台应用，所以「水流偏了」的提醒这一批还不会出现（今天页的提醒条目前主要来自预言对账）。
- 没做「点通知直接跳到总控执行」——通知和确认都在总控那边（总控 0.11 的 `MolingProvider`）。

**用户在手机上怎么验收（≤5 步）**
1. 手机上装新版总控（`zongkong-latest`）+ 墨灵 → 设置页显示「总控：已连接（严管 / 正常）」。
2. 对话里让她排一个行动，点「好」→ Toast「交给总控了，在总控里确认后执行」。
3. 下拉通知栏 → 总控弹出一条「墨灵建议」；点「执行」→ 总控里多了一步（挂在「墨灵的水道」下）；另一条点「不用」→ 什么都没发生。
4. 强行停止总控 → 墨灵设置页显示「总控：连不上」；点「好」的卡变「没送到总控」+「重试」，界面不卡。
5. 再打开总控、在墨灵设置页点「重新连接」→ 又显示「已连接」，之前没送到的卡点「重试」能交上去。
---

## M6 · 今天页

**做了什么**
- 今天页从上到下：**提醒条 → 今天的水道 → 改道 → 等你决定的 → 今天的预言 → 最近做了什么**。
- 提醒条：`BrainHost.notices` 最多 3 条（新的在前），点「×」划掉；挂着提议的条上直接带「好 / 不用」。
- 今天的水道：没有今天的 → 「排今天的水道」（调 `brain.planDay`，期间显示「在排…」）；有 → 逐行显示时间 / 种类 / 标题，**当前该流的那条高亮**；下面是「重排」（`force = true`）和「定稿，交给总控」（`brain.confirmFlow`，返回的提议显示成卡片，点「好」才交）；定稿后按钮变灰「已定稿」。
- 改道：卡住了 / 累了 / 晚了 / 情况变了 四个按钮 → `brain.reroute(cause)`，Toast 显示大脑返回的那句话（空串显示「现在没有在流的水道」）。
- 等你决定的：`brain.pending(now)` 的提议，用共用 `ProposalCard`（和对话页同一组件）。
- 今天的预言：今天下的注逐条（内容、把握 %、对账后「对了 / 错了」），底部一行 `Predict.scoreText`。
- 最近做了什么：`state.log` 最后 10 条小字。
- 每 20 秒本地走一次表，让大字时间和「当前这一条」高亮自己更新，不用等别处的刷新。

**为什么这么做**
- 今天页是每天真正会打开的那一页：要一眼看到「现在水该在哪」，所以把水道放在最上面、当前那条高亮。
- 时间用日历日（`Brain.calendarDay`，0 点换日），和大脑的水道一致；**不用**导演的 `dayKey`（4 点换日），不然凌晨会显示错的水道。
- 所有会动现实的按钮都走「提议 → 点好 → 大脑执行 → 总控再确认」；页面自己只显示和转发，不直接执行。
- 提醒条和卡片都从 `BrainHost` 的 StateFlow 读，页面不自己存状态；每步 `runCatching`，总控连不上或模型失败只 Toast，界面不卡。

**没做什么**
- 「体系」页还是空页（下一批）。
- 没有后台定时：离开 App 之后今天页不会自己更新（回到前台会 `tick` 补上）。
- 没做打开 Gemini / ChatGPT，去聊只给开场白 + 复制。
- 没在真机上验收（这里没有设备和模拟器），需要用户按下面的步骤试。

**用户在手机上怎么验收（≤5 步）**
1. 打开「今天」→ 点「排今天的水道」→ 按钮显示「在排…」，之后出现水道行，当前那条有底色。
2. 填了密钥时能看到「今天的预言」；没有密钥、又不满 3 天记录时没有预言，这是对的。
3. 点「定稿，交给总控」→ 出现一张卡片；点「好」→ 总控弹「墨灵建议」通知，这里按钮变灰「已定稿」。
4. 点「累了」→ Toast 一句，水道里插进 20 分钟「歇一会儿」，后面的顺延（想重来就点「重排」）。
5. 点「重排」→ 水道重新排；切到「对话」再回来，今天页的状态还在。

---

## 第二批的底线（自查）

1. 没改 `core/` 下任何文件，也没改它的测试（这一批的 `BrainTest` / `ZkLinkTest` 是 Claude 写的，我们只跑）。
2. 没申请文档以外的权限：这一批只多了一条 `<queries><package com.zongkong.app /></queries>`（不加 Android 11 起看不见总控）。
3. 提议在用户点「好」之前一律不执行；交给总控之后，总控那边还要用户再点一次「执行」。
4. 总控连不上、模型失败时界面不卡、墨灵不崩：所有 `brain.*` 调用都 `runCatching`，卡片会写「没送到总控」并给「重试」。
5. 每一步写了 DEVLOG，并等 CI 绿了再推下一步（M4 → M5 → M6 三次提交，CI 每次都绿）。
---

## 第二批验证（2026-10-21）

- **本地**：Kotlin 编译通过，`testDebugUnitTest` **48 个用例全过**（BrainTest 15 / ZkLinkTest 4 / DirectorEngineTest 20 / ConcludeTest 8 / FileMindStoreTest 1）；debug APK 约 11.3 MB。
- **APK 清单**（aapt2 dump）：包名 `com.moling.app`、minSdk 26、targetSdk 34、启动页 `com.moling.app.MainActivity`、应用名「墨灵」；权限仍然只有 `INTERNET`（`<queries>` 里的 `com.zongkong.app` 和语音识别不是权限）。
- **CI**：M4（`b772879`）、M5（`8b3f5db`）、M6（`bdd553c`）三次提交的 `moling-android` 全部绿（build + release 两个 job），按文档「CI 绿了再做下一步」。
- **发布**：release `moling-latest` 已更新成这一批的 `moling.apk`，还是同一个地址，手机浏览器直接下：
  `https://github.com/aozhenyu666-cmyk/test/releases/download/moling-latest/moling.apk`
- **本机环境注记**（和第一批一样，与代码无关）：Windows 中文用户名路径会让 Gradle 测试 worker 的 classpath `@argfile` 读不到（`ClassNotFoundException: GradleWorkerMain`）；同一份代码放 ASCII 路径下 48/48 通过。CI 在 Linux 上不受影响。
- **没验证到的**：这里没有真机/模拟器。**总控联动（通知 → 点「执行」）、语音输入、朗读、覆盖安装保留数据**只在代码层面保证（失败只提示、不崩），需要用户按上面的验收步骤在手机上试。
---

## M7 · 收尾：体系页、打开演员、权柄上限

> 说明：仓库里没有第三批施工文档（`DEEPSEEK.md` 只到第二批，`DEEPSEEK-2.md` 到 M6 为止）。这一节是“按文档做完”的收尾：只做
> `DEEPSEEK.md` 的模块速查 + `partner-blueprint/README.md`（第 3、5 节）里已经写明、而 App 还空着的三块——「体系」页（第三个标签）、
> “去聊”时打开演员、设置里的权柄上限。**没有加文档外的新功能**；正式 M7 文档来了再对齐，`ui/AtlasScreen.kt` 的文件头也写了这句。

**做了什么**
- **体系页**（`ui/AtlasScreen.kt`）：空态说明；主题（横向 FilterChip，点一下 `brain.setTopic`）；当前主题的论断按 事实 / 判断 / 假设 / 问题 / 方法 分组（每条带把握、已验证、来源）；关系（只显示两端都在当前主题里的）；**缺口 · 下次该聊**（`AtlasOps.gaps` 的 why + question）；贴材料（`brain.ingest`，返回新增几条并 Toast，模型不可用时提示「没拆出新论断」）。
- **`BrainHost` 补三个包装**：`ingest(text, source, topicName): Int`、`setTopic(name)`、`setUserMax(tier)`，和其他方法一样走私有 `io(...)`（IO 线程、异常兜底、调完把 `brain.state()` 推给界面）。
- **打开演员**：`OpeningDialog` 里用 `getLaunchIntentForPackage(actor.pkg)` 查演员装没装，装了才显示「打开 Gemini Live / ChatGPT」；打开成功就关对话框，失败只提示、不关（开场白不能丢）。`AndroidManifest.xml` 加 `com.google.android.apps.bard` / `com.openai.chatgpt` 的 `<queries>`——只是包可见性声明，**不是权限**。
- **设置页权柄上限**：「参谋 / 规划者 / 管家」三档（调 `brain.setUserMax`），下面展示按实际等级算的 `Charter.describe(brain.tier())`（只读）。

**为什么这么做**
- 三个标签里只有「体系」还是空页，而 core 的 `Atlas` / `AtlasOps` 早就写好了（拆论断、合并、找缺口、下一次该聊的问题），不接上就缺一块。
- 「去聊」之前只能复制开场白，用户还得自己找 App；蓝图 P4 说至少要能一步打开演员——不需要新权限，只加 `<queries>`。
- 权柄上限在 core 里有 `setUserMax` / `Charter.effective`，蓝图也说“数字由我定”，但 App 一直没有入口；补上之后“参谋 / 规划者 / 管家”的差别才看得见。
- 范围克制：体系总结（synthesis）、删除 / 编辑 / 验证论断都没做——前两项 core 没暴露对应入口（改 core 是禁止的），要么等正式文档，要么留给 Claude 补大脑。

**没做什么**
- 小窗（悬浮、开场白交接）、接力棒页、后台常驻心跳、读前台应用后的「水流偏了」——这些需要新权限 / 前台服务或正式施工文档，这一批不做。
- 体系总结、删除 / 编辑 / 验证论断——core 没有暴露入口。
- 没在真机上验收（这里没有设备 / 模拟器）。

**用户在手机上怎么验收（≤5 步）**
1. 设置页把权柄上限切到「参谋」→ 杀掉 App 重开，选择还在，下面的“她能做什么”跟着变。
2. 「体系」页贴一段 GPT / Gemini 的结论 + 主题名 → 点「收进体系图」→ 按钮变「在拆…」→ Toast「收进 N 条」，论断按类型出现（没密钥时提示没拆出新论断，界面不卡）。
3. 有缺口时出现【待验证】等 + 加粗的「下一问」；点另一个主题，下面的论断 / 关系 / 缺口跟着切。
4. 对话里让她给一条「去聊」提议并点「好」→ 弹出开场白；装了 Gemini 的话点「打开 Gemini Live」直接跳过去。
5. 没装演员时对话框只有「复制」「知道了」，点了不崩、不卡。
---

## M8 · 调试页

**做了什么**
- 设置页最下面加「调试」入口 → `ui/DebugScreen.kt`（只读）：权柄等级 + `trust` 六个数；总控 `lastStatus` 每个字段一行 + 「最后读到 HH:mm:ss」（null 写「连不上」）；提议按六种状态计数 + 最近 10 条（文字 / 状态 / `zkOutcome` / `receipt`）；导演的接力棒 / 议程条数 / 今天打扰次数 / 今天模型花费；后台心跳上次跑的时间；`state.log` 倒序。
- 顶部「复制全部」：把整段报告放进剪贴板，真机上出问题直接粘给 Claude。
- `BrainHost` 补 `directorSnapshot()`（转 `brain.director.snapshot()`）和 `lastHeartbeatAt()`（`tickNow` 里记时间）。

**为什么这么做**
- 后面 M9–M16 每一步都要在真机上验证，先有“能看见”的页面，出问题不用猜。
- 报告每次重组现算：导演快照和心跳时间不在 `BrainState` 里，按 state 缓存会显示旧值。
- `ZkStatus` 用 `toString()` 拆行、不逐个字段写死：以后 core 加字段，调试页自动跟上（列表字段里逗号会被拆行，调试页够用）。

**没做什么**
- 纯只读：不写状态、不清日志、不调模型。
- 「后台」块里的看前台应用 / 包名 / 类别 / 连续分钟先显示「—」，M10 接上 UsageStats 后由 Lead 填。

**用户在手机上怎么验收（≤5 步）**
1. 设置页拉到最下面，点「调试」。
2. 从上到下能看到六块：【权柄】有等级和接受 / 猜对 / Brier / 连续天数。
3. 【总控】装了且连着时逐字段一行 + 「最后读到 HH:mm:ss」；没装显示「连不上」。
4. 【后台】「心跳上次」是刚才（打开 App 会跳一次）；其余四项是「—」。
5. 点「复制全部」→ Toast「复制了」→ 粘到聊天框，六块文字都在。