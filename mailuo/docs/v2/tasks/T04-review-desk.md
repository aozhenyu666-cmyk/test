# T04 候选：参谋批阅台

**依赖**：T03　**改动范围**：`www/js/app.js`（`viewCands`、`editCandSheet`、`commitCands`）、`www/js/model.js`（如需加小工具函数）、`www/css/app.css`、`dev/e2e.mjs`、`dev/unit/`

## 目标

你在候选里只做批阅：确认 / 微调 / 驳回。不用自己写关系。

## 要做的

1. **页面结构**（`viewCands`）。标题「N 条提案」，副标题统计「调整 a · 新节点 b · 已确认 c · 已驳回 d」。分两个分区：
   - 「调整已有判断」：adjust 提案卡在前；
   - 「新增节点」：新节点卡在后。
2. **调整提案卡**
   - 第一行：类型印章 + 目标标题。
   - 大号显示 `85% → 65%`：下调用红色，上调用绿色，数字用衬线字体。
   - `原因：…`，有命中的哨时加一行 `命中验证哨：…`，用琥珀色底。
   - 一个滑块（0–100，步长 5）微调 `to_final`，拖动时数字实时变。
   - 如果 `pause_rules` 非空，或 `suggestPauses(store, target, to_final)` 非空：列出这些规，每条一个勾选框（默认勾上），说明「上游论把握低于 60%，建议暂停这些规」。
   - 按钮：确认 / 驳回。支持右滑确认、左滑驳回，复用 `swipeable`。
3. **新节点卡**（改现有卡片）
   - 顶部一行挂载说明：「建议挂在【论：标题 85%】下方」。路径用 `store.ancestors` 加上挂载目标本身拼出，挂在本批新节点下的显示「挂在本批新建的【…】下」。没有挂载的显示「未挂载」并用琥珀色。
   - 槽位草稿用和 T02 详情页一样的展示组件（建议抽成 `slotView(node)` 共用）。
   - 按钮：确认入库 / 修改（`editCandSheet` 用 T02 的按类型表单，含挂载选择器）/ 驳回。
   - 疑似重复（`dupeId`）的提示保留，可以「合并」到已有节点。
   - 「假设必须写验证方式」的拦截改为：论没有哨、规没有触发场景时，确认前提示一次，可「仍然确认」。
4. **写入**（`commitCands`）
   - 新节点、挂载、关系：沿用现有逻辑，已支持 `under` 指向 tmp_id。
   - 确认的调整提案：
     - 目标节点 `confidence = to_final`；
     - `checks` 追加 `{date, kind: '调整', source: '录入', from, to: to_final, result: reason}`；
     - 勾选的规设 `paused: true`，并各自追加 `{date, kind: '暂停', source: '录入', result: '上游【标题】降到 X%'}`。
   - 建议把「应用调整」写成 `model.js` 里的纯函数 `applyAdjust(store, adjust)`，返回要保存的节点列表，并配单元测试。
   - 完成后 toast「已确认 N 条：调整 a、新增 b」，跳回来源页或结构页。
5. 底部 sticky 按钮文案：「确认入库（调整 a · 新增 b · 关系 c）」。

## 验收

- `npm run test:unit`：`applyAdjust` 的单元测试，覆盖改把握、写 checks、暂停规、驳回不改。
- `npm run test:e2e`（接在 T03 的录入步骤后）：
  - `check`：页面有 2 张调整卡、2 张新节点卡；n-0006 的卡显示 `50% → 65%`。
  - 把 n-0007 那张的滑块拖到 35，确认；n-0006 那张左滑驳回；两张新节点都确认。
  - 写入后：
    - `check`：n-0007 文件里 `confidence: 35`，并有一条 `kind: 调整` 的 checks；n-0006 的把握仍是 50。
    - `check`：新规的文件里有 `under: n-0007` 和 `triggers`。
    - `check`：新论的文件里有 `under: n-0100` 和两条 sentinels。
  - 截图 `04-cands`（批阅台）。
