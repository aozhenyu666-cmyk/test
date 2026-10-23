# 真机测试结果（第一/二段）

日期：2026-10-23 23:37 – 2026-10-24 01:10（Asia/Shanghai）
设备：vivo iQOO 12，型号 `V2307A`（产品 `PD2307`），序列号 `10AE5U23LT004DN`
系统：Android `16`（SDK 36），OriginOS build `PD2307C_A_16.2.20.3.W10`
屏幕：1260×2800，density 560
被测代码：`893091f44d23dc253f4364015fc0b4420a78f90c`（review 修复后；设备结果包含 4a19657 前后的两段）
被测 APK：`C55E6E9C606284DC22807B679F9CC152113849A4EE1256DA01DB221617EB9EE7`（28,354,937 bytes；与设备上已安装 base.apk 哈希一致）

> 本文件记录的是 review 修复后的 APK `C55E6E9C...`（28,354,937 bytes）。B1 记忆页副作用已在设备复测：打开前后均为 20 条事件，未被清空。
执行方式：ADB 直连（`adb devices -l` 为 `device`），未使用 Mobile MCP 或其它插件。

> 只记录实际发生的事。没有执行的用例写 BLOCKED；部分执行写 PARTIAL，不写 PASS。

---

## 1. 环境与安装

| 项 | 结果 |
|---|---|
| ADB | 37.0.1；默认 TEMP 下 `adb.log` 被拒，改用 workspace 的 `_adb-temp` 后正常 |
| DSH | 0.2.1-rc.2（desktop profile） |
| 设备连接 | `adb devices -l` 显示 `10AE5U23LT004DN device product:PD2307 model:V2307A` |
| 已装应用 | `cn.companion.pet` versionCode 3 / versionName 0.3.0 |
| 安装一致性 | `adb pull base.apk` 的 SHA-256 与本地交付 APK 完全一致 |
| 仪器测试 APK | **安装失败**：`INSTALL_FAILED_ABORTED: User rejected permissions`；换 `-t`、`pm install` 仍失败，属于 vivo 安装安全确认，不是 APK 损坏 |
| 启动 | `am start -W -n cn.companion.pet/.MainActivity` → `Status: ok`，WARM，272ms |
| 进程 | PID 19993，测试期间未变化；无 FATAL EXCEPTION / ANR |

---

## 2. 权限与状态

测试开始时"开始"页已经显示：

- 悬浮窗：**已开启**（appops `SYSTEM_ALERT_WINDOW: allow`）
- 无障碍：**已开启**（`enabled_accessibility_services` 中包含 `cn.companion.pet/cn.companion.pet.a11y.PerceptionService`）
- 麦克风和通知：**已开启**（`RECORD_AUDIO`、`POST_NOTIFICATIONS` granted）
- 不限制电池：**已开启**（deviceidle 白名单已加入）
- 状态：**正在陪伴**

说明：首次用 shell 设置无障碍时，我把它写成了只有本应用，短暂移除了原有的 `SelectToSpeakService`；发现后立即恢复为两个服务并存，并从 `settings get secure` 回读确认。最终列表：

```text
com.pl.getaway.getaway/com.google.android.accessibility.selecttospeak.SelectToSpeakService:cn.companion.pet/cn.companion.pet.a11y.PerceptionService
```

---

## 3. 用例结果

| ID | 场景 | 结果 | 实际证据 |
|---|---|---|---|
| DT-01 | 安装与启动 | **PASS** | 已装 APK 哈希与本地一致；`am start` ok；PID 19993；无崩溃；截图 `pet-01-start3.png` |
| DT-02 | 权限引导 | **PASS** | 开始页四项均"已开启"；`settings get secure`、`appops get`、`dumpsys package` 回读；截图 `pet-03-start.png` |
| DT-03 | 悬浮窗与拖动 | **PASS** | `dumpsys window` 确认 `type=2038 APPLICATION_OVERLAY`、`appop=SYSTEM_ALERT_WINDOW`；左右拖动后角色移到另一侧并吸附；截图 `pet-27-drag.png` |
| DT-04 | 短交流 / 文字输入 | **PASS** | 点角色弹出气泡；"打字"→键盘真实弹出（`mInputShown=true`）；输入 hello 并发送，模型真实回复并收尾；气泡两行正常；截图 `pet-10-input.png`、`pet-22-reply2.png`、`pet-26-bubblefix.png` |
| DT-05 | 跨午夜 | **PARTIAL** | 事件日志出现 23:59 的 proactive（day 2026-10-23）和 00:01 的 exchange（day 2026-10-24），归属日分开；但没有用户主动回复的受控两次交流，仍不写成完整通过 |
| DT-06 | 夜间请求中新增交流 | **BLOCKED** | 需要解锁后在设置页触发"现在整理一次"并同时交流 |
| DT-07 | 请求中改档案 | **BLOCKED** | 同上 |
| DT-08 | 强杀恢复 | **BLOCKED** | 未在整理过程中强杀；避免破坏当前状态 |
| DT-09 | 预算上限 | **BLOCKED** | 未改每日上限 |
| DT-10 | 相关召回 | **BLOCKED** | 连续性开关默认关，未做两次跨天交流 |
| DT-11 | 不相关不注入 | **BLOCKED** | 同上 |
| DT-12 | 纠正事实 | **BLOCKED** | 还没有夜间产出的 facts |
| DT-13 | 忘记事实 | **BLOCKED** | 同上 |
| DT-14 | 主动话题 | **PARTIAL** | 记念头后 `topics.json` 已入队一条 `test_note_123`，来源 id 指向 NOTE 事件；**未测分享/忽略/静默** |
| DT-15 | 素材 | **BLOCKED** | App 内没有本地 fixture 入口 |
| DT-16 | UI 适配 | **PARTIAL** | 浅色/深色、字体 1.0/1.3 开始页已截图（`pet-25-status.png`、`pet-32-dark-top.png`、`pet-33-darkfix.png`）；430dp/TalkBack/键盘遮挡未测；1.3 下按钮竖排已修复 |
| DT-17 | TalkBack/返回 | **BLOCKED** | 未开 TalkBack |
| DT-18 | 仪器测试 | **BLOCKED** | 测试 APK 被 vivo 安装确认拒绝；`am instrument` 未执行 |
| DT-19 | 用户主动停止 | **PASS** | 点"停止"后 PetService 记录消失、悬浮窗移除、`user_stopped=true`；再点"启动桌宠"服务恢复、悬浮窗回来；截图 `pet-28-stopped.png`、`pet-29-restarted.png` |
| DT-20 | 暂停/继续 | **PASS** | 点"暂停"后状态"已暂停"、`pet_paused=true`；点"继续"恢复；截图 `pet-30-paused.png` |
| DT-21 | 状态刷新 | **PASS（修复后）** | 升级/看门狗拉起服务后开始页曾显示"已停止"；代码改为每 1.5s 对齐真实服务状态，复测显示"正在陪伴"；截图 `pet-25-status.png` |
| DT-22 | 纯文字气泡宽度 | **PASS（修复后）** | 设备上发现收尾气泡缩成竖排单字；加最小宽度并 `EXACTLY` 重测后，复测正常两行；截图 `pet-26-bubblefix.png` |

---

## 4. 关键证据

### 4.1 事件与笔记落盘

```json
{"id":"d31f46a0-...","occurredAt":1792770205639,"day":"2026-10-23","kind":"note","text":"test_note_123","source":"note"}
{"id":"f5392cca-...","occurredAt":1792770205652,"day":"2026-10-23","kind":"exchange","text":"- 23:43 | 念头 | test_note_123","source":"note"}
```

- 事件归属日 `2026-10-23` 正确；文本一行化。
- `topics.json`：一条 topic，`sourceIds` 指向 NOTE 事件 id，`sharedAt=null`、未过期。
- `state.json`：`interest=0.55`、`lastReason=他记下一个念头`，与 D3 规则一致。
- `notes/2026-10-23.md`：`- 23:43 | 系统导航 | 「」 | test_note_123`（当时前台是系统桌面/设置，原文为空，属于实际记录，不是伪造）。
- `memory-01.tar` 已从设备拉取（12,288 bytes）。

### 4.2 锁屏

- `input keyevent 26` → `mWakefulness=Asleep`、`mDreamingLockscreen=true`；截图 `pet-12a-asleep.png`。
- 唤醒后：`mWakefulness=Awake`，**PID 仍为 19993**；`PetService` 仍 `isForeground=true`；无障碍服务仍绑定；无崩溃日志。
- 设备有安全锁屏，未获得 PIN/指纹，无法完成"解锁回到桌面后角色恢复"的验证；截图停在锁屏 `pet-12b-wake.png`。**不把这一段写成完整锁屏恢复通过。**

### 4.3 视觉发现（需修/需确认）

1. **悬浮角色压住开始页左侧内容**：角色位于 x=0、y≈1044，覆盖"必需"标题附近；虽然靠边，但会挡住可读文字。建议在 safe-area 计算中把顶部/左侧文字区算进去，或默认再往边缘收。
2. **气泡会盖住开始页的状态/操作按钮**：气泡从角色旁展开，宽度约 781px，覆盖"重新唤出/暂停/停止"的一部分。功能上可关闭，但视觉上遮挡主要操作。
3. 气泡按钮较多时换行正常；文字输入时气泡会上移，键盘不挡输入框（截图 `pet-10-input.png`）。
4. 当前截图都是浅色、默认字体；深色和字体放大尚未验证。

---

## 4.4 本轮发现并修复的问题（已在设备复测）

1. **纯文字气泡宽度塌缩**：收尾句/信没有按钮撑宽度，打字机开始时文字为空，窗口被量成一条竖线，中文一字一行。修复：`PetOverlay.layoutBubble()` 加 200dp 最小宽度并按 `EXACTLY` 重新测量。复测 `pet-26-bubblefix.png` 正常两行。
2. **服务已运行但开始页显示“已停止”**：静态 `PetService.running` 变化不会触发重组。修复：开始页每 1.5s 对齐服务/暂停状态。复测 `pet-25-status.png` 显示“正在陪伴”。
3. **字体 1.3 时“停止”竖排**：三个按钮在固定 Row 里放不下。修复：改用 `FlowRow` 自动换行。复测 `pet-33-darkfix.png` 两行排列。
4. **阅读/写作道具盖住嘴**：蓝色书本画在脸上。修复：道具改到脸之前绘制并下移。复测 `pet-34-light.png` 嘴可见。

## 4.5 review 修复后的设备复测（2026-10-24 01:10）

- 安装 review 修复 APK `C55E6E9C...`（versionName 0.3.0，28,354,937 bytes）。
- 打开“记忆”页前：`events-2026-10.jsonl` 为 **20 行**；打开并等待 3 秒后仍为 **20 行**，未被清空；页面显示“清空今天的记录”按钮但未弹确认框。截图 `pet-41-memory-b1.png`。**B1 设备级验证通过。**
- 事件日志同时出现跨午夜证据：`23:59` 的 proactive 归属 `2026-10-23`，`00:01` 的 exchange 归属 `2026-10-24`；说明写入时按设备时区归日，没有把次日事件追加到旧日文件。由于没有受控的用户回复两次交流，DT-05 仍为 PARTIAL。
- 测试 APK 仍被 vivo 安装确认拒绝（`INSTALL_FAILED_ABORTED: User rejected permissions`），`MemoryTabSideEffectTest` / `BootstrapSmokeTest` 仍未执行。

## 5. 证据文件

目录：`_pet-reports/deepseek-v2/device-test/`

| 文件 | 内容 |
|---|---|
| `device-info.txt` | 型号/系统/build/分辨率/包版本 |
| `package.txt` | dumpsys package 全量 |
| `accessibility.txt` | dumpsys accessibility |
| `services.txt` | PetService / PerceptionService 状态 |
| `logcat-session.txt` | 本轮完整 logcat |
| `memory-snapshot.txt` | events/topics/state/note 文本 |
| `memory-01.tar` | memory 目录打包 |
| `installed-base.apk` | 设备上拉回的 APK（用于哈希对比） |
| `pet-01..14*.png` | 启动、开始页、气泡、键盘、笔记、锁屏、唤醒截图 |
| `pet-0*.xml` | 对应的 uiautomator 层级 |

---

## 6. 下一步（需要解锁后继续）

1. **解锁手机并保持亮屏**（当前在安全锁屏；不需要把 PIN 告诉我）。
2. 继续：拖动吸附、两轮模型交流、夜间整理/强杀恢复、预算、连续性开关、暗色/字体 1.3、TalkBack。
3. DT-05 目前只有事件归属日证据（23:59 day23 / 00:01 day24）；完整两次交流仍需另选跨午夜窗口，不能补假结论。
4. 若要跑 DT-18：在手机上允许一次"USB 安装"确认，或手动安装测试 APK；否则保持 BLOCKED。

---

## 7. 结论

本轮真机证据能支持的结论：

- **iQOO 12 / Android 16 / OriginOS 上能安装、启动、显示悬浮角色、拖动吸附、打开气泡、弹出键盘、保存念头、完成一次真实模型交流、暂停、用户主动停止与重新启动。**
- **锁屏期间进程与前台服务未被杀；但没有完成解锁后的完整恢复验证。**
- **测试 APK 被系统安装确认拦住；仪器测试未执行。**
- **UI 存在角色/气泡遮挡内容的问题，需要修。**
- 其余用例仍未验证，不能写成通过。