# 整体流程线 → 主界面线：现状同步（2026-09-28）

仓库 `aozhenyu666-cmyk/test`，分支 `claude/operit-ai-analysis-vb197a`，目录 `flow/`。完整现状见 `flow/STATUS.md`。

## 1. 对用户说话的只剩一张脸：小满·陪伴

- 角色卡 `小满·陪伴`（a2a22c7c…），固定会话 52a18815-2c07-4f6d-bbae-ae5d040daffb（标题"小满"）。
- 用户的主对话卡叫 `小满`（7dbdd1d0…），T05 时曾被误改名为 "Xiaoman (Original)"，已改回。
- `小满·陪伴` 开了工具白名单：内建工具只允许 `use_package`，包只允许 `focus_hub_progress` 和 `companion_brain`。
- `drift/channel.txt` 的 `chat_id` 已改为 52a18815…；`tts_chat_id` 换成了新会话 ba81044b…。
- `/sdcard/Download/Operit/companion/config.json` 已写成 `{"chat_id":"52a18815…"}`，所以主控台的"她"就是小满·陪伴。请在 `findCompanion` 的判定顺序里继续尊重这个文件。

## 2. 新 ToolPkg：小满的大脑（local.companion_brain 0.1.2，已真机验证）

- 源码在 `flow/companion_brain/`，设计在 `flow/design/brain_v1.md`。
- 工作流 `BRAIN_Tick` 每 15 分钟调用 `companion_brain:tick`，已取代 `S3_Brief_Loop`（后者已停用）。
- **开口一律调用你们的 `focus_hub_nav:check_in(message=…, force=true, speak, popup)`**，所以打卡记录 `companion/checkins/` 里的 `line` 现在是大脑生成的。
  - `check_in` 的返回可能是 JSON 字符串，大脑按文本里有没有 `CHECKED_IN` 判断，请不要改这个状态字。
  - **请不要启用主控台自带的"陪伴打卡（每小时）"模板**，否则又会变成两个声音。
- **派活**：大脑会写 `companion/warden/assignment.json`，格式和 `setAssignment` 一样，`standard.type="statement"`，所以主控台的督促页能显示。
- **锁**：大脑不调用 app_suspender，只往 `events/lock_queue.txt` 追加 `lock <pkg>`，由 S4 执行。你们 warden 里的"真锁"路径如果要启用，也请走同一个队列，避免两套锁机体系。
- **记忆文件**在 `/sdcard/Download/Operit/companion/brain/`：
  - `profile.md`：关于 TA；
  - `commitments.jsonl`：承诺；
  - `said.jsonl`：她主动说过的话；
  - `state.json`：当前阶段；
  - `actions.jsonl`：每次思考的决定。
  主控台想展示"她最近说了什么、TA 答应了什么"，可以直接只读这几个文件。
- 用户批准的契约：
  - 真锁开启：只锁 ent.list 里的 App，提醒后 30 分钟无回应才锁；
  - 北京时间 23:30–08:00 安静；
  - 每小时最多开口 4 次；
  - AI 类 App 都在 `task_apps.conf` 的 JOB_SEARCH 里，并且在 protect.deny 里永不冻结。

## 3. 进展与判断

- `progress/*.jsonl` 现在有三个来源：主控台按钮、小满·陪伴在会话里调 `report_progress`（via=chat_ai）、打卡回应。
- 判断官的证据包会读最近 24 小时的 REAL_USER 进展，按 `ts` 取，不看文件名日期。
- 时区：设备是 America/Los_Angeles，事件脚本是 Asia/Shanghai。
  - `focus_hub` 写进展时，`iso`/`date` 用的是设备时区；
  - 主控台 `decideCheckin` 的安静时段也是按设备时区算的；
  - 用户选择"代码里写死北京时间"，大脑已经这样做了。建议主控台里跟时间有关的判断也改用北京时间。

## 4. 接下来（流程线）

用户要一个"严格模式"：先主动封锁核心娱乐 App，TA 交差、和小满谈过之后才按时限放开。设计定稿后会再同步。涉及主控台的部分，比如显示严格模式状态、交差入口，到时再对齐。
