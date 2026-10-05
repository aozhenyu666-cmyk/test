"use strict";
// 司南的读写与模型调用。状态在 companion/sinan/state.json，对话按天记在 log-日期.jsonl。
// 用户的话逐字记下；模型用 Tools.Chat.call（和 cognitive_continuity 后台用的是同一个宿主接口）；
// 调不通就用本地问题兜底，并如实标记 via=local。
Object.defineProperty(exports, "__esModule", { value: true });
exports.NATIVE_TITLE = exports.SINAN_DIR = void 0;
exports.loadSinan = loadSinan;
exports.readThread = readThread;
exports.begin = begin;
exports.say = say;
exports.switchMode = switchMode;
exports.sprint = sprint;
exports.pause = pause;
exports.resume = resume;
exports.finish = finish;
exports.note = note;
exports.ensureNativeChat = ensureNativeChat;
exports.NATIVE_PERSONA = void 0;
const L = require("./sinan_logic.js");
const gate_js_1 = require("./gate.js");
exports.SINAN_DIR = "/sdcard/Download/Operit/companion/sinan";
const STATE_PATH = `${exports.SINAN_DIR}/state.json`;
const LINE_NUMBER_PREFIX = /^\s*\d+\| ?/;
const THREAD_LINES = 60;
exports.NATIVE_TITLE = "司南";
function errorText(error) {
    return error && typeof error === "object" && "message" in error ? String(error.message) : String(error);
}
function stripNumbers(content) {
    const lines = String(content || "").split("\n");
    const numbered = lines.some((line) => LINE_NUMBER_PREFIX.test(line));
    return numbered ? lines.filter((line) => LINE_NUMBER_PREFIX.test(line)).map((line) => line.replace(LINE_NUMBER_PREFIX, "")) : lines;
}
// 北京时间的日期，日志按这个分文件
function dayOf(ms) {
    return new Date(ms + 8 * 3600000).toISOString().slice(0, 10);
}
function logPath(ms) {
    return `${exports.SINAN_DIR}/log-${dayOf(ms)}.jsonl`;
}
async function readState() {
    try {
        const exists = await Tools.Files.exists(STATE_PATH);
        if (!exists || !exists.exists)
            return L.freshState();
        const part = await Tools.Files.readPart(STATE_PATH, 1, 2000);
        return L.validate(JSON.parse(stripNumbers(part.content).join("\n")));
    }
    catch {
        return L.freshState();
    }
}
async function saveState(state) {
    await Tools.Files.write(STATE_PATH, `${JSON.stringify(state)}\n`, false);
}
async function append(entry) {
    await Tools.Files.write(logPath(entry.ts), `${JSON.stringify(entry)}\n`, true);
}
// 今天的最近几十行；只看当前这件事的（没有在做的事就看今天全部）
async function readThread(taskId, now = Date.now()) {
    const path = logPath(now);
    try {
        const exists = await Tools.Files.exists(path);
        if (!exists || !exists.exists)
            return [];
        const probe = await Tools.Files.readPart(path, 1, 1);
        const total = Number(probe.totalLines) || 0;
        if (total <= 0)
            return [];
        const part = await Tools.Files.readPart(path, Math.max(1, total - THREAD_LINES + 1), total);
        const rows = [];
        for (const line of stripNumbers(part.content)) {
            if (!line.trim())
                continue;
            try {
                rows.push(JSON.parse(line));
            }
            catch {
                // 跳过写坏的一行
            }
        }
        return taskId ? rows.filter((r) => r.task_id === taskId) : rows;
    }
    catch {
        return [];
    }
}
async function focusCtx(now) {
    try {
        return (0, gate_js_1.focusNow)(await (0, gate_js_1.loadGate)(), now);
    }
    catch {
        return null;
    }
}
async function loadSinan(now = Date.now()) {
    const state = await readState();
    const thread = await readThread(state.task ? state.task.id : null, now);
    return { ...L.view(state, now), thread, focus: await focusCtx(now) };
}
// 问模型；失败就本地兜底。返回 {reply, question, via, error?}
async function ask(state, history, userText, now) {
    const ctx = { focus: await focusCtx(now), now };
    if (!Tools.Chat || typeof Tools.Chat.call !== "function")
        return { ...L.fallbackReply(state), via: "local", error: "这个 Operit 版本没有模型接口（Tools.Chat.call）" };
    try {
        const r = await Tools.Chat.call({ functionType: "CHAT", recordTokenUsage: true, turns: L.buildTurns(state, history, userText, ctx) });
        const parsed = L.parseReply(r && r.text);
        if (!parsed)
            throw new Error("模型没有返回文字");
        return { ...parsed, via: "model" };
    }
    catch (error) {
        return { ...L.fallbackReply(state), via: "local", error: errorText(error) };
    }
}
async function aiTurn(state, history, prompt, now) {
    const out = await ask(state, history, prompt, now);
    L.recordAi(state, out, now);
    await append({ ts: now, role: "ai", mode: state.mode, task_id: state.task ? state.task.id : null, text: out.reply, question: out.question, via: out.via, ...(out.error ? { error: out.error } : {}) });
    return out;
}
// 开始一件事：记下原话，请司南提第一个问题
async function begin(title, material, now = Date.now()) {
    const state = await readState();
    L.beginTask(state, title, material, now);
    await append({ ts: now, role: "event", type: "begin", mode: state.mode, task_id: state.task.id, text: state.task.title });
    const out = await aiTurn(state, [], `我这段时间要做：${state.task.title}。${L.MODES[state.mode].kickoff}`, now);
    await saveState(state);
    return out;
}
// 说一句：原话入账 → 司南接着说
async function say(text, now = Date.now()) {
    const t = String(text || "").trim();
    if (!t)
        throw new Error("先写点什么");
    const state = await readState();
    if (!state.task)
        return begin(t, "", now);
    const history = await readThread(state.task.id, now);
    L.recordUser(state, now);
    if (state.sprint && state.sprint.ends_at <= now)
        state.sprint.reported = true;
    await append({ ts: now, role: "user", mode: state.mode, task_id: state.task.id, text: t });
    const out = await aiTurn(state, history, t, now);
    if (out.via === "model" && L.needsMemo(state))
        await compress(state, history.concat([{ role: "user", text: t }, { role: "ai", text: out.reply }]), now);
    await saveState(state);
    return out;
}
// 备忘失败不影响对话，下次再试
async function compress(state, history, now) {
    try {
        const r = await Tools.Chat.call({ functionType: "CHAT", recordTokenUsage: true, turns: L.memoTurns(state, history) });
        if (r && typeof r.text === "string" && L.applyMemo(state, r.text, now))
            await append({ ts: now, role: "event", type: "memo", mode: state.mode, task_id: state.task.id, text: "整理了一次备忘" });
    }
    catch {
        // 忽略
    }
}
// 换模式：司南用新模式的方式开口（开场请求不算用户的话）
async function switchMode(mode, now = Date.now()) {
    const state = await readState();
    L.setMode(state, mode, now);
    if (!state.task) {
        await saveState(state);
        return null;
    }
    await append({ ts: now, role: "event", type: "mode", mode, task_id: state.task.id, text: L.MODES[mode].name });
    // 歇着的时候只换模式，回来时再按新模式开口，不连发问题
    if (state.task.paused_at) {
        await saveState(state);
        return null;
    }
    const history = await readThread(state.task.id, now);
    const out = await aiTurn(state, history, L.kickoffText(mode), now);
    await saveState(state);
    return out;
}
async function sprint(goal, minutes, now = Date.now()) {
    const state = await readState();
    if (!state.task)
        L.beginTask(state, goal, "", now);
    L.startSprint(state, goal, minutes, now);
    await append({ ts: now, role: "event", type: "sprint", mode: "sprint", task_id: state.task.id, text: `${state.sprint.minutes} 分钟：${state.sprint.goal}` });
    const history = await readThread(state.task.id, now);
    const out = await aiTurn(state, history, `【冲刺开始】${state.sprint.minutes} 分钟，我要交出：${state.sprint.goal}。确认一下标准，然后让我开始。`, now);
    await saveState(state);
    return out;
}
async function pause(now = Date.now()) {
    const state = await readState();
    L.pauseTask(state, now);
    await append({ ts: now, role: "event", type: "pause", mode: state.mode, task_id: state.task.id, text: "歇一会儿" });
    await saveState(state);
}
async function resume(now = Date.now()) {
    const state = await readState();
    const wasPaused = !!(state.task && state.task.paused_at);
    L.resumeTask(state, now);
    let out = null;
    if (state.task && wasPaused) {
        await append({ ts: now, role: "event", type: "resume", mode: state.mode, task_id: state.task.id, text: "回来了" });
        const history = await readThread(state.task.id, now);
        out = await aiTurn(state, history, `【我回来了】接着刚才的地方，${L.MODES[state.mode].kickoff}`, now);
    }
    await saveState(state);
    return out;
}
async function finish(now = Date.now()) {
    const state = await readState();
    if (state.task)
        await append({ ts: now, role: "event", type: "finish", mode: state.mode, task_id: state.task.id, text: state.task.title });
    L.finishTask(state, now);
    await saveState(state);
}
// 原生对话（语音）里的司南把用户原话和它的问题记回同一条线
async function note(userText, aiQuestion, now = Date.now()) {
    const state = await readState();
    const t = String(userText || "").trim();
    if (!state.task)
        throw new Error("主控台里还没有在做的事");
    if (t) {
        L.recordUser(state, now);
        await append({ ts: now, role: "user", mode: state.mode, task_id: state.task.id, text: t, via: "voice" });
    }
    const q = String(aiQuestion || "").trim();
    if (q) {
        L.recordAi(state, { reply: q, question: q }, now);
        await append({ ts: now, role: "ai", mode: state.mode, task_id: state.task.id, text: q, question: q, via: "voice" });
    }
    await saveState(state);
    return L.view(state, now);
}
// ---------- 语音：在 Operit 原生对话里和司南说 ----------
exports.NATIVE_PERSONA = [
    "你是司南，用户自己请来的严格搭档，和主控台里的司南是同一个。你只帮他把眼前这件事想清楚、推下去。",
    "说话：中文口语，短，一次只问一个问题；不用“您”、不用颜文字、不念系统字段。",
    "1. 每次对话开始先调用 focus_hub_nav:sinan_context，看他在做什么、当前问题、模式、是不是专注时段。",
    "2. 他回答问题时，调用 focus_hub_nav:sinan_note，user_text 逐字填他的原话，ai_question 填你接下来要问的那一个问题；然后把这个问题说出来。闲聊和提问不要记。",
    "3. 不替他想完：先提示，再示范一半让他补。他说“懂了”，追一个小问题检验。",
    "4. 他想去刷 App：问他这件事停在哪、回来第一步做什么，告诉他去主控台回答三个问题拿钥匙。你不发钥匙、不改规则。",
    "5. 还没有在做的事：问他这段时间做什么，让他在主控台开始。",
].join("\n");
function cardsOf(r) {
    const list = r && (r.cards || r.characterCards || r.items);
    return Array.isArray(list) ? list : [];
}
// 一键准备：找标题为「司南」的对话；没有就建一张「司南」角色卡（只给 focus_hub_nav 工具）和一个绑定它的对话
async function ensureNativeChat() {
    const found = await Tools.Chat.listChats({ query: exports.NATIVE_TITLE, match: "exact", limit: 5 });
    const hit = ((found && found.chats) || []).find((c) => String(c.title || "").trim() === exports.NATIVE_TITLE);
    if (hit)
        return { chatId: hit.id, created: false };
    const S = Tools.SoftwareSettings;
    if (!S || typeof S.listCharacterCards !== "function" || typeof S.createCharacterCard !== "function" || typeof Tools.Chat.createNew !== "function")
        throw new Error("这个 Operit 版本不能自动建角色卡：请手动新建角色卡「司南」，新建对话标题写「司南」并绑定它");
    let card = cardsOf(await S.listCharacterCards()).find((c) => c.name === exports.NATIVE_TITLE);
    if (!card) {
        const r = await S.createCharacterCard({
            name: exports.NATIVE_TITLE,
            description: "主控台的严格搭档（由主控台创建）",
            character_setting: exports.NATIVE_PERSONA,
            opening_statement: "",
            tool_access_enabled: true,
            allowed_packages: ["focus_hub_nav"],
        });
        card = r && r.card;
    }
    if (!card || !card.id)
        throw new Error("角色卡「司南」没建成");
    const created = await Tools.Chat.createNew(undefined, false, card.id);
    const chatId = created && created.chatId;
    if (!chatId)
        throw new Error("对话没建成");
    if (typeof Tools.Chat.updateTitle === "function")
        await Tools.Chat.updateTitle(chatId, exports.NATIVE_TITLE);
    return { chatId, created: true };
}
