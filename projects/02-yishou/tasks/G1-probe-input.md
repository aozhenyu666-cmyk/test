# G1 · 时间探针输入框 + 入口题卡改成“先说要点”

给 GPT（Codex）的任务卡。总方案见 [PLAN.md](../PLAN.md) 第 3.1、3.2 节。等 G0 合并后开始。

## 规矩

- 全部步骤做完再开一个 PR，中途不停。缺信息按最保守的做法继续，写进 PR 的“待确认”。
- 基础分支：`ccr-a5172662-j1jcik`。新分支：`codex/yishou-g1-probe`。
- 判卷器（`judge/`）由 Claude 另写（C1）。这张卡**不要写判卷逻辑**，用下面的接口和一个假实现先接通。

## 接口（照抄，不要改名）

新建 `app/src/main/java/com/yishou/app/judge/Api.kt`：

```kotlin
package com.yishou.app.judge

/** 锁题：给你看题之前，要点就已经定死 */
data class LockedQ(
    val id: String,
    val prompt: String,          // 给用户看的题面（不含选项）
    val options: List<String>,   // 可以为空；只在要点过了之后才亮
    val points: List<Point>,     // 2–4 个
    val need: Int,               // 至少命中几个
    val reference: String,       // 参考答案，判完才给看
)
data class Point(val id: String, val criterion: String)

/** 时间探针记下的东西 */
data class ProbeTrace(
    val shownAt: Long,
    val firstInputAt: Long?,     // 第一个字或开始说话
    val submitAt: Long,
    val deletes: Int,
    val pauses: List<Pause>,     // 只记 ≥ 8 秒的
    val pastedRanges: List<IntRange>, // 粘贴进来的字在最终答案里的位置
    val viaVoice: Boolean,
)
data class Pause(val atChar: Int, val millis: Long)

data class Verdict(
    val hits: Map<String, String?>, // 要点 id → 引用的原话；没命中为 null
    val offTopic: Boolean,
    val passed: Boolean,
    val note: String,               // 给用户看的一句话
)

interface Judge {
    suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace): Verdict
}
```

假实现 `judge/FakeJudge.kt`：答案里出现了某个要点判据中至少 4 个连续的字，就算命中这一点。仅用于开发和测试，类注释写明“会被 C1 替换”。

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
   - 没过：按 `probeAction` 冻结 15 秒，或提示“把卡住时那个半截念头原样说出来”；标出没命中的判据（只显示判据，不显示参考答案）；同一题最多 3 次。
   - 过了：亮选项，选一个，本机对照锁死的答案核对。选对 → 放行 15 分钟（走 `PassIssuer`）。选错 → 不放，换一题。
3. 题库题转 `LockedQ`：【要点】按句号、分号拆成要点；没有【要点】的题不进入口。`need = max(1, 要点数 - 1)`。
4. 连着答错 2 次，或出不了题，显示 G0 的“等一会儿”。

`PassIssuer.eligible()` 改成看 `Verdict.passed && 选项选对`，删掉旧的 `evidenceOk` 条件。

### 4. 依赖注入

`YishouApp` 里加 `val judge: Judge`，先放 `FakeJudge`。C1 合并后只换这一行。

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
- [ ] 换成真判卷器只需要改 `YishouApp` 里一行
