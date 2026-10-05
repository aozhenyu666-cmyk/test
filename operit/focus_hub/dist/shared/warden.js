"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DEFAULT_CONTRACT = exports.WARDEN_MODE = exports.WARDEN_DIR = void 0;
exports.loadContract = loadContract;
exports.loadAssignment = loadAssignment;
exports.setAssignment = setAssignment;
exports.clearAssignment = clearAssignment;
exports.recordDelivery = recordDelivery;
exports.evaluate = evaluate;
exports.tick = tick;
exports.readDryRunLog = readDryRunLog;
exports.collectWarden = collectWarden;
exports.stageLabel = stageLabel;
exports.wardenReportToText = wardenReportToText;
const snapshot_js_1 = require("./snapshot.js");
const progress_js_1 = require("./progress.js");
const ROOT = "/sdcard/Download/Operit";
exports.WARDEN_DIR = `${ROOT}/companion/warden`;
const CONTRACT_PATH = `${exports.WARDEN_DIR}/contract.json`;
const ASSIGNMENT_PATH = `${exports.WARDEN_DIR}/assignment.json`;
const DRYRUN_LOG = `${exports.WARDEN_DIR}/dryrun.jsonl`;
const DELIVERY_LOG = `${exports.WARDEN_DIR}/deliveries.jsonl`;
// 第一阶段唯一允许的模式。代码里任何真锁路径都必须先过这个检查。
exports.WARDEN_MODE = "dryrun";
const LINE_NUMBER_PREFIX = /^\s*\d+\| ?/;
function errorText(error) {
    if (error && typeof error === "object" && "message" in error) {
        return String(error.message);
    }
    return String(error);
}
async function readJson(path) {
    try {
        if (!(await (0, snapshot_js_1.fileExists)(path)))
            return null;
        const part = await Tools.Files.readPart(path, 1, 500);
        const json = part.content
            .split("\n")
            .filter((line) => LINE_NUMBER_PREFIX.test(line))
            .map((line) => line.replace(LINE_NUMBER_PREFIX, ""))
            .join("\n");
        return JSON.parse(json);
    }
    catch {
        return null;
    }
}
// 默认契约：数值取自 app_suspender METADATA 第八节。
// [x] 项是用户/盘面已确认；其余是演练用的提案，真锁前要用户拍板。
exports.DEFAULT_CONTRACT = {
    version: 1,
    tier1: [
        { name: "抖音", pkg: "com.ss.android.ugc.aweme", verified: true },
        { name: "B站", pkg: "tv.danmaku.bili", verified: true },
        { name: "贴吧", pkg: "com.baidu.tieba", verified: true },
        { name: "红果短剧", pkg: "com.phoenix.read", verified: true },
        { name: "红果漫剧", pkg: "com.kylin.read", verified: false },
        { name: "X", pkg: "com.twitter.android", verified: false },
    ],
    tier2: [
        { name: "小红书", pkg: "com.xingin.xhs", verified: false },
        { name: "闲鱼", pkg: "com.taobao.idlefish", verified: true },
        { name: "王者荣耀", pkg: "com.tencent.tmgp.sgame", verified: true },
        { name: "三角洲行动", pkg: "com.tencent.tmgp.dfm", verified: false },
    ],
    watch: [{ name: "Rikkahub", pkg: "com.rikkahub", verified: false }],
    protectedApps: [
        "com.ai.assistance.operit",
        "com.tencent.mm",
        "com.google.android.apps.bard",
        "com.openai.chatgpt",
        "com.eg.android.AlipayGphone",
        "com.autonavi.minimap",
        "com.baidu.BaiduMap",
    ],
    timing: { graceMinutes: 30, tier2AfterMinutes: 30, maxSingleLockMinutes: 120, dailyUnlockHour: 23 },
    limits: { pausePerDay: 2, unlockPerDay: 2, emergencyPerWeek: 3, coolDownMinutes: 30 },
    judgeModel: "gpt-6-sol",
    confirmed: {
        protectedApps: true,
        unlockPerDay: true,
        coolDownMinutes: false,
        graceMinutes: true, // A2 等待回应 30 分钟
        judgeModel: true,
        tier1: false,
        tier2: false,
        tier2AfterMinutes: false,
        maxSingleLockMinutes: false,
        dailyUnlockHour: false,
        pausePerDay: false,
        emergencyPerWeek: false,
    },
};
async function loadContract() {
    const saved = await readJson(CONTRACT_PATH);
    if (!saved)
        return exports.DEFAULT_CONTRACT;
    // 用户/流程线写过的契约优先，缺的字段回落到默认
    return {
        ...exports.DEFAULT_CONTRACT,
        ...saved,
        timing: { ...exports.DEFAULT_CONTRACT.timing, ...(saved.timing ?? {}) },
        limits: { ...exports.DEFAULT_CONTRACT.limits, ...(saved.limits ?? {}) },
        confirmed: { ...exports.DEFAULT_CONTRACT.confirmed, ...(saved.confirmed ?? {}) },
        tier1: saved.tier1 ?? exports.DEFAULT_CONTRACT.tier1,
        tier2: saved.tier2 ?? exports.DEFAULT_CONTRACT.tier2,
        watch: saved.watch ?? exports.DEFAULT_CONTRACT.watch,
    };
}
async function loadAssignment() {
    const a = await readJson(ASSIGNMENT_PATH);
    return a && a.active ? a : null;
}
async function setAssignment(input) {
    const now = Date.now();
    const assignment = {
        // 同一毫秒内派两次活也不能撞 id，否则上一次的交差会算到这一次头上
        id: `A-${now}-${Math.random().toString(36).slice(2, 8)}`,
        task: input.task,
        createdAt: now,
        deadline: input.deadlineMs,
        standard: input.standard,
        active: true,
    };
    await Tools.Files.write(ASSIGNMENT_PATH, JSON.stringify(assignment), false);
    return assignment;
}
async function clearAssignment() {
    const a = await readJson(ASSIGNMENT_PATH);
    if (a)
        await Tools.Files.write(ASSIGNMENT_PATH, JSON.stringify({ ...a, active: false }), false);
}
// 只有产物型能被代码判：文件存在，且修改时间在派活之后
async function autoCheck(assignment, filePath) {
    if (assignment.standard.type !== "product")
        return "NEEDS_JUDGE";
    const target = filePath || assignment.standard.filePath || "";
    if (!target)
        return "NEEDS_JUDGE";
    try {
        const info = await Tools.Files.info(target);
        if (!info?.exists)
            return "PRODUCT_MISSING";
        const modified = parseModified(info.lastModified);
        if (modified != null && modified >= assignment.createdAt)
            return "PRODUCT_OK";
        return "PRODUCT_MISSING";
    }
    catch {
        return "PRODUCT_MISSING";
    }
}
function parseModified(value) {
    if (!value)
        return null;
    const m = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})/.exec(value.trim());
    if (!m)
        return null;
    const [, y, mo, d, h, mi, s] = m;
    const t = new Date(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s)).getTime();
    return Number.isFinite(t) ? t : null;
}
async function recordDelivery(quote, filePath = "") {
    const assignment = await loadAssignment();
    if (!assignment)
        return { delivery: null, reason: "现在没有在督促的任务" };
    const now = Date.now();
    const delivery = {
        ts: Math.floor(now / 1000),
        iso: (0, snapshot_js_1.formatDateTime)(now),
        assignmentId: assignment.id,
        task: assignment.task,
        quote: quote.trim(),
        filePath: filePath.trim(),
        autoCheck: await autoCheck(assignment, filePath.trim()),
    };
    await Tools.Files.write(DELIVERY_LOG, JSON.stringify(delivery) + "\n", true);
    // 产物已核对通过就撤下这次派活；其余等判官（后续阶段），先留着
    if (delivery.autoCheck === "PRODUCT_OK")
        await clearAssignment();
    return { delivery };
}
async function readDeliveries(assignmentId) {
    if (!(await (0, snapshot_js_1.fileExists)(DELIVERY_LOG)))
        return [];
    const { lines } = await (0, snapshot_js_1.readAll)(DELIVERY_LOG, 60);
    const out = [];
    for (const line of lines) {
        try {
            const d = JSON.parse(line);
            if (d.assignmentId === assignmentId)
                out.push(d);
        }
        catch {
            // 跳过坏行
        }
    }
    return out;
}
// 忽略 = 截止过了，且派活之后既没交差、也没有新的用户进展记录
function sawUserActivity(assignment, deliveries, progress) {
    if (deliveries.length > 0)
        return true;
    const since = Math.floor(assignment.createdAt / 1000);
    return progress.some((p) => p.origin === "REAL_USER" && p.ts >= since);
}
function evaluate(contract, assignment, deliveries, progress, now) {
    const base = {
        mode: exports.WARDEN_MODE,
        stage: "IDLE",
        assignment,
        now,
        minutesToDeadline: null,
        ignored: false,
        wouldLock: [],
        lastDelivery: deliveries[deliveries.length - 1] ?? null,
        line: "",
        reasons: [],
    };
    if (!assignment) {
        base.line = "现在没什么要盯的，去做你想做的吧。";
        return base;
    }
    const okDelivery = deliveries.find((d) => d.autoCheck === "PRODUCT_OK");
    if (okDelivery) {
        base.stage = "DELIVERED";
        base.line = `「${assignment.task}」交上来了，我看过了，这一幕收工。`;
        base.reasons.push("产物已核对通过");
        return base;
    }
    const minutesLeft = Math.round((assignment.deadline - now) / 60000);
    base.minutesToDeadline = minutesLeft;
    const overdue = -minutesLeft; // 超时多少分钟
    const ignored = overdue > 0 && !sawUserActivity(assignment, deliveries, progress);
    base.ignored = ignored;
    if (minutesLeft > 0) {
        base.stage = "ON_TRACK";
        base.line =
            minutesLeft <= 10
                ? `还有 ${minutesLeft} 分钟就到「${assignment.task}」的点了，收个尾吧。`
                : `「${assignment.task}」还剩 ${minutesLeft} 分钟。`;
        return base;
    }
    // 已超时
    if (deliveries.length > 0) {
        // 交了但还没被判为通过（产物缺失或要判官）
        base.stage = "GRACE";
        base.line = `你交的东西我先收下了，但还没算数——${lastCheckHint(base.lastDelivery)}`;
        base.reasons.push("已交差，等核对");
        return base;
    }
    if (ignored) {
        if (overdue >= contract.timing.graceMinutes + contract.timing.tier2AfterMinutes) {
            base.stage = "TIER2";
            base.wouldLock = [...contract.tier1, ...contract.tier2];
            base.reasons.push(`超时 ${overdue} 分钟，宽限和一级都过了`);
            base.line = "这一幕该换布景了。再不回来，能玩的都得先收走。";
        }
        else if (overdue >= contract.timing.graceMinutes) {
            base.stage = "TIER1";
            base.wouldLock = contract.tier1;
            base.reasons.push(`超时 ${overdue} 分钟，宽限期过了`);
            base.line = "时间到了，你没理我。娱乐的先收走，回来把事做了。";
        }
        else {
            base.stage = "GRACE";
            base.reasons.push(`超时 ${overdue} 分钟，还在 ${contract.timing.graceMinutes} 分钟宽限里`);
            base.line = `到点了，「${assignment.task}」呢？还有 ${contract.timing.graceMinutes - overdue} 分钟宽限。`;
        }
    }
    else {
        // 超时了但用户有在推进（有进展记录），先不升级
        base.stage = "GRACE";
        base.reasons.push("超时了，但你还在动，先不锁");
        base.line = "过点了，不过看得出你在弄，那我等你交。";
    }
    return base;
}
function lastCheckHint(d) {
    if (!d)
        return "再具体点。";
    if (d.autoCheck === "PRODUCT_MISSING")
        return "没找到你说的那个文件，或者它还是派活之前的。";
    return "得让我（或核对模型）看看够不够。";
}
async function tick(now = Date.now()) {
    const contract = await loadContract();
    const assignment = await loadAssignment();
    const deliveries = assignment ? await readDeliveries(assignment.id) : [];
    const progress = await (0, progress_js_1.readRecentProgress)(30).catch(() => []);
    const view = evaluate(contract, assignment, deliveries, progress, now);
    // 硬保险：第一阶段无论如何都不调用 app_suspender。
    // 这里只把"本来会锁什么"写进演练日志。
    let logged = false;
    if (view.stage === "TIER1" || view.stage === "TIER2") {
        const record = {
            ts: Math.floor(now / 1000),
            iso: (0, snapshot_js_1.formatDateTime)(now),
            mode: exports.WARDEN_MODE,
            stage: view.stage,
            task: assignment?.task ?? "",
            would_lock: view.wouldLock.map((a) => ({ name: a.name, pkg: a.pkg, verified: a.verified })),
            overdue_min: view.minutesToDeadline != null ? -view.minutesToDeadline : null,
            reasons: view.reasons,
            note: "演练：未执行任何冻结",
        };
        try {
            await Tools.Files.write(DRYRUN_LOG, JSON.stringify(record) + "\n", true);
            logged = true;
        }
        catch {
            // 写不了不影响判定
        }
    }
    return {
        stage: view.stage,
        mode: exports.WARDEN_MODE,
        wouldLock: view.wouldLock.map((a) => a.name),
        ignored: view.ignored,
        logged,
        note: "第一阶段：只演练，未冻结任何应用",
    };
}
async function readDryRunLog(limit = 20) {
    if (!(await (0, snapshot_js_1.fileExists)(DRYRUN_LOG)))
        return [];
    const { lines } = await (0, snapshot_js_1.readAll)(DRYRUN_LOG, limit);
    const out = [];
    for (const line of lines) {
        try {
            const r = JSON.parse(line);
            out.push({
                iso: r.iso,
                stage: r.stage,
                task: r.task,
                wouldLock: (r.would_lock ?? []).map((a) => a.name),
                overdueMin: r.overdue_min ?? null,
            });
        }
        catch {
            // 跳过坏行
        }
    }
    return out.reverse();
}
async function collectWarden(now = Date.now()) {
    const contract = await loadContract();
    try {
        const assignment = await loadAssignment();
        const deliveries = assignment ? await readDeliveries(assignment.id) : [];
        const progress = await (0, progress_js_1.readRecentProgress)(30).catch(() => []);
        const view = evaluate(contract, assignment, deliveries, progress, now);
        const dryRun = await readDryRunLog(20);
        const unconfirmed = Object.entries(contract.confirmed)
            .filter(([, v]) => !v)
            .map(([k]) => k);
        return { view, contract, dryRun, unconfirmed };
    }
    catch (error) {
        return {
            view: evaluate(contract, null, [], [], now),
            contract,
            dryRun: [],
            unconfirmed: [],
            error: errorText(error),
        };
    }
}
const STAGE_LABEL = {
    IDLE: "空闲",
    ON_TRACK: "进行中",
    GRACE: "宽限期",
    TIER1: "该锁一级（演练）",
    TIER2: "该升二级（演练）",
    DELIVERED: "已交差",
};
function stageLabel(stage) {
    return STAGE_LABEL[stage];
}
function wardenReportToText(r) {
    const out = [`督促（演练模式，不会真锁）· ${(0, snapshot_js_1.formatDateTime)(r.view.now)}`];
    const v = r.view;
    if (!v.assignment) {
        out.push("当前没有在督促的任务。");
    }
    else {
        out.push(`任务：${v.assignment.task}｜状态：${stageLabel(v.stage)}${v.minutesToDeadline != null ? `｜距截止 ${v.minutesToDeadline} 分钟` : ""}`);
        if (v.wouldLock.length)
            out.push(`演练：这一拍本来会锁 ${v.wouldLock.map((a) => a.name).join("、")}`);
        if (v.reasons.length)
            out.push(`依据：${v.reasons.join("；")}`);
        out.push(`她会说：${v.line}`);
    }
    if (r.dryRun.length) {
        out.push(`最近演练记录 ${r.dryRun.length} 条，最新：${r.dryRun[0].iso} ${r.dryRun[0].stage} → ${r.dryRun[0].wouldLock.join("、") || "—"}`);
    }
    if (r.unconfirmed.length)
        out.push(`还没拍板的契约项：${r.unconfirmed.join("、")}（真锁前要逐项确认）`);
    if (r.error)
        out.push(`读取失败：${r.error}`);
    return out.join("\n");
}
