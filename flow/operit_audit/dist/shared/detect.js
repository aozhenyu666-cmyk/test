"use strict";
// 纯判断函数：审计时喂真实数据，自测时喂人造的异常数据。
// 不读文件、不调工具，只做判断，所以能被单元测试覆盖——这就是"自测/模拟异常"的基础。
Object.defineProperty(exports, "__esModule", { value: true });
exports.workflowFreshness = workflowFreshness;
exports.failureRate = failureRate;
exports.judgeHealth = judgeHealth;
exports.channelHealth = channelHealth;
exports.lockQueueHealth = lockQueueHealth;
exports.fileFreshness = fileFreshness;
exports.worst = worst;
const MIN = 60 * 1000;
// 工作流调度是否迟到：lastExecutionTime 距现在超过 cadence 的几倍。
function workflowFreshness(now, enabled, lastExecMs, lastStatus, cadenceMin, totalExec) {
    if (!enabled)
        return { verdict: "WARN", detail: "已停用" };
    if (!totalExec || lastExecMs == null)
        return { verdict: "WARN", detail: "从未执行" };
    const ageMin = Math.round((now - lastExecMs) / MIN);
    const late = ageMin > cadenceMin * 3;
    if (lastStatus === "FAILED" && late)
        return { verdict: "FAIL", detail: `最近一次失败，且已 ${ageMin} 分钟没跑（节奏 ${cadenceMin} 分钟）` };
    if (late)
        return { verdict: "FAIL", detail: `已 ${ageMin} 分钟没跑，超过节奏 ${cadenceMin} 分钟的 3 倍` };
    if (lastStatus === "FAILED")
        return { verdict: "WARN", detail: `最近一次 FAILED，但在节奏内（${ageMin} 分钟前）` };
    return { verdict: "PASS", detail: `${ageMin} 分钟前跑过，最近 ${lastStatus}` };
}
// 累计失败率是否过高。
function failureRate(total, failed) {
    if (!total)
        return { verdict: "SKIP", detail: "无执行记录" };
    const pct = Math.round((failed / total) * 100);
    if (pct >= 50)
        return { verdict: "FAIL", detail: `失败率 ${pct}%（${failed}/${total}）` };
    if (pct >= 25)
        return { verdict: "WARN", detail: `失败率 ${pct}%（${failed}/${total}）` };
    return { verdict: "PASS", detail: `失败率 ${pct}%（${failed}/${total}）` };
}
// 判断官是否还在真正出判断：save_log.tsv 的行。
// 行格式："<时间>\tSAVED\tverdict=..." 或 "<时间>\tGATE_SKIP\t..." 或 "<时间>\tREJECTED_OUTPUT\t..."
function judgeHealth(now, lines, cadenceMin) {
    if (lines.length === 0)
        return { verdict: "WARN", detail: "save_log 为空或读不到" };
    const withTs = lines.filter((l) => l.ts != null);
    const lastSaved = withTs.filter((l) => l.kind === "SAVED").sort((a, b) => b.ts - a.ts)[0];
    const lastAny = withTs.sort((a, b) => b.ts - a.ts)[0];
    if (!lastAny)
        return { verdict: "WARN", detail: "save_log 没有带时间戳的行" };
    const lastAnyAge = Math.round((now - lastAny.ts) / MIN);
    // 最近只在 GATE_SKIP、很久没有 SAVED：和之前会话丢失那次的症状一样
    const rejected = withTs.filter((l) => l.kind === "REJECTED_OUTPUT" && now - l.ts < 2 * cadenceMin * MIN).length;
    if (!lastSaved)
        return { verdict: "FAIL", detail: `从没有 SAVED，最近只有 ${lastAny.kind}（${lastAnyAge} 分钟前）` };
    const savedAge = Math.round((now - lastSaved.ts) / MIN);
    if (savedAge > cadenceMin * 8)
        return { verdict: "FAIL", detail: `最近一次 SAVED 在 ${savedAge} 分钟前，疑似判断链断了` };
    if (rejected >= 2)
        return { verdict: "WARN", detail: `最近有 ${rejected} 次 REJECTED_OUTPUT，判断官输出格式可能有问题` };
    if (savedAge > cadenceMin * 3)
        return { verdict: "WARN", detail: `最近一次 SAVED 在 ${savedAge} 分钟前，略旧` };
    return { verdict: "PASS", detail: `最近一次 SAVED 在 ${savedAge} 分钟前` };
}
const MODE_WHITELIST = ["WINDOW", "BALL", "VOICE_BALL", "FULLSCREEN", "RESULT_DISPLAY", "SCREEN_OCR"];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
// 提醒通道配置是否干净：chat_id 是合法 UUID、mode 合法。
function channelHealth(kv) {
    const chatId = (kv.chat_id ?? "").trim();
    const mode = (kv.mode ?? "").trim();
    if (!chatId)
        return { verdict: "FAIL", detail: "channel.txt 没有 chat_id" };
    if (!UUID.test(chatId))
        return { verdict: "FAIL", detail: `chat_id 不是合法 UUID：${chatId.slice(0, 40)}` };
    if (mode && !MODE_WHITELIST.includes(mode))
        return { verdict: "WARN", detail: `mode=${mode} 不在白名单，宿主会退回 VOICE_BALL` };
    return { verdict: "PASS", detail: `chat_id=${chatId.slice(0, 8)}… mode=${mode || "(默认)"}` };
}
// 锁队列是否卡住：inflight 项超过 15 分钟没 ack（工作流中途死掉的信号）。
function lockQueueHealth(now, inflight, queueLen) {
    if (inflight) {
        const ageMin = Math.round((now - inflight.ts) / MIN);
        if (ageMin > 15)
            return { verdict: "FAIL", detail: `有一项卡在途 ${ageMin} 分钟：${inflight.act} ${inflight.pkg}` };
        return { verdict: "PASS", detail: `有一项在途 ${ageMin} 分钟（正常）：${inflight.act} ${inflight.pkg}` };
    }
    if (queueLen > 10)
        return { verdict: "WARN", detail: `队列积压 ${queueLen} 条，worker 可能没在消费` };
    return { verdict: "PASS", detail: queueLen ? `队列 ${queueLen} 条待处理` : "队列空" };
}
// 文件新鲜度：某个状态文件 mtime 距现在。
function fileFreshness(now, exists, mtimeMs, maxAgeMin, label) {
    if (!exists)
        return { verdict: "FAIL", detail: `${label} 不存在` };
    if (mtimeMs == null)
        return { verdict: "WARN", detail: `${label} 读不到修改时间` };
    const ageMin = Math.round((now - mtimeMs) / MIN);
    if (ageMin > maxAgeMin)
        return { verdict: "WARN", detail: `${label} 已 ${ageMin} 分钟没更新（上限 ${maxAgeMin}）` };
    return { verdict: "PASS", detail: `${label} ${ageMin} 分钟前更新` };
}
function worst(verdicts) {
    if (verdicts.includes("FAIL"))
        return "FAIL";
    if (verdicts.includes("WARN"))
        return "WARN";
    if (verdicts.some((v) => v === "PASS"))
        return "PASS";
    return "SKIP";
}
