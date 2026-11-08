# 独立测试 APK 方案与风险清单

目的：让测试包能**与手机上新装的正式版 DSH Go 并存**，避免签名冲突（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），
且**不卸载、不覆盖**现有 APP。

**结论先行：不能只改包名就宣称可安装可用。** 本仓库的包名同时是 Kotlin 包、Provider authority
基础、无障碍服务组件名、Shizuku 授权主体和运行时隔离边界。下面逐项列出真实依赖、改动量、
哪些必须改、哪些会失效、以及验证方式。

---

## 1. 为什么不能只改包名

### 1.1 签名才是并存的前提，包名只是隔离手段

- 同一个 `applicationId` + 不同签名 → 系统直接拒绝安装（无法共存）。
- 换 `applicationId` → 系统视为另一个 App，可共存；但**用户的无障碍授权、通知授权、
  Shizuku 授权都要重新给**，这是必须事先告知的。
- 所以「换包名」是**先决条件之一**，不是充分条件：换了包名还要保证 App 自身没有硬编码
  原来的包名或原 APK 的路径。

### 1.2 本仓库把包名用在哪些地方（实测）

| 用途 | 位置 | 是否随 applicationId 自动改变 |
|---|---|---|
| Kotlin 包 / import | 450 处 `io.github.ccch1mneyyy.dsh_app` | ❌ 需要重构（或只改 `applicationId`，见 §2 方案 A） |
| Provider authority（Shizuku） | `AndroidManifest.xml:285` `android:authorities="${applicationId}.shizuku"` | ✅ 已参数化 |
| Provider authority（文件分享） | `AndroidManifest.xml:305` `"${applicationId}.files"` | ✅ 已参数化 |
| 无障碍服务组件名 | `PhoneA11yService.component(context)` 用 `context.packageName` | ✅ 自动 |
| 前景/通知/广播 action | `"${app.packageName}.DSH_PROACTIVE…"`、`".DSH_LOOP…"`、`.PHONE_PROMPT…` | ✅ 自动 |
| 运行时数据目录 | `context.filesDir`（rootfs、loop、proactive、pets） | ✅ 自动（`/data/data/<新包名>/`） |
| 原生库/rootfs 布局 | `android/app/src/main/jniLibs`、`runtime/out/.../assets` | ✅ 不涉及包名 |
| Shizuku 授权（用户侧） | 系统按包名+签名记录 | ⚠️ **需要重新授权** |
| 无障碍授权（用户侧） | 系统按组件名记录 | ⚠️ **需要重新开启** |

结论：**框架层面基本已经参数化**（authority、action、路径都走 `applicationId`/`packageName`），
真正的工作量和风险集中在「Kotlin 包名」和「用户授权需要重做」。

---

## 2. 两种可行方案

### 方案 A（推荐）：只改 `applicationId` + 应用名，不动 Kotlin 包名

```kotlin
// app/android/app/build.gradle.kts
defaultConfig {
    applicationId = "io.github.ccch1mneyyy.dsh_app.test"   // ← 只改这一处
}
```

- Kotlin `package` 与 `namespace` 保持 `io.github.ccch1mneyyy.dsh_app`：Android 允许
  `applicationId` 与 `namespace` 不同，这是官方支持的常规做法。
- `applicationIdSuffix = ".test"` 是等价写法，且 debug 构建常用。
- Provider authority、组件名、广播 action、`filesDir` 全部自动跟随 → **无需改代码**。
- 应用名改 `android:label="DSH Go 测试"`（Manifest 或资源），让用户一眼区分。

**改动量**：1 行 Gradle + 1 处 label。**风险**：低，但必须验证 §3 的每一项。

### 方案 B（不推荐）：连 Kotlin 包一起改名

- 451 个文件改包、`namespace` 改、`R` 引用改、Flutter 侧若有按包名判断的逻辑也要改。
- 收益几乎为零（方案 A 已达到同样的隔离效果），风险高（大面积机械改动易引入编译错误）。
- **除非以后要并行维护两个独立产品线，否则不要选 B。**

---

## 3. 必须逐项核实的依赖（方案 A 也要做）

| # | 依赖 | 为什么可能坏 | 验证方式 | 当前评估 |
|---|---|---|---|---|
| 1 | 无障碍服务组件名 | 系统按 `包名/类名` 记录授权；换包名后旧授权对新包无效 | 装测试包 → 系统设置里看到的是**两个** DSH 条目，需单独开启 | 预期正常，需真机确认 |
| 2 | Shizuku Provider authority | `${applicationId}.shizuku` 已参数化 | 装测试包 → Shizuku 里出现新条目 → 授权 | 预期正常 |
| 3 | 文件分享 FileProvider | `${applicationId}.files` 已参数化 | 触发一次「分享/导出」流程 | 预期正常 |
| 4 | rootfs / 运行时路径 | 都是 `context.filesDir`，随包名隔离 | 首启是否重新解压 rootfs（首次会慢、占空间） | 预期正常，但**会再占一份 ~几百 MB** |
| 5 | 前台的 `Activity`/`Service` | 均按类名，不依赖包名 | 启停一次任务 | 预期正常 |
| 6 | 通知渠道 `dsh-proactive` / `dsh-loop` / `dsh-loop` | 渠道 id 是字符串，系统按 `包名+渠道id` 存 | 换包后是**全新渠道**，用户需重新授予通知权限 | 预期正常 |
| 7 | 闹钟 / 开机广播 | `WakeAlarm` + `BOOT_COMPLETED` 接收器按包名注册 | 换包后需重新允许自启动/后台 | 预期正常 |
| 8 | Flutter 侧 `SharedPreferences` | 按包名隔离 | 两包各自一份配置 | 预期正常 |
| 9 | 引擎 runtime 与插件 | rootfs 内 `/opt/dsh-go/**` 是**相对路径**，每个 App 一份 `filesDir` | 检查 `manifest.json` 里是否有绝对路径写死 | **需人工核对 manifest 生成逻辑** |
| 10 | 更新检查（beta 渠道） | 可能按包名或渠道判断 | 测试包不该触发正式版更新 | **需确认**，否则会提示升级正式版 |

**第 9、10 项是本文档要求"不要只改包名就宣称可用"的重点**：rootfs 是在首次启动时按
`runtime/out/<abi>/manifest.json` 解压的，若 manifest 或解压代码里有任何**绝对路径或原包名**，
测试包会读到正式包的数据或写入失败。这一点必须实测，不能推断。

---

## 4. 风险清单

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 用户以为换包名 = 数据迁移 | 测试包里没有原配置，看起来"丢了数据" | 明确告知：**两包数据完全独立**，测试包是新装 |
| R2 | 无障碍 / Shizuku / 通知 / 自启动 全部要重新授权 | 用户以为功能坏了 | 首次启动给一份"测试包需要重新授权"的说明 |
| R3 | 两个 DSH 的无障碍服务同时开启 | 双份事件监听、双份弹窗、两份 rootfs 抢 CPU | 说明：**测测试包时把正式版的无障碍关掉**（或反过来） |
| R4 | 磁盘占用翻倍 | rootfs 再解压一份 | 说明占用；低端机可能不够 |
| R5 | 测试包触发正式版更新 | 用户被引导升级/覆盖 | 关掉测试包的更新检查，或明确不打包 beta token |
| R6 | 测试包与正式包共享同一个「目标会话」 | 两个包各自连本机引擎、各自的配置 | 预期独立；需确认引擎侧无单例冲突 |
| R7 | 推送/商标/包名策略 | 上架时不能带 `.test` | 测试包只用于自测，不进商店 |
| R8 | 真机验证缺失 | 以上全部是**分析**，不是实测 | 见 §5 |

---

## 5. 验证计划（本机无法执行的部分如实标注）

| 步骤 | 状态 |
|---|---|
| 改 Gradle（`applicationIdSuffix = ".test"`）+ label | 未做（按指令本轮只出方案，不改代码） |
| `assembleArm64Debug` 构建测试包 | 未做 |
| `aapt2 dump packagename` 确认包名 | 未做 |
| `apksigner verify` 确认与正式包**不同**签名 | 未做 |
| 真机并存安装、无障碍授权、Shizuku 授权、通知、rootfs 解压、更新检查不误触发 | **NOT_TESTED**（不连接用户手机） |

**要出这个包，需要你明确授权。** 我不会在未得到许可前改动 `applicationId`，
更不会安装到任何设备。

---

## 6. 给决策者的最短可行路径

1. 我改 1 行 Gradle（`applicationIdSuffix = ".test"`）+ 1 处 label；
2. 本机构建，产出 `dsh-go-arm64-debug-test.apk`，报告新包名与签名指纹；
3. **你自己**安装到手机（我不碰设备），先确认：两个图标并存、无障碍里两个条目、
   首次解压完成、能开一次「触发测试」；
4. 若 §3 第 9/10 项有问题，再决定是否要改 rootfs manifest 的解压逻辑（那才是真正需要动代码的部分）。
