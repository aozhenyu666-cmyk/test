# 整体流程线：现状（2026-09-27 上线后）

## 现在对用户说话的只有一个角色：小满·陪伴

| 项 | 值 |
|---|---|
| 陪伴角色卡 | `小满·陪伴`（a2a22c7c-dc1f-40be-a00c-7c23ed618a18），设定 v4 在 `flow/cards/xiaoman/character_setting.txt` |
| 工具白名单 | 只允许 `use_package` + 包 `focus_hub_progress`（只能记录和读取用户进展） |
| 固定会话 | 52a18815-2c07-4f6d-bbae-ae5d040daffb（标题"小满"） |
| 主对话卡 | `小满`（7dbdd1d0…，用户自己的，不属于本线） |

## 链路

- **定时陪伴** `S3_Brief_Loop`（每 30 分钟）：收集事实 → 事实没变就跳过 → 【可用事实】发给小满·陪伴（隐藏事实原文，她记得自己说过什么，不在前台时弹通知）→ 洗成一句话 → 记 ALERT → 直接调 `operit_editor:test_tts_playback` 念出来。
- **规则提醒**（A1 等，经 `drift/drift_alert.sh`）：加【可用事实】前缀后发进小满·陪伴的会话，她用自己的话转告；念的是她的回复。出声会话换成了干净的 `ba81044b…`（【内部】出声信道2）。
- **判断官** `G2_Judge_Flow`：会话 50fb0680…（persist_turn=false，每次只看当次证据），卡片工具白名单为空。证据包包含用户最近 24 小时亲口说的进展（`progress/*.jsonl`，按 ts 取）和判读准则（锁屏不算偏离、置信度不逐轮抬高）。
- **用户进展来源**：主控台按钮（via=dashboard）；在小满·陪伴的会话里亲口说（via=chat_ai，由她调用 `report_progress` 记录）。

## 规则变更（用户批准）

- AI 助手类 App（ChatGPT、Claude、Gemini、DeepSeek、元宝、豆包、千问、Kimi、Grok、Chatbox、RikkaHub、ima、NotebookLM、Qoder）算"在任务上"（`p2/task_apps.conf` 的 JOB_SEARCH=），并在 `p2/protect.deny` 里永不冻结。

## 已知遗留（按价值排序）

1. **待真机观察**：小满·陪伴在会话里记录进展是否准确；判断官在锁屏、白名单修正后的判断质量。
2. 规则提醒走外部对话接口，会话里会出现一条【可用事实】开头的"用户气泡"（接口不支持隐藏）。
3. `drift_alert.sh` 的出声仍经过一个模型会话（`speak.sh`），出声信道2 的历史会重新增长；可改为工作流直调 TTS 或定期换会话。
4. 时区：设备是 America/Los_Angeles，事件脚本写死 Asia/Shanghai（用户决定先跳过）。
5. 遗留工作流仍启用：`P4_Drift_Alert`、`PROBE_Terminal_Channel`、`f6cc5199` 一次性任务。
6. 旧陪伴窗（ded96924，秘书卡）、旧出声信道（05caa1d5）已不再使用，未删除。
7. "思考模块"（P3）：结构化的"关于 TA"档案 + 对话后复盘写回记忆，尚未开始。
8. 给主界面线的统一状态文件 `state/now.json`（P4），尚未开始。

## 给主界面线的通知

- 提醒通道 `drift/channel.txt` 的 `chat_id` 已改为小满·陪伴的会话 52a18815…；"陪伴窗"标题未改。
- `progress/*.jsonl` 现在会有 `via=chat_ai` 的记录（来自小满·陪伴）。
- focus_hub 写进展时 `iso`/`date` 用的是设备时区（UTC-7），事件是北京时间；流程线读取时按 `ts` 处理，不受影响。

## 工作方式

给 Operit AI 的都是 `flow/tasks/T0x_*.txt` 任务书（目标 + 红线 + 验收），它执行后把报告写到 `/sdcard/Download/Operit/00_AGENT_HANDOVER/reports/T0x_report.txt`。脚本和卡片文本放在本仓库，手机用 `download_file` 按固定提交下载并校验 sha256。
