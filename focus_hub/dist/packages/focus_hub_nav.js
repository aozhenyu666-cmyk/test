"use strict";
/* METADATA
{
  "name": "focus_hub_nav",
  "display_name": {
    "zh": "主控台跳转",
    "en": "Focus Hub Navigation"
  },
  "description": {
    "zh": "把界面带到主控台，或在主界面打开指定对话。只做页面跳转，不发消息、不改工作流、不冻结应用。",
    "en": "Bring up the Focus Hub page, or open a specific chat in the main screen. Navigation only."
  },
  "enabled_by_default": true,
  "category": "System",
  "tools": [
    {
      "name": "open_focus_hub",
      "description": {
        "zh": "打开主控台页面（看板 + 会话导航）。适合放在工作流末尾，代替新建一段临时对话来提醒用户。",
        "en": "Open the Focus Hub page. Useful at the end of a workflow instead of starting a throwaway chat."
      },
      "parameters": []
    },
    {
      "name": "open_chat",
      "description": {
        "zh": "把主界面切换到指定对话并打开聊天页。",
        "en": "Switch the main screen to the given chat and open the chat page."
      },
      "parameters": [
        {
          "name": "chat_id",
          "description": {
            "zh": "对话 ID",
            "en": "Chat ID"
          },
          "type": "string",
          "required": true
        }
      ]
    },
    {
      "name": "check_in",
      "description": {
        "zh": "她来找用户：用当前陪伴状态生成一句话，同时语音念出、弹出主控台，并记一条待回应的打卡。深夜（23:30-08:00）、45 分钟内刚找过、或用户状态很好且 2 小时内报过进展时会自动跳过。适合放进定时工作流。",
        "en": "Companion check-in: speak one line, pop up the Focus Hub and log a pending check-in. Skips at night, during cooldown, or when the user is doing fine."
      },
      "parameters": [
        { "name": "message", "description": { "zh": "可选：指定要说的话；不填就按当前状态自动生成", "en": "Optional line to say" }, "type": "string", "required": false },
        { "name": "force", "description": { "zh": "true 时忽略深夜、冷却和状态判断", "en": "Ignore quiet hours and cooldown" }, "type": "boolean", "required": false },
        { "name": "speak", "description": { "zh": "是否语音念出，默认 true", "en": "Speak aloud, default true" }, "type": "boolean", "required": false },
        { "name": "popup", "description": { "zh": "是否弹出主控台，默认 true", "en": "Pop up the hub, default true" }, "type": "boolean", "required": false }
      ]
    }
  ]
}
*/
Object.defineProperty(exports, "__esModule", { value: true });
exports.open_focus_hub = open_focus_hub;
exports.open_chat = open_chat;
exports.check_in = check_in;
const nav_js_1 = require("../shared/nav.js");
const snapshot_js_1 = require("../shared/snapshot.js");
const format_js_1 = require("../shared/format.js");
const checkin_js_1 = require("../shared/checkin.js");
function errorText(error) {
    if (error && typeof error === "object" && "message" in error) {
        return String(error.message);
    }
    return String(error);
}
async function open_focus_hub() {
    try {
        await (0, nav_js_1.openRouteViaIntent)(nav_js_1.FOCUS_HUB_ROUTE);
        // 只能确认跳转请求已发出；后台启动 Activity 可能被系统拦截
        return { success: true, message: "已请求打开主控台（ACCEPTED，是否真的显示需以屏幕为准）" };
    }
    catch (error) {
        return { success: false, message: `打开主控台失败：${errorText(error)}` };
    }
}
async function open_chat(params) {
    const chatId = String(params?.chat_id ?? "").trim();
    if (!chatId)
        return { success: false, message: "缺少 chat_id" };
    try {
        await (0, nav_js_1.setMainChat)(chatId);
        await (0, nav_js_1.openRouteViaIntent)(nav_js_1.NATIVE_CHAT_ROUTE);
        return { success: true, message: `已把主界面切到 ${chatId} 并请求打开聊天页（ACCEPTED）` };
    }
    catch (error) {
        return { success: false, message: `打开对话失败：${errorText(error)}` };
    }
}
function flag(value, fallback) {
    if (value === undefined || value === null || value === "")
        return fallback;
    return value === true || String(value).toLowerCase() === "true";
}
const SKIP_TEXT = {
    SKIP_QUIET: "深夜时段（23:30-08:00），不打扰",
    SKIP_COOLDOWN: "45 分钟内刚找过，不重复打扰",
    SKIP_FINE: "状态很好、最近也报过进展，不打扰",
};
async function check_in(params) {
    try {
        const now = Date.now();
        const snap = await (0, snapshot_js_1.collectSnapshot)();
        const mood = (0, format_js_1.moodOf)(snap);
        const lastProgressTs = snap.progress?.[0]?.ts ?? null;
        const decision = flag(params?.force, false)
            ? "GO"
            : (0, checkin_js_1.decideCheckin)(now, mood.level, snap.checkins[0] ?? null, lastProgressTs);
        if (decision !== "GO") {
            return { success: true, status: decision, message: SKIP_TEXT[decision] };
        }
        const line = String(params?.message ?? "").trim() || mood.line;
        let speak = "SKIPPED";
        if (flag(params?.speak, true)) {
            try {
                const result = await Tools.SoftwareSettings.testTtsPlayback(line, { interrupt: false });
                speak = result?.playbackTriggered ? "ACCEPTED" : "FAILED";
            }
            catch {
                speak = "FAILED";
            }
        }
        let popup = "SKIPPED";
        if (flag(params?.popup, true)) {
            try {
                await (0, nav_js_1.openRouteViaIntent)(nav_js_1.FOCUS_HUB_ROUTE);
                popup = "ACCEPTED";
            }
            catch {
                popup = "FAILED";
            }
        }
        const ts = Math.floor(now / 1000);
        const record = {
            id: (0, checkin_js_1.newCheckinId)(ts),
            ts,
            iso: (0, checkin_js_1.isoLocal)(new Date(now)),
            type: "CHECKIN",
            level: mood.level,
            score: mood.score,
            line,
            speak,
            popup,
            trigger: flag(params?.force, false) ? "manual" : "workflow",
        };
        await (0, checkin_js_1.appendCheckin)(record);
        return {
            success: speak !== "FAILED" || popup !== "FAILED",
            status: "CHECKED_IN",
            id: record.id,
            line,
            speak,
            popup,
            message: `语音 ${speak}，弹窗 ${popup}。ACCEPTED 只代表已发出；用户是否听到/看到，以用户在主控台回应为准（回应会记成一条进展）。`,
        };
    }
    catch (error) {
        return { success: false, status: "ERROR", message: errorText(error) };
    }
}
