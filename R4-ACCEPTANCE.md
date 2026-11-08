# R4 第二阶段验收文档

**给验收人看的单页版。** 每条都写了「怎么自己验一遍」，不是让你信我的话。

- 集成分支：`integration/r4-hardening-2026-11-01`
- 本轮 DEV 分支：`fix/r4-dev-regression-build-2026-11-01`
- 起点：上一轮集成 `2f88256`
- 完整技术报告：`R4-HARDENING-REPORT.md`（同仓库根目录）

> **不写死 SHA，也不写死行数。** 要核 HEAD 用 `git ls-remote origin refs/heads/<branch>`；
> 要核提交范围用 `git log --oneline <from>..<to>`。写死的值一定会在下一次提交后过期。

---

## 一、三十秒结论

| 问题 | 答案 |
|---|---|
| 双 ABI 运行时建出来了吗？ | **是**，真建出来了，G1′ PASS |
| 有功能能在手机上跑吗？ | **不知道** —— adb 0 台设备，真机一次没碰 |
| 代码级功能闭环完整吗？ | 三条线完整（M1 拦截、M2 插件、M3 人格），两条有明确缺口 |
| 有回归吗？ | **没有**。但不是因为"970 = 970"——那句话作废了：**两棵树同环境各跑一次全量，失败测试名集合双向差集 0 条**（各 968 条）。数量相等本来就不代表集合相同 |
| 加新依赖了吗？ | **没有**，一行没加 |
| 能合 main 吗？ | 现在不建议 —— 真机验收之前不动 main |

---

## 二、验收清单（逐条可自查）

### A. 分支与保护（先验这个，一分钟）

| # | 检查 | 命令 | 期望 |
|---|---|---|---|
| A1 | 集成分支存在 | `git ls-remote origin refs/heads/integration/r4-hardening-2026-11-01` | 返回一个 SHA，且等于 `git rev-parse` 的本地值（此处**不写死**） |
| A2 | main 没动 | `git ls-remote origin refs/heads/main` | `bf015944...` |
| A3 | R3 发布版没动 | `git ls-remote origin refs/heads/fix/r3-minute-input-on-1.0.4` | `f1423dc1...` |
| A4 | 上一轮集成没被覆盖 | `git ls-remote origin refs/heads/integration/r4-2026-11-01` | `2f88256...` |
| A5 | 四个原始研发分支没动 | `git ls-remote origin 'refs/heads/feat/r4-*'` | bili `2eb704d` / intercept `35ac53d` / partner `d166e92` |
| A6 | 无 Release / 无 APK | `gh release list` | 无新增 |
| A7 | 无强制推送痕迹 | `git log --oneline --first-parent 2f88256..HEAD` | 没有 `+`/`-` 的改写记录；`git reflog` 里只有一条 `reset` 是回到 HEAD 本身 |

### B. 构建（G1′，最关键）

| # | 检查 | 命令 | 期望 |
|---|---|---|---|
| B1 | 两个 ABI 目录存在 | `ls runtime/out/` | `arm64-v8a` `x86_64` `manifest.json` |
| B2 | manifest 双 ABI | `cat runtime/out/manifest.json` | `abis` 同时含两项 |
| B3 | G1′ 断言 | `python -I runtime/lib/rtbuild.py verify-outputs --out runtime/out --abi arm64-v8a x86_64` | `G1' PASS`，exit 0 |
| B4 | rootfs 哈希一致 | 上面那条会一起验 | PASS |
| B5 | 四个原生库架构对 | 同上 | PASS |
| B6 | rootfs 无非本架构 ELF | 构建日志 | `wrong-arch: 0`（835 个 ELF 对象） |
| B7 | 插件测试文件没混进运行时 | `tar -tf ...\|grep dsh-go-proactive/.*test` | 0 条 |

**本机实测输出（原文）**
```
PASS  arm64-v8a  abi=arm64-v8a  rootfs-arm64-v8a.tar.xz  ...  4 jniLibs
PASS  x86_64     abi=x86_64     rootfs-x86_64.tar.xz     ...  4 jniLibs
PASS  runtime/out/manifest.json  abis=['arm64-v8a', 'x86_64']
G1' PASS: runtime/out holds a real runtime bundle for arm64-v8a, x86_64
```

> ⚠️ **`runtime/out` 没有提交进仓库**（310 MB 构建产物）。工作副本在 `D:\code\R4\h-dev\runtime\out`，
> 需要时用 `PROOT_VERSION=5.1.107.101 bash runtime/build-runtime.sh` 重建。
> 你验收 B1–B7 时请在那个目录下跑。

### C. 测试（每条都能自己跑）

| # | 层 | 命令 | 实测 |
|---|---|---|---|
| C1 | 内核纯逻辑（JVM 离线） | `powershell -File D:\code\R4\ldev-loop.ps1` | **PASS 172** |
| C2 | Android 感知编译 | `powershell -File D:\code\R4\hard-android.ps1` | **exit 0**（26 个 kt） |
| C3 | bili 插件 | `cd examples/plugins/dsh-go-bili; node --test` | **69 / 69** |
| C4 | cap-ledger | `cd examples/plugins/dsh-go-cap-ledger; node --test` | **42 / 42** |
| C5 | partner 插件 | `cd examples/plugins/dsh-go-partner; node --test` | **96 pass / 1 skip / 0 fail** |
| C6 | 运行时守卫 | `python -m unittest runtime.tests.test_verify_outputs` | **9 OK** |
| C7 | APK 校验器 | `cd tooling; python -m unittest test_verify_apk_runtime` | **8 OK** |
| C8 | Flutter 静态分析 | `flutter analyze` | **No issues found** |
| C9 | l10n 可重生成 | `python tool/l10n_merge.py --check` | **47 部件 / 5417 键** |
| C10 | 目标页面测试 | `flutter test test/phone/... test/intercept/...` | **+35 全过** |
| C11 | Android 主源码真编译（Gradle/AGP 9） | 见 §C11 | **BUILD SUCCESSFUL** |
| C12 | Android 单元测试源码真编译 | 见 §C11 | **BUILD SUCCESSFUL** |
| C13 | `L2sMigrationTest`（Linux 非 root） | 见 §C11 | **OK (23 tests)** |
| C14 | 两个真实 proot 树迁移 | 见 §C11 | `Done(links=5)` ×2，幂等 |

### C11：为什么这几条要在 Linux/WSL 里跑

`L2sMigrationTest` 用 `Files.setPosixFilePermissions`，**Windows 上直接抛异常**；它还有 3 条测试
把目录 `chmod 000` 后断言遍历停下，**在 root 下必然假失败**（root 照样读得进去）。
Android 上的运行时进程永远不是 root，所以**非 root 那次才是诚实的那次**。

```bash
wsl -d Ubuntu -- bash /mnt/d/code/R4/verify-fix.sh      # 编译 + 跑 L2sMigrationTest
wsl -d Ubuntu -- bash /mnt/d/code/R4/all-proot.sh       # 编 proot .98/.101，建真实树，跑迁移
```

Android 侧的两条编译走真 Gradle（AGP 9.1.0 / Kotlin 2.2.21 / JDK 21）：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
cd D:\code\R4\l-dev\app\android
gradle --no-daemon --offline -Pkotlin.incremental=false :app:compileArm64DebugKotlin
gradle --no-daemon --offline -Pkotlin.incremental=false :app:compileArm64DebugUnitTestKotlin
```

> `:app:testArm64DebugUnitTest` 在本机**跑不起来，且不是本次改动引起的**：这台机器的单元测试
> 过滤不到任何类（`No tests found for given includes`），在**未改动的基线树**上同样如此。
> 编译通过是本机能证到的最强结论；**单元测试的"执行"这一步记为 NOT_TESTED**。

**C5 那 1 个 skip 是什么**：`real-dsh-tools.test.mjs`，需要 `DSH_TOOLS_DIR` 指向 `app.asar` 里的真
`@deepseek-ai/dsh-tools`。**不是失败，是这条通道在本机跑不了**。我没有删掉它假装"全绿"。

### D. 回归对照（这条最重要，防止"测试多了但功能坏了"）

**这一节在 R4 的 DEV 轮被整节重做过。上一版的"970 = 970"是错的读法，下面给的是集合。**

| 全量跑（同会话、同 Flutter 3.47.7、同 `pubspec.lock`） | 通过 | 跳过 | 失败 | 失败名条数 |
|---|---|---|---|---|
| 本轮分支 `fix/r4-dev-…`（`B-full-hard.log`） | +7331 | ~372 | −969 | **968** |
| R3 基线 `wt-dev`（`B-full-wtdev.log`） | +7321 | ~372 | −969 | **968** |

- **双向差集 0 条**：没有任何一条"只在一边失败"。→ **本轮没有新增失败。**
- 表里的 **−969 与 968 条差 1**：`-r compact` 用回车覆盖行，原始日志里有 1 条是进度行被截断残留的
  `loading <日志>[E]`，不是测试名。按 `<path>.dart: <name>` 解析出 968 条真实失败。
  这一点也是我起初"看到一条差异"的原因，单独复跑后确认是解析假象。
- **为什么不能只看数字**：同一棵树、同一个 commit 跑两次，失败集合就能不一样——
  `HARD-full-ft.log`（−971）与 `HARD-full-ft2.log`（−970）都是 `hard @ ac04b38`，
  却各缺一条不同的 `session_sync_screen_test` 用例。这就是"数量相等 ≠ 集合相同"的现成例子。
- **上一版说的"并行偶发"也不成立**：`plugin_config_scope_fresh_test` 单文件跑 3/3 全过，
  `--concurrency=1` 与默认并发、单文件与两文件同跑，四种组合两棵树结果一致；
  `session_sync_screen_test` 的 4 条失败则是**每次都复现**的（`fake_async` 留下 120 s pending timer），
  两棵树一样。细节见 `R4-HARDENING-REPORT.md` §9.2。
- 既有 golden 像素失败按 `MAINTENANCE.md §9` 不动；**没有改任何 golden 图，没有放宽任何断言**。

**上一版提到的那次真回归仍然有效**：图标测试曾抓到 `intercept_page.dart` 用了 `Icons.close`，
该仓库要求所有图标走 `AppIcons` 角色表（Material 图标字体未打包，真机上会画成方块），已修。

---

## 三、本轮改了什么（按模块）

| 模块 | 提交 | 规模 | 核心内容 |
|---|---|---|---|
| **M1 行为管理** | `4da00b5` | 20 文件 +2344 −46 | 训练题可判定、一次只拦一个、槽位仲裁、拦截设置页接真内核 |
| **M2 插件生态** | `5f5f9c1` | 2 文件 +28 −3 | cap-ledger 四态 → 六态 |
| **M3 人格记忆情绪** | `b06e5a5` | 4 文件 +223 −24 | 七维唯一真相、身份面板接真数据、Config 真 schema |
| **DEV 构建** | `592becf` | 2 文件 +57 −11 | 修校验器假 FAIL、构建重试加固 → G1′ 通 |
| 图标回归修复 | `5a41937` | 1 文件 +3 −1 | `Icons.close` → `AppIcons.remove` |
| 文档 | 见 `git log` | — | `R4-HARDENING-REPORT.md`（本 DEV 轮把它整节更正过，见 §九 与 §11.7） |
| **DEV 本轮 · PRoot 数据丢失** | 见 `git log ac04b38..HEAD` | 3 文件 | `L2sMigration` 半途被杀留下的断链被当孤儿删掉 → 名字悬空、仍报兼容。`repair()` 只在唯一可能时修复，否则原样保留并要求兼容启动 |
| **DEV 本轮 · proot 默认值** | 同上 | 2 文件 | 默认改 `.101`；`THIRD_PARTY_NOTICES` 同步 |

---

## 四、修掉的 4 个真实缺陷（不是文档层面）

验收时如果你想看"到底修了什么真东西"，看这四条：

### 1. 换目标 APP 会覆盖 openEventId（M1）
**症状**：B站拦截页还开着时打开抖音 → 旧的 eventId 被覆盖，**永远拿不到回执**；冷却和每日额度白扣；新目标静默放行。
**修法**：守卫内部先判"有没有一个还开着"，有就返回 `Allow(another_open)`，**什么都不消耗**。
**测试**：`openingAnotherTargetWhileOneIsOpenIsNotASecondInterception`

### 2. 同 owner 换 token 会卡死槽位（M1）
**症状**：两个页面都是 `singleInstance`，新 Presentation 复用同一个 Activity 只换 token → claim 被拒 → 报 surface_busy，但 Activity 其实已经换成新内容了，槽位还挂在旧 token 上没人释放 → **主动搭话把自己饿死**。
**修法**：同 owner 换 token 视为"替换"，可以接管；旧 token 的迟到 release 仍被陈旧守卫挡住。
**测试**：`a_new_token_from_the_same_owner_takes_the_slot_over` + `the_replaced_token_cannot_free_its_replacement`

### 3. 训练页只是倒计时（M1）
**症状**："训练通过" = "页面停留了 60 秒"。把手机放下再回来就通过了。
**修法**：由 eventId **确定性生成**一道数字排序题；守卫侧 `onReleased` 在未解完时返回 false；按错进度清零；进程被杀回来是**同一道题**不是更简单的。
**测试**：`InterceptDrillTest` 10 项

### 4. 校验器拿字符串比整数（DEV）—— 假 FAIL
**症状**：manifest 记了 `machine: "aarch64"`（字符串）和 `e_machine: 183`（整数），校验拿字符串去比 183 → **每一个诚实构建都报错**。
**意义**：这是上一轮那个"假 PASS"的孪生兄弟。**一个永远通不过的检查，和一个永远通过不了的检查一样没用。**
**修法**：比较 `e_machine`，无数字时才用 `EM_NAMES` 反查。

### 5. PRoot 自己的递减不是原子的 → 迁移把数据删了还报"兼容"（DEV 本轮）
**症状**：PRoot 撤销一个名字时，先把存储文件改名成新计数，**再**把中间名指过去。在这两步之间被杀，
中间名就指向一个不存在的名字，而**唯一一份数据**的存储文件没有任何名字引用它。
旧的 `L2sMigration` 走到这里认不出这条链，把它算作"孤儿"**删掉**，然后 `compatibilityReason` 返回 null
→ 下次直接启动，名字全指向空气。
**复现**：手工造出这个状态，旧代码给出 `Done(links=0, removed=2)`、`store=[]`、
`compatibilityReason=null`，两个名字都读不出来。
**修法**：`chain()` 区分"PRoot 的但断了一条链"和"根本不是 PRoot 的"；`repair()` 只在
（a）同名前缀下**只有一个**候选存储文件、（b）它的计数**不小于**等它的名字数 时才修回；
任何不够确定的情况**原样不动**，store 保持非空 → `compatibilityReason` 继续要求兼容启动（PRoot 还能读）。
**测试**：`L2sMigrationTest` 新增 4 条（修复、两个候选不猜、计数不够不修、幽灵链不碰），18 → 23。

---

## 五、Schemastery 的决定（你上轮明确要求的）

**决定：不引入依赖。宿主本来就支持。**

上一轮我建议"为了原生表单破例引入 schemastery"——**这个建议是错的**，你让我先验证是对的决定。

| 证据 | 位置 |
|---|---|
| 宿主自带 schemastery | `~/.dsh/profiles/desktop/node_modules/@deepseek-ai/schemastery` → **3.18.5** |
| 宿主有完整表单引擎 | `app/lib/ui/engine_settings/schema_form.dart:6-8` → "schemaSections lays out **any plugin's** namespace by itself" |
| 有第三方插件配置页 | `app/lib/ui/plugins/config/plugin_config_page.dart` + `plugin_config_layout.dart` |
| 有读写通路 | `app/lib/state/plugin_config/plugin_config_bridge.dart` → `GET/POST /dsh-go/v1/plugin-config` |
| 有设计文档 | `docs/design/plugin-config.md`（PLUGIN-CONFIG 契约） |
| 有先例 | `dsh-bili-subagent` 把 schemastery 列为 optional peer，并用 `!z ? undefined : z.object({...})` |

上一轮那句注释 **"零依赖所以画不出表单" 因果说反了** —— 真实原因是**导出了 `undefined`**，宿主拿不到 schema。

现在改成真 schema，用**真实的** schemastery 3.18.5 解析通过（uid 59），**一行依赖没加**。

**未验证**：表单在真机上长什么样。这条是 **NOT_TESTED**，需要设备。

---

## 六、四态定级

### PASS（16 项）
M1 ACK 五态 · M1 两本账独立 · M1 一次只拦一个 · M1 训练可判定 · M1 槽位统一仲裁 ·
M1 拦截设置页接真内核 · M2 六态台账 · M3 七维同源 · M3 身份面板接真数据 · M3 Config 可被宿主解析 ·
DEV G1′ 双 ABI · DEV 校验器不再假 FAIL · **完整回归按失败名集合比对零新增** · 零新增第三方依赖 ·
**PRoot 非原子递减的数据丢失已修（复现 + 修复 + 4 条回归）** · **proot .98/.101 的 l2s 布局实测为同一份**

### PARTIAL（4 项）— 都是我自己标的，不是掩盖

| 项 | 到了哪 | 差哪 |
|---|---|---|
| 拦截白名单 | 能读、能展示 | **没有增删 UI**（内核支持写，页面这轮没做） |
| 台账统一 | 六态已建、契约清晰 | bili 与 cap-ledger **未合并成一次写入**，缺宿主跨插件状态能力 |
| PROOT 版本 | 双 ABI 用 `.101` 建成；**默认值已改为 `.101`**（`.98` 已下架，旧默认让每次构建 die）；两版本布局与迁移已实测 | 真机上"旧 `.98` 安装升级后首次直接启动"整链路未跑 |
| 训练题 | 有可判定题型、失败不放行 | 只有数字排序一种，没难度/内容分级 |

### FAIL
**无。**

### NOT_TESTED（6 类）
真机/模拟器全部渲染与交互 · APK 打包与真包校验 · 原生表单真机截图 · `dsh plugin add` 加载 ·
`real-dsh-tools` 那 1 个 skip · **PROOT `.101` × `L2sMigration` 的真机旧安装升级整链路**
（版本间布局与转换器行为已在 Linux 上实测，见 `R4-HARDENING-REPORT.md` §11.7）

---

## 七、这一轮最重要的更正

**上一轮报告写「WSL 挂了（E_UNEXPECTED），本机无法构建」。这个判断是错的。**

```
$ wsl -d Ubuntu -- uname -a
  wsl: Failed to translate 'E:\VMware\bin\'     ← Windows PATH 里有个 Linux 不存在的路径
  Linux LAPTOP-HANAC4Q6 6.6.87.2-microsoft-standard-WSL2 ... x86_64 GNU/Linux
  exit=0                                          ← 命令其实跑成功了
```

真因是 **Windows PATH 携带 `E:\VMware\bin\`**，导致 `wsl.exe` 打印告警并以非 0 退出，**但 Linux 命令正常执行**。

把这条误读成"环境坏了"，让上一轮把一个**本机可解**的问题记成了硬阻塞。这一轮我亲自复核推翻了它，
然后在同一台机器上真建出了两个 ABI。

**教训**：报"环境阻塞"之前，先确认输出到底是命令失败还是包装器失败。

---

## 八、建议你验收时按这个顺序

1. **先跑 A 组**（7 条，一分钟）—— 确认没人动过不该动的分支
2. **再跑 B3 一条**（`verify-outputs`）—— 一条命令看完 G1′ 六项判据
3. **再跑 C1 + C5**（内核 172 + partner 97）—— 代码级质量
4. **最后看 D 组**（回归对照）—— 确认测试多了但没坏
5. **§六 PARTIAL 那 4 条，逐条问我"为什么没做完"** —— 我给的答案在报告 §13

---

## 九、下一阶段需要你提供的

**只有一件：一台 ARM64 手机连上 adb。**

有了它，按 `R4-HARDENING-REPORT.md` §14.3 的 **24 条**走一遍，就能回答那个还没人回答的问题：
**"它到底能不能用。"**

在那之前，任何"已经能在手机上跑"的说法都不成立——**我不会那么说。**
