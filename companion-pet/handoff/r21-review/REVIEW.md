# R2.1 验收审查（对 deepseek/r21-proactive @ 66ce3ca）

日期：2026-10-26。版本 36 / 0.7.6-r21-review-preview。

## 已修的 bug

| # | 问题 | 后果 | 修法 |
|---|---|---|---|
| 1 | 训练**完成**时不释放 `SessionArbiter` / `OutputArbiter` 锁，只有“结束本次”才放 | 完成后点“下一题”被自己挡住（“当前已有训练在进行”）；严格陪想、外部 AI 也一直开不了 | 完成时 `releaseTrainingLocks()` |
| 2 | 温和搭话 `present()` 用 `acquire(AMBIENT, "ambient-"+now)` 占输出权，从不释放 | 第一句闲聊之后，所有温和主动搭话都被“同级不可抢占”挡掉，桌宠基本哑了 | 改为 `allows(AMBIENT)` 只检查不占用 |
| 3 | `SessionArbiter.release(kind, force=true)` 完全不核对种类 | `endThink()`/`strictAbort()`/`finishSession()` 会把**训练**或外部 AI 的锁一起放掉，互斥失效 | `force` 只跳过实例号，种类仍须一致；仅 `NONE+force` 清空 |
| 4 | 严格陪想**自然到期**时不放锁（`tickStrict` 先把 `flow.strict` 置空，`endThink` 里的释放走不到） | 严格回合结束后训练永远开不了 | 到期时释放；`endThink` 无条件释放 STRICT_THINK |
| 5 | 重启恢复训练/严格回合时不重新占锁 | 恢复后的训练能与严格陪想/外部 AI 并行 | 恢复时 `acquire` |
| 6 | S6 材料题第二步：本地形式合规的 PASS 带 `uncertainty`，直接交给 `acceptAdvice` 必被判技术故障，且从不调用模型 | **材料题第二步永远过不去**，S6 闭环无法完成、机会永远结算不了“有推进” | 形式合规 → 交给模型核对语义；无模型时如实说明“需联网核对”，不放行 |
| 7 | 材料匹配找不到 `sourceEventId` 时回退到“最新一条材料” | 与注释矛盾，连续导入会串题 | 删除回退，找不到就不出题（记抑制原因） |
| 8 | “我在想”批准后用 `trainingTimer.restore()`，实际是重置成默认 60 秒（且丢了读题加时），不是承诺的 90 秒 | 提示说“再给你 90 秒”，实际 60 秒就催 | 新增 `TrainingTimer.extend(now, millis)` |
| 9 | “我在想”额度只在内存里 | 进程重启后额度重置，可无限刷新（违反 S7） | 额度存入 `TrainingSession.extensionTimes/extensionMillis` 随断点落盘 |
| 10 | 核验中/恢复中也能点“我在想” | 无意义地消耗额度、打乱计时 | 仅 WAITING_INPUT / SCAFFOLDED 允许 |
| 11 | 材料题之后开内置题，`materialPlan` 残留 | 潜在串判分计划 | 开新内置题时清空 |

新增 `ReviewFixRegressionTest`（8 条）。

## 验证

- 本环境无法访问 Google Maven（dl.google.com 被网络策略拒绝），**不能本地构建 APK**。
- 把不依赖 Android 的源码和测试单独用 Kotlin/JVM 编译：**580 tests / 0 failures**（原 572 + 新 8）。
- `PetBrain.kt` 依赖 Android，本地未编译，靠推送后 GitHub Actions 的完整构建验证。
