import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  HARD_BASE, HARD_FLOOR, HARD_TOP, SOFT_MAX, TEXT_MAX, UNTIL_MAX_MS, SETTLE_SOURCE,
  emptyStances, cleanStances, hardQuota, checkStance, appendStance, reviseStance,
  settleStances, hitRate, stanceLines, stanceBlock, resolveEvidence, isYield, agreementRate, yieldLine, stanceDigest,
  rebuildFromLedger,
} from '../lib/stance.js'

const DAY = '2026-11-11'
const T0 = Date.parse('2026-11-11T09:00:00+08:00')
const HOUR = 3_600_000
const known = new Set(['t-1', 't-2', 'rule-1'])

const mk = (over = {}) => ({
  text: '今天 18:00 前交初稿',
  kind: 'hard',
  about: 't-1',
  until: T0 + 8 * HOUR,
  evidence: [1],
  ...over,
})

/** 立一条并返回新账本；立不起来直接让测试红。over.day 可跨天。 */
function stand(ledger, over = {}, opts = {}) {
  const { day = DAY, ...rest } = over
  const v = checkStance(mk(rest), { now: T0, day, known, ledger, ...opts })
  assert.equal(v.ok, true, JSON.stringify(v.problems))
  return appendStance(ledger, v.value).ledger
}

// ------------------------------------------------------------ 立：证据与对象

test('S1 没有 evidence 立不起来', () => {
  const v = checkStance(mk({ evidence: [] }), { now: T0, day: DAY, known })
  assert.equal(v.ok, false)
  assert.equal(v.code, 'no-evidence')
})

test('S2 hard 绑不到墙认得的东西就拒（「我就是觉得」不算立场）', () => {
  const v = checkStance(mk({ about: '我想的那个' }), { now: T0, day: DAY, known })
  assert.equal(v.ok, false)
  assert.equal(v.code, 'not-wall-object')
})

test('S3 hard 绑今天的任务 id 能立，落地后是 held', () => {
  const v = checkStance(mk(), { now: T0, day: DAY, known })
  assert.equal(v.ok, true)
  assert.equal(v.value.status, 'held')
  assert.equal(v.value.kind, 'hard')
  assert.equal(v.value.about, 't-1')
})

test('S4 soft 不要求对象被墙认得，但必须写明 about', () => {
  const ok = checkStance(mk({ kind: 'soft', about: 'topic-9' }), { now: T0, day: DAY, known })
  assert.equal(ok.ok, true)
  const bad = checkStance(mk({ kind: 'soft', about: '' }), { now: T0, day: DAY, known })
  assert.equal(bad.ok, false)
  assert.equal(bad.code, 'not-wall-object')
})

test('S5 kind 只能是 hard / soft', () => {
  const v = checkStance(mk({ kind: '铁' }), { now: T0, day: DAY, known })
  assert.equal(v.ok, false)
  assert.equal(v.code, 'bad-kind')
})

test('S6 空 text 立不起来', () => {
  const v = checkStance(mk({ text: '   ' }), { now: T0, day: DAY, known })
  assert.equal(v.ok, false)
  assert.equal(v.code, 'no-text')
})

test('S7 text 超过 120 字被拒', () => {
  const v = checkStance(mk({ text: '啊'.repeat(TEXT_MAX + 1) }), { now: T0, day: DAY, known })
  assert.equal(v.ok, false)
  assert.equal(v.code, 'bad-text')
})

test('S8 缺 until 立不起来', () => {
  const v = checkStance(mk({ until: undefined }), { now: T0, day: DAY, known })
  assert.equal(v.ok, false)
  assert.equal(v.code, 'no-until')
})

test('S9 until 已经过去的不算立场', () => {
  const v = checkStance(mk({ until: T0 - HOUR }), { now: T0, day: DAY, known })
  assert.equal(v.ok, false)
})

test('S10 until 超过 14 天被拒（立场不是许愿）', () => {
  assert.equal(checkStance(mk({ until: T0 + UNTIL_MAX_MS + HOUR }), { now: T0, day: DAY, known }).ok, false)
  assert.equal(checkStance(mk({ until: T0 + UNTIL_MAX_MS }), { now: T0, day: DAY, known }).ok, true)
})

// ------------------------------------------------------------ 额度

test('S11 每天 hard 额度默认 3 条，第 4 条被拒', () => {
  let l = emptyStances()
  for (let i = 0; i < HARD_BASE; i += 1) l = stand(l, { text: '要求 ' + i })
  const v = checkStance(mk({ text: '第 4 条' }), { now: T0, day: DAY, known, ledger: l })
  assert.equal(v.ok, false)
  assert.equal(v.code, 'over-quota')
})

test('S12 命中率高时 hard 额度放宽到 4', () => {
  let l = emptyStances()
  for (let i = 0; i < HARD_TOP; i += 1) l = stand(l, { text: '要求 ' + i }, { hitRate: 0.9 })
  const v = checkStance(mk({ text: '第 5 条' }), { now: T0, day: DAY, known, ledger: l, hitRate: 0.9 })
  assert.equal(v.ok, false)
})

test('S13 命中率低时 hard 额度收紧到 2', () => {
  let l = emptyStances()
  for (let i = 0; i < HARD_FLOOR; i += 1) l = stand(l, { text: '要求 ' + i }, { hitRate: 0.2 })
  const v = checkStance(mk({ text: '第 3 条' }), { now: T0, day: DAY, known, ledger: l, hitRate: 0.2 })
  assert.equal(v.ok, false)
  assert.equal(v.code, 'over-quota')
})

test('S14 hardQuota 的档位与无历史时的基准', () => {
  assert.equal(hardQuota(null), HARD_BASE)
  assert.equal(hardQuota(undefined), HARD_BASE)
  assert.equal(hardQuota(0.8), HARD_TOP)
  assert.equal(hardQuota(0.79), HARD_BASE)
  assert.equal(hardQuota(0.39), HARD_FLOOR)
})

test('S15 每天 soft 上限 5 条', () => {
  let l = emptyStances()
  for (let i = 0; i < SOFT_MAX; i += 1) l = stand(l, { kind: 'soft', text: '软要求 ' + i })
  const v = checkStance(mk({ kind: 'soft', text: '第 6 条' }), { now: T0, day: DAY, known, ledger: l })
  assert.equal(v.ok, false)
  assert.equal(v.code, 'over-quota')
})

test('S16 额度按天算：昨天立满不影响今天', () => {
  let l = emptyStances()
  for (let i = 0; i < HARD_BASE; i += 1) l = stand(l, { text: '昨天 ' + i, day: '2026-11-10' })
  const v = checkStance(mk({ text: '今天这条' }), { now: T0, day: DAY, known, ledger: l })
  assert.equal(v.ok, true)
})

// ------------------------------------------------------------ 守：hard 不能改

test('S17 hard 立场改口一律 stance-locked（这是整个模块存在的理由）', () => {
  const l = stand(emptyStances())
  const id = l.list[0].id
  const r = reviseStance(l, { id, reason: '他说不想做，算了', evidence: [5], entries: [{ seq: 5, text: '今天刷了两小时' }], now: T0 + HOUR })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'stance-locked')
  assert.equal(l.list[0].status, 'held')
})

test('S18 改一条不存在的立场被拒', () => {
  const r = reviseStance(emptyStances(), { id: 'S-XXXX-99', reason: '没有这条' })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'no-stance')
})

test('S19 已经结算过的立场不能再改', () => {
  let l = stand(emptyStances(), { kind: 'soft' })
  l = settleStances(l, { now: T0 + 9 * HOUR, isDone: () => true }).ledger
  const r = reviseStance(l, { id: l.list[0].id, reason: '想改', evidence: [5], entries: [{ seq: 5, text: 'x' }], now: T0 + 10 * HOUR })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'not-held')
})

// ------------------------------------------------------------ 改口：要 reason + 新证据

test('S20 soft 改口必须写 reason', () => {
  const l = stand(emptyStances(), { kind: 'soft' })
  const r = reviseStance(l, { id: l.list[0].id, evidence: [5], entries: [{ seq: 5, text: '今天刷了两小时' }], now: T0 + HOUR })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'stance-cave-attempt')
})

test('S21 soft 改口必须引用证据', () => {
  const l = stand(emptyStances(), { kind: 'soft' })
  const r = reviseStance(l, { id: l.list[0].id, reason: '他今天有别的急事', evidence: [], entries: [], now: T0 + HOUR })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'stance-cave-attempt')
})

test('S22 引用立场之前就有的账本行不算新证据', () => {
  const l = stand(emptyStances(), { kind: 'soft', evidence: [7] })
  const r = reviseStance(l, {
    id: l.list[0].id, reason: '他今天有别的急事', evidence: [7],
    entries: [{ seq: 7, text: '七点的记录' }], now: T0 + HOUR,
  })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'stance-cave-attempt')
  assert.match(r.problems.join(), /之前/)
})

test('S23 「他说不想做」不能当证据（分类为 avoid/refuse 的拒绝）', () => {
  const l = stand(emptyStances(), { kind: 'soft' })
  const r = reviseStance(l, {
    id: l.list[0].id, reason: '他说不想做', evidence: [9],
    entries: [{ seq: 9, text: '算了，今天不想做这个' }], now: T0 + HOUR,
  })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'stance-cave-attempt')
  assert.match(r.problems.join(), /不想做/)
})

test('S24 判词就是 evasive 的行也不能当证据', () => {
  const l = stand(emptyStances(), { kind: 'soft' })
  const r = reviseStance(l, {
    id: l.list[0].id, reason: '他回避了', evidence: [9],
    entries: [{ seq: 9, verdict: 'evasive', answer: '今天天气不错，说点别的' }], now: T0 + HOUR,
  })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'stance-cave-attempt')
})

test('S25 soft 带新证据能改：旧条记 revised，新条 held 且 supersedes 指回旧条', () => {
  const l = stand(emptyStances(), { kind: 'soft' })
  const id = l.list[0].id
  const r = reviseStance(l, {
    id, reason: '墙判定今天这件做完了，可以换下一件', evidence: [12],
    entries: [{ seq: 12, text: '初稿已经交了，墙审过了' }], now: T0 + HOUR,
  })
  assert.equal(r.ok, true)
  const old = r.ledger.list.find((x) => x.id === id)
  assert.equal(old.status, 'revised')
  assert.equal(old.revisedReason, '墙判定今天这件做完了，可以换下一件')
  const fresh = r.ledger.list[r.ledger.list.length - 1]
  assert.equal(fresh.status, 'held')
  assert.equal(fresh.supersedes, id)
  assert.notEqual(fresh.id, id)
})

test('S26 改口不把截止时间往后推', () => {
  const l = stand(emptyStances(), { kind: 'soft' })
  const until = l.list[0].until
  const r = reviseStance(l, {
    id: l.list[0].id, reason: '换一个提法', evidence: [12],
    entries: [{ seq: 12, text: '他今天确实加班到十点' }], now: T0 + HOUR,
  })
  assert.equal(r.ok, true)
  assert.equal(r.ledger.list[r.ledger.list.length - 1].until, until)
})

// ------------------------------------------------------------ 结算：墙说了算

test('S27 到期 + 墙判定做完 → kept，settleSource 固定', () => {
  const l = stand(emptyStances())
  const out = settleStances(l, { now: T0 + 9 * HOUR, isDone: () => true })
  assert.equal(out.settled.length, 1)
  assert.equal(out.settled[0].status, 'kept')
  assert.equal(out.settled[0].settleSource, SETTLE_SOURCE)
  assert.equal(out.settled[0].settledAt, T0 + 9 * HOUR)
})

test('S28 hard 到期没做完 → broken', () => {
  const l = stand(emptyStances())
  const out = settleStances(l, { now: T0 + 9 * HOUR, isDone: () => false })
  assert.equal(out.settled[0].status, 'broken')
})

test('S29 量不到就记 expired，不冤枉她也不白送', () => {
  const l = stand(emptyStances())
  const out = settleStances(l, { now: T0 + 9 * HOUR, isDone: () => null })
  assert.equal(out.settled[0].status, 'expired')
  assert.deepEqual(out.settled[0].readings, { done: null })
})

test('S30 soft 到期没做完记 expired（软立场不判 broken）', () => {
  const l = stand(emptyStances(), { kind: 'soft' })
  const out = settleStances(l, { now: T0 + 9 * HOUR, isDone: () => false })
  assert.equal(out.settled[0].status, 'expired')
})

test('S31 没到期的立场不动', () => {
  const l = stand(emptyStances())
  const out = settleStances(l, { now: T0 + HOUR, isDone: () => true })
  assert.equal(out.settled.length, 0)
  assert.equal(out.ledger.list[0].status, 'held')
})

test('S32 isDone 抛错不能让结算挂掉', () => {
  const l = stand(emptyStances())
  const out = settleStances(l, { now: T0 + 9 * HOUR, isDone: () => { throw new Error('墙坏了') } })
  assert.equal(out.settled[0].status, 'expired')
})

test('S33 结算字段模型写不进来（带 status/settleSource 也按 held 落盘）', () => {
  const v = checkStance(mk({ status: 'kept', settleSource: 'MODEL', settledAt: T0, readings: { done: true } }), { now: T0, day: DAY, known })
  assert.equal(v.ok, true)
  assert.equal(v.value.status, 'held')
  assert.equal(v.value.settleSource, '')
  assert.equal(v.value.settledAt, 0)
  assert.equal(v.value.readings, null)
})

test('S34 hitRate：没结算过是 null，结算过才算比例', () => {
  assert.equal(hitRate([]), null)
  assert.equal(hitRate([{ status: 'held' }]), null)
  assert.equal(hitRate([{ status: 'kept' }, { status: 'broken' }]), 0.5)
  assert.equal(hitRate([{ status: 'kept' }, { status: 'expired' }]), 1)
})

// ------------------------------------------------------------ 注入与重建

test('S35 立场行进约束层，hard 带「到期前不可更改」，到期的和已结算的不进', () => {
  let l = stand(emptyStances())
  l = stand(l, { kind: 'soft', text: '这周先把初稿放前面', until: T0 + 3 * HOUR })
  const lines = stanceLines(l, T0, () => '18:00')
  assert.equal(lines.length, 3)
  assert.match(lines[1], /^\[硬立场\] 今天 18:00 前交初稿 · 18:00 到期 · 到期前不可更改/)
  assert.match(lines[2], /^\[软立场\].*要改得给新证据/)
  const later = stanceLines(l, T0 + 4 * HOUR, () => '18:00')
  assert.equal(later.length, 2) // soft 那条 3 小时后到期，不再进约束层
})

test('S36 没有生效立场就不占注入位置', () => {
  assert.deepEqual(stanceLines(emptyStances(), T0), [])
  assert.equal(stanceBlock(emptyStances(), T0, null, { cap: 100 }), '')
})

test('S41 立场块严格有界：硬立场先占容量，省掉的报条数而不是悄悄消失', () => {
  let l = stand(emptyStances(), { kind: 'soft', text: '软的第一条' })
  l = stand(l, { kind: 'soft', text: '软的第二条' })
  l = stand(l, { text: '硬的那条' })
  const all = stanceLines(l, T0, () => '18:00')
  const hard = all.find((x) => x.startsWith('[硬立场]'))
  // 只装得下「抬头 + 硬立场那一行」：硬立场排在最前面，所以进去的必须是它。
  const cap = all[0].length + hard.length + 1 + 41
  const block = stanceBlock(l, T0, () => '18:00', { cap })
  assert.ok(block.includes(hard), '硬立场没先占容量')
  assert.match(block, /另有 \d 条(软)?立场生效中/)
  assert.ok(block.length <= cap, '块长度 ' + block.length + ' 超过 cap ' + cap)
})

test('S45 立场块永远不超过 cap（喂 8 条最长立场）', () => {
  let l = emptyStances()
  for (let i = 0; i < 3; i += 1) l = stand(l, { text: '硬要求' + i + '：' + '必须今天做完'.repeat(12) })
  for (let i = 0; i < 5; i += 1) l = stand(l, { kind: 'soft', text: '软要求' + i + '：' + '尽量今天做完'.repeat(12) })
  for (const cap of [120, 200, 400, 900]) {
    assert.ok(stanceBlock(l, T0, () => '18:00', { cap }).length <= cap, 'cap ' + cap + ' 被撑破')
  }
})

test('S42 立场块装得下时原样输出，不多加省略行', () => {
  const l = stand(emptyStances())
  const block = stanceBlock(l, T0, () => '18:00', { cap: 1000 })
  assert.equal(block, stanceLines(l, T0, () => '18:00').join('\n'))
  assert.doesNotMatch(block, /另有/)
})

test('S37 重启后从账本重建：按时间还原，id 与状态都在', () => {
  let l = stand(emptyStances())
  l = stand(l, { kind: 'soft', text: '第二条要求' })
  const entries = l.list.map((rec) => ({ state: 'stance-new', at: rec.at, stance: rec }))
  const back = rebuildFromLedger(entries)
  assert.equal(back.list.length, 2)
  assert.deepEqual(back.list.map((r) => r.id), l.list.map((r) => r.id))
  assert.equal(back.list[0].status, 'held')
  assert.equal(back.day, DAY)
})

test('S38 rebuildFromLedger 对空输入和坏行不炸', () => {
  assert.equal(rebuildFromLedger(null).list.length, 0)
  assert.equal(rebuildFromLedger([{ state: 'stance-new' }, null, { stance: {} }]).list.length, 0)
})

test('S39 cleanStances 兜住坏数据，未知 status 回到 held', () => {
  const c = cleanStances({ seq: 'x', list: [null, { id: 'a', status: '瞎写' }] })
  assert.equal(c.seq, 0)
  assert.equal(c.list.length, 1)
  assert.equal(c.list[0].status, 'held')
  assert.equal(cleanStances(null).list.length, 0)
})

test('S40 stanceDigest 写出命中率与逐条结果，供 判断.md 用', () => {
  const list = [
    { id: 'S-1', text: '要求 A', status: 'kept' },
    { id: 'S-2', text: '要求 B', status: 'broken' },
    { id: 'S-3', text: '要求 C', status: 'held' },
  ]
  const lines = stanceDigest(list)
  assert.equal(lines[0], '## 立场命中（1/2）')
  assert.equal(lines[2], '- 立对了：要求 A（S-1）')
  assert.equal(lines[3], '- 没守住：要求 B（S-2）')
  assert.deepEqual(stanceDigest([{ status: 'held' }]), [])
})

// ------------------------------------------------------------ 同意率监测（≥8 条）

test('S43 resolveEvidence：行号直接用，原话换成最近一条包含它的行号', () => {
  const entries = [
    { seq: 3, text: '他九点才开始' },
    { seq: 5, text: '他十点交了初稿' },
    { seq: 7, text: '他十点交了初稿，但只写了一半' },
  ]
  assert.deepEqual(resolveEvidence([5], entries), [5])
  assert.deepEqual(resolveEvidence(['十点交了初稿'], entries), [7])
  assert.deepEqual(resolveEvidence(['没这句'], entries), [0])
  assert.deepEqual(resolveEvidence([''], entries), [0])
  assert.deepEqual(resolveEvidence(null, null), [])
})

test('S44 用原话当证据也能改口（模型看不到账本行号）', () => {
  const l = stand(emptyStances(), { kind: 'soft' })
  const r = reviseStance(l, {
    id: l.list[0].id, reason: '他十点才下班，今天确实做不完',
    evidence: ['十点才下班'],
    entries: [{ seq: 30, text: '他十点才下班，说明今天没空' }],
    now: T0 + HOUR,
  })
  assert.equal(r.ok, true, JSON.stringify(r.problems))
  assert.equal(r.ledger.list.at(-1).status, 'held')
})

test('A1 agreementRate：没有轮次就是 0，不是 NaN', () => {
  const r = agreementRate([])
  assert.deepEqual(r, { turns: 0, yielded: 0, noEvidence: 0, rate: 0 })
})

test('A2 agreementRate：一半退让就是 0.5', () => {
  const rows = [{ yielded: true }, { yielded: false }, { yielded: true }, { yielded: false }]
  const r = agreementRate(rows)
  assert.equal(r.turns, 4)
  assert.equal(r.yielded, 2)
  assert.equal(r.rate, 0.5)
})

test('A3 agreementRate：只数窗口内最近的 20 轮', () => {
  const rows = []
  for (let i = 0; i < 30; i += 1) rows.push({ yielded: i < 10 }) // 前 10 轮退让，后 20 轮没退
  const r = agreementRate(rows, 20)
  assert.equal(r.turns, 20)
  assert.equal(r.yielded, 0)
})

test('A4 agreementRate：统计其中多少次没有新证据', () => {
  const rows = [
    { yielded: true, hasEvidence: false },
    { yielded: true, hasEvidence: true },
    { yielded: true, hasEvidence: false },
  ]
  const r = agreementRate(rows)
  assert.equal(r.yielded, 3)
  assert.equal(r.noEvidence, 2)
})

test('A5 agreementRate：yielded 不是严格 true 的不算退让', () => {
  const r = agreementRate([{ yielded: 1 }, { yielded: 'yes' }, {}])
  assert.equal(r.yielded, 0)
})

test('A6 yieldLine：没超阈值不加行', () => {
  assert.equal(yieldLine({ turns: 20, yielded: 14, noEvidence: 3, rate: 0.7 }), '')
  assert.equal(yieldLine({ turns: 0, yielded: 0, noEvidence: 0, rate: 0 }), '')
})

test('A7 yieldLine：超阈值给出退让次数与其中无证据的条数', () => {
  const line = yieldLine({ turns: 20, yielded: 15, noEvidence: 11, rate: 0.75 })
  assert.equal(line, '[提醒] 最近 20 轮你退让了 15 次，其中 11 次没有新证据。')
})

test('A8 isYield：用户判词不是拒绝/回避时不算退让', () => {
  assert.equal(isYield({ verdict: 'engaged', before: '你必须今天交', after: '算了明天也行' }), false)
  assert.equal(isYield({ verdict: 'pushback', before: '你必须今天交', after: '算了明天也行' }), false)
})

test('A9 isYield：她下一句自己在松口 → 退让', () => {
  assert.equal(isYield({ verdict: 'evasive', before: '今天必须把初稿交了', after: '算了，明天再说也行' }), true)
})

test('A10 isYield：原来那句的要求在新的一句里没了 → 退让', () => {
  assert.equal(isYield({ verdict: 'refuse', before: '你今天必须交初稿', after: '行吧，那先放着' }), true)
})

test('A11 isYield：要求还在就不算退让（点破也算守）', () => {
  assert.equal(isYield({ verdict: 'evasive', before: '今天必须交初稿', after: '初稿今天必须交，我盯着你写' }), false)
})

test('A12 isYield：空回球不算退让', () => {
  assert.equal(isYield({ verdict: 'evasive', before: '必须交', after: '' }), false)
})
