# v13 交付进度

> 2026-10-21 文档补齐时的核对快照。后续执行者必须以最新GitHub状态更新；“有计划”不等于“已实现”。
> 总入口：[DEEPSEEK-ALL](DEEPSEEK-ALL.md)。只有检查实际代码/CI/真机后才更新对应栏。

| 任务 | 实现状态 | 自动检查 | 真机/发布 | 交付链接 |
|---|---|---|---|---|
| 第八批 R1/R2 | **已合入工作分支 8cc1e54**（经 PR16） | CI通过；记录有15项脚本断言 | 正式发布加固待合入后验证 | [PR13](https://github.com/aozhenyu666-cmyk/test/pull/13) |
| C9内核 | 已合入工作分支147f8bd | Moling CI通过；记录称432核心测试 | 无案板UI的正式包 | [提交](https://github.com/aozhenyu666-cmyk/test/commit/147f8bde2a59ef789733444136941d50c05eae83) |
| I9 J1–J7 | **已合入 8cc1e54**（含来源原文、Claude 审查修复 4 处） | Moling CI通过 | 真机未测，未合入正式包 | [PR15，含PR13代码](https://github.com/aozhenyu666-cmyk/test/pull/15) |
| 动效打磨 | **已合入 8cc1e54** | Moling CI通过 | 真机未测 | [PR14](https://github.com/aozhenyu666-cmyk/test/pull/14) |
| P0前置整合 | 已实现：整合PR15（含PR13）+PR14，补 I9 来源点开原文 | 本地编译通过；CI 见分支 | 真机未测；发布 job 应 skipped | [PR16](https://github.com/aozhenyu666-cmyk/test/pull/16) |
| W10 T1–T9 | **进行中（里程碑2，第十四批修复完成）**：T1/T2(fetch,RSS,HTML,Brave适配)/T3(池、分享解析、分享后台提取正文)/T7(预算与事件) 核心与入口完成，F10-1…F10-7 + 顺手项已修；T4–T6、T8–T9 未做 | core-tests 448 全绿（47 类，V13WebTest 16；此前“442 全绿”已更正，见 DEVLOG 第十四批）；App compileDebugKotlin 通过；真实公网未验 | 真机未测 | [任务](DEEPSEEK-10.md) · [PR17](https://github.com/aozhenyu666-cmyk/test/pull/17) |
| W11 R1–R7 | 任务书已补齐，待实现 | 待验证 | 待验证 | [任务](DEEPSEEK-11.md) |
| W12 A1–A6 | **进行中**：A1–A3 核心 + 最小 UI 完成；A4 仅接口+拒绝降级；A5/A6 未做 | core-tests 461 全绿；App compileDebugKotlin 通过 | 真机未测 | [任务](DEEPSEEK-12.md) · [PR待建] |
| W13 G1–G7 | 任务书已补齐，待实现 | 待验证 | 待验证 | [任务](DEEPSEEK-13.md) |

## 更新格式

每批下方按任务号逐项追加：实际文件/提交、实现状态、测试命令与结果、CI链接、真机支持与未测项、PR与依赖、正式APK源码SHA。旧数据留日期，不能拿本地构建代替正式发布。

缺口统一写在这里并关联任务号；不得只在聊天里说“还差一点”。

当前明确缺口：第八批正式发布验证（P0）；I9/动效真机验收（P0）；W10–W13 功能（各批）。

### W10（2026-10-22）
- 里程碑1 已提交：Sources/WebPort/Inhale/Cycle 核心与测试；BrainHost 来源库 + 案板「加网页/链接」。
- 第十四批修复（提交 19cd181…1047588）：F10-1 内网防护逐跳解析 + OkHttp Dns；F10-2 canonical 保留 query、只按完整 URL 复用；F10-3 修好假服务器测试并断言 TOO_LARGE/TOO_MANY_REDIRECTS；F10-4 Content-Type/charset 嗅探；F10-5 网页材料标外部资料；F10-6 sources.json 原子写与 8MB 上限；F10-7 分享后台抓取 + 15 秒总时限；顺手项（跨主机鉴权头、RSS DOCTYPE 降级、删除原文入口）。core-tests 448 全绿；App compileDebugKotlin 通过；真机未测。
- 未做：设置页搜索/RSS 配置、ShareActivity 提取正文、联网采蜜、Prework 引用校验、HybridRecall/MemoryCandidate、主动循环接线、CognitiveProfile（见 DEVLOG 第十批）。

### P0（2026-10-22）
- 基于工作分支 `a8b5749` 建 `deepseek/moling-v13-integration`；合入 PR15（`deepseek/moling-workspace`，已含 PR13），再合 PR14（`deepseek/moling-polish`），解决 `CODEX.md`/`DEVLOG.md` 冲突，保留两边记录。
- 补 I9 来源标签点开原文：`m-…` 查当前 `WorkbenchState.materials`，`n-…` 查 `BrainState.atlas.nodes`，找不到显示“来源已删除”；没有做把新 s-ID 塞进旧解析器的伪造引用。
- 第八批发布脚本已在分支内：release job 只在工作分支跑，开发分支 CI 的 release 应为 skipped；正式发布验证仍待合入工作分支后做。
- 自动检查：本地 `compileDebugKotlin` 通过；App 测试/打包与 core 测试见本批 CI。真机未测。 
### W12（2026-10-22）
- 分支 `deepseek/moling-outbrain`（基于 W10 `4f3c905`）。实现 A1–A3 核心：`core/talk/Outbrain.kt`（选路/进场包/会话/结果/编码）、`OutbrainMachine.kt`（PREPARED→…→REVIEWED + CANCELLED/EXPIRED、幂等、归属、partial、过期、重启恢复）、`OutbrainDesk.kt`（持久化 + 结果落案板）、`Workbench.attachActorResult`（ACTOR_RESULT 未采用 + 材料，同会话追加、文本幂等）。
- 三条传输：分享/复制、模型 API（复用现有凭据/用量）、联络站（`RelaySync.routeOutbrain` 按 `[[外脑:ob-…]]` 认领）；未配置/失败不伪造。
- A4 `core/talk/Capture.kt`：UID→包名+证书校验、64 节点/8192 字/4 快照/32KiB、cursor/gap、坐标 2 秒/窗口失效、只带观察信号、UNSUPPORTED/DENIED 降级；**不申请无障碍/屏幕权限**。
- 最小 UI：`ui/OutbrainBits.kt`（发到外脑/预览材料/复制/打开/API 问/收线/看结论/看案板）、对话页+案板显示、胶囊状态、分享面板接回、调试页状态；`MainActivity` 恢复第四个「案板」标签（P0 合并丢失 J1 接线）。
- 自动检查：`tools/core-tests` **461 全绿**（442 + 19）；App `compileDebugKotlin` 通过（C:/autopilot-w12-wt）。**真机未测，真实 API/联络站未验**。
- 未完成：A2 辅助填入（需无障碍，只留降级）、A4 真实 Binder 服务与探针、A5 语音收线/微停顿、A6 真机逐传输、Notion outbox 归档、外脑结果 MemoryCandidate。

### 总控者审查（Claude，2026-10-22）
- PR16（第八、九批 + 动效）审查通过，附 4 处界面修复后合入工作分支 `8cc1e54`；核心测试全绿，发布脚本 mock 测试 15/15。真机未测。
- PR17–20 审查：均为“修完再合”，必修 / 应修清单见 [DEEPSEEK-14](DEEPSEEK-14.md)。另：`V13WebTest.fetchWithFakeServer` 在干净环境失败（F10-3），此前“全绿”记录不成立。
- 裁定：W12 A4/A2 冻结；W13 G1 不自动采用；Z12/Z13 总控部分由 Claude 负责。
