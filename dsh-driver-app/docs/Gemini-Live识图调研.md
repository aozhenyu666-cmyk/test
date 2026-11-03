# Gemini Live 状态识别：本轮只调研

核对日期：2026-11-03。结论：本次检索未找到可以直接安装或嵌入、专门判断“官方 Gemini App 是否正在 Live 通话”的成熟模块；不能把这个有限检索结论理解为不存在任何项目。本轮未加截图、OCR、屏幕文字读取或识图权限。

| 候选 | 核对到的能力 | 对本项目的意义 |
|---|---|---|
| [Pipecat Android Gemini Live Websocket Demo](https://github.com/pipecat-ai/pipecat-client-android-gemini-live-websocket-demo/blob/main/README.md) | README 明确是通过 WebSocket 接 Gemini Multimodal Live 的客户端 demo | 自己的客户端可掌握会话生命周期；它并不能检测官方 Gemini 的界面状态 |
| [ML Kit Vision Quickstart](https://github.com/googlesamples/mlkit/blob/master/android/vision-quickstart/README.md) | Android 相机/静态图文字识别、自定义图像分类的现成示例；样例标 Apache-2.0 | 可以作为 OCR 或截图分类的技术起点，仍需本机 Gemini 界面样本与规则；没有现成 Live 状态模型 |
| [Android AccessibilityService.takeScreenshot](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)) | 平台截图接口，自 API 30 起；需相应无障碍截图能力声明 | 仅是采集路径；安全窗口、版本、厂商行为等需核对。当前服务未声明截图能力 |
| [MediaProjection](https://developer.android.com/media/grow/media-projection) | 系统授权的屏幕捕获机制 | 可作另一采集路径；需要用户授权、可见前台服务，不能当成无提示后台检测 |

检索词：GitHub 仓库搜索 `"Gemini Live" android`、`"Gemini Live" "detect"`、`android screen OCR accessibility`；随后读取上面两个项目 README。仓库结果中大量是自建 Gemini Live 客户端和 Live API 应用，与检测官方 App 不是一件事。未运行这些项目，识别效果全部 NOT_TESTED。

若下一轮决定做原型，先取得本机、当前 Gemini 版本下的非敏感样本：普通聊天、Live 待机、收听、说话、暂停、结束和键盘/弹窗遮挡。可先比较“前台包名 + Live 独有按钮/文本/布局”的本机规则，再决定是否需要 OCR。版本与语言变化会让模板失效；结果应分为 Live / 非 Live / 未知，不能仅凭麦克风被占用推断是 Gemini Live，更不能凭一个未知结果触发处罚。

最小验证：离线留出样本测误报/漏报，记录样本数与 Gemini 版本；再测取帧耗时、电量、锁屏、分屏及厂商限制。先只展示诊断状态。用户确认下一轮范围和截图授权交互后再实现。
