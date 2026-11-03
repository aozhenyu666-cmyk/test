# 思维驾驶员 · 独立 APP

两件事：

1. **拦截娱乐 App，答对一道题才放行**（0.2.0 起）：拦截交给开源拦截器 [Curbox](https://github.com/curbox-app/curbox-android)，思维驾驶员当它的"答题验证方"——打开 B站 → Curbox 送回桌面并弹全屏页 → 进思维驾驶员答题（配对了大肥鱼就由它出题）→ 有依据才放行，放行多久、何时重拦由 Curbox 决定。
2. **学习陪练**：开着 Gemini Live 学习时，顶部浮小卡追问你；Gemini 说完你没接话时推你一下。**不拦截、不弹全屏。**

> 0.1.0 的"锁定期间只许用 Gemini"已经去掉：手机上做允许名单要预先知道 Gemini 调起的所有系统界面（文件/相册选择器、相机、权限框），会误伤 Gemini 本身和传图片。详见 `dsh-driver-core/docs` 外的调研报告结论：改用 Curbox 的拦截名单。

## 拦截娱乐 App（Curbox）

现在要用**带 API 3 的 Curbox**：第三方解锁条件 2026-10-23 才进 Curbox 代码，最新正式版 6（10-15）里没有。所以先装我们用原版源码（一行不改）编出来的调试版：仓库 Actions → `curbox-build` → 最新成功运行 → Artifacts 里的 `curbox-fdroid-debug`（包名 `neth.iecal.curbox.debug`）。Curbox 发了带 API 3 的正式版后改装官方版即可。

1. 装 Curbox，按它的引导打开它的无障碍和悬浮窗。
2. 思维驾驶员首页「拦截娱乐 App」→「去 Curbox 批准」→ 在 Curbox 弹出的页面批准 → 自动「注册」。看到「✓ 已连上 Curbox」就好。
3. 在 Curbox 里：新建或编辑一个应用拦截分组 → 选要拦的 App（**别选思维驾驶员**）→ 警告页设置 →「Require effort to unlock」→「Require another app」→ 选「思维驾驶员 · 答一道题」→ 设放行时长 → 保存并启用。
4. 打开被拦的 App 试一次。

出题顺序：议程里没解决的断点 → 上次结论的依据 → 主线目标的下一步 → 通用题；配对了大肥鱼就请它出（8 秒内）。只认"有依据"的回答：因果、引原文、具体规律、命中要的词，或至少 15 个字的实在话；点选、"嗯"、"不知道"都不放行。每次开门、出题、答题记在 `files/driver/sessions/gate.jsonl`。

## 装到手机上

1. 打开仓库的 Actions → `dsh-driver-app` → 最新一次成功的运行 → 页面底部 Artifacts 下载 `dsh-driver-debug-apk`，解压得到 `app-debug.apk`。
2. 安装（需要允许"安装未知应用"）。签名固定，以后的版本可以直接覆盖安装。
3. 打开「思维驾驶员」，按首页提示打开：
   - **前台记录（无障碍）**：可选，只用来在首页「诊断」里列出最近读到的前台应用，不拦截。
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
| `core/` | 纯 Kotlin：状态机 `Driver`、水位判定 `Bandwidth`、六骰子 `Dice`、去重 `Similar`、议程 `Agenda`、推进日志 `Telemetry`、拦截门出题判卷 `Gate`、Live 推断 `Reflex`。规则和 `dsh-driver-core/plugin` 的 JS 参照实现一致 |
| `core/src/test/` | 27 项测试：拦截门出题与判卷、完整闭环、两级升级、休息拉回、沉默阶梯、说完触发、冷却、随机插问、防重复、三级记录、议程续接、规则本身 |
| `app/` | Android 外壳（不用任何 androidx 库）：`Engine` 中枢、`Curbox` 对接（AIDL）、`CheckActivity` 拦截门答题页、`WatchService` 前台记录（诊断）、`LockService` 前台服务 + 音频状态、`Overlay` 悬浮小卡、`CardActivity` 展开页、`MainActivity` 首页、`Store` 本机存储 |

数据只存在本机 APP 私有目录 `files/driver/`：设置、主线与死穴、统计、议程、上一次结果、每次陪练的推进日志与拦截门记录（JSONL）。

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
| `core` 状态机、规则、拦截门 | **PASS**：27 项测试 |
| `app` 编译与打包 | 看 CI（`dsh-driver-app`）；0.1.0 首次运行 [41579870352](https://github.com/aozhenyu666-cmyk/test/actions/runs/41579870352) 通过 |
| Curbox 原版编译 | 看 CI（`curbox-build`） |
| 配对插件（配对码、令牌、请求门、出题、报告、认领出题对话） | **PASS（假宿主）**：`dsh-driver-core/plugin/test/app.test.mjs`；没在真实 DSH 引擎上跑过 |
| 真机：Curbox 拦截 → 拉起答题页 → 放行 / 重拦；音频推断 Gemini 在说/说完；APP 连本机插件 | **NOT_TESTED** |

## 已知限制

- 拦截的可靠性取决于 Curbox（它有看门狗、可用 Shizuku 自动重开无障碍）。国产 ROM 杀无障碍是所有拦截器的共同弱点。
- Curbox 的答题请求 10 分钟内有效；它自己的警告页被重建（旋转、进程被杀）这次就作废，要重新答。答题页拿不到被拦的是哪个 App。
- 陪练期间如果系统把 APP 进程杀了，这次陪练就丢了。
- "Gemini 在说话 / 说完了"是从系统音频状态推断的，没有真机证据。推断不出来时，退回 60 秒沉默判断。
- 「回到 Live」按顺序找：Gemini（`com.google.android.apps.bard`）→ Google App → ChatGPT，用第一个装了的。
