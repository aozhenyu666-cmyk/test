// 督促（狱卒）第一阶段：只演练，不锁任何东西。
//
// 设计前提（见 design/WARDEN_DESIGN.md）：
// - 这是用户给自己设的自律工具，用户始终能在系统设置里停用 Operit。目的不是关住人，
//   而是让"放弃当前任务"这件事要等、有代价、留记录，把人拉回来面对任务。
// - "罚"由代码按契约和时间推进，不问对话模型该不该罚；"判"（交的东西够不够）以后交给
//   一个只做核对的模型，它不知道有惩罚。第一阶段两者都只是记录。
// - 第一阶段（现在）：MODE 恒为 "dryrun"。只把"本来会做什么"写进日志，绝不调用 app_suspender。
//   真锁在后续阶段单独开，且要用户逐项确认契约数值后才做。
import { PATHS } from "./paths.js";
import { fileExists, formatDateTime, readAll } from "./snapshot.js";
import { readRecentProgress, type ProgressRecord } from "./progress.js";

const ROOT = "/sdcard/Download/Operit";
export const WARDEN_DIR = `${ROOT}/companion/warden`;
const CONTRACT_PATH = `${WARDEN_DIR}/contract.json`;
const ASSIGNMENT_PATH = `${WARDEN_DIR}/assignment.json`;
const DRYRUN_LOG = `${WARDEN_DIR}/dryrun.jsonl`;
const DELIVERY_LOG = `${WARDEN_DIR}/deliveries.jsonl`;

// 第一阶段唯一允许的模式。代码里任何真锁路径都必须先过这个检查。
export const WARDEN_MODE = "dryrun" as const;
export type WardenMode = typeof WARDEN_MODE | "tier1" | "full";

const LINE_NUMBER_PREFIX = /^\s*\d+\| ?/;

function errorText(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return String(error);
}

async function readJson<T>(path: string): Promise<T | null> {
  try {
    if (!(await fileExists(path))) return null;
    const part = await Tools.Files.readPart(path, 1, 500);
    const json = part.content
      .split("\n")
      .filter((line) => LINE_NUMBER_PREFIX.test(line))
      .map((line) => line.replace(LINE_NUMBER_PREFIX, ""))
      .join("\n");
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}

// ---------- 契约 ----------

export interface AppEntry {
  name: string;
  pkg: string;
  verified: boolean; // 包名是否已在真机核对；未核对的先不当真
}

export interface Contract {
  version: number;
  // 一级：核心娱乐，最先锁
  tier1: AppEntry[];
  // 二级：长期使用，升级后再锁
  tier2: AppEntry[];
  // 观望：既是工具又是诱因，只提醒不自动锁（如 Rikkahub）
  watch: AppEntry[];
  // 永不锁
  protectedApps: string[];
  timing: {
    graceMinutes: number; // 截止后宽限多久开始一级
    tier2AfterMinutes: number; // 一级后多久升二级
    maxSingleLockMinutes: number; // 单次最长（真锁阶段用）
    dailyUnlockHour: number; // 每天几点全部自动解开
  };
  limits: {
    pausePerDay: number;
    unlockPerDay: number;
    emergencyPerWeek: number;
    coolDownMinutes: number; // 急停冷静期
  };
  judgeModel: string;
  // 每项数值是否已由用户确认；未确认的只用于演练
  confirmed: Record<string, boolean>;
}

// 默认契约：数值取自 app_suspender METADATA 第八节。
// [x] 项是用户/盘面已确认；其余是演练用的提案，真锁前要用户拍板。
export const DEFAULT_CONTRACT: Contract = {
  version: 1,
  tier1: [
    { name: "抖音", pkg: "com.ss.android.ugc.aweme", verified: true },
    { name: "B站", pkg: "tv.danmaku.bili", verified: true },
    { name: "贴吧", pkg: "com.baidu.tieba", verified: true },
    { name: "红果短剧", pkg: "com.phoenix.read", verified: true },
    { name: "红果漫剧", pkg: "com.kylin.read", verified: false },
    { name: "X", pkg: "com.twitter.android", verified: false },
  ],
  tier2: [
    { name: "小红书", pkg: "com.xingin.xhs", verified: false },
    { name: "闲鱼", pkg: "com.taobao.idlefish", verified: true },
    { name: "王者荣耀", pkg: "com.tencent.tmgp.sgame", verified: true },
    { name: "三角洲行动", pkg: "com.tencent.tmgp.dfm", verified: false },
  ],
  watch: [{ name: "Rikkahub", pkg: "com.rikkahub", verified: false }],
  protectedApps: [
    "com.ai.assistance.operit",
    "com.tencent.mm",
    "com.google.android.apps.bard",
    "com.openai.chatgpt",
    "com.eg.android.AlipayGphone",
    "com.autonavi.minimap",
    "com.baidu.BaiduMap",
  ],
  timing: { graceMinutes: 30, tier2AfterMinutes: 30, maxSingleLockMinutes: 120, dailyUnlockHour: 23 },
  limits: { pausePerDay: 2, unlockPerDay: 2, emergencyPerWeek: 3, coolDownMinutes: 30 },
  judgeModel: "gpt-6-sol",
  confirmed: {
    protectedApps: true,
    unlockPerDay: true,
    coolDownMinutes: false,
    graceMinutes: true, // A2 等待回应 30 分钟
    judgeModel: true,
    tier1: false,
    tier2: false,
    tier2AfterMinutes: false,
    maxSingleLockMinutes: false,
    dailyUnlockHour: false,
    pausePerDay: false,
    emergencyPerWeek: false,
  },
};

export async function loadContract(): Promise<Contract> {
  const saved = await readJson<Partial<Contract>>(CONTRACT_PATH);
  if (!saved) return DEFAULT_CONTRACT;
  // 用户/流程线写过的契约优先，缺的字段回落到默认
  return {
    ...DEFAULT_CONTRACT,
    ...saved,
    timing: { ...DEFAULT_CONTRACT.timing, ...(saved.timing ?? {}) },
    limits: { ...DEFAULT_CONTRACT.limits, ...(saved.limits ?? {}) },
    confirmed: { ...DEFAULT_CONTRACT.confirmed, ...(saved.confirmed ?? {}) },
    tier1: saved.tier1 ?? DEFAULT_CONTRACT.tier1,
    tier2: saved.tier2 ?? DEFAULT_CONTRACT.tier2,
    watch: saved.watch ?? DEFAULT_CONTRACT.watch,
  };
}

// ---------- 派活 ----------

export type StandardType = "product" | "count" | "self";

export interface Assignment {
  id: string;
  task: string;
  createdAt: number; // ms
  deadline: number; // ms
  standard: { type: StandardType; detail: string; filePath?: string; count?: number };
  active: boolean;
}

export async function loadAssignment(): Promise<Assignment | null> {
  const a = await readJson<Assignment>(ASSIGNMENT_PATH);
  return a && a.active ? a : null;
}

export async function setAssignment(input: {
  task: string;
  deadlineMs: number;
  standard: Assignment["standard"];
}): Promise<Assignment> {
  const now = Date.now();
  const assignment: Assignment = {
    // 同一毫秒内派两次活也不能撞 id，否则上一次的交差会算到这一次头上
    id: `A-${now}-${Math.random().toString(36).slice(2, 8)}`,
    task: input.task,
    createdAt: now,
    deadline: input.deadlineMs,
    standard: input.standard,
    active: true,
  };
  await Tools.Files.write(ASSIGNMENT_PATH, JSON.stringify(assignment), false);
  return assignment;
}

export async function clearAssignment(): Promise<void> {
  const a = await readJson<Assignment>(ASSIGNMENT_PATH);
  if (a) await Tools.Files.write(ASSIGNMENT_PATH, JSON.stringify({ ...a, active: false }), false);
}

// ---------- 交差 ----------

export interface Delivery {
  ts: number;
  iso: string;
  assignmentId: string;
  task: string;
  quote: string;
  filePath: string;
  // 第一阶段的核对结果只有代码能判的那种；靠模型判的留给后续阶段
  autoCheck: "PRODUCT_OK" | "PRODUCT_MISSING" | "NEEDS_JUDGE" | "NONE";
}

// 只有产物型能被代码判：文件存在，且修改时间在派活之后
async function autoCheck(assignment: Assignment, filePath: string): Promise<Delivery["autoCheck"]> {
  if (assignment.standard.type !== "product") return "NEEDS_JUDGE";
  const target = filePath || assignment.standard.filePath || "";
  if (!target) return "NEEDS_JUDGE";
  try {
    const info = await Tools.Files.info(target);
    if (!info?.exists) return "PRODUCT_MISSING";
    const modified = parseModified(info.lastModified);
    if (modified != null && modified >= assignment.createdAt) return "PRODUCT_OK";
    return "PRODUCT_MISSING";
  } catch {
    return "PRODUCT_MISSING";
  }
}

function parseModified(value: string): number | null {
  if (!value) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})/.exec(value.trim());
  if (!m) return null;
  const [, y, mo, d, h, mi, s] = m;
  const t = new Date(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s)).getTime();
  return Number.isFinite(t) ? t : null;
}

export async function recordDelivery(quote: string, filePath = ""): Promise<{ delivery: Delivery | null; reason?: string }> {
  const assignment = await loadAssignment();
  if (!assignment) return { delivery: null, reason: "现在没有在督促的任务" };
  const now = Date.now();
  const delivery: Delivery = {
    ts: Math.floor(now / 1000),
    iso: formatDateTime(now),
    assignmentId: assignment.id,
    task: assignment.task,
    quote: quote.trim(),
    filePath: filePath.trim(),
    autoCheck: await autoCheck(assignment, filePath.trim()),
  };
  await Tools.Files.write(DELIVERY_LOG, JSON.stringify(delivery) + "\n", true);
  // 产物已核对通过就撤下这次派活；其余等判官（后续阶段），先留着
  if (delivery.autoCheck === "PRODUCT_OK") await clearAssignment();
  return { delivery };
}

async function readDeliveries(assignmentId: string): Promise<Delivery[]> {
  if (!(await fileExists(DELIVERY_LOG))) return [];
  const { lines } = await readAll(DELIVERY_LOG, 60);
  const out: Delivery[] = [];
  for (const line of lines) {
    try {
      const d = JSON.parse(line) as Delivery;
      if (d.assignmentId === assignmentId) out.push(d);
    } catch {
      // 跳过坏行
    }
  }
  return out;
}

// ---------- 判定（纯代码，不问模型该不该罚） ----------

export type WardenStage =
  | "IDLE" // 没有派活
  | "ON_TRACK" // 截止前
  | "GRACE" // 截止后宽限期
  | "TIER1" // 该锁一级了
  | "TIER2" // 该升二级了
  | "DELIVERED"; // 交了，等核对/已通过

export interface WardenView {
  mode: WardenMode;
  stage: WardenStage;
  assignment: Assignment | null;
  now: number;
  minutesToDeadline: number | null;
  ignored: boolean; // 截止已过、没交差、也没有新的用户进展
  wouldLock: AppEntry[]; // 演练：这一拍本来会锁的
  lastDelivery: Delivery | null;
  line: string; // 给"她"念的一句话
  reasons: string[];
}

// 忽略 = 截止过了，且派活之后既没交差、也没有新的用户进展记录
function sawUserActivity(assignment: Assignment, deliveries: Delivery[], progress: ProgressRecord[]): boolean {
  if (deliveries.length > 0) return true;
  const since = Math.floor(assignment.createdAt / 1000);
  return progress.some((p) => p.origin === "REAL_USER" && p.ts >= since);
}

export function evaluate(
  contract: Contract,
  assignment: Assignment | null,
  deliveries: Delivery[],
  progress: ProgressRecord[],
  now: number
): WardenView {
  const base: WardenView = {
    mode: WARDEN_MODE,
    stage: "IDLE",
    assignment,
    now,
    minutesToDeadline: null,
    ignored: false,
    wouldLock: [],
    lastDelivery: deliveries[deliveries.length - 1] ?? null,
    line: "",
    reasons: [],
  };
  if (!assignment) {
    base.line = "现在没什么要盯的，去做你想做的吧。";
    return base;
  }

  const okDelivery = deliveries.find((d) => d.autoCheck === "PRODUCT_OK");
  if (okDelivery) {
    base.stage = "DELIVERED";
    base.line = `「${assignment.task}」交上来了，我看过了，这一幕收工。`;
    base.reasons.push("产物已核对通过");
    return base;
  }

  const minutesLeft = Math.round((assignment.deadline - now) / 60000);
  base.minutesToDeadline = minutesLeft;
  const overdue = -minutesLeft; // 超时多少分钟
  const ignored = overdue > 0 && !sawUserActivity(assignment, deliveries, progress);
  base.ignored = ignored;

  if (minutesLeft > 0) {
    base.stage = "ON_TRACK";
    base.line =
      minutesLeft <= 10
        ? `还有 ${minutesLeft} 分钟就到「${assignment.task}」的点了，收个尾吧。`
        : `「${assignment.task}」还剩 ${minutesLeft} 分钟。`;
    return base;
  }

  // 已超时
  if (deliveries.length > 0) {
    // 交了但还没被判为通过（产物缺失或要判官）
    base.stage = "GRACE";
    base.line = `你交的东西我先收下了，但还没算数——${lastCheckHint(base.lastDelivery)}`;
    base.reasons.push("已交差，等核对");
    return base;
  }

  if (ignored) {
    if (overdue >= contract.timing.graceMinutes + contract.timing.tier2AfterMinutes) {
      base.stage = "TIER2";
      base.wouldLock = [...contract.tier1, ...contract.tier2];
      base.reasons.push(`超时 ${overdue} 分钟，宽限和一级都过了`);
      base.line = "这一幕该换布景了。再不回来，能玩的都得先收走。";
    } else if (overdue >= contract.timing.graceMinutes) {
      base.stage = "TIER1";
      base.wouldLock = contract.tier1;
      base.reasons.push(`超时 ${overdue} 分钟，宽限期过了`);
      base.line = "时间到了，你没理我。娱乐的先收走，回来把事做了。";
    } else {
      base.stage = "GRACE";
      base.reasons.push(`超时 ${overdue} 分钟，还在 ${contract.timing.graceMinutes} 分钟宽限里`);
      base.line = `到点了，「${assignment.task}」呢？还有 ${contract.timing.graceMinutes - overdue} 分钟宽限。`;
    }
  } else {
    // 超时了但用户有在推进（有进展记录），先不升级
    base.stage = "GRACE";
    base.reasons.push("超时了，但你还在动，先不锁");
    base.line = "过点了，不过看得出你在弄，那我等你交。";
  }
  return base;
}

function lastCheckHint(d: Delivery | null): string {
  if (!d) return "再具体点。";
  if (d.autoCheck === "PRODUCT_MISSING") return "没找到你说的那个文件，或者它还是派活之前的。";
  return "得让我（或核对模型）看看够不够。";
}

// ---------- tick：第一阶段只记账，绝不真锁 ----------

export interface TickResult {
  stage: WardenStage;
  mode: WardenMode;
  wouldLock: string[];
  ignored: boolean;
  logged: boolean;
  note: string;
}

export async function tick(now: number = Date.now()): Promise<TickResult> {
  const contract = await loadContract();
  const assignment = await loadAssignment();
  const deliveries = assignment ? await readDeliveries(assignment.id) : [];
  const progress = await readRecentProgress(30).catch(() => [] as ProgressRecord[]);
  const view = evaluate(contract, assignment, deliveries, progress, now);

  // 硬保险：第一阶段无论如何都不调用 app_suspender。
  // 这里只把"本来会锁什么"写进演练日志。
  let logged = false;
  if (view.stage === "TIER1" || view.stage === "TIER2") {
    const record = {
      ts: Math.floor(now / 1000),
      iso: formatDateTime(now),
      mode: WARDEN_MODE,
      stage: view.stage,
      task: assignment?.task ?? "",
      would_lock: view.wouldLock.map((a) => ({ name: a.name, pkg: a.pkg, verified: a.verified })),
      overdue_min: view.minutesToDeadline != null ? -view.minutesToDeadline : null,
      reasons: view.reasons,
      note: "演练：未执行任何冻结",
    };
    try {
      await Tools.Files.write(DRYRUN_LOG, JSON.stringify(record) + "\n", true);
      logged = true;
    } catch {
      // 写不了不影响判定
    }
  }
  return {
    stage: view.stage,
    mode: WARDEN_MODE,
    wouldLock: view.wouldLock.map((a) => a.name),
    ignored: view.ignored,
    logged,
    note: "第一阶段：只演练，未冻结任何应用",
  };
}

export interface DryRunRow {
  iso: string;
  stage: string;
  task: string;
  wouldLock: string[];
  overdueMin: number | null;
}

export async function readDryRunLog(limit = 20): Promise<DryRunRow[]> {
  if (!(await fileExists(DRYRUN_LOG))) return [];
  const { lines } = await readAll(DRYRUN_LOG, limit);
  const out: DryRunRow[] = [];
  for (const line of lines) {
    try {
      const r = JSON.parse(line) as {
        iso: string;
        stage: string;
        task: string;
        would_lock?: { name: string }[];
        overdue_min?: number | null;
      };
      out.push({
        iso: r.iso,
        stage: r.stage,
        task: r.task,
        wouldLock: (r.would_lock ?? []).map((a) => a.name),
        overdueMin: r.overdue_min ?? null,
      });
    } catch {
      // 跳过坏行
    }
  }
  return out.reverse();
}

// ---------- 汇总给 UI 和 AI ----------

export interface WardenReport {
  view: WardenView;
  contract: Contract;
  dryRun: DryRunRow[];
  unconfirmed: string[];
  error?: string;
}

export async function collectWarden(now: number = Date.now()): Promise<WardenReport> {
  const contract = await loadContract();
  try {
    const assignment = await loadAssignment();
    const deliveries = assignment ? await readDeliveries(assignment.id) : [];
    const progress = await readRecentProgress(30).catch(() => [] as ProgressRecord[]);
    const view = evaluate(contract, assignment, deliveries, progress, now);
    const dryRun = await readDryRunLog(20);
    const unconfirmed = Object.entries(contract.confirmed)
      .filter(([, v]) => !v)
      .map(([k]) => k);
    return { view, contract, dryRun, unconfirmed };
  } catch (error) {
    return {
      view: evaluate(contract, null, [], [], now),
      contract,
      dryRun: [],
      unconfirmed: [],
      error: errorText(error),
    };
  }
}

const STAGE_LABEL: Record<WardenStage, string> = {
  IDLE: "空闲",
  ON_TRACK: "进行中",
  GRACE: "宽限期",
  TIER1: "该锁一级（演练）",
  TIER2: "该升二级（演练）",
  DELIVERED: "已交差",
};

export function stageLabel(stage: WardenStage): string {
  return STAGE_LABEL[stage];
}

export function wardenReportToText(r: WardenReport): string {
  const out: string[] = [`督促（演练模式，不会真锁）· ${formatDateTime(r.view.now)}`];
  const v = r.view;
  if (!v.assignment) {
    out.push("当前没有在督促的任务。");
  } else {
    out.push(`任务：${v.assignment.task}｜状态：${stageLabel(v.stage)}${v.minutesToDeadline != null ? `｜距截止 ${v.minutesToDeadline} 分钟` : ""}`);
    if (v.wouldLock.length) out.push(`演练：这一拍本来会锁 ${v.wouldLock.map((a) => a.name).join("、")}`);
    if (v.reasons.length) out.push(`依据：${v.reasons.join("；")}`);
    out.push(`她会说：${v.line}`);
  }
  if (r.dryRun.length) {
    out.push(`最近演练记录 ${r.dryRun.length} 条，最新：${r.dryRun[0].iso} ${r.dryRun[0].stage} → ${r.dryRun[0].wouldLock.join("、") || "—"}`);
  }
  if (r.unconfirmed.length) out.push(`还没拍板的契约项：${r.unconfirmed.join("、")}（真锁前要逐项确认）`);
  if (r.error) out.push(`读取失败：${r.error}`);
  return out.join("\n");
}
