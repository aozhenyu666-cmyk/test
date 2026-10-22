// 端到端：在 Chromium 里加载真实的“伴生桥”扩展，打开模拟的 Gemini 页面，
// 检查伴生服务收到的事件（旧历史=backfill、流式只发一次完整回复、打字让出话权、浮层显示路标）。
//
//   python bansheng/scripts/run_standalone.py --mock --data <临时目录>   # 先启动伴生
//   node bansheng/tests/e2e_extension.mjs
//
// 这是模拟页面，不代表真实 Gemini 页面的结构没有变化。
import fs from "node:fs";
import { execSync } from "node:child_process";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

// 优先用本地安装的 playwright，否则用全局安装的
let chromium;
try { ({ chromium } = await import("playwright")); }
catch { ({ chromium } = createRequire(import.meta.url)(path.join(execSync("npm root -g").toString().trim(), "playwright"))); }

const here = path.dirname(fileURLToPath(import.meta.url));
const extDir = path.resolve(here, "../browser_extension");
const fixture = fs.readFileSync(path.join(here, "fixtures/gemini_sim.html"), "utf8");
const BASE = process.env.BANSHENG_URL || "http://127.0.0.1:48925";
const CONV = "simconv" + Date.now().toString(36);
const SID = `gemini:${CONV}`;

const userDir = fs.mkdtempSync(path.join(os.tmpdir(), "bansheng-e2e-"));
const ctx = await chromium.launchPersistentContext(userDir, {
  channel: "chromium",
  headless: true,
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: [`--disable-extensions-except=${extDir}`, `--load-extension=${extDir}`],
});
await ctx.route("https://gemini.google.com/**", (route) =>
  route.fulfill({ status: 200, contentType: "text/html; charset=utf-8", body: fixture,
    // 和真实 Gemini 一样要求 Trusted Types（页面主环境生效；扩展脚本本身不受限）
    headers: { "Content-Security-Policy": "require-trusted-types-for 'script'" } }));
const pageErrors = [];

const state = async () => (await fetch(`${BASE}/state?session_id=${encodeURIComponent(SID)}`)).json();
const ledger = async () => (await (await fetch(`${BASE}/ledger?n=300&session_id=${encodeURIComponent(SID)}`)).json()).records;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const checks = [];
const check = (name, ok, detail = "") => { checks.push({ name, ok }); console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`); };

const page = await ctx.newPage();
page.on("console", (m) => { if (m.type() === "error") pageErrors.push(m.text()); });
page.on("pageerror", (e) => pageErrors.push(String(e)));
await page.goto(`https://gemini.google.com/app/${CONV}`);
await sleep(4500); // 旧历史稳定 + 送达

let L = await ledger();
const hist = L.filter((r) => r.kind === "event");
check("页面已有的 2 条旧历史被送达且标为 backfill", hist.length === 2 && hist.every((r) => r.backfill), JSON.stringify(hist.map((r) => [r.speaker, r.backfill])));
check("旧历史没有触发思考", !L.some((r) => r.kind === "think_start"));

// 用户在输入框打字 → 伴生应收到“用户正在打字”
await page.click(".ql-editor");
await page.keyboard.type("我想要它像真的跟上我的家伙，可是做复杂了又怕做不出来");
const s1 = await state();
check("打字时话权显示为 user_typing", s1.floor.busy === "user_typing", String(s1.floor.busy));

const reply = "可以先搭一个后台管理系统，再加记忆服务和调度服务，最后做桌面端。这样每一块都能单独测试，后面也方便扩展到更多平台。";
await page.evaluate(([u, r]) => window.simSend(u, r, 300), ["我想要它像真的跟上我的家伙，可是做复杂了又怕做不出来", reply]);
await sleep(6000);

L = await ledger();
const live = L.filter((r) => r.kind === "event" && !r.backfill);
const mainFinal = live.filter((r) => r.speaker === "main_ai");
check("新的用户提问送达一次（非 backfill，对象=主力 AI）", live.filter((r) => r.speaker === "user").length === 1 && live.find((r) => r.speaker === "user")?.to === "main_ai");
check("流式回复只产生一条完整事件", mainFinal.length === 1, `got ${mainFinal.length}`);
const st = await state();
const lastMain = st.turns.filter((t) => t.speaker === "main_ai").pop();
check("完整事件是最终全文", lastMain && lastMain.text === reply);
check("回复完成后触发了一次思考", L.filter((r) => r.kind === "think_start").length === 1);
check("伴生做出了交付决定", st.recent_decisions.some((c) => c.status === "delivered"), JSON.stringify(st.recent_decisions.map((c) => [c.status, c.reason])));

// 浮层
const ov = await page.evaluate(() => {
  const host = [...document.documentElement.children].find((e) => e.shadowRoot && e.className === "bansheng-ignore");
  if (!host) return null;
  const g = (id) => host.shadowRoot.getElementById(id);
  return { topic: g("topic").textContent, confirmed: g("confirmed").textContent, open: g("open").textContent,
           say: g("say").style.display === "block" ? g("say").textContent : "", msg: g("msg").textContent };
});
check("页面浮层显示三行路标（主题来自对话）", ov && ov.topic && !ov.topic.includes("还在形成"), JSON.stringify(ov));
check("浮层显示伴生最近说的话", ov && ov.say.startsWith("伴生："), ov && ov.say);

// 刷新页面：不应重复送新消息，也不应再触发思考
await page.reload();
await sleep(4500);
const L2 = await ledger();
check("刷新后没有重复事件、没有新思考",
  L2.filter((r) => r.kind === "event").length === L.filter((r) => r.kind === "event").length &&
  L2.filter((r) => r.kind === "think_start").length === 1);

check("页面没有脚本错误（含 Trusted Types）", !pageErrors.some((e) => /TrustedHTML|Trusted Type|bansheng/i.test(e)), pageErrors.slice(0, 3).join(" | "));
await page.screenshot({ path: path.join(os.tmpdir(), "bansheng-e2e.png") });
await ctx.close();
const failed = checks.filter((c) => !c.ok).length;
console.log(`\n${checks.length - failed}/${checks.length} passed`);
process.exit(failed ? 1 : 0);
