# T06 温故 V2：激活词实战演练 + 反馈闭环

**依赖**：T02（可与 T03 / T04 并行）　**改动范围**：`www/js/features.js`（`dueReviews`、`gradeReview`，新增 `FEEDBACK`、`applyFeedback`）、`www/js/app.js`（`viewReview`、今日页温故磁贴）、`dev/unit/`、`dev/e2e.mjs`

## 逻辑（纯函数 + 单元测试）

```js
export const FEEDBACK = {
  有效: { rule: +3, parent: +2 },
  卡壳: { rule: -5, parent: -3 },
  max: 95, min: 5,
};
// 返回需要保存的节点数组（规本身 + 最近的上级论，若有），不直接写文件
export function applyFeedback(store, ruleId, kind /* '有效' | '卡壳' */, { today, source = '温故', note = '' } = {}) { … }
```

- 规的 `confidence` 按 `FEEDBACK[kind].rule` 增减，最近的上级论按 `parent` 增减，都夹在 `[min, max]` 之间。把握为 null 的，按 50 起算。
- 两个节点各追加一条 `checks`：
  ```
  {date, kind, source, delta, confidence: 新值, result: note || ('温故：' + kind)}
  ```
  上级论那条的 `result` 写「下游【规标题】实操有效」或「…实操卡壳」。
- `dueReviews(store, limit, { today })`：
  - 先放未暂停的规，按 `store.review[id].due` 升序，没复习过的在前；
  - 再放有验证哨的论；
  - 最后放问。问也可以完全不放，你决定并在 PR 里说明。

## 界面（`viewReview`）

- **规卡**
  - 正面大字：「演练：〈触发场景〉」。有多个触发场景时，每次随机取一个。下一行小字「此时你的【论】和【规】是什么？」。没有触发场景时退回显示标题 + 激活词。
  - 背面：
    - 上级论：类型字 + 标题 + 把握 + 一句结论；
    - 规：唯一动作；
    - 破戒代价：用朱砂色小字。
  - 底部三个按钮：「实操有效」（绿）、「实操卡壳」（红）、「只是回想」（灰）。
    - 前两个调用 `applyFeedback`，保存返回的节点，并照常安排下次复习：有效按「记得」，卡壳按「忘了」。
    - 「只是回想」只排复习，按「模糊」。
  - toast 显示变化，例如「规 60%→63%，上游论 50%→52%」。
- **论卡**：保持现在的「忘了 / 模糊 / 记得」，背面加验证哨列表。
- 今日页「温故」磁贴的小字改为「N 条规待演练」。

## 验收

- `npm run test:unit`：新建 `dev/unit/feedback.test.mjs`，覆盖以下情况：
  - 有效 / 卡壳的增减；
  - 上下限；
  - 没有上级论的规；
  - checks 内容；
  - `dueReviews` 的排序。
- `npm run test:e2e`：
  - 载入示例后打开 `#/review`。
  - `check`：第一张卡正面以「演练：」开头，内容是 n-0101 的某个触发场景。
  - 翻面后点「实操有效」。
    - `check`：n-0101 文件里 `confidence: 63`；n-0006 文件里 `confidence: 52`。
    - `check`：两个文件各有一条 `kind: 有效` 的 checks。
  - 截图 `12-review`（正面）、`12b-review-back`（背面）。
