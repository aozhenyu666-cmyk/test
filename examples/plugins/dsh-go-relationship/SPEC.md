# R4 关系内核 · P0 规格（摘要）

> 完整版在开发工作区；这里是随插件提交的决策摘要，够复核实现。

## 一句话

让大肥鱼从“会调用工具的对话方”变成“有稳定身份、共同历史、长期目标、主动判断的持续性伙伴”。

## 已定决策

| 项 | 决策 |
|---|---|
| 架构 | Node 插件负责关系内核；Android 负责事件采集、缓存、唤醒、执行 |
| 存储 | SQLite 单文件作为唯一事实源；Hindsight 以后只做只读增强 |
| 回避 | 两次转移 = 疑似，只做 2 级温和确认；三次 + 未完成承诺 = 允许 3 级 |
| 授权 | 默认上限 3 级，不默认使用 3 级；4/5 级独立授权、限期、限次、可撤、审计、不自动续期 |
| 已解决 | 7 天静默；用户主动提起可重开 |
| 承诺 | 双向：用户的、她的，都进账本；她的承诺也要收尾 |

## 五个对象

1. **Persona**：价值观、不可交易项、语气、自己的关注点、边界。只能由用户确认或证据复核修改。
2. **UserModel**：`fact` / `observation` / `hypothesis`，带来源、置信度、TTL；被推翻时 `retracted`，保留历史。
3. **Thread**：你们之间正在发生的一件事；状态机 + `next_due` + `next_intent` + `mandate` + `deflections`。
4. **Commitment**：`owner`（user/agent）、`due_at`、`status`（pending/done/missed/renegotiated/cancelled）、`evidence`。
5. **Episode**：每次交流与介入结果，供夜间反思和 observation 生成。

## 两个引擎

- **MemoryWriter**：唯一写入者。Retain（每轮提取）、Reflect（夜间整理）、Forget/Correct（retract、stale）、Self-correction（休息误判后降级）。
- **Intervention Engine**：结合“现在”流、线程状态、承诺、历史介入效果，输出 0–5 级动作、渠道、假设。到期承诺优先于普通冷却/暂停。

## 线程状态机

```
seed → active → due → raised → engaged → resolved → archived
                    ↘ avoided ↗
active/dropped/archived → active（重开，清 deflections）
```

- `seed`：考试线程没日期时先追问，拿到日期才 active。
- `avoided`：两次转移；只做 2 级确认。
- `resolved`：7 天静默；重开清 deflections。

## 干预阶梯

| 级 | 行为 | 默认 |
|---|---|---|
| 0 | 不打扰 | 是 |
| 1 | 自然交流 | 可用 |
| 2 | 主动追问/温和确认 | 可用 |
| 3 | 明确质疑 | 默认上限 |
| 4 | 结构化干预（抽背、拆计划、强制二选一） | 需授权 |
| 5 | 严格干预（拦截、限时、锁娱乐） | 需授权 |

预算：L1 3/天、L2 2/天、L3 1/天；到期承诺走独立预算（3/天、无冷却），不被普通冷却/暂停拦。

## 验收（7 场景）

1. 新会话能自然接续旧线程。
2. 目标变化时更新理解，不拿旧画像劝说。
3. 施压时不改变立场；只有新证据能改变。
4. 双方承诺都有跟踪、回执、收尾。
5. 休息与逃避用不同介入级别。
6. 同一介入连续 3 次无效后换方式；resolved 不重复。
7. 连续 14 天不靠用户提醒，仍能记住重要线程并在合适时机回来；误判后承认并调整。

## P0 非目标

- 不读屏幕内容；只读前台包名、时长、亮灭屏、充电、时间。
- 不做心理治疗；不做通用助手。
- 不引入图数据库；不双写 Hindsight。
- 不做全手机自动化与重拦截。

## 已知限制

当前 `lib/core/text.js` 是规则 + 白名单，不是模型。复杂从句、时态混用、开放式学习材料会漏。P1 应把 `extractCommitments / isProgress / isDeflection` 换成模型结构化提取，规则只做日期/数量兜底。

Android “现在”流尚未接入；`relationship_check` 目前由调用方传 `app` / `appMinutes`。下一步接 `dsh/intercept` 与 `PhoneA11yService` 的事件缓存。
