// dsh-go-companion · 情绪池（R7 B4）
//
// 四根轴各自回落、事件推动、余怨跨天、同类递减；情绪是账本的纯函数（不给它状态文件）。
// 末尾三条是硬边界：不改锁/额度/判定、每小时上限、不自己写文案。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  BURST_HOURLY_CAP, DIMS, DIM_META, EVENTS, GRUDGE_ANGER, IGNORED_AFTER_MS, REPEAT_FLOOR,
  TONES, applyEvent, behaviorOf, decayOf, emotionLine, emotionOf, emptyEmotion, eventsOf, toneOf,
} from '../lib/emotion.js'

const LIB = join(dirname(fileURLToPath(import.meta.url)), '..', 'lib')
const T = Date.UTC(2026, 10, 12, 6, 0, 0) // 固定时刻，不跟真实时间走
const MIN = 60_000
const HOUR = 3_600_000
const near = (a, b, tol = 0.51) => Math.abs(Number(a) - Number(b)) <= tol
const at = (h) => T + h * HOUR

/** 一条账本记录。 */
const row = (state, at_, extra = {}) => ({ state, at: at_, requestId: 'r-' + state + '-' + at_, ...extra })
/** replied 一条判球结果。 */
const replied = (at_, verdict, extra = {}) => ({ state: 'replied', at: at_, requestId: 'r-' + verdict + '-' + at_, verdict, ...extra })
const empty = () => emptyEmotion(T)

// ---------------------------------------------------------------- 一、日常值

test('E1 空账本是日常值，不是 0（回落回日常，不回落成空）', () => {
  const e = emotionOf([], { now: T })
  assert.equal(e.patience, 70)
  assert.equal(e.care, 60)
  assert.equal(e.anger, 15)
  assert.equal(e.interest, 50)
  assert.deepEqual(DIMS, ['patience', 'care', 'anger', 'interest'])
})

test('E2 四根轴的日常值/半衰期就是工单写的那张表', () => {
  assert.deepEqual(
    DIMS.map((d) => [DIM_META[d].label, DIM_META[d].base, DIM_META[d].halfMs]),
    [['耐心', 70, 3 * HOUR], ['在意', 60, 12 * HOUR], ['火气', 15, 90 * MIN], ['兴致', 50, 4 * HOUR]],
  )
})

// ---------------------------------------------------------------- 二、事件

test('E3 敷衍：耐心 −8、火气 +10', () => {
  const e = applyEvent(empty(), 'brief', { now: T })
  assert.equal(e.patience, 62)
  assert.equal(e.anger, 25)
})

test('E4 认真回：耐心 +6、兴致 +8、火气 −10（一次推三根轴）', () => {
  const e = applyEvent(empty(), 'engaged', { now: T })
  assert.equal(e.patience, 76)
  assert.equal(e.interest, 58)
  assert.equal(e.anger, 5)
})

test('E5 pushback 也算认真回（真反驳是最好的球）', () => {
  const e = emotionOf([replied(T, 'pushback')], { now: T })
  assert.equal(e.patience, 76)
  assert.equal(e.interest, 58)
})

test('E6 守住硬立场：在意 +10', () => {
  const e = emotionOf([{ state: 'stance-settled', at: T, requestId: 'r-k', stance: { id: 's1', kind: 'hard', status: 'kept' } }], { now: T })
  assert.equal(e.care, 70)
})

test('E7 软立场 kept 不算守住硬立场（工单说的是硬立场）', () => {
  const e = emotionOf([{ state: 'stance-settled', at: T, requestId: 'r-k', stance: { id: 's1', kind: 'soft', status: 'kept' } }], { now: T })
  assert.equal(e.care, 60)
})

test('E8 违抗：火气 +20，挂一天余怨', () => {
  const e = emotionOf([{ state: 'defied', at: T, requestId: 'r-d', app: 'com.tencent.tmgp.sgame' }], { now: T })
  assert.equal(e.anger, 35)
  assert.equal(e.grudgeUntil, T + 24 * HOUR)
})

test('E9 被晾着：发出去没人接 → 兴致 −5（之后按半衰期往日常值回）', () => {
  const e = emotionOf([row('generated', T - 10 * MIN)], { now: T, quietStart: 3, quietEnd: 3 })
  assert.ok(near(e.interest, 45, 0.3), '推下去是 45，过了 10 分钟回一点，实际 ' + e.interest)
  assert.ok(e.interest < 50, '比日常值低才叫被晾着')
})

test('E10 刚发出去还不到 5 分钟，不算被晾着', () => {
  const e = emotionOf([row('generated', T - MIN)], { now: T, quietStart: 3, quietEnd: 3 })
  assert.equal(e.interest, 50)
  assert.equal(eventsOf([row('generated', T - MIN)], { now: T, quietStart: 3, quietEnd: 3 }).length, 0)
})

test('E11 他回过这一球，就不算被晾着', () => {
  const entries = [row('generated', T - 10 * MIN, { requestId: 'x1' }), { state: 'replied', at: T - 8 * MIN, requestId: 'x1', verdict: 'engaged' }]
  assert.equal(eventsOf(entries, { now: T, quietStart: 3, quietEnd: 3 }).some((e) => e.id === 'ignored'), false)
})

test('E12 他后来随便说过一句话，也不算被晾着', () => {
  const entries = [row('generated', T - 12 * MIN, { requestId: 'y1' }), { state: 'human', at: T - 6 * MIN }]
  assert.equal(eventsOf(entries, { now: T, quietStart: 3, quietEnd: 3 }).some((e) => e.id === 'ignored'), false)
})

test('E13 安静时段发出去的那一球，没人接也不算被晾着（那是她不该发）', () => {
  // quiet 0..24 = 全天都在安静时段
  const entries = [row('generated', T - 10 * MIN)]
  assert.equal(eventsOf(entries, { now: T, quietStart: 0, quietEnd: 24 }).length, 0)
  assert.equal(eventsOf(entries, { now: T, quietStart: 3, quietEnd: 3 }).length, 1)
})

test('E14 evasive / refuse 不动情绪（工单只认 brief / stock 算敷衍）', () => {
  const e = emotionOf([replied(T, 'evasive'), replied(T + MIN, 'refuse')], { now: T + 2 * MIN })
  assert.equal(e.patience, 70)
  assert.equal(e.anger, 15)
})

// ---------------------------------------------------------------- 三、连发递减与连击

test('E15 同类连发递减：第二次敷衍的力度是一半', () => {
  const a = applyEvent(empty(), 'brief', { now: T })
  const b = applyEvent(a, 'brief', { now: T })
  assert.equal(b.patience, 58, '70 - 8 - 4')
  assert.equal(b.anger, 30, '15 + 10 + 5')
})

test('E16 递减有下限，第 4 次和第 5 次力度一样（不会衰减到没有）', () => {
  let e = empty()
  const seen = []
  for (let i = 0; i < 6; i += 1) {
    const before = e.patience
    e = applyEvent(e, 'brief', { now: T })
    seen.push(Number((before - e.patience).toFixed(2)))
  }
  assert.deepEqual(seen.slice(0, 3), [8, 4, 2])
  assert.equal(seen[5], 8 * REPEAT_FLOOR, '下限就是 REPEAT_FLOOR 那一档')
  assert.equal(seen[4], seen[5])
})

test('E17 隔一件事之后再敷衍，力度回到满格（递减只算连着来）', () => {
  let e = applyEvent(empty(), 'brief', { now: T })
  e = applyEvent(e, 'engaged', { now: T + MIN })
  const before = e.patience
  e = applyEvent(e, 'brief', { now: T + 2 * MIN })
  assert.ok(before - e.patience > 7.9, '这一下要接近满格 −8，实际 ' + (before - e.patience))
})

test('E18 连续 3 次敷衍：第三次额外 +15 火气，挂 6 小时余怨', () => {
  const e = emotionOf([replied(T, 'brief'), replied(T + MIN, 'brief'), replied(T + 2 * MIN, 'brief')], { now: T })
  const ids = eventsOf([replied(T, 'brief'), replied(T + MIN, 'brief'), replied(T + 2 * MIN, 'brief')], { now: T }).map((x) => x.id)
  assert.deepEqual(ids, ['brief', 'brief', 'brief', 'brief3'])
  assert.ok(near(e.anger, 47.5), '火气应到 47.5，实际 ' + e.anger)
  assert.equal(e.grudgeUntil, T + 2 * MIN + 6 * HOUR)
})

test('E19 第 4、5、6 次敷衍不再挂第二条余怨（只挂一次）', () => {
  const many = []
  for (let i = 0; i < 6; i += 1) many.push(replied(T + i * MIN, 'brief'))
  const ids = eventsOf(many, { now: T }).filter((x) => x.id === 'brief3')
  assert.equal(ids.length, 1)
})

test('E20 认真回把连击清零，之后再连三次才会重新挂余怨', () => {
  const entries = [
    replied(T, 'brief'), replied(T + MIN, 'brief'), replied(T + 2 * MIN, 'brief'),
    replied(T + 3 * MIN, 'engaged'),
    replied(T + 4 * MIN, 'brief'), replied(T + 5 * MIN, 'brief'),
  ]
  const ids = eventsOf(entries, { now: T }).map((x) => x.id)
  assert.deepEqual(ids, ['brief', 'brief', 'brief', 'brief3', 'engaged', 'brief', 'brief'])
})

// ---------------------------------------------------------------- 四、半衰期

test('E21 火气 90 分钟走完一半距离，回落到日常值（不是 0）', () => {
  const e = decayOf(applyEvent(empty(), 'brief', { now: T }), T + 90 * MIN)
  assert.ok(near(e.anger, 20), '25 与 15 的中点 = 20，实际 ' + e.anger)
  assert.ok(e.anger > 15, '只是往日常值回落，不是掉到 0')
})

test('E22 耐心半衰期 3 小时：走一半', () => {
  const e = decayOf(applyEvent(empty(), 'brief', { now: T }), T + 3 * HOUR)
  assert.ok(near(e.patience, 66), '62 与 70 的中点 = 66，实际 ' + e.patience)
})

test('E23 隔两个半衰期只剩四分之一的路程', () => {
  const e = decayOf(applyEvent(empty(), 'brief', { now: T }), T + 180 * MIN)
  assert.ok(near(e.anger, 17.5), '15 + 10/4 = 17.5，实际 ' + e.anger)
})

test('E24 时间不会倒退：now 早于 at 时不动', () => {
  const base = applyEvent(empty(), 'brief', { now: T })
  const e = decayOf(base, T - HOUR)
  assert.equal(e.anger, base.anger)
  assert.equal(e.at, base.at)
})

// ---------------------------------------------------------------- 五、余怨

test('E25 余怨期间火气回不到平静（floor 是 GRUDGE_ANGER）', () => {
  const e = applyEvent(applyEvent(applyEvent(empty(), 'brief', { now: T }), 'brief', { now: T }), 'brief3', { now: T })
  const later = decayOf(e, T + 3 * HOUR)
  assert.ok(later.anger >= 40, '不会掉回平静，实际 ' + later.anger)
  assert.ok(later.anger < GRUDGE_ANGER + 1, '但也不会自己往上涨过 45，实际 ' + later.anger)
})

test('E26 余怨过期之后照常回落到日常值', () => {
  const e = applyEvent(empty(), 'defiance', { now: T })
  const after = decayOf(e, T + 24 * HOUR + 1)
  assert.ok(near(after.anger, 15, 0.6), '过了一天余怨没了，回落到 15，实际 ' + after.anger)
})

test('E27 余怨跨天：跨过零点仍然在（存的是时间戳，不是"今天的条数"）', () => {
  const e = emotionOf([{ state: 'defied', at: T, requestId: 'r-d' }], { now: T })
  // T 是 06:00，往后 20 小时跨过零点还在余怨里
  assert.ok(e.grudgeUntil > T + 20 * HOUR)
  const sameDay = decayOf(e, T + 8 * HOUR)
  assert.ok(sameDay.anger > 20, '第二天早上还在气，实际 ' + sameDay.anger)
})

// ---------------------------------------------------------------- 六、纯函数与边界

test('E28 不改入参，返回新对象', () => {
  const base = empty()
  const snapshot = JSON.stringify(base)
  applyEvent(base, 'brief', { now: T })
  decayOf(base, T + HOUR)
  emotionOf([replied(T, 'brief')], { now: T })
  assert.equal(JSON.stringify(base), snapshot)
})

test('E29 四根轴永远在 0..100 之间（狂推也不会溢出）', () => {
  let e = empty()
  for (let i = 0; i < 40; i += 1) e = applyEvent(e, 'defiance', { now: T + i })
  for (let i = 0; i < 40; i += 1) e = applyEvent(e, 'engaged', { now: T + 100 + i })
  for (const id of DIMS) assert.ok(e[id] >= 0 && e[id] <= 100, id + ' 越界：' + e[id])
})

test('E30 账本乱序也按时间正序算（谁先谁后不由行位置定）', () => {
  const ordered = [replied(T, 'brief'), replied(T + MIN, 'engaged')]
  const shuffled = [ordered[1], ordered[0]]
  const a = emotionOf(ordered, { now: T + 2 * MIN })
  const b = emotionOf(shuffled, { now: T + 2 * MIN })
  assert.ok(near(a.patience, b.patience), a.patience + ' vs ' + b.patience)
})

test('E31 不认识的事件直接抛错，不当成没发生', () => {
  assert.throws(() => applyEvent(empty(), 'nope', { now: T }), /未知的情绪事件/)
})

test('E32 脏输入不炸（账本里有 null / 缺 at / 缺 stance）', () => {
  const e = emotionOf([null, {}, { state: 'stance-settled', at: T }, { state: 'replied', at: T }], { now: T })
  for (const id of DIMS) assert.ok(Number.isFinite(e[id]))
})

// ---------------------------------------------------------------- 七、进状态层的那一行

test('E33 一行形状：`[情绪] 火气 62（敷衍了 3 次）· 耐心 31`', () => {
  const e = emotionOf([replied(T, 'brief'), replied(T, 'brief'), replied(T, 'brief')], { now: T })
  const line = emotionLine(e, T)
  assert.match(line, /^\[情绪\] 火气 \d+/)
  assert.match(line, /· 耐心 \d+$/)
  assert.match(line, /（敷衍了 3 次）/, '连击要说出是几次：' + line)
})

test('E34 没有连击时不带括号', () => {
  const line = emotionLine(empty(), T)
  assert.equal(line, '[情绪] 火气 15 · 耐心 70')
})

test('E35 余怨还没过去时那一行说出来', () => {
  const line = emotionLine(emotionOf([{ state: 'defied', at: T, requestId: 'r-d' }], { now: T }), T)
  assert.match(line, /余怨还没过去/)
})

test('E36 只报数值，不报"她该说什么"（不带原话、不带句号结尾的句子）', () => {
  const line = emotionLine(emotionOf([replied(T, 'brief')], { now: T }), T)
  assert.equal(line.includes('。'), false)
  assert.ok(line.length <= 60, '状态层一行不能被情绪写长：' + line.length)
})

// ---------------------------------------------------------------- 八、behaviorOf（≥8）

// 下面五条是把四根轴拨到指定位置看档位怎么落——档位表就是这些边界，别让它们漂。
const dims = (over) => ({ ...empty(), ...over })

test('E37 火气高且耐心低 → blaze：连发 3 条、每条 40 字、间隔 3–8 秒', () => {
  const b = behaviorOf(dims({ anger: 80, patience: 30 }), { now: T })
  assert.equal(b.tone, 'blaze')
  assert.equal(b.burst, 3)
  assert.equal(b.maxChars, 40)
  assert.ok(b.delayMs[0] >= 3_000 && b.delayMs[1] <= 8_000, '间隔要在 3–8 秒：' + b.delayMs)
  assert.equal(b.channel, 'voice', '气到这一步就念出来')
})

test('E38 只有火气高 → hot：连发 2 条', () => {
  const b = behaviorOf(dims({ anger: 70, patience: 60 }), { now: T })
  assert.equal(b.tone, 'hot')
  assert.equal(b.burst, 2)
})

test('E39 兴致低 → flat：一条，短', () => {
  const b = behaviorOf(dims({ interest: 20 }), { now: T })
  assert.equal(b.tone, 'flat')
  assert.equal(b.burst, 1)
  assert.equal(b.maxChars, 40)
})

test('E40 兴致高 → eager：两条、每条更长', () => {
  const b = behaviorOf(dims({ interest: 85 }), { now: T })
  assert.equal(b.tone, 'eager')
  assert.equal(b.burst, 2)
  assert.ok(b.maxChars > 60)
})

test('E41 耐心低（火气还没到）→ poke：两条', () => {
  const b = behaviorOf(dims({ patience: 40, anger: 20 }), { now: T })
  assert.equal(b.tone, 'poke')
  assert.equal(b.burst, 2)
})

test('E42 什么都正常 → calm：一条，最长', () => {
  const b = behaviorOf(empty(), { now: T })
  assert.equal(b.tone, 'calm')
  assert.equal(b.burst, 1)
  assert.equal(b.maxChars, 120)
})

test('E43 安静时段一条都不发（不是少发，是 0）', () => {
  const e = applyEvent(applyEvent(empty(), 'defiance', { now: T }), 'defiance', { now: T })
  const b = behaviorOf(e, { now: T, quiet: true })
  assert.equal(b.burst, 0)
  assert.equal(b.channel, 'text', '安静时段再气也只发文字')
})

test('E44 一小时上限：已经发了 12 条就一条都不发；发了 11 条只剩 1 条', () => {
  const e = emotionOf([replied(T, 'brief'), replied(T, 'brief'), replied(T, 'brief')], { now: T })
  assert.equal(behaviorOf(e, { now: T, sentLastHour: BURST_HOURLY_CAP }).burst, 0)
  assert.equal(behaviorOf(e, { now: T, sentLastHour: BURST_HOURLY_CAP + 5 }).burst, 0, '超了也不能变成负数')
  assert.equal(behaviorOf(e, { now: T, sentLastHour: BURST_HOURLY_CAP - 1 }).burst, 1)
})

test('E45 档位只在 TONES 里，渠道只有 text / voice', () => {
  const seen = new Set()
  for (let anger = 0; anger <= 100; anger += 10) {
    for (let patience = 0; patience <= 100; patience += 10) {
      for (let interest = 0; interest <= 100; interest += 10) {
        const e = { ...empty(), anger, patience, interest }
        const b = behaviorOf(e, { now: T })
        assert.ok(TONES.includes(b.tone), '档位越界：' + b.tone)
        assert.ok(['text', 'voice'].includes(b.channel), '渠道越界：' + b.channel)
        assert.ok(b.burst >= 0 && b.burst <= 3)
        seen.add(b.tone)
      }
    }
  }
  assert.ok(seen.size >= 5, '六个档位至少走到五个：' + [...seen].join(','))
})

// ---------------------------------------------------------------- 九、硬边界三条

test('H1 硬边界一：情绪不改锁/额度/判定——wall*.js / force.js / machine.js 都不 import 它', () => {
  const files = readdirSync(LIB).filter((f) => f.endsWith('.js'))
  const forbade = files.filter((f) => /^wall.*\.js$/.test(f) || f === 'force.js' || f === 'machine.js')
  assert.deepEqual(forbade.sort(), ['force.js', 'machine.js', 'wall.js', 'wall_auditor.js', 'wall_voice.js'])
  for (const f of forbade) {
    const src = readFileSync(join(LIB, f), 'utf8')
    assert.equal(/from\s+['"]\.\/emotion\.js['"]/.test(src), false, f + ' 不许 import emotion.js')
  }
  // 反过来：emotion.js 也不许 import 它们（问不动判定，也改不了锁）。
  const own = readFileSync(join(LIB, 'emotion.js'), 'utf8')
  for (const f of forbade) assert.equal(new RegExp("from\\s+['\"]\\./" + f.replace('.', '\\.') + "['\"]").test(own), false, 'emotion.js 不许 import ' + f)
})

test('H2 硬边界二：连发每小时硬上限 12 条，安静时段 0 条', () => {
  assert.equal(BURST_HOURLY_CAP, 12)
  const worst = { ...empty(), anger: 100, patience: 0, interest: 100 }
  // 单轮最多 3 条，一小时 4 轮就顶到 12。
  assert.equal(behaviorOf(worst, { now: T }).burst, 3)
  assert.equal(behaviorOf(worst, { now: T, sentLastHour: 9 }).burst, 3)
  assert.equal(behaviorOf(worst, { now: T, sentLastHour: 10 }).burst, 2)
  assert.equal(behaviorOf(worst, { now: T, sentLastHour: 12 }).burst, 0)
  assert.equal(behaviorOf(worst, { now: T, quiet: true }).burst, 0)
})

test('H3 硬边界三：情绪不自己写文案——事件表没有 text，输出没有句子', () => {
  for (const [id, ev] of Object.entries(EVENTS)) {
    assert.equal('text' in ev, false, id + ' 不许带文案')
    assert.equal('line' in ev, false, id + ' 不许带文案')
    assert.equal(typeof ev.note, 'string')
    assert.ok(ev.note.length <= 6, 'note 是个短标签，不是句子：' + ev.note)
    for (const dim of Object.keys(ev.dims)) assert.ok(DIMS.includes(dim), id + ' 推了不存在的轴：' + dim)
  }
  const b = behaviorOf({ ...empty(), anger: 100, patience: 0 }, { now: T })
  for (const v of Object.values(b)) {
    if (typeof v === 'string') assert.ok(['text', 'voice'].includes(v) || TONES.includes(v), '不许出现自由文本：' + v)
  }
  assert.equal('text' in b, false, '情绪不下发文案，只有怎么发')
})
