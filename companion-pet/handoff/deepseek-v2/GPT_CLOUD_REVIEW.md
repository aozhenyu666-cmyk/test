# GPT / Codex 云端独立审查交接

日期：2026-10-23
仓库：https://github.com/aozhenyu666-cmyk/test
分支：`pet-v15/deepseek-continuity-v2`
代码提交：`4a19657fefbe2f8d35d10d2a22a6343654a47f30`
范围：只审 `companion-pet/`，不要动其它产品目录
设备：vivo iQOO 12（V2307A），Android 16（SDK 36），OriginOS `PD2307C_A_16.2.20.3.W10`

> 这份交接是给云端 GPT/Codex 的：请独立审代码、跑 JVM 测试、找缺口，**不要声称完成真机验证**。真机证据在本地，摘要见 `DEVICE_TEST_RESULTS.md`。

---

## 1. 你要做什么

1. 拉取分支，按 JDK 17 / Android SDK 35 构建；
2. 跑 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`；
3. 审 D1/D2/D3/D4 的代码与测试，重点看下面的问题；
4. 给出按严重级别排序的发现，带 `文件:行号`、证据、建议修法；
5. 给出“是否适合进入下一轮真机测试”的结论，不要代替真机结论。

---

## 2. 已有什么证据（可以复核，不要只信文字）

- JVM：**162 tests / 0 failures**；lint **0 errors / 5 warnings**；debug APK 构建成功（本机，JDK 22 / Gradle 8.10.2 / AGP 8.7.3）。
- 设备第一轮：
  - 安装/启动、权限（悬浮窗/无障碍/麦克风/通知/电池）PASS；
  - 悬浮角色可见、左右拖动吸附 PASS；
  - 气泡打开、真实键盘弹出、输入并发送、真实模型回复 PASS；
  - 念头落盘，`events/`、`topics.json`、`state.json` 内容与规则一致；
  - 暂停/继续、用户主动停止/重新启动 PASS；
  - 锁屏期间进程与前台服务存活 PASS；解锁后的完整恢复未完整验证；
  - 深色 + 字体 1.3 开始页通过；430dp/TalkBack 未测；
  - 仪器测试 APK 被 vivo 安装确认拒绝，`connectedDebugAndroidTest` 未执行。
- 设备上发现并已修复、复测 4 个问题：
  1. 纯文字气泡宽度塌缩（中文一字一行）→ 最小宽度 + EXACTLY 重测；
  2. 服务已运行但开始页显示“已停止” → 1.5s 状态对齐；
  3. 字体 1.3 时“停止”竖排 → FlowRow；
  4. 阅读/写作道具盖住嘴 → 绘制顺序与位置调整。
- 仍未验证：跨午夜、夜间请求中新增/改档、强杀恢复、预算上限、相关召回、纠正/遗忘、主动话题分享/忽略、素材、TalkBack、430dp。

本地证据目录（不在 git 里）：`_pet-reports/deepseek-v2/device-test/`，包含 14+ 张截图、logcat、dumpsys、memory tar、设备信息。
报告：`companion-pet/handoff/deepseek-v2/TEST_REPORT.md`、`DEVICE_TEST_RESULTS.md`、`DELIVERY.md`。

---

## 3. 重点审什么

### D1 记录可靠性
- `core/companion/EventLog.kt`：追加写、坏尾行、按日查询、归档幂等、`clearDay` 的语义；
- `core/memory/NightlyStore.kt`：批次 id、profile 版本冲突、journal 写入顺序、每个崩溃窗口、archive marker 去重、tombstone 过滤、facts 同步；
- `core/probe/AtomicBudget.kt` + `data/BudgetPrefs.kt`：进程内并发、SharedPreferences `commit`、跨日/重启、是否会有“预留了但没请求”的计数偏差；
- `PetBrain`/`NightlyWorker` 是否还有绕过预算或绕过存储协议的入口。

### D2 连续交流
- `FactStore`：纠正/确认/忘记/版本/`supersededBy`；猜测是否可能覆盖“他说过”；删除后重放/夜间同步/召回是否都排除；
- `MemoryRecall`：中文单字+双字召回的误召回、去重、时间衰减、字符预算；是否会把不相关内容注入；
- `CompanionContextAssembler`：persona 与数据分层、素材标记、来源标签、5000 码点裁剪顺序、emoji 代理对；
- `MemoryTab`：确认/更正/忘记是否只调用公开 API、是否会绕过 tombstone 或产生半更新。

### D3 状态与主动交流
- `CompanionState`：半衰期、边界、跨重启、事件增量是否会漂移；
- `TopicQueue`/`TopicGate`：来源必需、过期、一次性、忽略、冷却、安静时段、重复；
- 素材 fixture 是否会诱导“刚刷到/看完了”的虚构；没有素材时提示词是否明确。

### D4 设备修复与 UI
- `PetOverlay.layoutBubble()` 的最小宽度修复是否会在 360dp/430dp/大字体下造成溢出；
- `StartTab` 的 1.5s 状态轮询是否泄漏/耗电/在主线程读写；
- `FlowRow` 的 Compose 版本兼容性；
- `PetView` 道具绘制顺序是否影响其它状态；
- 悬浮角色/气泡仍会遮挡页面文字和按钮（已知未修），请评估是否必须在本轮修。

### 测试质量
- 162 个测试里是否有“只测实现细节、不测行为”的弱断言；
- 夜间/恢复/预算的故障注入是否覆盖真实崩溃窗口；
- 缺少哪些针对 Android 16 / iQOO 的仪器测试（即使云端不能跑，也可以给测试代码建议）。

---

## 4. 明确不要做

- 不要声称“真机通过”“iQOO 12 已验证”；
- 不要安装或修改 DSH 插件、不要动其它产品目录；
- 不要读取/输出 API Key、Cookie、私人聊天；
- 不要把 CI 的“测试 APK 编译成功”当成“仪器测试执行成功”；
- 不要为了让测试变绿而放宽断言或删除失败用例。

---

## 5. 建议的云端命令

```bash
cd companion-pet
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# 可选：只跑新增部分
./gradlew :app:testDebugUnitTest --tests 'cn.companion.pet.core.companion.*' --tests 'cn.companion.pet.core.memory.*'
```

如果依赖下载受限，先报告“无法复现构建”，不要把缓存或旧结果当新结果。

---

## 6. 期望输出格式

```text
结论：可以进入下一轮真机 / 需要先修
阻断项：
  - [B1] 文件:行号 — 问题 — 证据 — 建议
高风险：
  - [H1] ...
中/低：
  - [M1] ...
测试缺口：
  - ...
建议新增测试（可给代码片段）：
  - ...
对设备已修复 4 项的复核：
  - 气泡最小宽度：...
  - 状态轮询：...
  - FlowRow：...
  - 道具绘制：...
未验证项提醒：
  - ...
```

---

## 7. 一句话交接

请以 `4a19657` 为代码基线，独立审 `companion-pet/` 的可靠性、连续性、状态/话题、UI 修复和测试质量；只给代码/测试结论，真机结论等我们下一轮设备测试再定。
