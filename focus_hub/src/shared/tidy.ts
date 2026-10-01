// 对话整理：用 list_chats 返回的元数据盘点所有对话，不读内容、不花模型 token。
// 能自动做的只有两件，而且都要点两次确认：
// - 删除空对话（0–2 条），删之前把内容备份到 slim/deleted_chats.jsonl
// - 按"[类别] 原标题"改名，记下旧标题，可以整批撤销
// 长对话的提炼需要读内容，交给 Operit AI：这里只生成一份现成的指令文件。
import { isArchived, isPinned, type ChatEntry } from "./nav.js";
import { findCompanion } from "./companion.js";
import { formatDateTime, fileExists, readAll } from "./snapshot.js";
import { SLIM_DIR, scanRedundancy, workflowTexts } from "./slim.js";

const DELETED_LOG = `${SLIM_DIR}/deleted_chats.jsonl`;
const RENAME_LOG = `${SLIM_DIR}/renames.jsonl`;
const WORKFLOW_LOG = `${SLIM_DIR}/workflow_toggles.jsonl`;

// 一小时内刚建或刚动过的空对话可能马上要用，不删
const FRESH_MS = 3600 * 1000;
// 超长又 7 天没动的对话归入"归档"
const IDLE_MS = 7 * 24 * 3600 * 1000;

export const SHORT_MAX_MESSAGES = 2;
export const LONG_MIN_MESSAGES = 100;
export const LONG_MIN_TOKENS = 1_000_000;

export type ChatClass = "A" | "B" | "C" | "D";

export const CLASS_LABEL: Record<ChatClass, string> = {
  A: "空的或 1–2 句",
  B: "标题重复",
  C: "超长",
  D: "正常",
};

export interface TidyChat extends ChatEntry {
  cls: ChatClass;
  // 删改保护的原因；空字符串表示没有保护
  guard: string;
  tokens: number;
  proposedTitle: string | null;
}

export interface DuplicateGroup {
  key: string;
  titles: string[];
  ids: string[];
}

export interface TidyReport {
  scannedAt: number;
  totalCount: number;
  listed: number;
  complete: boolean;
  chats: TidyChat[];
  counts: Record<ChatClass, number>;
  duplicates: DuplicateGroup[];
  totalTokens: number;
  topTokens: { title: string; tokens: number; share: number }[];
  reportPath: string;
}

function dayKey(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, "0")}${String(d.getDate()).padStart(2, "0")}`;
}

function errorText(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return String(error);
}

// list_chats 单次最多 200 个、没有翻页参数；按三种排序各取一次再去重，最多能盖到约 600 个
async function listEverything(): Promise<{ chats: ChatEntry[]; totalCount: number }> {
  const seen = new Map<string, ChatEntry>();
  let totalCount = 0;
  const orders: [string, string][] = [
    ["messageCount", "desc"],
    ["messageCount", "asc"],
    ["updatedAt", "desc"],
  ];
  for (const [sortBy, order] of orders) {
    const result = await Tools.Chat.listChats({ sort_by: sortBy as "messageCount", sort_order: order as "desc", limit: 200 });
    totalCount = Math.max(totalCount, Number(result.totalCount) || 0);
    for (const c of result.chats ?? []) {
      if (seen.has(c.id)) continue;
      seen.set(c.id, {
        id: c.id,
        title: String(c.title ?? "").trim() || "（无标题）",
        messageCount: c.messageCount ?? 0,
        updatedAt: String(c.updatedAt ?? ""),
        isCurrent: Boolean(c.isCurrent),
        characterCardName: String(c.characterCardName ?? ""),
        inputTokens: Number(c.inputTokens) || 0,
        outputTokens: Number(c.outputTokens) || 0,
        characterCardId: String(c.characterCardId ?? ""),
      });
    }
    // 一次就拿全了，后面两次不用再查
    if (totalCount > 0 && seen.size >= totalCount) break;
  }
  return { chats: [...seen.values()], totalCount: Math.max(totalCount, seen.size) };
}

// 标题归一：去掉数字、日期、标点和"新对话"之类的套话，用来找重复
export function titleKey(title: string): string {
  return title
    .toLowerCase()
    .replace(/新(的)?对话|new chat|untitled|（无标题）/g, "")
    .replace(/[0-9０-９]+/g, "")
    .replace(/[\s\-_:：,，.。!！?？()（）\[\]【】「」"'“”#·/\\|]+/g, "")
    .trim();
}

const TEST_PATTERN = /测试|试试|试一下|test|probe|探针|demo|调试|debug/i;

function parseUpdated(value: string): number | null {
  const t = /^\d+$/.test(value) ? Number(value) : Date.parse(value);
  return Number.isFinite(t) && t > 0 ? t : null;
}

function categoryFor(c: TidyChat, herId: string, now: number): string | null {
  if (c.id === herId) return "她";
  if (c.guard.startsWith("工作流")) return "工作流";
  if (isPinned(c)) return "角色";
  if (TEST_PATTERN.test(c.title)) return "测试";
  if (c.cls === "C") {
    const updated = parseUpdated(c.updatedAt);
    return updated != null && now - updated > IDLE_MS ? "归档" : "长对话";
  }
  return "日常";
}

export function proposeTitle(c: TidyChat, herId: string, now: number = Date.now()): string | null {
  if (c.cls === "A") return null; // 空对话是删除候选，不改名
  if (/^\s*[\[【]/.test(c.title) || isArchived(c)) return null; // 已经有类别前缀或已归档
  const cat = categoryFor(c, herId, now);
  if (!cat) return null;
  const topic = c.title.length > 24 ? `${c.title.slice(0, 24)}…` : c.title;
  return `[${cat}] ${topic}`;
}

export async function scanChats(now: number = Date.now()): Promise<TidyReport> {
  const { chats, totalCount } = await listEverything();
  const companion = await findCompanion(chats).catch(() => null);
  const herId = companion?.chat?.id ?? "";

  // 被启用中的工作流写死 chat_id 的对话不能删
  const usedByFlow = new Map<string, string>();
  try {
    for (const f of await workflowTexts()) {
      if (!f.enabled) continue;
      for (const c of chats) if (!usedByFlow.has(c.id) && f.text.includes(c.id)) usedByFlow.set(c.id, f.name);
    }
  } catch {
    // 读不到工作流就只靠标题和"她"来保护
  }

  const groups = new Map<string, TidyChat[]>();
  const rows: TidyChat[] = chats.map((c) => {
    const updated = parseUpdated(c.updatedAt);
    const guard =
      c.id === herId
        ? "她的对话"
        : usedByFlow.has(c.id)
          ? `工作流在用：${usedByFlow.get(c.id)}`
          : isPinned(c) && !isArchived(c)
            ? "后台角色"
            : c.isCurrent
              ? "当前对话"
              : updated != null && now - updated < FRESH_MS && c.messageCount <= SHORT_MAX_MESSAGES
                ? "一小时内刚建"
                : "";
    const tokens = c.inputTokens + c.outputTokens;
    let cls: ChatClass = "D";
    if (c.messageCount <= SHORT_MAX_MESSAGES) cls = "A";
    else if (c.messageCount > LONG_MIN_MESSAGES || c.inputTokens > LONG_MIN_TOKENS) cls = "C";
    return { ...c, cls, guard, tokens, proposedTitle: null };
  });
  for (const r of rows) {
    const key = titleKey(r.title);
    if (!key) continue;
    const list = groups.get(key) ?? [];
    list.push(r);
    groups.set(key, list);
  }
  const duplicates: DuplicateGroup[] = [];
  for (const [key, list] of groups) {
    if (list.length < 2) continue;
    duplicates.push({ key, titles: list.map((x) => x.title), ids: list.map((x) => x.id) });
    for (const r of list) if (r.cls === "D") r.cls = "B";
  }
  duplicates.sort((a, b) => b.ids.length - a.ids.length);
  for (const r of rows) r.proposedTitle = proposeTitle(r, herId, now);

  rows.sort((a, b) => b.messageCount - a.messageCount);
  const counts: Record<ChatClass, number> = { A: 0, B: 0, C: 0, D: 0 };
  for (const r of rows) counts[r.cls] += 1;
  const totalTokens = rows.reduce((a, r) => a + r.tokens, 0);
  const topTokens = rows
    .slice()
    .sort((a, b) => b.tokens - a.tokens)
    .slice(0, 5)
    .map((r) => ({ title: r.title, tokens: r.tokens, share: totalTokens > 0 ? r.tokens / totalTokens : 0 }));

  const reportPath = `${SLIM_DIR}/chats_${dayKey(now)}.md`;
  const report: TidyReport = {
    scannedAt: now,
    totalCount,
    listed: rows.length,
    complete: rows.length >= totalCount,
    chats: rows,
    counts,
    duplicates,
    totalTokens,
    topTokens,
    reportPath,
  };
  try {
    await Tools.Files.write(reportPath, reportToMarkdown(report), false);
  } catch {
    // 写不了只影响文件，页面照常显示
  }
  return report;
}

function tokenText(n: number): string {
  if (n >= 1e8) return `${(n / 1e8).toFixed(1)}亿`;
  if (n >= 1e4) return `${(n / 1e4).toFixed(n >= 1e6 ? 0 : 1)}万`;
  return String(n);
}

function updatedText(value: string): string {
  const t = /^\d+$/.test(value) ? Number(value) : Date.parse(value);
  return Number.isFinite(t) && t > 0 ? formatDateTime(t) : value || "—";
}

export function reportToMarkdown(r: TidyReport): string {
  const lines = [
    `# 对话盘点（${formatDateTime(r.scannedAt)}，只读 list_chats，未读内容）`,
    "",
    `共 ${r.totalCount} 个对话，本次列出 ${r.listed} 个${r.complete ? "" : "（超过单次可列上限，有遗漏）"}。`,
    `A ${CLASS_LABEL.A} ${r.counts.A} · B ${CLASS_LABEL.B} ${r.counts.B} · C ${CLASS_LABEL.C} ${r.counts.C} · D ${CLASS_LABEL.D} ${r.counts.D}`,
    "",
    "## token 花在哪",
    ...r.topTokens.map((t) => `- ${t.title}：${tokenText(t.tokens)}（${Math.round(t.share * 100)}%）`),
    "",
    "## 全部对话",
    "",
    "| 类 | 标题 | 条数 | 输入/输出 token | 最后更新 | 角色卡 | 保护 |",
    "|---|---|---|---|---|---|---|",
    ...r.chats.map(
      (c) =>
        `| ${c.cls} | ${c.title.replace(/\|/g, "／")} | ${c.messageCount} | ${tokenText(c.inputTokens)}/${tokenText(c.outputTokens)} | ${updatedText(c.updatedAt)} | ${c.characterCardName || "—"} | ${c.guard || ""} |`
    ),
    "",
    "## 标题重复",
    ...(r.duplicates.length ? r.duplicates.map((g) => `- ${g.titles.join(" / ")}`) : ["- 没有"]),
    "",
  ];
  return lines.join("\n");
}

// ---------- 删除空对话（先备份） ----------

export interface DeleteResult {
  deleted: string[];
  skipped: { title: string; reason: string }[];
}

export async function deleteShortChats(targets: TidyChat[]): Promise<DeleteResult> {
  const out: DeleteResult = { deleted: [], skipped: [] };
  for (const c of targets) {
    // 硬保险：只删 A 类，受保护的一律跳过
    if (c.cls !== "A" || c.messageCount > SHORT_MAX_MESSAGES) {
      out.skipped.push({ title: c.title, reason: "不是空对话" });
      continue;
    }
    if (c.guard) {
      out.skipped.push({ title: c.title, reason: c.guard });
      continue;
    }
    try {
      let messages: { sender: string; content: string; timestamp: number }[] = [];
      try {
        const m = await Tools.Chat.getMessages(c.id, { order: "asc", limit: 10 });
        messages = (m.messages ?? []).map((x) => ({ sender: x.sender, content: x.content, timestamp: x.timestamp }));
      } catch {
        // 读不到内容也照样备份元数据
      }
      await Tools.Files.write(
        DELETED_LOG,
        JSON.stringify({ ts: Math.floor(Date.now() / 1000), id: c.id, title: c.title, messageCount: c.messageCount, card: c.characterCardName, messages }) + "\n",
        true
      );
      await Tools.Chat.deleteChat(c.id);
      out.deleted.push(c.title);
    } catch (error) {
      out.skipped.push({ title: c.title, reason: errorText(error) });
    }
  }
  return out;
}

// ---------- 改名（可撤销） ----------

interface RenameRecord {
  batch: string;
  ts: number;
  id: string;
  from: string;
  to: string;
  undone?: boolean;
}

export async function applyRenames(targets: TidyChat[]): Promise<{ renamed: number; failed: string[] }> {
  const batch = `R-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  let renamed = 0;
  const failed: string[] = [];
  for (const c of targets) {
    if (!c.proposedTitle || c.proposedTitle === c.title) continue;
    try {
      await Tools.Chat.updateTitle(c.id, c.proposedTitle);
      const rec: RenameRecord = { batch, ts: Math.floor(Date.now() / 1000), id: c.id, from: c.title, to: c.proposedTitle };
      await Tools.Files.write(RENAME_LOG, JSON.stringify(rec) + "\n", true);
      renamed += 1;
    } catch (error) {
      failed.push(`${c.title}：${errorText(error)}`);
    }
  }
  return { renamed, failed };
}

async function readRenames(): Promise<RenameRecord[]> {
  if (!(await fileExists(RENAME_LOG))) return [];
  const { lines } = await readAll(RENAME_LOG, 2000);
  const out: RenameRecord[] = [];
  for (const line of lines) {
    try {
      out.push(JSON.parse(line) as RenameRecord);
    } catch {
      // 跳过坏行
    }
  }
  return out;
}

export async function lastRenameBatch(): Promise<{ batch: string; count: number } | null> {
  const all = await readRenames();
  const undone = new Set(all.filter((r) => r.undone).map((r) => `${r.batch}:${r.id}`));
  const live = all.filter((r) => !r.undone && !undone.has(`${r.batch}:${r.id}`));
  const last = live[live.length - 1];
  if (!last) return null;
  return { batch: last.batch, count: live.filter((r) => r.batch === last.batch).length };
}

export async function undoLastRenames(): Promise<{ restored: number; failed: string[] }> {
  const all = await readRenames();
  const undone = new Set(all.filter((r) => r.undone).map((r) => `${r.batch}:${r.id}`));
  const live = all.filter((r) => !r.undone && !undone.has(`${r.batch}:${r.id}`));
  const last = live[live.length - 1];
  if (!last) return { restored: 0, failed: [] };
  let restored = 0;
  const failed: string[] = [];
  for (const r of live.filter((x) => x.batch === last.batch)) {
    try {
      await Tools.Chat.updateTitle(r.id, r.from);
      await Tools.Files.write(RENAME_LOG, JSON.stringify({ ...r, ts: Math.floor(Date.now() / 1000), undone: true }) + "\n", true);
      restored += 1;
    } catch (error) {
      failed.push(`${r.to}：${errorText(error)}`);
    }
  }
  return { restored, failed };
}

// ---------- 长对话提炼：生成给 Operit AI 的指令 ----------

export function distillPrompt(c: TidyChat): string {
  const segments = Math.max(1, Math.ceil(c.messageCount / 40));
  return [
    `请提炼对话「${c.title}」（chat_id: ${c.id}，共 ${c.messageCount} 条消息）。只读，不要删除或修改任何对话。`,
    "",
    `1. 用 read_messages_range 分段读取，每次 40 条（start=0,end=40；start=40,end=80……），共约 ${segments} 段。`,
    "   每读完一段先写 3–5 条笔记（这段在做什么、哪一步成功了、哪一步失败了），再读下一段。不要一次全部读完。",
    "2. 读完后写一份精简的「工作流说明」：",
    "   - 目标是什么",
    "   - 最终能用的步骤、参数和提示词（只保留最后成功的版本，失败的尝试不要）",
    "   - 注意事项",
    `3. 把说明保存到 /sdcard/Download/Operit/companion/slim/distilled_${c.id.slice(0, 8)}.md。`,
    "4. 然后问我要不要：① 存成记忆 ② 新建一个干净的对话来继续用这个工作流 ③ 删除原来的长对话。",
    "   我明确回复之前，什么都不要做。",
    "",
  ].join("\n");
}

export async function writeDistillPrompt(c: TidyChat): Promise<string> {
  const path = `${SLIM_DIR}/distill_prompt_${c.id.slice(0, 8)}.md`;
  await Tools.Files.write(path, distillPrompt(c), false);
  return path;
}

// ---------- 工作流：只停用明确过期的，可撤销 ----------

interface WorkflowToggle {
  batch: string;
  ts: number;
  id: string;
  name: string;
  why: string;
  undone?: boolean;
}

const PROBE_PATTERN = /probe|探针|一次性|test|测试/i;

// 一键整理只停两种：时间已过的一次性定时，和名字就是探针/测试的手动流程。其余只提示。
export async function disableExpiredWorkflows(now: number = Date.now()): Promise<{ disabled: string[]; suggested: string[] }> {
  const scan = await scanRedundancy(now);
  const all = await Tools.Workflow.getAll();
  const byName = new Map((all.workflows ?? []).map((w) => [w.name, w]));
  const batch = `W-${now}-${Math.random().toString(36).slice(2, 8)}`;
  const disabled: string[] = [];
  const suggested: string[] = [];
  for (const hint of scan.workflows) {
    const wf = byName.get(hint.name);
    if (!wf || !wf.enabled) continue;
    const expired = hint.why.includes("时间已过");
    const probe = hint.why.startsWith("手动流程") && PROBE_PATTERN.test(hint.name);
    if (!expired && !probe) {
      suggested.push(`${hint.name}（${hint.why}）`);
      continue;
    }
    try {
      const after = await Tools.Workflow.setEnabled(wf.id, false);
      if (after && after.enabled === false) {
        const rec: WorkflowToggle = { batch, ts: Math.floor(now / 1000), id: wf.id, name: wf.name, why: hint.why };
        await Tools.Files.write(WORKFLOW_LOG, JSON.stringify(rec) + "\n", true);
        disabled.push(wf.name);
      }
    } catch {
      suggested.push(`${hint.name}（停用失败，${hint.why}）`);
    }
  }
  return { disabled, suggested };
}

async function readToggles(): Promise<WorkflowToggle[]> {
  if (!(await fileExists(WORKFLOW_LOG))) return [];
  const { lines } = await readAll(WORKFLOW_LOG, 500);
  const out: WorkflowToggle[] = [];
  for (const line of lines) {
    try {
      out.push(JSON.parse(line) as WorkflowToggle);
    } catch {
      // 跳过坏行
    }
  }
  return out;
}

function liveToggles(all: WorkflowToggle[]): WorkflowToggle[] {
  const undone = new Set(all.filter((r) => r.undone).map((r) => `${r.batch}:${r.id}`));
  return all.filter((r) => !r.undone && !undone.has(`${r.batch}:${r.id}`));
}

export async function lastWorkflowBatch(): Promise<{ batch: string; names: string[] } | null> {
  const live = liveToggles(await readToggles());
  const last = live[live.length - 1];
  if (!last) return null;
  return { batch: last.batch, names: live.filter((r) => r.batch === last.batch).map((r) => r.name) };
}

export async function undoWorkflowBatch(): Promise<string[]> {
  const live = liveToggles(await readToggles());
  const last = live[live.length - 1];
  if (!last) return [];
  const restored: string[] = [];
  for (const r of live.filter((x) => x.batch === last.batch)) {
    await Tools.Workflow.setEnabled(r.id, true);
    await Tools.Files.write(WORKFLOW_LOG, JSON.stringify({ ...r, undone: true }) + "\n", true);
    restored.push(r.name);
  }
  return restored;
}

// ---------- 一键整理 ----------

export interface OneClickResult {
  deleted: string[];
  skipped: number;
  renamed: number;
  workflowsDisabled: string[];
  workflowsSuggested: string[];
  report: TidyReport;
}

// 删空对话（先备份）→ 按类别改名（可撤销）→ 停用过期的一次性/探针工作流（可撤销）→ 重新盘点
export async function oneClickTidy(now: number = Date.now()): Promise<OneClickResult> {
  const before = await scanChats(now);
  const shorts = before.chats.filter((c) => c.cls === "A" && !c.guard);
  const del = await deleteShortChats(shorts);
  const mid = await scanChats(now);
  const ren = await applyRenames(mid.chats.filter((c) => c.proposedTitle));
  let workflowsDisabled: string[] = [];
  let workflowsSuggested: string[] = [];
  try {
    const wf = await disableExpiredWorkflows(now);
    workflowsDisabled = wf.disabled;
    workflowsSuggested = wf.suggested;
  } catch {
    // 工作流读不到就只整理对话
  }
  const report = await scanChats(now);
  return { deleted: del.deleted, skipped: del.skipped.length, renamed: ren.renamed, workflowsDisabled, workflowsSuggested, report };
}
