export const MINUTE = 60_000;
export const HOUR = 3_600_000;
export const DAY = 86_400_000;

export function parseTs(value) {
  if (typeof value === 'number') return value;
  return Date.parse(value);
}

export function toIso(ts) {
  return new Date(ts).toISOString();
}

export function pad(n) {
  return String(n).padStart(2, '0');
}

/** Wall-clock parts in a fixed offset (default UTC+8, no DST). */
export function localParts(ts, tzOffsetMinutes = 480) {
  const d = new Date(ts + tzOffsetMinutes * MINUTE);
  return {
    year: d.getUTCFullYear(),
    month: d.getUTCMonth() + 1,
    day: d.getUTCDate(),
    hour: d.getUTCHours(),
    minute: d.getUTCMinutes(),
    weekday: d.getUTCDay(),
  };
}

export function localDateKey(ts, tzOffsetMinutes = 480) {
  const p = localParts(ts, tzOffsetMinutes);
  return `${p.year}-${pad(p.month)}-${pad(p.day)}`;
}

export function localHourFloat(ts, tzOffsetMinutes = 480) {
  const p = localParts(ts, tzOffsetMinutes);
  return p.hour + p.minute / 60;
}

/** Build a timestamp for a local wall-clock time, offset by days from `ts`. */
export function atLocal(ts, dayOffset, hour, minute = 0, tzOffsetMinutes = 480) {
  const p = localParts(ts, tzOffsetMinutes);
  const utc = Date.UTC(p.year, p.month - 1, p.day + dayOffset, hour, minute, 0, 0);
  return utc - tzOffsetMinutes * MINUTE;
}

export function nextWeekday(ts, targetDow, hour, tzOffsetMinutes = 480, strictNext = false) {
  const p = localParts(ts, tzOffsetMinutes);
  let delta = (targetDow - p.weekday + 7) % 7;
  if (delta === 0) {
    delta = strictNext || localHourFloat(ts, tzOffsetMinutes) >= hour ? 7 : 0;
  }
  return atLocal(ts, delta, hour, 0, tzOffsetMinutes);
}

export function addDays(ts, n) {
  return ts + n * DAY;
}

export function addHours(ts, n) {
  return ts + n * HOUR;
}

export function daysBetween(a, b) {
  return (b - a) / DAY;
}

export function isSameLocalDay(a, b, tzOffsetMinutes = 480) {
  return localDateKey(a, tzOffsetMinutes) === localDateKey(b, tzOffsetMinutes);
}
