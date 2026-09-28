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

import { recordCommitment, reflect as runReflect, status, tick as runTick } from "../shared/brain.js";
import { errorText } from "../shared/fsx.js";

function flag(value: unknown): boolean {
  return value === true || String(value).toLowerCase() === "true";
}

export async function tick(params: { force?: unknown }) {
  try {
    const r = await runTick({ force: flag(params?.force) });
    return { success: true, ...r };
  } catch (error) {
    return { success: false, status: "ERROR", message: errorText(error) };
  }
}

export async function record_commitment(params: { text?: string; due_time?: string; due_minutes?: number | string; quote?: string }) {
  try {
    return { success: true, message: await recordCommitment(params ?? {}) };
  } catch (error) {
    return { success: false, message: errorText(error) };
  }
}

export async function get_status() {
  try {
    return { success: true, status: await status() };
  } catch (error) {
    return { success: false, message: errorText(error) };
  }
}

export async function reflect() {
  try {
    return { success: true, notes: await runReflect(Date.now()) };
  } catch (error) {
    return { success: false, message: errorText(error) };
  }
}
