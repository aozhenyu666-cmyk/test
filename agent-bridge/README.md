# Agent Bridge · Windows 桌面多 Agent 协作平台

[下载 Windows 安装程序](https://github.com/aozhenyu666-cmyk/test/releases/download/agent-bridge-v0.2.0/AgentBridge-Setup-0.2.0-x64.exe) · [下载便携版](https://github.com/aozhenyu666-cmyk/test/releases/download/agent-bridge-v0.2.0/AgentBridge-Windows-x64.zip) · [版本说明](https://github.com/aozhenyu666-cmyk/test/releases/tag/agent-bridge-v0.2.0) · [验证记录](VALIDATION.md)

将 Codex CLI、Claude Code、DeepSeek Harness 等本机工具接入一个桌面工作台，自动派发任务、传递结果、继续讨论和保存运行记录。项目位于仓库独立的 `agent-bridge/` 目录。

## 安装与首次使用

适用 **Windows 10 1809+ / Windows 11，x64**。

1. 下载 `AgentBridge-Setup-0.2.0-x64.exe` 并运行，按提示安装。也可以下载便携 ZIP，完整解压后双击 `AgentBridge/AgentBridge.exe`。
2. **无需安装 Python**。应用内置运行时、终端组件和管理界面，不需要前端 CDN。
3. 先在“接入指南”添加演示伙伴，运行一次协作，确认派发、结果和日志。
4. 在“Agent 管理”点“检测本机工具”，或手动添加工具；选择你的项目目录，点“检查命令”。
5. Codex、Claude Code 和 Harness 本身仍须安装并完成登录。只安装某个工具的桌面界面，并不一定意味着命令行命令也已安装。

应用用 Microsoft Edge WebView2 显示独立窗口。大多数 Windows 11 已有此组件；若提示缺少运行时，请安装微软的 [WebView2 Evergreen Runtime](https://developer.microsoft.com/microsoft-edge/webview2/)。这是未签名的独立项目，Windows 可能显示来源或 SmartScreen 提示，请根据下载地址和 SHA-256 核对文件。

安装按当前用户进行，无需管理员权限。更新或卸载应用不删除 `%APPDATA%\AgentBridge` 中的数据。**便携 ZIP 的整个文件夹都要保留**，不要只复制主 EXE。

## 已实现的功能

| 功能 | 行为 |
| --- | --- |
| 伙伴协作 | 2–8 位伙伴按顺序接力，最多 24 轮，自动传递结果 |
| 主管派发 | 主管安排 → 1–7 位执行者依次处理 → 主管验收 |
| 单 Agent 任务 | 自动输入任务、收集结果并保存日志 |
| 持续对话 | 按对话保存任务与结果，可继续此前讨论 |
| 失败续跑 | 沿用已成功轮次，只运行剩余部分；也可从头重新运行 |
| 任务控制 | 按 Agent 串行排队，不同 Agent 并行；超时与取消 |
| Agent 信箱 | 留消息，或勾选“唤醒处理”创建任务 |
| 终端 | ConPTY + xterm.js，键盘、方向键、Ctrl+C、中文、多行粘贴、缩放和历史日志 |
| 接入检测 | 检查命令路径、工作目录和 `--version` 输出 |
| 导出与备份 | 导出任务 Markdown、Agent 配置或全部记录；合并导入 Agent 配置 |
| MCP | Agent 可以查询伙伴、发消息、派任务、读结果、查询与续接对话、续跑任务 |
| 桌面体验 | 原生窗口、目录选择、保存对话框、独立数据目录、安装程序和便携版 |

关闭应用窗口会停止其管理的进程。重启后未完成任务显示为“已中断”，可以手动续跑；不会未经选择自动重放任务。续跑不撤销此前的文件修改或外部操作。

## 接入工具

### Codex CLI

模板自动任务命令：

```json
["codex", "exec", "--sandbox", "workspace-write", "--skip-git-repo-check", "--output-last-message", "{output}", "-"]
```

提示词经 stdin 提交，`{output}` 是平台为本轮分配的最终结果文件。默认允许工作目录内写入，平台不添加跳过审批或关闭沙箱的参数。版本参数或权限不匹配时请查看日志，并以本机 `codex exec --help` 为准。

交互终端命令：`["codex"]`。

### Claude Code

自动任务模板：

```json
["claude", "-p", "--output-format", "text"]
```

提示词经 stdin 提交，以进程退出判断完成。工具审批需求可能使非交互任务失败或只返回说明；平台不会自行绕过。交互命令为 `["claude"]`。

### DeepSeek Harness / 自定义工具

不同 Harness 的启动方式不统一。选择自定义模板，填写你实际安装的命令和参数；平台不会假定存在名为 `deepseek` 的通用程序。

自动任务命令必须读取 stdin，输出最终文本或写入 `{output}`，完成后退出。例如你自己写的适配脚本可配置成：

```json
["python", "C:\\tools\\my_harness_adapter.py"]
```

该示例中的外部脚本仍需要你自己的 Python；应用内置运行时只供应用使用，不会替代自定义命令的安装依赖。若工具只接受命令行提示词参数，需用包装脚本读取 stdin 再调用其接口。

只有交互界面的程序可用“交互终端”。它不直接参与自动多轮协作，因为平台不能可靠猜测任意 TUI 何时完成。需要接入自动调度时，应提供明确的完成/结果信号适配器。

## 对话与任务

“继续对话”会把最近 6 个已完成任务的原始请求及结果传给下一次任务。每轮仍启动独立的工具进程；平台没有读取模型内部思考，也不恢复 Codex/Claude 自身原来的内部会话。

同一对话在上一次任务结束后才能继续。同一 Agent 串行执行；不同 Agent 可并行。主管模式中的执行者当前依次处理，末轮由主管验收，不是动态组织树。Agent 可通过 MCP 再派发任务；嵌套任务需要由使用者控制，避免互相等待或无界递归。

Agent 各自访问其工作目录。不同 Agent 同时修改共享目录仍可能冲突，建议使用独立 Git worktree。最多排队 32 个任务，单轮超时 10–7200 秒。单轮日志保留末尾 2 MB，结果文件上限 2 MB，协作上下文保留最近 120000 字；对话历史另保留最近 120000 字。

## 交互终端

应用新建并管理终端，不接管已经打开的 Windows Terminal、桌面工具窗口或网页。选择 Agent 后可修改交互启动命令。

- 点击终端区域即可直接键入，支持方向键、Ctrl+C 和终端粘贴。
- 下方自动输入框须先确认 Agent 在等待提示词，再点“标记为可输入”。发送后重新锁定，避免文本进入错误交互阶段。
- 多行自动输入使用括号粘贴序列，目标工具须支持。若不支持，请直接在终端键入或使用自动任务接口。
- 日志定期存盘；历史终端可回看，但历史进程不会在重启后继续运行。最多同时 8 个终端。

## MCP 配置

先启动应用，到“设置与备份”复制本机生成的 **JSON 或 TOML** 配置。这些配置已包含正确的 EXE 路径、参数和连接文件路径，分别合并到 Claude/通用 MCP 客户端或 Codex 配置中，不要覆盖原有内容。

桌面版使用 `AgentBridge-CLI.exe --mcp`，不依赖外部 Python。

工具包括 `list_agents`、`send_message`、`read_inbox`、`create_task`、`get_task`、`cancel_task`、`list_sessions`、`get_session`、`retry_task`。`send_message` 的 `trigger=true` 可唤醒目标 Agent；`create_task` 的 `session_id` 可续接已有对话。

不要在某个 Agent 自己的执行轮次里向自己创建任务并同步等待。平台会串行化该 Agent，新任务只能等待当前轮次结束。

## 数据与备份

数据目录为 `%APPDATA%\AgentBridge`，可在“设置与备份”打开。SQLite 数据库保存配置、任务、消息、对话及终端历史。运行中的 `connection.json` 含本地访问令牌，请勿上传；备份导出不包含连接令牌。

“导出全部记录”可能包含项目文本、Agent 输出和敏感信息，请自行保管。导入功能只合并 Agent 配置，不恢复或执行历史任务。不同电脑导入时，工作目录必须存在，否则整体导入会失败并保留原有配置。

旧版源码用户可在旧版 Agent 管理配置基础上重新填写接入信息，或从旧数据中的 Agent 配置整理 JSON 后导入；桌面版不会擅自移动旧版数据库。

服务仅监听动态的本机环回端口，限制 Host/Origin 并要求操作令牌。所有本机 MCP 客户端共享平台权限，Agent 名称不是身份隔离机制。当前适合个人电脑上的可信工具，尚不提供多用户托管、跨电脑/云端连接器、自动升级或代码签名。

## 开发与构建

源码运行需要 Python 3.10+。Windows 安装依赖后：

```powershell
python -m pip install -r requirements-windows.txt
python app_entry.py
python -m unittest discover -s tests -v
```

不带桌面窗口的服务：`python app_entry.py --serve --no-browser`。浏览器回归脚本 `python tests/browser_smoke.py` 需要额外的 Playwright + Chromium。

Windows 打包需要 PyInstaller、pywebview、pywinpty 和 Inno Setup 6：

```powershell
python -m pip install -r requirements-windows.txt pyinstaller==6.16.0
python packaging/build_windows.py
```

生成 `dist/AgentBridge-Setup-0.2.0-x64.exe` 和 `dist/AgentBridge-Windows-x64.zip`。GitHub Actions 先运行 Windows 测试，再检查真实 EXE 和原生窗口，之后生成安装包；构建失败不会发布下载版本。

终端前端使用 xterm.js 5.5.0 和 addon-fit 0.10.0，许可证保留在 `web/vendor/`。
