# 墨灵 App · 给 DeepSeek 的施工文档

> 2026-10-20。先读 `partner-blueprint/CONTEXT.md`（一页纸的来龙去脉），再读本文。
> 分工：核心逻辑已经写好，在 `moling/app/src/main/java/com/moling/core/`（纯 Kotlin，有单元测试）。**你做 Android 外壳，把界面和这些逻辑接起来。**
> 这份文档写得很具体：每一步写了要建哪些文件、调用哪些函数、做完怎么验收。照着一步一步做，不要跳步，也不要加文档没写的功能。
> **这是第一批（M0–M3）。** 后面的今天页、体系页、小窗和接力等，会在单独的文档里一批一批给出；这一批做完、CI 绿了再接下一批。

---

## 0. 墨灵是什么（一段话）

墨灵是用户的 AI 伙伴，一个独立的 Android App。它和用户聊天（朋友式，但每次都推进一步）；早上帮用户排好一天的安排（“水道”）；用户去 Gemini 或 ChatGPT 聊天时，在屏幕上用一个小窗递问题，聊完把结论接回来；把聊出来的东西整理成“体系图”；每天对自己的判断下注、晚上对账。另一个 App「总控」负责拦截和锁机，墨灵**不拦截、不锁机、不修改系统设置**，只提醒和建议。

---

## 1. 开工前

1. 仓库 `aozhenyu666-cmyk/test`，从分支 **`ccr-b6e667ac-bsoz44`** 拉你自己的分支（例如 `deepseek-moling`）。不要从别的分支拉。
2. **不要修改** `moling/app/src/main/java/com/moling/core/` 和 `moling/app/src/test/java/com/moling/core/` 下的任何文件。觉得要改，就在 `moling/DEVLOG.md` 里写清楚改什么、为什么，用户会转给 Claude。
3. 每完成一步（M0、M1……），在 `moling/DEVLOG.md` 追加一节，写四件事：做了什么、为什么这么做、没做什么、用户在手机上怎么验收（5 步以内）。
4. 每一步都要能编译、单元测试通过，再推送。

### 核心模块速查（都已写好）

| 包 | 你会用到的 |
|---|---|
| `com.moling.core.mind` | `DirectorEngine`（导演：喂事件，返回要显示的卡）、`Card` / `Choice` / `Reply` / `Sense` / `SenseKind` / `Opening` / `SessionLog` / `Facts` / `Actor`、接口 `FactsSource` / `Ask` / `MindStore` / `WindowPort`、`FileMindStore`、`DirectorConfig`、`Conclude`（理解收线的话） |
| `com.moling.core.talk` | `Talk.prompt(...)`、`Talk.parse(reply, dayStart)`、`Talk.local(...)`、`Talk.isFiller(text)`、`Turn` |
| `com.moling.core.flow` | `Flow.Inputs`、`Flow.local(inputs)`、`Flow.morningPrompt(inputs)`、`Flow.parse(reply, inputs)`、`Flow.render(plan)`、`Flow.current(plan, minute)`、`Flow.reroute(plan, channelId, cause, minute)`、`Flow.mark(...)`、`Flow.dayStart(day)`、`Flow.minuteOf(at)` |
| `com.moling.core.atlas` | `Atlas`、`AtlasOps.topic(...)`、`AtlasOps.ingestPrompt(...)`、`AtlasOps.parse(...)`、`AtlasOps.merge(...)`、`AtlasOps.gaps(...)`、`AtlasOps.nextQuestions(...)`、`AtlasOps.synthesisPrompt(...)`、`AtlasOps.parseSynthesis(...)`、`AtlasOps.outline(...)` |
| `com.moling.core.predict` | `Predict.local(...)`、`Predict.prompt(...)`、`Predict.parse(...)`、`Predict.scoreText(...)`、`DayStat` |
| `com.moling.core.power` | `Tier`、`Charter.describe(tier)`（设置页显示用） |
| `com.moling.core.guard` | `ZkGuard.step(memory, sample, day)`（判断总控的无障碍是否正常） |
| `com.moling.core.link` | `ZkCommand`、`ZkWire.toMap(command)`、`describe()`（建议给总控时用） |

---

## 2. M0：工程骨架

**建什么：**
- `moling/settings.gradle.kts`、`moling/build.gradle.kts`、`moling/app/build.gradle.kts`、`moling/gradle/`（wrapper）、`moling/gradlew`。直接参照 `zongkong/` 下同名文件，版本完全一致：AGP 8.5.2、Kotlin 2.0.21、Compose、`kotlinx-serialization-json:1.7.3`、`kotlinx-coroutines-android:1.8.1`、`okhttp`（版本照总控）、`junit:4.13.2`、`kotlinx-coroutines-test:1.8.1`。minSdk 26，targetSdk 34，JDK 17。
- 包名 `com.moling.app`，应用名「墨灵」。
- 固定调试签名：生成一个 `moling/app/debug.keystore` 提交进仓库，`signingConfigs.debug` 指向它（照总控的写法），这样以后覆盖安装数据不丢。
- `MainActivity.kt`：Compose，底部三个标签：**对话 / 今天 / 体系**，外加右上角“设置”。先放空页面。
- `.github/workflows/moling-android.yml`：触发条件 `push` 且 `paths: ["moling/**", ".github/workflows/moling-android.yml"]`；步骤：checkout → setup-java 17 → `./gradlew testDebugUnitTest assembleDebug`（工作目录 `moling`）→ 发布到 GitHub release **`moling-latest`**（照 `zongkong-android.yml` 里 release 那一步，只把名字和文件换掉）。**不要改动总控的任何 workflow。**

**验收：** CI 绿；手机浏览器打开 `https://github.com/aozhenyu666-cmyk/test/releases/download/moling-latest/moling.apk` 能装上，看到三个空标签页。

---

## 3. M1：设置和模型

**建什么：**
- `app/data/Settings.kt`：用 `EncryptedSharedPreferences` 保存文字模型配置：接口地址（默认 `https://api.deepseek.com`）、密钥、模型名（默认 `deepseek-chat`）。外加：人格设定（默认用 `com.moling.core.mind.DEFAULT_PERSONA`）、称呼、是否朗读。
- `app/data/LlmClient.kt`：OpenAI 兼容的 Chat Completions 请求（照抄 `zongkong/app/src/main/java/com/zongkong/core/Llm.kt` 里的 `LlmClient`，改包名）。30 秒超时。
- `app/data/MolingAsk.kt`：实现 `com.moling.core.mind.Ask`：调用 `LlmClient`，失败或超时返回 `null`；每次调用按天累计次数和字数，存在 SharedPreferences。
- 设置页：上面三项 + “测试连接”按钮 + 今天调用了几次、多少字 + `Charter.describe(Tier.PLANNER)` 的说明文字（只显示，不能改）。

**验收：** 填好 DeepSeek 密钥，点“测试连接”显示成功；断网时显示失败原因，界面不卡。

---

## 4. M2：存储和大脑宿主

**建什么：**
- `app/BrainHost.kt`（单例，`MolingApp.onCreate` 里初始化）。这是 App 访问核心逻辑的**唯一入口**，以后 Claude 会补一个更完整的大脑，到时只改这个文件。它持有：
  - `director = DirectorEngine(facts = PhoneFacts, ask = MolingAsk, store = FileMindStore(File(filesDir, "mind/director.json")), config = { DirectorConfig(persona = 设置里的人格) })`
  - 一份 `MolingState`（你在 `app/data/` 里自己定义的 `@Serializable` 数据类）：`flow: FlowPlan?`、`atlas: Atlas`、`topicId: String`、`predictions: List<Prediction>`、`days: List<DayStat>`、`talk: List<Turn>`。用 `FileMindStore(File(filesDir, "mind/moling.json"))` 存成 JSON。
  - 所有调用导演和改 `MolingState` 的操作都放在后台协程里、用一个 `Mutex` 串行执行；需要更新界面时切回主线程。
- `app/PhoneFacts.kt`：实现 `FactsSource`，返回 `Facts`。这一步只填你能拿到的：`now`、`minute`（北京时间一天中的分钟）、`quiet`（23:30–07:00 为 true）、今天的水道里当前水道的标题作为 `mainTitle`；其他拿不到的字段留默认值，并把名字写进 `unknown`（例如 `"总控数据"`）。

**验收：** 单元测试（`core` 的 29 个）全部通过；杀掉 App 再打开，`mind/` 下的两个 JSON 文件还在。

---

## 5. M3：对话页

**界面：** 聊天列表 + 底部输入框 + 麦克风按钮（系统语音识别 `SpeechRecognizer`，识别结果填进输入框）。回复可以朗读（系统 `TextToSpeech`，设置里能关）。

**发送一条消息时，按这个顺序：**
1. 把用户这句加进 `MolingState.talk`（`Turn("他", text, now)`）。
2. 拼提示词：`Talk.prompt(persona, now = director.nowText(now), flow = 今天的水道 Flow.render(plan) 或空, baton = director.snapshot().baton 的 nextAsk 或空, atlas = 当前主题的 AtlasOps.outline(...) 或空, tierText = "规划者：可以提建议和排计划，动现实前先问你", history = talk, user = text)`。
3. 调 `MolingAsk.ask(persona, prompt)`。返回 `null` 就用 `Talk.local(text, baton, 当前水道标题)`。
4. 用 `Talk.parse(reply, Flow.dayStart(今天))` 拆出正文和动作。正文加进对话（`Turn("墨灵", 正文, now)`）。
5. 每个动作在回复下面显示成一张小卡片，带“好 / 不用”两个按钮。**点“好”之前什么都不执行。** 点“好”之后：
   - `TalkAction.Zk`（主线、行动、专注、总控时间）和 `TalkAction.Summon`（去聊）→ 这一批先不执行，只弹一句“记下了，这一项下一批再接上”，并把动作内容存进 `MolingState` 的一个待办列表，方便以后接。
   - `TalkAction.AtlasNote` → 在对应主题下加一条论断（`Node`，类型“判断”，来源“墨灵对话”）。
   - `TalkAction.Reroute` → 对今天的水道调用 `Flow.reroute(...)`，原因按文字对应 `Cause`（卡住了 / 累了 / 晚了 / 情况变了）。
6. 对话只保留最近 40 条在 `MolingState.talk`。

**验收：** 发“嗯”，她会指出这句空、给一个二选一；发“帮我把下午三点排成写申论”，回复下面出现一张“行动”卡片；断网时也能收到本地回复。

---

---

## 7. 这一批的底线

1. 不改 `core/` 下的文件。
2. 不申请文档没写的权限。
3. 对话里的动作，用户点“好”之前一律不执行。
4. 网络或模型失败时界面不能卡，要用本地回复兜底。
5. 每一步做完写 DEVLOG，CI 绿了再推下一步。
