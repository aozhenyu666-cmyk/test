"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.appName = appName;
exports.gatherFacts = gatherFacts;
const fsx_js_1 = require("./fsx.js");
const memory_js_1 = require("./memory.js");
const time_js_1 = require("./time.js");
const APP_NAMES = {
    "tv.danmaku.bili": "B站",
    "com.ss.android.ugc.aweme": "抖音",
    "com.baidu.tieba": "贴吧",
    "com.tencent.tmgp.sgame": "王者荣耀",
    "com.xunmeng.pinduoduo": "拼多多",
    "com.phoenix.read": "红果短剧",
    "com.kylin.read": "红果漫剧",
    "com.twitter.android": "X",
    "com.xingin.xhs": "小红书",
    "com.taobao.idlefish": "闲鱼",
    "com.kurogame.mingchao": "鸣潮",
    "com.tencent.tmgp.dfm": "三角洲行动",
    "com.hpbr.bosszhipin": "Boss直聘",
    "com.zhaopin.social": "智联招聘",
    "com.job.android": "前程无忧",
    "com.openai.chatgpt": "ChatGPT",
    "com.anthropic.claude": "Claude",
    "com.tencent.mm": "微信",
    "org.telegram.messenger": "Telegram",
    "com.ai.assistance.operit": "Operit",
};
function appName(pkg) {
    return APP_NAMES[pkg] ?? pkg.split(".").pop() ?? pkg;
}
async function keyValues(path) {
    const out = {};
    for (const line of await (0, fsx_js_1.readLines)(path)) {
        const m = /^([A-Za-z_]+)=(.*)$/.exec(line.trim());
        if (m)
            out[m[1]] = m[2].trim();
    }
    return out;
}
async function pkgList(path) {
    return (await (0, fsx_js_1.readLines)(path))
        .map((l) => l.trim())
        .filter((l) => l && !l.startsWith("#") && /^[A-Za-z0-9._]+$/.test(l));
}
async function readEvents(now) {
    const out = [];
    // 事件目录按北京时间分日；凌晨时把前一天的尾巴也带上
    const days = [...new Set([(0, time_js_1.bjDateKey)(now - 3 * 3600 * 1000), (0, time_js_1.bjDateKey)(now)])];
    for (const day of days) {
        out.push(...(0, fsx_js_1.parseJsonl)(await (0, fsx_js_1.tailLines)(`${memory_js_1.ROOT}/events/${day}/events.jsonl`, 120)));
    }
    const seen = new Set();
    return out.filter((e) => {
        const k = `${e.type}-${e.ts}-${e.pkg ?? ""}-${e.src ?? ""}`;
        if (seen.has(k))
            return false;
        seen.add(k);
        return true;
    });
}
async function readVerdict(now) {
    const names = (await (0, fsx_js_1.listNames)(`${memory_js_1.ROOT}/judge`)).filter((n) => /^\d{8}\.jsonl$/.test(n));
    // 文件名按设备日期命名，最新的不一定排最后；取最后两个里时间最新的一行
    let best = null;
    for (const name of names.slice(-2)) {
        const [line] = (await (0, fsx_js_1.tailLines)(`${memory_js_1.ROOT}/judge/${name}`, 1)).slice(-1);
        if (!line)
            continue;
        const [time, body = ""] = line.split("\t");
        const ts = (0, time_js_1.parseScriptTime)(time, now);
        const m = /^([A-Z_]+)\s+CONF=(\d+)/.exec(body.trim());
        if (!ts || !m)
            continue;
        const field = (key) => {
            const r = new RegExp(`${key}=(.*?)(?=\\s+(?:WHY|NEXT|EVIDENCE|RECOMMENDED_LEVEL)=|$)`).exec(body);
            return r ? r[1].trim() : "";
        };
        const v = { ts, verdict: m[1], conf: Number(m[2]), why: field("WHY"), next: field("NEXT") };
        if (!best || v.ts > best.ts)
            best = v;
    }
    return best;
}
async function readProgress(now) {
    const names = (await (0, fsx_js_1.listNames)(`${memory_js_1.ROOT}/progress`)).filter((n) => /\.jsonl$/.test(n)).slice(-2);
    const items = [];
    for (const name of names) {
        for (const p of (0, fsx_js_1.parseJsonl)(await (0, fsx_js_1.tailLines)(`${memory_js_1.ROOT}/progress/${name}`, 60))) {
            if (p.origin !== "REAL_USER" || typeof p.ts !== "number")
                continue;
            const ts = p.ts * 1000;
            if (now - ts > 24 * 3600 * 1000 || ts > now + 60000)
                continue;
            items.push({ ts, kind: p.kind ?? "note", quote: p.user_quote ?? "", note: p.note ?? "", via: p.via ?? "" });
        }
    }
    return items.sort((a, b) => b.ts - a.ts);
}
async function gatherFacts(now) {
    const [taskKv, events, verdict, progress, assignmentRaw, lockable, protectedPkgs, taskApps, shots] = await Promise.all([
        keyValues(`${memory_js_1.ROOT}/drift/task_state.txt`),
        readEvents(now),
        readVerdict(now),
        readProgress(now),
        (0, fsx_js_1.readJson)(`${memory_js_1.ROOT}/companion/warden/assignment.json`),
        pkgList(`${memory_js_1.ROOT}/p2/ent.list`),
        pkgList(`${memory_js_1.ROOT}/p2/protect.deny`),
        keyValues(`${memory_js_1.ROOT}/p2/task_apps.conf`),
        (0, fsx_js_1.tailLines)(`${memory_js_1.ROOT}/events/shot_facts.tsv`, 1),
    ]);
    const samples = events
        .filter((e) => e.type === "FOREGROUND" && typeof e.ts === "number")
        .map((e) => ({ ts: e.ts * 1000, pkg: e.pkg ?? "none", ontask: String(e.ontask ?? "NONE"), screen: e.screen ?? "" }))
        .filter((s) => now - s.ts < 3 * 3600 * 1000)
        .sort((a, b) => b.ts - a.ts);
    const otherAlerts = events
        .filter((e) => e.type === "ALERT" && typeof e.ts === "number" && !String(e.src ?? "").startsWith("xiaoman"))
        .map((e) => e.ts * 1000);
    return {
        now,
        task: taskKv.TASK ?? "",
        taskState: taskKv.STATE ?? "",
        samples,
        otherAlerts,
        verdict,
        progress,
        assignment: assignmentRaw && assignmentRaw.active ? assignmentRaw : null,
        lockable,
        protectedPkgs,
        jobApps: (taskApps.JOB_SEARCH ?? "").split(",").map((s) => s.trim()).filter(Boolean),
        shotFact: shots[0] ?? "",
    };
}
