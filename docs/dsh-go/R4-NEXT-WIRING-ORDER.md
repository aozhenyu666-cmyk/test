# DSH Go · 下一轮指令：只接线，不加功能

> 给执行方（DeepSeek）。本轮唯一目标：**让已经写好的三套修复真正在手机上生效**。
> 起点分支：`fix/r4-transactional-state-2026-11-01` @ `7d2ef4d`。所有行号以这个提交为准。
> 如果分支已不在 `7d2ef4d`，先停下，回报 `git log --oneline 7d2ef4d..HEAD`，不要继续。

---

## 直接粘贴给 DeepSeek 的话

```
你在 dsh-go 仓库工作。读 docs/dsh-go/R4-NEXT-WIRING-ORDER.md（这份文件），只做其中的 W0–W4，按顺序做。
规则：
1. 只在分支 fix/r4-wiring-2026-11-02 上提交（从 7d2ef4d 切出）。不 force push，不碰其他分支。
2. 只改 W 任务里点名的文件。不写新的报告文档，只在最后更新一份 WIRING-RESULT.md（不超过 60 行）。
3. 每个 W 任务一个提交。提交前必须跑 tooling/run-kernel-tests.sh，把最后 5 行贴进提交说明。
4. 任何一步的验收命令不通过：停下，回报失败输出原文，不要绕开、不要改测试预期、不要跳到下一步。
5. 不构建 APK，不改 proactive/、plugins/、runtime/、Dart 代码（W3 里点名的一处除外）。
6. 回报只说三件事：改了哪些文件、验收命令输出原文、还有什么没做。不写"PASS（代码级）"这种判定。
```

---

## W0 · 准备（不改代码）

```bash
git fetch origin fix/r4-transactional-state-2026-11-01
git switch -c fix/r4-wiring-2026-11-02 7d2ef4d
bash tooling/run-kernel-tests.sh          # 必须是 OK (248 tests)
```

**验收**：输出包含 `OK (248 tests)`。如果不是 248 或者失败，**停下，回报**，W1 以后都不做。

---

## W1 · 生产初始化走原子快照 + 迁移（修自审 B-2、B-3）

**问题**：`InterceptRuntime.kt:76-91` 仍然 new 两个 `AtomicJsonFile`（`ledger.json`、`guard.json`），既没传 `repository`，也没调 `InterceptMigration.run()`。线上仍是两次写。

**做法**：

1. 新建纯 Kotlin 文件 `loop/core/InterceptKernel.kt`，**不得 import 任何 android.\***。内容是一个工厂：

```kotlin
object InterceptKernel {
    class Opened(
        val guard: InterceptGuard,
        val ledger: InterceptLedgerBook,
        val repo: InterceptRepository?,           // 迁移失败时为 null（见下）
        val migration: InterceptMigration.Result,
    )

    fun open(dir: java.io.File, clock: () -> Long, ids: () -> String): Opened
}
```

2. `open()` 的步骤**固定为下面的顺序**，照抄 `InterceptRepositoryCrashTest.kt:68-81` 里 `Proc.boot()` 的装配方式：
   1. `snapshot = AtomicJsonFile(File(dir, InterceptRepository.FILE_NAME))`
   2. `legacyGuard = File(dir,"guard.json")` 存在就是 `AtomicJsonFile(...)`，否则 null；`legacyLedger` 同理用 `ledger.json`
   3. `m = InterceptMigration.run(snapshot, legacyGuard, legacyLedger)`
   4. 如果 `m` 是 `Fresh`、`Migrated` 或 `AlreadyMigrated`：
      ```kotlin
      repo   = InterceptRepository(snapshot); repo.load()
      ledger = InterceptLedgerBook(snapshot, sharedWithRepository = true)
      ledger.repositoryView = { repo.committed.toMap() }
      ledger.recover()
      guard  = InterceptGuard(snapshot, ledger, clock, repository = repo, ids = ids)
      guard.recover(clock())
      ```
   5. 如果 `m` 是 `RecoveryRequired`：**退回旧的两文件模式**（也就是现在 `InterceptRuntime.kt:80-86` 的写法），`repo = null`，并把 `m.reason + ": " + m.detail` 作为 `recoveryIssue` 暴露出来。不删、不改旧文件。宁可不原子，也不丢用户的白名单。

3. `InterceptRuntime.ensure()`（76-91 行）改成调用 `InterceptKernel.open(dir, System::currentTimeMillis) { UUID.randomUUID().toString() }`。其余逻辑不动。
4. `InterceptPlugin.status()`（80-81 行附近）增加三个键：`"migration"`（结果类名）、`"snapshot"`（`repo != null`）、`"ledgerStorageError"`（账本的 `storageError`）。

**新增测试** `loop/core/InterceptKernelTest.kt`，用 `kotlin.io.path.createTempDirectory` 建真实临时目录，覆盖 5 个用例：

| # | 场景 | 断言 |
|---|---|---|
| a | 空目录 `open()` → Block → `onPageShown` → `onExited` | 目录里**只有** `intercept.snapshot.json`（以及它的 `.bak`/`.tmp`），**没有** `guard.json` / `ledger.json`；`migration is Fresh` |
| b | 先用旧写法（两个 `AtomicJsonFile`）造出带白名单 `tv.danmaku.bili` 的旧数据 → `open()` | `migration is Migrated`；`guard.config.whitelist` 含 `tv.danmaku.bili`；旧文件**字节不变** |
| c | 对 b 的目录再 `open()` 一次 | `AlreadyMigrated`；白名单还在；`triggerCount` 不变 |
| d | 用 `open()` 打开 → 触发 → **丢弃对象**，再用新的 `open()` 打开同一目录 | 回执是 NOT_SHOWN；`blockedCount == 0`；只有 1 条回执 |
| e | 造一对互相矛盾的旧文件（`ledger` 有 `end:X` 而 `guard` 仍 open X，或照抄 `InterceptMigrationTest` 里已有的矛盾用例） | `migration is RecoveryRequired`；`repo == null`；`recoveryIssue != null`；旧文件字节不变 |

**验收**：

```bash
bash tooling/run-kernel-tests.sh                                   # 248 + 5 = 253，全部 OK
grep -n "AtomicJsonFile(File(dir, \"guard.json\"))" app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/loop/intercept/InterceptRuntime.kt   # 必须无输出
grep -rn "InterceptKernel.open" app/android/app/src/main/kotlin    # 必须恰好 1 处，在 InterceptRuntime.ensure
```

---

## W2 · 失败降级的三个接缝接上真实实现（修自审 B-1，当前行为有害）

**问题**：
- `InterceptRuntime.kt:289` 的 `currentForeground = { null }` 从未被替换。`EnforcementOutcome.stillInFront(null, …)` 返回 true，结果是多按两次 HOME，然后静默失败。
- `notifyEnforcementUnavailable`（308 行）是空实现。
- `enforcementUnavailable`（304 行）没有任何地方读取。

**做法**：

1. `LoopRuntime.kt` 新增一个公开函数，**当场读一次**前台。不能用 `lastForeground`，它有 350 ms 去抖，HOME 之后读到的还是旧值：

```kotlin
/** 立刻读一次前台包名；没有无障碍服务或读失败时返回 null。主线程调用。 */
fun readForegroundNow(): String? {
    val s = service?.get() ?: return null
    return runCatching { foreground(s) }.getOrNull()
}
```

2. `InterceptRuntime.kt:289` 改成 `var currentForeground: () -> String? = { LoopRuntime.readForegroundNow() }`。
3. `EnforcementOutcome.kt:50`：`stillInFront(null, …)` **保持返回 true**（读不到就不能说已经离开，这是对的）。但在 `reportEnforcementUnavailable` 的原因里区分 `foreground_unreadable` 和 `still_in_front`，让设置页能看出是"读不到"还是"没走"。
4. `notifyEnforcementUnavailable`（308 行）改成发一条真通知：
   - 新建通知渠道 `intercept_alerts`，重要度 DEFAULT；
   - 标题"这次没有拦住"，正文用 `why`；
   - 点击打开 App 的拦截设置页，复用 `EntryActivity.openApp(context)`；
   - 同一个原因 10 分钟内只发一次。
   - 文案放进 `res/values/intercept_strings.xml` 和 `res/values-zh/intercept_strings.xml`。
5. `InterceptPlugin.status()` 增加 `"enforcementUnavailable" to InterceptRuntime.enforcementUnavailable`。
6. Dart：`app/lib/intercept/intercept_channel.dart` 的 `InterceptStatus.from` 读出这个字段；`app/lib/ui/phone/intercept_page.dart` 在它非空时顶部显示一行红字。**只加这一行显示，Dart 别的地方不动**（这就是规则 5 里的例外）。

**新增测试**：
- `EnforcementOutcomeTest` 补 2 个用例：`null` 对应 `foreground_unreadable`，同包对应 `still_in_front`。
- `InterceptSeamsWiredTest`：用反射读 `InterceptRuntime` 里 `currentForeground` 和 `notifyEnforcementUnavailable` 两个 lambda 的类名，断言它们**不是**文件里的默认空实现。JVM 上做不到时，改成源码检查测试：读取 `InterceptRuntime.kt` 文本，断言不含 `currentForeground: () -> String? = { null }` 和 `notifyEnforcementUnavailable: (String) -> Unit = { }`。

**验收**：

```bash
bash tooling/run-kernel-tests.sh          # 全部 OK
grep -n "= { null }\|-> Unit = { }" app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/loop/intercept/InterceptRuntime.kt   # 必须无输出
cd app && flutter analyze lib/intercept lib/ui/phone/intercept_page.dart    # No issues found
```

---

## W3 · 生产路径必须被测试覆盖（防止同类问题第四次出现）

**问题根源**：测试和生产走两条不同的构造路径。

**做法**：
1. `InterceptGuard` 构造参数 `repository` 的默认值 `= null`（`Intercept.kt:397`）**保留**，因为旧测试依赖它。
2. 但在 `tooling/run-kernel-tests.sh` 末尾加一道源码检查：生产目录 `app/android/app/src/main/kotlin` 下所有 `InterceptGuard(` 调用都必须带 `repository =`（`InterceptKernel.kt` 里的 `RecoveryRequired` 分支除外，用行尾注释 `// legacy-fallback` 标记）。不满足就 `exit 1`。
3. 同一个脚本里再检查：生产代码里所有 `var xxx: (...) -> ... = {` 形式的接缝，要么有赋值，要么默认实现不是空 lambda（`{ }` 或 `{ null }`）。

**验收**：故意把 W1 的 `repository = repo` 删掉跑一次脚本，必须 FAIL；改回来跑一次，必须 PASS。两次输出都贴进提交说明。

---

## W4 · 真机部署前的自检（不构建 APK，只准备）

在 `docs` 以外新建 `tooling/device-smoke.md`（不超过 80 行），写清楚下次有手机时要跑的命令。下面这些照抄进去：

```bash
adb logcat -c
adb logcat -s DshIntercept:V DshLoop:V &
# 1 打开 B站 → 期望日志出现 "ready:" 和一次 Block；拦截页出现
# 2 拦截页上按电源键熄屏 → 从锁屏通知直接进抖音 → 期望 EXITED(left) 回执，抖音被拦
# 3 拦截页上用最近任务切回 B站 → 期望 B站再次被拦
# 4 开发者选项开"不保留活动" → 打开 B站 → 回桌面 → 期望 onPageLost 回执，无悬挂
# 5 卸载无障碍权限后打开 B站 → 期望收到"这次没有拦住"通知，设置页顶部红字
adb shell run-as io.github.ccch1mneyyy.dsh_app ls files/loop/intercept/
# 期望：intercept.snapshot.json 存在；升级用户的 guard.json / ledger.json 保留但不再更新（mtime 不变）
adb shell run-as io.github.ccch1mneyyy.dsh_app cat files/loop/intercept/intercept.snapshot.json
```

每条都写上"期望看到什么、看到什么算失败"。

**验收**：文件存在，五条场景每条都有期望值和失败判据。

---

## 本轮明确不做（等负责人拍板后另开一轮）

- 主动交流「回应」的真实回复闭环
- 白名单撤销入口
- `hostdeps.mjs` 手机路径、rc.4 非 Electron 宿主验证
- PRoot `.101` 夹具清理、双 ABI 运行时构建
- 策略项 D1–D7（见 `R4-PHASE4-ARCH-REVIEW.md` 第 9 节）

## 交付格式（`WIRING-RESULT.md`，≤ 60 行）

```
分支 / HEAD（git ls-remote 原文）
W0..W4 每项：改动文件列表 | 验收命令 | 输出最后 5 行原文 | 状态 DONE / STOPPED(原因)
还没做的：逐条列
```

只有 DONE 和 STOPPED 两种状态。没有 PASS，没有 PARTIAL。在手机上跑过之前，任何东西都不叫"通过"。
