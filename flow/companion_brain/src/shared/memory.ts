import { appendJsonl, exists, parseJsonl, readJson, readText, tailLines, writeText } from "./fsx.js";
import { bjDateKey, bjIso } from "./time.js";

export const ROOT = "/sdcard/Download/Operit";
export const BRAIN_DIR = `${ROOT}/companion/brain`;
export const PATHS = {
  config: `${BRAIN_DIR}/config.json`,
  profile: `${BRAIN_DIR}/profile.md`,
  commitments: `${BRAIN_DIR}/commitments.jsonl`,
  said: `${BRAIN_DIR}/said.jsonl`,
  state: `${BRAIN_DIR}/state.json`,
  actions: `${BRAIN_DIR}/actions.jsonl`,
  reflections: `${BRAIN_DIR}/reflections.jsonl`,
};

export interface BrainConfig {
  companion_chat_id: string;
  companion_card_name: string;
  // 每小时最多主动开口几次（用户定：4）
  max_speaks_per_hour: number;
  // 主动开口之间的最短间隔（分钟）
  min_gap_minutes: number;
  // 跑偏后多久没回应才允许锁（分钟）
  lock_grace_minutes: number;
  // 同一个 App 锁过之后多久内不重复入队（分钟）
  relock_cooldown_minutes: number;
  // 北京时间安静时段
  quiet_start: string;
  quiet_end: string;
  // 总开关：false 时只思考和记账，不开口、不动手（用于并行观察）
  act: boolean;
}

export const DEFAULT_CONFIG: BrainConfig = {
  companion_chat_id: "52a18815-2c07-4f6d-bbae-ae5d040daffb",
  companion_card_name: "小满·陪伴",
  max_speaks_per_hour: 4,
  min_gap_minutes: 12,
  lock_grace_minutes: 30,
  relock_cooldown_minutes: 120,
  quiet_start: "23:30",
  quiet_end: "08:00",
  act: true,
};

export async function loadConfig(): Promise<BrainConfig> {
  const saved = await readJson<Partial<BrainConfig>>(PATHS.config);
  return { ...DEFAULT_CONFIG, ...(saved ?? {}) };
}

// ---------- 关于 TA ----------

const DEFAULT_PROFILE = `# 关于 TA

> 这份档案会在每次和小满说话时自动带给她。TA 可以直接改这个文件。
> "TA 自己写的"一节永远不会被自动改动；"观察"一节由每晚的复盘追加。

## TA 自己写的
（空。想让小满记住什么，就写在这里。）

## 目标
- 当前任务：求职投递（Boss 直聘等招聘 App 投简历）。

## 已知的习惯和偏好（外部 Agent 初始整理，TA 可改）
- 常用语音输入，话长、有口语，要抓意思不抠字面。
- 提醒如果没有后果、说得像机器，TA 会习惯性关掉、忽略。
- 不喜欢同一句话反复催；已经做完的事再被催会很烦。
- ChatGPT、Claude、DeepSeek 等 AI 应用是 TA 干活的工具，不算分心。
- 希望小满像一个真人：有记忆、不接受敷衍、但不死板。

## 管用的说法

## 不管用的说法

## 观察（自动追加）
`;

export async function loadProfile(): Promise<string> {
  if (!(await exists(PATHS.profile))) {
    await writeText(PATHS.profile, DEFAULT_PROFILE);
    return DEFAULT_PROFILE;
  }
  return readText(PATHS.profile);
}

export async function appendObservation(lines: string[], now: number): Promise<void> {
  const clean = lines.map((l) => l.replace(/\s+/g, " ").trim()).filter(Boolean).slice(0, 6);
  if (clean.length === 0) return;
  const profile = await loadProfile();
  const block = `\n### ${bjDateKey(now)}\n${clean.map((l) => `- ${l}`).join("\n")}\n`;
  await writeText(PATHS.profile, profile.replace(/\s*$/, "\n") + block);
}

// ---------- 承诺 ----------

export type CommitmentStatus = "open" | "kept" | "broken" | "cancelled";

export interface Commitment {
  id: string;
  ts: number;
  text: string;
  due_ts: number;
  quote: string;
  source: "user" | "assigned";
  status: CommitmentStatus;
  closed_ts?: number;
  note?: string;
}

interface CommitmentEvent {
  type: "add" | "status";
  id: string;
  ts: number;
  commitment?: Commitment;
  status?: CommitmentStatus;
  note?: string;
}

export async function loadCommitments(): Promise<Commitment[]> {
  const events = parseJsonl<CommitmentEvent>(await tailLines(PATHS.commitments, 400));
  const map = new Map<string, Commitment>();
  for (const e of events) {
    if (e.type === "add" && e.commitment) map.set(e.id, { ...e.commitment });
    else if (e.type === "status" && e.status && map.has(e.id)) {
      const c = map.get(e.id)!;
      c.status = e.status;
      c.closed_ts = e.ts;
      if (e.note) c.note = e.note;
    }
  }
  return [...map.values()].sort((a, b) => a.due_ts - b.due_ts);
}

export async function addCommitment(input: Omit<Commitment, "id" | "status" | "ts">, now: number): Promise<Commitment> {
  const c: Commitment = { id: `C-${now}-${Math.floor(Math.random() * 1e5)}`, ts: now, status: "open", ...input };
  await appendJsonl(PATHS.commitments, { type: "add", id: c.id, ts: now, commitment: c });
  return c;
}

export async function setCommitmentStatus(id: string, status: CommitmentStatus, now: number, note = ""): Promise<void> {
  await appendJsonl(PATHS.commitments, { type: "status", id, ts: now, status, ...(note ? { note } : {}) });
}

// ---------- 小满主动说过的话 ----------

export interface SaidRecord {
  ts: number;
  iso: string;
  stage: string;
  line: string;
  action: string;
  pkg?: string;
  why?: string;
  delivered: string;
}

export async function recentSaid(n: number): Promise<SaidRecord[]> {
  return parseJsonl<SaidRecord>(await tailLines(PATHS.said, n));
}

export async function appendSaid(record: Omit<SaidRecord, "iso">): Promise<void> {
  await appendJsonl(PATHS.said, { ...record, iso: bjIso(record.ts) });
}

export async function logAction(record: Record<string, unknown>): Promise<void> {
  await appendJsonl(PATHS.actions, record);
}

// ---------- 状态 ----------

export interface Episode {
  start_ts: number;
  pkg: string;
  nudges: number;
  first_nudge_ts: number | null;
  last_nudge_ts: number | null;
  locked: boolean;
}

export interface BrainState {
  episode: Episode | null;
  speaks: number[];
  last_sig: string;
  last_think_ts: number;
  last_shot_ts: number;
  last_reflect_date: string;
  locks: Record<string, number>;
  last_idle_checkin_ts: number;
  asked_commitments: Record<string, number>;
}

export const EMPTY_STATE: BrainState = {
  episode: null,
  speaks: [],
  last_sig: "",
  last_think_ts: 0,
  last_shot_ts: 0,
  last_reflect_date: "",
  locks: {},
  last_idle_checkin_ts: 0,
  asked_commitments: {},
};

export async function loadState(): Promise<BrainState> {
  const saved = await readJson<Partial<BrainState>>(PATHS.state);
  return { ...EMPTY_STATE, ...(saved ?? {}), locks: { ...(saved?.locks ?? {}) }, asked_commitments: { ...(saved?.asked_commitments ?? {}) } };
}

export async function saveState(state: BrainState): Promise<void> {
  await writeText(PATHS.state, JSON.stringify(state));
}
