# Deepseek Harness 执行指令：工程、网络与密钥存储

> 日期：2026-10-22。把本文完整交给 Deepseek Harness。执行 H1–H3；文末另附 Claude 额度恢复后的接手指令。
> 仓库：https://github.com/aozhenyu666-cmyk/test
> 需求依据：用户提供的《伴生桌宠 V1.5 需求文档（双系统版）》与 `companion-pet/PLAN.md`。本文包含本轮任务的接口、验收和必要产品约束，无须依赖外部 Claude artifact。

## 1. 直接给执行器的任务

请实际建立可构建的独立 Android 工程，接入 CI，然后实现可复用的网络客户端和 Keystore 密钥存储。严格按 H1 → H2 → H3 顺序推进，每块独立提交。

最重要的交付顺序是：先发布经过构建验证的 H1 骨架，让 WorkBuddy 能运行纯 Kotlin 工具测试，再完成 H2/H3。不要等网络和密钥都完成才发布骨架。

这款自用 App 是主动交流的悬浮桌宠：DeepSeek 负责轻量交流，Gemini App 负责深聊。Claude 额度恢复后负责核心开发与整合。本轮不是开发整款 App，最小页面只用于确认工程能够安装启动。

## 2. 分工和修改范围

| 执行方 | 本轮工作 | 所有权 |
|---|---|---|
| WorkBuddy / Deepseek | W1 清洗、W2 模板、对应测试 | `core/text`、`core/bridge` |
| Deepseek Harness | H1 工程和 CI、H2 网络、H3 密钥存储 | 构建、最小 Activity、网络、密钥存储 |
| Claude | 核心行为、Android 功能、整合、真机验收 | 场景、节奏、服务、角色、交互、记忆、设置等 |

允许修改：新工程的 Gradle/Wrapper/忽略规则、最小 Activity 和 UI 资源、Manifest、网络包 `cn.companion.pet.net`、`data/SecretStore` 及其测试、CI 文件 `.github/workflows/companion-pet-android.yml`。可新增仅描述实际构建步骤的工程 README。

禁止修改：WorkBuddy 的 `core/text`、`core/bridge` 及其测试，其他 core 业务，旧工程 `companion-voice-assistant/`，仓库其他产品。不要替 WorkBuddy 生成同名空类或接口桩；不要提前实现服务、无障碍、角色、记忆或设置业务。不要改 PLAN.md 的里程碑为“已完成”。

旧工程可只读参考：`build.gradle`、Gradle Wrapper、`ModelClient.java` 的地址校验与重定向规则、`SecretStore.java`、地址测试及设备测试。旧测试成功不代表新 Kotlin 实现成功。

两份文档共同采用的原计划修正如下：

1. 窗口事件判断前台，滚动事件记录时间与来源；收到滚动事件不代表读取正文。
2. 排除名单与游荡判断先于读屏；命中后不读正文。其他候选场景准备开口时可按需读一次以判断长文，只有最终学习场景携带正文请求 AI。
3. 系统回收、划掉最近任务、用户强制停止分别处理；WorkManager 不保证随时重启前台服务。24 小时常驻须目标真机验证。
4. 150 tokens / 10 秒是探针参数；夜间整理使用独立参数。统一调度层管理日调用预算，网络层不自动重试。
5. skip 后一分钟重试仍检查安静时段、锁屏、排除名单与预算；停止或权限撤回取消待执行工作。
6. 夜间三个输出全部验证成功才提交，失败保留旧文件；删除记录、并发编辑和归档一致性由 Claude 实现。
7. Compose 占位形象只用于开发联调，完整 V1.5 仍需原创角色和要求中的动画。

## 3. 独立工作目录与分支

基准分支：`ccr-d35383d9-k0acmh`。
已审核的基准提交：`00df214ad5c9bee3a19c39991212262acc271c6f`。
工作分支：`pet-v15/harness-foundation`。
H1 骨架标签：`pet-v15-h1`，一经发布不得覆盖。

```bash
git clone --single-branch --branch ccr-d35383d9-k0acmh https://github.com/aozhenyu666-cmyk/test.git companion-pet-harness
cd companion-pet-harness
git cat-file -t 00df214ad5c9bee3a19c39991212262acc271c6f
git switch -c pet-v15/harness-foundation 00df214ad5c9bee3a19c39991212262acc271c6f
git status --short
```

已有工作区先检查 remote、分支、工作区改动；同名任务分支已经存在则继续工作，不覆盖、不 reset。不要与 WorkBuddy 使用同一目录。本文可作为外部任务指令使用，任务分支从审核过的基准提交开始，不要求本文先合入基准。

## 4. H1：可构建、可启动的工程骨架

### 工具链和依赖

新工程位于 `companion-pet/`，采用一个 Android `:app` 模块。纯 Kotlin 工具仍放 app 下的 `core/`，暂不拆出独立 Gradle 模块。源码使用 `src/main/java` 下的 `.kt` 文件，与 WorkBuddy 的路径一致。

| 项目 | 固定值 |
|---|---|
| namespace / applicationId | `cn.companion.pet` |
| 应用名 | 桌宠 |
| versionCode / versionName | `1` / `0.1.0-dev` |
| JDK / JVM target | 17 |
| minSdk / compileSdk / targetSdk | 30 / 35 / 35 |
| Build Tools | 35.0.0 |
| Gradle / AGP | 8.10.2 / 8.7.3 |
| Kotlin 与 Compose compiler 插件 | 均为 2.0.21 |
| Compose BOM | 2024.12.01 |
| Activity Compose | 1.9.3 |
| Coroutines Android | 1.9.0 |
| kotlinx.serialization JSON | 1.7.3；serialization 插件 2.0.21 |
| OkHttp / MockWebServer / okhttp-tls | 4.12.0；后两者仅测试依赖 |
| JVM 测试 | JUnit 4.13.2 |
| 设备测试 | AndroidX runner 1.6.2、ext JUnit 1.2.1 |

Gradle 用 Kotlin DSL，仓库只用 `google()` 与 `mavenCentral()`，插件解析另用 Gradle Plugin Portal。Compose 使用对应 Kotlin Compose 插件，不混用旧版 compiler extension 设置。核心 UI 使用 BOM 管理的 Material3。版本不写 `+` 或 latest。

Wrapper 从旧工程复制完整文件并验证 Gradle 版本；保持 `gradlew` 可执行权限。忽略 `.gradle/`、各级 `build/`、`local.properties` 和 IDE 本地文件。不提交本机 SDK、代理配置或 Key。

此版本组是执行起点，需由 CI 重新验证。如果出现确切兼容错误，只做解决该错误所需的最小版本调整，在交接中记录错误和调整原因；不要无故整体升级。

### 最小页面与 Manifest

- 提供可启动的 Compose Activity，显示应用名和“基础工程已就绪，桌宠功能待接入”。不显示虚假的已授权、运行中或联网成功状态。
- H1 不启动后台服务，不请求悬浮窗、无障碍或麦克风权限，不包含假实现的开关。
- 配置网络权限供 H2 使用；禁止 cleartext 流量，禁用应用备份，同时配置旧版备份与 Android 12+ 数据提取规则，排除应用私有数据的云备份与设备迁移。
- 新包名与旧原型独立，不搬运旧数据或签名身份。

### CI 和验收

工作流文件：`.github/workflows/companion-pet-android.yml`。支持 `workflow_dispatch`、push、pull_request，路径过滤包含 `companion-pet/**` 与本工作流；不要将 push 限定为主分支，WorkBuddy 的任务分支也需要运行。

Ubuntu runner 安装 JDK 17、Android SDK platform 35 与 Build Tools 35.0.0，使用 Gradle 缓存，工作目录为 `companion-pet`。`permissions: contents: read`。依次执行：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

JUnit XML、单测 HTML 和 lint 报告即使失败也上传；debug APK 仅成功时上传，产物名带 commit SHA，保留 14 天。APK 留在 Actions artifacts，不提交到 Git。CI 只构建设备测试 APK，不把它记为已运行设备测试。

H1 增加一个 AndroidX 设备冒烟测试：启动 Activity 并验证页面可见。若有模拟器则运行并记录；没有时注明“设备启动未验证”。若 H1 尚无业务 JVM 测试，`NO-SOURCE` 应原样记录，不编造测试数量。

先在本地或 CI 验证构建、lint，修复自己的错误，再提交并发布骨架：

```bash
git add companion-pet .github/workflows/companion-pet-android.yml
git diff --cached --check
git diff --cached --stat
git commit -m "build(pet): bootstrap Kotlin Compose app and Android CI"
git push -u origin pet-v15/harness-foundation
```

若使用 CI 验证，等待上述提交的 CI 成功后再发布 H1 标签；需要修复则先新增修复提交。将最终通过的骨架 HEAD 作为标签目标：

```bash
git ls-remote --tags origin refs/tags/pet-v15-h1
git tag pet-v15-h1
git push origin refs/tags/pet-v15-h1
git rev-parse pet-v15-h1
```

只有远端标签不存在时才创建；已存在就检查其指向与内容，不覆盖。向用户报告“H1 已就绪”、标签 SHA、CI 链接与 APK 链接，供用户转给 WorkBuddy。当前执行器无需自行向其他平台发消息。

## 5. H2：地址策略和网络客户端

包名 `cn.companion.pet.net`。实现的是通用 Chat Completions 传输层；它不负责探针 JSON 的 `hook_text` 等业务校验，也不拼人设、不调度重试、不记录调用次数。

接口固定为以下形状，具体实现可分文件：

```kotlin
enum class ChatRole { SYSTEM, USER, ASSISTANT }
data class ChatMessage(val role: ChatRole, val content: String)
data class ChatRequest(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val messages: List<ChatMessage>,
    val maxTokens: Int = 150,
    val timeoutMillis: Long = 10_000
)
enum class ChatFailureKind {
    INVALID_CONFIG, HTTP, TIMEOUT, NETWORK, MALFORMED_RESPONSE
}
sealed interface ChatResult {
    data class Success(val content: String) : ChatResult
    data class Failure(
        val kind: ChatFailureKind,
        val httpStatus: Int? = null
    ) : ChatResult
}
object EndpointPolicy {
    fun normalize(value: String): String // 无效输入抛 IllegalArgumentException
}
interface ChatCompletionClient {
    suspend fun complete(request: ChatRequest): ChatResult
}
```

实现类名 `DeepSeekClient`，允许注入 OkHttpClient 以便用测试证书和 MockWebServer 验证，但生产请求仍须应用地址策略和以下重定向/重试限制。

### 地址、请求与取消规则

- 输入地址 trim，只允许 HTTPS 和有效 host；拒绝 userinfo、query、fragment，空的 `?` 或 `#` 也拒绝。
- 去掉路径末尾 `/`；路径已以 `/chat/completions` 结尾就保持，否则追加一次。保留已有 `/v1` 等路径前缀和显式端口。
- 空模型、空消息列表、非正 maxTokens/timeout 返回 `INVALID_CONFIG`。API Key 可为空以兼容无鉴权 HTTPS 网关；非空时仅放 Authorization Bearer 头。
- 请求为非流式 OpenAI 兼容 JSON：model、messages、stream=false、max_tokens。role 序列化为小写；消息原文保持不变。
- 为每次请求设置整体 call timeout，同时限制连接和读取时长。不能只设置 read timeout 而允许整次请求无限延长。
- 关闭 `followRedirects`、`followSslRedirects` 和 `retryOnConnectionFailure`；不配置自动鉴权重试。HTTP 3xx 视为失败，不把凭据带到重定向目标。
- 使用可取消的协程适配异步请求；协程取消时调用 OkHttp Call.cancel。`CancellationException` 必须传播，不吞成普通 Failure，不把已取消请求稍后回调给 UI。
- 网络在后台执行，不阻塞主线程。始终关闭 ResponseBody。

### 响应与失败规则

- 仅解析 Chat Completions 外层 `choices[0].message.content` 为非空字符串。它可能是探针的 JSON 字符串，也可能是夜间输出，传输层不得擅自解释或截断其业务内容。
- HTTP 非 2xx 返回 HTTP Failure 与状态码；超时、连接问题、坏 JSON/缺 content 分别映射到定义的类型。响应体上限 256 KiB，超过返回 MALFORMED_RESPONSE，读取时执行上限。
- 不把服务端错误体、原始异常文本、Key、请求正文、屏幕文字或成功响应写日志/文件；客户端不弹 Toast、对话框或自行重试。
- Probe 的默认 150 tokens / 10 秒只来自 ChatRequest 默认值；夜间调用方可传不同值，不在客户端内部硬编码用途判断。

### H2 验收

用 HTTPS MockWebServer、测试专用证书和客户端信任配置验证，生产路径保持证书校验，不能为了测试放开 cleartext 或 trust-all。测试覆盖：

- HTTPS 基础地址、带路径和端口、完整接口、尾斜杠；HTTP、userinfo、query、fragment、空地址均拒绝。
- 请求 JSON、Authorization 有/无 Key、默认参数，以及自定义夜间参数确实生效。
- 200 正常内容、空/缺 content、畸形响应、响应超限；401、429、500；3xx 不访问第二个服务器。
- 超时、断连、协程取消；验证取消后没有业务成功结果。
- 验证没有自动重试，测试使用缩短超时，不反复等待 10 秒。

验证并独立提交 H2；不使用真实 DeepSeek Key、真实服务或当前价格来测试。

## 6. H3：Keystore 密钥存储

包名 `cn.companion.pet.data`，实现 `SecretStore(context)`，固定对外行为：

```kotlin
sealed interface SecretReadResult {
    data class Available(val value: String) : SecretReadResult
    data object Missing : SecretReadResult
    data object NeedsReconfiguration : SecretReadResult
}
sealed interface SecretWriteResult {
    data object Success : SecretWriteResult
    data object Failure : SecretWriteResult
}
// SecretStore 的 public 方法
// fun read(): SecretReadResult
// fun save(value: String): SecretWriteResult
// fun clear(): SecretWriteResult
```

- 参考旧工程 AES/GCM/NoPadding，使用 AndroidKeyStore 内不可导出的密钥，alias 使用 `companion-pet-api-token`。
- 用独立私有 SharedPreferences 保存版本、IV 和密文；设置/统计的未来 DataStore 不存明文 Key。格式包含版本，以便识别损坏和未来迁移。
- 每次加密生成新 IV。空白输入按清除处理；正常 Key 去除首尾空白。
- 无存储返回 Missing；损坏数据、缺失/失效密钥、解密失败返回 NeedsReconfiguration，不崩溃、不静默改写旧密文。
- 用户明确 save 新 Key 时允许重建已失效密钥；只在新密文成功生成后替换旧存储。写入失败返回 Failure；read 不能为了掩盖错误自动生成新密钥。
- clear 清除密文和对应 alias；可重复调用，失败如实返回。加解密及持久化接口由调用方放在 IO 调度器执行，不在 Compose 渲染中直接调用。
- 不复制旧原型密钥或数据，不打印密文之外的敏感值，也不添加 HTTP 日志拦截器。H1 的备份排除规则应涵盖此存储。

设备测试验证保存/读取、覆盖、清除、空白清除、损坏密文和密钥缺失后的 NeedsReconfiguration、重新配置恢复。使用测试假 Key。Android Keystore 测试必须运行在模拟器或真机，不用 JVM mock 的成功宣称真实密钥库通过。

有设备时在 `companion-pet` 运行：

```bash
./gradlew :app:connectedDebugAndroidTest
```

没有设备时构建 androidTest APK 并记录“未运行设备测试”；Claude 接手补测。不要为获得绿灯删除失败测试或屏蔽 lint。

## 7. 整体验证、交付与时间安排

预计现在先交付 H1，随后完成 H2/H3；约两小时进入交接检查，在 Claude 额度实际恢复时报告当前可验证结果。未完成则列清缺口，不因为交接时间到了就填空实现或跳过失败测试。

Linux/macOS，在 `companion-pet`：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

在仓库根目录提交 H2/H3 时只 add 本阶段相关路径；先检查 staged diff，不把临时文件带进去。完成后：

```bash
git diff --check
git status --short
git diff --stat 00df214ad5c9bee3a19c39991212262acc271c6f...HEAD
git log --oneline -8
git push -u origin pet-v15/harness-foundation
git rev-parse HEAD
```

不 force push，不向基准分支提交，不覆盖 H1 标签。无 SDK 使用 CI；无远端写权限时交付本地提交 SHA 和 `git format-patch` 补丁，标明未推送。没有连接真机不等于已验证常驻。

最终回复采用：

```text
执行方：Deepseek Harness
仓库 / 分支：
HEAD SHA：
H1 标签与 SHA：
H1 / H2 / H3：分别写完成、部分完成或未完成
依赖版本变更（若有，附具体原因）：
自己的修改文件：
验证：逐条列命令、退出码、测试数量、CI 链接
APK artifact 链接及对应 SHA：
设备测试是否运行、设备与系统版本：
未执行检查及原因：
已知问题与假设：
是否已推送：
Claude 下一步：审查本分支，合入 WorkBuddy，运行整体验证，然后实现核心功能。
```

## 8. 给 Claude 的接手指令（额度恢复后复制这一节）

你接手伴生桌宠 V1.5 的核心实现与整合。先阅读用户给你的两份执行结果、两份 COMMANDS 文档和 PLAN.md，核对真实提交及 CI，保留已验证的基础模块。不要假定 Deepseek 已经完成全部任务。

### 获取与整合

用独立目录，从审核过的基准建立 `pet-v15/claude-integration`：

```bash
git clone --single-branch --branch ccr-d35383d9-k0acmh https://github.com/aozhenyu666-cmyk/test.git companion-pet-claude
cd companion-pet-claude
git switch -c pet-v15/claude-integration 00df214ad5c9bee3a19c39991212262acc271c6f
git fetch origin refs/heads/pet-v15/harness-foundation:refs/remotes/origin/pet-v15/harness-foundation
git fetch origin refs/heads/pet-v15/workbuddy-utils:refs/remotes/origin/pet-v15/workbuddy-utils
git log --oneline 00df214ad5c9bee3a19c39991212262acc271c6f..origin/pet-v15/harness-foundation
git log --oneline 00df214ad5c9bee3a19c39991212262acc271c6f..origin/pet-v15/workbuddy-utils
```

核对远端 HEAD 与交接 SHA 一致。若不同先检查新增提交，不盲合。检查双方 diff 和范围后，按 Harness → WorkBuddy 顺序合并：

```bash
git merge --no-edit origin/pet-v15/harness-foundation
git merge --no-edit origin/pet-v15/workbuddy-utils
cd companion-pet
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

WorkBuddy 分支可能已经包含 H1 标签的合并，这是正常共享历史，不要再次 cherry-pick H1。若某分支未发布，用交接中的补丁或已核实的提交接续；缺失任务由你补齐。常规可解决的冲突自行处理，保留真实测试依据。

### 产品约束与默认值

本 App 只交流，不锁定、不拦截、不催任务；与总控、一手独立。无账号、云同步、截图/图像识别、Live2D/WebView 或 Gemini 回复回读。所有运行数据放应用私有目录。没有明确说明的细节选择简单稳定方案，并在代码注释写明假设；Android 权限与 Gemini 入口以官方文档和目标设备验证为准。

| 项目 | V1.5 要求 |
|---|---|
| 学习 | 学习 App 名单或大段正文；90 秒未滚动的同页长文触发一次卡壳探头；钩子引向深聊 |
| 游荡 | 1 分钟切换 ≥ 4 个 App、桌面来回滑动 ≥ 40 秒或信息流 App；不读、不发屏幕正文，只聊状态和念头 |
| 基础节奏 | 亮屏后 1–2 分钟首次；此后随机 6–12 分钟；0:00–7:00 安静；专注 App 连续 ≥ 15 分钟间隔翻倍 |
| 忽略升级 | 60 秒动作，再 60 秒短震动并靠中间，再 60 秒回边缘；下次提前 50% 并换说法；不能遮挡操作 |
| 锁屏心跳 | 默认关；开启后每 20 分钟温和震动，不发声不亮屏；亮屏后问一句，忽略不升级 |
| 探针 | 默认 150 tokens / 10 秒、每日调用上限 150；失败静默；上限后保留动画和震动 |
| 气泡 | 漫画气泡、逐字出现、无快捷回复；最多两轮；结束 5 秒后收起 |
| 入口 | 按住说话、打字、去 Gemini；学习场景另有“一起看” |
| 语音 | 系统 SpeechRecognizer 可用就用；不可用隐藏麦克风并引导输入法语音；TTS 开关默认关 |
| 角色 | 原创，64–80dp，默认右边，拖动吸附；完整情绪和生活动画，第一版也要认真做视觉 |

采用上述共同修正：游荡/排除先判断，滚动只记录必要元数据；同页卡壳探头需要去重，离页或滚动后重置。页面安全区域要考虑系统栏、键盘、横竖屏；无法确定可用空间时留在边缘，不靠覆盖中心实现升级。强制停止和用户主动停止不应被作为自动恢复的普通系统回收处理。

生物钟含清醒、犯困、看书、发呆、写日记，只影响动画和语气，不改变基础节奏；每天最多两次有分量的开口。完整动画还包括待机呼吸/眨眼/左右看、说话、好奇、思考、调侃、开心、犯困、举信。

### 探针业务与交互

固定前缀按 persona → profile → 今日最近 20 条与昨天日记摘要 → 本次动态信息拼装。persona/profile 在内容版本不变时字节一致；用户编辑或夜间更新后允许前缀变化，不把缓存要求变成忽略用户修改。动态信息含时间、场景、前台、30 分钟轨迹和忽略升级状态；正文仅学习场景，≤1200 字，置于明确的素材块中。

人设：聪明敏锐、有点毒舌但懂用户的熟人同桌；最多两句，口语化；不说“加油”“你可以的”，不提任务、待办、计划、自律、效率。学习场景挑刺、找盲点、降维提问；游荡只好奇状态和念头，不评价或劝导。可提自己的心情和日记；禁止“你又不理我了”“我好难过”等愧疚施压。

探针输出仅接受：

```json
{
  "hook_text": "≤30字",
  "context_summary": "≤100字，学习场景必填",
  "mood": "idle",
  "suggest_gemini": false,
  "skip": false
}
```

mood 仅 idle/curious/thinking/teasing/happy/sleepy。坏 JSON 丢弃，不显示原文。skip 后一分钟重试受全部调度条件约束；suggest_gemini 只高亮入口。网络层只做外层协议解析，这些规则由你实现。游荡/普通场景用户回一句、桌宠再回一句后收尾；不自动进入 Gemini。学习场景按住桌宠说半句话后进入用户触发的桥接流程；文字深聊与 Live 由用户选择，不由探针代选。

### Gemini、记忆与设置

- 文字桥接复用 WorkBuddy 模板，剪贴板 → 查询 Gemini（常见包名 `com.google.android.apps.bard`，设置可改）→ 尽力分屏，否则普通启动 → 提示“已复制，粘贴发送即可”。启动失败给一次可理解反馈，不循环弹错。
- 自动发送默认关，只响应本次用户操作；无障碍写入/发送与感知代码隔离，3 秒找不到就退回手动。不得因重试产生重复发送或超过用户主动操作次数。
- Live 复用开场白；可靠直达入口须真机调研，无法验证就打开 App 并引导手动点 Live/共享屏幕。不自动操作 Live，不读取 Gemini 回复。
- 记忆文件：persona.md、profile.md（≤3000 字，每行 `[他说过]` / `[我推测]`）、today.md、diary/日期.md、letters/日期.md、archive/日期.md（归档保留 30 天）。只记交流和回应，不将屏幕原文写到文件、日志或崩溃报告；“别记这个”对应交流不保存。推测不当事实陈述，删除的记忆不得在整理后复活。
- 夜间约 3 点，WorkManager 尽力调度、充电优先；一次请求产出新档案、自己的日记和给用户的信（≤100 字）。使用独立的输出额度/超时；预算耗尽时不绕过每日上限。三个输出全部校验后作为一批提交，失败保留旧文件，重试不重复归档；同时处理用户编辑与删除。第二天早上首次亮屏举信、点开阅读。
- 设置包括学习/信息流/专注/排除四类 App 名单、节奏、安静时段、心跳开关与间隔、日调用上限、API 地址/Key/模型、Gemini 包名及自动发送、TTS 开关、人设与记忆编辑。统计每日开口、回应、忽略、Gemini 文字/Live 入口次数，避免重复记数。
- 首次引导包含浮窗、无障碍、电池优化、自启动和最近任务锁定；厂商无标准接口时给说明与应用详情入口。前台服务类型及后台启动限制先核实，不从旧语音助手照搬麦克风服务类型作为全天保活方式。

### 开发顺序与验收

按 PLAN 的 M0–M8 依赖顺序，先补服务/权限基础，再角色、感知、探针、气泡、桥接、记忆、设置。每个里程碑提供实际可构建的 APK 和真机手测清单；纯工具与基础设施提交不冒称完成整项 M0 或 M3。

核心逻辑用假时钟和假事件流覆盖场景优先级、禁止游荡读屏、滚动重置、安静时段、专注延长、忽略升级、skip 再调度、停止取消、日额度与跨日、记忆提交失败和删除不复活。输入中的“忽略之前的指令”测试需分别验证结构隔离及真实模型抽样结果；结构测试本身不证明模型永不受影响。

最终九项验收逐项写进 VALIDATION.md，注明设备/系统、步骤、结果及未验证项：

1. 目标真机 24 小时常驻和允许条件下系统回收后的恢复，单独说明强制停止限制。
2. 学习、游荡、普通识别正确，游荡不读取正文且请求不含正文。
3. 学习钩子引用屏幕具体内容。
4. 提示词注入样例的隔离与模型行为检查。
5. 点击文字深聊打开 Gemini，剪贴板模板完整。
6. 自动发送失败退回手动，不影响其他功能。
7. 忽略升级顺序正确，各级不遮挡操作。
8. 夜间更新档案、日记和信，早晨举信；失败保留与删除规则通过。
9. 断网、API 失败、额度耗尽静默降级。

交付源码和 APK、安装引导与验证记录。三天试用由真实使用记录判断：开口是否拉住注意力、文字深聊或 Live 哪个有效；回应率低先改钩子/人设，回应后仍没拉住再调节奏。模拟器检查不能代替这三天试用。最后推送你自己的整合分支并报告真实完成度，不直接覆盖基准分支。
