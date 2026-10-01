// 本地模拟 Operit 运行时（Tools.Files + complete）来跑 thought_relay.js。
// 用法：node test/run_local.mjs
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import vm from "node:vm";
import assert from "node:assert/strict";

const root = fs.mkdtempSync(path.join(os.tmpdir(), "thought_relay_"));
const map = (p) => path.join(root, p);
let clock = Date.parse("2026-10-01T09:00:00+08:00");
const RealDate = Date;
class FakeDate extends RealDate {
  constructor(...a) { super(...(a.length ? a : [clock])); }
  static now() { return clock; }
}
const Files = {
  async exists(p) { return { exists: fs.existsSync(map(p)) }; },
  async mkdir(p) { fs.mkdirSync(map(p), { recursive: true }); return { successful: true, details: "" }; },
  async read(p) { return { content: fs.readFileSync(map(p), "utf8") }; },
  async write(p, c, append) {
    fs.mkdirSync(path.dirname(map(p)), { recursive: true });
    append ? fs.appendFileSync(map(p), c) : fs.writeFileSync(map(p), c);
    return { successful: true, details: "" };
  },
};
let last;
const ctx = { Tools: { Files }, complete: (r) => (last = r), exports: {}, console, Date: FakeDate, Math, JSON };
vm.createContext(ctx);
const src = fs.readFileSync(new URL("../thought_relay.js", import.meta.url), "utf8");
vm.runInContext(src, ctx);
const call = async (name, params = {}) => { last = undefined; await ctx.exports[name](params); return last; };
const tick = (min) => (clock += min * 60000);
const show = (title, r) => console.log(`\n=== ${title} ===\n${r.message}`);

let r = await call("get_card");
assert.equal(r.success, true);

r = await call("open_thread", { question: "这个岗位要不要投？", next: "岗位职责里哪一条和我做过的事最接近？", materials: "要求一年经验|职责：数据整理、周报" });
assert.equal(r.success, true); show("开题", r);

r = await call("open_thread", { question: "另一个题", next: "x" });
assert.equal(r.success, false, "已有题目时应拒绝重开"); show("重开被拒", r);

tick(3);
r = await call("record_step", { text: "职责里的数据整理我实习做过", kind: "connect", help: 0, basis: "实习时每周整理销售数据", next: "经验要求一年，我的两段实习能不能算？", judgement: "暂时考虑投，因为职责符合", pending: "经验要求" });
assert.equal(r.success, true); show("第 1 步（独立）", r);

r = await call("record_step", { text: "x", kind: "bad", help: 0, next: "y" });
assert.equal(r.success, false, "非法 kind 应拒绝");

r = await call("park", { note: "查一下这家公司的评价" });
assert.equal(r.success, true); show("暂存", r);

tick(2);
r = await call("reminder_check", { min_interval_min: 30, quiet_min: 10 });
assert.equal(r.data.action, "SKIP"); assert.equal(r.data.reason, "recent_activity");

r = await call("pause", { reason: "leave", return_after_min: 60 });
show("暂停", r);
tick(40);
r = await call("reminder_check", {});
assert.equal(r.data.reason, "return_after_not_reached");
tick(25);
r = await call("reminder_check", {});
assert.equal(r.data.action, "REMIND"); show("提醒", r);
tick(35);
r = await call("reminder_check", {});
assert.equal(r.data.action, "REMIND"); assert.equal(r.data.unanswered_before, 1);

tick(5);
r = await call("resume", { mode: "brief" });
show("回来（brief）", r);
r = await call("resume", { mode: "help" });
assert.equal(r.data.hints, 1); show("帮我恢复", r);
tick(2);
r = await call("record_step", { text: "两段实习加起来 8 个月，可以写成项目经验", kind: "infer", help: 1, stuck: "forgot", next: "简历里哪一条能证明数据整理能力？", pending: "简历证明" });
show("恢复后第一步", r);

tick(1);
r = await call("set_next", { next: "简历第二段实习里有没有写周报？", reason: "问题太大，拆小", stuck: "connect" });
assert.equal(r.success, true);
tick(1);
r = await call("record_step", { text: "有，写了“每周汇总销售数据”", kind: "fact", help: 3, next: "这句够不够对上职责要求？" });

r = await call("stats", {});
show("统计", r);
assert.equal(r.data.steps, 3);
assert.deepEqual(JSON.parse(JSON.stringify(r.data.help)), { independent: 1, reminder: 1, starter: 0, demo: 1 });
assert.equal(r.data.recoveries.count, 1);
assert.equal(r.data.recoveries.avg_hints, 1);
assert.equal(r.data.recoveries.avg_min, 2);
assert.equal(r.data.silences, 1);
assert.equal(r.data.segments.max, 2);

r = await call("close_thread", { outcome: "投，简历补一句周报经历" });
show("结题", r);
r = await call("get_card");
assert.match(r.message, /当前没有/);

r = await call("main");
assert.equal(r.success, true, r.message);

// 坏行容错
const tdir = map("/sdcard/Download/Operit/thought_relay/threads");
const f = path.join(tdir, fs.readdirSync(tdir)[0]);
fs.appendFileSync(f, "{broken\n");
r = await call("stats", {});
assert.equal(r.data.bad_lines, 1);

console.log("\nALL TESTS PASSED");
fs.rmSync(root, { recursive: true, force: true });
