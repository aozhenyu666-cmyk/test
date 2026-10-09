# 一手（Android 自用）

完整需求见 [SPEC.md](SPEC.md)。当前进度：**M1 棋盘与一轮**。

## 拿到 APK

每次推送 `yishou/` 下的改动，GitHub Actions 的 `yishou-android` 流程会跑单元测试并打包调试版 APK：

1. 打开仓库的 Actions → `yishou-android` → 最新一次运行
2. 页面底部 Artifacts 下载 `yishou-debug-apk`（zip，解压后是 `app-debug.apk`）
3. 传到手机安装（需要允许“安装未知应用”）

APK 用仓库里固定的调试签名（`app/debug.keystore`），以后的新版本可以直接覆盖安装，本机数据保留。

## 代码在哪

| 目录 | 内容 |
|---|---|
| `data/` | Room 数据表与唯一的 DAO |
| `llm/Prompts.kt` | 所有提示词和返回格式，改陪练行为只改这里 |
| `llm/ChatClient.kt` | OpenAI 兼容接口请求，30 秒超时 |
| `llm/Coach.kt` | 开局 / 判定请求、JSON 解析、解析失败重试一次 |
| `round/RoundEngine.kt` | “一轮”的完整流程，主页、思考页、陪练窗口共用 |
| `settings/` | 加密保存的设置 |
| `ui/` | 各个页面 |

## 本地编译

用 Android Studio 打开 `yishou/` 目录，或命令行：

```
./gradlew testDebugUnitTest assembleDebug
```
