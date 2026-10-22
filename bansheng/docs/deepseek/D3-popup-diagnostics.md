# D3：扩展弹窗显示采集诊断

## 背景

浏览器扩展「伴生桥」（`bansheng/browser_extension/`，Chrome MV3）在 Gemini / ChatGPT 页面里运行 `capture.js`，它在页面里暴露了：

```js
globalThis.__banshengCapture.status()
// 返回 { adapter: "Gemini", sessionId: "gemini:abc", sent: 12, lastError: "", lastOk: 1792650000000,
//        turnsVisible: 6, generating: false, composerFound: true }
```

页面网站改版时，最常见的问题是“选择器找不到东西”，用户需要一个地方一眼看出采集是否正常。现在弹窗（`popup.html` / `popup.js`）只能检测伴生服务是否在线。

## 你要做的

1. `capture.js`：在文件**最末尾**、`scan();` 之后、IIFE 结束之前，加一个 `chrome.runtime.onMessage` 监听：收到 `{type: "bansheng_status"}` 时 `sendResponse(globalThis.__banshengCapture.status())`。不要改文件里其他代码。

2. `popup.js` / `popup.html`：“检测连接”按钮点击后：
   - 照旧检查伴生服务（`/health`）
   - 如果当前标签页是 gemini.google.com 或 chatgpt.com，用 `chrome.tabs.sendMessage(tab.id, {type: "bansheng_status"})` 取状态，用中文列出：
     - 适配器（Gemini / ChatGPT）
     - 当前会话（没有时写“新对话，发出第一句后才有”）
     - 页面上识别到的消息条数（为 0 且页面明明有对话时，提示“没识别到消息，网页可能改版了”）
     - 输入框是否找到（没找到时提示“找不到输入框，打字时伴生可能会插话”）
     - 是否正在生成
     - 已送出事件数、最近一次成功时间（“x 秒前”）、最近错误
   - 页面脚本没响应时（例如扩展刚装好、页面没刷新），提示“请刷新这个页面”
   - 加一个“复制诊断信息”按钮，把服务状态和页面状态以 JSON 复制到剪贴板，方便发给开发者
   - 用 `textContent` 或 `createElement` 写界面，不要用 `innerHTML` 拼接页面数据

## 注意

- 不需要新的权限（`chrome.tabs.sendMessage` 对已注入内容脚本的标签页可用）；不要修改 `manifest.json`。
- 注释用中文，不新增依赖。只改 `capture.js`（末尾追加）、`popup.html`、`popup.js`。

## 验收命令

```bash
node --check bansheng/browser_extension/capture.js && node --check bansheng/browser_extension/popup.js
python bansheng/scripts/run_standalone.py --mock --data /tmp/bs-d3 &
node bansheng/tests/e2e_extension.mjs     # 仍然全部 PASS
```

手动：Chrome 里重新加载扩展，打开 gemini.google.com，点扩展图标 →“检测连接”，确认上面各项都显示出来。
