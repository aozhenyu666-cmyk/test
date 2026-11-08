# 运行时与双 ABI：审计结论、可复现构建、断言

**状态：审计 PASS（静态 + 逆向验证），构建 NOT_TESTED（本机 WSL 不可用）**

G1′ 判据 = `runtime/out/` 下 `arm64-v8a` 与 `x86_64` 两个目录真实存在，且
`runtime/out/manifest.json` 的 `abis` 同时含两项。本轮**没有产出**这两个目录：
`wsl -d Ubuntu` 返回 `E_UNEXPECTED / E_ACCESSDENIED`，本机没有任何 x86_64 Linux 环境，
而 `runtime/build-runtime.sh` 第 3 行写明 "entirely on an x86_64 Linux host"。**所以 G1′ 是
NOT_TESTED，不是已验证。** 本文交付的是：审计结论 + 可直接粘的构建命令 + 把"静默跳过"补成硬失败。

---

## 1. 五个问题的答案

### Q1 `build-runtime.sh` 是否真实支持 arm64-v8a 和 x86_64 两个 ABI？

**是，代码层面完整支持，且两条路径的输入各自独立。**

- `build_abi()` 用 `case` 把 ABI 映射到五组参数，未知 ABI 直接 `die`：
  `arm64-v8a → arm64/arm64/arm64/aarch64`、`x86_64 → amd64/x64/x64/x86_64`。
  与 `rtbuild.py` 的 `ABI` 表（machine 183 / 62）一致。
- 每个 ABI 的输入都是**架构专属**的，没有"一个包喂两个 ABI"：
  Ubuntu Base（arm64 / amd64 两个 tarball，各自 pin）、Node（arm64 / x64）、
  CPython（aarch64 / x86_64 两份 python-build-standalone）、
  Termux 的 proot / libtalloc / libandroid-shmem（aarch64 / x86_64 三个包各两份 deb）。
  `build_abi` 里每个 ABI 都单独 `rm -rf "$out"` 再生产，互不覆盖。
- 每个 ABI 结束时自带两道 ABI 校验：`file -b` 检查四个 jniLib 的 ELF 名称，再遍历
  `build-report.json` 里**rootfs 内全部 ELF** 比对 `machine`，错一个就 `die`。
  这是"两个 ABI 真的各自成包"的最强证据 —— 它检查的是产物内容，不是命令行参数。
- `main()` 默认 `abis=(x86_64 arm64-v8a)`，也可只给一个。

**但有一处会在无提示的情况下退化**（已修，见 Q4）：`write_index()` 找不到某个 ABI 的
`manifest.json` 时 `continue`，索引里就少了那一项，而 `main()` 仍然 exit 0。

### Q2 Gradle 会不会静默跳过不存在的 ABI 目录？

**会。这是本轮查到的真漏洞，已加断言修掉。**

`build.gradle.kts` 的 `sourceSets { getByName("x64") { assets.srcDir(File(runtimeOut, "x86_64/assets")) } }`
指向的是**磁盘上可能不存在**的目录。Gradle 的 `SourceDirectorySet` 对不存在的目录**不报错、不警告**，
当作空集（我写了一个最小 Gradle 9.3.1 工程实测：`resourceDirs` 里三个路径都在，
`resourceFiles=0`，构建仍成功）。后果是：`runtime/out` 完全不存在时，
`flutter build apk --flavor x64` 依然 **BUILD SUCCESSFUL**，打出一个没有 rootfs、没有 proot 的包，
装到模拟器上启动即失败，而构建日志里一个字都没有。

修法（`app/android/app/build.gradle.kts`）：新增 `:app:checkRuntimeArm64` /
`:app:checkRuntimeX64` 两个 verification 任务，**flavor 的资产合并任务依赖它们**：

```
tasks.matching { it.name.matches(Regex("merge(Arm64|X64)(Debug|Profile|Release)Assets")) }
    .configureEach { dependsOn(runtimeChecks[flavor]) }
```

任务里检查 `runtime/out/<abi>/assets/runtime/manifest.json` 与
`runtime/out/<abi>/jniLibs/<abi>/lib{proot,proot-loader,talloc,android-shmem}.so` 存在，
缺任何一个抛 `GradleException`，并直接给出该跑哪条命令。实测输出（`runtime/out` 为空时）：

```
> Task :app:checkRuntimeX64 FAILED
Execution failed for task ':app:checkRuntimeX64'.
> no runtime for x86_64: D:\code\R4\wt-dev\runtime\out\x86_64\assets\runtime\manifest.json is missing.
  The x64 flavor would ship an APK with no rootfs (and no proot), and Gradle ignores a sourceSets
  srcDir that does not exist, so this would otherwise still print BUILD SUCCESSFUL. ...
BUILD FAILED in 56s
```

### Q3 `manifest.json` 能否正确记录两个 ABI？

**单 ABI 的 `runtime/out/<abi>/assets/runtime/manifest.json` 能；索引 `runtime/out/manifest.json`
能，但依赖上一步真的跑过。**

- 逐 ABI 的 manifest 由 `rtbuild.py cmd_manifest` 写，`"abi": args.abi` 直接来自命令行，
  加上 `rootfs` / `rootfsSha256` / `rootfsBytes` / `tarBytes` / `entries` / `jniLibs`（四个 .so 的
  sha256、bytes、ELF machine、needed、soname、minLoadAlign）/ 版本三元组 / `launch` 模板。
  里面没有 `abis` 复数键 —— 复数键只在索引里。
- 索引由 `write_index()` 扫 `x86_64`、`arm64-v8a` 两个目录生成 `{"schema":1,"abis":{...}}`。
  **它是"扫到什么就写什么"**：某个 ABI 没构建，索引就静默少一项（Q1 末尾那条）。
  已由 `rtbuild.py verify-outputs` 兜住：索引缺项 → 直接 FAIL。

### Q4 能不能识别缺失的运行时？

- **修之前：不能。** `tooling/verify_apk_runtime.py` 只校验 assets 侧的
  `manifest.json` / rootfs / 插件文件，**从不看 `lib/<abi>/*.so`**，
  也不校验 manifest 的 `abi` 是否和 APK 里的 ABI 目录一致。
  我用一个"有 assets、没有 jniLibs"的合成 APK 实测：**10 项全 PASS**，退出码 0 —— **假 PASS**。
- **修之后：能。** 新增三项检查（在 `tooling/verify_apk_runtime.py`）：

  | 检查 | 判据 |
  |---|---|
  | `manifest names an ABI the APK carries` | manifest.`abi` ∈ {arm64-v8a, x86_64} **且** APK 里 `lib/` 下只有一个 ABI 目录，且与 manifest 一致 |
  | `proot binaries in the APK` | `lib/<abi>/lib{proot,proot-loader,talloc,android-shmem}.so` 四个都在 |
  | `proot binaries match the manifest` | 每个 .so 的 sha256（和 bytes）与 manifest.`jniLibs` 记录一致 |
  | `manifest lists the proot binaries` | manifest.`jniLibs` 四个键齐 |

  同一个合成 APK 现在输出：

  ```
  PASS  runtime manifest in APK
  PASS  manifest names dshGoProactive
  PASS  launch passes the web patch
  PASS  rootfs archive in APK
  PASS  rootfs sha256 matches manifest
  FAIL  manifest names an ABI the APK carries
  FAIL  proot binaries in the APK
  FAIL  manifest lists the proot binaries
  ```

  并新增 `runtime/lib/rtbuild.py verify-outputs`（G1′ 的机器判据）：
  逐 ABI 检查 manifest、rootfs 的 sha256/bytes、四个 jniLib 的 sha256/bytes/ELF machine，
  再检查索引 `abis` 是否含全部请求的 ABI。空目录输出：

  ```
  FAIL  arm64-v8a: runtime/out\arm64-v8a\assets\runtime\manifest.json missing
  FAIL  x86_64: runtime/out\x86_64\assets\runtime\manifest.json missing
  FAIL  runtime/out\manifest.json missing
  G1' FAIL: runtime/out is not a complete bundle for arm64-v8a, x86_64
  ```

  `build-runtime.sh` 的 `main()` 现在最后调用它（`die` 兜底），所以"两个 ABI 都真的在"
  从"人工看 directory"变成"脚本 exit code"。

### Q5 不同 ABI 的 APK 是否真正各自包含 libflutter.so / libproot.so / rootfs？

**设计上是的，且两半都由 flavor 单独接线；但"同一个 APK 里混进另一个 ABI 的东西"这件事
目前只有本轮的检查能看到一半。**

| 内容 | 装法 | 按 flavor 分开？ |
|---|---|---|
| `libflutter.so` / `libapp.so` | Flutter Gradle 插件按 `--target-platform android-arm64\|android-x64` + `ndk.abiFilters` 产出，落在 `lib/<abi>/` | 是（`abiFilters` 单个值） |
| `libproot.so` 等四个 | `jniLibs.srcDir(File(runtimeOut, "<abi>/jniLibs"))`，路径里已按 ABI 分目录，脚本又逐个校验 ELF machine | 是（但目录不存在时静默为空 → Q2） |
| rootfs `rootfs-<abi>.tar.xz` | `assets.srcDir(File(runtimeOut, "<abi>/assets"))`，文件名自带 ABI，manifest 里 `abi` 字段也自带 | 是 |
| `useLegacyPackaging = true` | 必须：proot 要以文件形式解到 nativeLibraryDir 才能 exec | 全局 |

补充证据：`packaging.jniLibs.keepDebugSymbols` 只列出这四个 `lib*.so`，
说明打包链路确实按名字处理它们（而非依赖 AGP 的 strip）。本轮新增的
`proot binaries match the manifest` 检查把"APK 里的 .so 与构建时记录的是同一份字节"钉住，
跨 ABI 混装（例如 arm64 的包塞进 x86_64 的 libproot.so）会因 manifest.`abi` 与 `lib/` 目录不一致
或 machine 不同而被抓出来。

**NOT_TESTED**：真正 assemble 两个 flavor 再各自解包核对 —— 需要先有 `runtime/out`，
而它需要 x86_64 Linux（本机 WSL 挂了）。见第 3 节命令。

---

## 2. 本轮改了什么（可审查清单）

| 文件 | 改动 | 为什么 |
|---|---|---|
| `runtime/lib/rtbuild.py` | 新增 `verify-outputs` 子命令（`verify_outputs()` + 逐 ABI 校验 + 索引校验）；`main()` 现在 `sys.exit(rc)` | G1′ 的机器判据；此前 `main()` 丢弃子命令返回值 |
| `runtime/tests/test_verify_outputs.py` | 新增 9 个测试（完整树 PASS、空树 FAIL、单 ABI FAIL、缺 jniLib FAIL、sha 不符 FAIL、abi 字段不符 FAIL、索引缺项 FAIL、缺 rootfs FAIL、退出码） | 让断言本身有回归 |
| `runtime/build-runtime.sh` | `main()` 末尾跑 `verify-outputs` 并 `die`；补 proot 5.1.107.101 三个 pin；`PROOT_VERSION` 可用环境变量覆盖；注释写明 .98 已从 Termux 索引消失、.101 改了 l2s 布局 | 见下"顺带修的两个真故障" |
| `app/android/app/build.gradle.kts` | 新增 `checkRuntimeArm64` / `checkRuntimeX64`，资产合并任务依赖之 | 把 Gradle 的静默跳过变成硬失败 |
| `tooling/verify_apk_runtime.py` | 新增 4 项原生库/ABI 检查 | 消掉"没有 jniLibs 也全 PASS"的假 PASS |
| `tooling/test_verify_apk_runtime.py` | 合成 APK 现在带 `lib/<abi>/*.so`；新增 3 个测试（空 runtime/out、字节不符、x86_64 与 abi 不符） | 同上 |

### 顺带修的两个真故障（都在本轮实测复现，不是推测）

1. **默认 proot 版本已经下不到了。** `termux_deb proot "$PROOT_VERSION"` 会在 Termux 索引里
   找 `5.1.107.98`，而 2026-11-01 的 `Packages`（aarch64 与 x86_64 都查了）只剩
   `5.1.107.101`。用脚本里那段 awk 原样复跑：
   ```
   proot 5.1.107.98  -> NO RECORD（脚本会 die "Termux index no longer has proot 5.1.107.98"）
   proot 5.1.107.101 -> OK: a28355e9…（aarch64） 85adb8d0…（x86_64）
   ```
   这也正是 R3 那条 CI 提交 `4e2216d5` 在 workflow 里用 python `sed` 现场改脚本的原因 ——
   它绕的是真问题，但绕在 CI 里而不是脚本里。本轮把 pin 和开关放进脚本本身，
   并给出明确注释（**默认值仍留 .98**，因为升级 proot 需要真机回归，见下一条）。
2. **proot 5.1.107.101 改了 link2symlink 的 l2s 布局。** 我下了 .98 与 .101 的源码包逐字比对
   `src/extension/link2symlink/link2symlink.c`：.101 新增 `.proot.l2s.` 中间名、
   `decrement_intermediate_count`、unlink/rename 的 removed_link 记录等。
   `app/.../runtime/L2sMigration.kt` 的注释和实现是按 **.98** 的
   `.l2s.<name><NNNN>.<MMMM>` 写的，并把 `PROOT_L2S_DIR={ROOTFS}/root/.l2s` 固定写进 launch 模板。
   **所以换 .101 前必须先真机验证 L2sMigration**，否则"PRoot 装的树直接 native 启动"这条路径会读到
   未迁移的 .l2s 名字。本轮**没有改** L2sMigration（不归本模块，且没有真机），只把风险写进脚本注释、
   本文和 MAINTENANCE 的待办。

---

## 3. 可复现构建方案（下一步直接粘）

### 3.1 先解决 host：WSL 起不来

```powershell
wsl --status
wsl -d Ubuntu -- uname -a          # 现在返回 E_UNEXPECTED / E_ACCESSDENIED
wsl --shutdown; wsl -d Ubuntu -- uname -a
```

若仍起不来，按优先级换 host（都能跑 `build-runtime.sh`，因为脚本只要求 x86_64 Linux +
curl/sha256sum/python3/tar/xz，并且 `--offline` 之外不假设发行版）：

1. 修复 WSL（`Get-WindowsOptionalFeature -Online -FeatureName Microsoft-Windows-Subsystem-Linux`；
   必要时 `wsl --update`）。
2. Docker Desktop（WSL2 backend 起来后）：
   `docker run --rm -it -v D:/code/R4:/w -w /w/wt-dev debian:bookworm bash`
3. 已有 GitHub Actions 的 ubuntu-24.04（`.github/workflows/build-r3-phone-apk.yml` 就是这么干的）。

### 3.2 在 x86_64 Linux 里构建（两条都要求 `runtime/out/` 落盘到 D 盘）

```bash
cd /d/code/R4/wt-dev            # git-bash 写法；WSL 里是 /mnt/d/code/R4/wt-dev
export PROOT_VERSION=5.1.107.101   # .98 已不在 Termux 索引里（见上）
bash runtime/build-runtime.sh      # 两个 ABI，x86_64 先、arm64-v8a 后；脚本自己跑 G1′ 断言
```

只想先要手机那个：

```bash
bash runtime/build-runtime.sh arm64-v8a
```

产物（G1′ 要求的正是前两个目录）：

```text
runtime/out/arm64-v8a/assets/runtime/rootfs-arm64-v8a.tar.xz
runtime/out/arm64-v8a/assets/runtime/manifest.json
runtime/out/arm64-v8a/jniLibs/arm64-v8a/lib{proot,proot-loader,talloc,android-shmem}.so
runtime/out/x86_64/…（同构）
runtime/out/manifest.json          # abis 必须同时含 arm64-v8a 与 x86_64
```

### 3.3 构建完立刻验收（这条是 G1′ 的机器判据，Windows 上也能跑）

```powershell
cd D:\code\R4\wt-dev
python -I runtime/lib/rtbuild.py verify-outputs --out runtime/out --abi arm64-v8a x86_64
# 期望最后一行：G1' PASS: runtime/out holds a real runtime bundle for arm64-v8a, x86_64
python -c "import json;print(json.load(open('runtime/out/manifest.json'))['abis'].keys())"
```

### 3.4 打 APK（两个 flavor 都要，且现在缺运行时会直接失败）

```bash
cd /d/code/R4/wt-dev
tooling/build-apk.sh --dry-run -f all      # 先看命令
tooling/build-apk.sh -f all                # 本地双架构验证
```

Windows 上只要 `runtime/out` 已经有了，就可以直接用 Flutter 打（Gradle 断言同样生效）：

```powershell
cd D:\code\R4\wt-dev\app
flutter build apk --release --flavor arm64 --target-platform android-arm64
flutter build apk --release --flavor x64   --target-platform android-x64
```

### 3.5 打完逐个核内容（不是看 BUILD SUCCESSFUL）

```powershell
cd D:\code\R4\wt-dev
python -I tooling/verify_apk_runtime.py app/build/app/outputs/flutter-apk/app-arm64-release.apk --plugin runtime/proactive/dsh-go-proactive
python -I tooling/verify_apk_runtime.py app/build/app/outputs/flutter-apk/app-x64-release.apk   --plugin runtime/proactive/dsh-go-proactive
```

两个都必须 `OK`。任一 `FAIL` 都不得称为"包含主动搭话 / 可运行"。

### 3.6 回归测试（本机可跑，本轮全绿）

```powershell
python -I runtime/tests/test_proactive_packaging.py        # 4 tests
python -I runtime/tests/test_verify_outputs.py             # 9 tests
cd tooling; python -I -m unittest discover -s . -p "test_verify*.py"   # 8 tests
```

---

## 4. 本轮验证口径

| 项 | 结果 |
|---|---|
| 两个 ABI 的源码级支持（参数映射、两份输入、ELF 双校验） | **PASS**（读代码 + 复跑 Termux 索引 awk） |
| Gradle 静默跳过不存在 srcDir | **PASS（复现成功）** → 已修，修后实测 `checkRuntimeX64 FAILED` |
| manifest 记录 ABI（逐 ABI + 索引） | **PASS**（代码 + 9 个单测） |
| verify_apk_runtime 假 PASS | **PASS（复现成功：10 项全 PASS）** → 已修，修后 3 项 FAIL |
| `runtime/out` 真有 arm64-v8a 与 x86_64、索引含两项（G1′） | **NOT_TESTED**：本机 WSL 不可用，无 x86_64 Linux host |
| 真 assemble 两个 flavor 并逐包核对 libflutter/libproot/rootfs | **NOT_TESTED**：同上，必须先有 `runtime/out` |
| proot 5.1.107.101 与 `L2sMigration` 的兼容性 | **NOT_TESTED**：需要真机 |
| `flutter analyze` / `flutter test` / python 单测 | **PASS**（数字见交接报告） |

---

## 5. 剩余风险

1. **G1′ 仍未达成**，依赖一台可用的 x86_64 Linux host。断言已就位，但没有产物。
2. **proot 5.1.107.101 与 L2sMigration.kt 的 l2s 布局差异**未在真机验证；脚本默认仍是 .98
   （现在会 `die` 并提示），要出包必须显式 `PROOT_VERSION=5.1.107.101`。
3. **索引 `manifest.json` 的 `abis` 只反映"这次扫描看到什么"**，历史 ABI 可能过期；
   `verify-outputs` 只保证"请求的 ABI 都在且自洽"。
4. **`tooling/test_build_number.py` 的两个 dry-run 测试在 Windows 上失败**（`build-apk.sh`
   经 cmd 调 bash 拿不到输出）。**这是既有缺陷**，在未改动的 `D:\code\R4\wt-m1`（f1423dc1）
   上同样 2 FAIL，与本次改动无关，本轮不修。
