# G1 · 时间探针输入框 + 入口题卡改成“先说要点”

给 GPT（Codex）的任务卡。总方案见 [PLAN.md](../PLAN.md) 第 3.1、3.2 节。等 G0 合并后开始。

## 规矩

- 全部步骤做完再开一个 PR，中途不停。缺信息按最保守的做法继续，写进 PR 的“待确认”。
- 基础分支：`ccr-a5172662-j1jcik`。新分支：`codex/yishou-g1-probe`。
- 判卷器 `judge/` 已经由 Claude 写好（C1，分支 `claude/yishou-c1-judge`），先把它合进你的分支。**不要改 `judge/` 下的文件**，有问题写进 PR。

## 已有的接口（在 `judge/Api.kt`，直接用）

- `LockedQ`：锁题。题库题用 `Locks.fromBank(...)` 转，返回 null 的题不进入口。
- `ProbeTrace`：时间探针记下的东西，你要做的就是把它填出来。
- `Judge.judge(q, answer, trace, seen)`：`seen` 传这一题已经给用户看过的提示（`Point.hint`），从提示里抄的话不算。
- `Verdict`：`passed` 过没过；`block` 本机挡下的原因；`unavailable` 判卷不在（这时显示 G0 的“等一会儿”，不放水）；`note` 直接显示给用户。
- `YishouApp.judge` 已经接好真判卷器。测试里自己写假的 `Judge`。

## 步骤

### 1. 探针输入框 `ui/ProbeTextField.kt`

- 包一层 Compose 的 `TextField`：对外暴露 `value`、`onValueChange`，另外提供 `trace(): ProbeTrace`。
- 纯逻辑放 `judge/ProbeRecorder.kt`，不依赖 Android，接收事件：`shown(t)`、`edit(old, new, t)`、`paste(range, t)`、`voiceStart(t)`、`submit(t)`。
  - 字数变少算一次删改。
  - 两次编辑间隔 ≥ 8 秒，记一个 `Pause`，位置是当时的光标处（拿不到光标就用文本长度）。
  - 一次编辑增加 ≥ 8 个字、且间隔 < 100 毫秒，按粘贴处理（输入法联想上屏一般 < 8 字）。
- 时钟用构造参数传进去，测试里用假时钟。

### 2. 探针规则 `judge/ProbeRules.kt`

```kotlin
enum class ProbeAction { NONE, FREEZE_15S, ASK_FRAGMENT }
fun probeAction(trace: ProbeTrace, answer: String, passed: Boolean): ProbeAction
```

- TTFT < 3 秒且没过 → `FREEZE_15S`
- TTFT > 40 秒，且答案实在字 < 15 → `ASK_FRAGMENT`（不管过没过）
- 其他 → `NONE`
- 实在字用已有的 `think/Words.substance()`。

### 3. 入口题卡改流程

现在入口题卡（`card/` + `gate/`）是：下注 → 依据 → 揭晓，可以只点选项。改成：

1. 只显示题面，不显示选项。用 `ProbeTextField` 说要点（语音也行，走已有语音输入）。
2. 交了 → `Judge.judge()`。
   - 没过：显示 `Verdict.note`；按 `probeAction` 冻结 15 秒，或提示“把卡住时那个半截念头原样说出来”；同一题最多 3 次。
   - **不要显示判据原文**（`Point.criterion`）和参考答案，只能显示没命中要点的 `Point.hint`，并把显示过的 hint 放进下一次的 `seen`。
   - `unavailable` 为真：不算这次，显示“等一会儿”。
   - 过了：亮选项，选一个，本机对照锁死的答案核对。选对 → 放行 15 分钟（走 `PassIssuer`）。选错 → 不放，换一题。
3. 题库题转 `LockedQ`：用 `Locks.fromBank(id, passage, question, options, answer, explain, hint = card.skill.hint)`。返回 null 的题不进入口。
4. 连着答错 2 次，或出不了题，显示 G0 的“等一会儿”。

`PassIssuer.eligible()` 改成看 `Verdict.passed && 选项选对`，删掉旧的 `evidenceOk` 条件。

## 测试

- `ProbeRecorderTest`：TTFT、删改、停顿、粘贴识别，各至少 2 个用例。
- `ProbeRulesTest`：三种动作的边界（2.9 秒 / 3.0 秒，40 秒 / 15 字）。
- `GateFlowTest`（纯逻辑层）：
  - 只点选项、没交要点 → 不放行
  - 要点没过 → 不亮选项
  - 要点过、选项错 → 不放行
  - 要点过、选项对 → 放行 15 分钟

## 验收

- [ ] CI 绿，PR 里贴链接
- [ ] 真机：入口先看不到选项；秒答乱写会冻结 15 秒
- [ ] 真机：只写“因为一手”“有道理”，或者照抄题目，过不去
