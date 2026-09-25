"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MOOD_LEVELS = void 0;
exports.pendingCheckinOf = pendingCheckinOf;
exports.computeMood = computeMood;
// 心情只是把真实数据和规则阶梯换一种说法呈现；它不执行任何动作
exports.MOOD_LEVELS = ["安心", "在意", "担心", "要谈谈"];
const LEVEL_FLOORS = [0, 20, 45, 70];
function clock(tsSec) {
    const d = new Date(tsSec * 1000);
    const pad = (n) => (n < 10 ? `0${n}` : String(n));
    return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function levelOf(score) {
    let level = 0;
    LEVEL_FLOORS.forEach((floor, index) => {
        if (score >= floor)
            level = index;
    });
    return level;
}
// 最近一次打卡之后还没有任何进展记录，就算没回应
function pendingCheckinOf(checkins, progress, nowSec) {
    const last = checkins[0];
    if (!last || nowSec - last.ts > 3 * 3600)
        return null;
    const answered = progress.some((p) => p.ts >= last.ts);
    return answered ? null : last;
}
function ruleAction(execRules, index, fallback) {
    const row = execRules?.[index];
    const action = row && row.length >= 3 ? row[row.length - 1] : "";
    const id = row?.[0] ?? "";
    return action ? `${action}${id ? `（${id.split("_")[0]}）` : ""}` : fallback;
}
function computeMood(input) {
    const nowSec = Math.floor(input.now / 1000);
    const reasons = [];
    let score = 0;
    const recent = input.focus?.recent;
    const recentTotal = recent ? recent.on + recent.off : 0;
    if (recent && recentTotal > 0 && recent.off > 0) {
        score += Math.round((recent.off / recentTotal) * 50);
        reasons.push(`近 1 小时 ${recentTotal} 次采样有 ${recent.off} 次不在任务上`);
    }
    const drifts = (input.focus?.drifts ?? []).filter((d) => nowSec - d.ts <= 2 * 3600);
    if (drifts.length > 0) {
        score += Math.min(30, drifts.length * 15);
        reasons.push(`2 小时内偏移 ${drifts.length} 次`);
    }
    const progress = [...input.progress].sort((a, b) => b.ts - a.ts);
    const latest = progress[0];
    if (latest && latest.kind === "stuck" && nowSec - latest.ts <= 3 * 3600) {
        score += 15;
        reasons.push(`你说卡在「${latest.user_quote}」，还没解开`);
    }
    const pending = pendingCheckinOf(input.checkins, input.progress, nowSec);
    if (pending && nowSec - pending.ts > 30 * 60) {
        score += 10;
        reasons.push(`${clock(pending.ts)} 来找过你，还没回她`);
    }
    const hour = new Date(input.now).getHours();
    const todayProgress = progress.filter((p) => new Date(p.ts * 1000).toDateString() === new Date(input.now).toDateString());
    if (todayProgress.length === 0 && hour >= 11 && recent && recent.off > 0) {
        score += 10;
        reasons.push("今天还没记过进展");
    }
    let relief = 0;
    for (const p of progress) {
        if (nowSec - p.ts > 90 * 60)
            break;
        if (p.kind === "done" || p.kind === "progress") {
            relief += 15;
            if (relief <= 30)
                reasons.push(`${clock(p.ts)} 你说「${p.user_quote}」`);
        }
        else if (p.kind === "pause") {
            relief += 20;
            reasons.push(`${clock(p.ts)} 你说要暂停：「${p.user_quote}」`);
        }
    }
    score -= Math.min(relief, 40);
    score = Math.max(0, Math.min(100, score));
    const level = levelOf(score);
    const task = input.task || "正事";
    const app = recent?.offApps[0] ?? "";
    let line;
    if (level === 0) {
        line =
            todayProgress.length > 0
                ? `${task}今天已经记了 ${todayProgress.length} 条啦，好厉害～ 我就在旁边陪着你。`
                : recentTotal === 0
                    ? "今天还没看到你的动静呢～ 在忙什么呀？"
                    : `我在这儿呢～ ${task}先从哪一步开始？`;
    }
    else if (level === 1) {
        line = app
            ? `${app}已经开了一会儿啦～ ${task}那边还等着你，我们先回去好不好？`
            : `好像有点走神啦～ ${task}那边还等着你呢。`;
    }
    else if (level === 2) {
        line =
            latest?.kind === "stuck"
                ? `你说卡在「${latest.user_quote}」……要不要跟我说说卡在哪？我们一起想办法。`
                : `离开${task}挺久了哦……是不是遇到什么麻烦了？跟我说说嘛。`;
    }
    else {
        line = app ? `不可以再刷${app}了！先停一下，我们聊聊好不好？` : "先停一下好不好？我们聊聊。";
    }
    const next = [
        "保持就好；偏离了我会先提醒你",
        `再偏离下去：${ruleAction(input.execRules, 0, "会提醒你")}`,
        `再这样下去：${ruleAction(input.execRules, 1, "会来追问你")}`,
        `再这样下去：${ruleAction(input.execRules, 2, "可能暂停干扰应用")}`,
    ][level];
    return {
        score,
        level,
        name: exports.MOOD_LEVELS[level],
        line,
        reasons: reasons.length > 0 ? reasons : ["现在没有需要在意的事"],
        next,
        pendingCheckin: pending,
    };
}
