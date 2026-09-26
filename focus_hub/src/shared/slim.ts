// 省流：量出每次对话带着多少上下文、钱花在哪，只提供能撤销的精简动作。
// 不删任何东西；每个改动都先把原值写进 slim/actions.jsonl，可以一键恢复。
import { isPinned, listChats, type ChatEntry } from "./nav.js";
import { findCompanion } from "./companion.js";
import { parseLastModified } from "./health.js";
import { fileExists, formatDateTime, loadSchedule, readAll } from "./snapshot.js";

const ROOT = "/sdcard/Download/Operit";
export const SLIM_DIR = `${ROOT}/companion/slim`;
const TOKEN_LOG = `${SLIM_DIR}/token_snapshots.jsonl`;
const ACTION_LOG = `${SLIM_DIR}/actions.jsonl`;

// 两次记账至少隔这么久，文件一天最多十几行
const SNAPSHOT_EVERY_MS = 2 * 3600 * 1000;
const SNAPSHOT_TOP = 20;
const TOP_CHATS = 8;
const SUMMARY_PROBE = 6;
const STALE_DAYS = 7;

// 按 Operit 1.12.2 源码估算：中文内置工具说明约 5700 字（文件、网页、记忆等），开着工具时每轮都带
export const BUILTIN_TOOL_TOKENS = 2600;

// 花火那种陪伴角色只需要这几个包
const COMPANION_PACKAGES = ["focus_hub_data", "focus_hub_progress", "focus_hub_nav", "voice_bar"];

function errorText(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return String(error);
}

// 粗估：英文约 4 个字符 1 token，中文约 1 个字 0.7 token
export function estimateTokens(text: string): number {
  let ascii = 0;
  let other = 0;
  for (const ch of String(text ?? "")) {
    if (ch.charCodeAt(0) < 128) ascii += 1;
    else other += 1;
  }
  return Math.round(ascii / 4 + other * 0.7);
}

export function formatTokens(n: number): string {
  if (n >= 1e8) return `${(n / 1e8).toFixed(1)} 亿`;
  if (n >= 1e4) return `${(n / 1e4).toFixed(n >= 1e6 ? 0 : 1)} 万`;
  return String(Math.round(n));
}

// ---------- 会话账单 ----------

export interface ChatCost {
  id: string;
  title: string;
  card: string;
  messages: number;
  input: number;
  output: number;
  perMessage: number;
  growth: number | null;
  backstage: boolean;
  isHer: boolean;
  // null = 从没压缩过；undefined = 没查到
  lastSummaryAt?: number | null;
}

interface TokenSnapshot {
  ts: number;
  chats: Record<string, number>;
}

async function readTokenSnapshots(): Promise<TokenSnapshot[]> {
  if (!(await fileExists(TOKEN_LOG))) return [];
  const { lines } = await readAll(TOKEN_LOG, 40);
  const out: TokenSnapshot[] = [];
  for (const line of lines) {
    try {
      const parsed = JSON.parse(line) as TokenSnapshot;
      if (parsed && typeof parsed.ts === "number" && parsed.chats) out.push(parsed);
    } catch {
      // 跳过坏行
    }
  }
  return out;
}

// 优先取 24 小时前后的那次记账；没有就用最早的一次（至少一小时前）
function pickBaseline(snaps: TokenSnapshot[], now: number): TokenSnapshot | null {
  const nowSec = Math.floor(now / 1000);
  const old = snaps.filter((s) => nowSec - s.ts >= 3600);
  if (old.length === 0) return null;
  const target = nowSec - 24 * 3600;
  let best = old[0];
  for (const s of old) {
    if (Math.abs(s.ts - target) < Math.abs(best.ts - target)) best = s;
  }
  return best;
}

async function probeLastSummary(chatId: string): Promise<number | null | undefined> {
  try {
    const ChatHistoryManager = Java.com.ai.assistance.operit.data.repository.ChatHistoryManager;
    const manager = ChatHistoryManager.getInstance(Java.getApplicationContext());
    const value = await manager.callSuspend("getLatestSummaryTimestamp", chatId);
    if (value == null) return null;
    const n = Number(value);
    return Number.isFinite(n) && n > 0 ? n : null;
  } catch {
    return undefined;
  }
}

export interface ChatBill {
  rows: ChatCost[];
  totalChats: number;
  totalInput: number;
  baselineAt: number | null;
  grownSince: number | null;
  idleChats: number;
}

async function loadChatBill(now: number): Promise<ChatBill> {
  const chats = await listChats("");
  const companion = await findCompanion(chats).catch(() => null);
  const herId = companion?.chat?.id ?? "";
  const snaps = await readTokenSnapshots();
  const baseline = pickBaseline(snaps, now);

  const byInput = chats.slice().sort((a, b) => b.inputTokens - a.inputTokens);
  const rows: ChatCost[] = byInput.slice(0, TOP_CHATS).map((c: ChatEntry) => {
    const before = baseline?.chats[c.id];
    return {
      id: c.id,
      title: c.title,
      card: c.characterCardName,
      messages: c.messageCount,
      input: c.inputTokens,
      output: c.outputTokens,
      perMessage: c.messageCount > 0 ? Math.round(c.inputTokens / c.messageCount) : 0,
      growth: baseline ? c.inputTokens - (before ?? 0) : null,
      backstage: isPinned(c),
      isHer: c.id === herId,
    };
  });
  for (const row of rows.slice(0, SUMMARY_PROBE)) {
    row.lastSummaryAt = await probeLastSummary(row.id);
  }

  const totalInput = chats.reduce((a, c) => a + c.inputTokens, 0);
  let grownSince: number | null = null;
  if (baseline) {
    grownSince = chats.reduce((a, c) => a + Math.max(0, c.inputTokens - (baseline.chats[c.id] ?? 0)), 0);
  }
  const idleCutoff = now - STALE_DAYS * 24 * 3600 * 1000;
  const idleChats = chats.filter((c) => {
    const t = /^\d+$/.test(c.updatedAt) ? Number(c.updatedAt) : Date.parse(c.updatedAt);
    return Number.isFinite(t) && t < idleCutoff && !isPinned(c) && c.id !== herId;
  }).length;

  // 记一笔账，下次就能算出这段时间谁在涨
  const last = snaps[snaps.length - 1];
  if (!last || now - last.ts * 1000 >= SNAPSHOT_EVERY_MS) {
    const top: Record<string, number> = {};
    for (const c of byInput.slice(0, SNAPSHOT_TOP)) top[c.id] = c.inputTokens;
    try {
      await Tools.Files.write(TOKEN_LOG, JSON.stringify({ ts: Math.floor(now / 1000), chats: top }) + "\n", true);
    } catch {
      // 记不上只影响下次的增长数字
    }
  }

  return { rows, totalChats: chats.length, totalInput, baselineAt: baseline ? baseline.ts * 1000 : null, grownSince, idleChats };
}

// ---------- 每轮都带的：工具包清单 ----------

export interface PackageCost {
  name: string;
  displayName: string;
  tokens: number;
  toolCount: number;
  builtIn: boolean;
  enabled: boolean;
  usedBy: string[];
}

export interface PackageBill {
  enabled: PackageCost[];
  disabledByHub: PackageCost[];
  listTokens: number;
}

async function workflowTexts(): Promise<{ name: string; enabled: boolean; text: string; id: string }[]> {
  const list = await Tools.Workflow.getAll();
  const out: { name: string; enabled: boolean; text: string; id: string }[] = [];
  for (const wf of list.workflows ?? []) {
    try {
      const detail = await Tools.Workflow.get(wf.id);
      out.push({ id: wf.id, name: wf.name, enabled: Boolean(wf.enabled), text: JSON.stringify(detail.nodes ?? []) });
    } catch {
      out.push({ id: wf.id, name: wf.name, enabled: Boolean(wf.enabled), text: "" });
    }
  }
  return out;
}

async function loadPackageBill(flows: { name: string; enabled: boolean; text: string }[], hubDisabled: Set<string>): Promise<PackageBill> {
  const result = await Tools.SoftwareSettings.listSandboxPackages();
  const rows: PackageCost[] = (result.packages ?? []).map((p) => ({
    name: p.packageName,
    displayName: p.displayName || p.packageName,
    tokens: estimateTokens(`- ${p.packageName} : ${p.description ?? ""}\n`),
    toolCount: p.toolCount ?? 0,
    builtIn: Boolean(p.isBuiltIn),
    enabled: Boolean(p.enabled),
    usedBy: flows.filter((f) => f.enabled && f.text.includes(`${p.packageName}:`)).map((f) => f.name),
  }));
  const enabled = rows.filter((r) => r.enabled).sort((a, b) => b.tokens - a.tokens);
  return {
    enabled,
    disabledByHub: rows.filter((r) => !r.enabled && hubDisabled.has(r.name)),
    listTokens: enabled.reduce((a, r) => a + r.tokens, 0),
  };
}

// ---------- 角色卡 ----------

export interface ToolAccess {
  enabled: boolean;
  allowedBuiltinTools: string[];
  allowedPackages: string[];
  allowedSkills: string[];
  allowedMcpServers: string[];
}

export interface CardCost {
  id: string;
  name: string;
  promptTokens: number;
  access: ToolAccess;
  chats: number;
  hasBackup: boolean;
}

function accessOf(raw: Partial<ToolAccess> | null | undefined): ToolAccess {
  return {
    enabled: Boolean(raw?.enabled),
    allowedBuiltinTools: (raw?.allowedBuiltinTools ?? []).slice(),
    allowedPackages: (raw?.allowedPackages ?? []).slice(),
    allowedSkills: (raw?.allowedSkills ?? []).slice(),
    allowedMcpServers: (raw?.allowedMcpServers ?? []).slice(),
  };
}

async function loadCards(chatsByCard: Record<string, number>, backups: Set<string>): Promise<CardCost[]> {
  const result = await Tools.SoftwareSettings.listCharacterCards();
  return (result.cards ?? [])
    .map((c) => ({
      id: c.id,
      name: c.name,
      promptTokens: estimateTokens([c.description, c.characterSetting, c.otherContentChat, c.advancedCustomPrompt].join("\n")),
      access: accessOf(c.toolAccessConfig),
      chats: chatsByCard[c.name] ?? 0,
      hasBackup: backups.has(c.id),
    }))
    .sort((a, b) => b.chats - a.chats || b.promptTokens - a.promptTokens);
}

export function describeAccess(access: ToolAccess): string {
  if (!access.enabled) return "全部工具";
  const parts = [`内置 ${access.allowedBuiltinTools.length}`, `包 ${access.allowedPackages.length}`];
  if (access.allowedSkills.length) parts.push(`技能 ${access.allowedSkills.length}`);
  if (access.allowedMcpServers.length) parts.push(`MCP ${access.allowedMcpServers.length}`);
  return `白名单（${parts.join(" · ")}）`;
}

// ---------- 自动总结 ----------

// 宿主口径：contextLength 以 K tokens 计（默认 64），summaryTokenThreshold 是占上下文的比例（默认 0.7）
export interface SummaryConfig {
  configName: string;
  enableSummary: boolean;
  tokenThreshold: number;
  byMessageCount: boolean;
  messageThreshold: number;
  contextLength: number;
}

// 不触发总结时，一次请求最多能带多少 tokens
export function summaryCeiling(s: SummaryConfig): number | null {
  if (!s.contextLength) return null;
  const ratio = s.enableSummary && s.tokenThreshold > 0 && s.tokenThreshold <= 1 ? s.tokenThreshold : 1;
  return Math.round(s.contextLength * 1000 * ratio);
}

export function describeSummary(s: SummaryConfig): string {
  const ctx = s.contextLength ? `上下文 ${s.contextLength}K` : "上下文未知";
  if (!s.enableSummary) return `${ctx}，自动总结关着`;
  const parts = [s.tokenThreshold > 0 && s.tokenThreshold <= 1 ? `用到 ${Math.round(s.tokenThreshold * 100)}% 时总结` : "按 token 总结"];
  if (s.byMessageCount && s.messageThreshold > 0) parts.push(`或每 ${s.messageThreshold} 条总结`);
  return `${ctx}，${parts.join("，")}`;
}

async function loadSummaryConfig(): Promise<SummaryConfig | null> {
  const binding = await Tools.SoftwareSettings.getFunctionModelConfig("CHAT");
  const all = await Tools.SoftwareSettings.listModelConfigs();
  const config = (all.configs ?? []).find((c) => c.id === binding.configId);
  if (!config) return null;
  return {
    configName: config.name ?? binding.configName ?? binding.configId,
    enableSummary: Boolean(config.enableSummary),
    tokenThreshold: Number(config.summaryTokenThreshold) || 0,
    byMessageCount: Boolean(config.enableSummaryByMessageCount),
    messageThreshold: Number(config.summaryMessageCountThreshold) || 0,
    contextLength: Number(config.contextLength) || 0,
  };
}

// ---------- 撤销记录 ----------

export interface SlimAction {
  ts: number;
  iso: string;
  kind: "package" | "card";
  target: string;
  label: string;
  before: unknown;
  after: unknown;
  ok: boolean;
  detail: string;
}

export async function readActions(limit = 40): Promise<SlimAction[]> {
  if (!(await fileExists(ACTION_LOG))) return [];
  const { lines } = await readAll(ACTION_LOG, limit);
  const out: SlimAction[] = [];
  for (const line of lines) {
    try {
      out.push(JSON.parse(line) as SlimAction);
    } catch {
      // 跳过坏行
    }
  }
  return out;
}

async function logAction(action: Omit<SlimAction, "ts" | "iso">): Promise<void> {
  const now = Date.now();
  const record: SlimAction = { ts: Math.floor(now / 1000), iso: formatDateTime(now), ...action };
  await Tools.Files.write(ACTION_LOG, JSON.stringify(record) + "\n", true);
}

// 当前处于"被主控台停用"状态的包：最后一次动作是停用
function hubDisabledPackages(actions: SlimAction[]): Set<string> {
  const last = new Map<string, SlimAction>();
  for (const a of actions) if (a.kind === "package" && a.ok) last.set(a.target, a);
  return new Set([...last.values()].filter((a) => a.after === false).map((a) => a.target));
}

function cardBackups(actions: SlimAction[]): Map<string, ToolAccess> {
  const out = new Map<string, ToolAccess>();
  for (const a of actions) {
    if (a.kind !== "card" || !a.ok) continue;
    if (a.label === "恢复") out.delete(a.target);
    else if (!out.has(a.target)) out.set(a.target, accessOf(a.before as ToolAccess));
  }
  return out;
}

export async function setPackageEnabled(name: string, enabled: boolean): Promise<string> {
  const result = await Tools.SoftwareSettings.setSandboxPackageEnabled(name, enabled);
  const ok = result.currentEnabled === enabled;
  await logAction({
    kind: "package",
    target: name,
    label: enabled ? "启用" : "停用",
    before: result.previousEnabled,
    after: result.currentEnabled,
    ok,
    detail: result.message ?? "",
  });
  if (!ok) throw new Error(result.message || `宿主回读仍是 ${result.currentEnabled ? "启用" : "停用"}`);
  return enabled ? `已重新启用 ${name}` : `已停用 ${name}（可在这里恢复）`;
}

export type CardPreset = "chat_only" | "companion" | "restore";

export const PRESET_LABEL: Record<CardPreset, string> = {
  chat_only: "只聊天",
  companion: "陪伴",
  restore: "恢复",
};

function toWriteOptions(access: ToolAccess) {
  return {
    tool_access_enabled: access.enabled,
    allowed_builtin_tools: access.allowedBuiltinTools,
    allowed_packages: access.allowedPackages,
    allowed_skills: access.allowedSkills,
    allowed_mcp_servers: access.allowedMcpServers,
  };
}

export async function applyCardPreset(cardId: string, preset: CardPreset): Promise<string> {
  const current = await Tools.SoftwareSettings.getCharacterCard(cardId);
  const before = accessOf(current.card.toolAccessConfig);
  let target: ToolAccess;
  if (preset === "restore") {
    const backup = cardBackups(await readActions(200)).get(cardId);
    if (!backup) throw new Error("没有这张卡的备份");
    target = backup;
  } else if (preset === "chat_only") {
    target = { enabled: true, allowedBuiltinTools: [], allowedPackages: [], allowedSkills: [], allowedMcpServers: [] };
  } else {
    const packages = await Tools.SoftwareSettings.listSandboxPackages();
    const have = new Set((packages.packages ?? []).filter((p) => p.enabled).map((p) => p.packageName));
    target = {
      enabled: true,
      allowedBuiltinTools: ["use_package"],
      allowedPackages: COMPANION_PACKAGES.filter((p) => have.has(p)),
      allowedSkills: [],
      allowedMcpServers: [],
    };
  }
  const result = await Tools.SoftwareSettings.updateCharacterCard(cardId, toWriteOptions(target));
  const after = accessOf(result.card?.toolAccessConfig);
  const ok = after.enabled === target.enabled && after.allowedPackages.length === target.allowedPackages.length;
  await logAction({
    kind: "card",
    target: cardId,
    label: PRESET_LABEL[preset],
    before,
    after,
    ok,
    detail: current.card.name,
  });
  if (!ok) throw new Error("宿主回读的工具权限和设置的不一致，已记录");
  return preset === "restore" ? `「${current.card.name}」的工具权限已恢复` : `「${current.card.name}」改成了「${PRESET_LABEL[preset]}」：${describeAccess(after)}`;
}

// ---------- 冗余扫描（只读） ----------

export interface RedundantEntry {
  name: string;
  isDirectory: boolean;
  newest: number | null;
  refs: string[];
  disabledRefs: string[];
}

export interface WorkflowHint {
  name: string;
  why: string;
}

export interface RedundancyReport {
  scannedAt: number;
  candidates: RedundantEntry[];
  kept: number;
  workflows: WorkflowHint[];
  reportPath: string;
}

async function newestUnder(path: string, own: number | null): Promise<number | null> {
  let newest = own;
  try {
    const listing = await Tools.Files.list(path);
    for (const entry of listing.entries ?? []) {
      const t = parseLastModified(entry.lastModified);
      if (t != null && (newest == null || t > newest)) newest = t;
    }
  } catch {
    // 目录读不了就用它自己的时间
  }
  return newest;
}

export async function scanRedundancy(now: number = Date.now()): Promise<RedundancyReport> {
  const flows = await workflowTexts();
  const listing = await Tools.Files.list(ROOT);
  const cutoff = now - STALE_DAYS * 24 * 3600 * 1000;
  const candidates: RedundantEntry[] = [];
  let kept = 0;
  for (const entry of listing.entries ?? []) {
    if (entry.name.startsWith(".")) continue;
    const own = parseLastModified(entry.lastModified);
    const newest = entry.isDirectory ? await newestUnder(`${ROOT}/${entry.name}`, own) : own;
    const needle = `Operit/${entry.name}`;
    const refs = flows.filter((f) => f.enabled && f.text.includes(needle)).map((f) => f.name);
    const disabledRefs = flows.filter((f) => !f.enabled && f.text.includes(needle)).map((f) => f.name);
    const recent = newest != null && newest >= cutoff;
    if (refs.length > 0 || recent || entry.name === "companion" || entry.name === "workflow") {
      kept += 1;
      continue;
    }
    candidates.push({ name: entry.name, isDirectory: entry.isDirectory, newest, refs, disabledRefs });
  }
  candidates.sort((a, b) => (a.newest ?? 0) - (b.newest ?? 0));

  const workflows: WorkflowHint[] = [];
  const all = await Tools.Workflow.getAll();
  for (const wf of all.workflows ?? []) {
    if (!wf.enabled) continue;
    const last = wf.lastExecutionTime ?? null;
    let schedule: { label: string; intervalMs: number | null } | null = null;
    try {
      schedule = await loadSchedule(wf.id);
    } catch {
      schedule = null;
    }
    if (schedule && schedule.label.startsWith("一次性")) {
      workflows.push({ name: wf.name, why: `${schedule.label}，时间已过但仍启用` });
    } else if (!schedule && (last == null || last < cutoff)) {
      workflows.push({ name: wf.name, why: last == null ? "手动流程，从没运行过" : `手动流程，${STALE_DAYS} 天以上没用过` });
    }
  }

  const d = new Date(now);
  const day = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, "0")}${String(d.getDate()).padStart(2, "0")}`;
  const reportPath = `${SLIM_DIR}/redundancy_${day}.md`;
  const lines = [
    `# 冗余候选（主控台只读扫描，${formatDateTime(now)}）`,
    "",
    `口径：${ROOT} 顶层条目里，没有被任何**启用中**工作流的节点提到、而且 ${STALE_DAYS} 天内没有改动的。`,
    "只是候选：脚本之间可能互相引用，移动或归档前先在 workflow/ 和各 .sh 里搜一遍名字。主控台不会移动或删除任何文件。",
    "",
    "## 文件与目录",
    ...(candidates.length
      ? candidates.map((c) => `- ${c.isDirectory ? "📁" : "📄"} ${c.name} · 最近改动 ${c.newest ? formatDateTime(c.newest) : "未知"}${c.disabledRefs.length ? ` · 只被停用的工作流提到：${c.disabledRefs.join("、")}` : ""}`)
      : ["- 没有"]),
    "",
    "## 工作流",
    ...(workflows.length ? workflows.map((w) => `- ${w.name}：${w.why}`) : ["- 没有"]),
    "",
  ];
  try {
    await Tools.Files.write(reportPath, lines.join("\n"), false);
  } catch {
    // 写不了就只在页面上显示
  }
  return { scannedAt: now, candidates, kept, workflows, reportPath };
}

// ---------- 汇总 ----------

export interface SlimReport {
  generatedAt: number;
  chats: ChatBill | null;
  packages: PackageBill | null;
  cards: CardCost[] | null;
  summary: SummaryConfig | null;
  actions: SlimAction[];
  errors: string[];
}

export async function collectSlimReport(now: number = Date.now()): Promise<SlimReport> {
  const errors: string[] = [];
  const actions = await readActions(200).catch(() => [] as SlimAction[]);
  const backups = cardBackups(actions);

  let chats: ChatBill | null = null;
  try {
    chats = await loadChatBill(now);
  } catch (error) {
    errors.push(`会话账单：${errorText(error)}`);
  }

  let flows: { name: string; enabled: boolean; text: string; id: string }[] = [];
  try {
    flows = await workflowTexts();
  } catch (error) {
    errors.push(`工作流：${errorText(error)}`);
  }

  let packages: PackageBill | null = null;
  try {
    packages = await loadPackageBill(flows, hubDisabledPackages(actions));
  } catch (error) {
    errors.push(`工具包：${errorText(error)}`);
  }

  let cards: CardCost[] | null = null;
  try {
    const byCard: Record<string, number> = {};
    for (const c of await listChats("")) {
      if (c.characterCardName) byCard[c.characterCardName] = (byCard[c.characterCardName] ?? 0) + 1;
    }
    cards = await loadCards(byCard, new Set(backups.keys()));
  } catch (error) {
    errors.push(`角色卡：${errorText(error)}`);
  }

  let summary: SummaryConfig | null = null;
  try {
    summary = await loadSummaryConfig();
  } catch (error) {
    errors.push(`自动总结：${errorText(error)}`);
  }

  return { generatedAt: now, chats, packages, cards, summary, actions: actions.slice(-8).reverse(), errors };
}

export function slimReportToText(r: SlimReport): string {
  const out: string[] = [`省流报告（${formatDateTime(r.generatedAt)}，数字是估算）`];
  if (r.chats) {
    const c = r.chats;
    out.push(
      `会话 ${c.totalChats} 个，累计输入 ${formatTokens(c.totalInput)} tokens${c.grownSince != null && c.baselineAt ? `；自 ${formatDateTime(c.baselineAt)} 起增加 ${formatTokens(c.grownSince)}` : ""}；${STALE_DAYS} 天没动的 ${c.idleChats} 个`
    );
    for (const row of c.rows) {
      const flags = [row.isHer ? "她" : "", row.backstage ? "后台角色" : "", row.lastSummaryAt === null ? "从没压缩" : ""].filter(Boolean).join("/");
      out.push(
        `- ${row.title}${flags ? `[${flags}]` : ""}：${row.messages} 条，输入 ${formatTokens(row.input)}，平均每条 ${formatTokens(row.perMessage)}${row.growth != null ? `，近期 +${formatTokens(row.growth)}` : ""}`
      );
    }
  }
  if (r.packages) {
    out.push(`启用工具包 ${r.packages.enabled.length} 个，清单每轮约 ${formatTokens(r.packages.listTokens)} tokens；内置工具说明约 ${BUILTIN_TOOL_TOKENS}`);
    const unused = r.packages.enabled.filter((p) => p.usedBy.length === 0).slice(0, 10);
    if (unused.length) out.push(`没被工作流用到的前几个：${unused.map((p) => `${p.name}(${p.tokens})`).join("、")}`);
  }
  if (r.cards) {
    for (const card of r.cards.filter((c) => c.chats > 0).slice(0, 8)) {
      out.push(`角色卡「${card.name}」：人设约 ${formatTokens(card.promptTokens)}，${describeAccess(card.access)}，${card.chats} 个对话`);
    }
  }
  if (r.summary) {
    const s = r.summary;
    const ceiling = summaryCeiling(s);
    out.push(`对话模型「${s.configName}」：${describeSummary(s)}${ceiling ? `；长对话每次请求最多约 ${formatTokens(ceiling)} tokens` : ""}`);
  }
  for (const e of r.errors) out.push(`读取失败：${e}`);
  return out.join("\n");
}
