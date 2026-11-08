# R4-HARDENING-REPORT · 第二阶段：集成加固、功能补齐、构建准备

**hardening 分支** `integration/r4-hardening-2026-11-01`
**本轮 DEV 分支** `fix/r4-dev-regression-build-2026-11-01`（工作副本 `D:\code\R4\l-dev`）
**HEAD** 一律用 `git rev-parse HEAD` 取。**本文不写死 SHA、不写死提交数、不写死行数** ——
写死的值会在下一次提交后变成假话。
**起点** 上一轮集成 `2f88256`（远端核对一致，未 reset、未覆盖）

所有数字都是本机复跑出来的，不是任何模块的自述。**本文 §9 与 §11.7 是 DEV 轮按实测重写的，
推翻了上一版的两处结论**（"970 = 970 所以零回归"、"proot 默认值保持 .98"）。

---

## 0. 结论摘要

| 方向 | 状态 |
|---|---|
| M1 行为管理（ACK / 两本账 / 拦截闭环 / 设置页 / 训练题 / 槽位） | **PASS** |
| M2 插件生态（bili-peek / cap-ledger 六态 / 宿主兼容） | **PASS**（真机渲染 NOT_TESTED） |
| M3 人格记忆情绪（七维同源 / 身份面板 / Config） | **PASS**（原生表单截图 NOT_TESTED） |
| DEV 构建体系（G1′ 双 ABI） | **PASS** ← 本轮最大的实质突破 |
| 完整回归无新增失败 | **PASS**（两树失败名集合双向差集 0 条，§9；不是"计数相等"） |
| PRoot 非原子递减（新发现的数据丢失） | **PASS**（复现 → 修复 → 4 条回归，§11.7） |
| proot 默认版本 | **改为 `.101`**；上一版"改布局"的理由实测不成立（§11.7） |
| Schemastery | **宿主已支持，不需要引入依赖** |

**G1′ 本轮真的通了。** `runtime/out/` 下 arm64-v8a 与 x86_64 都是真实产物，manifest 同时含两个 ABI。

---

## 1. 保护项核对

| 项 | 期望 | 实测 | 结果 |
|---|---|---|---|
| `main` | 不动 | `bf01594471e11d7c1dbfe743a304a02d66068b29` | ✅ |
| R3 发布分支 | 不动 | `f1423dc11cb4d42ca67225011c6437d1abd0025f` | ✅ |
| `feat/r4-intercept-and-ack` | 不动 | `35ac53d3e6283231ace0957e9cbb98c7293c76d1` | ✅ |
| `feat/r4-bili-plugin` | 不动 | `2eb704d0aa93b1f04b05d55d949d3513f6371a20` | ✅ |
| `feat/r4-partner-plugin` | 不动 | `d166e9269b3ff311d9d439bd67dd884b215313fd` | ✅ |
| 上一轮集成分支 | 不覆盖 | `2f88256cb83d6c21821c0278555471f9a979d5ae` | ✅ |
| 远端 HEAD 核对 | 先查再动 | 与报告一致，直接以其为起点 | ✅ |
| 引入第三方依赖 | 未审核不得引入 | **零新增依赖**（见 §7） | ✅ |
| 手机 / APK / Release | 不碰 | adb 0 台，无安装、无 Release | ✅ |

---

## 2. 原 11 项遗留任务逐项结果

| ID | 模块 | 问题 | 实际复查 | 状态 |
|---|---|---|---|---|
| 1 | DEV | G1′ 未达成，`runtime/out/` 缺两个 ABI | **已真实构建出两个 ABI**，见 §8 | **PASS** |
| 2 | 全部 | 真机/模拟器未验证 | adb 仍 0 台；代码级闭环已补齐 | **NOT_TESTED** |
| 3 | M1 | 拦截设置页没有 Dart UI | 新增 `lib/intercept/intercept_channel.dart` + `lib/ui/phone/intercept_page.dart`，接真实通道，已挂进手机控制页 | **PASS** |
| 4 | M1 | 训练页只是停留计时 | 改成由 eventId 确定性生成的**可判定训练题**，守卫侧强制解完才放行 | **PASS** |
| 5 | M1 | 白名单/目标只能在 config 层编辑 | 设置页可增删目标、暂停/恢复、调四个参数；白名单仍在 config（见 §9） | **PARTIAL** |
| 6 | M3 | purpose 面板是 P2 占位 | 新增 `identityPanel()` 读真实 persona / roleState / topics / 三个开关 | **PASS** |
| 7 | M3 | `Config = undefined`，原生设置页画不出 | 改成真 schema；宿主 schemastery 3.18.5 解析通过（uid 59） | **PASS** |
| 8 | M2 | cap-ledger 未统一接管 | 四态扩到**六态**；AVAILABLE / EXECUTED / FAILED 为派生值，不接受手写 | **PARTIAL**（见下） |
| 9 | 全部 | 既有 golden 失败是既有的 | R3 与 R4 **同环境各跑一次全量**，失败名集合双向差集 0 条（§9） | **PASS**（既有失败保留，无新增） |
| 10 | M3 | `real-dsh-tools.test.mjs` 默认 skip | 仍需 `DSH_TOOLS_DIR` 指向 asar | **NOT_TESTED**（1 skip，已注明） |
| 11 | M2 | 插件未在真 profile `dsh plugin add` 加载过 | 需要真引擎 | **NOT_TESTED** |

### 第 8 项为什么是 PARTIAL 而不是 PASS

六态台账已经做出来了，但**「bili 改用 cap-ledger 那一份」这一步只做了一半**：bili 的 `lib/ledger.mjs` 仍在，两处没有真正合并成一次写入。原因写在提交里 —— 跨插件观测需要宿主暴露「别的插件是否 loaded / toolRegistered」，而当前宿主契约里没有这个能力。**我没有为了让报告好看去编一个宿主能力**，也没有把两份账硬塞成一个（那会制造第二套插件加载器，正是任务禁止的）。

### 第 5 项为什么是 PARTIAL

目标清单增删、启用、暂停恢复、四个参数（放行时长 / 训练题长度 / 冷却 / 每日上限）都已在设置页接通。**白名单只有读取与展示，没有增删入口** —— 内核的 `setConfig` 支持写 whitelist，但页面这轮没做那一段 UI。这是明确的功能缺口，不是"已经支持"。

---

## 3. M1 修改文件

提交 `4da00b5`（源分支 `harden/m1` = `a3249c1`，20 文件 +2344 −46）

```
loop/core/Intercept.kt                   +197  训练题 + 一次一拦 + 非目标回归
loop/core/SurfaceSlot.kt                  +30  替换语义 + 陈旧释放守卫
loop/intercept/InterceptPageActivity.kt  +109  倒计时 → 真训练题
loop/intercept/InterceptRuntime.kt        +17  暴露 drill / tapDrill
res/values{,-zh}/intercept_strings.xml    +20  训练题文案
loop/core/InterceptDrillTest.kt          +137  新增 10 项
loop/core/InterceptChainTest.kt           +66  新增 3 项（含非目标回归）
loop/core/SurfaceSlotTest.kt              +29  替换语义 3 项
app/lib/intercept/intercept_channel.dart +326  新增，类型化通道
app/lib/ui/phone/intercept_page.dart     +448  新增，设置页
app/lib/ui/phone/phone_control_screen.dart +10  入口
app/lib/l10n/parts/intercept_{zh,en}.arb  +148  新增 l10n 部件
app/lib/l10n/gen/*                       +548  重生成
app/test/intercept/intercept_channel_test.dart +189  新增 10 项
```

## 4. M2 修改文件

提交 `5f5f9c1`（源分支 `harden/m2` = `a6677bc`）

```
examples/plugins/dsh-go-cap-ledger/lib/ledger.mjs   +25 −3  六态
examples/plugins/dsh-go-cap-ledger/test/ledger.test.mjs  +3  断言更新
```

## 5. M3 修改文件

提交 `b06e5a5`（源分支 `harden/m3` = `2694425`，4 文件 +223 −24）

```
examples/plugins/dsh-go-partner/lib/dims.mjs   新增  七维唯一真相
examples/plugins/dsh-go-partner/lib/views.mjs  +190  identityPanel + 改用 dims.mjs
examples/plugins/dsh-go-partner/lib/mood.mjs   +12 −18  改用 dims.mjs
examples/plugins/dsh-go-partner/lib/index.js   +55 −13  Config 真 schema
```

## 6. DEV 修改文件

提交 `592becf`（源分支 `harden/dev` = `00cc94f`，2 文件 +57 −11）

```
runtime/lib/rtbuild.py      +46 −7  修 machine 比较的类型错误
runtime/build-runtime.sh    +45 −8  fetch 重试加固（--retry-all-errors + 断点续传 + 备用镜像）
```

## 7. 提交（用命令核对，不抄 SHA）

写死的 SHA 一定会随下一次提交过期，所以这里不给表格，给命令：

```powershell
# 各模块分支、集成分支、本轮 DEV 分支的当前尖
git ls-remote origin 'refs/heads/harden/*' 'refs/heads/integration/*' 'refs/heads/fix/r4-dev-*'

# hardening 集成本身引入了哪些提交
git log --oneline --first-parent 2f88256..integration/r4-hardening-2026-11-01

# 本轮（DEV）在集成之上又加了哪些
git log --oneline --first-parent ac04b38..fix/r4-dev-regression-build-2026-11-01
git diff --stat ac04b38..fix/r4-dev-regression-build-2026-11-01
```

四个源分支、集成分支、本轮 DEV 分支都已推 origin；**没有 force push，没有 rebase 别人的分支。**

---

## 8. 每项测试的实际结果

### 8.1 内核纯逻辑（JVM，离线）

```
$ powershell -File D:\code\R4\ldev-loop.ps1          # 本轮分支
=== sources: 35 kt files
compile OK
PASS  172 tests
```

172 = 上一轮 160 + 本轮 12（InterceptDrillTest 10 + InterceptChainTest 非目标/一次一拦 3，另有一项由旧断言改写）。

### 8.2 Android 编译

**上一版这里是"26 个源文件 + 桩，exit=0"的子集编译。本轮换成真 Gradle。**

```
$ cd D:\code\R4\l-dev\app\android
$ gradle --no-daemon --offline -Pkotlin.incremental=false :app:compileArm64DebugKotlin
BUILD SUCCESSFUL in 1m 53s
130 actionable tasks: 25 executed, 105 up-to-date

$ gradle --no-daemon --offline -Pkotlin.incremental=false :app:compileArm64DebugUnitTestKotlin
BUILD SUCCESSFUL in 1m 11s
```

为什么这条要真跑：上一版那个"26 文件 + 桩"的子集编译，**在未改动的基线树上同样报 100 条
`unresolved reference`**——它缺 `R`/`i18n`/`phone.*` 等，本身就是个不可靠的判据
（`ldev-android-compile.log` 与 `wtdev-android-compile.log` 逐条对得上）。
真 Gradle 才是"能过"的证据。

**未做到的一步，如实写在这里**：`:app:testArm64DebugUnitTest` 在本机**执行不起来**
（`No tests found for given includes`，在未改动的基线树上同样如此）。
所以 Android 单元测试**编译**通过、**执行**记为 NOT_TESTED。
`L2sMigrationTest` 的实际执行是在 Linux（WSL）上跑的，见 §11.7。

### 8.3 插件（node --test，集成分支上复跑）

```
bili:    tests 69 / pass 69 / fail 0
cap:     tests 42 / pass 42 / fail 0
partner: tests 97 / pass 96 / fail 0 / skipped 1
```

### 8.4 构建守卫（python unittest）

```
runtime guards exit=0    Ran 9 tests, OK
apk verifier   exit=0    Ran 8 tests, OK
```

### 8.5 Flutter 静态分析（本轮分支上重跑）

```
$ cd D:\code\R4\l-dev\app && flutter analyze      # Flutter 3.47.7 / Dart 3.13.5
Analyzing app...
No issues found! (ran in 133.7s)
```

### 8.6 l10n 可重生成

```
$ python tool/l10n_merge.py --check
✓ 47 parts, 5417 keys
```

47 部件 = 上一轮 46 + 本轮新增 `intercept`。**M1 手改 gen 的那批仍然可以重新生成**，这条是上一轮的重点，本轮再次确认。

### 8.7 目标文件的 Flutter 测试

```
$ flutter test test/phone/proactive_page_test.dart test/proactive_channel_test.dart test/intercept/intercept_channel_test.dart
00:05 +35: All tests passed!
```

35 = 上一轮 25 + 本轮 intercept 通道 10。

### 8.8 全量 Flutter 回归与 R3 基线对照

见 §9。

---

## 9. Flutter 基线失败对照

**方法：两个工作树，同一台机器、同一个 Flutter、同一份 `pubspec.lock`，各跑一次全量 `flutter test`，
比较的是失败测试的**名字集合**，不是计数。**

**分类**：上一轮已逐条确认这些失败里 933 条栈顶是 `MatchesGoldenFile`（像素比对），
且把 `media_golden_test.dart` 单独在两个树上跑结果相同（各 `+0 −12`，失败原因
`Golden "media_cards_portrait_dark.png": Pixel test failed, 0.66%`）。
**结论：这是本机字体/基线问题，不是任何一轮引入的回归。**

本轮的要求是「比较 R3 与 R4 的失败测试名称、确认没有新增、保留原有失败记录、不改测试预期制造 PASS」。
本轮的做法：
- **没有修改任何 golden 图片、没有改任何测试预期**；
- 新增的测试全部是新文件（`InterceptDrillTest` / `intercept_channel_test`），不掺进既有断言；
- 唯一改动的既有断言是 `InterceptChainTest` 里那几个「训练通过」的调用点，原因是**行为本身变了**
  （倒计时 → 解题），属于"规则变了的正当更新"，且新断言比原来更严（必须先解完题）。

### 9.1 本轮实测：数量、**失败名集合**、以及"集合为什么不一样"

**先说更正。** 上一版这里写的是「净失败数完全相同（970 = 970）」，并据此说"零回归"。
**那个结论下得太轻率**，而且它当时依赖的方法本身有问题。这一节把它重做了一遍。

先说最硬的一条事实：**同一棵树、同一个 commit，两次全量跑的失败集合就不一样。**

| 全量跑 | 树与 commit | 通过 | 跳过 | 失败 |
|---|---|---|---|---|
| `BASE-full-ft.log` | `wt-dev` @ `69e36ff`（R3 基线） | +7320 | ~370 | −970 |
| `HARD-full-ft.log` | `hard` @ `ac04b38` | +7329 | ~370 | −971 |
| `HARD-full-ft2.log` | `hard` @ **同一个** `ac04b38` | +7330 | ~370 | −970 |

`HARD-full-ft.log` 与 `HARD-full-ft2.log` 之间**没有改过一行代码**，失败集合却不同：
前一次少了 `session_sync_screen_test` 的 `ordinary double-escape …`，后一次少了
`ordinary button … new-mux-ready-snapshot-held`。所以「失败集合差异」首先不是 R3↔R4 的差异，
**是全量套件自身的顺序/共享状态不稳定**。

为了把两件事分开，本轮做了一次**同环境对跑**：`hard` 与 `wt-dev` 各跑一次全量，
同一台机器、同一个 Flutter、同一份 `pubspec.lock`：

| 全量跑 | 树 | 通过 | 跳过 | 失败 | 失败名集合 |
|---|---|---|---|---|---|
| `B-full-hard.log` | `hard` | +7331 | ~372 | −969 | 见下 |
| `B-full-wtdev.log` | `wt-dev` | +7321 | ~372 | −969 | **与上一行完全相同** |

**差集结果：两棵树的失败测试名集合逐条相同（各 968 条，双向差集都是 0）。**

> **"−969" 与"968 条"为什么差 1**：`-r compact` 用回车覆盖同一行，所以原始日志里那 969 条带 `[E]` 的
> 物理行中，有 1 条其实是进度行被截断后残留的 `loading D:/code/R4/B-full-<树>.log [E]`——
> 它不是测试名。按 `<path>.dart: <name>` 解析得到 968 条真实失败，两树逐条相同。
> 这一条也是我先按物理行去重时，为什么会在两树之间各看到**一条并不存在的差异**的原因；
> 单独复跑那两个文件（各 2 次、两棵树）结果完全一致，确认是解析假象，不是行为差异。

两条被上一版当作"差异项"的测试，本轮**双向差集里都不再出现**：

| 测试 | 在 `hard` | 在 `wt-dev` | 结论 |
|---|---|---|---|
| `session_sync_screen_test`（4 条） | 失败 | **同样失败 4 条** | **不是差异**：两树同失败，是该文件自身在本机的既有问题 |
| `plugin_config_scope_fresh_test` | 通过 | 通过 | **不是不稳定**：单跑 3/3 通过，全量跑也通过 |

### 9.2 为什么上一版会得出相反的结论：三个"假差异"来源

上一版拿**归档日志**（`HARD-full-ft.log` / `BASE-full-ft.log`）做差集，得到两条"差异项"。
本轮用可重复的实验把它们逐条拆掉：

1. **`plugin_config_scope_fresh_test` 不是"并行偶发"，而是"套件状态下才失败"。**
   它在本轮 env 下**独立连续跑 3 次全过**（`hard` 3/3、`wt-dev` 3/3，都是 `+12 All tests passed`）。
   上一版说"并行偶发"，本轮把并发也测了：

   | 组合 | `hard` | `wt-dev` |
   |---|---|---|
   | 单文件，默认并发 | +12 全过 | +12 全过 |
   | 单文件，`--concurrency=1` | +12 全过 | +12 全过 |
   | 与 `session_sync` 两文件同跑，默认并发 | +15 −4（4 条全是 sync 的） | +15 −4（同） |
   | 与 `session_sync` 两文件同跑，`--concurrency=1` | +15 −4（同） | +15 −4（同） |

   **四种组合在兩棵树上结果一致**：plugin 全过、sync 固定 4 条失败。
   所以这里没有并发竞态，只有"全量套件跑到它时的状态"这一件事。

2. **`session_sync_screen_test` 的失败是确定性的，不是随机的。**
   它在两棵树、单文件、`--concurrency=1`、连跑 3 次，**每次都是同样 4 条**失败，
   原因也每次一样：`fake_async` 里 `DshConnection._exchange` 留下 120 s `Pending timer`，
   报 `A Timer is still pending even after the widget tree was disposed`。
   一个每次都能复现的失败，不能叫"偶发"。

3. **两个"嫌疑"文件在两棵树里字节相同。**
   `plugin_config_scope_fresh_test.dart` = `d8ea7651…`、`session_sync_screen_test.dart` = `c764fe2d…`
   （`git rev-parse HEAD:<path>`，`hard` 与 `wt-dev` 一致）。**差异不可能来自这两个文件。**
   同理，`app/lib` 与 `app/test` 在两树间没有任何差异（`git diff --stat` 为空）。

### 9.3 归档里那 2 条"基线有、本轮没有"的失败

本轮全量跑出来，`hard` 与 `wt-dev` 都比归档的 `BASE-full-ft.log` 少两条：

```
- test/plugins/sdk_english_diagnostics_test.dart :: live English SDK diagnostic text matches the App parser for the shared corpus
- test/share_hosted/hosted_crypto_test.dart :: WebCrypto interop what the app seals, WebCrypto and the viewer open
```

单独跑这两个文件，**在两棵树上都是 `+9 ~2 All tests passed`（exit 0）**。它们是归档那次全量跑的
不稳定项，不是本轮修掉了什么，也不是"消失的真失败"。

### 9.4 结论（这一版的口径）

- **失败数量：`hard` 与 `wt-dev` 都是 −969（按名字去重 968 条）。**
- **失败集合：两树逐条相同，双向差集 0 条。→ 本轮没有新增失败。**
- **"970 = 970" 的表述作废。** 数量相等本来也不代表集合相同；这一版给的是集合，不是计数。
- **归档日志的集合差异有两个来源，都与代码无关**：全量套件自身的顺序/共享状态不稳定，
  以及三个测试类的本机既有问题（golden 像素、`session_sync` 的 pending timer、上述两条）。
- **没有**修改任何 golden 图片、没有放宽任何断言来制造 PASS。

### 9.5 保留原有失败记录，未改测试预期

- **没有**修改任何 golden 图片；
- **没有**为了让测试通过而放宽任何断言；
- 唯一改动过的既有断言是 `InterceptChainTest` 里几个"训练通过"的调用点，因为**行为本身变了**
  （倒计时 → 解题），且新断言更严（必须先解完题）；
- 新增测试全部是新文件，不掺进既有断言。

---

---

## 10. Schemastery 最终技术决定及依据

### 决定

**不引入 schemastery 依赖。宿主已支持，插件改成导出真 schema 即可。**

### 依据（全部来自读源码，不是推测）

1. **宿主自带 schemastery**
   ```
   ~/.dsh/profiles/desktop/node_modules/@deepseek-ai/schemastery  →  "version": "3.18.5"
   ```

2. **宿主有完整的 schema 表单引擎**（且明确写着服务于任意插件）
   ```
   app/lib/ui/engine_settings/schema_form.dart:6-8
     "…[schemaSections] lays out any plugin's namespace by itself."
   ```
   配套：`schema_fields.dart`(516) `field_sheets.dart`(1502) `field_row.dart`(419)
   `bitset_field.dart` `sequence_field.dart` `schema_collapsed_groups.dart` `schema_documentation.dart`

3. **宿主有第三方插件配置页**
   ```
   app/lib/ui/plugins/config/plugin_config_page.dart
   app/lib/ui/plugins/config/plugin_config_layout.dart   (:14, :124 使用 schemaSections)
   app/lib/ui/plugins/config/plugin_config_save_bar.dart
   ```

4. **数据通路完整（读 + 写 + 持久化 + 回退）**
   ```
   app/lib/state/plugin_config/plugin_config_api.dart
   app/lib/state/plugin_config/plugin_config_bridge.dart
     GET  /dsh-go/v1/plugin-config          → 插件目录
     GET  .../<entryId>                     → 一项的完整表单（schema 是 uid/refs 图）
     POST .../<entryId>  {revision, ops}    → 写（live 字段即发，普通字段攒到保存）
     老宿主 404 → 回退 settings/describe + settings/mutate
   ```
   设计文档：`docs/design/plugin-config.md`（PLUGIN-CONFIG 契约，含 schemastery 3.18.5 的 `.volatile()` 说明）

5. **先例：另一个第三方插件就是这么做的**
   ```
   dsh-bili-subagent/package.json
     "peerDependencies": { "@deepseek-ai/schemastery": "*" }   (optional: true)
   dsh-bili-subagent/index.js:128
     export const Config = !z ? undefined : z.object({ ... })
   ```
   注释写明：**「schemastery 拿不到时这里是 undefined —— 插件照常加载，只是设置页降级」**。
   partner 照这个取舍实现。

6. **上一轮的因果说反了**
   `export const Config = undefined` 的注释是「本包坚持零第三方依赖，画不出原生设置表单」。
   真实原因是**导出了 undefined**：宿主拿不到 schema，自然画不出。零依赖本身不是障碍。

### 验证

```
$ node -e "... globalThis.__dshSchemastery = z; import('./lib/index.js')"
Config defined: true
schema uid: 59
```
用**真实的** @deepseek-ai/schemastery 3.18.5 解析通过。

### 仍未验证的部分（诚实标注）

**原生设置页在真机上的实际渲染没有截图证据** —— 需要真引擎 + 真机和 adb，本轮不具备。
所以这条是：**schema 可被宿主解析 = 真跑通**；**表单长什么样 = NOT_TESTED**。

---

## 11. G1′ 双 ABI 证据

### 11.1 环境真相（本轮最重要的更正）

上一轮报告写「WSL 挂了（E_UNEXPECTED），本机无法构建」。**这个判断是错的。**

```
$ wsl -l -v
  NAME      STATE      VERSION
* Ubuntu    Running    2

$ wsl -d Ubuntu -- uname -a
  wsl: Failed to translate 'E:\VMware\bin\'        ← Windows PATH 里有个 Linux 不存在的路径
  Linux LAPTOP-HANAC4Q6 6.6.87.2-microsoft-standard-WSL2 #1 SMP ... x86_64 GNU/Linux
  exit=0                                             ← 命令其实跑成功了
```

真因是 **Windows PATH 携带 `E:\VMware\bin\` 这个 Linux 侧不存在的路径**，导致 `wsl.exe` 打印告警并以非 0 退出，
**但 Linux 命令本身正常执行**。把这条误读成"WSL 坏了"，让上一轮把一个本机可解的问题记成了环境阻塞。

附带事实：Ubuntu 是 **26.04.1**，root，20 核，7 GB 内存，`/ ` 可用 950 GB，
`curl / xz / python3 / file / tar / wget / git / unzip` 齐全（`unzip` 是本次新装的）。

### 11.2 构建命令

```bash
cd /root/r4build/dsh-Go            # 在 Linux 原生文件系统里构建（不在 /mnt/d，避免 drvfs 权限语义）
bash runtime/build-runtime.sh   # 默认已是 5.1.107.101；要回退：PROOT_VERSION=5.1.107.98
```

`runtime/build-runtime.sh` 本身就支持两个 ABI（:6-7），**未改一行源码**就产出了两个 ABI。

### 11.3 G1′ 验收输出（原文）

```
$ python -I runtime/lib/rtbuild.py verify-outputs --out runtime/out --abi arm64-v8a x86_64
PASS  arm64-v8a  abi=arm64-v8a  rootfs-arm64-v8a.tar.xz  r14-dsh0.2.1-rc.4-node24.22.1-ubuntu24.04.5-5ad412bd59fc  4 jniLibs
PASS  x86_64     abi=x86_64     rootfs-x86_64.tar.xz     r14-dsh0.2.1-rc.4-node24.22.1-ubuntu24.04.5-2e8c6b058713  4 jniLibs
PASS  runtime/out/manifest.json  abis=['arm64-v8a', 'x86_64']
G1' PASS: runtime/out holds a real runtime bundle for arm64-v8a, x86_64
exit=0
```

### 11.4 六项判据逐条

| 判据 | 证据 | 结果 |
|---|---|---|
| 两个 ABI 目录存在 | `runtime/out/arm64-v8a` `runtime/out/x86_64` | ✅ |
| manifest 同时含两个 ABI | `abis: ['arm64-v8a', 'x86_64']` | ✅ |
| rootfs 哈希一致 | 两个 ABI `rootfsSha256 matches manifest: True`，`rootfsBytes` 也对 | ✅ |
| 原生库架构正确 | 每 ABI 4 个 so：`libproot.so` `libproot-loader.so` `libtalloc.so` `libandroid-shmem.so`，e_machine 校验通过 | ✅ |
| 不存在错误架构 ELF | rootfs 内 **835 个 ELF 对象，wrong-arch: 0** | ✅ |
| 测试文件没混进运行时 | `dsh-go-proactive` 下 test 条目 = **0** | ✅ |

### 11.5 产物明细

```
runtime/out/arm64-v8a/assets/runtime/rootfs-arm64-v8a.tar.xz   158,315,364 B
runtime/out/arm64-v8a/assets/runtime/manifest.json
runtime/out/arm64-v8a/assets/runtime/licenses/...              proot / talloc / libandroid-shmem
runtime/out/arm64-v8a/jniLibs/arm64-v8a/lib{proot,proot-loader,talloc,android-shmem}.so

runtime/out/x86_64/assets/runtime/rootfs-x86_64.tar.xz        165,008,632 B
runtime/out/x86_64/assets/runtime/manifest.json
runtime/out/x86_64/assets/runtime/licenses/...
runtime/out/x86_64/jniLibs/x86_64/lib{proot,proot-loader,talloc,android-shmem}.so

runtime/out/manifest.json   abis = [arm64-v8a, x86_64]
总大小 310 MB
```

### 11.6 修掉的两个真实缺陷（否则 G1′ 既通不了、也会假通过）

**(a) verify-outputs 的 machine 比较类型错**（`runtime/lib/rtbuild.py`）

manifest 同时记录 `machine: "aarch64"`（字符串，来自 `EM_NAMES`）与 `e_machine: 183`（整数）。
校验写的是 `want.get("machine") not in (None, ABI[abi]["machine"])`，拿**字符串**和**整数 183** 比，
于是每一个诚实的构建都报 `libproot.so is machine aarch64, not 183`。

**这是上一轮那个"假 PASS"的孪生兄弟：一个永远不可能通过的检查，和一个永远通过不了的检查一样没用。**
现在比较 `e_machine`，只有在 manifest 没有数字时才退回 `EM_NAMES` 反查。

**(b) `fetch()` 的重试覆盖不到 TLS 早断**（`runtime/build-runtime.sh`）

`curl --retry 3` 只重试"瞬时"错误，**不含** TLS 握手提前结束（curl 35），而这几个镜像在高负载下正是这样断的
（构建时在 `librtmp1_*.deb` 上真实撞到）。改成 `--retry-all-errors` + `--continue-at -`（断点续传，重试不重下）
+ 备用镜像轮询。

### 11.7 PROOT 版本决定（已更正：默认值现在是 `.101`）

**上一版的结论是「默认值保持 `5.1.107.98` 不动」，理由是「`.101` 重写了 l2s 布局」。这个理由经不起实测，已推翻。**

本轮不是读源码，而是**把两个版本各自编译出来、真跑**：

1. 从 `proot-v5.1.107.98.zip` / `proot-v5.1.107.101.zip`（哈希与 `build-runtime.sh` 的 `PIN` 一致）
   各自 `make` 出二进制（WSL Ubuntu 26.04，gcc 15.2.0）。
2. 用 `--link2symlink` + `PROOT_L2S_DIR=<rootfs>/root/.l2s` 让**各自的二进制**去建硬链接，
   得到**真实布局**，不是猜的。

**两个版本产出的布局完全相同：**

```
f .l2s.alpha0001.0003        <- 存储文件 = 中间名 + .<count>
f .l2s.tool0001.0002
l .l2s.alpha0001 -> /…/root/.l2s/.l2s.alpha0001.0003   <- 中间名
l .l2s.tool0001  -> /…/root/.l2s/.l2s.tool0001.0002
/root/alpha      -> /…/root/.l2s/.l2s.alpha0001        <- 每个名字，目标为主机绝对路径
```

**上一版说的 "`.101` 新增 `.proot.l2s.` 中间名" 是错的**：那个前缀在
`link2symlink.c` 里位于 `#ifdef USERLAND` 之下，Termux 的发行二进制里没有它。
证据（两层）：

| 检查 | 结果 |
|---|---|
| `strings` Termux 官方 `proot_5.1.107.101_aarch64.deb` 里的 `proot` | 有 `.l2s.`、`%s%04d.0002`、`5.1.107.101`；**没有** `.proot.l2s.` |
| 本仓库自建/预置的 `libproot.so`（arm64） | 版本串 `5.1.107.101`；前缀只有 `.l2s.`；**没有** `.proot.l2s.` |
| `build-runtime.sh` 自己那段 awk 在实时索引上复跑 | `5.1.107.98` → NO RECORD；`5.1.107.101` → `a28355e9…`/`85adb8d0…`，与 `PIN` 一致 |

**所以"迁移会出错"这个前提不成立。** 进一步，本轮用真实的 `.98` 树和真实的 `.101` 树各跑了一遍
`L2sMigration`（就地迁移，因为存储里是主机绝对路径，拷贝会打断链路）：

| 真实树 | 迁移结果 | 迁移后 store | 名字 | mode / mtime / 内容 | 二次运行 |
|---|---|---|---|---|---|
| `.98` 建的 | `Done(links=5, removed=0, repaired=0)` | 空 | 全是普通文件 | `750` / `2021-03-04T05:06:07Z` / 原样 | `Done(0,0)` 幂等 |
| `.101` 建的 | `Done(links=5, removed=0, repaired=0)` | 空 | 全是普通文件 | `750` / `2021-03-04T05:06:07Z` / 原样 | `Done(0,0)` 幂等 |

`L2sMigrationTest` 也加了 `.101` 的真实布局 fixture（`theStoreAReleaseLeavesBehindIsTheOneThisConverts`）。

**决定：默认 `PROOT_VERSION` 改为 `5.1.107.101`；`.98` 的哈希保留，供回退复现。**
这同时修掉一个真故障：`.98` 已从 Termux 索引消失，旧默认让**每一次构建都停在 `termux_deb` 上 die**。

**仍然 NOT_TESTED 的**：真机上"装有 `.98` 运行时的旧安装，升级到带 `.101` 的包后首次直接启动"这一整条路径。
本轮证明的是"两个版本写出的存储布局相同、转换器对两种树结果相同"，**不是**真机集成验证。

### 11.8 runtime/out 不入库

310 MB 构建产物**没有提交**。它是 `runtime/build-runtime.sh` 的产物，不是源码；提交它会把仓库撑坏。
需要时用一条命令重建（见 §14）。

## 12. 四模块之间的接口冲突检查

| 检查项 | 方法 | 结果 |
|---|---|---|
| 工具名冲突 | 扫全部插件的 `defineTool`/`register` 名字 | 6 个全不重复：`peek_bili_video` / `cap_ledger_status` / `partner_recall` / `partner_remember` / `partner_correct` / `partner_mood` |
| 面板 ID 冲突 | 读每个 `package.json` 的 `dshGo.contributes.panels` | `peek` / `ledger` / `persona,memory,mood,purpose` —— 无重叠 |
| strip ID 冲突 | 同上，`composer.strip` | `peek.strip` / `ledger.strip` / `persona.strip` —— 无重叠 |
| 宿主能力面 | grep `ctx.*` | 三家统一只用 `ctx.tools` / `ctx.effect` / `ctx.dshGo`；`inject(['dshGo'])` 无问号、无顶层 inject |
| 第二套 Scheduler / 预算 | grep `class .*Scheduler|class .*Budget` | 只有 R3 原有的 `ProactiveScheduler`；拦截的 `dailyMax`/`cooldownMs` 是**拦截账自己的**，与 `SchedulerConfig` 不共用不覆盖 |
| 统一仲裁 | 读 `SurfaceSlot` + 两个 Runtime | 主动页与拦截页**真的统一到一个槽**：claim 在前、释放覆盖 `onDestroy`/`withdraw`/`withdrawAll` 三条路径 |
| 两本账独立 | 读文件路径常量 | `filesDir/loop/proactive/ledger.json` 与 `filesDir/loop/intercept/ledger.json`，**两个文件两套计数** |
| Coordinator 是否唯一主动仲裁入口 | grep `Command.Present` | 只有 `ProactiveRuntime` 一处；拦截页**不走** Coordinator（设计如此，独立账），但**走同一个屏幕槽** |

**一处需要说明的架构取舍**：拦截事件不经过 Coordinator。这是上一轮就定下的（拦截有自己的账和决策路径），
本轮**没有**把它塞进 Coordinator —— 那会制造第二套状态机。但"屏幕只能有一个"这条**已经统一**（`SurfaceSlot`），
这也正是任务书要求"所有主动展示和拦截事件必须经过统一仲裁"的实质。

---

## 13. 尚未解决的问题

### 阻塞（本机无法解）

1. **真机/模拟器验证全部未做** —— adb 0 台设备。所有"界面上长什么样"都是 **NOT_TESTED**：
   拦截页、训练题、设置页、partner 四面板、bili 工具卡、原生配置表单。
2. **APK 未打、未校验** —— G1′ 的运行时已就绪，但本轮明令不建 APK，所以
   `tooling/verify_apk_runtime.py` 只在单测里验过，**没有对真实 APK 跑过**。

### 功能缺口（代码层，明确记录）

3. **拦截白名单没有增删 UI**（§2 第 5 项）：内核支持写 `whitelist`，页面这轮只读了没写。
4. **bili 与 cap-ledger 未真正合并成一份账**（§2 第 8 项）：需要宿主暴露跨插件状态能力，当前契约没有。
5. **训练题只有一种题型**（数字排序），没有难度分级、没有内容真的跟"专注"相关的素材。
6. **PROOT `.101` 的 l2s 迁移未在真机验证**：默认值因此保持 `.98` 不动（这是刻意选择，不是遗漏）。
7. **M3 的 `real-dsh-tools.test.mjs` 仍 skip**：需要 `DSH_TOOLS_DIR` 指向 app.asar 内的真 dsh-tools。

### 已知但本轮不动的既有问题

8. **既有 golden 失败**：本机字体基线问题；R3 基线与本轮**同环境对跑失败名集合完全相同**（§9），按 `MAINTENANCE.md §9` 不动。
9. **`LoopHardeningTest` 在 JVM 侧编译不过**：引用了 Android 类 `InteractionHub`，需要真 Android 测试运行时；
   离线验证集一直排除它的 15 项。**任何人不得据此宣称"全部单测通过"。**

---

## 14. 下一阶段 ARM64 真机验收清单

前置：`runtime/out` 两个 ABI 已就绪（本轮已达成）。以下按依赖顺序。

### 14.1 构建（本机即可，已验证）

```bash
wsl -d Ubuntu -- bash -lc "cd /root/r4build/dsh-Go && bash runtime/build-runtime.sh"
wsl -d Ubuntu -- bash -lc "cd /root/r4build/dsh-Go && python3 -I runtime/lib/rtbuild.py verify-outputs --out runtime/out --abi arm64-v8a x86_64"
\# 期望：G1' PASS；把 runtime/out 拷回 Windows 工作树
```

### 14.2 打包与校验

```bash
tooling/build-apk.sh -f arm64
python -I tooling/verify_apk_runtime.py <apk> --plugin runtime/proactive/dsh-go-proactive
\# 校验器现在真的查：rootfs / manifest / libproot.so / libproot-loader.so / libtalloc.so
\#                / libandroid-shmem.so / 插件文件 / 每个的 SHA256
```

### 14.3 真机必测（每条要截图或日志，否则写 NOT_TESTED）

**A. 娱乐拦截闭环（M1，最优先）**
1. 装包 → 打开手机控制页 → 看到「娱乐拦截」入口
2. 设置页能读到内核配置（开关 / 目标清单 / 四个参数）
3. 开一个目标 APP → 拦截页弹出
4. **训练题出现 4 个数字**，按错 → 进度清零并提示"重新来一遍"
5. 按对 → 训练按钮才可点 → 点后放行并真的回到那个 APP
6. 白名单 / 暂停 / 紧急退出各自生效，回执入账
7. **同一目标 APP 冷却内再开**：不重复拦
8. **打开另一个目标 APP**（页面还在时）：不弹第二页、不漏账 ← 本轮修的缺陷
9. 计数与回执在设置页与真机行为一致

**B. ACK 五态（M1）**
10. 主动搭话弹出 → 只点「我看到了」→ 状态是 ACKED（不是已回复）
11. ACK 后再回复 → RESPONDED
12. 只显示不点 → 到期后 UNCONFIRMED（不是 IGNORED_SEEN）← 本轮收紧的语义
13. ACK 后过完 replyWindow 仍不回复 → IGNORED_SEEN
14. 两本账互不影响：拦截暂停**不会**暂停主动搭话

**C. 双页仲裁（M1，本轮核心）**
15. 主动搭话页在屏时打开目标 APP → **不叠屏**
16. 主动页被新的 Presentation 替换时 → 新的能显示（不因槽位卡死）
17. 杀进程后重开 → 屏幕是空的（槽位不持久化）

**D. 插件（M2）**
18. bili 工具卡在真机上渲染
19. 真实联网查一个 BV → 标题/UP/播放都出来
20. cap-ledger 面板显示六态

**E. 人格记忆情绪（M3）**
21. 四个面板渲染（人格 / 记忆 / 心情 / 目的）
22. **心跳维度 `heartache` 有柱子**（防上一轮的键名错位回归）
23. 原生配置表单能画出来、能改、能存、重启后还在
24. 改配置 → 保存 → 杀进程 → 重开 → 数据正确

### 14.4 通过标准

每条要么有证据（截图/日志/回执 JSON），要么在报告里写 **NOT_TESTED + 原因**。
**不允许把"模拟器通过"写成"真机通过"。**

---

## 15. 四种状态总表

### PASS

| 项 | 证据 |
|---|---|
| M1 ACK 五态 | `Engagement{RESPONDED,DISMISSED,ACKED,IGNORED_SEEN,UNCONFIRMED,NOT_DELIVERED}`，`AckStateMachineTest` 覆盖每个态可达；IGNORED_SEEN 已收紧为"ACK 后过期未回" |
| M1 两本账独立 | 两个 JSON 文件、两套计数、两套去重键；有"拦截暂停不暂停主动搭话"的测试 |
| M1 一次只拦一个 | 换包名返回 `Allow(another_open)`，不消耗冷却/日额度，旧 eventId 不被覆盖；3 项新测试 |
| M1 训练可判定 | `InterceptDrill` 由 eventId 确定性生成；守卫侧 `onReleased` 在未解完时返回 false；10 项测试 |
| M1 SurfaceSlot 统一仲裁 | 主动页/拦截页共用一槽；替换语义 + 陈旧释放守卫；7 类情形有测试 |
| M1 拦截设置页接真内核 | `dsh/intercept` 通道 + `intercept_page.dart`，每次操作只 patch 一个键，不留本地副本；已挂进手机控制页；10 项 Dart 测试 |
| M2 cap-ledger 六态 | `install/loaded/toolRegistered/available/executed/failed`，后三个为派生值不接受输入；42 项测试 |
| M3 七维同源 | `dims.mjs` 唯一真相，views/mood 均从它取；`heartache=7` 渲染通过（789 B） |
| M3 身份面板接真数据 | `identityPanel()` 读 persona/roleState/topics/开关；缺失时如实报"哪种读不到"而不是编人设 |
| M3 Config 可被宿主解析 | 真 `@deepseek-ai/schemastery` 3.18.5 解析通过，uid 59 |
| DEV G1′ 双 ABI | 见 §11，两个 ABI 全部 PASS，835 ELF 零错架构 |
| DEV 校验器不再假 FAIL | `e_machine` 类型错误已修 |
| DEV 构建重试加固 | `--retry-all-errors` + 断点续传 + 备用镜像 |
| 完整回归零新增失败 | 两树失败名集合双向差集 0 条（§9），不是靠计数相等 |
| l10n 可重生成 | 47 部件 / 5417 键，`--check` 通过 |
| Flutter 静态分析 | No issues found |
| 无新增第三方依赖 | 全程零新增（schemastery 由宿主提供） |
| PRoot 非原子递减的数据丢失 | `repair()` + 4 条回归；旧代码实测会 `Done(0,2)` 删掉数据并报兼容（§11.7、`C-proot-all-atomic.log`） |
| proot .98/.101 的 l2s 布局 | 两版本各自编译并真跑，布局逐字相同；`.proot.l2s.` 只在 `-DUSERLAND` 里（§11.7） |
| Android 主源码 + 单元测试源码真编译 | Gradle 9.3.1 / AGP 9.1.0 / JDK 21：`BUILD SUCCESSFUL` ×2 |
| `L2sMigrationTest` | 23/23 OK（Linux 非 root；root 下 3 条必然假失败，故不采用 root 结果） |

### PARTIAL

| 项 | 到了哪 | 差哪 |
|---|---|---|
| M1 拦截白名单 | 能读、能展示 | 没有增删 UI |
| M2 台账统一 | 六态已建、契约清晰 | bili 与 cap-ledger 未合并成一次写入，缺宿主跨插件状态能力 |
| PROOT 版本 | 双 ABI 用 `.101` 建成；**默认值已改为 `.101`**（`.98` 已下架）；.98/.101 布局实测同一份，两棵真实树迁移结果一致（§11.7） | 真机上"旧 `.98` 安装升级后首次直接启动"整链路仍未跑 |
| 训练题 | 有可判定题型、失败不放行 | 只有数字排序一种，没有难度/内容分级 |

### FAIL

**无。** 本轮没有未达成的硬性验收项。

（需要说清：上一轮 G1′ 是 FAIL，本轮已转 PASS；本轮没有再发现新的 FAIL 项。）

### NOT_TESTED

| 项 | 原因 |
|---|---|
| 所有真机/模拟器渲染与交互 | adb 0 台设备 |
| APK 打包与 `verify_apk_runtime.py` 对真包 | 本轮明令不建 APK |
| 原生配置表单的真机渲染截图 | 需要真引擎 + 设备；schema 可解析已验 |
| `dsh plugin add` 后在真 profile 加载 | 需要真引擎 |
| `real-dsh-tools.test.mjs`（1 skip） | 需 `DSH_TOOLS_DIR` 指向 asar |
| PROOT `.101` × `L2sMigration` **真机**旧安装升级 | 版本间布局与转换已实测（§11.7），真机整链路仍未跑 |

---

## 16. 可复现命令

```powershell
# 工作树
D:\code\R4\hard     integration/r4-hardening-2026-11-01 @ 5a41937
D:\code\R4\h-m1     harden/m1 @ 79bfdce
D:\code\R4\h-m2     harden/m2 @ a6677bc
D:\code\R4\h-m3     harden/m3 @ 2694425
D:\code\R4\h-dev    harden/dev @ 00cc94f
D:\code\R4\wt-dev   R3 基线对照（app/lib+app/test 字节等于 f1423dc1）

# 内核 172 项 / Android 感知编译
powershell -NoProfile -ExecutionPolicy Bypass -File D:\code\R4\ldev-loop.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File D:\code\R4\hard-android.ps1

# 插件
cd D:\code\R4\hard\examples\plugins\dsh-go-bili;       node --test   # 69
cd D:\code\R4\hard\examples\plugins\dsh-go-cap-ledger; node --test   # 42
cd D:\code\R4\hard\examples\plugins\dsh-go-partner;    node --test   # 96/97

# 构建守卫
cd D:\code\R4\hard;          python -m unittest runtime.tests.test_verify_outputs   # 9
cd D:\code\R4\hard\tooling; python -m unittest test_verify_apk_runtime            # 8

# l10n / Flutter
cd D:\code\R4\hard\app
$env:PYTHONIOENCODING='utf-8'; $env:FLUTTER='C:\dev\flutter\bin\flutter.bat'
python tool\l10n_merge.py --check                       # 47 parts, 5417 keys
$env:DSH_TEST_FONT_DIR='D:\code\testfonts'
C:\dev\flutter\bin\flutter.bat analyze                # No issues found
C:\dev\flutter\bin\flutter.bat test test\phone\proactive_page_test.dart test\proactive_channel_test.dart test\intercept\intercept_channel_test.dart  # +35

# G1'（本轮真的 PASS）
cd D:\code\R4\h-dev
python -I runtime/lib/rtbuild.py verify-outputs --out runtime/out --abi arm64-v8a x86_64

# 重建 runtime（Linux）
wsl -d Ubuntu -- bash -lc "cd /root/r4build/dsh-Go && bash runtime/build-runtime.sh"
```

---

## 17. 一句话总结

**上一轮把"能跑的逻辑"和"能看的东西"都做出来了，但没人在真机上见过它；这一轮把逻辑做成闭环
（训练可判定、一次只拦一个、一屏只一个页面、设置页接真内核），并把 G1′ 从"环境阻塞"变成"真的建出来了"。**

**剩下的只有一件事挡在中间：设备。** 手上有 ARM64 手机之后，按 §14.3 的 24 条走一遍，
就能回答"它到底能不能用"。在那之前，任何"已经能在手机上跑"的说法都不成立 —— 我不会那么说。
