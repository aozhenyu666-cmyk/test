"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.tick = tick;
exports.recordCommitment = recordCommitment;
exports.memoryBlock = memoryBlock;
exports.reflect = reflect;
exports.status = status;
exports.drill = drill;
const act_js_1 = require("./act.js");
const facts_js_1 = require("./facts.js");
const fsx_js_1 = require("./fsx.js");
const memory_js_1 = require("./memory.js");
const rules_js_1 = require("./rules.js");
const think_js_1 = require("./think.js");
const time_js_1 = require("./time.js");
const fsx_js_2 = require("./fsx.js");
// 用户自己报了"完成"：之后到点的承诺算兑现
async function settleCommitments(facts, now) {
    const open = (await (0, memory_js_1.loadCommitments)()).filter((c) => c.status === "open");
    for (const c of open) {
        const done = facts.progress.find((p) => p.kind === "done" && p.ts > c.ts);
        if (done)
            await (0, memory_js_1.setCommitmentStatus)(c.id, "kept", now, `TA 报了完成：${done.quote.slice(0, 40)}`);
    }
}
async function tick(options = {}) {
    const now = Date.now();
    const cfg = await (0, memory_js_1.loadConfig)();
    const state = await (0, memory_js_1.loadState)();
    const facts = await (0, facts_js_1.gatherFacts)(now);
    await settleCommitments(facts, now);
    const commitments = await (0, memory_js_1.loadCommitments)();
    const open = commitments.filter((c) => c.status === "open");
    // 每晚 23 点后复盘一次（北京时间）
    const today = (0, time_js_1.bjDateKey)(now);
    if ((0, time_js_1.bjParts)(now).h >= 23 && state.last_reflect_date !== today) {
        try {
            await reflect(now);
        }
        catch (error) {
            await (0, memory_js_1.logAction)({ ts: now, type: "REFLECT_FAIL", error: (0, fsx_js_1.errorText)(error) });
        }
        state.last_reflect_date = today;
    }
    const { plan, episode } = (0, rules_js_1.plan)(facts, state, cfg, commitments, state.asked_commitments);
    state.episode = episode;
    if (plan.stage === "none" || !plan.canSpeak) {
        await (0, memory_js_1.saveState)(state);
        return { status: plan.stage === "none" ? "IDLE" : "HOLD", stage: plan.stage, reason: plan.reason };
    }
    if (!options.force && plan.sig === state.last_sig && plan.stage !== "lock") {
        await (0, memory_js_1.saveState)(state);
        return { status: "SAME_SITUATION", stage: plan.stage, reason: plan.reason };
    }
    if (plan.wantShot && cfg.act) {
        const fact = await (0, act_js_1.lookAtScreen)();
        state.last_shot_ts = now;
        if (fact)
            facts.shotFact = fact;
    }
    const profile = await (0, memory_js_1.loadProfile)();
    const said = await (0, memory_js_1.recentSaid)(8);
    const d = await (0, think_js_1.think)(facts, plan, profile, open, said);
    state.last_think_ts = now;
    state.last_sig = plan.sig;
    const results = [];
    let delivered = "dry-run";
    if (cfg.act) {
        if (d.action === "lock" && plan.lockPkg) {
            const r = await (0, act_js_1.enqueueLock)(plan.lockPkg, facts.protectedPkgs, facts.lockable);
            results.push(`lock ${plan.lockPkg}: ${r}`);
            if (r === "QUEUED") {
                state.locks[plan.lockPkg] = now;
                if (state.episode)
                    state.episode.locked = true;
            }
        }
        if (d.speak && d.line) {
            const popup = ["ask", "lock", "commitment_due"].includes(plan.stage);
            const del = await (0, act_js_1.speak)(d.line, "小满", popup);
            delivered = del.via.join("+") + (del.errors.length ? ` | ${del.errors.join("; ")}` : "");
            await (0, act_js_1.logAlertEvent)(plan.stage, now);
        }
        if (d.action === "open_app" && d.openApp)
            results.push(`open ${d.openApp}: ${await (0, act_js_1.openApp)(d.openApp)}`);
        if (d.action === "assign" && d.assign) {
            const a = await (0, act_js_1.setAssignment)(d.assign.task, d.assign.minutes, now);
            await (0, memory_js_1.addCommitment)({ text: d.assign.task, due_ts: a.deadline, quote: d.line, source: "assigned" }, now);
            results.push(`assign ${d.assign.task} → ${(0, time_js_1.bjHM)(a.deadline)}`);
        }
    }
    if (d.action === "record_commitment" && d.commitment) {
        await (0, memory_js_1.addCommitment)({ text: d.commitment.text, due_ts: now + d.commitment.dueMinutes * 60000, quote: d.commitment.quote, source: "user" }, now);
        results.push(`commitment ${d.commitment.text}`);
    }
    if (d.memoryNote)
        await (0, memory_js_1.appendObservation)([d.memoryNote], now);
    if (d.speak) {
        state.speaks = [...state.speaks.filter((t) => now - t < 3 * 3600 * 1000), now];
        if (state.episode && ["nudge", "ask", "lock"].includes(plan.stage)) {
            state.episode.nudges += 1;
            state.episode.first_nudge_ts = state.episode.first_nudge_ts ?? now;
            state.episode.last_nudge_ts = now;
        }
        if (plan.stage === "commitment_due")
            for (const c of plan.due)
                state.asked_commitments[c.id] = now;
        if (plan.stage === "checkin")
            state.last_idle_checkin_ts = now;
        await (0, memory_js_1.appendSaid)({ ts: now, stage: plan.stage, line: d.line, action: d.action, pkg: plan.lockPkg || plan.driftPkg || undefined, why: d.why, delivered });
    }
    await (0, memory_js_1.logAction)({ ts: now, type: "TICK", stage: plan.stage, reason: plan.reason, source: d.source, speak: d.speak, line: d.line, action: d.action, results, delivered, why: d.why, error: d.error ?? "" });
    await (0, memory_js_1.saveState)(state);
    return { status: d.speak ? "SPOKE" : "DECIDED_SILENT", stage: plan.stage, reason: plan.reason, line: d.line, action: [d.action, ...results].join("; "), delivered, why: d.why, error: d.error };
}
// ---------- 小满·陪伴 在对话里记承诺 ----------
async function recordCommitment(params) {
    const now = Date.now();
    const text = String(params.text ?? "").trim();
    if (!text)
        throw new Error("缺少 text");
    let due = null;
    if (params.due_time)
        due = (0, time_js_1.bjTimeToday)(String(params.due_time), now);
    if (!due && params.due_minutes != null) {
        const m = Number(params.due_minutes);
        if (Number.isFinite(m) && m > 0)
            due = now + m * 60000;
    }
    if (!due)
        due = now + 60 * 60000;
    const c = await (0, memory_js_1.addCommitment)({ text: text.slice(0, 60), due_ts: due, quote: String(params.quote ?? text).slice(0, 80), source: "user" }, now);
    return `已记下：${c.text}（北京时间 ${(0, time_js_1.bjHM)(c.due_ts)} 前）`;
}
// ---------- 注入到小满·陪伴每一轮对话的记忆 ----------
async function memoryBlock() {
    const now = Date.now();
    const [profile, commitments, said, state, facts] = await Promise.all([(0, memory_js_1.loadProfile)(), (0, memory_js_1.loadCommitments)(), (0, memory_js_1.recentSaid)(3), (0, memory_js_1.loadState)(), (0, facts_js_1.gatherFacts)(now)]);
    const open = commitments.filter((c) => c.status === "open");
    const lines = [];
    lines.push(`【小满的记忆（系统自动附上，只供你参考，不要照念）】北京时间 ${(0, time_js_1.bjHM)(now)}`);
    lines.push("关于 TA：");
    lines.push(profile.slice(0, 1500));
    lines.push("TA 答应过、还没兑现的事：");
    lines.push(open.length ? open.slice(0, 5).map((c) => `- ${c.text}（${c.due_ts <= now ? "已到点" : `${(0, time_js_1.bjHM)(c.due_ts)} 前`}；原话「${c.quote}」）`).join("\n") : "- 没有");
    lines.push("你最近主动对 TA 说过的话：");
    lines.push(said.length ? said.map((s) => `- ${(0, time_js_1.minutesAgoText)(s.ts, now)}：${s.line}`).join("\n") : "- 没有");
    lines.push(`TA 最近报的进展：${facts.progress.slice(0, 3).map((p) => `${(0, time_js_1.minutesAgoText)(p.ts, now)}「${p.quote}」`).join("；") || "没有"}`);
    if (state.episode)
        lines.push(`现在：TA 在刷${(0, facts_js_1.appName)(state.episode.pkg)}，你已经提醒过 ${state.episode.nudges} 次${state.episode.locked ? "，已按约定暂停了它" : ""}。`);
    lines.push("TA 在对话里说出带时间的打算（比如「一点前投一家」）时，用 companion_brain:record_commitment 记下来；说到进展时照旧用 report_progress。");
    return lines.join("\n");
}
// ---------- 每晚复盘 ----------
async function reflect(now) {
    const today = (0, time_js_1.bjDateKey)(now);
    const said = (0, fsx_js_1.parseJsonl)(await (0, fsx_js_1.tailLines)(memory_js_1.PATHS.said, 80)).filter((s) => (0, time_js_1.bjDateKey)(s.ts) === today);
    const facts = await (0, facts_js_1.gatherFacts)(now);
    const todays = facts.progress.filter((p) => (0, time_js_1.bjDateKey)(p.ts) === today);
    const commitments = await (0, memory_js_1.loadCommitments)();
    // 过期 2 小时仍未兑现的承诺记为未兑现（代码判，不靠模型）
    for (const c of commitments) {
        if (c.status === "open" && now - c.due_ts > 2 * 3600 * 1000)
            await (0, memory_js_1.setCommitmentStatus)(c.id, "broken", now, "过期未报完成");
    }
    const timeline = [
        ...said.map((s) => ({ ts: s.ts, text: `小满（${s.stage}）：${s.line}` })),
        ...todays.map((p) => ({ ts: p.ts, text: `TA（${p.kind}）：${p.quote}` })),
    ]
        .sort((a, b) => a.ts - b.ts)
        .map((x) => `${(0, time_js_1.bjHM)(x.ts)} ${x.text}`)
        .join("\n");
    if (!timeline.trim())
        return "今天没有可复盘的对话";
    const kept = commitments.filter((c) => (0, time_js_1.bjDateKey)(c.ts) === today).map((c) => `${c.text}：${c.status}`).join("；") || "无";
    const prompt = `下面是今天小满主动说的话和 TA 的回应（北京时间）。请复盘：哪种说法换来了 TA 的回应或行动，哪种被无视；TA 今天的状态规律。
只输出 JSON：{"observations": ["最多 4 条，每条一句，具体、可用于明天调整说法"], "worked": ["管用的说法特征"], "ignored": ["被无视的说法特征"]}

${timeline}

今天的承诺：${kept}`;
    let parsed = {};
    try {
        const r = await Tools.Chat.call({ functionType: "SUMMARY", turns: [{ kind: "USER", content: prompt }], enableThinking: false });
        const text = String(r?.text ?? "");
        const s = text.indexOf("{");
        const e = text.lastIndexOf("}");
        parsed = s >= 0 && e > s ? JSON.parse(text.slice(s, e + 1)) : {};
    }
    catch (error) {
        await (0, memory_js_1.logAction)({ ts: now, type: "REFLECT_MODEL_FAIL", error: (0, fsx_js_1.errorText)(error) });
    }
    const notes = [
        ...(parsed.observations ?? []),
        ...(parsed.worked ?? []).map((w) => `管用：${w}`),
        ...(parsed.ignored ?? []).map((w) => `被无视：${w}`),
    ];
    await (0, memory_js_1.appendObservation)(notes, now);
    await (0, fsx_js_2.appendJsonl)(memory_js_1.PATHS.reflections, { ts: now, date: today, notes, said: said.length, progress: todays.length });
    return notes.length ? notes.join("\n") : "复盘没有产出";
}
async function status() {
    const now = Date.now();
    const cfg = await (0, memory_js_1.loadConfig)();
    const state = await (0, memory_js_1.loadState)();
    const facts = await (0, facts_js_1.gatherFacts)(now);
    const commitments = await (0, memory_js_1.loadCommitments)();
    const { plan } = (0, rules_js_1.plan)(facts, state, cfg, commitments, state.asked_commitments);
    return {
        now: (0, time_js_1.bjHM)(now),
        config: cfg,
        plan: { stage: plan.stage, reason: plan.reason, allowed: plan.allowed, canSpeak: plan.canSpeak, lockPkg: plan.lockPkg },
        episode: state.episode,
        speaksLastHour: state.speaks.filter((t) => now - t < 3600 * 1000).length,
        openCommitments: commitments.filter((c) => c.status === "open"),
        recentSaid: await (0, memory_js_1.recentSaid)(5),
        latestSample: facts.samples[0] ?? null,
        verdict: facts.verdict,
    };
}
async function drill(scenario, options = {}) {
    const MIN = 60000;
    const cfg = await (0, memory_js_1.loadConfig)();
    const real = await (0, memory_js_1.loadState)();
    let now = Date.now();
    if (scenario === "quiet") {
        // 挪到北京时间当天 00:30（安静时段）
        const p = (0, time_js_1.bjParts)(now);
        now = Date.UTC(p.y, p.mo - 1, p.d, 0, 30) - 8 * 3600 * 1000 + 24 * 3600 * 1000;
    }
    const facts = await (0, facts_js_1.gatherFacts)(now);
    facts.now = now;
    const app = options.app || "com.baidu.tieba";
    const off = (m) => ({ ts: now - m * MIN, pkg: app, ontask: "0", screen: "ON" });
    facts.samples = scenario === "working" ? [{ ts: now - 5 * MIN, pkg: facts.jobApps[0] ?? "com.hpbr.bosszhipin", ontask: "1", screen: "ON" }] : [off(3), off(18), off(33)];
    facts.otherAlerts = [];
    facts.progress = scenario === "pause" ? [{ ts: now - 5 * MIN, kind: "pause", quote: "我去吃个饭，一小时后开始", note: "", via: "drill" }] : [];
    const state = { ...real, speaks: [], last_sig: "", locks: {}, asked_commitments: {} };
    state.episode =
        scenario === "ask"
            ? { start_ts: now - 33 * MIN, pkg: app, nudges: 1, first_nudge_ts: now - 12 * MIN, last_nudge_ts: now - 12 * MIN, locked: false }
            : scenario === "lock" || scenario === "quiet"
                ? { start_ts: now - 50 * MIN, pkg: app, nudges: 2, first_nudge_ts: now - 35 * MIN, last_nudge_ts: now - 12 * MIN, locked: false }
                : null;
    const commitments = scenario === "commitment"
        ? [{ id: "C-drill", ts: now - 60 * MIN, text: "投一家", due_ts: now - 2 * MIN, quote: "三点前我投一家", source: "user", status: "open" }]
        : [];
    if (scenario === "commitment")
        facts.samples = [{ ts: now - 5 * MIN, pkg: "none", ontask: "NONE", screen: "OFF" }];
    const { plan } = (0, rules_js_1.plan)(facts, state, cfg, commitments, {});
    const out = {
        scenario,
        note: "演练：事实是模拟的，规则层和模型是真的；不写记忆、不改大脑状态",
        plan: { stage: plan.stage, reason: plan.reason, allowed: plan.allowed, canSpeak: plan.canSpeak, lockPkg: plan.lockPkg },
    };
    if (plan.stage === "none" || !plan.canSpeak)
        return { ...out, decision: "不开口" };
    const d = await (0, think_js_1.think)(facts, plan, await (0, memory_js_1.loadProfile)(), commitments, await (0, memory_js_1.recentSaid)(5));
    out.decision = { speak: d.speak, line: d.line, action: d.action, openApp: d.openApp, assign: d.assign, commitment: d.commitment, why: d.why, source: d.source, error: d.error ?? "" };
    const done = [];
    if (options.execute) {
        if (d.action === "lock" && plan.lockPkg)
            done.push(`lock ${plan.lockPkg}: ${await (0, act_js_1.enqueueLock)(plan.lockPkg, facts.protectedPkgs, facts.lockable)}`);
        if (d.speak && d.line) {
            const del = await (0, act_js_1.speak)(d.line, "小满", ["ask", "lock", "commitment_due"].includes(plan.stage));
            done.push(`speak: ${del.via.join("+")}${del.errors.length ? ` | ${del.errors.join("; ")}` : ""}`);
        }
        if (d.action === "open_app" && d.openApp)
            done.push(`open ${d.openApp}: ${await (0, act_js_1.openApp)(d.openApp)}`);
        if (d.action === "assign" && d.assign)
            done.push(`assign（演练不写派活文件）：${d.assign.task} ${d.assign.minutes} 分钟`);
    }
    out.executed = options.execute ? done : "没有执行（execute=false）";
    await (0, memory_js_1.logAction)({ ts: Date.now(), type: "DRILL", scenario, stage: plan.stage, line: d.line, action: d.action, executed: done });
    return out;
}
