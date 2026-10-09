"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DEFAULT_FILES = exports.DEFAULT_WORKFLOWS = exports.ROOT = void 0;
exports.loadConfig = loadConfig;
const fsx_js_1 = require("./fsx.js");
exports.ROOT = "/sdcard/Download/Operit";
const CONFIG_PATH = `${exports.ROOT}/audit/config.json`;
// 默认规格。用户或流程线可用 audit/config.json 覆盖（缺字段回落默认）。
exports.DEFAULT_WORKFLOWS = [
    { name: "P4_Event_Sampler", cadenceMin: 15, critical: true, expectEnabled: true },
    { name: "SCHED_Watchdog", cadenceMin: 15, critical: true, expectEnabled: true },
    { name: "G2_Judge_Flow", cadenceMin: 30, critical: true, expectEnabled: true },
    { name: "S4_Lock_Queue_Worker", cadenceMin: 15, critical: true, expectEnabled: true },
    { name: "BRAIN_Tick", cadenceMin: 15, critical: true, expectEnabled: true },
    { name: "OUTBOX_Carrier", cadenceMin: 15, critical: false, expectEnabled: true },
    { name: "S3_Brief_Loop", cadenceMin: 30, critical: false, expectEnabled: false }, // 已被 BRAIN_Tick 取代
];
exports.DEFAULT_FILES = [
    { path: `${exports.ROOT}/drift/task_state.txt`, maxAgeMin: 60, critical: true, label: "当前任务投影" },
    { path: `${exports.ROOT}/companion/brain/state.json`, maxAgeMin: 60, critical: true, label: "大脑状态" },
    { path: `${exports.ROOT}/judge/.pack_last.txt`, maxAgeMin: 90, critical: false, label: "判断证据包" },
];
async function loadConfig() {
    const saved = await (0, fsx_js_1.readJson)(CONFIG_PATH);
    return {
        workflows: saved?.workflows ?? exports.DEFAULT_WORKFLOWS,
        files: saved?.files ?? exports.DEFAULT_FILES,
        judgeCadenceMin: saved?.judgeCadenceMin ?? 30,
    };
}
