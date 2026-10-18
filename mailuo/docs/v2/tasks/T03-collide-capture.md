# T03 录入：接入「碰撞引擎」

**依赖**：T02　**改动范围**：`www/js/app.js`（`viewCapture`、`startCands`）、`dev/e2e.mjs`；逻辑已在 `www/js/collide.js`，原则上不改它（要改就同步改单元测试）

## 目标

贴进来的材料先撞旧体系，再考虑新建：

- 命中验证哨的，生成把握度调整提案；
- 能补断头链的，挂到已有节点下面；
- 只有全新的内容才新建，而且槽位草稿都填好。

## 要做的

1. **调用流程**（改 `viewCapture` 里的 `btn` 和 `finish`）：
   ```js
   import { PROMPT_COLLIDE, libraryDigest, collideUserMessage, normalizeCollide } from './collide.js';
   const digest = libraryDigest(store, 材料, { system });
   const res = await callModel('split', PROMPT_COLLIDE, collideUserMessage(digest, 材料), { maxTokens: 3500 });
   const data = normalizeCollide(extractJson(res.text), store);
   startCands({ ...data, system, source });
   ```
   - 结果三类都为空时，提示「材料没有撞到任何东西，也没有新内容」，不跳转。
2. **发送前提示**：在「拆分」按钮上方加一行小字，输入时实时更新：「将附上节点库摘要：N 个节点 · 约 X token（库里共 M 个）」。数值来自 `libraryDigest`，输入框 input 事件里 300ms 防抖计算。
3. **按钮文案**：主按钮「撞击体系，生成候选」；页面副标题改为「新材料先撞击已有判断，再补链，最后才新建。所有改动都要你在候选里确认。」。
4. **手动模式**（`manualPanel`）：复制内容改为 `PROMPT_COLLIDE + '\n\n' + collideUserMessage(digest, 材料)`，粘贴回来的 JSON 走同一个 `normalizeCollide`。
5. **startCands**：接收 `{adjust, nodes, links}`。
   - `adjust` 每项加 `state: 'pending'` 和 `to_final: to`。
   - `nodes` 沿用现有字段，再加 `dupeId`：优先用 `dupe_of`，其次用 `findDuplicate` 的结果。
   - 草稿存进 `localStorage` 的 `mailuo.draft`，逻辑和现在一样。
6. 旧的 `PROMPT_SPLIT` / `normalizeSplit` 先保留不删（T08 清理），但录入页不再调用。

## 验收

- `dev/server.mjs` 里已有碰撞引擎的模拟输出 `COLLIDE`：2 条调整、1 个挂在 n-0007 下的规、1 个挂在 n-0100 下的论、1 条反例关系。
- `npm run test:e2e`：
  - 改写「录入与拆分」那段：载入示例体系后去 `#/capture`，贴一段材料，点主按钮。
    - `check`：模拟接口收到的系统提示词包含「碰撞引擎」；用户消息包含「节点库摘要」和 `n-0006`。
    - `check`：30 秒内跳到 `#/cands`。
    - `check`：`session.cands` 草稿里有 2 条 adjust、2 个 nodes（在 `localStorage` 的 `mailuo.draft` 里读）。
  - 手动模式：复制内容包含「碰撞引擎」；粘贴 `JSON.stringify(COLLIDE)` 也能进候选。
  - 页面上显示「将附上节点库摘要」的提示文字。
  - 原先依赖旧拆分结果（3 张卡、合并、9/11 个节点等）的检查，按新流程改写或删除。候选页的具体断言放到 T04。
