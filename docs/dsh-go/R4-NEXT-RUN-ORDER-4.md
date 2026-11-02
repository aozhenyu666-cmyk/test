# DSH Go · 第四张指令单：修编译错误 → PRoot .102 → 模拟器首轮（H0–H4）

> 起点：`integration/r4-p4-2026-11-02` @ `086c2e7`。

## G0–G3 复核结论（审查方）

| 项 | 复核 |
|---|---|
| G1 退还扣费 | ✅ 发现得好：退还额在**普通路径**上也一直是 0（`endOpen` 先把三个字段清零了），比审查单描述的更严重。`transact` 现在也会还原这三个字段（`Intercept.kt:867-880`） |
| G2 调试开关 | ❌ **Android 端编译不过**。`InterceptRuntime` 是 `object`（43 行），389 行却声明了 `private companion object`。Kotlin 2.2.21 实测：`error: modifier 'companion' is not applicable inside 'standalone object'`。内核测试不编译这个文件，所以 278 项照样是绿的。**G2 没有跑 Gradle 编译。** 用 `FLAG_DEBUGGABLE` 代替 `BuildConfig.DEBUG` 是可以接受的 |
| G3 集成分支 | ✅ 存在，但里面带着上面这个编译错误 |
| G4 停在 aarch64 | ✅ 停得对。审查方从源码核实：termux/proot `v5.1.107.101..v5.1.107.102` 只有两个提交——`7471c71`（只改 `/proc/<pid>/fd` 的 readlink 名字回填，`recall_fd`/`readlink_proc_fd`）和修改主页链接。`move_and_symlink_path`、`decrement_link_count` **没有改动**，硬链接的存储布局不变 |

## 负责人的决定（用户粘贴时请把每一行改成自己的选择）

```
决定1 PRoot：升到 5.1.107.102          （备选：自己镜像 .101）
决定2 模拟器：接受 x86_64 模拟器作为第一轮证据，全部标注 EMULATOR；部署前仍必须有 arm64 真机一轮
决定3 D1：确认。页面停留到上限 → 记 BLOCKED(page_ttl)、回桌面、不放行
决定4 旧数据对不上：照样迁移并记录 recoveryIssue   （备选：维持停在旧格式并提示）
批准构建：是
```

---

## 直接粘贴给 DeepSeek 的话（连同上面的决定块一起贴）

```
你在 dsh-go 仓库工作，读 docs/dsh-go/R4-NEXT-RUN-ORDER-4.md，按 H0–H4 顺序做。上面"负责人的决定"就是本轮授权。
规则：
- 新分支 fix/r4-device-run-2026-11-02 从 086c2e7 切出；不 force push。完成后把 integration/r4-p4-2026-11-02 快进到它（只能快进）。
- 新规则：任何改动 app/android 下文件的提交，都必须跑
  tooling/compile-android.sh（H1 新建），并把最后 5 行贴进提交说明。内核测试绿 ≠ App 能编译。
- 先红后绿；验收不过就停下回报原文；状态只写 DONE / STOPPED(原因)。
- 模拟器上的每条结果都标 EMULATOR，不得写成"真机通过"。
- 只更新 WIRING-RESULT.md（≤80 行）。
```

### H0 · 准备
```bash
git switch -c fix/r4-device-run-2026-11-02 086c2e7
bash tooling/run-kernel-tests.sh                # OK (≥280 tests) + wiring OK
```

### H1 · 修 G2 的编译错误，并把 Android 编译变成固定步骤
1. **先红**：新建 `tooling/compile-android.sh`，内容是
   `cd app/android && gradle --no-daemon --offline -Pkotlin.incremental=false :app:compileArm64DebugKotlin :app:compileX64DebugKotlin`
   （仓库里没有 gradlew，用本机 `gradle`，与以前 `hard-android.ps1` 的命令一致）。
   在 `086c2e7` 上跑一次，**必须看到** `modifier 'companion' is not applicable inside 'standalone object'`，原文贴进提交说明。
2. **再绿**：`InterceptRuntime.kt:389-392` 删掉 `private companion object { ... }`，改成在 object 体内直接写 `private const val DEBUG_FAIL_START_MARKER = "debug-fail-start"`。
3. `run-kernel-tests.sh` 的 wiring 检查第 5 条如果依赖 companion 的写法，同步调整。
4. 验收：
   - `compile-android.sh` 两个 flavor 都 BUILD SUCCESSFUL；
   - `run-kernel-tests.sh` OK + wiring OK；
   - `find app/android/app/build -name 'InterceptRuntime*.class' -newer tooling/compile-android.sh` 有输出（防止再编译到旧代码，W 轮出过这个问题）。

### H2 · PRoot 升到 5.1.107.102（决定1）
1. `runtime/build-runtime.sh`：
   - `PROOT_VERSION` 默认值改成 `5.1.107.102`；
   - 新增 `proot_5.1.107.102_aarch64.deb`、`proot_5.1.107.102_x86_64.deb`、`proot-v5.1.107.102.zip` 三个哈希。前两个取自 Termux 索引 `Packages` 的 `SHA256:` 行，原文贴进提交说明；源码 zip 自己下载后算哈希。
   - `.101` 的哈希保留，注释写"仅用于复现旧构建，索引已不可达"。
2. 更新 `THIRD_PARTY_NOTICES.md` 和 licence 行（照 `ea932c9` 当时改 .101 的方式）。
3. **真二进制采集**：新建 `runtime/tests/direct/l2s-capture.sh`。在 x86_64 Linux 上用 .102 deb 解出的 proot（`--link2symlink`，`PROOT_L2S_DIR` 指向临时 store）跑：
   - 建一个文件；
   - `ln` 出 3 个名字，再 `rm` 掉其中 1 个；
   - 用 `find <store> -printf '%y %f -> %l\n'` 采集，结果写入 `app/android/app/src/test/resources/l2s-102-capture.txt` 并提交。
4. `L2sMigrationTest.theStoreAReleaseLeavesBehindIsTheOneThisConverts` 改成读取这个采集文件来造夹具（不再手写）；同时把 602-637 行的 `link101` 改名为 `linkHypotheticalOneHop`，相关测试名去掉 `proot101`。
5. 在 Linux **非 root** 用户下跑 L2sMigrationTest，必须全部 OK。
6. `runtime/build-runtime.sh`（两个 ABI）→ `python -m unittest runtime.tests.test_verify_outputs` 全过；两个 ABI 的 `strings libproot.so | grep 5.1.107` 都必须是 `.102`。
7. 把下载到的 `.102` 两个 deb 和源码 zip 复制到仓库外的固定缓存目录，并在 `docs/build/runtime-and-abis.md` 写明路径和哈希。下次 Termux 再滚版本时，可以用缓存复现。

### H3 · x86_64 模拟器首轮（决定2，全部标 EMULATOR）
```bash
tooling/build-apk.sh --debug -f x64
python tooling/verify_apk_runtime.py <apk>
adb -s <emulator> install -r <apk>
```
按 `tooling/device-smoke.md` 第 1–9 条逐条做，每条保存：
- `adb logcat -s DshIntercept:V DshLoop:V` 原文 → `evidence/emulator/2026-11-02/<编号>.log`；
- 有界面就截图 → `<编号>.png`。

结果表：EMULATOR-PASS / EMULATOR-FAIL / NOT_RUN（附原因），每条附日志关键行。

第 2 条（熄屏 + 锁屏进 APP）和第 4 条（不保留活动）在模拟器上也要跑，但备注栏必须写"ROM 行为未覆盖，需要 arm64 真机"。

**任何一条 EMULATOR-FAIL：停下，不修，先回报。**

### H4 · arm64 APK 只构建不安装
```bash
tooling/build-apk.sh --debug -f arm64
python tooling/verify_apk_runtime.py <apk>
```
记录 APK 的 sha256。没有 arm64 真机就写 `STOPPED(no arm64 device)`，**不准用模拟器顶替**。

## 决定4 的实现（只有决定块写的是"照样迁移"才做；否则跳过）
- `InterceptMigration.kt`：计数与回执对不上（检查 2，以及 127-135 行的白名单检查）时，**不再返回 `RecoveryRequired`**。改为照常迁移：保留旧 `guard.json` 里的全部 config（包括白名单），保留旧 ledger 的计数和回执，**不补造任何回执**，并在快照里写 `recoveryIssue = "migrated_with_inconsistency: <原检查的 detail>"`，设置页用 F4 的黄字显示。
- 只有文件**读不出来**（Unreadable）时才继续返回 `RecoveryRequired`。
- 红测试：照 `InterceptMigrationTest` 里现有的"白名单多于放行数"用例 → 期望改为 `Migrated`，白名单保留，`recoveryIssue` 非空。**这是负责人的决定改变了预期，不算"改测试预期绕过"，提交说明里写明依据是决定4。**

## 仍然不做
主动交流真实回复闭环、白名单撤销入口、hostdeps.mjs / rc.4、其余 D2–D7。
