# 交接文档：Operit「整体流程」线（给新对话）

- 写于：2026-09-25，由"主界面"线的对话整理
- 用途：新开一个对话，专门处理 Operit 的整体流程（感知 → 判断 → 提醒/语音 → 用户回应 → 回流）。原对话继续做主控台界面，两条线并行。
- 仓库：`aozhenyu666-cmyk/test`，分支 `claude/hello-34vzla`

---

## 0. 开新对话时怎么用

在新对话里发下面这段话，并**同时附上这份文档和用户手里的三份原始文档**：

- `OPERIT_AGENT_HANDOVER_2026-09-24.md`（系统总体现状，最重要）
- `SCHED_Watchdog_WORKFLOW_REFERENCE.md`（真实工作流 JSON 样例）
- 《Operit 仪表盘 API 交接文档》（已实测的 API 和数据文件）

> 这三份包含设备和使用细节，没有放进仓库，需要你手动附上。

开场白（可直接复制）：

```text
你接手 Operit「整体流程」线。先读附带的交接文档 FLOW_TRACK_HANDOVER.md 和三份原始文档，
然后 clone 仓库 aozhenyu666-cmyk/test 的 claude/hello-34vzla 分支看已有成果。
先不要写代码：读完后用你自己的话复述（1）你负责什么、不负责什么（2）第一步打算做什么、需要我提供什么。
```

---

## 1. 背景

**用户**：用 Operit AI（Android AI Agent，版本 **1.12.2**）搭建了一套"自我管理 + 陪伴"系统。当前声明任务是**求职投递**。系统会采样前台 App、判断是否偏离任务、提醒、必要时冻结干扰 App，并有多个角色卡负责陪伴、判断、复盘和解锁审讯。

**用户想要的**（多次表达，但自己也说不太清，需要你主动琢磨）：

- 一个像活人的 AI：有记忆，会根据记忆调整交流方式，不接受敷衍，也不死板。
- 不想面对一堆散乱的对话和角色；觉得"整个功能体系有些混乱"。
- 在意 API 消耗：Operit 自己边聊边搭工作流会累积很长的上下文，很贵。
- 语音提醒、思考模块这些要真正起作用，不是只返回"调用成功"。

**沟通习惯**：用户常用语音输入，消息较长、有口语和识别错字，要抓住意思而不是字面。用中文交流。用户欢迎你给明确建议，但方向性决定要先确认。

---

## 2. 两条线的分工

| | 主界面线（原对话） | **整体流程线（你）** |
|---|---|---|
| 负责 | 主控台 ToolPkg（`focus_hub/`）：看板、会话导航、进展入口、页面跳转、界面改版 | 工作流、`/sdcard/Download/Operit/` 下的 shell 脚本、判断官、提醒通道、语音、角色分工、记忆与"思考"机制 |
| 仓库目录 | `focus_hub/`、`design/` | 建议新建 `flow/`（工作流模板、脚本补丁、给 Operit AI 的指令） |
| 不碰 | 工作流和脚本 | `focus_hub/`、`design/` 目录 |

### 两条线之间的接口（改之前先和对方对齐）

1. **用户进展**：主界面线提供，你负责让判断/提醒链路读它。
   - 路径：`/sdcard/Download/Operit/progress/<YYYYMMDD>.jsonl`，只追加写。
   - 每行格式：
     ```json
     {"id":"PROG-1790312345-4821","ts":1790312345,"iso":"2026-09-25 14:05:45","date":"20260925","type":"USER_PROGRESS","origin":"REAL_USER","via":"chat_ai","kind":"done","user_quote":"投了两家","note":"Boss 直聘","task":"求职投递","ptr":"T_1789439490890_tma07.txt","chat_id":"..."}
     ```
   - `kind`：`progress` 继续 / `stuck` 卡住 / `done` 提交 / `pause` 暂停 / `note` 说明；`via`：`dashboard` 或 `chat_ai`。
   - 写入工具：`focus_hub_progress:report_progress(kind, user_quote, note?)`；读取：`focus_hub_progress:get_recent_progress(limit?)`。
2. **主控台只读的文件**：`drift/task_state.txt`、`events/<日期>/events.jsonl`、`events/EXEC_RULES.tsv`、`events/ACTIVE_RULES.md`、`events/ACTION_LOG.tsv`。**改这些文件的格式前，先通知主界面线。**
3. **打卡（主控台 v0.4 已实现）**：`focus_hub_nav:check_in(message?, force?, speak?, popup?)` = 语音念一句 + 弹出主控台 + 记一条待回应的打卡，写入 `/sdcard/Download/Operit/companion/checkins/<YYYYMMDD>.jsonl`（`type: "CHECKIN"`，带 `level`、`line`、`speak`/`popup` 各自的 ACCEPTED/FAILED/SKIPPED）。用户在首页点任意动作即算回应，并记成一条进展。自带深夜、冷却和"状态好就不打扰"的节制。**流程线梳理提醒渠道时，可以考虑让 S3 秘书等提醒改为调用它（可以传 `message` 指定要说的话）**，这样提醒、语音、弹窗和用户回应就合成同一条链。
4. **（提议，待定）统一状态文件**：主界面改版要把"规则阶梯"画成陪伴角色的心情（安心 → 在意 A1 → 担心 A2 → 要谈谈 A3，见 `design/focus_hub_redesign_mockup.html`）。希望流程线产出一个状态文件，例如 `/sdcard/Download/Operit/state/now.json`，包含当前偏离等级、连续次数、最近一次动作及其四态、下一步会触发的动作。字段请你在摸清 `drift_scan.sh` 后提议，双方确认后再定。

---

## 3. 已经确认的事实（从 Operit v1.12.2 源码或真机得到，可以直接用）

**聊天相关**

- `Tools.Chat.switchTo` / `createNew` 操作的是**悬浮窗服务**里的聊天核心；悬浮窗服务没运行时直接返回 `Service not connected`，不会自动拉起。
- `switchTo` 内部是 `switchChatLocal(syncToGlobal=false)`：**只切悬浮窗里的对话，不切主界面**。
- 主界面跟随 `ChatHistoryManager` 的全局当前对话。可以通过 Java 桥设置：`ChatHistoryManager.getInstance(ctx).callSuspend("setCurrentChatId", id)`，先用 `chatExists` 校验。
- `Tools.Chat.listChats` 读聊天记录库，不依赖悬浮窗服务；**默认只返回 50 条**，最多 200；先按标题过滤再截取。
- `Tools.Chat.call`（直接调用某个功能模型，不进对话）需要 manifest `api_version: "1.0.1"`，1.12.2 支持。
- `Tools.Chat.startService({ initial_mode })` 支持 `WINDOW / BALL / VOICE_BALL / FULLSCREEN / ...`，是语音入口的候选。

**文件相关**

- `Tools.Files.read` 只读前 32KB，并且**每行前加行号前缀**（`"  12| "`）；大文件用 `readPart(path, start, end)`，它会返回 `totalLines`。
- `Tools.Files.write(path, content, true)` 原样追加，会自动创建父目录。
- `Tools.Files.info(path).lastModified` 格式为本地时间 `yyyy-MM-dd HH:mm:ss.SSS`。

**工作流相关**

- `workflow:get_workflow` 返回的节点**只有 `__type`，没有 `type` 字段**；识别触发器要看 `triggerType`。
- 定时间隔小于 15 分钟会被宿主抬到 15 分钟（WorkManager 下限）。
- cron 只支持简化形式：`M H * * *`（每天）、`0 */N * * *`（每 N 小时）、`*/N * * * *`（每 N 分钟），重复执行时换算成固定周期。`specific_time` 是一次性的。
- ToolPkg 可以通过 manifest 的 `workflow_templates` 注册工作流模板，导入后在"工作流 → 从模板新建"一键创建。格式参考 Operit 仓库 `examples/template_try/`。**这是不经过 Operit 对话、零 token 部署工作流的办法**，但"导入后是否自动启用和调度"还没实测。

**跳转和通知**

- `MainActivity` 读取 Intent extra `com.ai.assistance.operit.extra.OPEN_ROUTE_ID` 并打开对应路由（冷启动和已运行都会处理）。原生聊天页的路由是 `native.ai_chat`。
- JS 的 `Intent.start()` 必须设置 action；debug 包名下 `setComponent` 会把类名拼错，要传 `包名/完整类名`。
- `Tools.System.sendNotification(message, title)` **没有点击跳转参数**。
- Operit 在后台时，安卓可能拦截启动 Activity（有悬浮窗权限时通常放行，未实测）。

---

## 4. 系统现状（摘要，细节看原始交接文档）

- 26 条工作流，基本都是"调度壳 + `super_admin:shell` 调用 `/sdcard` 上的脚本"，真正的逻辑在脚本里。
- 失败率偏高的链：`G2_Judge_Flow` 138/373（37%）、`P4_Event_Sampler` 174/782（22%）、`LIFE_MorningDigest` 6/9。
- 已知缺陷：
  1. **用户在对话里说的进展不回流到判断官**，导致重复催已经做完的事（用户最早抱怨的"不像活人、不听人说话"就是这个）。
  2. 判断官的哈希去重因证据包里有"每拍必变字段"而失效，同样的事实被反复判断，**浪费 token**。
  3. 对用户说话的角色太多：陪伴窗、秘书、跑偏提醒窗、温柔巡检、判断官、复盘者、解锁审讯官、备用出口。
  4. 提醒只到 ACCEPTED（HTTP 200/回调 1），当前 `show_floating=false`，用户是否看见没有证据。
- **待执行**：停用 5 条遗留工作流（`Lock_Freeze_Lock` 仍启用，并且带 6 小时自动解冻，是"锁了又解锁"的来源之一）。指令在 `ops/01_disable_legacy_workflows.md`，用户会交给 Operit AI 执行，结果表回来后请核对。

---

## 5. 你要解决的问题（建议顺序，可以调整，但先和用户确认）

**P1 让用户的话进入判断**（最高价值）
- 改 `gen_pack.sh`，把当天 `progress/*.jsonl` 的最近几条放进证据包，并在判断官提示词里要求"用户已经说做完的事不再催"。
- 同时修 `hash_gate.sh` 的去重键：去掉每拍必变的字段，让同样的事实不再重复调用模型。
- 需要的材料：`ops/01` 后半段的只读导出 `scripts_export_20260925.txt`（用户正在向 Operit AI 要）。

**P2 理清"谁对用户说话"**
- 原则建议：对用户只有一张脸（陪伴角色）；判断官、复盘者等是后台角色，结论交给她转达。
- 梳理 S3 秘书、OUTBOX、跑偏提醒窗、温柔巡检各自投到哪个会话，提出合并方案。
- 语音：确认语音提醒和语音对话走哪条链（`speak=true` 的 TTS 会话、`VOICE_BALL`），目标是和陪伴角色使用同一个对话、同一份记忆。
- 每个提醒按四态记录：`REQUESTED / ACCEPTED / EFFECT_VERIFIED / USER_CONFIRMED`。

**P3 "思考模块"**（用户说的"像活人、会记忆、不接受敷衍"）
- 之前给用户的建议（可以沿用或推翻）：
  - 规则写成动机加判断标准，不要写成禁令。例如"锁是我们约好帮他管时间的，不是惩罚；要解锁先问理由……"。这样换模型也更稳。
  - 用"最多追问一次"这类具体行为约束讨好倾向，不要用"要有个性"这种形容词。
  - 记忆：建一份结构化的"关于他"档案；用 `ToolPkg.registerSystemPromptComposeHook` 在每轮对话自动注入，不依赖模型自己去查。
  - 反思：用 `Tools.Chat.call` 在对话后做一次复盘，把结论写回记忆，下次自动注入。
- 用户可能更想先看到效果，建议先挑一个小切口做出来让他体验。

**P4 给主界面提供统一状态文件**（见第 2 节接口 3）。

**不做的事**（来自用户和原交接文档，必须遵守）
- 不建第二套锁机体系；冻结、解冻、查询**只用 `app_suspender` 插件**，禁止 `pm suspend`。
- 新增或修改处罚、冻结、锁屏、解锁规则属于"立法"，必须用户批准（"狱卒不立法"）。
- 不删除工作流；停用前先备份、停用后回读。
- 不把"工具回执成功"写成"用户已收到"或"闭环完成"。

---

## 6. 工作方式

- **你在云端容器里，碰不到用户的手机。** 能做的是：读 Operit 源码确认真实行为、写 ToolPkg/工作流模板/脚本补丁、写给 Operit AI 执行的指令。每一步都要用户在手机上执行或测试，把结果发回来。
- 读源码：`GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 https://github.com/AAswordman/Operit`，再 `git fetch --depth 1 origin tag v1.12.2` 并检出。**以 v1.12.2 为准，不要用 main 分支。** 类型定义已复制在仓库 `types/`。
- 给 Operit AI 的指令照 `ops/01_disable_legacy_workflows.md` 的格式写：目标清单、禁止事项、逐步操作、每步回读、固定格式的结果表。用户和他的 Operit AI 很习惯这种写法。
- 状态用词统一：`VERIFIED`、`AVAILABLE_UNTESTED`、`RUNNING_BUT_BROKEN`、`INCONCLUSIVE`、`NOT_AVAILABLE`。模拟测试通过写"VERIFIED（模拟宿主）"，不能写成真机已验证。
- 每次交付前自查一遍：会不会和主界面线改到同一个文件？会不会绕过 `action_guard` 或 `app_suspender`？

---

## 7. 等用户提供的材料

- [ ] `ops/01` 停用 5 条遗留工作流的结果表
- [ ] `scripts_export_20260925.txt`（`eventd.sh`、`judge/*.sh`、G2 完整 JSON 和判断官提示词）
- [ ] `drift_scan.sh`、`drift_alert.sh`、`brief_pack.sh`、`drift/channel.txt`（P2 需要，可以让 Operit AI 用同样方式导出）

---

## 8. 仓库里已有的东西

| 路径 | 内容 |
|---|---|
| `focus_hub/` | 主控台 ToolPkg v0.3（看板、会话导航、进展入口、跳转工具）。`README.md` 有安装、验证状态、测试清单 |
| `focus_hub/test/harness.js` | 模拟 Operit 宿主的测试（文件读写的行号和截断、聊天、Intent、Java 桥）。写 ToolPkg 可以参考这种测法 |
| `ops/01_disable_legacy_workflows.md` | 给 Operit AI 的停用指令和脚本导出指令 |
| `design/focus_hub_redesign_mockup.html` | 主界面改版草图（一张脸、心情等于规则阶梯、四个动作、系统页） |
| `types/` | Operit v1.12.2 的 ToolPkg 类型定义 |
