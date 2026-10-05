# 现状速览（2026-10-06）

总计划见 `docs/00_总计划.md`（替代之前所有轮次计划）。

## 现在的样子
- **唯一入口**：主控台 0.9.0（`local.focus_hub`），底栏三栏：
  - 专注：严格搭档「司南」+ 专注时段 + 对话门钥匙；
  - 陪伴：小满 + 进展 + 会话；
  - 管理：督促、省流、系统。
- **司南**：主控台直接调用 Operit 的模型接口。
  - 四种模式：追问、陪读、冲刺、复盘。
  - 交流方式：打字、念出、🎙 语音（第一次点时自动建「司南」角色卡和对话）、专注推进主动叫回。
  - 你的话逐字记在 `companion/sinan/log-日期.jsonl`。
- **锁**：还没接通，钥匙只记账。等 A-026 的结果做 M2。

## 进行中
- Operit A：A-030（装 0.9.0，跑通一问一答和语音）。
- Operit B、Codex：暂停。

## 下载
- 安装包：https://raw.githubusercontent.com/aozhenyu666-cmyk/test/ccr-6193e40d-cxxw8a/operit/handoff/install_focus_hub_0.9.0.zip
- 仓库分支：aozhenyu666-cmyk/test @ ccr-6193e40d-cxxw8a，目录 `operit/`（仓库是公开的）
