// Relationship scoring. Pure functions, no I/O, so they run in the browser and in Node tests.
//
// The numbers are a self-awareness gauge, not a debt ledger: they tell you when to look
// closer, never "who owes whom".

import { TIERS, CARE_TYPES, DEFAULT_PREFS, STAGES } from './models.js';

const DAY = 86400000;
const NOT_ACCEPTED = ['declined', 'postponed', 'no_reply', 'flaked'];

const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
const round = (x, d = 2) => Math.round(x * 10 ** d) / 10 ** d;

export function parseDay(iso) {
  // 'YYYY-MM-DD' → local midnight timestamp
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).getTime();
}

export function daysBetween(fromTs, toTs) {
  return Math.floor((toTs - fromTs) / DAY);
}

function startOfDay(now) {
  const d = new Date(now);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

/** Convert one side of an interaction ({money, minutes, emotion}) into effort units. */
export function flowUnits(flow, tierId = 'bond', prefs = DEFAULT_PREFS) {
  if (!flow) return 0;
  const w = TIERS[tierId]?.weights || TIERS.bond.weights;
  const money = Math.max(0, Number(flow.money) || 0);
  const minutes = Math.max(0, Number(flow.minutes) || 0);
  const emotion = clamp(Number(flow.emotion) || 0, 0, 5);
  return (
    w.money * Math.log2(1 + money / (prefs.moneyUnit || 100)) +
    w.time * Math.log2(1 + minutes / 30) +
    w.emotion * emotion * 0.6
  );
}

export function decay(daysAgo, halfLife = DEFAULT_PREFS.halfLifeDays) {
  return 0.5 ** (Math.max(0, daysAgo) / halfLife);
}

/** Time-decayed totals of what I gave vs. what I got. balance ∈ [-1, 1]; > 0 means I give more. */
export function ledger(entity, interactions, now = Date.now(), prefs = DEFAULT_PREFS) {
  let give = 0;
  let get = 0;
  for (const it of interactions) {
    const w = decay(daysBetween(parseDay(it.date), now), prefs.halfLifeDays);
    give += w * flowUnits(it.give, entity.tier, prefs);
    get += w * flowUnits(it.get, entity.tier, prefs);
  }
  const total = give + get;
  return {
    give: round(give),
    get: round(get),
    total: round(total),
    balance: total > 0 ? round((give - get) / total) : 0,
  };
}

/** Who reaches out first, over the last 180 days. ratio = share initiated by me. */
export function initiative(interactions, now = Date.now()) {
  let me = 0;
  let them = 0;
  for (const it of interactions) {
    if (daysBetween(parseDay(it.date), now) > 180) continue;
    if (it.initiator === 'me') me++;
    else if (it.initiator === 'them') them++;
  }
  const n = me + them;
  return { me, them, ratio: n >= 3 ? round(me / n) : null };
}

/** Invitation stats, both directions, plus the current streak of my invites that were not accepted. */
export function invites(interactions) {
  const sorted = [...interactions].sort(byDateAsc);
  const mine = sorted.filter((i) => i.invite && i.invite.by === 'me');
  const theirs = sorted.filter((i) => i.invite && i.invite.by === 'them');
  const accepted = mine.filter((i) => i.invite.status === 'accepted').length;

  let softRejectStreak = 0;
  for (let k = mine.length - 1; k >= 0; k--) {
    if (NOT_ACCEPTED.includes(mine[k].invite.status)) softRejectStreak++;
    else break;
  }
  const iDeclined = theirs.filter((i) => NOT_ACCEPTED.includes(i.invite.status)).length;

  return {
    myInvites: mine.length,
    myAccepted: accepted,
    // Laplace smoothing so one data point doesn't swing the score to 0 or 1.
    responsiveness: round((accepted + 1) / (mine.length + 2)),
    softRejectStreak,
    theirInvites: theirs.length,
    iDeclined,
  };
}

/** Promise keeping rate for each side, smoothed. */
export function credibility(interactions) {
  const tally = { me: { kept: 0, broken: 0, pending: 0 }, them: { kept: 0, broken: 0, pending: 0 } };
  for (const it of interactions) {
    const p = it.promise;
    if (!p || !tally[p.by]) continue;
    tally[p.by][p.status] = (tally[p.by][p.status] || 0) + 1;
  }
  const score = (t) => ({
    ...t,
    resolved: t.kept + t.broken,
    rate: round((t.kept + 1) / (t.kept + t.broken + 2)),
  });
  return { me: score(tally.me), them: score(tally.them) };
}

/** Activity in the last 30 days vs. the average 30-day window in the 90 days before. */
export function trend(entity, interactions, now = Date.now(), prefs = DEFAULT_PREFS) {
  let recent = 0;
  let before = 0;
  for (const it of interactions) {
    const age = daysBetween(parseDay(it.date), now);
    const u = flowUnits(it.give, entity.tier, prefs) + flowUnits(it.get, entity.tier, prefs) + 0.5;
    if (age <= 30) recent += u;
    else if (age <= 120) before += u;
  }
  const baseline = before / 3;
  if (baseline < 1 && recent < 1) return { dir: 'flat', recent: round(recent), baseline: round(baseline) };
  const ratio = baseline > 0 ? recent / baseline : 2;
  const dir = ratio > 1.3 ? 'up' : ratio < 0.7 ? 'down' : 'flat';
  return { dir, recent: round(recent), baseline: round(baseline), ratio: round(ratio) };
}

/** Next occurrence of an annual date ('MM-DD' or 'YYYY-MM-DD'). */
export function nextOccurrence(dateStr, now = Date.now()) {
  const parts = dateStr.split('-').map(Number);
  const [m, d] = parts.length === 3 ? [parts[1], parts[2]] : parts;
  if (!m || !d) return null;
  const today = startOfDay(now);
  const y = new Date(today).getFullYear();
  let next = new Date(y, m - 1, d).getTime();
  if (next < today) next = new Date(y + 1, m - 1, d).getTime();
  return { ts: next, days: daysBetween(today, next) };
}

export function upcomingKeyDates(entity, now = Date.now()) {
  return (entity.keyDates || [])
    .map((k) => ({ ...k, next: nextOccurrence(k.date, now) }))
    .filter((k) => k.next)
    .sort((a, b) => a.next.days - b.next.days);
}

/** Kin tier: rough count of remaining in-person meetings at the past year's pace. */
export function meetingsLeft(entity, interactions, now = Date.now(), prefs = DEFAULT_PREFS) {
  if (!entity.birthYear) return null;
  const age = new Date(now).getFullYear() - entity.birthYear;
  const yearsLeft = Math.max(0, (prefs.lifeExpectancy || 82) - age);
  const meetsLastYear = interactions.filter(
    (i) => i.type === 'meet' && daysBetween(parseDay(i.date), now) <= 365,
  ).length;
  return { age, yearsLeft, meetsPerYear: meetsLastYear, estimate: Math.round(yearsLeft * meetsLastYear) };
}

/** Contract tier: what I get back per unit put in, with time valued at my hourly rate. */
export function roi(entity, interactions, now = Date.now(), prefs = DEFAULT_PREFS) {
  const hv = Number(entity.contract?.hourlyValue) || prefs.hourlyValue || 50;
  let giveV = 0;
  let getV = 0;
  let giveMin = 0;
  let getMoney = 0;
  for (const it of interactions) {
    if (daysBetween(parseDay(it.date), now) > 365) continue;
    giveV += (Number(it.give?.money) || 0) + ((Number(it.give?.minutes) || 0) / 60) * hv;
    getV += (Number(it.get?.money) || 0) + ((Number(it.get?.minutes) || 0) / 60) * hv;
    giveMin += Number(it.give?.minutes) || 0;
    getMoney += Number(it.get?.money) || 0;
  }
  return {
    hourlyValue: hv,
    ratio: giveV > 0 ? round(getV / giveV) : null,
    effectiveHourly: giveMin > 0 ? round(getMoney / (giveMin / 60), 1) : null,
  };
}

function weightedFeeling(interactions, now, halfLife) {
  let s = 0;
  let w = 0;
  for (const it of interactions) {
    const k = decay(daysBetween(parseDay(it.date), now), halfLife);
    s += k * (Number(it.feeling) || 0);
    w += k;
  }
  return w > 0 ? s / w : 0;
}

function byDateAsc(a, b) {
  return a.date === b.date ? (a.createdAt || 0) - (b.createdAt || 0) : a.date < b.date ? -1 : 1;
}

/**
 * Full analysis of one relationship. `interactions` must already be filtered to this entity.
 * Returns metrics, temperature (0-100), stage, and alerts.
 */
export function analyze(entity, interactions, now = Date.now(), prefs = DEFAULT_PREFS) {
  const its = interactions.filter((i) => !i.deleted).sort(byDateAsc);
  const tier = entity.tier;
  const cadence = Number(entity.cadenceDays) || TIERS[tier]?.defaultCadence || 30;

  // A declined or ignored invite from me is an attempt, not contact: it must not warm the relationship.
  const contacts = its.filter((i) => !(i.invite && i.invite.by === 'me' && NOT_ACCEPTED.includes(i.invite.status)));
  const last = contacts[contacts.length - 1];
  const daysSinceLast = last ? daysBetween(parseDay(last.date), now) : null;
  const myCare = contacts.filter((i) => i.initiator === 'me' && CARE_TYPES.includes(i.type));
  const lastCare = myCare[myCare.length - 1];
  const daysSinceCare = lastCare ? daysBetween(parseDay(lastCare.date), now) : null;

  const led = ledger(entity, its, now, prefs);
  const ini = initiative(its, now);
  const inv = invites(its);
  const cred = credibility(its);
  const tr = trend(entity, its, now, prefs);
  const dates = upcomingKeyDates(entity, now);
  const meet = tier === 'kin' ? meetingsLeft(entity, its, now, prefs) : null;
  const ret = tier === 'deal' ? roi(entity, its, now, prefs) : null;

  // --- temperature ---
  const freshness = daysSinceLast == null ? 0 : Math.exp(-daysSinceLast / cadence);
  const mood = (clamp(weightedFeeling(its, now, prefs.halfLifeDays), -2, 2) + 2) / 4;
  let reciprocity;
  if (tier === 'kin') reciprocity = daysSinceCare == null ? 0.2 : Math.exp(-daysSinceCare / cadence);
  else if (tier === 'deal') reciprocity = cred.them.rate;
  else reciprocity = led.total > 0 ? 1 - Math.abs(led.balance) : 0.5;
  const response = inv.responsiveness;

  const temperature = its.length
    ? Math.round(100 * (0.35 * freshness + 0.25 * mood + 0.2 * reciprocity + 0.2 * response))
    : null;

  // --- stage ---
  const recentConflict = its.some(
    (i) => i.type === 'conflict' && (Number(i.feeling) || 0) < 0 && daysBetween(parseDay(i.date), now) <= 14,
  );
  let stage = null;
  if (temperature != null) {
    if (inv.softRejectStreak >= 3 || (recentConflict && temperature < 40)) stage = 'breaking';
    else if (tr.dir === 'down' || inv.softRejectStreak >= 2 || temperature < 45) stage = 'cooling';
    else if (tr.dir === 'up' && temperature >= 60) stage = 'warming';
    else stage = 'stable';
  }

  const alerts = buildAlerts({ entity, tier, cadence, its, daysSinceLast, daysSinceCare, led, ini, inv, cred, dates, ret, recentConflict });

  return {
    entityId: entity.id,
    tier,
    count: its.length,
    temperature,
    stage,
    stageLabel: stage ? STAGES[stage].label : '暂无数据',
    daysSinceLast,
    daysSinceCare,
    ledger: led,
    initiative: ini,
    invites: inv,
    credibility: cred,
    trend: tr,
    keyDates: dates,
    meetingsLeft: meet,
    roi: ret,
    alerts,
  };
}

function buildAlerts(c) {
  const out = [];
  const add = (level, code, title, advice) => out.push({ level, code, title, advice });

  if (c.inv.softRejectStreak >= 2) {
    add(
      c.inv.softRejectStreak >= 3 ? 'danger' : 'warn',
      'soft_reject',
      `软拒绝信号：你的邀约连续 ${c.inv.softRejectStreak} 次没被接受`,
      '对方可能在回避，也可能正处在难处。暂停邀约，换成不需要回应的关心（比如“最近辛苦了，有空再聚”）。不要追问原因，更不要说气话。',
    );
  }

  if (c.tier === 'bond' && c.its.length >= 4 && c.led.total >= 6) {
    if (c.led.balance > 0.45) {
      add('warn', 'one_way_giving', '单向付出：你的投入明显多于回流',
        '先别想着讨回来。把主动投入降一档，留出空间看对方会不会主动。对方不动，也是一种答案。');
    } else if (c.led.balance < -0.45) {
      add('warn', 'over_taking', '过度索取：最近你得到的多于给出的',
        '找一个具体的机会回馈对方，比如请一顿饭、帮一个忙，或者认真听对方说一次话。');
    }
  }

  if (c.tier !== 'deal' && c.ini.ratio != null && c.ini.me + c.ini.them >= 5 && c.ini.ratio >= 0.8) {
    add('info', 'initiative_gap', `主动性差距：近半年 ${Math.round(c.ini.ratio * 100)}% 的联系由你发起`,
      '这不一定是坏事，有的人就是不擅长主动。但如果你已经觉得累，可以适当放慢。');
  }

  if (c.tier === 'kin') {
    if (c.daysSinceCare == null) {
      add('warn', 'care_overdue', '还没有记录过你对 TA 的主动关怀', '今天就打个电话，问问身体和最近的生活。');
    } else if (c.daysSinceCare > c.cadence) {
      add(c.daysSinceCare > c.cadence * 2 ? 'danger' : 'warn', 'care_overdue',
        `已经 ${c.daysSinceCare} 天没主动关心了（你设定的是每 ${c.cadence} 天）`,
        '打个电话或发条消息。不用找理由，“想你们了”就够了。');
    }
  } else if (c.daysSinceLast != null && c.daysSinceLast > c.cadence * 1.5) {
    add('info', 'overdue', `已经 ${c.daysSinceLast} 天没联系`,
      c.tier === 'bond' ? '如果这段关系对你重要，发一条不需要对方回复的问候。' : '确认一下有没有需要跟进的事项。');
  }

  for (const k of c.dates) {
    if (k.next.days <= 14) {
      add(k.next.days <= 3 ? 'warn' : 'info', 'key_date',
        `${k.label}${k.next.days === 0 ? '就是今天' : `还有 ${k.next.days} 天`}${k.lunar ? '（农历，请核对今年的公历日期）' : ''}`,
        c.tier === 'kin' ? '提前准备：一个电话、一份小礼物，或者回家一趟。' : '想一想要不要表示，以及用什么方式最合适。');
    }
  }

  if (c.cred.them.resolved >= 3 && c.cred.them.rate < 0.4) {
    add('warn', 'low_credibility', `对方承诺兑现率偏低（${Math.round(c.cred.them.rate * 100)}%）`,
      c.tier === 'deal' ? '把口头承诺改成书面约定，按阶段兑现，到期主动核对。' : '别把计划建立在对方的承诺上，给自己留后手。');
  }
  if (c.cred.me.resolved >= 2 && c.cred.me.rate < 0.5) {
    add('warn', 'self_credibility', `你自己的承诺兑现率偏低（${Math.round(c.cred.me.rate * 100)}%）`,
      '少承诺，承诺了就做到。信任是在小事上攒起来的。');
  }

  if (c.tier === 'deal' && c.ret?.ratio != null && c.ret.ratio < 0.6) {
    add('warn', 'low_roi', `投入产出比偏低（${c.ret.ratio}）`,
      '列出你的筹码和替代方案（BATNA），再决定是谈条件、减少投入，还是准备退出。');
  }

  if (c.recentConflict) {
    add('warn', 'recent_conflict', '两周内有过一次不愉快的冲突',
      '冲突后 48 小时内不做重大决定。先去「博弈沙盘」把你想说的话过一遍。');
  }

  const order = { danger: 0, warn: 1, info: 2 };
  return out.sort((a, b) => order[a.level] - order[b.level]);
}

/** Preview how adding a draft interaction would change the analysis (for the log form). */
export function previewDelta(entity, interactions, draft, now = Date.now(), prefs = DEFAULT_PREFS) {
  const before = analyze(entity, interactions, now, prefs);
  const after = analyze(entity, [...interactions, draft], now, prefs);
  const beforeCodes = new Set(before.alerts.map((a) => a.code + a.title));
  return {
    before,
    after,
    tempDelta: before.temperature == null || after.temperature == null ? null : after.temperature - before.temperature,
    balanceDelta: round(after.ledger.balance - before.ledger.balance),
    newAlerts: after.alerts.filter((a) => !beforeCodes.has(a.code + a.title)),
  };
}
