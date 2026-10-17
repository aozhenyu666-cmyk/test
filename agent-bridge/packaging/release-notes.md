Windows 桌面版，内置 Python 运行时，无需手动安装 Python。

**推荐下载安装程序 `AgentBridge-Setup-0.2.0-x64.exe`**。也可下载便携版 ZIP，完整解压后运行 `AgentBridge.exe`。

新增独立窗口、1–8 位 Agent 协作、主管与多执行者流程、对话历史与续接、失败续跑、消息唤醒、命令检测、结果导出、配置备份/导入，以及可键入、缩放和保存历史的 Windows 终端。MCP 提供查询、交流、派发和续跑能力。

适用 Windows 10 1809+ / Windows 11 x64；窗口依赖 Microsoft Edge WebView2 Runtime。Codex CLI、Claude Code 或 Harness 本身仍需安装并登录。DeepSeek Harness 使用实际启动命令配置。

Windows 自动测试包含真实 ConPTY 中文输入与缩放、带空格路径的 batch 命令；打包时还运行内置运行时和原生 WebView2 窗口检查。测试与构建成功后才发布。

此版本未签名，首次运行可能出现 Windows 来源提示。数据保存在 `%APPDATA%\AgentBridge`；升级和卸载不删除数据。当前为本机版，不包含云端连接器，也不接管已经打开的其他应用窗口。
