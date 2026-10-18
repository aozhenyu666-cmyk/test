# 语音伙伴：任务规格（T1–T7）

给编码代理看的详细规格。总体思路和取舍在作者的规划文档里；这里只写“做什么、改哪里、不做什么、怎样算做完”。每次只做被点名的那一条任务。

## 一句话架构

一手里有一个**会话控制器**。**导演**（用户配置的强文字模型）每隔几轮写一张很短的**导演卡**；**嘴耳层**（本机流式听写 → 聊天模型 → 流式朗读；不用 Gemini Live）照着导演卡说话，要看屏幕时才调识图模型。**执行器**监视前台应用、管计时通知；路线 A 里它负责拉起 Gemini App。**记忆**是一手已有的数据：主线断点、收线句、棋谱（`echo` 表）、题库、海龟汤。

新代码放在两个包里：

- `com.yishou.app.partner`：会话计划、在场统计、导演、控制器（纯逻辑，可在 JVM 上测）。
- `com.yishou.app.voice`：本机听写、说完检测、流式朗读（Android 部分）。

## 伙伴的行为（所有任务共同遵守）

- 它是坐在旁边的同学：口语，一次一两句；不替用户总结，不先给答案。
- 每场有目标、时长（默认 25 分钟）和强度：**陪**（大多安静，关键处问一句）/ **诱**（打赌、二选一、海龟汤、“如果……会怎样”）/ **严**（每段必须答才往下，10 秒读秒）。
- 没接的阶梯：问了没人回 → 缩短重问一次 → 换成二选一 → 不再问，记一笔“没接”，回到陪着。收线时把没接次数告诉用户。不纠缠、不说教。
- 闲聊最多两轮就拉回目标。到点只问两件事：“一句话，今天聊出了什么？”“下一步做什么？”。不延长。

---

## 任务 T1：伴读会话的纯逻辑

**新文件** `app/src/main/java/com/yishou/app/partner/Partner.kt`（不依赖 Android），测试 `app/src/test/java/com/yishou/app/partner/PartnerTest.kt`。

内容：

1. `enum class Material { VIDEO, ARTICLE, DRILL, CHESS, OTHER }`，`enum class Gear { ACCOMPANY, LURE, STRICT }`（陪 / 诱 / 严，带中文 label）。
2. `data class PartnerPlan(material, goal: String, minutes: Int, gear: Gear)`。
3. `object Opening`：`fun build(plan, lastClose: String?, lastNext: String?): String` 拼出路线 A 的开场白：伴读设定的开场白（见下）+ 这次目标 + 时长 + 强度那一句 + 材料追加提示 + 有上次收线时加“上次你说：……，下一步是……，先问我做了没有”。
   - 开场白原文：`现在开始伴读。你是坐我旁边的同学，不是助手。别替我总结，先让我自己讲；我讲完你指出我漏了什么，一次一处；隔几分钟问我一个推演题或反例；我走神就直接问我在干什么。说话简短口语。结束时问我一句"聊出了什么"。`
   - 材料追加提示：视频“每讲完一个知识点就停下来让我复述，复述不出来就倒回去。”；文章“每读完一段，让我说这一段和上一段是什么关系。”；做题“我做之前先让我说思路，做完先别给答案，问我哪一步最不确定。”；象棋“我走每步之前问我对方最好的应手是什么。”
   - 强度那一句：陪“大部分时间安静，关键句再问。”；诱“多用二选一、打赌和‘如果……会怎样’来勾我说话。”；严“每一段都要我答了才往下，答不上来就停在这里。”
4. `class Presence(allowed: Set<String>, awayLimitMs: Long = 120_000)`：喂前台事件 `onForeground(pkg, at)`，算出 `presentMs`、`leaves`（离开允许集合且超过 awayLimitMs 记一次）、`awaySince`（当前离开的起点，在场时为 null）。允许集合 = Gemini 包名 + 用户登记的材料 App + 一手自己。
5. `object Closing`：`fun ok(line, next): Boolean`（收线句去掉口水后至少 6 个字，下一步至少 4 个字，复用 `think/Words.substance`）。

**完成标准**：测试覆盖开场白各分支、在场统计（进出、短暂离开不算、长时间离开算一次、结束时结算）、收线判定。不改任何已有文件。

## 任务 T2：路线 A 接进一手

依赖 T1。

1. **数据**：新表 `partner_session`（`id`、`material`、`goal`、`minutes`、`gear`、`startedAt`、`endedAt`、`presentMs`、`leaves`、`closeLine`、`nextStep`、`route`（"gemini" / "live"）），DAO，数据库版本 +1，写迁移。
2. **入口**：首页加“语音伙伴”入口 → 开场页：材料类型、目标（`VoiceTextField`）、时长 10/25/45、强度三档、“用 Gemini 开始”按钮。
3. **开始**：`Launch.copy` 放开场白进剪贴板；打开 Gemini（包名 `com.google.android.apps.bard`，没装就打开 `https://gemini.google.com/app`）；提示“进伴读 Gem → 粘贴 → 点 Live → 共享屏幕”。
4. **在场**：会话进行中，`gate/GateService` 把前台包名喂给 `Presence`；允许集合里的应用不触发入口页，其余关注应用照常触发。常驻通知每分钟更新“伴读中 · 还剩 N 分钟”；离开超过 2 分钟改成“离开 N 分钟了，回去吗？”，带“回 Gemini”按钮。
5. **收线**：到点或用户回到一手点“结束”→ 收线页，`Closing.ok` 过了才能保存；收线句写进回响队列（`YishouApp.echo(text, EchoKind.CONCLUSION, "partner")`）。没收线就走掉的，下次入口页先问“上次伴读还没收线”（照 `GateState.followUp` 的写法）。

**不要做**：自动点 Gemini 里的按钮、读 Gemini 的屏幕或对话、在 Gemini 上面盖浮窗。

**完成标准**：迁移建表语句和实体一致；纯逻辑测试通过；PR 里写一份真机验收步骤（开一场 10 分钟、中途切短视频 3 分钟、回来收线）。

## 任务 T3：导演

**新文件** `partner/Director.kt`（纯逻辑）和 `llm/DirectorLlm.kt`（用 `ChatClient`），测试 `partner/DirectorTest.kt`。

- `data class Turn(val who: String, val text: String)`（who = "我" / "伙伴"）。
- `data class DirectorInput(plan, elapsedMin, transcript: List<Turn>, missed: Int, lastClose: String?, memory: List<String>)`；拼请求时只带最近 12 轮、记忆最多 5 条。
- `data class DirectorCard(next, face: Int(1–6), gear: Gear, hook, close: Boolean)`。
- `interface Director { suspend fun card(input): LlmResult<DirectorCard> }`；模型失败时用 `Directors.local(input)` 出一张本机卡（六面轮换、二选一钩子，到点 close=true）。
- 解析用 `llm/Tagged.kt`；`【问法】` 认六面名字（精简 / 预判 / 联想 / 提问 / 体系 / 回响 → 1–6）；`【强度】` 认陪 / 诱 / 严；`【收不收】` 含“收”且不含“不收”为 true；钩子截到 25 字。
- 到点（elapsedMin ≥ minutes）时无论模型怎么写都 close=true。

系统提示词，一字不改：

```
你是「一手」语音伙伴的导演。你不直接和用户说话；你写一张很短的导演卡，交给负责说话的实时语音模型去演。
用户的问题：看东西停在第一反应，推理到一半断掉，不会自己提问，容易被别的东西勾走。伙伴的工作是让他自己动脑，不替他想完。
根据【计划】【已经过了】【最近对话】【没接次数】【上次收线】【记忆】，只输出下面五行：
【下一步】伙伴接下来要推的一件事，一句话
【问法】从六面里挑一个：精简 / 预判 / 联想 / 提问 / 体系 / 回响
【强度】陪 / 诱 / 严（没接次数达到 2 时，改用二选一或打赌；用户自己说要逼紧时用严）
【钩子】一句可以直接说出口的话，口语，不超过 25 字，优先二选一、打赌、“如果……会怎样”
【收不收】到点或用户说要结束时写“收”，否则写“不收”
不要替用户总结，不要给答案，不要写这五行以外的内容。
```

**完成标准**：解析、本机兜底、到点强制收、请求截断都有测试。

## 任务 T4：语音链路（本机听写 + 流式回复 + 流式朗读）

不用 Gemini Live。一轮对话 = 本机听写出一句 → 聊天模型流式回一两句 → 第一句出来就开始朗读。

1. **耳朵**：`voice/Ears.kt` 接口（`start()`、`stop()`、事件 `Flow<EarEvent>`：`Partial(text)`、`Final(text)`、`SpeechStart`、`SpeechEnd`）。两个实现：
   - `SherpaEars`：用 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0，有 Kotlin API）做本机流式中文识别 + Silero VAD。先联网核对 sherpa-onnx 当前的 Android 集成方式和推荐的中文流式模型（中英双语流式 Zipformer 或流式 Paraformer），写进 PR 说明。模型不打进 APK：设置里点“下载本机听写模型”才从 sherpa-onnx 的 GitHub 发布页下载到应用私有目录，界面写明大小和来源。
   - `SystemEars`：包一层现有的 `speech/VoiceInput`，作为没下载模型时的兜底。
2. **说完检测**（纯逻辑 `voice/TurnEnd.kt`，重点写测试）：不能一停顿就当说完——用户边想边说。规则：句子完整（有句末语气或标点、不是以“然后、就是、那个、嗯、因为”结尾）静音 0.8 秒算说完；不完整的放宽到 2.5 秒；说了“我想想”“等一下”放宽到 8 秒；任何时候说“好了”“说完了”立刻算完。
3. **流式回复**：`llm/ChatClient.kt` 加流式请求（`stream: true`，解析 SSE），纯函数 `llm/Sse.kt` 有测试。`voice/Sentences.kt` 把流切成句子（。！？；或超过 30 字的逗号处），出一句交一句给朗读。
4. **嘴巴**：复用 `window/Speaker.kt`（语音接口或系统朗读），加一个句子队列。
5. **打断**：朗读中本机 VAD 听到用户开口超过 0.3 秒 → 立刻停朗读、取消还在路上的模型请求、清空队列，转去听。录音源用 `VOICE_COMMUNICATION` 并开回声消除；建议戴耳机（界面提示一次）。
6. 设置里加“语音伙伴”一节：说话用的模型（快、便宜）和导演用的模型（强）可以分开填，都走用户已配置的接口；加一个“语音测试”页：说话、看实时字幕、看每轮耗时（说完 → 第一个字出声）。

**不要做**：后台常开麦克风；存录音；把模型打进 APK。

**完成标准**：`TurnEnd`、`Sse`、`Sentences` 有测试；PR 里写真机验收步骤（说完到出声的耗时、插话能否打断、边想边说会不会被抢话）。

## 任务 T5：会话控制器

依赖 T3、T4。**新文件** `partner/Controller.kt`（纯逻辑，时钟、耳朵、模型都注入），`ui/PartnerScreen.kt`，测试 `partner/ControllerTest.kt`。

- 状态：`OPEN → ACCOMPANY ⇄ PROBE → CLOSE → ENDED`，另有 `PULLBACK`（闲聊超两轮或导演要求拉回）。
- 输入：用户说完的一句（T4 的 `Final`）、伙伴说完、每秒时钟、导演卡到达。输出：动作（请求说话模型、说钩子、请求导演、结束）。
- 没接的阶梯：伙伴在 PROBE 里问完 20 秒内用户没开口 → 第 1 次用钩子缩短重问；第 2 次要求二选一；第 3 次记 `missed += 1` 回 ACCOMPANY。ACCOMPANY 里安静不算没接。
- 导演节奏：每 3 轮用户发言或 90 秒请求一次导演卡，同时最多一个在路上；导演失败用本机卡。
- 计时：到时长进 CLOSE，只问收线两问；把用户回答写进 `partner_session`（T2 的表，route = "live"）和回响队列。
- 用户话里的指令由本机识别（复用 `speech/VoiceCommands.kt` 的写法）：“来一锅”→ 海龟汤，“看一下”→ T7，“结束 / 今天到这”→ 收线，“逼紧一点 / 放松点”→ 换强度。
- 说话模型每轮的请求 = 伙伴设定（T1 的开场白那段）+ 当前导演卡 + 最近 8 轮对话；要求只回一两句、口语、不念导演卡。导演卡不念出来。
- 界面：剩余时间、强度、最近 4 行转写、一个大“停”按钮；麦克风类型的前台服务和常驻通知（通知里也有“停”）。

**完成标准**：阶梯、导演节奏、到点收线、指令识别都有测试（假耳朵、假模型、假时钟）。

## 任务 T6：语音海龟汤和语音刷题

依赖 T5。

- `start_soup` 或用户说“来一锅”：控制器进 SOUP 模式，用 `soup/Soup.kt` 的 `SoupSession` 和 `YishouApp.soupHost` 判；用户的话以“我猜”开头走 `guess`，否则走 `ask`；判出来的结果**只朗读那几个字**（“是 / 不是 / 部分是 / 无关 / 这不是是非题，换个问法”）。问够或猜中后念 ABCD，用户说字母 → `pick`，用锁死的答案核对。聊天模型自己不判。
- 刷题：用 `CardSession` 先说再选：念题干，用户先说答案和思路，再念选项，用户说字母，一手判对错。
- 结果照原样记进 `soup` 表 / 题卡记录。

**完成标准**：模式切换、问 / 猜分流、只念固定字、字母识别（“B”“选 B”“第二个”）有测试。

## 任务 T7：看一眼

依赖 T5。用户说“看一下”“看看屏幕”：用 `look/` 里现有的屏幕授权流程取一帧，缩到最长边 1280、JPEG，交给用户配置的识图模型（`YishouApp.vision`）写一段不超过 150 字的“屏幕上是什么”，放进导演和说话模型的上下文；伙伴据此接着聊。没有授权就提示去授权，不自己弹系统窗口以外的东西。从不定时截图，图片不落盘。

**完成标准**：触发词识别有测试；PR 里写真机验收步骤（看网课时说“看一下”，它能说出屏幕上的内容）。
