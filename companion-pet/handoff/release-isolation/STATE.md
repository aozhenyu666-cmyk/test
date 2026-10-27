# 发布隔离：正式 APK 链接不再被旧分支覆盖

## 事故

`tongzhuo-latest` 先后被 `deepseek/moling-c3-proactivity`（0.5.4 / 22）和 `deepseek/c3-focus-guard`（0.5.5 / 23）覆盖，比手机上的 0.5.8 / 26 低，装不上。
根因：每个分支用的是**自己分支里的**工作流文件；旧分支的旧工作流在任何推送后都会写 `tongzhuo-latest`。
PR #35 只改主线的工作流，**挡不住旧分支**（旧分支推送时跑的还是旧文件），所以单独合并它不够。

## 设计（本分支 integration/tongzhuo-presence-focus-v1 已实现，等负责人合入主线）

1. 构建工作流 `companion-pet-android.yml`：**永远不写正式链接**。推送只发到每个分支自己的预览 tag `tongzhuo-preview-<分支名>`（互不覆盖），拉取请求不发布；脚本里显式拒绝写 `tongzhuo-latest` / `tongzhuo-stable`。
2. 正式发布工作流 `companion-pet-release.yml`：只能手动触发（workflow_dispatch），只在 `ccr-2899d69f-xlzjh6` 上运行（job 级 if），绑定环境 `official-release`；写入**新的** tag `tongzhuo-stable`，并检查 versionCode 必须大于已发布的版本，否则失败。
   旧工作流只认识 `tongzhuo-latest`，写不到 `tongzhuo-stable` —— 这是真正挡住旧分支的关键。
3. 建议（需要仓库管理员在网页上操作，代码做不到）：Settings → Environments → `official-release` → Required reviewers 选负责人，正式发布前需要点批准。

## 迁移步骤（负责人决定后执行）

1. 把本分支里的两个工作流文件合入主线（可单独挑这两个文件，不必带 C3 代码）。
2. 在 Actions 里手动运行 “Companion Pet official release”（ref 选主线）→ 生成 `tongzhuo-stable`。
3. 新的正式直链：`https://github.com/aozhenyu666-cmyk/test/releases/download/tongzhuo-stable/tongzhuo.apk`
4. `tongzhuo-latest` 视为废弃（旧分支可能还会写它，不要再用它安装）。可以在它的说明里写“已废弃”。
5. PR #35 建议关闭，以本设计替代（它只解决主线上的一半问题）。

## 回滚

还原这两个工作流文件到合入前的版本即可：构建会恢复成每次推送写 `tongzhuo-latest` 的旧行为；`tongzhuo-stable` 留着不影响。

## 本轮做了 / 没做

- 没有运行任何会写 `tongzhuo-latest` 的工作流；没有合并主线；没有改 PR #35。
- 当前 `tongzhuo-latest` 仍是 DeepSeek 的 0.5.5 / 23：**不要用它安装，也不要为了装它卸载 0.5.8**。
- 本分支的预览：`tongzhuo-preview-integration-tongzhuo-presence-focus-v1`。

## 后续（2026-10-27）：正式发布一次都没成功过，真正原因是工作流根本注册不上

上面第 12 条的设计随 `deepseek/r21-proactive` 进了主线，但**正式发布一次都没成功过**。
`gh release view tongzhuo-stable` 至今返回 `release not found`，`tongzhuo-latest` 仍停在 2026-10-25 的 0.5.5 / 23，与此吻合。

实测定位到三层原因，前两层是代码缺陷，第三层是仓库结构问题：

### 1. 门禁分支上不存在该文件（设计写错了）

第 12 条写「只在 `ccr-2899d69f-xlzjh6` 上运行」，但那条分支上**没有** `companion-pet-release.yml`。
GitHub 只能运行该 ref 上存在的工作流，所以这个入口在主线从未出现。

### 2. versionCode 校验是 fail-open 的

原写法 `gh release view ... 2>/dev/null | grep ... || true`：一旦查询本身失败（网络抖动、API 限流），
`OLD` 变空 → `[ -n "${OLD}" ]` 为假 → 整个判断被跳过 → 低版本照样发出去。
这正是当初 0.5.5 覆盖 0.5.8 的同一条失效路径，只是触发条件更隐蔽。

### 3. 只有 workflow_dispatch 的工作流，从未注册过 → 手动触发直接 404

修完第 1 条后仍然触发不了。实测：

```
$ gh workflow run companion-pet-release.yml --ref fix/r21-release-chain
HTTP 404: workflow companion-pet-release.yml not found on the default branch
```

对照实验（同仓库、同一个非默认分支）：

| 工作流 | 默认分支 tip 上存在 | 能否 dispatch |
|---|---|---|
| `companion-pet-android.yml` | 否 | **能**（成功拿到 run id） |
| `companion-pet-release.yml` | 否 | **不能**（404） |

两者都不在默认分支，差别在于前者历史上有 `push`/`pull_request` 触发、曾出现在默认分支历史里
（`f6156af`），因此被 GitHub 注册过；后者只有 `workflow_dispatch`，
**从未出现在默认分支，所以 GitHub 从没把它注册成可触发的工作流**。

本仓库默认分支是 SP 插件分支 `claude/super-productivity-mcp-mobile-xe4499`，
把它改成宠物主线属于管理员级仓库设置，不适合由代码修复代替。

### 本轮修复（`fix/r21-release-chain`）

| 项 | 改法 |
|---|---|
| 触发方式 | 增加 `push: tags: tongzhuo-release-*`。推 tag 就能让 GitHub 注册并运行本工作流，不再依赖改默认分支 |
| 门禁来源 | `if` 白名单：宠物主线 + 旧发布分支 + `tongzhuo-release-*` tag |
| tag 防滥用 | tag 触发时用 compare API 校验 tag 提交属于主线历史（`ahead`/`identical` 放行，`behind`/`diverged` 拒绝），防止从旧分支打 tag 绕过白名单 |
| 版本校验 | 改为 fail-closed：release 存在但解析不出版本号 → 拒绝；查询失败 → 拒绝，不盲发 |
| 产物校验 | 删旧 release **之前**先确认 APK 存在且非空，避免把下载链接弄成 404 才失败 |

### 实测验证（不是推断）

1. **tag 触发确实能让 GitHub 注册该工作流**：推一个探测 tag 后，
   `actions/workflows` 列表出现 `Companion Pet official release | id=398320315`（此前完全没有）。
2. **来源校验确实拦得住**：探测 tag 打在修复分支（非主线）上，运行后
   job 在 `Verify tag comes from an allowed branch` 一步 `failure`，
   后续构建与发布步骤全部 `skipped`，日志明确写出
   `Refusing: tag ... is not on any allowed branch`，且 `tongzhuo-stable` 没有被创建。
   探测 tag 事后已删除。
3. **compare 语义对真实仓库实测**：主线 HEAD→`identical`、主线上的旧提交→`ahead`、
   旧分支 0.5.5→`diverged`，与判定一致。
4. `tools/release_gate_test.ps1` 扩到 **13 项**，覆盖版本门禁与 tag 来源校验，
   并保留旧逻辑漏洞的复现用例（13 passed / 0 failed）。

**仍未做**：没有真的把 vc39 发成正式版（要你决定）；`official-release` 环境仍需在仓库网页设置 Required reviewers。
正式发布用法已改为：`git tag tongzhuo-release-39 && git push origin tongzhuo-release-39`（tag 必须打在主线提交上）。
