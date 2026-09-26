# 给 Operit AI 的指令：修复判断官（G2）会话断链

> 用法：用户批准后，把"指令正文"整段复制给 Operit AI。
>
> **根因（ops/03 已查明）**：`G2_Judge_Flow` 的 `e_judge` 节点把判断发往会话 `28f6fbb9-e08c-4536-8500-60cde7647094`，但这个会话已经不存在。执行日志原文：`读取对话消息失败: Chat not found by query: 28f6fbb9-…`。角色卡"判断官"（`1bd093cf-1a17-459f-92ed-6e8c8ea788a7`）还在。自 2026-09-23 00:06 起，判断官再没存下过任何判断。
>
> **修法**：新建一个绑定"判断官"角色卡的会话，把 `e_judge` 的 `chat_id` 换成它；同时把 `persist_turn` 改为 `false`。
>
> 为什么改 `persist_turn`：
> - 原来是 `true`，每轮证据包（约 9.5 KB）和判断都会存进这个会话，下一轮全部作为上下文再发给模型。越跑越贵，而且判断官会被自己过去的结论带偏。
> - 改成 `false` 后，这一轮不写入会话，会话保持为空，每次判断只看当次的证据包。
> - 判断结果本来就由 `judge_save2.sh` 落盘到 `judge/<日期>.jsonl`，不依赖会话留存。
> - 这个效果目前是 AVAILABLE_UNTESTED（源码：`ChatTurnOptions.persistTurn`），第 7 步会实测。
>
> 依据：Operit v1.12.2 源码中的 `examples/extended_chat.ts`（`chat_with_agent`）、`StandardChatManagerTool.kt`（`create_new_chat`、`update_chat_title`）和 `StandardWorkflowTools.kt`（`patch_workflow` 的 `node_patches` 用 `op=update` 时只合并给出的 `actionConfig` 键）。

---

## 指令正文

本轮修复判断官工作流 `G2_Judge_Flow`（`b3b65845-74ad-4834-9806-73d859775284`）的会话断链。用户已批准本轮操作。严格按步骤执行，每一步都要有工具回读证据；遇到任何与预期不符的情况，立即停止并报告，不要自行变通。

### 禁止事项

- 只修改 `G2_Judge_Flow` 的 `e_judge` 节点里的 `chat_id` 和 `persist_turn` 两个参数。不改其他节点、连线、触发器、开关，也不改其他工作流。
- 不删除任何会话、工作流或文件。
- 不手动触发任何工作流（等它按 30 分钟的定时自己跑）。
- 不调用 `app_suspender` 的任何工具。
- 不向判断官或任何会话发消息。

### 步骤

**第 1 步：确认前提。**
- 调用 `extended_chat:find_chat`，`query`=`28f6fbb9-e08c-4536-8500-60cde7647094`，`match`=`exact`。预期：找不到。如果找到了，立即停止并报告。
- 调用 `extended_chat:list_character_cards`，确认存在名字恰好是 `判断官`、id 为 `1bd093cf-1a17-459f-92ed-6e8c8ea788a7` 的卡片。不符就停止。

**第 2 步：备份。** 调用 `workflow:get_workflow`，`workflow_id`=`b3b65845-74ad-4834-9806-73d859775284`，把完整返回 JSON 原样写入：
`/sdcard/Download/Operit/00_AGENT_HANDOVER/g2_backup_20260926/b3b65845-74ad-4834-9806-73d859775284.json`
写入后用 `super_admin:shell` 执行 `wc -c <文件>; grep -c 28f6fbb9 <文件>`，确认文件非空并且包含旧会话 ID。

**第 3 步：新建判断官会话。** 调用 `create_new_chat`，参数：
- `group` = `后台角色`
- `set_as_current_chat` = `false`
- `character_card_id` = `1bd093cf-1a17-459f-92ed-6e8c8ea788a7`

记下返回的新 `chatId`（下文记作 `<NEW>`）。返回为空或失败就停止。

**第 4 步：改标题，防止被误删。** 调用 `update_chat_title`，`chat_id`=`<NEW>`，`title`=`判断官·G2后台（勿删）`。

**第 5 步：回读新会话。** 调用 `extended_chat:find_chat`，`query`=`<NEW>`，`match`=`exact`。确认能找到，并且绑定的角色卡名字是 `判断官`。不是就停止，不要进行第 6 步。

**第 6 步：改节点。** 调用 `workflow:patch_workflow`：
- `workflow_id` = `b3b65845-74ad-4834-9806-73d859775284`
- `node_patches` = 下面这段 JSON（把 `<NEW>` 替换成第 3 步的真实 ID，其他字符不要动）：

```json
[{"op":"update","id":"e_judge","node":{"actionConfig":{"chat_id":"<NEW>","persist_turn":"false"}}}]
```

不要传 `name`、`description`、`enabled`、`connection_patches`。

**第 7 步：回读工作流。** 再调用一次 `workflow:get_workflow`（同一个 ID），核对：
- `e_judge` 的 `chat_id` = `<NEW>`，`persist_turn` = `false`，`character_card_name` = `判断官`，`message` 仍然引用 `e_pack`，`timeout` = `240`；
- 节点数、连线数和 `enabled` 与第 2 步的备份一致。

任何一项不符：用备份里的 `e_judge` 原参数再 patch 回去，然后停止并报告。

### 输出格式

只输出下面这张表和一句结论：

| 步骤 | 结果 | 证据（ID、回读值或错误原文） |
|---|---|---|

`结果` 只能是：`OK`、`STOPPED`、`FAILED`（附错误原文）。

结论写一句，例如："e_judge 已改绑到 <NEW>（判断官），persist_turn=false，其余节点与备份一致；等下一次定时运行验证。"

---

## 验证（第二天或 30～60 分钟后，单独发）

用 `super_admin:shell` 执行，把输出原样发给用户：

```sh
tail -n 5 /sdcard/Download/Operit/judge/save_log.tsv; ls -t /sdcard/Download/Operit/workflow/_execution_logs/b3b65845-74ad-4834-9806-73d859775284 | head -3
```

再调用 `extended_chat:find_chat`（`query`=`<NEW>`，`match`=`exact`），报告消息数量。预期是 0 或接近 0，这样才能证明 `persist_turn=false` 生效。

判定：
- 出现新的 `SAVED verdict=…` 行：判断链恢复（VERIFIED）。
- 只有 `GATE_SKIP`：证据没变，再等一轮。
- 出现 `REJECTED_OUTPUT`：会话通了，但判断官的输出格式不对，把最新执行日志发给外部 Agent。
