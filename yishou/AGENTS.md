# 给 Codex（以及其他编码代理）的规矩：yishou/

「一手」是作者本人自用的 Android 应用（Kotlin + Jetpack Compose + Room），目录 `yishou/`。

## 先读

- `yishou/README.md`：功能和目录说明。
- 做语音伙伴的任务时：`yishou/docs/voice-partner.md`，只做任务里指定的那一条。

## 分支和提交

- 从 `ccr-a5172662-j1jcik` 切新分支，PR 合回 `ccr-a5172662-j1jcik`。不要动 `main`，不要改别的分支。
- 提交说明用中文，写清做了什么。不要在提交、PR、代码注释里写模型名称。
- 推到分支后 GitHub Actions 会跑单元测试并打包（只有 `ccr-a5172662-j1jcik` 会发布到下载页）。PR 里贴上 CI 结果。

## 构建和测试

- 在 `yishou/` 下：`./gradlew testMainlineDebugUnitTest`（单元测试），`./gradlew assembleMainlineDebug assembleChaluDebug`（打包）。
- 沙盒里没有 Android SDK 时：不要硬装半天。把纯逻辑写成不依赖 Android 的 Kotlin 文件，单元测试照写；推分支，让 CI 跑，并在 PR 里说明本地没跑成。
- 两个构建变体：`mainline`（主线）和 `chalu`（岔路，`BuildConfig.CHALU = true`）。纯逻辑不读 `BuildConfig`，需要时用构造参数传进去。

## 写代码

- 先写纯逻辑和 JVM 单元测试（`app/src/test/java/...`），再接 Android 和界面。网络、音频、系统服务都藏在接口后面，测试里用假对象。
- 照着已有写法：会话类看 `line/LineSession.kt`、`soup/Soup.kt`（`MutableStateFlow` 状态 + 挂起函数）；模型请求看 `llm/ChatClient.kt` 和 `llm/SoupHost.kt`（标签文本，不要求 JSON）；语音看 `speech/` 和 `window/Speaker.kt`；界面看 `ui/SoupScreen.kt`。
- 注释和界面文字用中文，口语、短句；注释说“为什么”，不复述代码。
- 数据库：改表就升 `AppDatabase` 的 version，写 `MIGRATION_x_y`（建表语句要和 Room 生成的一字不差），加进 `addMigrations`。schema JSON 由 CI 导出，合并后由人补提交。
- 设置里的密钥一律走已有的加密设置（`settings/`），不进代码、不进日志。
- 不要顺手重构、改名、格式化与任务无关的文件。

## 透明原则（不能破）

- 仅作者本人在自己设备上使用；所有权限由用户手动授予；应用图标、名称和常驻通知“一手正在运行”始终可见；不做隐藏、防卸载、设备管理员。
- 无障碍服务只读前台应用包名，不读屏幕内容、不模拟点击。
- 麦克风只在用户开始会话后打开，全程有可见通知和停止按钮；不存录音。
- 屏幕截图只在用户明确要求时取一帧，走系统授权，图片不落盘。
- 数据只存在本机；网络只发往用户自己配置的模型 / 语音接口。

## 拿不准时

产品上的取舍（交互怎么设计、哪个更好用）写在 PR 说明里问，不要自己拍板。
