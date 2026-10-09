# 一手（Android 自用）

完整需求见 [SPEC.md](SPEC.md)。当前进度：M1 棋盘与一轮、M2 入口思考页、M3 陪练窗口、M4 每晚总结与导出（v0.2.0）。

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
| `round/RoundEngine.kt` | “一轮”的完整流程，主页、思考页、陪练窗口共用；放行与离线保存 |
| `gate/` | 无障碍服务（只读包名）和入口思考页 |
| `window/` | 陪练窗口：时间计算、闹钟、朗读、窗口页 |
| `summary/` | 每晚总结、WorkManager 定时、Markdown 导出 |
| `settings/` | 加密保存的设置 |
| `system/` | 通知 |
| `ui/` | 主页、设置、权限、关注的应用、总结等页面 |

## 本地编译

用 Android Studio 打开 `yishou/` 目录，或命令行：

```
./gradlew testDebugUnitTest assembleDebug
```
