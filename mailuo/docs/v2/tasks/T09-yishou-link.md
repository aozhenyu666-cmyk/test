# T09（暂缓）与「一手」「总控」联动：规供弹 + 实操回流

**依赖**：T06　**状态**：等 T01–T08 完成、在真机上用过一周后再做

## 目标

脉络里的规，带着触发场景推给「一手」的回合指令库。你在一手里是顺利执行了还是卡壳跳过，这个数据回流到脉络，加减规和上游论的把握度。

## 文件契约

不走网络，三个 App 共用手机「文档/脉络」文件夹。

- **脉络写出** `.mailuo/outbox/rules.json`：每次规变化时整体重写。
  ```json
  {"version": 1, "updated": "2026-10-18T09:00:00Z",
   "rules": [{"id": "n-0101", "title": "…", "action": "…", "triggers": ["…"], "keywords": ["…"],
              "confidence": 63, "paused": false, "chain": ["为什么想到一半就停？", "下一问没有产生"]}]}
  ```
- **一手 / 总控写入** `.mailuo/inbox/feedback-<来源>-<时间戳>.json`：
  ```json
  {"source": "一手", "items": [{"rule_id": "n-0101", "date": "2026-10-18", "result": "有效", "note": "第 3 回合用上了"}]}
  ```
  `result` 取值：有效 / 卡壳 / 跳过。
- **脉络回到前台时**扫描 inbox：
  - 有效 / 卡壳逐条调用 T06 的 `applyFeedback(store, rule_id, result, { source })`，跳过只记 checks；
  - 处理完把文件移到 `.mailuo/inbox/done/`；
  - toast「一手回流 N 条反馈」。

## 实现前要先确认

- 「一手」「总控」的代码在本仓库其他分支（`yishou` / `zongkong` 相关 workflow 所在分支）。先读它们的存储位置和回合指令数据结构，再决定 outbox 字段是否需要调整。
- 安卓分区存储下，三个 App 能否互相读写同一文件夹里的文件。见 README「v0.1.5」一节的说明，可能需要改用共享 ContentProvider 或系统分享。
