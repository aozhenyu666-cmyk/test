"use strict";
// 专注时段与对话门：时段内打开重度 App 先回答三个问题，答得具体给一把限时钥匙。
// 规则由用户 2026-10-05 立法（三段时段、每天 2 把、10 分钟、最长 15 分钟、冷却 5→10→20→40）。
// 判定逻辑在 gate_logic.js（纯函数，电脑上有测试）；这里只负责读写状态文件。
// 真锁接通之前，钥匙只记账（status=unbound），不改变手机限制。
Object.defineProperty(exports, "__esModule", { value: true });
exports.GATE_DIR = void 0;
exports.loadGate = loadGate;
exports.saveGate = saveGate;
exports.gateSummary = gateSummary;
exports.openGate = openGate;
exports.submitGate = submitGate;
exports.tickGate = tickGate;
exports.focusNow = focusNow;
const L = require("./gate_logic.js");
exports.GATE_DIR = "/sdcard/Download/Operit/companion/gate";
const STATE_PATH = `${exports.GATE_DIR}/state.json`;
const LINE_NUMBER_PREFIX = /^\s*\d+\| ?/;
async function readJson(path) {
    const exists = await Tools.Files.exists(path);
    if (!exists || !exists.exists)
        return null;
    const part = await Tools.Files.readPart(path, 1, 2000);
    const lines = String(part.content || "").split("\n");
    const numbered = lines.some((line) => LINE_NUMBER_PREFIX.test(line));
    const json = (numbered ? lines.filter((line) => LINE_NUMBER_PREFIX.test(line)).map((line) => line.replace(LINE_NUMBER_PREFIX, "")) : lines).join("\n");
    return JSON.parse(json);
}
// 新版本加了配置项时，用默认值补齐旧文件，不覆盖用户改过的值
function withDefaults(state) {
    const d = L.defaultConfig();
    state.config = { ...d, ...state.config, gate: { ...d.gate, ...(state.config && state.config.gate) } };
    return state;
}
async function loadGate() {
    let state = null;
    try {
        state = await readJson(STATE_PATH);
    }
    catch {
        state = null;
    }
    return withDefaults(state ? L.validate(state) : L.freshState());
}
async function saveGate(state) {
    await Tools.Files.write(STATE_PATH, `${JSON.stringify(state)}\n`, false);
}
function focusNow(state, now) {
    return L.focusWindow(now, state.config);
}
function gateSummary(state, now) {
    return L.summary(state, now);
}
async function openGate(pkg, now = Date.now()) {
    const state = await loadGate();
    const result = L.gateOpen(state, now, pkg);
    await saveGate(state);
    return result;
}
// 一次提交三个回答；全部有效就按默认时长放行
async function submitGate(gateId, answers, now = Date.now()) {
    const state = await loadGate();
    const result = L.gateSubmit(state, now, gateId, answers);
    if (result.status === "granted")
        result.key = L.setKeyStatus(state, result.key.id, "unbound");
    await saveGate(state);
    return result;
}
// 定时调用：超时没答完的门记一次失误并延长冷却；到期的钥匙收回，返回需要提醒的内容
async function tickGate(now = Date.now()) {
    const state = await loadGate();
    const expired = L.expireGates(state, now);
    const closed = [];
    for (const key of L.dueKeys(state, now)) {
        L.setKeyStatus(state, key.id, "closed");
        closed.push({ app: key.app_name, back_to: key.back_to });
    }
    if (expired.length || closed.length)
        await saveGate(state);
    return { expired, closed };
}
