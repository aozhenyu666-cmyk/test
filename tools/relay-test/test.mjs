// 联络站的本地测试：node tools/relay-test/test.mjs
import assert from "node:assert/strict";
import worker from "../../moling/app/src/main/assets/relay-worker.js";

const kv = new Map();
const env = {
  MOLING_TOKEN: "tok123",
  MCP_SECRET: "sec456",
  MOLING: {
    async get(k, type) { const v = kv.get(k); return v === undefined ? null : type === "json" ? JSON.parse(v) : v; },
    async put(k, v) { kv.set(k, v); },
  },
};
const base = "https://moling.example.workers.dev";
const call = (path, init = {}) => worker.fetch(new Request(base + path, init), env);
const rpc = async (body) => { const r = await call("/mcp/sec456", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }); return [r.status, r.status === 202 ? null : await r.json()]; };

// 没有令牌不给读写
assert.equal((await call("/api/inbox")).status, 401);
assert.equal((await call("/mcp/wrong", { method: "POST", body: "{}" })).status, 404);

// 墨灵推黑板
let r = await call("/api/board", { method: "PUT", headers: { authorization: "Bearer tok123" }, body: JSON.stringify({ text: "## 墨灵想问你的\n- Q1：他定的申论框架是哪三段？", questions: [{ id: "Q1", text: "申论框架" }] }) });
assert.equal(r.status, 200);

// MCP 握手
let [s, j] = await rpc({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18" } });
assert.equal(j.result.serverInfo.name, "moling-relay");
[s, j] = await rpc({ jsonrpc: "2.0", method: "notifications/initialized" });
assert.equal(s, 202);
[s, j] = await rpc({ jsonrpc: "2.0", id: 2, method: "tools/list" });
assert.deepEqual(j.result.tools.map((t) => t.name), ["read_moling_board", "send_to_moling"]);

// AI 读黑板、回答
[s, j] = await rpc({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "read_moling_board", arguments: {} } });
assert.match(j.result.content[0].text, /Q1：他定的申论框架/);
[s, j] = await rpc({ jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "send_to_moling", arguments: { text: "三段：现象、原因、对策", question_id: "Q1" } } });
assert.match(j.result.content[0].text, /第 1 条/);
[s, j] = await rpc({ jsonrpc: "2.0", id: 5, method: "tools/call", params: { name: "send_to_moling", arguments: { text: "  " } } });
assert.equal(j.result.isError, true);
[s, j] = await rpc({ jsonrpc: "2.0", id: 6, method: "tools/call", params: { name: "nope", arguments: {} } });
assert.equal(j.error.code, -32602);
[s, j] = await rpc({ jsonrpc: "2.0", id: 7, method: "resources/list" });
assert.equal(j.error.code, -32601);

// 墨灵来收
r = await call("/api/inbox?after=0", { headers: { authorization: "Bearer tok123" } });
j = await r.json();
assert.equal(j.items.length, 1);
assert.equal(j.items[0].q, "Q1");
assert.equal(j.items[0].from, "ChatGPT");
r = await call("/api/inbox?after=1", { headers: { authorization: "Bearer tok123" } });
assert.equal((await r.json()).items.length, 0);

// GET 走 MCP 地址：不支持
assert.equal((await call("/mcp/sec456")).status, 405);
console.log("relay ok");
