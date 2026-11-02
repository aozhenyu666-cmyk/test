# DSH Go · 第三张指令单：收尾小修 + 首次真机（G0–G5）

> 起点：`fix/r4-snapshot-fix-2026-11-02` @ `bf47bbf`。

## F0–F7 复核结论（审查方）

| 项 | 复核 |
|---|---|
| F1 设置页暂停不再毒化提交 | ✅ `InterceptRepository.kt:194` 排除 `SETTINGS_PAUSE_PREFIX`；迁移同步处理 |
| F2 提交失败回滚 | ✅ `transact()`（`Intercept.kt:850-869`）覆盖 setConfig / onReleased / onExited / onPaused / onUnavailable / tapDrill；⚠️ 见 G1 |
| F3 老用户迁移 | ✅ 有红有绿 |
| F4 退回旧格式要提示 | ✅ status + Dart 黄字 |
| F5 通知可点、检查器去掉注释 | ✅ `InterceptRuntime.kt:100` 赋值 `launchIntent` |
| F6 本地化 | ✅ 相对 `7d2ef4d` 只差 26 行 |
| F7 生产路径回归 | ✅ 18 个用例全部经由 `InterceptKernel.open`，没有用 `MemorySnapshotStore` |
| 提交顺序 | ✅ F1–F3 都是先红后绿 |

**遗留问题**

1. **G1**：`transact()` 回滚时没有还原 `chargedLastTriggerAt / chargedLastTriggerPackage / chargedCountToday`（`Intercept.kt:408-410`）。`onUnavailable`（734-748）和 `endOpen`（825-829）在 block 里把它们清零了。如果一次 NOT_SHOWN 提交失败、之后重试成功，退还的就是 0：`countToday` 归零，当天的每日上限被整个重置。只有写盘失败时才会触发，但一旦触发就会绕过每日上限。
2. **G2**：`tooling/device-smoke.md` 第 5 条无法验证 W2。关掉无障碍之后，App 根本看不到 B站被打开，既不会拦截也不会失败，所以那条通知永远不会出现。这条测试无论代码对错都会判失败，等于没有测任何东西。

---

## 直接粘贴给 DeepSeek 的话

```
你在 dsh-go 仓库工作，按 G0–G5 顺序做。规则同前：
- 新分支 fix/r4-device-prep-2026-11-02 从 bf47bbf 切出；不 force push，不碰其他分支。
- 每个 G 一个提交；能写测试的先红后绿，提交说明贴 run-kernel-tests.sh 最后 8 行。
- 只改点名文件；只更新 WIRING-RESULT.md（≤60 行）。验收不过就停下回报原文。
- G4、G5 必须等负责人在对话里明确说"批准构建"之后才做；没有批准就在 G3 停下。
- 状态只写 DONE / STOPPED(原因)。真机上的每一条结果必须附 logcat 原文，不许总结代替。
```

### G0 · 准备
```bash
git switch -c fix/r4-device-prep-2026-11-02 bf47bbf
bash tooling/run-kernel-tests.sh      # 必须 OK (278 tests) + wiring OK
```

### G1 · 回滚时一并还原"本次触发扣了多少"
- `Intercept.kt:850-869` 的 `transact()`：在快照里加上 `chargedLastTriggerAt`、`chargedLastTriggerPackage`、`chargedCountToday`，回滚时一起还原。
- **红测试**（`InterceptProductionPathTest`）：
  1. 设 `dailyMax = 3`；
  2. 拦截两次，两次都正常结束；
  3. 第三次触发；
  4. 注入一次 `onUnavailable` 提交失败；
  5. 再调一次 `onUnavailable`，这次成功。

  断言：`countToday == 2`（退还的是第三次扣掉的那一次，**不是 0**），`lastTriggerAt` 等于第二次拦截的值。

### G2 · 让真机能验证"拦截失败要提醒用户"
- `InterceptRuntime.show()`：**只在 `BuildConfig.DEBUG` 下**，如果 `files/loop/intercept/debug-fail-start` 这个文件存在，就让 `InterceptPageActivity.start(...)` 直接当作失败，走现有的 `start_failed` 路径。release 构建里这段必须完全不生效。
- 在 `run-kernel-tests.sh` 的 wiring 检查里加一条：`debug-fail-start` 只能出现在 `BuildConfig.DEBUG` 判断之内。
- 改 `tooling/device-smoke.md`：
  - 第 5 条换成：`adb shell run-as io.github.ccch1mneyyy.dsh_app touch files/loop/intercept/debug-fail-start` → 打开 B站 → 期望出现通知"这次没有拦住"，点通知能打开 App，设置页有红字；回执里没有 BLOCKED；做完删掉这个文件。
  - 新增第 9 条：关掉无障碍 → 打开设置页 → 记录界面上**有没有**提示"无障碍未连接、拦截不工作"。只记录实际看到的，不判 PASS/FAIL（这是给负责人的观察项）。

### G3 · 合成第四阶段集成分支
```bash
git switch -c integration/r4-p4-2026-11-02 fix/r4-device-prep-2026-11-02
git push -u origin integration/r4-p4-2026-11-02
git ls-remote origin refs/heads/integration/r4-p4-2026-11-02   # 原文贴进 WIRING-RESULT.md
```
**没有"批准构建"就在这里停下。**

### G4 · 运行时双 ABI 构建（需要批准）
```bash
runtime/build-runtime.sh                                  # 默认 PROOT .101，两个 ABI
python -m unittest runtime.tests.test_verify_outputs      # 必须全过
```
记录两个 ABI 的 `libproot.so` 中 `strings | grep 5.1.107` 的输出。

### G5 · debug APK + 真机自检（需要批准，并且有一台 arm64 手机）
```bash
tooling/build-apk.sh --debug -f arm64
python tooling/verify_apk_runtime.py <apk>
adb install -r <apk>
```
按 `tooling/device-smoke.md` 1–9 条逐条做。每一条保存：
- `adb logcat -s DshIntercept:V DshLoop:V` 的原文，放到 `evidence/device/2026-11-02/<编号>.log`；
- 有界面的情况再截一张图：`adb exec-out screencap -p > evidence/device/2026-11-02/<编号>.png`。

结果表只写三种：PASS（附日志里的关键行）/ FAIL（附关键行）/ NOT_RUN（附原因）。
没有手机：G5 写 STOPPED(no device)。

## 交付
WIRING-RESULT.md：分支 / HEAD 原文；G0–G5 每项的改动文件、测试输出最后 3 行、状态；G5 的真机结果表。

## 仍然不做
主动交流真实回复闭环、白名单撤销入口、hostdeps.mjs / rc.4、PRoot 夹具、D1–D7（D1 的 BLOCKED+回桌面已经在代码里，需要负责人补一句确认）。
