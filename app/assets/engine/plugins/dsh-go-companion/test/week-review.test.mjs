// 【周验收】回收：容错解析、最多 3 条、逐条采纳、只进上下文 7 天。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { addReview, decideReview, parseWeekReview, reviewMarkdown, reviewPromptLines, REVIEW_TTL_MS } from '../lib/week_review.js'

const PASTE = `好的，下面是我的判断。

**【周验收】**
- 判断：这一周是在演，七天里四天没有验收点。
- 调整1：题库类保底 10 → 15，理由：保底过低
* 调整 2：晚上 20:00 以后不排新验收点
调整3: （可选）
调整4：多余的一条
下周盯：没过保底的个数

希望有帮助！`

test('容错解析：前后有废话、markdown 符号、半角冒号、（可选）占位、多出来的第 4 条', () => {
  const p = parseWeekReview(PASTE)
  assert.equal(p.ok, true)
  assert.equal(p.verdict, '这一周是在演，七天里四天没有验收点。')
  assert.deepEqual(p.adjustments, ['题库类保底 10 → 15，理由：保底过低', '晚上 20:00 以后不排新验收点', '多余的一条'])
  assert.equal(p.watch, '没过保底的个数')
  assert.equal(parseWeekReview('随便说点什么').ok, false)
})

test('逐条采纳；采纳的进鲸澜上下文 7 天，过期就没了；没采纳的不进', () => {
  const t0 = 1_800_000_000_000
  const a = addReview(null, parseWeekReview(PASTE), { now: t0 })
  assert.equal(a.review.status, 'pending')
  assert.deepEqual(reviewPromptLines(a.state, t0), [], '没决定之前不进上下文')
  const d = decideReview(a.state, a.review.id, [0], { now: t0 + 1000 })
  assert.equal(d.ok, true)
  const lines = reviewPromptLines(d.state, t0 + 2000)
  assert.match(lines.join('\n'), /1\. 题库类保底 10 → 15/)
  assert.doesNotMatch(lines.join('\n'), /20:00/)
  assert.match(lines.join('\n'), /这周盯：没过保底的个数/)
  assert.deepEqual(reviewPromptLines(d.state, t0 + REVIEW_TTL_MS + 5000), [])
  assert.equal(decideReview(d.state, a.review.id, [1]).ok, false, '决定过的不能再改')
  assert.match(reviewMarkdown(d.review), /（采纳）[\s\S]*（没采纳）/)
})

test('新来的一份顶掉还没决定的旧的', () => {
  const a = addReview(null, parseWeekReview(PASTE), { now: 1 })
  const b = addReview(a.state, { ok: true, verdict: '新的', adjustments: ['x'], watch: '' }, { now: 2, by: 'gemini' })
  assert.equal(b.state.items.length, 1)
  assert.equal(b.state.items[0].by, 'gemini')
})
