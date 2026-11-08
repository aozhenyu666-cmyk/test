# R4 交付物地址与内容清单

**验收用索引。** 每条都是绝对路径，可以直接复制到资源管理器或命令行。

---

## 一、两个核心文档（先看这两个）

| 文档 | 绝对路径 | 大小 | 内容 |
|---|---|---|---|
| **验收单页** | `D:\code\R4\l-dev\R4-ACCEPTANCE.md` | — | 验收清单 A/B/C/D 四组，每条带命令和期望输出 |
| **完整技术报告** | `D:\code\R4\l-dev\R4-HARDENING-REPORT.md` | — | 17 节，含 G1′ 证据、失败集合对照（§9）、PROOT 决定（§11.7） |

两份都在分支里（`git show HEAD:R4-ACCEPTANCE.md` 可在任何机器上取）。

> **本版 INDEX 的三条更正**（上一版写错了，这里改掉）：
> 1. **不写死 SHA，不写死提交数，不写死行数。** 会过期的东西一律给命令。
> 2. **「970 = 970」作废。** 失败数量相等不等于失败集合相同；本轮给的是**失败名集合的双向差集**（§六，结果 0 条）。
> 3. **PROOT 默认值不是 `.98` 了，是 `.101`。** 上一版说 `.101` 改了 l2s 布局，实测不成立（§六、报告 §11.7）。

**代码在哪：本轮改动的分支是 `fix/r4-dev-regression-build-2026-11-01`，工作副本 `D:\code\R4\l-dev`。**

---

## 二、代码在哪（工作副本）

**所有工作副本都在 `D:\code\R4\` 下，是同一个 git 仓库的 worktree（共享 .git）。**

| 目录 | 分支 | HEAD | 用途 |
|---|---|---|---|
| `D:\code\R4\hard` | `integration/r4-hardening-2026-11-01` | `ac04b38`（用 `git rev-parse` 复核） | hardening 集成结果 |
| `D:\code\R4\l-dev` | `fix/r4-dev-regression-build-2026-11-01` | 见 `git rev-parse HEAD` | **本轮 DEV 改动在这里，验收这次的代码看它** |
| `D:\code\R4\h-m1` | `harden/m1` | 见 `git rev-parse` | M1 独立分支 |
| `D:\code\R4\h-m2` | `harden/m2` | 见 `git rev-parse` | M2 独立分支 |
| `D:\code\R4\h-m3` | `harden/m3` | 见 `git rev-parse` | M3 独立分支 |
| `D:\code\R4\h-dev` | `harden/dev` | 见 `git rev-parse` | DEV 独立分支 |
| `D:\code\R4\int` | `integration/r4-2026-11-01` | 见 `git rev-parse` | 上一轮集成（对照用） |
| `D:\code\R4\wt-dev` | `fix/r3-minutes-merge` | 见 `git rev-parse` | **R3 基线对照树**（app/lib+app/test 与 `f1423dc1` 字节相同） |
| `D:\code\R4\wt-m1` `wt-m2` `wt-m3` | `feat/r4-*` | — | 上一轮三个原始研发分支 |
| `D:\code\R4\dsh-Go` | `main` | 见 `git rev-parse` | 主克隆（只读参照） |

> ⚠️ 这些不是独立 clone。**删掉其中一个目录会让其他 worktree 失效。**
> 增删用 `git worktree add/remove`，不要用 `rm -rf`。

---

## 三、四个模块改了什么（精确到文件）

### M1 行为管理（精确文件见 `git show --stat`；不写死提交号与行数）

**Kotlin（内核）**
```
D:\code\R4\hard\app\android\app\src\main\kotlin\io\github\ccch1mneyyy\dsh_app\
  loop\core\Intercept.kt              +197  训练题 InterceptDrill、一次一拦、非目标回归
  loop\core\SurfaceSlot.kt            +30   替换语义、陈旧释放守卫
```

**Kotlin（Android 适配层）**
```
  loop\intercept\InterceptPageActivity.kt  +109  倒计时 → 真训练题 UI
  loop\intercept\InterceptRuntime.kt        +17   暴露 drill / tapDrill
  loop\intercept\InterceptPlugin.kt         （未改，通道已够用）
```

**资源**
```
  res\values\intercept_strings.xml      +9    训练题文案（英）
  res\values-zh\intercept_strings.xml   +11   训练题文案（中）
```

**Dart（新文件）**
```
D:\code\R4\hard\app\lib\
  intercept\intercept_channel.dart      +326  类型化通道（status/setConfig/receipts/pause/resume）
  ui\phone\intercept_page.dart         +448  拦截设置页（目标增删、参数、暂停恢复、账本、回执）
  ui\phone\phone_control_screen.dart    +10   入口（在"主动搭话"下面一行）
```

**l10n（新部件）**
```
D:\code\R4\hard\app\lib\l10n\
  parts\intercept_zh.arb                +74   49 个键
  parts\intercept_en.arb                +74
  app_zh.arb / app_en.arb               （合并产物）
  gen\app_localizations*.dart          +548   重生成
```

**测试**
```
  test\kotlin\...\loop\core\InterceptDrillTest.kt        +115  新增 10 项
  test\kotlin\...\loop\core\InterceptChainTest.kt        +66   新增 3 项
  test\kotlin\...\loop\core\SurfaceSlotTest.kt           +29   替换语义 3 项
  app\test\intercept\intercept_channel_test.dart          +189  新增 10 项
```

### M2 插件生态

```
D:\code\R4\hard\examples\plugins\dsh-go-cap-ledger\
  lib\ledger.mjs        +25  六态（新增 available / executed / failed，均为派生值）
  test\ledger.test.mjs  +3   STATES 断言 4 → 6
```

### M3 人格记忆情绪

```
D:\code\R4\hard\examples\plugins\dsh-go-partner\
  lib\dims.mjs    +53   新增：七维唯一真相（DIMS / DIM_LABEL / DIM_VISIBLE_MIN / dimsOf）
  lib\views.mjs   +119  identityPanel() + 改用 dims.mjs
  lib\mood.mjs    +23   -24  改用 dims.mjs（先 import 再 re-export）
  lib\index.js    +55   -13  export const Config = undefined → 真 schema
```

### DEV 构建

```
D:\code\R4\hard\runtime\
  lib\rtbuild.py        +13  修 machine 比较的类型错误（假 FAIL）
  build-runtime.sh      +55   fetch 重试加固（--retry-all-errors + 断点续传 + 备用镜像）
```

### 回归修复

```
D:\code\R4\hard\app\lib\ui\phone\intercept_page.dart    Icons.close → AppIcons.remove
```

---

### DEV 本轮：PRoot 迁移与版本默认值

```
D:\code\R4\l-dev\app\android\app\src\main\kotlin\io\github\ccch1mneyyy\dsh_app\runtime\
  L2sMigration.kt        chain() 三态（Found/Broken/NotOurs）+ repair() + Done.repaired
  DshProcess.kt          日志里带上 repaired 计数
D:\code\R4\l-dev\app\android\app\src\test\kotlin\…\runtime\
  L2sMigrationTest.kt    18 → 23（断链修复 4 条 + .101 真实布局 1 条）
D:\code\R4\l-dev\runtime\build-runtime.sh         默认 PROOT_VERSION 改 .101
D:\code\R4\l-dev\THIRD_PARTY_NOTICES.md           proot 主行改 .101，.98 记为可回退
```

复现命令（Linux/WSL）：

```bash
wsl -d Ubuntu -- bash /mnt/d/code/R4/verify-fix.sh    # 编译 + L2sMigrationTest
wsl -d Ubuntu -- bash /mnt/d/code/R4/all-proot.sh     # 编 proot .98/.101 并建真实树、跑迁移
```

---

## 四、运行时产物在哪（G1′ 的证据）

### 4.1 Windows 侧（验收 B 组请在这里跑）

```
D:\code\R4\h-dev\runtime\out\
├── manifest.json                              4 KB    abis = [arm64-v8a, x86_64]
├── arm64-v8a\
│   ├── build-report.json                     330 KB   835 个 ELF 审计，wrong-arch: 0
│   ├── assets\runtime\
│   │   ├── rootfs-arm64-v8a.tar.xz          151 MB   ← 主要产物
│   │   ├── manifest.json                      10 KB
│   │   └── licenses\                          proot / talloc / libandroid-shmem
│   └── jniLibs\arm64-v8a\
│       ├── libproot.so                      250 KB
│       ├── libproot-loader.so                20 KB
│       ├── libtalloc.so                      30 KB
│       └── libandroid-shmem.so               10 KB
└── x86_64\
    ├── build-report.json                     310 KB
    ├── assets\runtime\
    │   ├── rootfs-x86_64.tar.xz             157 MB
    │   ├── manifest.json
    │   └── licenses\
    └── jniLibs\x86_64\
        └── （同上 4 个 so）
```

**总计 310 MB。不在 git 里**（构建产物不入库）。

### 4.2 Linux 侧（构建发生的地方）

```
WSL Ubuntu:  /root/r4build/dsh-Go/runtime/
             ├── out/      310 MB   构建产物（已拷回 Windows）
             ├── .cache/   582 MB   下载缓存（镜像 tarball）
             └── .work/    1.1 GB   解包工作区
WSL 构建日志: /root/r4build/build.log
```

**WSL 磁盘**：950 GB 可用。C 盘只剩 1.5 GB，**所以一切产物都在 D 盘或 WSL 内部**。

---

## 五、验证脚本（可直接跑）

```
D:\code\R4\ldev-loop.ps1            内核 JVM 测试，打本轮分支（172 项，不需要设备/引擎）
D:\code\R4\ldev-all-android.ps1      整棵 main kotlin 树对 android.jar（子集编译，仅供参考）
D:\code\R4\run-ft.ps1               flutter test 单次跑，参数化（树 / 文件 / 日志）
D:\code\R4\verify-fix.sh            WSL：编 L2sMigration + 跑 L2sMigrationTest
D:\code\R4\all-proot.sh             WSL：编 proot .98/.101、建真实 l2s 树、跑迁移
D:\code\R4\hard-verify.ps1          上一版的内核测试（打 hardening 树，对照用）
```

跑法：
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\code\R4\ldev-loop.ps1
```
```bash
wsl -d Ubuntu -- bash /mnt/d/code/R4/verify-fix.sh
wsl -d Ubuntu -- bash /mnt/d/code/R4/all-proot.sh
```

**必须带 `-ExecutionPolicy Bypass`**：.ps1 没有数字签名，直接 `&` 调用会被拦。

> ⚠️ **`ldev-all-android.ps1` 不要当成判据。** 它只编	extbf{一个包}、不带 Gradle 生成的
> `R`/`i18n`/`phone.*`，在**未改动的基线树上同样报 100 条 unresolved reference**。
> 真正的 android 判据是真 Gradle（见 ACCEPTANCE §C11）。

G1′ 那条：
```powershell
cd D:\code\R4\h-dev
python -I runtime/lib/rtbuild.py verify-outputs --out runtime/out --abi arm64-v8a x86_64
```

---

## 六、测试证据（原始日志，可核对）

**R4 本轮（同一会话、同一个 Flutter 3.47.7 / Dart 3.13.5、同一份 `pubspec.lock`）**

| 日志 | 绝对路径 | 内容 |
|---|---|---|
| 全量回归 · 本轮分支 | `D:\code\R4\B-full-hard.log` | `+7331 ~372 −969`，失败名 968 条 |
| 全量回归 · R3 基线 | `D:\code\R4\B-full-wtdev.log` | `+7321 ~372 −969`，失败名 968 条 |
| 集合差集 | 见 `R4-HARDENING-REPORT.md` §9 | **双向差集 0 条**（−969 与 968 条差 1 = `-r compact` 截断残留的一行 `loading <日志>`，不是测试） |
| Flutter analyze（本轮分支） | `D:\code\R4\D-analyze-ldev.log` | `No issues found!` |
| 内核 JVM 测试（本轮分支） | `D:\code\R4\ldev-loop-test.log` | `PASS 172 tests` |
| L2sMigrationTest（Linux 非 root） | `D:\code\R4\C-proot-all-atomic.log` | `OK (23 tests)` |
| proot .98/.101 真实布局 | `D:\code\R4\C-proot-all-atomic.log` | 两版本布局相同 |
| 两个真实树的迁移 | `D:\code\R4\C-inplace-migrate.log` | 各 `Done(links=5)`，幂等 |
| Android 单元测试编译 | `D:\code\R4\G-gradle-utest-compile.log` | `BUILD SUCCESSFUL` |
| Android 主源码编译 | `D:\code\R4\G-gradle-ldev.log` | `BUILD SUCCESSFUL` |
| 插件 node 测试 | `D:\code\R4\D-node-*.log` | bili 69 / cap-ledger 42 / partner 96+1 skip |

**归档的历史日志（上一轮的，仍保留可核对，但"970 = 970"的结论已作废）：**

| 日志 | 内容 |
|---|---|
| `HARD-full-ft2.log` | `+7330 ~370 −970` |
| `HARD-full-ft.log` | `+7329 ~370 −971`（**同一 commit 的另一次跑，集合就不同**） |
| `BASE-full-ft.log` | `+7320 ~370 −970`（R3 基线） |

⚠️ 这三份的失败**集合**互不相同。**数量相等不代表集合相同**；结论请看
`R4-HARDENING-REPORT.md` §9，那里给的是集合比对，不是计数。

---

## 七、Git 地址

### 7.1 远端

```
https://github.com/aozhenyu666-cmyk/dsh-Go
```

### 7.2 本轮分支（都已推 origin）

| 分支 | 说明 |
|---|---|
| `integration/r4-hardening-2026-11-01` | hardening 集成结果（本轮从它延长） |
| `fix/r4-dev-regression-build-2026-11-01` | **本轮 DEV 工作分支** |
| `harden/m1` / `harden/m2` / `harden/m3` / `harden/dev` | 四个模块分支 |

**不要照抄 SHA。** 写死的 SHA 会随下一次提交过期，看的是命令的输出：

```powershell
git ls-remote origin refs/heads/integration/r4-hardening-2026-11-01
git ls-remote origin refs/heads/fix/r4-dev-regression-build-2026-11-01
```

### 7.3 受保护分支（本轮未动；下表 SHA 是核对当时的取值，用 `git ls-remote` 复核）

| 分支 | SHA | 期望 |
|---|---|---|
| `main` | `bf01594471e11d7c1dbfe743a304a02d66068b29` | 未动 ✅ |
| `fix/r3-minute-input-on-1.0.4`（R3 发布版） | `f1423dc11cb4d42ca67225011c6437d1abd0025f` | 未动 ✅ |
| `integration/r4-2026-11-01`（上一轮） | `2f88256cb83d6c21821c0278555471f9a979d5ae` | 未动 ✅ |
| `feat/r4-intercept-and-ack` | `35ac53d3e6283231ace0957e9cbb98c7293c76d1` | 未动 ✅ |
| `feat/r4-bili-plugin` | `2eb704d0aa93b1f04b05d55d949d3513f6371a20` | 未动 ✅ |
| `feat/r4-partner-plugin` | `d166e9269b3ff311d9d439bd67dd884b215313fd` | 未动 ✅ |

### 7.4 hardening 集成在这一轮之前做了什么

**不要照抄 SHA，也不要照着某个提交数去数。** 唯一的权威是命令本身：

```powershell
git log --oneline --first-parent 2f88256..integration/r4-hardening-2026-11-01
git show --stat integration/r4-hardening-2026-11-01
```

### 7.5 本轮（DEV）新增的分支与提交

分支：`fix/r4-dev-regression-build-2026-11-01`，从 `integration/r4-hardening-2026-11-01`
（`ac04b38`）直线延长，**没有 rebase、没有 force push，其它分支一个没动**。

```powershell
git log --oneline ac04b38..fix/r4-dev-regression-build-2026-11-01
git diff --stat ac04b38..fix/r4-dev-regression-build-2026-11-01
```

提交内容（不是数量）：

| 主题 | 改了什么 | 为什么 |
|---|---|---|
| PRoot 非原子递减的修复 | `L2sMigration.kt`：`chain()` 三态、`repair()`、`Done.repaired` | 半途被杀留下的断链，旧代码把它当孤儿**把数据删了**，还报"兼容" |
| `.101` 成为默认 | `runtime/build-runtime.sh`、`THIRD_PARTY_NOTICES.md` | 上一版"改布局"的理由经实测不成立；且 `.98` 已下架，旧默认让每次构建 die |
| 测试 | `L2sMigrationTest.kt` 18 → 23 | 断链修复 4 条 + `.101` 真实布局 1 条 |

### 7.6 网络注意

**裸连 github 的 git 通道在本机是断的**，必须走本地代理：

```powershell
git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 fetch origin
git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 push origin <branch>
```

`gh api` 走另一条通道，不需要代理。

---

## 八、四份过程文档（复盘用）

```
D:\code\R4\R4-TRUTH.md                 8.6 KB   第一轮前的事实基线（分支血缘、环境、代理）
D:\code\R4\R4-INTEGRATION-REVIEW.md    9.1 KB   第一轮四模块交付的独立复核
D:\code\R4\R4-INTEGRATION-REPORT.md   15.2 KB   第一轮集成结果（11 项遗留来源）
D:\code\R4\R4-HARDENING-REPORT.md     30.9 KB   本轮完整报告（17 节）
D:\code\R4\R4-ACCEPTANCE.md           11.9 KB   本轮验收单页
```

## 九、派单与调研（可追溯当时怎么要求的）

```
D:\code\R4\briefs\harden-m1.md           M1 派单
D:\code\R4\briefs\harden-m2.md           M2 派单
D:\code\R4\briefs\harden-m3.md           M3 派单
D:\code\R4\briefs\harden-dev.md          DEV 派单
D:\code\R4\briefs\SCHEMASTERY-FINDING.md  Schemastery 决定性证据（宿主自带 3.18.5）
```

## 十、仓库内的相关文档

```
D:\code\R4\hard\docs\loop\ROUND4_ACK_AND_INTERCEPT.md      M1 设计：ACK 状态机 + 拦截链（含 mermaid）
D:\code\R4\hard\docs\build\runtime-and-abis.md             DEV 双 ABI 审计全文
D:\code\R4\hard\HANDOVER_WORKBODY.md                        交接文档
D:\code\R4\hard\R4-HARDENING-REPORT.md                     报告副本（已入库）
D:\code\R4\hard\R4-ACCEPTANCE.md                           验收副本（已入库）
```

---

## 十一、验收最快的三分钟路径

```powershell
# 1. 保护项（7 条，一次看完）
cd D:\code\R4\dsh-Go
git ls-remote origin refs/heads/main refs/heads/fix/r3-minute-input-on-1.0.4 refs/heads/integration/r4-2026-11-01 refs/heads/integration/r4-hardening-2026-11-01

# 2. G1'（一条命令看完六项判据）
cd D:\code\R4\h-dev
python -I runtime/lib/rtbuild.py verify-outputs --out runtime/out --abi arm64-v8a x86_64

# 3. 内核测试（172 项，打本轮分支）
powershell -NoProfile -ExecutionPolicy Bypass -File D:\code\R4\ldev-loop.ps1

# 4. 插件测试（69 + 42 + 96 + 1 skip）
cd D:\code\R4\l-dev\examples\plugins\dsh-go-bili;       node --test
cd ..\dsh-go-cap-ledger; node --test
cd ..\dsh-go-partner;    node --test

# 5. 本轮那次真 Gradle 编译（Android 判据，不是子集编译）
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
cd D:\code\R4\l-dev\app\android
gradle --no-daemon --offline -Pkotlin.incremental=false :app:compileArm64DebugKotlin
gradle --no-daemon --offline -Pkotlin.incremental=false :app:compileArm64DebugUnitTestKotlin

# 6. proot 的实测（Linux/WSL）
wsl -d Ubuntu -- bash /mnt/d/code/R4/all-proot.sh
```

**回归那条不要只看数字**：`B-full-hard.log` 与 `B-full-wtdev.log` 要**按失败测试名集合**比对，
差集是 0 条才算过关。方法在 `R4-HARDENING-REPORT.md` §9。

想深挖任何一条，去 `R4-HARDENING-REPORT.md` 对应章节（§11 G1′ / §9 回归与集合对照 / §10 Schemastery / §11.7 PROOT / §13 未解决 / §14 真机清单）。
