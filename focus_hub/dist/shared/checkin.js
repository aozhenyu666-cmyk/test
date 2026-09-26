"use strict";
// "她来找你"：一次打卡 = 念一句话 + 弹出主控台 + 留一条待回应。回应就是一条用户进展。
Object.defineProperty(exports, "__esModule", { value: true });
exports.QUIET_END_MIN = exports.QUIET_START_MIN = void 0;
exports.isoLocal = isoLocal;
exports.checkinPath = checkinPath;
exports.readRecentCheckins = readRecentCheckins;
exports.appendCheckin = appendCheckin;
exports.decideCheckin = decideCheckin;
exports.newCheckinId = newCheckinId;
const CHECKIN_DIR = "/sdcard/Download/Operit/companion/checkins";
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
function checkinPath(date) {
    return `${CHECKIN_DIR}/${date}.jsonl`;
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
async function readRecentCheckins(now, limit = 10) {
    const out = [];
    for (const date of [dateKey(new Date(now - 24 * 3600 * 1000)), dateKey(new Date(now))]) {
        for (const line of await readLines(checkinPath(date), limit)) {
            try {
                const record = JSON.parse(line);
                if (record?.type === "CHECKIN")
                    out.push(record);
            }
            catch {
                // 跳过损坏的行
            }
        }
    }
    return out.slice(-limit).reverse();
}
async function appendCheckin(record) {
    const date = dateKey(new Date(record.ts * 1000));
    await Tools.Files.write(checkinPath(date), `${JSON.stringify(record)}\n`, true);
}
exports.QUIET_START_MIN = 23 * 60 + 30;
exports.QUIET_END_MIN = 8 * 60;
const COOLDOWN_SEC = 45 * 60;
const SAME_LEVEL_SEC = 2 * 3600;
const CALM_GAP_SEC = 3 * 3600;
// 打扰要有节制：深夜不打扰；刚找过不重复找；心情没变就不重复说；安心时很久没动静才问一句
function decideCheckin(now, level, lastCheckin, lastProgressTs) {
    const d = new Date(now);
    const minutes = d.getHours() * 60 + d.getMinutes();
    if (minutes >= exports.QUIET_START_MIN || minutes < exports.QUIET_END_MIN)
        return "SKIP_QUIET";
    const nowSec = Math.floor(now / 1000);
    const sinceLast = lastCheckin ? nowSec - lastCheckin.ts : Infinity;
    if (sinceLast < COOLDOWN_SEC)
        return "SKIP_COOLDOWN";
    if (level === 0) {
        const recentProgress = lastProgressTs != null && nowSec - lastProgressTs < 2 * 3600;
        return recentProgress || sinceLast < CALM_GAP_SEC ? "SKIP_FINE" : "GO";
    }
    if (lastCheckin && lastCheckin.level === level && level < 3 && sinceLast < SAME_LEVEL_SEC)
        return "SKIP_SAME";
    return "GO";
}
function newCheckinId(ts) {
    return `CHK-${ts}-${Math.floor(Math.random() * 1e5)}`;
}
