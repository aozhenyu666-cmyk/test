# AGENTS.md

这个仓库里有好几个项目（总控 `zongkong/`、墨灵 `moling/`、一手 `yishou/` 等）。

- **做墨灵（`moling/`）或总控里接墨灵的部分（`zongkong/.../core/moling/`、`zongkong/.../app/link/Moling*`）之前，先读 `moling/CODEX.md`**，再读 `partner-blueprint/CONTEXT.md`。
- 两个 `core/` 目录（`moling/app/src/main/java/com/moling/core/`、`zongkong/app/src/main/java/com/zongkong/core/`）是规则所在，界面任务不要改它们。
- 纯逻辑测试：`tools/core-tests/` 里 `gradle test`。Android 编译以 CI 为准。
- 改了 `zongkong/` 的推送会触发总控 CI 并覆盖手机上的总控下载链接：只从包含工作分支 `ccr-b3c7616e-xlohkd` 最新提交的分支推。
