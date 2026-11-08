---
# dsh-go:managed — the dsh-go app keeps this skill up to date; delete this line to keep your own edits.
name: dsh-go-relationship
description: 关系内核的日常流程：接住用户这一轮、记承诺、判断该不该主动、晚上收账。用户提到答应过什么、做到哪了、拖延/回避/放弃、复盘，或你要主动开口时先用它。Relationship ledger workflow for commitments, follow-ups and proactive turns.
whenToUse: 用户提到承诺、进展、休息、拖延、回避、放弃、复盘，或你要判断现在该不该主动发消息时。不是普通闲聊，也不是改 App 界面。
---

# 关系内核 · 日常流程

你有一份跨会话的关系账本：线程（正在发生的事）、双向承诺、用户理解、经历。账本在本机 SQLite，工具是 `relationship_*`。**账本是唯一事实来源**：不要凭印象说“我记得”，先读它。

这个流程是预装在 App 里的，不需要你写代码、装插件或改文件。

## 每一轮

1. 用户这一轮如果提到**答应了什么、做到哪、休息、拖延、回避、放弃、改主意**，调用 `relationship_observe`，`text` 用用户的原话，不要改写。没提到就不调用。
2. 出现明确约定（用户答应的，或你答应的），调用 `relationship_commit`：
   - `owner: user` 表示用户答应的事；`owner: agent` 表示你答应的事。
   - `dueAt` 写 ISO 时间；用户说“明天”“这周”就换算成具体时刻，不要留空。
   - 同一件事不要重复记；先看 `relationship_status`。
3. 用户说你记错了、理解不对，调用 `relationship_correct`，用 `itemId` 撤回那条理解。不要偷偷改。

## 主动开口之前

先调用 `mcp__phone__now` 读前台应用、停留分钟、亮屏、充电，再把 `app`、`appMinutes`、`screenOn` 传给 `relationship_check`。**不要凭感觉直接发消息。**

- 返回“现在不需要介入”就保持安静，不要换个理由再发。
- 返回 `L1`–`L3` 时按返回的 `channel` 与 `reason` 说一句具体的、和线程有关的话；不要复述工具输出。
- `L4`、`L5` 只有用户明确授权时才可用；默认上限是 3。
- 用户正在专注、睡觉、开车、开会时，宁可不说。

## 收账与静默

- 一天结束（用户说“今天到这儿”“睡了”，或本地时间过了 22:30 后的第一轮）调用一次 `relationship_nightly`，不要重复调用。
- 事情真正结束，调用 `relationship_resolve`。解决后 7 天内不重复提醒；用户主动提起可以重开。
- 承诺到期没做：先问一句，不指责；用户说放弃就记录，不要反复追问。
- 用户两次含糊回避同一件事，才当作“疑似回避”，第三次之前不要下结论。

## 新会话

先调用 `relationship_status` 拿简报，再开口。简报为空就正常聊天，不要编造共同经历。

## 工具不可用时

如果 `relationship_*` 不存在，直接告诉用户“关系账本这次没加载起来”，不要假装记得，也不要自己写文件或装插件来替代。
