# 主控台（Focus Hub）ToolPkg

目标 Operit 版本：**1.12.2**（类型定义取自 Operit `v1.12.2` 标签，见仓库根目录 `types/`）。

侧边栏里的一个页面，两个标签：

- **看板**：只读。顶部四格概览（当前任务、需关注的工作流、今日事件、用得最多的 App），下面是工作流、App 使用、最近事件；规则、动作审计、数据源明细收在"展开"里。
- **会话**：把所有对话整理成"固定入口"（陪伴窗、温柔巡检、判断官等）和"最近 30 个"，可以按标题搜索。点一下就在主界面打开那个对话。消息超过 300 条的会标出"上下文很长"。

另有两个工具包：

| 工具 | 用途 |
|---|---|
| `focus_hub_data:get_dashboard_snapshot` | 只读，让 AI 读到和看板同一份数据 |
| `focus_hub_nav:open_focus_hub` | 把界面带到主控台，可放在工作流末尾代替新建临时对话 |
| `focus_hub_nav:open_chat(chat_id)` | 在主界面打开指定对话 |

**不做的事**：不冻结、不解冻、不触发或修改工作流、不发消息、不写文件、不新建对话。

## 安装

1. 下载 `build/focus_hub.toolpkg`。
2. Operit → 包管理 → 导入包（或复制到 `Android/data/com.ai.assistance.operit/files/packages/`）。
3. 启用「主控台」，主侧边栏会出现入口。

旧版本直接导入覆盖即可（`toolpkg_id` 不变）。SHA-256 见 `build/focus_hub.toolpkg.sha256`。

## v0.2 修了什么

1. **"进入固定对话失败：Service not connected"**。宿主的 `switch_chat`/`create_new_chat` 操作的是悬浮窗服务，服务没运行就直接报这个错；而且 `switchTo` 内部是 `switchChatLocal(syncToGlobal=false)`，只切悬浮窗、不切主界面，所以"切过去再嵌入显示"本来就不成立。v0.2 去掉了固定对话，改为会话导航：通过 `ChatHistoryManager.setCurrentChatId`（主界面跟随的全局当前对话）切换，再跳到 `native.ai_chat`，先用 `chatExists` 校验。
2. **工作流全都显示"非间隔定时，不判断迟到"**。`get_workflow` 返回的节点只有 `__type`、没有 `type` 字段，旧代码按 `type === "trigger"` 找触发器，一个都没找到。现在按 `triggerType === "schedule"` 识别，并支持 cron（与宿主 `calculateCronInterval` 规则一致）：每天定时的超过约 27 小时没跑算疑似迟到。
3. **标题贴着屏幕左边**。宿主只要看到 `paddingTop` 等单边属性就忽略 `paddingHorizontal`，现在三边分别写。
4. 同一行不再重复显示"正常"；失败率 ≥ 20% 的工作流标红（例如 G2 138/373 = 37%）。

## 验证状态

| 项目 | 状态 | 依据 |
|---|---|---|
| 类型检查（v1.12.2 类型） | VERIFIED | `tsc` 0 错误 |
| 数据解析、调度判断、事件合并、缺文件处理 | VERIFIED（模拟宿主） | `test/harness.js` 47 项通过；调度用例按真机 `get_workflow` 格式（无 `type` 字段）构造 |
| 看板在真机加载、各数据源读取 | VERIFIED（v0.1 真机截图） | 26 个工作流、当前任务均显示 |
| 会话列表（`list_chats`，不依赖悬浮窗服务） | AVAILABLE_UNTESTED | 源码确认读聊天记录库 |
| 点开对话（Java 桥 `ChatHistoryManager.setCurrentChatId` + `native.ai_chat`） | AVAILABLE_UNTESTED | 源码确认路由名和主界面跟随机制；调用宿主内部类，跨版本可能失效 |
| `open_focus_hub` 从工作流拉起页面 | AVAILABLE_UNTESTED | `MainActivity.handleIntent` 读取 `OPEN_ROUTE_ID`；Operit 在后台时系统可能拦截 Activity 启动 |

## 实机测试清单

1. 导入覆盖，打开主控台：标题不再贴边；工作流卡片显示"每 30 分钟""每天 23:30"这类调度，不再是"非间隔定时"。
2. 看有没有工作流被标成"疑似迟到"，对照你知道的实际情况判断对不对。
3. 切到"会话"：能看到固定入口和最近对话。
4. 点一个对话 → 应跳到主界面聊天页，并且是那个对话。
5. 新建一个只有"手动触发 → 执行 `focus_hub_nav:open_focus_hub`"的测试工作流，分别在 Operit 前台和切到别的 App 后手动触发，看主控台会不会弹出来。

哪一步不对，把现象或报错原文发回来。

## 开发

```bash
./build.sh        # tsc 编译 → 跑 test/harness.js → 打包 build/focus_hub.toolpkg
node test/harness.js
```

`src/main.ts` 注册路由和侧边栏入口；`shared/snapshot.ts` 汇总看板数据；`shared/format.ts` 生成 AI 用的文本；`shared/nav.ts` 会话列表与跳转；`ui/focus_hub/index.ui.ts` 页面；`packages/` 两个工具包。
