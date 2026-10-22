const DEFAULTS = { endpoint: "http://127.0.0.1:48925", token: "", enabled: true };
const $ = (id) => document.getElementById(id);

chrome.storage.local.get(DEFAULTS, (s) => {
  $("endpoint").value = s.endpoint; $("token").value = s.token; $("enabled").checked = s.enabled;
  $("panel").href = s.endpoint;
});

$("save").onclick = () => {
  chrome.storage.local.set({ endpoint: $("endpoint").value.trim() || DEFAULTS.endpoint, token: $("token").value.trim(), enabled: $("enabled").checked },
    () => { $("out").textContent = "已保存"; });
};

$("test").onclick = async () => {
  const health = await chrome.runtime.sendMessage({ type: "bansheng_api", path: "/health" });
  let page = "（当前标签页不是 Gemini / ChatGPT）";
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tab && /gemini\.google\.com|chatgpt\.com/.test(tab.url || "")) page = "当前页可采集；页面右下角卡片显示采集状态";
  } catch (e) {}
  $("out").textContent = (health && health.ok ? "伴生服务：已连接" : "伴生服务：连不上（" + (health && health.error || "未启动") + "）") + "\n" + page;
};
