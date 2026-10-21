# 墨灵：当前交接与完整执行入口

> 更新：2026-10-21。仓库 `aozhenyu666-cmyk/test`，工作分支 **`ccr-b3c7616e-xlohkd`**。仓库默认分支不是本项目施工基线。
> 用户要求继续完成“七脑＋五手”，不能把第八批/I9完成当作整个计划完成。

## 1. 先读什么

根 `AGENTS.md` → 本页 → `partner-blueprint/CONTEXT.md` → **[完整执行计划 DEEPSEEK-ALL.md](DEEPSEEK-ALL.md)**。
总设计和协议链接都在总计划内；当前状态见 [V13-PROGRESS.md](V13-PROGRESS.md)。历史上下文有旧“未做”表述，当前状态以实际代码、PR和这两页为准。

## 2. 当前代码与待整合成果

- 第四、五批Codex和第六、七批DeepSeek已经合入，不重做。
- Claude已在 `147f8bd` 提交C9核心：workspace/Workbench、strategy/Prework、BrainHost的bench接口和娱乐入口统一破雾；工作分支CI通过。
- 第八批发布加固：[PR13](https://github.com/aozhenyu666-cmyk/test/pull/13)，已提交/CI绿/检查时未合入。
- 第九批I9：[PR15](https://github.com/aozhenyu666-cmyk/test/pull/15)，主要界面已提交/CI绿/未合入，来源标签点原文与真机验证未收尾；其中已包含PR13所有提交。
- 动效打磨：[PR14](https://github.com/aozhenyu666-cmyk/test/pull/14)，独立待整合，不能被PR15覆盖丢失。
- 第十至十三批任务书本次已补齐，尚未实现；开工重查最新SHA和状态，不沿用以上快照当实时事实。

现有生产对象是 `Focus/Material/WorkbenchState/WorkspaceArtifact/PortResult`，协调器为 `core/workspace/Workbench.kt`，App通过 `BrainHost.workbench/bench*` 使用。设计DTO不是已存在的SDK；复用实际模型，不复制一套平行状态。

## 3. 后续完整任务

| 顺序 | 施工文档 | 内容 |
|---|---|---|
| P0 | [总执行计划第4节](DEEPSEEK-ALL.md) | 收拢已有PR、来源原文查看、回归工作台 |
| 第十批 | [DEEPSEEK-10.md](DEEPSEEK-10.md) | 联网/RSS/正文、来源记忆、统一循环与主动实质代工 |
| 第十一批 | [DEEPSEEK-11.md](DEEPSEEK-11.md) | 只听电台、轮盘/语音、反免疫、材料化对弈与选择赌局 |
| 第十二批 | [DEEPSEEK-12.md](DEEPSEEK-12.md) | 外脑会话闭环、限定捕获、进场/胶囊/收线 |
| 第十三批 | [DEEPSEEK-13.md](DEEPSEEK-13.md) | 计划代驾、总控训练兑换、系统工具和整套验收 |

第九批详细I9仍见 [Claude的任务书](DEEPSEEK-9.md)。不把C9再分配一次。第八批仍见 [DEEPSEEK-8.md](DEEPSEEK-8.md)。

## 4. 核心与权限的任务边界

1. **界面任务不改core**。第四至七批和第九批I9沿用这个约束。
2. **第十至十三批含明确C/Z核心任务**：按各批允许文件/协议范围实现并补测试；不要求等待Claude先写核心。UI、核心、总控分里程碑提交。不能借总重构顺便改权限规则。
3. 保留必要应用/急事、授权书、冷静期、固定包名与签名。墨灵提议与总控执行分开；不能把模型分数或未回应当成新增授权。
4. 屏幕、语音、日历等能力仅在对应批次明确范围内接入，权限与会话状态可见，可停止、有拒绝降级。不在纯界面批次顺手增加权限。
5. 总控的推送可能覆盖手机正式包；所有施工分支包含工作分支最新提交。开发分支使用artifact；正式发布仅已审查基线。
6. 不强推、不擅自合入共享工作分支。需要整合多个前置时在独立分支完成并交PR；不以Claude登录故障为由停止可独立推进的工作。

## 5. 验证与交付

纯逻辑在 `tools/core-tests/` 跑实际用例；受影响App跑 `./gradlew testDebugUnitTest assembleDebug`，Android以CI为准。测试数量写本次结果或标记录来源，不沿用旧343/410/432代替新功能验收。

每批更新DEVLOG和V13-PROGRESS：实现、自动验证、真机验证分别写；附分支/提交/PR/CI、未完成项与操作步骤。网络/模型/总控连不上时降级并记录原因，不伪装成功。

用户验收入口：[10分钟测试](TESTING-QUICK.md) 与 [完整测试](TESTING.md)；新批次增加专属流程。构建成功不等于固定下载包已更新，发布后另查SHA/版本/tag/直链。
