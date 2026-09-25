"use strict";
/* METADATA
{
  "name": "focus_hub_progress",
  "display_name": {
    "zh": "用户进展",
    "en": "User Progress"
  },
  "description": {
    "zh": "用户进展的统一入口。用户在对话里说自己做了什么、卡住了、完成提交了或要暂停时，调用 report_progress 记下来；判断、提醒之前先用 get_recent_progress 看用户最近说过什么，避免重复催已经做完的事。",
    "en": "Single entry for user progress. Record what the user says they did, and check recent progress before judging or reminding."
  },
  "enabled_by_default": true,
  "category": "System",
  "tools": [
    {
      "name": "report_progress",
      "description": {
        "zh": "记录一条用户亲口说的进展。只在用户本人明确表达时调用，不要替用户推测或补写。kind：progress=继续/在做、stuck=卡住、done=完成或提交、pause=暂停、note=其他说明。同一句话 10 分钟内重复调用只记一次。",
        "en": "Record progress the user stated themselves. kind: progress | stuck | done | pause | note."
      },
      "parameters": [
        {
          "name": "kind",
          "description": { "zh": "progress / stuck / done / pause / note", "en": "progress / stuck / done / pause / note" },
          "type": "string",
          "required": true
        },
        {
          "name": "user_quote",
          "description": { "zh": "用户的原话，逐字摘录，不要改写", "en": "The user's own words, verbatim" },
          "type": "string",
          "required": true
        },
        {
          "name": "note",
          "description": { "zh": "可选：你对这条进展的一句补充，例如对应哪件事", "en": "Optional one-line context" },
          "type": "string",
          "required": false
        }
      ]
    },
    {
      "name": "get_recent_progress",
      "description": {
        "zh": "读取用户最近的进展记录（今天和昨天，新的在前）。在判断用户是否偏离任务、或准备提醒之前先调用。",
        "en": "Read the user's recent progress records (today and yesterday, newest first)."
      },
      "parameters": [
        {
          "name": "limit",
          "description": { "zh": "最多返回几条，默认 10", "en": "Max records, default 10" },
          "type": "number",
          "required": false
        }
      ]
    }
  ]
}
*/
Object.defineProperty(exports, "__esModule", { value: true });
exports.report_progress = report_progress;
exports.get_recent_progress = get_recent_progress;
const progress_js_1 = require("../shared/progress.js");
function errorText(error) {
    if (error && typeof error === "object" && "message" in error) {
        return String(error.message);
    }
    return String(error);
}
function currentChatId() {
    try {
        return String(getChatId() ?? "");
    }
    catch {
        return "";
    }
}
async function report_progress(params) {
    try {
        const result = await (0, progress_js_1.recordProgress)({
            kind: String(params?.kind ?? ""),
            userQuote: String(params?.user_quote ?? ""),
            note: params?.note,
            via: "chat_ai",
            chatId: currentChatId(),
        });
        if (result.status === "REJECTED") {
            return { success: false, status: result.status, message: result.reason };
        }
        const label = progress_js_1.KIND_LABEL[result.record.kind];
        if (result.status === "DUPLICATE") {
            return { success: true, status: result.status, message: `10 分钟内已记过同一句（${label}），没有重复写入`, id: result.record.id };
        }
        return {
            success: true,
            status: result.status,
            message: `已记下（${label}）：${result.record.user_quote}｜任务 ${result.record.task || "未知"}｜${result.path}`,
            id: result.record.id,
        };
    }
    catch (error) {
        return { success: false, status: "ERROR", message: errorText(error) };
    }
}
async function get_recent_progress(params) {
    const limit = Math.min(Math.max(Number(params?.limit) || 10, 1), 50);
    try {
        const records = await (0, progress_js_1.readRecentProgress)(limit);
        if (records.length === 0)
            return { success: true, records: "今天和昨天没有进展记录" };
        const lines = records.map((r) => `${r.iso}｜${progress_js_1.KIND_LABEL[r.kind] ?? r.kind}｜「${r.user_quote}」${r.note ? `｜${r.note}` : ""}｜任务 ${r.task || "未知"}｜来自 ${r.via === "dashboard" ? "主控台" : "对话"}`);
        return { success: true, records: lines.join("\n") };
    }
    catch (error) {
        return { success: false, records: `读取失败：${errorText(error)}` };
    }
}
