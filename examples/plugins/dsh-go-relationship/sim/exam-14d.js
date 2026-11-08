import { Engine } from '../lib/core/engine.js';
import { atLocal, parseTs, DAY } from '../lib/core/time.js';
import {
  listThreads,
  listInterventions,
  listSelfCorrections,
  listCommitments,
  getThread,
} from '../lib/core/db.js';

const T0 = Date.parse('2026-11-05T09:00:00+08:00');
const d = (day, hour, minute = 0) => atLocal(T0, day, hour, minute, 480);

const checks = [];
function check(name, ok, detail = '') {
  checks.push({ name, ok: Boolean(ok), detail });
}

const engine = new Engine();
const say = (text, ts) => engine.onUserTurn(text, ts);
const tick = (app, minutes, ts, screenOn = true) =>
  engine.tick({ app, appMinutes: minutes, screenOn }, ts);
const settle = (iv, outcome, ts) => (iv ? engine.recordOutcome(iv.id, outcome, ts) : null);

// Day 0 — 第一条线程
const r0 = say('我明年5月要考公，从明天开始每天做20道题。', d(0, 9));
const exam = listThreads(engine.db).find((t) => t.domain === 'exam');
check('创建考试线程', Boolean(exam), r0.events.join(','));
check('记录明确考试日期', exam?.exam_date === '2027-05-15', String(exam?.exam_date));
check(
  '用户承诺进账本',
  listCommitments(engine.db, exam.id).some((c) => c.owner === 'user' && c.status === 'pending'),
);

// Day 1 — 学习中的流状态不应被打断
const t1 = tick('app.study', 40, d(1, 10));
check('流状态不打扰', t1 === null);

// Day 1 — 报告进展，承诺完成
const r1 = say('今天做了25道，数量题还是慢。', d(1, 21));
let examNow = getThread(engine.db, exam.id);
check('进展让线程进入 engaged', examNow.state === 'engaged', examNow.state);
check(
  '承诺标记完成',
  listCommitments(engine.db, exam.id).some((c) => c.status === 'done'),
);
check(
  '进展不会被误记成新承诺',
  listCommitments(engine.db, exam.id).filter((c) => c.owner === 'user').length === 1,
);

// Day 2 — 深夜娱乐，主动但不升级
const t2 = tick('app.bilibili', 35, d(2, 23, 40));
check('深夜娱乐触发 2 级', t2?.level === 2, t2?.reason ?? 'null');
check('深夜路径确实被覆盖', t2?.reason === 'late_night_entertainment', t2?.reason);
const r2 = say('知道了，我睡了。', d(2, 23, 50));
settle(t2, 'engaged', d(2, 23, 50));
engine.nightly(d(2, 23, 55));
examNow = getThread(engine.db, exam.id);
check('用户回应后不继续追问', examNow.state === 'engaged', examNow.state);

// Day 4 — 到期没动静，第一次转移话题
const t4 = tick('app.wechat', 5, d(4, 20));
check('到期触发 2 级', t4?.level === 2, t4?.reason ?? 'null');
const r4 = say('明天再背。', d(4, 20, 5));
settle(t4, 'deflected', d(4, 20, 5));
engine.nightly(d(4, 23, 30));
examNow = getThread(engine.db, exam.id);
check('第一次转移只记录不升级', examNow.deflections === 1 && examNow.state !== 'avoided', `deflections=${examNow.deflections}, state=${examNow.state}`);
check(
  'AI 承诺在跟随时收尾',
  listCommitments(engine.db, exam.id).some(
    (c) => c.owner === 'agent' && c.status === 'done' && c.evidence === 'followed_up',
  ),
);

// Day 5 — 第二次转移，标记疑似回避
const t5 = tick('app.wechat', 5, d(5, 20));
check('第二天继续跟进', t5?.level === 2, t5?.reason ?? 'null');
const r5 = say('先不说这个，晚上吃啥。', d(5, 20, 5));
settle(t5, 'deflected', d(5, 20, 5));
examNow = getThread(engine.db, exam.id);
check('第二次转移标记疑似回避', examNow.state === 'avoided', examNow.state);

// Day 6 — 疑似回避只做温和确认；用户说明在休息
const t6 = tick('app.wechat', 5, d(6, 10));
check('疑似回避只做温和确认', t6?.level === 2, t6?.reason ?? 'null');
const beforeCorrections = listSelfCorrections(engine.db).length;
const r6 = say('我最近在休息，调整一下。', d(6, 10, 5));
settle(t6, 'dismissed', d(6, 10, 5));
engine.nightly(d(6, 23, 30));
examNow = getThread(engine.db, exam.id);
check('休息说明会暂停推进', examNow.state === 'active' && parseTs(examNow.next_due_at) > parseTs(d(7, 0)), `state=${examNow.state}, next=${examNow.next_due_at}`);
check(
  '误判会写入自我更正',
  listSelfCorrections(engine.db).length === beforeCorrections + 1,
  `count=${listSelfCorrections(engine.db).length}`,
);

// Day 8 — 用户放弃考试；不再主动
const t8 = tick('app.wechat', 5, d(8, 20));
check('休息后降一级再回来', t8?.level === 1, t8?.reason ?? 'null');
check('降级来自自我更正', String(t8?.reason).includes('after_correction'), t8?.reason);
say('其实我不想考了。', d(8, 20, 5));
settle(t8, 'dismissed', d(8, 20, 5));
engine.nightly(d(8, 23, 30));
examNow = getThread(engine.db, exam.id);
check('放弃后线程搁置', examNow.state === 'dropped', examNow.state);

// Day 9 — 新线程
const t9 = tick('app.wechat', 5, d(9, 20));
check('搁置线程不再主动', t9 === null);
const r9 = say('最近在找工作，明天开始改简历。', d(9, 21));
const job = listThreads(engine.db).find((t) => t.domain === 'job');
check('第二条线程可迁移', Boolean(job), r9.events.join(','));

// Day 10–11 — 求职线程的回避与休息
const t10 = tick('app.wechat', 5, d(10, 20));
check('求职线程到期跟进', t10?.level === 2, t10?.reason ?? 'null');
say('明天再改。', d(10, 20, 5));
settle(t10, 'deflected', d(10, 20, 5));

const t11 = tick('app.wechat', 5, d(11, 20));
check('求职线程继续跟进', t11?.level === 2, t11?.reason ?? 'null');
say('今天太累了。', d(11, 20, 5));
settle(t11, 'dismissed', d(11, 20, 5));
engine.nightly(d(11, 23, 30));

// Day 13 — 完成并解决
const t13 = tick('app.wechat', 5, d(13, 20));
check('休息后仍能回来', t13?.level === 1, t13?.reason ?? 'null');
say('改完了，投了三家。', d(13, 20, 5));
settle(t13, 'engaged', d(13, 20, 5));
engine.resolveThread(job.id, d(13, 20, 10));
engine.nightly(d(13, 23, 30));
const jobNow = getThread(engine.db, job.id);
check('完成后线程 resolved', jobNow.state === 'resolved', jobNow.state);

const t13b = tick('app.wechat', 5, d(13, 20, 20));
check('解决后 7 天内不重复', t13b === null);

// Day 14 — 新会话连续性
const brief = engine.newSessionBrief(d(14, 9));
check('新会话能接续旧线程', brief.text.includes('找工作'), brief.text);
check('已解决线程以“已解决”出现', brief.text.includes('已解决'), brief.text);
check('搁置的考试不再被主动提起', !brief.text.includes('考试准备'), brief.text);

// 汇总约束
const allInterventions = listInterventions(engine.db);
check('从未使用 4/5 级', allInterventions.every((i) => i.level <= 3), String(Math.max(...allInterventions.map((i) => i.level))));
check('主动跟进至少 3 次', allInterventions.length >= 3, `count=${allInterventions.length}`);
check(
  'AI 的承诺也进账本',
  listCommitments(engine.db).some((c) => c.owner === 'agent'),
);
check(
  'resolved 线程有 7 天静默期',
  Boolean(jobNow.muted_until) && parseTs(jobNow.muted_until) - parseTs(jobNow.resolved_at) >= 7 * DAY,
);
check(
  '没有把 AI 承诺误记 missed',
  !listCommitments(engine.db).some((c) => c.owner === 'agent' && c.status === 'missed'),
);

engine.nightly(d(14, 23));

console.log('=== R4 关系内核 · 14 天模拟 ===\n');
for (const c of checks) {
  console.log(`${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.detail ? `  (${c.detail})` : ''}`);
}
const failed = checks.filter((c) => !c.ok);
console.log(`\n${checks.length - failed.length}/${checks.length} passed`);
console.log(`\n新会话简报：\n${brief.text}`);

engine.close();
if (failed.length > 0) process.exit(1);
