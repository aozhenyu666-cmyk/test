// "她来找你"：一次打卡 = 念一句话 + 弹出主控台 + 留一条待回应。回应就是一条用户进展。

const CHECKIN_DIR = "/sdcard/Download/Operit/companion/checkins";
const LINE_NUMBER_PREFIX = /^\s*\d+\| ?/;

export type ChannelStatus = "ACCEPTED" | "FAILED" | "SKIPPED";

export interface CheckinRecord {
  id: string;
  ts: number;
  iso: string;
  type: "CHECKIN";
  level: number;
  score: number;
  line: string;
  speak: ChannelStatus;
  popup: ChannelStatus;
  speak_error?: string;
  speak_via?: string;
  trigger: "workflow" | "manual";
}

function pad2(value: number): string {
  return value < 10 ? `0${value}` : String(value);
}

function dateKey(d: Date): string {
  return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}`;
}

export function isoLocal(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}

export function checkinPath(date: string): string {
  return `${CHECKIN_DIR}/${date}.jsonl`;
}

async function readLines(path: string, tail: number): Promise<string[]> {
  const exists = await Tools.Files.exists(path);
  if (!exists?.exists) return [];
  const probe = await Tools.Files.readPart(path, 1, 1);
  if (probe.totalLines <= 0) return [];
  const start = Math.max(1, probe.totalLines - tail + 1);
  const part = await Tools.Files.readPart(path, start, probe.totalLines);
  return part.content
    .split("\n")
    .filter((line) => LINE_NUMBER_PREFIX.test(line))
    .map((line) => line.replace(LINE_NUMBER_PREFIX, ""))
    .filter((line) => line.trim());
}

export async function readRecentCheckins(now: number, limit = 10): Promise<CheckinRecord[]> {
  const out: CheckinRecord[] = [];
  for (const date of [dateKey(new Date(now - 24 * 3600 * 1000)), dateKey(new Date(now))]) {
    for (const line of await readLines(checkinPath(date), limit)) {
      try {
        const record = JSON.parse(line) as CheckinRecord;
        if (record?.type === "CHECKIN") out.push(record);
      } catch {
        // 跳过损坏的行
      }
    }
  }
  return out.slice(-limit).reverse();
}

export async function appendCheckin(record: CheckinRecord): Promise<void> {
  const date = dateKey(new Date(record.ts * 1000));
  await Tools.Files.write(checkinPath(date), `${JSON.stringify(record)}\n`, true);
}

export type CheckinDecision = "GO" | "SKIP_QUIET" | "SKIP_COOLDOWN" | "SKIP_FINE" | "SKIP_SAME";

export const QUIET_START_MIN = 23 * 60 + 30;
export const QUIET_END_MIN = 8 * 60;
const COOLDOWN_SEC = 45 * 60;
const SAME_LEVEL_SEC = 2 * 3600;
const CALM_GAP_SEC = 3 * 3600;

// 打扰要有节制：深夜不打扰；刚找过不重复找；心情没变就不重复说；安心时很久没动静才问一句
export function decideCheckin(
  now: number,
  level: number,
  lastCheckin: CheckinRecord | null,
  lastProgressTs: number | null
): CheckinDecision {
  const d = new Date(now);
  const minutes = d.getHours() * 60 + d.getMinutes();
  if (minutes >= QUIET_START_MIN || minutes < QUIET_END_MIN) return "SKIP_QUIET";
  const nowSec = Math.floor(now / 1000);
  const sinceLast = lastCheckin ? nowSec - lastCheckin.ts : Infinity;
  if (sinceLast < COOLDOWN_SEC) return "SKIP_COOLDOWN";
  if (level === 0) {
    const recentProgress = lastProgressTs != null && nowSec - lastProgressTs < 2 * 3600;
    return recentProgress || sinceLast < CALM_GAP_SEC ? "SKIP_FINE" : "GO";
  }
  if (lastCheckin && lastCheckin.level === level && level < 3 && sinceLast < SAME_LEVEL_SEC) return "SKIP_SAME";
  return "GO";
}

export function newCheckinId(ts: number): string {
  return `CHK-${ts}-${Math.floor(Math.random() * 1e5)}`;
}
