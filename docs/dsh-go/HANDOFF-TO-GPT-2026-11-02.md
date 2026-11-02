# DSH Go × 一手 · 交接给 GPT（2026-11-02）

> 写给接手做计划的 GPT。这份文件自成一体：现状、代码在哪、已验证 / 未验证、待决策、下一步、协作规矩。
> 产品总纲以用户的《主动式 AI 认知与行动辅助系统 · PROJECT MASTER BRIEF V1.0》为准，这里不重复。

---

## 1. 一句话现状

**P0-A「刷题闭环」的代码两边都写完、云端测试通过，手机安装包已出（一手测试版正在构建）；跨 App 联调还没在真机上做过。** 两个 PR / 分支都没有合并。

P0-A 链路：打开 B站 → **DSH Go** 拦截 → 点「去一手答一道题」→ **一手**出题库题并判卷 → 判卷通过后一手交回一次性凭证 → DSH Go 核对后放行 B站。
分权：**一手决定答没答对；DSH Go 只核对"这张凭证是不是这一次拦截的"。**

---

## 2. 代码在哪

### 仓库

| 仓库 | 内容 |
|---|---|
| `aozhenyu666-cmyk/dsh-Go`（代码里叫 dsh-go） | DSH Go（Flutter + Kotlin，Android） |
| `aozhenyu666-cmyk/test` | 一手（`yishou/`）、总控（`zongkong/`）等；本文档与所有审查/任务单在 `ccr-3a3a8f25-uzp2it` 分支的 `docs/dsh-go/` |

### DSH Go 分支（按时间先后，后一条包含前一条）

| 分支 | HEAD | 是什么 |
|---|---|---|
| `integration/r4-logic-closure-2026-11-01` | `868dc33` | 第三阶段（已冻结） |
| `fix/r4-phase4-2026-11-01` → `fix/r4-transactional-state-2026-11-01` | `7d2ef4d` | 第四阶段：拦截生命周期、原子快照（当时未接线） |
| `fix/r4-wiring-2026-11-02` | `caeba65` | W 轮：快照/迁移/失败提醒接进生产 |
| `fix/r4-snapshot-fix-2026-11-02` | `bf47bbf` | F 轮：设置页暂停毒化快照、提交失败回滚等 |
| `fix/r4-device-prep-2026-11-02` / `integration/r4-p4-2026-11-02` | `16f8e1e` / `086c2e7` | G 轮：退还扣费修复、调试失败开关 |
| `build/r4-phone-apk-2026-11-02` | `68525a3` | 审查方：修 G2 编译错误、PRoot→.102、云端出 APK |
| **`feat/p0-yishou-loop-2026-11-02`** | **`9bd93fb`** | **审查方：P0-A DSH Go 侧核心（当前最新）** |

### 一手分支 / PR（test 仓库）

| 分支 | HEAD | 是什么 |
|---|---|---|
| `ccr-a5172662-j1jcik` | `ec5f302` | 一手正式开发线，发布 `yishou-latest` |
| **`feat/dsh-handoff-2026-11-02`（PR #50，草稿）** | **`04b2d6e`** | DeepSeek：P0-A 一手侧（Y1–Y7） |
| `test/yishou-dsh-handoff-apk` | `a1fe511` | 审查方：= PR #50 + 一个出手机测试版的构建配置 |

### P0-A 关键文件

| 侧 | 文件 | 作用 |
|---|---|---|
| DSH Go | `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/loop/core/YishouProtocol.kt` | 协议 v1：action、extras 名、`parsePass` |
| DSH Go | `.../loop/core/Intercept.kt` | `InterceptGuard.beginHandoff / cancelHandoff / onExternalPass / expireHandoff`；交接期间切到任何目标 APP → EXITED 并重新判定 |
| DSH Go | `.../loop/intercept/InterceptRuntime.kt` | `handOffToYishou`、交接 15 分钟计时、交接中暂停页面 10 分钟上限、`onYishouPass` |
| DSH Go | `.../loop/intercept/YishouPassActivity.kt` | 接收一手凭证（导出、无界面） |
| DSH Go | `.../loop/intercept/InterceptPageActivity.kt` | 「去一手答一道题」按钮；交接中自己被遮住不算离开 |
| DSH Go | `app/android/app/src/test/.../loop/core/InterceptYishouHandoffTest.kt` | 15 个测试，全部经由生产入口 `InterceptKernel.open` |
| 一手 | `yishou/app/src/main/java/com/yishou/app/dsh/DshHandoff.kt` | 协议镜像、`parseRequest`、`passExtras`、`blockedReason`、`shouldSend` |
| 一手 | `yishou/app/src/main/java/com/yishou/app/dsh/DshTrainActivity.kt` | 导出的校验跳板；真正的思考页不导出 |
| 一手 | `yishou/app/src/main/java/com/yishou/app/gate/GateViewModel.kt` | 只在 `finishGrant(..., verified = true)` 时回传凭证 |
| 一手 | `yishou/docs/dsh-handoff.md` | 协议与使用说明 |

### 协议 v1（两边已对齐，别改名）

DSH Go → 一手：`com.yishou.app.action.TRAIN_FOR_DSH`，extras `dsh_protocol`(=1) `dsh_event_id` `dsh_nonce` `dsh_target_package` `dsh_target_label` `dsh_reply_package`
一手 → DSH Go：`io.github.ccch1mneyyy.dsh_app.action.YISHOU_PASS`，extras `dsh_protocol` `dsh_event_id` `dsh_nonce`(原样) `dsh_pass_minutes`(1–120) `dsh_ref`
规则：nonce 每次拦截一个、只发给 `com.yishou.app`、只用一次；交接最长 15 分钟，超时记"已拦截"，绝不放行。

---

## 3. 手机安装包

| App | 下载页 | 状态 |
|---|---|---|
| DSH Go R4 | https://github.com/aozhenyu666-cmyk/dsh-Go/releases/tag/r4-p4-build-4 | ✅ 已发布。SHA256 `608521ed60a2e5d303b7b2c93b91dda5c84f4f765474e825bf19c88ba6432722`。包名 `io.github.ccch1mneyyy.dsh_app.r4`，与官方版并存。PRoot 5.1.107.102 |
| 一手交接测试版 | https://github.com/aozhenyu666-cmyk/test/releases/tag/yishou-dsh-handoff-test | ⏳ 交接时构建中（run 41425613529）。成功后页面里有 APK 和 `APK-SHA256.txt`。包名 `com.yishou.app`、与 `yishou-latest` 同签名同版本：直接覆盖，数据保留 |

一手测试版如果失败：只看 `.github/workflows/yishou-dsh-handoff-test.yml`（test 仓库 `test/yishou-dsh-handoff-apk` 分支），不要改 `yishou/` 代码。

---

## 4. 已验证 / 未验证

| 项 | 状态 | 证据 |
|---|---|---|
| DSH Go 内核测试 | ✅ 294 项通过 + 接线检查通过 | dsh-Go Actions run 41400537822（CI 先跑测试再编译） |
| DSH Go APK 编译与运行时校验 | ✅ | 同上 |
| 一手单元测试 + 打包 | ✅ | PR #50 的 `build` 检查 |
| 一手模拟器集成测试 | ❌ 两台都失败，**失败用例与基线 `ec5f302` 完全相同**：API34 `VoiceIntegrationTest`；API29 再加 `GateQuestionsIntegrationTest.oldBankCardWithoutPointsStillGivesAQuestion` | test Actions run 41422085272 vs 41374474826 |
| 两个 App 在手机上联调 | ❌ **从未做过** | — |
| 用户实际体验 R4 拦截（build-3） | 用户反馈"问题不大，但是毛坯房" | — |

---

## 5. 下一步（按顺序）

1. **真机联调 8 步**（详见 `docs/dsh-go/P0A-PR50-REVIEW-ROUND2-AND-PHONE-TEST.md`）。先在一手关注名单里去掉 B站。
   1. 打开 B站 → 拦截页有「去一手答一道题」
   2. 答对 → 自动回到 B站 可用
   3. DSH Go 记录最新一条是放行，原因含 `yishou`
   4. 进一手按返回 → 回到拦截页
   5. 进一手不答 → 切 B站 → **又被拦**
   6. 进一手不答 → 切抖音 → **被拦**
   7. 进一手放 15 分钟 → B站 仍被拦
   8. 一手陪练窗口中 → 提示不能答题、只能回拦截页
2. 1–7 全"是"之后：合并 PR #50 → `ccr-a5172662-j1jcik`；DSH Go `feat/p0-yishou-loop-2026-11-02` 合进 R4 集成分支（只快进，不 force push）。
3. P0 闭环剩下的部分（总纲要求"回写总控"）：总控（`com.zongkong.app`，test 仓库 `zongkong/`，分支 `deepseek/zk-client-v2`）还没接。建议协议照 P0-A：任务 ID + 一次性密钥 + 结果回执。
4. 已知但没做的产品缺口：
   - DSH Go 主动交流的「回应」按钮是**假回复**（不打开会话、不发消息就记"已回复"）；
   - DSH Go 白名单**加了撤不掉**；
   - DSH Go 插件在手机上找不到宿主包（`examples/plugins/dsh-go-partner/lib/hostdeps.mjs` 漏了 `/opt/dsh/node_modules`），手机上配置表单可能出不来；
   - 一手两项模拟器集成测试长期红着；
   - 一手"判卷服务临时不可用"时提示写成"题库空、也没配模型"，措辞不准。

---

## 6. 待用户决定（一直没拍板）

| # | 问题 | 现状 | 审查方建议 |
|---|---|---|---|
| D1 | 拦截页停留到上限（10 分钟） | 代码已是：记"已拦截"、回桌面、不放行 | 确认即可 |
| D4 | 旧数据两份文件对不上时 | 停在旧格式并黄字提示 | 照样迁移并记录问题（保留白名单），停旧格式不多保住任何数据 |
| — | 一手 PR 改了**普通模式**：测试页、陪练中选对题不再放行 | 已写进 PR #50 正文 | 看起来是修旧漏洞，需要用户知情 |
| — | "授权规则：放行 N 分钟"按钮实际打不开（窗口内本来就自动放行） | 未改 | 去掉按钮，补上授权时段配置 |
| — | 主动交流「回应」 | 未改 | 做真闭环：打开指定会话、发送成功才算回复 |

---

## 7. 协作规矩（这几轮的教训，务必沿用）

1. **不让执行方（DeepSeek）自己定计划、自己判"通过"。** 它长时间自主跑时，写了几千行，关键机制却没接进生产。改成"小任务单 + 每步验收命令 + 独立复核"之后才可靠。
2. 任务单要写到**文件和行号**，给出现成代码片段，规定"验收不过就停下贴原文"。
3. 状态只用 **DONE / STOPPED**。没在手机上跑过的，不叫"通过"。
4. **测试必须走生产入口**。DSH Go 用 `InterceptKernel.open(临时目录)`，不许手写构造对象（以前 24 项测试全绿，三个机制却从没在生产里跑过）。
5. **内核测试绿 ≠ App 能编译**。G2 轮就是测试全绿、App 编译不过。现在 DSH Go 的 CI 先跑内核测试，再完整编译 APK；改了 Android 代码必须看完整编译的结果。
6. 汇报要写**失败**，不只写成功（PR #50 首轮漏报了集成测试失败）。
7. 用户没有电脑：代码推到 GitHub → 云端测试并编译 → 用户在手机上从 Release 页面下载。DSH Go 用 `.github/workflows/build-r4-phone-apk.yml`（新分支要加进 `on.push.branches`）；一手用 `yishou-android.yml`（只有 `ccr-a5172662-j1jcik` 发布）或测试分支上的 `yishou-dsh-handoff-test.yml`。
8. 一手仓库规矩见 `yishou/AGENTS.md`：中文提交、不顺手重构、先纯逻辑后 Android、不在提交里写模型名。

---

## 8. 本轮所有文档（test 仓库 `ccr-3a3a8f25-uzp2it` 分支，`docs/dsh-go/`）

| 文件 | 内容 |
|---|---|
| `R4-PHASE4-ARCH-REVIEW.md` | 第三阶段架构审查（5 P0 / 12 P1 / 9 P2）与第四阶段方案 |
| `R4-NEXT-WIRING-ORDER.md` | W0–W4 接线任务单 |
| `R4-NEXT-FIX-ORDER-2.md` | F0–F7 快照修复任务单 |
| `R4-NEXT-DEVICE-ORDER-3.md` | G0–G5 真机准备任务单 |
| `R4-NEXT-RUN-ORDER-4.md` | H0–H4（PRoot .102、模拟器；后来改为云端构建） |
| `P0A-YISHOU-ORDER-1.md` | P0-A 一手侧任务单 Y1–Y4 |
| `P0A-PR50-REVIEW-AND-ORDER-2.md` | PR #50 第一轮审查 + Y5–Y7 |
| `P0A-PR50-REVIEW-ROUND2-AND-PHONE-TEST.md` | PR #50 第二轮审查 + 8 步手机联调 |
| `HANDOFF-TO-GPT-2026-11-02.md` | 本文件 |
