# 交给 Codex：连接仓库 + 执行指令

本文件给你（人）看，告诉你怎么把 V2 的任务交给 Codex。Codex 自己要读的是 `mailuo/AGENTS.md` 和 `mailuo/docs/v2/`。

- **仓库**：`aozhenyu666-cmyk/test`（公开）
- **基础分支**：`ccr-153bfa69-lm85up`。它已经包含 T00：V2 数据结构、碰撞引擎逻辑、单元测试和全部任务卡。
- **下载链接**：<https://github.com/aozhenyu666-cmyk/test/releases/download/mailuo-latest/mailuo.apk>。只有集成分支（`main` / `ccr-153bfa69-lm85up`）会更新这个链接；Codex 的任务分支不会覆盖它。

## 一、连接仓库

### 方式 A：Codex 云端（网页 / 手机 App 里的 Codex）

1. 在 Codex 设置里连接 GitHub，授权仓库 `aozhenyu666-cmyk/test`。
2. 新建一个环境（Environment），仓库选它，**默认分支填 `ccr-153bfa69-lm85up`**。
3. 环境的 **Setup script** 填：
   ```bash
   cd mailuo
   npm ci
   npx playwright install --with-deps chromium
   ```
4. 打开环境的联网权限，至少在 setup 阶段打开，因为要下载 npm 包和 Chromium。
5. 每个任务新开一个 Codex 任务，粘贴下面第三部分对应的指令。完成后让 Codex 开 PR，**目标分支选 `ccr-153bfa69-lm85up`**。

### 方式 B：Codex CLI（电脑上）

```bash
git clone https://github.com/aozhenyu666-cmyk/test.git mailuo-repo
cd mailuo-repo
git checkout ccr-153bfa69-lm85up
cd mailuo && npm ci && npx playwright install chromium && npm test && cd ..
# 看到「# fail 0」和「ALL PASSED」后，在仓库根目录启动 Codex：
codex
```

每个任务开一个新分支：`git checkout -b codex/mailuo-v2-T01 ccr-153bfa69-lm85up`。做完后 `git push -u origin codex/mailuo-v2-T01`，然后在 GitHub 上开 PR 到 `ccr-153bfa69-lm85up`。

## 二、总指令（每个任务开头都先发这一段）

```
你在仓库 aozhenyu666-cmyk/test 里工作，基础分支是 ccr-153bfa69-lm85up。
项目是 mailuo/ 目录下的「脉络」App：没有构建步骤的原生 ES modules + Capacitor 安卓壳。

开始前按顺序读：
1. mailuo/AGENTS.md（开发约定、命令、硬性约束、测试写法）
2. mailuo/docs/v2/PLAN.md（V2 方案、数据结构、四个模块怎么变）
3. 本次任务卡 mailuo/docs/v2/tasks/<任务卡文件名>

规则：
- 只做任务卡里写的内容，「不要做」里列的不碰；任务卡没说清楚的地方，按 PLAN.md 的原则自行决定，并在 PR 描述里写明你的决定。
- 纯逻辑写进 model.js / collide.js / features.js / pack.js 并在 mailuo/dev/unit/ 加单元测试；界面改 app.js / app.css。
- 不加新依赖、不引入框架或构建工具；界面文字用简体中文。
- 完成后在 mailuo/ 下运行 npm run test:unit 和 npm run test:e2e，两个都必须通过（e2e 最后一行是 ALL PASSED）；
  再运行 SHOTS=1 npm run test:e2e 更新截图。
- 新开分支 codex/mailuo-v2-<任务编号>，提交信息用中文或英文都可以，PR 标题「[脉络 V2][<任务编号>] 简述」，
  PR 目标分支 ccr-153bfa69-lm85up，PR 描述列出：改了什么、做了哪些自行决定、两个测试命令输出的最后几行、新截图。
```

## 三、各任务指令（接在总指令后面发）

按顺序做，前一个 PR 合并后再开下一个。T05 / T06 / T07 互相独立，赶时间可以并行。

| 顺序 | 发给 Codex 的话 |
|---|---|
| 1 | `本次任务卡：mailuo/docs/v2/tasks/T01-chain-view.md（结构页改成「问 → 论 → 规」链条视图）。` |
| 2 | `本次任务卡：mailuo/docs/v2/tasks/T02-node-card.md（节点详情页：推演槽位、按类型编辑、挂载选择）。` |
| 3 | `本次任务卡：mailuo/docs/v2/tasks/T03-collide-capture.md（录入接入碰撞引擎）。逻辑已在 mailuo/www/js/collide.js，只做接线和界面。` |
| 4 | `本次任务卡：mailuo/docs/v2/tasks/T04-review-desk.md（候选页改成参谋批阅台）。` |
| 5 | `本次任务卡：mailuo/docs/v2/tasks/T05-health.md（体检 V2：五类逻辑与实操漏洞）。` |
| 6 | `本次任务卡：mailuo/docs/v2/tasks/T06-drill.md（温故 V2：触发场景演练 + 反馈闭环）。` |
| 7 | `本次任务卡：mailuo/docs/v2/tasks/T07-ask-pack.md（提问打包和提示词 B 适配 V2）。` |
| 8 | `本次任务卡：mailuo/docs/v2/tasks/T08-wrapup.md（收尾：清理、文档、版本 0.2.0）。` |
| — | T09（与一手 / 总控联动）先不做，等 V2 在手机上用过一周再说。 |

## 四、每个 PR 合并前怎么验

1. 看 PR 上的 GitHub Actions：`e2e` 和 `apk` 两个 job 都要是绿的。
2. 想让我（Claude）审一下：把 PR 链接发给我，说「审一下这个 PR」。我会按任务卡逐条对照，并跑测试。
3. 合并到 `ccr-153bfa69-lm85up` 后，下载链接会在几分钟内自动更新成新版本，手机上直接覆盖安装就能试。

## 五、出问题时

- **Codex 说装不了 Chromium**：让它先只跑 `npm run test:unit`，并在 PR 里注明 e2e 没跑。CI 上的 e2e 会兜底。
- **两个并行的 PR 冲突**：先合并一个，再让另一个 Codex 任务 `git merge ccr-153bfa69-lm85up` 解决冲突后重新跑测试。
- **e2e 在 CI 上偶发失败**：多半是等待条件写成了等内存状态。按 AGENTS.md「测试写法」改成等文件内容。
