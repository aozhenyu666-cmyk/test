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
