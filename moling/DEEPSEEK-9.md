# 第九批：本地案板与第一块实质代工

这是用户与 Gemini 创意综合后的 v13 第一波。第八批仍负责发布稳定，不要混入同一个 PR。本文是可执行任务入口，完整范围和验收以 [交付计划](../partner-blueprint/v13-delivery.md) 第4节的 C9、I9 为准。

先读根 AGENTS、CODEX、CONTEXT，再读：

1. [七脑五手总设计](../partner-blueprint/v13-system-blueprint.md)
2. [契约与跨进程协议](../partner-blueprint/v13-contracts.md)
3. [完整 Kotlin 契约](../partner-blueprint/v13/V13Contracts.kt)
4. [实施分解与验收](../partner-blueprint/v13-delivery.md)

从工作分支 `ccr-b3c7616e-xlohkd` 的最新提交新建 `deepseek/moling-workspace`。已有同名工作先比较与保留，不重置、不强推。

目标不是增加一句“我帮你想好了”，而是交付：用户材料 → 可编辑纲要/首段 → 参谋对具体块挑刺 → A/B选择 → 采用/撤销 → 重启恢复/导出。

复用 Atlas、FOG、Chess、Habits、既有模型端口。新增本地案板、焦点、作业与版本模型；先实现用户点击启动的代工，后续主动代工复用同一条链路。同步修正娱乐打断早期仍问“卡在哪”的入口；模型无选项时必须有本地点选降级。

本批 C9 是明确限定的独立内核功能任务，可新增 `core/workspace/strategy/cycle` 及必要 Brain 适配，调整 Live 的娱乐低门槛策略；I9 的 UI提交不顺手改 core。总控目录、Powers授权规则、计划自动confirmed、签名文件、屏幕/麦克风新权限都不在本批。

完成后在 DEVLOG 追加实际改动、验证、未完成项、用户验收。分里程碑提交并创建指向工作分支的 PR，不自行合入，不凭旧测试数宣称新功能通过。
