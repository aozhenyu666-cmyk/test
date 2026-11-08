# EXECUTION_STATE

最后更新：2026-10-31 08:45（Asia/Shanghai）
工作副本：`D:\code\dshgo`（C 盘满，已从 `C:\work\dshgo` 迁移；git 历史完整）
分支：`feat/proactive-integration`（候选测试版），`review/d5-loop-to-kernel`（补丁，待 Claude 审）

## 当前阶段

阶段 A–C 完成，阶段 D 的 D1–D4 完成，D5 已出独立补丁未合并。APK 已构建。

## 最近成功提交

| SHA | 内容 |
|---|---|
| be80fc2 | D4 打包 proactive 宿主插件（runtime） |
| 41be916 | D3 主动搭话设置页 |
| 8187696 | D1 Dart 转发 + D2 通知端口 + 三个内核风险修复 |
| c949906 | 0003 proactive Android 适配（Claude，冲突已解） |
| aa64058 | 0002 proactive 内核（Claude，零冲突） |
| 43c2c99 | T2 设置页补全 + T9 隐私披露 |
| a691371 | P0-01 / P1-01 / P1-02 三项缺陷修复 |
| 95ec7d6 | P0 基线记录 |
| bc72be3 | 0001 event-loop-v1（Claude 原型，干净应用） |
| 10c3519 | 上游 d38a39a 基线 |
| aa8876b | D5（在 review/d5-loop-to-kernel 分支，不在集成分支） |

## 已完成测试

| 层 | 结果 |
|---|---|
| JVM（loop 包，含 Claude 内核） | **96/96 通过** |
| Flutter 新增页面（loop + proactive） | **34/34 通过** |
| 插件纯逻辑 `node --test test/core.test.mjs` | **10/10 通过** |
| `flutter analyze` | **No issues found** |
| `gradlew :app:assembleArm64Debug` | **BUILD SUCCESSFUL** |
| APK | 125.9 MB，SHA256 `53d879bae229155548e40f770356e5e8efa92089512f087e8e4efa43de623f28` |

## 当前错误

无。三个环境阻塞已逐一解决并记录在 BUILD_REPORT.md：
非 ASCII 路径（Gradle/AGP）、Gradle worker argfile 编码、pub cache 跨盘 junction rename。

## 已尝试的解决办法（关键三条）

1. 上游 331MB 仓库 `git clone` 反复 RPC reset → 改用 GitHub API tree + curl 并行按文件下载，
   逐文件校验字节数一致（3300 个文件全部匹配）。
2. `Your project path contains non-ASCII characters` → 工作副本迁到纯 ASCII 路径。
3. `ClassNotFoundException: GradleWorkerMain` → `GRADLE_USER_HOME` 指向 ASCII 路径。
4. `磁盘空间不足`（C 盘）→ 工作区与缓存迁到 D 盘，`PUB_CACHE` 用真实目录（junction 跨盘
   rename 会 errno 17）。

## 下一步操作

1. 交给 Claude 审核：`PATCHES/0004-d5-loop-to-kernel.patch` 与 `CLAUDE_REVIEW.md`。
2. D6 真机验证全部 NOT_TESTED，需人工按 ROUND2.md 第 6 节补证据。
3. 若要把 D5 并入候选版：先审阅，再 `git am PATCHES/0004-*.patch` 到集成分支并重跑回归。
4. runtime 打包（D4）与 `test-host.sh` 的 proactive 端到端需要 Linux + bwrap，本机 NOT_TESTED。

## 待 Claude 审核内容

- D5：第一轮 `app_enter` 事件改走内核（触及两轮接缝）。
- `ProactiveRuntime.holdAgent/flushHeldAgent`：AgentPort 未就绪时暂存并重放，是否与内核
  自身 recovery 语义重复（当前实现只在适配层，不改内核）。
- `NotificationPortImpl` 从不回报 Shown 的取舍。
- ROUND2.md 第 7 节 Claude 自列的 8 个问题。

## 2026-10-31 第二轮更新（任务更新后）

- **D3 补齐目标会话选择**：`ProactiveAgentSessions` + `agentSessions`/`agentSessionSet` 通道；
  Kotlin 侧 `ProactiveRuntime.fetchPluginRoute` 一次性转发插件 `/status` 与 `/config`（`status 0`
  保持"没连上"与"插件拒绝"可区分）；页面列出活着的会话与空闲/忙状态，含"不指定"与三种失败说明。
  测试：`proactive_page_test` 12/12（新增 4 例）。提交 `a67bf3b`。
- **真实引擎 + 假模型端到端跑通**：本机装 `@deepseek-ai/dsh@0.2.1-rc.4`，跑
  `test/e2e-live.mjs`，**ALL PASS 26/26**，证据 `evidence/e2e-live-real-engine-2026-10-31.log`。
  这是"插件 + 协议 + 引擎"这半边，**不是真机验证**。
- APK 重建：SHA-256 `5ce608df245f419225f533bf3586e943b933e6e5a3a9704cb1d985c4ea7d58e4`，
  包名 `io.github.ccch1mneyyy.dsh_app`，debug 签名。

### 本轮新增的环境阻塞与解法

| 现象 | 根因 | 解法 |
|---|---|---|
| `spawn EFTYPE` / `spawn EINVAL` | Windows 上 `.js` 与 `.bat` 都不能被 `spawn` 直接执行 | 在仓库外复制一份 e2e，加 `shell: true` 并固定插件目录；另建 `dsh-bin.bat` 串 node + `lib/bin.js` |
| mock 请求日志读不到 | mock 用 `LOG` 环境变量（默认 `requests.jsonl`） | 起 mock 时传 `LOG=D:\code\logs\mockreq.jsonl` |
| `npm` 不存在（只有裸 `node.exe`） | 本机 node 是单文件发行 | 走 `D:\node_modules\corepack` 里的 npm 12.2.1 |

### 下一步

1. 等 Claude 审 `PATCHES/0004-d5-loop-to-kernel.patch`。
2. 真机项（T0/D6）留待用户手动验收，未做任何设备操作。
3. 如需把 D5 并入候选版：审阅后 `git am PATCHES/0004-*.patch` 到集成分支并重跑 96 项 + 34 项。
