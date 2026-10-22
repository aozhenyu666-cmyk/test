// 采集：观察页面变化，把新的用户提问和主力 AI 的完整回复送给伴生。
// - 页面打开、切换会话时已经存在的旧消息标记为 backfill（只用于理解，不触发介入）
// - 主力 AI 生成中只发节流的 partial（让伴生知道“它还在说”），稳定后发一次完整内容
// - 用户在输入框打字时通知伴生让出话权
(() => {
  if (globalThis.__banshengCapture) return;
  const A = globalThis.BanshengAdapters && globalThis.BanshengAdapters.pick();
  if (!A) return;

  const STABLE_MS = 1500;          // 回复文字多久不变算完成
  const USER_STABLE_MS = 300;
  const BACKFILL_WINDOW_MS = 2500; // 加载/切换会话后这段时间内出现的消息视作旧历史
  const PARTIAL_EVERY_MS = 2000;
  const TYPING_EVERY_MS = 1000;

  const api = (path, body) =>
    new Promise((resolve) => {
      try {
        chrome.runtime.sendMessage({ type: "bansheng_api", path, body }, (r) => {
          void chrome.runtime.lastError;
          resolve(r || { ok: false });
        });
      } catch (e) {
        resolve({ ok: false, error: String(e) }); // 扩展被重新加载后旧页面脚本会走到这里
      }
    });

  const st = {
    convId: undefined,
    known: new Map(),
    backfillUntil: Date.now() + BACKFILL_WINDOW_MS,
    lastPartialAt: 0,
    lastTypingAt: 0,
    lastComposerActivity: 0,
    sent: 0,
    lastError: "",
    lastOk: 0,
  };
  const sessionId = () => (st.convId ? `${A.name}:${st.convId}` : "");

  async function send(events) {
    if (!events.length) return;
    const r = await api("/events", { events });
    if (r.ok) { st.sent += events.length; st.lastOk = Date.now(); st.lastError = ""; }
    else st.lastError = r.offline ? "伴生服务没开" : r.disabled ? "已在扩展里暂停" : `错误 ${r.status || ""}`;
    return r;
  }

  function scan() {
    const now = Date.now();
    const cid = A.conversationId();
    if (cid !== st.convId) {
      const fromNewChat = st.convId === "";
      if (!fromNewChat) {
        // 首次加载或切到另一个已有会话：之后短时间内出现的都是旧历史
        st.known.clear();
        st.backfillUntil = now + BACKFILL_WINDOW_MS;
      }
      st.convId = cid;
    }
    const turns = A.turns();
    const lastMain = [...turns].reverse().find((t) => t.speaker === "main_ai");
    const generating = A.generating();
    const out = [];
    for (const t of turns) {
      let k = st.known.get(t.key);
      if (!k) {
        const typedRecently = now - st.lastComposerActivity < 4000;
        k = { text: "", changedAt: now, initial: now < st.backfillUntil && !(t.speaker === "user" && typedRecently), sentText: null };
        st.known.set(t.key, k);
      }
      if (t.text !== k.text) { k.text = t.text; k.changedAt = now; }
      if (!k.text || !st.convId) continue;
      const stableFor = now - k.changedAt;
      const isLastMain = lastMain && t.key === lastMain.key;
      const done = t.speaker === "user" ? stableFor >= USER_STABLE_MS
        : stableFor >= STABLE_MS && !(isLastMain && generating);
      const base = { session_id: sessionId(), speaker: t.speaker, text: k.text, turn_key: t.key, source: A.source,
                     addressee: t.speaker === "user" ? "main_ai" : "user", backfill: k.initial, ts: now / 1000 };
      if (done) {
        if (k.sentText !== k.text) { k.sentText = k.text; out.push({ ...base, complete: true }); }
      } else if (t.speaker === "main_ai" && isLastMain && !k.initial && now - st.lastPartialAt > PARTIAL_EVERY_MS) {
        st.lastPartialAt = now;
        out.push({ ...base, complete: false });
      }
    }
    send(out);
  }

  let scanTimer = 0;
  const schedule = () => { clearTimeout(scanTimer); scanTimer = setTimeout(scan, 250); };
  new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
  setInterval(scan, 800);

  const onComposer = (e) => {
    const c = A.composer();
    if (!c || !(c === e.target || c.contains(e.target))) return;
    const now = Date.now();
    st.lastComposerActivity = now;
    if (sessionId() && now - st.lastTypingAt > TYPING_EVERY_MS) {
      st.lastTypingAt = now;
      api("/floor", { session_id: sessionId(), user_typing: true });
    }
  };
  document.addEventListener("input", onComposer, true);
  document.addEventListener("keydown", onComposer, true);

  globalThis.__banshengCapture = {
    adapter: A.label,
    sessionId,
    status: () => ({ adapter: A.label, sessionId: sessionId(), sent: st.sent, lastError: st.lastError, lastOk: st.lastOk,
                     turnsVisible: A.turns().length, generating: A.generating(), composerFound: !!A.composer() }),
  };
  scan();
})();
