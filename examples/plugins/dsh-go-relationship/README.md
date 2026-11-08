# dsh-go-relationship

R4 的**关系内核 P0**：把「她记得我们之间发生过什么、答应过什么、什么时候该管」做成一套可运行的本地账本。

- 本地 SQLite：`$DSH_HOME/relationship/relationship.db`
- 零第三方依赖；不联网、不读屏幕、不写别的插件文件
- 不改变 DSH 的沙箱 / 审批 / 隐私边界

## 五个对象

| 对象 | 作用 |
|---|---|
| `Persona` | 她是谁、坚持什么、不做什么（慢变） |
| `UserModel` | 事实 / 观察 / 推测，带来源与保鲜期 |
| `Thread` | 你们之间正在发生的一件事，有状态机 |
| `Commitment` | 双向承诺：用户答应的、她答应的 |
| `Episode` | 每次交流与介入结果，供夜间反思 |

循环：经历 → 记忆 → 理解 → 判断 → 交流 → 观察结果 → 修正理解。

## 七个工具

| 工具 | 用途 |
|---|---|
| `relationship_status` | 读线程、待办承诺、新会话简报 |
| `relationship_observe` | 记录一条用户原话（承诺/进展/休息/回避/放弃） |
| `relationship_commit` | 把用户或她自己的承诺记入账本 |
| `relationship_correct` | 用户说“不是这样”时让旧理解失效 |
| `relationship_check` | 结合前台应用/时长，判断现在是否介入、几级 |
| `relationship_nightly` | 夜间整理：missed、AI 承诺收尾、生成 observation |
| `relationship_resolve` | 标记线程已解决（7 天静默，可重开） |

## 关键规则

- 默认干预上限 **3 级**；4/5 级必须显式授权（领域/级别/起止/每日次数/可撤销/审计）。
- 两次转移话题只是**疑似回避**，只做 2 级温和确认；三次以上且存在未完成承诺才允许 3 级。
- 用户说明在休息：暂停推进、写自我更正、下一次降一级。
- 已解决线程 7 天静默；用户主动提起可重开。
- 双方承诺都进账本；她答应“明天来问”也必须有 due 和收尾。
- 安静时段不在凌晨判 missed；到期承诺在早上补跟进前保持 pending。

## 安装

在 R4 worktree 里：

```bash
dsh plugin --profile <profile> add /绝对路径/examples/plugins/dsh-go-relationship
```

或在 App 的插件市场/本地目录安装。

## 在 DSH Go 里怎么部署（不靠手机端模型写代码）

发布版走**预置插件**：源码复制到 `app/assets/engine/plugins/dsh-go-relationship/`，
`BuiltinPluginGuest` 在每次引擎启动时把它写进 rootfs，并通过额外的 `--patch` 挂载。
伴随的 `assets/skills/dsh-go-relationship/SKILL.md` 是预置工作流，模型只照流程调用
这 7 个工具。加新能力不需要在手机上装 pnpm 包，也不需要让模型生成代码。完整步骤与
失败模式见 [`docs/BUILTIN-PLUGIN-DEPLOYMENT.md`](../../../docs/BUILTIN-PLUGIN-DEPLOYMENT.md)。

## 测试

```bash
cd examples/plugins/dsh-go-relationship
npm test
```

## 已知限制

意图提取仍是**规则 + 白名单**（`lib/core/text.js`）。它能稳定覆盖验收脚本里的句型，但对复杂从句、时态混用、开放式学习材料会漏。P1 应把 `extractCommitments / isProgress / isDeflection` 换成模型结构化提取，规则只做日期/数量兜底。

Android 的「现在」流（前台应用、停留时长、亮灭屏、充电）由手机操控的 `mcp__phone__now` 提供；工作流要求先读它，再把 `app` / `appMinutes` 传给 `relationship_check`。无障碍被系统冻结时读数会变旧，见 `docs/PHONE-HEALTH-VERIFICATION.md`。

完整产品规格：[SPEC.md](SPEC.md)。
