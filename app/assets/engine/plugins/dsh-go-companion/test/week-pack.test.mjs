// 周冷数据包：只有数字；豁免带原话；末尾固定提问要【周验收】块。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { prevYmd, weekPack, WEEK_PROMPT } from '../lib/week_pack.js'

const TODAY = '2026-11-21'
const dayOf = (at) => new Date(at).toISOString().slice(0, 10)
const at = (day, h = 10) => Date.parse(day + 'T' + String(h).padStart(2, '0') + ':00:00Z')
const S = (day, status, extra = {}) => ({ id: day + status + Math.random(), day, title: '点' + status, kind: 'quiz', status, paidMinutes: status === 'stretch' ? 19 : status === 'floor' ? 15 : 0, sameDay: false, ...extra })

test('七天：按天数、通过率、空天、豁免原话、紧急 / 接管 / 改小被拒都数出来', () => {
  const y = prevYmd(TODAY)
  const seals = { items: [
    S(TODAY, 'stretch'), S(TODAY, 'below'), S(y, 'floor', { sameDay: true }), S(y, 'waived', { waivedReason: '发烧' }),
    S(prevYmd(TODAY, 9), 'stretch'), // 一周外，不算
  ] }
  const entries = [
    { state: 'bout-open', at: at(TODAY) }, { state: 'bout-open', at: at(y) },
    { state: 'gemini-recap', receipt: true, verdict: 'pass', at: at(TODAY) }, { state: 'gemini-recap', receipt: true, verdict: 'partial', at: at(TODAY) },
    { state: 'gemini-recap', at: at(TODAY) }, // 不是回执（老的口头复盘），不算
    { state: 'emergency-exit', at: at(y) }, { state: 'breakglass', at: at(y) }, { state: 'takeover-start', at: at(TODAY) },
    { state: 'seal-lower-refused', at: at(TODAY) },
  ]
  const p = weekPack({ entries, seals, today: TODAY, dayOf })
  assert.equal(p.totals.from, prevYmd(TODAY, 6))
  assert.equal(p.totals.checkpoints, 4)
  assert.equal(p.totals.passRate, 67) // 拉伸 1 + 保底 1，没过 1；豁免不算进分母
  assert.equal(p.totals.emptyDays, 5)
  assert.equal(p.totals.receipts, 2)
  assert.equal(p.totals.receiptsPass, 1)
  assert.equal(p.totals.emergencies, 2)
  assert.equal(p.totals.takeovers, 1)
  assert.equal(p.totals.lowerRefused, 1)
  assert.equal(p.totals.live, 1)
  assert.equal(p.totals.paid, 34)
  assert.deepEqual(p.waivers, [{ day: y, title: '点waived', reason: '发烧' }])
  assert.match(p.text, /「点waived」：发烧/)
  assert.ok(p.text.endsWith(WEEK_PROMPT))
  assert.match(WEEK_PROMPT, /最多 3 条调整/)
})

test('什么都没有：通过率是「—」，不是 0%', () => {
  const p = weekPack({ entries: [], seals: { items: [] }, today: TODAY, dayOf })
  assert.equal(p.totals.passRate, null)
  assert.match(p.text, /通过率 —/)
  assert.equal(p.rows.length, 7)
})
