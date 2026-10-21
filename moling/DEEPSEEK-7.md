# 墨灵 · 给 DeepSeek 的施工文档（第七批：心智引擎的界面，E1–E6）

> 2026-10-21。方向：墨灵不只是“把掉进坑里的人拉出来”，平时也要让他的脑子转起来——
> 她收集、记住、推演，递到他面前的永远是“带漏洞的靶子”和“点一下的选项”，他只做判断、捉虫、拍板。
>
> **核心逻辑 Claude 已经写好并测过**（`tools/core-tests` 410 个测试全过）：
> 思维转速、三手棋、刷完一大段的“过路费”、体系图拓扑检索、她把新旧论断连线、采蜜简报、她摸清的规律。
> 心跳里已经接好：闲着又好久没动脑时她会来下棋；刷完一大段离开时收一回合过路费；想完会采一次蜜；夜里会提规律。
> **你只做界面**。不改 `com/moling/core/`、不加权限、不碰总控和“一手”。
>
> 还没做的第六批 D 组（`DEEPSEEK-6.md` 末尾“续二”）先做完，再做这一批。

---

## 0. 开工

1. `git fetch origin && git checkout -b deepseek-mind origin/ccr-b3c7616e-xlohkd`（如果 D 组还在 `deepseek-autopilot-b` 上没合，先 `git merge origin/deepseek-autopilot-b`）。
2. `cd tools/core-tests && gradle test` 一直全绿；`node tools/relay-test/test.mjs` 输出 `relay ok`。
3. 每个任务一个提交，说明写任务号。

### 你会用到的（都已写好）

| 用途 | 调用 |
|---|---|
| 状态 | `BrainHost.state.value`：`mindMarks`、`chess`（正在下的一局，null = 没有）、`chessDone`（最近 20 局）、`briefs`、`rules`、`atlas` |
| 思维转速 | `com.moling.core.spar.Rpm.score(marks, now)`、`Rpm.line(marks, now)`、`Rpm.byHour(marks, dayStart)`、`Rpm.LOW`（=3） |
| 开一局 | `BrainHost.spar(): LiveTurn?`（`line`、`choices`、`chess = true`；null = 体系图里没东西可下） |
| 下一手 | 和所有选项一样：`BrainHost.tap(choice): LiveTurn`（正在下棋时自动当成棋；`choices` 为空 = 这局下完） |
| 下完的一局 | `Chess.score`（0–3）、`conclusion`（挂进体系图的新结论）、`source`、`bait`、`flaw`、`moves` |
| 简报 | `Brief(question, lines, status = new/kept/dropped)`；`BrainHost.keepBrief(id, keep): Int`（收进体系图几条） |
| 规律 | `Rule(whenText, thenText, why, status = proposed/kept/dropped)`；`Rules.line(rule)`；`BrainHost.setRule(id, keep)` |
| 体系图来源 | `Node.source`：`三手棋`、`采蜜（待核）`；`Edge.note` 以 `她连的：` 开头的是她推出来的线 |

---

## E1：三手棋的样子

她用任何方式出现（底部卡片、桌宠、对话页）时，如果 `turn.chess == true` 或 `BrainHost.state.value.chess != null`：

1. 卡片 / 气泡顶上一个小标签“三手棋 · 第 N 手”（N = `chess.turn`，下完显示“收官”），右边三个小圆点表示进度（下过的实心）。
2. 她的“暴论”（`chess.bait`）用一个细墨线框框起来，像棋盘上的一枚子。
3. 选项照常是按钮；最后一个是“我自己说一句”（`Chesses.FREE`）——点它时**不要调 `tap`**，而是展开一个单行输入框，发出去时用 `BrainHost.tap(他写的话)`。
4. 下完（`choices` 为空、`chess == null`）：显示分数（0–3 颗墨点）和“新结论挂进体系图了”那句；点一下跳到体系页（对话页里就是普通一条话，不用跳）。

## E2：“来一局”入口

1. 对话页台子（`ChatScreen.kt` 的 `Stage`）“让她想想”旁边加「来一局」→ `BrainHost.spar()`；拿到的 `choices` 交给对话页已有的选项按钮（`choices = t.choices`），她那句话已经进了对话记录。拿到 null 就 Toast“体系图里还没东西可下，先在对话里说‘记下’几条”。
2. 今天页“思维转速”（E3）那一节右边也放一个「来一局」，点了用 `LiveScreens.peek(context, Mood.CURIOUS, t.line)`，打开前把 `LiveScreens.nextChoices = t.choices`。

## E3：思维转速（今天页）

`TodayScreen.kt` 在“今天的手机”上面加一节“思维转速”：
1. 左边一个 `InkRing`（进度 = `score / 12f`，封顶 1），中间大字分数，下面 `Rpm.line(...)`；
2. 右边一条 24 小时的细墨线图（同 `HourStrip` 的画法，数据 `Rpm.byHour`，颜色用 `WashViolet`）；
3. 分数 < `Rpm.LOW` 时下面一行小字：“脑子歇太久了，来一局？”+「来一局」（E2）。

## E4：她采回来的简报（体系页）

`AtlasScreen.kt` 顶部（列表 / 沙盘切换下面）：`briefs.filter { it.status == "new" }` 不为空时，一张张卡：
- 标题“她替你采的：${question}”，下面每条 `lines` 一行（括号里的来源类型用小字灰色）；
- 「收下」→ `BrainHost.keepBrief(id, true)`，Toast“收进体系图 N 条（把握低，待你核实）”；「不要」→ `keepBrief(id, false)`；
- 卡片底部小字：“这是模型凭已知整理的，没联网查；也已经挂到黑板上问 GPT 了。”

## E5：她摸清的规律（了解页）

`AboutScreen.kt` 在“她推过的”上面加一节“她摸清的规律”：
- `proposed` 的：一句 `Rules.line(rule)` + 小字依据 `why` + 「对」「不对」→ `BrainHost.setRule(id, true/false)`；说明一行“你点‘对’之后，她才会按这条来找你”。
- `kept` 的：列在下面，每条后面一个小「不再用」→ `setRule(id, false)`。

## E6：体系图里看得出来源

- 列表里论断的来源是 `三手棋` 的，前面画一个小棋子（实心墨点套一个细圈）；`采蜜（待核）` 的，前面一个小纸页，文字后面加“（待核）”。
- 沙盘（`AtlasMapView`）里，她连的线（`Edge.note` 以 `她连的：` 开头）用虚线画。
- 三手棋下完的新结论，列表里第一次出现时轻轻闪一下（`animateColorAsState`，1 秒）——可选。

---

## 底线（同第六批第 11 节）

不读屏幕内容、不挡别的应用的操作、不替他做决定、不自己开麦；简报和规律都要他点了才生效。

## 交付

`moling/DEVLOG.md` 末尾加“第七批（DeepSeek）”，推 `deepseek-mind`，CI 绿了告诉用户，由 Claude 合并。
