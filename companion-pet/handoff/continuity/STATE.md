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
| service/PetBrain.kt | 想一想闲置 3 分钟即 endThink | 严格回合不受该清理影响；回答先进核验再决定通过；迟到结果丢弃 |
| data/Settings.kt | 无 | strictEnabled（默认关）、strictMinutes（默认 10，3–60） |
| ui/SettingsTab.kt | 无 | “严格陪伴”开关 + 回合时长 + 说明 |

普通陪伴与严格陪伴是两个状态：普通模式代码路径未改。

## 三、状态契约

- 进度 phase：READY / PROMPTING / WAITING / EVALUATING / SUPPORT / COMPLETED / EXPIRED / ABORTED
- 健康 health：OK / DEGRADED / UNAVAILABLE（与进度独立）
- 判定 verdict：PASS / NEED_EVIDENCE / NEED_SUPPORT / INPUT_UNCERTAIN
- 结束原因：COMPLETED / EXPIRED / ABORTED / NEVER_ENGAGED
- 统计区分“独立通过”与“提示后通过”；到期从不记达标。

## 四、测试

JVM：431 全过（基线 420 + StrictSessionTest 11）
lint：0 errors / 15 warnings
APK：29,812,659 bytes（构建后另给最终 SHA）

覆盖场景：沉默不清会话、提醒上限 2 次、简短但充分可通过、缺依据不通过、说不会给支架、过期版本拒绝、跨会话/跨步骤拒绝、中止后迟到结果丢弃、零参与到期记 NEVER_ENGAGED、有回答但到期仍不算通过、提示后通过单独计、识别不确定不计认知失败。

## 五、明确未完成

- 连续语音（Gemini Live / LiveKit / Pipecat 任一路线）：**未接入**。现在仍是系统 SpeechRecognizer 单次识别 + 文字输入。
- 沉默自动提醒的 30/60/120 秒节奏与“缩小问题”文案已实现，但真机未验证。
- 持久化：StrictSessionState 可序列化，但本轮尚未落盘恢复（进程重启恢复仍缺）。
- 外部 Gemini 路径：仍只显示“应用守护有效”，对话内容未接入，不报告认知达标。
- 视觉进入同一话题：未做。

## 六、真机未测

全部 NOT_RUN：严格回合实际观感、语音打断、识别纠错、iQOO 后台拉起、窗口层级、锁屏、覆盖安装 28→29 数据保留。

## 七、需要用户提供的一项配置

连续语音需要一条供应商凭据（Gemini Live API Key 或 LiveKit token 服务地址）。未提供时只能做本地与接口层测试，不能声称语音已接通。
