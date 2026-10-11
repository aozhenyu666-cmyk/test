# 一手（Android 自用）

完整需求见 [SPEC.md](SPEC.md)。当前进度：M1 棋盘与一轮、M2 入口思考页、M3 陪练窗口、M4 每晚总结与导出，以及对话式主页、拍照作答、防掉线提醒、开局规则、陪练看屏、起手式、盘点页，以及六骰子思考法、局面壁纸与小组件、窗口前台服务、白局夜局界面，以及主控台和分组训练，以及语音陪练、无回应拉回、预判本（下注与预演）、间隔回响和运行日志（v0.7.0）。

## 拿到 APK

手机浏览器打开这个地址直接下载（始终是最新一次构建）：

https://github.com/aozhenyu666-cmyk/test/releases/download/yishou-latest/yishou.apk

每次推送 `yishou/` 下的改动，GitHub Actions 的 `yishou-android` 流程会跑单元测试、打包调试版 APK，并更新上面这个地址。

发布页里还有一个 `schemas.zip`，是 Room 导出的数据库结构，提交在 `app/schemas/` 下。

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
| `system/` | 通知、防掉线提醒、图片转写 |
| `look/` | 陪练看屏（屏幕授权、按需截一帧） |
| `stats/` | 盘点：每天的请求用量和计数 |
| `llm/Faces.kt` | 六骰子：六种思考动作、最小动作和检查标准 |
| `wallpaper/` | 局面壁纸（也用作壁纸守护） |
| `widget/` | 桌面小组件 |
| `training/` | 分组训练：每天的目标组数按达标情况加减 |
| `speech/` | 语音：录音与说完检测、听写 / 朗读接口、语音口令 |
| `bet/` | 预判本的校准计算（说的把握和实际命中率） |
| `recall/` | 间隔回响：有效的一手在第 1、3、7 天回来 |
| `log/` | 运行日志：请求失败、服务出错、崩溃 |
| `ui/` | 主页、设置、权限、关注的应用、总结等页面 |

## 本地编译

用 Android Studio 打开 `yishou/` 目录，或命令行：

```
./gradlew testDebugUnitTest assembleDebug
```
