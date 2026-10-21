// 墨灵联络站（Cloudflare Worker，免费额度够用）
//
// 让 ChatGPT（或 Claude 等支持 MCP 的 AI）和你手机上的墨灵互相看得见：
//   - 墨灵每 10 分钟把她的“黑板”（了解、这几天、她的判断、想问的问题）推上来：PUT /api/board
//   - AI 通过 MCP 读黑板、把回答发给她：POST /mcp/<MCP_SECRET>
//   - 墨灵来收：GET /api/inbox?after=N
// 需要：KV 命名空间绑定为 MOLING；机密 MOLING_TOKEN（墨灵用）、MCP_SECRET（MCP 地址里的那段）。
// 别人的回答只进收件箱，墨灵把关后才收；改她对你的了解要你确认。

const MAX_TEXT = 8000;
const KEEP = 100;

const TOOLS = [
  {
    name: "read_moling_board",
    title: "读墨灵的黑板",
    description:
      "读墨灵（用户手机上的 AI 伙伴）的黑板：她对用户的了解、这几天的情况、她自己的判断、她想问你的问题（Q 编号）。回答前先读。",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  {
    name: "send_to_moling",
    title: "发给墨灵",
    description:
      "把你的回答、观察或对体系框架的修正发给墨灵。回答她的问题时填 question_id（如 Q3）。只写有把握的，猜测请标“猜测”。她会把关后再收；改动她对用户的了解需要用户确认。",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "要告诉墨灵的内容（最多 8000 字）" },
        question_id: { type: "string", description: "回答的是她的哪个问题，比如 Q3；不是回答就不填" },
        from: { type: "string", description: "你是谁，比如 ChatGPT" },
      },
      required: ["text"],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
  },
];

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

async function readInbox(env) {
  return (await env.MOLING.get("inbox", "json")) || { seq: 0, items: [] };
}

async function addToInbox(env, text, from, q) {
  const box = await readInbox(env);
  const item = { id: box.seq + 1, at: Date.now(), from: (from || "AI").slice(0, 40), q: (q || "").slice(0, 10), text: String(text).slice(0, MAX_TEXT) };
  box.seq = item.id;
  box.items = [...box.items, item].slice(-KEEP);
  await env.MOLING.put("inbox", JSON.stringify(box));
  return item;
}

function sameSecret(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

async function api(req, env, path) {
  const auth = req.headers.get("authorization") || "";
  if (!sameSecret(auth.replace(/^Bearer\s+/i, ""), env.MOLING_TOKEN || "")) return json({ error: "unauthorized" }, 401);
  if (path === "/api/board" && req.method === "PUT") {
    const b = await req.json().catch(() => null);
    if (!b || typeof b.text !== "string") return json({ error: "bad board" }, 400);
    const board = { text: b.text.slice(0, 20000), questions: Array.isArray(b.questions) ? b.questions.slice(0, 20) : [], at: Date.now() };
    await env.MOLING.put("board", JSON.stringify(board));
    return json({ ok: true, at: board.at });
  }
  if (path === "/api/inbox" && req.method === "GET") {
    const after = Number(new URL(req.url).searchParams.get("after") || 0);
    const box = await readInbox(env);
    return json({ items: box.items.filter((i) => i.id > after), last: box.seq });
  }
  if (path === "/api/ping") return json({ ok: true });
  return json({ error: "not found" }, 404);
}

async function callTool(env, name, args) {
  if (name === "read_moling_board") {
    const board = await env.MOLING.get("board", "json");
    if (!board) return { content: [{ type: "text", text: "墨灵还没把黑板推上来（她每 10 分钟同步一次）。" }] };
    const age = Math.round((Date.now() - board.at) / 60000);
    return { content: [{ type: "text", text: `${board.text}\n\n（黑板是 ${age} 分钟前更新的）` }] };
  }
  if (name === "send_to_moling") {
    const text = args && typeof args.text === "string" ? args.text.trim() : "";
    if (!text) return { content: [{ type: "text", text: "text 不能为空" }], isError: true };
    const item = await addToInbox(env, text, args.from || "ChatGPT", args.question_id || "");
    return { content: [{ type: "text", text: `已放进墨灵的收件箱（第 ${item.id} 条）。她下次同步时会把关后收下。` }] };
  }
  return null;
}

async function mcp(req, env) {
  if (req.method !== "POST") return new Response("Method Not Allowed", { status: 405, headers: { allow: "POST" } });
  const msg = await req.json().catch(() => null);
  if (!msg || typeof msg !== "object") return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }, 400);
  const many = Array.isArray(msg);
  const out = [];
  for (const m of many ? msg : [msg]) {
    const r = await handle(env, m);
    if (r) out.push(r);
  }
  if (out.length === 0) return new Response(null, { status: 202 });
  return json(many ? out : out[0]);
}

async function handle(env, m) {
  const id = m.id;
  const notify = id === undefined || id === null;
  const ok = (result) => ({ jsonrpc: "2.0", id, result });
  const err = (code, message) => ({ jsonrpc: "2.0", id, error: { code, message } });
  switch (m.method) {
    case "initialize":
      return ok({
        protocolVersion: (m.params && m.params.protocolVersion) || "2025-06-18",
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: "moling-relay", title: "墨灵联络站", version: "1.0.0" },
        instructions: "先用 read_moling_board 读墨灵的黑板，再用 send_to_moling 回答她的问题（带 question_id）或告诉她你的观察。",
      });
    case "ping":
      return notify ? null : ok({});
    case "tools/list":
      return ok({ tools: TOOLS });
    case "tools/call": {
      const p = m.params || {};
      const r = await callTool(env, p.name, p.arguments || {});
      return r ? ok(r) : err(-32602, `没有这个工具：${p.name}`);
    }
    default:
      if (notify) return null; // notifications/initialized 等
      return err(-32601, `不支持：${m.method}`);
  }
}

export default {
  async fetch(req, env) {
    const path = new URL(req.url).pathname.replace(/\/+$/, "") || "/";
    if (path === "/") return new Response("墨灵联络站在。", { headers: { "content-type": "text/plain; charset=utf-8" } });
    if (path.startsWith("/api/")) return api(req, env, path);
    if (env.MCP_SECRET && sameSecret(path, `/mcp/${env.MCP_SECRET}`)) return mcp(req, env);
    return new Response("Not Found", { status: 404 });
  },
};
