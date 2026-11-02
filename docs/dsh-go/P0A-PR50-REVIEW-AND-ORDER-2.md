# PR #50 审查 · 一手 ↔ DSH Go 做题交接（暂不合并）

> PR：`aozhenyu666-cmyk/test#50`，分支 `feat/dsh-handoff-2026-11-02` @ `dc1b825a`，目标 `ccr-a5172662-j1jcik` @ `ec5f302c`
> DSH Go 对端：`aozhenyu666-cmyk/dsh-Go` 分支 `feat/p0-yishou-loop-2026-11-02` @ `9bd93fb`（294 项内核测试通过，安装包 r4-p4-build-4）

## 结论

**协议对得上，安全边界守住了，可以进入真机联调；但有 3 处"进了 DSH 模式却出不去"的死路要先补，并且 PR 说明漏报了集成测试的失败。** 暂不合并。

### 协议逐项核对（与 `YishouProtocol.kt` 对照）

| 项 | 一手 PR | 结果 |
|---|---|---|
| action / extras 名字和类型 | `DshHandoff.kt:4-14` 与 DSH Go 完全一致 | ✅ |
| 请求校验：版本、eventId ≤64、nonce 16–64、reply 必须是 `io.github.ccch1mneyyy.dsh_app` 或其带点后缀 | `DshHandoff.kt:29-38`；`...dsh_appX`（不带点）会被拒 | ✅ |
| 凭证回传：eventId、nonce 原样，分钟夹到 1–120 | `DshHandoff.kt:41-47` | ✅ DSH Go 侧也限 1..120 |
| 回传只在 Pass 已存库后 | `GateViewModel.finishGrant` → `sendDshPass`，且只认 `GATE_PASS` | ✅ 比任务单更严 |
| 低能量放行（试满后选对）、默写放行 | DSH 模式下都不发凭证（`GateViewModel.kt:252-253、351-352`） | ✅ 任务单没要求，是正确的加严 |
| 一手能否"看见" DSH Go（Android 11+ 包可见性） | 一手清单已有 `MAIN`+`LAUNCHER` 查询，DSH Go 有桌面图标 → 可见 | ✅ |
| 思考页不导出，只开放校验跳板 | `DshTrainActivity` 导出，`GateActivity` 仍 `exported=false` | ✅ |
| 放行组 | 用目标 APP 在一手里的组；B站 若在一手关注名单里，一手自己的门禁会认这张 Pass | ✅ |
| 放弃做题 | DSH 模式下返回键和底部按钮只 `finishAndRemoveTask()`，回到 DSH Go 拦截页 → DSH Go 收回交接 | ✅ |
| 同一事件页面重建不重发；同一 APP 的新交接重新开题 | `GateViewModel.start()` 用 eventId 区分 | ✅ |

### CI 核对

| 检查 | 结果 | 说明 |
|---|---|---|
| `yishou-android / build`（单测 + 两版打包） | ✅ 成功 | |
| `yishou-integration / android-runtime (34)` | ❌ 失败 | 21 项中只挂 `VoiceIntegrationTest`（15 s 超时） |
| `yishou-integration / android-runtime (29)` | ❌ 失败 | 挂 `VoiceIntegrationTest` + `GateQuestionsIntegrationTest.oldBankCardWithoutPointsStillGivesAQuestion` |
| 与基线 `ec5f302` 的同一套测试（run 41374474826）对比 | **失败集合完全相同** | 不是这个 PR 引入的；三个门禁集成测试在 API 34 上全过 |

**汇报问题**：PR 说明只写了 `build` 成功，没提两项集成测试失败。结论虽然是"既有失败"，但必须写出来，并附上与基线的对比。

---

## 问题清单

| ID | 级别 | 问题 | 位置 |
|---|---|---|---|
| R1 | P1 | **陪练窗口进行中**时，DSH 模式显示"先回到陪练"，按钮打开一手的陪练页并关掉思考页 → 人被带进陪练，DSH Go 拦截页在后台空等 15 分钟后记"已拦截"。不是绕过，但人会以为卡住了 | `GateActivity.kt` `s.inWindow ->` 分支 |
| R2 | P1 | **伴读没收线**（`partnerPending`）时同理：按钮跳到一手首页 | `GateActivity.kt` `s.partnerPending ->` 分支 |
| R3 | P1 | **一手出不了正式题**（题库空 + 没配模型，只剩默写）时，DSH 模式下默写不发凭证（对的），但提示写"继续做题后才能回到拦截页放行"，而实际上没有题可做 → 死路 | `GateViewModel.kt:352` |
| R4 | P2 | 发凭证与否靠统计标签 `event == DayStats.GATE_PASS` 判断。将来有人改统计标签就会悄悄断掉回传。应该由放行来源（判卷通过）显式决定 | `GateViewModel.finishGrant` |
| R5 | P2 | DSH 模式下 `passMinutes != null` 但 `dshSent == false` 且没有 error 时，界面显示"过了，正在回到…"却不会自动关闭（目前路径上很难出现，但界面文字不该在没发出时就说"正在回到"） | `GateActivity.kt` `s.passMinutes != null ->` 分支 |
| R6 | P2 | `if (correct && mayGrant())` 也改变了**非 DSH 模式**：测试页、陪练窗口里选对题不再走这条放行。看起来是在修一个旧漏洞（测试页本不该放行），但超出任务单，需要负责人知情 | `GateViewModel.kt:251` |
| R7 | 汇报 | PR 说明漏报集成测试失败 | PR 正文 |

---

## 下一阶段任务（给 DeepSeek，只改 `yishou/`，在同一个 PR 分支上继续）

```
继续在 feat/dsh-handoff-2026-11-02 上提交（不新开分支、不 force push、不合并）。
每个 Y 一个中文提交；先写纯逻辑和 JVM 测试，再接界面。
CI 失败就停下贴日志原文。状态只写 DONE / STOPPED(原因)。
最后更新 PR 正文：补上 yishou-integration 两项失败、失败用例名、与基线 ec5f302 的对比结论。
```

### Y5 · DSH 模式下"出不了题"时，明确告诉人回拦截页（修 R1、R2、R3）

1. 在 `dsh/DshHandoff.kt` 加纯函数：

```kotlin
/** DSH 模式下这一次能不能在一手里拿到可回传的放行；不能时给一句人话原因。null = 能。 */
fun blockedReason(inWindow: Boolean, partnerPending: Boolean, onlyRecall: Boolean): String? = when {
    inWindow -> "陪练窗口进行中，这次不能在一手答题放行"
    partnerPending -> "上次伴读还没收线，这次不能在一手答题放行"
    onlyRecall -> "一手现在没有可出的题（题库空、也没配模型）"
    else -> null
}
```

   测试 `DshHandoffTest` 补 4 个用例（三种原因各一个、都为 false 时返回 null）。
2. `GateScreen` 里，**`s.dshMode` 为真时**，`inWindow` / `partnerPending` 两个分支不再显示"回到陪练""回一手收线"，改为显示 `blockedReason(...)` 的文字，加一句"回到拦截页，用那里的训练题"，按钮只调 `onHome`（即 `finishAndRemoveTask()`）。
3. `onlyRecall` 的判定：在 `GateViewModel.pick()` 里，DSH 模式下如果选出的 `mode == GateMain.LOW_ENERGY`，就把状态记成"只有默写"（新增 `dshNoQuestion: Boolean`），界面按第 2 条显示。`GateViewModel.kt:352` 那句"继续做题后才能回到拦截页放行"改成 `blockedReason(..., onlyRecall = true)` 的文字。
4. 非 DSH 模式的界面和行为一律不变。

**验收**：
- 单测全过；
- `grep -n "回到陪练\|回一手收线" yishou/app/src/main/java/com/yishou/app/gate/GateActivity.kt` 的每一处，都在 `!s.dshMode` 的条件之内（把对应代码段贴进提交说明）。

### Y6 · 回传由"判卷通过"显式决定，不靠统计标签（修 R4、R5）

1. `finishGrant(pass, event)` 加参数 `verified: Boolean`。三个调用处传值如下：
   - `pass()` 里（线程 / 现出题 / 题库 `issue()`）传 `true`；
   - 题库选对那一支：`issueRevealed` 得到的传 `false`，`issue()` 得到的传 `true`；
   - 默写传 `false`。
2. `sendDshPass` 只在 `verified == true` 时调用，删掉 `event == DayStats.GATE_PASS` 这个判断。
3. 界面：DSH 模式下，`passMinutes != null && !s.dshSent && s.error == null` 时，文字改成"这次放行不能交回 DSH Go，请回到拦截页"，并显示"回到拦截页"按钮；只有 `s.dshSent` 时才显示"过了，正在回到…"。
4. 把"判断要不要回传"抽成纯函数 `DshHandoff.shouldSend(dshMode, verified, isTest, group)`，补 4 个用例（DSH + 判卷通过 + 正常组 → true；非 DSH → false；未判卷 → false；测试页或"早上"组 → false）。

**验收**：
- `grep -n "DayStats.GATE_PASS" yishou/app/src/main/java/com/yishou/app/gate/GateViewModel.kt` 里，不再有任何一处和 `sendDshPass` 有关；
- 单测全过。

### Y7 · 说清 R6

不改代码。在 PR 正文里单独写一段：`if (correct && mayGrant())` 对非 DSH 模式的影响——测试页、陪练窗口、伴读未收线时，题库选对**不再**发放行。说明为什么这是对的，并列出受影响的已有用例（`GateIntegrationTest`、`GateQuestionsIntegrationTest` 是否覆盖这条路径）。由负责人决定是否保留。

---

## 审查方（我）接下来做的

1. **出一个手机能直接下载的一手测试版**：这个 PR 的安装包现在只在 Actions 的 Artifacts 里，要登录 GitHub 才能下，手机上不方便。我会加一个只在这个 PR 分支上触发、发布预发布版本的构建。包名保持 `com.yishou.app`（协议要求）、签名沿用仓库里的调试签名，可以直接覆盖安装、保留一手的数据。
2. **真机联调清单（P0-A 验收）**，两个 App 都装好后按顺序测：
   1. 一手关注名单去掉 B站 → 打开 B站 → DSH Go 拦截页出现「去一手答一道题」；
   2. 点按钮 → 一手出题 → 答对、判卷通过 → **自动回到 B站**，DSH Go 记录里有一条 `RELEASED · yishou`；
   3. 再点一次，进一手后**直接按返回** → 回到拦截页，按钮可以再点；
   4. 进一手后**不答题，直接切到 B站** → B站 **再次被拦**；
   5. 进一手后**切到抖音** → 抖音被拦；
   6. 进一手后放着不动超过 15 分钟 → 回来时拦截已结束，记"已拦截"，B站 仍然会被拦。
3. 六条都通过后，才建议合并 PR #50 到 `ccr-a5172662-j1jcik`，同时把 DSH Go 的 `feat/p0-yishou-loop` 合进 R4 集成分支。
