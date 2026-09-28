"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.isQuiet = isQuiet;
exports.updateEpisode = updateEpisode;
exports.plan = plan;
const time_js_1 = require("./time.js");
const MIN = 60 * 1000;
const ASK_AFTER_MIN = 10;
const PAUSE_HOLD_MIN = 90;
const CHECKIN_GAP_MIN = 150;
const SHOT_GAP_MIN = 30;
function hmToMin(hm) {
    const [h, m] = hm.split(":").map(Number);
    return h * 60 + m;
}
function isQuiet(now, cfg) {
    const m = (0, time_js_1.bjMinutesOfDay)(now);
    const s = hmToMin(cfg.quiet_start);
    const e = hmToMin(cfg.quiet_end);
    return s > e ? m >= s || m < e : m >= s && m < e;
}
function speakBudget(now, state, facts, cfg, urgent) {
    const hour = state.speaks.filter((t) => now - t < 60 * MIN);
    if (hour.length >= cfg.max_speaks_per_hour)
        return { ok: false, why: `一小时内已开口 ${hour.length} 次` };
    if (urgent)
        return { ok: true, why: "" };
    const last = Math.max(0, ...state.speaks);
    if (now - last < cfg.min_gap_minutes * MIN)
        return { ok: false, why: "距上次开口太近" };
    const other = Math.max(0, ...facts.otherAlerts);
    if (now - other < cfg.min_gap_minutes * MIN)
        return { ok: false, why: "规则提醒刚说过" };
    return { ok: true, why: "" };
}
// 更新本轮的"跑偏片段"：返回新的 episode（可能为 null）以及是否刚刚回到正轨
function updateEpisode(facts, state) {
    const now = facts.now;
    const latest = facts.samples[0];
    const fresh = latest && now - latest.ts < 25 * MIN ? latest : null;
    const lastProgress = facts.progress[0];
    const paused = !!lastProgress && lastProgress.kind === "pause" && now - lastProgress.ts < PAUSE_HOLD_MIN * MIN;
    const driftSamples = facts.samples.filter((s) => now - s.ts < 40 * MIN && s.ontask === "0" && s.screen !== "OFF");
    const judgeDrift = !!facts.verdict && facts.verdict.verdict === "DRIFT_RISK" && facts.verdict.conf >= 70 && now - facts.verdict.ts < 40 * MIN;
    const drifting = !!fresh && fresh.ontask === "0" && (driftSamples.length >= 2 || judgeDrift);
    const onTask = !!fresh && fresh.ontask === "1";
    let episode = state.episode ? { ...state.episode } : null;
    if (paused)
        return { episode: null, backOnTrack: false, paused, driftPkg: "" };
    if (drifting) {
        if (!episode) {
            const oldest = driftSamples[driftSamples.length - 1];
            episode = { start_ts: oldest ? oldest.ts : now, pkg: fresh.pkg, nudges: 0, first_nudge_ts: null, last_nudge_ts: null, locked: false };
        }
        else {
            episode.pkg = fresh.pkg;
        }
        // TA 在上次提醒之后回过话（进展记录）：算回应，给一个新的宽限期，但不取消这一段
        if (episode.last_nudge_ts && lastProgress && lastProgress.ts > episode.last_nudge_ts) {
            episode.first_nudge_ts = lastProgress.ts;
            episode.last_nudge_ts = lastProgress.ts;
            episode.nudges = Math.max(1, episode.nudges);
        }
        return { episode, backOnTrack: false, paused, driftPkg: episode.pkg };
    }
    const backOnTrack = !!episode && episode.nudges > 0 && onTask;
    return { episode: null, backOnTrack, paused, driftPkg: "" };
}
function plan(facts, state, cfg, commitments, asked) {
    const now = facts.now;
    const quiet = isQuiet(now, cfg);
    const { episode, backOnTrack, paused, driftPkg } = updateEpisode(facts, state);
    const due = commitments.filter((c) => c.status === "open" && c.due_ts <= now && !asked[c.id]);
    // 正在招聘 App 里干活时不去打扰（很久没动静的问候只在没在干活时才发）
    const latest = facts.samples[0];
    const working = !!latest && now - latest.ts < 25 * MIN && latest.ontask === "1";
    let stage = "none";
    let reason = "";
    let allowed = ["none"];
    let lockPkg = "";
    if (episode) {
        const sinceFirst = episode.first_nudge_ts ? now - episode.first_nudge_ts : 0;
        const sinceLast = episode.last_nudge_ts ? now - episode.last_nudge_ts : Infinity;
        const lockable = facts.lockable.includes(episode.pkg) &&
            !facts.protectedPkgs.includes(episode.pkg) &&
            !facts.jobApps.includes(episode.pkg) &&
            now - (state.locks[episode.pkg] ?? 0) > cfg.relock_cooldown_minutes * MIN;
        if (episode.nudges === 0) {
            stage = "nudge";
            reason = "刚发现跑偏";
            allowed = ["none", "open_app"];
        }
        else if (!episode.locked && lockable && sinceFirst >= cfg.lock_grace_minutes * MIN) {
            stage = "lock";
            reason = `提醒后 ${Math.round(sinceFirst / MIN)} 分钟没有回应，按约定锁`;
            allowed = ["lock"];
            lockPkg = episode.pkg;
        }
        else if (sinceLast >= ASK_AFTER_MIN * MIN) {
            stage = "ask";
            reason = episode.locked ? "锁了之后还在分心" : "提醒过还没回来";
            allowed = ["none", "open_app", "record_commitment", "assign"];
        }
        else {
            reason = "刚提醒过，等一等";
        }
    }
    else if (due.length > 0 && !paused) {
        stage = "commitment_due";
        reason = "有承诺到点了";
        allowed = ["none", "open_app", "record_commitment"];
    }
    else if (backOnTrack) {
        stage = "praise";
        reason = "提醒后回到了正轨";
    }
    else if (!paused && facts.taskState === "ACTIVE" && !working) {
        const lastSpeak = Math.max(0, ...state.speaks, state.last_idle_checkin_ts);
        const lastProgress = facts.progress[0]?.ts ?? 0;
        if (now - lastSpeak > CHECKIN_GAP_MIN * MIN && now - lastProgress > 3 * 60 * MIN) {
            stage = "checkin";
            reason = "很久没动静了";
            allowed = ["none", "record_commitment", "assign"];
        }
    }
    if (paused && stage === "none")
        reason = "TA 说了暂停";
    const budget = speakBudget(now, state, facts, cfg, stage === "lock");
    const canSpeak = stage !== "none" && !quiet && budget.ok;
    if (stage !== "none" && !canSpeak)
        reason += quiet ? "（安静时段）" : `（${budget.why}）`;
    // 安静时段也不锁
    if (quiet && stage === "lock") {
        allowed = ["none"];
        lockPkg = "";
    }
    const wantShot = (stage === "ask" || stage === "lock") && now - state.last_shot_ts > SHOT_GAP_MIN * MIN;
    const sig = [stage, episode?.pkg ?? "", episode?.nudges ?? 0, facts.progress[0]?.ts ?? 0, due.map((d) => d.id).join(",")].join("|");
    return {
        plan: { stage, reason, allowed, canSpeak, quiet, driftPkg, lockPkg, due, wantShot, sig },
        episode,
    };
}
