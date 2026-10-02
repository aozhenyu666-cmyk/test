"use strict";

const assert = require("assert");
const C = require("../lib/compactor.js");
const { PRESETS } = require("../lib/config.js");

let passed = 0;
function test(name, fn) {
  try {
    fn();
    passed += 1;
    console.log(`ok - ${name}`);
  } catch (error) {
    console.error(`FAIL - ${name}`);
    throw error;
  }
}

const big = (n, ch = "x") => ch.repeat(n);
const toolCall = (name, value) => `<tool name="${name}"><param name="path">/sdcard/a.txt</param><param name="content">${value}</param></tool>`;
const toolResult = (name, body) => `<tool_result name="${name}" status="success"><content>${body}</content></tool_result>`;

// 一段典型的 Operit preparedHistory：系统提示 + N 轮，每轮带一次工具调用
function buildHistory(floors, opts = {}) {
  const h = [{ kind: "SYSTEM", content: "SYS" }];
  for (let i = 0; i < floors; i += 1) {
    h.push({ kind: "USER", content: `question ${i} ` + big(opts.userLen || 50, "u") });
    h.push({ kind: "TOOL_CALL", content: toolCall("write_file", big(opts.paramLen || 2000, "p")) });
    h.push({ kind: "TOOL_RESULT", content: toolResult("read_file", `r${i} ` + big(opts.resultLen || 5000, "r")) });
    h.push({ kind: "ASSISTANT", content: `<think>${big(500, "t")}</think>answer ${i} ` + big(opts.asstLen || 3000, "a") });
  }
  return h;
}

const kinds = (h) => h.map((t) => t.kind).join(",");

test("truncateMiddle keeps head/tail, respects limit, idempotent", () => {
  const s = "HEAD" + big(5000) + "TAIL";
  const out = C.truncateMiddle(s, 300);
  assert.ok(out.length <= 300, `len ${out.length}`);
  assert.ok(out.startsWith("HEAD"));
  assert.ok(out.endsWith("TAIL"));
  assert.ok(out.includes("[精简: 省略"));
  assert.strictEqual(C.truncateMiddle(out, 300), out);
  assert.strictEqual(C.truncateMiddle("short", 300), "short");
});

test("truncateMiddle never splits surrogate pairs", () => {
  const s = "😀".repeat(400);
  const out = C.truncateMiddle(s, 201);
  assert.ok(!/[\ud800-\udbff](?![\udc00-\udfff])/.test(out), "dangling high surrogate");
  assert.ok(!/(^|[^\ud800-\udbff])[\udc00-\udfff]/.test(out), "dangling low surrogate");
});

test("tool result keeps XML envelope and name", () => {
  const out = C.compactToolResult(toolResult("read_file", big(5000)), 300, null);
  assert.ok(/^<tool_result name="read_file" status="success"><content>/.test(out));
  assert.ok(out.endsWith("</content></tool_result>"));
  assert.ok(out.length < 400);
});

test("tool result with random tag suffix and multiple blocks", () => {
  const content = `<tool_result_ab12 name="a" status="success"><content>${big(3000)}</content></tool_result_ab12>\n<tool_result_ab12 name="b" status="error"><content>${big(3000)}</content></tool_result_ab12>`;
  const out = C.compactToolResult(content, 200, null);
  assert.strictEqual((out.match(/<tool_result_ab12 /g) || []).length, 2);
  assert.strictEqual((out.match(/<\/tool_result_ab12>/g) || []).length, 2);
  assert.ok(out.length < 600);
});

test("duplicate tool results collapse", () => {
  const seen = new Set();
  const body = big(1000, "d");
  C.compactToolResult(toolResult("read_file", body), 300, seen);
  const second = C.compactToolResult(toolResult("read_file", body), 300, seen);
  assert.ok(second.includes("结果相同"));
  assert.ok(second.startsWith('<tool_result name="read_file"'));
});

test("tool call keeps tags, truncates only long params", () => {
  const out = C.compactToolCall(toolCall("write_file", big(3000)), 200);
  assert.ok(out.includes('<param name="path">/sdcard/a.txt</param>'));
  assert.ok(/<param name="content">[\s\S]*<\/param><\/tool>$/.test(out));
  assert.ok(out.length < 400);
});

test("assistant with protocol tags is not truncated", () => {
  const meta = `<meta provider="gemini:thought_signature">${big(3000)}</meta>`;
  assert.strictEqual(C.compactAssistant(meta, 200), meta);
  const out = C.compactAssistant(`<think>${big(100)}</think>hello`, 200);
  assert.strictEqual(out, "hello");
});

test("standard preset: recent floors untouched, old ones compacted, pairing intact", () => {
  const h = buildHistory(12);
  const { history, stats } = C.compactHistory(h, PRESETS.standard);
  assert.strictEqual(kinds(history), kinds(h), "same turn sequence");
  assert.ok(stats.afterChars < stats.beforeChars * 0.6, `${stats.beforeChars} -> ${stats.afterChars}`);
  // 最后 3 轮（12 条）原样
  const tail = h.slice(-12);
  assert.deepStrictEqual(history.slice(-12), tail);
  // 系统提示不动
  assert.strictEqual(history[0].content, "SYS");
  // 每个 TOOL_CALL 后面都紧跟 TOOL_RESULT
  history.forEach((t, i) => {
    if (t.kind === "TOOL_CALL") assert.strictEqual(history[i + 1].kind, "TOOL_RESULT");
  });
});

test("compaction is idempotent (host reuses the result as exec context)", () => {
  // 丢楼层会改变楼层数，宿主也不会对同一份结果再跑一次钩子；这里验证的是「内容压缩」本身幂等。
  const opts = Object.assign({}, PRESETS.aggressive, { maxFloors: 0, budgetChars: 0 });
  const h = buildHistory(15);
  const once = C.compactHistory(h, opts).history;
  const twice = C.compactHistory(once, opts).history;
  assert.deepStrictEqual(twice.map((t) => t.content), once.map((t) => t.content));
});

test("prefix is stable between consecutive turns (cache friendly)", () => {
  // 第 n 轮和第 n+1 轮发出去的前缀，在 step 内应该完全一样
  const opts = Object.assign({}, PRESETS.standard, { maxFloors: 0 });
  let stableTransitions = 0;
  for (let n = 6; n < 20; n += 1) {
    const a = C.compactHistory(buildHistory(n), opts).history;
    const b = C.compactHistory(buildHistory(n + 1), opts).history;
    const prefixSame = a.every((t, i) => b[i] && b[i].content === t.content);
    if (prefixSame) stableTransitions += 1;
  }
  // step=4：每 4 次里最多 1 次前缀变化
  assert.ok(stableTransitions >= 10, `stable ${stableTransitions}/14`);
});

test("maxFloors drops whole floors in batches and adds a notice", () => {
  const h = buildHistory(25, { resultLen: 100, paramLen: 50, asstLen: 50 });
  const opts = Object.assign({}, PRESETS.aggressive, { budgetChars: 0 });
  const { history, stats } = C.compactHistory(h, opts);
  const floors = history.filter((t) => t.kind === "USER").length;
  assert.ok(floors <= opts.maxFloors && floors > opts.maxFloors - opts.step, `floors ${floors}`);
  assert.strictEqual(history[0].kind, "SYSTEM");
  assert.strictEqual(history[1].kind, "USER", "starts at a floor boundary");
  assert.ok(history[1].content.startsWith("[精简: 更早的"));
  assert.strictEqual(stats.droppedFloors, 25 - floors);
  // 最后一条用户消息（当前输入）不能被改
  const lastUser = h.map((t) => t.kind).lastIndexOf("USER");
  assert.strictEqual(history[history.length - 4].content, h[lastUser].content);
});

test("budgetChars trims until under budget but keeps recent floors", () => {
  const h = buildHistory(8, { resultLen: 20000 });
  const opts = Object.assign({}, PRESETS.standard, { budgetChars: 30000, maxFloors: 0 });
  const { history, stats } = C.compactHistory(h, opts);
  const floors = history.filter((t) => t.kind === "USER").length;
  assert.ok(floors >= opts.keepFloors);
  assert.ok(stats.droppedFloors > 0);
  assert.strictEqual(history[0].kind, "SYSTEM");
});

test("long single-floor agent loop: old tool results compacted", () => {
  const h = [{ kind: "SYSTEM", content: "SYS" }, { kind: "USER", content: "do the big task" }];
  for (let i = 0; i < 30; i += 1) {
    h.push({ kind: "TOOL_CALL", content: toolCall("read_file", "x") });
    h.push({ kind: "TOOL_RESULT", content: toolResult("read_file", `${i}` + big(8000, "r")) });
  }
  const { history, stats } = C.compactHistory(h, PRESETS.standard);
  assert.strictEqual(kinds(history), kinds(h));
  assert.ok(stats.afterChars < stats.beforeChars / 3, `${stats.beforeChars} -> ${stats.afterChars}`);
  const results = history.filter((t) => t.kind === "TOOL_RESULT");
  assert.ok(results.slice(-6).every((t) => t.content.length > 8000), "recent results kept");
  assert.strictEqual(history[1].content, "do the big task");
});

test("SUMMARY turns survive floor dropping", () => {
  const h = buildHistory(20, { resultLen: 100, paramLen: 50, asstLen: 50 });
  h.splice(1, 0, { kind: "SUMMARY", content: "summary of older stuff" });
  const { history } = C.compactHistory(h, Object.assign({}, PRESETS.aggressive, { budgetChars: 0 }));
  assert.ok(history.some((t) => t.kind === "SUMMARY" && t.content === "summary of older stuff"));
});

test("short chats are untouched", () => {
  const h = buildHistory(2);
  const { history, stats } = C.compactHistory(h, PRESETS.standard);
  assert.strictEqual(stats.compressedTurns, 0);
  assert.deepStrictEqual(history, h);
});

test("slimToolItems drops details/notes, keeps every tool", () => {
  const items = [
    { name: "a", description: big(500), details: "long", notes: "n", parametersStructured: [] },
    { name: "b", description: "ok" },
  ];
  const out = C.slimToolItems(items, 160);
  assert.strictEqual(out.length, 2);
  assert.strictEqual(out[0].details, "");
  assert.strictEqual(out[0].notes, "");
  assert.ok(out[0].description.length <= 160);
  assert.strictEqual(out[1].description, "ok");
  assert.strictEqual(C.slimToolItems([{ name: "b", description: "ok" }], 160), null);
});

console.log(`\n${passed} compactor tests passed`);
