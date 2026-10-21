# 给 Codex 的交接（2026-10-21）

> **当前交接更新（2026-10-21，真机试用阶段）**
>
> 第四、五批已合入；Claude 的 C4–C7、v5–v12，以及 DeepSeek 第六、七批也已合入。
> 下方第四、五批说明是历史记录，不要重做。当前工作分支仍为 `ccr-b3c7616e-xlohkd`，新施工从它的最新提交开始。
> **给 DeepSeek 的新任务：[第八批：发布稳定与验收收口](DEEPSEEK-8.md)**。第八批独立处理发布流程与文档；新增系统能力按下面 v13 计划另开任务实施。
> 用户先看 [10 分钟测试](TESTING-QUICK.md)，完整流程见 [TESTING.md](TESTING.md)。
> 连续对讲、Notion 镜子已有 App 实现（对讲 / `app/mirror/`），属“真机 / 服务配置待验”，不是“完全没做”，也不是“已经真机可用”。
> 本次 App 基线为 `b838311`；已有键盘、返回和重复点选修正，仍待真机验证。下面的旧测试数不代表本次新跑的结果。

> **v13 总设计与新施工入口（2026-10-21）**
>
> 用户与 Gemini 的创意已综合为 [七脑＋五手总设计](../partner-blueprint/v13-system-blueprint.md)、[契约与跨进程协议](../partner-blueprint/v13-contracts.md)、[完整 Kotlin 定义](../partner-blueprint/v13/V13Contracts.kt) 和 [交付计划](../partner-blueprint/v13-delivery.md)。
> 新增能力的第一波是 [第九批：本地案板与实质代工](DEEPSEEK-9.md)，从资料到草稿、挑刺、采用、导出形成闭环。第八批发布修复保持独立。
> 四五批的“界面任务不改 core”仍适用；第九批 C9（`core/workspace/`、`core/strategy/Prework.kt`、Live 破雾统一、Brain 的 `markMind`/`keepClaims`）已由 Claude 完成，DEEPSEEK-9 只剩 I9 界面。不得借总重构顺手修改总控授权、放宽规则或签名。
> 当前新增文档是实施规格，不表示 v13 已装进用户 APK；后续施工和验收分批进行，不重做已有 FOG/Chess/Habits/Atlas。

> 你接 DeepSeek 的班，继续做墨灵（`moling/`）和总控里接墨灵的那部分界面。先读这一页，再读 `partner-blueprint/CONTEXT.md`（一页纸的来龙去脉）。

## 1. 现在到哪了

| 谁 | 做了什么 | 在哪 |
|---|---|---|
| Claude | 墨灵的大脑和规矩（`moling/app/src/main/java/com/moling/core/`）；总控接收墨灵、授权书、临时开关（`zongkong/app/src/main/java/com/zongkong/core/moling/`） | 已在工作分支 |
| DeepSeek 第一到三批 | 墨灵 App：对话、今天、体系、设置、调试页、后台心跳、看前台应用、通知、早晚自动、总控结局、导演卡片、备份 | 已合进工作分支，记录在 `moling/DEVLOG.md` |
| **你（接下来）** | 第四批 `moling/DEEPSEEK-4.md`、第五批 `moling/DEEPSEEK-5.md` | 下面第 2 节 |
| Claude（之后） | 核心逻辑 C4–C7（`partner-blueprint/v4-core-design.md`），再写第六、七批文档 | — |

**工作分支：`ccr-b3c7616e-xlohkd`**。所有新分支从它拉。

## 2. 历史：第四、五批（已合入，不要重做）

下面这份表是当时的交接记录；两批都已完成并合进工作分支，**不要再当成新任务做**。

| 批次 | 文档 | 当时的分支 | 内容 |
|---|---|---|---|
| 第四批 | `moling/DEEPSEEK-4.md`（P1–P7） | `codex/moling-presence` | 小窗、打开娱乐 App 时先答一问、桌面小部件、晨报和到点提醒、分享进墨灵、从 GPT / Gemini 回来接住收线 |
| 第五批 | `moling/DEEPSEEK-5.md`（A1–A6） | `codex/moling-auth` | 总控的授权书页、墨灵建议列表、临时开关显示；墨灵按授权书交（`ZkBridge.sendAuto`）、显示授权书和临时开关 |

- 两批改的文件不重叠（各自文档第 1 节列了），当时是并行做的。
- 开分支：`git fetch origin && git checkout -b codex/moling-presence origin/ccr-b3c7616e-xlohkd`。第三批**已经合进工作分支**，文档里“再合并 `deepseek-moling`”那一步跳过。
- 每批做完：在 `moling/DEVLOG.md` 追加（和前三批同样的格式：做了什么 / 为什么 / 没做什么 / 用户怎么验收），推你的分支。用户会让 Claude 审查后合进工作分支。

## 3. 规矩（和 DeepSeek 一样，必须遵守）

1. **不改 `core/`**：墨灵的 `moling/app/src/main/java/com/moling/core/` 和总控的 `zongkong/app/src/main/java/com/zongkong/core/`（包括它们的测试）。规则都在那里，界面只调用。觉得 core 要改，写进 DEVLOG 的“要 Claude 改的”一节，说清改什么、为什么。
2. **权限**：只加文档里允许的（第四批只有 `SYSTEM_ALERT_WINDOW`，第五批不加）。
3. **提议在用户同意之前不执行**；总控那边授权书外的照旧要用户点“执行”。墨灵不修总控、不冻结任何 App。
4. **总控的坑**：总控的 CI 在任何分支改了 `zongkong/` 并测试通过后，会覆盖用户手机上的总控下载链接。所以第五批改总控前，确认你的分支包含工作分支的最新提交（不能比它旧），推之前本地跑总控的单元测试。
5. 出错不能崩：网络、模型、总控连不上、权限没开，一律降级并在调试页写清楚。
6. 提交信息写清楚做了什么、为什么；一个里程碑一个提交。

## 4. 怎么验证

- **纯逻辑**：`tools/core-tests/` 里 `gradle test`（两边的 core + 交叉测试）。本批（第八批，2026-10-21）实际运行 **410 个全绿**；文中出现的 343 是更早的旧记录，不代表本次结果。你不改 core，这里应该一直是绿的。
- **App**：`moling/` 和 `zongkong/` 各自 `./gradlew testDebugUnitTest assembleDebug`（需要 Android SDK；没有就以 CI 为准：`moling-android`、`zongkong-android` 两个 workflow）。
- **真机**：每批 DEVLOG 末尾写“真机验收清单”，用户照着在手机上试。

## 5. 速查

- 大脑的入口：`moling/app/src/main/java/com/moling/core/Brain.kt`（开头的注释写了全部规矩）；App 只经 `moling/app/src/main/java/com/moling/app/BrainHost.kt` 调它。
- 墨灵 ↔ 总控的约定：墨灵 `core/link/ZkLink.kt`，总控 `core/moling/Moling.kt`、`Authority.kt`；总控 App 侧 `app/link/MolingProvider.kt`、`MolingBridge.kt`。
- 权柄：`moling/.../core/power/Powers.kt`；v4 的整体规划：`partner-blueprint/v4-power.md`。

---

## 第六批（2026-10-21 追加，已合入）

`moling/DEEPSEEK-6.md`：反自动驾驶——桌宠、盯着你、耳语、参谋弹幕、捉虫题、褪色、沙盘、演员胶囊、出现方式看板。
规则和大脑接口 Claude 已写好（`core/autopilot/`、`core/live/Habit.kt`、`core/live/Quiz.kt`、`core/atlas/AtlasMap.kt`、`app/live/Appear.kt`），只做界面和胶水。底线见那份文档第 11 节。
