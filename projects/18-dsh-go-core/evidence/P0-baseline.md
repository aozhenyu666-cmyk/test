# 阶段记录 · 基线（P0）

时间：2026-10-31
工作分支：feat/event-loop-v1（基线提交 10c3519 = 上游 d38a39a 源码；补丁提交 bc72be3）
工作副本：C:\work\dshgo（纯 ASCII 路径，Android 插件拒绝非 ASCII 路径）

## 环境
| 项 | 版本 / 路径 |
|---|---|
| Git | 2.55.0.windows.5 |
| Flutter | 3.47.7 stable（C:\dev\flutter），Dart 3.13.5 |
| JDK | Microsoft OpenJDK 21.0.12.1（JAVA_HOME 已设置） |
| Android SDK | D:\code\android-sdk，platforms 34/35/36，build-tools 36.0.0 |
| Gradle | wrapper 9.3.1（从 Flutter cache 补入 gradlew/gradlew.bat/gradle-wrapper.jar） |
| 结论 | 构建环境完整，**不需要** GitHub Actions；本机构建可用 |

## 环境踩坑（已解决，写进报告）
1. 上游仓库 331MB（其中 app/test/**/goldens 约 282MB）。git clone 反复 `RPC failed / early EOF`。
   → 改用 GitHub API 取 tree + curl 并行按文件下载（排除 goldens、site/home/dist），3300 个文件全部校验 size 一致。
2. 工作目录含中文 → AGP 报 `Your project path contains non-ASCII characters`。
   → 工作副本迁到 `C:\work\dshgo`。
3. Gradle test worker 报 `ClassNotFoundException: GradleWorkerMain`：classpath argfile 落在 `C:\Users\敖震宇\.gradle\.tmp`。
   → `GRADLE_USER_HOME=C:\work\gradlehome`（caches/wrapper 用 junction 复用旧缓存）。
4. 本仓库按 ABI 分 flavor，任务名是 `testArm64DebugUnitTest` / `assembleArm64Debug`，不是 `testDebugUnitTest`。

## 基线验证（补丁应用后、未改动）
| 检查 | 命令 | 结果 |
|---|---|---|
| 补丁应用 | `git am --3way 0001-event-loop-v1.patch` | PASS（干净应用，无冲突） |
| JVM 单测 | `gradlew :app:testArm64DebugUnitTest --tests "io.github.ccch1mneyyy.dsh_app.loop.*"` | PASS，17/17，0 失败 |
| Dart 静态检查 | `flutter analyze` | PASS，No issues found |
| APK 构建 | `gradlew :app:assembleArm64Debug` | PASS，产出 app-arm64-debug.apk 125.7MB |

## 下一步
三个关键缺陷修复（SHOWN 过早 / 无障碍异常无诊断 / 状态文件替换可靠性）。
