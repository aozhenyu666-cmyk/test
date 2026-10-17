# 第一版验证记录

验证环境：Linux、Python 3.12、Node.js 24（仅用于 JavaScript 语法检查）、Chromium / Playwright。

## 已通过

`python -m unittest discover -s tests -v`：21 项通过。

- 中文提示词通过标准输入传入，伙伴四轮传递真实子进程输出。
- 主管安排、执行者处理、主管验收按顺序执行并传递上下文。
- 结果文件与运行日志区分。
- 失败后停止后续轮次，缺失命令明确报错。
- 同一 Agent 串行、不同 Agent 并行。
- 等待中的任务可取消；运行中的进程可停止；超时进程会被结束。
- 重启后未完成记录标为中断；相同数据目录有实例锁。
- 本机 HTTP 页面与接口、访问令牌、跨站和 Host 限制。
- 实际 stdio MCP 初始化、工具列表、HTTP 连接与中文信箱读写。
- Windows 终端输入锁与单行输入规则的模拟后端测试。
- Windows batch 启动命令的引号构造测试。

`node --check web/app.js`：通过。

`python tests/browser_smoke.py`：Chromium 中 6 个流程通过，页面 JavaScript 错误为 0。

1. 从网页添加演示伙伴，运行四轮伙伴协作并查看各轮结果。
2. 切换上下级模式并完成三轮任务。
3. 发送消息，确认文本中的 HTML/script 不会被执行。
4. 编辑 Agent 名称并确认保存。
5. Linux 中启动 Windows 终端功能时，界面显示明确的环境提示。
6. 390px 窄屏没有横向页面溢出。

浏览器检查为可选开发检查，需另行安装 Playwright 和 Chromium；正常使用产品无需安装它们。检查脚本会启动隔离的临时数据服务，结束后停止服务并清理数据。

## 尚未实测

- Windows 上 PowerShell 安装与启动。
- Windows ConPTY 中真实 Codex / DeepSeek Harness 的输入、回车和退出。
- 真实模型 CLI 的登录、权限审批和各版本命令参数。
- 云端或跨电脑连接（第一版没有实现）。

已核对 pywinpty 上游 `PtyProcess.spawn/read/write/terminate` 的接口定义，但接口核对和模拟测试不能替代 Windows 实机验证。

压缩包不含开发日志数据库、连接令牌、模型密钥或虚拟环境。
