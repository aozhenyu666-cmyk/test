# DEEPSEEK-D1：总控 / 一手 合并与止血任务书

2026-11-05 · 基线分支 `claude/yishou-c1-core`（一手 0.25.4）

这份任务书是自包含的，按顺序做完即可，不需要回来确认。**先做任务一和任务二并出包，任务三到五排在后面。**

---

## 0. 动手前必须知道的仓库事实

| 事实 | 影响 |
|---|---|
| 一手最新 0.25.4 在 `fix/gate-loop-break`（2026-11-02），该分支**不含 `zongkong/` 目录** | 一手和总控已经分叉成两棵树，必须先建集成分支 |
| 总控最新 0.17.0 在 `feat/dual-mode-closed-loop`（2026-10-29） | 这是总控的合并基线 |
| 总控 0.16.0-health-v2 在 `deepseek/strict-mode` | 自愈 / 健康协议在这条线上 |
| 总控 0.11.0 在 `deepseek/zk-training-ui` | **唯一带 `:sentinel` 独立进程的线**，最新 0.17.0 反而没有 |
| `GuardService` 在所有分支都跑在主进程 | 这是“和哨兵一起掉”的根因 |
| 构建路径含中文时 Gradle 单测会 worker 崩溃 | 本地请在 ASCII 路径下检出，例如 `C:\build\repo` |

本次已经交付并验证过的规则模块（不要重写）：

```
yishou/app/src/main/java/com/yishou/app/core/
  Stall.kt  NextAction.kt  FinalReview.kt  Weekly.kt
yishou/app/src/test/java/com/yishou/app/core/
  StallTest.kt  NextActionTest.kt  FinalReviewTest.kt  WeeklyTest.kt
```

45 个测试全过。这四个文件是纯 Kotlin/JVM，不碰 Android。

---

## 任务一：合并开发线（先做，不写新功能）

### 1.1 建集成分支（这两条历史没有共同祖先，不能直接 merge）

已核实：`git merge-base feat/dual-mode-closed-loop fix/gate-loop-break` 返回空，两条线是**无关历史**。直接 `git merge` 会把整棵 `yishou/` 当成“双方各自新增”而全量冲突。

正确做法是**按目录装配**，不是合并历史：

```bash
git fetch --all --prune
git checkout -b integration/all feat/dual-mode-closed-loop   # 这棵树同时有 yishou 和 zongkong

# 用一手 0.25.4 的整棵 yishou 替换掉旧版
git rm -r --quiet yishou
git checkout fix/gate-loop-break -- yishou

# 确认版本
grep versionName yishou/app/build.gradle.kts zongkong/app/build.gradle.kts

git add -A yishou
git commit -m "assembly: 一手 0.25.4 + 总控 0.17.0（无关历史，按目录装配）"
```

`projects/` 目录这两条树上都没有，从本次分支取：

```bash
git checkout claude/yishou-c1-core -- projects
```

原则：

- **不要再 `git merge` 旧线**。旧线只用于 `git checkout <branch> -- <具体目录/文件>` 或 `cherry-pick` 单个 commit。
- 根文件（`README.md`、`package.json`、`.gitignore`）保留 `feat/dual-mode-closed-loop` 的版本，不手工重写。
- `yishou/` 以 `fix/gate-loop-break` 为准；`zongkong/` 以 `feat/dual-mode-closed-loop` 为准。

### 1.2 把两条旧线的独占能力搬过来

**从 `deepseek/strict-mode`（0.16.0-health-v2）搬自愈 / 健康协议：**

```bash
git log --oneline feat/dual-mode-closed-loop..deepseek/strict-mode
```

按 commit 逐个 `cherry-pick`，只挑健康协议、无障碍自愈、心跳相关；不要挑 0.16 的 UI 改动。

**从 `deepseek/zk-training-ui`（0.11.0）搬哨兵：**

需要搬的文件：

```
zongkong/app/src/main/java/com/zongkong/app/guard/Sentinel.kt
zongkong/app/src/main/java/com/zongkong/app/guard/SentinelService.kt
zongkong/app/src/main/java/com/zongkong/app/guard/SentinelBlockActivity.kt
zongkong/app/src/main/res/xml/sentinel_service.xml
strings 里的 sentinel_name
AndroidManifest 里 :sentinel 的 service / activity
```

注意：0.11.0 的 `SentinelLink` 用的是 `filesDir/zk/sentinel/` 下的 `state.json` + `beat`，这套文件契约要保留，任务二会复用同一个模式。

### 1.3 验收

```bash
cd zongkong && ./gradlew :app:assembleMainlineDebug -Pandroid.overridePathCheck=true
cd ../yishou && ./gradlew :app:assembleMainlineDebug -Pandroid.overridePathCheck=true
```

- 两个 APK 都能出包。
- 安装后无障碍设置里只出现总控 + 哨兵两个服务，一手的不再出现（任务三完成后再验）。
- 在 `DEVLOG.md` 写清：合并了哪几条线、各自版本、哪些 commit 被丢弃及原因。

---

## 任务二：无障碍服务独立进程（最高优先级，先发版）

### 2.1 先纠正一个错误做法

**不要只给 `GuardService` 加 `android:process=":guard"`。** 它现在依赖 `zk.store`、`Engine`、`Notifications`、`SenseHub` 这些内存单例；换进程后每个进程有独立的 `Application` 和单例，Room 也不支持多进程并发写。只加一行配置的结果是：拦截逻辑读到空状态，看起来“服务活着但什么都不拦”。

### 2.2 正确结构

```
主进程（com.zongkong.app）
  Engine / 计时 / 截图 / Notion / 通知 / 完整 GateActivity
  写：filesDir/zk/guard/state.json   （状态 + 心跳，每次变化和每 5 秒一次）
  读：filesDir/zk/guard/events.log   （按 cursor 增量消费）
  读：filesDir/zk/guard/guard.beat   （判断 :guard 是否活着）

:guard 进程（轻，只有三件事）
  GuardService        只读前台包名
  GuardLink           读写上面三个文件
  GuardBlockActivity  极简拦截页（主进程死了时用）
  GuardNotify         常驻通知
  写：events.log、guard.beat
  不读 Room、不碰网络、不初始化 AI
```

### 2.3 文件契约

`state.json`（主进程写，`:guard` 读）：

```json
{
  "at": 1762300000000,
  "strict": true,
  "freeUntil": 0,
  "blocked": ["com.ss.android.ugc.aweme", "com.xingin.xhs"],
  "whitelist": ["com.zongkong.app", "com.yishou.app"],
  "focusUntil": 0
}
```

写入必须是 **临时文件 + rename**（同目录 `state.json.tmp` → `state.json`），保证 `:guard` 永远读不到半个 JSON。读取端 `catch (JSONException)` 后沿用上一次成功解析的值。

`events.log`（`:guard` 追加，主进程消费）：每行一个 JSON。

```json
{"ts":1762300000123,"pkg":"com.ss.android.ugc.aweme","event":"fg"}
{"ts":1762300000456,"pkg":"com.ss.android.ugc.aweme","event":"block"}
{"ts":1762300060000,"pkg":"com.zongkong.app","event":"pass","minutes":10}
```

主进程把消费到的字节偏移写进 `events.cursor`；超过 256KB 轮转一次。**不要在 `:guard` 里做时长累计**——那需要跨进程状态，容易双算。

### 2.4 GuardService 在 `:guard` 里只做四件事

窗口切换（`TYPE_WINDOW_STATE_CHANGED`）时：

1. 读 `state.json`（带 1 秒内存缓存，mtime 变了才重读）。
2. 若 `strict && now > freeUntil && pkg in blocked && pkg !in whitelist`：
   - 主进程心跳新鲜（`state.at` 在 10 秒内）→ 启动完整 `GateActivity`（会拉起主进程）；
   - 心跳过期 → 启动 `:guard` 里的 `GuardBlockActivity`（极简页，不依赖主进程）。
3. 往 `events.log` 追加一条 `fg`（每次切换都记，主进程自己判断算不算娱乐时长）。
4. 每 5 秒写一次 `guard.beat`（时间戳 + 进程存活标记）。

### 2.5 自愈（需要一次 adb 授权）

Manifest 加：

```xml
<uses-permission
    android:name="android.permission.WRITE_SECURE_SETTINGS"
    tools:ignore="ProtectedPermissions" />
```

一次性授权：

```bash
adb shell pm grant com.zongkong.app android.permission.WRITE_SECURE_SETTINGS
```

逻辑：

- 主进程发现 `state.at` 超过 90 秒没更新，或 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 里没有总控组件 → 尝试把自己追加回去。
- 写之前先读当前值，已包含就不要再写（避免把用户手动开的其他服务关掉）。
- 失败（没权限）→ 弹一次引导页，不要反复弹。
- 频率限制：最多 10 分钟一次，连续失败 3 次后当天不再自动尝试，只留手动按钮。

### 2.6 UsageStats 兜底

Manifest 加：

```xml
<uses-permission
    android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions" />
```

- 用 `AppOpsManager` 检查 `OPSTR_GET_USAGE_STATS`。
- 只在无障碍心跳过期时启用：查最近 10 秒的 `ACTIVITY_RESUMED`，取最后一条包名。
- **只用于判断“现在是不是在被拦应用里”，不要用于累计分钟数**（UsageStats 有延迟，会双算）。
- 精度预期：2–5 秒。够拦截，不够做秒级统计。

### 2.7 iQOO 12 引导页

五项，每项一个复选框（本地记录，不假装能程序化验证）：

1. 允许后台高耗电
2. 允许自启动
3. 允许关联启动
4. 允许后台弹出界面
5. 锁定最近任务（后台卡片下拉加锁）

能拿到 Intent 的直接跳（`com.iqoo.secure` / `com.vivo.permissionmanager`），拿不到就写清路径文字。**不要循环弹**，只在检测到掉线时出现一次。

### 2.8 掉线日志（用来查清为什么和哨兵一起掉）

`filesDir/zk/guard/incidents.log`，每行一个 JSON：

```json
{"ts":1762300000000,"kind":"main_dead","beatAgeMs":120000,"battery":37,"charging":false,"screenOn":true,"fgPkg":"com.tencent.mm"}
```

`kind` 取值：`main_dead` / `service_disabled` / `crash` / `frozen` / `usage_fallback`。

在总控状态页做一个“最近掉线”列表，**不要推送**，不要做成仪表盘。

### 2.9 验收（必须逐条做）

```bash
# 1. 主进程被杀，:guard 仍然拦
adb shell am kill com.zongkong.app
# 打开被拦应用 → 2 秒内出现极简拦截页

# 2. 状态文件生效
# 改动 state.json 的 strict=false → 1 秒内放行

# 3. 自愈
adb shell settings put secure enabled_accessibility_services ""
# 90 秒内自动恢复（已授权的情况下）

# 4. 不双拦
# 同时开总控和一手，切到娱乐 App，只出现一个拦截页

# 5. 无常驻崩溃
adb logcat -d | grep -i "FATAL EXCEPTION"
```

---

## 任务三：只留一个拦截器

1. 一手 `GateService` 从 Manifest 移除，或设 `android:enabled="false"`；`GateActivity` 保留，但只由总控调用。
2. 总控 → 一手的出题契约（二选一，优先 A）：
   - **A. 显式 Intent + 签名校验**：总控 `startActivityForResult` 打开一手的出题页，一手返回题目文本与题号。
   - **B. ContentProvider**：一手暴露只读 provider，总控查一道题。
3. 一手没装 / 调用失败：总控用内置最小题库（10 道言语题）继续拦，**不允许因此放行**。
4. 放行记账全部在总控：`pass` 表（包名组、开始、结束、来源 roundId）。到期由总控重新锁。
5. 专注时段归总控：`focusUntil` + 可开 App 白名单，写进 `state.json`。一手只负责时段内的陪练流。

验收：

```bash
adb shell settings get secure enabled_accessibility_services
# 只应包含 com.zongkong.app/...GuardService 和 ...SentinelService
```

切到娱乐 App，`adb logcat` 里只应有一条 `block` 事件。

---

## 任务四：长期统筹（最小版）

目标：月 → 周 → 今天三件事，全部落在总控里，不新建 App。

数据：

```
Goal(month)     一行字 + 验收物
WeekPlan        本周要推进的 1–3 件事
DailyThree      今天三件事，每条必须过 NextActionRules.check
```

三个主动触点（都要能一键忽略）：

1. **早上解锁**：显示今天三件事，只显示，不要求确认。
2. **计划时间到了还没开始**：只问一句“现在开始，还是推到几点？”——两个按钮。
3. **晚上**：收三句回顾（做了什么 / 卡在哪 / 明天第一件事）。

周日晚生成下周草稿：**AI 写草稿，人只改不写**。草稿必须引用上周的实际结算率，不许写空话。

验收：

- 周日晚不需要用户从零输入就能得到一份可编辑的下周计划。
- 早上解锁 3 秒内看到三件事。
- 到点未开始只问一次，不重复催。

---

## 任务五：一手陪练流安卓接线

规则层已经写好，DeepSeek 只接安卓三件事：截屏、听写、浮窗。

每一拍只用三样“此刻”的东西出题：

1. 屏幕上的文字；
2. 用户边做边念叨的话（sherpa-onnx 离线识别，**不存录音**，只留文本）；
3. 用户定的下一步动作。

硬规则：

- 出的那句话必须引用屏幕上或用户刚说的原词，本机核对；引不出来就不发。用 `FinalReviewRules.quotableSpan()` 取可引用片段（长句自动取末段）。
- 用户正在说话时（VAD 判定）不插嘴。
- 10 分钟最多说 6 次。
- 连续三拍没接 → 用 `Stall.classify()` 分清是卡住、累了还是不想做，再按 `Stall.respond()` 接。
- 语音模型只走离线；网络只用于文本请求。

验收：

- 断网时仍能识别并出题（模型已下载的情况下）。
- `filesDir` 下不存在任何音频文件。
- 连续三拍沉默后，只出现一次“要一个回来的时间”，不是连续追问。

---

## 全局验收

| 项 | 标准 |
|---|---|
| 构建 | 两个项目在 ASCII 路径下都能 `assembleMainlineDebug` |
| 单测 | 一手现有 450 个 `@Test` + 新增 core 45 个全过 |
| 拦截 | 只出现一个拦截页；主进程被杀后 2 秒内仍拦 |
| 自愈 | 无障碍被关后 90 秒内自动恢复（授权后） |
| 数据 | 周报只出现四个数：专注分钟、结算率、正确率/用时、裸考 |
| 日志 | 每次掉线有 kind 和心跳年龄，可回溯 |

---

## 禁止事项（违反即返工）

1. 不加这份任务书之外的新功能。
2. 不做认知训练、脑力游戏、工作记忆训练、反应抑制训练。
3. 不做连续打卡、排名、进度仪表盘。
4. 不做防卸载、不做“更难退出”。
5. 不做第二个拦截器。
6. 不用长篇输出、说教、重复提醒。
7. 不把 `:guard` 做成第二个主进程；它只允许读包名、读写三个文件、弹极简页。
8. 不在 `:guard` 里访问 Room、网络、AI。

---

## 交付格式

每个任务一个 commit（或一组小 commit），message 用中文，写清：

```
任务二：无障碍拆到 :guard 独立进程
- GuardLink 文件契约（state.json / events.log / guard.beat）
- 主进程只写状态，:guard 只读状态
- 主进程死了走 GuardBlockActivity
- 验收：am kill 后 2 秒内仍拦
```

做完任务一、二先出一个 APK，在 iQOO 12 上跑完 2.9 的五条验收，把结果贴进 `DEVLOG.md`，再继续任务三。
