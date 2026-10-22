# D1：ChatGPT 模拟页面和端到端测试

## 背景

「伴生」是一个桌面 AI 伙伴，浏览器扩展「伴生桥」（`bansheng/browser_extension/`）从 Gemini / ChatGPT 网页读出对话，发给本机的伴生服务（`http://127.0.0.1:48925`）。Gemini 已经有一个模拟页面和端到端测试：

- `bansheng/tests/fixtures/gemini_sim.html`：模拟 Gemini 网页结构
- `bansheng/tests/e2e_extension.mjs`：用 Playwright 加载真实扩展，把 `https://gemini.google.com/**` 的请求替换成模拟页面，检查伴生服务收到的事件

ChatGPT 的适配器已经写好（`bansheng/browser_extension/adapters.js` 里的 `chatgpt` 对象），但还没有测试。

## 你要做的

新建两个文件，**不要修改其他任何文件**：

1. `bansheng/tests/fixtures/chatgpt_sim.html`：模拟 ChatGPT 网页结构，必须和 `adapters.js` 里 chatgpt 适配器使用的选择器一致：
   - 每条消息是一个带 `data-message-author-role="user"` 或 `"assistant"` 和唯一 `data-message-id` 的元素
   - 助手消息的正文在内部的 `.markdown` 元素里
   - 输入框：`<div id="prompt-textarea" contenteditable="true">`
   - 生成中时页面上有可见的 `<button data-testid="stop-button">`，生成结束后移除
   - 页面打开时已有 2 条旧消息（1 条用户、1 条助手）
   - 提供 `window.simSend(userText, replyText, chunkMs)`：追加一条用户消息和一条助手消息，助手正文每 `chunkMs` 毫秒多出 12 个字（模拟流式输出），开始时显示停止按钮，结束时移除
   - 不要用 `innerHTML` 拼接内容，用 `createElement` / `textContent`（真实网站开了 Trusted Types）

2. `bansheng/tests/e2e_extension_chatgpt.mjs`：照 `e2e_extension.mjs` 的结构写，区别：
   - 路由 `https://chatgpt.com/**` 到 `chatgpt_sim.html`，同样带响应头 `Content-Security-Policy: require-trusted-types-for 'script'`
   - 打开 `https://chatgpt.com/c/<随机会话号>`，会话 id 是 `chatgpt:<会话号>`
   - 输入框选择器是 `#prompt-textarea`
   - 检查项和 Gemini 版一致：旧历史 2 条且都是 backfill、旧历史不触发思考、打字时话权 `user_typing`、新提问送达一次且对象是 `main_ai`、流式回复只产生一条完整事件、完整事件是最终全文、回复完成后思考一次、有一次交付、浮层显示路标和伴生的话、刷新不重复、页面没有脚本错误

## 注意

- 测试开头用和 `e2e_extension.mjs` 相同的方式加载 playwright（先本地 import，失败再从全局 `npm root -g` 加载）。
- 注释用中文。不要引入新的 npm 依赖。
- 如果测试失败是因为 `adapters.js` 的 ChatGPT 适配器有问题，**不要改 adapters.js**，在交付说明里写清楚哪一项失败、你认为原因是什么。

## 验收命令

```bash
python bansheng/scripts/run_standalone.py --mock --data /tmp/bs-d1 &
node bansheng/tests/e2e_extension_chatgpt.mjs      # 期望：全部 PASS
node bansheng/tests/e2e_extension.mjs              # Gemini 版仍然全部 PASS
```
