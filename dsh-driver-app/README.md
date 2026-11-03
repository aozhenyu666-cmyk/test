# 思维驾驶员 · 独立 APP

开着 Gemini Live / ChatGPT 学习时：切走就把你拉回原断点；Gemini 说完你没接、或者长时间不说话，就给你最省力的一步；回得快但没依据就踩刹车。

不依赖 DSH Go 也能完整运行（全部用模板出题）。和 DSH Go 配对后，追问由大肥鱼的模型结合它的记忆（fish-memory）来写；连不上时自动退回模板。

## 装到手机上

1. 打开仓库的 Actions → `dsh-driver-app` → 最新一次成功的运行 → 页面底部 Artifacts 下载 `dsh-driver-debug-apk`，解压得到 `app-debug.apk`。
2. 安装（需要允许"安装未知应用"）。签名固定，以后的版本可以直接覆盖安装。
3. 打开「思维驾驶员」，按首页提示打开：
   - **切屏检测（无障碍）**：必需。只读前台应用的包名和窗口类型，不读屏幕文字。
   - **悬浮窗**：建议。没有时，所有卡都用全屏页。
   - **不被省电杀掉**：建议。小米/华为/OPPO 还要在系统设置里手动开「自启动」「后台弹出界面」。
   - **通知**：首次打开会问。
4. 在首页写上你的主线目标和老毛病（一行一条），驾驶员追问时会点破。

## 连接大肥鱼（DSH Go，可选）

1. 首页「连接大肥鱼」→「复制给大肥鱼的安装说明」→ 打开 DSH Go，粘贴发给大肥鱼。它会在自己的本机环境里下载插件到 `/root/.dsh/dsh-go/plugins/dsh-driver-core`、用 `dsh plugin --profile web add` 装上，再运行 `bin/pair-code.mjs` 告诉你一个配对码（例：`3080:K7PQ-M3XA`，10 分钟有效、只能用一次）。说明原文在 `app/src/main/kotlin/com/dshdriver/app/Install.kt`。
2. 回到思维驾驶员，填配对码 →「配对」。APP 用它换一个长期令牌，以后自动连。
3. 在 DSH Go 里打开你想让它出题的那个对话（建议单开一个，免得出题内容混进日常聊天），说一句「驾驶员就用这个会话」。首页显示「✓ 出题对话已指定」就好了。

连接只走手机本机 `127.0.0.1`，不出手机。插件的 APP 接口自己校验令牌，拒绝浏览器请求，猜错 5 次锁 1 分钟。每次锁定结束，结果会交给插件存进 `reports.jsonl`，大肥鱼以后可以回顾。

## 用一次

首页填主题、这一段要钉的一件事、时长（默认 15 分钟）→「开始锁定」→ 自动打开 Gemini → 你开 Live 正常学。

- 切去别的应用、回桌面、进设置：立刻盖上断点卡，点 A/B 或写一句才回到 Live。
- 第 2 次再切走或不回：全屏 + 震动 + 语音念题，每 20 秒一次。
- 能解除它的只有三样：有效回答、选「休息 N 分钟」（到点拉回原断点）、「紧急结束」（永远可用，记为主动结束）。
- 来电、输入法、系统界面不算切走。

结束后通知里和首页都会显示：有依据的思考几次、回应几次、切屏几次、接回几次。没做完的断点会挂在首页「还挂着没解决的」，下次开同一个主题时第一问就问你要不要接着做。

## 代码结构

| 位置 | 内容 |
|---|---|
| `core/` | 纯 Kotlin：状态机 `Driver`、水位判定 `Bandwidth`、六骰子 `Dice`、去重 `Similar`、议程 `Agenda`、推进日志 `Telemetry`、切屏判定与 Live 推断 `Reflex`。规则和 `dsh-driver-core/plugin` 的 JS 参照实现一致 |
| `core/src/test/` | 24 项测试：完整闭环、两级升级、休息拉回、沉默阶梯、说完触发、冷却、随机插问、防重复、三级记录、议程续接、规则本身 |
| `app/` | Android 外壳（不用任何 androidx 库）：`Engine` 中枢、`WatchService` 无障碍、`LockService` 前台服务 + 音频状态、`CardActivity` 断点卡、`Overlay` 悬浮小卡、`MainActivity` 首页、`Store` 本机存储 |

数据只存在本机 APP 私有目录 `files/driver/`：设置、主线与死穴、统计、议程、上一次结果、每次锁定的推进日志（JSONL）。

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
| `core` 状态机与规则 | **PASS**：24 项测试 |
| `app` 编译与打包 | **PASS**：CI 首次运行 [41579870352](https://github.com/aozhenyu666-cmyk/test/actions/runs/41579870352) 编译通过、24 项测试全过、产出 APK（约 0.9 MB） |
| 配对插件（配对码、令牌、请求门、出题、报告、认领出题对话） | **PASS（假宿主）**：`dsh-driver-core/plugin/test/app.test.mjs`；没在真实 DSH 引擎上跑过 |
| 真机：无障碍读前台、断点卡后台弹出、回到 Live、音频推断 Gemini 在说/说完、Live 被关检测、APP 连本机插件 | **NOT_TESTED** |

## 已知限制

- 锁定期间如果系统把 APP 进程杀了，这次锁定就丢了（不会假装还在锁定）。所以"不被省电杀掉"很重要。
- "Gemini 在说话 / 说完了"是从系统音频状态推断的，没有真机证据。推断不出来时，退回 60 秒沉默判断。
- 默认允许的应用：Gemini（`com.google.android.apps.bard`）、Google App（Live 有时在这里面）、ChatGPT、DSH Go。
