# 验证记录

验证日期：2026-10-17。

## 构建与静态检查

- JDK 21、Gradle 8.10.2、Android Gradle Plugin 8.7.3。
- compileSdk / targetSdk：35；minSdk：30（Android 11）。
- `:app:assembleDebug`：成功。
- `:app:assembleDebugAndroidTest`：成功。
- `:app:testDebugUnitTest`：6 项测试，0 失败，0 错误。
- `:app:lintDebug`：`No issues found.`
- `apksigner verify --verbose`：签名校验通过，APK v2 签名。

自动测试验证了明确指令、否定句/问句不触发操作、截图指令与附加问题、暂停和音量指令，以及接口地址必须是 HTTPS 且不能携带 URL 用户信息、查询参数或片段。

APK SHA-256：

```text
bd658f80c581442e81f9e18d20ee83fa0cb6f271c41fb64b04109549b0b895b0
```

## Android 15 模拟器结果

在无硬件加速的 Android 15 x86_64 模拟器中成功安装、启动应用，并查看了实际主界面。

7 项基础检查通过：主界面、Keystore 加密存取、前台服务启动、模型未配置时的反馈、应用不存在时的反馈、打开设置请求、未授权截图时的拦截。完整测试随后因测试环境的无障碍服务连接未就绪而停止，未把这次截图检查记为通过。

重建测试环境的无障碍连接后，对最终 APK 进行定向复核，4 项全部通过：主界面、前台服务启动、实际截图与 JPEG 尺寸检查、服务关闭。合计验证 9 类不同的设备行为。原始记录见 `docs/device-basic-checks.txt` 和 `docs/device-capture-checks.txt`。

截图检查验证系统截图接口和图片编码，不包含真实模型的视觉识别效果。模拟器未验证实际麦克风输入与中文语音播报。

## 设备检查方法

项目提供 `app/src/androidTest/.../SmokeInstrumentation.java`，使用原生 Instrumentation，在测试设备上检查主界面、密钥库加解密、服务启动/关闭、缺少模型时的反馈、找不到应用时的反馈、打开设置、截图授权拦截，以及实际截图/JPEG 尺寸。没有调用真实模型服务，也没有使用真实 API Key。

只在专用测试模拟器上授予麦克风、通知和浮窗权限，再运行。启动 Instrumentation 会停止目标进程；应在测试启动后重新确认无障碍服务已经启用，而不能只依赖启动前的授权状态：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w cn.companion.assistant.test/cn.companion.assistant.SmokeInstrumentation
```

只复核截图与关闭流程可添加 `-e screen_only true`。测试进程启动后，在专用模拟器中完整关闭再重新开启无障碍服务，并等待连接就绪，避免复用已失效的服务连接。

普通用户应通过应用中的系统权限引导进行授权。

## 需要目标真机验证

- 系统是否提供第三方可调用的中文识别服务、设备端语言包和中文 TTS 引擎。
- 实际语音识别质量、端到端延迟、耳机/扬声器路由及耗电。
- 锁屏、来电、省电策略、权限撤回和系统杀进程后的行为。
- 用户选择的模型服务的文字/图片兼容性、鉴权、计费和保留政策。
- 不同 App、受保护内容和不同手机厂商下的启动、导航及截图表现。

构建通过和模拟器检查不代表这些真机场景已经验证。
