# HANDOVER · DEV（电脑端主线维护与构建体系）

**分支**：`fix/r3-minutes-merge`（worktree `D:\code\R4\wt-dev`，与 R3 基线 `f1423dc1` 同一 git 仓库）
**本轮结论**：R3 的自由分钟输入**本来就在**基线上，本轮只做"确认没被带坏" + 选择性移植 + 构建体系审计。
**不合并 main、不发 Release、不碰手机、不跑模拟器、不构建 APK。**

---

## 1. 一句话交代交接文档的方向错误

交接文档说"fix 分支缺 `_editMinutes`，要把自由输入加回来"，**方向是反的**。
R4-TRUTH.md §3 与 lead 的逐字读已定案，本轮复核一致：

| | `proactive/on-1.0.4` (6f326699) | R3 基线 `f1423dc1` |
|---|---|---|
| 间隔输入 | `_cycleMin`/`_cycleMax` + `minIntervalSteps=[15,30,45,60]` 只能循环预设 | `_editMinutes()` + `_MinutesDialog` 自由输入 |
| `_editMinutes` 出现 | 0 次 | 3 次（定义 + 两处 `ListPageRow.onTap`） |
| `minIntervalSteps` 出现 | 2 次 | 0 次 |
| `_MinutesDialog` | 无 | 有（`StatefulWidget`，自持 controller，1–10080 校验） |

**所以被回退的是 `proactive` 那一支。** 本分支是从 `6f326699` 出发、把 R3 的三个必要提交
cherry-pick 回来，不是"从 f1423dc1 上再补一次"。

---

## 2. 5 个提交的取舍（逐条判断 + 理由）

compare `6f326699...f1423dc1` = ahead 5 / behind 0，4 个文件。

| # | 提交 | 带？ | 理由 |
|---|---|---|---|
| 1 | `ad4aeac5` fix(proactive): restore editable random interval dialog from R3 | **带** | 就是 R3 的自由分钟输入本体：删 `_cycleMin/_cycleMax`/`*IntervalSteps`，加 `_editMinutes()` + `_MinutesDialog`。它是"保留 R3 行为"的唯一来源。 |
| 2 | `cfcf5f2e` test(proactive): restore typed interval and input validation coverage | **带** | 跟着 #1 的测试：打字输入、`0`/`99999` 被拒不下发、min 越过 max 时一起抬。没有它 #1 是无覆盖的行为改动。 |
| 3 | `9b00b641` ci: build full Android arm64 R3 APK and publish test release | **不带** | 它新增的 `.github/workflows/build-r3-phone-apk.yml` 是**一次性出 R3 测试包的私用通道**：写死包名 `.mine`、应用名 "DSH Go R3"、`--build-number 10311445`、`gh release create r3-1.0.4-build-<n> --prerelease`、`retention-days: 14`，还把 `applicationId` 现场 python 改掉。R4 主线已有 `tooling/build-apk.sh` 负责同一件事（versionCode = 构建分钟），再进一份并行 CI 会造成：两套包名/签名口径、Release 命名冲突、以及"推送即发 Release"这种不该进主线的副作用。**判断：不进 R4 主线**，需要时从 R3 tag/branch 取。 |
| 4 | `4e2216d5` ci: use available Termux proot binary and robust Android package verification | **不带（但把真问题挪进脚本）** | 它两件事都只改上面那个 workflow：① 用 python 现场把脚本里的 `termux_deb proot "$PROOT_VERSION"` 改成 `"5.1.107.101"`；② 把 aapt 定位改成 `$ANDROID_HOME/build-tools` + `test -x`。①**是真实故障**（我复核了 Termux 索引：`.98` 已消失、只剩 `.101`，脚本默认会 `die`），但修法应该在 `runtime/build-runtime.sh` 而不是在 CI 里 sed 脚本；② 属于该 workflow 的私有步骤。**判断：workflow 不进主线；proot pin 以更好的形式落进 `build-runtime.sh`**（见 §5）。**运行时行为不动**：`PROOT_VERSION` 默认仍是 `.98`，只加注释 + 环境变量开关，因为 `.101` 改了 link2symlink 的 l2s 布局，而 `L2sMigration.kt` 是按 `.98` 写的，必须先真机验证。 |
| 5 | `f1423dc1` fix(runtime): prune empty excluded plugin test directories from APK | **带** | 4 行：`add_tree()` 里遇到被排除的目录时 `dirnames[:] = []; continue`，不再只丢文件留下空目录。它让 `plugin test/ not shipped` 这条 APK 检查真正成立（此前 `opt/dsh-go/dsh-go-proactive/test/` 空目录会进 rootfs）。纯打包正确性修复，和 CI 无关。 |

---

## 3. 本分支的提交

从 `6f326699` 起 3 个 cherry-pick（均带 `-x`，原 SHA 写在提交说明里）：

```
7a9682b fix(proactive): restore editable random interval dialog from R3      (from ad4aeac5)
73edd83 test(proactive): restore typed interval and input validation coverage (from cfcf5f2e)
d3c4281 fix(runtime): prune empty excluded plugin test directories from APK   (from f1423dc1)
```

之后的审计改动见 §5。备份分支：`backup/r3-full-5commits` = `f1423dc1`（原 5 提交全量，未删）。

与原 R3 分支的差异（`git diff f1423dc1 HEAD`）**只应该少一个文件**：
`.github/workflows/build-r3-phone-apk.yml`（即我们主动排除的 #3/#4）。

---

## 4. 文件与 R3 的异同（任务 1 第 4 问）

```bash
git diff --stat f1423dc1 HEAD
#  .github/workflows/build-r3-phone-apk.yml | 89 --------------------------------   ← 有意排除
```

| 检查项 | 结论 |
|---|---|
| `app/lib/ui/phone/proactive_page.dart` 与 R3 是否一致 | **一致**（`git diff f1423dc1 HEAD -- app/lib/...` 为空） |
| `app/test/phone/proactive_page_test.dart` 与 R3 是否一致 | **一致** |
| `runtime/lib/rtbuild.py` 的 prune 修复 | **一致** |
| **有没有重复实现**（两套间隔编辑逻辑） | **没有**。`_editMinutes` 仅 3 处引用、`_cycleMin/_cycleMax/minIntervalSteps/maxIntervalSteps` 在本分支 `git grep` **零命中**（见 §6 证据）。全仓 `minIntervalMs` 的写入点只有 `_editMinutes` 一处 + `proactive_state.dart` 的模型/序列化。 |
| **有没有不该带入的改动** | **没有**。唯一的额外文件是本轮审计新增的（§5），全部与运行时/构建断言相关，没有一行碰 R3 的主动搭话行为。 |
| 内核侧是否被要求"再改一次" | **没有，也不该改**。`ProactiveScheduler.kt:29-31` 已经 `coerceIn(60_000L, 7*86_400_000L)` 并保证 min ≤ max；UI 的 1–10080 只是提前拒绝废话，两者一致。 |

---

## 5. 构建体系审计（任务 2）——摘要，全文见 `docs/build/runtime-and-abis.md`

**G1′ 判据仍未达成，本轮标 NOT_TESTED（WSL 不可用）。** 五个问题的结论：

| Q | 结论 |
|---|---|
| build-runtime.sh 真支持两个 ABI？ | **是**。`case` 映射五组参数，两份 Ubuntu/Node/CPython/Termux 输入各自 pin，每个 ABI 结尾用 `file -b` + `build-report.json` 全量 ELF machine 双校验，错一个就 `die`。**缺陷**：`write_index()` 对缺失 ABI 静默 `continue` 且 `main()` 仍 exit 0。 |
| Gradle 会静默跳过不存在的 ABI 目录？ | **会，已实测复现**（最小 Gradle 9.3.1 工程：`srcDir` 不存在 → `resourceFiles=0`，BUILD SUCCESSFUL）。**已修**：`checkRuntimeArm64/X64` 任务 + 资产合并 `dependsOn`，缺 manifest/jniLibs 直接 `GradleException`。实测 `checkRuntimeX64 FAILED`。 |
| manifest 能记录两个 ABI？ | 逐 ABI manifest 有 `abi`/`rootfs`/`jniLibs` 全套哈希；索引 `runtime/out/manifest.json` 的 `abis` 靠 `write_index()` 扫目录生成，**缺项不会报错** → 由新 `verify-outputs` 兜住。 |
| verify_apk_runtime.py 能识别缺失运行时？ | **修之前不能**：合成"有 assets、无 jniLibs"的 APK **10 项全 PASS、退出码 0**（假 PASS）。**已修**：新增 4 项检查（abi 一致 / 四个 .so 在不在 / 与 manifest 逐字节一致 / manifest 是否列出），同 APK 现在 3 项 FAIL。 |
| 两个 ABI 的 APK 各自含 libflutter/libproot/rootfs？ | 设计上分别接线（`abiFilters` 单值、`<abi>/jniLibs`、`<abi>/assets`、rootfs 文件名自带 ABI、manifest 自带 `abi`）。**真 assemble 未做 → NOT_TESTED**。 |

### 5.1 本轮构建体系改动（代码）

| 文件 | 改动 |
|---|---|
| `runtime/lib/rtbuild.py` | 新增 `verify-outputs` 子命令（G1′ 机器判据）；`main()` 现在传回退出码 |
| `runtime/tests/test_verify_outputs.py` | 新增，**9 tests OK** |
| `runtime/build-runtime.sh` | `main()` 末尾 `verify-outputs` + `die`；补 `proot 5.1.107.101` 三个 pin；`PROOT_VERSION` 可覆盖 |
| `app/android/app/build.gradle.kts` | `checkRuntimeArm64/X64` + 资产合并依赖 |
| `tooling/verify_apk_runtime.py` | 4 项原生库/ABI 检查 |
| `tooling/test_verify_apk_runtime.py` | 合成 APK 带 `lib/<abi>/*.so`；新增 3 tests |
| `docs/build/runtime-and-abis.md` | 审计全文 + 可复现命令 |

### 5.2 顺带查出的两个真故障

1. **默认 proot 已下不到**：Termux 索引只剩 `5.1.107.101`（aarch64/x86_64 都查了）。用脚本里的 awk 原样复跑：
   `.98 → NO RECORD`（脚本会 die）、`.101 → OK`（哈希已 pin）。这正是 `4e2216d5` 在 CI 里 sed 脚本的原因。
2. **`.101` 改了 link2symlink 的 l2s 布局**（新增 `.proot.l2s.` 中间名等，已逐字 diff 源码），
   而 `L2sMigration.kt` 按 `.98` 写。**换版本前必须真机验证**，所以默认值没动。

---

## 6. 验证结果（真实输出）

### 6.1 `flutter analyze`（`D:\code\R4\wt-dev\app`）

```
Analyzing app...
No issues found! (ran in 39.7s)
```

### 6.2 `flutter test test/phone/proactive_page_test.dart`

```
00:00 +1: wire a receipt keeps the six moments apart
00:00 +2: wire status survives missing and odd fields
00:00 +3: page shows the state, the honest wake warning and the receipts
00:02 +4: page the switch, gaps, quiet hours and daily limit write config
00:03 +5: page a typed gap outside the range is refused, not sent
00:04 +6: page the gaps can never cross
00:04 +7: page a missing transport is stated instead of pretending
00:04 +8: page the target session can be picked from the live ones
00:04 +9: page the target can be cleared again
00:04 +10: page an unreadable session list is explained, not hidden
00:05 +11: page no live session is said plainly
00:06 +12: page a damaged kernel state is surfaced
00:06 +13: page phone control links to it
00:07 +13: All tests passed!
```

**13 passed.** 间隔输入与边界三条都在：`+4`（打字写入 config）、`+5`（`0`/`99999` 被拒且不下发）、
`+6`（min 抬过 max 时一起抬）。

加上 `test/proactive_channel_test.dart` 一起跑：`00:07 +25: All tests passed!` —— **25 passed**。

`test/phone` 全目录（21 个非 golden 测试文件）+ `proactive_channel_test.dart`：
`00:33 +203: All tests passed!` —— **203 passed**。

### 6.3 golden 失败：既有问题，与本次改动无关（已对照证明）

```
flutter test test/phone        → 01:14 +200 -36: Some tests failed.
flutter test -r compact (全仓) → 08:58 +3102 ~265 -350: Some tests failed.
```

36 个失败**全部是 golden 像素比对**（`phone_golden_test.dart` 32 个、`phone_floating_bar_test.dart` 4 个），
例：`Golden "goldens/phone_main_dark.png": Pixel test failed, 2.78%, 74530px diff detected.`。
全仓 350 个失败同理（`*_golden_test.dart` 占绝大多数）。

**证明是既有问题**：完全未改动的 `D:\code\R4\wt-m1`（`f1423dc1`，同一份 `app/`）上跑同样的文件：

```
flutter test test/phone/phone_golden_test.dart          → 01:30 +0 -32: Some tests failed.
flutter test test/phone/phone_floating_bar_test.dart    → 00:18 +9 -4: Some tests failed.
Golden "goldens/phone_floating_bar_light.png": Pixel test failed, 2.63%, 34641px diff detected.
```

同样的 32 + 4。且 `git diff f1423dc1 HEAD -- app/lib app/test` **输出为空** —— 本分支的
`app/lib` 与 `app/test` 与 R3 基线逐字节相同，golden 失败不可能由本分支引入。
根因大概率是本机字体（`C:\dev\fonts_extract` 里的 Noto CJK 与生成基线时那份不同字号/字重），
按 `MAINTENANCE.md` §9「截图基线只有在干净官方版通过时才更新」，**本轮不动 golden、不 `--update-goldens`**。

> 环境注记：本机 `flutter test` 需要 `DSH_TEST_FONT_DIR=C:\dev\fonts_extract\usr\share\fonts\noto-cjk`
> （仓库默认找 Linux 的 `/usr/share/fonts/noto-cjk`），且 shell 环境缺 `LOCALAPPDATA`/`PUB_CACHE`，
> 需显式设为 `C:\Users\<user>\AppData\Local\Pub\Cache`。二者都是环境问题，与代码无关。

### 6.4 `r4-verify.ps1 -Target d-core`（loop 内核离线 JVM 基线）

```
=== loop kernel offline JVM: D:\code\R4\wt-m1
  PASS  93 tests
=== loop kernel offline JVM: D:\code\R4\wt-m2
  PASS  93 tests
=== loop kernel offline JVM: D:\code\R4\wt-dev
  PASS  93 tests
```

注：`r4-verify.ps1` 读 `$env:USERPROFILE`，本会话的 shell 环境为空，需先
`$env:USERPROFILE=[Environment]::GetFolderPath('UserProfile')` 再调用（否则全部 COMPILE FAIL）。

### 6.5 python 侧

```
python -I runtime/tests/test_proactive_packaging.py   → Ran 4 tests ... OK
python -I runtime/tests/test_verify_outputs.py        → Ran 9 tests ... OK
cd tooling; python -I -m unittest discover -s . -p "test_verify*.py" → Ran 8 tests ... OK
python -I runtime/lib/rtbuild.py verify-outputs --out runtime/out --abi arm64-v8a x86_64
  → exit 1，G1' FAIL（本机确实没有 runtime/out，断言按预期报错）
```

### 6.6 其它既有缺陷（本轮不修）

- `r4-verify.ps1 -Target d-core`：`wt-m1 / wt-m2 / wt-dev` 三处 **PASS 93 tests**（本机实跑）。
- `tooling/test_build_number.py` 的 2 个 `BuildApkDryRunTest` 在 Windows 上失败
  （`build-apk.sh` 经 cmd 调 bash 拿不到 stdout）。在**未改动的** `D:\code\R4\wt-m1`
  （f1423dc1）上同样 `Ran 12 tests ... FAILED (failures=2)` —— 既有缺陷。
- `runtime/tests/test_patch_dsh.py`：5 个失败/错误，`repo` 的 `core.autocrlf=true` 让
  `.diff` 变 CRLF；在 `git checkout-index` 出来的新副本上同样失败 → 既有缺陷，
  且本次改动没碰 `patch-dsh`。Linux 上按仓库 `.gitattributes` 是 LF，不会复现。

---

## 7. 未完成项与下一步

| 优先级 | 事项 | 阻塞 |
|---|---|---|
| P0 | 拿到一台可用的 x86_64 Linux（修 WSL / Docker / Actions），跑 `PROOT_VERSION=5.1.107.101 bash runtime/build-runtime.sh`，让 G1′ 真正成立 | WSL `E_UNEXPECTED` |
| P0 | 有 `runtime/out` 后 `tooling/build-apk.sh -f all`，两个 APK 各跑一次 `verify_apk_runtime.py` | 同上 |
| P0 | 真机冒烟（`docs/loop/VALIDATION.md` §3） | adb 0 台设备 |
| P1 | proot 5.1.107.101 × `L2sMigration.kt` 的真机回归；通过后把默认值从 `.98` 提到 `.101`，同步 `THIRD_PARTY_NOTICES.md` 的版本行 | 需真机 |
| P1 | CI workflow（R3 那份）如果要进主线，应重写成用 `tooling/build-apk.sh` + 不写死 `.mine`/build-number/Release | 设计决策 |
| P2 | 修 `tooling/test_build_number.py` 的两次 dry-run 测试在 Windows 下的 stdout 编码问题 | 既有缺陷 |

---

## 8. 纪律自查

- 没有 `git add -A`：只 add 了本次改动的 7 个路径。
- 没有 push main、没有 force push、没动别人的 worktree（`wt-m1/m2/m3` 只读跑过测试）。
- 没有构建 APK、没跑模拟器、没碰真机。
- 所有构建/日志/产物都在 `D:\code\R4\` 下。
