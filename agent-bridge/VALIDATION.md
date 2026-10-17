# Agent Bridge 0.2.0 验证记录

## 本地核心与浏览器验证

Linux / Python 3.12：33 项自动测试中，30 项通过，3 项 Windows 专属检查跳过。覆盖任务传递、主管流程、并发/串行、停止、超时、失败续跑、会话上下文、版本检查、备份的整体校验、消息唤醒、HTTP 限制和真实 stdio MCP 协议。

Chromium / Playwright：13 个操作流程通过，页面 JavaScript 错误为 0：

1. 伙伴四轮协作与逐轮结果。
2. 主管三轮派发与验收。
3. 消息中的 HTML/script 作为文本展示。
4. Agent 配置编辑。
5. 命令检查结果展示。
6. 从历史对话继续任务。
7. Markdown 结果下载。
8. MCP 本机 JSON/TOML 配置生成。
9. 配置导出与导入。
10. 主管与两名执行者的四轮协作。
11. 消息唤醒 Agent 并收集结果。
12. 非 Windows 终端入口的明确反馈。
13. 390px 窄屏无横向页面溢出。

`node --check web/app.js` 与 Python 编译检查通过。

## Windows 持续构建与分发检查

[Agent Bridge Windows 工作流](https://github.com/aozhenyu666-cmyk/test/actions/workflows/agent-bridge-windows.yml) 在 Windows runner 中执行：

- 全部 33 项自动测试，包含真实 ConPTY 的中文输入/输出、窗口缩放，带空格路径的 .cmd 包装器，以及停止终端时清理子进程树。
- PyInstaller 生成原生 GUI EXE 与共享运行时的 CLI 辅助 EXE。
- 打包后的 `--self-test` 检查内置运行时、前端资源、真实子进程、SQLite、stdio MCP、pywebview 与 winpty。
- 打包后的 `--window-self-test` 启动真实 WebView2 窗口，验证界面连接到本地服务，以及离线终端资源已加载。
- Inno Setup 生成安装程序。
- 将安装程序静默安装到带空格的路径，在已安装程序中运行自检，再卸载。
- 打包便携 ZIP 并生成 SHA-256 校验文件。

任一检查失败都不会执行发布步骤。具体构建日志和最终提交见对应版本的 GitHub Actions 记录。

## 验证范围

这些检查使用离线演示 Agent 和测试进程，不调用真实模型，不使用用户密钥。真实 Codex / Claude / DeepSeek Harness 的登录、审批策略、版本参数，以及用户项目中的实际任务仍需在本机配置后验证。

Windows runner 的自动验证不等于覆盖所有 Windows 版本、杀毒软件、企业策略、输入法和第三方 TUI。程序没有代码签名；安装和窗口显示依赖当前系统的 Microsoft Edge WebView2 Runtime。

不包含云端或跨电脑连接，不接管已有桌面窗口，不声称恢复模型工具内部会话，也不承诺用任意 TUI 的输出自动判断任务已完成。
