# DSH Go 核心改造：事件闭环 v1

[返回项目总览](../README.md) · [返回仓库首页](../../README.md)

以 [DSH Go](https://github.com/ccch1mneyyy/dsh-go) 为核心搭体系：改造现有 App，不重写。
本目录放审计、架构、任务拆分和第一条闭环的原型补丁。补丁基于上游 `d38a39a`（DSH Go 1.0.2），**没有改上游主线**。

## 第一条闭环

设置里选一个测试 APP → 打开它 → 无障碍收到窗口事件 → 复核真实前台包名 → 本地规则确认 → 弹出交互页 → 点"返回" → 保存回执。
不调用 AI、没有长期记忆、不接总控。

## 文件

| 内容 | 入口 |
|---|---|
| 源码审计（事件入口、前台识别、弹窗/通知、任务依赖、结果记录、后台恢复、历史项目复用分类） | [docs/AUDIT.md](docs/AUDIT.md) |
| 架构图、四个模块、统一事件结构、改动文件清单、技术风险 | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| 验证记录与真机测试步骤（真机项全部 NOT_TESTED） | [docs/VALIDATION.md](docs/VALIDATION.md) |
| DeepSeek 任务拆分（标出需要 Claude 审核的） | [docs/TASKS.md](docs/TASKS.md) |
| 原型补丁（31 个文件，含测试） | [patch/0001-event-loop-v1.patch](patch/0001-event-loop-v1.patch) |
| **第二轮**：主动交互内核（T4 仲裁 / T6 调度 / T8 Agent 协议）、状态迁移图、12 个场景、边界、审查问题 | [docs/ROUND2.md](docs/ROUND2.md) |
| Android ↔ DSH 插件接口契约 | [docs/AGENT_PROTOCOL.md](docs/AGENT_PROTOCOL.md) |
| 给 DeepSeek 的集成指南（合并顺序、冲突风险、D1–D6） | [docs/INTEGRATION_DEEPSEEK.md](docs/INTEGRATION_DEEPSEEK.md) |
| 内核补丁（只有新文件：Kotlin 内核 + 66 个测试 + 宿主插件 + 真实引擎测试） | [patch/0002-proactive-kernel.patch](patch/0002-proactive-kernel.patch) |
| Android 集成补丁（适配层 + 共享文件小改） | [patch/0003-proactive-android.patch](patch/0003-proactive-android.patch) |
| 真实引擎端到端日志（26/26） | [docs/e2e-live-2026-10-30.log](docs/e2e-live-2026-10-30.log) |

## 把补丁放进 dsh-go 的独立分支

```sh
git clone https://github.com/ccch1mneyyy/dsh-go && cd dsh-go
git checkout -b feat/event-loop-v1 d38a39a
git am /path/to/0001-event-loop-v1.patch
git am /path/to/0002-proactive-kernel.patch     # 第二轮，只有新文件
git am /path/to/0003-proactive-android.patch    # 第二轮，含共享文件小改
```

之后在有 Android SDK 的机器上 `cd app && flutter build apk --debug` 构建（本轮沙箱没有 Android SDK，完整构建是 NOT_TESTED）。

## 当前状态

- 闭环逻辑 JVM 测试 17/17 通过；Android 新代码对 API 35 做了编译检查；`flutter analyze` 无问题；手机操控 208 个 Flutter 测试（含截图对比）全部通过；全量 Flutter 测试 8220 通过，8 个截图失败在干净上游上同样失败，与本改动无关。
- 真机：全部 NOT_TESTED，步骤见验证记录第 3 节。
- 第二轮：内核 JVM 测试 66/66；宿主插件单元测试 10/10；插件在真实 DSH 引擎（0.2.1-rc.4，本地假模型）上端到端 26/26；Android 适配只做了编译检查。Android 行为、真实模型、远程主机全部 NOT_TESTED。
