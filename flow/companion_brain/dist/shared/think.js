"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.buildPrompt = buildPrompt;
exports.cleanLine = cleanLine;
exports.fallbackLine = fallbackLine;
exports.validate = validate;
exports.think = think;
// 思考：把事实 + 记忆 + 规则层给的上限交给模型，让它决定说什么、做哪一个允许的动作。
// 模型只输出 JSON；代码负责校验，越权或格式不对就退回保守做法。
const facts_js_1 = require("./facts.js");
const fsx_js_1 = require("./fsx.js");
const time_js_1 = require("./time.js");
const KIND_LABEL = { progress: "在做", stuck: "卡住", done: "完成", pause: "暂停", note: "说明" };
const STAGE_GUIDE = {
    nudge: "刚发现 TA 跑偏。轻轻点一句：在刷什么、大概多久了，给一个马上能做的小步骤。只有你判断 TA 很可能会去做时，才用 open_app 帮 TA 打开招聘 App。",
    ask: "之前提醒过，TA 还没回来。追问一次：要一个具体时间或具体的第一步。可以用 assign 派一个 20 分钟内能做完的小活，或用 record_commitment 记下 TA 已经说出口的承诺。别说教。",
    lock: "按你们约好的规则，系统正在把 TA 正在刷的这个 App 暂停。你要用一句平实的话告诉 TA：暂停了哪个、为什么（提醒后一直没回应）、想解开要走解锁流程。不威胁，不嘲讽。action 必须是 lock。",
    commitment_due: "TA 之前答应的事到点了。引用 TA 的原话问一句做了没有；没做就问现在能不能开始。",
    checkin: "很久没动静。轻轻问一句近况或下一步，不要像查岗。",
    praise: "提醒之后 TA 回到正轨了。简短地认一下，不夸张，不要顺势加码。",
};
function samplesText(facts) {
    const rows = facts.samples.slice(0, 6).map((s) => {
        const state = s.ontask === "1" ? "在任务上" : s.ontask === "0" ? "不在任务上" : "锁屏/看不到";
        return `- ${(0, time_js_1.minutesAgoText)(s.ts, facts.now)}：${s.pkg === "none" ? "看不到前台" : (0, facts_js_1.appName)(s.pkg)}（${state}）`;
    });
    return rows.length ? rows.join("\n") : "- 最近没有采样";
}
function progressText(facts) {
    const rows = facts.progress.slice(0, 5).map((p) => `- ${(0, time_js_1.minutesAgoText)(p.ts, facts.now)} ${KIND_LABEL[p.kind] ?? p.kind}：「${p.quote}」${p.note ? `（${p.note}）` : ""}`);
    return rows.length ? rows.join("\n") : "- 24 小时内 TA 没报过进展";
}
function commitmentsText(open, now) {
    const rows = open.slice(0, 5).map((c) => `- ${c.text}（${c.due_ts <= now ? "已到点" : `${(0, time_js_1.bjHM)(c.due_ts)} 前`}；原话「${c.quote}」）`);
    return rows.length ? rows.join("\n") : "- 没有";
}
function saidText(said, now) {
    const rows = said.slice(-5).map((s) => `- ${(0, time_js_1.minutesAgoText)(s.ts, now)}：${s.line}`);
    return rows.length ? rows.join("\n") : "- 今天还没主动说过话";
}
function buildPrompt(facts, plan, profile, open, said) {
    const system = `你是"小满"的思考部分。小满是陪 TA 找工作的伙伴：站在 TA 这边，有记忆，不接受敷衍但只追一次，不说教、不吓唬、不重复自己。
你现在要决定：这一刻小满要不要主动开口、说什么、做不做一个动作。

说话要求（line）：
- 口语，一句话，最多 60 个字，像发微信；会被念出来，所以不用颜文字、表情、英文缩写。
- 不说应用包名、时间戳、编号，不说 DRIFT_RISK、L2、置信度、判断官、工作流、采样这类系统词。
- 先认 TA 说过的话；不要重复"小满最近说过的话"里的句子，换角度或直接引用 TA 的原话。
- 只用下面给你的事实，不知道的不编。

只输出一个 JSON 对象，不要任何其他文字：
{"speak": true 或 false, "line": "要说的话", "action": "允许的动作之一", "open_app": "包名（仅 action=open_app 时）", "assign": {"task": "要做的小活", "minutes": 20}, "commitment": {"text": "承诺内容", "due_minutes": 30, "quote": "TA 的原话"}, "memory_note": "值得记进档案的新发现（没有就空）", "why": "一句理由"}
action 只能从"允许的动作"里选。open_app 只能打开招聘 App。`;
    const user = `【此刻】北京时间 ${(0, time_js_1.bjHM)(facts.now)}；当前任务：${facts.task || "未声明"}（${facts.taskState || "未知"}）
【阶段】${plan.stage}：${plan.reason}
【这一步该怎么做】${STAGE_GUIDE[plan.stage] ?? "没有需要说的就保持沉默（speak=false）。"}
【允许的动作】${plan.allowed.join(" / ")}${plan.lockPkg ? `（要暂停的是：${(0, facts_js_1.appName)(plan.lockPkg)}）` : ""}
【最近前台】
${samplesText(facts)}
【判断官】${facts.verdict ? `${(0, time_js_1.minutesAgoText)(facts.verdict.ts, facts.now)}：${facts.verdict.verdict}，${facts.verdict.why}` : "暂无"}
【屏幕】${facts.shotFact || "没有截屏事实"}
【TA 最近说的进展】
${progressText(facts)}
【TA 答应过、还没兑现的事】
${commitmentsText(open, facts.now)}
【正在进行的派活】${facts.assignment ? `${facts.assignment.task}（${(0, time_js_1.bjHM)(facts.assignment.deadline)} 前）` : "没有"}
【小满最近说过的话】
${saidText(said, facts.now)}
【招聘 App】${facts.jobApps.slice(0, 3).map((p) => `${(0, facts_js_1.appName)(p)}=${p}`).join("，")}
【关于 TA】
${profile.slice(0, 1800)}`;
    return { system, user };
}
const PKG_TOKEN = /\b(?:com|tv|org|me|ai|xyz)\.[A-Za-z0-9_.]+/g;
const SYSTEM_WORDS = /(DRIFT_RISK|ON_TRACK|INSUFFICIENT|STUCK|NO_TASK|L[0-3]\b|置信度|判断官|工作流|采样|CONF=)/g;
function cleanLine(line) {
    return String(line ?? "")
        .replace(/<[^>]*>/g, "")
        .replace(PKG_TOKEN, "")
        .replace(SYSTEM_WORDS, "")
        .replace(/[\r\n]+/g, " ")
        .replace(/["“”]/g, "")
        .replace(/\s{2,}/g, " ")
        .trim()
        .slice(0, 80);
}
function fallbackLine(facts, plan, open) {
    const app = plan.lockPkg || plan.driftPkg;
    const name = app ? (0, facts_js_1.appName)(app) : "别的";
    switch (plan.stage) {
        case "nudge":
            return `又在刷${name}啦，先去投一家再回来看？`;
        case "ask":
            return `${name}还没放下呢，说个时间吧，几点开始投？`;
        case "lock":
            return `按咱们说好的，${name}先暂停了。想解开就走解锁流程，先投一家？`;
        case "commitment_due":
            return open[0] ? `你说的「${open[0].quote.slice(0, 20)}」到点了，做了吗？` : "说好的事到点了，做了吗？";
        case "checkin":
            return "好一会儿没听你说了，现在在忙什么？";
        case "praise":
            return "回来了就好，继续。";
        default:
            return "";
    }
}
function extractJson(text) {
    const cleaned = text.replace(/<think[\s\S]*?<\/think>/g, "");
    const s = cleaned.indexOf("{");
    const e = cleaned.lastIndexOf("}");
    if (s < 0 || e <= s)
        return null;
    try {
        return JSON.parse(cleaned.slice(s, e + 1));
    }
    catch {
        return null;
    }
}
function validate(raw, facts, plan, open) {
    const base = { speak: false, line: "", action: "none", openApp: "", assign: null, commitment: null, memoryNote: "", why: "", source: "model" };
    if (!raw) {
        return { ...base, speak: plan.canSpeak, line: fallbackLine(facts, plan, open), action: plan.stage === "lock" ? "lock" : "none", source: "fallback", error: "模型输出不是 JSON" };
    }
    let action = String(raw.action ?? "none");
    if (!plan.allowed.includes(action))
        action = "none";
    // 锁由规则层决定，模型不能取消
    if (plan.stage === "lock")
        action = "lock";
    let openApp = String(raw.open_app ?? "").trim();
    if (action === "open_app" && !facts.jobApps.includes(openApp)) {
        openApp = facts.jobApps[0] ?? "";
        if (!openApp)
            action = "none";
    }
    const a = raw.assign;
    const assign = action === "assign" && a && String(a.task ?? "").trim() ? { task: String(a.task).trim().slice(0, 60), minutes: Math.min(120, Math.max(5, Number(a.minutes) || 20)) } : null;
    if (action === "assign" && !assign)
        action = "none";
    const c = raw.commitment;
    const commitment = action === "record_commitment" && c && String(c.text ?? "").trim()
        ? { text: String(c.text).trim().slice(0, 60), dueMinutes: Math.min(24 * 60, Math.max(5, Number(c.due_minutes) || 30)), quote: String(c.quote ?? "").trim().slice(0, 80) }
        : null;
    if (action === "record_commitment" && !commitment)
        action = "none";
    let line = cleanLine(String(raw.line ?? ""));
    let speak = raw.speak === true && plan.canSpeak;
    if (plan.stage === "lock" && plan.canSpeak)
        speak = true;
    if (speak && line.length < 4)
        line = fallbackLine(facts, plan, open);
    return {
        speak,
        line: speak ? line : "",
        action,
        openApp: action === "open_app" ? openApp : "",
        assign,
        commitment,
        memoryNote: String(raw.memory_note ?? "").trim().slice(0, 120),
        why: String(raw.why ?? "").trim().slice(0, 120),
        source: "model",
    };
}
async function think(facts, plan, profile, open, said) {
    const { system, user } = buildPrompt(facts, plan, profile, open, said);
    try {
        const result = await Tools.Chat.call({
            functionType: "CHAT",
            turns: [
                { kind: "SYSTEM", content: system },
                { kind: "USER", content: user },
            ],
            enableThinking: false,
        });
        return validate(extractJson(String(result?.text ?? "")), facts, plan, open);
    }
    catch (error) {
        const d = validate(null, facts, plan, open);
        return { ...d, error: `模型调用失败：${(0, fsx_js_1.errorText)(error)}` };
    }
}
