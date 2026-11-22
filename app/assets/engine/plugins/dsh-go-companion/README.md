# dsh-go-companion

主动互动的**唯一决策与回合服务**。Android 负责唤醒和展示；这里决定“现在说不说、说什么、说给谁”，
并把每一步写进账本。

## 它做什么

1. 接收手机端的一次主动事件（`POST /request`），语义与旧 `dsh-go-proactive` 一致：
   `accepted → claimed → generated | failed | cancelled`。
2. **冷会话**：会话不活着时先 `sessionController.resolveAgent(sessionId)`，而不是直接拒绝；
   失败也不会新建会话。
3. **话题**：只读 `fish-memory.sqlite`，从「未完成话题（topics）→ 最近共同经历（episodes）」里选一个；
   `facts` 只取钉住/重要，最多 5 条做背景。
4. **prompt**：一个人格、一段话题、少量背景；明确禁止提“事件/系统/主动”、禁止工具、禁止“今天有什么计划”。
5. **账本**：`$DSH_HOME/companion/ledger.jsonl`，`requestId` 贯穿，记录每一步状态。
6. **本地决策**（`POST /plan`）：开关、安静时段、日上限、最小间隔、掷骰、话题选择；零模型。

## 路由（前缀 `/dsh-go-companion/v1`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/request` | 原生闹钟判定“到点”后调用；返回 202 accepted |
| GET | `/result?requestId&waitMs` | 长轮询（≤25s） |
| POST | `/cancel` | 撤回；晚到的文本不再交出 |
| POST | `/plan` | 本地决策，返回 `speak/reason/topic`；未命中不消耗模型 |
| POST | `/outcome` | `delivered / displayed / replied / substantive / dismissed` 回执 |
| GET | `/status` | 配置、活会话、计数、今日统计、最近 20 条账本、记忆库是否存在 |
| POST | `/config` | `sessionId`、开关、安静时段、日上限、间隔、掷骰线、冷会话开关、称呼 |

鉴权复用 `connection.requestRejection(req)`（与 `/api` 同等凭据）。

## 配置

`$DSH_HOME/companion/config.json`

```json
{
  "enabled": true,
  "sessionId": "session-…",
  "quietStart": 1,
  "quietEnd": 8,
  "dailyMax": 6,
  "minGapMs": 1800000,
  "diceThreshold": 60,
  "cooldownMs": 86400000,
  "allowColdSession": true,
  "owner": "主人",
  "self": "大肥鱼"
}
```

旧 `dsh-go-proactive` 设置过 `sessionId` 时，第一次加载会自动迁过来。

## P1.2：话题花园 / 跟进 / 连续性

话题不再只是“从记忆里捞一条”，而是 `$DSH_HOME/companion/topics.json` 里一份独立的清单：

- **生命周期**：`candidate → active → paused → 重新唤起 → done`；记忆库仍是事实/未完成话题的真源，只读。
- **来源**：`memory.topic`（未完成）、`memory.episode`（最近共同经历）、
  `relationship.commitment`（到期承诺）、`conversation`（正常聊天里发现）、`agent`（模型主动记下）。
- **生成类型**：`continue`（延续）、`share`（分享/吐槽）、`explore`（一起想）；
  连续两次追问后会优先换分享/探索，避免变成问卷。
- **去重**：标题 bigram 相似度 ≥ 0.7 合并；同一记忆话题 id 复用。
- **评分**：类型权重 + 兴趣 + 新鲜度 − 尝试次数 − 同类型疲劳；冷却、下次可问时间都参与过滤。
- **不强行开口**：`nextTopic()` 返回 null 就 `409 no-topic`，不发空泛问候。
- **跟进**：`substantive` 继续；`brief` 2h 后换轻的；`avoid` 12h；`refuse` 结束；
  `none` 最多两次（4h / 一周），之后开新周期。
- **连续性**：生成后只保存 `lastSentSummary`（我说过什么），用户实质回应后才升级为
  `lastSummary`（我们聊过什么）；下次 prompt 不会把单方面发过的话说成聊过。
  用户换话题时旧话题保留为 `paused`，不丢。

正常聊天里，模型可以调用 `companion_topic` 工具（`add/list/done/pause`）把
“以后还想聊的事”放进花园；主动回合内工具仍被硬拦。

### P1.3 调度闭环（同一只闹钟）

- `/plan` 额外返回 `nextCheckAt`（话题到期 / 冷却结束 / 安静结束 / 次日额度）。
- 原生 `ProactiveRuntime` 在 tick 等待、以及用户回答/划掉之后调用 `/plan`，
  把下一次检查写回唯一的 `ProactiveScheduler` 并重新 arm，不新增第二套定时器。
- 已结束的话题不会因为记忆仍是 open 就复活；只有 `lastTouchAt` 比结束时间新，
  或显式 `reopen`，才会重新成为候选。
- 连续回避不消耗无回应额度：第一次 12h，第二次 24h，之后仍可回到候选。
- `lastSentSummary` 只代表 AI 单方面发过；只有实质回应后才升级成 `lastSummary`，
  下次 prompt 不会把“我说过”说成“我们聊过”。

## 部署

作为内置插件放在 APK assets，由 `BuiltinPluginGuest` 每次启动铺到
`/opt/dsh-app/plugins/dsh-go-companion/`。原生 `AgentWire.PREFIX` 指向本插件；
旧 `dsh-go-proactive` 仍随 runtime 存在，但不再被调用。

## 验证

```bash
cd app/assets/engine/plugins/dsh-go-companion && node --test test/*.test.mjs   # 44 项
```

真机：设置 → 手机操控 → 主动搭话 → 开启（会自动选一个空闲会话）→「现在试一次」；
日志应出现 `[companion] loaded; session=…` 与 `[companion] routes /dsh-go-companion/v1`，
账本出现 `accepted → claimed → generated`。

## 边界

- 不读屏幕内容、不截图、不联网到外部服务；只读本机 SQLite。
- `generated` 只代表模型出了文字，不代表用户看到；送达/展示/回应由 `/outcome` 记录。
- 主动回合内禁用一切工具（`tools/pre-execute` 硬拦）。

## P5：节奏与素材（2026-11-06）

- **手边的情况**：`POST /request` 可带 `phone`（前台 App、停留分钟、今日各 App 累计、亮屏、充电、当地时间；
  来自 App 侧 `NowFeed`，不含屏幕文字）。`lib/phone.js` 清洗后写进 prompt。正开着娱乐 App ≥10 分钟或今天娱乐类 ≥60 分钟，
  这一球优先打**观察**；**没带 `phone` 就不打观察球**（没东西可观察只能编）。
- **阶梯真的排上闹钟**：`/plan` 在"发出去还没到下一级"时回 `reason: ladder-wait`，`nextCheckAt` = 第一球 + 5 / 15 分钟，
  不再按 `minGap` 冷却。阶梯时间从这一串没接的球里第一球算，每级只走一次，错过就跳到够得着的最高一级。
- **停下与恢复**：原球 + 轻球 + 换渠道三球都没接才停（`NO_TAKE_LIMIT = 3`）；最后一球后歇 3 小时（`REST_MS`）旧账清零。
  以前停下后没有任何条件能解除。
- **教练模式高频**：`coachMinGapMs` 8 分钟、`coachHourlyMax` 6、`coachDailyMax` 40，`/config` 可改；伙伴模式不变。
  掷骰没中的下次检查跟 `minGap` 走，不再写死 30 分钟。
- **回合里接着打不占额度**：每小时/每天上限只数新开的球和阶梯补的球（`rally.js` 的 `capCount`）。
- **两张人格卡合一**：关系库 `persona` 表（价值观/底线/边界/口吻/兴趣）打底，`config.persona` 覆盖；`/status` 的
  `personaEffective` 是实际进 prompt 的那张。
- **部署清单守卫**：`test/manifest.test.mjs` 检查 `lib/` 下每个文件都写进了 `BuiltinPluginGuest.kt`。


## P6：大脑（水位 / 脾气 / 思维题 / 学习 / 力度）

设计见 `docs/dafeiyu/大肥鱼-水道-主动引导体系.md`。

- `tide.js` 水位 0–100（清醒/在滑/在沉/陷住了）决定节奏：间隔按水位缩短并带随机，在沉/陷住不掷骰子。
- `temper.js` 脾气：哄/逗/冷/怼/训，底色随模式（温和 0.25 / 严格 0.7），水位往硬里推，随机游走，偶尔翻脸。存 `companion/temper.json`。
- `brain.js` 大脑：Thompson 抽样挑脸，近端结果 = 发球后 10 分钟内离开娱乐 App 或认真回球；思维题弱项加权；判断从证据重算，翻了就记 `revision`。存 `companion/brain.json`。
- `drills.js` 九种思维题，有合格线；上一题没回/答糊弄，下一球先说他、同类题重问。判球按合格线判。
- `force.js` 力度 0–4（说/追/屏/锁/赶），不掷骰子；`/plan` 回 `force` 和 `tide`，手机执行锁/回桌面。配置 `control: false` 最多到浮层。
- 一早第一球不等骰子；"严格点 / 温柔点 / 切到严格模式"一句话切换。

## P7：真聊天为主，做题改成可选

- `POST /say {text}`：浮层里打的字原样进大肥鱼的会话（不带插件来源，就是他本人说的），大肥鱼照常回、工具不拦；
  回复从 `GET /result` 取。账本记 `said / heard`，不算主动发球、不占额度、不进阶梯。
- 每一球都提前告诉大肥鱼：他回你时像朋友一样接着聊，不出题不打分；他在解释理由就先复述你理解的理由再表态。
- 他讲了站得住的理由（判球 = pushback）：松 20 分钟，力度最多到"追"，不锁（`brain.graceUntil`）。
- 做题默认关（`drills: false`）。说"开始做题 / 做题模式"打开，"别出题了"关掉。关着时，要锁的那一球改成"问他为什么还在这里"。
