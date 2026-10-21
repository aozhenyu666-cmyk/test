# 给 Codex 的交接（2026-10-21）

> 你接 DeepSeek 的班，继续做墨灵（`moling/`）和总控里接墨灵的那部分界面。先读这一页，再读 `partner-blueprint/CONTEXT.md`（一页纸的来龙去脉）。

## 1. 现在到哪了

| 谁 | 做了什么 | 在哪 |
|---|---|---|
| Claude | 墨灵的大脑和规矩（`moling/app/src/main/java/com/moling/core/`）；总控接收墨灵、授权书、临时开关（`zongkong/app/src/main/java/com/zongkong/core/moling/`） | 已在工作分支 |
| DeepSeek 第一到三批 | 墨灵 App：对话、今天、体系、设置、调试页、后台心跳、看前台应用、通知、早晚自动、总控结局、导演卡片、备份 | 已合进工作分支，记录在 `moling/DEVLOG.md` |
| **你（接下来）** | 第四批 `moling/DEEPSEEK-4.md`、第五批 `moling/DEEPSEEK-5.md` | 下面第 2 节 |
| Claude（之后） | 核心逻辑 C4–C7（`partner-blueprint/v4-core-design.md`），再写第六、七批文档 | — |

**工作分支：`ccr-b3c7616e-xlohkd`**。所有新分支从它拉。

## 2. 你的两批任务

两份文档名字里写的是 DeepSeek，内容就是你的任务说明，照做即可，只有分支不同：

| 批次 | 文档 | 你的分支 | 内容 |
|---|---|---|---|
| 第四批 | `moling/DEEPSEEK-4.md`（P1–P7） | `codex/moling-presence` | 小窗、打开娱乐 App 时先答一问、桌面小部件、晨报和到点提醒、分享进墨灵、从 GPT / Gemini 回来接住收线 |
| 第五批 | `moling/DEEPSEEK-5.md`（A1–A6） | `codex/moling-auth` | 总控的授权书页、墨灵建议列表、临时开关显示；墨灵按授权书交（`ZkBridge.sendAuto`）、显示授权书和临时开关 |

- 两批改的文件不重叠（各自文档第 1 节列了），可以分两个任务并行。
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

- **纯逻辑**：`tools/core-tests/` 里 `gradle test`（两边的 core + 交叉测试，现在 343 个）。你不改 core，这里应该一直是绿的。
- **App**：`moling/` 和 `zongkong/` 各自 `./gradlew testDebugUnitTest assembleDebug`（需要 Android SDK；没有就以 CI 为准：`moling-android`、`zongkong-android` 两个 workflow）。
- **真机**：每批 DEVLOG 末尾写“真机验收清单”，用户照着在手机上试。

## 5. 速查

- 大脑的入口：`moling/app/src/main/java/com/moling/core/Brain.kt`（开头的注释写了全部规矩）；App 只经 `moling/app/src/main/java/com/moling/app/BrainHost.kt` 调它。
- 墨灵 ↔ 总控的约定：墨灵 `core/link/ZkLink.kt`，总控 `core/moling/Moling.kt`、`Authority.kt`；总控 App 侧 `app/link/MolingProvider.kt`、`MolingBridge.kt`。
- 权柄：`moling/.../core/power/Powers.kt`；v4 的整体规划：`partner-blueprint/v4-power.md`。
