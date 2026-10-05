"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.default = Screen;
const snapshot_js_1 = require("../../shared/snapshot.js");
const format_js_1 = require("../../shared/format.js");
const progress_js_1 = require("../../shared/progress.js");
const nav_js_1 = require("../../shared/nav.js");
const companion_js_1 = require("../../shared/companion.js");
const speech_js_1 = require("../../shared/speech.js");
const slim_js_1 = require("../../shared/slim.js");
const warden_js_1 = require("../../shared/warden.js");
const sinan_js_1 = require("../../shared/sinan.js");
const sinan_logic_js_1 = require("../../shared/sinan_logic.js");
const gate_js_1 = require("../../shared/gate.js");
// 心情四档固定配色：安心 / 在意 / 担心 / 要谈谈
const MOOD_COLOR = ["#7FAE8E", "#E2B25A", "#E0876B", "#C75A6B"];
const MOOD_TINT = ["#E4F0E8", "#FBF1DC", "#FBE6DE", "#F8E0E4"];
const MOOD_INK = ["#3F7A52", "#A2741D", "#B5553A", "#A23A4C"];
const ZONE_WEIGHTS = [20, 25, 25, 30];
// 司南的两套底色：专注时段是夜紫，平时是深青；金色只给钥匙、进度和司南的头像
const PAL = {
    focus: { grad: ["#1A1838", "#2E2560", "#4A3489"], mid: "#2E2560", deep: "#1A1838", chip: "#4B3F86", soft: "#CFC8F0" },
    calm: { grad: ["#0E3433", "#17524F", "#277567"], mid: "#17524F", deep: "#0E3433", chip: "#2F6F68", soft: "#BFE3DC" },
};
const GOLD = "#F2C36B";
const GOLD_GRAD = ["#FFE3A3", "#F2C36B", "#D9993A"];
const ON_DARK = "#FFFFFF";
const NAV_GRAD = ["#4A3489", "#2E2560"];
const THREAD_SHOWN = 16;
const MANAGE_TABS = [
    { key: "warden", name: "督促" },
    { key: "slim", name: "省流" },
    { key: "sys", name: "系统" },
];
const ACTIONS = [
    { kind: "progress", icon: "▶" },
    { kind: "stuck", icon: "◼" },
    { kind: "done", icon: "✓" },
    { kind: "pause", icon: "Ⅱ" },
];
const COLLAPSED_WORKFLOWS = 6;
const COLLAPSED_PACKAGES = 10;
const RECENT_CHATS = 30;
const COLLAPSED_CHATS = 6;
function errorText(error) {
    if (error && typeof error === "object" && "message" in error) {
        return String(error.message);
    }
    return String(error);
}
function parseTime(value) {
    if (!value)
        return null;
    const n = /^\d+$/.test(value) ? Number(value) : Date.parse(value);
    return Number.isFinite(n) && n > 0 ? n : null;
}
function Screen(ctx) {
    const { UI } = ctx;
    const colors = ctx.MaterialTheme.colorScheme;
    const [page, setPage] = ctx.useState("page", "focus");
    const [manageTab, setManageTab] = ctx.useState("manageTab", "warden");
    const [showAllChats, setShowAllChats] = ctx.useState("showAllChats", false);
    const [snap, setSnap] = ctx.useState("snap", null);
    const [loading, setLoading] = ctx.useState("loading", false);
    const [loadError, setLoadError] = ctx.useState("loadError", "");
    const [progressKind, setProgressKind] = ctx.useState("progressKind", "progress");
    const [progressText, setProgressText] = ctx.useState("progressText", "");
    const [savingProgress, setSavingProgress] = ctx.useState("savingProgress", false);
    const [voiceBusy, setVoiceBusy] = ctx.useState("voiceBusy", false);
    const [speaking, setSpeaking] = ctx.useState("speaking", false);
    // 按钮结果留在卡片上，不只靠一闪而过的提示
    const [status, setStatus] = ctx.useState("status", null);
    const [chats, setChats] = ctx.useState("chats", null);
    const [chatsError, setChatsError] = ctx.useState("chatsError", "");
    const [query, setQuery] = ctx.useState("query", "");
    const [openingId, setOpeningId] = ctx.useState("openingId", "");
    const [showAllWorkflows, setShowAllWorkflows] = ctx.useState("showAllWorkflows", false);
    const [showMore, setShowMore] = ctx.useState("showMore", false);
    const [slim, setSlim] = ctx.useState("slim", null);
    const [slimError, setSlimError] = ctx.useState("slimError", "");
    const [slimStatus, setSlimStatus] = ctx.useState("slimStatus", null);
    const [confirmKey, setConfirmKey] = ctx.useState("confirmKey", "");
    const [busyKey, setBusyKey] = ctx.useState("busyKey", "");
    const [scan, setScan] = ctx.useState("scan", null);
    const [showAllPackages, setShowAllPackages] = ctx.useState("showAllPackages", false);
    const [warden, setWarden] = ctx.useState("warden", null);
    const [wardenError, setWardenError] = ctx.useState("wardenError", "");
    const [wardenStatus, setWardenStatus] = ctx.useState("wardenStatus", null);
    const [assignTask, setAssignTask] = ctx.useState("assignTask", "");
    const [assignStd, setAssignStd] = ctx.useState("assignStd", "product");
    const [assignFile, setAssignFile] = ctx.useState("assignFile", "");
    const [deliverQuote, setDeliverQuote] = ctx.useState("deliverQuote", "");
    const [deliverFile, setDeliverFile] = ctx.useState("deliverFile", "");
    const [wardenBusy, setWardenBusy] = ctx.useState("wardenBusy", false);
    const [showContract, setShowContract] = ctx.useState("showContract", false);
    // 「专注」页：司南、专注时段与钥匙
    const [sinan, setSinan] = ctx.useState("sinan", null);
    const [gate, setGate] = ctx.useState("gate", null);
    const [focusBusy, setFocusBusy] = ctx.useState("focusBusy", false);
    const [focusStatus, setFocusStatus] = ctx.useState("focusStatus", null);
    const [draft, setDraft] = ctx.useState("draft", "");
    const [material, setMaterial] = ctx.useState("material", "");
    const [showMaterial, setShowMaterial] = ctx.useState("showMaterial", false);
    const [sprintGoal, setSprintGoal] = ctx.useState("sprintGoal", "");
    const [openGateRec, setOpenGateRec] = ctx.useState("openGateRec", null);
    const [gateAnswers, setGateAnswers] = ctx.useState("gateAnswers", ["", "", "", "", ""]);
    const focusLoadingRef = ctx.useRef("focusLoadingRef", false);
    const loadingRef = ctx.useRef("loadingRef", false);
    const slimLoadingRef = ctx.useRef("slimLoadingRef", false);
    const wardenLoadingRef = ctx.useRef("wardenLoadingRef", false);
    const chatsLoadingRef = ctx.useRef("chatsLoadingRef", false);
    const autoLoadedRef = ctx.useRef("autoLoadedRef", false);
    const mood = snap ? (0, format_js_1.moodOf)(snap) : null;
    const companionName = snap?.companion?.name ?? "小妹";
    // ---------- 动作 ----------
    async function refresh() {
        if (loadingRef.current)
            return;
        loadingRef.current = true;
        setLoading(true);
        setLoadError("");
        try {
            setSnap(await (0, snapshot_js_1.collectSnapshot)());
        }
        catch (error) {
            setLoadError(errorText(error));
        }
        finally {
            loadingRef.current = false;
            setLoading(false);
        }
    }
    async function refreshChats() {
        if (chatsLoadingRef.current)
            return;
        chatsLoadingRef.current = true;
        setChatsError("");
        try {
            setChats(await (0, nav_js_1.listChats)(""));
        }
        catch (error) {
            setChatsError(errorText(error));
        }
        finally {
            chatsLoadingRef.current = false;
        }
    }
    async function refreshSlim() {
        if (slimLoadingRef.current)
            return;
        slimLoadingRef.current = true;
        setSlimError("");
        try {
            setSlim(await (0, slim_js_1.collectSlimReport)());
        }
        catch (error) {
            setSlimError(errorText(error));
        }
        finally {
            slimLoadingRef.current = false;
        }
    }
    async function refreshWarden() {
        if (wardenLoadingRef.current)
            return;
        wardenLoadingRef.current = true;
        setWardenError("");
        try {
            const report = await (0, warden_js_1.collectWarden)();
            setWarden(report);
            if (report.view.assignment)
                setAssignTask(report.view.assignment.task);
        }
        catch (error) {
            setWardenError(errorText(error));
        }
        finally {
            wardenLoadingRef.current = false;
        }
    }
    async function openManage(tab) {
        setManageTab(tab);
        if (tab === "slim" && slim == null)
            await refreshSlim();
        if (tab === "warden" && warden == null)
            await refreshWarden();
        if (tab === "sys" && snap == null)
            await refresh();
    }
    async function openPage(next) {
        setPage(next);
        if (next === "focus")
            await refreshFocus();
        if (next === "life" && chats == null)
            await refreshChats();
        if (next === "manage")
            await openManage(manageTab);
    }
    async function createAssignment(deadlineMs) {
        if (wardenBusy)
            return;
        const task = assignTask.trim() || snap?.task?.task || "当前任务";
        setWardenBusy(true);
        try {
            await (0, warden_js_1.setAssignment)({ task, deadlineMs, standard: { type: assignStd, detail: "", filePath: assignFile.trim() } });
            setWardenStatus({ ok: true, text: `派活：${task}（演练，不会真锁）` });
            await refreshWarden();
        }
        catch (error) {
            setWardenStatus({ ok: false, text: `没派上：${errorText(error)}` });
        }
        finally {
            setWardenBusy(false);
        }
    }
    async function stopAssignment() {
        if (wardenBusy)
            return;
        setWardenBusy(true);
        try {
            await (0, warden_js_1.clearAssignment)();
            setWardenStatus({ ok: true, text: "撤下了这次督促" });
            await refreshWarden();
        }
        catch (error) {
            setWardenStatus({ ok: false, text: `没撤下：${errorText(error)}` });
        }
        finally {
            setWardenBusy(false);
        }
    }
    async function deliver() {
        if (wardenBusy)
            return;
        const quote = deliverQuote.trim();
        if (!quote && !deliverFile.trim()) {
            await ctx.showToast("写一句你交的是什么，或填个文件路径～");
            return;
        }
        setWardenBusy(true);
        try {
            const { delivery, reason } = await (0, warden_js_1.recordDelivery)(quote || "（交了文件）", deliverFile.trim());
            if (!delivery) {
                setWardenStatus({ ok: false, text: reason ?? "没记上" });
                return;
            }
            setDeliverQuote("");
            setDeliverFile("");
            const verdict = delivery.autoCheck === "PRODUCT_OK" ? "产物核对通过，这次收工" : delivery.autoCheck === "PRODUCT_MISSING" ? "没找到产物或它还是派活前的" : "已收下，够不够要核对模型看（后续阶段）";
            setWardenStatus({ ok: delivery.autoCheck !== "PRODUCT_MISSING", text: verdict });
            await refreshWarden();
        }
        catch (error) {
            setWardenStatus({ ok: false, text: `没记上：${errorText(error)}` });
        }
        finally {
            setWardenBusy(false);
        }
    }
    // 会改设置的按钮都要点两次：第一次只把按钮变成"再点确认"
    async function confirmThen(key, run) {
        if (busyKey)
            return;
        if (confirmKey !== key) {
            setConfirmKey(key);
            return;
        }
        setConfirmKey("");
        setBusyKey(key);
        try {
            setSlimStatus({ ok: true, text: await run() });
            await refreshSlim();
        }
        catch (error) {
            setSlimStatus({ ok: false, text: `没改成：${errorText(error)}` });
        }
        finally {
            setBusyKey("");
        }
    }
    async function runScan() {
        if (busyKey)
            return;
        setBusyKey("scan");
        try {
            setScan(await (0, slim_js_1.scanRedundancy)());
        }
        catch (error) {
            setSlimStatus({ ok: false, text: `扫描失败：${errorText(error)}` });
        }
        finally {
            setBusyKey("");
        }
    }
    async function openChat(chatId) {
        if (openingId)
            return;
        setOpeningId(chatId);
        try {
            await (0, nav_js_1.setMainChat)(chatId);
            await ctx.navigate(nav_js_1.NATIVE_CHAT_ROUTE);
            setStatus({ ok: true, text: `已请求打开对话（${(0, snapshot_js_1.formatClock)(Date.now())}）` });
        }
        catch (error) {
            setStatus({ ok: false, text: `打开对话失败：${errorText(error)}` });
            await ctx.showToast(`打开失败：${errorText(error)}`);
        }
        finally {
            setOpeningId("");
        }
    }
    async function talkToHer() {
        const chat = snap?.companion?.chat;
        if (!chat) {
            setStatus({ ok: false, text: "还没认出她是哪个对话：在「陪伴」页下面的「会话」里点一个对话的「设为她」" });
            return;
        }
        await openChat(chat.id);
    }
    async function voice() {
        if (voiceBusy)
            return;
        setVoiceBusy(true);
        try {
            await (0, nav_js_1.startVoiceWith)(snap?.companion?.chat?.id ?? null);
            setStatus({ ok: true, text: `已请求打开语音球并切到她（${(0, snapshot_js_1.formatClock)(Date.now())}）` });
        }
        catch (error) {
            setStatus({ ok: false, text: `语音没打开：${errorText(error)}` });
        }
        finally {
            setVoiceBusy(false);
        }
    }
    async function sayLine() {
        if (!mood || speaking)
            return;
        setSpeaking(true);
        setStatus({ ok: true, text: "她在开口…" });
        try {
            const result = await (0, speech_js_1.speak)(mood.line, `主控台 ${(0, snapshot_js_1.formatClock)(Date.now())}`);
            setStatus(result.status === "ACCEPTED"
                ? { ok: true, text: result.via === "voice_bar" ? "念完了（用你的 voice_bar）" : "念完了（系统 TTS）" }
                : { ok: false, text: `没念出来：${result.error}` });
        }
        catch (error) {
            setStatus({ ok: false, text: `没念出来：${errorText(error)}` });
        }
        finally {
            setSpeaking(false);
        }
    }
    async function chooseCompanion(entry) {
        try {
            await (0, companion_js_1.setCompanionChat)(entry.id);
            const companion = await (0, companion_js_1.findCompanion)(chats ?? undefined);
            if (snap)
                setSnap({ ...snap, companion });
            setStatus({ ok: true, text: `以后「她」就是「${entry.title}」` });
            await ctx.showToast(`已设为她：${entry.title}`);
        }
        catch (error) {
            await ctx.showToast(`没设上：${errorText(error)}`);
        }
    }
    async function saveProgress() {
        if (savingProgress)
            return;
        const text = progressText.trim();
        if (progressKind === "pause" && !text) {
            await ctx.showToast("暂停要写一句为什么～");
            return;
        }
        setSavingProgress(true);
        try {
            const pending = mood?.pendingCheckin;
            const result = await (0, progress_js_1.recordProgress)({
                kind: progressKind,
                userQuote: text || `（点了「${progress_js_1.KIND_LABEL[progressKind]}」）`,
                note: pending ? `回应 ${pending.iso.slice(11, 16)} 的打卡` : "",
                via: "dashboard",
            });
            if (result.status === "REJECTED") {
                await ctx.showToast(result.reason);
                return;
            }
            setProgressText("");
            await ctx.showToast(result.status === "DUPLICATE" ? "刚刚已经记过这一句啦" : `记下了：${progress_js_1.KIND_LABEL[result.record.kind]}`);
            const records = await (0, progress_js_1.readRecentProgress)(20);
            if (snap)
                setSnap({ ...snap, progress: records });
        }
        catch (error) {
            await ctx.showToast(`没记上：${errorText(error)}`);
        }
        finally {
            setSavingProgress(false);
        }
    }
    // ---------- 「专注」页动作（司南） ----------
    async function refreshFocus() {
        if (focusLoadingRef.current)
            return;
        focusLoadingRef.current = true;
        try {
            const now = Date.now();
            const [v, g] = await Promise.all([(0, sinan_js_1.loadSinan)(now), (0, gate_js_1.loadGate)()]);
            setSinan(v);
            setGate({ focus: (0, gate_js_1.focusNow)(g, now), summary: (0, gate_js_1.gateSummary)(g, now), apps: g.config.heavy_apps, limit: g.config.gate.daily_key_limit || 2 });
        }
        catch (error) {
            setFocusStatus({ ok: false, text: `读取失败：${errorText(error)}` });
        }
        finally {
            focusLoadingRef.current = false;
        }
    }
    async function focusAction(fn) {
        if (focusBusy)
            return;
        setFocusBusy(true);
        try {
            await fn();
        }
        catch (error) {
            setFocusStatus({ ok: false, text: errorText(error) });
        }
        finally {
            setFocusBusy(false);
            await refreshFocus();
        }
    }
    function replyStatus(out) {
        if (!out)
            return null;
        return out.via === "model" ? null : { ok: false, text: `模型没接上，先用本地问题顶上（${out.error || "未知原因"}）` };
    }
    async function doSend() {
        await focusAction(async () => {
            const t = draft.trim();
            if (!t) {
                setFocusStatus({ ok: false, text: sinan && sinan.task ? "先写下你自己的回答" : "先说一件这段时间要做的事" });
                return;
            }
            const out = sinan && sinan.task ? await (0, sinan_js_1.say)(t) : await (0, sinan_js_1.begin)(t, material);
            setDraft("");
            setMaterial("");
            setShowMaterial(false);
            setFocusStatus(replyStatus(out));
        });
    }
    async function doMode(mode) {
        if (sinan && sinan.mode === mode)
            return;
        await focusAction(async () => {
            if (mode === "sprint" && !(sinan && sinan.sprint && !sinan.sprint.over)) {
                await (0, sinan_js_1.switchMode)("sprint").catch(() => null);
                setFocusStatus({ ok: true, text: "写下这次要交出的东西，选个时长" });
                return;
            }
            setFocusStatus(replyStatus(await (0, sinan_js_1.switchMode)(mode)));
            if (mode === "read" && !(sinan && sinan.task && sinan.task.hasMaterial))
                setShowMaterial(true);
        });
    }
    async function doSprint(minutes) {
        await focusAction(async () => {
            const out = await (0, sinan_js_1.sprint)(sprintGoal, minutes);
            setSprintGoal("");
            setFocusStatus(replyStatus(out) || { ok: true, text: `冲刺开始：${minutes} 分钟。到点我会来问你交出了什么` });
        });
    }
    async function doPause() {
        await focusAction(async () => {
            await (0, sinan_js_1.pause)();
            setFocusStatus({ ok: true, text: "好，歇一会儿。15 分钟后我来问你回不回来" });
        });
    }
    async function doResume() {
        await focusAction(async () => {
            await (0, sinan_js_1.resume)();
            setFocusStatus({ ok: true, text: "回来了，接着上次的问题" });
        });
    }
    async function doFinish() {
        if (confirmKey !== "finish") {
            setConfirmKey("finish");
            setFocusStatus({ ok: true, text: "再点一次「收起这件事」确认；对话记录会留着" });
            return;
        }
        setConfirmKey("");
        await focusAction(async () => {
            await (0, sinan_js_1.finish)();
            setFocusStatus({ ok: true, text: "这件事收起来了。下一件是什么？" });
        });
    }
    async function doSpeakQuestion() {
        await focusAction(async () => {
            const q = sinan && sinan.task ? sinan.task.question : "";
            const r = await (0, speech_js_1.speak)(q || "这段时间做什么？", "司南");
            setFocusStatus(r.status === "ACCEPTED" ? { ok: true, text: "念完了" } : { ok: false, text: `没念出来：${r.error}` });
        });
    }
    async function doVoice() {
        await focusAction(async () => {
            const r = await (0, sinan_js_1.ensureNativeChat)();
            await (0, nav_js_1.startVoiceWith)(r.chatId);
            setFocusStatus({ ok: true, text: r.created ? "已建好「司南」对话并打开语音球。说的话会记回这里" : "已打开语音球，切到「司南」" });
        });
    }
    async function doTextNative() {
        await focusAction(async () => {
            const r = await (0, sinan_js_1.ensureNativeChat)();
            await (0, nav_js_1.setMainChat)(r.chatId);
            await ctx.navigate(nav_js_1.NATIVE_CHAT_ROUTE);
        });
    }
    async function doOpenGate(app) {
        await focusAction(async () => {
            const d = await (0, gate_js_1.openGate)(app.package);
            if (d.status === "asking") {
                setOpenGateRec(d.gate);
                setGateAnswers(["", "", "", "", ""]);
                setFocusStatus({ ok: true, text: "回答这三个问题，认真答完就给钥匙" });
            }
            else if (d.status === "cooldown")
                setFocusStatus({ ok: false, text: `还在冷却，${d.minutes_left} 分钟后再来` });
            else if (d.status === "limit")
                setFocusStatus({ ok: false, text: `今天的钥匙用完了（${d.issued}/${d.limit}）` });
            else if (d.status === "key_active")
                setFocusStatus({ ok: true, text: "这把钥匙还在用" });
            else
                setFocusStatus({ ok: true, text: d.note || "不需要钥匙" });
        });
    }
    async function doSubmitGate() {
        await focusAction(async () => {
            const g = openGateRec;
            const r = await (0, gate_js_1.submitGate)(g.id, gateAnswers.slice(0, g.questions.length));
            if (r.status === "granted") {
                setOpenGateRec(null);
                setFocusStatus({ ok: true, text: `钥匙给你：${r.key.minutes} 分钟。到点我会提醒你回来做：${r.key.back_to}（锁定还没接通，这次只记账）` });
            }
            else
                setFocusStatus({ ok: false, text: `再具体一点：${r.problems.join("；")}` });
        });
    }
    // ---------- 基础组件 ----------
    function text(value, style, color, extra = {}) {
        return UI.Text({ text: value, style: style, color, ...extra });
    }
    function muted(value, maxLines) {
        return text(value, "bodySmall", colors.onSurfaceVariant, maxLines ? { maxLines } : {});
    }
    function card(children, spacing = 10, containerColor = colors.surface) {
        return UI.Card({ fillMaxWidth: true, containerColor, elevation: 0, shape: { cornerRadius: 20 } }, UI.Column({ fillMaxWidth: true, padding: 18, spacing }, children));
    }
    function label(value) {
        return text(value, "labelMedium", colors.onSurfaceVariant);
    }
    function block(color, props) {
        return UI.Box({ background: color, backgroundShape: { cornerRadius: 5 }, ...props });
    }
    function pill(value, bg, ink, onClick) {
        const inner = text(value, "labelMedium", ink, { paddingHorizontal: 12, paddingVertical: 5 });
        return UI.Surface({ containerColor: bg, shape: { type: "pill" }, ...(onClick ? { onClick } : {}) }, inner);
    }
    function pageColumn(items) {
        return UI.LazyColumn({ weight: 1, fillMaxWidth: true, padding: { horizontal: 16, vertical: 8 }, spacing: 12 }, items);
    }
    function pageHeader(title, trailing = []) {
        return UI.Row({ fillMaxWidth: true, paddingStart: 4, paddingTop: 8, paddingBottom: 4, verticalAlignment: "center", spacing: 8 }, [
            text(title, "headlineSmall", colors.onSurface, { weight: 1 }),
            ...trailing,
            UI.IconButton({ icon: Icons.Refresh, enabled: !loading, onClick: page === "life" ? () => Promise.all([refresh(), refreshChats()]) : page === "manage" ? (manageTab === "slim" ? refreshSlim : manageTab === "warden" ? refreshWarden : refresh) : refresh }),
        ]);
    }
    function spinner() {
        return UI.Row({ fillMaxWidth: true, padding: 32, horizontalArrangement: "center" }, [UI.CircularProgressIndicator({})]);
    }
    // ---------- 专注（司南） ----------
    function gradientCard(pal, children, radius = 28, spacing = 14) {
        // 外层卡片的底色兜底；里层渐变（旧版宿主不认 backgroundBrush 时就显示底色）
        return UI.Card({ fillMaxWidth: true, containerColor: pal.mid, elevation: 6, shape: { cornerRadius: radius } }, UI.Column({ fillMaxWidth: true, backgroundBrush: { type: "verticalGradient", colors: pal.grad } }, [
            UI.Column({ fillMaxWidth: true, padding: 20, spacing }, children),
        ]));
    }
    function bar(fraction, fill, track, height = 6) {
        const done = Math.min(1, Math.max(0, fraction));
        return UI.Row({ fillMaxWidth: true, height, background: track, backgroundShape: { type: "pill" } }, [
            ...(done > 0.005 ? [UI.Box({ weight: done, height, background: fill, backgroundShape: { type: "pill" } })] : []),
            ...(done < 0.995 ? [UI.Box({ weight: 1 - done, height })] : []),
        ]);
    }
    function minutesOf(hhmm) {
        const m = /^(\d{1,2}):(\d{2})$/.exec(String(hhmm || ""));
        return m ? Number(m[1]) * 60 + Number(m[2]) : null;
    }
    function sinanAvatar(pal, size = 52) {
        return UI.Surface({ width: size, height: size, shape: { type: "circle" }, containerColor: GOLD, shadowElevation: 4 }, UI.Box({ fillMaxSize: true, contentAlignment: "center", backgroundBrush: { type: "verticalGradient", colors: GOLD_GRAD }, backgroundShape: { type: "circle" } }, [
            text("司", size > 40 ? "titleLarge" : "labelLarge", pal.deep, { fontWeight: "bold" }),
        ]));
    }
    function modeChip(pal, key) {
        const on = sinan && sinan.mode === key;
        const m = sinan_logic_js_1.MODES[key];
        return UI.Surface({ weight: 1, shape: { type: "pill" }, containerColor: on ? ON_DARK : pal.chip, onClick: () => doMode(key) }, UI.Box({ fillMaxWidth: true, paddingVertical: 8, contentAlignment: "center" }, [text(m.name, "labelLarge", on ? pal.deep : ON_DARK)]));
    }
    function hero() {
        const f = gate ? gate.focus : null;
        const active = !!(f && f.active);
        const pal = active ? PAL.focus : PAL.calm;
        const v = sinan;
        const t = v ? v.task : null;
        const status = !f ? "读取中…" : active ? `专注中 · ${f.start}–${f.end} · 还剩 ${f.minutes_left} 分` : `平时 · 下一段${f.next_is_tomorrow ? "明天 " : " "}${f.next || "—"}`;
        const items = [
            UI.Row({ fillMaxWidth: true, spacing: 14, verticalAlignment: "center" }, [
                sinanAvatar(pal),
                UI.Column({ weight: 1, spacing: 2 }, [
                    text("司南", "titleLarge", ON_DARK, { fontWeight: "bold" }),
                    text(status, "labelMedium", pal.soft, { maxLines: 1 }),
                ]),
                UI.Surface({ shape: { type: "circle" }, containerColor: pal.chip, onClick: refreshFocus }, text("↻", "titleMedium", ON_DARK, { padding: 10 })),
            ]),
        ];
        if (active) {
            const span = (minutesOf(f.end) ?? 0) - (minutesOf(f.start) ?? 0);
            items.push(bar(span > 0 ? 1 - f.minutes_left / span : 0, GOLD, pal.chip));
        }
        // 当前问题：整张卡的主角
        items.push(UI.Column({ fillMaxWidth: true, spacing: 6, paddingTop: 4 }, [
            text(t ? `在做 · ${t.title}` : "还没开始", "labelMedium", pal.soft, { maxLines: 1 }),
            text(!v ? "…" : !t ? "这段时间做什么？" : t.paused ? "歇着呢。回来就接着上次的问题。" : t.question || "司南在想第一个问题…", "headlineSmall", ON_DARK, { fontWeight: "bold" }),
        ]));
        if (v && v.sprint) {
            const s = v.sprint;
            items.push(UI.Column({ fillMaxWidth: true, spacing: 6 }, [
                text(s.over ? `冲刺到点 · ${s.goal}` : `冲刺 · ${s.goal} · 还剩 ${s.minutes_left} 分`, "labelLarge", GOLD, { maxLines: 2 }),
                bar(s.progress, GOLD, pal.chip, 4),
            ]));
        }
        if (gate) {
            const sum = gate.summary, limit = gate.limit || 2, left = sum.keys_left_today;
            const dots = Array.from({ length: limit }, (_, i) => UI.Box({ width: 10, height: 10, background: i < left ? GOLD : pal.chip, backgroundShape: { type: "circle" } }));
            items.push(UI.Row({ fillMaxWidth: true, spacing: 6, verticalAlignment: "center" }, [
                text("钥匙", "labelMedium", pal.soft),
                ...dots,
                text(sum.cooldown_minutes_left ? `冷却 ${sum.cooldown_minutes_left} 分` : sum.active_keys.length ? `${sum.active_keys[0].app} 还能用 ${sum.active_keys[0].minutes_left} 分` : "", "labelMedium", pal.soft, { weight: 1, maxLines: 1 }),
            ]));
        }
        items.push(UI.Row({ fillMaxWidth: true, spacing: 6 }, sinan_logic_js_1.MODE_ORDER.map((k) => modeChip(pal, k))));
        return gradientCard(pal, items);
    }
    function eventLine(r) {
        const what = { begin: `开始：${r.text}`, mode: `换成${r.text}`, sprint: `冲刺 ${r.text}`, pause: "歇一会儿", resume: "回来了", finish: `收起：${r.text}` }[r.type] || r.text;
        return UI.Row({ fillMaxWidth: true, horizontalArrangement: "center", paddingVertical: 2 }, [
            UI.Surface({ shape: { type: "pill" }, containerColor: colors.surfaceVariant }, text(`${clock(r.ts)} · ${what}`, "labelSmall", colors.onSurfaceVariant, { paddingHorizontal: 12, paddingVertical: 4, maxLines: 1 })),
        ]);
    }
    function bubble(r) {
        if (r.role === "event")
            return eventLine(r);
        if (r.role === "user") {
            return UI.Row({ fillMaxWidth: true, paddingStart: 48 }, [
                UI.Box({ weight: 1 }),
                UI.Surface({ containerColor: colors.primary, shape: { topStart: 20, topEnd: 20, bottomStart: 20, bottomEnd: 6 } }, UI.Column({ padding: 12, spacing: 2 }, [
                    text(r.text, "bodyLarge", colors.onPrimary),
                    text(`${clock(r.ts)}${r.via === "voice" ? " · 语音" : ""}`, "labelSmall", colors.onPrimary),
                ])),
            ]);
        }
        return UI.Row({ fillMaxWidth: true, paddingEnd: 32, spacing: 8, verticalAlignment: "top" }, [
            sinanAvatar(PAL.focus, 30),
            UI.Surface({ weight: 1, containerColor: colors.surfaceVariant, shape: { topStart: 6, topEnd: 20, bottomStart: 20, bottomEnd: 20 } }, UI.Column({ padding: 12, spacing: 2 }, [
                text(r.text, "bodyLarge", colors.onSurface),
                ...(r.via === "local" ? [text("模型没接上，这句是本地备用问题", "labelSmall", MOOD_INK[2])] : []),
            ])),
        ]);
    }
    function clock(ts) {
        return new Date(ts + 8 * 3600000).toISOString().slice(11, 16);
    }
    function threadCard() {
        const rows = (sinan ? sinan.thread : []).slice(-THREAD_SHOWN);
        if (!rows.length)
            return null;
        return UI.Column({ fillMaxWidth: true, spacing: 10, paddingVertical: 4 }, rows.map(bubble));
    }
    function composer() {
        const v = sinan;
        const t = v ? v.task : null;
        const items = [];
        if (t && t.paused) {
            items.push(UI.Button({ fillMaxWidth: true, text: focusBusy ? "处理中…" : "我回来了", enabled: !focusBusy, onClick: doResume }));
            return card(items, 10);
        }
        if (v && v.mode === "sprint" && !(v.sprint && !v.sprint.over)) {
            items.push(label("这次冲刺要交出什么"));
            items.push(UI.TextField({ fillMaxWidth: true, value: sprintGoal, onValueChange: setSprintGoal, placeholder: "看得见、能检查的东西：比如 做完第3讲例题1-5" }));
            items.push(UI.Row({ fillMaxWidth: true, spacing: 8 }, sinan_logic_js_1.SPRINT_MINUTES.map((m) => UI.Button({ weight: 1, text: `${m} 分钟`, enabled: !focusBusy, onClick: () => doSprint(m) }))));
            return card(items, 10);
        }
        items.push(UI.TextField({ fillMaxWidth: true, value: draft, onValueChange: setDraft, placeholder: t ? "用你自己的话回答（半句也行）" : "说一件这段时间要做的具体的事", minLines: 2 }));
        if (showMaterial)
            items.push(UI.TextField({ fillMaxWidth: true, value: material, onValueChange: setMaterial, placeholder: "贴材料：文字或链接（开始时一起交给司南）", minLines: 3 }));
        items.push(UI.Row({ fillMaxWidth: true, spacing: 8, verticalAlignment: "center" }, [
            UI.Button({ weight: 1, text: focusBusy ? "司南在想…" : t ? "发送" : "开始", enabled: !focusBusy, onClick: doSend }),
            UI.FilledTonalButton({ enabled: !focusBusy, onClick: doSpeakQuestion }, text("🔈", "labelLarge", colors.onSurface)),
            UI.FilledTonalButton({ enabled: !focusBusy, onClick: doVoice }, text("🎙", "labelLarge", colors.onSurface)),
        ]));
        const links = [];
        if (!t && !showMaterial)
            links.push(UI.TextButton({ onClick: () => setShowMaterial(true) }, text("＋ 贴材料", "labelLarge", colors.primary)));
        if (t) {
            links.push(UI.TextButton({ enabled: !focusBusy, onClick: doPause }, text("Ⅱ 歇一会儿", "labelLarge", colors.primary)));
            links.push(UI.TextButton({ enabled: !focusBusy, onClick: doFinish }, text(confirmKey === "finish" ? "再点确认" : "收起这件事", "labelLarge", confirmKey === "finish" ? MOOD_INK[3] : colors.onSurfaceVariant)));
        }
        links.push(UI.TextButton({ enabled: !focusBusy, onClick: doTextNative }, text("打字长聊", "labelLarge", colors.onSurfaceVariant)));
        items.push(UI.Row({ fillMaxWidth: true, spacing: 0, verticalAlignment: "center" }, links));
        return card(items, 8);
    }
    function gateCard() {
        const g = gate;
        if (!g || !(g.focus.active || openGateRec))
            return null;
        const items = [UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
                text("想刷一会儿？", "titleMedium", colors.onSurface, { weight: 1 }),
                text(`今天还剩 ${g.summary.keys_left_today} 把`, "labelMedium", colors.onSurfaceVariant),
            ])];
        if (openGateRec) {
            openGateRec.questions.forEach((q, i) => items.push(UI.TextField({
                fillMaxWidth: true,
                value: gateAnswers[i],
                onValueChange: (val) => { const a = gateAnswers.slice(); a[i] = val; setGateAnswers(a); },
                placeholder: `${i + 1}. ${q}`,
            })));
            items.push(UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                UI.Button({ weight: 1, text: "交上", enabled: !focusBusy, onClick: doSubmitGate }),
                UI.TextButton({ onClick: () => setOpenGateRec(null) }, text("先不要了", "labelLarge", colors.onSurfaceVariant)),
            ]));
        }
        else {
            items.push(muted("专注时段里这些 App 要先回答三个问题，拿一把限时钥匙。"));
            items.push(UI.Row({ fillMaxWidth: true, spacing: 8 }, g.apps.slice(0, 4).map((a) => pill(a.name, colors.surfaceVariant, colors.onSurface, () => doOpenGate(a)))));
        }
        items.push(muted("真锁还没接通：钥匙现在只记账，不改手机限制。"));
        return UI.Card({ fillMaxWidth: true, containerColor: colors.surface, elevation: 0, border: { width: 1, color: GOLD }, shape: { cornerRadius: 20 } }, UI.Column({ fillMaxWidth: true, padding: 18, spacing: 10 }, items));
    }
    function focusPage() {
        const items = [hero()];
        if (focusStatus)
            items.push(UI.Surface({ fillMaxWidth: true, shape: { cornerRadius: 14 }, containerColor: focusStatus.ok ? MOOD_TINT[0] : MOOD_TINT[3] }, text(focusStatus.text, "bodyMedium", focusStatus.ok ? MOOD_INK[0] : MOOD_INK[3], { padding: 12 })));
        const thread = threadCard();
        if (thread)
            items.push(thread);
        else if (sinan && !sinan.task)
            items.push(muted(`${sinan_logic_js_1.MODES[sinan.mode].name}：${sinan_logic_js_1.MODES[sinan.mode].hint}`));
        items.push(composer());
        const gc = gateCard();
        if (gc)
            items.push(gc);
        return UI.LazyColumn({ weight: 1, fillMaxWidth: true, padding: { horizontal: 16, vertical: 12 }, spacing: 12, autoScrollToEnd: false }, items);
    }
    // ---------- 今天 ----------
    function systemIssues(s) {
        const health = s.health.filter((h) => h.status === "STALE" || h.status === "MISSING").length;
        const flows = s.workflows.filter((w) => w.health === "FAILED" || w.health === "STALE").length;
        return health + flows;
    }
    function pendingBanner() {
        const pending = mood?.pendingCheckin;
        if (!pending)
            return null;
        return card([
            text(`🎭 ${pending.iso.slice(11, 16)} ${companionName}来找过你`, "titleSmall", MOOD_INK[1]),
            text(`“${pending.line}”`, "bodyMedium", colors.onSurface),
            muted("点下面任意一个动作回应她，她就知道你看到了。"),
        ], 6, MOOD_TINT[1]);
    }
    function avatar(level) {
        return UI.Card({
            width: 64,
            height: 64,
            containerColor: "#FBE4DC",
            shape: { type: "circle" },
            border: { width: 3, color: MOOD_COLOR[level] },
            elevation: 0,
        }, UI.Box({ fillMaxSize: true, contentAlignment: "center" }, [text("🎭", "headlineMedium", colors.onSurface)]));
    }
    function meter(score, level) {
        const left = Math.max(score, 0.5);
        const right = Math.max(100 - score, 0.5);
        return UI.Column({ fillMaxWidth: true, spacing: 4 }, [
            UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
                UI.Box({ weight: left, height: 14 }),
                UI.Box({ width: 14, height: 14, background: MOOD_COLOR[level], backgroundShape: { type: "circle" } }),
                UI.Box({ weight: right, height: 14 }),
            ]),
            UI.Row({ fillMaxWidth: true, spacing: 3 }, ZONE_WEIGHTS.map((w, i) => block(MOOD_COLOR[i], { weight: w, height: 10, backgroundShape: { type: "pill" } }))),
            UI.Row({ fillMaxWidth: true }, ["安心", "在意", "担心", "要谈谈"].map((name, i) => text(name, "labelSmall", i === level ? MOOD_INK[i] : colors.onSurfaceVariant, { weight: ZONE_WEIGHTS[i] }))),
        ]);
    }
    function herCard(s) {
        const m = (0, format_js_1.moodOf)(s);
        const hasChat = Boolean(s.companion?.chat);
        return card([
            UI.Row({ fillMaxWidth: true, spacing: 14, verticalAlignment: "center" }, [
                avatar(m.level),
                UI.Column({ weight: 1, spacing: 6 }, [
                    text(companionName, "titleLarge", colors.onSurface),
                    UI.Row({ spacing: 6 }, [pill(m.name, MOOD_TINT[m.level], MOOD_INK[m.level])]),
                ]),
            ]),
            text(`“${m.line}”`, "bodyLarge", colors.onSurface, { paddingTop: 4 }),
            muted(`为什么是「${m.name}」：${m.reasons.join(" · ")}`, 3),
            meter(m.score, m.level),
            UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                UI.Button({ text: openingId ? "打开中…" : "找她聊", weight: 1, enabled: hasChat && !openingId, onClick: talkToHer }),
                UI.FilledTonalButton({ weight: 1, enabled: !voiceBusy, onClick: voice }, text(voiceBusy ? "打开中…" : "🎙 语音聊", "labelLarge", colors.onSurface)),
                UI.FilledTonalButton({ weight: 1, enabled: !speaking, onClick: sayLine }, text(speaking ? "念着…" : "🔈 念一句", "labelLarge", colors.onSurface)),
            ]),
            ...(status ? [text(status.text, "bodySmall", status.ok ? MOOD_INK[0] : MOOD_INK[3])] : []),
            ...(hasChat ? [muted(`她 = 「${s.companion?.chat?.title}」${s.companion?.source === "chosen" ? "（你选的）" : ""}`, 1)] : [muted("还没认出她是哪个对话，在下面「会话」里点一个对话的「设为她」")]),
        ]);
    }
    function actionTile(kind, icon) {
        const on = progressKind === kind;
        return UI.Surface({
            weight: 1,
            containerColor: on ? colors.primaryContainer : colors.surfaceVariant,
            shape: { cornerRadius: 14 },
            onClick: () => setProgressKind(kind),
        }, UI.Column({ fillMaxWidth: true, paddingVertical: 10, horizontalAlignment: "center", spacing: 2 }, [
            text(icon, "titleMedium", on ? colors.primary : colors.onSurface),
            text(progress_js_1.KIND_LABEL[kind], "labelLarge", on ? colors.primary : colors.onSurface),
        ]));
    }
    function taskCard(s) {
        return card([
            label("当前任务"),
            text(s.task?.task || "还没有声明任务", "titleLarge", colors.onSurface),
            UI.Row({ fillMaxWidth: true, spacing: 8 }, ACTIONS.map((a) => actionTile(a.kind, a.icon))),
            UI.TextField({
                fillMaxWidth: true,
                value: progressText,
                onValueChange: setProgressText,
                placeholder: progressKind === "pause" ? "暂停要说为什么" : "一句话（可不写），比如：又投了一家",
            }),
            UI.Button({
                fillMaxWidth: true,
                text: savingProgress ? "记录中…" : `记下「${progress_js_1.KIND_LABEL[progressKind]}」`,
                enabled: !savingProgress,
                onClick: saveProgress,
            }),
        ]);
    }
    function hourColor(bucket) {
        if (bucket.on === 0 && bucket.off === 0)
            return colors.surfaceVariant;
        if (bucket.on > bucket.off)
            return MOOD_COLOR[0];
        if (bucket.off > bucket.on)
            return MOOD_COLOR[2];
        return MOOD_COLOR[1];
    }
    function dayCard(s) {
        const focus = s.events?.focus;
        const known = focus ? focus.onTask + focus.offTask : 0;
        const today = new Date(s.generatedAt).toDateString();
        const todayProgress = (s.progress ?? []).filter((p) => new Date(p.ts * 1000).toDateString() === today);
        const stat = (value, key) => UI.Surface({ weight: 1, containerColor: colors.surfaceVariant, shape: { cornerRadius: 12 } }, UI.Column({ fillMaxWidth: true, padding: 10, spacing: 2 }, [
            text(value, "titleMedium", colors.onSurface, { maxLines: 1 }),
            text(key, "labelSmall", colors.onSurfaceVariant),
        ]));
        const children = [label("今天的你")];
        if (focus) {
            children.push(UI.Row({ fillMaxWidth: true, spacing: 2 }, focus.hours.map((b) => block(hourColor(b), { weight: 1, height: 24, backgroundShape: { cornerRadius: 4 } }))), UI.Row({ fillMaxWidth: true }, ["0", "6", "12", "18", "24"].map((h, i) => text(h, "labelSmall", colors.onSurfaceVariant, i < 4 ? { weight: 1 } : {}))));
        }
        else {
            children.push(muted("今天的采样没读到，见系统页"));
        }
        children.push(UI.Row({ fillMaxWidth: true, spacing: 8 }, [
            stat(known > 0 ? `${Math.round(((focus?.onTask ?? 0) / known) * 100)}%` : "—", "采样在任务上"),
            stat(String(todayProgress.length), "条进展"),
            stat(focus?.offApps[0]?.name ?? "—", "偏离时最常开"),
        ]));
        for (const p of todayProgress.slice(0, 5)) {
            children.push(UI.Row({ fillMaxWidth: true, spacing: 10, verticalAlignment: "center" }, [
                text(p.iso.slice(11, 16), "labelMedium", colors.onSurfaceVariant),
                pill(progress_js_1.KIND_LABEL[p.kind] ?? p.kind, p.kind === "stuck" ? MOOD_TINT[3] : colors.primaryContainer, p.kind === "stuck" ? MOOD_INK[3] : colors.primary),
                text(`「${p.user_quote}」`, "bodyMedium", colors.onSurface, { weight: 1, maxLines: 2 }),
            ]));
        }
        children.push(muted("色块是前台采样口径：绿=在任务上，橙=不在，灰=没数据或看不出"));
        return card(children);
    }
    function lifePage() {
        const issues = snap ? systemIssues(snap) : 0;
        const chip = snap
            ? issues > 0
                ? pill(`系统 · ${issues} 处要看`, MOOD_TINT[3], MOOD_INK[3], () => { setPage("manage"); return openManage("sys"); })
                : pill("系统正常", MOOD_TINT[0], MOOD_INK[0], () => { setPage("manage"); return openManage("sys"); })
            : null;
        const items = [pageHeader("陪伴", chip ? [chip] : [])];
        if (loadError)
            items.push(card([text(`读取失败：${loadError}`, "bodyMedium", colors.error)]));
        if (!snap) {
            items.push(spinner());
        }
        else {
            const banner = pendingBanner();
            if (banner)
                items.push(banner);
            items.push(herCard(snap), taskCard(snap), dayCard(snap));
        }
        items.push(...chatsItems());
        return pageColumn(items);
    }
    // ---------- 会话 ----------
    function chatRow(entry, icon, canChoose = false) {
        const updated = parseTime(entry.updatedAt);
        const meta = [
            `${entry.messageCount} 条`,
            ...(entry.characterCardName ? [entry.characterCardName] : []),
            updated ? (0, snapshot_js_1.formatAgo)(updated, Date.now()) : entry.updatedAt,
        ].join(" · ");
        return UI.Surface({ fillMaxWidth: true, containerColor: colors.surface, onClick: () => openChat(entry.id) }, UI.Row({ fillMaxWidth: true, paddingVertical: 10, spacing: 12, verticalAlignment: "center" }, [
            UI.Surface({ width: 40, height: 40, containerColor: colors.surfaceVariant, shape: { type: "circle" } }, UI.Box({ fillMaxSize: true, contentAlignment: "center" }, [text(icon, "titleMedium", colors.onSurface)])),
            UI.Column({ weight: 1, spacing: 2 }, [
                text(entry.title, "bodyLarge", colors.onSurface, { maxLines: 1 }),
                muted(meta, 1),
            ]),
            ...(openingId === entry.id
                ? [text("打开中…", "labelMedium", colors.primary)]
                : entry.messageCount >= nav_js_1.LONG_CHAT_MESSAGES
                    ? [text("太长了", "labelMedium", MOOD_INK[3])]
                    : entry.isCurrent
                        ? [text("当前", "labelMedium", colors.primary)]
                        : []),
            ...(canChoose
                ? [UI.TextButton({ onClick: () => chooseCompanion(entry) }, text("设为她", "labelMedium", colors.primary))]
                : []),
        ]));
    }
    function chatGroup(title, rows, note) {
        return card([label(title), ...(note ? [muted(note)] : []), ...rows], 2);
    }
    function chatsItems() {
        const items = [
            UI.Row({ fillMaxWidth: true, paddingStart: 4, paddingTop: 12 }, [text("会话", "titleLarge", colors.onSurface)]),
            UI.TextField({ fillMaxWidth: true, value: query, onValueChange: setQuery, placeholder: "搜索对话", singleLine: true }),
        ];
        if (chatsError)
            items.push(card([text(`读取对话失败：${chatsError}`, "bodyMedium", colors.error)]));
        if (chats == null) {
            items.push(spinner());
            return items;
        }
        const herId = snap?.companion?.chat?.id ?? "";
        const keyword = query.trim();
        if (keyword) {
            const matches = chats.filter((c) => c.title.includes(keyword));
            items.push(matches.length > 0 ? chatGroup(`搜索结果 ${matches.length}`, matches.map((c) => chatRow(c, "💬", c.id !== herId))) : muted("没有匹配的对话"));
            return items;
        }
        const her = chats.filter((c) => c.id === herId);
        const backstage = chats.filter((c) => (0, nav_js_1.isPinned)(c) && c.id !== herId);
        const recentAll = chats.filter((c) => !(0, nav_js_1.isPinned)(c) && c.id !== herId).slice(0, RECENT_CHATS);
        const recent = showAllChats ? recentAll : recentAll.slice(0, COLLAPSED_CHATS);
        items.push(her.length > 0
            ? chatGroup("她（主入口）", her.map((c) => chatRow(c, "🎭")))
            : chatGroup("她（主入口）", [muted("还没认出她，在下面点一个对话的「设为她」")]));
        if (backstage.length > 0) {
            items.push(chatGroup("后台角色", backstage.map((c) => chatRow(c, "⚙", true)), "平时不用直接找它们"));
        }
        items.push(chatGroup(`最近 ${recentAll.length} 个`, [
            ...recent.map((c) => chatRow(c, "💬", true)),
            ...(recentAll.length > COLLAPSED_CHATS ? [UI.TextButton({ onClick: () => setShowAllChats(!showAllChats) }, text(showAllChats ? "收起" : `展开全部 ${recentAll.length} 个`, "labelLarge", colors.primary))] : []),
        ]));
        return items;
    }
    // ---------- 系统 ----------
    function healthRow(h, now) {
        const statusText = { FRESH: "正常", STALE: "偏旧", MISSING: "缺失", UNKNOWN: "未知" }[h.status];
        const color = h.status === "FRESH" ? MOOD_INK[0] : h.status === "STALE" ? MOOD_INK[2] : MOOD_INK[3];
        return UI.Row({ fillMaxWidth: true, paddingVertical: 6, spacing: 10, verticalAlignment: "center" }, [
            UI.Column({ weight: 1, spacing: 1 }, [
                text(h.label, "bodyLarge", colors.onSurface),
                muted([h.cadence, h.note].filter(Boolean).join(" · "), 1),
            ]),
            UI.Column({ horizontalAlignment: "end", spacing: 1 }, [
                text(statusText, "labelLarge", color),
                muted(h.modifiedAt != null ? (0, snapshot_js_1.formatAgo)(h.modifiedAt, now) : "—"),
            ]),
        ]);
    }
    function healthColor(health) {
        if (health === "FAILED" || health === "STALE")
            return MOOD_INK[3];
        if (health === "OK" || health === "RUNNING_NOW")
            return MOOD_INK[0];
        return colors.onSurfaceVariant;
    }
    function workflowRow(row, now) {
        const failRate = row.total > 0 ? row.failed / row.total : 0;
        const meta = [
            row.lastExecutionTime != null ? `${(0, snapshot_js_1.formatDateTime)(row.lastExecutionTime)}（${(0, snapshot_js_1.formatAgo)(row.lastExecutionTime, now)}）` : "",
            row.note,
        ].filter(Boolean).join(" · ");
        return UI.Column({ fillMaxWidth: true, spacing: 2, paddingVertical: 6 }, [
            UI.Row({ fillMaxWidth: true, spacing: 8, verticalAlignment: "center" }, [
                text(row.name, "bodyLarge", colors.onSurface, { weight: 1, maxLines: 1 }),
                text(format_js_1.HEALTH_LABEL[row.health], "labelLarge", healthColor(row.health)),
            ]),
            ...(meta ? [muted(meta, 2)] : []),
            ...(row.total > 0
                ? [
                    text(`成功 ${row.success} · 失败 ${row.failed} · 累计 ${row.total}${failRate >= 0.2 ? ` · 失败率 ${Math.round(failRate * 100)}%` : ""}`, "bodySmall", failRate >= 0.2 ? MOOD_INK[3] : colors.onSurfaceVariant),
                ]
                : []),
        ]);
    }
    function sysItems() {
        const items = [];
        if (!snap) {
            items.push(spinner());
            return items;
        }
        const s = snap;
        const now = s.generatedAt;
        items.push(card([label("体检 · 看关键文件多久没更新"), ...s.health.map((h) => healthRow(h, now)), muted("更新了不代表内容一定对；没更新基本说明那条链停了。")], 2));
        const rows = s.workflows;
        const visible = showAllWorkflows ? rows : rows.slice(0, COLLAPSED_WORKFLOWS);
        items.push(card([
            UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
                text("工作流", "titleMedium", colors.onSurface, { weight: 1 }),
                ...(rows.length > COLLAPSED_WORKFLOWS
                    ? [UI.TextButton({ onClick: () => setShowAllWorkflows(!showAllWorkflows) }, text(showAllWorkflows ? "收起" : `全部 ${rows.length} 个`, "labelLarge", colors.primary))]
                    : []),
            ]),
            muted("有问题的排前面。成功只代表执行层返回成功，不代表动作生效或你已看到。"),
            ...visible.map((row) => workflowRow(row, now)),
        ], 2));
        if (s.usage) {
            const max = Math.max(1, ...s.usage.rows.map((r) => r.foregroundMinutes));
            items.push(card([
                label(`App 使用 · 过去 ${s.usage.windowHours} 小时（系统记录）`),
                ...s.usage.rows.map((r) => UI.Column({ fillMaxWidth: true, spacing: 4 }, [
                    UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                        text(r.appName, "bodyMedium", colors.onSurface, { weight: 1, maxLines: 1 }),
                        text(`${r.foregroundMinutes} 分钟`, "labelLarge", colors.onSurface),
                        muted(`最近 ${(0, snapshot_js_1.formatClock)(r.lastTimeUsed)}`),
                    ]),
                    UI.LinearProgressIndicator({ fillMaxWidth: true, progress: r.foregroundMinutes / max }),
                ])),
            ]));
        }
        if (s.events) {
            items.push(card([
                label(`最近事件 · ${s.events.date} · 共 ${s.events.totalLines} 行`),
                ...s.events.rows.slice(0, 10).map((r) => UI.Column({ fillMaxWidth: true, spacing: 1 }, [
                    UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                        text(r.type, "labelLarge", colors.primary),
                        text(`${r.time}${r.count > 1 ? ` ×${r.count}` : ""}`, "labelMedium", colors.onSurfaceVariant, { weight: 1 }),
                        muted(r.src, 1),
                    ]),
                    ...(r.summary ? [muted(r.summary, 2)] : []),
                ])),
            ]));
        }
        const more = [
            UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
                text("动作记录与数据源", "titleMedium", colors.onSurface, { weight: 1 }),
                UI.TextButton({ onClick: () => setShowMore(!showMore) }, text(showMore ? "收起" : "展开", "labelLarge", colors.primary)),
            ]),
        ];
        if (showMore) {
            more.push(label("最近动作（历史记录，不代表当前状态）"));
            for (const cells of s.actions?.rows ?? [])
                more.push(muted(cells.join(" · "), 2));
            more.push(label("数据源"));
            for (const src of s.sources) {
                more.push(text(`${src.label}：${format_js_1.SOURCE_LABEL[src.status]}${src.status === "OK" ? "" : ` · ${src.detail}`}`, "bodySmall", src.status === "OK" ? colors.onSurfaceVariant : colors.error));
            }
            more.push(muted(`心情由采样、偏移和你的进展算出，只用来呈现，不会执行动作。读取于 ${(0, snapshot_js_1.formatDateTime)(now)}。`));
        }
        items.push(card(more, 6));
        return items;
    }
    // ---------- 省流 ----------
    function actionButton(key, labelText, onClick, danger = false) {
        const confirming = confirmKey === key;
        const busy = busyKey === key;
        const shown = busy ? "处理中…" : confirming ? "再点确认" : labelText;
        return UI.TextButton({ enabled: !busyKey || busy, onClick }, text(shown, "labelLarge", confirming || danger ? MOOD_INK[3] : colors.primary));
    }
    function flag(value, level) {
        return pill(value, MOOD_TINT[level], MOOD_INK[level]);
    }
    function chatCostRow(row, baselineAt) {
        const flags = [];
        if (row.isHer)
            flags.push(flag("她", 0));
        if (row.backstage)
            flags.push(flag("后台角色", 1));
        if (row.lastSummaryAt === null && row.messages > 30)
            flags.push(flag("从没压缩", 3));
        const growth = row.growth != null && baselineAt != null && row.growth > 0 ? ` · 自 ${(0, snapshot_js_1.formatDateTime)(baselineAt)} +${(0, slim_js_1.formatTokens)(row.growth)}` : "";
        return UI.Column({ fillMaxWidth: true, spacing: 3, paddingVertical: 6 }, [
            UI.Row({ fillMaxWidth: true, spacing: 6, verticalAlignment: "center" }, [
                text(row.title, "bodyLarge", colors.onSurface, { weight: 1, maxLines: 1 }),
                ...flags,
            ]),
            muted(`${row.messages} 条 · 累计输入 ${(0, slim_js_1.formatTokens)(row.input)} · 平均每条 ${(0, slim_js_1.formatTokens)(row.perMessage)}${growth}`, 2),
            ...(row.card ? [muted(`角色卡：${row.card}`, 1)] : []),
        ]);
    }
    function cardRow(c) {
        const run = (preset) => () => confirmThen(`card:${c.id}:${preset}`, () => (0, slim_js_1.applyCardPreset)(c.id, preset));
        return UI.Column({ fillMaxWidth: true, spacing: 2, paddingVertical: 6 }, [
            UI.Row({ fillMaxWidth: true, spacing: 6, verticalAlignment: "center" }, [
                text(c.name, "bodyLarge", colors.onSurface, { weight: 1, maxLines: 1 }),
                text(c.access.enabled ? "已精简" : "全部工具", "labelMedium", c.access.enabled ? MOOD_INK[0] : colors.onSurfaceVariant),
            ]),
            muted(`人设约 ${(0, slim_js_1.formatTokens)(c.promptTokens)} tokens · ${(0, slim_js_1.describeAccess)(c.access)} · ${c.chats} 个对话`, 2),
            UI.Row({ fillMaxWidth: true, spacing: 2 }, [
                actionButton(`card:${c.id}:chat_only`, "只聊天", run("chat_only")),
                actionButton(`card:${c.id}:companion`, "陪伴", run("companion")),
                ...(c.hasBackup ? [actionButton(`card:${c.id}:restore`, "恢复", run("restore"))] : []),
            ]),
        ]);
    }
    function packageRow(p) {
        const key = `pkg:${p.name}`;
        const used = p.usedBy.length > 0;
        return UI.Row({ fillMaxWidth: true, spacing: 8, paddingVertical: 4, verticalAlignment: "center" }, [
            UI.Column({ weight: 1, spacing: 1 }, [
                text(p.displayName, "bodyMedium", colors.onSurface, { maxLines: 1 }),
                muted(`${p.name} · 约 ${p.tokens} tokens${used ? ` · 工作流在用：${p.usedBy.slice(0, 2).join("、")}${p.usedBy.length > 2 ? " 等" : ""}` : ""}`, 2),
            ]),
            actionButton(key, "停用", () => confirmThen(key, () => (0, slim_js_1.setPackageEnabled)(p.name, false)), used),
        ]);
    }
    function slimItems() {
        const items = [];
        if (slimStatus) {
            items.push(card([text(slimStatus.text, "bodyMedium", slimStatus.ok ? MOOD_INK[0] : colors.error)], 4, slimStatus.ok ? MOOD_TINT[0] : MOOD_TINT[3]));
        }
        if (slimError)
            items.push(card([text(`读取失败：${slimError}`, "bodyMedium", colors.error)]));
        if (!slim) {
            items.push(spinner());
            return items;
        }
        const r = slim;
        const ceiling = r.summary ? (0, slim_js_1.summaryCeiling)(r.summary) : null;
        // 一句话讲清楚钱花在哪
        const head = [
            label("每次和 AI 说一句话，发出去的是：人设 + 工具说明 + 这段对话的历史"),
            muted("历史越长，每一句越贵；后台角色被工作流每半小时喂一次，最容易越滚越大。"),
        ];
        if (r.chats) {
            head.push(UI.Row({ fillMaxWidth: true, spacing: 16 }, [
                UI.Column({ weight: 1, spacing: 0 }, [text((0, slim_js_1.formatTokens)(r.chats.totalInput), "headlineSmall", colors.onSurface), muted("所有对话累计输入")]),
                UI.Column({ weight: 1, spacing: 0 }, [
                    text(r.chats.grownSince != null ? `+${(0, slim_js_1.formatTokens)(r.chats.grownSince)}` : "—", "headlineSmall", MOOD_INK[2]),
                    muted(r.chats.baselineAt != null ? `自 ${(0, snapshot_js_1.formatDateTime)(r.chats.baselineAt)}` : "明天再看就有增长数字"),
                ]),
            ]));
        }
        if (ceiling)
            head.push(muted(`按现在的设置，长对话每一句最多带约 ${(0, slim_js_1.formatTokens)(ceiling)} tokens 历史才会压缩。`));
        items.push(card(head, 8));
        if (r.chats) {
            items.push(card([
                text("最费的对话", "titleMedium", colors.onSurface),
                ...r.chats.rows.map((row) => chatCostRow(row, r.chats?.baselineAt ?? null)),
                muted(`共 ${r.chats.totalChats} 个对话，${r.chats.idleChats} 个 7 天没动过（归档建议工作流会处理，主控台不删对话）。`),
            ], 2));
        }
        if (r.summary) {
            const s = r.summary;
            const advice = [];
            if (!s.enableSummary)
                advice.push("自动总结关着：对话会一直带着全部历史，建议打开。");
            if (s.contextLength >= 32)
                advice.push(`上下文 ${s.contextLength}K 对后台角色偏大，调到 16–32K 能直接砍掉大半费用。`);
            if (!s.byMessageCount)
                advice.push("可以再打开「按条数总结」，让后台角色的历史不再越滚越长。");
            items.push(card([
                text("自动压缩", "titleMedium", colors.onSurface),
                text(`「${s.configName}」：${(0, slim_js_1.describeSummary)(s)}`, "bodyMedium", colors.onSurface),
                ...(advice.length ? advice.map((a) => muted(`· ${a}`)) : [muted("设置已经比较省。")]),
                muted("这是全局模型设置，主控台只读；在 Operit 设置 → 模型配置里改。"),
            ], 6));
        }
        if (r.cards) {
            const used = r.cards.filter((c) => c.chats > 0 || c.hasBackup || c.access.enabled).slice(0, 10);
            items.push(card([
                text("角色卡带多少工具", "titleMedium", colors.onSurface),
                muted(`「全部工具」的角色每句话都带内置工具说明（约 ${slim_js_1.BUILTIN_TOOL_TOKENS} tokens）和整张工具包清单。`),
                muted("只聊天 = 不带任何工具，适合判断官、秘书这种只输出文字的角色；它的工作流要是需要它读文件，就别选。陪伴 = 只留主控台和语音。改之前自动备份，随时点「恢复」。"),
                ...used.map(cardRow),
            ], 2));
        }
        if (r.packages) {
            const pk = r.packages;
            const visible = showAllPackages ? pk.enabled : pk.enabled.slice(0, COLLAPSED_PACKAGES);
            const rows = [
                UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
                    text("每句话都带的工具包清单", "titleMedium", colors.onSurface, { weight: 1 }),
                    ...(pk.enabled.length > COLLAPSED_PACKAGES
                        ? [UI.TextButton({ onClick: () => setShowAllPackages(!showAllPackages) }, text(showAllPackages ? "收起" : `全部 ${pk.enabled.length} 个`, "labelLarge", colors.primary))]
                        : []),
                ]),
                muted(`开着 ${pk.enabled.length} 个，清单每句约 ${(0, slim_js_1.formatTokens)(pk.listTokens)} tokens。停用只是关掉，随时可以在这里或包管理里重新启用。标红的是工作流在用的，别停。`),
                ...visible.map(packageRow),
            ];
            if (pk.disabledByHub.length) {
                rows.push(label("主控台停用过的"));
                for (const p of pk.disabledByHub) {
                    const key = `pkg-on:${p.name}`;
                    rows.push(UI.Row({ fillMaxWidth: true, spacing: 8, verticalAlignment: "center" }, [
                        text(p.displayName, "bodyMedium", colors.onSurfaceVariant, { weight: 1, maxLines: 1 }),
                        actionButton(key, "重新启用", () => confirmThen(key, () => (0, slim_js_1.setPackageEnabled)(p.name, true))),
                    ]));
                }
            }
            items.push(card(rows, 2));
        }
        const scanRows = [
            UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
                text("外面的冗余", "titleMedium", colors.onSurface, { weight: 1 }),
                actionButton("scan", scan ? "重新扫描" : "扫描", runScan),
            ]),
            muted("只读：找 Operit 目录里没被启用中工作流用到、7 天没动过的东西，和过期的一次性/长期不用的手动工作流。不移动、不删除。"),
        ];
        if (scan) {
            scanRows.push(label(`文件与目录 · ${scan.candidates.length} 个候选（${scan.kept} 个在用或最近动过）`));
            for (const c of scan.candidates.slice(0, 15)) {
                scanRows.push(muted(`${c.isDirectory ? "📁" : "📄"} ${c.name} · ${c.newest ? `最近改动 ${(0, snapshot_js_1.formatDateTime)(c.newest)}` : "时间未知"}${c.disabledRefs.length ? ` · 只被停用的「${c.disabledRefs[0]}」提到` : ""}`, 2));
            }
            if (scan.workflows.length) {
                scanRows.push(label("可以考虑停用的工作流"));
                for (const w of scan.workflows)
                    scanRows.push(muted(`${w.name}：${w.why}`, 2));
            }
            scanRows.push(muted(`完整清单写在 ${scan.reportPath}，可以直接交给 Operit AI 或流程线核对。`));
        }
        items.push(card(scanRows, 4));
        if (r.actions.length) {
            items.push(card([
                label("主控台做过的改动"),
                ...r.actions.map((a) => muted(`${a.iso} · ${a.label} · ${a.kind === "card" ? `角色卡「${a.detail}」` : a.target}${a.ok ? "" : " · 未生效"}`, 2)),
                muted("全部记录在 companion/slim/actions.jsonl。"),
            ], 4));
        }
        for (const e of r.errors)
            items.push(muted(`读取失败：${e}`));
        return items;
    }
    // ---------- 督促（演练） ----------
    function stdChip(type, name, hint) {
        const on = assignStd === type;
        return UI.Surface({ weight: 1, containerColor: on ? MOOD_TINT[0] : colors.surfaceVariant, shape: { cornerRadius: 12 }, onClick: () => setAssignStd(type) }, UI.Column({ fillMaxWidth: true, padding: 10, spacing: 1 }, [
            text(name, "labelLarge", on ? MOOD_INK[0] : colors.onSurface),
            muted(hint, 2),
        ]));
    }
    function deadlineButton(labelText, minutes) {
        return UI.Button({
            weight: 1,
            text: labelText,
            enabled: !wardenBusy,
            onClick: () => {
                const now = Date.now();
                let deadline = now + (typeof minutes === "number" ? minutes * 60000 : 0);
                if (minutes === "tonight") {
                    const d = new Date(now);
                    d.setHours(21, 0, 0, 0);
                    if (d.getTime() <= now)
                        d.setDate(d.getDate() + 1);
                    deadline = d.getTime();
                }
                return createAssignment(deadline);
            },
        });
    }
    function wardenBanner(r) {
        const v = r.view;
        const level = v.stage === "TIER2" ? 3 : v.stage === "TIER1" ? 2 : v.stage === "GRACE" ? 1 : 0;
        const lines = [
            UI.Row({ fillMaxWidth: true, spacing: 8, verticalAlignment: "center" }, [
                text((0, warden_js_1.stageLabel)(v.stage), "titleMedium", MOOD_INK[level], { weight: 1 }),
                ...(v.minutesToDeadline != null
                    ? [text(v.minutesToDeadline >= 0 ? `还剩 ${v.minutesToDeadline} 分钟` : `超时 ${-v.minutesToDeadline} 分钟`, "labelLarge", v.minutesToDeadline >= 0 ? colors.onSurfaceVariant : MOOD_INK[2])]
                    : []),
            ]),
        ];
        if (v.line)
            lines.push(text(`“${v.line}”`, "bodyMedium", colors.onSurface));
        if (v.wouldLock.length) {
            lines.push(text(`演练：这一拍本来会锁 ${v.wouldLock.map((a) => a.name).join("、")}`, "bodyMedium", MOOD_INK[3]));
            lines.push(muted("第一阶段不会真锁，只是记下来。等你觉得判断准了，我们再开真锁。"));
        }
        if (v.reasons.length)
            lines.push(muted(v.reasons.join("；")));
        return card(lines, 6, MOOD_TINT[level]);
    }
    function wardenItems() {
        const items = [];
        items.push(card([
            text("演练模式：只记录，不锁任何 App", "titleSmall", MOOD_INK[0]),
            muted("它按你签的契约和时间，算出到点没交差时本来会锁什么，写进日志给你核对。真锁要你逐项确认契约后单独开。这手机始终是你的，系统设置里随时能停用 Operit——它做的是让放弃有代价、被记下，把你拉回来面对任务。"),
        ], 4, MOOD_TINT[0]));
        if (wardenStatus)
            items.push(card([text(wardenStatus.text, "bodyMedium", wardenStatus.ok ? MOOD_INK[0] : colors.error)], 4, wardenStatus.ok ? MOOD_TINT[0] : MOOD_TINT[3]));
        if (wardenError)
            items.push(card([text(`读取失败：${wardenError}`, "bodyMedium", colors.error)]));
        if (!warden) {
            items.push(spinner());
            return items;
        }
        const r = warden;
        if (r.view.assignment) {
            items.push(wardenBanner(r));
            // 交差
            items.push(card([
                text("交差", "titleMedium", colors.onSurface),
                muted("交你为这个任务做出来的东西。产物型会核对文件是不是派活之后改的；其它类型先收下，够不够以后交给核对模型。"),
                UI.TextField({ fillMaxWidth: true, value: deliverQuote, onValueChange: setDeliverQuote, placeholder: "一句话：我做了什么 / 投了哪几家" }),
                UI.TextField({ fillMaxWidth: true, value: deliverFile, onValueChange: setDeliverFile, placeholder: "产物文件路径（可选）", singleLine: true }),
                UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                    UI.Button({ weight: 1, text: wardenBusy ? "记录中…" : "交差", enabled: !wardenBusy, onClick: deliver }),
                    UI.TextButton({ enabled: !wardenBusy, onClick: stopAssignment }, text("撤下", "labelLarge", colors.onSurfaceVariant)),
                ]),
            ], 8));
        }
        else {
            items.push(card([
                text("给自己派个活", "titleMedium", colors.onSurface),
                UI.TextField({ fillMaxWidth: true, value: assignTask, onValueChange: setAssignTask, placeholder: snap?.task?.task || "要推进的任务", singleLine: true }),
                muted("怎么算完成："),
                UI.Row({ fillMaxWidth: true, spacing: 6 }, [
                    stdChip("product", "产物", "有文件，代码能核对"),
                    stdChip("count", "计数", "投几家之类，交截图"),
                    stdChip("self", "自述", "只说想清楚了，最弱"),
                ]),
                ...(assignStd === "product" ? [UI.TextField({ fillMaxWidth: true, value: assignFile, onValueChange: setAssignFile, placeholder: "产物文件路径（可选）", singleLine: true })] : []),
                muted("截止时间："),
                UI.Row({ fillMaxWidth: true, spacing: 6 }, [deadlineButton("30 分钟", 30), deadlineButton("1 小时", 60), deadlineButton("2 小时", 120), deadlineButton("今晚 21 点", "tonight")]),
            ], 8));
        }
        // 契约（你签的，可折叠）
        const c = r.contract;
        const contractHead = [
            UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
                text("契约", "titleMedium", colors.onSurface, { weight: 1 }),
                UI.TextButton({ onClick: () => setShowContract(!showContract) }, text(showContract ? "收起" : "看看", "labelLarge", colors.primary)),
            ]),
            muted(`一级 ${c.tier1.length} 个 · 二级 ${c.tier2.length} 个 · 保护 ${c.protectedApps.length} 个${r.unconfirmed.length ? ` · ${r.unconfirmed.length} 项待你拍板` : " · 已全部确认"}`),
        ];
        if (showContract) {
            const appList = (title, apps, level) => UI.Column({ fillMaxWidth: true, spacing: 1, paddingVertical: 4 }, [
                label(title),
                text(apps.map((a) => `${a.name}${a.verified ? "" : "?"}`).join("　"), "bodyMedium", MOOD_INK[level]),
            ]);
            contractHead.push(appList("一级（先锁）", c.tier1, 2));
            contractHead.push(appList("二级（升级后锁）", c.tier2, 3));
            contractHead.push(appList("观望（只提醒）", c.watch, 1));
            contractHead.push(muted(`名字后带 ? 的包名还没在真机核对过；真锁前要确认。`));
            contractHead.push(muted(`时间：超时 ${c.timing.graceMinutes} 分钟宽限后一级，再过 ${c.timing.tier2AfterMinutes} 分钟升二级，每天 ${c.timing.dailyUnlockHour} 点全部自动解开。`));
            contractHead.push(muted(`额度：每天暂停 ${c.limits.pausePerDay} 次、人工解锁 ${c.limits.unlockPerDay} 次，每周急停 ${c.limits.emergencyPerWeek} 次（冷静期 ${c.limits.coolDownMinutes} 分钟）。核对模型 ${c.judgeModel}。`));
            if (r.unconfirmed.length)
                contractHead.push(text(`还没拍板：${r.unconfirmed.join("、")}`, "bodySmall", MOOD_INK[2]));
            contractHead.push(muted("改契约要在 companion/warden/contract.json 里写，或交给我/流程线；这里只显示。"));
        }
        items.push(card(contractHead, 4));
        // 演练日志
        if (r.dryRun.length) {
            items.push(card([
                label(`演练记录 · ${r.dryRun.length} 条（本来会锁什么，没真锁）`),
                ...r.dryRun.slice(0, 12).map((row) => UI.Column({ fillMaxWidth: true, spacing: 1 }, [
                    UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                        text((0, warden_js_1.stageLabel)(row.stage) || row.stage, "labelLarge", MOOD_INK[3]),
                        muted(`${row.iso}${row.overdueMin != null ? ` · 超时 ${row.overdueMin} 分钟` : ""}`, 1),
                    ]),
                    muted(`${row.task || "—"} → ${row.wouldLock.join("、") || "—"}`, 2),
                ])),
                muted("写在 companion/warden/dryrun.jsonl。"),
            ], 6));
        }
        else {
            items.push(card([muted("还没有演练记录。派个活、把截止时间调短，等打点工作流跑一拍（或到点后打开这页），就能看到它本来会锁什么。")], 4));
        }
        return items;
    }
    // ---------- 管理 ----------
    function segment() {
        return UI.Surface({ fillMaxWidth: true, shape: { type: "pill" }, containerColor: colors.surfaceVariant }, UI.Row({ fillMaxWidth: true, padding: 4, spacing: 4 }, MANAGE_TABS.map((t) => {
            const on = manageTab === t.key;
            return UI.Surface({ weight: 1, shape: { type: "pill" }, containerColor: on ? colors.surface : colors.surfaceVariant, shadowElevation: on ? 2 : 0, onClick: () => openManage(t.key) }, UI.Box({ fillMaxWidth: true, paddingVertical: 8, contentAlignment: "center" }, [
                text(t.name, "labelLarge", on ? colors.primary : colors.onSurfaceVariant),
            ]));
        })));
    }
    function managePage() {
        const items = [pageHeader("管理"), segment()];
        items.push(...(manageTab === "slim" ? slimItems() : manageTab === "warden" ? wardenItems() : sysItems()));
        return pageColumn(items);
    }
    // ---------- 底栏：悬浮胶囊 ----------
    function navItem(target, glyph, name) {
        const on = page === target;
        const inner = UI.Row({ fillMaxWidth: true, paddingVertical: 10, horizontalArrangement: "center", verticalAlignment: "center", spacing: 6 }, [
            text(glyph, "titleMedium", on ? ON_DARK : colors.onSurfaceVariant),
            text(name, "labelLarge", on ? ON_DARK : colors.onSurfaceVariant, on ? { fontWeight: "bold" } : {}),
        ]);
        return UI.Surface({ weight: 1, shape: { type: "pill" }, containerColor: on ? NAV_GRAD[0] : colors.surface, onClick: () => openPage(target) }, on ? UI.Box({ fillMaxWidth: true, backgroundBrush: { type: "verticalGradient", colors: NAV_GRAD }, backgroundShape: { type: "pill" } }, [inner]) : inner);
    }
    const body = page === "life" ? lifePage() : page === "manage" ? managePage() : focusPage();
    return UI.Column({
        fillMaxSize: true,
        onLoad: async () => {
            if (autoLoadedRef.current)
                return;
            autoLoadedRef.current = true;
            await Promise.all([refresh(), refreshFocus()]);
        },
    }, [
        body,
        UI.Box({ fillMaxWidth: true, padding: { horizontal: 16, vertical: 10 } }, [
            UI.Surface({ fillMaxWidth: true, shape: { type: "pill" }, containerColor: colors.surface, shadowElevation: 10, tonalElevation: 2 }, UI.Row({ fillMaxWidth: true, padding: 6, spacing: 6 }, [
                navItem("focus", "◎", "专注"),
                navItem("life", "♡", "陪伴"),
                navItem("manage", "☰", "管理"),
            ])),
        ]),
    ]);
}
