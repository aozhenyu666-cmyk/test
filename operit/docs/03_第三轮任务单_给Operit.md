# 第三轮任务单：安装主控中枢（给 Operit A 组）

> **已被取代（2026-10-05）**：主控中枢将升级为 v0.2「核心对话台」，安装步骤并入 `operit/rounds/A-027_给Operit_A.md`。本文件不要再发。

task_id：A-030-zhukong；前置：A-020（cognitive_core 0.2.0 已升级）与 B-020（adapter-catalog、xiaoman-integration 已交付）。

背景：主控中枢是新的 ToolPkg（`com.community.zhukong`），提供对话门、伴读启动和收线。思考线写进已安装的 cognitive_core，不另建账本。它不直接冻结或解冻任何 App；真实执行只通过之后绑定的现有通道。设计见 `docs/02_详细设计.md`。

交付目录：`/sdcard/Download/Operit/acceptance/integration-030/A/` 下的独立本轮目录。

## 步骤

1. 读取并备份 `cognitive_core:status` 原始返回（不改变它的提醒开关和事务）。
2. 核对安装包 SHA256（期望值见本仓库 `operit/dist/SHA256SUMS`），不一致就停止并回报。
3. 安装 `zhukong-0.1.1.toolpkg`，启用子包 `zhukong`。同步 Skill（`zhukong-skill-0.1.1.zip` 解压到 Skills 目录），不要猜测加载成功。
4. 调用 `zhukong:status`，确认 version=0.1.1、`thread.backend=cognitive_core`、`executor.unlock=false`。
5. 侧边栏打开「主控台」，截图。面板报错时保留原文。
6. **伴读测试**（施工测试，标明不是用户的认知任务）：在面板填标题"施工测试材料"，点「开始伴读」，确认 Gemini 被打开；读回 cognitive_core status，确认出现该材料且活动为 watching。不要代替用户在 Gemini 里点共享屏幕。
7. **对话门测试（只记账）**：在面板依次测试三种情况：B站敷衍回答 → retry；B站三题具体回答 → 钥匙状态 unbound；抖音开门后不回答，3 分钟后调用 `zhukong:gate_tick` → 显示冷却 5 分钟。这些是施工数据，回执中注明 gate_id。注意：这次测试会占用用户当天 1 把钥匙并留下 1 次冷却，请在回执里提醒用户。
8. **工作流**：调用 `zhukong:install_workflows`（不带 enable），确认创建了 4 条停用的工作流，读回其节点与 triggerConfig。不要启用。
9. **小满说话（只有 B 组确认小满会话 ID 后才做）**：`zhukong:configure {"speak":{"mode":"chat","chat_id":"<小满会话完整ID>"}}`，手动调用一次 `zhukong:reading_invite`，确认小满会话里出现一句邀请。
10. **不做**：不绑定 `routes.unlock/lock`，不启用工作流，不修改钥匙参数，不碰 S4 队列和解锁闸门。这些等用户用满一周、并且 B 组的锁队列接口核实后再做。

## 回执

开头 `A｜A-030-zhukong｜阶段`。交付 before/after 的 status 原文、面板截图、工作流读回、测试 gate_id 与结果。分别写明：已安装、已验证、未验证、失败，以及原因。

## 交给用户的三件事（回执最后单独列出）

1. 在 Gemini 里建「伴读」Gem（设定在 `operit/gemini/伴读Gem设定.md`）。
2. 新建「守门人」角色卡（设定在 `operit/roles/角色卡说明.md`），把会话 ID 告诉施工方。
3. 选一份接下来真的要学的材料，作为第一次伴读。
