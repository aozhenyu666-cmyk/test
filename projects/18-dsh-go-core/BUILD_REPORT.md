# BUILD_REPORT

环境、构建链路与产物。工作副本：`D:\code\dshgo`（`C:\work\dshgo` 因 C 盘满迁移而来，git 历史完整）。

## 1. 工具链

| 项 | 值 |
|---|---|
| Git | 2.55.0.windows.5 |
| Flutter | 3.47.7 stable（`C:\dev\flutter`），Dart 3.13.5 |
| JDK | Microsoft OpenJDK 21.0.12.1（`JAVA_HOME` 已设） |
| Android SDK | `D:\code\android-sdk`，platforms 34/35/36，build-tools 36.0.0 |
| Gradle | wrapper 9.3.1（`gradlew`/`gradlew.bat`/`gradle-wrapper.jar` 从 Flutter cache 补入） |
| Node | v24.19.0（插件测试用） |
| Python | 3.14.6（`tool/l10n_merge.py`、`runtime/lib/rtbuild.py`） |
| 结论 | **本地构建可用，不需要 GitHub Actions** |

`flutter doctor` 唯一实质问题是 `maven.google.com` 偶发超时；本项目 `settings.gradle.kts`
已把阿里云镜像放在首位，构建实际未受影响。

## 2. 上游获取方式（非常规）

上游 `dsh-Go` 在 `d38a39a` 的工作树约 **331 MB**，其中 `app/test/**/goldens` 约 282 MB
PNG 基线。`git clone`（含 `--depth 1 --filter=blob:none`）在本网络下反复
`RPC failed / early EOF / invalid index-pack`。

最终做法：

1. `GET /repos/.../git/trees/<sha>?recursive=1` 取全量 tree（4,257 blob，未截断）；
2. 生成 curl config，**排除** `app/test/**/goldens` 与 `site/home/dist`，保留 3,300 个文件；
3. `curl --parallel` 按文件下载到 `C:/tmp-dshgo`（ASCII 路径），逐文件比对 `size` 字段，
   **3,300/3,300 全部一致**；
4. 之后按需补下载 `app/test/phone/goldens/`（34 张，17 MB）用于回归对照。

`git am` 应用补丁时 Python/PowerShell 写出的文件带 BOM 且为 CRLF，`git am` 报
`Patch format detection failed`；去掉 BOM 并统一 LF 后可干净应用。

## 3. 环境阻塞与解法（全部已解决）

| # | 现象 | 根因 | 解法 |
|---|---|---|---|
| 1 | `Your project path contains non-ASCII characters` | AGP 拒绝含中文的工程路径 | 工作副本放纯 ASCII 路径（`D:\code\dshgo`） |
| 2 | 测试进程 `ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain` | Gradle test worker 的 classpath argfile 落在 `C:\Users\<user>\.gradle\.tmp` | `GRADLE_USER_HOME` 指向 ASCII 路径 |
| 3 | `buildCMakeDebug` ninja `FindFirstFileExA` 找不到 pub cache | pub cache 在 `C:\Users\<user>\AppData\Local\Pub` | `PUB_CACHE` 指向 ASCII 路径 |
| 4 | `MergeNativeLibsTask ... 磁盘空间不足` | C 盘用尽（APK 125 MB + 中间产物数 GB） | 工作区与缓存迁到 D 盘（可用 160 GB） |
| 5 | `pub get` `Rename failed ... errno = 17` | `PUB_CACHE` 用 junction 跨盘，rename 不能跨卷 | D 盘用**真实目录**，不用 junction |

## 4. 构建命令与结果

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:ANDROID_HOME="D:\code\android-sdk"; $env:ANDROID_SDK_ROOT="D:\code\android-sdk"
$env:GRADLE_USER_HOME="D:\code\gradlehome"; $env:PUB_CACHE="D:\code\pubcache"

cd D:\code\dshgo\app;            C:\dev\flutter\bin\flutter.bat pub get
cd D:\code\dshgo\app\android;    gradlew.bat :app:assembleArm64Debug
```

- **BUILD SUCCESSFUL in 5m 11s**，273 个任务级依赖全部解析成功（含 `jni` 的 CMake 构建）。
- 本仓库按 ABI 分 flavor，任务名是 `assembleArm64Debug` / `testArm64DebugUnitTest`，
  **不是** `assembleDebug` / `testDebugUnitTest`（后者会报 task not found）。

## 5. 产物

| 项 | 值 |
|---|---|
| 路径 | `app/build/app/outputs/flutter-apk/app-arm64-debug.apk` |
| 大小 | 125.9 MB（125,971,xxx 字节级） |
| SHA-256 | `5ce608df245f419225f533bf3586e943b933e6e5a3a9704cb1d985c4ea7d58e4` |
| 签名 | `CN=Android Debug, O=Android, C=US` |
| 证书 SHA-256 | `10cdb677f36a1f5b5a9762c190736b9fd64016876a378b9bdc9297bb5724b758` |

### 关于签名与安装（重要，未执行安装）

这是 **debug 签名**，由本机 `~/.android/debug.keystore` 生成。它**不能**覆盖手机上可能
已有的正式签名安装（签名不同 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。按要求：

- 本过程**没有连接、没有安装、没有卸载**任何真实手机应用；
- 若将来要装到已有 DSH Go 的手机上，应优先使用独立测试包名（`applicationIdSuffix`）
  或安全的数据迁移方案，**不得通过卸载现有 APP 解决签名冲突**。

## 6. 未验证的部分（NOT_TESTED）

- `runtime/build-runtime.sh` 的完整 rootfs 构建（需要一个 Linux 主机、Ubuntu base、
  proot/talloc/shmem deb；本机无法执行）。
- `runtime/test-host.sh` 的 proactive 端到端（需要 bwrap + x86_64 Linux）。
  代码已按 D4 接入，`rtbuild.py` 语法与 `proactive_drop_reason` 规则已本地验证。
- 所有真机行为（无障碍启动页面、闹钟送达、开机广播、强行停止恢复）见 VALIDATION_REPORT。
