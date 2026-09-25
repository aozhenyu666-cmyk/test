// Node harness: mocks the Operit host APIs used by focus_hub and exercises the compiled dist.
const path = require("path");
const DIST = path.join(__dirname, "..", "dist");

const now = Date.now();
const pad = (n) => String(n).padStart(2, "0");
const d = new Date(now);
const today = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;

const events = [];
for (let i = 0; i < 300; i++) {
  const ts = Math.floor(now / 1000) - (300 - i) * 60;
  events.push(JSON.stringify({ eid: `FOREGROUND-${ts}`, id: `FOREGROUND-${ts}`, ts, iso: `2026-09-25 ${pad(Math.floor(i / 60))}:${pad(i % 60)}:00`, date: today, type: "FOREGROUND", pkg: "com.baidu.tieba", task: "求职投递", state: "ACTIVE", mode: "JOB_SEARCH", ontask: "0", src: "P3_OBS", screen: "ON", dedup: `fg-${i}` }));
}
events.push(JSON.stringify({ eid: "SCHED_PATROL-1", ts: Math.floor(now / 1000), iso: "2026-09-25 23:59:00", type: "SCHED_PATROL", late: "0", rescue: "0", src: "sched_watchdog", dedup: "sp-1" }));
events.push("{broken json");

const FILES = {
  "/sdcard/Download/Operit/drift/task_state.txt": "STATE=ACTIVE\nTASK=求职投递\nMODE=JOB_SEARCH\nPTR=T_1789439490890_tma07.txt\nDERIVED_FROM=PTR.txt+task_card(单向投影)\nSYNCED_AT=2026-09-24 21:59:13\n",
  [`/sdcard/Download/Operit/events/${today}/events.jsonl`]: events.join("\n") + "\n",
  "/sdcard/Download/Operit/events/EXEC_RULES.tsv": "# id\tcond\taction\nA1_REMIND\tdrift_level=1\t提醒\nA2_ASK\tdrift_level=2\t追问\nA3_STOP_APP\tdrift_level=3\t停止/冻结干扰应用\nA4_LOCK_SCREEN\tdrift_streak>=3\t锁屏10分钟\n",
  "/sdcard/Download/Operit/events/ACTIVE_RULES.md": "# 生效规则\n\n- [ADVICE] 贴吧超过30分钟提醒\n- [PROMPT] 偏移时追问\n- [PREAPPROVED_GUARD] 冻结抖音\n普通说明行\n",
  "/sdcard/Download/Operit/events/ACTION_LOG.tsv": Array.from({ length: 30 }, (_, i) => `2026-09-25 10:${pad(i)}\t${i % 2 ? "unfreeze" : "freeze"}\tEV-${i}\tcom.ss.android.ugc.aweme\tok\t${i % 3 ? "VERIFIED_SUCCESS" : "UNKNOWN"}`).join("\n") + "\n",
};

// Mirrors StandardFileSystemTools.readFilePart + addLineNumbers(content, startIndex, totalLines)
function readPart(p, startLine = 1, endLine) {
  if (!(p in FILES)) throw new Error(`File does not exist or is not a regular file: ${p}`);
  const all = FILES[p].split("\n");
  if (all[all.length - 1] === "") all.pop();
  const total = all.length;
  const s = Math.min(Math.max(1, startLine), Math.max(1, total));
  const e = Math.min(Math.max(endLine ?? s + 199, s), Math.max(1, total));
  let part = total > 0 ? all.slice(s - 1, e).join("\n") : "";
  const truncated = part.length > 32000;
  if (truncated) part = part.slice(0, 32000);
  const digits = String(total).length;
  let content = part.split("\n").map((l, i) => `${String(s - 1 + i + 1).padStart(digits, " ")}| ${l}`).join("\n");
  if (truncated) content += "\n\n... (file content truncated) ...";
  return { content, totalLines: total, startLine: s - 1, endLine: e };
}

const H = 3600 * 1000;
const workflows = [
  { id: "w1", name: "S3_Brief_Loop", enabled: true, lastExecutionTime: now - 10 * 60000, lastExecutionStatus: "SUCCESS", totalExecutions: 206, successfulExecutions: 173, failedExecutions: 33 },
  { id: "w2", name: "SCHED_Watchdog", enabled: true, lastExecutionTime: now - 3 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 398, successfulExecutions: 389, failedExecutions: 9 },
  { id: "w3", name: "P4_Event_Sampler", enabled: true, lastExecutionTime: now - 5 * 60000, lastExecutionStatus: "FAILED", totalExecutions: 782, successfulExecutions: 608, failedExecutions: 174 },
  { id: "w4", name: "Old_Test", enabled: false, lastExecutionTime: now - 50 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 3, successfulExecutions: 3, failedExecutions: 0 },
  { id: "w5", name: "Never_Run", enabled: true, totalExecutions: 0, successfulExecutions: 0, failedExecutions: 0 },
  { id: "w6", name: "Daily_Digest", enabled: true, lastExecutionTime: now - 20 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 5, successfulExecutions: 5, failedExecutions: 0 },
];
const details = {
  w1: [{ type: "trigger", triggerType: "schedule", triggerConfig: { schedule_type: "interval", interval_ms: "600000", enabled: "true" } }],
  w2: [{ type: "trigger", triggerType: "schedule", triggerConfig: { schedule_type: "interval", interval_ms: "900000" } }],
  w6: [{ type: "trigger", triggerType: "schedule", triggerConfig: { schedule_type: "specific_time", specific_time: "22:00" } }],
};

// 60 个临时对话，模拟工作流不断新建对话的情况
const chats = Array.from({ length: 60 }, (_, i) => ({ id: `tmp${i}`, title: `临时对话${i}`, messageCount: 1, updatedAt: "x" }));
const calls = [];
global.Tools = {
  Files: {
    exists: async (p) => ({ exists: p in FILES, isDirectory: false }),
    readPart: async (p, s, e) => readPart(p, s, e),
  },
  Workflow: {
    getAll: async () => ({ workflows, totalCount: workflows.length }),
    get: async (id) => ({ id, nodes: details[id] ?? [{ type: "trigger", triggerType: "manual" }] }),
  },
  System: {
    getAppUsageTime: async (opts) => {
      calls.push(["usage", opts]);
      return { entries: [
        { packageName: "com.ai.assistance.operit", appName: "Operit AI", totalForegroundTimeMs: 18972703, lastTimeUsed: now - 60000, isSystemApp: false },
        { packageName: "com.baidu.tieba", appName: "百度贴吧", totalForegroundTimeMs: 837062, lastTimeUsed: now - 30 * 60000, isSystemApp: false },
        { packageName: "com.zero", appName: "零分钟", totalForegroundTimeMs: 1000, lastTimeUsed: now, isSystemApp: false },
      ] };
    },
  },
  Chat: {
    // 与宿主一致：默认只返回 50 条；按标题精确过滤发生在截取之前
    listAll: async () => ({ chats: chats.slice(-50) }),
    listChats: async ({ query, match, limit }) => {
      calls.push(["listChats", query, match, limit]);
      const hit = chats.filter((c) => (match === "exact" ? c.title === query : c.title.includes(query)));
      return { chats: hit.slice(0, limit ?? 50) };
    },
    switchTo: async (id) => { calls.push(["switchTo", id]); return { chatId: id }; },
    createNew: async (g, set) => { calls.push(["createNew", g, set]); chats.push({ id: "c2", title: "新对话", messageCount: 0, updatedAt: "y" }); return { chatId: "c2" }; },
    updateTitle: async (id, t) => { calls.push(["updateTitle", id, t]); chats.find((c) => c.id === id).title = t; return { chatId: id, title: t }; },
  },
};
global.Icons = new Proxy({}, { get: (_, k) => String(k) });
global.ToolPkg = { registered: [], registerUiRoute(d) { this.registered.push(["route", d.id, d.route]); }, registerNavigationEntry(d) { this.registered.push(["nav", d.id, d.surface, d.icon]); } };

function makeCtx() {
  const state = new Map();
  const refs = new Map();
  const UI = new Proxy({}, { get: (_, type) => (props = {}, children) => ({ type, props, children }) });
  return {
    state,
    UI,
    MaterialTheme: { colorScheme: new Proxy({}, { get: (_, k) => `color:${String(k)}` }) },
    useState(key, init) { if (!state.has(key)) state.set(key, init); return [state.get(key), (v) => state.set(key, v)]; },
    useRef(key, init) { if (!refs.has(key)) refs.set(key, { current: init }); return refs.get(key); },
    showToast() {},
  };
}

function walk(node, fn) {
  if (!node || typeof node !== "object") return;
  fn(node);
  const kids = node.children == null ? [] : Array.isArray(node.children) ? node.children : [node.children];
  kids.forEach((k) => walk(k, fn));
}
function texts(node) { const out = []; walk(node, (n) => { if (n.props && typeof n.props.text === "string") out.push(n.props.text); }); return out; }
function find(node, pred) { let hit = null; walk(node, (n) => { if (!hit && pred(n)) hit = n; }); return hit; }

function assert(cond, msg) { if (!cond) { console.error("FAIL:", msg); process.exitCode = 1; } else console.log("ok  -", msg); }

(async () => {
  const main = require(path.join(DIST, "main.js"));
  assert(main.registerToolPkg() === true, "registerToolPkg returns true");
  assert(JSON.stringify(ToolPkg.registered) === JSON.stringify([["route", "focus_hub", "toolpkg:local.focus_hub:ui:focus_hub"], ["nav", "focus_hub_sidebar", "main_sidebar_plugins", "Dashboard"]]), "registers route + sidebar entry");

  const { collectSnapshot } = require(path.join(DIST, "shared/snapshot.js"));
  const snap = await collectSnapshot();
  assert(snap.sources.every((s) => s.status === "OK"), "all sources OK: " + snap.sources.map((s) => s.label + "=" + s.status).join(","));
  assert(snap.task.task === "求职投递" && snap.task.syncedAt === "2026-09-24 21:59:13", "task_state parsed (line-number prefixes stripped)");
  const h = Object.fromEntries(snap.workflows.map((w) => [w.name, w.health]));
  assert(h.S3_Brief_Loop === "OK", "10-min interval, ran 10 min ago -> OK (interval raised to 15 min)");
  assert(h.SCHED_Watchdog === "STALE", "15-min interval, last run 3h ago -> STALE");
  assert(h.P4_Event_Sampler === "FAILED", "last status FAILED -> FAILED");
  assert(h.Old_Test === "DISABLED" && h.Never_Run === "NO_HISTORY", "disabled / never-run classified");
  assert(h.Daily_Digest === "OK" && snap.workflows.find((w) => w.name === "Daily_Digest").note.includes("非间隔"), "specific_time schedule not judged stale");
  assert(snap.workflows[0].health === "FAILED" && snap.workflows[snap.workflows.length - 1].health === "DISABLED", "problems sorted first");
  assert(snap.usage.rows.length === 2 && snap.usage.rows[0].appName === "Operit AI" && snap.usage.rows[0].foregroundMinutes === 316, "usage sorted, minutes computed, zero-minute rows dropped");
  assert(calls[0][1].sinceHours === 24 && calls[0][1].includeSystemApps === false, "usage called with 24h window, no system apps");
  assert(snap.events.totalLines === 302 && snap.events.rows.length === 2 && snap.events.rows[1].count === 38 && snap.events.unparsable === 1, "events: tail of 40 lines of a >32KB file, repeats merged, 1 unparsable");
  assert(snap.events.rows[0].type === "SCHED_PATROL", "newest event first");
  assert(snap.events.rows[1].time === "04:22:00–04:59:00", "merged row keeps time range");
  assert(snap.events.rows[1].summary.includes("pkg=百度贴吧(com.baidu.tieba)"), "pkg mapped to app name");
  assert(!snap.events.rows[1].summary.includes("dedup") && !snap.events.rows[1].summary.includes("eid"), "noise keys hidden");
  assert(snap.actions.totalLines === 30 && snap.actions.rows.length === 12 && snap.actions.rows[0][2] === "EV-29", "action log tail, newest first");
  assert(snap.execRules.length === 4 && snap.execRules[0][0] === "A1_REMIND", "exec rules parsed, comment skipped");
  assert(snap.activeRules.tagCounts.ADVICE === 1 && snap.activeRules.tagCounts.PREAPPROVED_GUARD === 1 && snap.activeRules.lines.length === 3, "active rule tags counted");

  const tool = require(path.join(DIST, "packages/focus_hub_data.js"));
  const out = await tool.get_dashboard_snapshot();
  assert(out.success && out.snapshot.includes("【主控台快照】") && out.snapshot.includes("SCHED_Watchdog：疑似迟到"), "AI tool returns text snapshot");
  console.log("\n----- AI tool output -----\n" + out.snapshot + "\n--------------------------\n");

  // Missing files -> MISSING, no throw
  const saved = { ...FILES };
  for (const k of Object.keys(FILES)) delete FILES[k];
  const empty = await collectSnapshot();
  assert(empty.sources.filter((s) => s.status === "MISSING").length === 5 && empty.task === null && empty.events === null, "missing files reported as MISSING, not fabricated");
  Object.assign(FILES, saved);

  // UI render
  const Screen = require(path.join(DIST, "ui/focus_hub/index.ui.js")).default;
  const ctx = makeCtx();
  let tree = Screen(ctx);
  await tree.props.onLoad();
  tree = Screen(ctx);
  const t1 = texts(tree);
  assert(t1.includes("求职投递") && t1.includes("SCHED_Watchdog") && t1.some((x) => x.startsWith("只读看板 · 更新于")), "board renders snapshot");
  const before = calls.filter((c) => c[0] === "usage").length;
  await tree.props.onLoad();
  assert(calls.filter((c) => c[0] === "usage").length === before, "onLoad does not reload twice");
  assert(!find(tree, (n) => n.type === "AiChat"), "board tab has no AiChat");

  // Chat tab: no fixed chat -> missing, no auto-create
  const chatBtn = find(tree, (n) => n.type === "OutlinedButton" && texts(n).includes("对话"));
  await chatBtn.props.onClick();
  tree = Screen(ctx);
  assert(ctx.state.get("chatPhase") === "missing" && !calls.some((c) => c[0] === "createNew"), "no chat titled 主控台 -> shows missing, does not auto-create");
  const createBtn = find(tree, (n) => n.type === "Button" && String(n.props.text).startsWith("创建"));
  await createBtn.props.onClick();
  tree = Screen(ctx);
  assert(ctx.state.get("chatPhase") === "ready" && find(tree, (n) => n.type === "AiChat"), "explicit create -> titled 主控台, switched, AiChat shown");
  assert(JSON.stringify(calls.filter((c) => !["usage", "listChats"].includes(c[0]))) === JSON.stringify([["createNew", null, true], ["updateTitle", "c2", "主控台"], ["switchTo", "c2"]]), "create sequence: createNew -> updateTitle -> switchTo");
  assert(calls.some((c) => c[0] === "listChats" && c[2] === "exact" && c[3] === 200), "looks up fixed chat by exact title with limit 200");

  // Re-entering chat tab re-switches to the fixed chat, never creates
  const boardBtn = find(tree, (n) => n.type === "OutlinedButton" && texts(n).includes("看板"));
  await boardBtn.props.onClick();
  tree = Screen(ctx);
  await find(tree, (n) => n.type === "OutlinedButton" && texts(n).includes("对话")).props.onClick();
  assert(calls.filter((c) => c[0] === "switchTo").length === 2 && calls.filter((c) => c[0] === "createNew").length === 1, "re-entering chat re-switches, no new chat");

  // An old 主控台 chat beyond the 50 most recent must still be found
  chats.unshift({ id: "old", title: "主控台", messageCount: 120, updatedAt: "old" });
  chats.splice(chats.findIndex((c) => c.id === "c2"), 1);
  const ctx3 = makeCtx();
  let t3 = Screen(ctx3);
  await t3.props.onLoad();
  t3 = Screen(ctx3);
  await find(t3, (n) => n.type === "OutlinedButton" && texts(n).includes("对话")).props.onClick();
  assert(ctx3.state.get("chatPhase") === "ready" && ctx3.state.get("chatId") === "old", "old fixed chat outside the 50 most recent is still found");

  // Duplicate titles -> multiple, user picks
  chats.push({ id: "c3", title: "主控台", messageCount: 9, updatedAt: "z" });
  const ctx2 = makeCtx();
  let t2 = Screen(ctx2);
  await t2.props.onLoad();
  t2 = Screen(ctx2);
  await find(t2, (n) => n.type === "OutlinedButton" && texts(n).includes("对话")).props.onClick();
  t2 = Screen(ctx2);
  assert(ctx2.state.get("chatPhase") === "multiple" && ctx2.state.get("candidates").length === 2, "two chats titled 主控台 -> asks user to pick");
})();
