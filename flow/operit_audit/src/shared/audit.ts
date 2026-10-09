import {
  CheckResult,
  Verdict,
  channelHealth,
  failureRate,
  fileFreshness,
  judgeHealth,
  lockQueueHealth,
  workflowFreshness,
  worst,
} from "./detect.js";
import { exists, errorText, readJson, readLines, tailLines } from "./fsx.js";
import { AuditConfig, ROOT, loadConfig } from "./spec.js";
import { parseScriptTime } from "./time.js";

interface RawWorkflow {
  name?: string;
  enabled?: boolean;
  lastExecutionTime?: number | null;
  lastExecutionStatus?: string | null;
  totalExecutions?: number;
  failedExecutions?: number;
}

async function callTool(name: string, params: Record<string, string | number | boolean | object> = {}): Promise<any> {
  return await toolCall(name, params);
}

async function getWorkflows(): Promise<RawWorkflow[]> {
  try {
    const r = await callTool("workflow:get_all_workflows", {});
    const list = r?.workflows ?? r?.result?.workflows ?? (Array.isArray(r) ? r : []);
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

async function kvFile(path: string): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  for (const line of await readLines(path)) {
    const m = /^([A-Za-z_]+)\s*=\s*(.*)$/.exec(line.trim());
    if (m) out[m[1]] = m[2].replace(/#.*$/, "").trim();
  }
  return out;
}

async function fileMtime(path: string): Promise<{ exists: boolean; mtime: number | null }> {
  try {
    if (!(await exists(path))) return { exists: false, mtime: null };
    const info = await Tools.Files.info(path);
    return { exists: true, mtime: parseScriptTime((info as any)?.lastModified, Date.now()) };
  } catch {
    return { exists: false, mtime: null };
  }
}

export interface AuditReport {
  ts: number;
  overall: Verdict;
  criticalFail: boolean;
  groups: { group: string; checks: CheckResult[] }[];
  summary: { pass: number; warn: number; fail: number; skip: number };
}

export async function runAudit(): Promise<AuditReport> {
  const now = Date.now();
  const cfg: AuditConfig = await loadConfig();
  const groups: { group: string; checks: CheckResult[] }[] = [];
  const criticalFails: string[] = [];

  // 1) 工作流：启用状态、调度新鲜度、失败率
  const workflows = await getWorkflows();
  const byName = new Map(workflows.map((w) => [String(w.name ?? ""), w]));
  const wfChecks: CheckResult[] = [];
  if (workflows.length === 0) {
    wfChecks.push({ id: "wf.fetch", title: "读取工作流列表", verdict: "FAIL", detail: "get_all_workflows 没返回数据" });
    criticalFails.push("无法读取工作流列表");
  }
  for (const spec of cfg.workflows) {
    const w = byName.get(spec.name);
    if (!w) {
      const v: Verdict = spec.expectEnabled ? (spec.critical ? "FAIL" : "WARN") : "SKIP";
      wfChecks.push({ id: `wf.${spec.name}`, title: spec.name, verdict: v, detail: v === "SKIP" ? "不存在（预期如此）" : "工作流不存在" });
      if (v === "FAIL" && spec.critical) criticalFails.push(`${spec.name} 不存在`);
      continue;
    }
    const enabled = w.enabled === true;
    if (enabled !== spec.expectEnabled) {
      const v: Verdict = spec.critical ? "FAIL" : "WARN";
      wfChecks.push({ id: `wf.${spec.name}`, title: spec.name, verdict: v, detail: `期望${spec.expectEnabled ? "启用" : "停用"}，实际${enabled ? "启用" : "停用"}` });
      if (v === "FAIL") criticalFails.push(`${spec.name} 开关不符`);
      continue;
    }
    if (!spec.expectEnabled) {
      wfChecks.push({ id: `wf.${spec.name}`, title: spec.name, verdict: "PASS", detail: "已按预期停用" });
      continue;
    }
    const fresh = workflowFreshness(now, enabled, w.lastExecutionTime ?? null, w.lastExecutionStatus ?? null, spec.cadenceMin, w.totalExecutions ?? 0);
    const rate = failureRate(w.totalExecutions ?? 0, w.failedExecutions ?? 0);
    const v = worst([fresh.verdict, rate.verdict]);
    wfChecks.push({ id: `wf.${spec.name}`, title: spec.name, verdict: v, detail: `${fresh.detail}；${rate.detail}` });
    if (v === "FAIL" && spec.critical) criticalFails.push(`${spec.name}：${fresh.detail}`);
  }
  groups.push({ group: "工作流", checks: wfChecks });

  // 2) 状态文件新鲜度
  const fileChecks: CheckResult[] = [];
  for (const fspec of cfg.files) {
    const { exists: ex, mtime } = await fileMtime(fspec.path);
    const r = fileFreshness(now, ex, mtime, fspec.maxAgeMin, fspec.label);
    fileChecks.push({ id: `file.${fspec.label}`, title: fspec.label, verdict: r.verdict, detail: r.detail });
    if (r.verdict === "FAIL" && fspec.critical) criticalFails.push(`${fspec.label}：${r.detail}`);
  }
  // channel.txt 内容校验
  const ch = channelHealth(await kvFile(`${ROOT}/drift/channel.txt`));
  fileChecks.push({ id: "file.channel", title: "提醒通道配置", verdict: ch.verdict, detail: ch.detail });
  if (ch.verdict === "FAIL") criticalFails.push(`提醒通道：${ch.detail}`);
  groups.push({ group: "状态文件", checks: fileChecks });

  // 3) 判断链是否还在出结论（save_log.tsv）
  const execChecks: CheckResult[] = [];
  const saveLines = (await tailLines(`${ROOT}/judge/save_log.tsv`, 40)).map((line) => {
    const [time, kind = ""] = line.split("\t");
    return { ts: parseScriptTime(time, now), kind: kind.trim() };
  });
  const jh = judgeHealth(now, saveLines, cfg.judgeCadenceMin);
  execChecks.push({ id: "exec.judge", title: "判断官是否在出结论", verdict: jh.verdict, detail: jh.detail });
  if (jh.verdict === "FAIL") criticalFails.push(`判断链：${jh.detail}`);

  // 锁队列是否卡住
  const inflightRaw = (await readLines(`${ROOT}/events/.lockq.inflight`))[0]?.trim() ?? "";
  const inflightParts = inflightRaw.split(/\s+/);
  const inflight = inflightParts.length >= 3 ? { act: inflightParts[0], pkg: inflightParts[1], ts: (Number(inflightParts[2]) || 0) * 1000 } : null;
  const queueLen = (await readLines(`${ROOT}/events/lock_queue.txt`)).filter((l) => /^(lock|unlock)\s/.test(l.trim())).length;
  const lq = lockQueueHealth(now, inflight, queueLen);
  execChecks.push({ id: "exec.lockqueue", title: "锁队列", verdict: lq.verdict, detail: lq.detail });
  if (lq.verdict === "FAIL") criticalFails.push(`锁队列：${lq.detail}`);
  groups.push({ group: "最近执行", checks: execChecks });

  const all = groups.flatMap((g) => g.checks);
  const summary = {
    pass: all.filter((c) => c.verdict === "PASS").length,
    warn: all.filter((c) => c.verdict === "WARN").length,
    fail: all.filter((c) => c.verdict === "FAIL").length,
    skip: all.filter((c) => c.verdict === "SKIP").length,
  };
  return { ts: now, overall: worst(all.map((c) => c.verdict)), criticalFail: criticalFails.length > 0, groups, summary };
}

const ICON: Record<Verdict, string> = { PASS: "✅", WARN: "⚠️", FAIL: "❌", SKIP: "⏭️" };

export function renderReport(r: AuditReport): string {
  const lines: string[] = [];
  lines.push(`# Operit 体检 ${ICON[r.overall]} ${r.overall}`);
  lines.push(`通过 ${r.summary.pass} · 警告 ${r.summary.warn} · 失败 ${r.summary.fail} · 跳过 ${r.summary.skip}`);
  if (r.criticalFail) lines.push("⚠️ 有关键链失败，需要处理");
  for (const g of r.groups) {
    lines.push(`\n## ${g.group}`);
    for (const c of g.checks) lines.push(`${ICON[c.verdict]} ${c.title}：${c.detail}`);
  }
  return lines.join("\n");
}

export async function auditSummary(): Promise<{ report: AuditReport; text: string }> {
  try {
    const report = await runAudit();
    return { report, text: renderReport(report) };
  } catch (error) {
    throw new Error(errorText(error));
  }
}
