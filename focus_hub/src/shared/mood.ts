import type { CheckinRecord } from "./checkin.js";
import type { ProgressRecord } from "./progress.js";
import type { FocusDay } from "./snapshot.js";

// 心情只是把真实数据和规则阶梯换一种说法呈现；它不执行任何动作
export const MOOD_LEVELS = ["安心", "在意", "担心", "要谈谈"] as const;
const LEVEL_FLOORS = [0, 20, 45, 70];

export interface MoodInput {
  now: number;
  task: string;
  focus: FocusDay | null;
  progress: ProgressRecord[];
  checkins: CheckinRecord[];
}

// 花火的口吻：每档几种说法，按时段轮换，并避开上一次打卡说过的那句
const LINES = {
  goodWithProgress: [
    "{task}今天已经推进了 {n} 步，这出戏越来越好看了。",
    "嗯哼，{n} 条进展——主角状态不错嘛，接着演。",
    "照这个节奏，今天能有个好结局。",
  ],
  idle: [
    "舞台都搭好了，主角怎么还没登场？",
    "今天的{task}，打算从哪一幕开始？",
    "我在台下等着呢，先说说今天想做什么。",
  ],
  fresh: ["今天还没看到你的动静呢——在忙什么呀？", "幕布还没拉开？我可等着看呢。"],
  drifting: [
    "{app}那一幕看挺久了吧？该回{task}了哦。",
    "嘘——{task}那边的观众还在等主角呢。",
    "这段剧情有点拖了，我们换回{task}好不好？",
  ],
  driftingNoApp: ["好像有点走神啦，{task}那边还等着你呢。", "这一幕节奏慢下来了哦，{task}还记得吗？"],
  stuck: ["卡在「{quote}」了？说说看，也许换个演法就过去了。", "「{quote}」这一关，要不要我陪你拆一拆？"],
  worried: [
    "离开{task}有一阵了……是剧本卡住了，还是演员跑了？",
    "台下有点安静了呢。{task}那边，发生什么了？",
  ],
  talk: ["{app}先放下。这一幕，我们得好好谈谈。", "再这样演下去，这一幕可要换布景了哦——先停一下？"],
  talkNoApp: ["先停一下好不好？我们聊聊。", "这一幕得暂停了，过来跟我说说。"],
};

function pickLine(options: string[], seed: number, avoid: string, fill: (t: string) => string): string {
  for (let i = 0; i < options.length; i++) {
    const line = fill(options[(seed + i) % options.length]);
    if (line !== avoid) return line;
  }
  return fill(options[seed % options.length]);
}

export interface Mood {
  score: number;
  level: number;
  name: string;
  line: string;
  reasons: string[];
  pendingCheckin: CheckinRecord | null;
}

function clock(tsSec: number): string {
  const d = new Date(tsSec * 1000);
  const pad = (n: number) => (n < 10 ? `0${n}` : String(n));
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function levelOf(score: number): number {
  let level = 0;
  LEVEL_FLOORS.forEach((floor, index) => {
    if (score >= floor) level = index;
  });
  return level;
}

// 最近一次打卡之后还没有任何进展记录，就算没回应
export function pendingCheckinOf(checkins: CheckinRecord[], progress: ProgressRecord[], nowSec: number): CheckinRecord | null {
  const last = checkins[0];
  if (!last || nowSec - last.ts > 3 * 3600) return null;
  const answered = progress.some((p) => p.ts >= last.ts);
  return answered ? null : last;
}

export function computeMood(input: MoodInput): Mood {
  const nowSec = Math.floor(input.now / 1000);
  const reasons: string[] = [];
  let score = 0;

  const recent = input.focus?.recent;
  const recentTotal = recent ? recent.on + recent.off : 0;
  if (recent && recentTotal > 0 && recent.off > 0) {
    score += Math.round((recent.off / recentTotal) * 50);
    reasons.push(`近 1 小时 ${recentTotal} 次采样有 ${recent.off} 次不在任务上`);
  }

  const drifts = (input.focus?.drifts ?? []).filter((d) => nowSec - d.ts <= 2 * 3600);
  if (drifts.length > 0) {
    score += Math.min(30, drifts.length * 15);
    reasons.push(`2 小时内偏移 ${drifts.length} 次`);
  }

  const progress = [...input.progress].sort((a, b) => b.ts - a.ts);
  const latest = progress[0];
  if (latest && latest.kind === "stuck" && nowSec - latest.ts <= 3 * 3600) {
    score += 15;
    reasons.push(`你说卡在「${latest.user_quote}」，还没解开`);
  }

  const pending = pendingCheckinOf(input.checkins, input.progress, nowSec);
  if (pending && nowSec - pending.ts > 30 * 60) {
    score += 10;
    reasons.push(`${clock(pending.ts)} 来找过你，还没回她`);
  }

  const hour = new Date(input.now).getHours();
  const todayProgress = progress.filter((p) => new Date(p.ts * 1000).toDateString() === new Date(input.now).toDateString());
  if (todayProgress.length === 0 && hour >= 11 && recent && recent.off > 0) {
    score += 10;
    reasons.push("今天还没记过进展");
  }

  let relief = 0;
  for (const p of progress) {
    if (nowSec - p.ts > 90 * 60) break;
    if (p.kind === "done" || p.kind === "progress") {
      relief += 15;
      if (relief <= 30) reasons.push(`${clock(p.ts)} 你说「${p.user_quote}」`);
    } else if (p.kind === "pause") {
      relief += 20;
      reasons.push(`${clock(p.ts)} 你说要暂停：「${p.user_quote}」`);
    }
  }
  score -= Math.min(relief, 40);

  score = Math.max(0, Math.min(100, score));
  const level = levelOf(score);

  const task = input.task || "正事";
  const app = recent?.offApps[0] ?? "";
  const seed = Math.floor(input.now / 3600000);
  const avoid = input.checkins[0]?.line ?? "";
  const fill = (t: string) =>
    t
      .replace(/\{task\}/g, task)
      .replace(/\{app\}/g, app)
      .replace(/\{n\}/g, String(todayProgress.length))
      .replace(/\{quote\}/g, latest?.user_quote ?? "");
  let options: string[];
  if (level === 0) {
    options = todayProgress.length > 0 ? LINES.goodWithProgress : recentTotal === 0 ? LINES.fresh : LINES.idle;
  } else if (level === 1) {
    options = app ? LINES.drifting : LINES.driftingNoApp;
  } else if (level === 2) {
    options = latest?.kind === "stuck" ? LINES.stuck : LINES.worried;
  } else {
    options = app ? LINES.talk : LINES.talkNoApp;
  }
  const line = pickLine(options, seed, avoid, fill);

  return {
    score,
    level,
    name: MOOD_LEVELS[level],
    line,
    reasons: reasons.length > 0 ? reasons : ["现在没有需要在意的事"],
    pendingCheckin: pending,
  };
}
