# 主控台（Focus Hub）ToolPkg

目标 Operit 版本：**1.12.2**（类型定义取自 Operit `v1.12.2` 标签，见仓库根目录 `types/`）。

侧边栏里的一个页面，底部四个标签：

- **今天**：首页是"她"。默认是秘书会话（S3 的投递目标）；在「会话」页任意对话上点「设为她」可以换人，选择存在 `companion/config.json`。名字取该对话绑定的角色卡。
  - **心情**四档：安心 / 在意 / 担心 / 要谈谈，按最近一小时采样、偏移、你说的进展和未回应的打卡计算，"为什么"一行写着依据。心情只用来呈现，不执行任何动作，也不显示规则。
  - **找她聊**：在主界面打开她的对话。**🎙 语音聊**：启动语音球，并把悬浮窗对话切到她。**🔈 念一句**：用你的 `voice_bar` 把卡片上那句话念出来。按钮结果（含错误原文）会留在卡片上。
  - **四个动作**：继续 / 卡住 / 提交 / 暂停，点一下再"记下"就是一条进展；暂停必须写原因。
  - **今天的你**：24 小时专注色条（采样口径）、在任务上的比例、今天的进展。
- **会话**：她是唯一主入口；判断官、温柔巡检、陪伴窗等列为"后台角色"；另有最近 30 个对话和搜索。除她以外每个对话都有「设为她」。
- **省流**：量出上下文花在哪，并提供能撤销的精简（见下）。
- **系统**：体检（关键文件多久没更新）、工作流、App 使用、最近事件、动作记录与数据源。

## 省流：上下文花在哪

每次和 AI 说一句话，发出去的是 **人设 + 工具说明 + 这段对话的历史**。按 Operit 1.12.2 的默认设置（上下文 64K、用到 70% 才压缩），一段长对话的每一句最多要带约 4.5 万 tokens。判断官、秘书这些后台角色被工作流每半小时喂一次，是最容易越滚越大的地方。

省流页从上到下：

1. **总账**：所有对话累计输入多少 tokens，以及自上次记账（约 24 小时前）增加了多少。主控台每两小时最多记一次账到 `companion/slim/token_snapshots.jsonl`，第一次打开时还没有增长数字。
2. **最费的对话**：按累计输入排前 8 个，写出条数、平均每条多少、最近涨了多少；标出「她」「后台角色」和「从没压缩」（从来没触发过自动总结）。
3. **自动压缩**：读当前对话模型的上下文长度和总结设置，给出建议。这是全局设置，主控台只读，要在 Operit 设置 → 模型配置里改。
4. **角色卡带多少工具**：没设白名单的角色每句话都带内置工具说明（约 2600 tokens）和整张工具包清单。每张卡有两个预设：
   - **只聊天**：不带任何工具。适合判断官、秘书这种只输出文字的角色；它的工作流要是需要它自己读文件，就别选。
   - **陪伴**：只留 `use_package` 和主控台、语音几个包（花火用这个）。
   - 改之前自动备份原来的工具权限，**恢复** 回到第一次改动之前的状态。
5. **每句话都带的工具包清单**：开着的包按清单长度排序，标出哪些被启用中的工作流用到（标红，别停）。**停用** 只是关掉，下面会出现「主控台停用过的」，一键重新启用。
6. **外面的冗余**：点「扫描」，列出 `/sdcard/Download/Operit` 顶层没被启用中工作流提到、7 天没动过的文件和目录，以及过期的一次性工作流、从没运行或 7 天以上没用的手动工作流。只读，清单写到 `companion/slim/redundancy_<日期>.md`，可以交给 Operit AI 或流程线核对后再处理。
7. **改动记录**：所有停用、启用、预设、恢复都追加写进 `companion/slim/actions.jsonl`（含改动前后的值）。

会改设置的按钮都要**点两次**：第一次变成「再点确认」，第二次才执行，执行后回读宿主的值，不一致就报错并记录。

建议顺序：先给判断官、秘书这类后台角色卡设「只聊天」，手动跑一次对应工作流确认没坏；再看自动压缩的建议；最后停掉标记为没被工作流用到、你也不用的包。

**她来找你（打卡）**：`focus_hub_nav:check_in` 念出一句话（心情不是安心时再弹出主控台），并记一条待回应的打卡。你在首页点任意一个动作就算回应。深夜（23:30–08:00）、45 分钟内刚找过、心情和上次一样且两小时内说过、或安心且最近有进展时，会自动跳过。包里自带工作流模板「陪伴打卡（每小时）」，在 Operit「工作流 → 从模板新建」里选它即可。

工具一览：

| 工具 | 用途 |
|---|---|
| `focus_hub_data:get_dashboard_snapshot` | 只读，让 AI 读到和主控台同一份状态（含心情、专注、体检） |
| `focus_hub_progress:report_progress(kind, user_quote, note?)` | 用户进展统一入口：AI 在对话里记下你亲口说的进展 |
| `focus_hub_progress:get_recent_progress(limit?)` | 读最近进展，判断和提醒前先看 |
| `focus_hub_nav:check_in(message?, force?, speak?, popup?)` | 她来找你：语音 + 弹窗 + 待回应 |
| `focus_hub_nav:open_focus_hub` / `open_chat(chat_id)` | 打开主控台 / 在主界面打开指定对话 |
| `focus_hub_slim:get_slim_report(scan?)` | 只读省流报告。**默认不启用**（启用后它自己也会进每轮的包清单），需要让 Operit AI 读报告时再到包管理里打开 |

**不做的事**：不冻结、不解冻、不触发或修改工作流、不新建或删除对话、不移动或删除文件。文件写入只有：`progress/<日期>.jsonl`（进展，追加）、`companion/checkins/<日期>.jsonl`（打卡，追加）、`companion/config.json`（你选的"她"）、`companion/slim/`（省流记账、改动记录、扫描清单）。会改 Operit 设置的只有省流页上你点两次确认的按钮（停用/启用包、角色卡工具白名单），每一次都能撤销。

**发声**：先调用你的 `voice_bar:say` 合成音频（MiniMax 等，密钥在 voice_bar 自己的环境变量里），从返回结果里找出音频文件，用安卓 MediaPlayer 播放完再返回；`voice_bar` 不可用时退回 Operit 自带 TTS。两条都失败时，错误原文会写进打卡记录的 `speak_error` 和卡片状态行。

## 安装

1. 下载 `build/focus_hub.toolpkg`。
2. Operit → 包管理 → 导入包（或复制到 `Android/data/com.ai.assistance.operit/files/packages/`）。
3. 启用「主控台」，主侧边栏会出现入口。

旧版本直接导入覆盖即可（`toolpkg_id` 不变）。SHA-256 见 `build/focus_hub.toolpkg.sha256`。

## v0.6.0

- 新增「省流」页：会话 token 账单（含 24 小时增长）、自动压缩设置、角色卡工具白名单预设（只聊天 / 陪伴 / 恢复）、工具包停用与重新启用、冗余文件和工作流扫描（只读）、改动记录。
- 新增 `focus_hub_slim:get_slim_report`，默认不启用。
- 没有注册提示词钩子：钩子会在每次请求时同步运行，反而拖慢对话，所以只用宿主已有的数据来量。

## v0.5.0

- **规则不再出现在界面上**：去掉首页"再这样下去会怎样"和系统页的规则表；主控台也不再读取规则文件。
- 她的台词换成花火的口吻，每档多种说法，按时段轮换，并避开上一次打卡说过的那句。
- 打卡更克制：心情没变且两小时内说过就不重复；"安心"时 3 小时没动静才问一句，而且只念不弹窗。
- 头像换成 🎭。配套角色卡见仓库 `cards/huahuo.json`。

## v0.4.2

- 发声改走 `voice_bar:say` + MediaPlayer 播放，系统 TTS 作为后备；打卡记录新增 `speak_via`。
- 首页新增「🔈 念一句」，随时测试她的声音。

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
| 数据解析、调度判断、事件合并、缺文件处理 | VERIFIED（模拟宿主） | `test/harness.js` 119 项通过；调度用例按真机 `get_workflow` 格式（无 `type` 字段）构造 |
| 看板在真机加载、各数据源读取 | VERIFIED（v0.1 真机截图） | 26 个工作流、当前任务均显示 |
| 会话列表（`list_chats`，不依赖悬浮窗服务） | AVAILABLE_UNTESTED | 源码确认读聊天记录库 |
| 点开对话（Java 桥 `ChatHistoryManager.setCurrentChatId` + `native.ai_chat`） | AVAILABLE_UNTESTED | 源码确认路由名和主界面跟随机制；调用宿主内部类，跨版本可能失效 |
| 进展记录：校验、追加写入、去重、读取、看板卡片 | VERIFIED（模拟宿主） | harness 覆盖 |
| 省流：账单、增长、包权重、预设/恢复、停用/启用、冗余扫描、两次确认 | VERIFIED（模拟宿主） | harness 覆盖；数据结构按 1.12.2 类型定义 |
| `list_chats` 的 `inputTokens` 是该对话累计输入 | AVAILABLE_UNTESTED | 源码确认来自 `ChatHistoryManager.updateChatTokenCounts`；真机数字待看 |
| 「从没压缩」（Java 桥 `getLatestSummaryTimestamp`） | AVAILABLE_UNTESTED | 调用宿主内部类，失败时不显示这个标记 |
| 角色卡白名单对工作流驱动的对话生效 | AVAILABLE_UNTESTED | 源码里按对话绑定的角色卡解析工具权限；改完请手动跑一次对应工作流确认 |
| token 估算 | 估算 | 英文约 4 字符 1 token、中文约 1 字 0.7 token；内置工具说明按源码字数估约 2600 |
| 心情计算、打卡决策（深夜/冷却/状态好）、整天事件分块读取、体检 | VERIFIED（模拟宿主） | harness 覆盖，时钟固定在 14:30 |
| 系统 TTS `test_tts_playback` | NOT_AVAILABLE（本机） | 真机核查返回 `Unknown error`，仅作后备 |
| `voice_bar:say` 合成 + MediaPlayer 播放 | AVAILABLE_UNTESTED | 参数按真机导出的 METADATA；返回结构未知，路径提取兼容字段和 `<voice>` 标签属性两种写法 |
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
5. 点「🔈 念一句」：应该听到你的 MiniMax 声音；卡片状态行会写"念完了（用你的 voice_bar）"，失败则写出原因。
6. 在"我的进展"里选"提交"，写一句，点记下：卡片里出现这条；文件管理器里 `Download/Operit/progress/<今天>.jsonl` 多了一行。
6. 在陪伴窗里（角色卡加了那句话之后）说"刚投了一家"，看 AI 是否调用 report_progress，看板刷新后能看到"来自 对话"的记录。
7. 新建一个只有"手动触发 → 执行 `focus_hub_nav:open_focus_hub`"的测试工作流，分别在 Operit 前台和切到别的 App 后手动触发，看主控台会不会弹出来。

8. 打开「省流」：最费的对话里应能看到判断官、秘书；看数字是否和你的感觉一致。
9. 在一张后台角色卡上点「只聊天」两次，然后手动运行一次它的工作流，看输出是否正常；不正常就点「恢复」两次。
10. 点「扫描」，把 `companion/slim/redundancy_<日期>.md` 发给我或流程线。

哪一步不对，把现象或报错原文发回来。

## 开发

```bash
./build.sh        # tsc 编译 → 跑 test/harness.js → 打包 build/focus_hub.toolpkg
node test/harness.js
```

`src/main.ts` 注册路由和侧边栏入口；`shared/snapshot.ts` 汇总看板数据；`shared/format.ts` 生成 AI 用的文本；`shared/nav.ts` 会话列表与跳转；`ui/focus_hub/index.ui.ts` 页面；`shared/progress.ts` 进展读写；`shared/slim.ts` 省流；`packages/` 四个工具包。
