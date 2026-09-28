import { enqueueLock, lookAtScreen, logAlertEvent, openApp, setAssignment, speak } from "./act.js";
import { Facts, appName, gatherFacts } from "./facts.js";
import { errorText, parseJsonl, tailLines } from "./fsx.js";
import {
  PATHS,
  SaidRecord,
  addCommitment,
  appendObservation,
  appendSaid,
  loadCommitments,
  loadConfig,
  loadProfile,
  loadState,
  logAction,
  recentSaid,
  saveState,
  setCommitmentStatus,
} from "./memory.js";
import { plan as makePlan } from "./rules.js";
import { think } from "./think.js";
import { bjDateKey, bjHM, bjParts, bjTimeToday, minutesAgoText } from "./time.js";
import { appendJsonl } from "./fsx.js";

export interface TickResult {
  status: string;
  stage: string;
  reason: string;
  line?: string;
  action?: string;
  delivered?: string;
  why?: string;
  error?: string;
}

// 用户自己报了"完成"：之后到点的承诺算兑现
async function settleCommitments(facts: Facts, now: number): Promise<void> {
  const open = (await loadCommitments()).filter((c) => c.status === "open");
  for (const c of open) {
    const done = facts.progress.find((p) => p.kind === "done" && p.ts > c.ts);
    if (done) await setCommitmentStatus(c.id, "kept", now, `TA 报了完成：${done.quote.slice(0, 40)}`);
  }
}

export async function tick(options: { force?: boolean } = {}): Promise<TickResult> {
  const now = Date.now();
  const cfg = await loadConfig();
  const state = await loadState();
  const facts = await gatherFacts(now);
  await settleCommitments(facts, now);
  const commitments = await loadCommitments();
  const open = commitments.filter((c) => c.status === "open");

  // 每晚 23 点后复盘一次（北京时间）
  const today = bjDateKey(now);
  if (bjParts(now).h >= 23 && state.last_reflect_date !== today) {
    try {
      await reflect(now);
    } catch (error) {
      await logAction({ ts: now, type: "REFLECT_FAIL", error: errorText(error) });
    }
    state.last_reflect_date = today;
  }

  const { plan, episode } = makePlan(facts, state, cfg, commitments, state.asked_commitments);
  state.episode = episode;

  if (plan.stage === "none" || !plan.canSpeak) {
    await saveState(state);
    return { status: plan.stage === "none" ? "IDLE" : "HOLD", stage: plan.stage, reason: plan.reason };
  }
  if (!options.force && plan.sig === state.last_sig && plan.stage !== "lock") {
    await saveState(state);
    return { status: "SAME_SITUATION", stage: plan.stage, reason: plan.reason };
  }

  if (plan.wantShot && cfg.act) {
    const fact = await lookAtScreen();
    state.last_shot_ts = now;
    if (fact) facts.shotFact = fact;
  }

  const profile = await loadProfile();
  const said = await recentSaid(8);
  const d = await think(facts, plan, profile, open, said);
  state.last_think_ts = now;
  state.last_sig = plan.sig;

  const results: string[] = [];
  let delivered = "dry-run";
  if (cfg.act) {
    if (d.action === "lock" && plan.lockPkg) {
      const r = await enqueueLock(plan.lockPkg, facts.protectedPkgs, facts.lockable);
      results.push(`lock ${plan.lockPkg}: ${r}`);
      if (r === "QUEUED") {
        state.locks[plan.lockPkg] = now;
        if (state.episode) state.episode.locked = true;
      }
    }
    if (d.speak && d.line) {
      const popup = ["ask", "lock", "commitment_due"].includes(plan.stage);
      const del = await speak(d.line, "小满", popup);
      delivered = del.via.join("+") + (del.errors.length ? ` | ${del.errors.join("; ")}` : "");
      await logAlertEvent(plan.stage, now);
    }
    if (d.action === "open_app" && d.openApp) results.push(`open ${d.openApp}: ${await openApp(d.openApp)}`);
    if (d.action === "assign" && d.assign) {
      const a = await setAssignment(d.assign.task, d.assign.minutes, now);
      await addCommitment({ text: d.assign.task, due_ts: a.deadline, quote: d.line, source: "assigned" }, now);
      results.push(`assign ${d.assign.task} → ${bjHM(a.deadline)}`);
    }
  }
  if (d.action === "record_commitment" && d.commitment) {
    await addCommitment({ text: d.commitment.text, due_ts: now + d.commitment.dueMinutes * 60000, quote: d.commitment.quote, source: "user" }, now);
    results.push(`commitment ${d.commitment.text}`);
  }
  if (d.memoryNote) await appendObservation([d.memoryNote], now);

  if (d.speak) {
    state.speaks = [...state.speaks.filter((t) => now - t < 3 * 3600 * 1000), now];
    if (state.episode && ["nudge", "ask", "lock"].includes(plan.stage)) {
      state.episode.nudges += 1;
      state.episode.first_nudge_ts = state.episode.first_nudge_ts ?? now;
      state.episode.last_nudge_ts = now;
    }
    if (plan.stage === "commitment_due") for (const c of plan.due) state.asked_commitments[c.id] = now;
    if (plan.stage === "checkin") state.last_idle_checkin_ts = now;
    await appendSaid({ ts: now, stage: plan.stage, line: d.line, action: d.action, pkg: plan.lockPkg || plan.driftPkg || undefined, why: d.why, delivered });
  }
  await logAction({ ts: now, type: "TICK", stage: plan.stage, reason: plan.reason, source: d.source, speak: d.speak, line: d.line, action: d.action, results, delivered, why: d.why, error: d.error ?? "" });
  await saveState(state);
  return { status: d.speak ? "SPOKE" : "DECIDED_SILENT", stage: plan.stage, reason: plan.reason, line: d.line, action: [d.action, ...results].join("; "), delivered, why: d.why, error: d.error };
}

// ---------- 小满·陪伴 在对话里记承诺 ----------

export async function recordCommitment(params: { text?: string; due_time?: string; due_minutes?: number | string; quote?: string }): Promise<string> {
  const now = Date.now();
  const text = String(params.text ?? "").trim();
  if (!text) throw new Error("缺少 text");
  let due: number | null = null;
  if (params.due_time) due = bjTimeToday(String(params.due_time), now);
  if (!due && params.due_minutes != null) {
    const m = Number(params.due_minutes);
    if (Number.isFinite(m) && m > 0) due = now + m * 60000;
  }
  if (!due) due = now + 60 * 60000;
  const c = await addCommitment({ text: text.slice(0, 60), due_ts: due, quote: String(params.quote ?? text).slice(0, 80), source: "user" }, now);
  return `已记下：${c.text}（北京时间 ${bjHM(c.due_ts)} 前）`;
}

// ---------- 注入到小满·陪伴每一轮对话的记忆 ----------

export async function memoryBlock(): Promise<string> {
  const now = Date.now();
  const [profile, commitments, said, state, facts] = await Promise.all([loadProfile(), loadCommitments(), recentSaid(3), loadState(), gatherFacts(now)]);
  const open = commitments.filter((c) => c.status === "open");
  const lines: string[] = [];
  lines.push(`【小满的记忆（系统自动附上，只供你参考，不要照念）】北京时间 ${bjHM(now)}`);
  lines.push("关于 TA：");
  lines.push(profile.slice(0, 1500));
  lines.push("TA 答应过、还没兑现的事：");
  lines.push(open.length ? open.slice(0, 5).map((c) => `- ${c.text}（${c.due_ts <= now ? "已到点" : `${bjHM(c.due_ts)} 前`}；原话「${c.quote}」）`).join("\n") : "- 没有");
  lines.push("你最近主动对 TA 说过的话：");
  lines.push(said.length ? said.map((s: SaidRecord) => `- ${minutesAgoText(s.ts, now)}：${s.line}`).join("\n") : "- 没有");
  lines.push(`TA 最近报的进展：${facts.progress.slice(0, 3).map((p) => `${minutesAgoText(p.ts, now)}「${p.quote}」`).join("；") || "没有"}`);
  if (state.episode) lines.push(`现在：TA 在刷${appName(state.episode.pkg)}，你已经提醒过 ${state.episode.nudges} 次${state.episode.locked ? "，已按约定暂停了它" : ""}。`);
  lines.push("TA 在对话里说出带时间的打算（比如「一点前投一家」）时，用 companion_brain:record_commitment 记下来；说到进展时照旧用 report_progress。");
  return lines.join("\n");
}

// ---------- 每晚复盘 ----------

export async function reflect(now: number): Promise<string> {
  const today = bjDateKey(now);
  const said = parseJsonl<SaidRecord>(await tailLines(PATHS.said, 80)).filter((s) => bjDateKey(s.ts) === today);
  const facts = await gatherFacts(now);
  const todays = facts.progress.filter((p) => bjDateKey(p.ts) === today);
  const commitments = await loadCommitments();

  // 过期 2 小时仍未兑现的承诺记为未兑现（代码判，不靠模型）
  for (const c of commitments) {
    if (c.status === "open" && now - c.due_ts > 2 * 3600 * 1000) await setCommitmentStatus(c.id, "broken", now, "过期未报完成");
  }

  const timeline = [
    ...said.map((s) => ({ ts: s.ts, text: `小满（${s.stage}）：${s.line}` })),
    ...todays.map((p) => ({ ts: p.ts, text: `TA（${p.kind}）：${p.quote}` })),
  ]
    .sort((a, b) => a.ts - b.ts)
    .map((x) => `${bjHM(x.ts)} ${x.text}`)
    .join("\n");
  if (!timeline.trim()) return "今天没有可复盘的对话";

  const kept = commitments.filter((c) => bjDateKey(c.ts) === today).map((c) => `${c.text}：${c.status}`).join("；") || "无";
  const prompt = `下面是今天小满主动说的话和 TA 的回应（北京时间）。请复盘：哪种说法换来了 TA 的回应或行动，哪种被无视；TA 今天的状态规律。
只输出 JSON：{"observations": ["最多 4 条，每条一句，具体、可用于明天调整说法"], "worked": ["管用的说法特征"], "ignored": ["被无视的说法特征"]}

${timeline}

今天的承诺：${kept}`;
  let parsed: { observations?: string[]; worked?: string[]; ignored?: string[] } = {};
  try {
    const r = await Tools.Chat.call({ functionType: "SUMMARY", turns: [{ kind: "USER", content: prompt }], enableThinking: false });
    const text = String(r?.text ?? "");
    const s = text.indexOf("{");
    const e = text.lastIndexOf("}");
    parsed = s >= 0 && e > s ? JSON.parse(text.slice(s, e + 1)) : {};
  } catch (error) {
    await logAction({ ts: now, type: "REFLECT_MODEL_FAIL", error: errorText(error) });
  }
  const notes = [
    ...(parsed.observations ?? []),
    ...(parsed.worked ?? []).map((w) => `管用：${w}`),
    ...(parsed.ignored ?? []).map((w) => `被无视：${w}`),
  ];
  await appendObservation(notes, now);
  await appendJsonl(PATHS.reflections, { ts: now, date: today, notes, said: said.length, progress: todays.length });
  return notes.length ? notes.join("\n") : "复盘没有产出";
}

export async function status(): Promise<Record<string, unknown>> {
  const now = Date.now();
  const cfg = await loadConfig();
  const state = await loadState();
  const facts = await gatherFacts(now);
  const commitments = await loadCommitments();
  const { plan } = makePlan(facts, state, cfg, commitments, state.asked_commitments);
  return {
    now: bjHM(now),
    config: cfg,
    plan: { stage: plan.stage, reason: plan.reason, allowed: plan.allowed, canSpeak: plan.canSpeak, lockPkg: plan.lockPkg },
    episode: state.episode,
    speaksLastHour: state.speaks.filter((t) => now - t < 3600 * 1000).length,
    openCommitments: commitments.filter((c) => c.status === "open"),
    recentSaid: await recentSaid(5),
    latestSample: facts.samples[0] ?? null,
    verdict: facts.verdict,
  };
}

// ---------- 演练：用模拟场景跑一遍"读取 → 规则 → 思考 → 行动"，不写记忆、不改状态 ----------

export type DrillScenario = "nudge" | "ask" | "lock" | "pause" | "commitment" | "quiet" | "working";

export async function drill(scenario: DrillScenario, options: { execute?: boolean; app?: string } = {}): Promise<Record<string, unknown>> {
  const MIN = 60000;
  const cfg = await loadConfig();
  const real = await loadState();
  let now = Date.now();
  if (scenario === "quiet") {
    // 挪到北京时间当天 00:30（安静时段）
    const p = bjParts(now);
    now = Date.UTC(p.y, p.mo - 1, p.d, 0, 30) - 8 * 3600 * 1000 + 24 * 3600 * 1000;
  }
  const facts = await gatherFacts(now);
  facts.now = now;
  const app = options.app || "com.baidu.tieba";
  const off = (m: number) => ({ ts: now - m * MIN, pkg: app, ontask: "0", screen: "ON" });
  facts.samples = scenario === "working" ? [{ ts: now - 5 * MIN, pkg: facts.jobApps[0] ?? "com.hpbr.bosszhipin", ontask: "1", screen: "ON" }] : [off(3), off(18), off(33)];
  facts.otherAlerts = [];
  facts.progress = scenario === "pause" ? [{ ts: now - 5 * MIN, kind: "pause", quote: "我去吃个饭，一小时后开始", note: "", via: "drill" }] : [];

  const state = { ...real, speaks: [] as number[], last_sig: "", locks: {} as Record<string, number>, asked_commitments: {} as Record<string, number> };
  state.episode =
    scenario === "ask"
      ? { start_ts: now - 33 * MIN, pkg: app, nudges: 1, first_nudge_ts: now - 12 * MIN, last_nudge_ts: now - 12 * MIN, locked: false }
      : scenario === "lock" || scenario === "quiet"
        ? { start_ts: now - 50 * MIN, pkg: app, nudges: 2, first_nudge_ts: now - 35 * MIN, last_nudge_ts: now - 12 * MIN, locked: false }
        : null;

  const commitments =
    scenario === "commitment"
      ? [{ id: "C-drill", ts: now - 60 * MIN, text: "投一家", due_ts: now - 2 * MIN, quote: "三点前我投一家", source: "user" as const, status: "open" as const }]
      : [];
  if (scenario === "commitment") facts.samples = [{ ts: now - 5 * MIN, pkg: "none", ontask: "NONE", screen: "OFF" }];

  const { plan } = makePlan(facts, state, cfg, commitments, {});
  const out: Record<string, unknown> = {
    scenario,
    note: "演练：事实是模拟的，规则层和模型是真的；不写记忆、不改大脑状态",
    plan: { stage: plan.stage, reason: plan.reason, allowed: plan.allowed, canSpeak: plan.canSpeak, lockPkg: plan.lockPkg },
  };
  if (plan.stage === "none" || !plan.canSpeak) return { ...out, decision: "不开口" };

  const d = await think(facts, plan, await loadProfile(), commitments, await recentSaid(5));
  out.decision = { speak: d.speak, line: d.line, action: d.action, openApp: d.openApp, assign: d.assign, commitment: d.commitment, why: d.why, source: d.source, error: d.error ?? "" };

  const done: string[] = [];
  if (options.execute) {
    if (d.action === "lock" && plan.lockPkg) done.push(`lock ${plan.lockPkg}: ${await enqueueLock(plan.lockPkg, facts.protectedPkgs, facts.lockable)}`);
    if (d.speak && d.line) {
      const del = await speak(d.line, "小满", ["ask", "lock", "commitment_due"].includes(plan.stage));
      done.push(`speak: ${del.via.join("+")}${del.errors.length ? ` | ${del.errors.join("; ")}` : ""}`);
    }
    if (d.action === "open_app" && d.openApp) done.push(`open ${d.openApp}: ${await openApp(d.openApp)}`);
    if (d.action === "assign" && d.assign) done.push(`assign（演练不写派活文件）：${d.assign.task} ${d.assign.minutes} 分钟`);
  }
  out.executed = options.execute ? done : "没有执行（execute=false）";
  await logAction({ ts: Date.now(), type: "DRILL", scenario, stage: plan.stage, line: d.line, action: d.action, executed: done });
  return out;
}
