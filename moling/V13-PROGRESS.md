# v13 交付进度

> 2026-10-21 文档补齐时的核对快照。后续执行者必须以最新GitHub状态更新；“有计划”不等于“已实现”。
> 总入口：[DEEPSEEK-ALL](DEEPSEEK-ALL.md)。只有检查实际代码/CI/真机后才更新对应栏。

| 任务 | 实现状态 | 自动检查 | 真机/发布 | 交付链接 |
|---|---|---|---|---|
| 第八批 R1/R2 | 已提交，未合入 | CI通过；记录有15项脚本断言 | 正式发布加固待合入后验证 | [PR13](https://github.com/aozhenyu666-cmyk/test/pull/13) |
| C9内核 | 已合入工作分支147f8bd | Moling CI通过；记录称432核心测试 | 无案板UI的正式包 | [提交](https://github.com/aozhenyu666-cmyk/test/commit/147f8bde2a59ef789733444136941d50c05eae83) |
| I9 J1–J7 | 主要实现已提交；来源点开原文缺失 | Moling CI通过 | 真机未测，未合入正式包 | [PR15，含PR13代码](https://github.com/aozhenyu666-cmyk/test/pull/15) |
| 动效打磨 | 已提交，未合入 | Moling CI通过 | 真机未测 | [PR14](https://github.com/aozhenyu666-cmyk/test/pull/14) |
| P0前置整合 | 已实现：整合PR15（含PR13）+PR14，补 I9 来源点开原文 | 本地编译通过；CI 见分支 | 真机未测；发布 job 应 skipped | 本分支 PR |
| W10 T1–T9 | **进行中（里程碑2）**：T1/T2(fetch,RSS,HTML,Brave适配)/T3(池、分享解析、分享提取正文)/T7(预算与事件) 核心与入口完成；T4–T6、T8–T9 未做 | core-tests 442 全绿；App build/test 通过；真实公网未验 | 真机未测 | [任务](DEEPSEEK-10.md) · [PR17](https://github.com/aozhenyu666-cmyk/test/pull/17) |
| W11 R1–R7 | **部分实现**：R1 电台核心、R2 案板/对话面板、R3 安静期与曝光、R5 赌局核心完成；R4/R6 只有接口与说明 | core-tests 472 全绿；App compileDebugKotlin / testDebugUnitTest 通过 | 真机未测；R4/R6/R5 UI 入口未做 | [任务](DEEPSEEK-11.md) |
| W12 A1–A6 | 任务书已补齐，待实现 | 待验证 | 待验证 | [任务](DEEPSEEK-12.md) |
| W13 G1–G7 | 任务书已补齐，待实现 | 待验证 | 待验证 | [任务](DEEPSEEK-13.md) |

## 更新格式

每批下方按任务号逐项追加：实际文件/提交、实现状态、测试命令与结果、CI链接、真机支持与未测项、PR与依赖、正式APK源码SHA。旧数据留日期，不能拿本地构建代替正式发布。

缺口统一写在这里并关联任务号；不得只在聊天里说“还差一点”。

当前明确缺口：第八批正式发布验证（P0）；I9/动效真机验收（P0）；W10 T4–T9；W11 R4/R6 与 R5 UI 入口；W12–W13 功能（各批）。

### W10（2026-10-22）
- 里程碑1 已提交：Sources/WebPort/Inhale/Cycle 核心与测试；BrainHost 来源库 + 案板「加网页/链接」。
- 未做：设置页搜索/RSS 配置、ShareActivity 提取正文、联网采蜜、Prework 引用校验、HybridRecall/MemoryCandidate、主动循环接线、CognitiveProfile（见 DEVLOG 第十批）。

### P0（2026-10-22）
- 基于工作分支 `a8b5749` 建 `deepseek/moling-v13-integration`；合入 PR15（`deepseek/moling-workspace`，已含 PR13），再合 PR14（`deepseek/moling-polish`），解决 `CODEX.md`/`DEVLOG.md` 冲突，保留两边记录。
- 补 I9 来源标签点开原文：`m-…` 查当前 `WorkbenchState.materials`，`n-…` 查 `BrainState.atlas.nodes`，找不到显示“来源已删除”；没有做把新 s-ID 塞进旧解析器的伪造引用。
- 第八批发布脚本已在分支内：release job 只在工作分支跑，开发分支 CI 的 release 应为 skipped；正式发布验证仍待合入工作分支后做。
- 自动检查：本地 `compileDebugKotlin` 通过；App 测试/打包与 core 测试见本批 CI。真机未测。 

### W11（2026-10-22）
- 分支 `deepseek/moling-radio-play`，基于 W10 `4f3c905`。
- R1 `core/live/Radio.kt` 电台状态机（PREPARING/PLAYING/PAUSED/FINISHED/STOPPED、session/segment/deadline/generation、停止清待播与迟到回调拒绝、重复 start 幂等、30–90 秒/5 分钟/准备超时）；`RadioContent` 只拆案板已有材料与稿子；`RadioPorts.kt` 仅 R4/R6 接口。
- R2 `app/live/RadioHost.kt` + `RadioPanel.kt`：StateFlow 渲染、当前段/来源/进度/暂停继续跳过停止；接案板与对话页；声音走现有 Speaker，不新增联网抓取。
- R3 `HabitBook.quietUntil` + `Exposure` + `Habits.explicitStop/restore`；`Brain.live` 安静期不出现；「先不聊了」立即进安静期并可恢复；设置页/调试页可看。
- R5 `core/predict/Bet.kt` ChoiceBet：同 id 一笔、观测才结算、UNOBSERVABLE、取消不算输、可评分 Brier 与旧预测分开；`BrainState.bets` 与 Brain/BrainHost API、调试页只读列表。
- 自动检查：core-tests **472 全绿**（新增 30）；App 本地 `compileDebugKotlin`、`testDebugUnitTest` 通过；真机未跑。
- 未完成：R4 材料化 FOG/TOLL/Chess；R6 轮盘/音频焦点/VoiceSession/微停顿；R5 卡片/电台赌局入口。不新增权限。

