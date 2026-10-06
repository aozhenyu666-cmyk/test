"use strict";
/* METADATA
{
  "name": "focus_hub_dice",
  "display_name": {
    "zh": "六骰子思维训练",
    "en": "Six-Dice Thinking"
  },
  "description": {
    "zh": "六骰子思维训练：定时掷骰推送训练卡（步骤、卡住降级、元认知提问），记录完成情况。工作流调用 tick；司南等角色可在用户卡住时调用 roll，把一面骰子用在手头的事上。",
    "en": "Six-dice thinking practice: timed training cards with fallback steps and metacognitive prompts, plus records."
  },
  "enabled_by_default": true,
  "category": "Productivity",
  "tools": [
    {
      "name": "tick",
      "description": {
        "zh": "节拍入口（给工作流用）：按设置的间隔、时段与是否让路给司南，决定是否掷骰并推送。没到时间会直接跳过。",
        "en": "Workflow tick: roll and push a card when due."
      },
      "parameters": []
    },
    {
      "name": "roll",
      "description": {
        "zh": "立刻掷一次并推送。用户卡住或想练的时候用。fusion=true 时素材取自当前手头的事，并先问元认知两问。",
        "en": "Roll now and push a card."
      },
      "parameters": [
        { "name": "mode", "description": { "zh": "可选：指定 1-6 的骰面", "en": "Optional face 1-6" }, "type": "number", "required": false },
        { "name": "fusion", "description": { "zh": "可选：是否用手头的事做素材", "en": "Use the current task as material" }, "type": "boolean", "required": false }
      ]
    },
    {
      "name": "record",
      "description": {
        "zh": "记录用户对当前这张卡的完成情况：done（完成）/ down（降级完成）/ skip（跳过），附用户原话。只记录用户真实说过的。",
        "en": "Record the user's result for the current card."
      },
      "parameters": [
        { "name": "result", "description": { "zh": "done / down / skip", "en": "done / down / skip" }, "type": "string", "required": true },
        { "name": "note", "description": { "zh": "用户原话或简短说明", "en": "User's words" }, "type": "string", "required": false }
      ]
    },
    {
      "name": "status",
      "description": {
        "zh": "读取今天的训练统计、当前这张卡和设置。",
        "en": "Today's stats, current card and settings."
      },
      "parameters": []
    }
  ]
}
*/
Object.defineProperty(exports, "__esModule", { value: true });
exports.tick = tick;
exports.roll = roll;
exports.record = record;
exports.status = status;
const dice = require("../shared/dice.js");
function fail(e) { return { success: false, message: String((e && e.message) || e) }; }
async function tick() {
    try { return { success: true, ...(await dice.tick(Date.now())) }; } catch (e) { return fail(e); }
}
async function roll(params) {
    try {
        const p = params || {};
        return { success: true, ...(await dice.tick(Date.now(), { force: true, forceMode: Number(p.mode) || 0, fusion: p.fusion === true || p.fusion === "true" })) };
    } catch (e) { return fail(e); }
}
async function record(params) {
    try { const p = params || {}; return { success: true, ...(await dice.record(Date.now(), String(p.result || ""), p.note)) }; } catch (e) { return fail(e); }
}
async function status() {
    try { return { success: true, summary: await dice.summary(Date.now()), config: await dice.loadConfig() }; } catch (e) { return fail(e); }
}
