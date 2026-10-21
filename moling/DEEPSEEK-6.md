# 墨灵 · 给 DeepSeek 的施工文档（第六批：反自动驾驶，Z1–Z9）

> 2026-10-21。目标：他脑子掉线、靠本能在刷 / 在横跳时，墨灵像一颗“小石子”砸进来，把他拉回手动挡；
> 而且**每次出现的方式不一样**，用久了也不会被大脑当成背景噪音过滤掉（反免疫）。
>
> **规则和逻辑 Claude 已经写好并测过**（`tools/core-tests` 394 个测试全过）：
> 自动驾驶识别、反免疫账本、挑出现方式、捉虫题、体系图沙盘的数据、大脑的接口。
> **你只做界面和 Android 胶水**：把大脑挑出的每一种“出现方式”做出来。
> 这一批**只改墨灵**（`moling/`），不碰总控、不碰“一手”。

---

## 0. 先看懂这件事

- “自动驾驶”≠“在玩”。是**不动脑、靠手指本能**：5 分钟里在几个应用之间来回切、退出去又马上回到同一个短视频、拿起手机几秒又放下、一个应用连刷很久。
  大脑用“使用情况访问”（只有应用和时间）算一个 0–100 的分，60 以上叫醒墨灵（`Trigger.AUTOPILOT`）。
- 墨灵醒了以后，大脑会挑一种**出现方式**（`LiveTurn.modality`）。现在只有两种做好了（拦人页、底部卡片），其余五种是你这一批要做的：

| 方式 | 是什么 | 你做的任务 |
|---|---|---|
| `PET` 桌宠 | 墨滴从屏幕边上跳出来，头顶一个气泡说一句 | Z1 |
| `STARE` 盯着你 | 墨滴变大、不说话，盯着他，头顶一个 45 秒的圆环倒计时，逼他好奇点一下 | Z2 |
| `WHISPER` 耳语 | 戴着耳机时，把音乐压低，耳机里说一句；说完桌宠气泡亮 8 秒，点了能回 | Z3 |
| `COUNCIL` 参谋弹幕 | 教练 / 军师 / 对手 / 朋友四句话像弹幕一样从屏幕上方飘过 | Z4 |
| `QUIZ` 捉虫题 | 把他自己的一条论断改错一处、切成几段，他点出错的那段 | Z5 |

- **反免疫**：每次出现后，界面要把他的反应报回大脑（`BrainHost.react`）。同一种方式连着两次被秒划 / 敷衍 / 不理，大脑就让它歇 48 小时，下次换别的。**报反应是每个任务的验收项。**

---

## 1. 开工前

1. 拉分支：`git fetch origin && git checkout -b deepseek-autopilot origin/ccr-b3c7616e-xlohkd`。推送只推这个分支。
2. 这一批分两组，可以开两个会话并行：
   - **A 组**（Z1–Z4、Z6、Z8）：新建 `app/live/PetOverlay.kt`、`app/live/Whisper.kt`；改 `app/live/Appear.kt`、`AndroidManifest.xml`（加一个 service）、`app/data/Settings.kt`（加开关）、`app/ui/SettingsScreen.kt`（“活的墨灵”那一节加开关）。
   - **B 组**（Z5、Z7、Z9）：新建 `app/live/QuizActivity.kt`、`app/ui/AtlasMapView.kt`；改 `app/live/LiveScreens.kt`（只在拦人页加一个按钮）、`app/ui/AtlasScreen.kt`、`app/ui/AboutScreen.kt`、`AndroidManifest.xml`（加一个 activity）；`Appear.kt` **只改 QUIZ 那一行**。
3. **不改** `com/moling/core/` 下任何文件（规则在那里）。觉得规则不对，写进 `moling/DEVLOG.md` 的“给 Claude”一节。
4. **不加任何权限**。悬浮窗用已有的 `SYSTEM_ALERT_WINDOW`；没给就退回底部卡片（`Appear.show` 已经这么做）。
5. 纯逻辑测试：`cd tools/core-tests && gradle test` 必须一直全绿。Android 编译看 CI（`moling-android`）。
6. 大脑每次随机挑方式，真机试某一种时，临时把 `Appear.show` 写死成那一种，试完改回来（不要提交写死的版本）。

### 你会用到的（都已写好）

| 用途 | 调用 |
|---|---|
| 这次怎么出现 | `turn.modality: Modality`（`PEEK / GATE / WHISPER / PET / STARE / COUNCIL / QUIZ`）、`turn.mood`、`turn.line`、`turn.quiz: Quiz?` |
| 分发入口 | `app/live/Appear.kt` 的 `show(context, turn, label)`——你把各分支换成真的实现 |
| 能不能用 | `Appear.can(context)`；耳语的判断写在 `Appear.canWhisper(context)`（Z3 实现） |
| 报反应 | `BrainHost.react(modality, reaction, ms)`；`Reaction`：`REPLIED`（认真回了 / 点了“聊这个”）、`PERFUNCTORY`（回了“嗯”“好”，用 `Live.perfunctory(text)` 判断）、`DISMISSED_FAST`（3 秒内划掉）、`DISMISSED`、`IGNORED`（一直没碰、到时间自己消失）、`ESCAPED` |
| 回她一句 | 打开 `LiveScreens.peek(context, mood, line, modality)`（底部卡片，自带回复框和报反应） |
| 她的样子 | `ui/MolingFace.kt` 的 `MolingFace(mood, modifier)`；插画在 `ui/art/InkArt.kt`（`InkRing`、`InkRipple`、`InkHalo`……） |
| 念出来 | `ui/Speaker.kt` 的 `Speaker.speak(text) { 念完回调 }` |
| 参谋 | `BrainHost.council(question): Council?`（`coach / strategist / critic / friend / together`，要调模型，可能 null） |
| 捉虫题 | `Quiz(parts, wrong, fix, source)`；再要一道：`BrainHost.quiz()`；答完：`BrainHost.quizResult(right)` |
| 沙盘数据 | `com.moling.core.atlas.AtlasMap.islands(state.atlas)` → `Island(name, dots, gaps, lit, progress)`；`Dot(claim, kind, lit)` |
| 账本摘要 | `com.moling.core.live.Habits.summary(state.habits, now)` |
| 永不碰的应用 | `com.moling.core.power.Charter.neverTouch` |

---

## 2. Z1（A 组）：桌宠 `PET`

**建什么：** `app/live/PetOverlay.kt`，一个普通 `Service`（参考 `overlay/OverlayService.kt` 怎么在 Service 里放 `ComposeView` 和 Lifecycle）。

1. `PetOverlay.show(context, mood, line, modality = PET)`：起服务、加一个**只有墨滴和气泡那么大**的悬浮窗（`TYPE_APPLICATION_OVERLAY`，`FLAG_NOT_FOCUSABLE`，`WRAP_CONTENT`），**不盖住别的应用、不吞别处的点击**。
2. 出场：从屏幕右边（或上次的位置）弹出来——先探头（只露半个），0.4 秒后整个跳出来，落地时压扁一下再弹回（`animateFloatAsState` + spring）。头顶一个纸色气泡（`Paper` 底、细墨线边）写 `line`，最多 3 行，长了省略。
3. 交互：
   - 点墨滴或气泡 → `LiveScreens.peek(...)` 打开底部卡片（卡片自己报反应），桌宠收起；
   - 往屏幕边上拖 → 收起，报 `DISMISSED`（出现不到 3 秒就拖走报 `DISMISSED_FAST`）；
   - 长按 → 收起，一小时内不再用桌宠（`SharedPreferences` 记个时间，`Appear.show` 里这一小时 PET 改成 PEEK），报 `DISMISSED`；
   - 12 秒没人碰 → 缩回边上消失，报 `IGNORED`。
4. 位置：可拖动，松手吸到左右边；记住位置（`SharedPreferences`，和 `OverlayService` 用不同的键）。
5. `Appear.show` 里：`Modality.PET -> PetOverlay.show(...)`；没悬浮窗权限时仍走 `peek`。

**验收：** 在设置里把“她多主动”调到“多”，开着 B 站来回切几个应用 → 一两分钟内墨滴从边上跳出来说一句；点她出卡片；不碰 12 秒自己缩回；调试页日志里能看到反应记了。

---

## 3. Z2（A 组）：盯着你 `STARE`

同一个服务，另一种样子：

1. 墨滴出现在屏幕**中上部**，是平时的 2 倍大（约 140dp），表情用 `turn.mood`（多半是 `POUT` 或 `CURIOUS`），**不说话、没有气泡**；身后一圈 `InkRing`，45 秒从满走到空。
2. 每 6 秒眨一次眼、身体轻轻转向屏幕中间（`MolingFace` 自带呼吸眨眼，你只要加一点左右晃）。
3. 点她 → 气泡这才冒出来写 `line`，同时打开 `peek`；报 `REPLIED` 的事交给 peek。
4. 45 秒没点 → 她叹口气（换 `Mood.SAD` 一秒）缩回去，报 `IGNORED`。
5. 窗口只有墨滴和圆环那么大，不挡别的地方。

**验收：** 把 `Appear.show` 临时写死成 STARE 试：她出现、盯着、圆环在走；点她冒气泡并打开卡片；不点 45 秒后缩回。

---

## 4. Z3（A 组）：耳语 `WHISPER`

**建什么：** `app/live/Whisper.kt`。

1. `Appear.canWhisper(context)`：
   - `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 里有有线耳机 / 蓝牙耳机（`TYPE_WIRED_HEADSET`、`TYPE_WIRED_HEADPHONES`、`TYPE_BLUETOOTH_A2DP`、`TYPE_USB_HEADSET`、`TYPE_BLE_HEADSET`）；
   - 不在 23:30–07:00；
   - 设置开关“她可以在耳机里说话”开着（默认开，放在“活的墨灵”那一节）。
2. `Whisper.say(context, mood, line)`：
   - 申请音频焦点 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`（`AudioFocusRequest`，用途 `USAGE_ASSISTANT`）——别的应用的声音会自动压低；
   - `Speaker.speak(line) { 放掉焦点；PetOverlay.show(context, mood, "", WHISPER) 只亮 8 秒 }`——桌宠气泡写“回她”两个字，点了打开 `peek`（带上 `line`）；
   - 8 秒没点报 `IGNORED`，点了交给 peek。
3. **不自己开麦克风。** 要回她就点一下（打开卡片后可以用卡片里的输入，或者以后的对讲）。
4. `Appear.show` 里：`Modality.WHISPER -> Whisper.say(...)`。

**验收：** 戴耳机放音乐 → 让她以耳语出现：音乐变小、耳机里一句、音乐恢复、边上亮 8 秒“回她”。拔掉耳机后她不会用耳语。

---

## 5. Z4（A 组）：参谋弹幕 `COUNCIL`

1. `Appear.show` 里：`Modality.COUNCIL ->` 在后台协程里调 `BrainHost.council("他现在在「$label」里。接下来十分钟他该怎么办？")`；
   - 拿到 null（模型没接上）→ 退回 `PetOverlay.show(mood, line, PET)`；
   - 拿到了 → 弹幕。
2. 弹幕：一个**整屏但不可触摸**的悬浮窗（`FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE`，`MATCH_PARENT`；**窗口 `alpha` 不超过 0.8**，这是 Android 12 起不可触摸悬浮窗的要求，超过了系统会挡住他的操作）。
   - 屏幕上 1/3 区域，四行从右往左飘过，间隔 0.8 秒先后出发，每行 8 秒飘完：`教练：…`、`军师：…`、`对手：…`、`朋友：…`；文字墨色、半透明纸色底条，字号 15sp。
   - 飘完后弹幕窗口关掉，桌宠带着 `together`（“合起来：…”）出来（`PET` 的样子），点了打开 `peek`。
3. 反应照 PET 的规则报（报 `COUNCIL` 这个方式）。

**验收：** 弹幕飘的时候他照样能滑、能点（不挡操作）；飘完桌宠带着合起来的那句出来。

---

## 6. Z5（B 组）：捉虫题 `QUIZ`

**建什么：** `app/live/QuizActivity.kt`（透明主题，和 `PeekActivity` 一样从底部出一张纸卡）。

1. 打开方式：`QuizActivity.open(context, mood, line, quiz)`，`quiz` 的 `parts`（用 `\u0001` 拼成一个字符串）、`wrong`、`fix`、`source` 放 extras。
2. 卡片：上面 `MolingFace` + `line`；下面一行字“这句话有一处被我改错了，点出来：”；`parts` 一段一个圆角片（`FlowRow`，细墨线边）。
   - 点对 → 那段变成印泥红、划线，下面写“原来是：$fix”，墨滴换 `HAPPY`；`BrainHost.quizResult(true)`；报 `REPLIED`；2 秒后可以关。
   - 点错 → 那段抖一下变灰，提示“不是这段”；第二次点错就直接揭晓；`quizResult(false)`；仍报 `REPLIED`（他动脑了）。
   - 点外面关掉：报 `DISMISSED` / `DISMISSED_FAST`；15 秒没碰自己关：报 `IGNORED`。
3. `Appear.show` 里：`Modality.QUIZ -> turn.quiz?.let { QuizActivity.open(...) } ?: LiveScreens.peek(...)`。
4. **拦人页加一条路**（`LiveScreens.kt` 的 `GateActivity`，“回她”按钮那一行下面）：一个文字按钮「做一道捉虫题」→ 调 `BrainHost.quiz()`，在拦人页里用同样的片段样式出题；答对后自动调一次 `BrainHost.gateReply("我刚答对了你的捉虫题：原来是「${quiz.fix}」")`（放不放还是她和硬规矩说了算）。拿不到题就提示“现在出不了题”。

**验收：** 设置里写过一条“对你的了解”（例如“每天写 3 段就不会断手感”）→ 拦人页点「做一道捉虫题」→ 出现切成几段的句子，其中数字被改了；点对后她放行或接着问。

---

## 7. Z6（A 组）：褪色（默认关）

他已经在自动驾驶、而且还停在那个娱乐应用里时，屏幕慢慢变灰，把刷屏的爽感抽掉一点。**完全由他自己打开**。

1. 设置“活的墨灵”那一节加开关“刷着刷着慢慢褪色”（默认**关**），下面一行说明：“只在她觉得你在机械地刷、而且你还停在娱乐应用里时；只让画面变灰，不挡操作；离开就恢复；每天最多 20 分钟。”
2. `PetOverlay` 里再放一个整屏、不可触摸的窗口（同 Z4 的标志；**alpha 上限 0.8**），内容是一层灰（`Color(0xFF808080)`），透明度从 0 在 10 分钟里慢慢升到 0.35。
3. 什么时候开 / 关：`BrainHost.liveTick` 每分钟一次（**这一处你可以改 `BrainHost.kt`，只加不改**）：
   - 开：开关开着 + 这次心跳算出的自动驾驶 `on` + 前台类别是 `ENTERTAINMENT` / `SHOPPING` + 包名不在 `Charter.neverTouch` + 今天还没用满 20 分钟 → `PetOverlay.fade(context, level)`；
   - 关：上面任何一条不满足、拦人页出现、亮度回来的时候 → `PetOverlay.fade(context, 0f)` 并移除窗口。
4. 用了多少分钟记在 `SharedPreferences`（按日期）。

**验收：** 打开开关，连刷短视频直到她判断自动驾驶 → 画面一点点变灰，手指照常能滑；切到微信立刻恢复；关掉开关不再出现。

---

## 8. Z7（B 组）：体系图沙盘

**建什么：** `app/ui/AtlasMapView.kt` 的 `AtlasMapView(islands, onOpenTopic)`；在 `AtlasScreen.kt` 顶部加一个切换「列表 / 沙盘」。

1. 每个主题一座“岛”：一个淡墨圈（大小按论断数），圈里论断是点：亮的（`dot.lit`）用实心墨点 + 一点 `WashGold` 光晕，雾里的用淡墨空心点；缺口用小黑洞（深墨实心圈 + 外面一圈虚线）。
2. 岛下面一行 `AtlasMap.line(island)`，再一根细细的进度线（`island.progress`）。
3. 点岛 → `onOpenTopic(topicId)`，回到列表并切到那个主题（看 `AtlasScreen` 现有的切换主题写法）。
4. 岛的位置：按顺序排成两列错开，像群岛；不需要力导向布局。
5. 静态画就行，不用一直动（省电）；只有缺口的黑洞可以慢慢转（`InkGalaxy` 那种）。

**验收：** 体系页切到“沙盘”，能一眼看出哪个主题亮了几成、还有几个黑洞；点岛回到那个主题的列表。

---

## 9. Z8（A 组）：和演员聊时的小胶囊

他点“去聊”打开 Gemini / ChatGPT 聊的时候（`overlay/Presence.kt` 的 `Presence.session` 不为 null、前台是演员），屏幕顶角挂一个很小的胶囊：

1. 内容：这次要聊的那一问（`session.opening` 里的问题，取第一行，最多 16 字）+ 已经聊了几分钟（`mm:ss`）。
2. 胶囊用 `PetOverlay` 的窗口（小，`FLAG_NOT_FOCUSABLE`），可拖；点一下展开成两行（完整问题 + “聊完了就回来跟我说一句结论”），再点收起。
3. 前台离开演员时胶囊消失（收线由 `Presence` 现有的流程管，不要重复做）。
4. 在 `HeartService` 的心跳里判断显示 / 隐藏（**`HeartService.kt` 只加不改**）。

**验收：** 从墨灵“去聊”打开 ChatGPT → 角上出现胶囊、时间在走；切回桌面胶囊消失，`Presence` 原来的收线照常。

---

## 10. Z9（B 组）：她怎么出现（看板）

`AboutScreen.kt` 最下面加一节「她怎么出现」：

1. 一句说明：“同一种方式被你连着秒划两次，她会让它歇两天，换别的方式来。”
2. `Habits.summary(state.habits, now)` 每行一条（例如“桌宠：5 次，管用 60%（歇着）”）。
3. 每种方式后面一个小插画（PEEK 纸页、GATE 沙漏、WHISPER 涟漪、PET 墨滴小脸、STARE 圆环、COUNCIL 对话、QUIZ 星涡），用 `ui/art` 里现成的。
4. 设置“活的墨灵”那一节（**A 组改 `SettingsScreen.kt`，B 组不要碰它**）——如果 A 组有空，加“不想要的方式”多选（桌宠 / 盯着 / 耳语 / 弹幕 / 捉虫题），存在 `Settings.kt`；`Appear.show` 里被他关掉的方式一律改成 `PEEK`。

---

## 11. 底线（写在代码里的不改，你做的界面也不能绕开）

1. **不读其他应用的内容**：不用无障碍读别的应用的文字、控件、坐标；自动驾驶只用“哪个应用、几点到几点”。
2. **不挡别的应用的操作**：桌宠 / 胶囊的窗口只有它自己那么大；弹幕和褪色的整屏窗口必须 `FLAG_NOT_TOUCHABLE`、`alpha ≤ 0.8`。真正“拦住”只有拦人页，它的规矩不变（只拦娱乐 / 购物、每天 3 次、5 分钟、3 轮、永远有「急事」）。
3. **不替他做决定**：任何出现方式都只是说话、提问、出题；不自动定稿、不自动交总控、不倒计时“默认生效”。
4. **不自己开麦克风**，不在后台录音。
5. **永不碰的应用**（电话、短信、支付、地图、设置、总控、墨灵自己、微信……）里不出现桌宠 / 弹幕 / 褪色。
6. **深夜 23:30–07:00** 只有“夜里躺着刷”和拦人照旧，其他方式不出现（大脑已经这么判断，界面不要自己加）。
7. 他在设置里关掉“她会主动找你”，这一批的东西全部不出现。

---

## 12. 交付

- 每个任务一个提交，提交说明写任务号（`Z1 桌宠` …）。
- 做完在 `moling/DEVLOG.md` 末尾加一节“第六批（DeepSeek）”：做了什么、真机怎么验、没做完的、给 Claude 的问题。
- 推 `deepseek-autopilot`，CI `moling-android` 绿了告诉用户，由 Claude 合进工作分支。

### 从 Gemini 那份方案里**没采用**的（以及原因）

| 没采用的 | 原因 |
|---|---|
| 用无障碍读别的应用的文字和按钮坐标、让桌宠趴在“下一集”按钮上吞点击 | 读别的应用的内容、挡别的应用的操作，越过了底线 1、2；“拦”只走拦人页 |
| 滑动节律 / 连点遥测（总控无障碍） | 要在总控里新增对别的应用操作的监听；先用使用记录做，够用再说 |
| 水道 20 分钟不改就默许生效、草靶子倒计时自动入库 | 不替他做决定（底线 3）。改成：被冷落多了，她用“我猜……对吗？”来问 |
| 她说完自动开麦 6 秒 | 不自己开麦克风（底线 4）。改成：亮 8 秒“回她”，点了再说 |
| 拆掉 / 改造“一手” | 不动已有体系。捉虫题是墨灵自己的小题 |
| 用无障碍往 Gemini / ChatGPT 自动粘贴 | 继续用剪贴板 + 分享（已有）；Z8 只加一个小胶囊 |
