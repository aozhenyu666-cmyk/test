# 对 GPT 独立审查（4a19657）的修复回应

审查基线：`4a19657fefbe2f8d35d10d2a22a6343654a47f30`
审查结论：blocker 1 / high 4 / medium 4；要求“需要先修”
本回应：逐项修复并转成项目内回归；修复后的代码提交见交付回复与 `git log -1`。

---

## 1. 修复结果

| ID | 问题 | 修复 | 回归证据 |
|---|---|---|---|
| **B1** | 打开记忆页直接在组合体里清空当天事件 | 删除组合期副作用；改为“清空今天的记录”按钮 + 二次确认对话框；确认后才执行 `clearDay` | `MemoryTabSideEffectTest`（仪器测试，已编译；设备断开未执行）；代码审阅 |
| **H1** | 夜间提交与 UI 编辑不共用锁；版本检查后可被用户编辑覆盖 | `MemoryStore` 暴露共享 `lock`，`PetApp.memoryLock` 指向它；`NightlyStore` 使用同一把锁；提交前冻结 profile/diary/letter 的 beforeHash，再迭代输出；用户中途改动会命中冲突而不是被覆盖 | `ReviewRegressionTest.userConfirmationBetweenVersionCheckAndJournalSnapshotIsNotOverwritten` 通过 |
| **H2** | 用户更正的事实被旧 profile 同步反覆盖 | 新增 `FactOrigin`；用户确认/更正标记 `USER`，夜间同步标记 `PROFILE_SYNC`；同步遇到同 key 的 USER 事实直接返回，不创建旧版本 | `userCorrectionSurvivesNextProfileSync` 通过 |
| **H3** | 忘记只软删事实，最近上下文/夜间/topic/journal 恢复仍使用旧内容 | 召回、最近交流、夜间输入、话题队列、旧 prompt 路径都按 tombstone **包含匹配**过滤；journal 增加 `deletedVersion`，恢复前不一致则废弃；话题队列读 `deleted.txt` 过滤 | 4 个 H3 回归（recent/nightly/topic/recovery）通过 |
| **H4** | JSONL 坏尾行导致下一条成功 append 也丢失 | `appendEvent` 在同锁内检测无换行尾行，先把坏片段写入 `broken-tails.log`，截断到最后一个完整换行，再追加新事件 | `appendAfterTruncatedTailPreservesNextCompleteEvent` 通过 |
| **M1** | 新事件以未衰减状态为基数，抹掉时间衰减 | `PetStateStore.apply` 先 `decay(now)` 再施加事件增量 | `eventAppliesToDecayedStateAfterRestart` 通过 |
| **M2** | 普通搭话不断重置话题冷却，队列长期饿死 | 话题冷改为“从话题入队起至少 30 分钟”，不再被普通开口重置 | `schedulerAndTopicGateDoNotStarveQueuedTopic` 通过 |
| **M3** | 预算落盘失败仍返回预留成功 | `BudgetPersistence.save` 返回 Boolean；`BudgetPrefs` 返回 `commit()` 结果；`AtomicBudget.reserve` 只在耐久写入成功后返回 true | 新增 `failedPersistDoesNotReserve` 通过 |
| **M4** | 冒烟测试子串选择器可能匹配多个节点 | 改为精确文本“悬浮窗”定位；仪器 APK 已重新编译 | 仪器测试已编译；设备断开未执行 |

---

## 2. 测试结果

修复后（本机 ASCII 构建副本，JDK 22 / SDK 35 / Gradle 8.10.2 / AGP 8.7.3）：

```text
:app:testDebugUnitTest          PASS   174 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 4 warnings
:app:assembleDebug              PASS   app-debug.apk 28,354,937 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译
```

新增/接入的独立回归：

- `cn.companion.pet.audit.ReviewRegressionTest`：11 个，全部通过（含 2 个 control）；
- `AtomicBudgetTest.failedPersistDoesNotReserve`；
- `MemoryTabSideEffectTest`：仪器测试，编译通过，设备未执行；
- 原 162 个测试保持通过，没有删除或弱化断言。

APK SHA-256：`C55E6E9C606284DC22807B679F9CC152113849A4EE1256DA01DB221617EB9EE7`

---

## 3. 仍未覆盖 / 需要下一轮设备验证

- **B1**：设备已断开，`MemoryTabSideEffectTest` 未执行；下一轮先装新 APK，打开记忆页前后对比 `events` 行数。
- **H1**：JVM 确定性时序回归通过；真实 UI 并发（用户编辑 vs 夜间 commit）仍需设备/多线程栅栏验证。
- **H2/H3**：逻辑回归通过；真实夜间产出的 facts 与重启/重放链路仍建议在设备上跑一次。
- **M4**：仪器测试编译通过，未在设备执行。
- 设备测试的其余未完成项（跨午夜、强杀、预算、召回、TalkBack、430dp）保持不变，不写成通过。

---

## 4. 结论

- 审查的 blocker/high/medium 均有代码修复和对应回归；
- 174 个 JVM 测试通过，lint 0 errors；没有删改原断言；
- 可以进入下一轮真机验证，但**不把本轮构建当作真机通过**；设备需重新连接/授权后再执行仪器测试与 B1 页面副作用检查。
