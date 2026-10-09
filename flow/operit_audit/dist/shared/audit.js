"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.runAudit = runAudit;
exports.renderReport = renderReport;
exports.auditSummary = auditSummary;
const detect_js_1 = require("./detect.js");
const fsx_js_1 = require("./fsx.js");
const spec_js_1 = require("./spec.js");
const time_js_1 = require("./time.js");
async function callTool(name, params = {}) {
    return await toolCall(name, params);
}
async function getWorkflows() {
    try {
        const r = await callTool("workflow:get_all_workflows", {});
        const list = r?.workflows ?? r?.result?.workflows ?? (Array.isArray(r) ? r : []);
        return Array.isArray(list) ? list : [];
    }
    catch {
        return [];
    }
}
async function kvFile(path) {
    const out = {};
    for (const line of await (0, fsx_js_1.readLines)(path)) {
        const m = /^([A-Za-z_]+)\s*=\s*(.*)$/.exec(line.trim());
        if (m)
            out[m[1]] = m[2].replace(/#.*$/, "").trim();
    }
    return out;
}
async function fileMtime(path) {
    try {
        if (!(await (0, fsx_js_1.exists)(path)))
            return { exists: false, mtime: null };
        const info = await Tools.Files.info(path);
        return { exists: true, mtime: (0, time_js_1.parseScriptTime)(info?.lastModified, Date.now()) };
    }
    catch {
        return { exists: false, mtime: null };
    }
}
async function runAudit() {
    const now = Date.now();
    const cfg = await (0, spec_js_1.loadConfig)();
    const groups = [];
    const criticalFails = [];
    // 1) 工作流：启用状态、调度新鲜度、失败率
    const workflows = await getWorkflows();
    const byName = new Map(workflows.map((w) => [String(w.name ?? ""), w]));
    const wfChecks = [];
    if (workflows.length === 0) {
        wfChecks.push({ id: "wf.fetch", title: "读取工作流列表", verdict: "FAIL", detail: "get_all_workflows 没返回数据" });
        criticalFails.push("无法读取工作流列表");
    }
    for (const spec of cfg.workflows) {
        const w = byName.get(spec.name);
        if (!w) {
            const v = spec.expectEnabled ? (spec.critical ? "FAIL" : "WARN") : "SKIP";
            wfChecks.push({ id: `wf.${spec.name}`, title: spec.name, verdict: v, detail: v === "SKIP" ? "不存在（预期如此）" : "工作流不存在" });
            if (v === "FAIL" && spec.critical)
                criticalFails.push(`${spec.name} 不存在`);
            continue;
        }
        const enabled = w.enabled === true;
        if (enabled !== spec.expectEnabled) {
            const v = spec.critical ? "FAIL" : "WARN";
            wfChecks.push({ id: `wf.${spec.name}`, title: spec.name, verdict: v, detail: `期望${spec.expectEnabled ? "启用" : "停用"}，实际${enabled ? "启用" : "停用"}` });
            if (v === "FAIL")
                criticalFails.push(`${spec.name} 开关不符`);
            continue;
        }
        if (!spec.expectEnabled) {
            wfChecks.push({ id: `wf.${spec.name}`, title: spec.name, verdict: "PASS", detail: "已按预期停用" });
            continue;
        }
        const fresh = (0, detect_js_1.workflowFreshness)(now, enabled, w.lastExecutionTime ?? null, w.lastExecutionStatus ?? null, spec.cadenceMin, w.totalExecutions ?? 0);
        const rate = (0, detect_js_1.failureRate)(w.totalExecutions ?? 0, w.failedExecutions ?? 0);
        const v = (0, detect_js_1.worst)([fresh.verdict, rate.verdict]);
        wfChecks.push({ id: `wf.${spec.name}`, title: spec.name, verdict: v, detail: `${fresh.detail}；${rate.detail}` });
        if (v === "FAIL" && spec.critical)
            criticalFails.push(`${spec.name}：${fresh.detail}`);
    }
    groups.push({ group: "工作流", checks: wfChecks });
    // 2) 状态文件新鲜度
    const fileChecks = [];
    for (const fspec of cfg.files) {
        const { exists: ex, mtime } = await fileMtime(fspec.path);
        const r = (0, detect_js_1.fileFreshness)(now, ex, mtime, fspec.maxAgeMin, fspec.label);
        fileChecks.push({ id: `file.${fspec.label}`, title: fspec.label, verdict: r.verdict, detail: r.detail });
        if (r.verdict === "FAIL" && fspec.critical)
            criticalFails.push(`${fspec.label}：${r.detail}`);
    }
    // channel.txt 内容校验
    const ch = (0, detect_js_1.channelHealth)(await kvFile(`${spec_js_1.ROOT}/drift/channel.txt`));
    fileChecks.push({ id: "file.channel", title: "提醒通道配置", verdict: ch.verdict, detail: ch.detail });
    if (ch.verdict === "FAIL")
        criticalFails.push(`提醒通道：${ch.detail}`);
    groups.push({ group: "状态文件", checks: fileChecks });
    // 3) 判断链是否还在出结论（save_log.tsv）
    const execChecks = [];
    const saveLines = (await (0, fsx_js_1.tailLines)(`${spec_js_1.ROOT}/judge/save_log.tsv`, 40)).map((line) => {
        const [time, kind = ""] = line.split("\t");
        return { ts: (0, time_js_1.parseScriptTime)(time, now), kind: kind.trim() };
    });
    const jh = (0, detect_js_1.judgeHealth)(now, saveLines, cfg.judgeCadenceMin);
    execChecks.push({ id: "exec.judge", title: "判断官是否在出结论", verdict: jh.verdict, detail: jh.detail });
    if (jh.verdict === "FAIL")
        criticalFails.push(`判断链：${jh.detail}`);
    // 锁队列是否卡住
    const inflightRaw = (await (0, fsx_js_1.readLines)(`${spec_js_1.ROOT}/events/.lockq.inflight`))[0]?.trim() ?? "";
    const inflightParts = inflightRaw.split(/\s+/);
    const inflight = inflightParts.length >= 3 ? { act: inflightParts[0], pkg: inflightParts[1], ts: (Number(inflightParts[2]) || 0) * 1000 } : null;
    const queueLen = (await (0, fsx_js_1.readLines)(`${spec_js_1.ROOT}/events/lock_queue.txt`)).filter((l) => /^(lock|unlock)\s/.test(l.trim())).length;
    const lq = (0, detect_js_1.lockQueueHealth)(now, inflight, queueLen);
    execChecks.push({ id: "exec.lockqueue", title: "锁队列", verdict: lq.verdict, detail: lq.detail });
    if (lq.verdict === "FAIL")
        criticalFails.push(`锁队列：${lq.detail}`);
    groups.push({ group: "最近执行", checks: execChecks });
    const all = groups.flatMap((g) => g.checks);
    const summary = {
        pass: all.filter((c) => c.verdict === "PASS").length,
        warn: all.filter((c) => c.verdict === "WARN").length,
        fail: all.filter((c) => c.verdict === "FAIL").length,
        skip: all.filter((c) => c.verdict === "SKIP").length,
    };
    return { ts: now, overall: (0, detect_js_1.worst)(all.map((c) => c.verdict)), criticalFail: criticalFails.length > 0, groups, summary };
}
const ICON = { PASS: "✅", WARN: "⚠️", FAIL: "❌", SKIP: "⏭️" };
function renderReport(r) {
    const lines = [];
    lines.push(`# Operit 体检 ${ICON[r.overall]} ${r.overall}`);
    lines.push(`通过 ${r.summary.pass} · 警告 ${r.summary.warn} · 失败 ${r.summary.fail} · 跳过 ${r.summary.skip}`);
    if (r.criticalFail)
        lines.push("⚠️ 有关键链失败，需要处理");
    for (const g of r.groups) {
        lines.push(`\n## ${g.group}`);
        for (const c of g.checks)
            lines.push(`${ICON[c.verdict]} ${c.title}：${c.detail}`);
    }
    return lines.join("\n");
}
async function auditSummary() {
    try {
        const report = await runAudit();
        return { report, text: renderReport(report) };
    }
    catch (error) {
        throw new Error((0, fsx_js_1.errorText)(error));
    }
}
