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

## 把补丁放进 dsh-go 的独立分支

```sh
git clone https://github.com/ccch1mneyyy/dsh-go && cd dsh-go
git checkout -b feat/event-loop-v1 d38a39a
git am /path/to/0001-event-loop-v1.patch
```

之后在有 Android SDK 的机器上 `cd app && flutter build apk --debug` 构建（本轮沙箱没有 Android SDK，完整构建是 NOT_TESTED）。

## 当前状态

- 闭环逻辑 JVM 测试 17/17 通过；Android 新代码对 API 35 做了编译检查；`flutter analyze` 无问题；手机操控原有 208 个 Flutter 测试（含截图对比）全部通过。
- 真机：全部 NOT_TESTED，步骤见验证记录第 3 节。
