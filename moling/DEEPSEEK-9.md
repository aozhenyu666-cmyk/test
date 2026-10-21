# 墨灵 · 给 DeepSeek 的施工文档（第九批 I9：案板）

> 2026-10-21。v13 第一波。目标：**他贴两三句材料 → 她先做一份三点纲要 → 他点「对手挑刺 → 采用 A → 改首段」→ 拿走一份能复制 / 导出的稿子。**
> 只加一个标签页、或者让模型多说两句，不算交付。
>
> **核心 Claude 已经写好并测过**：`core/workspace/`（焦点、稿子、版本、撤销、动作幂等、作业协调）、`core/strategy/Prework.kt`（纲要 / 初稿 / 挑刺 / 改写 / 四参谋的提示词、解析、离线兜底），
> `BrainHost` 里的 `bench*` 方法也接好了。`tools/core-tests` 432 个测试全过。
> **你只做界面**：不改 `com/moling/core/`、不改 `BrainHost` 已有方法的签名、不加权限、不碰总控。
>
> 本文替换了之前的第九批入口（那版把 C9 + I9 一起派出去）：C9 已由 Claude 完成，这里只剩 I9。第八批（发布稳定）仍是独立任务，不要混进同一个 PR。

---

## 0. 开工

1. 先读根目录 `AGENTS.md`、`moling/CODEX.md`、`partner-blueprint/CONTEXT.md`；背景再看 [七脑五手总设计](../partner-blueprint/v13-system-blueprint.md)、[契约](../partner-blueprint/v13-contracts.md)，范围以 [交付计划](../partner-blueprint/v13-delivery.md) 第 4 节 I9 为准。
2. `git fetch origin && git checkout -b deepseek/moling-workspace origin/ccr-b3c7616e-xlohkd`（从工作分支**最新**提交开；已有同名分支先比较、保留已有工作，不重置、不强推）。
3. `cd tools/core-tests && gradle test` 一直全绿。Android 编译以 CI 为准（推上去看 `moling-android`）。
4. 每个任务一个提交，说明里写任务号（J1…J7）。

## 1. 你会用到的（都已写好，不要改）

状态：`BrainHost.workbench: StateFlow<WorkbenchState>`。**界面一律从这里读、`collectAsState()` 渲染**；方法的返回值只用来弹提示。

| 用途 | 调用 / 字段 |
|---|---|
| 正在做的那件事 | `ws.active(): Focus?`；`ws.focuses`（`status` = ACTIVE / PAUSED / ARCHIVED） |
| 这件事的材料 | `ws.materialsOf(focus)` → `Material(id, kind = paste/atlas/share, title, text)` |
| 这件事的稿子 | `ws.artifactsOf(focus.id)`（按创建先后；最新的在最后） |
| 撤销栈 | `ws.versions(artifactId)`（新的在后；每格 `note` = 那一下做了什么、`at`） |
| 作业 | `ws.jobs`：`PreworkJob(id, kind, focusId, status, artifactId, blockId, error, createdAt, finishedAt)` |
| 日志 | `ws.log`（最近 100 条） |
| 开一件事 | `BrainHost.benchStart(question, material): Focus?` |
| 从体系图选主题 | `BrainHost.benchFromAtlas(topicId, question = ""): Focus?`（主题里没论断返回 null） |
| 补材料 | `benchAddMaterial(focusId, text)`（之前还没回来的代工会作废，这是对的） |
| 切换 / 收起 | `benchSwitch(focusId)`、`benchArchive(focusId)` |
| **替我先做一块** | `benchPrework(focusId, JobKind.OUTLINE)`；写成初稿 `JobKind.DRAFT`（`artifactId` = 哪份纲要，可空）；四参谋 `JobKind.COUNCIL`（`artifactId` 必填） |
| 稿子上的一下 | `benchApply(artifact, kind, blockId?, optionId?, text?)`，`kind` 见下表 |
| 取消作业 | `benchCancel(jobId)` |
| 导出 | `benchMarkdown(artifactId): String` |
| 收进体系图 | 先 `benchClaims(artifactId)` 给他看，他点“收进”再 `benchKeep(artifactId, topicId = null)`（返回条数） |

`benchApply` 的 `kind`（`ArtifactActionKind`）：

| 按钮 | kind | 参数 |
|---|---|---|
| 挑刺 | `REQUEST_CRITIC` | `blockId` = 正文块 |
| 采用 | `ACCEPT_BLOCK` | `blockId` |
| 改（他自己写） | `EDIT_BLOCK` | `blockId`、`text` = 新内容 |
| 重写 / 改首段 | `REWRITE_BLOCK` | `blockId`、`text` = 要求（可空，默认“开门见山、更短更有力”） |
| 删 | `DELETE_BLOCK` | `blockId` |
| 选 A / B | `CHOOSE_BRANCH` | `blockId` = **批注块**的 id、`optionId` |
| 整份采用 | `ACCEPT_ARTIFACT` | — |
| 撤销 | `UNDO` | — |

返回 `PortResult`：`Ok(value)` 或 `Failed(error)`。`error.code` → 提示语（`error.message` 已经是给人看的中文，直接 Toast 也行）：

| code | 什么情况 | 界面 |
|---|---|---|
| `REVISION_CONFLICT` | 他点的时候看的是旧版本 | Toast“稿子刚变过，看一眼再点”（界面已自动刷新） |
| `STALE` | 她想的时候他把那一段改了 / 换了材料 | Toast 原文，不用重试 |
| `BUSY` | 这一下还在做 | 不弹，按钮本来就该是转圈状态 |
| `OFFLINE` | 模型没接上 | Toast 原文；改写失败时提示“先自己改” |
| `CANCELLED` / `NOT_FOUND` / `BAD_ACTION` | — | Toast 原文 |

**防连点**：`benchApply` 一定传**当前渲染的那个 `artifact` 对象**。动作 id 由“稿子 id + 版本 + 按钮 + 块”算出来，同一版本上连点 / 两处同时点只算一次——不要自己造 id、不要传别的版本的对象。

块（`ArtifactBlock`）怎么画：

| `kind` | 样子 |
|---|---|
| `MARKDOWN` | 正文（见 J3 的简易 Markdown） |
| `QUOTE` | 左边一条细墨线 |
| `ASSUMPTION` | 虚线框 + 小标签「待核」（这是她推的或者没依据的，必须看得出来） |
| `REVIEW_NOTE` | 批注：小字 `voice`（对手 / 教练 / 军师 / 朋友）+ 一句话 + `options` 的按钮（A / B） |
| `CHOICE` + `exerciseOnly` | 练习靶子：小标签「练习」+ 选项按钮；选了之后显示 `options[0].explanation`（漏洞在哪） |

`sourceIds`：`m-…` = 他的材料，`n-…` = 体系图论断。画成小标签（“材料”“体系”），点一下弹出原文。空的不画。
`accepted = true` 的块右上角一个小勾。`artifact.offline = true` 时卡片顶部一行灰字：“模型没接上，这是从你的材料里拆的骨架”。

---

## J1：「案板」标签

`MainActivity.kt`：底栏加第四个“案板”（`tab == 3`，放在“体系”后面；已有的 0/1/2 不动，`EXTRA_TAB` 的老用法不受影响）。图标用 `art/InkArt.kt` 里的风格自己画一个（一张纸 + 一道墨线就行）。新文件 `ui/BenchScreen.kt`。返回手势已经是“不在对话页就回对话页”，不用改。

## J2：没有焦点时

三个入口（大按钮，竖排）：
1. **贴材料**：一个“你要攻什么”（单行，可空）+ 一个材料框（多行）→ `benchStart`。
2. **从体系选**：列出 `BrainHost.state.value.atlas.topics` → `benchFromAtlas`；返回 null 时 Toast“这个主题里还没有论断”。
3. **先给骨架**：只填“你要攻什么” → `benchStart(question, "")`。

开好以后**不自动开工**，显示焦点页（J3），上面一个醒目的「替我先做一块」。

## J3：焦点页

- 顶部：问题（大字）、`材料 N 条`（点开看 / 「补材料」）、右边一个小菜单：切到别的事（列 PAUSED 的）、开新的一件、收起这件。
- 还没有稿子：「替我先做一块」→ `benchPrework(focus.id, JobKind.OUTLINE)`。
- 有作业在跑（`jobs` 里 `status == RUNNING` 且 `focusId` 是这件）：一行“她在做：${job.kind.label}…” + 小「取消」。
- 稿子：`artifactsOf` 的每份一张卡（J4），最新的在上、展开；旧的折叠成一行（标题 + 种类 + 第几版）。
- 底部：「写成初稿」（有纲要后出现，`JobKind.DRAFT`）、`focus.nextChoice` 有值就作为一行灰字提示。

简易 Markdown：只认行首 `#`/`##`（加粗大字）、`- `/`1. `（列表缩进）、`**粗体**`、`> `（引用线）。不要为这个加依赖库。

## J4：稿子卡片

- 头：标题、种类（纲要 / 初稿 / 四参谋）、“第 ${revision} 版”、状态（`ACCEPTED` 显示“已采用”）。
- 每个正文块（`MARKDOWN`/`QUOTE`/`ASSUMPTION` 且不是练习）下面一排小按钮：**挑刺 · 采用 · 改 · 重写 · 删**。
  - 第一个正文块（`Artifacts.firstContent(a)`）的「重写」写成「改首段」。
  - 「改」：弹一个多行输入框，预填原文 → `EDIT_BLOCK`。
  - 「重写」：弹一个单行“想怎么改”（可空）→ `REWRITE_BLOCK`。
  - 「删」：二次确认。
  - 这一块有作业在跑（`jobs` 里 `status == RUNNING && blockId == 块id`）：这排按钮变成一个小转圈 + “她在挑刺… / 她在改…” + 「取消」。
- 批注块紧跟在被批的段落后面：A / B 两个按钮，长按（或点小箭头）展开看改后的整段；点了 → `CHOOSE_BRANCH`。
- 卡片底部一排：**撤销**（`versions` 为空时禁用）· **版本**（底部弹窗列 `versions` 倒序：`note` + 时间，最上面一行“现在：第 N 版”）· **整份采用** · **四参谋** · **导出** · **收进体系图**。
  - 导出：`benchMarkdown` → 复制到剪贴板 + 一个系统分享（`ACTION_SEND text/plain`）。不需要 Notion。
  - 收进体系图：弹窗列出 `benchClaims`（空的话说“先采用几段”），他点「收进」→ `benchKeep`，Toast“收进体系图 N 条”。
- `COUNCIL_REVIEW` 卡：四条批注各一行（谁说的 + 一句），没有块按钮。

## J5：调试页

`DebugScreen.kt` 加一节“案板”：当前焦点 id / 版本；每份稿子 `id · kind · 第几版 · offline`；最近 10 个作业 `id · kind · status · focusId · artifactId · error.code`；`ws.log` 最近 20 条。

## J6：分享进来也能上案板（小）

`ShareActivity.kt` 的弹窗加一个「放上案板」：`benchStart("", text)`，然后用 `MainActivity.EXTRA_TAB = 3` 打开主界面。

## J7：破雾的按钮（核心已改，界面只需确认）

v13 起所有娱乐入口（该做事时在刷、躺着刷、刷很久、自动驾驶、过路费）都走破雾：她不问“卡在哪 / 还要多久”，一定有按钮，**最后一个按钮永远是「先不聊了」**（`Live.EXIT`），点了她收尾、拦着时直接放行。
已有的选项按钮照常把它交给 `BrainHost.tap(...)` 就行；建议把它画得淡一点（文字按钮），和其他选项区分开。对话页、卡片、桌宠、拦人页都检查一遍。

---

## 底线

- 只有他点了才调模型：不要在进页面、切焦点、补材料时自动 `benchPrework`；不加定时代工。
- 不加权限、不联网查资料、不读别的应用的屏幕。
- 「采用」只是内容上的采用，不触发总控任何动作；收进体系图要他在弹窗里再点一次。
- 练习靶子（`exerciseOnly`）不导出、不进体系图——核心已经保证，界面别另外绕过去拼文本。

## 真机验收（写进 DEVLOG，逐步给截图）

1. 案板 → 贴材料：“申论开头最容易卡。我平时先列三点再写。可是三点常常互相重复。” → 「替我先做一块」→ 出三点纲要，有一块标「待核」。
2. 第一段「挑刺」→ 出现对手批注和 A / B → 点 A → 第一段变了、批注收起、版本 +1。
3. 「改首段」→ 第一段被改写。连点两下只改一次。
4. 「撤销」→ 回到上一版；再撤销 → 再往前一版。
5. 杀掉 App 再打开 → 稿子、版本都在。
6. 「导出」→ 粘贴到备忘录，是干净的 Markdown（没有批注、没有练习靶子，“待核”有标注）。
7. 关掉网络（或清空 API 密钥）重来一遍 → 出“离线骨架”，挑刺给三问，改首段提示“先自己改”，自己「改」能用。
8. 刷抖音 5 分钟左右等她出来 → 不问“卡在哪”，有「先不聊了」，点了就收尾。

## 交付

`moling/DEVLOG.md` 末尾加“第九批 I9（DeepSeek）”：改了哪些文件、CI 链接、真机走到第几步、没做完的。每一项分开写“实现完成 / 自动检查通过 / 真机未测或通过”，不要拿测试总数当新功能已验收的证据。推 `deepseek/moling-workspace`，建 PR 指向 `ccr-b3c7616e-xlohkd`，CI 绿了告诉用户，由 Claude 审查合入。不要合入、不要强推。
