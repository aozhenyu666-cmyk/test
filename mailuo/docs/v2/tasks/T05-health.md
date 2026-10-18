# T05 体检 V2：逻辑与实操双轨巡检

**依赖**：T02（可与 T03 / T04 并行）　**改动范围**：`www/js/features.js`（`healthCheck`）、`www/js/app.js`（`viewHealth`、今日页体检磁贴）、`dev/unit/`、`dev/e2e.mjs`

## 规则（全部写在 `features.js` 的 `HEALTH_RULES`，每条一个纯函数）

| key | 名称 | 级别 | 判定 | 给出的建议动作 |
|---|---|---|---|---|
| `undecided` | 议而不决 | high | 把握 ≥ 75 的论，或任何问；其 `store.descendants()` 里没有一条未暂停的规 | 按钮「挂一条规」：打开新建规弹层，`under` 预填 |
| `orphanRule` | 师出无名 | high | 规的 `under` 为空或指向不存在的节点 | 按钮「挂到…」：打开挂载选择器（复用 T02） |
| `inverted` | 地基倒挂 | high | 规把握 > 85，但它最近的上级论（`store.ancestors` 里第一个论）把握 < 70 | 文案「上游只有 X% 把握，规却有 Y%」 |
| `noSentinel` | 盲信无哨 | mid | 论把握 < 90，且没有 delta ≠ 0 的验证哨。只有 delta=0 的哨时，文案改成「验证哨没有加减分」 | 按钮「补验证哨」：打开编辑 |
| `stale` | 脱离实操 | mid | 规把握 ≥ 80、未暂停、`created` 早于 7 天前，最近 7 天 `checks` 里没有 kind 为 有效 / 卡壳 / 回填 的记录；**或者** 最近 7 天卡壳 ≥ 2 次 | 文案「这条规是不是太难执行？建议降级或拆分」 |
| `broken` | 断链 | high | `under` 或 `links[].to` 指向不存在的节点 | 按钮「清理」：去掉坏引用 |
| `dupe` | 可能重复 | low | 沿用现有实现（类型相同、非上下级、标题像或激活词重合） | 「合并」按钮（已有） |
| `kwCount` | 激活词不足 | low | 沿用现有实现 | 「AI 补全激活词」按钮（已有） |

- v1 的其他规则全部删掉：noVerify、overconfident、weakVerified、旧 stale、badRel、orphan、long、noWhy、rivalNoTest。
- 健康分算法不变：high 6、mid 3、low 1，按节点数归一。
- 「今天」用参数传入（`healthCheck(store, system, { today })`），方便单元测试。

## 界面

- 问题卡片按级别排序，标题前是级别圆点。每个节点 chip 后跟一个对应的建议动作按钮，见上表。
- 今日页「体检」磁贴下方的小字改为最严重的一类问题名，例如「议而不决 2」。

## 验收

- `npm run test:unit`：新建 `dev/unit/health.test.mjs`，每条规则至少一个命中、一个不命中的用例，用 `makeStore` 构造。
- `npm run test:e2e`：
  - 载入示例体系后打开 `#/health`。
    - `check`：出现「议而不决」，命中 n-0001…n-0005 中把握 ≥ 75 的那些。示例里它们是 50–60，所以先把 n-0001 的把握改到 80 再检查。
    - `check`：出现「盲信无哨」，n-0001 只有迁移来的 delta=0 哨，文案是「验证哨没有加减分」。
  - 新建一条不挂载的规：`check` 出现「师出无名」。
  - 截图 `11-health`。
