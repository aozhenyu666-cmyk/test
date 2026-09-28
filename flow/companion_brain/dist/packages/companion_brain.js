"use strict";
/* METADATA
{
  "name": "companion_brain",
  "display_name": {
    "zh": "小满的大脑",
    "en": "Companion Brain"
  },
  "description": {
    "zh": "陪伴角色的思考与行动：每 15 分钟看一次事实和记忆，按用户批准的契约决定是否开口、打开招聘 App、派活、记承诺，或把分心 App 放进现有锁队列。记忆放在 /sdcard/Download/Operit/companion/brain/。",
    "en": "Thinking and acting for the companion: decides whether to speak, open a job app, assign a task, record a commitment or queue a lock, within the user's contract."
  },
  "enabled_by_default": true,
  "category": "System",
  "tools": [
    {
      "name": "tick",
      "description": {
        "zh": "工作流每 15 分钟调用一次：收集事实 → 规则层算出允许的动作 → 思考 → 执行。安静时段、冷却、事实没变时自动跳过。",
        "en": "Run one think-and-act cycle. Called by the workflow every 15 minutes."
      },
      "parameters": [
        { "name": "force", "description": { "zh": "可选：true 时即使情况没变也重新思考一次", "en": "Optional: rethink even if nothing changed" }, "type": "boolean", "required": false }
      ]
    },
    {
      "name": "record_commitment",
      "description": {
        "zh": "记下用户亲口说出的、带时间的打算（例如「一点前投一家」「半小时后开始」）。到点后小满会来问。只在用户本人说出口时调用。",
        "en": "Record a time-bound commitment the user stated themselves."
      },
      "parameters": [
        { "name": "text", "description": { "zh": "要做的事，简短", "en": "What will be done" }, "type": "string", "required": true },
        { "name": "due_time", "description": { "zh": "北京时间 HH:MM，例如 13:00；和 due_minutes 二选一", "en": "Beijing time HH:MM" }, "type": "string", "required": false },
        { "name": "due_minutes", "description": { "zh": "多少分钟后到点；和 due_time 二选一", "en": "Minutes from now" }, "type": "number", "required": false },
        { "name": "quote", "description": { "zh": "用户原话，逐字", "en": "User's words verbatim" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "get_status",
      "description": {
        "zh": "只读：当前阶段、允许的动作、最近说过的话、未兑现的承诺。",
        "en": "Read-only brain status."
      },
      "parameters": []
    },
    {
      "name": "drill",
      "description": {
        "zh": "演练：用模拟场景跑一遍读取→规则→思考→行动，看小满会怎么判断、怎么说。不写记忆、不改大脑状态。execute=true 时真的开口/打开App/把App放进锁队列。",
        "en": "Drill a simulated scenario through rules, thinking and (optionally) actions."
      },
      "parameters": [
        { "name": "scenario", "description": { "zh": "nudge 刚跑偏 / ask 提醒后没回来 / lock 提醒30分钟没回应 / pause 用户说了暂停 / commitment 承诺到点 / quiet 深夜 / working 正在招聘App里", "en": "scenario" }, "type": "string", "required": true },
        { "name": "execute", "description": { "zh": "可选：true 时真的执行动作（开口、打开App、入锁队列）", "en": "Optional: really act" }, "type": "boolean", "required": false },
        { "name": "app", "description": { "zh": "可选：模拟正在刷的 App 包名，默认 com.baidu.tieba（贴吧）", "en": "Optional package" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "reflect",
      "description": {
        "zh": "立即做一次今日复盘，把观察写进 profile.md（平时每晚 23 点后自动做）。",
        "en": "Run today's reflection now."
      },
      "parameters": []
    }
  ]
}
*/
Object.defineProperty(exports, "__esModule", { value: true });
exports.tick = tick;
exports.record_commitment = record_commitment;
exports.get_status = get_status;
exports.reflect = reflect;
exports.drill = drill;
const brain_js_1 = require("../shared/brain.js");
const fsx_js_1 = require("../shared/fsx.js");
function flag(value) {
    return value === true || String(value).toLowerCase() === "true";
}
async function tick(params) {
    try {
        const r = await (0, brain_js_1.tick)({ force: flag(params?.force) });
        return { success: true, ...r };
    }
    catch (error) {
        return { success: false, status: "ERROR", message: (0, fsx_js_1.errorText)(error) };
    }
}
async function record_commitment(params) {
    try {
        return { success: true, message: await (0, brain_js_1.recordCommitment)(params ?? {}) };
    }
    catch (error) {
        return { success: false, message: (0, fsx_js_1.errorText)(error) };
    }
}
async function get_status() {
    try {
        return { success: true, status: await (0, brain_js_1.status)() };
    }
    catch (error) {
        return { success: false, message: (0, fsx_js_1.errorText)(error) };
    }
}
async function reflect() {
    try {
        return { success: true, notes: await (0, brain_js_1.reflect)(Date.now()) };
    }
    catch (error) {
        return { success: false, message: (0, fsx_js_1.errorText)(error) };
    }
}
const SCENARIOS = ["nudge", "ask", "lock", "pause", "commitment", "quiet", "working"];
async function drill(params) {
    try {
        const scenario = String(params?.scenario ?? "").trim();
        if (!SCENARIOS.includes(scenario))
            return { success: false, message: `scenario 只能是 ${SCENARIOS.join(" / ")}` };
        return { success: true, result: await (0, brain_js_1.drill)(scenario, { execute: flag(params?.execute), app: params?.app ? String(params.app) : undefined }) };
    }
    catch (error) {
        return { success: false, message: (0, fsx_js_1.errorText)(error) };
    }
}
