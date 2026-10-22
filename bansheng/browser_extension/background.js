// 只做一件事：把页面脚本的请求转发到本机伴生服务（页面本身不能直接跨域访问 127.0.0.1）。
const DEFAULTS = { endpoint: "http://127.0.0.1:48925", token: "", enabled: true };

async function settings() {
  const s = await chrome.storage.local.get(DEFAULTS);
  return { ...DEFAULTS, ...s };
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg?.type !== "bansheng_api") return;
  (async () => {
    const s = await settings();
    if (!s.enabled && msg.path !== "/health") return { ok: false, disabled: true };
    const headers = { "Content-Type": "application/json" };
    if (s.token) headers["X-Bansheng-Token"] = s.token;
    try {
      const r = await fetch(s.endpoint.replace(/\/$/, "") + msg.path, {
        method: msg.body ? "POST" : "GET",
        headers,
        body: msg.body ? JSON.stringify(msg.body) : undefined,
      });
      const data = await r.json().catch(() => ({}));
      return { ok: r.ok, status: r.status, data };
    } catch (e) {
      return { ok: false, offline: true, error: String(e) };
    }
  })().then(sendResponse);
  return true;
});
