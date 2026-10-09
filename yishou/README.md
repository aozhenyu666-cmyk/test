# 一手（Android 自用）

完整需求见 [SPEC.md](SPEC.md)。当前进度：**M1 棋盘与一轮**。

## 拿到 APK

手机浏览器打开这个地址直接下载（始终是最新一次构建）：

https://github.com/aozhenyu666-cmyk/test/releases/download/yishou-latest/yishou.apk

每次推送 `yishou/` 下的改动，GitHub Actions 的 `yishou-android` 流程会跑单元测试、打包调试版 APK，并更新上面这个地址。

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
