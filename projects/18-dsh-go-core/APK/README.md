# APK

本轮的 arm64 debug 包（二进制不随 git 提交，避免仓库膨胀；构建产物在构建机上）：

    app/build/app/outputs/flutter-apk/app-arm64-debug.apk

| 项 | 值 |
|---|---|
| 包名 | `io.github.ccch1mneyyy.dsh_app` |
| 大小 | 125.9 MB |
| SHA-256 | `5ce608df245f419225f533bf3586e943b933e6e5a3a9704cb1d985c4ea7d58e4` |
| 签名 | `CN=Android Debug, O=Android, C=US`（debug） |
| 证书 SHA-256 | `10cdb677f36a1f5b5a9762c190736b9fd64016876a378b9bdc9297bb5724b758` |

重建：

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:ANDROID_HOME="D:\code\android-sdk"; $env:ANDROID_SDK_ROOT="D:\code\android-sdk"
$env:GRADLE_USER_HOME="D:\code\gradlehome"; $env:PUB_CACHE="D:\code\pubcache"
cd D:\code\dshgo\app;         C:\dev\flutter\bin\flutter.bat pub get
cd D:\code\dshgo\app\android; gradlew.bat :app:assembleArm64Debug
```

⚠️ 这是 **debug 签名**，不能覆盖手机上可能存在��正式签名安装
（会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。若需装到已有 DSH Go 的手机，请用独立测试包名
（`applicationIdSuffix`）或安全的数据迁移方案；**不得卸载现有 APP**。
