# T08 收尾：清理、文档、版本 0.2.0

**依赖**：T01–T07 全部合并　**改动范围**：`mailuo/` 下全部，以及 `.github/workflows/mailuo-android.yml`（如需）

## 要做的

1. **清理**：
   - 删除不再使用的代码：`PROMPT_SPLIT`、`splitUserMessage`、`normalizeSplit`（确认没有引用后）、层级相关 CSS（`.lv-*` 中不再用的）、`features.js` 里已被 V2 替代的函数。
   - 用 `grep` 确认没有死引用。
2. **迁移体验**：已有 v1 数据的用户第一次打开 V2 时，今日页显示一张一次性提示卡：「升级到 V2：发现 N 个未挂载的论 / 规，去结构页把它们拖到对应的问下面，或用『AI 整理挂载』」。
   - 「AI 整理挂载」用碰撞引擎实现：把未挂载节点的摘要当作材料，只要 `adjust` 为空、`nodes` 为空，只返回挂载建议。
   - 如果要做 AI 整理挂载，在 `collide.js` 里加 `PROMPT_MOUNT` 和 `normalizeMounts`，并配单元测试。
3. **文档**：
   - `README.md` 顶部加「V2.0：问 · 论 · 规」一节，内容取自 `docs/v2/PLAN.md` 的第 1–3 节。
   - 截图表格换成新截图（`SHOTS=1 npm run test:e2e` 生成后挑选）。
   - 在 README 和 `AGENTS.md` 里同步更新 e2e 检查项数量。
4. **版本**：
   - `package.json`、`www/js/app.js` 的 `VERSION`、`www/sw.js` 的缓存名都改为 `0.2.0`。
5. **CI**：合并到集成分支后，确认 GitHub Actions 三个 job（e2e / apk / release）都通过，并确认 <https://github.com/aozhenyu666-cmyk/test/releases/download/mailuo-latest/mailuo.apk> 是 0.2.0。

## 验收

- `npm test` 全绿。
- 390px 截图里没有横向滚动，深浅两种模式都检查过。
- PR 描述附上前后对比截图各 3 张：结构、候选、温故。
