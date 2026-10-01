"use strict";
/* METADATA
{
    "name": "thought_relay",
    "version": "0.1.0",
    "display_name": {
        "zh": "思考接续",
        "en": "Thought Relay"
    },
    "description": {
        "zh": "保存并恢复“当前思考现场”：问题—上一步—下一步—暂存区。每轮只推进一个动作，记录每一步用了多少帮助，中断后回到原返回点。数据只追加写入 /sdcard/Download/Operit/thought_relay/。",
        "en": "Keeps a resumable 'current thinking card' (question / last step / next step / parking lot), advances one action per turn, records help level per step, and restores the return point after interruptions. Append-only data under /sdcard/Download/Operit/thought_relay/."
    },
    "author": ["aozhenyu666-cmyk"],
    "category": "Productivity",
    "tools": [
        {
            "name": "get_card",
            "description": { "zh": "读取当前思考卡（无进行中的题目时列出最近题目，供接回而不是重开）。", "en": "Read the current thinking card; lists recent threads when none is active." },
            "parameters": [
                { "name": "thread_id", "description": { "zh": "可选：指定题目 ID，默认当前题目", "en": "Optional thread id (default: active)" }, "type": "string", "required": false }
            ]
        },
        {
            "name": "open_thread",
            "description": { "zh": "开一个新题目。已有进行中的题目时会拒绝，除非 confirm_switch=true（旧题自动暂停保留）。", "en": "Open a new thread. Refuses while another is active unless confirm_switch=true (old one is paused, not lost)." },
            "parameters": [
                { "name": "question", "description": { "zh": "正在想的那一个问题", "en": "The single question being worked on" }, "type": "string", "required": true },
                { "name": "next", "description": { "zh": "第一步：一个具体、能回答的小问题", "en": "First small, answerable step" }, "type": "string", "required": true },
                { "name": "materials", "description": { "zh": "可选：必要材料，用 | 分隔，每条一句", "en": "Optional key materials, separated by |" }, "type": "string", "required": false },
                { "name": "confirm_switch", "description": { "zh": "可选：用户明确要换题时传 true", "en": "Optional: true when the user explicitly switches" }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "record_step",
            "description": { "zh": "记下用户刚完成的一步，并设定下一步。下一步必须用到这一步的结果。", "en": "Record the step just completed and set the next one, which must build on it." },
            "parameters": [
                { "name": "text", "description": { "zh": "这一步的结果（用户说出的事实或推论，简短）", "en": "Result of this step, short" }, "type": "string", "required": true },
                { "name": "kind", "description": { "zh": "fact=补事实 / connect=连接两条信息 / infer=推出结果 / decide=决定核实", "en": "fact / connect / infer / decide" }, "type": "string", "required": true },
                { "name": "help", "description": { "zh": "本步用到的最高帮助：0=独立 1=提醒相关信息 2=给句子开头 3=AI示范", "en": "Highest help used: 0 none, 1 reminder, 2 sentence starter, 3 AI demo" }, "type": "number", "required": true },
                { "name": "next", "description": { "zh": "下一步：一个具体、能回答的小问题", "en": "Next small, answerable step" }, "type": "string", "required": true },
                { "name": "basis", "description": { "zh": "可选：支撑这一步的关键条件/依据", "en": "Optional supporting condition" }, "type": "string", "required": false },
                { "name": "judgement", "description": { "zh": "可选：当前暂时判断（例：暂时考虑投，因为职责符合）", "en": "Optional current tentative judgement" }, "type": "string", "required": false },
                { "name": "pending", "description": { "zh": "可选：还待确认的条件", "en": "Optional conditions still unconfirmed" }, "type": "string", "required": false },
                { "name": "stuck", "description": { "zh": "可选：本步之前卡在哪：material=材料没读进 forgot=忘了上一步 connect=不会连接 absent=离开了 refuse=不想做", "en": "Optional stuck type before this step" }, "type": "string", "required": false }
            ]
        },
        {
            "name": "set_next",
            "description": { "zh": "不记新步骤，只把下一步换小/换说法（如问题太大、材料太长）。", "en": "Reframe the next step without recording a step." },
            "parameters": [
                { "name": "next", "description": { "zh": "新的下一步", "en": "New next step" }, "type": "string", "required": true },
                { "name": "reason", "description": { "zh": "为什么换（简短）", "en": "Why (short)" }, "type": "string", "required": false },
                { "name": "stuck", "description": { "zh": "可选：卡住类型，同 record_step", "en": "Optional stuck type" }, "type": "string", "required": false }
            ]
        },
        {
            "name": "park",
            "description": { "zh": "暂存区：放下一个旁支念头/待查资料；或用 done 标记某条已处理。", "en": "Parking lot: add a side thought, or mark one done." },
            "parameters": [
                { "name": "note", "description": { "zh": "要暂存的内容", "en": "Note to park" }, "type": "string", "required": false },
                { "name": "done", "description": { "zh": "可选：标记第几条（从 1 开始）已处理", "en": "Optional 1-based index to mark done" }, "type": "number", "required": false }
            ]
        },
        {
            "name": "pause",
            "description": { "zh": "离开/切换任务/主动暂停时保存返回点。", "en": "Save the return point when leaving or pausing." },
            "parameters": [
                { "name": "reason", "description": { "zh": "可选：leave / switch / manual / refuse", "en": "Optional reason" }, "type": "string", "required": false },
                { "name": "return_after_min", "description": { "zh": "可选：约定多少分钟后再提醒", "en": "Optional minutes before reminding" }, "type": "number", "required": false }
            ]
        },
        {
            "name": "resume",
            "description": { "zh": "回来时调用。brief=只给返回点；help=“帮我恢复”，重放两三句必要信息。", "en": "Call on return. brief = return point only; help = short replay." },
            "parameters": [
                { "name": "mode", "description": { "zh": "brief（默认）或 help", "en": "brief (default) or help" }, "type": "string", "required": false },
                { "name": "thread_id", "description": { "zh": "可选：接回某个旧题目", "en": "Optional thread to resume" }, "type": "string", "required": false }
            ]
        },
        {
            "name": "reminder_check",
            "description": { "zh": "给定时工作流用：判断现在该不该提醒，返回 REMIND（带返回点文本）或 SKIP。", "en": "For scheduled workflows: returns REMIND with return-point text, or SKIP." },
            "parameters": [
                { "name": "min_interval_min", "description": { "zh": "可选：两次提醒最小间隔分钟，默认 30", "en": "Optional min minutes between reminders (default 30)" }, "type": "number", "required": false },
                { "name": "quiet_min", "description": { "zh": "可选：最近这么多分钟内有活动就不打扰，默认 10", "en": "Optional quiet window after activity (default 10)" }, "type": "number", "required": false }
            ]
        },
        {
            "name": "stats",
            "description": { "zh": "验收统计：连续步数、独立/提示/示范分开计、恢复耗时与提示次数。", "en": "Acceptance stats: connected steps, help levels, recovery time and hints." },
            "parameters": [
                { "name": "days", "description": { "zh": "可选：统计最近几天，默认 7", "en": "Optional days (default 7)" }, "type": "number", "required": false }
            ]
        },
        {
            "name": "close_thread",
            "description": { "zh": "结束当前题目（得出结论或明确放弃）。", "en": "Close the current thread." },
            "parameters": [
                { "name": "outcome", "description": { "zh": "结论或结束原因", "en": "Outcome or reason" }, "type": "string", "required": true }
            ]
        }
    ]
}
*/
const ThoughtRelay = (function () {
    let BASE = "/sdcard/Download/Operit/thought_relay";
    const HELP_LABEL = ["独立", "提示", "句子开头", "AI示范"];
    const KIND_LABEL = {
        fact: "补事实",
        connect: "连接两条信息",
        infer: "推出结果",
        decide: "决定核实",
    };
    const STUCK_LABEL = {
        material: "材料没读进去",
        forgot: "忘了上一步",
        connect: "知道材料但不会连接",
        absent: "中途离开",
        refuse: "不想做",
    };
    const MIN = 60 * 1000;
    // ---------- 基础 ----------
    function pad(n) {
        return n < 10 ? "0" + n : String(n);
    }
    function hhmm(ms) {
        const d = new Date(ms);
        return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }
    function minutesBetween(a, b) {
        return Math.round((b - a) / MIN);
    }
    function newId() {
        const d = new Date();
        const stamp = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
        return `${stamp}_${Math.random().toString(36).slice(2, 6)}`;
    }
    function str(v) {
        return v === undefined || v === null ? "" : String(v).trim();
    }
    function short(s, n) {
        return s.length > n ? s.slice(0, n) + "…" : s;
    }
    function indexPath() {
        return `${BASE}/index.jsonl`;
    }
    function threadPath(id) {
        return `${BASE}/threads/${id}.jsonl`;
    }
    async function ensureDir(path) {
        const e = await Tools.Files.exists(path);
        if (!e.exists) {
            const r = await Tools.Files.mkdir(path, true);
            if (!r.successful)
                throw new Error(`创建目录失败: ${path} (${r.details})`);
        }
    }
    async function readEvents(path) {
        const e = await Tools.Files.exists(path);
        if (!e.exists)
            return { events: [], bad: 0 };
        const r = await Tools.Files.read(path);
        const events = [];
        let bad = 0;
        for (const line of String(r.content || "").split("\n")) {
            const s = line.trim();
            if (!s)
                continue;
            try {
                const obj = JSON.parse(s);
                if (obj && typeof obj.t === "number" && typeof obj.ev === "string")
                    events.push(obj);
                else
                    bad++;
            }
            catch (_) {
                bad++;
            }
        }
        return { events, bad };
    }
    // 只追加一行 JSON；不覆盖、不删除。
    async function append(path, obj) {
        const r = await Tools.Files.write(path, JSON.stringify(obj) + "\n", true);
        if (!r.successful)
            throw new Error(`写入失败: ${path} (${r.details})`);
    }
    async function writeThread(id, ev, fields = {}) {
        const obj = { t: Date.now(), ev, ...fields };
        await append(threadPath(id), obj);
        return obj;
    }
    async function writeIndex(ev, thread, question) {
        await append(indexPath(), { t: Date.now(), ev, thread, question });
    }
    // ---------- 回放 ----------
    function replay(id, events, bad) {
        const s = {
            id,
            question: "",
            materials: [],
            last: null,
            next: "",
            judgement: "",
            pending: "",
            parking: [],
            status: "active",
            pausedAt: 0,
            pauseReason: "",
            returnAfter: 0,
            lastActivity: 0,
            lastRemind: 0,
            remindsSinceActivity: 0,
            recovery: null,
            steps: [],
            segments: [],
            currentSegment: 0,
            recoveries: [],
            reminds: 0,
            silences: 0,
            reframes: 0,
            stuckCounts: {},
            openedAt: 0,
            outcome: "",
            badLines: bad,
        };
        const endSegment = () => {
            if (s.currentSegment > 0)
                s.segments.push(s.currentSegment);
            s.currentSegment = 0;
        };
        const activity = (t) => {
            s.lastActivity = t;
            s.remindsSinceActivity = 0;
        };
        const countStuck = (k) => {
            if (k)
                s.stuckCounts[k] = (s.stuckCounts[k] || 0) + 1;
        };
        for (const e of events) {
            switch (e.ev) {
                case "open":
                    s.question = str(e.question);
                    s.materials = Array.isArray(e.materials) ? e.materials.map(str).filter(Boolean) : [];
                    s.next = str(e.next);
                    s.openedAt = e.t;
                    s.status = "active";
                    activity(e.t);
                    break;
                case "step": {
                    const step = {
                        t: e.t,
                        text: str(e.text),
                        kind: str(e.kind),
                        help: Number(e.help) || 0,
                        basis: str(e.basis),
                        stuck: str(e.stuck),
                    };
                    s.steps.push(step);
                    s.last = step;
                    s.next = str(e.next);
                    if (e.judgement !== undefined && str(e.judgement))
                        s.judgement = str(e.judgement);
                    if (e.pending !== undefined)
                        s.pending = str(e.pending);
                    countStuck(step.stuck);
                    if (s.recovery) {
                        s.recoveries.push({
                            dur_min: minutesBetween(s.recovery.start, e.t),
                            hints: s.recovery.hints,
                            help: step.help,
                        });
                        s.recovery = null;
                    }
                    s.status = "active";
                    s.currentSegment++;
                    activity(e.t);
                    break;
                }
                case "reframe":
                    s.next = str(e.next);
                    s.reframes++;
                    countStuck(str(e.stuck));
                    activity(e.t);
                    break;
                case "park":
                    s.parking.push({ note: str(e.note), t: e.t, done: false });
                    activity(e.t);
                    break;
                case "park_done": {
                    const i = Number(e.index) - 1;
                    if (s.parking[i])
                        s.parking[i].done = true;
                    activity(e.t);
                    break;
                }
                case "pause":
                    s.status = "paused";
                    s.pausedAt = e.t;
                    s.pauseReason = str(e.reason);
                    s.returnAfter = Number(e.return_after) || 0;
                    endSegment();
                    break;
                case "resume":
                    if (!s.recovery)
                        s.recovery = { start: e.t, hints: 0 };
                    s.status = "active";
                    s.returnAfter = 0;
                    endSegment();
                    activity(e.t);
                    break;
                case "recover_help":
                    if (!s.recovery)
                        s.recovery = { start: e.t, hints: 0 };
                    s.recovery.hints++;
                    activity(e.t);
                    break;
                case "remind":
                    s.reminds++;
                    s.lastRemind = e.t;
                    s.remindsSinceActivity++;
                    break;
                case "silence":
                    s.silences++;
                    break;
                case "close":
                    s.status = "closed";
                    s.outcome = str(e.outcome);
                    endSegment();
                    break;
            }
        }
        return s;
    }
    async function loadThread(id) {
        const { events, bad } = await readEvents(threadPath(id));
        if (events.length === 0)
            return null;
        return replay(id, events, bad);
    }
    async function loadIndex() {
        const { events } = await readEvents(indexPath());
        const map = {};
        let active = "";
        for (const e of events) {
            const id = str(e.thread);
            if (!id)
                continue;
            if (!map[id])
                map[id] = { id, question: str(e.question), lastT: e.t, closed: false };
            map[id].lastT = e.t;
            if (str(e.question))
                map[id].question = str(e.question);
            if (e.ev === "activate") {
                map[id].closed = false;
                active = id;
            }
            else if (e.ev === "close") {
                map[id].closed = true;
                if (active === id)
                    active = "";
            }
        }
        const threads = Object.keys(map)
            .map((k) => map[k])
            .sort((a, b) => b.lastT - a.lastT);
        return { active, threads };
    }
    async function requireThread(threadId) {
        const id = str(threadId) || (await loadIndex()).active;
        if (!id)
            throw new Error("当前没有进行中的题目。先用 get_card 看最近题目，用 resume(thread_id) 接回，或用 open_thread 开题。");
        const s = await loadThread(id);
        if (!s)
            throw new Error(`找不到题目 ${id}`);
        return s;
    }
    // ---------- 呈现 ----------
    function card(s) {
        const lines = [];
        lines.push("【思考卡】");
        lines.push(`正在想：${s.question}`);
        if (s.last) {
            const basis = s.last.basis ? `（依据：${s.last.basis}）` : "";
            lines.push(`刚才到：${s.last.text}${basis}［${HELP_LABEL[s.last.help] || "?"}］`);
        }
        else {
            lines.push("刚才到：（刚开题，还没有第一步）");
        }
        lines.push(`接下来：${s.next}`);
        const open = s.parking.filter((p) => !p.done);
        lines.push(open.length ? `暂存区：${open.length} 条 · 最新「${short(open[open.length - 1].note, 24)}」` : "暂存区：空");
        if (s.judgement || s.pending) {
            lines.push(`判断：${s.judgement || "（未形成）"}${s.pending ? `｜待确认：${s.pending}` : ""}`);
        }
        return lines.join("\n");
    }
    function returnPoint(s) {
        const y = s.last ? s.last.text : "刚开题，还没走第一步";
        return `刚才在想「${s.question}」，你已经推到「${y}」。现在只需要接：${s.next}`;
    }
    function progressLine(s) {
        const seg = s.currentSegment;
        const h = [0, 0, 0, 0];
        for (const st of s.steps)
            h[Math.max(0, Math.min(3, st.help))]++;
        const base = `进展：共 ${s.steps.length} 步，本段连续 ${seg} 步（独立 ${h[0]}｜提示 ${h[1]}｜句子开头 ${h[2]}｜AI示范 ${h[3]}）`;
        if (s.judgement || s.pending) {
            return `${base}\n现在：${s.judgement || "还没形成判断"}${s.pending ? `；还待确认：${s.pending}` : ""}`;
        }
        return base;
    }
    function checkNext(next) {
        const w = [];
        const q = (next.match(/[?？]/g) || []).length;
        if (q > 1)
            w.push("下一步里有多个问题，只保留一个。");
        if (next.length > 60)
            w.push("下一步偏长，考虑拆小到一句能答的程度。");
        return w;
    }
    function ok(message, data = {}) {
        return { success: true, message, data };
    }
    function fail(message, data = {}) {
        return { success: false, message, data };
    }
    // ---------- 工具 ----------
    async function get_card(params) {
        await ensureDir(`${BASE}/threads`);
        const idx = await loadIndex();
        const id = str(params.thread_id) || idx.active;
        if (!id) {
            const recent = idx.threads.filter((t) => !t.closed).slice(0, 5);
            if (!recent.length)
                return ok("当前没有题目。可以用 open_thread 开一个。", { active: null, recent: [] });
            const list = recent.map((t) => `- ${t.id}：${t.question}（${hhmm(t.lastT)}）`).join("\n");
            return ok(`当前没有进行中的题目。最近未结束的题目（优先接回，而不是重开）：\n${list}`, { active: null, recent });
        }
        const s = await loadThread(id);
        if (!s)
            return fail(`找不到题目 ${id}`);
        const extra = s.status === "paused" ? `\n（已暂停于 ${hhmm(s.pausedAt)}，回来请先 resume）` : "";
        return ok(card(s) + extra, { thread_id: s.id, status: s.status, next: s.next, return_point: returnPoint(s) });
    }
    async function open_thread(params) {
        const question = str(params.question);
        const next = str(params.next);
        if (!question || !next)
            return fail("question 和 next 都不能为空。");
        await ensureDir(`${BASE}/threads`);
        const idx = await loadIndex();
        if (idx.active) {
            const cur = await loadThread(idx.active);
            if (cur && cur.status !== "closed") {
                if (params.confirm_switch !== true) {
                    return fail(`已有进行中的题目，未开新题。先接原来的返回点：\n${returnPoint(cur)}\n如果用户明确要换题，再带 confirm_switch=true 调用（旧题会暂停保留）。`, { active: cur.id });
                }
                await writeThread(cur.id, "pause", { reason: "switch" });
            }
        }
        const id = newId();
        const materials = str(params.materials)
            .split("|")
            .map(str)
            .filter(Boolean);
        await writeThread(id, "open", { question, next, materials });
        await writeIndex("activate", id, question);
        const s = await loadThread(id);
        const warn = checkNext(next);
        return ok(card(s) + (warn.length ? `\n提醒：${warn.join(" ")}` : ""), { thread_id: id, warnings: warn });
    }
    async function record_step(params) {
        const text = str(params.text);
        const kind = str(params.kind);
        const next = str(params.next);
        const help = Number(params.help);
        if (!text || !next)
            return fail("text 和 next 都不能为空。");
        if (!KIND_LABEL[kind])
            return fail(`kind 只能是 ${Object.keys(KIND_LABEL).join(" / ")}。`);
        if (!(help >= 0 && help <= 3 && Math.floor(help) === help))
            return fail("help 只能是 0/1/2/3。");
        const stuck = str(params.stuck);
        if (stuck && !STUCK_LABEL[stuck])
            return fail(`stuck 只能是 ${Object.keys(STUCK_LABEL).join(" / ")}。`);
        const s0 = await requireThread();
        if (s0.status === "closed")
            return fail("这个题目已结束。");
        const fields = { text, kind, help, next };
        if (str(params.basis))
            fields.basis = str(params.basis);
        if (params.judgement !== undefined)
            fields.judgement = str(params.judgement);
        if (params.pending !== undefined)
            fields.pending = str(params.pending);
        if (stuck)
            fields.stuck = stuck;
        await writeThread(s0.id, "step", fields);
        const s = (await loadThread(s0.id));
        const warn = checkNext(next);
        return ok(`${card(s)}\n${progressLine(s)}${warn.length ? `\n提醒：${warn.join(" ")}` : ""}`, {
            thread_id: s.id,
            steps: s.steps.length,
            segment: s.currentSegment,
            warnings: warn,
        });
    }
    async function set_next(params) {
        const next = str(params.next);
        if (!next)
            return fail("next 不能为空。");
        const stuck = str(params.stuck);
        if (stuck && !STUCK_LABEL[stuck])
            return fail(`stuck 只能是 ${Object.keys(STUCK_LABEL).join(" / ")}。`);
        const s0 = await requireThread();
        if (s0.status === "closed")
            return fail("这个题目已结束。");
        const fields = { next, reason: str(params.reason) };
        if (stuck)
            fields.stuck = stuck;
        await writeThread(s0.id, "reframe", fields);
        const s = (await loadThread(s0.id));
        const warn = checkNext(next);
        return ok(card(s) + (warn.length ? `\n提醒：${warn.join(" ")}` : ""), { thread_id: s.id, warnings: warn });
    }
    async function park(params) {
        const s0 = await requireThread();
        const note = str(params.note);
        const done = Number(params.done);
        if (!note && !done)
            return fail("note 和 done 至少给一个。");
        if (done) {
            const open = s0.parking[done - 1];
            if (!open)
                return fail(`暂存区没有第 ${done} 条（共 ${s0.parking.length} 条）。`);
            await writeThread(s0.id, "park_done", { index: done });
        }
        if (note)
            await writeThread(s0.id, "park", { note });
        const s = (await loadThread(s0.id));
        const list = s.parking.map((p, i) => `${i + 1}. ${p.done ? "✓ " : ""}${p.note}`).join("\n");
        return ok(`已放进暂存区，继续当前问题。\n接下来：${s.next}\n暂存区：\n${list}`, { thread_id: s.id, parking: s.parking });
    }
    async function pause(params) {
        const s0 = await requireThread();
        if (s0.status === "closed")
            return fail("这个题目已结束。");
        const fields = { reason: str(params.reason) || "manual" };
        const after = Number(params.return_after_min);
        if (after > 0)
            fields.return_after = Date.now() + after * MIN;
        await writeThread(s0.id, "pause", fields);
        const s = (await loadThread(s0.id));
        const when = after > 0 ? `约 ${after} 分钟后提醒你回来。` : "回来时会从这里接上。";
        return ok(`返回点已保存：\n${returnPoint(s)}\n${when}`, { thread_id: s.id, return_point: returnPoint(s) });
    }
    async function resume(params) {
        const mode = str(params.mode) || "brief";
        if (mode !== "brief" && mode !== "help")
            return fail("mode 只能是 brief 或 help。");
        await ensureDir(`${BASE}/threads`);
        const idx = await loadIndex();
        const wanted = str(params.thread_id);
        if (wanted && wanted !== idx.active) {
            const target = await loadThread(wanted);
            if (!target)
                return fail(`找不到题目 ${wanted}`);
            if (target.status === "closed")
                return fail("这个题目已结束。");
            if (idx.active) {
                const cur = await loadThread(idx.active);
                if (cur && cur.status !== "closed" && cur.status !== "paused")
                    await writeThread(cur.id, "pause", { reason: "switch" });
            }
            await writeIndex("activate", wanted, target.question);
        }
        const s0 = await requireThread(wanted || undefined);
        if (s0.status === "closed")
            return fail("这个题目已结束。");
        const away = s0.lastActivity ? minutesBetween(s0.lastActivity, Date.now()) : 0;
        if (!s0.recovery)
            await writeThread(s0.id, "resume", { mode, away_min: away });
        if (mode === "help")
            await writeThread(s0.id, "recover_help", {});
        const s = (await loadThread(s0.id));
        if (mode === "brief") {
            return ok(`${returnPoint(s)}\n（离开约 ${away} 分钟。想不起来可以说“帮我恢复”。）`, {
                thread_id: s.id,
                away_min: away,
                return_point: returnPoint(s),
            });
        }
        const parts = [`你在想：${s.question}`];
        const recent = s.steps.slice(-3);
        if (recent.length) {
            parts.push("已经走过：");
            for (const st of recent)
                parts.push(`- ${st.text}${st.basis ? `（因为 ${st.basis}）` : ""}`);
        }
        if (s.materials.length)
            parts.push(`手上的材料：${s.materials.slice(0, 3).join("；")}`);
        if (s.judgement || s.pending)
            parts.push(`目前判断：${s.judgement || "还没形成"}${s.pending ? `；待确认：${s.pending}` : ""}`);
        parts.push(`现在只需要接：${s.next}`);
        parts.push("先确认一句“对，接着来”，或者直接回答这一步。");
        return ok(parts.join("\n"), {
            thread_id: s.id,
            away_min: away,
            hints: s.recovery ? s.recovery.hints : 0,
            return_point: returnPoint(s),
        });
    }
    async function reminder_check(params) {
        const interval = Number(params.min_interval_min) > 0 ? Number(params.min_interval_min) : 30;
        const quiet = Number(params.quiet_min) >= 0 && params.quiet_min !== undefined ? Number(params.quiet_min) : 10;
        await ensureDir(`${BASE}/threads`);
        const idx = await loadIndex();
        if (!idx.active)
            return ok("SKIP", { action: "SKIP", reason: "no_active_thread" });
        const s = await loadThread(idx.active);
        if (!s || s.status === "closed")
            return ok("SKIP", { action: "SKIP", reason: "closed" });
        const now = Date.now();
        if (s.returnAfter && now < s.returnAfter) {
            return ok("SKIP", { action: "SKIP", reason: "return_after_not_reached" });
        }
        if (s.lastActivity && now - s.lastActivity < quiet * MIN) {
            return ok("SKIP", { action: "SKIP", reason: "recent_activity" });
        }
        if (s.lastRemind && now - s.lastRemind < interval * MIN) {
            return ok("SKIP", { action: "SKIP", reason: "interval_not_reached" });
        }
        // 上一次提醒后没有任何回应：只记“状态未知”，不判定走神。
        if (s.remindsSinceActivity > 0)
            await writeThread(s.id, "silence", { since: s.lastRemind });
        await writeThread(s.id, "remind", { interval_min: interval });
        const text = `${returnPoint(s)}\n方便时回来接这一步就行；现在不方便可以回“稍后”。`;
        return ok(text, { action: "REMIND", text, thread_id: s.id, unanswered_before: s.remindsSinceActivity });
    }
    async function stats(params) {
        const days = Number(params.days) > 0 ? Number(params.days) : 7;
        const since = Date.now() - days * 24 * 60 * MIN;
        await ensureDir(`${BASE}/threads`);
        const idx = await loadIndex();
        const help = [0, 0, 0, 0];
        const kinds = {};
        const stuck = {};
        const segments = [];
        const recoveries = [];
        let reminds = 0;
        let silences = 0;
        let reframes = 0;
        let bad = 0;
        let threads = 0;
        for (const t of idx.threads) {
            const { events, bad: b } = await readEvents(threadPath(t.id));
            const recent = events.filter((e) => e.t >= since);
            if (!recent.length)
                continue;
            threads++;
            bad += b;
            // 用全量回放保证状态正确，再只统计窗口内的事件。
            const s = replay(t.id, events, b);
            for (const st of s.steps) {
                if (st.t < since)
                    continue;
                help[Math.max(0, Math.min(3, st.help))]++;
                kinds[st.kind] = (kinds[st.kind] || 0) + 1;
            }
            for (const e of recent) {
                if (e.ev === "remind")
                    reminds++;
                if (e.ev === "silence")
                    silences++;
                if (e.ev === "reframe")
                    reframes++;
                if ((e.ev === "step" || e.ev === "reframe") && str(e.stuck))
                    stuck[str(e.stuck)] = (stuck[str(e.stuck)] || 0) + 1;
            }
            const replayWindow = replay(t.id, recent, 0);
            segments.push(...replayWindow.segments);
            if (replayWindow.currentSegment > 0)
                segments.push(replayWindow.currentSegment);
            recoveries.push(...replayWindow.recoveries);
        }
        const total = help[0] + help[1] + help[2] + help[3];
        const maxSeg = segments.length ? Math.max(...segments) : 0;
        const avgSeg = segments.length ? +(segments.reduce((a, b) => a + b, 0) / segments.length).toFixed(1) : 0;
        const avgRecMin = recoveries.length ? +(recoveries.reduce((a, r) => a + r.dur_min, 0) / recoveries.length).toFixed(1) : 0;
        const avgRecHints = recoveries.length ? +(recoveries.reduce((a, r) => a + r.hints, 0) / recoveries.length).toFixed(1) : 0;
        const recIndependent = recoveries.filter((r) => r.hints === 0 && r.help === 0).length;
        const lines = [
            `最近 ${days} 天，涉及 ${threads} 个题目`,
            `思考连续性：共 ${total} 步；连续段 ${segments.length} 段，最长 ${maxSeg} 步，平均 ${avgSeg} 步`,
            `帮助程度：独立 ${help[0]}｜提示 ${help[1]}｜句子开头 ${help[2]}｜AI示范 ${help[3]}`,
            `中断恢复：${recoveries.length} 次，平均 ${avgRecMin} 分钟接上第一步，平均“帮我恢复” ${avgRecHints} 次，其中不靠任何提示接上 ${recIndependent} 次`,
            `卡住类型：${Object.keys(stuck).length ? Object.keys(stuck).map((k) => `${STUCK_LABEL[k] || k} ${stuck[k]}`).join("｜") : "无记录"}；换小下一步 ${reframes} 次`,
            `提醒：${reminds} 次，其中前一次无回应（状态未知）${silences} 次`,
            "注：锁机时长、聊天时长、记录条数不算作思考成绩，这里不统计。",
        ];
        if (bad)
            lines.push(`⚠️ 有 ${bad} 行数据无法解析，已跳过。`);
        return ok(lines.join("\n"), {
            days,
            threads,
            steps: total,
            help: { independent: help[0], reminder: help[1], starter: help[2], demo: help[3] },
            kinds,
            segments: { count: segments.length, max: maxSeg, avg: avgSeg },
            recoveries: { count: recoveries.length, avg_min: avgRecMin, avg_hints: avgRecHints, without_hints: recIndependent },
            stuck,
            reframes,
            reminds,
            silences,
            bad_lines: bad,
        });
    }
    async function close_thread(params) {
        const outcome = str(params.outcome);
        if (!outcome)
            return fail("outcome 不能为空。");
        const s0 = await requireThread();
        if (s0.status === "closed")
            return fail("这个题目已结束。");
        await writeThread(s0.id, "close", { outcome });
        await writeIndex("close", s0.id, s0.question);
        const s = (await loadThread(s0.id));
        const open = s.parking.filter((p) => !p.done);
        const parkNote = open.length ? `\n暂存区还有 ${open.length} 条，可作为下一个题目的候选：\n${open.map((p) => `- ${p.note}`).join("\n")}` : "";
        return ok(`题目已结束：${s.question}\n结论：${outcome}\n${progressLine(s)}${parkNote}`, { thread_id: s.id });
    }
    // 自检：在独立目录里走一遍完整流程，不碰正式数据。
    async function main() {
        const realBase = BASE;
        BASE = `${realBase}_selftest_${Date.now()}`;
        const results = [];
        try {
            results.push(await open_thread({ question: "这个岗位要不要投？", next: "岗位职责里哪一条和我做过的事最接近？" }));
            results.push(await record_step({ text: "职责里的数据整理我实习做过", kind: "connect", help: 0, next: "经验要求一年，我的两段实习能不能算？", judgement: "暂时考虑投", pending: "经验要求" }));
            results.push(await park({ note: "查一下这家公司的评价" }));
            results.push(await pause({ reason: "leave" }));
            results.push(await resume({ mode: "help" }));
            results.push(await record_step({ text: "两段实习加起来 8 个月，差一点但可以写成项目经验", kind: "infer", help: 1, next: "简历里哪一条能证明这一点？", pending: "简历证明" }));
            results.push(await stats({}));
            results.push(await close_thread({ outcome: "自检完成" }));
            const okAll = results.every((r) => r.success);
            return { success: okAll, message: okAll ? `自检通过（数据在 ${BASE}，可删除）` : "自检有失败项", data: results.map((r) => r.message) };
        }
        finally {
            BASE = realBase;
        }
    }
    async function wrap(func, params) {
        try {
            complete(await func(params || {}));
        }
        catch (error) {
            complete({ success: false, message: `执行失败: ${error && error.message ? error.message : String(error)}` });
        }
    }
    return {
        get_card: (p) => wrap(get_card, p),
        open_thread: (p) => wrap(open_thread, p),
        record_step: (p) => wrap(record_step, p),
        set_next: (p) => wrap(set_next, p),
        park: (p) => wrap(park, p),
        pause: (p) => wrap(pause, p),
        resume: (p) => wrap(resume, p),
        reminder_check: (p) => wrap(reminder_check, p),
        stats: (p) => wrap(stats, p),
        close_thread: (p) => wrap(close_thread, p),
        main: (p) => wrap(main, p),
    };
})();
exports.get_card = ThoughtRelay.get_card;
exports.open_thread = ThoughtRelay.open_thread;
exports.record_step = ThoughtRelay.record_step;
exports.set_next = ThoughtRelay.set_next;
exports.park = ThoughtRelay.park;
exports.pause = ThoughtRelay.pause;
exports.resume = ThoughtRelay.resume;
exports.reminder_check = ThoughtRelay.reminder_check;
exports.stats = ThoughtRelay.stats;
exports.close_thread = ThoughtRelay.close_thread;
exports.main = ThoughtRelay.main;
