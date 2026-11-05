# G3 · 专注流（一次做完）

> **作废。** 专注时段按新分工归总控，见 [DeepSeek 总任务书 D1](../../DEEPSEEK-D1.md) 第 2 节。下面的内容只作参考。

给 GPT（Codex）的任务卡。

## 这是什么

用户的原话：做题时经常分心，比别人慢很多；一般的 AI 只看结果、不看时间。要一个“专注流”：

- 开始前有一份计划：做什么、做多少、总共多久、每组几分钟、休息几分钟。计划可以让 AI 按模板生成，整段粘进来。
- 开始后一组一组走，像番茄钟，但节奏自己定（比如 10 分钟一组、休息 1 分钟；中途发现 6 分钟一组更顺，就改）。
- **不验收内容**。每组结束只点一下“做完了 / 没做完”，可以加一句。
- 整段时间里手机只能用：系统界面、一手、白名单（比如 ChatGPT、Gemini）、通讯应用（微信、短信、电话）。别的全拦，**休息时也拦**，收费站的放行在专注流里不算数。
- 提前结束要在一手里等 3 分钟，离开页面重新计时。

## 规矩（先读）

- **12 步全部做完再开一个 PR，中途不停。** 缺信息按最保守的做法继续，写进 PR 的“待确认”。
- 基础分支：`claude/yishou-c1-judge`（已经有 `focus/Focus.kt` 的纯逻辑和测试）。新分支：`codex/yishou-g3-focus`。PR 目标分支：`ccr-a5172662-j1jcik`。
- **不要改 `focus/Focus.kt`、`judge/`、`thread/`、`reflex/`。** 可以在 `focus/` 下新建文件。
- 中文提交说明、中文注释、口语短句；不写模型名称。

## 已经写好的（直接调）

| 用途 | 调用 |
|---|---|
| 模板 | `FocusPlans.TEMPLATE`、`FocusPlans.ASK_AI`（发给 AI 的整句） |
| 读计划 | `FocusPlans.parse(text)` → `FocusPlan?`；`FocusPlans.check(plan)` → 原因或 null |
| 开始 | `FocusEngine.start(plan, now)` → `FocusSession?` |
| 现在第几组 | `session.phase(now)` → `Phase(kind = WORK/REST/DONE, round, startedAt, endsAt)` |
| 换节奏 | `FocusEngine.retune(s, workMin, restMin, note, now)` |
| 延长 | `FocusEngine.extend(s, minutes, now)`（只能延） |
| 每组标记 | `FocusEngine.mark(s, round, done, note, now)` |
| 提前结束 | `askQuit` → `quitRemainingMs` → `quit`；离开等待页 `cancelQuit` |
| 拦不拦 | `FocusRules.decide(pkg, self, allowed, messaging, systemAllowed, staticSystem)` |
| 应用名 → 包名 | `FocusRules.resolve(names, installed)`；通讯默认 `FocusRules.DEFAULT_MESSAGING` |

## 步骤

### 1. 存取 `focus/FocusStore.kt`（新建）

- 正在进行的那一场存 `filesDir/focus_active.json`，结束的追加到 `filesDir/focus_history.json`（最多 200 场）。
- 新建 `focus/FocusJson.kt` 做 `FocusSession` 和 JSON 互转（计划、开始、结束、换节奏、标记、提前结束时间、允许的包名）。
- `YishouApp` 里放一个 `val focus = FocusStore(this)`，对外提供 `StateFlow<FocusSession?>`；进程重启后读回来，过了 `endsAt` 自动归档。
- 允许的应用存**包名**（`allowedPackages: Set<String>`），和计划里的名字分开存。

### 2. 首页入口

首页四个大入口旁边加“专注流”。正在进行时，首页顶部一条横幅：“专注中 · 第 N 组 · 还剩 mm:ss”，点进去回到专注页。

### 3. 计划页 `ui/FocusPlanScreen.kt`

- 上面两个按钮：“复制给 AI 的模板”（复制 `FocusPlans.ASK_AI`，提示“已复制，发给 ChatGPT/Gemini，说说你的情况”）、“粘贴计划”（从剪贴板读，`parse`）。
- 下面是表单（粘贴后自动填好，也可以手填）：任务、目标、总时长、每组、休息。
- 允许的应用：先用 `resolve` 把计划里的名字对到已安装应用（已安装应用用 `<queries>` 里已有的启动器查询），对上的打勾；对不上的标红，点一下从已安装列表里选。另有“从列表加”。
- 通讯应用一栏默认 `DEFAULT_MESSAGING` 里装了的，可去掉。
- “开始”：`check` 不过就显示原因。开始前确认无障碍服务连着（`GateService.connected`），没连就提示去开，不让开始。

### 4. 专注页 `ui/FocusScreen.kt`

- 中间大字倒计时（这一段剩多少）、“工作 / 休息”、第几组、任务和目标、已做完几组。
- 休息时写“站起来走走，喝口水。手机只能用白名单”。
- 按钮：“换节奏”（两个数字框 + 一句为什么，调 `retune`）、“延长”（5/10/15 分钟）、“提前结束”。
- 提前结束：进等待页，大字 3 分钟倒计时，写“等满才能结束；离开这一页重新计时”。`onStop` 时 `cancelQuit`。等满出现“结束”按钮。
- 每组工作结束（进入休息或下一组）时，页面和通知都问：“这一组做完了吗？”两个按钮 + 可选一句。不点也不拦。

### 5. 计时前台服务 `focus/FocusService.kt`

- 前台服务（类型 `specialUse`，manifest 里已有这个权限；写清 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明“专注计时”），常驻通知：“专注中 · 工作/休息 · 第 N 组 · 还剩 mm:ss”，每秒或每 10 秒刷新一次都行，通知里有“打开”。
- 每段切换时：震动两下 + 一声提示音（用 `RingtoneManager` 默认通知音）；进入休息、回到工作、全部结束各一句通知。
- 用 `AlarmManager` 或协程排下一次切换，进程被杀后由 `FocusStore` 读回来、服务重启接着走（`START_STICKY`）。
- 结束时（到点或提前结束）归档、停服务、发一条“专注流结束：做完 X / 共 Y 组”。

### 6. 接进无障碍服务（最重要）

在 `GateService.check(pkg)` **最前面**（在伴读、陪练窗口、收费站之前）加：

```kotlin
val focus = app.focus.active(now)
if (focus != null) {
    when (FocusRules.decide(pkg, packageName, focus.allowedPackages, focus.messaging, systemAllowed(), WindowRules.STATIC_SYSTEM)) {
        FocusRules.Decision.ALLOW -> return
        FocusRules.Decision.BLOCK -> { GateTrace.log("专注流：不许打开", pkg); launchGate(pkg, GateActivity.FOCUS_GROUP, now); return }
    }
}
```

- `GateActivity` 加 `FOCUS_GROUP = "__focus__"`：这个组只显示“专注中：「任务」· 还剩 mm:ss”和两个按钮“回到专注页”“回桌面”，**不出题、不放行**。
- 收费站的放行（`activePass`）在专注流里不查。
- 每秒巡检那段已经会对前台应用调 `check`，所以专注流开始时人正停在抖音里也会被拦，不用另写。
- 系统不让弹出时，已有的遮罩兜底照样生效；遮罩上的标题换成“专注中，先回到「任务」”（给 `GateCover.show` 加一个可选标题参数，默认不变）。

### 7. 记账

每场结束写进 `DayLedger`（来源“专注流”：任务、总分钟、组数、做完几组），每晚总结能看到。

### 8. 拦截体检页

`GateDoctorScreen` 加一行“专注流：没开 / 开着（到 HH:mm）”，日志里已有“专注流：不许打开”。

### 9. 设置

设置里加“专注流”一节：默认每组、默认休息、通讯应用列表。不要加别的设置。

### 10. 测试（JVM）

- `FocusJsonTest`：存了读回来一样（含换节奏、标记、提前结束）。
- `FocusStoreTest`：过了结束时间读回来自动归档。
- 专注流开着时，白名单应用 ALLOW、娱乐应用 BLOCK、有收费站放行也 BLOCK（把 `check` 里这段判断抽成纯函数测）。

### 11. 模拟器测试（`app/src/androidTest/.../integration/FocusIntegrationTest.kt`）

照 `GateIntegrationTest` 的写法（`connectGate`、`RuntimeSupport.fixture()`）：

- 开一场专注流（白名单不含测试应用），打开测试应用 → 出现“专注中”页，不出题。
- 给测试应用的组塞一个有效放行，再打开 → 照样拦。
- 把测试应用加进白名单 → 不拦。
- 提前结束：等待页离开再回来，倒计时重新开始。

### 12. 版本和说明

版本 `0.26.0`。README 顶部加 0.26 的说明（五到八行，口语）。写 `yishou/docs/G3-HANDOFF.md`：做了什么、没做成什么、真机要测什么。

## 验收（PR 里逐条打勾，真机项写“未测”）

- [ ] CI 绿（单元测试、打包、模拟器测试；`VoiceIntegrationTest` 主线上本来就挂，单独写明）
- [ ] 粘贴 AI 生成的计划能直接开始
- [ ] 一组一组震动提醒；中途换节奏后从现在重新排
- [ ] 专注中打开 B 站、抖音被拦，休息时也被拦；ChatGPT、微信能用
- [ ] 提前结束要等 3 分钟，离开重计
- [ ] App 被划掉后，专注流和拦截照样继续
