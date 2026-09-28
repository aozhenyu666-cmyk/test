// Node harness: mocks the Operit 1.12.2 host APIs used by companion_brain and exercises dist/.
// Run: node test/harness.js   (exit code 1 on any failed check)
const path = require("path");
const assert = require("assert");
const DIST = path.join(__dirname, "..", "dist");
const R = "/sdcard/Download/Operit";

// ---------- clock (Beijing 2026-09-28 14:00 = UTC 06:00) ----------
let NOW = Date.UTC(2026, 8, 28, 6, 0, 0);
Date.now = () => NOW;
const MIN = 60000;
const bjKey = (ms) => {
  const t = new Date(ms + 8 * 3600 * 1000);
  return `${t.getUTCFullYear()}${String(t.getUTCMonth() + 1).padStart(2, "0")}${String(t.getUTCDate()).padStart(2, "0")}`;
};

// ---------- files ----------
let FILES = {};
function reset() {
  FILES = {
    [`${R}/drift/task_state.txt`]: "STATE=ACTIVE\nTASK=求职投递\nMODE=JOB_SEARCH\n",
    [`${R}/p2/ent.list`]: "# 组A\ntv.danmaku.bili\ncom.ss.android.ugc.aweme\ncom.baidu.tieba\n# com.xingin.xhs\n",
    [`${R}/p2/protect.deny`]: "# 保护\ncom.ai.assistance.operit\ncom.openai.chatgpt\ncom.tencent.mm\n",
    [`${R}/p2/task_apps.conf`]: "JOB_SEARCH=com.hpbr.bosszhipin,com.zhaopin.social,com.openai.chatgpt\nSTUDY=x\n",
  };
  state.calls = [];
}
function readPart(p, s = 1, e) {
  if (!(p in FILES)) throw new Error(`no file ${p}`);
  const all = FILES[p].split("\n");
  if (all[all.length - 1] === "") all.pop();
  const total = all.length;
  const a = Math.min(Math.max(1, s), Math.max(1, total));
  const b = Math.min(Math.max(e ?? a + 199, a), Math.max(1, total));
  const digits = String(total).length;
  const content = total ? all.slice(a - 1, b).map((l, i) => `${String(a + i).padStart(digits, " ")}| ${l}`).join("\n") : "";
  return { content, totalLines: total };
}
const state = { calls: [], modelReply: null, prompts: [] };
const rec = (...a) => state.calls.push(a);

global.Tools = {
  Files: {
    exists: async (p) => ({ exists: p in FILES }),
    readPart: async (p, s, e) => readPart(p, s, e),
    write: async (p, c, append) => {
      FILES[p] = append && FILES[p] ? FILES[p] + c : c;
      return { successful: true };
    },
    list: async (dir) => ({
      entries: Object.keys(FILES)
        .filter((f) => f.startsWith(dir + "/") && !f.slice(dir.length + 1).includes("/"))
        .map((f) => ({ name: f.slice(dir.length + 1), isDirectory: false })),
    }),
  },
  Chat: {
    call: async (opts) => {
      const user = opts.turns.map((t) => t.content).join("\n");
      state.prompts.push(user);
      rec("model", opts.functionType);
      const reply = typeof state.modelReply === "function" ? state.modelReply(user) : state.modelReply;
      if (reply instanceof Error) throw reply;
      return { text: reply ?? "" };
    },
  },
  SoftwareSettings: { testTtsPlayback: async (t) => (rec("tts", t), { playbackTriggered: true }) },
  System: {
    sendNotification: async (m, t) => (rec("notify", t, m), "ok"),
    startApp: async (p) => (rec("startApp", p), { success: true }),
  },
};
global.toolCall = async (name, params) => {
  rec("tool", name, params);
  if (name === "focus_hub_nav:check_in") return { success: true, status: "CHECKED_IN", speak: "ACCEPTED", popup: params.popup ? "ACCEPTED" : "SKIPPED" };
  if (name === "super_admin:shell") {
    if (params.command.includes("shot_facts.sh")) return { output: "FACT_OK FACT|123|app=other|resume=no|applied=0|interview=unknown\nSHOT=x" };
    return { output: "OK" };
  }
  throw new Error(`unknown tool ${name}`);
};
let HOOK = null;
global.ToolPkg = { registerSystemPromptComposeHook: (d) => (HOOK = d) };

// ---------- helpers ----------
function sample(minAgo, pkg, ontask, screen = "ON") {
  const ts = Math.floor((NOW - minAgo * MIN) / 1000);
  const f = `${R}/events/${bjKey(NOW - minAgo * MIN)}/events.jsonl`;
  FILES[f] = (FILES[f] ?? "") + JSON.stringify({ ts, type: "FOREGROUND", pkg, ontask, screen, src: "P3_OBS" }) + "\n";
}
function progress(minAgo, kind, quote) {
  const ts = Math.floor((NOW - minAgo * MIN) / 1000);
  const f = `${R}/progress/${bjKey(NOW)}.jsonl`;
  FILES[f] = (FILES[f] ?? "") + JSON.stringify({ ts, origin: "REAL_USER", via: "chat_ai", kind, user_quote: quote }) + "\n";
}
const advance = (min) => (NOW += min * MIN);
const said = () => (FILES[`${R}/companion/brain/said.jsonl`] ?? "").trim().split("\n").filter(Boolean).map(JSON.parse);
const tools = (name) => state.calls.filter((c) => c[0] === "tool" && c[1] === name);
const reply = (o) => JSON.stringify(o);

const brain = require(path.join(DIST, "packages/companion_brain.js"));
const main = require(path.join(DIST, "main.js"));

let failed = 0;
async function check(name, fn) {
  try {
    await fn();
    console.log(`ok   ${name}`);
  } catch (e) {
    failed++;
    console.log(`FAIL ${name}\n     ${e.stack.split("\n").slice(0, 3).join("\n     ")}`);
  }
}

(async () => {
  await check("idle when on task", async () => {
    reset();
    sample(20, "com.hpbr.bosszhipin", "1");
    sample(5, "com.hpbr.bosszhipin", "1");
    const r = await brain.tick({});
    assert.strictEqual(r.status, "IDLE", JSON.stringify(r));
    assert.strictEqual(state.calls.filter((c) => c[0] === "model").length, 0);
  });

  await check("drift → nudge, speaks through check_in + notification, logs ALERT", async () => {
    reset();
    sample(25, "com.baidu.tieba", "0");
    sample(10, "com.baidu.tieba", "0");
    state.modelReply = reply({ speak: true, line: "贴吧刷了二十多分钟啦，先去 Boss 投一家？", action: "none", why: "刚发现" });
    const r = await brain.tick({});
    assert.strictEqual(r.status, "SPOKE", JSON.stringify(r));
    assert.strictEqual(r.stage, "nudge");
    assert.strictEqual(tools("focus_hub_nav:check_in").length, 1);
    assert.strictEqual(tools("focus_hub_nav:check_in")[0][2].popup, false);
    assert(state.calls.some((c) => c[0] === "notify" && c[1] === "小满"));
    assert(tools("super_admin:shell").some((c) => c[2].command.includes("eventd.sh add ALERT src=xiaoman_brain")));
    assert.strictEqual(said().length, 1);
    assert(state.prompts.at(-1).includes("贴吧（不在任务上）") && !state.prompts.at(-1).includes("com.baidu.tieba（"), "prompt uses app names");
  });

  await check("five minutes later: waits, no model call", async () => {
    advance(5);
    sample(0, "com.baidu.tieba", "0");
    state.calls = [];
    const r = await brain.tick({});
    assert.notStrictEqual(r.status, "SPOKE", JSON.stringify(r));
    assert.strictEqual(state.calls.filter((c) => c[0] === "model").length, 0);
  });

  await check("12 minutes after nudge → ask, looks at screen, model assigns a task", async () => {
    advance(8);
    sample(0, "com.baidu.tieba", "0");
    state.calls = [];
    state.modelReply = reply({ speak: true, line: "还在贴吧呢，给你个小活：二十分钟内投一家？", action: "assign", assign: { task: "在 Boss 直聘投一家", minutes: 20 } });
    const r = await brain.tick({});
    assert.strictEqual(r.stage, "ask", JSON.stringify(r));
    assert(tools("super_admin:shell").some((c) => c[2].command.includes("shot_facts.sh")), "took a screenshot");
    assert(state.prompts.at(-1).includes("FACT|123"), "screen fact in prompt");
    const a = JSON.parse(FILES[`${R}/companion/warden/assignment.json`]);
    assert.strictEqual(a.task, "在 Boss 直聘投一家");
    assert.strictEqual(a.active, true);
    assert(FILES[`${R}/companion/brain/commitments.jsonl`].includes("assigned"));
    assert.strictEqual(tools("focus_hub_nav:check_in")[0][2].popup, true, "ask pops up the hub");
  });

  await check("model cannot lock before the rule layer allows it", async () => {
    advance(12);
    sample(0, "com.baidu.tieba", "0");
    state.modelReply = reply({ speak: true, line: "我要锁了哦", action: "lock" });
    const r = await brain.tick({});
    assert.strictEqual(r.stage, "ask", JSON.stringify(r));
    assert(!(FILES[`${R}/events/lock_queue.txt`] ?? "").includes("lock"), "no lock yet");
  });

  await check("30+ minutes after first nudge without reply → lock queued via existing queue", async () => {
    advance(15);
    sample(0, "com.baidu.tieba", "0");
    state.modelReply = reply({ speak: true, line: "说好的，贴吧先暂停了，想解开走解锁流程。", action: "none" });
    const r = await brain.tick({});
    assert.strictEqual(r.stage, "lock", JSON.stringify(r));
    assert.strictEqual(FILES[`${R}/events/lock_queue.txt`], "lock com.baidu.tieba\n");
    assert.strictEqual(said().at(-1).stage, "lock");
  });

  await check("no second lock of the same app within cooldown", async () => {
    advance(15);
    sample(0, "com.baidu.tieba", "0");
    state.modelReply = reply({ speak: true, line: "锁了还在刷呢？几点开始投？", action: "none" });
    await brain.tick({});
    assert.strictEqual(FILES[`${R}/events/lock_queue.txt`], "lock com.baidu.tieba\n");
  });

  await check("user says pause → silent, episode cleared", async () => {
    advance(15);
    sample(0, "com.baidu.tieba", "0");
    progress(1, "pause", "我去吃个饭，一小时后开始");
    state.calls = [];
    const r = await brain.tick({});
    assert.strictEqual(r.status, "IDLE", JSON.stringify(r));
    const st = JSON.parse(FILES[`${R}/companion/brain/state.json`]);
    assert.strictEqual(st.episode, null);
  });

  await check("quiet hours: no speaking, no locking", async () => {
    reset();
    NOW = Date.UTC(2026, 8, 28, 16, 30, 0); // 北京 00:30
    sample(30, "com.baidu.tieba", "0");
    sample(10, "com.baidu.tieba", "0");
    const st = { episode: { start_ts: NOW - 60 * MIN, pkg: "com.baidu.tieba", nudges: 2, first_nudge_ts: NOW - 50 * MIN, last_nudge_ts: NOW - 20 * MIN, locked: false }, speaks: [] };
    FILES[`${R}/companion/brain/state.json`] = JSON.stringify(st);
    const r = await brain.tick({});
    assert.strictEqual(r.status, "HOLD", JSON.stringify(r));
    assert(!(FILES[`${R}/events/lock_queue.txt`] ?? "").length);
    assert.strictEqual(tools("focus_hub_nav:check_in").length, 0);
  });

  await check("rate limit: 4 speaks in the last hour → hold", async () => {
    reset();
    NOW = Date.UTC(2026, 8, 28, 6, 0, 0);
    sample(25, "tv.danmaku.bili", "0");
    sample(5, "tv.danmaku.bili", "0");
    FILES[`${R}/companion/brain/state.json`] = JSON.stringify({ speaks: [NOW - 50 * MIN, NOW - 40 * MIN, NOW - 30 * MIN, NOW - 20 * MIN] });
    const r = await brain.tick({});
    assert.strictEqual(r.status, "HOLD", JSON.stringify(r));
  });

  await check("another reminder (drift_alert) just spoke → hold to avoid two voices", async () => {
    reset();
    sample(25, "tv.danmaku.bili", "0");
    sample(5, "tv.danmaku.bili", "0");
    const f = `${R}/events/${bjKey(NOW)}/events.jsonl`;
    FILES[f] += JSON.stringify({ ts: Math.floor((NOW - 3 * MIN) / 1000), type: "ALERT", src: "drift_alert" }) + "\n";
    const r = await brain.tick({});
    assert.strictEqual(r.status, "HOLD", JSON.stringify(r));
  });

  await check("record_commitment (Beijing HH:MM) → asked when due, only once", async () => {
    reset();
    sample(5, "com.hpbr.bosszhipin", "1");
    const r = await brain.record_commitment({ text: "投一家", due_time: "14:30", quote: "两点半前我投一家" });
    assert(r.success && r.message.includes("14:30"), JSON.stringify(r));
    advance(31);
    sample(0, "com.hpbr.bosszhipin", "1");
    state.modelReply = reply({ speak: true, line: "你说两点半前投一家，现在到点了，投了吗？", action: "none" });
    const t = await brain.tick({});
    assert.strictEqual(t.stage, "commitment_due", JSON.stringify(t));
    advance(15);
    sample(0, "com.hpbr.bosszhipin", "1");
    const t2 = await brain.tick({});
    assert.notStrictEqual(t2.stage, "commitment_due", JSON.stringify(t2));
  });

  await check("user reports done → commitment kept", async () => {
    progress(1, "done", "投了一家");
    await brain.tick({});
    const lines = FILES[`${R}/companion/brain/commitments.jsonl`];
    assert(lines.includes('"status":"kept"'), lines);
  });

  await check("garbage model output → fallback line, still no lock outside lock stage", async () => {
    reset();
    NOW = Date.UTC(2026, 8, 28, 6, 0, 0);
    sample(25, "com.ss.android.ugc.aweme", "0");
    sample(5, "com.ss.android.ugc.aweme", "0");
    state.modelReply = "我觉得应该提醒一下";
    const r = await brain.tick({});
    assert.strictEqual(r.status, "SPOKE", JSON.stringify(r));
    assert(r.line.includes("抖音"), r.line);
  });

  await check("model error → fallback, reported", async () => {
    reset();
    sample(25, "com.ss.android.ugc.aweme", "0");
    sample(5, "com.ss.android.ugc.aweme", "0");
    state.modelReply = new Error("timeout");
    const r = await brain.tick({});
    assert.strictEqual(r.status, "SPOKE");
    assert(r.error.includes("timeout"));
  });

  await check("line is cleaned of package names and system words", async () => {
    reset();
    sample(25, "com.ss.android.ugc.aweme", "0");
    sample(5, "com.ss.android.ugc.aweme", "0");
    state.modelReply = reply({ speak: true, line: "判断官说 DRIFT_RISK，你在 com.ss.android.ugc.aweme 上", action: "none" });
    const r = await brain.tick({});
    assert(!/DRIFT_RISK|com\.ss|判断官/.test(r.line), r.line);
  });

  await check("open_app only opens job apps", async () => {
    reset();
    sample(25, "com.ss.android.ugc.aweme", "0");
    sample(5, "com.ss.android.ugc.aweme", "0");
    state.modelReply = reply({ speak: true, line: "我帮你把 Boss 打开了，投一家？", action: "open_app", open_app: "com.ss.android.ugc.aweme" });
    await brain.tick({});
    const opened = state.calls.filter((c) => c[0] === "startApp").map((c) => c[1]);
    assert.deepStrictEqual(opened, ["com.hpbr.bosszhipin"]);
  });

  await check("protected app never enters the lock queue", async () => {
    reset();
    FILES[`${R}/p2/ent.list`] += "com.tencent.mm\n";
    sample(40, "com.tencent.mm", "0");
    sample(5, "com.tencent.mm", "0");
    FILES[`${R}/companion/brain/state.json`] = JSON.stringify({ episode: { start_ts: NOW - 60 * MIN, pkg: "com.tencent.mm", nudges: 2, first_nudge_ts: NOW - 45 * MIN, last_nudge_ts: NOW - 15 * MIN, locked: false }, speaks: [] });
    state.modelReply = reply({ speak: true, line: "微信聊完了吗？", action: "none" });
    const r = await brain.tick({});
    assert.notStrictEqual(r.stage, "lock", JSON.stringify(r));
    assert(!(FILES[`${R}/events/lock_queue.txt`] ?? "").length);
  });

  await check("screen off samples are not drift", async () => {
    reset();
    sample(25, "none", "NONE", "OFF");
    sample(5, "none", "NONE", "OFF");
    FILES[`${R}/companion/brain/state.json`] = JSON.stringify({ speaks: [NOW - 30 * MIN] });
    const r = await brain.tick({});
    assert.strictEqual(r.status, "IDLE", JSON.stringify(r));
  });

  await check("prompt hook injects memory only into the companion chat", async () => {
    reset();
    main.registerToolPkg();
    const ev = (chatId) => ({ eventName: "after_compose_system_prompt", eventPayload: { chatId, systemPrompt: "BASE" } });
    const hit = await HOOK.function(ev("52a18815-2c07-4f6d-bbae-ae5d040daffb"));
    assert(hit.systemPrompt.startsWith("BASE") && hit.systemPrompt.includes("小满的记忆") && hit.systemPrompt.includes("关于 TA"), hit.systemPrompt.slice(0, 200));
    assert.strictEqual(await HOOK.function(ev("other-chat")), null);
    assert.strictEqual(await HOOK.function({ eventName: "before_compose_system_prompt", eventPayload: { chatId: "52a18815-2c07-4f6d-bbae-ae5d040daffb" } }), null);
    assert(FILES[`${R}/companion/brain/profile.md`].includes("## TA 自己写的"), "profile seeded");
  });

  await check("nightly reflection appends observations to profile", async () => {
    reset();
    NOW = Date.UTC(2026, 8, 28, 15, 5, 0); // 北京 23:05
    FILES[`${R}/companion/brain/said.jsonl`] = JSON.stringify({ ts: NOW - 3 * 3600000, stage: "nudge", line: "又在刷B站啦", action: "none" }) + "\n";
    progress(170, "progress", "好好好这就去");
    state.modelReply = (u) => (u.includes("请复盘") ? reply({ observations: ["直接点出 App 名字后 TA 十分钟内回了话"], worked: ["引用原话"], ignored: [] }) : reply({ speak: false }));
    await brain.tick({});
    assert(FILES[`${R}/companion/brain/profile.md`].includes("直接点出 App 名字后"), FILES[`${R}/companion/brain/profile.md`].slice(-300));
    const st = JSON.parse(FILES[`${R}/companion/brain/state.json`]);
    assert.strictEqual(st.last_reflect_date, "20260928");
  });

  await check("act=false (observe mode): decides and logs but touches nothing", async () => {
    reset();
    NOW = Date.UTC(2026, 8, 28, 6, 0, 0);
    FILES[`${R}/companion/brain/config.json`] = JSON.stringify({ act: false });
    sample(25, "com.baidu.tieba", "0");
    sample(5, "com.baidu.tieba", "0");
    state.modelReply = reply({ speak: true, line: "又在贴吧啦", action: "open_app", open_app: "com.hpbr.bosszhipin" });
    const r = await brain.tick({});
    assert.strictEqual(r.delivered, "dry-run");
    assert.strictEqual(tools("focus_hub_nav:check_in").length, 0);
    assert.strictEqual(state.calls.filter((c) => c[0] === "startApp").length, 0);
    assert(FILES[`${R}/companion/brain/actions.jsonl`].includes("又在贴吧啦"));
  });

  await check("get_status is read-only and reports the plan", async () => {
    reset();
    sample(25, "com.baidu.tieba", "0");
    sample(5, "com.baidu.tieba", "0");
    const before = JSON.stringify(Object.keys(FILES).sort());
    const s = await brain.get_status();
    assert(s.success && s.status.plan.stage === "nudge", JSON.stringify(s).slice(0, 300));
    assert.strictEqual(state.calls.filter((c) => c[0] === "model").length, 0);
    assert.strictEqual(JSON.stringify(Object.keys(FILES).filter((f) => !f.endsWith("profile.md")).sort()), JSON.stringify(JSON.parse(before).sort()));
  });


  await check("drill: every scenario reaches the expected stage without touching state or memory", async () => {
    reset();
    NOW = Date.UTC(2026, 8, 28, 6, 0, 0);
    FILES[`${R}/companion/brain/state.json`] = JSON.stringify({ speaks: [NOW - 5 * MIN] });
    const stateBefore = FILES[`${R}/companion/brain/state.json`];
    state.modelReply = reply({ speak: true, line: "演练里的一句话", action: "none" });
    const want = { nudge: "nudge", ask: "ask", lock: "lock", pause: "none", commitment: "commitment_due", quiet: "lock", working: "none" };
    for (const [sc, stage] of Object.entries(want)) {
      const r = await brain.drill({ scenario: sc });
      assert(r.success, JSON.stringify(r));
      assert.strictEqual(r.result.plan.stage, stage, `${sc}: ${JSON.stringify(r.result.plan)}`);
    }
    const q = await brain.drill({ scenario: "quiet" });
    assert.strictEqual(q.result.plan.canSpeak, false);
    assert.strictEqual(FILES[`${R}/companion/brain/state.json`], stateBefore, "state untouched");
    assert(!FILES[`${R}/companion/brain/said.jsonl`], "said untouched");
    assert(!(FILES[`${R}/events/lock_queue.txt`] ?? "").length, "no lock without execute");
    assert.strictEqual(tools("focus_hub_nav:check_in").length, 0, "no speaking without execute");
  });

  await check("drill lock with execute=true queues the lock and speaks", async () => {
    reset();
    state.modelReply = reply({ speak: true, line: "说好的，贴吧先暂停了。", action: "none" });
    const r = await brain.drill({ scenario: "lock", execute: true });
    assert.strictEqual(r.result.decision.action, "lock");
    assert.strictEqual(FILES[`${R}/events/lock_queue.txt`], "lock com.baidu.tieba\n");
    assert.strictEqual(tools("focus_hub_nav:check_in").length, 1);
    assert(!FILES[`${R}/companion/brain/said.jsonl`], "drill not written to said");
  });

  await check("drill rejects unknown scenario", async () => {
    const r = await brain.drill({ scenario: "whatever" });
    assert.strictEqual(r.success, false);
  });


  await check("check_in result as JSON string: no second TTS", async () => {
    reset();
    const orig = global.toolCall;
    global.toolCall = async (name, params) => {
      if (name === "focus_hub_nav:check_in") return JSON.stringify({ success: true, status: "CHECKED_IN", speak: "ACCEPTED", popup: "SKIPPED" });
      return orig(name, params);
    };
    sample(25, "com.baidu.tieba", "0");
    sample(5, "com.baidu.tieba", "0");
    state.modelReply = reply({ speak: true, line: "又在贴吧啦，先投一家？", action: "none" });
    const r = await brain.tick({});
    global.toolCall = orig;
    assert(r.delivered.startsWith("check_in(speak=ACCEPTED)"), r.delivered);
    assert.strictEqual(state.calls.filter((c) => c[0] === "tts").length, 0, "no double speech");
  });

  await check("judge verdict written in Beijing time is not read as 15 hours in the future", async () => {
    reset();
    NOW = Date.UTC(2026, 8, 28, 6, 0, 0); // 北京 14:00
    // 北京时间 12:00 写的一行（两小时前）；设备时区若按洛杉矶解析会变成未来
    FILES[`${R}/judge/20260928.jsonl`] = "2026-09-28 12:00:00\tDRIFT_RISK CONF=90 WHY=刷贴吧 NEXT=去投\n";
    const s = await brain.get_status();
    const v = s.status.verdict;
    assert(v.ts <= NOW, `verdict ts in the future: ${v.ts - NOW}`);
    assert(NOW - v.ts < 3 * 3600 * 1000, `verdict too old: ${(NOW - v.ts) / 3600000}h`);
  });

  console.log(failed ? `\n${failed} FAILED` : "\nall passed");
  process.exit(failed ? 1 : 0);
})();
