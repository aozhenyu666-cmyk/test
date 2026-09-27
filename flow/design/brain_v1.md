# 小满的大脑 v1：思考 + 控制手机（设计稿）

## 目标

1. **会思考**：不是按模板念一句，而是看完事实和记忆之后，自己决定该不该开口、说什么、要不要做点什么。
2. **有记忆**：记得 TA 是什么样的人、说过什么话、答应过什么事，哪种说法对 TA 管用。
3. **能动手**：能调用手机功能（开口、弹出主控台、打开 App、按约定锁 App），而不只是发消息。
4. **不吃载体**：该不该升级、能不能锁，由代码按 TA 批准的契约决定；模型只负责判断和措辞。换成更宽松的模型，底线也不变。

## 结构：一张脸，一个大脑

```
感知（已有）          记忆（新）                  思考（新）              行动（代码执行）
前台采样 / 用量  ─┐   brain/profile.md 关于TA     规则层（代码）：          开口：focus_hub_nav:check_in
判断官结论       ─┤   brain/commitments.jsonl   安静时段、冷却、承诺到期、    （念 + 弹主控台 + 待回应）
用户进展         ─┼─▶ brain/said.jsonl          契约阶段 → 允许的动作上限 ─▶ 打开 App（Intent）
主控台打卡/回应  ─┤                                    │                     派活 / 交差：focus_hub_warden
督促契约/派活    ─┘                              思考（Tools.Chat.call）：   锁 App：warden → app_suspender
                                               在上限内选：沉默/开口/        （按契约，先演练）
                                               记承诺/派活/建议升级，       写 said / 承诺
                                               输出 JSON，代码校验
```

- **新 ToolPkg `companion_brain`**：由流程线维护；不改 focus_hub，只调用它的工具（`check_in`、`warden`、`report_progress`）。
- **小满·陪伴**仍是唯一的脸。大脑的每次决定都会写进 `said.jsonl`，再由提示词钩子注入她的会话，所以 TA 在会话里回她时，她知道自己刚说了什么、为什么说。

## 四个部件

### 1. 记忆（文件，TA 也能直接看和改）

- `companion/brain/profile.md`：关于 TA。目标、作息、容易卡住的地方、什么话管用或不管用、TA 明确说过的偏好和禁区。TA 写的行永远保留；反思只在"观察"区追加。
- `companion/brain/commitments.jsonl`：TA 的承诺。例如"一点前投一家"，记录为 `{text, due_ts, status: open|kept|broken|cancelled, quote}`。
- `companion/brain/said.jsonl`：小满主动说过的话、当时的阶段，以及 TA 有没有回应、多久回应。

### 2. 注入（`registerToolPkgSystemPromptComposeHook`）

只对小满·陪伴的会话（52a18815…）和大脑自己的思考调用生效。每一轮自动附上：

- profile 摘要；
- 未完成的承诺；
- 小满最近主动说的 3 句话；
- 最新的判断；
- 今天的进展。

不需要她调工具去查，也不多花一次模型调用。

### 3. 思考（每 15 分钟一次，工作流调用 `companion_brain:tick`）

1. **收集事实**：判断官最新结论、最近采样、进展、打卡回应、承诺、督促阶段。
2. **规则层（代码，确定性）**：
   - 安静时段不开口；
   - 冷却期不开口；
   - 事实没变就不开口，除非有承诺到期；
   - 按契约算出当前阶段和**允许的动作上限**。模型可以选更轻的动作，不能超过上限。
3. **思考（`Tools.Chat.call`）**：输入人设、记忆、事实和允许的动作，输出 JSON：
   `{decide: silent|speak, line, action: none|open_app|record_commitment|assign|propose_escalate, commitment, why}`
4. **校验**：代码检查动作不超上限、话不超长、不含包名或系统词；不合格就沉默并记账。

### 4. 反思（每晚一次）

读当天的 `said`、回应、进展和判断，用 `Tools.Chat.call` 总结：

- 哪种说法换来了回应，哪种被无视；
- 哪些承诺兑现了。

结果写进 profile 的"观察"区，并更新承诺状态。涉及规则的想法只写 `RULE_PROPOSALS`，**不自动立法**。

## 替换关系

- `S3_Brief_Loop`：大脑跑稳后停用（先并行一天，只记录不开口，对比效果）。
- focus_hub 的"陪伴打卡"模板：**不启用**。大脑自己调用 `check_in(message=…)`，避免两个声音。
- 判断官 G2：保留，作为事实来源之一。
- `drift_alert.sh` 的规则提醒：保留，已经走小满·陪伴。以后可以并入大脑。

## 待用户拍板（立法类）

见对话中的问题：锁 App 的开关、允许大脑用的手机动作、安静时段与时区、主动开口的上限。
