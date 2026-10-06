"use strict";
// 主控台“司南”页：司南这条线现在走到哪、今天的计划与规则、三个数、一键找她。
// 只读 core/ 下的文件；按钮只是替你给司南发一句话，状态仍由司南自己写。
Object.defineProperty(exports, "__esModule", { value: true });
exports.use = use;
exports.parseAll = parseAll;
const nav_js_1 = require("./nav.js");

const ROOT = "/sdcard/Download/Operit/";
const CORE = ROOT + "core/";
const STATUS_NAME = { thinking: "在想", acting: "在做", resting: "休息", paused: "暂停", idle: "空闲" };
const QUICK = [
    { label: "我在做", say: "我在做，先别打扰。" },
    { label: "卡住了", say: "我卡住了，帮我理一下。" },
    { label: "休息", say: "我休息一下。" },
    { label: "回来了", say: "我回来了，接着来。" },
];

function bj(now) {
    const d = new Date(now + 8 * 3600 * 1000);
    const p = (n) => (n < 10 ? "0" + n : String(n));
    return { day: `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`, min: d.getUTCHours() * 60 + d.getUTCMinutes(), hhmm: `${p(d.getUTCHours())}:${p(d.getUTCMinutes())}` };
}
function toMin(s) { const m = /^(\d{1,2}):(\d{2})$/.exec(String(s || "").trim()); return m ? Number(m[1]) * 60 + Number(m[2]) : -1; }

// 纯函数：把几个文件的原文解析成页面要显示的东西
function parseAll(raw, now) {
    const t = bj(now);
    let thread = null;
    try { thread = raw.thread ? JSON.parse(raw.thread) : null; } catch (e) { thread = null; }
    const lines = String(raw.windows || "").split("\n").map((l) => l.trim()).filter(Boolean);
    const planDay = (lines.find((l) => l.startsWith("date=")) || "").slice(5);
    const windows = lines.filter((l) => !l.startsWith("date=") && !l.startsWith("#")).map((l) => {
        const [id, start, end, title] = l.split("|");
        const s = toMin(start), e = toMin(end);
        return { id, start, end, title: title || "", now: planDay === t.day && t.min >= s && t.min < e, done: planDay === t.day && t.min >= e };
    });
    const outputs = String(raw.outputs || "").split("\n").filter((l) => l.startsWith("| " + t.day)).length;
    let replies = 0, lastUserTs = "";
    String(raw.log || "").split("\n").forEach((l) => {
        if (!l.trim()) return;
        try { const r = JSON.parse(l); if (r.who === "user") { replies++; lastUserTs = r.ts || lastUserTs; } } catch (e) { }
    });
    const manual = (String(raw.profile || "").split("## 使用说明书")[1] || "").split("\n## ")[0]
        .split("\n").map((l) => l.trim()).filter((l) => l.startsWith("- ") && !l.includes("（空"));
    let rules = [];
    try { const today = raw.today ? JSON.parse(raw.today) : null; if (today && Array.isArray(today.rules)) rules = today.rules.map(String); } catch (e) { }
    const chatId = (/main_chat_id=([^\s]+)/.exec(String(raw.config || "")) || [])[1] || "";
    return { today: t.day, clock: t.hhmm, planIsToday: planDay === t.day, thread, windows, outputs, replies, lastUserTs,
        manualLast: manual.length ? manual[manual.length - 1].slice(2) : "", rules, chatId };
}

async function readText(path) { try { const r = await Tools.Files.read(path); return String((r && r.content) || ""); } catch (e) { return ""; } }
async function loadRaw(now) {
    const day = bj(now).day;
    const names = { thread: CORE + "thread.json", windows: CORE + "today_windows.txt", today: CORE + "today.json", outputs: CORE + "成果.md",
        log: CORE + "log/" + day + ".jsonl", profile: CORE + "profile.md", config: CORE + "config.txt" };
    const keys = Object.keys(names);
    const values = await Promise.all(keys.map((k) => readText(names[k])));
    const raw = {}; keys.forEach((k, i) => { raw[k] = values[i]; });
    return raw;
}

function use(ctx, kit) {
    const { UI } = ctx;
    const colors = kit.colors;
    const ACCENT = "#B5653A", TINT = "#FBEDE4";
    const [data, setData] = ctx.useState("sinan.data", null);
    const [busy, setBusy] = ctx.useState("sinan.busy", false);
    const [note, setNote] = ctx.useState("sinan.note", null);
    const [draft, setDraft] = ctx.useState("sinan.draft", "");

    async function refresh() {
        try { const now = Date.now(); setData(parseAll(await loadRaw(now), now)); }
        catch (e) { setNote({ ok: false, text: "读取失败：" + String((e && e.message) || e) }); }
    }
    async function openHer() {
        if (!data || !data.chatId) { setNote({ ok: false, text: "还没有司南对话：先完成命令 1B" }); return; }
        try { await nav_js_1.setMainChat(data.chatId); await ctx.navigate(nav_js_1.NATIVE_CHAT_ROUTE); }
        catch (e) { setNote({ ok: false, text: "打开失败：" + String((e && e.message) || e) }); }
    }
    async function voice() {
        if (!data || !data.chatId) return;
        try { await nav_js_1.startVoiceWith(data.chatId); setNote({ ok: true, text: "已打开语音球并切到司南" }); }
        catch (e) { setNote({ ok: false, text: "语音没打开：" + String((e && e.message) || e) }); }
    }
    async function say(message) {
        const text = String(message || "").trim();
        if (!text || busy || !data || !data.chatId) return;
        setBusy(true);
        setNote({ ok: true, text: "已发给司南，等她回复…" });
        try {
            try { await Tools.Chat.startService(); } catch (e) { }
            await Tools.Chat.sendMessage(text, data.chatId, undefined, undefined, { persist_turn: true, notify_reply: true, timeout_ms: 120000 });
            setDraft("");
            setNote({ ok: true, text: "司南已回复，去对话里看看" });
            await refresh();
        } catch (e) {
            setNote({ ok: false, text: "发送没完成：" + String((e && e.message) || e) });
        } finally { setBusy(false); }
    }

    function nowCard(full) {
        const th = (data && data.thread) || {};
        const head = [
            UI.Row({ fillMaxWidth: true, spacing: 12, verticalAlignment: "center" }, [
                UI.Card({ width: 48, height: 48, containerColor: TINT, shape: { type: "circle" }, border: { width: 2, color: ACCENT }, elevation: 0 },
                    UI.Box({ fillMaxSize: true, contentAlignment: "center" }, [kit.text("🧭", "titleLarge", colors.onSurface)])),
                UI.Column({ weight: 1, spacing: 2 }, [
                    kit.text("司南", "titleLarge", ACCENT),
                    kit.muted(th.step ? `第 ${th.step} 步 · ${STATUS_NAME[th.status] || th.status || ""}` : (STATUS_NAME[th.status] || "还没开始")),
                ]),
            ]),
            kit.text(th.task || "还没有开始的事", "titleMedium", colors.onSurface),
            ...(th.question ? [UI.Surface({ fillMaxWidth: true, containerColor: TINT, shape: { cornerRadius: 14 } },
                UI.Column({ fillMaxWidth: true, padding: 12, spacing: 2 }, [kit.label("她在问"), kit.text(th.question, "bodyLarge", colors.onSurface)]))] : []),
        ];
        const more = full ? [
            ...(th.last_step ? [kit.muted("上一步：" + th.last_step)] : []),
            ...(th.parked_ideas ? [kit.muted("先放着的想法：" + th.parked_ideas)] : []),
            UI.Row({ fillMaxWidth: true, spacing: 6 }, QUICK.map((q) =>
                UI.Surface({ weight: 1, containerColor: colors.surfaceVariant, shape: { cornerRadius: 12 }, onClick: () => say(q.say) },
                    UI.Box({ fillMaxWidth: true, contentAlignment: "center" }, [kit.text(q.label, "labelMedium", colors.onSurface, { paddingVertical: 8 })])))),
            UI.TextField({ fillMaxWidth: true, value: draft, onValueChange: setDraft, placeholder: "跟司南说一句：交东西、改计划、求助都行" }),
            UI.Button({ fillMaxWidth: true, text: busy ? "发送中…" : "发给司南", enabled: !busy && !!draft.trim(), onClick: () => say(draft) }),
        ] : [];
        return kit.card([
            ...head, ...more,
            UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                UI.Button({ text: "找司南", weight: 1, enabled: !!(data && data.chatId), onClick: openHer }),
                UI.FilledTonalButton({ weight: 1, enabled: !!(data && data.chatId), onClick: voice }, kit.text("🎙 语音找她", "labelLarge", colors.onSurface)),
            ]),
            ...(note ? [kit.text(note.text, "bodySmall", note.ok ? colors.primary : colors.error)] : []),
        ]);
    }
    function planCard() {
        return kit.card([
            kit.label(`今天 · ${data.today} ${data.clock}`),
            ...(data.planIsToday && data.windows.length
                ? data.windows.map((w) => UI.Row({ fillMaxWidth: true, spacing: 10, verticalAlignment: "center" }, [
                    UI.Box({ width: 8, height: 8, background: w.now ? ACCENT : w.done ? "#9DB5A6" : "#D5D9DD", backgroundShape: { type: "circle" } }),
                    kit.text(`${w.start}–${w.end}`, "labelMedium", colors.onSurfaceVariant),
                    kit.text(w.title, w.now ? "titleSmall" : "bodyMedium", w.now ? ACCENT : w.done ? colors.onSurfaceVariant : colors.onSurface, { weight: 1, maxLines: 2 }),
                ]))
                : [kit.muted("今天的计划还没排（08:00 司南会来排）")]),
            ...(data.rules.length ? [kit.label("今天的规则"), ...data.rules.map((r) => kit.text("· " + r, "bodyMedium", colors.onSurface))] : []),
        ]);
    }
    function numbersCard() {
        const stat = (value, key) => UI.Surface({ weight: 1, containerColor: colors.surfaceVariant, shape: { cornerRadius: 12 } },
            UI.Column({ fillMaxWidth: true, padding: 10, spacing: 2 }, [kit.text(value, "titleMedium", colors.onSurface, { maxLines: 1 }), kit.text(key, "labelSmall", colors.onSurfaceVariant)]));
        return kit.card([
            kit.label("三个数"),
            UI.Row({ fillMaxWidth: true, spacing: 8 }, [stat(String(data.replies), "今天回应"), stat(String(data.outputs), "今天交出"), stat(data.lastUserTs || "—", "最近回应")]),
            ...(data.manualLast ? [kit.label("使用说明书 · 最新一条"), kit.text(data.manualLast, "bodyMedium", colors.onSurface)] : []),
        ]);
    }
    function page() {
        const items = [UI.Row({ fillMaxWidth: true, paddingStart: 4, paddingTop: 8, paddingBottom: 4, verticalAlignment: "center" }, [
            kit.text("司南", "headlineSmall", colors.onSurface, { weight: 1 }),
            UI.IconButton({ icon: Icons.Refresh, onClick: refresh }),
        ])];
        if (!data) items.push(kit.spinner());
        else items.push(nowCard(true), planCard(), numbersCard());
        return kit.pageColumn(items);
    }
    function miniCard() { return data ? nowCard(false) : null; }
    return { refresh, page, miniCard };
}
