"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PATHS = exports.FIXED_CHAT_TITLE = void 0;
exports.formatClock = formatClock;
exports.formatDateTime = formatDateTime;
exports.formatAgo = formatAgo;
exports.collectSnapshot = collectSnapshot;
exports.FIXED_CHAT_TITLE = "主控台";
const ROOT = "/sdcard/Download/Operit";
exports.PATHS = {
    taskState: `${ROOT}/drift/task_state.txt`,
    eventsDir: `${ROOT}/events`,
    execRules: `${ROOT}/events/EXEC_RULES.tsv`,
    activeRules: `${ROOT}/events/ACTIVE_RULES.md`,
    actionLog: `${ROOT}/events/ACTION_LOG.tsv`,
};
// WorkManager 不接受小于 15 分钟的周期，宿主会把间隔抬到 15 分钟
const MIN_SCHEDULE_INTERVAL_MS = 15 * 60 * 1000;
const STALE_GRACE_MS = 5 * 60 * 1000;
const EVENT_TAIL_LINES = 40;
const ACTION_TAIL_LINES = 12;
const RULE_TAGS = ["ADVICE", "PROMPT", "PREAPPROVED_GUARD"];
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
async function loadScheduleIntervalMs(workflowId) {
    const detail = await Tools.Workflow.get(workflowId);
    for (const node of detail.nodes ?? []) {
        const trigger = node;
        if (trigger.type !== "trigger" || trigger.triggerType !== "schedule")
            continue;
        const config = trigger.triggerConfig ?? {};
        if (config.enabled === "false")
            continue;
        if (config.schedule_type !== "interval")
            return null;
        const interval = Number(config.interval_ms);
        return Number.isFinite(interval) && interval > 0 ? interval : null;
    }
    return null;
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
                const interval = await loadScheduleIntervalMs(row.id);
                if (interval == null) {
                    row.note = "非间隔定时，不判断迟到";
                    return row;
                }
                const effective = Math.max(interval, MIN_SCHEDULE_INTERVAL_MS);
                row.note = `每 ${Math.round(effective / 60000)} 分钟`;
                if (now - row.lastExecutionTime > effective * 2 + STALE_GRACE_MS) {
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
            limit: 30,
            includeSystemApps: false,
        });
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
        return { value: { windowHours, rows }, source: { label, status: "OK", detail: `过去 ${windowHours} 小时` } };
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
async function loadEvents(now, appNames) {
    const today = dateKey(new Date(now));
    const yesterday = dateKey(new Date(now - 24 * 3600 * 1000));
    let date = today;
    let path = `${exports.PATHS.eventsDir}/${today}/events.jsonl`;
    try {
        if (!(await fileExists(path))) {
            const fallback = `${exports.PATHS.eventsDir}/${yesterday}/events.jsonl`;
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
        const tail = await readTail(path, EVENT_TAIL_LINES);
        const rows = [];
        let unparsable = 0;
        for (const line of tail.lines) {
            if (!line.trim())
                continue;
            let event;
            try {
                event = JSON.parse(line);
            }
            catch {
                unparsable += 1;
                continue;
            }
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
        return { date, totalLines: tail.totalLines, rows, unparsable };
    });
}
function splitColumns(line) {
    return line.split(/\t+|\s{2,}/).map((cell) => cell.trim()).filter(Boolean);
}
async function collectSnapshot() {
    const now = Date.now();
    const [task, workflows, usage] = await Promise.all([
        loadFile("当前任务", exports.PATHS.taskState, async () => parseTaskState((await readHead(exports.PATHS.taskState, 50)).lines)),
        loadWorkflows(now),
        loadUsage(),
    ]);
    const appNames = new Map();
    for (const row of usage.value?.rows ?? []) {
        appNames.set(row.packageName, row.appName);
    }
    const [events, actions, execRules, activeRules] = await Promise.all([
        loadEvents(now, appNames),
        loadFile("动作审计", exports.PATHS.actionLog, async () => {
            const tail = await readTail(exports.PATHS.actionLog, ACTION_TAIL_LINES);
            const rows = tail.lines.filter((line) => line.trim()).map(splitColumns);
            rows.reverse();
            return { totalLines: tail.totalLines, rows };
        }),
        loadFile("行动规则表", exports.PATHS.execRules, async () => (await readHead(exports.PATHS.execRules, 60)).lines
            .filter((line) => line.trim() && !line.trim().startsWith("#"))
            .map(splitColumns)),
        loadFile("生效规则", exports.PATHS.activeRules, async () => {
            const head = await readHead(exports.PATHS.activeRules, 400);
            const tagCounts = {};
            const lines = [];
            for (const tag of RULE_TAGS)
                tagCounts[tag] = 0;
            for (const line of head.lines) {
                const hits = RULE_TAGS.filter((tag) => line.includes(tag));
                if (hits.length === 0)
                    continue;
                for (const tag of hits)
                    tagCounts[tag] += 1;
                if (lines.length < 15)
                    lines.push(line.trim().replace(/^[-*]\s+/, ""));
            }
            return { tagCounts, lines };
        }),
    ]);
    return {
        generatedAt: now,
        task: task.value,
        workflows: workflows.value ?? [],
        usage: usage.value,
        events: events.value,
        actions: actions.value,
        execRules: execRules.value,
        activeRules: activeRules.value,
        sources: [task.source, workflows.source, usage.source, events.source, actions.source, execRules.source, activeRules.source],
    };
}
