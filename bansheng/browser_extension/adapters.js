// 页面适配器：从 Gemini / ChatGPT 的网页读出“谁说了什么、是否还在生成、当前会话号”。
// 页面结构会变，选择器都集中在这里，坏了只改这一个文件。
(() => {
  if (globalThis.BanshengAdapters) return;

  const visible = (el) => {
    if (!el) return false;
    const r = el.getBoundingClientRect();
    const st = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && st.visibility !== "hidden" && st.display !== "none";
  };

  const cleanText = (el) => {
    if (!el) return "";
    const clone = el.cloneNode(true);
    clone.querySelectorAll(".cdk-visually-hidden, button, mat-icon, .bansheng-ignore, script, style").forEach((n) => n.remove());
    return (clone.innerText || clone.textContent || "").replace(/\n{3,}/g, "\n\n").trim();
  };

  // 简单稳定的字符串哈希（用于生成轮次 key）
  const hash = (s) => {
    let h = 2166136261;
    for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
    return (h >>> 0).toString(36);
  };

  const anyStopButton = (selectors) =>
    selectors.some((sel) => Array.from(document.querySelectorAll(sel)).some(visible));

  const gemini = {
    name: "gemini",
    label: "Gemini",
    source: "gemini_web",
    match: () => location.hostname === "gemini.google.com",
    conversationId() {
      const m = location.pathname.match(/\/app\/([A-Za-z0-9_-]+)/) || location.pathname.match(/\/gem\/[^/]+\/([A-Za-z0-9_-]+)/);
      return m ? m[1] : "";
    },
    composer: () =>
      document.querySelector("rich-textarea .ql-editor[contenteditable='true']") ||
      document.querySelector("div[contenteditable='true'][role='textbox']"),
    generating: () =>
      anyStopButton([
        "button[aria-label*='Stop' i]",
        "button[aria-label*='停止']",
        "button.stop",
        "[data-test-id='stop-button']",
      ]),
    turns() {
      const els = Array.from(document.querySelectorAll("user-query, model-response")).filter(
        (e) => !e.closest(".conversation-container-leave-animation")
      );
      const out = [];
      const seen = {};
      let lastUserKey = "start";
      for (const el of els) {
        const isUser = el.tagName === "USER-QUERY";
        const container = el.closest(".conversation-container");
        const cid = container && container.id ? container.id : "";
        if (isUser) {
          const text = cleanText(el.querySelector(".query-text") || el);
          const h = hash(text);
          seen[h] = (seen[h] || 0) + 1;
          const key = cid ? `u:${cid}` : `u:${h}#${seen[h]}`;
          lastUserKey = key;
          out.push({ speaker: "user", key, text, el });
        } else {
          const text = cleanText(el.querySelector("message-content .markdown") || el.querySelector(".markdown") || el.querySelector("message-content") || el);
          // 回复的 key 绑在它回答的那条提问上：历史向上加载时不会错位
          const key = cid ? `m:${cid}` : `m:${lastUserKey}`;
          out.push({ speaker: "main_ai", key, text, el });
        }
      }
      return out;
    },
  };

  const chatgpt = {
    name: "chatgpt",
    label: "ChatGPT",
    source: "chatgpt_web",
    match: () => location.hostname === "chatgpt.com",
    conversationId() {
      const m = location.pathname.match(/\/c\/([A-Za-z0-9_-]+)/);
      return m ? m[1] : "";
    },
    composer: () => document.querySelector("#prompt-textarea") || document.querySelector("div[contenteditable='true'][role='textbox']"),
    generating: () => anyStopButton(["button[data-testid='stop-button']", "button[aria-label*='Stop' i]"]),
    turns() {
      const out = [];
      for (const el of document.querySelectorAll("[data-message-author-role]")) {
        const role = el.getAttribute("data-message-author-role");
        if (role !== "user" && role !== "assistant") continue;
        const id = el.getAttribute("data-message-id") || hash(cleanText(el));
        const body = role === "assistant" ? el.querySelector(".markdown") || el : el;
        out.push({ speaker: role === "user" ? "user" : "main_ai", key: `${role[0]}:${id}`, text: cleanText(body), el });
      }
      return out;
    },
  };

  globalThis.BanshengAdapters = { list: [gemini, chatgpt], pick: () => [gemini, chatgpt].find((a) => a.match()) || null, cleanText, hash };
})();
