"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.KIND_LABEL = exports.PROGRESS_KINDS = void 0;
exports.progressPath = progressPath;
exports.readRecentProgress = readRecentProgress;
exports.recordProgress = recordProgress;
const paths_js_1 = require("./paths.js");
// 用户只认四个可见动作：继续、卡住、提交、暂停；note 用于其他说明
exports.PROGRESS_KINDS = ["progress", "stuck", "done", "pause", "note"];
exports.KIND_LABEL = {
    progress: "继续",
    stuck: "卡住",
    done: "提交",
    pause: "暂停",
    note: "说明",
};
const DEDUP_WINDOW_SEC = 10 * 60;
const MAX_QUOTE_CHARS = 500;
const LINE_NUMBER_PREFIX = /^\s*\d+\| ?/;
function pad2(value) {
    return value < 10 ? `0${value}` : String(value);
}
function dateKey(d) {
    return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}`;
}
function isoLocal(d) {
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}
function progressPath(date) {
    return `${paths_js_1.PATHS.progressDir}/${date}.jsonl`;
}
async function readLines(path, tail) {
    const exists = await Tools.Files.exists(path);
    if (!exists?.exists)
        return [];
    const probe = await Tools.Files.readPart(path, 1, 1);
    if (probe.totalLines <= 0)
        return [];
    const start = Math.max(1, probe.totalLines - tail + 1);
    const part = await Tools.Files.readPart(path, start, probe.totalLines);
    return part.content
        .split("\n")
        .filter((line) => LINE_NUMBER_PREFIX.test(line))
        .map((line) => line.replace(LINE_NUMBER_PREFIX, ""))
        .filter((line) => line.trim());
}
function parseRecords(lines) {
    const out = [];
    for (const line of lines) {
        try {
            const record = JSON.parse(line);
            if (record && record.type === "USER_PROGRESS")
                out.push(record);
        }
        catch {
            // 损坏的行不影响其余记录
        }
    }
    return out;
}
async function readRecentProgress(limit, now = Date.now()) {
    const today = dateKey(new Date(now));
    const yesterday = dateKey(new Date(now - 24 * 3600 * 1000));
    const records = [
        ...parseRecords(await readLines(progressPath(yesterday), limit)),
        ...parseRecords(await readLines(progressPath(today), limit)),
    ];
    return records.slice(-limit).reverse();
}
async function currentTask() {
    try {
        const lines = await readLines(paths_js_1.PATHS.taskState, 50);
        const map = {};
        for (const line of lines) {
            const index = line.indexOf("=");
            if (index > 0)
                map[line.slice(0, index).trim()] = line.slice(index + 1).trim();
        }
        return { task: map.TASK ?? "", ptr: map.PTR ?? "" };
    }
    catch {
        return { task: "", ptr: "" };
    }
}
async function recordProgress(input, now = Date.now()) {
    const kind = String(input.kind ?? "").trim();
    if (!exports.PROGRESS_KINDS.includes(kind)) {
        return { status: "REJECTED", reason: `kind 必须是 ${exports.PROGRESS_KINDS.join(" / ")} 之一` };
    }
    const quote = String(input.userQuote ?? "").trim().slice(0, MAX_QUOTE_CHARS);
    if (!quote) {
        return { status: "REJECTED", reason: "user_quote 不能为空：必须是用户自己说的原话" };
    }
    const d = new Date(now);
    const ts = Math.floor(now / 1000);
    const date = dateKey(d);
    // 同一句话在 10 分钟内重复上报只记一次（AI 可能重试或重复调用）
    const recent = parseRecords(await readLines(progressPath(date), 20));
    const dup = recent
        .reverse()
        .find((r) => r.kind === kind && r.user_quote === quote && ts - r.ts < DEDUP_WINDOW_SEC);
    if (dup)
        return { status: "DUPLICATE", record: dup };
    const { task, ptr } = await currentTask();
    const record = {
        id: `PROG-${ts}-${Math.floor(Math.random() * 1e5)}`,
        ts,
        iso: isoLocal(d),
        date,
        type: "USER_PROGRESS",
        origin: "REAL_USER",
        via: input.via,
        kind,
        user_quote: quote,
        note: String(input.note ?? "").trim().slice(0, MAX_QUOTE_CHARS),
        task,
        ptr,
        chat_id: String(input.chatId ?? ""),
    };
    const path = progressPath(date);
    const result = await Tools.Files.write(path, `${JSON.stringify(record)}\n`, true);
    if (result && result.successful === false) {
        return { status: "REJECTED", reason: `写入失败：${result.details}` };
    }
    return { status: "RECORDED", record, path };
}
