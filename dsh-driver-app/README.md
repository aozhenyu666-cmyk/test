# 思维驾驶员 · 独立 APP

## 0.4 自我察觉

本轮从最新 `5c697b9` 接续，保留已有暖色界面、Curbox 内置拦截及 Shizuku 自愈。首页新增监督模式（默认仍为伙伴）：5分钟小问、10分钟四问复盘，支持TTS提醒、跳过、部分作答、草稿恢复、本地观察记录和JSON导出。应用时间线保留2小时，未知/锁屏时间单独标记；观察记录14天、察觉截图7天，图片默认不上传。

同时部署配套插件0.2，重载DSH内核：观察按20条以内批量补发、准确id确认去重；大肥鱼出题带最近6条观察与教练计划。复盘消化仅可调用原生 remember 最多2次，不给 run_code 或其他工具放行；仅PTC会话无法在这个限制下写记忆，计划仍可更新。无连接时APP独立使用固定问题，待恢复再补发。

详见 [实现、部署及验证边界](docs/自我察觉实现说明.md) 与 [参考项目及许可](docs/THIRD_PARTY_NOTICES.md)。真机后台限制、TTS、实际DSH记忆写入仍需验证；不保证强制停止后能自行恢复。


两件事：

1. **拦截娱乐 App，答对一道题才放行**（0.3 起内置，不再依赖外部拦截器）：打开你选定要拦的 App → 我们自己把你送回桌面并弹一道题（配对了大肥鱼就由它出题）→ 有依据才放行一段时间。
2. **学习陪练**：开着 Gemini Live 学习时，顶部浮小卡追问你；Gemini 说完你没接话时推你一下。**不拦截、不弹全屏。**

> 0.1.0 的"锁定期间只许用 Gemini"没有回来：手机上做允许名单要预先知道 Gemini 调起的所有系统界面（文件/相册选择器、相机、权限框），会误伤 Gemini 本身和传图片。现在用的是**拦截名单**（只拦你勾的那几个）。
>
> 0.2.0 曾把拦截交给外部拦截器 Curbox，被划掉后它的无障碍会被系统关掉、只能手动重开，所以 0.3 把拦截那段搬进了本 APP。

## 拦截娱乐 App（内置）

拦截这一段是照抄 [Curbox](https://github.com/curbox-app/curbox-android)（GPL-3.0，提交 `b06520f4`）的做法搬进来的——它的拦截本身很好用，问题只在于"被划掉后无障碍就垮掉"。

1. 打开首页「拦截娱乐 App」→ 打开开关 → 「选择要拦的 App」勾上要管的（列表里没有思维驾驶员自己）。
2. 「答对之后放行多久」选一个（默认 10 分钟）。
3. 打开被拦的 App 试一次：应该先被送回桌面，再弹答题页。

出题顺序：议程里没解决的断点 → 上次结论的依据 → 主线目标的下一步 → 通用题；配对了大肥鱼就请它出（8 秒内）。只认"有依据"的回答：因果、引原文、具体规律、命中要的词，或至少 15 个字的实在话；点选、"嗯"、"不知道"都不放行。每次开门、出题、答题记在 `files/driver/sessions/gate.jsonl`。

**拦截页不进最近任务**（`excludeFromRecents`），所以划不掉——这是和外部拦截器最大的区别。

## 别让它被系统关掉

无障碍服务是绑在进程上的：被划掉或被强制停止后，系统会把它从"已启用"列表里删掉，而它自己救不了自己。三层兜底：

| 层 | 什么时候起作用 | 要什么 |
|---|---|---|
| 直接写回 | APP 每次回到前台、开机、覆盖安装 | `WRITE_SECURE_SETTINGS`（用 Shizuku 或 adb 授予一次） |
| Shizuku 写回 | 上面那个权限没有时，借 shell 身份执行 `settings put` | 装 Shizuku 并授权本 APP |
| 看门狗 | 每 15 分钟查一次（JobScheduler），进程被杀也还在 | 无 |

首页「别让它被系统关掉」卡片会显示当前靠哪一层活着，没权限时给一键授权。

用 Shizuku 把 `WRITE_SECURE_SETTINGS` 授予后，以后就不必依赖 Shizuku 常驻了。手动也行：

```bash
adb shell pm grant com.dshdriver.app android.permission.WRITE_SECURE_SETTINGS
```

写回永远是**只追加自己**，不覆盖别人——整串覆盖会把用户其他无障碍服务一起关掉。

## 装到手机上

1. 打开仓库的 Actions → `dsh-driver-app` → 最新一次成功的运行 → 页面底部 Artifacts 下载 `dsh-driver-debug-apk`，解压得到 `app-debug.apk`。
2. 安装（需要允许"安装未知应用"）。签名固定，以后的版本可以直接覆盖安装。
3. 打开「思维驾驶员」，按首页提示打开：
   - **拦截娱乐 App（无障碍）**：拦截必需。只读前台包名，不读屏幕文字。
   - **写系统设置**：建议，用 Shizuku / adb 授予一次，之后无障碍被关掉能自己写回。
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
| `core/src/test/` | 36 项测试：拦截门出题与判卷、完整闭环、两级升级、休息拉回、沉默阶梯、说完触发、冷却、随机插问、防重复、三级记录、议程续接、拦截名单与放行到期 |
| `app/Ui.kt` | 界面的颜色、字号、圆角、按钮和卡片组件都在这里（Claude 风格的暖色调）。设计说明见 [docs/UI.md](docs/UI.md) |
| `core/BlockRules.kt` | 拦截名单 + 每个包的放行到期时间，纯函数、可测 |
| `app/Intercept.kt` | 规则取用（缓存 3 秒）、放行写入、"永远不拦"的名单 |
| `app/Services.kt` | `WatchService` 无障碍服务（**拦截在这里**）、`LockService` 陪练前台服务 + 音频状态、`WatchdogJob` 看门狗、`BootReceiver` |
| `app/A11yGuard.kt` | 无障碍状态判断与写回（直接写 / 走 Shizuku / 通知提醒） |
| `app/Shell.kt` | 用 Shizuku 的 shell 身份跑命令（抄自 Curbox 的 `ShizukuRunner`） |
| `app/GateActivity.kt` | 拦截门答题页：出题、判卷、答对写放行、打开目标 App |
| `app/` | Android 外壳（不用任何 androidx 库）：`Engine` 中枢、`Overlay` 悬浮小卡、`CardActivity` 展开页、`MainActivity` 首页、`Store` 本机存储 |

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
| `core` 状态机、规则、拦截门、拦截名单 | **PASS**：36 项测试 |
| `app` 编译与打包 | 看 CI（`dsh-driver-app`）；0.1.0 首次运行 [41579870352](https://github.com/aozhenyu666-cmyk/test/actions/runs/41579870352) 通过 |
| 配对插件（配对码、令牌、请求门、出题、报告、认领出题对话） | **PASS（假宿主）**：`dsh-driver-core/plugin/test/app.test.mjs`；没在真实 DSH 引擎上跑过 |
| 拦截闭环：打开被拦 App → 回桌面 → 弹答题页 → 答对写放行 → 打开被拦 App | **PASS（模拟器）**：Pixel AVD API 34，拦 Chrome 实测 |
| 放行到期后重新拦 | **PASS（模拟器）**：清掉放行记录后 Chrome 一回前台就被拦 |
| 无障碍自愈：`am force-stop` 后系统把它删掉 → 打开 APP 自动写回 | **PASS（模拟器）**：`enabled_accessibility_services` 从 null 恢复 |
| Shizuku 那条路（没有 `WRITE_SECURE_SETTINGS` 时借 shell 写回） | **NOT_TESTED**：模拟器没装 Shizuku |
| 看门狗（JobScheduler 15 分钟）实际被调度的时机 | **NOT_TESTED**：国产 ROM 可能把它压到几小时 |
| 真机：拦截、悬浮卡位置、音频推断 Gemini 在说/说完、APP 连本机插件 | **NOT_TESTED** |

## 已知限制

- 强杀无障碍是所有拦截器的共同弱点，包括内置后的我们：**强制停止**仍然会让它失守，只能等下次打开 APP 或看门狗（≥15 分钟）把它写回去。真正扛得住的是"另一个 APP 当守护"（比如让哨兵也拿 `WRITE_SECURE_SETTINGS`），这一步还没做。
- 只做了"名单 + 放行时长"这一档。Curbox 的分组、时间段、每日使用时长、按组冷却都没搬（我们的存储在 `Store` 里手写 JSON，带不动它那套 DataStore 模型）。
- 放行到期后不会把正在用的 App 踢出去，要等切走再回来才重新判——这是故意的，避免正在用的时候突然弹页。
- 拦截页拿不到"用户是从哪个入口打开的"，只认包名。
- 这一版抄了 Curbox 的代码，所以 `dsh-driver-app` 整体以 **GPL-3.0** 发布（`LICENSE` 是全文，抄来的文件头写明了来源和提交号）。
- 陪练期间如果系统把 APP 进程杀了，这次陪练就丢了。
- "Gemini 在说话 / 说完了"是从系统音频状态推断的，没有真机证据。推断不出来时，退回 60 秒沉默判断。
- 「回到 Live」按顺序找：Gemini（`com.google.android.apps.bard`）→ Google App → ChatGPT，用第一个装了的。
