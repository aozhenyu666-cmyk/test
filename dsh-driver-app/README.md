# 思维驾驶员 · 独立 APP

两件事：

1. **内置拦截所选 App**（0.3.0）：只拦首页勾选的 App，先回桌面，再弹答题页；通过本地判卷后按包放行（默认 10 分钟，可调），到期重查。判卷只识别有依据/有内容的回答，不验证知识答案是否正确。仍保留 [Curbox](https://github.com/curbox-app/curbox-android) API v3 兼容入口。
2. **学习陪练**：开着 Gemini Live 学习时，顶部浮小卡追问你；Gemini 说完你没接话时推你一下。**不拦截、不弹全屏。**

> 0.1.0 的"锁定期间只许用 Gemini"已经去掉：手机上做允许名单要预先知道 Gemini 调起的所有系统界面（文件/相册选择器、相机、权限框），会误伤 Gemini 本身和传图片。详见 `dsh-driver-core/docs` 外的调研报告结论：改用 Curbox 的拦截名单。

## 内置拦截与无障碍自动写回

1. 首页「内置应用拦截」勾选应用，设置放行时长，打开拦截开关。
2. 打开「思维驾驶员 · 应用拦截」无障碍。系统界面、桌面和输入法优先豁免；名单外全部放行。请勿同时用 Curbox 拦同一应用，以免两边重复弹页。
3. 可选：电脑连手机执行 `adb shell pm grant com.dshdriver.app android.permission.WRITE_SECURE_SETTINGS`，再打开首页「无障碍被关闭后自动写回」。首页可复制命令、查看设置状态、服务连接和最近写回时间。
4. 自动修复每 30 秒检查，另有最短 15 分钟的 Job（受系统调度限制），并在启动/回首页/开机/更新后检查。写回只追加自身组件、保留其他服务。写回不等于服务连接成功。
5. 整包被强制停止后不能自救。哨兵独立看护三应用的改动在 `codex/sentinel-a11y-guardian`，需要安装该分支构建、授予**哨兵自己的**写安全设置权限，并在哨兵里打开对应看护开关。一手仅作为被哨兵写回的目标时，不需要为这项功能给一手授予该权限。

要在系统里手动关闭无障碍，先关闭本机自动修复和哨兵对应看护开关。卸载重装后需重新授予权限。国产系统仍需手动允许自启动、后台弹页和后台运行；效果 NOT_TESTED。答题页从最近任务中排除，只能减少被划掉任务的机会，不能保证免杀。

## 可选：继续使用 Curbox

现在要用**带 API 3 的 Curbox**：第三方解锁条件 2026-10-23 才进 Curbox 代码，最新正式版 6（10-15）里没有。所以先装我们用原版源码（一行不改）编出来的调试版：仓库 Actions → `curbox-build` → 最新成功运行 → Artifacts 里的 `curbox-fdroid-debug`（包名 `neth.iecal.curbox.debug`）。Curbox 发了带 API 3 的正式版后改装官方版即可。

1. 装 Curbox，按它的引导打开它的无障碍和悬浮窗。
2. 思维驾驶员首页「拦截娱乐 App」→「去 Curbox 批准」→ 在 Curbox 弹出的页面批准 → 自动「注册」。看到「✓ 已连上 Curbox」就好。
3. 在 Curbox 里：新建或编辑一个应用拦截分组 → 选要拦的 App（**别选思维驾驶员**）→ 警告页设置 →「Require effort to unlock」→「Require another app」→ 选「思维驾驶员 · 答一道题」→ 设放行时长 → 保存并启用。
4. 打开被拦的 App 试一次。

出题先展示断点、上次结论或主线等具体素材，再随机选择回忆、反驳、教 Gemini、小任务、连接主线，最近三种不重复；没有个人记录时会明确提示，不虚构经历。配对插件最多注入 12 条记忆和最近 5 份报告的结论；模板相似度达 0.6 或没引用素材会在总计 8 秒预算内重出一次，再失败则换互动类型用本地兜底。相似度只检测字面，不保证语义去重。只认"有依据"的回答：因果、引原文、具体规律、命中要的词，或至少 15 个字的实在话；点选、"嗯"、"不知道"都不放行。每次开门、出题、答题记在 `files/driver/sessions/gate.jsonl`。

## 装到手机上

1. 打开仓库的 Actions → `dsh-driver-app` → 最新一次成功的运行 → 页面底部 Artifacts 下载 `dsh-driver-debug-apk`，解压得到 `app-debug.apk`。
2. 安装（需要允许"安装未知应用"）。签名固定，以后的版本可以直接覆盖安装。
3. 打开「思维驾驶员」，按首页提示打开：
   - **应用拦截（无障碍）**：内置拦截需要；同时在首页「诊断」列出最近读到的前台应用。
   - **悬浮窗**：建议。没有时，所有卡都用全屏页。
   - **不被省电杀掉**：建议（陪练用）。小米/华为/OPPO 还要在系统设置里手动开「自启动」「后台弹出界面」。
   - **通知**：首次打开会问。
4. 在首页写上你的主线目标和老毛病（一行一条），驾驶员追问时会点破。

## 连接大肥鱼（DSH Go，可选）

1. 首页「连接大肥鱼」→「复制给大肥鱼的安装说明」→ 打开 DSH Go，粘贴发给大肥鱼。它会在自己的本机环境里下载插件到 `/root/.dsh/dsh-go/plugins/dsh-driver-core`、用 `dsh plugin --profile web add` 装上，再运行 `bin/pair-code.mjs` 告诉你一个配对码（例：`3080:K7PQ-M3XA`，10 分钟有效、只能用一次）。说明原文在 `app/src/main/kotlin/com/dshdriver/app/Install.kt`。
2. 回到思维驾驶员，填配对码 →「配对」。APP 用它换一个长期令牌，以后自动连。
3. 在 DSH Go 里打开你想让它出题的那个对话（建议单开一个，免得出题内容混进日常聊天），说一句「驾驶员就用这个会话」。首页显示「✓ 出题对话已指定」就好了。

连接只走手机本机 `127.0.0.1`，不出手机。插件的 APP 接口自己校验令牌，拒绝浏览器请求，猜错 5 次锁 1 分钟。每次锁定结束，结果会交给插件存进 `reports.jsonl`，大肥鱼以后可以回顾。

## 学习陪练

首页填主题、这一段要钉的一件事、时长（默认 15 分钟）→「开始陪练」→ 自动打开 Gemini（设置里排第一个、而且装了的那个）→ 你开 Live 正常学。

- 顶部浮小卡追问你：秒回没依据就踩刹车；Gemini 说完 8–15 秒你没接话，提示你念一句给 Live；完全沉默 60 秒给一个 A/B 小动作。
- 卡没人理只会重新出现并短震一下，**不全屏、不念题、不判你切走**。切去别的 App、传图片、开相机都不受影响。
- 结束后显示：有依据的思考几次、回应几次。没做完的断点挂在首页「还挂着没解决的」，下次同主题开场先问要不要接着做；拦截门出题也会优先问它。

## 代码结构

| 位置 | 内容 |
|---|---|
| `core/` | 纯 Kotlin：状态机 `Driver`、水位判定 `Bandwidth`、六骰子 `Dice`、去重 `Similar`、议程 `Agenda`、推进日志 `Telemetry`、拦截门出题判卷 `Gate`、Live 推断 `Reflex`。另含名单规则 `BlockRules` 与互动 `Interaction`；APP 是运行时实现，插件 JS 状态机为参照 |
| `core/src/test/` | 33 项测试：拦截门出题与判卷、完整闭环、两级升级、休息拉回、沉默阶梯、说完触发、冷却、随机插问、防重复、三级记录、议程续接、规则本身 |
| `app/` | Android 外壳（不用任何 androidx 库）：`Engine` 中枢、`Curbox` 对接（AIDL）、`CheckActivity` 拦截门答题页、`WatchService` 名单拦截与诊断、`GateActivity` 内置答题、`ProtectionService` / Job 自动写回、`LockService` 前台服务 + 音频状态、`Overlay` 悬浮小卡、`CardActivity` 展开页、`MainActivity` 首页、`Store` 本机存储 |

数据存在本机 APP 私有文件与 SharedPreferences；`files/driver/` 保存：设置、主线与死穴、统计、议程、上一次结果、每次陪练的推进日志与拦截门记录（JSONL）。

## 构建

```bash
# 只跑 core 测试：任何有 JDK 17+ 的机器都行，不需要 Android SDK
gradle :core:test

# 打 APK：需要 Android SDK（设好 ANDROID_HOME），CI 上就是这样跑的
./gradlew :core:test :app:assembleDebug
```

没有 Android SDK 时 `settings.gradle.kts` 只包含 `:core`。

## 状态

| 部分 | 状态 |
|---|---|
| `core` 状态机、规则、拦截门 | **PASS**：33 项测试 |
| `app` 编译与打包 | 看 CI（`dsh-driver-app`）；0.1.0 首次运行 [41579870352](https://github.com/aozhenyu666-cmyk/test/actions/runs/41579870352) 通过 |
| Curbox 原版编译 | 看 CI（`curbox-build`） |
| 配对插件（配对码、令牌、请求门、出题、报告、认领出题对话） | **PASS（假宿主）**：`dsh-driver-core/plugin/test/app.test.mjs`；没在真实 DSH 引擎上跑过 |
| 真机：Curbox 拦截 → 拉起答题页 → 放行 / 重拦；音频推断 Gemini 在说/说完；APP 连本机插件 | **NOT_TESTED** |

## 已知限制

- 内置拦截与自动写回的真机可靠性 NOT_TESTED。后台启动页面、清后台、强制停止和国产 ROM 行为都必须真机测。
- Curbox 的答题请求 10 分钟内有效；它自己的警告页被重建（旋转、进程被杀）这次就作废，要重新答。答题页拿不到被拦的是哪个 App。
- 陪练期间如果系统把 APP 进程杀了，这次陪练就丢了。
- "Gemini 在说话 / 说完了"是从系统音频状态推断的，没有真机证据。推断不出来时，退回 60 秒沉默判断。
- 「回到 Live」按顺序找：Gemini（`com.google.android.apps.bard`）→ Google App → ChatGPT，用第一个装了的。

实现选择与验收边界见 [实施记录](docs/实施记录-0.3.md)；[Gemini Live 识图调研](docs/Gemini-Live识图调研.md) 只调研，未接入截图或 OCR。
