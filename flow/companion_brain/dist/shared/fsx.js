"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.exists = exists;
exports.readLines = readLines;
exports.tailLines = tailLines;
exports.readText = readText;
exports.readJson = readJson;
exports.parseJsonl = parseJsonl;
exports.writeText = writeText;
exports.appendLine = appendLine;
exports.appendJsonl = appendJsonl;
exports.listNames = listNames;
exports.errorText = errorText;
// Tools.Files.readPart 返回的每行都带 "  12| " 行号前缀，这里统一剥掉。
const LINE_NUMBER_PREFIX = /^\s*\d+\| ?/;
const CHUNK = 300;
function strip(content) {
    return content
        .split("\n")
        .filter((line) => LINE_NUMBER_PREFIX.test(line))
        .map((line) => line.replace(LINE_NUMBER_PREFIX, ""));
}
async function exists(path) {
    try {
        const r = await Tools.Files.exists(path);
        return !!r?.exists;
    }
    catch {
        return false;
    }
}
async function totalLines(path) {
    const probe = await Tools.Files.readPart(path, 1, 1);
    return probe.totalLines || 0;
}
async function readLines(path) {
    if (!(await exists(path)))
        return [];
    try {
        const total = await totalLines(path);
        const out = [];
        for (let s = 1; s <= total; s += CHUNK) {
            const part = await Tools.Files.readPart(path, s, Math.min(total, s + CHUNK - 1));
            out.push(...strip(part.content));
        }
        return out;
    }
    catch {
        return [];
    }
}
async function tailLines(path, n) {
    if (!(await exists(path)))
        return [];
    try {
        const total = await totalLines(path);
        if (total <= 0)
            return [];
        const start = Math.max(1, total - n + 1);
        const out = [];
        for (let s = start; s <= total; s += CHUNK) {
            const part = await Tools.Files.readPart(path, s, Math.min(total, s + CHUNK - 1));
            out.push(...strip(part.content));
        }
        return out.filter((l) => l.trim());
    }
    catch {
        return [];
    }
}
async function readText(path) {
    return (await readLines(path)).join("\n");
}
async function readJson(path) {
    try {
        const text = await readText(path);
        return text.trim() ? JSON.parse(text) : null;
    }
    catch {
        return null;
    }
}
function parseJsonl(lines) {
    const out = [];
    for (const line of lines) {
        const t = line.trim();
        if (!t.startsWith("{"))
            continue;
        try {
            out.push(JSON.parse(t));
        }
        catch {
            // 跳过损坏的行
        }
    }
    return out;
}
async function writeText(path, content) {
    await Tools.Files.write(path, content, false);
}
async function appendLine(path, line) {
    await Tools.Files.write(path, line.endsWith("\n") ? line : `${line}\n`, true);
}
async function appendJsonl(path, record) {
    await appendLine(path, JSON.stringify(record));
}
async function listNames(dir) {
    try {
        const r = await Tools.Files.list(dir);
        return (r?.entries ?? []).filter((e) => !e.isDirectory).map((e) => e.name).sort();
    }
    catch {
        return [];
    }
}
function errorText(error) {
    if (error && typeof error === "object" && "message" in error) {
        return String(error.message);
    }
    return String(error);
}
