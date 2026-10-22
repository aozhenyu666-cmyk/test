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
| W11 R1–R7 | **部分实现 + 第十四批修复完成**：R1 电台核心、R2 案板/对话面板（音频焦点最小版、TTS 令牌）、R3 安静期（「别管我」同 EXIT、明确停止不记 REPLIED、只记这一次出现）、R5 赌局核心完成；F11-1…F11-7 + 小项已修，F11-5 未做；R4/R6 其余只有接口与说明 | core-tests 50 类 481 全绿（HabitTest 8 / FogTest 13 / RadioTest 14 / V13WebTest 16）；App compileDebugKotlin / testDebugUnitTest 通过 | 真机未测；R4/R6/R5 UI 入口未做；F11-5 未接 | [任务](DEEPSEEK-11.md) |
| W12 A1–A6 | **部分（第十四批 F12-1…F12-7 已修，已合入工作分支）**：A1–A3 外脑会话核心 + 最小界面（分享 / 复制 / 模型 API）；A4 屏幕快照按裁定冻结（已修、不接线）；A2/A5/A6 未做；联络站传输目前只是复制并打开 | 合入后 core-tests 见 DEVLOG 第十四批合入记录 | 真机未测 | [任务](DEEPSEEK-12.md) · [第十四批](DEEPSEEK-14.md) |
| W13 G1–G4 | **部分（第十四批 F13-1…F13-8 已修；第十五批 B5 补 G4 适配器）**：G1/G2 计划代驾核心+今天页/设置（已取消自动采用，到点只提醒）；G3 墨灵侧训练兑换协议；G4 系统工具 Android 适配器+设置页（日历忙闲/ACTION_INSERT、勿扰只恢复自己记录、歌单显式 Intent）。G5–G7、Z13 未做 | core-tests 590 全绿（58 类；SystemPortsTest 10）；App testDebugUnitTest 293 全绿（28 类，新增 SystemToolRulesTest 8）；CI 以 push 后 run 为准 | 真机未测（G4 适配器）；B5 只加 READ_CALENDAR 且点开关才申请；未改 zongkong、core SystemPorts 契约、签名/包名 | [任务](DEEPSEEK-13.md) · [第十五批](DEEPSEEK-15.md) · [第十四批](DEEPSEEK-14.md) |

### W13（2026-10-22，分支 deepseek/moling-planner，基于 4f3c905）
- G1/G2：`core/flow/PlanAdoption.kt`（PlanDraft/PlanAdoption/PlanOperation/PlanDriver，换日/改版冲突/operationId 幂等/回执分离/UNKNOWN 不重发）；`BrainHost` 存 `mind/plan-adoption.json`，今天页「计划代驾」采纳/不采纳，设置页到点提醒开关与 10/20/30 分钟。**第十四批 F13-1 起没有自动本地采用：到点只推「一键采用」提醒（通知 + 今天页按钮），他点了才算；也不会自动交给总控或执行现实动作。**
- G3：`core/link/GateV2.kt`（v2 外壳/能力协商/错误码，不动 v1 与 ZK_LINK_VERSION）与 `core/redeem/TrainingRedeem.kt`（墨灵侧协议；pass 只认总控回执并核对绑定；未验证不发奖）。总控侧 Z13 未做。
- G4：`core/system/SystemPorts.kt`（日历/勿扰/歌单端口、能力状态、默认明确拒绝；日历去重/编辑页等待用户保存/勿扰只恢复自己改的档位/歌单 URI 校验）。未加权限、未改 Manifest、未接 Android 适配器。
- 第十五批 B5（G4 适配器，2026-10-22）：新增 `app/system/`（`SystemToolConfig` 纯规则、`AndroidSystemPorts` 实现 core 三个端口、`SystemToolHost` 持久化与动作记录）；设置页「系统工具」三项独立开关（日历选完后读忙闲、`ACTION_INSERT` 写日程由用户保存、勿扰改/恢复自己改过的档位、歌单显式 Intent）；调试页只读能力状态/最近动作；`BrainHost.planDay` 用日历忙闲。只加 `READ_CALENDAR`，点开关才申请；不申请 `WRITE_CALENDAR`。core-tests 590 全绿；App testDebugUnitTest 293 全绿；真机未测。未做进入水道自动打开歌单/专注自动勿扰。
- 第十四批修复（提交 `7f0f496`…`df7ac6d`）：F13-1 取消自动采用与可见时间、到点只提醒；F13-2 不采纳/拒绝粘住；F13-3 SENDING→UNKNOWN、ZK_PENDING 不本地过期、已发不记没发；F13-4 capabilities 缺失拒绝；F13-5 空白名单拒绝；F13-6 requestId 唯一；F13-7 锁内改状态、锁外调端口（“只成功一次”由总控保证）；F13-8 定稿文案改“你已定稿”；小项 acceptReceipt 一致。详见 DEVLOG 第十四批。
- 自动检查：`tools/core-tests` **510 个全绿，0 失败**（51 类；W13 类 62：PlanDriverTest 21、TrainingRedeemTest 26、SystemPortsTest 10、GateV2Test 5），在 ASCII 路径 `C:/autopilot-w13-wt` 运行；App `compileDebugKotlin` 通过。中文路径跑 core-tests 有 JVM 类路径编码问题，已改用 ASCII junction。
- 明确未完成：Z13 总控侧策略/挑战/rubric/临时通行与 ContentProvider；GateV2 端口 Android 适配器和训练 UI；G2 发送入口（本批不自动执行）；G5 追链与总控训练界面；G6 交叉/新旧版本/备份恢复；G7 最终验收；真机全部未测。
- 提交/PR：见本分支 git log；PR 由 Lead 建。

## 更新格式

每批下方按任务号逐项追加：实际文件/提交、实现状态、测试命令与结果、CI链接、真机支持与未测项、PR与依赖、正式APK源码SHA。旧数据留日期，不能拿本地构建代替正式发布。

缺口统一写在这里并关联任务号；不得只在聊天里说“还差一点”。

当前明确缺口：第八批正式发布验证（P0）；I9/动效真机验收（P0）；W10 T4–T9；W11 R4/R6 与 R5 UI 入口、F11-5 曝光回报、真机；W12–W13 功能（各批）；B5 系统工具真机验收（日历/勿扰/歌单）。

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
- A4 `core/talk/Capture.kt`：调用方（墨灵）UID→包名+固定证书校验（不是目标 App）、范围只能来自总控用户选择、64 节点/8192 字/4 快照/32KiB、cursor/gap、坐标 2 秒/窗口失效、只带观察信号、UNSUPPORTED/DENIED 降级；**不申请无障碍/屏幕权限**。
- 最小 UI：`ui/OutbrainBits.kt`（发到外脑/预览材料/复制/打开/API 问/收线/看结论/看案板）、对话页+案板显示、胶囊状态、分享面板接回、调试页状态；`MainActivity` 恢复第四个「案板」标签（P0 合并丢失 J1 接线）。
- 第十四批修复（提交 `e064eb2`…`8d14b8b`）：F12-1 追加结果回待审且 claims 不含新文本；F12-2 会话 id 加时间戳/读坏文件不重用编号/复用旧稿前核对；F12-3 收线默认不选、标记优先；F12-4 过期可清/手动接回/“稍后”真推迟 1 小时；F12-5 API 防重复花钱；F12-6 Capture 只修不接线（校验墨灵调用方、actorTurns 不放大、排除密码/验证/支付/通知、范围只能来自用户选择）；F12-7 分享回流多会话弹窗带原文、12000 截断、超出另存材料、API 回答过 clean()。
- 自动检查：`tools/core-tests` **49 类 478 测试全绿**（本批新增 11）；App `compileDebugKotlin` 通过（C:/autopilot-w12-wt）。**真机未测，真实 API/联络站未验**。
- 未完成：A2 辅助填入（需无障碍，只留降级）、A4 真实 Binder 服务与探针、A5 语音收线/微停顿、A6 真机逐传输、Notion outbox 归档、外脑结果 MemoryCandidate。

### W11（2026-10-22）
- 分支 `deepseek/moling-radio-play`，基于 W10 `4f3c905`。
- R1 `core/live/Radio.kt` 电台状态机（PREPARING/PLAYING/PAUSED/FINISHED/STOPPED、session/segment/deadline/generation、停止清待播与迟到回调拒绝、重复 start 幂等、30–90 秒/5 分钟/准备超时）；`RadioContent` 只拆案板已有材料与稿子；`RadioPorts.kt` 仅 R4/R6 接口。
- R2 `app/live/RadioHost.kt` + `RadioPanel.kt`：StateFlow 渲染、当前段/来源/进度/暂停继续跳过停止；接案板与对话页；声音走现有 Speaker，不新增联网抓取。
- R3 `HabitBook.quietUntil` + `Exposure` + `Habits.explicitStop/restore`；`Brain.live` 安静期不出现；「先不聊了」立即进安静期并可恢复；设置页/调试页可看。
- R5 `core/predict/Bet.kt` ChoiceBet：同 id 一笔、观测才结算、UNOBSERVABLE、取消不算输、可评分 Brier 与旧预测分开；`BrainState.bets` 与 Brain/BrainHost API、调试页只读列表。
- 自动检查：core-tests **472 全绿**（新增 30）；App 本地 `compileDebugKotlin`、`testDebugUnitTest` 通过；真机未跑。
- 未完成：R4 材料化 FOG/TOLL/Chess；R6 轮盘/音频焦点/VoiceSession/微停顿；R5 卡片/电台赌局入口。不新增权限。
- 第十四批修复（提交 b947d3e…f106752，2026-10-22）：F11-1 音频焦点最小正确版（失焦/拔耳机/来电/熄屏/离开面板 stopAll，拿不到焦点只出文字）；F11-2 Speaker 每次 speak 令牌、stop 作废，电台播前查 accepts；F11-3「别管我」同 Live.EXIT 进安静期（只改那一处）；F11-4 EXIT/别管我不报 REPLIED，explicitStop 只给这一次出现记 STOPPED、找不到不造假，拦人放行只记一次 GATE；F11-6 不拦人的破雾退出不报 release、ignored 清零；F11-7 前 3 + EXIT 必留；小项 QUOTE 标引用、“先安静”改名挪位、暂停从段首续念、EXIT 回话改“设置里恢复”。**F11-5 未做**（生产 App 没有把被拦浮层 / 没戴耳机回报 UNOBSERVED，仍按 SHOWN 记）。core-tests 481 全绿；App 编译/单测通过；真机未测。



### 总控者审查（Claude，2026-10-22）
- PR16（第八、九批 + 动效）审查通过，附 4 处界面修复后合入工作分支 `8cc1e54`；核心测试全绿，发布脚本 mock 测试 15/15。真机未测。
- PR17–20 审查：均为“修完再合”，必修 / 应修清单见 [DEEPSEEK-14](DEEPSEEK-14.md)。另：`V13WebTest.fetchWithFakeServer` 在干净环境失败（F10-3），此前“全绿”记录不成立。
- 裁定：W12 A4/A2 冻结；W13 G1 不自动采用；Z12/Z13 总控部分由 Claude 负责。
