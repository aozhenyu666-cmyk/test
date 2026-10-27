# G0 · 理顺发布、并入放行收口、堵住“写一句就放”

给 GPT（Codex）的任务卡。总方案见 [PLAN.md](../PLAN.md)。

## 规矩（这张卡优先于 `yishou/AGENTS.md` 里“只做一条”“拿不准就停下问”）

- 下面 5 步**全部做完**，再开一个 PR。中途不要停下来问；真缺信息时，按最保守的做法继续，写进 PR 的“待确认”。
- 基础分支：`ccr-a5172662-j1jcik`。新分支名：`codex/yishou-g0-cleanup`。PR 合回 `ccr-a5172662-j1jcik`。
- 提交说明、注释用中文；不写模型名称。

## 步骤

### 1. 下载页只从主线发布

`deepseek/yishou-v2` 上的 `.github/workflows/yishou-android.yml`，第 43 行 `Publish to release yishou-latest` 没有分支条件，每次推送都会覆盖下载页。
在**那个分支**上单独提一个小提交，给这一步加上：

```yaml
if: github.ref == 'refs/heads/ccr-a5172662-j1jcik'
```

（主线的同名文件已经有这一行，照抄。）这是本卡唯一允许改别的分支的地方，只改这一行。

### 2. 并入 #37 和 #38

把 `codex/yishou-intake-safety`（已包含 #37 和 #38）合进新分支。冲突以 #37/#38 为准。

### 3. 门不免费，但不锁死：加“等待兜底”

#37 删掉了离线放行和“答错也放”，方向对，但题库空或没网时会把人彻底挡在外面。加一条兜底：

- 入口里，题出不来，或者连着答错 2 次，出现“等一会儿”按钮。
- 点了之后是一个 10 分钟倒计时页面：不能跳过，离开页面就重新计时。倒计时结束放行 5 分钟。
- 纯逻辑写在 `gate/WaitFallback.kt`：

  ```kotlin
  /** 等待兜底：等待比答题更难受，所以它只是兜底，不会变成捷径 */
  class WaitFallback(private val clock: () -> Long) {
      fun start(): Long                // 返回开始时间
      fun leave()                      // 离开页面：作废
      fun remainingMs(): Long
      fun done(): Boolean
  }
  ```

  放行时长写成常量：`WAIT_MS = 10 * 60_000L`，`WAIT_PASS_MS = 5 * 60_000L`。
- 放行由 `PassIssuer` 新加一个方法 `issueAfterWait(group, fallback)` 发，`done()` 为假时返回 null。记账类型用新的 `DayStats.WAIT_PASS`。

### 4. 删掉“看完做什么”的放行条件

`GateViewModel` 里放行依赖 `exit` 不为空；这句随便写什么都能过（实测“因为一手”就过）。

- 放行不再看 `exit`。
- `exit` 输入框保留为可选，不填也行，不参与任何判定。

### 5. 改 `yishou/AGENTS.md`

- 把“只做任务里指定的那一条”改成：“只做任务卡里的事，但任务卡里的步骤要全部做完再开 PR；中途不停。”
- 加一节“任务卡在哪”：`projects/02-yishou/tasks/`（在分支 `ccr-1addeeab-loh4x0` 上）。
- 版本号改为 `0.23.0`，README 顶部加一段 0.23 的说明（三到五行，口语）。

## 测试（`app/src/test/java/com/yishou/app/gate/`）

- `WaitFallbackTest`：没等满不放；离开后重新计时；等满放 5 分钟。
- `PassIssuerTest` 补：`exit` 写“因为一手”、题没答对 → 不放行。
- 跑 `./gradlew testMainlineDebugUnitTest assembleMainlineDebug assembleChaluDebug`；本地跑不了就推分支看 CI。

## 验收

- [ ] CI 绿，PR 里贴链接
- [ ] 下载页的 APK 版本是 0.23.0
- [ ] 入口：只写“看完做什么”不答题，过不去
- [ ] 入口：断网 → 能看到“等一会儿”，等满 10 分钟放 5 分钟
