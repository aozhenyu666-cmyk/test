// Node harness: mocks the Operit host and exercises operit_audit against healthy and broken states.
const path = require("path");
const assert = require("assert");
const DIST = path.join(__dirname, "..", "dist");
const R = "/sdcard/Download/Operit";

let NOW = Date.UTC(2026, 9, 9, 6, 0, 0); // 北京 14:00
Date.now = () => NOW;
const MIN = 60000;
const bjIso = (ms) => {
  const t = new Date(ms + 8 * 3600 * 1000);
  const p = (n) => String(n).padStart(2, "0");
  return `${t.getUTCFullYear()}-${p(t.getUTCMonth() + 1)}-${p(t.getUTCDate())} ${p(t.getUTCHours())}:${p(t.getUTCMinutes())}:${p(t.getUTCSeconds())}`;
};

let FILES = {};
let WORKFLOWS = [];
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
global.Tools = {
  Files: {
    exists: async (p) => ({ exists: p in FILES }),
    readPart: async (p, s, e) => readPart(p, s, e),
    info: async (p) => (p in FILES ? { exists: true, lastModified: FILES[p + "::mtime"] ?? bjIso(NOW) } : { exists: false }),
  },
};
global.toolCall = async (name) => {
  if (name === "workflow:get_all_workflows") return { workflows: WORKFLOWS };
  throw new Error(`unexpected tool ${name}`);
};
global.ToolPkg = {};

// healthy baseline
function healthy() {
  FILES = {};
  WORKFLOWS = [
    { name: "P4_Event_Sampler", enabled: true, lastExecutionTime: NOW - 5 * MIN, lastExecutionStatus: "SUCCESS", totalExecutions: 800, failedExecutions: 100 },
    { name: "SCHED_Watchdog", enabled: true, lastExecutionTime: NOW - 10 * MIN, lastExecutionStatus: "SUCCESS", totalExecutions: 400, failedExecutions: 9 },
    { name: "G2_Judge_Flow", enabled: true, lastExecutionTime: NOW - 20 * MIN, lastExecutionStatus: "SUCCESS", totalExecutions: 400, failedExecutions: 40 },
    { name: "S4_Lock_Queue_Worker", enabled: true, lastExecutionTime: NOW - 8 * MIN, lastExecutionStatus: "SUCCESS", totalExecutions: 400, failedExecutions: 8 },
    { name: "BRAIN_Tick", enabled: true, lastExecutionTime: NOW - 7 * MIN, lastExecutionStatus: "SUCCESS", totalExecutions: 50, failedExecutions: 0 },
    { name: "OUTBOX_Carrier", enabled: true, lastExecutionTime: NOW - 10 * MIN, lastExecutionStatus: "SUCCESS", totalExecutions: 400, failedExecutions: 9 },
    { name: "S3_Brief_Loop", enabled: false, lastExecutionTime: NOW - 1000 * MIN, lastExecutionStatus: "SUCCESS", totalExecutions: 200, failedExecutions: 30 },
  ];
  FILES[`${R}/drift/task_state.txt`] = "STATE=ACTIVE\nTASK=求职投递\n";
  FILES[`${R}/drift/task_state.txt::mtime`] = bjIso(NOW - 10 * MIN);
  FILES[`${R}/companion/brain/state.json`] = "{}";
  FILES[`${R}/companion/brain/state.json::mtime`] = bjIso(NOW - 7 * MIN);
  FILES[`${R}/judge/.pack_last.txt`] = "pack";
  FILES[`${R}/judge/.pack_last.txt::mtime`] = bjIso(NOW - 20 * MIN);
  FILES[`${R}/drift/channel.txt`] = "title=陪伴窗\nmode=WINDOW\nchat_id=52a18815-2c07-4f6d-bbae-ae5d040daffb\n";
  FILES[`${R}/judge/save_log.tsv`] = `${bjIso(NOW - 40 * MIN)}\tGATE_SKIP\tx\n${bjIso(NOW - 20 * MIN)}\tSAVED\tverdict=ON_TRACK\tconf=80\n`;
  FILES[`${R}/events/lock_queue.txt`] = "";
}

const audit = require(path.join(DIST, "packages/operit_audit.js"));
let failed = 0;
async function check(name, fn) {
  try { await fn(); console.log(`ok   ${name}`); }
  catch (e) { failed++; console.log(`FAIL ${name}\n     ${e.message}`); }
}
const find = (r, id) => r.report.groups.flatMap((g) => g.checks).find((c) => c.id === id);

(async () => {
  await check("healthy system → overall PASS, no critical fail", async () => {
    healthy();
    const r = await audit.audit({});
    assert.strictEqual(r.success, true);
    assert.strictEqual(r.criticalFail, false, r.message);
    assert.strictEqual(r.overall, "PASS", r.message);
    assert.strictEqual(find(r, "wf.S3_Brief_Loop").verdict, "PASS"); // disabled as expected
  });

  await check("G2 judge chain dead (only GATE_SKIP) → FAIL + critical", async () => {
    healthy();
    FILES[`${R}/judge/save_log.tsv`] = `${bjIso(NOW - 300 * MIN)}\tSAVED\tverdict=ON_TRACK\n${bjIso(NOW - 10 * MIN)}\tGATE_SKIP\tx\n`;
    const r = await audit.audit({});
    assert.strictEqual(find(r, "exec.judge").verdict, "FAIL", r.message);
    assert.strictEqual(r.criticalFail, true);
    assert.strictEqual(r.overall, "FAIL");
  });

  await check("sampler stale (not run for 60min) → FAIL", async () => {
    healthy();
    WORKFLOWS.find((w) => w.name === "P4_Event_Sampler").lastExecutionTime = NOW - 60 * MIN;
    const r = await audit.audit({});
    assert.strictEqual(find(r, "wf.P4_Event_Sampler").verdict, "FAIL", r.message);
  });

  await check("critical workflow disabled unexpectedly → FAIL", async () => {
    healthy();
    WORKFLOWS.find((w) => w.name === "G2_Judge_Flow").enabled = false;
    const r = await audit.audit({});
    assert.strictEqual(find(r, "wf.G2_Judge_Flow").verdict, "FAIL", r.message);
  });

  await check("S3 unexpectedly re-enabled → WARN (not critical)", async () => {
    healthy();
    WORKFLOWS.find((w) => w.name === "S3_Brief_Loop").enabled = true;
    const r = await audit.audit({});
    assert.strictEqual(find(r, "wf.S3_Brief_Loop").verdict, "WARN", r.message);
  });

  await check("bad channel chat_id → FAIL", async () => {
    healthy();
    FILES[`${R}/drift/channel.txt`] = "mode=WINDOW\nchat_id=garbage\n";
    const r = await audit.audit({});
    assert.strictEqual(find(r, "file.channel").verdict, "FAIL", r.message);
  });

  await check("stuck lock-queue inflight → FAIL", async () => {
    healthy();
    FILES[`${R}/events/.lockq.inflight`] = `lock com.baidu.tieba ${Math.floor((NOW - 30 * MIN) / 1000)}\n`;
    const r = await audit.audit({});
    assert.strictEqual(find(r, "exec.lockqueue").verdict, "FAIL", r.message);
  });

  await check("missing critical state file → FAIL", async () => {
    healthy();
    delete FILES[`${R}/companion/brain/state.json`];
    const r = await audit.audit({});
    assert.strictEqual(find(r, "file.大脑状态").verdict, "FAIL", r.message);
  });

  await check("workflow list empty → FAIL + critical", async () => {
    healthy();
    WORKFLOWS = [];
    const r = await audit.audit({});
    assert.strictEqual(r.criticalFail, true);
    assert.strictEqual(find(r, "wf.fetch").verdict, "FAIL");
  });

  await check("self_test passes against intact detection logic", async () => {
    const r = await audit.self_test();
    assert.strictEqual(r.success, true);
    assert.strictEqual(r.verdict, "PASS", r.message);
    assert(r.report.passed >= 15, `only ${r.report.passed} cases`);
  });

  await check("audit with self_test=true includes both", async () => {
    healthy();
    const r = await audit.audit({ self_test: true });
    assert.strictEqual(r.selfTest, "PASS");
    assert(r.message.includes("自测") && r.message.includes("体检"), r.message.slice(0, 80));
  });

  await check("audit never calls a mutating tool", async () => {
    healthy();
    let calls = [];
    const orig = global.toolCall;
    global.toolCall = async (n, p) => { calls.push(n); return orig(n, p); };
    await audit.audit({});
    global.toolCall = orig;
    assert.deepStrictEqual([...new Set(calls)], ["workflow:get_all_workflows"], `called: ${calls}`);
  });

  console.log(failed ? `\n${failed} FAILED` : "\nall passed");
  process.exit(failed ? 1 : 0);
})();
