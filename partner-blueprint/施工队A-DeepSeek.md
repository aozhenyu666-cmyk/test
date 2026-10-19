# 墨灵 · 施工队 A（DeepSeek）施工文档

> 2026-10-19。两队同时施工：**施工队 A（你，DeepSeek）做小窗、接力、感知、保活这些 Android 侧的东西；施工队 B（Claude）做导演和记忆这些纯逻辑。**两边只通过一份接口约定对接，互不等待。
> 背景（可以先不看，有疑问时再查）：仓库 `partner-blueprint/README.md`（第三版总体设计）、`partner-blueprint/MIND.md`（记忆和能力）。

---

## 0. 一分钟看懂要做什么

用户想要一个会主动找他、记得他、在他和 Gemini / ChatGPT 说话时帮他递问题的 AI 伙伴，叫“墨灵”。

- **演员**：Gemini Live 和 ChatGPT 的**官方 App**（用户有会员）。我们不接它们的 API，也不自己做语音模型。
- **导演**：总控 App 里的一个小文字模型（DeepSeek API），负责分析局面、准备问题、读回结果。**这部分由 Claude 做。**
- **小窗**：浮在 Gemini / ChatGPT 上面的小窗口。导演准备好问题，用户看着小窗把问题说给演员听，聊完对着小窗说一句结论。**这部分由你做。**

你做的是导演的“身体”：眼睛（感知事件）、嘴（小窗、通知、朗读）、腿（打开 Gemini / ChatGPT、交接开场白、统计在场），还有保命（无障碍健康监测）。

---

## 1. 代码在哪、分支怎么开

- 仓库 `aozhenyu666-cmyk/test`，**从 `ccr-b6e667ac-bsoz44` 分支拉你的分支**（例如 `deepseek-window`）。这个分支包含总控全部代码，以及接口约定。
- 总控是 Kotlin + Jetpack Compose 的 Android App，在 `zongkong/` 目录。下文路径都相对 `zongkong/app/src/main/java/com/zongkong/`。
- **接口约定**：`core/director/Contract.kt`。先通读这个文件，所有对接都靠它。
- **替身导演**：`core/director/FakeDirector.kt`。真导演还没好时用它，行为固定，接口和真导演一样。真导演做好后叫 `DirectorEngine`，换掉构造那一行就行。
- **你只改 `app/` 下的代码，以及新加的 `app/...` 文件。不要改 `core/director/` 下的文件**（那是施工队 B 的）。觉得约定需要改，就在你的 DEVLOG 里写清楚想怎么改、为什么，用户会转告 B。
- 注意：仓库的 GitHub Actions 会在任何分支改动 `zongkong/` 后自动构建，测试通过就**覆盖用户手机上的“最新版”下载链接**（`zongkong-latest`）。所以每次推送前要保证 App 能正常用；没做完的功能用开关关着（默认关）。

---

## 2. 你要做的五件事

顺序是建议，可以按你的判断调整。每件做完都在 `zongkong/DEVLOG.md` 写一节：做了什么、为什么、没做什么、用户在真机上怎么验收。

### A1 导演宿主和三个接口实现

在 App 里放一个导演实例，并实现约定里要你实现的接口：

| 接口 | 你要做的 | 提示 |
|---|---|---|
| `FactsSource` | 从总控 `Store`（`app/data/Store.kt`）取出此刻的事实，填 `Facts` | 主线、计划、娱乐时长、信用、态度这些总控都有；拿不到的写进 `unknown` |
| `Ask` | 转接总控已有的文字模型接口 `core/Llm.kt` 的 `LlmApi` | 失败、超时都返回 null；统计每次请求的字数，给 B 的花费统计用 |
| `MindStore` | 把一段 JSON 存在手机本地（原子写入，写坏了能回退到上一份） | 可以照总控现有的存储方式做 |
| `WindowPort` | 见 A2 | |

建一个 `app/director/DirectorHost.kt`（名字随意），持有 `Director` 实例，现在先用 `FakeDirector()`。设置里加一个总开关“墨灵导演（试验）”，**默认关**。

### A2 小窗

这是这一轮的主角。

- **收起时**：一个小圆点停在屏幕边上，可以拖动。总控已有墨灵悬浮层（`app/system/Pet.kt`，借无障碍服务的悬浮层），能复用就复用，或者另做，你来判断。
- **展开时**：显示 `Card`：
  - 标题、现在这一问（`ask`，最显眼）；
  - `sayLines`：可以直接念给演员的话，点一下复制；
  - 有 `choices` 时显示这些选项；没有时显示默认四个按钮：**继续这一问 / 卡住了 / 有结论 🎤 / 休息**；
  - `queued > 0` 时显示“还有 N 个”；
  - `minutes > 0` 时显示倒计时；
  - `confidence` 不为空时，小字显示“我猜（把握：中）”。
- **有结论 🎤**：按下后用系统语音识别听写一句话（总控已有听写，`app/system/Voice.kt`），把原文包成 `Reply.Conclusion` 交给导演。听写不可用时让用户打字。
- **每个操作**都包装成对应的 `Reply` 交给 `director.onReply()`，把返回的卡显示出来；返回 null 就收起。被划掉或超时没反应时发 `Reply.Dismissed`。
- `speak` 不为空且不在静默时段时，用总控已有的朗读（`app/system/Speaker.kt`）念出来。
- 什么时候自动展开：导演返回卡时；用户打开 Gemini / ChatGPT 时（展开开场白，见 A3）。其他时候保持小圆点。
- **样子**：简洁、一眼能看懂，一次只突出一个问题。具体设计你自由发挥，可以参考总控现有的风格。

### A3 接力：去 Gemini / ChatGPT 聊，再接回来

- 当卡的 `actor` 不是 `NONE`，用户点“去聊”时：调 `director.opening(actor, cardId, now)` 拿到 `Opening`。
- 把开场白交给演员。下面三种方式都试，探针 P3（见第 4 节）的结果决定默认用哪种：
  1. 复制到剪贴板，打开演员 App，小窗提示“粘贴后再点 Live”；
  2. 用系统分享把文字发给演员 App；
  3. 小窗展开开场白全文，让用户照着念。
- 打开演员 App：Gemini 包名 `com.google.android.apps.bard`，ChatGPT `com.openai.chatgpt`；没装就打开网页版。能直达 Gemini Live 最好（探针 P4）。
- **会话中**：小窗依次显示 `Opening.cards`，用户点按钮就往下走（这就是蓝图里的 A 档、B 档）。统计演员 App 在前台的时间和离开次数（离开超过 2 分钟算一次，提醒一次）。语音伙伴分支里的 `Presence`（`codex/voice-partner-t4a` 分支 `yishou/app/src/main/java/com/yishou/app/partner/Partner.kt`）可以参考。
- **结束**：倒计时到、用户点“有结论”、或者离开太久，生成 `SessionLog` 交给 `director.onSession()`。
- **C 档（可选，看探针 P2 的结果）**：如果无障碍能读到 Gemini Live 的字幕或 ChatGPT 的对话文字，每 30–60 秒把最近几句作为 `Sense(INBOX, payload = {source: "字幕", text: ...})` 交给导演。读不到就算了，**不要假装读到了**。这需要的读屏权限只对 Gemini / ChatGPT 两个包开放，不要扩到别的 App。

### A4 感知：把手机上的事变成 `Sense`

把下面这些事件交给 `director.onSense()`，导演返回卡就交给小窗：

| 事件 | 从哪来 |
|---|---|
| `UNLOCK` / `SCREEN_OFF` | 系统广播 |
| `FOREGROUND` | `app/guard/GuardService.kt` 已经收到前台切换，转发一份；category 用总控已有的拦截名单 / 娱乐分类判断，Gemini 和 ChatGPT 记为 `ACTOR` |
| `LONG_ENTERTAINMENT` | 同一个娱乐 App 连续 15 分钟（先写死，以后再调） |
| `PLAN_DUE` / `PLAN_PROGRESS` | 总控的行动到点、开始、做完、卡住 |
| `IDLE_START` | 没有进行中、也没有 2 小时内排着的行动 |
| `PAYMENT` | 先不做（以后读付款通知） |
| `ACTOR_OPEN` | 前台切到 Gemini / ChatGPT |
| `HEALTH` | 见 A5 |
| `TICK` | 每 30 分钟兜底一次 |
| `NIGHT` | 每晚一次（默认 23:00，用户的复盘时间之后） |

**避免各说各话**：总控现在有几个各自主动找用户的地方：`app/system/Partner.kt`（解锁就问）、`InitiativeRunner`、`HqRunner`、`TakeoverRunner`。导演开关打开时，前三个不再自己弹，改成发事件给导演，由导演统一决定说什么；`TakeoverRunner`（强督导）保持不变。导演开关关闭时，一切保持原样。

### A5 健康：分四层看无障碍

- 记录四层状态：权限开没开、服务连没连上、最近一次收到事件是什么时候、目标页面读不读得到。变化时发 `Sense(HEALTH)`，`payload` 里放这四项。
- 区分“用户自己在设置里关掉”和“意外掉线”。前者尊重并记录，后者尝试修复。总控已有自愈和诊断（`app/system/SelfHeal.kt`、`app/guard/Diag.kt`），在上面扩展。
- 跑一周后，从日志里看掉线主要掉在哪一层（探针 P7）。用户希望再做一个独立的小“哨兵” App 来保活（设计见 `partner-blueprint/v2-design.md` 第 6 节）。等这一周的数据出来，你再判断要不要做、怎么做。

---

## 3. 底线（必须遵守）

1. **规则由代码决定，模型说了不算。**导演和演员都不能放宽规则、发娱乐时间、扩大监控范围、改紧急退出。放宽规则仍然走总控现有的 24 小时冷静期。
2. **读不到就说读不到。**C 档读不到字幕时停在 B 档，不假装知道对话内容。
3. **电话、短信、支付、地图、紧急退出永远不当惩罚手段。**
4. **不接 Gemini / ChatGPT 的 API。**
5. **读屏只对 Gemini / ChatGPT 开放。**现有守门服务继续只读包名。
6. **导演开关默认关，关着时总控的行为和现在完全一样。**
7. **零食预算、时长这类数字不要预设**，由用户自己定。

---

## 4. 先做的探针

能写成调试按钮的就写成按钮；要用户在手机上操作的，写成 5 步以内的说明放进 DEVLOG。

| # | 验证什么 | 通过的标准 | 不通过怎么办 |
|---|---|---|---|
| P1 | 小窗能不能浮在 Gemini Live 全屏界面和 ChatGPT 语音界面上 | 看得见、点得动 | 改用通知卡片 + 朗读 |
| P2 | 无障碍能不能读到 Gemini Live 的字幕和 ChatGPT 的对话文字 | 能连续读到最近几句 | 停在 B 档 |
| P3 | 开场白怎么交给 Gemini：粘贴后再进 Live 它还记不记得？分享过去会不会预填？ | 进 Live 后问“我们今天聊什么”，它能答对 | 照着小窗念 |
| P4 | 能不能从总控直接打开 Gemini Live | 一步进 Live | 打开 Gemini 首页，小窗提示点哪里 |
| P5 | 小窗里的系统语音识别在 vivo 上能不能用 | 能听写 | 打字，或用总控已有的听写接口 |
| P7 | 无障碍在 vivo 上实际掉在哪一层 | 有一周的日志 | 决定哨兵怎么做 |

---

## 5. 和施工队 B 怎么会合

- B 在同一个仓库的 `ccr-b6e667ac-bsoz44` 分支上做 `core/director/` 下的真导演（`DirectorEngine`）：局面、假设账本、议程、开场白、接力棒更新、夜间整理、日记、事故簿、花费统计。
- 你先用 `FakeDirector` 把整条链跑通：事件 → 小窗 → 回应 → 去 Gemini → 收线 → 回来。B 做好后，把 `FakeDirector()` 换成 `DirectorEngine(...)`，构造参数就是你实现的 `FactsSource`、`Ask`、`MindStore`。
- **会合时验收的场景**：约定的做事时段里，主线过点没开始，用户在刷短视频。依次发生：小窗问一个点选题 → 用户选“卡住了” → 去 Gemini 聊 10 分钟 → 对着小窗收线 → 接力棒更新、下一步变成行动 → 30–60 分钟后导演回头看做了没有。

---

## 6. 给你的自由

除了第 3 节的底线和第 2 节的接口，界面、交互细节、命名、实现方式、先后顺序都由你判断。觉得某处设计不合理就直接改，在 DEVLOG 里写一句为什么。遇到 Android 系统限制做不到的，如实写下来，并给出替代方案。
