# DELIVERY（代码交付与审核入口）

> **2026-10-31 起，当前开发分支是 `proactive/on-1.0.4`（基于官方 1.0.4 的真实提交）。维护、升级、部署、扩展规则见 [MAINTENANCE.md](MAINTENANCE.md)。** 下文提到的 `feat/proactive-integration`、`review/*` 分支已冻结，仅作历史参考。

日期：2026-10-31
目的：让评审人能直接读**真实代码**，而不是只看任务完成报告。

## 1. 远程代码（我自己的 Fork，非上游）

上游 `ccch1mneyyy/dsh-Go` **只读、未推送任何东西**。所有分支推到我的 fork：

| 分支 | 提交 SHA | 链接 |
|---|---|---|
| `feat/proactive-integration`（集成候选版） | `67bd05b9f8127a48da3a5583673a5f5353c00002` | https://github.com/aozhenyu666-cmyk/dsh-Go/tree/feat/proactive-integration |
| `review/d5-loop-to-kernel`（D5，待审，**未合并**） | `aa8876b7195ad00d3df191609aecb432bf8a21ad` | https://github.com/aozhenyu666-cmyk/dsh-Go/tree/review/d5-loop-to-kernel |

远程 `git ls-remote` 已核对：两个 head 与本地 SHA **逐字一致**。

### 提交序列（集成分支，基线 `10c3519` = 上游 `d38a39a`）

| SHA | 内容 |
|---|---|
| `67bd05b` | docs: sanitize host paths in the build report |
| `0daaa34` | 真实引擎 e2e 证据 + D3 会话选择状态 |
| `a67bf3b` | **D3 目标会话选择** |
| `b23a8c9` | 交付文档八件套 + Claude 审核包 |
| `be80fc2` | **D4 打包宿主插件** |
| `41be916` | **D3 主动搭话设置页** |
| `8187696` | **D1 Dart 转发 + D2 通知端口 + 三个内核风险修复** |
| `c949906` | 0003 proactive Android 适配（Claude；`LoopRuntime` 冲突已手工合并） |
| `aa64058` | 0002 proactive 内核（Claude；零冲突） |
| `43c2c99` | T2 设置页补全 + T9 隐私披露 |
| `a691371` | **P0-01 / P1-01 / P1-02 三项缺陷** |
| `95ec7d6` | P0 基线记录 |
| `bc72be3` | 0001 event-loop-v1（Claude 原型） |
| `10c3519` | 上游 `d38a39a` 基线 |

### D5 的 diff 怎么看

D5 的真实改动**只有一个文件**：`LoopRuntime.kt`（+52 −1）。

- 逐行 diff（GitHub）：https://github.com/aozhenyu666-cmyk/dsh-Go/commit/aa8876b7195ad00d3df191609aecb432bf8a21ad
- 本地补丁：`projects/18-dsh-go-core/PATCHES/0004-d5-loop-to-kernel.patch`

⚠️ **注意**：`review/d5-loop-to-kernel` 是在「D3 会话选择」之前切出来的分支，
所以 `git diff feat/proactive-integration..review/d5-loop-to-kernel` 会显示大量**反向的**
无关差异（D3/D4 的内容看起来被"删除"）。那是分支基线不同，不是 D5 改动了它们。
**审 D5 请只看 `aa8876b` 这一个提交**，或只看上面的补丁文件。

## 2. 可恢复的离线副本

`D:\code\dshgo-round2.bundle`（38.7 MB）

```
git bundle verify     → The bundle records a complete history.（okay）
git bundle list-heads → 4 个分支：
  43c2c99 refs/heads/feat/event-loop-v1
  67bd05b refs/heads/feat/proactive-integration
  10c3519 refs/heads/main
  aa8876b refs/heads/review/d5-loop-to-kernel
```

已做**真实恢复演练**：`git clone <bundle>` 到空目录 → 四个分支全部出现 →
检出 `feat/proactive-integration` = `67bd05b`，关键文件存在
（`app/lib/proactive/proactive_channel.dart`、`projects/18-dsh-go-core/CLAUDE_REVIEW.md`）。

## 3. APK

| 项 | 值 |
|---|---|
| 路径（构建机） | `D:\code\dshgo\app\build\app\outputs\flutter-apk\app-arm64-debug.apk` |
| 包名 | `io.github.ccch1mneyyy.dsh_app` |
| 大小 | 125.9 MB |
| SHA-256 | `5ce608df245f419225f533bf3586e943b933e6e5a3a9704cb1d985c4ea7d58e4` |
| 签名 | `CN=Android Debug, O=Android, C=US`（debug） |
| 证书 SHA-256 | `10cdb677f36a1f5b5a9762c190736b9fd64016876a378b9bdc9297bb5724b758` |

APK **不入 git**（125 MB 会让仓库膨胀）；哈希与重建命令在
`projects/18-dsh-go-core/APK/README.md`。

## 4. 凭据扫描结果

- 我改动的 **84 个文件**：`0` 处凭据模式（`sk-*`、`gho_*`、`ghp_*`、`AKIA*`、私钥头）。
- 全仓匹配到的 `sk-*` / `AKIA*` 全部是**上游原本就有的安全测试假值**
  （如 `AKIAIOSFODNN7EXAMPLE`、`sk-0123456789abcdef0123456789`），用于验证「密钥脱敏」功能，
  不是我新增的。
- 我的交付文档里原本写了本机用户名路径（`C:\Users\<真实用户名>\…`），已脱敏为
  `C:\Users\<user>\…` 后才推送。
- 未提交任何 API Key、Cookie、Token、个人数据、手机信息。

## 5. 评审包

| 文件 | 用途 |
|---|---|
| `VALIDATION_REPORT.md` | 全部测试证据，含**真实 DSH 引擎 + 假模型 26/26** |
| `CLAUDE_REVIEW.md` | 需要 Claude 判断的 8 个具体问题 |
| `TASK_STATUS.md` | 逐项 PASS/PARTIAL/REVIEW_REQUIRED/NOT_TESTED |
| `BUILD_REPORT.md` | 环境、踩坑、构建命令、产物 |
| `ARCHITECTURE_FINAL.md` | 实际落地的两条链路与不变量 |
| `CHANGELOG.md` | 相对上游的全部改动 |
| `EXECUTION_STATE.md` | 断点恢复信息 |
| `TEST_APK_PLAN.md` | **独立测试 APK 方案与风险清单** |
| `PATCHES/0004-d5-loop-to-kernel.patch` | D5 单提交补丁 |

## 6. 边界确认

- ❌ 没有合并 D5（`review/d5-loop-to-kernel` 独立存在，集成分支不含它）。
- ❌ 没有删除旧 T5（`TriggerRules` + 7 项测试保留；按集成指南其规则权威已移交内核，
  但代码没删）。
- ❌ 没有连接、安装、卸载、清除任何真实手机上的 App。
- ❌ 没有向无写权限的上游推送，没有覆盖任何既有远程分支。
- ✅ 本轮只做交付与方案，**未新增功能**。
