# 导出分析 01（flow_export_20260925.txt）

来源：`ops/02` 导出，设备 shell 时间 2026-09-25 09:37:55（时区见第 4 条），332 个文件，NOT_FOUND=0。

## 1. 判断官（G2）自 09-23 00:06 起全部失败 — RUNNING_BUT_BROKEN

- `judge/save_log.tsv` 最后一条 `SAVED` 是 `2026-09-23 00:06:29 verdict=DRIFT_RISK conf=55`，之后只有 `GATE_SKIP`。
- 工作流计数：09-24 23:54 为 373/235/138，本次 394/235/159。新增 21 次运行，21 次都失败，成功数一直停在 235。
- 证据包 `.pack_last.txt` 和哈希 `.last_evidence_hash` 仍在更新（09:14），说明 `e_pack`、`e_gate` 在跑。
- `judge_save2.sh` 从未记过 `REJECTED_OUTPUT`，所以没走到 `e_save2`。失败在 `e_judge`（`extended_chat:chat_with_agent`）或 `e_raw`（`create_file`）。
- 按 Operit v1.12.2 `examples/extended_chat.ts`，`chat_with_agent` 会报错的情况有三种：找不到角色卡"判断官"；会话 `28f6fbb9` 绑定了别的角色；`sendMessage` 抛错（模型接口错误、上下文过长等）。超时不算，超时返回 success=true。
- 附带缺陷：`hash_gate.sh` 在判断**之前**就写入新哈希，所以判断失败后同样的证据不会重试，只会 `GATE_SKIP`。
- 下一步：`ops/03_g2_judge_diagnose.md`（执行日志 + 角色卡 + 会话 + 时区）。

## 2. 证据包内容问题（P1 相关）

- `gen_pack.sh` 不读 `progress/`，用户进展完全进不了判断。确认了交接文档说的头号缺陷。
- 证据包里有一条自动规则 `RV-AUTO-3778c2de`："历史经验：连续 30 次判断为 DRIFT_RISK"。判断官会被自己过去的结论带偏，形成自我强化。
- 证据包每次都附带 20 多条 `LESSONS.md` 施工教训标题，与判断无关，只增加 token。
- 截屏取证 `FACT|...` 行混入了 `\n<silent mood=\` 这类残留，属于数据质量问题。

## 3. 提醒通道现状

- `drift/channel.txt`（09-23 v5）：`mode=WINDOW`，`speak=true`，`show_floating=false`，文字发往陪伴窗 `ded96924`（秘书角色卡），语音另走 `tts_chat_id=05caa1d5`。
- 按 v5 的注释，用户当时觉得弹窗挡屏、里面是旧对话，所以改成不抢屏。结果文字进了一个用户不会主动打开的会话，语音由另一个会话念出来，两者不共享上下文。
- S3 秘书链正常（232/197/35），但 `drift/.brief_resp.prev.*` 从 09-22 起每 30 分钟一份，说明秘书每轮都在开口。

## 4. 时区不一致（INCONCLUSIVE，待 ops/03 第 4 步确认）

- `eventd.sh`、`gen_pack.sh`、`hash_gate.sh` 等写死了 `TZ=Asia/Shanghai`，因此事件目录已经到了 `20260926`。
- 没设 TZ 的脚本（`judge_save2.sh`、`gate_skip.sh`）和 focus_hub 的进展记录用设备默认时区。进展 `ts=1790353282` 对应 UTC 16:21，`iso` 却写的是 `09:21`，相当于 UTC-7。
- 后果：同一时刻，事件记在 `20260926`，进展记在 `20260925.jsonl`。P1 按日期读进展时会对不上。改 focus_hub 的时间格式属于主界面线，需要通知那边。

## 5. 工作流开关

- `Lock_Freeze_Lock`、`Lock_Freeze_Unlock` 当前 `enabled=false`，但 `ops/01` 回报"未执行"，是谁停的不清楚（INCONCLUSIVE）。
- `P4_Drift_Alert`、`PROBE_Terminal_Channel`、`f6cc5199` 一次性任务仍是 `enabled=true`。
- 导出脚本的缺陷：执行日志是按工作流分子目录存的，H 段没进子目录，所以没取到 G2 日志。已在 ops/03 补上。

---

## 补充：ops/03 诊断结果（2026-09-26）

- **G2 根因已确认（VERIFIED）**：最近两次执行日志都是 `AskJudge` 失败，错误原文 `读取对话消息失败: Chat not found by query: 28f6fbb9-e08c-4536-8500-60cde7647094`。判断官会话已不存在。角色卡"判断官"还在，id `1bd093cf-1a17-459f-92ed-6e8c8ea788a7`（共 10 张卡）。
  - 谁删的查不到。`plugins/moodlet/data.json` 给这个会话标了"会话轮换建档"，只是个标签。
  - 修复指令：`ops/04_g2_rebind_judge_chat.md`，需要用户批准。
- **手机时区（VERIFIED）**：`persist.sys.timezone=America/Los_Angeles`，`date` 输出 PDT（UTC-7）。
  - 事件脚本写死了 `TZ=Asia/Shanghai`；其他没设 TZ 的脚本，以及 focus_hub 的 JS，都按洛杉矶时间走。两边相差 15 小时。
  - 以哪个时区为准，要看用户人在哪里，需要用户确认。
