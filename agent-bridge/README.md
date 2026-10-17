# Agent Bridge · Windows 本地多 Agent 协作台

[下载 Windows 源码运行包](https://github.com/aozhenyu666-cmyk/test/releases/download/agent-bridge-v0.1.0/AgentBridge-Windows-v0.1.0.zip) · [版本说明](https://github.com/aozhenyu666-cmyk/test/releases/tag/agent-bridge-v0.1.0) · [验证记录](VALIDATION.md)

仓库中的本项目位于独立的 `agent-bridge/` 目录。下载包只包含 Agent Bridge，解压后双击 `AgentBridge/start.cmd`，需要 Python 3.10+。

把本机命令行 Agent 接入同一个平台，自动提交任务、传递结果，并保留运行日志。

这是可运行的第一版本，不是对已有桌面窗口的键鼠遥控器。核心自动协作使用 CLI 进程；Windows 交互输入使用平台新建的 ConPTY 会话。

## Windows 启动

1. 安装 **Python 3.10 或更新版本**，安装时勾选 **Add Python to PATH**。建议 Windows 10 1809+ 或 Windows 11。
2. 将整个文件夹解压到普通可写目录，例如 `C:\AgentBridge`，不要直接在压缩包里运行。
3. 双击 `start.cmd`。首次会创建项目专用 `.venv` 并安装 `pywinpty`，需要联网；不会修改系统的 PowerShell 执行策略。
4. 浏览器自动打开 **http://127.0.0.1:8765**。保持启动窗口运行，按 Ctrl+C 停止服务和正在管理的任务。
5. 先到“接入指南”点“添加演示伙伴”，发起任务，确认自动派发、轮次传递和日志可用。演示不调用模型，也不真正执行任务。

也可先双击 `install.cmd` 安装依赖。端口冲突时，在 PowerShell 中执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1 -Port 8877
```

本地数据库在 `data/bridge.sqlite3`。停止服务后复制 `data` 文件夹即可备份。`data/connection.json` 是运行期间生成的本地 MCP 连接信息，包含令牌，请勿上传或分享。重新启动后令牌会变化。

## 接入真实 Agent

先在普通终端安装并登录对应工具，确认能独立运行。平台沿用你的 PATH、环境变量、登录信息和工具权限，不要求把模型密钥填进网页。

### Codex CLI

在“Agent 管理”添加 Agent，选 Codex 模板，填写 Windows 项目目录。默认自动任务命令：

```json
["codex", "exec", "--sandbox", "workspace-write", "--skip-git-repo-check", "--output-last-message", "{output}", "-"]
```

平台将提示词写入标准输入，读取 Codex 写入的最终结果文件。`workspace-write` 允许在工作目录内修改文件；平台不会添加跳过审批或关闭沙箱的参数。请根据本机版本的 `codex exec --help` 调整；遇到权限要求时，工具可能失败并将原因写入日志。

### Claude Code

模板命令：

```json
["claude", "-p", "--output-format", "text"]
```

平台把提示词写入 stdin，收集文本输出。请根据本机 `claude --help` 和权限设置调整。平台不自动添加 `--dangerously-skip-permissions`；非交互模式中的工具权限需求可能使任务失败或仅返回说明。

### DeepSeek Harness / 其他工具

不同 Harness 的命令和参数不统一，没有假定存在名为 `deepseek` 的程序。选择自定义模板，把占位命令换成你实际使用的命令数组。该命令必须：

- 从 **标准输入 stdin** 读取完整提示词；
- 将最终结果输出到 **stdout**，或用 `{output}` 指定的文件输出；
- 完成后退出，成功时退出码为 0。

例如下面只是你自己适配脚本的示例路径，不是某个 Harness 的真实命令：

```json
["python", "C:\\tools\\my_harness_adapter.py"]
```

如果真实命令只接受参数中的提示词，需要写一个很小的包装脚本：读取 stdin，再通过该工具的 SDK/命令行接口提交。平台不把模型文本拼入 shell 命令。

只有交互 TUI 的工具，请先走“交互终端”。需要在第一版自动协作中使用它，仍需补齐明确的结果/完成信号适配，不能仅靠“几秒没输出”判断完成。

## 协作模式

| 模式 | 执行顺序 |
| --- | --- |
| 单 Agent | 提交任务 → 执行 → 保存结果 |
| 伙伴协作 | A → B → A → B，最多 8 轮，每轮收到原始任务和已有结果 |
| 上下级派发 | 主管安排 → 执行者处理 → 主管验收，共 3 轮 |

这是有限轮次的调度。上下级属于平台给出的角色与固定流程，尚未实现动态组织树、任意递归拆任务或模型自行决定所有路由。

每轮启动独立进程，文本上下文由平台传递；不会自动恢复 CLI 原来的会话，也不会读取模型内部思考。Agent 能访问哪些文件，取决于它的工作目录和工具权限。

同一 Agent 的任务串行执行，不同 Agent 可并行。共享文件夹中的不同 Agent 仍可能同时修改文件，建议各自使用 Git worktree。队列最多 32 个任务，每轮超时可设为 10–7200 秒，默认 600 秒。单轮日志最多保留末尾 2 MB，结果文件上限 2 MB；轮次上下文保留最近 120000 字。限制超出时应缩小任务，而不是假定已完整传递。

任务失败会停止后续轮次，不会将错误输出当成功结果继续转发。支持取消等待和运行中的任务；Windows 通过 `taskkill /T /F` 停止本次 CLI 的进程树。停止无法撤销已经修改的文件或已提交的外部操作。服务重启后未完成任务标记为“已中断”，不会自动重放。

## Windows 自动输入

“交互终端”通过 pywinpty / ConPTY 创建独立会话，例如：

```json
["codex"]
```

也可以填写你的 Harness 交互启动命令数组。等待其进入提示输入框后，点击“标记为可输入”，输入一条单行消息并发送。平台会向终端写入文本，并可自动写入回车。每次发送后锁定输入，待你再次确认就绪。

这条路径：

- 创建新会话，不接管已打开的 Windows Terminal、桌面应用或远端网页；
- 输出为文本日志，没有完整终端屏幕仿真，TUI 的光标重绘内容可能较杂；
- 不猜测 Agent 完成状态；因此交互终端还不能直接参与自动多轮协作；
- 暂只支持单行提示词，不提供任意控制键或密码/审批框自动输入；
- 在 Windows 上需要实测各工具的回车和 TUI 行为。

## MCP：让 Agent 主动交流

`mcp_server.py` 提供 stdio MCP 工具，无额外 Python 依赖。须先运行平台。提供：

| 工具 | 用途 |
| --- | --- |
| `list_agents` | 查询 Agent ID 与状态 |
| `send_message` | 向信箱写入消息，不会唤醒接收者 |
| `read_inbox` | 按接收者查询最近消息，不删除 |
| `create_task` | 提交异步单人/伙伴/上下级任务 |
| `get_task` | 查询状态、运行日志和结果 |
| `cancel_task` | 取消任务 |

Codex 的 MCP 配置片段（通常放入已有的 `%USERPROFILE%\.codex\config.toml`，不要覆盖原配置）：

```toml
[mcp_servers.agent_bridge]
command = 'C:\AgentBridge\.venv\Scripts\python.exe'
args = ['C:\AgentBridge\mcp_server.py']
```

Claude Code 常见的项目 `.mcp.json` 配置片段（与已有配置合并；以本机版本文档为准）：

```json
{
  "mcpServers": {
    "agent_bridge": {
      "command": "C:\\AgentBridge\\.venv\\Scripts\\python.exe",
      "args": ["C:\\AgentBridge\\mcp_server.py"]
    }
  }
}
```

可向已接入 MCP 的 Agent 发出类似指令：

> 用 list_agents 找到评审伙伴，再创建一个 single 任务，请它审查指定工作目录中的改动。记录返回的任务 ID，稍后用 get_task 查看结果。

派发会创建独立 CLI 进程，并不是向已有对话插话。不要让正在执行的 Agent 创建“发给自己”的任务并同步等待，否则该任务会一直等待当前轮次释放 Agent。嵌套协作仍受全局队列和各任务轮数限制，但当前没有跨任务的模型调用预算追踪，应避免无界递归。

默认读取同目录下 `data/connection.json`。如果用自定义数据目录启动：

```powershell
.\.venv\Scripts\python.exe mcp_server.py --connection C:\BridgeData\connection.json
```

所有本机 MCP 客户端共享平台权限，Agent 名称和发送者 ID 只是协作标识，不是隔离或身份认证。此版本适合你自己电脑上的可信工具，不是多用户托管服务。

## 验证和兼容性

核心服务仅用 Python 标准库，Linux/macOS 可运行自动任务、网页界面与 MCP；Windows 交互终端需 pywinpty。网页不依赖 CDN，不需要前端构建工具。

```powershell
python -m unittest discover -s tests -v
python bridge.py --no-browser
```

开发环境可验证：离线自动任务、伙伴上下文传递、主管流程、排队、超时、停止、重启恢复、HTTP 访问限制与 MCP 协议。

**开发时没有连接你的 Windows 电脑，也未使用你的真实 Codex、Claude 或 Harness 登录。Windows ConPTY、PowerShell 安装启动和真实模型 CLI 的端到端行为仍须在 Windows 上验证。**

当前不包含云端连接器、屏幕点击、自动修改现有工具配置、云托管或跨电脑通信。
