import { atLocal, nextWeekday, DAY, HOUR, localDateKey, localParts } from './time.js';

const DOMAIN_RULES = [
  {
    domain: 'exam',
    keywords: ['考公', '考研', '考试', '刷题', '做题', '背单词', '复习', '上岸', '行测', '申论', '备考'],
  },
  {
    domain: 'job',
    keywords: ['找工作', '求职', '面试', '简历', '投递', 'offer', '招聘', '跳槽'],
  },
  {
    domain: 'sleep',
    keywords: ['熬夜', '睡觉', '早睡', '失眠'],
  },
  {
    domain: 'project',
    keywords: ['项目', '上线', '交付', '写代码', '论文'],
  },
];

const COMMITMENT_VERBS = ['复习', '做', '背', '学', '写', '改', '投', '交', '发', '完成', '刷', '练', '看', '过'];

export function detectDomain(text) {
  for (const rule of DOMAIN_RULES) {
    for (const keyword of rule.keywords) {
      if (text.includes(keyword)) return { domain: rule.domain, keyword };
    }
  }
  return null;
}

export function makeTitle(domain) {
  switch (domain) {
    case 'exam':
      return '考试准备';
    case 'job':
      return '找工作';
    case 'sleep':
      return '作息';
    case 'project':
      return '项目';
    default:
      return '最近的事';
  }
}

/** Returns { iso: 'YYYY-MM-DD', ts: number } or null. */
export function extractExamDate(text, now, tzOffsetMinutes = 480) {
  let match = text.match(/(\d{4})\s*年\s*(\d{1,2})\s*月(?:\s*(\d{1,2})\s*[日号])?/);
  if (match) {
    const ts = atLocal(now, 0, 0, 0, tzOffsetMinutes);
    const year = Number(match[1]);
    const month = Number(match[2]);
    const day = Number(match[3] ?? 15);
    const utc = Date.UTC(year, month - 1, day, 0, 0, 0, 0);
    const value = utc - tzOffsetMinutes * 60_000;
    return { iso: localDateKey(value, tzOffsetMinutes), ts: value, assumed: false };
  }

  match = text.match(/明年\s*(\d{1,2})\s*月(?:\s*(\d{1,2})\s*[日号])?/);
  if (match) {
    const p = new Date(now + tzOffsetMinutes * 60_000);
    const year = p.getUTCFullYear() + 1;
    const month = Number(match[1]);
    const day = Number(match[2] ?? 15);
    const utc = Date.UTC(year, month - 1, day, 0, 0, 0, 0);
    const value = utc - tzOffsetMinutes * 60_000;
    return { iso: localDateKey(value, tzOffsetMinutes), ts: value, assumed: !match[2] };
  }

  match = text.match(/(\d{1,2})\s*月(?:\s*(\d{1,2})\s*[日号])?/);
  if (match) {
    const p = new Date(now + tzOffsetMinutes * 60_000);
    const month = Number(match[1]);
    const day = Number(match[2] ?? 15);
    let year = p.getUTCFullYear();
    let utc = Date.UTC(year, month - 1, day, 0, 0, 0, 0);
    let value = utc - tzOffsetMinutes * 60_000;
    if (value < now) {
      year += 1;
      utc = Date.UTC(year, month - 1, day, 0, 0, 0, 0);
      value = utc - tzOffsetMinutes * 60_000;
    }
    return { iso: localDateKey(value, tzOffsetMinutes), ts: value, assumed: !match[2] };
  }

  if (text.includes('半年后')) {
    const value = now + 180 * DAY;
    return { iso: localDateKey(value, tzOffsetMinutes), ts: value, assumed: false };
  }

  return null;
}

function dueForMarker(marker, now, tzOffsetMinutes) {
  let value;
  switch (marker) {
    case '今晚':
    case '今天':
      value = atLocal(now, 0, 22, 0, tzOffsetMinutes);
      break;
    case '明天':
      value = atLocal(now, 1, 20, 0, tzOffsetMinutes);
      break;
    case '后天':
      value = atLocal(now, 2, 20, 0, tzOffsetMinutes);
      break;
    case '这周':
      value = nextWeekday(now, 0, 20, tzOffsetMinutes);
      break;
    case '下周':
      value = nextWeekday(now, 1, 20, tzOffsetMinutes, true);
      break;
    case '周末':
    case '这周末': {
      const p = localParts(now, tzOffsetMinutes);
      if (p.weekday === 0) {
        const sundayDue = atLocal(now, 0, 20, 0, tzOffsetMinutes);
        value = sundayDue > now ? sundayDue : nextWeekday(now, 6, 20, tzOffsetMinutes);
      } else if (p.weekday === 6) {
        const saturdayDue = atLocal(now, 0, 20, 0, tzOffsetMinutes);
        value = saturdayDue > now ? saturdayDue : atLocal(now, 1, 20, 0, tzOffsetMinutes);
      } else {
        value = nextWeekday(now, 6, 20, tzOffsetMinutes);
      }
      break;
    }
    case '下周末':
      value = nextWeekday(now, 6, 20, tzOffsetMinutes, true);
      break;
    case '每天':
      value = atLocal(now, 1, 20, 0, tzOffsetMinutes);
      break;
    default:
      return null;
  }
  return value > now ? value : now + HOUR;
}

const PAST_CLAUSE_RE = /((?<![为除因])了|已经|刚|昨天|前天|上次|刚才)/;
const FUTURE_ONLY_RE = /(明天|后天|下周|周末)/;
const STRIP_FILLERS_RE = /(^|[，,。；！？\s])(对了|好了|行了|好吧|行吧)[，,。！？\s]*/g;
const CONCRETE_RE = /(\d+\s*(个|道|页|小时|分钟|家|套|遍|次|篇|章)|单词|简历|题|卷子|行测|申论|复习|学习|看书|练|交|发)/;
const NON_TASK_RE = /(电影|看剧|追剧|直播|视频|综艺|演出|展览|看展|过周末|生日|发火|朋友|工资|游戏|旅游|饭|酒|聚会|逛街|睡觉|过节|过年|球|比赛|演唱会|票|交费|缴费)/;
const STRIP_NON_TASK_RE = new RegExp(NON_TASK_RE.source, 'g');
const TASK_RE = /(复习|学习|看书|做题|做\s*\d|背单词|背|写|改|投简历|投|交简历|交作业|发邮件|练|行测|申论|单词|简历|卷子|题|章|页|道|作业|网课|视频课|课|教材|讲义|论文|文献|报告|案例|PPT|ppt|PDF|pdf|回放|表格|图表|导图|课件|材料|录播|笔记|资料|真题|错题|课程|书|文档|Excel|excel|Word|word|链接|网盘|代码|源码|PR|pr|commit|diff|日志)/;
const OBJECT_VERBS = new Set(['看', '过', '交', '发']);

function pickMarker(clause) {
  const markers = ['下周末', '这周末', '每天', '明天', '后天', '这周', '下周', '周末', '今晚', '今天'];
  let best = null;
  for (const marker of markers) {
    const index = clause.indexOf(marker);
    if (index < 0) continue;
    if (
      best === null ||
      index < best.index ||
      (index === best.index && marker.length > best.marker.length)
    ) {
      best = { marker, index };
    }
  }
  return best?.marker ?? null;
}

export function extractCommitments(text, now, tzOffsetMinutes = 480) {
  const clauses = text
    .split(/[，。；！？,\n]/)
    .map((clause) => clause.trim())
    .filter(Boolean);

  const out = [];
  let inheritedMarker = null;

  for (const clause of clauses) {
    if (NON_TASK_RE.test(clause) && !TASK_RE.test(clause.replace(STRIP_NON_TASK_RE, ''))) {
      continue;
    }

    const explicitMarker = pickMarker(clause);
    const marker =
      explicitMarker ??
      (inheritedMarker && /^(再|然后|并|还)/.test(clause) ? inheritedMarker : null);
    if (explicitMarker) inheritedMarker = explicitMarker;
    if (!marker) continue;

    const verb = COMMITMENT_VERBS.find((v) => clause.includes(v));
    if (!verb) continue;

    if (OBJECT_VERBS.has(verb) && !TASK_RE.test(clause)) continue;

    const futureOnly = FUTURE_ONLY_RE.test(clause);
    const explicitPastDay = /(昨天|前天|上次|刚才)/.test(clause);
    if (explicitPastDay) continue;
    if (!futureOnly && PAST_CLAUSE_RE.test(clause)) continue;

    if ((marker === '今天' || marker === '今晚') && isProgress(clause)) continue;

    out.push({
      statement: clause,
      dueAt: dueForMarker(marker, now, tzOffsetMinutes),
      marker,
      verb,
    });
  }
  return out;
}

export function extractCommitment(text, now, tzOffsetMinutes = 480) {
  return extractCommitments(text, now, tzOffsetMinutes)[0] ?? null;
}

export function isProgress(text) {
  const normalized = text.replace(STRIP_FILLERS_RE, '$1');
  const futureOnly = FUTURE_ONLY_RE.test(normalized);
  const explicitPast = /(今天|今晚|昨天|前天|这周(?!末)|上次)/.test(normalized);
  if (futureOnly && !explicitPast) return false;

  const hasFuture = /(明天|后天|下周|这周|周末|每天)/.test(normalized);
  const hasPast = PAST_CLAUSE_RE.test(normalized);
  if (hasFuture && !hasPast) return false;
  return /(做了|做完|做好|做对|背了|背完|学完|学了|学好|复习了|复习完|看完了|看完|看了|看好|练了|练完|交了|交完|发了|发完|过了一遍|过完了|写完|写了|写好|改完|改了|投了|投完|提交了|完成|搞定|弄完|刷完|答对|准备好|(?:背|改|练|读|听|记|整理|打印|交|发|写|做|看|学|准备|复习|刷|投|收|答)\s*(?:好|完))/.test(
    normalized,
  );
}

export function isAffirmative(text) {
  return /(^|[，。！？\s])(好|行|可以|知道了|嗯|会的|没问题)([，。！？\s]|$)/.test(text);
}

export function isRestExplanation(text) {
  return /(休息|累了|太累|调整|缓缓|缓一下|状态不好|放松)/.test(text);
}

export function isGoalAbandon(text) {
  return /(不想考|不考了|放弃|不想找|不找了|不学了)/.test(text);
}

export function isDeflection(text) {
  if (/明天再/.test(text) && CONCRETE_RE.test(text)) return false;
  return /(先不说|等会|再说|别聊|吃啥|别的|困了|刷会|刷一会|明天再|改天|先这样)/.test(text);
}
