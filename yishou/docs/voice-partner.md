# 语音伙伴：任务规格（T1–T7）

给编码代理看的详细规格。总体思路和取舍在作者的规划文档里；这里只写“做什么、改哪里、不做什么、怎样算做完”。每次只做被点名的那一条任务。

## 一句话架构

一手里有一个**会话控制器**。**导演**（用户配置的强文字模型）每隔几轮写一张很短的**导演卡**；**嘴耳层**（Gemini Live API 实时语音，或一手现有的听写 → 模型 → 朗读链路）照着导演卡说话。**执行器**监视前台应用、管计时通知；路线 A 里它负责拉起 Gemini App。**记忆**是一手已有的数据：主线断点、收线句、棋谱（`echo` 表）、题库、海龟汤。

新代码放在两个包里：

- `com.yishou.app.partner`：会话计划、在场统计、导演、控制器（纯逻辑，可在 JVM 上测）。
- `com.yishou.app.voice`：和实时语音接口的连接、录音和播放（Android 部分）。

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

## 任务 T4：Live 连接

**先联网核对官方文档**（Gemini Live API 的 WebSocket 地址、setup / realtimeInput / clientContent / toolResponse 消息格式、当前可用的原生音频模型名、会话时长限制、会话恢复、上下文压缩、打断事件、输入输出转写），把链接和结论写进 PR 说明。不能联网就停下说明，不要凭记忆写协议。

1. `voice/LiveLink.kt` 接口：`connect(system: String, tools: List<ToolSpec>)`、`sendAudio(pcm16k: ByteArray)`、`sendText(text: String, speak: Boolean)`（speak=false 用于把导演卡作为上下文送进去、不要求它立刻回话；官方不支持就在 PR 里写清用什么替代）、`sendToolResult(id, result)`、`close()`；事件 `Flow<LiveEvent>`：`AudioOut`、`InputText`、`OutputText`、`ToolCall`、`Interrupted`、`TurnDone`、`GoAway`、`Closed(reason)`。
2. `voice/GeminiLive.kt`：OkHttp WebSocket 实现；消息的拼装和解析写成纯函数（`voice/LiveProtocol.kt`），用官方文档里的示例 JSON 写单元测试。不引入 Firebase。
3. 音频：`AudioRecord` 16 kHz 单声道 16 位；`AudioTrack` 24 kHz；有 `AcousticEchoCanceler` 就开。收到 `Interrupted`、或本机 VAD（`speech/Vad.kt`）在播放中听到用户开口 → 立刻停播并清空缓冲。
4. 收到 `GoAway` 或断线：带会话恢复句柄重连，最多 3 次；重连后由控制器重发最近一张导演卡。
5. 设置里加“实时语音”一节：API Key（加密保存）、模型名；加一个“实时语音测试”页：连上、说话、看两边的转写。

**不要做**：持续推视频；后台常开麦克风；存录音。

**完成标准**：协议纯函数有测试；PR 里写真机验收步骤（说完到开口的延迟、插话能否打断）。

## 任务 T5：会话控制器

依赖 T3、T4。**新文件** `partner/Controller.kt`（纯逻辑，时钟和连接都注入），`ui/PartnerLiveScreen.kt`，测试 `partner/ControllerTest.kt`。

- 状态：`OPEN → ACCOMPANY ⇄ PROBE → CLOSE → ENDED`，另有 `PULLBACK`（闲聊超两轮或导演要求拉回）。
- 输入：用户整句转写、伙伴说完、每秒时钟、导演卡到达、工具调用。输出：给连接的动作（送导演卡、说钩子、送工具结果、结束）。
- 没接的阶梯：伙伴在 PROBE 里问完 20 秒内用户没开口 → 第 1 次用钩子缩短重问；第 2 次要求二选一；第 3 次记 `missed += 1` 回 ACCOMPANY。ACCOMPANY 里安静不算没接。
- 导演节奏：每 3 轮用户发言或 90 秒请求一次导演卡，同时最多一个在路上；导演失败用本机卡。
- 计时：到时长进 CLOSE，只问收线两问；把用户回答写进 `partner_session`（T2 的表，route = "live"）和回响队列。
- 给实时模型声明的工具：`note(text)`（记一句用户自己说出的判断）、`ask_director()`、`start_soup()`、`end_session()`。
- 实时模型的系统提示：伴读设定（T1 的开场白那段）+ “你会不时收到以【导演卡】开头的文字，照着它推进，不要把导演卡念出来，不要说你收到了导演卡。”
- 备用链路：没有实时语音 Key 时，同一个控制器接一手现有的 `VoiceInput` + `ChatClient` + `Speaker`。
- 界面：剩余时间、强度、最近 4 行转写、一个大“停”按钮；麦克风类型的前台服务和常驻通知（通知里也有“停”）。

**完成标准**：阶梯、导演节奏、到点收线、工具处理、断线重发导演卡都有测试（假连接 + 假时钟）。

## 任务 T6：语音海龟汤和语音刷题

依赖 T5。

- `start_soup` 或用户说“来一锅”：控制器进 SOUP 模式，用 `soup/Soup.kt` 的 `SoupSession` 和 `YishouApp.soupHost` 判；用户的话以“我猜”开头走 `guess`，否则走 `ask`；判出来的结果让实时模型**只念那几个字**（“是 / 不是 / 部分是 / 无关 / 这不是是非题，换个问法”）。问够或猜中后念 ABCD，用户说字母 → `pick`，用锁死的答案核对。实时模型自己不判。
- 刷题：用 `CardSession` 先说再选：念题干，用户先说答案和思路，再念选项，用户说字母，一手判对错。
- 结果照原样记进 `soup` 表 / 题卡记录。

**完成标准**：模式切换、问 / 猜分流、只念固定字、字母识别（“B”“选 B”“第二个”）有测试。

## 任务 T7：看一眼

依赖 T5。用户说“看一下”“看看屏幕”或模型调用 `look()`：用 `look/` 里现有的屏幕授权流程取一帧，缩到最长边 1280、JPEG，作为一张图片送进实时会话一次。没有授权就提示去授权，不自己弹系统窗口以外的东西。从不定时截图，图片不落盘。

**完成标准**：触发词识别有测试；PR 里写真机验收步骤（看网课时说“看一下”，它能说出屏幕上的内容）。
