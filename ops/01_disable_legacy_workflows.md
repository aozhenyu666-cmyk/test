# 给 Operit AI 的指令：停用 5 条遗留工作流

> 用法：把"指令正文"整段复制给 Operit AI。它只做停用和回读，不删除、不触发、不改其他任何东西。

---

## 指令正文

本轮只做一件事：**停用**下面 5 条遗留工作流。用户已批准停用（不是删除）。严格按步骤执行，每一步都要有工具回读证据；遇到任何与预期不符的情况，立即停止并报告，不要自行变通。

### 目标清单

| ID 前 8 位 | 名称 | 停用理由 |
|---|---|---|
| `784b49ac` | `Lock_Freeze_Lock` | 历史四包冻结，旧配置带 6 小时自动解冻，与现行 S4 冻结队列冲突 |
| `609f5f96` | `Lock_Freeze_Unlock` | 历史四包解冻，与 S4 + UNLOCK_POLICY 冲突 |
| `59ea3c7e` | `P4_Drift_Alert` | 历史跑偏提醒，已被 S3/管家链覆盖 |
| `79554300` | `PROBE_Terminal_Channel` | 一次性探针残留 |
| `f6cc5199` | `一次性定时任务 2026-09-22 12:47` | 过期一次性任务残留 |

### 禁止事项

- 不删除任何工作流（不调用 `workflow:delete_workflow`）。
- 不触发任何工作流（不调用 `workflow:trigger_workflow`）。
- 不修改任何工作流的节点或连线（不调用 `update_workflow` / `patch_workflow`）。
- 不调用 `app_suspender` 的任何工具，不改变任何 App 的冻结状态。
- 不修改清单以外的工作流。

### 步骤

**第 1 步：确认目标。** 调用 `workflow:get_all_workflows`。对清单里每一条，找出 **ID 以该前 8 位开头、且名称完全一致** 的工作流，记下完整 UUID 和当前 `enabled`。
- 两个条件有一个对不上：这一条标记为 `NOT_FOUND_OR_MISMATCH`，跳过，不要猜。
- 已经是 `enabled=false`：标记为 `ALREADY_DISABLED`，跳过。

**第 2 步：检查引用。** 读取 `/sdcard/Download/Operit/workflow/` 下所有 JSON（或对其余每条启用中的工作流调用 `workflow:get_workflow`），查找这 5 个完整 UUID 是否出现在任何 `workflow:trigger_workflow` 节点的 `workflow_id` 里。
- 如果有：**整轮停止**，报告"哪条工作流的哪个节点引用了哪个目标"，等用户决定。

**第 3 步：备份。** 对每条要停用的目标调用 `workflow:get_workflow`，把完整返回 JSON 原样写入：
`/sdcard/Download/Operit/00_AGENT_HANDOVER/disabled_backup_20260925/<完整UUID>.json`
写入后读回文件确认非空，并且包含该 UUID。

**第 4 步：逐条停用。** 对每条目标调用 `workflow:disable_workflow`，一次一条。

**第 5 步：回读。** 再调用一次 `workflow:get_all_workflows`，确认每条目标 `enabled=false`，并确认清单外工作流的数量和开关与第 1 步完全一致。

### 输出格式

只输出下面这张表和一句结论，不要写其他内容：

| 名称 | 完整 UUID | 停用前 enabled | 备份文件 | 停用后 enabled（回读） | 结果 |
|---|---|---|---|---|---|

`结果` 只能是：`DISABLED_VERIFIED`、`ALREADY_DISABLED`、`NOT_FOUND_OR_MISMATCH`、`STOPPED_REFERENCED`、`FAILED`（附错误原文）。

结论写一句，例如："5 条中 4 条 DISABLED_VERIFIED，1 条 ALREADY_DISABLED；清单外 21 条工作流开关与停用前一致。"

补充说明：停用工作流不会撤销 `app_suspender` 里已经排好的自动解冻计时（如果旧锁链曾经设置过），这一点只报告、不处理。

---

## 可选：导出脚本（只读，为修判断官去重和接入进展做准备）

> 这部分单独发，和上面的停用分开执行。

本轮只读，不修改任何文件。把下面这些文件的**完整内容**合并写入一个文件
`/sdcard/Download/Operit/00_AGENT_HANDOVER/scripts_export_20260925.txt`，
每个文件前面加一行 `===== <完整路径> =====`，文件不存在就写 `===== <完整路径> ===== NOT_FOUND`：

- `/sdcard/Download/Operit/events/eventd.sh`
- `/sdcard/Download/Operit/judge/` 目录下所有 `.sh`（至少包括 `ingest.sh`、`gen_pack.sh`、`hash_gate.sh`、`judge_save2.sh`）
- 判断官（`G2_Judge_Flow`）发送给"判断官"会话的提示词原文（在工作流节点里，调用 `workflow:get_workflow` 读取 `b3b65845` 开头的工作流，把完整 JSON 也附上）

写完后读回，报告文件大小和包含的文件数。然后把这个文件发给用户。
