# 主控台（Focus Hub）ToolPkg

目标 Operit 版本：**1.12.2**（类型定义取自 Operit `v1.12.2` 标签，见仓库根目录 `types/`）。

一个页面，两个标签：

- **看板**：只读汇总当前任务、工作流运行、App 使用、最近事件、规则、动作审计、数据源状态。
- **对话**：固定对话「主控台」，直接嵌在页面里（`AiChat` 组件），每次进入都切回同一个对话。

另带一个给 AI 用的只读工具 `focus_hub_data:get_dashboard_snapshot`，让主控对话里的 AI 读到和看板同一份数据。

**不做的事**：不冻结、不解冻、不触发或修改工作流、不发外部消息、不写任何文件。唯一会产生变化的操作是你手动点"创建「主控台」对话"。

## 安装

1. 下载 `build/focus_hub.toolpkg`。
2. Operit → 包管理 → 导入包，选择这个文件（或复制到 `Android/data/com.ai.assistance.operit/files/packages/`）。
3. 在包管理里启用「主控台」。
4. 主侧边栏会出现「主控台」入口。

SHA-256 见 `build/focus_hub.toolpkg.sha256`。

## 让主控 AI 会用看板数据

在「主控台」对话绑定的角色卡或提示词里加一句，例如：

> 需要了解我当前的任务、工作流状态、今天用了哪些 App 或最近发生了什么时，先使用 focus_hub_data 包，调用 get_dashboard_snapshot，再回答。快照里写明了口径，按口径说话。

## 数据来源

| 看板区域 | 来源 | 读取方式 |
|---|---|---|
| 当前任务 | `/sdcard/Download/Operit/drift/task_state.txt` | 前 50 行 |
| 工作流 | `Tools.Workflow.getAll()`；间隔定时的再调 `get()` 读 `triggerConfig` | — |
| App 使用 | `Tools.System.getAppUsageTime({ sinceHours: 24 })` | 前 12 个 |
| 最近事件 | `/sdcard/Download/Operit/events/<今天>/events.jsonl`，今天没有就用昨天 | 最后 40 行，连续相同事件合并 |
| 规则 | `EXEC_RULES.tsv`、`ACTIVE_RULES.md` | 前 60 / 400 行 |
| 动作审计 | `ACTION_LOG.tsv` | 最后 12 行 |

说明：

- 宿主读文件单次最多 32KB，还会在每行前加行号。这里用 `readPart` 先取总行数再读末尾，并去掉行号前缀，所以大文件也能读到最新内容。
- "疑似迟到"只对 `schedule_type=interval` 的工作流判断：宿主会把间隔抬到至少 15 分钟，超过 2 个间隔再加 5 分钟没跑就算迟到。定点、cron 调度不做判断。
- 找固定对话按精确标题「主控台」查（上限 200 条）。`listAll()` 默认只返回最近 50 个对话，所以没有用它。

## 验证状态

| 项目 | 状态 | 依据 |
|---|---|---|
| 类型检查（v1.12.2 类型） | VERIFIED | `tsc` 0 错误 |
| 数据解析、迟到判断、事件合并、缺文件处理 | VERIFIED（模拟宿主） | `test/harness.js` 32 项通过；模拟读文件复刻了宿主的行号前缀和 32KB 截断 |
| 固定对话查找、不自动新建、同名多个时让你选 | VERIFIED（模拟宿主） | 同上，包含"超过 50 个对话"场景 |
| 包能导入、启用、侧边栏出现入口 | AVAILABLE_UNTESTED | 需要实机 |
| 各 `Tools.*` 在 UI 上下文的真实返回 | AVAILABLE_UNTESTED | 需要实机 |
| `AiChat` 嵌入后能正常收发消息 | AVAILABLE_UNTESTED | 1.12.2 源码有实现（`ToolPkgComposeDslScreen.kt` 的 `renderAiChatNode`），示例里没人用过 |
| AI 能调用 `get_dashboard_snapshot` | AVAILABLE_UNTESTED | 需要实机 |

## 实机测试清单

1. 导入并启用，侧边栏出现「主控台」。
2. 打开看板：七个区域都有内容；"数据源"卡片里都是"已读取"。有"读取失败"的，把那一行的错误原文发回来。
3. 对照你已知的数据，抽查一两项：比如某个工作流的失败次数、贴吧的使用分钟数。
4. 点右上角刷新，更新时间会变。
5. 切到"对话"：
   - 还没有「主控台」对话：页面提示并给出创建按钮，**不会自己新建**。
   - 点创建后进入对话，发一句话，AI 能回复。
6. 回主界面切到别的对话，再回来点"对话"，确认回到的是「主控台」。
7. 在主控对话里问"看看现在的情况"，确认 AI 调用了 `get_dashboard_snapshot`。

## 开发

```bash
./build.sh        # tsc 编译 → 跑 test/harness.js → 打包 build/focus_hub.toolpkg
node test/harness.js
```

源码在 `src/`：`main.ts` 注册路由和侧边栏入口；`shared/snapshot.ts` 汇总数据；`shared/format.ts` 生成 AI 用的文本；`ui/focus_hub/index.ui.ts` 页面；`packages/focus_hub_data.ts` AI 工具。
