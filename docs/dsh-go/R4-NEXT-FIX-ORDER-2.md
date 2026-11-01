# DSH Go · 第二张修复单：快照路径上的数据问题（F1–F7）

> 给执行方（DeepSeek）。起点：`fix/r4-wiring-2026-11-02` @ `caeba65`。行号以这个提交为准。
> 分支不在 `caeba65` 就停下，回报 `git log --oneline caeba65..HEAD`，不要继续。

## 上一轮（W0–W4）复核结论

| 项 | 复核 |
|---|---|
| W1 生产初始化走 `InterceptKernel.open` | ✅ 属实（`InterceptRuntime.kt:85`，`InterceptKernel.kt:41-91`） |
| W2 三个接缝接上真实实现 | ✅ 属实；但 `EnforcementAlert.launchIntent` 从来没被赋值，通知点了没反应（F5） |
| W3 接线检查 | ✅ 有效，但只按字符串匹配，写在注释里也算"已接线"（F5） |
| W4 真机清单 | ✅ |
| 范围 | ❌ `app_localizations_en.dart` 被整体重排了约 2700 行，与本轮无关（F6） |

**W1 把快照路径接进了生产，同时暴露了快照路径自己的两个数据缺陷（F1、F2）。当时没有任何测试走生产路径去跑设置页的暂停。**

---

## 直接粘贴给 DeepSeek 的话

```
你在 dsh-go 仓库工作。读 docs/dsh-go/R4-NEXT-FIX-ORDER-2.md，只做 F0–F7，按顺序做。
规则同上一轮：
1. 只在 fix/r4-snapshot-fix-2026-11-02 上提交（从 caeba65 切出），不 force push，不碰其他分支。
2. 每个 F 一个提交；先提交会失败的测试（红），再提交修复（绿）。两次 run-kernel-tests.sh 的最后 8 行都贴进提交说明。
3. 只改 F 任务点名的文件。不写新报告，只更新 WIRING-RESULT.md（≤ 60 行）。
4. 验收不通过就停下，回报原始输出。不改测试预期，不绕开。
5. 不构建 APK。状态只写 DONE / STOPPED。
```

---

## F0 · 准备

```bash
git switch -c fix/r4-snapshot-fix-2026-11-02 caeba65
bash tooling/run-kernel-tests.sh        # 必须是 OK (258 tests) 且 wiring OK
```

---

## F1（P0）· 设置页"暂停"会让快照永久提交失败

**执行路径**（新装用户，生产快照模式）：
1. 设置页点"暂停"，即 `InterceptPlugin "pause"` → `guard.onPaused(null, …)`（`Intercept.kt:671-695`）。
2. 内存里写入 `pauseUntilWall`，账本加了一条 `eventId = "pause-<now>"` 的回执，`triggerCount` 不变。
3. `persist()` → `repo.commit()` → `validate()`（`InterceptRepository.kt:185-191`）：`triggerCount(0) < receipts(1)` → **提交被拒**。
4. `onPaused` 仍然返回 true，内存不回滚（见 F2）。
5. 之后**每一次**提交都带着这条多出来的回执，都会被拒，直到 `triggerCount` 追上回执数。回执上限是 100 条，最坏要再经过约 100 次拦截才会恢复。
6. 这期间进程一死，`recover()` 读回最后一份成功的快照：暂停、白名单、所有回执全部丢失。

现有测试只在**无仓库**的旧路径上测过 `onPaused(null)`（`InterceptAuthorizationSemanticsTest.kt:128`、`LedgerBookTest.kt:238`），所以没发现。

**修复**（最小改动，不改暂停的语义）：
- `Ledgers.kt` 增加常量 `InterceptLedger.SETTINGS_PAUSE_PREFIX = "pause-"`，`Intercept.kt:688` 改用这个常量。
- `InterceptRepository.validate()` 的触发数检查只统计**不以这个前缀开头**的回执：
  `trigger < receipts.count { eventId 不以 SETTINGS_PAUSE_PREFIX 开头 }` 时才拒绝。
- `InterceptMigration.kt:120` 的同一条检查做同样处理。

**红测试**，新建 `InterceptProductionPathTest.kt`，一律用 `InterceptKernel.open(临时目录)`，不用 `MemorySnapshotStore`：
- 新装 → 设置页暂停 → 断言 `guard.storageError == null`；重新 `open()` 同一目录 → `pauseUntilWall` 还在。
- 新装 → 设置页暂停 3 次 → 拦截 1 次 → 加白名单 → 重新 `open()` → 白名单还在，`storageError == null`。

---

## F2（P0）· 快照提交失败时内存不回滚、调用方仍得到"成功"

**位置**：`Intercept.kt:832-842`。`repo.commit()` 返回 false 时只设置 `storageError`，各个改状态的方法照样返回 true。页面以为放行、白名单、暂停都成功了，磁盘上却没有，重启即丢。这正是 P0-4 要消灭的那类问题，只是换了个位置。

**修复**：
1. `InterceptGuard` 增加私有函数 `transact(block: () -> Boolean): Boolean`：
   1. 先记下 `config`、`state`、`ledger.state` 和 `ledger.appliedIds()`；
   2. 执行 block，然后 `persist()`；
   3. 如果仓库模式下提交失败，把上面四样恢复原状，返回 false。
   `InterceptLedgerBook` 需要新增 `restore(state, applied)`，只改内存。
2. 以下方法改为经过 `transact`：`onReleased`、`onExited`、`onPaused`（含 eventId 为 null 的情况）、`setConfig`、`tapDrill`、`onUnavailable`、`onPageLost`，以及以后新增的白名单撤销。
3. `onForeground`（触发）和 `onPageShown`（展示证明）**不回滚**：拦截照常进行，只记 `storageError`。理由是保护用户比记账更要紧，内存里已经挡住了，不能因为写盘失败就放行。
4. 页面收到 false 已有处理（留在页面上并提示），不用改。

**红测试**（放在 `InterceptProductionPathTest.kt`）：用一个可注入写失败的存储（`InterceptRepositoryCrashTest` 里已有 `FaultyDisk`，照抄），对上面列出的每个方法各写一个用例：提交失败 → 返回 false，四样状态与调用前完全相同。

---

## F3（P1）· 迁移检查把老用户全部推回旧的两文件模式

**位置**：`InterceptMigration.kt:108-125`。账本回执只保留最近 100 条（`Ledgers.kt:206,335`），计数却一直累加。
- 被拦过 100 次以上的用户：`blockedCount > receipts.size` → `RecoveryRequired`。
- 用过设置页暂停的用户：回执数可能大于触发数 → `RecoveryRequired`。

结果是**越老的用户越会永远停在没有原子性的旧模式**，而且没有任何提示（见 F4）。

**修复**（只修误报，不改"不一致就不迁移"的原则）：
- 当 `receipts.size >= InterceptLedger.RECEIPT_CAP` 时跳过 `maxSingle` 检查，因为回执已被截断，计数必然更大。
- 触发数检查按 F1 排除设置页暂停的回执。
- 白名单检查（127-135 行）不动。

**红测试**（`InterceptMigrationTest` 里补）：
- 造 150 次"展示后放行"的旧数据（`blockedCount = 150`，`userReleasedCount = 150`，回执 100 条）→ 断言 `Migrated`。
- 旧数据里有 2 条设置页暂停回执、0 次触发 → 断言 `Migrated`。

> 负责人待定：数据确实对不上时（比如真的碰上了崩溃窗口），是"带着问题照样迁移、记录 recoveryIssue"，还是现在的"停在旧模式"？本单**不改**这条原则，等负责人决定。

---

## F4（P1）· 退回旧模式的用户看不到任何提示

**位置**：`InterceptPlugin.kt`（status）里只有 `migration` 类名和 `snapshot` 布尔值，Dart 两个都不读；迁移失败的详细原因只写了日志（`InterceptRuntime.kt` 里 `opened.recoveryIssue` 那段）。

**修复**：
- status 增加 `"migrationIssue" to opened.recoveryIssue`（在 `InterceptRuntime` 里保存下来）。
- Dart `InterceptStatus.from` 读出 `snapshot` 和 `migrationIssue`。
- `intercept_page.dart` 在 `snapshot == false` 时显示一行黄字："拦截记录仍使用旧格式，原因：…"，写法照抄 224-230 行 `enforcementUnavailable` 的显示。

**验收**：`flutter analyze lib/intercept lib/ui/phone/intercept_page.dart` 输出 No issues found。

---

## F5（P2）· 通知点了没反应；接线检查会被注释骗过

1. `EnforcementAlert.launchIntent` 从未赋值。在 `InterceptRuntime.ensure()` 里赋值为 `{ ctx -> EntryActivity.openApp(ctx) }`（`shortcuts/EntryActivity.kt:77`）。
2. `run-kernel-tests.sh` 的 W3 检查：
   - grep 之前先去掉以 `//`、`*`、`/*` 开头的行，再做匹配；
   - 增加一条检查：生产代码里必须存在 `EnforcementAlert.launchIntent =` 赋值。

**负向验收**：分别在注释里写一行 `EnforcementAlert.notify`，再删掉真实调用，跑脚本必须 WIRING FAIL；恢复后必须 OK。两次输出都贴进提交说明。

---

## F6（P2）· 撤销本地化生成文件的无关重排

`app/lib/l10n/gen/app_localizations_en.dart` 相对 `7d2ef4d` 改了约 2700 行，几乎全是换行重排，真正的改动只有一个键。这会给后续合并制造大量冲突。

**修复**：
1. `git checkout 7d2ef4d -- app/lib/l10n/gen/`。
2. 用项目原本的生成方式重新生成：`cd app && python3 tool/l10n_merge.py`，然后 `flutter gen-l10n`。依据是提交 `295fd35` 的说明。不要另外跑 `dart format`。完成后 `python3 tool/l10n_merge.py --check` 必须 exit 0。
3. `@interceptEnforcementUnavailable` 的 placeholder 元数据，en 和 zh 两边要一致。生成源是 `parts/`，顶层 arb 如果也是生成出来的，就不要手改。

**验收**：`git diff 7d2ef4d -- app/lib/l10n/gen/ | grep -c '^[+-][^+-]'` 小于 40，并且只涉及 `interceptEnforcementUnavailable`。

---

## F7 · 生产路径回归包

把 `InterceptProductionPathTest.kt` 补成一张"所有用户操作 × 生产存储"的表。每一行都用 `InterceptKernel.open(临时目录)` 执行，然后断言 `storageError == null`，再重新 `open()`，断言状态保持不变：

| 操作 | 重新打开后必须保持 |
|---|---|
| 触发 → 展示 → 训练放行 | 限时放行、1 条 RELEASED 回执 |
| 触发 → 展示 → 加白名单 | 白名单 |
| 触发 → 展示 → 页面暂停 | `pauseUntilWall` |
| 设置页暂停 → 恢复 | `pauseUntilWall == 0` |
| 触发 → 未展示 → NOT_SHOWN | 冷却已退还，`blockedCount == 0` |
| 触发 → 进程死（丢弃对象） | 回执为 NOT_SHOWN |
| 触发 → 展示 → 进程死 | 回执为 BLOCKED |
| 改配置（删掉一个目标） | 新配置 |
| 循环 120 次"触发 → 展示 → 退出" | 每次 `storageError == null`；回执 100 条；`triggerCount == 120` |

**验收**：`bash tooling/run-kernel-tests.sh` 全部 OK，并且 wiring OK。

---

## 本轮仍然不做

- 主动交流的真实回复闭环、白名单撤销入口、`hostdeps.mjs`、rc.4、PRoot 夹具与双 ABI 构建、D1–D7。
- 构建 APK / 上真机：F1–F7 全部 DONE 并经过复核之后，由负责人另行批准。

## 交付格式（更新 WIRING-RESULT.md，≤ 60 行）

```
分支 / HEAD（git ls-remote 原文）
F0..F7 每项：改动文件 | 红测试输出最后 3 行 | 绿测试输出最后 3 行 | DONE / STOPPED(原因)
```
