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