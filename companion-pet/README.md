# companion-pet

伴生桌宠 V1.5 的 Android 工程骨架（H1）。本目录当前只包含可构建、可启动的最小工程与 CI 配置。

## 当前状态

- 可构建的最小 Compose Activity（仅显示应用名与"基础工程已就绪"文案）
- 不启动后台服务，不请求悬浮窗、无障碍、麦克风权限
- 已禁止明文流量，已关闭自动备份并排除云备份/设备迁移
- GitHub Actions 负责构建验证（本机没有 Android SDK 时以 CI 为准）

## 本地构建

前置条件：JDK 17、Android SDK Platform 35、Build Tools 35.0.0。

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

设备测试需要模拟器或真机：

```bash
./gradlew :app:connectedDebugAndroidTest
```

## 尚未包含

H2 的网络客户端、H3 的 Keystore 密钥存储，以及所有桌宠业务功能（服务、感知、探针、气泡、记忆、设置）。
