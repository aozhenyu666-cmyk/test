# 主控台（Focus Hub）ToolPkg

目标 Operit 版本：**1.12.2**（类型定义取自 Operit `v1.12.2` 标签，见仓库根目录 `types/`）。

侧边栏里的一个页面，底部三个标签：

- **今天**：首页是"她"。默认是秘书会话（S3 的投递目标）；在「会话」页任意对话上点「设为她」可以换人，选择存在 `companion/config.json`。名字取该对话绑定的角色卡。
  - **心情**四档：安心 / 在意 / 担心 / 要谈谈，按最近一小时采样、偏移、你说的进展和未回应的打卡计算，"为什么"一行写着依据。每一档对应规则阶梯的下一步（取自 `EXEC_RULES.tsv`）。心情只用来呈现，不执行任何动作。
  - **找她聊**：在主界面打开她的对话。**🎙 语音聊**：启动语音球，并把悬浮窗对话切到她。按钮结果（含错误原文）会留在卡片上。
  - **四个动作**：继续 / 卡住 / 提交 / 暂停，点一下再"记下"就是一条进展；暂停必须写原因。
  - **今天的你**：24 小时专注色条（采样口径）、在任务上的比例、今天的进展。
- **会话**：她是唯一主入口；判断官、温柔巡检、陪伴窗等列为"后台角色"；另有最近 30 个对话和搜索。除她以外每个对话都有「设为她」。
- **系统**：体检（关键文件多久没更新）、工作流、App 使用、最近事件、规则/审计/数据源。

**她来找你（打卡）**：`focus_hub_nav:check_in` 一次做三件事：用 Operit 当前配置的语音念出一句话、弹出主控台、记一条待回应的打卡。你在首页点任意一个动作就算回应。深夜（23:30–08:00）、45 分钟内刚找过、或者你状态很好且 2 小时内报过进展时，会自动跳过。包里自带工作流模板「陪伴打卡（每小时）」，在 Operit「工作流 → 从模板新建」里选它即可。

工具一览：

| 工具 | 用途 |
|---|---|
| `focus_hub_data:get_dashboard_snapshot` | 只读，让 AI 读到和主控台同一份状态（含心情、专注、体检） |
| `focus_hub_progress:report_progress(kind, user_quote, note?)` | 用户进展统一入口：AI 在对话里记下你亲口说的进展 |
| `focus_hub_progress:get_recent_progress(limit?)` | 读最近进展，判断和提醒前先看 |
| `focus_hub_nav:check_in(message?, force?, speak?, popup?)` | 她来找你：语音 + 弹窗 + 待回应 |
| `focus_hub_nav:open_focus_hub` / `open_chat(chat_id)` | 打开主控台 / 在主界面打开指定对话 |

**不做的事**：不冻结、不解冻、不触发或修改工作流、不新建对话。写入只有三处：`progress/<日期>.jsonl`（进展，追加）、`companion/checkins/<日期>.jsonl`（打卡，追加）、`companion/config.json`（你选的"她"）。

语音走的是 Operit"设置"里配置的 TTS。如果你的 MiniMax 语音已经配在那里，打卡会直接用它；如果是脚本自己调用的，之后再接入，密钥不需要给任何人。

## 安装

1. 下载 `build/focus_hub.toolpkg`。
2. Operit → 包管理 → 导入包（或复制到 `Android/data/com.ai.assistance.operit/files/packages/`）。
3. 启用「主控台」，主侧边栏会出现入口。

旧版本直接导入覆盖即可（`toolpkg_id` 不变）。SHA-256 见 `build/focus_hub.toolpkg.sha256`。

## v0.4.1

按真机只读核查报告修正：
- "她"默认改为秘书会话，并可在会话页「设为她」。
- 删除"让她细说"（真机点击无反应、用途不清）；ToolPkg API 回到 1.0.0。
- 语音按钮改名「🎙 语音聊」（真机确认能唤出语音球）。
- 按钮结果和错误原文持续显示在卡片上。
- 打卡语音失败时记录宿主返回的错误原文（`speak_error`）。**已知：本机 `test_tts_playback` 返回 `Unknown error`，打卡目前念不出声，待改走你的 `voice_bar:say`。**

## v0.4 新增

陪伴式改版：首页是她（心情 = 规则阶梯的呈现）、四个动作、今日专注色条；会话页只留一个主入口；系统页收纳工程信息；"她来找你"打卡（语音 + 弹窗 + 待回应），附每小时工作流模板；"让她细说"按需调用模型（需要 ToolPkg API 1.0.1）。

## v0.3 新增

用户进展统一入口（见上）。看板上的"我的进展"卡片可以直接记；AI 快照把进展放在最前面。

## v0.2 修了什么

1. **"进入固定对话失败：Service not connected"**。宿主的 `switch_chat`/`create_new_chat` 操作的是悬浮窗服务，服务没运行就直接报这个错；而且 `switchTo` 内部是 `switchChatLocal(syncToGlobal=false)`，只切悬浮窗、不切主界面，所以"切过去再嵌入显示"本来就不成立。v0.2 去掉了固定对话，改为会话导航：通过 `ChatHistoryManager.setCurrentChatId`（主界面跟随的全局当前对话）切换，再跳到 `native.ai_chat`，先用 `chatExists` 校验。
2. **工作流全都显示"非间隔定时，不判断迟到"**。`get_workflow` 返回的节点只有 `__type`、没有 `type` 字段，旧代码按 `type === "trigger"` 找触发器，一个都没找到。现在按 `triggerType === "schedule"` 识别，并支持 cron（与宿主 `calculateCronInterval` 规则一致）：每天定时的超过约 27 小时没跑算疑似迟到。
3. **标题贴着屏幕左边**。宿主只要看到 `paddingTop` 等单边属性就忽略 `paddingHorizontal`，现在三边分别写。
4. 同一行不再重复显示"正常"；失败率 ≥ 20% 的工作流标红（例如 G2 138/373 = 37%）。

## 验证状态

| 项目 | 状态 | 依据 |
|---|---|---|
| 类型检查（v1.12.2 类型） | VERIFIED | `tsc` 0 错误 |
| 数据解析、调度判断、事件合并、缺文件处理 | VERIFIED（模拟宿主） | `test/harness.js` 65 项通过；调度用例按真机 `get_workflow` 格式（无 `type` 字段）构造 |
| 看板在真机加载、各数据源读取 | VERIFIED（v0.1 真机截图） | 26 个工作流、当前任务均显示 |
| 会话列表（`list_chats`，不依赖悬浮窗服务） | AVAILABLE_UNTESTED | 源码确认读聊天记录库 |
| 点开对话（Java 桥 `ChatHistoryManager.setCurrentChatId` + `native.ai_chat`） | AVAILABLE_UNTESTED | 源码确认路由名和主界面跟随机制；调用宿主内部类，跨版本可能失效 |
| 进展记录：校验、追加写入、去重、读取、看板卡片 | VERIFIED（模拟宿主） | harness 覆盖 |
| 心情计算、打卡决策（深夜/冷却/状态好）、整天事件分块读取、体检 | VERIFIED（模拟宿主） | harness 覆盖，时钟固定在 14:30 |
| 打卡语音 `SoftwareSettings.testTtsPlayback` | NOT_AVAILABLE（本机） | 真机核查 `test_tts_playback` 返回 `Unknown error`；需改走 `voice_bar:say` |
| 语音按钮能唤出语音球 | VERIFIED（用户观察） | 切到她的对话、能否正常通话仍待确认 |
| 语音按钮（`startService VOICE_BALL` + `switchTo`） | AVAILABLE_UNTESTED | 显式启动悬浮窗服务后再切换，避开 Service not connected |
| 工作流模板导入后是否自动启用和调度 | AVAILABLE_UNTESTED | 格式照官方 template_try |
| 进展记录在真机写入 `/sdcard/.../progress/` | AVAILABLE_UNTESTED | 宿主 `write_file` 源码确认 append 原样追加、自动建目录 |
| 对话里的 AI 会主动调用 report_progress | AVAILABLE_UNTESTED | 取决于角色卡提示和模型 |
| `open_focus_hub` 从工作流拉起页面 | AVAILABLE_UNTESTED | `MainActivity.handleIntent` 读取 `OPEN_ROUTE_ID`；Operit 在后台时系统可能拦截 Activity 启动 |

## 实机测试清单

1. 导入覆盖，打开主控台：标题不再贴边；工作流卡片显示"每 30 分钟""每天 23:30"这类调度，不再是"非间隔定时"。
2. 看有没有工作流被标成"疑似迟到"，对照你知道的实际情况判断对不对。
3. 切到"会话"：能看到固定入口和最近对话。
4. 点一个对话 → 应跳到主界面聊天页，并且是那个对话。
5. 在"我的进展"里选"提交"，写一句，点记下：卡片里出现这条；文件管理器里 `Download/Operit/progress/<今天>.jsonl` 多了一行。
6. 在陪伴窗里（角色卡加了那句话之后）说"刚投了一家"，看 AI 是否调用 report_progress，看板刷新后能看到"来自 对话"的记录。
7. 新建一个只有"手动触发 → 执行 `focus_hub_nav:open_focus_hub`"的测试工作流，分别在 Operit 前台和切到别的 App 后手动触发，看主控台会不会弹出来。

哪一步不对，把现象或报错原文发回来。

## 开发

```bash
./build.sh        # tsc 编译 → 跑 test/harness.js → 打包 build/focus_hub.toolpkg
node test/harness.js
```

`src/main.ts` 注册路由和侧边栏入口；`shared/snapshot.ts` 汇总看板数据；`shared/format.ts` 生成 AI 用的文本；`shared/nav.ts` 会话列表与跳转；`ui/focus_hub/index.ui.ts` 页面；`shared/progress.ts` 进展读写；`packages/` 三个工具包。
