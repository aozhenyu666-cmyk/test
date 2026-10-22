// 页面右下角的三行路标：在聊什么 / 已确认 / 没解开；可收起、静音、调主动程度、点击修改。
// 也显示伴生最近说的一句（没开声音时可以只看文字）。
(() => {
  if (globalThis.__banshengOverlay || !globalThis.__banshengCapture) return;
  globalThis.__banshengOverlay = true;
  const cap = globalThis.__banshengCapture;

  const api = (path, body) =>
    new Promise((resolve) => {
      try {
        chrome.runtime.sendMessage({ type: "bansheng_api", path, body }, (r) => { void chrome.runtime.lastError; resolve(r || { ok: false }); });
      } catch (e) { resolve({ ok: false }); }
    });

  const host = document.createElement("div");
  host.className = "bansheng-ignore";
  host.style.cssText = "position:fixed;right:16px;bottom:96px;z-index:2147483646;";
  const root = host.attachShadow({ mode: "open" });
  root.innerHTML = `
  <style>
    :host { all: initial; }
    .card { font: 13px/1.5 system-ui, "PingFang SC", "Microsoft YaHei", sans-serif; width: 300px; max-width: calc(100vw - 32px);
      background: rgba(255,255,255,.96); color:#1f2328; border:1px solid #e3e0da; border-radius:12px;
      box-shadow:0 6px 24px rgba(0,0,0,.12); overflow:hidden; }
    @media (prefers-color-scheme: dark) { .card { background: rgba(32,34,38,.96); color:#e8e6e1; border-color:#33373c; } .k { color:#9aa0a6 !important; } }
    .hd { display:flex; align-items:center; gap:6px; padding:6px 10px; cursor:default; user-select:none; }
    .dot { width:8px; height:8px; border-radius:50%; background:#aaa; }
    .dot.ok { background:#3a9b5d; } .dot.think { background:#d79b2b; } .dot.off { background:#c0564b; }
    .t { font-weight:600; } .sp { flex:1; }
    button, select { font:inherit; font-size:12px; border:1px solid rgba(127,127,127,.35); background:transparent; color:inherit; border-radius:6px; padding:1px 7px; cursor:pointer; }
    button.on { background:#c0564b; color:#fff; border-color:#c0564b; }
    .bd { padding:2px 10px 8px; }
    .ln { display:grid; grid-template-columns:3.6em 1fr; gap:6px; padding:2px 0; cursor:pointer; }
    .ln:hover .v { text-decoration: underline dotted; }
    .k { color:#6b7280; }
    .say { margin-top:6px; padding:6px 8px; border-radius:8px; background:rgba(59,110,168,.10); display:none; }
    .msg { color:#6b7280; font-size:12px; padding-top:4px; }
    .collapsed .bd { display:none; } .collapsed { width:auto; }
  </style>
  <div class="card" id="card">
    <div class="hd"><span class="dot" id="dot"></span><span class="t">伴生</span><span class="sp"></span>
      <select id="lv" title="主动程度"><option value="0">关</option><option value="1">少</option><option value="2">正常</option><option value="3">多</option></select>
      <button id="mute" title="让伴生安静">静音</button><button id="fold" title="收起">－</button></div>
    <div class="bd">
      <div class="ln" data-line="topic"><span class="k">在聊</span><span class="v" id="topic">…</span></div>
      <div class="ln" data-line="confirmed"><span class="k">已确认</span><span class="v" id="confirmed">…</span></div>
      <div class="ln" data-line="open"><span class="k">没解开</span><span class="v" id="open">…</span></div>
      <div class="say" id="say"></div>
      <div class="msg" id="msg"></div>
    </div>
  </div>`;
  document.documentElement.appendChild(host);
  const $ = (id) => root.getElementById(id);
  let last = null;

  try {
    chrome.storage.local.get({ overlayCollapsed: false }, (v) => $("card").classList.toggle("collapsed", !!v.overlayCollapsed));
  } catch (e) {}
  $("fold").onclick = () => {
    const c = $("card").classList.toggle("collapsed");
    $("fold").textContent = c ? "＋" : "－";
    try { chrome.storage.local.set({ overlayCollapsed: c }); } catch (e) {}
  };
  $("mute").onclick = async () => { await api("/control", { action: last?.policy?.muted ? "unmute" : "mute" }); refresh(); };
  $("lv").onchange = async () => { await api("/control", { action: "level", value: Number($("lv").value) }); refresh(); };
  root.querySelectorAll(".ln").forEach((el) => (el.onclick = async () => {
    if (!last?.session_id) return;
    const line = el.dataset.line;
    const v = prompt("改成（留空＝交回给伴生自动维护）：", last.signpost[line]);
    if (v !== null) { await api("/edit", { session_id: last.session_id, line, text: v }); refresh(); }
  }));

  async function refresh() {
    if (document.hidden) return;
    const sid = cap.sessionId();
    const r = await api("/state" + (sid ? "?session_id=" + encodeURIComponent(sid) : ""));
    const s = cap.status();
    if (!r.ok) {
      $("dot").className = "dot off";
      $("msg").textContent = r.offline ? "伴生服务没开（N.E.K.O. 里启动「伴生」插件）" : r.disabled ? "扩展已暂停" : "连接出错";
      return;
    }
    last = r.data;
    const d = last;
    $("dot").className = "dot " + (d.thinking ? "think" : "ok");
    $("lv").value = String(d.policy.level);
    $("mute").classList.toggle("on", d.policy.muted);
    $("mute").textContent = d.policy.muted ? "已静音" : "静音";
    if (!sid) { $("topic").textContent = "（新对话，发出第一句后开始）"; $("confirmed").textContent = $("open").textContent = "—"; }
    else if (d.signpost) { for (const k of ["topic", "confirmed", "open"]) $(k).textContent = d.signpost[k]; }
    const said = (d.recent_decisions || []).filter((c) => c.status === "delivered").pop();
    const fresh = said && said.age_s < 90;
    $("say").style.display = fresh ? "block" : "none";
    if (fresh) $("say").textContent = "伴生：" + said.text;
    $("msg").textContent = s.lastError ? s.lastError : `${s.adapter} · 已送 ${s.sent} 条${d.floor?.busy ? " · 等你：" + d.floor.busy : ""}`;
  }
  refresh();
  setInterval(refresh, 2000);
})();
