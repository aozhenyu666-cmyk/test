// 用户决定：一律按北京时间（UTC+8，无夏令时）算"今天""几点"，不看手机时区。
const BJ_OFFSET_MS = 8 * 3600 * 1000;

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

export interface BjParts {
  y: number;
  mo: number;
  d: number;
  h: number;
  mi: number;
  s: number;
}

export function bjParts(ms: number): BjParts {
  const t = new Date(ms + BJ_OFFSET_MS);
  return {
    y: t.getUTCFullYear(),
    mo: t.getUTCMonth() + 1,
    d: t.getUTCDate(),
    h: t.getUTCHours(),
    mi: t.getUTCMinutes(),
    s: t.getUTCSeconds(),
  };
}

export function bjDateKey(ms: number): string {
  const p = bjParts(ms);
  return `${p.y}${pad2(p.mo)}${pad2(p.d)}`;
}

export function bjIso(ms: number): string {
  const p = bjParts(ms);
  return `${p.y}-${pad2(p.mo)}-${pad2(p.d)} ${pad2(p.h)}:${pad2(p.mi)}:${pad2(p.s)}`;
}

export function bjHM(ms: number): string {
  const p = bjParts(ms);
  return `${pad2(p.h)}:${pad2(p.mi)}`;
}

export function bjMinutesOfDay(ms: number): number {
  const p = bjParts(ms);
  return p.h * 60 + p.mi;
}

// 北京时间当天 00:00 的时间戳
export function bjMidnight(ms: number): number {
  const p = bjParts(ms);
  return Date.UTC(p.y, p.mo - 1, p.d) - BJ_OFFSET_MS;
}

// "13:00" → 今天（北京）这个时刻；已经过了就算明天
export function bjTimeToday(hm: string, nowMs: number): number | null {
  const m = /^(\d{1,2})[:：](\d{2})$/.exec(hm.trim());
  if (!m) return null;
  const h = Number(m[1]);
  const mi = Number(m[2]);
  if (h > 23 || mi > 59) return null;
  let t = bjMidnight(nowMs) + (h * 60 + mi) * 60000;
  if (t <= nowMs) t += 24 * 3600 * 1000;
  return t;
}

// 设备写的本地时间字符串（judge_save2.sh 等没设 TZ 的脚本、Files.info.lastModified）按设备时区解析
export function parseDeviceLocal(value: string | undefined | null): number | null {
  if (!value) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(value.trim());
  if (!m) return null;
  const t = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]), Number(m[6])).getTime();
  return Number.isFinite(t) ? t : null;
}

export function minutesAgoText(ms: number, nowMs: number): string {
  const min = Math.max(0, Math.round((nowMs - ms) / 60000));
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  const h = Math.floor(min / 60);
  return h < 24 ? `${h} 小时前` : `${Math.floor(h / 24)} 天前`;
}

// 脚本写的时间字符串可能是设备时区，也可能是北京时间（有的脚本设了 TZ=Asia/Shanghai）。
// 两种都算一遍，取不晚于现在、且最接近现在的那个。
export function parseScriptTime(value: string | undefined | null, nowMs: number): number | null {
  if (!value) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(value.trim());
  if (!m) return null;
  const [y, mo, d, h, mi, s] = m.slice(1).map(Number);
  const device = new Date(y, mo - 1, d, h, mi, s).getTime();
  const beijing = Date.UTC(y, mo - 1, d, h, mi, s) - BJ_OFFSET_MS;
  const ok = [device, beijing].filter((t) => Number.isFinite(t) && t <= nowMs + 2 * 60 * 1000);
  if (ok.length) return Math.max(...ok);
  return Math.min(device, beijing);
}
