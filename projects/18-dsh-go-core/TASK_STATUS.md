# TASK_STATUS

逐项状态。**不能仅凭代码存在就标 PASS**；没有设备证据的一律 NOT_TESTED。

图例：PASS（有本轮证据）/ PARTIAL（部分证据）/ BLOCKED / NOT_TESTED / REVIEW_REQUIRED

## 阶段 A：工程基线

| 项 | 状态 | 说明 |
|---|---|---|
| A1 环境检查 | **PASS** | 见 BUILD_REPORT §1；本地构建可用 |
| A2 克隆上游 | **PASS** | 非常规方式（API tree + curl），3,300 文件逐一校验 |
| A3 切换 d38a39a | **PASS** | 基线提交 `10c3519` |
| A4 应用 Claude 补丁 | **PASS** | 0001 干净应用（`bc72be3`） |
| A5 确认 31 文件范围 | **PASS** | 与 README 记载一致 |
| A6 跑现有测试 | **PASS** | JVM 17/17（当时），analyze 无问题 |
| A7 尝试 debug APK | **PASS** | `assembleArm64Debug` BUILD SUCCESSFUL |
| A8 核实构建链路 | **PASS** | 本仓库按 ABI 分 flavor，任务名非 `assembleDebug` |

## 阶段 B：审查问题修复

| 项 | 状态 | 证据 |
|---|---|---|
| P0-01 界面真实显示判定 | **PASS** | 3 项 JVM 测试 + `InteractionActivity` 可见性证明 |
| P1-01 无障碍异常可观测性 | **PASS** | `LoopDiagnostics` + 2 项测试 + 设置页展示 |
| P1-02 存储可靠性（`FileLoopStore`） | **PASS** | 3 项测试（备份回退/不丢文件/空文件） |
| P1-02 存储可靠性（内核 `AtomicJsonFile`） | **PASS** | 同样缺陷，已修，3 项 `KernelRiskTest` |

## 阶段 C：普通功能任务

| 项 | 状态 | 说明 |
|---|---|---|
| T1 构建与测试 | **PASS** | 单测可跑；APK 已出；SHA-256 与构建日志齐全 |
| T2 设置页完善 | **PASS** | 超时 15/30/60/120、surface、visitId 前 8 位、JSON 导出；中英文案 + 14 项 Flutter 测试 |
| T3 通知兜底 | **PARTIAL** | 契约与状态序列已测（JVM + 回执 `surface=notification`）；**实际发通知 NOT_TESTED**（无设备） |
| T5 本地规则 | **PARTIAL** | 我的 `TriggerRules`（7 项测试）保留且通过；**已按 Claude 集成指南作废**，规则权威在 `ProactiveScheduler`（17 项测试）。两套并存但只有内核那套被调度使用 |
| T7 AgentBridge v0 | **PASS** | 接口 + `LocalTemplateBridge` + `DeliveryGuard`，超时/异常/空文本一律 `Fallback`；测试覆盖 |
| T9 权限与隐私披露 | **PASS** | 披露弹层新增「触发测试」条目；默认关闭；明确只读前台 App 名称 |

## 阶段 D：高风险核心（Claude 主导，我只做适配）

| 项 | 状态 | 说明 |
|---|---|---|
| T4 统一仲裁 | **REVIEW_REQUIRED** | Claude 实现（`Coordinator`，25 项测试）；我未重写 |
| T6 主动调度 | **REVIEW_REQUIRED** | Claude 实现（`ProactiveScheduler`，17 项测试）；我未重写 |
| T8 Agent 真实接入 | **REVIEW_REQUIRED** | Claude 实现（协议 + 插件）；D1 转发是我做的适配 |
| D1 Dart Agent 转发 | **PASS** | 12 项 Flutter 测试：三种 op 的路径/方法/体、失败传 0、远程 404、原始应答不解释 |
| D2 通知界面 | **PARTIAL** | 实现完整、不报 `Shown`；**实际投递 NOT_TESTED** |
| D3 主动搭话设置页 | **PASS** | 12 项 Flutter 测试；开关、间隔范围、免打扰、每日上限、**目标会话选择**、`wakeMode` 诚实说明、transport 状态、回执六段时间戳 |
| D4 打包宿主插件 | **PARTIAL** | `rtbuild.py`/`build-runtime.sh`/`test-host.sh` 已改并验证语法与裁剪规则；**插件 × 真实引擎 e2e PASS 26/26**（含 401 鉴权、不唤醒会话、requestId 不重发、agent-busy、取消后不交出迟到文字、deadline）；完整 rootfs 打包与 bwrap e2e 仍 NOT_TESTED |
| D5 第一轮事件改走内核 | **REVIEW_REQUIRED** | 独立分支 `review/d5-loop-to-kernel` + `PATCHES/0004-d5-loop-to-kernel.patch`，**未并入候选版** |
| D6 真机验证 | **NOT_TESTED** | 无设备，按要求不连接 |

## 阶段 E：T0 验收

| 项 | 状态 |
|---|---|
| 20 次应用切换 | NOT_TESTED（JVM 已覆盖逻辑） |
| 前后台切换 | NOT_TESTED（JVM 已覆盖） |
| 主动退出 | NOT_TESTED（JVM 已覆盖） |
| 服务重连 | NOT_TESTED（JVM 已覆盖） |
| 进程重启 | NOT_TESTED（JVM 已覆盖） |
| 异常回执 | NOT_TESTED（JVM 已覆盖） |
| DSH 任务冲突 | NOT_TESTED（`CoordinatorTest` 已覆盖逻辑） |
| 通知兜底 | NOT_TESTED |
| 调度恢复 | NOT_TESTED（`ProactiveSchedulerTest` 已覆盖逻辑） |
| 模型失败降级 | NOT_TESTED（真实模型） |

## 汇总

- **PASS**：A1–A8、P0-01、P1-01、P1-02（两处）、T1、T2、T7、T9、D1、D3
- **PARTIAL**：T3（实现与契约完整，投递未测）、T5（保留测试但规则权威已移交内核）、D2、D4
- **REVIEW_REQUIRED**：T4、T6、T8（Claude 内核，待审）、D5（待审，未合并）
- **NOT_TESTED**：全部真机项（D6 / T0）、真实模型、远程主机
- **BLOCKED**：无
