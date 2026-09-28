"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.EMPTY_STATE = exports.DEFAULT_CONFIG = exports.PATHS = exports.BRAIN_DIR = exports.ROOT = void 0;
exports.loadConfig = loadConfig;
exports.loadProfile = loadProfile;
exports.appendObservation = appendObservation;
exports.loadCommitments = loadCommitments;
exports.addCommitment = addCommitment;
exports.setCommitmentStatus = setCommitmentStatus;
exports.recentSaid = recentSaid;
exports.appendSaid = appendSaid;
exports.logAction = logAction;
exports.loadState = loadState;
exports.saveState = saveState;
const fsx_js_1 = require("./fsx.js");
const time_js_1 = require("./time.js");
exports.ROOT = "/sdcard/Download/Operit";
exports.BRAIN_DIR = `${exports.ROOT}/companion/brain`;
exports.PATHS = {
    config: `${exports.BRAIN_DIR}/config.json`,
    profile: `${exports.BRAIN_DIR}/profile.md`,
    commitments: `${exports.BRAIN_DIR}/commitments.jsonl`,
    said: `${exports.BRAIN_DIR}/said.jsonl`,
    state: `${exports.BRAIN_DIR}/state.json`,
    actions: `${exports.BRAIN_DIR}/actions.jsonl`,
    reflections: `${exports.BRAIN_DIR}/reflections.jsonl`,
};
exports.DEFAULT_CONFIG = {
    companion_chat_id: "52a18815-2c07-4f6d-bbae-ae5d040daffb",
    companion_card_name: "小满·陪伴",
    max_speaks_per_hour: 4,
    min_gap_minutes: 12,
    lock_grace_minutes: 30,
    relock_cooldown_minutes: 120,
    quiet_start: "23:30",
    quiet_end: "08:00",
    act: true,
};
async function loadConfig() {
    const saved = await (0, fsx_js_1.readJson)(exports.PATHS.config);
    return { ...exports.DEFAULT_CONFIG, ...(saved ?? {}) };
}
// ---------- 关于 TA ----------
const DEFAULT_PROFILE = `# 关于 TA

> 这份档案会在每次和小满说话时自动带给她。TA 可以直接改这个文件。
> "TA 自己写的"一节永远不会被自动改动；"观察"一节由每晚的复盘追加。

## TA 自己写的
（空。想让小满记住什么，就写在这里。）

## 目标
- 当前任务：求职投递（Boss 直聘等招聘 App 投简历）。

## 已知的习惯和偏好（外部 Agent 初始整理，TA 可改）
- 常用语音输入，话长、有口语，要抓意思不抠字面。
- 提醒如果没有后果、说得像机器，TA 会习惯性关掉、忽略。
- 不喜欢同一句话反复催；已经做完的事再被催会很烦。
- ChatGPT、Claude、DeepSeek 等 AI 应用是 TA 干活的工具，不算分心。
- 希望小满像一个真人：有记忆、不接受敷衍、但不死板。

## 管用的说法

## 不管用的说法

## 观察（自动追加）
`;
async function loadProfile() {
    if (!(await (0, fsx_js_1.exists)(exports.PATHS.profile))) {
        await (0, fsx_js_1.writeText)(exports.PATHS.profile, DEFAULT_PROFILE);
        return DEFAULT_PROFILE;
    }
    return (0, fsx_js_1.readText)(exports.PATHS.profile);
}
async function appendObservation(lines, now) {
    const clean = lines.map((l) => l.replace(/\s+/g, " ").trim()).filter(Boolean).slice(0, 6);
    if (clean.length === 0)
        return;
    const profile = await loadProfile();
    const block = `\n### ${(0, time_js_1.bjDateKey)(now)}\n${clean.map((l) => `- ${l}`).join("\n")}\n`;
    await (0, fsx_js_1.writeText)(exports.PATHS.profile, profile.replace(/\s*$/, "\n") + block);
}
async function loadCommitments() {
    const events = (0, fsx_js_1.parseJsonl)(await (0, fsx_js_1.tailLines)(exports.PATHS.commitments, 400));
    const map = new Map();
    for (const e of events) {
        if (e.type === "add" && e.commitment)
            map.set(e.id, { ...e.commitment });
        else if (e.type === "status" && e.status && map.has(e.id)) {
            const c = map.get(e.id);
            c.status = e.status;
            c.closed_ts = e.ts;
            if (e.note)
                c.note = e.note;
        }
    }
    return [...map.values()].sort((a, b) => a.due_ts - b.due_ts);
}
async function addCommitment(input, now) {
    const c = { id: `C-${now}-${Math.floor(Math.random() * 1e5)}`, ts: now, status: "open", ...input };
    await (0, fsx_js_1.appendJsonl)(exports.PATHS.commitments, { type: "add", id: c.id, ts: now, commitment: c });
    return c;
}
async function setCommitmentStatus(id, status, now, note = "") {
    await (0, fsx_js_1.appendJsonl)(exports.PATHS.commitments, { type: "status", id, ts: now, status, ...(note ? { note } : {}) });
}
async function recentSaid(n) {
    return (0, fsx_js_1.parseJsonl)(await (0, fsx_js_1.tailLines)(exports.PATHS.said, n));
}
async function appendSaid(record) {
    await (0, fsx_js_1.appendJsonl)(exports.PATHS.said, { ...record, iso: (0, time_js_1.bjIso)(record.ts) });
}
async function logAction(record) {
    await (0, fsx_js_1.appendJsonl)(exports.PATHS.actions, record);
}
exports.EMPTY_STATE = {
    episode: null,
    speaks: [],
    last_sig: "",
    last_think_ts: 0,
    last_shot_ts: 0,
    last_reflect_date: "",
    locks: {},
    last_idle_checkin_ts: 0,
    asked_commitments: {},
};
async function loadState() {
    const saved = await (0, fsx_js_1.readJson)(exports.PATHS.state);
    return { ...exports.EMPTY_STATE, ...(saved ?? {}), locks: { ...(saved?.locks ?? {}) }, asked_commitments: { ...(saved?.asked_commitments ?? {}) } };
}
async function saveState(state) {
    await (0, fsx_js_1.writeText)(exports.PATHS.state, JSON.stringify(state));
}
