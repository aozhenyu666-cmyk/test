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
| W11 R1–R7 | 任务书已补齐，待实现 | 待验证 | 待验证 | [任务](DEEPSEEK-11.md) |
| W12 A1–A6 | 任务书已补齐，待实现 | 待验证 | 待验证 | [任务](DEEPSEEK-12.md) |
| W13 G1–G4 | **部分**：G1/G2 计划代驾核心+今天页/设置；G3 墨灵侧训练兑换协议；G4 系统工具接口与拒绝降级。G5–G7、Z13 未做 | core-tests 493 全绿（新增 51）；App compileDebugKotlin 通过 | 真机未测；未改 zongkong/权限/Manifest | [任务](DEEPSEEK-13.md) |

### W13（2026-10-22，分支 deepseek/moling-planner，基于 4f3c905）
- G1/G2：`core/flow/PlanAdoption.kt`（PlanDraft/PlanAdoption/PlanOperation/PlanDriver，可见时间/换日/改版冲突/operationId 幂等/回执分离/UNKNOWN 不重发）；`BrainHost` 存 `mind/plan-adoption.json`，今天页「计划代驾」采纳/不采纳，设置页开关与 10/20/30 分钟。**本地采纳不会自动交给总控或执行现实动作。**
- G3：`core/link/GateV2.kt`（v2 外壳/能力协商/错误码，不动 v1 与 ZK_LINK_VERSION）与 `core/redeem/TrainingRedeem.kt`（墨灵侧协议；pass 只认总控回执并核对绑定；未验证不发奖）。总控侧 Z13 未做。
- G4：`core/system/SystemPorts.kt`（日历/勿扰/歌单端口、能力状态、默认明确拒绝；日历去重/编辑页等待用户保存/勿扰只恢复自己改的档位/歌单 URI 校验）。未加权限、未改 Manifest、未接 Android 适配器。
- 自动检查：`tools/core-tests` 493 个全绿，0 失败（新增 51：PlanDriverTest 15、TrainingRedeemTest 21、SystemPortsTest 10、GateV2Test 5），在 ASCII 路径 `C:/autopilot-w13-wt` 运行；App `compileDebugKotlin` 通过。中文路径跑 core-tests 有 JVM 类路径编码问题，已改用 ASCII junction。
- 明确未完成：Z13 总控侧策略/挑战/rubric/临时通行与 ContentProvider；GateV2 端口 Android 适配器和训练 UI；G2 发送入口（本批不自动执行）；G5 追链与总控训练界面；G6 交叉/新旧版本/备份恢复；G7 最终验收；真机全部未测。
- 提交/PR：见本分支 git log；PR 由 Lead 建。

## 更新格式

每批下方按任务号逐项追加：实际文件/提交、实现状态、测试命令与结果、CI链接、真机支持与未测项、PR与依赖、正式APK源码SHA。旧数据留日期，不能拿本地构建代替正式发布。

缺口统一写在这里并关联任务号；不得只在聊天里说“还差一点”。

当前明确缺口：第八批正式发布验证（P0）；I9/动效真机验收（P0）；W10–W13 功能（各批）。

### W10（2026-10-22）
- 里程碑1 已提交：Sources/WebPort/Inhale/Cycle 核心与测试；BrainHost 来源库 + 案板「加网页/链接」。
- 未做：设置页搜索/RSS 配置、ShareActivity 提取正文、联网采蜜、Prework 引用校验、HybridRecall/MemoryCandidate、主动循环接线、CognitiveProfile（见 DEVLOG 第十批）。

### P0（2026-10-22）
- 基于工作分支 `a8b5749` 建 `deepseek/moling-v13-integration`；合入 PR15（`deepseek/moling-workspace`，已含 PR13），再合 PR14（`deepseek/moling-polish`），解决 `CODEX.md`/`DEVLOG.md` 冲突，保留两边记录。
- 补 I9 来源标签点开原文：`m-…` 查当前 `WorkbenchState.materials`，`n-…` 查 `BrainState.atlas.nodes`，找不到显示“来源已删除”；没有做把新 s-ID 塞进旧解析器的伪造引用。
- 第八批发布脚本已在分支内：release job 只在工作分支跑，开发分支 CI 的 release 应为 skipped；正式发布验证仍待合入工作分支后做。
- 自动检查：本地 `compileDebugKotlin` 通过；App 测试/打包与 core 测试见本批 CI。真机未测。 

