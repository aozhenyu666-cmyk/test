// Node harness: mocks the Operit 1.12.2 host APIs used by focus_hub and exercises the compiled dist.
const path = require("path");
const DIST = path.join(__dirname, "..", "dist");

const now = Date.now();
const pad = (n) => String(n).padStart(2, "0");
const d = new Date(now);
const today = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;

// ---------- files ----------
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

// ---------- workflows (get_workflow nodes carry __type but no `type`, as on device) ----------
const H = 3600 * 1000;
const trig = (config) => [{ __type: "com.ai.assistance.operit.data.model.TriggerNode", id: "t", name: "t", triggerType: "schedule", triggerConfig: config }];
const workflows = [
  { id: "w1", name: "S3_Brief_Loop", enabled: true, lastExecutionTime: now - 10 * 60000, lastExecutionStatus: "SUCCESS", totalExecutions: 206, successfulExecutions: 173, failedExecutions: 33 },
  { id: "w2", name: "SCHED_Watchdog", enabled: true, lastExecutionTime: now - 3 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 398, successfulExecutions: 389, failedExecutions: 9 },
  { id: "w3", name: "P4_Event_Sampler", enabled: true, lastExecutionTime: now - 5 * 60000, lastExecutionStatus: "FAILED", totalExecutions: 782, successfulExecutions: 608, failedExecutions: 174 },
  { id: "w4", name: "Old_Test", enabled: false, lastExecutionTime: now - 50 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 3, successfulExecutions: 3, failedExecutions: 0 },
  { id: "w5", name: "Never_Run", enabled: true, totalExecutions: 0, successfulExecutions: 0, failedExecutions: 0 },
  { id: "w6", name: "Daily_Archive_Advice", enabled: true, lastExecutionTime: now - 20 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 10, successfulExecutions: 7, failedExecutions: 3 },
  { id: "w7", name: "LIFE_MorningDigest", enabled: true, lastExecutionTime: now - 30 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 9, successfulExecutions: 3, failedExecutions: 6 },
  { id: "w8", name: "G2_Judge_Flow", enabled: true, lastExecutionTime: now - 20 * 60000, lastExecutionStatus: "SUCCESS", totalExecutions: 373, successfulExecutions: 235, failedExecutions: 138 },
  { id: "w9", name: "OneShot", enabled: true, lastExecutionTime: now - 90 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 1, successfulExecutions: 1, failedExecutions: 0 },
  { id: "w10", name: "P1_Board_Start", enabled: true, lastExecutionTime: now - 90 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 11, successfulExecutions: 11, failedExecutions: 0 },
];
const details = {
  w1: trig({ schedule_type: "interval", interval_ms: "600000", enabled: "true", repeat: "true" }),
  w2: trig({ schedule_type: "interval", interval_ms: "900000", enabled: "true", repeat: "true" }),
  w6: trig({ schedule_type: "cron", cron_expression: "30 23 * * *", enabled: "true", repeat: "true" }),
  w7: trig({ schedule_type: "cron", cron_expression: "0 8 * * *", enabled: "true" }),
  w8: trig({ schedule_type: "interval", interval_ms: "1800000", enabled: "true", repeat: "true" }),
  w9: trig({ schedule_type: "specific_time", specific_time: "2026-09-22 12:47", enabled: "true" }),
  w10: [{ __type: "com.ai.assistance.operit.data.model.TriggerNode", id: "m", name: "手动", triggerType: "manual", triggerConfig: { enabled: "true" } }],
};

// ---------- chats ----------
const chats = [
  { id: "ded96924", title: "陪伴窗", messageCount: 812, updatedAt: String(now - 5 * 60000), isCurrent: false, characterCardName: "秘书" },
  { id: "f0953479", title: "温柔巡检", messageCount: 120, updatedAt: String(now - 3 * H), isCurrent: true, characterCardName: "陪伴想小处" },
  ...Array.from({ length: 60 }, (_, i) => ({ id: `tmp${i}`, title: `临时对话${i}`, messageCount: 2, updatedAt: String(now - i * H), isCurrent: false })),
];
const knownChatIds = new Set(chats.map((c) => c.id));

const calls = [];
const record = (...args) => calls.push(args);

global.Tools = {
  Files: {
    exists: async (p) => ({ exists: p in FILES, isDirectory: false }),
    readPart: async (p, s, e) => readPart(p, s, e),
    write: async (p, content, append) => {
      record("write", p, append);
      FILES[p] = append && FILES[p] ? FILES[p] + content : content;
      return { successful: true, details: "" };
    },
  },
  Workflow: {
    getAll: async () => ({ workflows, totalCount: workflows.length }),
    get: async (id) => ({ id, nodes: details[id] ?? [] }),
  },
  System: {
    getAppUsageTime: async (opts) => {
      record("usage", opts);
      return { entries: [
        { packageName: "com.ai.assistance.operit", appName: "Operit AI", totalForegroundTimeMs: 18972703, lastTimeUsed: now - 60000, isSystemApp: false },
        { packageName: "com.baidu.tieba", appName: "百度贴吧", totalForegroundTimeMs: 837062, lastTimeUsed: now - 30 * 60000, isSystemApp: false },
        { packageName: "com.zero", appName: "零分钟", totalForegroundTimeMs: 1000, lastTimeUsed: now, isSystemApp: false },
      ] };
    },
  },
  Chat: {
    listChats: async (params) => {
      record("listChats", params);
      let hit = chats.slice();
      if (params.query) hit = hit.filter((c) => (params.match === "exact" ? c.title === params.query : c.title.includes(params.query)));
      return { chats: hit.slice(0, params.limit ?? 50) };
    },
    switchTo: async () => { record("switchTo"); throw new Error("Service not connected"); },
    createNew: async () => { record("createNew"); throw new Error("Service not connected"); },
  },
};

// Java bridge: ChatHistoryManager singleton + application context
const manager = {
  callSuspend: async (method, ...args) => {
    record("mgr." + method, ...args);
    if (method === "chatExists") return knownChatIds.has(args[0]);
    return null;
  },
};
global.Java = {
  getApplicationContext: () => ({ getPackageName: () => "com.ai.assistance.operit.debug" }),
  com: { ai: { assistance: { operit: { data: { repository: { ChatHistoryManager: { getInstance: (ctx) => { record("getInstance", !!ctx); return manager; } } } } } } } },
};

// Mirrors the host JS Intent helper (AndroidUtils.js) closely enough to check what we send
global.IntentFlag = { ACTIVITY_NEW_TASK: 0x10000000, ACTIVITY_SINGLE_TOP: 0x20000000 };
global.Intent = class {
  constructor(action) { this.action = action; this.flags = []; this.extras = {}; }
  setComponent(pkg, component) {
    this.packageName = pkg;
    this.component = component.includes(".") ? (component.includes(pkg) ? component : `${pkg}.${component}`) : `${pkg}.${component}`;
    return this;
  }
  addFlag(f) { this.flags.push(f); return this; }
  putExtra(k, v) { this.extras[k] = v; return this; }
  async start() {
    if (!this.action) throw new Error("Package name or action not set.");
    const component = this.component.includes("/") ? this.component : `${this.packageName}/${this.component}`;
    record("intent", { action: this.action, component, flags: this.flags, extras: this.extras });
    return { success: true };
  }
};

global.getChatId = () => "chat-xyz";
global.Icons = new Proxy({}, { get: (_, k) => String(k) });
global.ToolPkg = { registered: [], registerUiRoute(d) { this.registered.push(["route", d.id, d.route]); }, registerNavigationEntry(d) { this.registered.push(["nav", d.id, d.surface, d.icon]); } };

function makeCtx() {
  const state = new Map();
  const refs = new Map();
  const UI = new Proxy({}, { get: (_, type) => (props = {}, children) => ({ type, props, children }) });
  return {
    state,
    toasts: [],
    UI,
    MaterialTheme: { colorScheme: new Proxy({}, { get: (_, k) => `color:${String(k)}` }) },
    useState(key, init) { if (!state.has(key)) state.set(key, init); return [state.get(key), (v) => state.set(key, v)]; },
    useRef(key, init) { if (!refs.has(key)) refs.set(key, { current: init }); return refs.get(key); },
    showToast(m) { this.toasts.push(m); },
    navigate(route) { record("navigate", route); },
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
function findAll(node, pred) { const out = []; walk(node, (n) => { if (pred(n)) out.push(n); }); return out; }
const button = (tree, label) => find(tree, (n) => ["Button", "OutlinedButton", "TextButton"].includes(n.type) && (n.props.text === label || texts(n).includes(label)));

let failures = 0;
function assert(cond, msg) { if (!cond) { console.error("FAIL:", msg); failures += 1; } else console.log("ok  -", msg); }

(async () => {
  const main = require(path.join(DIST, "main.js"));
  assert(main.registerToolPkg() === true, "registerToolPkg returns true");
  assert(JSON.stringify(ToolPkg.registered) === JSON.stringify([["route", "focus_hub", "toolpkg:local.focus_hub:ui:focus_hub"], ["nav", "focus_hub_sidebar", "main_sidebar_plugins", "Dashboard"]]), "registers route + sidebar entry");

  // ---------- snapshot ----------
  const { collectSnapshot } = require(path.join(DIST, "shared/snapshot.js"));
  const snap = await collectSnapshot();
  assert(snap.sources.every((s) => s.status === "OK"), "all sources OK");
  assert(snap.task.task === "求职投递" && snap.task.syncedAt === "2026-09-24 21:59:13", "task_state parsed (line-number prefixes stripped)");
  const wf = Object.fromEntries(snap.workflows.map((w) => [w.name, w]));
  assert(wf.G2_Judge_Flow.note === "每 30 分钟" && wf.G2_Judge_Flow.health === "OK", "trigger node without `type` field is still recognised (the on-device bug)");
  assert(wf.S3_Brief_Loop.health === "OK" && wf.S3_Brief_Loop.note === "每 15 分钟", "10-min interval raised to 15 min, recent run OK");
  assert(wf.SCHED_Watchdog.health === "STALE", "15-min interval, last run 3h ago -> STALE");
  assert(wf.P4_Event_Sampler.health === "FAILED", "last status FAILED -> FAILED");
  assert(wf.Daily_Archive_Advice.note === "每天 23:30" && wf.Daily_Archive_Advice.health === "OK", "daily cron, ran 20h ago -> OK");
  assert(wf.LIFE_MorningDigest.note === "每天 08:00" && wf.LIFE_MorningDigest.health === "STALE", "daily cron, last run 30h ago -> STALE");
  assert(wf.OneShot.note.startsWith("一次性") && wf.OneShot.health === "OK", "specific_time is one-shot, never STALE");
  assert(wf.P1_Board_Start.note === "手动触发" && wf.P1_Board_Start.health === "OK", "manual-only workflow labelled 手动触发");
  assert(wf.Old_Test.health === "DISABLED" && wf.Never_Run.health === "NO_HISTORY", "disabled / never-run classified");
  assert(snap.workflows[0].health === "FAILED" && snap.workflows[snap.workflows.length - 1].health === "DISABLED", "problems sorted first");
  assert(snap.usage.rows.length === 2 && snap.usage.rows[0].foregroundMinutes === 316, "usage sorted, zero-minute rows dropped");
  assert(snap.events.totalLines === 302 && snap.events.rows.length === 2 && snap.events.rows[1].count === 38 && snap.events.unparsable === 1, "events tail of a >32KB file, repeats merged");
  assert(snap.events.rows[1].summary.includes("pkg=百度贴吧(com.baidu.tieba)") && !snap.events.rows[1].summary.includes("dedup"), "event summary maps pkg, hides noise keys");
  assert(snap.actions.rows.length === 12 && snap.actions.rows[0][2] === "EV-29", "action log tail, newest first");
  assert(snap.execRules.length === 4 && snap.activeRules.tagCounts.PREAPPROVED_GUARD === 1, "rules parsed");

  const data = require(path.join(DIST, "packages/focus_hub_data.js"));
  const out = await data.get_dashboard_snapshot();
  assert(out.success && out.snapshot.includes("LIFE_MorningDigest：疑似迟到") && out.snapshot.includes("每天 08:00"), "AI snapshot text includes schedule labels");

  const saved = { ...FILES };
  for (const k of Object.keys(FILES)) delete FILES[k];
  const empty = await collectSnapshot();
  assert(empty.sources.filter((s) => s.status === "MISSING").length === 5 && empty.task === null, "missing files reported as MISSING, not fabricated");
  Object.assign(FILES, saved);

  // ---------- progress ----------
  const prog = require(path.join(DIST, "packages/focus_hub_progress.js"));
  const progPath = `/sdcard/Download/Operit/progress/${today}.jsonl`;
  const bad1 = await prog.report_progress({ kind: "maybe", user_quote: "x" });
  const bad2 = await prog.report_progress({ kind: "done", user_quote: "  " });
  assert(!bad1.success && !bad2.success && !(progPath in FILES), "rejects unknown kind and empty quote without writing");
  const ok1 = await prog.report_progress({ kind: "done", user_quote: "投了两家", note: "Boss 直聘" });
  const rec = JSON.parse(FILES[progPath].trim().split("\n")[0]);
  assert(ok1.success && ok1.status === "RECORDED", "report_progress records");
  assert(rec.type === "USER_PROGRESS" && rec.origin === "REAL_USER" && rec.via === "chat_ai" && rec.kind === "done", "record carries type/origin/via/kind");
  assert(rec.user_quote === "投了两家" && rec.task === "求职投递" && rec.ptr === "T_1789439490890_tma07.txt" && rec.chat_id === "chat-xyz", "record carries verbatim quote, task, PTR and chat id");
  assert(calls.some((c) => c[0] === "write" && c[1] === progPath && c[2] === true), "append-only write to progress/<date>.jsonl");
  const dup = await prog.report_progress({ kind: "done", user_quote: "投了两家" });
  assert(dup.status === "DUPLICATE" && FILES[progPath].trim().split("\n").length === 1, "same sentence within 10 min not written twice");
  await prog.report_progress({ kind: "stuck", user_quote: "简历项目经历写不下去" });
  const recent = await prog.get_recent_progress({ limit: 5 });
  assert(recent.success && recent.records.split("\n")[0].includes("卡住") && recent.records.includes("投了两家"), "get_recent_progress newest first");
  const snap2 = await collectSnapshot();
  assert(snap2.progress.length === 2 && snap2.sources.find((x) => x.label === "用户进展").status === "OK", "snapshot includes progress");
  const out2 = await data.get_dashboard_snapshot();
  assert(out2.snapshot.includes("用户最近亲口说的进展") && out2.snapshot.includes("「简历项目经历写不下去」"), "AI snapshot surfaces progress first");

  // ---------- nav tools ----------
  const nav = require(path.join(DIST, "packages/focus_hub_nav.js"));
  calls.length = 0;
  const r1 = await nav.open_focus_hub();
  const i1 = calls.find((c) => c[0] === "intent")[1];
  assert(r1.success && i1.action === "android.intent.action.MAIN", "open_focus_hub sends an activity intent with an action");
  assert(i1.component === "com.ai.assistance.operit.debug/com.ai.assistance.operit.ui.main.MainActivity", "component is package/MainActivity even for the .debug package");
  assert(i1.extras["com.ai.assistance.operit.extra.OPEN_ROUTE_ID"] === "toolpkg:local.focus_hub:ui:focus_hub", "route extra matches MainActivity.handleIntent");
  assert(i1.flags.includes(0x10000000), "NEW_TASK flag set");

  calls.length = 0;
  const r2 = await nav.open_chat({ chat_id: "nope" });
  assert(!r2.success && r2.message.includes("对话不存在") && !calls.some((c) => c[0] === "mgr.setCurrentChatId" || c[0] === "intent"), "open_chat refuses unknown chat ids before changing anything");
  calls.length = 0;
  const r3 = await nav.open_chat({ chat_id: "ded96924" });
  const seq = calls.map((c) => c[0]).filter((n) => n.startsWith("mgr.") || n === "intent");
  assert(r3.success && JSON.stringify(seq) === JSON.stringify(["mgr.chatExists", "mgr.setCurrentChatId", "intent"]), "open_chat: chatExists -> setCurrentChatId -> open chat route");
  assert(calls.find((c) => c[0] === "intent")[1].extras["com.ai.assistance.operit.extra.OPEN_ROUTE_ID"] === "native.ai_chat", "open_chat opens native.ai_chat");
  assert(!calls.some((c) => c[0] === "switchTo" || c[0] === "createNew"), "never touches floating-service tools");

  // ---------- UI: board ----------
  const Screen = require(path.join(DIST, "ui/focus_hub/index.ui.js")).default;
  const ctx = makeCtx();
  let tree = Screen(ctx);
  const header = tree.children[0];
  assert(header.props.paddingStart === 20 && header.props.paddingTop === 12 && header.props.paddingHorizontal === undefined, "header uses explicit side paddings (paddingHorizontal is ignored by the host when a side is set)");
  await tree.props.onLoad();
  tree = Screen(ctx);
  let t = texts(tree);
  assert(t.includes("当前任务") && t.includes("求职投递") && t.includes("需关注 3"), "overview tiles show task and attention count");
  assert(t.includes("全部 10 个") && !t.includes("P1_Board_Start"), "workflow list collapsed to 6 with a toggle");
  await button(tree, "全部 10 个").props.onClick();
  tree = Screen(ctx);
  assert(texts(tree).includes("Old_Test") && button(tree, "收起"), "toggle expands all workflows");
  assert(texts(tree).some((x) => x.includes("失败率 37%")), "high failure rate highlighted (G2 138/373)");
  assert(findAll(tree, (n) => n.type === "LinearProgressIndicator").length === 2, "usage rows have bars");
  const usageBars = findAll(tree, (n) => n.type === "LinearProgressIndicator").map((n) => n.props.progress);
  assert(usageBars[0] === 1 && usageBars[1] > 0 && usageBars[1] < 0.1, "bars scaled to the top app");
  assert(!find(tree, (n) => n.type === "AiChat"), "no embedded AiChat any more");
  const before = calls.filter((c) => c[0] === "usage").length;
  await tree.props.onLoad();
  assert(calls.filter((c) => c[0] === "usage").length === before, "onLoad does not reload twice");

  // ---------- UI: progress card ----------
  await tree.props.onLoad.call(null);
  ctx.state.set("snap", await collectSnapshot());
  tree = Screen(ctx);
  t = texts(tree);
  assert(t.includes("我的进展") && t.includes("「简历项目经历写不下去」"), "progress card shows recent records");
  const saveBtn = find(tree, (n) => n.type === "Button" && String(n.props.text).startsWith("记下"));
  assert(saveBtn.props.enabled === false, "记下 disabled while text empty");
  await button(tree, "提交").props.onClick();
  ctx.state.set("progressText", "又投了一家");
  tree = Screen(ctx);
  await find(tree, (n) => n.type === "Button" && String(n.props.text).startsWith("记下（提交）")).props.onClick();
  const lines = FILES[progPath].trim().split("\n").map((l) => JSON.parse(l));
  assert(lines.length === 3 && lines[2].via === "dashboard" && lines[2].kind === "done" && lines[2].user_quote === "又投了一家", "dashboard writes via=dashboard");
  assert(ctx.state.get("progressText") === "" && ctx.state.get("snap").progress[0].user_quote === "又投了一家", "input cleared and card refreshed");

  // ---------- UI: chats ----------
  calls.length = 0;
  await button(tree, "会话").props.onClick();
  tree = Screen(ctx);
  t = texts(tree);
  assert(calls.some((c) => c[0] === "listChats" && c[1].limit === 200 && c[1].sort_by === "updatedAt"), "chat list loaded sorted by updatedAt, limit 200");
  assert(t.includes("固定入口") && t.includes("陪伴窗") && t.includes("温柔巡检") && t.includes("最近 30 个"), "pinned group + recent 30");
  assert(t.includes("当前") && t.some((x) => x.includes("上下文很长")), "current chat marked, 812-message chat flagged as long");
  assert(t.some((x) => x.includes("812 条消息 · 秘书")), "row shows message count and role card");

  ctx.state.set("query", "临时对话5");
  tree = Screen(ctx);
  t = texts(tree);
  assert(t.includes("搜索结果 11") && !t.includes("固定入口"), "search filters locally (临时对话5, 50-59)");
  ctx.state.set("query", "");
  tree = Screen(ctx);

  calls.length = 0;
  const row = find(tree, (n) => n.type === "Surface" && texts(n).includes("陪伴窗"));
  await row.props.onClick();
  const uiSeq = calls.map((c) => c[0]).filter((n) => n.startsWith("mgr.") || n === "navigate");
  assert(JSON.stringify(uiSeq) === JSON.stringify(["mgr.chatExists", "mgr.setCurrentChatId", "navigate"]), "tapping a chat sets the main chat, then navigates");
  assert(calls.find((c) => c[0] === "navigate")[1] === "native.ai_chat", "navigates to native.ai_chat");
  assert(ctx.state.get("openingId") === "", "opening state cleared");

  knownChatIds.delete("f0953479");
  calls.length = 0;
  await find(Screen(ctx), (n) => n.type === "Surface" && texts(n).includes("温柔巡检")).props.onClick();
  assert(ctx.toasts.some((m) => m.includes("对话不存在")) && !calls.some((c) => c[0] === "navigate"), "deleted chat -> toast, no navigation");

  console.log(failures === 0 ? "\nALL PASSED" : `\n${failures} FAILED`);
  process.exitCode = failures === 0 ? 0 : 1;
})();
