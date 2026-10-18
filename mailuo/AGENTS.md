# AGENTS.md：脉络（mailuo）开发约定

给 Codex / Claude 等编程助手的工作说明。**先读这份，再读 `docs/v2/PLAN.md`，再读你要做的任务卡 `docs/v2/tasks/T0x-*.md`。**

## 这是什么

「脉络」是一个手机优先的个人知识结构 App（Android 用 Capacitor 包一层 WebView）。V2 的核心是「问 → 论 → 规」三级链条：

- 前台：只有三种节点卡片。
- 后台：每张卡片里有 AI 起草的推演槽位，即支柱、盲区、验证哨、触发场景、代价。

每个节点是一个 Markdown 文件，带 YAML 头，整个文件夹就是一个 Obsidian 仓库。

## 目录

```
mailuo/
├── www/                    ← 整个 App（没有构建步骤，原生 ES modules，直接被 Capacitor 打包）
│   ├── index.html
│   ├── css/app.css         ← 唯一样式表；颜色只用 :root 里的变量（含深色模式）
│   ├── js/app.js           ← 路由 + 全部页面（视图函数 viewXxx）+ 弹层；UI 都在这里
│   ├── js/model.js         ← 数据结构、YAML 读写、Store（节点索引、挂载链条、写文件）
│   ├── js/collide.js       ← 录入 V2「碰撞引擎」：提示词、节点库摘要、模型输出规范化（纯逻辑）
│   ├── js/features.js      ← 体检 / 溯源 / 温故 / 本周回顾（纯逻辑）
│   ├── js/pack.js          ← 提问时的上下文打包（token 预算）
│   ├── js/llm.js           ← 模型接口（OpenAI 兼容 / Anthropic）、提示词 A/B、回答解析、用量账本
│   ├── js/vault.js         ← 存储后端（IndexedDB / 本地文件夹 / 安卓文档目录）、备份
│   ├── js/ui.js            ← h() 建元素、图标、弹层 sheet、toast、把握表盘 dial、swipeable
│   ├── js/seed.js          ← 示例体系「思维问题」（V2 链条，界面开发时的样例数据）
│   └── vendor/             ← js-yaml、fflate、capacitor.js（不要改）
├── dev/
│   ├── server.mjs          ← 开发服务器 + 模拟模型接口（/mock/v1），含碰撞引擎的模拟输出 COLLIDE
│   ├── e2e.mjs             ← 端到端验收（Playwright，手机尺寸 Chromium，一个文件按流程往下跑）
│   └── unit/*.test.mjs     ← 纯逻辑单元测试（node --test，不需要浏览器）
├── docs/v2/                ← V2 方案与任务卡
├── android/                ← Capacitor 安卓工程（一般不用改）
└── AGENTS.md
```

## 命令（都在 `mailuo/` 目录下执行）

```bash
npm ci                                   # 装依赖（Capacitor 等）
npx playwright install --with-deps chromium   # e2e 需要；装不了时至少跑单元测试
npm run test:unit                        # 纯逻辑单元测试，1 秒内跑完，改 model/collide/features 后先跑它
npm run test:e2e                         # 端到端验收（约 30 秒），最后输出 ALL PASSED
npm test                                 # 两个都跑
npm run dev                              # http://localhost:5178 ，模型地址填 http://localhost:5178/mock/v1，密钥随便填
SHOTS=1 npm run test:e2e                 # 顺便把截图写到 docs/screenshots/
```

**提交前必须：`npm run test:unit` 全过，`npm run test:e2e` 输出 `ALL PASSED`。**

## 硬性约束

1. **不引入构建步骤、框架或新的 npm 依赖**。没有 React，没有 TypeScript，也没有打包器。新代码沿用 `h()` + 视图函数的写法。
2. **纯逻辑和界面分开**：
   - 判断、计算、规范化写进 `model.js` / `collide.js` / `features.js` / `pack.js`，并在 `dev/unit/` 加单元测试。
   - `app.js` 只负责界面和交互。
3. **数据格式要兼容**：
   - YAML 字段名用英文，顺序由 `model.js` 的 `FIELD_ORDER` 决定。新字段要加进 `FIELD_ORDER`，并在 `parseNode` 里规范化。
   - v1 文件（`类型: 论断/概念…`、`parent: 层级`、`verify`）必须仍能读入。
   - 不要删除用户文件里你不认识的字段（它们在 `n.extra` 里，会原样写回）。
4. **新建节点一律用 `blankNode({...})`**。`store.saveNode()` 会用 `ensureShape()` 补齐字段，但别依赖它。
5. **挂载只走 `store.canMount()` / `store.mount()`**。挂载规则见 `MOUNT_RULES`：论挂在问/论下，规挂在论/问下，问不挂或挂在问下，不能成环。
6. **界面文字用简体中文**：
   - 简洁，不用感叹号。
   - 专有名词沿用：问 / 论 / 规、把握、验证哨、反方盲区、破局支柱、触发场景、破戒代价、候选、体检、温故、录入。
7. **样式**：
   - 只用 `css/app.css` 里的变量和已有组件：`.card`、`.node-row`、`.pill`、`.kw`、`.btn`、`.seg`、`.sheet`、`dial()`、`swipeable()`、`manualPanel()`。
   - 新样式追加到 `app.css` 末尾，深浅两种模式都要能看。
   - 手机宽度 390px 下不能横向滚动。
8. **只改 `mailuo/` 里的文件**。CI 需要时可以改 `.github/workflows/mailuo-android.yml`。不要提交 `node_modules/`、`android/app/build/`、`*.apk`。
9. **一个任务卡对应一个分支、一个 PR**。PR 标题格式：`[脉络 V2][T0x] 简述`。PR 描述列出改动和验收结果，即 `test:unit` / `test:e2e` 的输出尾行。

## 测试写法

- **单元测试**：`dev/unit/helpers.mjs` 里的 `makeStore([...节点覆盖字段])` 可以建一个内存仓库，例子见 `model.test.mjs` 和 `collide.test.mjs`。
- **e2e**：
  - 在 `dev/e2e.mjs` 里按流程往下追加步骤，断言统一用 `check(条件, '中文说明')`。
  - 需要时用 `shot('NN-名字')` 截图。
  - 等待条件时，**等文件内容**（`vault.read(path)`），不要等内存状态：IndexedDB 写入是异步的，等内存状态在 CI 上会偶发失败。
  - 模拟模型的返回在 `dev/server.mjs`：按系统提示词里的关键字区分，比如「结构拆分器」「碰撞引擎」「激活词生成器」。
- e2e 里节点 id 是固定的。示例体系的 id 见 `www/js/seed.js` 顶部注释。

## 已知的坑

- **YAML 一律用 `CORE_SCHEMA` 解析**（`splitFrontmatter` 已处理）。否则 `2026-10-10` 会变成 Date 对象，写回后日期就坏了。
- **`route()` 会重绘整页并回到顶部**。要保持滚动位置，用 `route(true)`。
- `hashchange` 时会自动关掉弹层（`closeSheets`）。
- 弹层里的异步操作完成后，先 `close()` 再 `route()`。
- `store.load()` 是先读完再整体替换，期间界面不会空掉。所以别在 load 过程中改 `store.nodes`。
