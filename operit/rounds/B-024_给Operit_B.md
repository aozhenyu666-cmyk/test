# B｜B-024｜只读查清三件事

**用户要做的**：无。
**你的权限**：只读。可以在沙箱里执行不改变任何状态的检查脚本。不要冻结或解冻任何 App，不要启停工作流，不要修改文件、角色卡、PTR，不要启动"不做手机控"的任何模式。
**交付目录**：`/sdcard/Download/Operit/acceptance/round1/B-024/`

## 1. "不做手机控"能否被外部触发

这决定它能否成为严格模式的执行者。

- 读出它的包名、版本号（`dumpsys package <包名>` 的 versionName/versionCode）。
- 从 `dumpsys package <包名>` 中列出所有 **exported=true** 的 Activity、Receiver、Service，以及它们的 intent-filter（action、scheme、host）。
- 查它是否声明了以下任一种入口：Tasker/Locale 插件、自定义 URL scheme、快捷方式（shortcuts）、可被其他 App 调用的广播 action、无障碍服务、设备管理员。
- **结论只写三种之一**：
  - "有可调用入口"：写出具体 action 或 URI 和参数；
  - "只有界面入口"：需要模拟点击才能操作；
  - "无"。

## 2. 小满的"暂停"是怎么写的

这决定新主控怎样判断暂停已经结束。

- 找到 `companion_brain`（或 `focus_hub_progress:report_progress`）里写入 `progress/*.jsonl` 的源码位置。导出该函数原文。
- 统计最近 7 天 `progress/*.jsonl` 中每种 `kind` 的条数，每种附一条原文样例。重点看有没有 resume、done、继续这类表示结束暂停的记录。
- 回答：用户说"暂停"时，有没有记录时长或结束时间？说"回来了"时会不会写一条记录？

## 3. 模型与语音接口在当前宿主是否存在

这决定 preview.4 的"AI 接着问"能不能用。

- 读出 Operit 宿主的 versionName 和 versionCode。
- 在沙箱脚本里执行，并原样返回结果：

```js
complete({
  chat_call: typeof Tools.Chat?.call,
  start_service: typeof Tools.Chat?.startService,
  tts: typeof Tools.SoftwareSettings?.testTtsPlayback,
  workflow_create: typeof Tools.Workflow?.create
});
```

- 注明：沙箱的结果不等于以 `api_version 1.0.0` 声明的 ToolPkg 里的结果，两者是不同环境。

## 回执

开头 `B｜B-024｜阶段`。先写三条结论，每条一行，再附原始输出。三项互不依赖，某一项读不到就写原因，继续做其他两项。
