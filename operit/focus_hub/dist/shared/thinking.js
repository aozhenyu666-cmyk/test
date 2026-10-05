"use strict";
// 当前任务循环：读 cognitive_core 账本里的当前问题，保存本人回答，再请后台模型（control_plane:coach）接着问。
// 全部经 toolCall 调后台工具；后台不可用时如实返回，不在本地另建一本账。
Object.defineProperty(exports, "__esModule", { value: true });
exports.BACKEND_ROUTE = void 0;
exports.loadTask = loadTask;
exports.answer = answer;
exports.coach = coach;
exports.pause = pause;
exports.resume = resume;
exports.startTask = startTask;
exports.BACKEND_ROUTE = "toolpkg:com.community.cognitive_continuity:ui:continuity";
function parse(value) {
    if (typeof value !== "string")
        return value;
    try {
        return JSON.parse(value);
    }
    catch {
        return { raw: value };
    }
}
function failed(r) {
    return !r || r.success === false || r.ok === false;
}
function data(r) {
    return r && typeof r === "object" && "data" in r ? r.data : r;
}
function messageOf(r, fallback) {
    return String((r && (r.message || r.error || (r.data && r.data.code))) || fallback);
}
function errorText(error) {
    return error && typeof error === "object" && "message" in error ? String(error.message) : String(error);
}
async function call(name, params) {
    return parse(await toolCall(name, params || {}));
}
async function readState() {
    const r = await call("cognitive_core:status", {});
    if (failed(r))
        throw new Error(messageOf(r, "后台账本不可用"));
    const d = data(r);
    if (!d || !d.state || !d.state.core)
        throw new Error("后台账本返回的格式不对");
    return d.state;
}
// 把账本状态压成界面要用的样子
function view(state) {
    const core = state.core, s = core.session, cp = state.control_plane || null;
    const binding = cp && cp.policy ? cp.policy.task_binding || null : null;
    const base = { available: true, coreRevision: core.revision, controlRevision: cp ? cp.revision || 0 : 0, managed: !!cp };
    if (!s || s.status === "COMPLETE")
        return { ...base, session: null, real: false };
    const turn = s.turns && s.turns.length ? s.turns[s.turns.length - 1] : null;
    const contributions = turn && turn.ai_contributions ? turn.ai_contributions : [];
    const real = !cp || (!!binding && binding.session_id === s.id && binding.purpose === "real_task");
    return {
        ...base,
        real,
        session: {
            id: s.id,
            title: s.title,
            status: s.status,
            activity: s.activity || "thinking",
            questionId: s.current_question_id,
            question: s.next_entry ? s.next_entry.question : turn ? turn.question : "",
            scaffold: s.next_entry ? s.next_entry.scaffold : "",
            aiHelp: contributions.length ? contributions[contributions.length - 1].text : "",
            lastAnswer: s.last_step ? s.last_step.user_answer : "",
            lastAnswerAt: s.last_step ? s.last_step.at : null,
            answers: (s.cognition || []).length,
            materials: (s.materials || []).map((m) => m.title),
            pausedAt: s.status === "PAUSED" ? s.updated_at : null,
        },
    };
}
async function loadTask() {
    try {
        return view(await readState());
    }
    catch (error) {
        return { available: false, error: errorText(error), session: null, real: false };
    }
}
async function apply(event) {
    const r = await call("cognitive_core:apply", { event_json: JSON.stringify(event) });
    if (failed(r))
        throw new Error(messageOf(r, "账本拒绝了这次写入"));
    return data(r);
}
const COACH_NOTES = {
    COACH_ALREADY_ATTEMPTED: "这一题 AI 已经试过一次，后台暂不允许重试（升级到 preview.4 后可以）",
    MODEL_API_UNAVAILABLE: "后台调用不到模型接口",
    REAL_TASK_REQUIRED: "先开始一件你自己选的事",
    STALE_REVISION: "账本刚变过，再点一次",
};
// 请后台模型围绕当前材料和你刚才的回答接着问
async function coach(tag) {
    const v = await loadTask();
    if (!v.available)
        return { status: "unavailable", note: v.error };
    if (!v.managed)
        return { status: "unavailable", note: "后台没有 AI 接续功能（需要 cognitive_continuity 0.3 以上）" };
    if (!v.session || !v.real)
        return { status: "skipped", note: "当前不是你选的真实任务" };
    let r;
    try {
        r = await call("control_plane:coach", {
            event_id: `hub-coach:${v.session.questionId}${tag ? ":" + tag : ""}`,
            expected_core_revision: v.coreRevision,
            expected_control_revision: v.controlRevision,
        });
    }
    catch (error) {
        return { status: "unknown", note: errorText(error) };
    }
    if (failed(r)) {
        const code = r && r.data && r.data.code;
        return { status: "failed", code: code || null, note: COACH_NOTES[code] || messageOf(r, "AI 没有接上") };
    }
    const job = data(r) || {};
    const st = job.status || (job.job && job.job.status);
    return st === "saved" ? { status: "saved" } : { status: st || "unknown", note: "AI 这次没接上，可以点「再问一次」" };
}
// 本人的回答：原话入账，然后请 AI 接着问
async function answer(text) {
    const t = String(text || "").trim();
    if (!t)
        throw new Error("写下你自己的回答");
    const v = await loadTask();
    if (!v.available)
        throw new Error(`后台账本不可用：${v.error}`);
    if (!v.session)
        throw new Error("还没有正在做的事，先开始一件");
    if (!v.real)
        throw new Error("当前这条不是你亲自选的事（是安装测试），先开始一件真实的事");
    if (v.session.status !== "ACTIVE")
        throw new Error("这件事暂停着，先点「我回来了」");
    await apply({ type: "answer", event_id: `hub:answer:${Date.now()}`, session_id: v.session.id, question_id: v.session.questionId, text: t, source: "USER" });
    return { coach: await coach() };
}
async function pause(reason) {
    const v = await loadTask();
    if (!v.session || v.session.status !== "ACTIVE")
        throw new Error("没有进行中的事");
    await apply({ type: "pause", event_id: `hub:pause:${Date.now()}`, session_id: v.session.id, reason: reason || "在主控台点了歇一会儿" });
}
async function resume() {
    const v = await loadTask();
    if (!v.session || v.session.status !== "PAUSED")
        throw new Error("当前没有暂停中的事");
    await apply({ type: "resume", event_id: `hub:resume:${Date.now()}`, session_id: v.session.id });
}
// 开始一件真实的事：优先用后台的 begin_task；后台还没有这个接口时，请用户去认知主控台开始
async function startTask(object, material) {
    const o = String(object || "").trim();
    if (!o)
        throw new Error("写下现在要做的事");
    const m = String(material || "").trim();
    const isLink = /^https?:\/\//.test(m);
    let r;
    try {
        r = await call("cognitive_core:begin_task", { object: o, material_text: isLink ? "" : m, material_ref: isLink ? m : "" });
    }
    catch (error) {
        if (/not found|未找到|不存在/i.test(errorText(error)))
            return { status: "use_backend_console" };
        throw error;
    }
    if (failed(r)) {
        if (/not found|未找到|不存在/i.test(messageOf(r, "")))
            return { status: "use_backend_console" };
        throw new Error(messageOf(r, "开始失败"));
    }
    return { status: "started", coach: await coach() };
}
