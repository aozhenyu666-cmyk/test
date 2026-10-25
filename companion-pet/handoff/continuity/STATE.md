# 同桌：主动接续与严格陪伴（最小闭环）

日期：2026-10-25。基线：integration/tongzhuo-presence-focus-v1 @ a06ac65（0.6.1-preview / 28）。
分支：deepseek/companion-continuity。版本：0.6.2-preview / 29。只改 companion-pet/。

## 一、核对结论（与指令一致）

- 仓库 https://github.com/aozhenyu666-cmyk/test，默认分支不含 companion-pet，不能从默认分支开工。
- companion-pet / cn.companion.pet / 同桌 三者一致；applicationId 未改，签名与记忆兼容。
- companion-voice-assistant/ 是另一旧原型，本轮未触碰。
- 总控、一手未接入、未改动。

## 二、本轮补上的缺口

| 文件 | 原来 | 现在 |
|---|---|---|
| core/continuity/StrictSession.kt（新） | 无结构化回合状态 | 纯 Kotlin 状态机：phase/health/verdict、固定截止、沉默分类、迟结果丢弃、到期不等于达标 |
| core/continuity/StrictEvalPrompt.kt（新） | feedback 只返回一句自由文本 | 严格 schema：verdict/met/missing/quote/feedback/next，解析失败返回 null 走降级 |
| core/continuity/StrictSessionStore.kt（新） | 无持久化 | 原子写；只恢复未结束未到期回合；认空窗、不补造；结束留墓碑 |
| service/PetBrain.kt | 想一想闲置 3 分钟即 endThink | 严格回合不受该清理影响；回答先进核验；每次状态变化落盘；启动时恢复 |
| data/Settings.kt | 无 | strictEnabled（默认关）、strictMinutes（默认 10，3–60） |
| ui/SettingsTab.kt | 无 | “严格陪伴”开关 + 回合时长 + 说明 |

普通陪伴与严格陪伴是两个状态：普通模式代码路径未改。

## 三、状态契约

- 进度 phase：READY / PROMPTING / WAITING / EVALUATING / SUPPORT / COMPLETED / EXPIRED / ABORTED
- 健康 health：OK / DEGRADED / UNAVAILABLE（与进度独立）
- 判定 verdict：PASS / NEED_EVIDENCE / NEED_SUPPORT / INPUT_UNCERTAIN
- 结束原因：COMPLETED / EXPIRED / ABORTED / NEVER_ENGAGED
- 统计区分“独立通过”与“提示后通过”；到期从不记达标。

## 四、持久化与恢复语义

- 落盘点：开启回合、提问、用户回答、提醒、核验结果、暂停/结束、到期。
- 恢复（StrictSessionStore.restore）：
  - Resume：active 且未到期 → 回到原问题，保留 revision（迟到结果仍会被拒），health 置 DEGRADED，并显示断开空窗分钟数；
  - Expired：已过期 → 记 EXPIRED 并告知，不复活；
  - Finished：COMPLETED/ABORTED → 清墓碑，不复活；
  - None：无记录。
- 进程被杀期间不可观测，恢复只承认空窗，不补造期间行为。
- 用户点 × 主动收起 = 记 ABORTED 落盘，重启不会把回合拉回来。

## 五、测试

JVM：441 全过（基线 420 + StrictSessionTest 11 + StrictSessionStoreTest 10）
lint：0 errors / 15 warnings
APK：30,127,353 bytes，SHA-256 A9119C5C710E0C0DC84755DBFD313E21F179CE49BD98E0DC50A8E9AFE9AD9E6D

覆盖场景：沉默不清会话、提醒上限 2 次、简短但充分可通过、缺依据不通过、说不会给支架、版本过期拒绝、跨会话/跨步骤拒绝、中止后迟到结果丢弃、零参与到期记 NEVER_ENGAGED、有回答但到期仍不算通过、提示后通过单独计、识别不确定不计认知失败；存储往返、恢复带空窗、过期不恢复、完成/中止留墓碑、空存储、损坏文件报错不崩、恢复保留 revision、clear 清墓碑。

## 六、明确未完成

- 连续语音（Gemini Live / LiveKit / Pipecat 任一路线）：**未接入**。现在仍是系统 SpeechRecognizer 单次识别 + 文字输入。
- 沉默 30/60/120 秒节奏与“缩小问题”文案已实现，真机未验证。
- 外部 Gemini 路径：仍只显示“应用守护有效”，对话内容未接入，不报告认知达标。
- 视觉进入同一话题：未做。

## 七、真机未测

全部 NOT_RUN：严格回合观感、语音打断、识别纠错、iQOO 后台拉起、窗口层级、锁屏、覆盖安装 28→29 数据保留、杀进程恢复实测。

## 八、需要用户提供的一项配置

连续语音需要一条供应商凭据（Gemini Live API Key 或 LiveKit token 服务地址）。未提供时只能做本地与接口层测试，不能声称语音已接通。
