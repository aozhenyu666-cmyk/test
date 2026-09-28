import { listNames, parseJsonl, readJson, readLines, tailLines } from "./fsx.js";
import { ROOT } from "./memory.js";
import { bjDateKey, parseDeviceLocal } from "./time.js";

export interface Sample {
  ts: number; // ms
  pkg: string;
  ontask: string; // "1" | "0" | "NONE"
  screen: string;
}

export interface Verdict {
  ts: number;
  verdict: string;
  conf: number;
  why: string;
  next: string;
}

export interface ProgressItem {
  ts: number; // ms
  kind: string;
  quote: string;
  note: string;
  via: string;
}

export interface Assignment {
  id: string;
  task: string;
  createdAt: number;
  deadline: number;
  active: boolean;
}

export interface Facts {
  now: number;
  task: string;
  taskState: string;
  samples: Sample[]; // 新 → 旧
  otherAlerts: number[]; // 非大脑发出的 ALERT 时间（ms），用来避免两个声音撞车
  verdict: Verdict | null;
  progress: ProgressItem[]; // 最近 24 小时，新 → 旧
  assignment: Assignment | null;
  lockable: string[]; // ent.list 里已启用冻结的包（用户已批准的锁机对象）
  protectedPkgs: string[];
  jobApps: string[];
  shotFact: string;
}

const APP_NAMES: Record<string, string> = {
  "tv.danmaku.bili": "B站",
  "com.ss.android.ugc.aweme": "抖音",
  "com.baidu.tieba": "贴吧",
  "com.tencent.tmgp.sgame": "王者荣耀",
  "com.xunmeng.pinduoduo": "拼多多",
  "com.phoenix.read": "红果短剧",
  "com.kylin.read": "红果漫剧",
  "com.twitter.android": "X",
  "com.xingin.xhs": "小红书",
  "com.taobao.idlefish": "闲鱼",
  "com.kurogame.mingchao": "鸣潮",
  "com.tencent.tmgp.dfm": "三角洲行动",
  "com.hpbr.bosszhipin": "Boss直聘",
  "com.zhaopin.social": "智联招聘",
  "com.job.android": "前程无忧",
  "com.openai.chatgpt": "ChatGPT",
  "com.anthropic.claude": "Claude",
  "com.tencent.mm": "微信",
  "org.telegram.messenger": "Telegram",
  "com.ai.assistance.operit": "Operit",
};

export function appName(pkg: string): string {
  return APP_NAMES[pkg] ?? pkg.split(".").pop() ?? pkg;
}

async function keyValues(path: string): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  for (const line of await readLines(path)) {
    const m = /^([A-Za-z_]+)=(.*)$/.exec(line.trim());
    if (m) out[m[1]] = m[2].trim();
  }
  return out;
}

async function pkgList(path: string): Promise<string[]> {
  return (await readLines(path))
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith("#") && /^[A-Za-z0-9._]+$/.test(l));
}

interface RawEvent {
  ts?: number;
  type?: string;
  pkg?: string;
  ontask?: string;
  screen?: string;
  src?: string;
}

async function readEvents(now: number): Promise<RawEvent[]> {
  const out: RawEvent[] = [];
  // 事件目录按北京时间分日；凌晨时把前一天的尾巴也带上
  const days = [...new Set([bjDateKey(now - 3 * 3600 * 1000), bjDateKey(now)])];
  for (const day of days) {
    out.push(...parseJsonl<RawEvent>(await tailLines(`${ROOT}/events/${day}/events.jsonl`, 120)));
  }
  const seen = new Set<string>();
  return out.filter((e) => {
    const k = `${e.type}-${e.ts}-${e.pkg ?? ""}-${e.src ?? ""}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

async function readVerdict(): Promise<Verdict | null> {
  const names = (await listNames(`${ROOT}/judge`)).filter((n) => /^\d{8}\.jsonl$/.test(n));
  // 文件名按设备日期命名，最新的不一定排最后；取最后两个里时间最新的一行
  let best: Verdict | null = null;
  for (const name of names.slice(-2)) {
    const [line] = (await tailLines(`${ROOT}/judge/${name}`, 1)).slice(-1);
    if (!line) continue;
    const [time, body = ""] = line.split("\t");
    const ts = parseDeviceLocal(time);
    const m = /^([A-Z_]+)\s+CONF=(\d+)/.exec(body.trim());
    if (!ts || !m) continue;
    const field = (key: string) => {
      const r = new RegExp(`${key}=(.*?)(?=\\s+(?:WHY|NEXT|EVIDENCE|RECOMMENDED_LEVEL)=|$)`).exec(body);
      return r ? r[1].trim() : "";
    };
    const v = { ts, verdict: m[1], conf: Number(m[2]), why: field("WHY"), next: field("NEXT") };
    if (!best || v.ts > best.ts) best = v;
  }
  return best;
}

interface RawProgress {
  ts?: number;
  origin?: string;
  kind?: string;
  user_quote?: string;
  note?: string;
  via?: string;
}

async function readProgress(now: number): Promise<ProgressItem[]> {
  const names = (await listNames(`${ROOT}/progress`)).filter((n) => /\.jsonl$/.test(n)).slice(-2);
  const items: ProgressItem[] = [];
  for (const name of names) {
    for (const p of parseJsonl<RawProgress>(await tailLines(`${ROOT}/progress/${name}`, 60))) {
      if (p.origin !== "REAL_USER" || typeof p.ts !== "number") continue;
      const ts = p.ts * 1000;
      if (now - ts > 24 * 3600 * 1000 || ts > now + 60000) continue;
      items.push({ ts, kind: p.kind ?? "note", quote: p.user_quote ?? "", note: p.note ?? "", via: p.via ?? "" });
    }
  }
  return items.sort((a, b) => b.ts - a.ts);
}

export async function gatherFacts(now: number): Promise<Facts> {
  const [taskKv, events, verdict, progress, assignmentRaw, lockable, protectedPkgs, taskApps, shots] = await Promise.all([
    keyValues(`${ROOT}/drift/task_state.txt`),
    readEvents(now),
    readVerdict(),
    readProgress(now),
    readJson<Assignment>(`${ROOT}/companion/warden/assignment.json`),
    pkgList(`${ROOT}/p2/ent.list`),
    pkgList(`${ROOT}/p2/protect.deny`),
    keyValues(`${ROOT}/p2/task_apps.conf`),
    tailLines(`${ROOT}/events/shot_facts.tsv`, 1),
  ]);

  const samples: Sample[] = events
    .filter((e) => e.type === "FOREGROUND" && typeof e.ts === "number")
    .map((e) => ({ ts: (e.ts as number) * 1000, pkg: e.pkg ?? "none", ontask: String(e.ontask ?? "NONE"), screen: e.screen ?? "" }))
    .filter((s) => now - s.ts < 3 * 3600 * 1000)
    .sort((a, b) => b.ts - a.ts);

  const otherAlerts = events
    .filter((e) => e.type === "ALERT" && typeof e.ts === "number" && !String(e.src ?? "").startsWith("xiaoman"))
    .map((e) => (e.ts as number) * 1000);

  return {
    now,
    task: taskKv.TASK ?? "",
    taskState: taskKv.STATE ?? "",
    samples,
    otherAlerts,
    verdict,
    progress,
    assignment: assignmentRaw && assignmentRaw.active ? assignmentRaw : null,
    lockable,
    protectedPkgs,
    jobApps: (taskApps.JOB_SEARCH ?? "").split(",").map((s) => s.trim()).filter(Boolean),
    shotFact: shots[0] ?? "",
  };
}
