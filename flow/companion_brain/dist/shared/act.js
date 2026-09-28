"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.shell = shell;
exports.speak = speak;
exports.logAlertEvent = logAlertEvent;
exports.openApp = openApp;
exports.enqueueLock = enqueueLock;
exports.setAssignment = setAssignment;
exports.lookAtScreen = lookAtScreen;
// 行动：全部由代码执行，模型不直接碰手机。
// 锁不另起炉灶：只往现有锁队列 events/lock_queue.txt 投一行，由 S4_Lock_Queue_Worker 调 app_suspender 并回读。
const fsx_js_1 = require("./fsx.js");
const memory_js_1 = require("./memory.js");
async function call(name, params) {
    return await toolCall(name, params);
}
function outputOf(result) {
    if (result == null)
        return "";
    if (typeof result === "string")
        return result;
    if (typeof result.output === "string")
        return result.output;
    if (result.result && typeof result.result.output === "string")
        return result.result.output;
    return JSON.stringify(result);
}
async function shell(command) {
    return outputOf(await call("super_admin:shell", { command }));
}
// 开口：优先走主控台的 check_in（念 + 弹主控台 + 记一条待回应）；不行就退回系统 TTS。再发一条通知，保证看得到字。
async function speak(line, title, popup) {
    const out = { via: [], errors: [] };
    let spoken = false;
    try {
        const r = await call("focus_hub_nav:check_in", { message: line, force: true, speak: true, popup });
        // toolCall 的返回可能是对象、包了一层 result，或者是 JSON 字符串；统一转成文本再判断
        const text = typeof r === "string" ? r : JSON.stringify(r ?? {});
        if (text.includes("CHECKED_IN")) {
            const speakAccepted = /"speak"\s*:\s*"ACCEPTED"|语音 ACCEPTED/.test(text);
            out.via.push(`check_in(speak=${speakAccepted ? "ACCEPTED" : "?"})`);
            // 主控台已经受理了语音就不再用系统 TTS 重念，避免念两遍
            spoken = speakAccepted || !/"speak"\s*:\s*"FAILED"|语音 FAILED/.test(text);
        }
        else {
            out.errors.push(`check_in:${text.slice(0, 120) || "空返回"}`);
        }
    }
    catch (error) {
        out.errors.push(`check_in:${(0, fsx_js_1.errorText)(error)}`);
    }
    if (!spoken) {
        try {
            const r = await Tools.SoftwareSettings.testTtsPlayback(line, { interrupt: true });
            if (r?.playbackTriggered)
                out.via.push("tts");
            else
                out.errors.push("tts:playbackTriggered=false");
        }
        catch (error) {
            out.errors.push(`tts:${(0, fsx_js_1.errorText)(error)}`);
        }
    }
    try {
        await Tools.System.sendNotification(line, title);
        out.via.push("notification");
    }
    catch (error) {
        out.errors.push(`notification:${(0, fsx_js_1.errorText)(error)}`);
    }
    return out;
}
// 记一条 ALERT 事件：判断官证据包里的"上次提醒"、规则提醒的节流都认它
async function logAlertEvent(stage, ts) {
    try {
        await shell(`sh ${memory_js_1.ROOT}/events/eventd.sh add ALERT src=xiaoman_brain stage=${stage} --dedup "xmbr-${Math.floor(ts / 1000)}"`);
    }
    catch {
        // 记账失败不影响开口
    }
}
async function openApp(pkg) {
    try {
        const r = await Tools.System.startApp(pkg);
        return r?.success === false ? `FAILED ${r?.details ?? ""}` : "ACCEPTED";
    }
    catch (error) {
        return `FAILED ${(0, fsx_js_1.errorText)(error)}`;
    }
}
async function enqueueLock(pkg, protectedPkgs, lockable) {
    if (!/^[A-Za-z0-9._]+$/.test(pkg))
        return "REJECTED bad_pkg";
    if (protectedPkgs.includes(pkg))
        return "REJECTED protected";
    if (!lockable.includes(pkg))
        return "REJECTED not_in_ent_list";
    await (0, fsx_js_1.appendLine)(`${memory_js_1.ROOT}/events/lock_queue.txt`, `lock ${pkg}`);
    return "QUEUED";
}
async function setAssignment(task, minutes, now) {
    const id = `A-${now}-${Math.random().toString(36).slice(2, 8)}`;
    const deadline = now + minutes * 60 * 1000;
    // 与主控台"督促"的 assignment.json 同格式，主控台能直接显示和交差
    await (0, fsx_js_1.writeText)(`${memory_js_1.ROOT}/companion/warden/assignment.json`, JSON.stringify({ id, task, createdAt: now, deadline, standard: { type: "statement", detail: "由小满派活", filePath: "" }, active: true }));
    return { id, deadline };
}
// 看一眼屏幕：复用现有截屏取证脚本（截图 → OCR → 一行 FACT）
async function lookAtScreen() {
    try {
        const out = await shell(`sh ${memory_js_1.ROOT}/judge/shot_facts.sh`);
        const m = /FACT_OK (FACT\|[^\s]+)/.exec(out);
        return m ? m[1] : "";
    }
    catch {
        return "";
    }
}
