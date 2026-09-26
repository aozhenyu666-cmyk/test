// Node harness: mocks the Operit 1.12.2 host APIs used by focus_hub and exercises the compiled dist.
const path = require("path");
const fs = require("fs");
const DIST = path.join(__dirname, "..", "dist");

// Fixed clock: today 14:30 local, so quiet hours / cooldown are deterministic
const base = new Date();
base.setHours(14, 30, 0, 0);
const now = base.getTime();
Date.now = () => now;

const pad = (n) => String(n).padStart(2, "0");
const dkey = (t) => { const x = new Date(t); return `${x.getFullYear()}${pad(x.getMonth() + 1)}${pad(x.getDate())}`; };
const iso = (t) => { const x = new Date(t); return `${x.getFullYear()}-${pad(x.getMonth() + 1)}-${pad(x.getDate())} ${pad(x.getHours())}:${pad(x.getMinutes())}:${pad(x.getSeconds())}`; };
const mtime = (t) => `${iso(t)}.000`;
const today = dkey(now);
const yesterday = dkey(now - 24 * 3600 * 1000);
const MIN = 60000, H = 3600000;

// ---------- events: a day of 15-min samples, a drift, long filler lines, one broken line ----------
const events = [];
const fg = (t, pkg, ontask, extra = {}) => events.push(JSON.stringify({ eid: `FG-${t}`, ts: Math.floor(t / 1000), iso: iso(t), date: today, type: "FOREGROUND", pkg, task: "求职投递", state: "ACTIVE", mode: "JOB_SEARCH", ontask, src: "P3_OBS", screen: "ON", dedup: `fg-${t}`, ...extra }));
for (let i = 0; i < 120; i++) {
  events.push(JSON.stringify({ eid: `UR-${i}`, ts: Math.floor((now - 6 * H + i * MIN) / 1000), iso: iso(now - 6 * H + i * MIN), type: "USAGE_REFRESH", status: "ok", src: "publish_usage", pad: "x".repeat(900), dedup: `ur-${i}` }));
}
for (let t = now - 5 * H; t < now - 60 * MIN; t += 15 * MIN) fg(t, "com.boss.zhipin", "1");
fg(now - 4 * H, "none", "");
fg(now - 3 * H, "com.kurogame.mingchao", "0");
fg(now - 45 * MIN, "com.boss.zhipin", "1");
fg(now - 30 * MIN, "com.baidu.tieba", "0");
fg(now - 20 * MIN, "com.boss.zhipin", "1");
fg(now - 5 * MIN, "com.baidu.tieba", "0");
events.push(JSON.stringify({ eid: "DRIFT-1", ts: Math.floor((now - 20 * MIN) / 1000), iso: iso(now - 20 * MIN), type: "DRIFT", pkg: "com.baidu.tieba", kind: "streak", src: "drift_scan", dedup: "d1" }));
events.push("{broken json");

const P = "/sdcard/Download/Operit";
const FILES = {
  [`${P}/drift/task_state.txt`]: "STATE=ACTIVE\nTASK=求职投递\nMODE=JOB_SEARCH\nPTR=T_1789439490890_tma07.txt\nDERIVED_FROM=PTR.txt+task_card(单向投影)\nSYNCED_AT=2026-09-24 21:59:13\n",
  [`${P}/events/${today}/events.jsonl`]: events.join("\n") + "\n",
  [`${P}/events/EXEC_RULES.tsv`]: "# id\tcond\taction\nA1_REMIND\tdrift_level=1\t提醒\nA2_ASK\tdrift_level=2\t追问\nA3_STOP_APP\tdrift_level=3\t停止/冻结干扰应用\nA4_LOCK_SCREEN\tdrift_streak>=3\t锁屏10分钟\n",
  [`${P}/events/ACTIVE_RULES.md`]: "# 生效规则\n\n- [ADVICE] 贴吧超过30分钟提醒\n- [PROMPT] 偏移时追问\n- [PREAPPROVED_GUARD] 冻结抖音\n普通说明行\n",
  [`${P}/events/ACTION_LOG.tsv`]: Array.from({ length: 30 }, (_, i) => `2026-09-25 10:${pad(i)}\t${i % 2 ? "unfreeze" : "freeze"}\tEV-${i}\tcom.ss.android.ugc.aweme\tok\t${i % 3 ? "VERIFIED_SUCCESS" : "UNKNOWN"}`).join("\n") + "\n",
};
const MTIMES = {
  [`${P}/p2/obs_log.txt`]: now - 5 * MIN,
  [`${P}/events/${today}/events.jsonl`]: now - 5 * MIN,
  [`${P}/selfreview/.usage_fresh`]: now - 42 * MIN,
  [`${P}/judge/${yesterday}.jsonl`]: now - 30 * H,
  [`${P}/drift/channel_health.tsv`]: now - 12 * MIN,
  [`${P}/events/${yesterday}/digest.txt`]: now - 14 * H,
  [`${P}/drift/task_state.txt`]: now - 3 * H,
};

// Mirrors StandardFileSystemTools.readFilePart + addLineNumbers(content, startIndex, totalLines)
let readPartCalls = 0;
function readPart(p, startLine = 1, endLine) {
  readPartCalls += 1;
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
const trig = (config) => [{ __type: "com.ai.assistance.operit.data.model.TriggerNode", id: "t", name: "t", triggerType: "schedule", triggerConfig: config }];
const workflows = [
  { id: "w1", name: "S3_Brief_Loop", enabled: true, lastExecutionTime: now - 10 * MIN, lastExecutionStatus: "SUCCESS", totalExecutions: 206, successfulExecutions: 173, failedExecutions: 33 },
  { id: "w2", name: "SCHED_Watchdog", enabled: true, lastExecutionTime: now - 3 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 398, successfulExecutions: 389, failedExecutions: 9 },
  { id: "w3", name: "P4_Event_Sampler", enabled: true, lastExecutionTime: now - 5 * MIN, lastExecutionStatus: "FAILED", totalExecutions: 782, successfulExecutions: 608, failedExecutions: 174 },
  { id: "w4", name: "Old_Test", enabled: false, lastExecutionTime: now - 50 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 3, successfulExecutions: 3, failedExecutions: 0 },
  { id: "w5", name: "Never_Run", enabled: true, totalExecutions: 0, successfulExecutions: 0, failedExecutions: 0 },
  { id: "w6", name: "Daily_Archive_Advice", enabled: true, lastExecutionTime: now - 15 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 10, successfulExecutions: 7, failedExecutions: 3 },
  { id: "w7", name: "LIFE_MorningDigest", enabled: true, lastExecutionTime: now - 30 * H, lastExecutionStatus: "SUCCESS", totalExecutions: 9, successfulExecutions: 3, failedExecutions: 6 },
  { id: "w8", name: "G2_Judge_Flow", enabled: true, lastExecutionTime: now - 20 * MIN, lastExecutionStatus: "SUCCESS", totalExecutions: 373, successfulExecutions: 235, failedExecutions: 138 },
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
  { id: "d20b4e22-f6ab-4766-bc83-54e96c99bb44", title: "秘书", messageCount: 530, updatedAt: String(now - 2 * MIN), isCurrent: false, characterCardName: "小处" },
  { id: "ded96924", title: "陪伴窗", messageCount: 812, updatedAt: String(now - 5 * MIN), isCurrent: false, characterCardName: "陪伴" },
  { id: "f0953479", title: "温柔巡检", messageCount: 120, updatedAt: String(now - 3 * H), isCurrent: true, characterCardName: "陪伴想小处" },
  { id: "28f6fbb9", title: "判断官", messageCount: 400, updatedAt: String(now - 20 * MIN), isCurrent: false },
  ...Array.from({ length: 60 }, (_, i) => ({ id: `tmp${i}`, title: `临时对话${i}`, messageCount: 2, updatedAt: String(now - i * H), isCurrent: false })),
];
const knownChatIds = new Set(chats.map((c) => c.id));

const calls = [];
const record = (...args) => calls.push(args);
let floatingServiceRunning = false;
let ttsFails = false;

global.Tools = {
  Files: {
    exists: async (p) => ({ exists: p in FILES, isDirectory: false }),
    readPart: async (p, s, e) => readPart(p, s, e),
    write: async (p, content, append) => {
      record("write", p, append);
      FILES[p] = append && FILES[p] ? FILES[p] + content : content;
      MTIMES[p] = now;
      return { successful: true, details: "" };
    },
    info: async (p) => (p in MTIMES ? { exists: true, lastModified: mtime(MTIMES[p]) } : { exists: false, lastModified: "" }),
  },
  Workflow: {
    getAll: async () => ({ workflows, totalCount: workflows.length }),
    get: async (id) => ({ id, nodes: details[id] ?? [] }),
  },
  System: {
    getAppUsageTime: async (opts) => {
      record("usage", opts);
      return { entries: [
        { packageName: "com.ai.assistance.operit", appName: "Operit AI", totalForegroundTimeMs: 18972703, lastTimeUsed: now - MIN, isSystemApp: false },
        { packageName: "com.baidu.tieba", appName: "百度贴吧", totalForegroundTimeMs: 837062, lastTimeUsed: now - 5 * MIN, isSystemApp: false },
        { packageName: "com.kurogame.mingchao", appName: "鸣潮", totalForegroundTimeMs: 1000, lastTimeUsed: now - 3 * H, isSystemApp: false },
      ] };
    },
  },
  SoftwareSettings: {
    testTtsPlayback: async (text, opts) => { record("tts", text, opts); if (ttsFails) throw new Error("Unknown error"); return { playbackTriggered: true, initialized: true }; },
  },
  Chat: {
    listChats: async (params) => {
      record("listChats", params);
      let hit = chats.slice();
      if (params.query) hit = hit.filter((c) => (params.match === "exact" ? c.title === params.query : c.title.includes(params.query)));
      return { chats: hit.slice(0, params.limit ?? 50) };
    },
    switchTo: async (id) => { record("switchTo", id); if (!floatingServiceRunning) throw new Error("Service not connected"); return { chatId: id }; },
    createNew: async () => { record("createNew"); throw new Error("Service not connected"); },
    startService: async (opts) => { record("startService", opts); floatingServiceRunning = true; return {}; },
    call: async (opts) => { record("chatCall", opts); return { text: "  我看到你刚才在贴吧啦～先投一家好不好？  ", turns: [] }; },
  },
};

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
const texts = (node) => { const out = []; walk(node, (n) => { if (n.props && typeof n.props.text === "string") out.push(n.props.text); }); return out; };
const find = (node, pred) => { let hit = null; walk(node, (n) => { if (!hit && pred(n)) hit = n; }); return hit; };
const findAll = (node, pred) => { const out = []; walk(node, (n) => { if (pred(n)) out.push(n); }); return out; };
const clickable = (tree, label) => find(tree, (n) => n.props && typeof n.props.onClick === "function" && (n.props.text === label || texts(n).includes(label)));
const has = (tree, sub) => texts(tree).some((t) => t.includes(sub));

let failures = 0;
function assert(cond, msg) { if (!cond) { console.error("FAIL:", msg); failures += 1; } else console.log("ok  -", msg); }

(async () => {
  const main = require(path.join(DIST, "main.js"));
  assert(main.registerToolPkg() === true, "registerToolPkg returns true");
  assert(JSON.stringify(ToolPkg.registered) === JSON.stringify([["route", "focus_hub", "toolpkg:local.focus_hub:ui:focus_hub"], ["nav", "focus_hub_sidebar", "main_sidebar_plugins", "Dashboard"]]), "registers route + sidebar entry");

  // ---------- manifest + workflow template ----------
  const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, "..", "manifest.json"), "utf8"));
  assert(manifest.api_version === "1.0.0" && manifest.version === "0.4.1", "v0.4.1 back on ToolPkg API 1.0.0 (Chat.call no longer used)");
  const tpl = JSON.parse(fs.readFileSync(path.join(__dirname, "..", manifest.resources[0].path), "utf8"));
  const exec = tpl.nodes.find((n) => n.type === "execute");
  assert(manifest.workflow_templates[0].resource_key === manifest.resources[0].key, "workflow template points at a declared resource");
  assert(tpl.nodes.every((n) => n.__type && n.type) && exec.actionType === "focus_hub_nav:check_in", "template nodes carry __type + type and call focus_hub_nav:check_in");
  assert(tpl.nodes.some((n) => n.triggerType === "schedule" && n.triggerConfig.interval_ms === "3600000"), "template runs hourly");

  // ---------- snapshot ----------
  const { collectSnapshot } = require(path.join(DIST, "shared/snapshot.js"));
  readPartCalls = 0;
  const snap = await collectSnapshot();
  assert(snap.sources.every((s) => s.status === "OK"), "all sources OK");
  assert(snap.task.task === "求职投递", "task_state parsed");
  const wf = Object.fromEntries(snap.workflows.map((w) => [w.name, w]));
  assert(wf.G2_Judge_Flow.note === "每 30 分钟" && wf.SCHED_Watchdog.health === "STALE" && wf.LIFE_MorningDigest.health === "STALE", "schedule detection (no `type` field) + staleness");
  assert(wf.P1_Board_Start.note === "手动触发" && wf.OneShot.note.startsWith("一次性"), "manual / one-shot labelled");

  const f = snap.events.focus;
  assert(snap.events.unparsable === 1 && snap.events.totalLines === events.length, "whole day read in chunks (long lines forced chunk halving), 1 unparsable");
  assert(f.onTask === 18 && f.offTask === 3 && f.unknown === 1, `focus counts from FOREGROUND only (on ${f.onTask} off ${f.offTask} unk ${f.unknown})`);
  assert(f.recent.on === 2 && f.recent.off === 2 && f.recent.offApps[0] === "百度贴吧", "last-hour window maps pkg to app name");
  assert(f.offApps[0].name === "百度贴吧" && f.offApps[0].count === 2 && f.offApps[1].name === "鸣潮", "off-task apps ranked, names from full usage list");
  assert(f.hours[14].off === 2 && f.hours[14].on === 1 && f.hours.reduce((a, b) => a + b.on + b.off + b.unknown, 0) === 22, "hourly buckets");
  assert(f.drifts.length === 1, "DRIFT events collected");

  const hc = Object.fromEntries(snap.health.map((h) => [h.label, h]));
  assert(hc["前台采样"].status === "FRESH" && hc["用量刷新"].status === "FRESH" && hc["提醒通道"].status === "FRESH", "fresh artifacts");
  assert(hc["判断官"].status === "STALE" && hc["判断官"].path.endsWith(`${yesterday}.jsonl`), "judge falls back to yesterday's file and is STALE (30h)");
  assert(hc["日报"].status === "FRESH" && hc["当前任务"].status === "FRESH", "yesterday's digest OK, task_state has no staleness rule");
  assert(snap.companion.source === "secretary" && snap.companion.chat.title === "秘书" && snap.companion.name === "小处", "companion defaults to the secretary chat (S3 target), name from its role card");

  // ---------- mood ----------
  const { moodOf, snapshotToText } = require(path.join(DIST, "shared/format.js"));
  let mood = moodOf(snap);
  assert(mood.score === 50 && mood.name === "担心", `mood: 2/4 off (+25) + drift (+15) + no progress after 11:00 (+10) = 50 担心 (got ${mood.score})`);
  assert(mood.reasons.some((r) => r.includes("近 1 小时 4 次采样有 2 次")) && mood.reasons.some((r) => r.includes("偏移 1 次")), "reasons explain the score");
  assert(mood.line.includes("求职投递") && mood.next.includes("追问"), "line in her voice; next step from EXEC_RULES (A2 追问)");
  const aiText = snapshotToText(snap);
  assert(aiText.includes("陪伴状态：担心") && aiText.includes("今日专注") && aiText.includes("系统体检"), "AI snapshot includes mood, focus and health");

  const { computeMood } = require(path.join(DIST, "shared/mood.js"));
  const nowSec = Math.floor(now / 1000);
  const m2 = computeMood({ now, task: "求职投递", focus: f, progress: [{ ts: nowSec - 600, kind: "stuck", user_quote: "简历写不下去", type: "USER_PROGRESS" }], checkins: [], execRules: null });
  assert(m2.score === 55 && m2.line.includes("简历写不下去"), "stuck adds 15 and changes her line to ask about it");
  const m3 = computeMood({ now, task: "求职投递", focus: f, progress: [{ ts: nowSec - 600, kind: "pause", user_quote: "去吃饭", type: "USER_PROGRESS" }], checkins: [], execRules: null });
  assert(m3.score === 20 && m3.name === "在意", "pause with a reason relieves 20");

  // ---------- check-in decision ----------
  const { decideCheckin } = require(path.join(DIST, "shared/checkin.js"));
  const at = (h, m) => { const x = new Date(now); x.setHours(h, m, 0, 0); return x.getTime(); };
  assert(decideCheckin(at(23, 45), 2, null, null) === "SKIP_QUIET" && decideCheckin(at(7, 59), 2, null, null) === "SKIP_QUIET", "quiet hours 23:30–08:00");
  assert(decideCheckin(at(14, 0), 1, { ts: Math.floor(at(13, 30) / 1000) }, null) === "SKIP_COOLDOWN", "45-minute cooldown");
  assert(decideCheckin(at(14, 0), 0, null, Math.floor(at(13, 30) / 1000)) === "SKIP_FINE", "doing fine + recent progress -> leave them alone");
  assert(decideCheckin(at(14, 0), 1, null, null) === "GO", "otherwise GO");

  // ---------- check_in tool ----------
  const nav = require(path.join(DIST, "packages/focus_hub_nav.js"));
  calls.length = 0;
  const ci = await nav.check_in({});
  const tts = calls.find((c) => c[0] === "tts");
  const intent = calls.find((c) => c[0] === "intent");
  assert(ci.status === "CHECKED_IN" && ci.speak === "ACCEPTED" && ci.popup === "ACCEPTED", "check_in speaks and pops up");
  assert(tts && tts[1] === mood.line && intent[1].extras["com.ai.assistance.operit.extra.OPEN_ROUTE_ID"] === "toolpkg:local.focus_hub:ui:focus_hub", "same line is spoken; popup opens the hub route");
  const ckPath = `${P}/companion/checkins/${today}.jsonl`;
  const ckRec = JSON.parse(FILES[ckPath].trim());
  assert(ckRec.type === "CHECKIN" && ckRec.level === 2 && ckRec.trigger === "workflow", "check-in logged with level and trigger");
  assert(ci.message.includes("ACCEPTED 只代表已发出"), "result is honest about ACCEPTED vs seen");
  const again = await nav.check_in({});
  assert(again.status === "SKIP_COOLDOWN", "second check-in right away is skipped");
  const forced = await nav.check_in({ force: "true", speak: "false", message: "我在呢" });
  assert(forced.status === "CHECKED_IN" && forced.speak === "SKIPPED" && forced.line === "我在呢", "force + custom message + speak=false (string flags from workflows)");
  ttsFails = true;
  const failed = await nav.check_in({ force: true });
  const lastCk = JSON.parse(FILES[ckPath].trim().split("\n").pop());
  assert(failed.speak === "FAILED" && lastCk.speak_error === "Unknown error" && failed.message.includes("Unknown error"), "TTS failure is recorded with the host's error text");
  ttsFails = false;

  // ---------- progress ----------
  const prog = require(path.join(DIST, "packages/focus_hub_progress.js"));
  const progPath = `${P}/progress/${today}.jsonl`;
  const bad = await prog.report_progress({ kind: "maybe", user_quote: "x" });
  assert(!bad.success && !(progPath in FILES), "rejects unknown kind without writing");

  // ---------- nav tools ----------
  calls.length = 0;
  const r2 = await nav.open_chat({ chat_id: "nope" });
  assert(!r2.success && !calls.some((c) => c[0] === "mgr.setCurrentChatId"), "open_chat refuses unknown chat ids");

  // ---------- UI: today ----------
  const Screen = require(path.join(DIST, "ui/focus_hub/index.ui.js")).default;
  const ctx = makeCtx();
  let tree = Screen(ctx);
  await tree.props.onLoad();
  tree = Screen(ctx);
  assert(has(tree, "小处") && has(tree, "担心") && has(tree, "为什么是「担心」"), "her card: name, mood, reasons");
  assert(has(tree, `${new Date(forced ? now : now).getHours()}:30 小处来找过你`) || has(tree, "小处来找过你"), "pending check-in banner shows");
  assert(has(tree, "系统 · 4 处要看"), "system chip counts stale health (1) + attention workflows (3)");
  assert(findAll(tree, (n) => n.type === "Box" && n.props.height === 24).length === 24, "24-hour strip");
  assert(has(tree, "采样在任务上") && has(tree, "86%"), "stats: 18/21 on task");

  // pause needs a reason
  await clickable(tree, "暂停").props.onClick();
  tree = Screen(ctx);
  calls.length = 0;
  await clickable(tree, "记下「暂停」").props.onClick();
  assert(ctx.toasts.some((t) => t.includes("暂停要写一句为什么")) && !calls.some((c) => c[0] === "write"), "pause without a reason is refused");

  // one-tap 继续 with empty text
  await clickable(Screen(ctx), "继续").props.onClick();
  await clickable(Screen(ctx), "记下「继续」").props.onClick();
  let lines = FILES[progPath].trim().split("\n").map((l) => JSON.parse(l));
  assert(lines[0].user_quote === "（点了「继续」）" && lines[0].via === "dashboard" && lines[0].note.startsWith("回应"), "one tap records an honest quote and answers the check-in");
  tree = Screen(ctx);
  assert(!has(tree, "来找过你"), "banner disappears once answered");

  // 提交 with text lowers the mood
  await clickable(tree, "提交").props.onClick();
  ctx.state.set("progressText", "投了两家");
  await clickable(Screen(ctx), "记下「提交」").props.onClick();
  tree = Screen(ctx);
  const moodNow = moodOf(ctx.state.get("snap"));
  assert(moodNow.score < 50 && has(tree, "「投了两家」"), `recording progress lowers her mood (${moodNow.score}) and shows in the day log`);

  // her buttons
  calls.length = 0;
  await clickable(tree, "找她聊").props.onClick();
  assert(JSON.stringify(calls.filter((c) => c[0].startsWith("mgr.") || c[0] === "navigate").map((c) => [c[0], c[1]])) === JSON.stringify([["mgr.chatExists", "d20b4e22-f6ab-4766-bc83-54e96c99bb44"], ["mgr.setCurrentChatId", "d20b4e22-f6ab-4766-bc83-54e96c99bb44"], ["navigate", "native.ai_chat"]]), "找她聊 opens the secretary chat in the main screen");
  assert(has(Screen(ctx), "已请求打开对话"), "status line records the result");
  calls.length = 0;
  floatingServiceRunning = false;
  await clickable(tree, "🎙 语音聊").props.onClick();
  const seq = calls.filter((c) => ["startService", "switchTo"].includes(c[0]));
  assert(seq[0][0] === "startService" && seq[0][1].initial_mode === "VOICE_BALL" && seq[1][1] === "d20b4e22-f6ab-4766-bc83-54e96c99bb44" && has(Screen(ctx), "已请求打开语音球"), "语音聊 starts the voice ball, switches the floating chat to her, status shown");
  tree = Screen(ctx);
  assert(!has(tree, "让她细说") && !has(tree, "她细说的"), "让她细说 removed");
  assert(has(tree, "她 = 「秘书」"), "card says which chat she is");

  // ---------- UI: chats ----------
  await clickable(tree, "会话").props.onClick();
  tree = Screen(ctx);
  assert(has(tree, "她（主入口）") && has(tree, "后台角色") && has(tree, "最近 30 个"), "chats grouped: her / backstage / recent");
  const herGroup = find(tree, (n) => n.type === "Card" && texts(n).includes("她（主入口）"));
  const backGroup = find(tree, (n) => n.type === "Card" && texts(n).includes("后台角色"));
  assert(texts(herGroup).includes("秘书") && !texts(herGroup).includes("陪伴窗"), "the secretary is the only main entry");
  assert(texts(backGroup).includes("陪伴窗") && texts(backGroup).includes("温柔巡检") && texts(backGroup).includes("判断官"), "other roles listed as backstage");
  const chooseBtn = (g) => find(g, (n) => n.type === "TextButton" && texts(n).includes("设为她"));
  assert(!chooseBtn(herGroup) && chooseBtn(backGroup), "设为她 offered on other chats only");

  // choose 陪伴窗 instead
  const peiRow = find(backGroup, (n) => n.type === "Surface" && texts(n).includes("陪伴窗"));
  await find(peiRow, (n) => n.type === "TextButton" && texts(n).includes("设为她")).props.onClick();
  const cfg = JSON.parse(FILES[`${P}/companion/config.json`].trim());
  assert(cfg.chat_id === "ded96924", "设为她 writes companion/config.json");
  tree = Screen(ctx);
  const herGroup2 = find(tree, (n) => n.type === "Card" && texts(n).includes("她（主入口）"));
  assert(texts(herGroup2).includes("陪伴窗") && ctx.state.get("snap").companion.source === "chosen", "chosen chat becomes her");
  const fresh = await collectSnapshot();
  assert(fresh.companion.chat.id === "ded96924" && fresh.companion.source === "chosen", "choice survives a fresh snapshot (read from config)");
  assert(has(tree, "太长了"), "812-message chat flagged");

  // ---------- UI: system ----------
  await clickable(tree, "系统").props.onClick();
  tree = Screen(ctx);
  assert(has(tree, "体检") && has(tree, "判断官") && has(tree, "偏旧"), "health list");
  assert(has(tree, "全部 10 个") && !has(tree, "P1_Board_Start"), "workflows collapsed");
  await clickable(tree, "全部 10 个").props.onClick();
  tree = Screen(ctx);
  assert(has(tree, "P1_Board_Start") && has(tree, "失败率 37%"), "workflows expand; failure rate flagged");
  await clickable(tree, "展开").props.onClick();
  tree = Screen(ctx);
  assert(has(tree, "只用来呈现，不会执行动作"), "mood disclaimer in system details");

  console.log(failures === 0 ? "\nALL PASSED" : `\n${failures} FAILED`);
  process.exitCode = failures === 0 ? 0 : 1;
})();
