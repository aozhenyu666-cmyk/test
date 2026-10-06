"use strict";
// 六骰子思维训练引擎：掷骰、选素材、组卡片（步骤 + 卡住降级 + 元认知）、推送、记录。
// 六面并列随机；回响留在骰子里。标准路径来自用户与 AI 共同定下的“骰子思考法 v2.1”。
Object.defineProperty(exports, "__esModule", { value: true });
const materials_js_1 = require("./dice-materials.js");

const OPERIT = "/sdcard/Download/Operit/";
const DIR = OPERIT + "dice/";
exports.DIR = DIR;
exports.PATHS = {
    config: DIR + "config.json",
    state: DIR + "state.json",
    pool: DIR + "素材库.txt",
    logDir: DIR + "log/",
    thread: OPERIT + "core/thread.json",
    windows: OPERIT + "core/today_windows.txt",
};

exports.MODES = {
    1: { name: "精简总结", icon: "✂", color: "#5B8DEF", tint: "#E6EEFD", one: "把复杂变简单",
        steps: ["第一刀：一句话——它到底是什么？（去掉修饰、例子）", "第二刀：三个要点，只能三个", "第三刀：讲给十岁小孩听"],
        down: "先不管三刀，只用一句话说出它让你印象最深的一个点，再从这个点倒推回去。",
        pass: "三刀都有实质，第三刀真的够简单", retry: "要点不是三个，或第三刀还在用术语" },
    2: { name: "预判推演", icon: "🔭", color: "#8E6CEF", tint: "#EEE8FD", one: "在发生前看到分支",
        steps: ["找出两个会变化的关键变量", "变量一推两层因果：A → B → C", "变量二也推两层", "每条分支估个概率，说出理由"],
        down: "只找一个变量，只推一层；还卡就只回答：如果不做任何干预，它最可能变成什么？",
        pass: "至少推了两层因果", retry: "只推一层，或只说“可能变好/变坏”" },
    3: { name: "联想", icon: "🔗", color: "#E08A3C", tint: "#FCEEDF", one: "把一个特征嫁接到别处",
        steps: ["剥离一个可迁移的抽象特征（不是长得像）", "在另一个完全不同的领域找一个", "再换一个领域找一个", "这个类比在什么情况下会失效？"],
        down: "跳过抽象特征，先说它让你想到的第一个东西，再问：它们有什么共同的运行规则？",
        pass: "特征够抽象，两个匹配跨了不同领域", retry: "特征只是原事物的标签，或同一领域类比" },
    4: { name: "提问", icon: "❓", color: "#D9534F", tint: "#FBE5E4", one: "对任何事追问到底",
        steps: ["事实问：真的吗？证据是什么？", "假设问：藏了什么没说的前提？", "反面问：反过来成立吗？有反例吗？", "边界问：什么情况下它不再成立？", "行动问：所以呢？我现在该做什么？"],
        down: "只做前三问；还卡就只做事实问和反面问。",
        pass: "五问都答了，反例和边界都问了", retry: "“不知道”没尝试，或跳过了反例" },
    5: { name: "知识体系", icon: "🗺", color: "#2E9E8F", tint: "#DFF3F0", one: "给知识找到家族",
        steps: ["定位：它在哪个领域、哪个层级？", "上位：它属于什么更大的概念？", "平行：和它同级但不同的有什么？", "下位：它下面包含什么更小的？", "把这几个串成一张关系图，说出来"],
        down: "只做定位和上位；还卡就只回答：把它放进一个文件夹，文件夹叫什么？",
        pass: "三条连接都找到，地图有逻辑", retry: "只有罗列没有关系，找不到平行概念" },
    6: { name: "回响", icon: "🔁", color: "#7A8B99", tint: "#EAEFF2", one: "把学过的东西拉回来",
        steps: ["自由回忆：不翻记录，凭记忆说出至少 3 条", "对比校准：翻记录，标出漏了什么、记错了什么", "串联叙述：把它们串成一段连贯的话"],
        down: "只回忆一条，然后翻记录对比，把这一条的差距写出来，就算通过。",
        pass: "至少回忆起两条，串联有逻辑", retry: "几乎全忘，或串联是硬凑" },
};

exports.DEFAULT_CONFIG = {
    enabled: false,          // 默认关闭，在主控台“骰子”页打开
    interval_min: 10,        // 5 / 10 / 15 / 30
    delivery: "both",        // chat / notify / both
    ai_example: true,        // 让对话里的 AI 给一个示范开头
    active_start: "08:30",
    active_end: "22:30",
    yield_to_sinan: true,    // 司南工作段里你在做事时，骰子让路
    rotate_after: 10,        // 对话里每 10 张卡换一个新对话，清掉上下文
    delete_old_chats: true,  // 只删除骰子自己建的旧对话
    fusion_every: 3,         // 每 3 张卡有一张“融合卡”：先问元认知两问，素材取自你手头的事
    coach_card_name: "骰子教练",
};

function bj(now) {
    const d = new Date(now + 8 * 3600 * 1000);
    const p = (n) => (n < 10 ? "0" + n : String(n));
    return { day: `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`, min: d.getUTCHours() * 60 + d.getUTCMinutes(), hhmm: `${p(d.getUTCHours())}:${p(d.getUTCMinutes())}` };
}
exports.bj = bj;
function toMin(s) { const m = /^(\d{1,2}):(\d{2})$/.exec(String(s || "").trim()); return m ? Number(m[1]) * 60 + Number(m[2]) : -1; }

async function readText(path) {
    try { const r = await Tools.Files.read(path); return String((r && r.content) || ""); } catch (e) { return ""; }
}
async function readJson(path, fallback) {
    const t = await readText(path);
    if (!t.trim()) return fallback;
    try { return JSON.parse(t); } catch (e) { return fallback; }
}
async function writeJson(path, value) {
    await Tools.Files.write(path, JSON.stringify(value, null, 2) + "\n", false);
}
async function loadConfig() { return { ...exports.DEFAULT_CONFIG, ...(await readJson(exports.PATHS.config, {})) }; }
exports.loadConfig = loadConfig;
async function saveConfig(patch) {
    const cfg = { ...(await loadConfig()), ...(patch || {}) };
    await writeJson(exports.PATHS.config, cfg);
    return cfg;
}
exports.saveConfig = saveConfig;
function emptyState() { return { day: "", sent_today: 0, stats: {}, used: [], last_sent_ms: 0, card: null, chat_id: "", chat_round: 0, in_chat: 0, own_chats: [], total: 0 }; }
async function loadState(now) {
    const s = { ...emptyState(), ...(await readJson(exports.PATHS.state, {})) };
    const day = bj(now).day;
    if (s.day !== day) { s.day = day; s.sent_today = 0; s.stats = {}; s.used = []; }
    return s;
}
exports.loadState = loadState;

// 用户素材：每行“[模式] 内容”，模式可写数字或名字；没写模式的行任何一面都能用
function parsePool(text) {
    const byName = {}; Object.keys(exports.MODES).forEach((k) => { byName[exports.MODES[k].name] = Number(k); });
    const out = [];
    String(text || "").split("\n").forEach((raw, i) => {
        const line = raw.trim();
        if (!line || line.startsWith("#")) return;
        const m = /^\[([^\]]+)\]\s*(.+)$/.exec(line);
        let mode = 0, body = line;
        if (m) { const tag = m[1].replace(/[🎲·\s]/g, ""); mode = Number(tag) || byName[tag] || 0; body = m[2]; }
        out.push({ mode, level: "我的", text: body, id: "u" + i });
    });
    return out;
}
exports.parsePool = parsePool;

function pick(list, rng) { return list[Math.floor(rng() * list.length) % list.length]; }

// 组一张训练卡。fusion=true 时先问元认知两问，素材取自当前手头的事。
function compose(mode, material, opts) {
    const M = exports.MODES[mode];
    const o = opts || {};
    const head = o.fusion
        ? ["先停一下，回答两句：", "① 我现在在干嘛？（诚实一句话）", "② 下一个动作是什么？", "然后用这一面分析你手头的事。"]
        : ["先问自己：我现在这一步，要弄清什么或做出什么？"];
    const lines = [`🎲${mode}·${M.name}｜${M.one}`, `素材：${material.text}`, ...head, ...M.steps.map((s, i) => `${i + 1}. ${s}`), `→ 卡住了？降级：${M.down}`, "做完回我一句；用了降级就说“降级”，想跳过就说“跳过”。"];
    const coach = o.ai_example
        ? `\n（给教练：先用不超过 80 字示范一个开头，不要替我做完；等我回答后，对照“通过：${M.pass}／重来：${M.retry}”只指出最关键的一处，让我改一次。）`
        : "";
    return {
        mode, name: M.name, icon: M.icon, fusion: !!o.fusion, material: material.text, level: material.level,
        steps: M.steps, down: M.down, title: `🎲${mode}·${M.name}`,
        text: lines.join("\n") + coach,
        short: `${M.name}｜${material.text.slice(0, 40)}${material.text.length > 40 ? "…" : ""}`,
    };
}
exports.compose = compose;

async function currentTaskMaterial() {
    const t = await readText(exports.PATHS.thread);
    try {
        const th = JSON.parse(t);
        const bits = [th.task, th.last_step, th.question].filter((x) => x && String(x).trim());
        if (bits.length) return { mode: 0, level: "手头的事", text: bits.join("；"), id: "thread" };
    } catch (e) { }
    return null;
}

// 掷一次：返回卡片（不推送）。forceMode 可指定 1-6。
async function roll(now, options) {
    const o = options || {};
    const rng = o.rng || Math.random;
    const cfg = o.config || (await loadConfig());
    const st = o.state || (await loadState(now));
    const mode = o.forceMode >= 1 && o.forceMode <= 6 ? o.forceMode : 1 + Math.floor(rng() * 6) % 6;
    const fusion = !!o.fusion || (cfg.fusion_every > 0 && (st.total + 1) % cfg.fusion_every === 0);
    let material = null;
    if (fusion) material = await currentTaskMaterial();
    if (!material) {
        const pool = [...materials_js_1.BUILTIN.map((m, i) => ({ ...m, id: "b" + i })), ...parsePool(await readText(exports.PATHS.pool))];
        const fit = pool.filter((m) => (m.mode === mode || m.mode === 0) && !st.used.includes(m.id));
        material = pick(fit.length ? fit : pool.filter((m) => m.mode === mode || m.mode === 0), rng);
    }
    return { card: compose(mode, material, { fusion: fusion && material.id === "thread", ai_example: cfg.ai_example }), material, cfg, st };
}
exports.roll = roll;

// 司南工作段里、你又在做事时，骰子让路
async function sinanBusy(now) {
    const t = bj(now);
    const lines = (await readText(exports.PATHS.windows)).split("\n").map((l) => l.trim());
    if (!lines.includes("date=" + t.day)) return false;
    const inWindow = lines.some((l) => { const p = l.split("|"); return p.length >= 3 && t.min >= toMin(p[1]) && t.min < toMin(p[2]); });
    if (!inWindow) return false;
    try { const th = JSON.parse(await readText(exports.PATHS.thread)); return !["resting", "paused", "idle"].includes(th.status); } catch (e) { return true; }
}
exports.sinanBusy = sinanBusy;

async function findCoachCardId(name) {
    try { const r = await Tools.Chat.listCharacterCards(); const c = (r.cards || []).find((x) => x.name === name); return c ? c.id : ""; } catch (e) { return ""; }
}

// 确保有一个训练对话；每 rotate_after 张卡换新对话，清掉上下文
async function ensureChat(cfg, st, now) {
    if (st.chat_id && st.in_chat < cfg.rotate_after) return st.chat_id;
    const cardId = await findCoachCardId(cfg.coach_card_name);
    const created = await Tools.Chat.createNew("骰子训练", false, cardId || undefined);
    const id = String((created && (created.chatId || created.id)) || "");
    if (!id) throw new Error("没能新建骰子对话");
    st.chat_round += 1;
    try { await Tools.Chat.updateTitle(id, `骰子训练·第${st.chat_round}轮（自动清理）`); } catch (e) { }
    if (cfg.delete_old_chats && st.chat_id && st.own_chats.includes(st.chat_id)) {
        try { await Tools.Chat.deleteChat(st.chat_id); } catch (e) { }
        st.own_chats = st.own_chats.filter((x) => x !== st.chat_id);
    }
    st.own_chats.push(id);
    st.chat_id = id;
    st.in_chat = 0;
    return id;
}

async function deliver(card, cfg, st, now) {
    const done = [];
    if (cfg.delivery === "notify" || cfg.delivery === "both") {
        try { await Tools.System.sendNotification(card.short + "\n" + card.steps[0], "🎲 " + card.title); done.push("notify"); } catch (e) { done.push("notify_failed"); }
    }
    if (cfg.delivery === "chat" || cfg.delivery === "both") {
        const id = await ensureChat(cfg, st, now);
        try { await Tools.Chat.startService(); } catch (e) { }
        await Tools.Chat.sendMessage(card.text, id, undefined, "骰子", { persist_turn: true, notify_reply: true, timeout_ms: 120000 });
        st.in_chat += 1;
        done.push("chat");
    }
    return done;
}

async function appendLog(now, record) {
    const day = bj(now).day;
    await Tools.Files.write(exports.PATHS.logDir + day + ".jsonl", JSON.stringify({ ts: bj(now).hhmm, ...record }) + "\n", true);
}
exports.appendLog = appendLog;

// 节拍：工作流每 5 分钟调用一次，自己按间隔和时段决定要不要掷
async function tick(now, options) {
    const o = options || {};
    const cfg = await loadConfig();
    const st = await loadState(now);
    const t = bj(now);
    const skip = async (why) => { await writeJson(exports.PATHS.state, st); return { sent: false, reason: why }; };
    if (!cfg.enabled && !o.force) return skip("disabled");
    if (!o.force && (t.min < toMin(cfg.active_start) || t.min >= toMin(cfg.active_end))) return skip("outside_hours");
    if (!o.force && now - st.last_sent_ms < cfg.interval_min * 60000 - 30000) return skip("not_due");
    if (!o.force && cfg.yield_to_sinan && (await sinanBusy(now))) return skip("yield_to_sinan");
    const r = await roll(now, { config: cfg, state: st, rng: o.rng, forceMode: o.forceMode, fusion: o.fusion });
    const delivered = await deliver(r.card, cfg, st, now);
    st.used.push(r.material.id);
    st.card = { ...r.card, at: t.hhmm, result: "" };
    st.last_sent_ms = now; st.sent_today += 1; st.total += 1;
    const k = String(r.card.mode); st.stats[k] = st.stats[k] || { sent: 0, done: 0, down: 0, skip: 0 }; st.stats[k].sent += 1;
    await writeJson(exports.PATHS.state, st);
    await appendLog(now, { kind: "sent", mode: r.card.mode, fusion: r.card.fusion, material: r.material.text, delivered });
    return { sent: true, card: r.card, delivered };
}
exports.tick = tick;

// 记录这一张卡的结果：done / down（降级完成）/ skip，附一句话
async function record(now, result, note) {
    const st = await loadState(now);
    if (!st.card) return { ok: false, message: "现在没有待完成的卡" };
    const k = String(st.card.mode);
    st.stats[k] = st.stats[k] || { sent: 0, done: 0, down: 0, skip: 0 };
    if (["done", "down", "skip"].includes(result)) st.stats[k][result] += 1;
    st.card.result = result;
    await writeJson(exports.PATHS.state, st);
    await appendLog(now, { kind: "result", mode: st.card.mode, result, note: String(note || "").slice(0, 500), who: "user" });
    return { ok: true, stats: st.stats };
}
exports.record = record;

// 今天的统计，给主控台、司南晚上回顾用
async function summary(now) {
    const st = await loadState(now);
    const rows = Object.keys(exports.MODES).map((k) => ({ mode: Number(k), name: exports.MODES[k].name, ...(st.stats[k] || { sent: 0, done: 0, down: 0, skip: 0 }) }));
    const done = rows.reduce((a, r) => a + r.done + r.down, 0);
    return { day: st.day, sent: st.sent_today, done, rows, card: st.card, chat_id: st.chat_id, total: st.total };
}
exports.summary = summary;
