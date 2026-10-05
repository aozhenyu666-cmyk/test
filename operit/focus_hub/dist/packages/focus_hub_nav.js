"use strict";
/* METADATA
{
  "name": "focus_hub_nav",
  "display_name": {
    "zh": "主控台跳转",
    "en": "Focus Hub Navigation"
  },
  "description": {
    "zh": "把界面带到主控台、打开指定对话、她来找你打卡；专注时段内按当前问题主动推进（focus_nudge）。不冻结应用。",
    "en": "Navigation, companion check-ins and focus-window nudges. Never freezes apps."
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
        "zh": "她来找用户：用当前陪伴状态生成一句话，语音念出，心情不是安心时再弹出主控台，并记一条待回应的打卡。深夜（23:30-08:00）、45 分钟内刚找过、心情和上次一样且两小时内说过、或安心且最近有进展时自动跳过。适合放进定时工作流。",
        "en": "Companion check-in: speak one line, pop up the Focus Hub and log a pending check-in. Skips at night, during cooldown, or when the user is doing fine."
      },
      "parameters": [
        {
          "name": "message",
          "description": {
            "zh": "可选：指定要说的话；不填就按当前状态自动生成",
            "en": "Optional line to say"
          },
          "type": "string",
          "required": false
        },
        {
          "name": "force",
          "description": {
            "zh": "true 时忽略深夜、冷却和状态判断",
            "en": "Ignore quiet hours and cooldown"
          },
          "type": "boolean",
          "required": false
        },
        {
          "name": "speak",
          "description": {
            "zh": "是否语音念出，默认 true",
            "en": "Speak aloud, default true"
          },
          "type": "boolean",
          "required": false
        },
        {
          "name": "popup",
          "description": {
            "zh": "是否弹出主控台；默认只在心情不是安心时弹",
            "en": "Pop up the hub; default only when mood is above calm"
          },
          "type": "boolean",
          "required": false
        }
      ]
    },
    {
      "name": "focus_nudge",
      "description": {
        "zh": "专注时段推进：时段内如果你有一会儿没回答当前问题，她念出当前问题并弹出主控台；没开始任何事时问你这段时间做什么；暂停超过 15 分钟问一次回来吗。同时收回到期的钥匙并提醒你说过回来先做什么。时段外只收钥匙不打扰。适合每 15 分钟的工作流。",
        "en": "Focus-window nudge: speak the current question and pop up the hub when idle; also closes expired keys."
      },
      "parameters": [
        {
          "name": "force",
          "description": {
            "zh": "true 时忽略时段与间隔，立即推进一次（测试用）",
            "en": "Ignore window and spacing"
          },
          "type": "boolean",
          "required": false
        }
      ]
    }
  ]
}
*/
Object.defineProperty(exports, "__esModule", { value: true });
exports.open_focus_hub = open_focus_hub;
exports.open_chat = open_chat;
exports.check_in = check_in;
exports.focus_nudge = focus_nudge;
const nav_js_1 = require("../shared/nav.js");
const snapshot_js_1 = require("../shared/snapshot.js");
const speech_js_1 = require("../shared/speech.js");
const format_js_1 = require("../shared/format.js");
const checkin_js_1 = require("../shared/checkin.js");
const gate_js_1 = require("../shared/gate.js");
const thinking_js_1 = require("../shared/thinking.js");
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
    SKIP_FINE: "状态很好、最近也报过进展（或刚问过），不打扰",
    SKIP_SAME: "心情和上次一样，两小时内已经说过，不重复念叨",
    SKIP_FOCUS: "专注时段内由「专注推进」负责，打卡让路",
};
async function check_in(params) {
    try {
        const now = Date.now();
        const snap = await (0, snapshot_js_1.collectSnapshot)();
        const mood = (0, format_js_1.moodOf)(snap);
        const lastProgressTs = snap.progress?.[0]?.ts ?? null;
        let decision = flag(params?.force, false)
            ? "GO"
            : (0, checkin_js_1.decideCheckin)(now, mood.level, snap.checkins[0] ?? null, lastProgressTs);
        // 专注时段里由 focus_nudge 围绕当前问题推进，打卡让路，避免两个声音
        if (decision === "GO" && !flag(params?.force, false)) {
            try {
                if ((0, gate_js_1.focusNow)(await (0, gate_js_1.loadGate)(), now).active)
                    decision = "SKIP_FOCUS";
            }
            catch {
                // 读不到时段配置就照常打卡
            }
        }
        if (decision !== "GO") {
            return { success: true, status: decision, message: SKIP_TEXT[decision] };
        }
        const line = String(params?.message ?? "").trim() || mood.line;
        let speak = "SKIPPED";
        let speakError = "";
        let speakVia = "";
        if (flag(params?.speak, true)) {
            const said = await (0, speech_js_1.speak)(line, `打卡 ${(0, checkin_js_1.isoLocal)(new Date(now)).slice(11, 16)}`);
            speak = said.status;
            speakVia = said.via;
            speakError = said.error ?? "";
        }
        // 安心时只说一句，不把主控台弹出来打断
        let popup = "SKIPPED";
        if (flag(params?.popup, mood.level > 0)) {
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
            ...(speakVia && speakVia !== "none" ? { speak_via: speakVia } : {}),
            ...(speakError ? { speak_error: speakError } : {}),
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
            message: `语音 ${speak}${speakVia && speakVia !== "none" ? `（${speakVia}）` : ""}${speakError ? `（${speakError}）` : ""}，弹窗 ${popup}。ACCEPTED 只代表已发出；用户是否听到/看到，以用户在主控台回应为准（回应会记成一条进展）。`,
        };
    }
    catch (error) {
        return { success: false, status: "ERROR", message: errorText(error) };
    }
}

// ---------- 专注时段推进 ----------
const NUDGE_LOG = `${gate_js_1.GATE_DIR}/nudges.jsonl`;
const NUDGE_GAP_MS = 20 * 60 * 1000;
const ANSWER_FRESH_MS = 20 * 60 * 1000;
const PAUSE_GRACE_MS = 15 * 60 * 1000;
function shortText(value, n) {
    const t = String(value || "").replace(/\s+/g, " ").trim();
    return t.length > n ? t.slice(0, n) + "…" : t;
}
async function lastNudgeAt() {
    try {
        const exists = await Tools.Files.exists(NUDGE_LOG);
        if (!exists?.exists)
            return 0;
        const probe = await Tools.Files.readPart(NUDGE_LOG, 1, 1);
        if (probe.totalLines <= 0)
            return 0;
        const part = await Tools.Files.readPart(NUDGE_LOG, probe.totalLines, probe.totalLines);
        const line = String(part.content || "").split("\n").map((l) => l.replace(/^\s*\d+\| ?/, "")).filter((l) => l.trim()).pop();
        return line ? Number(JSON.parse(line).ts_ms) || 0 : 0;
    }
    catch {
        return 0;
    }
}
async function focus_nudge(params) {
    try {
        const now = Date.now();
        const force = flag(params?.force, false);
        const state = await (0, gate_js_1.loadGate)();
        const window = (0, gate_js_1.focusNow)(state, now);
        const tick = await (0, gate_js_1.tickGate)(now);
        const said = [];
        for (const k of tick.closed) {
            const line = `给${k.app}的钥匙到时间了。${k.back_to ? "你说过回来先做：" + shortText(k.back_to, 30) : "回来接着做吧"}`;
            said.push({ line, speak: (await (0, speech_js_1.speak)(line, "钥匙到期")).status });
        }
        if (!window.active && !force)
            return { success: true, status: "OUTSIDE_WINDOW", closed_keys: tick.closed.length, expired_gates: tick.expired.length, message: "不在专注时段，只收了到期的钥匙" };
        const task = await (0, thinking_js_1.loadTask)();
        let line = "";
        let reason = "";
        if (!task.available) {
            line = "后台账本连不上，打开主控台看一眼";
            reason = "BACKEND_UNAVAILABLE";
        }
        else if (!task.session || !task.real) {
            line = "这段时间做什么？打开主控台写一句";
            reason = "NO_TASK";
        }
        else if (task.session.status === "PAUSED") {
            if (!force && now - (task.session.pausedAt || 0) < PAUSE_GRACE_MS)
                return { success: true, status: "SKIP_PAUSED", message: "刚说过要歇，还没到 15 分钟" };
            line = "歇得差不多了，回来吗？";
            reason = "PAUSE_OVER";
        }
        else {
            if (!force && task.session.lastAnswerAt && now - task.session.lastAnswerAt < ANSWER_FRESH_MS)
                return { success: true, status: "SKIP_ACTIVE", message: "你刚答过，不打扰" };
            line = `回来接着想：${shortText(task.session.question, 40)}`;
            reason = "IDLE";
        }
        if (!force && now - (await lastNudgeAt()) < NUDGE_GAP_MS)
            return { success: true, status: "SKIP_SPACING", message: "20 分钟内刚找过" };
        const voice = await (0, speech_js_1.speak)(line, "专注推进");
        let popup = "FAILED";
        try {
            await (0, nav_js_1.openRouteViaIntent)(nav_js_1.FOCUS_HUB_ROUTE);
            popup = "ACCEPTED";
        }
        catch {
            popup = "FAILED";
        }
        const record = { ts_ms: now, iso: (0, checkin_js_1.isoLocal)(new Date(now)), type: "FOCUS_NUDGE", reason, line, speak: voice.status, popup, window: window.active ? `${window.start}-${window.end}` : "forced" };
        await Tools.Files.write(NUDGE_LOG, `${JSON.stringify(record)}\n`, true);
        return { success: true, status: "NUDGED", reason, line, speak: voice.status, popup, closed_keys: said, message: "语音和弹窗只代表已发出；你是否看到听到以你回到主控台为准" };
    }
    catch (error) {
        return { success: false, status: "ERROR", message: errorText(error) };
    }
}
