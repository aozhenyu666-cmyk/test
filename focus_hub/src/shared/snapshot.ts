import { PATHS } from "./paths.js";
import { readRecentProgress, type ProgressRecord } from "./progress.js";

export { PATHS };

// WorkManager 不接受小于 15 分钟的周期，宿主会把间隔抬到 15 分钟
const MIN_SCHEDULE_INTERVAL_MS = 15 * 60 * 1000;
const STALE_GRACE_MS = 5 * 60 * 1000;
const EVENT_TAIL_LINES = 40;
const ACTION_TAIL_LINES = 12;
const RULE_TAGS = ["ADVICE", "PROMPT", "PREAPPROVED_GUARD"];

export type SourceStatus = "OK" | "MISSING" | "ERROR";

export interface SourceInfo {
  label: string;
  status: SourceStatus;
  detail: string;
}

export interface TaskState {
  state: string;
  task: string;
  mode: string;
  syncedAt: string;
}

export type WorkflowHealth =
  | "FAILED"
  | "STALE"
  | "RUNNING_NOW"
  | "OK"
  | "NO_HISTORY"
  | "DISABLED";

export interface WorkflowRow {
  id: string;
  name: string;
  enabled: boolean;
  lastExecutionTime: number | null;
  lastExecutionStatus: string | null;
  total: number;
  success: number;
  failed: number;
  health: WorkflowHealth;
  note: string;
}

export interface AppUsageRow {
  packageName: string;
  appName: string;
  foregroundMinutes: number;
  lastTimeUsed: number;
}

export interface EventRow {
  time: string;
  type: string;
  src: string;
  summary: string;
  count: number;
}

export interface Snapshot {
  generatedAt: number;
  task: TaskState | null;
  workflows: WorkflowRow[];
  usage: { windowHours: number; rows: AppUsageRow[] } | null;
  events: { date: string; totalLines: number; rows: EventRow[]; unparsable: number } | null;
  actions: { totalLines: number; rows: string[][] } | null;
  execRules: string[][] | null;
  activeRules: { tagCounts: Record<string, number>; lines: string[] } | null;
  progress: ProgressRecord[] | null;
  sources: SourceInfo[];
}

function errorText(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return String(error);
}

// 宿主读文件时会给每行加 "  12| " 行号前缀，并在超过 32KB 时追加截断标记
const LINE_NUMBER_PREFIX = /^\s*\d+\| ?/;
const TRUNCATED_MARK = "... (file content truncated) ...";

function stripLineNumbers(content: string): string[] {
  const lines: string[] = [];
  for (const line of String(content ?? "").split("\n")) {
    if (LINE_NUMBER_PREFIX.test(line)) {
      lines.push(line.replace(LINE_NUMBER_PREFIX, ""));
    }
  }
  return lines;
}

interface FileLines {
  lines: string[];
  totalLines: number;
  truncated: boolean;
}

async function fileExists(path: string): Promise<boolean> {
  const result = await Tools.Files.exists(path);
  return Boolean(result && result.exists && !result.isDirectory);
}

async function readHead(path: string, maxLines: number): Promise<FileLines> {
  const part = await Tools.Files.readPart(path, 1, maxLines);
  return {
    lines: stripLineNumbers(part.content),
    totalLines: part.totalLines,
    truncated: part.content.includes(TRUNCATED_MARK),
  };
}

async function readTail(path: string, count: number): Promise<FileLines> {
  const probe = await Tools.Files.readPart(path, 1, 1);
  const totalLines = probe.totalLines;
  if (totalLines <= 0) {
    return { lines: [], totalLines: 0, truncated: false };
  }
  const start = Math.max(1, totalLines - count + 1);
  const part = await Tools.Files.readPart(path, start, totalLines);
  return {
    lines: stripLineNumbers(part.content),
    totalLines,
    truncated: part.content.includes(TRUNCATED_MARK),
  };
}

type Loaded<T> = { value: T | null; source: SourceInfo };

async function loadFile<T>(
  label: string,
  path: string,
  read: () => Promise<T>
): Promise<Loaded<T>> {
  try {
    if (!(await fileExists(path))) {
      return { value: null, source: { label, status: "MISSING", detail: `文件不存在：${path}` } };
    }
    return { value: await read(), source: { label, status: "OK", detail: path } };
  } catch (error) {
    return { value: null, source: { label, status: "ERROR", detail: errorText(error) } };
  }
}

function pad2(value: number): string {
  return value < 10 ? `0${value}` : String(value);
}

export function formatClock(ms: number): string {
  const d = new Date(ms);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

export function formatDateTime(ms: number): string {
  const d = new Date(ms);
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${formatClock(ms)}`;
}

export function formatAgo(ms: number, now: number): string {
  const diff = Math.max(0, now - ms);
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return "刚刚";
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时 ${minutes % 60} 分钟前`;
  return `${Math.floor(hours / 24)} 天前`;
}

function dateKey(d: Date): string {
  return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}`;
}

function parseTaskState(lines: string[]): TaskState {
  const map: Record<string, string> = {};
  for (const line of lines) {
    const index = line.indexOf("=");
    if (index > 0) {
      map[line.slice(0, index).trim()] = line.slice(index + 1).trim();
    }
  }
  return {
    state: map.STATE ?? "",
    task: map.TASK ?? "",
    mode: map.MODE ?? "",
    syncedAt: map.SYNCED_AT ?? "",
  };
}

interface ScheduleInfo {
  label: string;
  intervalMs: number | null;
}

// 与宿主 WorkflowScheduler.calculateCronInterval 的简化规则一致
function cronSchedule(expression: string): ScheduleInfo {
  const parts = expression.trim().split(/\s+/);
  if (parts.length < 5) return { label: `cron ${expression}`, intervalMs: null };
  const [minute, hour] = parts;
  if (/^\d+$/.test(minute) && /^\d+$/.test(hour)) {
    return { label: `每天 ${pad2(Number(hour))}:${pad2(Number(minute))}`, intervalMs: 24 * 3600 * 1000 };
  }
  if (minute === "0" && hour.startsWith("*/")) {
    const n = Number(hour.slice(2));
    if (n > 0) return { label: `每 ${n} 小时`, intervalMs: n * 3600 * 1000 };
  }
  if (minute.startsWith("*/") && hour === "*") {
    const n = Number(minute.slice(2));
    if (n > 0) return { label: `每 ${n} 分钟`, intervalMs: n * 60 * 1000 };
  }
  return { label: `cron ${expression}`, intervalMs: null };
}

async function loadSchedule(workflowId: string): Promise<ScheduleInfo | null> {
  const detail = await Tools.Workflow.get(workflowId);
  for (const node of detail.nodes ?? []) {
    // get_workflow 返回的节点可能只有 __type 没有 type 字段，按 triggerType 识别
    const trigger = node as { triggerType?: string; triggerConfig?: Record<string, string> };
    if (trigger.triggerType !== "schedule") continue;
    const config = trigger.triggerConfig ?? {};
    if (config.enabled === "false") continue;
    const repeat = config.repeat !== "false";
    let info: ScheduleInfo;
    if (config.schedule_type === "interval") {
      const interval = Math.max(Number(config.interval_ms) || 0, MIN_SCHEDULE_INTERVAL_MS);
      info = { label: `每 ${Math.round(interval / 60000)} 分钟`, intervalMs: interval };
    } else if (config.schedule_type === "cron" && config.cron_expression) {
      info = cronSchedule(config.cron_expression);
    } else if (config.schedule_type === "specific_time") {
      return { label: `一次性 ${config.specific_time ?? ""}`.trim(), intervalMs: null };
    } else {
      continue;
    }
    if (!repeat) return { label: `${info.label}（不重复）`, intervalMs: null };
    if (info.intervalMs != null) {
      info.intervalMs = Math.max(info.intervalMs, MIN_SCHEDULE_INTERVAL_MS);
    }
    return info;
  }
  return null;
}

function staleThresholdMs(intervalMs: number): number {
  // 短周期容忍错过一拍；日级任务只多给一小时左右的余量
  if (intervalMs <= 3600 * 1000) return intervalMs * 2 + STALE_GRACE_MS;
  return intervalMs + Math.max(3600 * 1000, intervalMs / 8);
}

async function loadWorkflows(now: number): Promise<Loaded<WorkflowRow[]>> {
  const label = "工作流";
  try {
    const list = await Tools.Workflow.getAll();
    const rows = await Promise.all(
      (list.workflows ?? []).map(async (wf): Promise<WorkflowRow> => {
        const row: WorkflowRow = {
          id: wf.id,
          name: wf.name,
          enabled: Boolean(wf.enabled),
          lastExecutionTime: wf.lastExecutionTime ?? null,
          lastExecutionStatus: wf.lastExecutionStatus ?? null,
          total: wf.totalExecutions ?? 0,
          success: wf.successfulExecutions ?? 0,
          failed: wf.failedExecutions ?? 0,
          health: "OK",
          note: "",
        };
        if (!row.enabled) {
          row.health = "DISABLED";
          return row;
        }
        if (row.lastExecutionTime == null) {
          row.health = "NO_HISTORY";
          return row;
        }
        if (row.lastExecutionStatus === "FAILED") {
          row.health = "FAILED";
          return row;
        }
        if (row.lastExecutionStatus === "RUNNING") {
          row.health = "RUNNING_NOW";
          return row;
        }
        try {
          const schedule = await loadSchedule(row.id);
          if (schedule == null) {
            row.note = "手动触发";
            return row;
          }
          row.note = schedule.label;
          if (schedule.intervalMs != null && now - row.lastExecutionTime > staleThresholdMs(schedule.intervalMs)) {
            row.health = "STALE";
          }
        } catch (error) {
          row.note = `读取调度配置失败：${errorText(error)}`;
        }
        return row;
      })
    );
    const order: WorkflowHealth[] = ["FAILED", "STALE", "RUNNING_NOW", "OK", "NO_HISTORY", "DISABLED"];
    rows.sort(
      (a, b) => order.indexOf(a.health) - order.indexOf(b.health) || a.name.localeCompare(b.name)
    );
    return { value: rows, source: { label, status: "OK", detail: `共 ${rows.length} 个` } };
  } catch (error) {
    return { value: null, source: { label, status: "ERROR", detail: errorText(error) } };
  }
}

async function loadUsage(): Promise<Loaded<{ windowHours: number; rows: AppUsageRow[] }>> {
  const label = "App 使用时长";
  const windowHours = 24;
  try {
    const result = await Tools.System.getAppUsageTime({
      sinceHours: windowHours,
      limit: 30,
      includeSystemApps: false,
    });
    const rows = (result.entries ?? [])
      .map((entry) => ({
        packageName: entry.packageName,
        appName: entry.appName || entry.packageName,
        foregroundMinutes: Math.round((entry.totalForegroundTimeMs ?? 0) / 60000),
        lastTimeUsed: entry.lastTimeUsed ?? 0,
      }))
      .filter((row) => row.foregroundMinutes > 0)
      .sort((a, b) => b.foregroundMinutes - a.foregroundMinutes)
      .slice(0, 12);
    return { value: { windowHours, rows }, source: { label, status: "OK", detail: `过去 ${windowHours} 小时` } };
  } catch (error) {
    return { value: null, source: { label, status: "ERROR", detail: errorText(error) } };
  }
}

const EVENT_HIDDEN_KEYS = new Set(["eid", "id", "ts", "iso", "date", "type", "src", "dedup"]);

function summarizeEvent(event: Record<string, unknown>, appNames: Map<string, string>): string {
  const parts: string[] = [];
  for (const [key, raw] of Object.entries(event)) {
    if (EVENT_HIDDEN_KEYS.has(key) || raw === "" || raw == null) continue;
    let value = String(raw);
    if (key === "pkg" && appNames.has(value)) {
      value = `${appNames.get(value)}(${value})`;
    }
    parts.push(`${key}=${value}`);
  }
  return parts.join(" · ");
}

function eventTime(event: Record<string, unknown>): string {
  const iso = typeof event.iso === "string" ? event.iso : "";
  if (iso.length >= 16) return iso.slice(11, 19) || iso;
  const ts = Number(event.ts);
  return Number.isFinite(ts) && ts > 0 ? formatClock(ts * 1000) : "时间未记录";
}

async function loadEvents(
  now: number,
  appNames: Map<string, string>
): Promise<Loaded<{ date: string; totalLines: number; rows: EventRow[]; unparsable: number }>> {
  const today = dateKey(new Date(now));
  const yesterday = dateKey(new Date(now - 24 * 3600 * 1000));
  let date = today;
  let path = `${PATHS.eventsDir}/${today}/events.jsonl`;
  try {
    if (!(await fileExists(path))) {
      const fallback = `${PATHS.eventsDir}/${yesterday}/events.jsonl`;
      if (!(await fileExists(fallback))) {
        return { value: null, source: { label: "事件流", status: "MISSING", detail: `今天和昨天都没有事件文件：${path}` } };
      }
      date = yesterday;
      path = fallback;
    }
  } catch (error) {
    return { value: null, source: { label: "事件流", status: "ERROR", detail: errorText(error) } };
  }
  return loadFile("事件流", path, async () => {
    const tail = await readTail(path, EVENT_TAIL_LINES);
    const rows: EventRow[] = [];
    let unparsable = 0;
    for (const line of tail.lines) {
      if (!line.trim()) continue;
      let event: Record<string, unknown>;
      try {
        event = JSON.parse(line) as Record<string, unknown>;
      } catch {
        unparsable += 1;
        continue;
      }
      const row: EventRow = {
        time: eventTime(event),
        type: String(event.type ?? "未记录"),
        src: String(event.src ?? "未记录"),
        summary: summarizeEvent(event, appNames),
        count: 1,
      };
      // 采样器会连续写入内容相同的事件，合并成一行并保留时间范围
      const prev = rows[rows.length - 1];
      if (prev && prev.type === row.type && prev.src === row.src && prev.summary === row.summary) {
        prev.count += 1;
        prev.time = `${prev.time.split("–")[0]}–${row.time}`;
      } else {
        rows.push(row);
      }
    }
    rows.reverse();
    return { date, totalLines: tail.totalLines, rows, unparsable };
  });
}

function splitColumns(line: string): string[] {
  return line.split(/\t+|\s{2,}/).map((cell) => cell.trim()).filter(Boolean);
}

// 进展文件不存在只说明还没人记过，不算数据源缺失
async function loadProgress(now: number): Promise<Loaded<ProgressRecord[]>> {
  const label = "用户进展";
  try {
    const records = await readRecentProgress(8, now);
    return { value: records, source: { label, status: "OK", detail: `${records.length} 条` } };
  } catch (error) {
    return { value: null, source: { label, status: "ERROR", detail: errorText(error) } };
  }
}

export async function collectSnapshot(): Promise<Snapshot> {
  const now = Date.now();

  const [task, workflows, usage, progress] = await Promise.all([
    loadFile("当前任务", PATHS.taskState, async () => parseTaskState((await readHead(PATHS.taskState, 50)).lines)),
    loadWorkflows(now),
    loadUsage(),
    loadProgress(now),
  ]);

  const appNames = new Map<string, string>();
  for (const row of usage.value?.rows ?? []) {
    appNames.set(row.packageName, row.appName);
  }

  const [events, actions, execRules, activeRules] = await Promise.all([
    loadEvents(now, appNames),
    loadFile("动作审计", PATHS.actionLog, async () => {
      const tail = await readTail(PATHS.actionLog, ACTION_TAIL_LINES);
      const rows = tail.lines.filter((line) => line.trim()).map(splitColumns);
      rows.reverse();
      return { totalLines: tail.totalLines, rows };
    }),
    loadFile("行动规则表", PATHS.execRules, async () =>
      (await readHead(PATHS.execRules, 60)).lines
        .filter((line) => line.trim() && !line.trim().startsWith("#"))
        .map(splitColumns)
    ),
    loadFile("生效规则", PATHS.activeRules, async () => {
      const head = await readHead(PATHS.activeRules, 400);
      const tagCounts: Record<string, number> = {};
      const lines: string[] = [];
      for (const tag of RULE_TAGS) tagCounts[tag] = 0;
      for (const line of head.lines) {
        const hits = RULE_TAGS.filter((tag) => line.includes(tag));
        if (hits.length === 0) continue;
        for (const tag of hits) tagCounts[tag] += 1;
        if (lines.length < 15) lines.push(line.trim().replace(/^[-*]\s+/, ""));
      }
      return { tagCounts, lines };
    }),
  ]);

  return {
    generatedAt: now,
    task: task.value,
    workflows: workflows.value ?? [],
    usage: usage.value,
    events: events.value,
    actions: actions.value,
    execRules: execRules.value,
    activeRules: activeRules.value,
    progress: progress.value,
    sources: [task.source, progress.source, workflows.source, usage.source, events.source, actions.source, execRules.source, activeRules.source],
  };
}
