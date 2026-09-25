// 系统体检：只看关键产物文件多久没更新。更新了不代表内容正确，没更新基本说明那条链停了。

const ROOT = "/sdcard/Download/Operit";

export type HealthStatus = "FRESH" | "STALE" | "MISSING" | "UNKNOWN";

export interface HealthCheck {
  label: string;
  cadence: string;
  path: string;
  status: HealthStatus;
  modifiedAt: number | null;
  note: string;
}

interface CheckSpec {
  label: string;
  cadence: string;
  paths: (today: string, yesterday: string) => string[];
  staleAfterMin: number | null;
  note?: string;
}

const MIN = 60 * 1000;

const CHECKS: CheckSpec[] = [
  { label: "前台采样", cadence: "每 15 分钟", paths: () => [`${ROOT}/p2/obs_log.txt`], staleAfterMin: 35 },
  { label: "事件流", cadence: "每 15 分钟", paths: (t) => [`${ROOT}/events/${t}/events.jsonl`], staleAfterMin: 40 },
  { label: "用量刷新", cadence: "每小时", paths: () => [`${ROOT}/selfreview/.usage_fresh`], staleAfterMin: 130 },
  {
    label: "判断官",
    cadence: "每 30 分钟",
    paths: (t, y) => [`${ROOT}/judge/${t}.jsonl`, `${ROOT}/judge/${y}.jsonl`],
    staleAfterMin: 180,
    note: "证据没变时会跳过，不写新判断",
  },
  { label: "提醒通道", cadence: "每 30 分钟", paths: () => [`${ROOT}/drift/channel_health.tsv`], staleAfterMin: 75 },
  { label: "日报", cadence: "每天 23:55", paths: (t, y) => [`${ROOT}/events/${t}/digest.txt`, `${ROOT}/events/${y}/digest.txt`], staleAfterMin: 26 * 60 },
  { label: "当前任务", cadence: "随任务切换", paths: () => [`${ROOT}/drift/task_state.txt`], staleAfterMin: null },
];

function pad2(value: number): string {
  return value < 10 ? `0${value}` : String(value);
}

function dateKey(d: Date): string {
  return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}`;
}

// 宿主格式：本地时间 yyyy-MM-dd HH:mm:ss.SSS
export function parseLastModified(value: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})/.exec(String(value ?? ""));
  if (!m) return null;
  const [, y, mo, d, h, mi, s] = m.map(Number);
  const t = new Date(y, mo - 1, d, h, mi, s).getTime();
  return Number.isFinite(t) ? t : null;
}

async function checkOne(spec: CheckSpec, now: number): Promise<HealthCheck> {
  const today = dateKey(new Date(now));
  const yesterday = dateKey(new Date(now - 24 * 3600 * 1000));
  const candidates = spec.paths(today, yesterday);
  for (const path of candidates) {
    try {
      const info = await Tools.Files.info(path);
      if (!info || !info.exists) continue;
      const modifiedAt = parseLastModified(info.lastModified);
      if (modifiedAt == null) {
        return { label: spec.label, cadence: spec.cadence, path, status: "UNKNOWN", modifiedAt: null, note: "读不出修改时间" };
      }
      const stale = spec.staleAfterMin != null && now - modifiedAt > spec.staleAfterMin * MIN;
      return {
        label: spec.label,
        cadence: spec.cadence,
        path,
        status: stale ? "STALE" : "FRESH",
        modifiedAt,
        note: spec.note ?? "",
      };
    } catch (error) {
      return {
        label: spec.label,
        cadence: spec.cadence,
        path,
        status: "UNKNOWN",
        modifiedAt: null,
        note: String((error as { message?: unknown })?.message ?? error),
      };
    }
  }
  return { label: spec.label, cadence: spec.cadence, path: candidates[0], status: "MISSING", modifiedAt: null, note: "文件不存在" };
}

export async function loadHealth(now: number): Promise<HealthCheck[]> {
  return Promise.all(CHECKS.map((spec) => checkOne(spec, now)));
}
