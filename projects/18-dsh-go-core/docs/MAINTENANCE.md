# DSH Go 自用分支：维护、升级、部署与扩展指南

**读者**：接手这个项目的 AI（GPT / DeepSeek / Claude）和项目负责人。动手前先读完第 1、2、8 节。

**一句话**：我们在官方 DSH Go 上做**深度集成的自用分支**（不向官方提交），把自己的代码集中在自有目录里，对官方文件只做少量登记过的"接缝"改动；官方每次发版，把我们的提交整体挪到新版本上，跑固定检查，再打包部署到手机。

---

## 1. 决策记录：为什么是"底层集成的自用分支"

| 方案 | 能做到 | 做不到 / 代价 | 结论 |
|---|---|---|---|
| 工作流 / 技能 / 提示词 | 让 Agent 按某种方式说话、做事 | 碰不到 Android：无障碍事件、弹页面、闹钟都没有；约束靠模型自觉 | 只适合"内容和语气"类需求 |
| DSH 引擎插件（如 `dsh-go-proactive`、dsh-speak-up） | 访问会话、Agent 回合、工具执行拦截 | 跑在手机里的 Linux 引擎中，碰不到 Android 系统能力；依赖引擎内部接口 | 作为"Agent 那一半"使用 |
| DSH Go 原生插件协议（面板、胶囊、状态条） | 不改 App 就能加界面 | 只能由用户点按触发，3 秒窗口，不能后台主动 | 适合纯界面类扩展 |
| 独立的伴随 App | 与官方完全解耦 | 第二个无障碍服务、重复授权；看不到 DSH 是否在操作手机，"DSH 忙就让路"做不到 | 不采用 |
| **在 DSH Go 里底层集成（采用）** | 监听手机事件、和 DSH 的任务与确认弹窗仲裁、弹页面、闹钟、插件联动 | 每次官方更新要跟进；要自己打包、签名、部署 | **采用，用本文的方法控制成本** |
| 把钩子提交给官方 | 长期零维护 | 官方不一定接受，节奏不由我们 | **不做**，自己维护 |

---

## 2. 仓库与分支布局（aozhenyu666-cmyk/dsh-Go）

| 分支 | 作用 | 规则 |
|---|---|---|
| `main` | **官方镜像**：与 `ccch1mneyyy/dsh-go` 的 `main` 完全一致 | 只做快进同步（`--ff-only`），**永远不提交我们的东西** |
| `proactive/on-<官方版本>`，当前 `proactive/on-1.0.4` | **我们的工作分支**，起点是那个官方版本的真实提交 | 日常开发在这里；每个官方版本开一个新分支，旧的保留用于回退；**不要 force-push** |
| `feat/proactive-integration`、`review/d5-loop-to-kernel`、`review/r3-core-fixes` | 历史分支 | **冻结，只读**。它们的起点是"下载文件当基线"的快照提交，和官方没有共同 git 历史，不能再在上面开发或打包 |

`proactive/on-1.0.4` 的提交按层次排列（这个顺序以后也保持）：

1. 内核与第一轮闭环（Kotlin，只有新文件，含测试）
2. 宿主插件、运行时打包测试、APK 校验工具（只有新文件）
3. Flutter 界面、Dart 转发、文案（只有新文件）
4. **接缝**：对官方文件的全部手改，加上重新生成的多语言文件
5. 文档（`projects/18-dsh-go-core/`、`docs/loop/`）

升级时只有第 4 个提交会冲突，前 3 个都是新文件。

---

## 3. 我们的代码在哪（自有目录，升级时不冲突）

| 目录 | 内容 |
|---|---|
| `app/android/app/src/main/kotlin/.../dsh_app/loop/` | 第一轮闭环：前台观察、访问状态机、交互页、诊断、与内核的结果回写（`KernelMirror`） |
| `.../loop/core/` | **主动交互内核**（纯 Kotlin）：仲裁 `Coordinator`、调度 `ProactiveScheduler`、开关职责 `Switches`、Agent 协议 `AgentWire`、原子存储 |
| `.../loop/proactive/` | Android 适配：读取 DSH 状态组装闸门、执行内核命令、令牌页面、通知、闹钟与开机接收器、`dsh/proactive` 通道 |
| `app/android/app/src/test/kotlin/.../loop/` | JVM 测试（108 个） |
| `app/lib/loop/`、`app/lib/proactive/`、`app/lib/state/proactive_transport.dart`、`app/lib/ui/phone/loop_test_page.dart`、`proactive_page.dart` | Flutter 侧 |
| `app/lib/l10n/parts/loop_*.arb`、`proactive_*.arb` | 我们的文案 |
| `runtime/proactive/dsh-go-proactive/` | **DSH 宿主插件**（Agent 回合、工具守卫）及其测试 |
| `runtime/tests/test_proactive_packaging.py`、`tooling/verify_apk_runtime.py` | 打包配方测试、APK 内容校验 |

---

## 4. 接缝清单（对官方文件的全部改动）

**这张表是升级时唯一需要人工核对的地方。新增任何接缝都必须登记到这里。**

| 官方文件 | 改了什么 | 约行数 | 官方改动它时要检查 |
|---|---|---|---|
| `AndroidManifest.xml` | 开机权限；交互页、主动页两个 Activity；唤醒接收器 | 33 | 1.0.4 已与官方新增的权限冲突过一次，两边都保留即可 |
| `MainActivity.kt` | 注册 `LoopPlugin`、`ProactivePlugin` | 2 | 插件注册方式是否变了 |
| `phone/PhonePrompts.kt` | 只读的 `busy()` 和 `onBusyChanged` 回调 | 8 | 提问是否仍全部经过 `schedule()` |
| `phone/a11y/PhoneA11yService.kt` | 连接、事件、断开时调用 `LoopRuntime` | 5 | 服务生命周期方法是否改名或拆分 |
| `app/lib/app.dart` | 启动 Agent 转发 | 4 | 启动流程 |
| `app/lib/ui/phone/phone_control_screen.dart` | "触发测试""主动搭话"入口 | 22 | 页面结构；会影响截图基线 |
| `app/lib/ui/phone/phone_disclosure_sheet.dart` + `l10n/parts/phone_*.arb` | 隐私说明里加一行（读前台包名） | 6 | 文案与实际行为必须一致 |
| `app/lib/update/update_config.dart` | 发布来源可由 `DSH_UPDATE_REPO` 覆盖（默认不变） | 8 | 更新逻辑是否改了地址的拼法 |
| `runtime/build-runtime.sh`、`runtime/lib/rtbuild.py`、`runtime/test-host.sh` | 把宿主插件打进 rootfs，并写进 `dsh-web.patch.yml` | 约 75 | 官方的 rootfs 打包方式（参照官方 `dsh-media` 的做法） |
| `app/test/phone/goldens/phone_disclosure_{light,dark}.png` | 隐私说明多了一行后的截图基线 | — | 官方改这个弹层时重新生成 |
| `.gitattributes`（新增） | 强制 LF 换行，防止 Windows 环境把文件整体改成 CRLF | — | 官方若新增同名文件，合并规则 |
| **生成文件**：`app/lib/l10n/app_{zh,en}.arb`、`app/lib/l10n/gen/*` | 由 `tool/l10n_merge.py` 生成 | — | **永远不要手工合并，冲突时直接重新生成** |

---

## 5. 官方发新版时的升级流程

假设官方发布了 1.0.5，当前工作分支是 `proactive/on-1.0.4`，它的起点是 1.0.4 的提交 `bf01594`。

```sh
# 一次性：添加官方仓库
git remote add upstream https://github.com/ccch1mneyyy/dsh-go

# 1. 同步官方镜像
git fetch upstream
git checkout main && git merge --ff-only upstream/main && git push origin main

# 2. 从旧工作分支开新分支，把我们的提交挪到新版本上
git checkout -b proactive/on-1.0.5 proactive/on-1.0.4
git rebase --onto main bf01594          # bf01594 = 旧分支的官方起点（1.0.4）
```

冲突处理规则：

| 冲突文件 | 怎么办 |
|---|---|
| 生成的多语言文件（`app_*.arb`、`l10n/gen/*`） | 取官方版本（rebase 时是 `git checkout --ours <文件>`），继续；全部挪完后运行 `cd app && python3 tool/l10n_merge.py` 重新生成，提交 |
| Manifest、设置页等接缝文件 | 两边的改动都保留，对照第 4 节逐条核对我们的改动还在 |
| 我们自有目录里的文件 | 不应出现冲突；出现了说明官方新增了同名文件，停下来人工判断 |
| 截图基线 PNG | 先确认干净的官方版本上测试通过，再在我们的分支上 `--update-goldens` 重新生成，并人工看图确认只多了我们的内容 |

挪完后**必须**做的检查：

1. **引擎版本**：`grep '^DSH_VERSION=' runtime/build-runtime.sh`。如果和上一版不同（当前是 `0.2.1-rc.4`），插件依赖的引擎内部接口可能变了，必须重跑第 6 节第 4 项的真实引擎测试；不通过就不许发布。
2. **接口漂移**：内核读取的官方接口没变（`PhoneControl.peek()`、`tasks.isActive`、`tasks.callsInFlight`、`prompts`、`PhoneA11yService.isConnected()`、`TreeCapture.foregroundPackage`、`ProtectedPages`）。
3. 跑第 6 节全部检查。
4. 在新分支的 `projects/18-dsh-go-core/` 里记一笔：官方版本、冲突、检查结果。

---

## 6. 检查清单（每次提交、每次升级都跑）

| # | 检查 | 命令 | 通过标准 |
|---|---|---|---|
| 1 | Kotlin 单元测试 | `cd app/android && ./gradlew :app:testArm64DebugUnitTest --tests "io.github.ccch1mneyyy.dsh_app.loop.*"` | 全部通过（当前 108 个） |
| 2 | Dart 静态检查 | `cd app && flutter analyze` | No issues |
| 3 | Flutter 测试 | `flutter test test/phone test/proactive_channel_test.dart test/l10n test/update` | 全部通过；截图对比要在装了 Noto Sans CJK 的环境跑（`DSH_TEST_FONT_DIR`），字体不对时先在干净官方版上对照 |
| 4 | 插件 × 真实引擎 | 按 `runtime/locks/dsh-<版本>` 装引擎（锁文件里的 npmmirror 可替换成 registry.npmjs.org，完整性校验不变），然后 `node runtime/proactive/dsh-go-proactive/test/e2e-live.mjs <dsh> <端口> <假模型端口> <日志>` | ALL PASS（当前 32 项，含"主动回合里工具被拒、用户回合里同一工具正常执行"） |
| 5 | 插件单元测试 | `cd runtime/proactive/dsh-go-proactive && node --test test/core.test.mjs` | 全部通过（12） |
| 6 | 打包配方 | `python3 -I runtime/tests/test_proactive_packaging.py` | OK |
| 7 | APK 内容 | `python3 tooling/verify_apk_runtime.py <apk> --plugin runtime/proactive/dsh-go-proactive` | 全部 PASS。**不通过的 APK 不得称为"包含主动搭话"** |

---

## 7. 部署到手机

### 7.1 构建（需要两台环境，或一台 Linux）

1. **运行时（只能在 Linux x86_64 上）**：`runtime/build-runtime.sh`，产物在 `runtime/out/<abi>/`。没有这一步，APK 里就没有 rootfs，自然也没有插件。Windows 环境只能拿 Linux 产出的 `runtime/out/` 来打包。
2. **APK**：`cd app && flutter build apk --flavor arm64 …`（或 `gradlew :app:assembleArm64Release`）。构建参数里必须带：
   - `--dart-define=DSH_UPDATE_REPO=aozhenyu666-cmyk/dsh-go`：应用内更新只看我们自己的发布，不会提示去装官方版。
3. **校验**：第 6 节第 7 项，必须全部 PASS。

### 7.2 安装方式（不卸载手机上的官方版）

- **与官方版并存（推荐）**：构建时设置 `applicationIdSuffix = ".mine"`，应用名改成"DSH Go（自用）"。依据见 `TEST_APK_PLAN.md`：Provider authority、广播、数据目录都已随包名参数化。
- 两个 App 的数据完全独立；首次启动会再解压一份 rootfs（占几百 MB）。
- 无障碍、通知、Shizuku、自启动都要对新 App **重新授权**。
- **只开一个 DSH 的无障碍服务**：测试自用版时，在系统设置里关掉官方版的无障碍，否则两边会同时监听、同时弹窗。
- 用自己的固定签名（不要用 debug 签名发给别人）。签名不同的包不能互相覆盖安装。
- 许可：DSH Go 是 AGPL-3.0。只要把 APK 给别人用，就必须同时提供对应源码（我们的分支是公开的即可）。

### 7.3 首次使用

1. 打开"手机操控"，在系统设置里开启**自用版**的无障碍服务。
2. "主动搭话"页：先选**目标会话**（必须是已经打开过的会话，插件不会新建或唤醒会话）；再设间隔、免打扰、每日上限。
3. "触发测试"（可选）：选一个测试 APP。
4. 用 `docs/loop/VALIDATION.md` 第 3 节的步骤做真机冒烟，把结果记进 `VALIDATION_REPORT.md`。

---

## 8. 以后扩展功能：放在哪一层

原则：**放在能满足需求的最便宜的一层**。越往下，能力越强，但每次官方升级的成本也越高。

| 层 | 位置 | 适合 | 升级成本 |
|---|---|---|---|
| L0 工作流 / 技能 / 提示词 | DSH 技能、插件里的提示文本 | 说什么、怎么说、语气、话题 | 几乎为零 |
| L1 宿主插件 | `runtime/proactive/dsh-go-proactive/` | Agent 回合的发起、结果、工具限制、会话选择 | 引擎版本变化时重跑真实引擎测试 |
| L2 原生插件协议 | 独立的 DSH 插件 + `ctx.dshGo` | 用户点按触发的界面（面板、胶囊、状态条） | 低 |
| L3 内核 | `loop/core/` | 新的主动交互类型、调度规则、优先级、合并规则 | 低：纯 Kotlin，有测试，不碰官方代码 |
| L4 Android 适配 | `loop/`、`loop/proactive/` | 新的事件来源（日历、电量、通知）、新的展示方式 | 中：系统行为要真机验证 |
| L5 接缝 | 官方文件 | 以上都做不到时 | **高**：每行都是每次升级的成本，必须登记到第 4 节 |

示例：

| 未来需求 | 放哪 |
|---|---|
| 早上主动问候、晚上不打扰 | L3 调度配置（已有：间隔、免打扰、每日上限） |
| 换一种说话风格 | L0 / L1 的提示文本 |
| 根据日历或电量主动提醒 | L4 新事件来源 → 以 `EventRequest` 交给 L3，**必须经过 Coordinator** |
| 主动回合里允许 Agent 查天气 | L1：把守卫从"全部拒绝"改成白名单，**必须审核**，手机操控类工具永远不放开 |
| 主动消息的历史记录页 | Flutter 新文件 + L3 已有的回执数据 |

**任何层都必须遵守的红线**：

1. 一切主动展示都经过 `Coordinator`（不得自己弹页面或发通知）。
2. 不得调用 `PromptArbiter`、`PhonePrompts.present`、`PhoneTasks.begin`，不得伪造任务所有权。
3. 主动回合的工具守卫（`tools/pre-execute`）不得移除或放宽，除非是经审核的白名单。
4. 发给 Agent 的只有事实（包名、时间、计数），**永远不发屏幕内容**。
5. 插件不新建、不唤醒会话。
6. 不伪造 Agent 的文字：Agent 不可用就不说话（第一轮 `app_enter` 的固定测试文案除外）。
7. 隐私说明的文案必须和实际行为一致（比如现在"触发测试或主动搭话开启时读取前台包名"）。

---

## 9. 给 AI 协作者的工作规则

- 只在 `proactive/on-<版本>` 上工作，不改 `main`，不在冻结的历史分支上开发。
- **永远不要把官方代码"下载文件当基线"导入**；必须基于官方真实的 git 提交。
- 换行一律 LF，不写 BOM（仓库有 `.gitattributes`；Windows 上 `git config core.autocrlf false`）。
- 不删、不放宽测试；截图基线只有在"干净官方版通过、我们的分支有预期变化、人工看过图"时才更新。
- 改接缝要在提交说明里写明，并更新第 4 节。
- 涉及 `loop/core/**`、插件 `lib/**`、接缝文件的改动，提交前让 Claude 审核。
- 每份报告都标 PASS / FAIL / NOT_TESTED；没有真机证据的写 NOT_TESTED。`BUILD SUCCESSFUL` 不等于运行时里有插件。

---

## 10. 已知风险与权衡

| 风险 | 说明 | 现在的处理 |
|---|---|---|
| 依赖引擎内部接口 | 插件用到 `agents.get`、会话事件、`tools/pre-execute`，只在 0.2.1-rc.4 上验证过 | 升级流程第 1 项；待办：插件对未知引擎版本拒绝工作 |
| 官方改动集中在我们的接缝处 | 无障碍服务、Manifest、手机操控设置页是官方常改的地方 | 接缝清单 + 冲突规则 |
| 主动回合写进用户的会话 | 主动消息会出现在那个会话的历史里 | 设计如此（DSH 自己的回合），需要用户事先选好目标会话 |
| 真机验证缺失 | 所有 Android 行为都还是 NOT_TESTED | 第 7.3 节冒烟 |
| 运行时只能在 Linux 构建 | Windows 只能打 APK 外壳 | 第 7.1 节；用校验工具把关 |
| 第一轮的通知兜底已不可达 | D5 之后 `InteractionHub.show` 没人调用 | 待办：单独一个提交删除 |

---

## 11. 待办（按优先级）

| 优先级 | 事项 |
|---|---|
| P0 | 在构建机上跑第 6 节第 1 项（Gradle 测试）和 APK 构建 |
| P0 | Linux 上构建运行时 → 打 APK → `verify_apk_runtime.py` 全 PASS |
| P0 | 真机冒烟（7.3），结果写进 `VALIDATION_REPORT.md` |
| P1 | 插件启动时检查引擎版本，未验证过的版本拒绝发起主动回合 |
| P1 | 自用版构建配置：`applicationIdSuffix`、应用名、签名、`DSH_UPDATE_REPO` |
| P1 | 收窄接缝：Android 代码移到独立 Gradle 模块（页面声明靠 Manifest 自动合并，官方 Manifest 不再改）；Flutter 部分做成本地插件包（自动注册，`MainActivity` 不再改）；文案改用自己包里的语言文件（不再改官方生成文件）。完成后接缝只剩无障碍服务钩子、确认弹窗回调、设置页入口、运行时打包四处 |
| P2 | 删除第一轮不可达的通知兜底代码 |
| P2 | `AtomicJsonFile` 从备份恢复时写进 `Coordinator.recoveryIssue` |
