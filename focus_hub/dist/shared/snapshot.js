"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PATHS = void 0;
exports.formatClock = formatClock;
exports.formatDateTime = formatDateTime;
exports.formatAgo = formatAgo;
exports.collectSnapshot = collectSnapshot;
const paths_js_1 = require("./paths.js");
Object.defineProperty(exports, "PATHS", { enumerable: true, get: function () { return paths_js_1.PATHS; } });
const progress_js_1 = require("./progress.js");
const health_js_1 = require("./health.js");
const checkin_js_1 = require("./checkin.js");
const companion_js_1 = require("./companion.js");
// WorkManager 不接受小于 15 分钟的周期，宿主会把间隔抬到 15 分钟
const MIN_SCHEDULE_INTERVAL_MS = 15 * 60 * 1000;
const STALE_GRACE_MS = 5 * 60 * 1000;
const EVENT_TAIL_LINES = 40;
const EVENT_MAX_LINES = 3000;
const RECENT_WINDOW_SEC = 60 * 60;
const ACTION_TAIL_LINES = 12;
function errorText(error) {
    if (error && typeof error === "object" && "message" in error) {
        return String(error.message);
    }
    return String(error);
}
// 宿主读文件时会给每行加 "  12| " 行号前缀，并在超过 32KB 时追加截断标记
const LINE_NUMBER_PREFIX = /^\s*\d+\| ?/;
const TRUNCATED_MARK = "... (file content truncated) ...";
function stripLineNumbers(content) {
    const lines = [];
    for (const line of String(content ?? "").split("\n")) {
        if (LINE_NUMBER_PREFIX.test(line)) {
            lines.push(line.replace(LINE_NUMBER_PREFIX, ""));
        }
    }
    return lines;
}
async function fileExists(path) {
    const result = await Tools.Files.exists(path);
    return Boolean(result && result.exists && !result.isDirectory);
}
async function readHead(path, maxLines) {
    const part = await Tools.Files.readPart(path, 1, maxLines);
    return {
        lines: stripLineNumbers(part.content),
        totalLines: part.totalLines,
        truncated: part.content.includes(TRUNCATED_MARK),
    };
}
async function readTail(path, count) {
    const probe = await Tools.Files.readPart(path, 1, 1);
    const totalLines = probe.totalLines;
    if (totalLines <= 0) {
        return { lines: [], totalLines: 0, truncated: false };
    }
    const start = Math.max(1, totalLines - count + 1);
    const part = await Tools.Files.readPart(path, start, totalLines);
    return {
        lines: stripLineNumbers(part.content),
        totalLines,
        truncated: part.content.includes(TRUNCATED_MARK),
    };
}
// 宿主单次最多返回 32KB，按块读完整个文件；块内被截断就减半重读
async function readAll(path, maxLines) {
    const probe = await Tools.Files.readPart(path, 1, 1);
    const totalLines = probe.totalLines;
    const lines = [];
    let start = Math.max(1, totalLines - maxLines + 1);
    let chunk = 60;
    while (totalLines > 0 && start <= totalLines) {
        const end = Math.min(totalLines, start + chunk - 1);
        const part = await Tools.Files.readPart(path, start, end);
        if (part.content.includes(TRUNCATED_MARK) && chunk > 1) {
            chunk = Math.max(1, Math.floor(chunk / 2));
            continue;
        }
        lines.push(...stripLineNumbers(part.content));
        start = end + 1;
    }
    return { lines, totalLines, truncated: false };
}
async function loadFile(label, path, read) {
    try {
        if (!(await fileExists(path))) {
            return { value: null, source: { label, status: "MISSING", detail: `文件不存在：${path}` } };
        }
        return { value: await read(), source: { label, status: "OK", detail: path } };
    }
    catch (error) {
        return { value: null, source: { label, status: "ERROR", detail: errorText(error) } };
    }
}
function pad2(value) {
    return value < 10 ? `0${value}` : String(value);
}
function formatClock(ms) {
    const d = new Date(ms);
    return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}
function formatDateTime(ms) {
    const d = new Date(ms);
    return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${formatClock(ms)}`;
}
function formatAgo(ms, now) {
    const diff = Math.max(0, now - ms);
    const minutes = Math.floor(diff / 60000);
    if (minutes < 1)
        return "刚刚";
    if (minutes < 60)
        return `${minutes} 分钟前`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24)
        return `${hours} 小时 ${minutes % 60} 分钟前`;
    return `${Math.floor(hours / 24)} 天前`;
}
function dateKey(d) {
    return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}`;
}
function parseTaskState(lines) {
    const map = {};
    for (const line of lines) {
        const index = line.indexOf("=");
        if (index > 0) {
            map[line.slice(0, index).trim()] = line.slice(index + 1).trim();
        }
    }
    return {
        state: map.STATE ?? "",
        task: map.TASK ?? "",
        mode: map.MODE ?? "",
        syncedAt: map.SYNCED_AT ?? "",
    };
}
// 与宿主 WorkflowScheduler.calculateCronInterval 的简化规则一致
function cronSchedule(expression) {
    const parts = expression.trim().split(/\s+/);
    if (parts.length < 5)
        return { label: `cron ${expression}`, intervalMs: null };
    const [minute, hour] = parts;
    if (/^\d+$/.test(minute) && /^\d+$/.test(hour)) {
        return { label: `每天 ${pad2(Number(hour))}:${pad2(Number(minute))}`, intervalMs: 24 * 3600 * 1000 };
    }
    if (minute === "0" && hour.startsWith("*/")) {
        const n = Number(hour.slice(2));
        if (n > 0)
            return { label: `每 ${n} 小时`, intervalMs: n * 3600 * 1000 };
    }
    if (minute.startsWith("*/") && hour === "*") {
        const n = Number(minute.slice(2));
        if (n > 0)
            return { label: `每 ${n} 分钟`, intervalMs: n * 60 * 1000 };
    }
    return { label: `cron ${expression}`, intervalMs: null };
}
async function loadSchedule(workflowId) {
    const detail = await Tools.Workflow.get(workflowId);
    for (const node of detail.nodes ?? []) {
        // get_workflow 返回的节点可能只有 __type 没有 type 字段，按 triggerType 识别
        const trigger = node;
        if (trigger.triggerType !== "schedule")
            continue;
        const config = trigger.triggerConfig ?? {};
        if (config.enabled === "false")
            continue;
        const repeat = config.repeat !== "false";
        let info;
        if (config.schedule_type === "interval") {
            const interval = Math.max(Number(config.interval_ms) || 0, MIN_SCHEDULE_INTERVAL_MS);
            info = { label: `每 ${Math.round(interval / 60000)} 分钟`, intervalMs: interval };
        }
        else if (config.schedule_type === "cron" && config.cron_expression) {
            info = cronSchedule(config.cron_expression);
        }
        else if (config.schedule_type === "specific_time") {
            return { label: `一次性 ${config.specific_time ?? ""}`.trim(), intervalMs: null };
        }
        else {
            continue;
        }
        if (!repeat)
            return { label: `${info.label}（不重复）`, intervalMs: null };
        if (info.intervalMs != null) {
            info.intervalMs = Math.max(info.intervalMs, MIN_SCHEDULE_INTERVAL_MS);
        }
        return info;
    }
    return null;
}
function staleThresholdMs(intervalMs) {
    // 短周期容忍错过一拍；日级任务只多给一小时左右的余量
    if (intervalMs <= 3600 * 1000)
        return intervalMs * 2 + STALE_GRACE_MS;
    return intervalMs + Math.max(3600 * 1000, intervalMs / 8);
}
async function loadWorkflows(now) {
    const label = "工作流";
    try {
        const list = await Tools.Workflow.getAll();
        const rows = await Promise.all((list.workflows ?? []).map(async (wf) => {
            const row = {
                id: wf.id,
                name: wf.name,
                enabled: Boolean(wf.enabled),
                lastExecutionTime: wf.lastExecutionTime ?? null,
                lastExecutionStatus: wf.lastExecutionStatus ?? null,
                total: wf.totalExecutions ?? 0,
                success: wf.successfulExecutions ?? 0,
                failed: wf.failedExecutions ?? 0,
                health: "OK",
                note: "",
            };
            if (!row.enabled) {
                row.health = "DISABLED";
                return row;
            }
            if (row.lastExecutionTime == null) {
                row.health = "NO_HISTORY";
                return row;
            }
            if (row.lastExecutionStatus === "FAILED") {
                row.health = "FAILED";
                return row;
            }
            if (row.lastExecutionStatus === "RUNNING") {
                row.health = "RUNNING_NOW";
                return row;
            }
            try {
                const schedule = await loadSchedule(row.id);
                if (schedule == null) {
                    row.note = "手动触发";
                    return row;
                }
                row.note = schedule.label;
                if (schedule.intervalMs != null && now - row.lastExecutionTime > staleThresholdMs(schedule.intervalMs)) {
                    row.health = "STALE";
                }
            }
            catch (error) {
                row.note = `读取调度配置失败：${errorText(error)}`;
            }
            return row;
        }));
        const order = ["FAILED", "STALE", "RUNNING_NOW", "OK", "NO_HISTORY", "DISABLED"];
        rows.sort((a, b) => order.indexOf(a.health) - order.indexOf(b.health) || a.name.localeCompare(b.name));
        return { value: rows, source: { label, status: "OK", detail: `共 ${rows.length} 个` } };
    }
    catch (error) {
        return { value: null, source: { label, status: "ERROR", detail: errorText(error) } };
    }
}
async function loadUsage() {
    const label = "App 使用时长";
    const windowHours = 24;
    try {
        const result = await Tools.System.getAppUsageTime({
            sinceHours: windowHours,
            limit: 60,
            includeSystemApps: false,
        });
        const names = {};
        for (const entry of result.entries ?? []) {
            if (entry.packageName && entry.appName)
                names[entry.packageName] = entry.appName;
        }
        const rows = (result.entries ?? [])
            .map((entry) => ({
            packageName: entry.packageName,
            appName: entry.appName || entry.packageName,
            foregroundMinutes: Math.round((entry.totalForegroundTimeMs ?? 0) / 60000),
            lastTimeUsed: entry.lastTimeUsed ?? 0,
        }))
            .filter((row) => row.foregroundMinutes > 0)
            .sort((a, b) => b.foregroundMinutes - a.foregroundMinutes)
            .slice(0, 12);
        return { value: { windowHours, rows, names }, source: { label, status: "OK", detail: `过去 ${windowHours} 小时` } };
    }
    catch (error) {
        return { value: null, source: { label, status: "ERROR", detail: errorText(error) } };
    }
}
const EVENT_HIDDEN_KEYS = new Set(["eid", "id", "ts", "iso", "date", "type", "src", "dedup"]);
function summarizeEvent(event, appNames) {
    const parts = [];
    for (const [key, raw] of Object.entries(event)) {
        if (EVENT_HIDDEN_KEYS.has(key) || raw === "" || raw == null)
            continue;
        let value = String(raw);
        if (key === "pkg" && appNames.has(value)) {
            value = `${appNames.get(value)}(${value})`;
        }
        parts.push(`${key}=${value}`);
    }
    return parts.join(" · ");
}
function eventTime(event) {
    const iso = typeof event.iso === "string" ? event.iso : "";
    if (iso.length >= 16)
        return iso.slice(11, 19) || iso;
    const ts = Number(event.ts);
    return Number.isFinite(ts) && ts > 0 ? formatClock(ts * 1000) : "时间未记录";
}
function eventHour(event) {
    const iso = typeof event.iso === "string" ? event.iso : "";
    if (iso.length >= 13) {
        const h = Number(iso.slice(11, 13));
        if (Number.isInteger(h) && h >= 0 && h < 24)
            return h;
    }
    const ts = Number(event.ts);
    return Number.isFinite(ts) && ts > 0 ? new Date(ts * 1000).getHours() : null;
}
// 只统计前台采样：ontask=1 在任务上，ontask=0 不在；pkg=none、息屏或缺字段一律算未知，不推断
function computeFocus(events, now, appNames) {
    const hours = Array.from({ length: 24 }, () => ({ on: 0, off: 0, unknown: 0 }));
    const offCounts = new Map();
    const recent = { on: 0, off: 0, offApps: [] };
    const drifts = [];
    let onTask = 0;
    let offTask = 0;
    let unknown = 0;
    const nowSec = Math.floor(now / 1000);
    for (const event of events) {
        const ts = Number(event.ts) || 0;
        if (event.type === "DRIFT") {
            drifts.push({ ts, summary: summarizeEvent(event, appNames) });
            continue;
        }
        if (event.type !== "FOREGROUND")
            continue;
        const hour = eventHour(event);
        const pkg = String(event.pkg ?? "");
        const ontask = String(event.ontask ?? "");
        const known = pkg && pkg !== "none" && event.screen !== "OFF" && (ontask === "0" || ontask === "1");
        const bucket = hour == null ? null : hours[hour];
        const isRecent = ts > 0 && nowSec - ts <= RECENT_WINDOW_SEC;
        if (!known) {
            unknown += 1;
            if (bucket)
                bucket.unknown += 1;
            continue;
        }
        if (ontask === "1") {
            onTask += 1;
            if (bucket)
                bucket.on += 1;
            if (isRecent)
                recent.on += 1;
        }
        else {
            offTask += 1;
            if (bucket)
                bucket.off += 1;
            offCounts.set(pkg, (offCounts.get(pkg) ?? 0) + 1);
            if (isRecent) {
                recent.off += 1;
                const name = appNames.get(pkg) ?? pkg;
                if (!recent.offApps.includes(name))
                    recent.offApps.push(name);
            }
        }
    }
    const offApps = [...offCounts.entries()]
        .sort((a, b) => b[1] - a[1])
        .slice(0, 5)
        .map(([pkg, count]) => ({ pkg, name: appNames.get(pkg) ?? pkg, count }));
    return { samples: onTask + offTask + unknown, onTask, offTask, unknown, hours, offApps, recent, drifts };
}
async function loadEvents(now, appNames) {
    const today = dateKey(new Date(now));
    const yesterday = dateKey(new Date(now - 24 * 3600 * 1000));
    let date = today;
    let path = `${paths_js_1.PATHS.eventsDir}/${today}/events.jsonl`;
    try {
        if (!(await fileExists(path))) {
            const fallback = `${paths_js_1.PATHS.eventsDir}/${yesterday}/events.jsonl`;
            if (!(await fileExists(fallback))) {
                return { value: null, source: { label: "事件流", status: "MISSING", detail: `今天和昨天都没有事件文件：${path}` } };
            }
            date = yesterday;
            path = fallback;
        }
    }
    catch (error) {
        return { value: null, source: { label: "事件流", status: "ERROR", detail: errorText(error) } };
    }
    return loadFile("事件流", path, async () => {
        const all = await readAll(path, EVENT_MAX_LINES);
        const parsed = [];
        let unparsable = 0;
        for (const line of all.lines) {
            if (!line.trim())
                continue;
            try {
                parsed.push(JSON.parse(line));
            }
            catch {
                unparsable += 1;
            }
        }
        const focus = computeFocus(parsed, now, appNames);
        const rows = [];
        for (const event of parsed.slice(-EVENT_TAIL_LINES)) {
            const row = {
                time: eventTime(event),
                type: String(event.type ?? "未记录"),
                src: String(event.src ?? "未记录"),
                summary: summarizeEvent(event, appNames),
                count: 1,
            };
            // 采样器会连续写入内容相同的事件，合并成一行并保留时间范围
            const prev = rows[rows.length - 1];
            if (prev && prev.type === row.type && prev.src === row.src && prev.summary === row.summary) {
                prev.count += 1;
                prev.time = `${prev.time.split("–")[0]}–${row.time}`;
            }
            else {
                rows.push(row);
            }
        }
        rows.reverse();
        return { date, totalLines: all.totalLines, rows, unparsable, focus };
    });
}
function splitColumns(line) {
    return line.split(/\t+|\s{2,}/).map((cell) => cell.trim()).filter(Boolean);
}
// 进展文件不存在只说明还没人记过，不算数据源缺失
async function loadProgress(now) {
    const label = "用户进展";
    try {
        const records = await (0, progress_js_1.readRecentProgress)(20, now);
        return { value: records, source: { label, status: "OK", detail: `${records.length} 条` } };
    }
    catch (error) {
        return { value: null, source: { label, status: "ERROR", detail: errorText(error) } };
    }
}
async function collectSnapshot() {
    const now = Date.now();
    const [task, workflows, usage, progress, health, checkins, companion] = await Promise.all([
        loadFile("当前任务", paths_js_1.PATHS.taskState, async () => parseTaskState((await readHead(paths_js_1.PATHS.taskState, 50)).lines)),
        loadWorkflows(now),
        loadUsage(),
        loadProgress(now),
        (0, health_js_1.loadHealth)(now).catch(() => []),
        (0, checkin_js_1.readRecentCheckins)(now).catch(() => []),
        (0, companion_js_1.findCompanion)().catch(() => null),
    ]);
    const appNames = new Map();
    for (const [pkg, name] of Object.entries(usage.value?.names ?? {})) {
        appNames.set(pkg, name);
    }
    const [events, actions] = await Promise.all([
        loadEvents(now, appNames),
        loadFile("动作审计", paths_js_1.PATHS.actionLog, async () => {
            const tail = await readTail(paths_js_1.PATHS.actionLog, ACTION_TAIL_LINES);
            const rows = tail.lines.filter((line) => line.trim()).map(splitColumns);
            rows.reverse();
            return { totalLines: tail.totalLines, rows };
        }),
    ]);
    return {
        generatedAt: now,
        task: task.value,
        workflows: workflows.value ?? [],
        usage: usage.value,
        events: events.value,
        actions: actions.value,
        progress: progress.value,
        health,
        checkins,
        companion,
        sources: [task.source, progress.source, workflows.source, usage.source, events.source, actions.source],
    };
}
