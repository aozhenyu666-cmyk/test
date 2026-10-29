# 同桌阶段 3 交接文档

> 写给接手的人。**先读完这一节再动手。**
> 生成时间：2026-10-29 23:35（Asia/Shanghai）

---

## 0. 三十秒摘要

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/aozhenyu666-cmyk/test |
| 工作目录 | `companion-pet/`（Apple 包名 `cn.companion.pet`，但产品名是「同桌」） |
| 当前分支 | `feat/proactive-engine-v1` |
| HEAD | `b8ccf1383664cad892a6a83936011867c6d61659` |
| 远端 | **已同步**（`origin/feat/proactive-engine-v1` = `b8ccf13`） |
| 基线分支 | `claude/r21-strict-takeover` @ `af271d7` 之前 |
| 版本 | vc45 / 0.8.5-partner-recovery-preview |
| JVM 测试 | **902 全绿**（本地验证副本） |
| 真机 | iQOO 12（V2307A），Android 16 / SDK 36 |

**一句话状态**：主动说话链路已修通并真机验证；约定机制代码完成、创建侧真机验证；**到点追问闭环未验证**。

---

## 1. 关键教训（血泪，别重蹈）

### 1.1 这台机器装不下「构建 + 模拟器」

- **16G 内存**。Gradle daemon 起来后吃 3G+，模拟器 qemu 吃 2.5G。
- 每次构建后**必须** `gradle --stop`，否则模拟器内应用会 ANR。
- 曾因 C 盘只剩 0.6G 导致模拟器 5 分钟起不来。
- **建议**：用真机验收，别用模拟器。

### 1.2 不要改用户手机的默认输入法

**我犯过这个错**：为了让 adb 输入中文，把 ADBKeyBoard 设成了默认 IME。它是无界面输入法，导致用户**真实键盘弹不出来**。

修复方式（已做）：
```bash
adb shell ime set com.vivo.ai.ime.nex/com.vivo.ai.ime.main.IMEService
adb shell ime disable com.android.adbkeyboard/.AdbIME
adb shell pm uninstall com.android.adbkeyboard
```

**结论：中文输入验证应该让用户手动输入，或改用英文测试。**

### 1.3 测试通过 ≠ 交付完成

我这轮犯的最严重错误：**24 个约定测试写在磁盘上但从未 `git add`**，却宣称"902 测试全绿"。

**接手后请每次确认：**
```bash
git status --short          # 必须为空
git ls-tree -r --name-only HEAD | grep 你的新文件
```

### 1.4 改测试前先证明测试本身有缺陷

已改过一次 `FocusEvidenceIntegrationTest`，原因：它用 `File.renameTo` 统计写盘次数，而 `renameTo` 在目标存在时返回 false，导致 `save()` 误报失败 → 每次 tick 都重写 → 测出 122 次。

**业务不变量没变**（120 次 tick 内写盘 ≤ 2 次），只把 replace 换成 `Files.move(REPLACE_EXISTING)` 模拟真实原子替换。

---

## 2. 本轮修了什么（按提交顺序）

### `34c1d26` 会话与动作修复

| 问题 | 证据 | 修法 |
|---|---|---|
| **死会话堵死 tick** | 连续 45 秒每秒 `tick gate: session=TEXT` | `restoreSession` 加 40 分钟陈旧自愈 |
| `partnerSampleJob` 不复位 | `isActive` 恒 true | `finally` 置空 |
| 无停止按钮 | — | `ChatActivity` 加「停止生成」 |
| 无返回动作 | — | `PartnerAction` + `ActionIntent` |

### `8198bf4` 分身识别

**真机查实**：vivo 应用分身是 `User 999`，微信有 `u0_a410` + `u999_a410` 两个实例；**Gemini 没有分身**。
- `FocusContract.targetUserId` + `identityCertain`
- 拿不到 userId 时如实标「身份不确定」
- **R3 结论是 `BLOCKED_CAPABILITY`，不是 PASS**

### `5720c32` 显示修复（最重要）

`displayConfirmed` 用 `partnerBusy()` 一票否决，而 `partnerBusy()` 在守护会话时返回非 null → **生成了话但永远不显示**。

真机证据：`displayConfirmed shown=true allowed=true`

### `af271d7` 自动守护

- `focusAutoArm`：打开 AI App 自动建立守护
- `targetHostPackages`：Gemini 界面在 `googlequicksearchbox` 里
- STRICT 迁移（磁盘存 SOFT 会覆盖代码默认值）

### `fea3f98` 随机引擎 + 通知

9 个新模块，见第 3 节。

### `7f60947` 动机向量

`Drives`（6 维）+ `DriveEngine`，含 app 内可调的黏度。

### `7a8c283` + `f415b8c` 约定机制

### `b8ccf13` 补提交遗漏的 24 个约定测试

---

## 3. 新增模块清单

### `core/proactive/`（纯逻辑，零 Android 依赖，可 JVM 测）

| 文件 | 职责 | 关键设计 |
|---|---|---|
| `ProactiveMode.kt` | 三档频率 | DAILY 8/h、HIGH 12/h、EXPERIMENT 20/h |
| `RandomTriggerConfig.kt` | 全部参数 | **对数正态均值校正**：`median = 3600000/rate/exp(σ²/2)` |
| `RandomTriggerEngine.kt` | 随机触发 | 对数正态 + 伯努利突发 + 沉默恢复 + 硬约束 |
| `Drives.kt` | 动机向量 | 6 维、8h 半衰期、说完 ×0.45、越久没聊 connect 涨 |
| `DriveEngine.kt` | 动机接入 | `appetiteFor(need)` 返回 0.5~1.6 乘数 |
| `ProactiveContext.kt` | 情境触发 | 7 种，**LIGHT_SMALL_TALK 永远兜底** |
| `AutonomousConversationEngine.kt` | 话题形式 | 6 种轮换 + 锚点去重 |
| `InteractionPolicy.kt` | 退避仲裁 | 有地板 `MIN_FACTOR=0.25`，预约不受闲聊退避影响 |
| `MessageDispatcher.kt` | 投递四态 | GENERATED/PUBLISHED/DISPLAYED/READ |

### `core/appointment/`

| 文件 | 职责 |
|---|---|
| `Appointment.kt` | 数据模型（`dueAtMillis` / `version` / `lateToleranceMillis`） |
| `AppointmentParser.kt` | 三态解析：Ready / NeedTime / NeedTopic |
| `AppointmentBook.kt` | 账本：创建/改期/取消/结算/过期 |

### `notify/`

| 文件 | 职责 |
|---|---|
| `ProactiveNotifier.kt` | 独立通知通道，**每条消息独立 ID** |
| `ProactiveProbeReceiver.kt` | 通知自检（adb broadcast 触发） |
| `TestKeyReceiver.kt` | **仅 debug 构建**的 Key 注入 |

---

## 4. 测试状况（如实）

### JVM 测试：902 全绿

| 套件 | 数量 | 覆盖 |
|---|---|---|
| `RandomTriggerEngineTest` | 10 | 种子分布差异、60 分钟模拟、硬约束、闸门、退避 |
| `DrivesTest` | 10 | 衰减、满足、累积、人格偏置、不爆表 |
| `ProactiveModulesTest` | 14 | 情境、话题、仲裁、投递四态 |
| `AppointmentParserTest` | 11 | 各种时间格式、"半"、缺时间/缺话题不猜 |
| `AppointmentBookTest` | 13 | 版本失效、只结算相关件、过期不补发、重启恢复 |
| 其余既有 | 844 | 全部保持通过 |

**跑测试命令：**
```bash
# 注意：仓库路径含中文，必须复制到 ASCII 路径
# 详见第 5 节
gradle :app:testDebugUnitTest
```

### 测试抓到的 5 个真 bug

1. **`0` 哨兵冲突**：用 0 表示"没说过"，但 0 是合法时间戳 → minGap 失效
2. **对数正态均值未校正**：`E[X]=median×exp(σ²/2)`，σ=0.8 差 37.7% → 20/h 实际 14.5
3. **突发窗口按总量计数**：burstMax=3 仍放过 4 次
4. **「半小时」解析成 30 分钟**：把 `HALF` 当 5 个单位
5. **退避失效**：接入随机引擎后 `silencePenalty()` 变死代码 → 120 分钟不回应说 40 次

---

## 5. 构建环境（照抄即可）

### 必须复制到 ASCII 路径

```
源：C:\Users\敖震宇\Documents\deepseek-harness\default-workspace\_r21-fix
坑：路径含中文「敖震宇」，AGP 8.7.3 直接拒绝
解：复制到 C:\pet-verify4\companion-pet
```

### Gradle

本机无网下载 8.10.2，用缓存的 8.11.1：
```
Gradle  : C:\gradle-dist\gradle-8.11.1\bin\gradle.bat
HOME    : C:\gradle-home（必须 ASCII，否则测试 worker 报 GradleWorkerMain 找不到）
JDK     : C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot
SDK     : C:\android-sdk（或 D:\code\android-sdk）
```

### 构建 + 安装

```powershell
# 1) 同步源码到验证副本
robocopy "C:\Users\敖震宇\...\_r21-fix\companion-pet" "C:\pet-verify4\companion-pet" /E /XD .gradle build .kotlin

# 2) 构建
$env:GRADLE_USER_HOME="C:\gradle-home"
& "C:\gradle-dist\gradle-8.11.1\bin\gradle.bat" --no-daemon :app:assembleDebug

# 3) 立刻停 daemon（否则模拟器被挤死）
& "C:\gradle-dist\gradle-8.11.1\bin\gradle.bat" --stop

# 4) 安装
C:\adbwork\adb.exe -s <设备序列号> install -r app-debug.apk
```

### APK 信息

```
路径  : C:\pet-verify4\companion-pet\app\build\outputs\apk\debug\app-debug.apk
大小  : 32,306,557 字节
SHA256: EC3B6DBC3514B00038C40C60F6C5267D39C3820041E9782B88DE452B5B128DFE
对应提交: f415b8c（b8ccf13 只加了测试，未重新构建 APK）
```

**注意**：`b8ccf13` 只添加了测试文件，产品代码未变，所以 APK 仍对应 `f415b8c`。

---

## 6. 真机验证结果

### ✅ PASS（有证据）

| 项 | 证据 |
|---|---|
| **主动说话**（修完后） | `displayConfirmed shown=true allowed=true`，气泡显示「Ar 我问四遍你不吭声…」 |
| **通知投递**（不依赖 UI） | `PetNotify: probe: send=true result=sent`，`id=90001`，`channel=companion_proactive`，`importance=4` |
| **真实模型调用** | `call_status.ok=true`，`message=正常`，今日调用 5/150 |
| **自动守护** | `PetGuard: tick state=IN_TARGET fg=com.google.android.googlequicksearchbox` |
| **离开拦截** | `PetGuard: tick state=AWAY_GRACE fg=com.tencent.mm` → `REMIND 守护还剩 24 分钟` |
| **拉回目标** | 契约 `lastResult=已回到Gemini` |
| **约定创建** | `appointments.json`：`topic=我项目进度`、`dueAtMillis-createdAt=600000ms` |
| **约定确认消息** | 聊天页原文「记下了：9 分钟后问「我项目进度」。要取消或改期就跟我说。」 |
| **多轮递进**（动机层） | 5 句围绕同一话题层层追问 |

### ❌ NOT_RUN（必须补验）

| 项 | 为什么没验 |
|---|---|
| **约定到点自动追问** | 约定设在 23:18，验证在 23:12 被打断 |
| **约定取消/改期** | 账本逻辑有测试，**UI 还没做** |
| **黏度滑块实际效果** | UI 已做，未在真机操作 |
| **同话题连续上限** | 同上 |
| **息屏到期** | 未测 |
| **进程重建后到期** | 未测 |
| **专注期间到期禁言** | 未测 |
| **投递失败重试恢复** | 未测 |
| **3.1 首页状态卡** | **未做** |
| **3.4 四入口整理** | **未做** |

---

## 7. 待办清单（按优先级）

### P0：把约定闭环验完

```bash
# 1) 建约定（让用户在聊天页说，或直接写文件）
# 2) 立刻读状态
adb -s <serial> shell run-as cn.companion.pet cat files/memory/appointments.json
# 3) 等到期，观察
adb -s <serial> shell run-as cn.companion.pet cat files/memory/appointments.json   # status → awaiting
adb -s <serial> shell dumpsys notification --noredact | grep companion_proactive
# 4) 检查对话
adb -s <serial> shell run-as cn.companion.pet cat files/memory/partner/dialogue.json
```

### P1：随机闲聊默认关闭（任务 2）

当前 `Settings.presenceEnabled` 默认 **true**，与阶段 3 要求「无约定主动闲聊暂时默认关闭」不一致。

需要：
- 新增 `allowRandomSmallTalk` 设置，默认 **false**
- `ProactiveMode` 下 `LIGHT_SMALL_TALK` 候选不参与
- **已创建的约定提醒不受此开关影响**
- 幂等迁移：能识别用户明确选择的保留该选择

### P2：3.1 首页状态卡

- 大字显示：当前状态 / 剩余时间 / 限制范围
- 缺权限时红字提示「限制不会生效」，**绝不显示"已保护"**
- 明确写「这是应用层拦截，不是整机锁定」

### P3：约定 UI

- 约定列表（何时问什么）
- 取消 / 改期入口

### P4：3.4 四入口整理

专注 / 聊天 / 记忆 / 约定，四个入口可达；记忆显示来源 + 更正/遗忘。

---

## 8. 已知风险与技术债

### 8.1 关键风险

| 风险 | 说明 |
|---|---|
| **`ticker` 会静默停止** | 实测切 App 后 `own_life.lastAdvancedAt` 停 25 分钟，进程仍在 FGS、无异常、无日志。我加过独立的 `startGuardLoop` 协程但仍复现。**未定位根因** |
| **分身守护做不到** | `BLOCKED_CAPABILITY`。无障碍只绑定机主，看不到 User 999 |
| **STRICT 不是整机锁定** | 遮罩可被 Home/返回/最近任务绕过。需 Device Owner 才能更强，未做 |
| `焦点` 测试曾失败 | 已修，但改的是测试（`renameTo` → `Files.move`） |

### 8.2 技术债

- `PetBrain.kt` 3500+ 行，未拆分（计划里的 R4 没做）
- 面具上的菜单从 9 项减到 3 项，但其他功能入口仍需整理
- `ProactiveProbeReceiver` / `TestKeyReceiver` 是调试用，正式发布前应移除或加构建类型隔离

### 8.3 参考项目与许可证

| 项目 | 许可 | 用法 |
|---|---|---|
| SurfCore | MIT（但 `@snowluma/*` 依赖有瑕疵） | **只参考 drives 设计，重写实现**，未抄代码 |
| dsh-world | 需核实 | 只参考约定状态机思路 |
| proactive_chat / proactive_care | **AGPL-3.0** | **未抄任何代码**，只在设计上参考"约定优先于定时" |
| AIRI | 需核实 | 未使用 |

---

## 9. 代码修改约定

1. **核心层不加 `android.util.Log`**——`core/` 是纯 JVM，单测会抛 "not mocked"。诊断信息走 `lastActionNote` 之类的字段
2. **文件是 CRLF**，写入时注意行尾，替换锚点要用 CRLF
3. `core/` 层无 I/O、无网络（`AppointmentStore`/`Drives` 是例外，它们是数据层）
4. 中文注释、英文标识符（与现有代码一致）

---

## 10. 复现步骤（10 分钟验收）

```powershell
# 1) 拉代码
git clone https://github.com/aozhenyu666-cmyk/test.git
git checkout feat/proactive-engine-v1   # or b8ccf13

# 2) 复制到 ASCII 路径（必做）
robocopy ".companion-pet" "C:\pet-verify4\companion-pet" /E /XD .gradle build

# 3) 构建
$env:GRADLE_USER_HOME="C:\gradle-home"
& "C:\gradle-dist\gradle-8.11.1\bin\gradle.bat" --no-daemon :app:testDebugUnitTest :app:assembleDebug
& "C:\gradle-dist\gradle-8.11.1\bin\gradle.bat" --stop

# 4) 装到真机
adb -s <serial> install -r C:\pet-verify4\companion-pet\app\build\outputs\apk\debug\app-debug.apk

# 5) 配置（Key 用 debug 广播，仅 debuggable 包生效）
adb -s <serial> shell am broadcast -a cn.companion.pet.action.SET_TEST_KEY --es key "sk-xxx" -p cn.companion.pet

# 6) 验证通知通道
adb -s <serial> shell am broadcast -a cn.companion.pet.action.PROBE_NOTIFICATION --es text "测试" -p cn.companion.pet
adb -s <serial> shell dumpsys notification --noredact | grep companion_proactive

# 7) 验证约定（让用户在聊天页说「十分钟后问问我进度」）
adb -s <serial> shell run-as cn.companion.pet cat files/memory/appointments.json
```

---

## 11. 文件索引

| 想了解什么 | 看哪里 |
|---|---|
| 阶段 3 原计划 | `handoff/stage3/PLAN.md` |
| R0-R4 执行计划 | `handoff/last-rescue/DSH_EXECUTION_PLAN.md` |
| 真机实测记录 | `handoff/last-rescue/DEVICE_RESULTS.md` |
| ProactiveEngine 计划 | `handoff/proactive-v1/PLAN.md` |
| GPT 给的原始方案 | `handoff/last-rescue/PLAN.md`（上游） |

---

## 12. 联系上下文

- 用户的**核心痛点**：两个项目都失败在「基础功能不可靠」（专注锁机、主动说话）
- 用户的**验收标准**：「能用它完成一次专注，并体验一次有上下文、能回应、能取消的主动交流」
- 用户**明确要求**：不要虚报、不要用自动化测试代替真机结论
- 用户**反感**：界面臃肿、重复说同一件事、内部枚举暴露给用户

**接手第一条建议**：先把 P0 的约定闭环验完，那是阶段 3 的完成标准。别急着加功能。
