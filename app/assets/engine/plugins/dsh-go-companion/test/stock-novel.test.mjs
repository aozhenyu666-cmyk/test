import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  DUP_MAX, RECENT_WINDOW, SHORT_OK, SKIP_STATE,
  contentGate, dupOf, gateRound, hitsOf, isStock, nounsOf, overlapOf, recentOwnTexts,
} from '../lib/stock.js'
import { CONCRETE_POOL, GOOD_SAMPLE, STOCK_SAMPLE } from './fixtures/stock-samples.mjs'

const gen = (text, at = 1) => ({ state: 'generated', text, at })

test('样本集就是 20 句，且句句不同', () => {
  assert.equal(STOCK_SAMPLE.length, 20)
  assert.equal(new Set(STOCK_SAMPLE).size, 20)
})

test('20 句套话里，每一句都至少被一道守卫拦下', () => {
  for (const s of STOCK_SAMPLE) {
    const g = contentGate(s, { recent: [], nouns: CONCRETE_POOL })
    assert.equal(!g.pass || isStock(s), true, s + ' 没被拦下')
  }
})

test('20 句套话里，绝大部分是因为"没有具体名词"被拦（模板守卫只认那几句原句）', () => {
  let byContent = 0
  for (const s of STOCK_SAMPLE) {
    const g = contentGate(s, { recent: [], nouns: CONCRETE_POOL })
    if (g.code === 'no-content') byContent += 1
  }
  assert.ok(byContent >= 12, '只有 ' + byContent + ' 句走了信息增量守卫')
})

test('反向样本：带具体名词的正常发球一句都不许被误杀', () => {
  for (const s of GOOD_SAMPLE) {
    assert.equal(isStock(s), false, s)
    assert.deepEqual(contentGate(s, { recent: [], nouns: CONCRETE_POOL }), { pass: true }, s)
  }
})

test('recentOwnTexts：只要 generated，按旧→新，窗口默认 5 条', () => {
  const entries = [
    gen('第一句有内容'), { state: 'failed', text: '失败的' }, gen('第二句有内容'),
    { state: 'accepted' }, gen('第三句有内容'), gen('第四句有内容'),
    gen('第五句有内容'), gen('第六句有内容'),
  ]
  const out = recentOwnTexts(entries)
  assert.equal(out.length, RECENT_WINDOW)
  assert.deepEqual(out, ['第二句有内容', '第三句有内容', '第四句有内容', '第五句有内容', '第六句有内容'])
})

test('recentOwnTexts：空文本不算，账本为空也不炸', () => {
  assert.deepEqual(recentOwnTexts([]), [])
  assert.deepEqual(recentOwnTexts(null), [])
  assert.deepEqual(recentOwnTexts([gen('   '), gen(''), gen('有东西')]), ['有东西'])
})

test('overlapOf：自己和自己 1，完全无关接近 0', () => {
  assert.equal(overlapOf('写报告那件事昨天说半小时能完', '写报告那件事昨天说半小时能完'), 1)
  assert.ok(overlapOf('申论下午三点交', '通勤记不住东西') < DUP_MAX)
})

test('dupOf：取和最近几句里最像的那一句', () => {
  const recent = ['申论下午三点交', '写报告半小时能完', 'B站看了二十分钟']
  assert.equal(dupOf('写报告半小时能完', recent), 1)
  assert.ok(dupOf('申论下午三点交上去', recent) >= DUP_MAX)
})

test('contentGate：和自己刚说过的一句几乎一样 → dup', () => {
  const s = '你在 B站 看了二十分钟了，我猜你在躲申论'
  const g = contentGate(s + '吧', { recent: [s], nouns: CONCRETE_POOL })
  assert.equal(g.pass, false)
  assert.equal(g.code, 'dup')
})

test('contentGate：整句没有一个具体名词 → no-content', () => {
  assert.deepEqual(contentGate('你要相信自己，慢慢来', { recent: [], nouns: CONCRETE_POOL }), { pass: false, code: 'no-content' })
})

test('contentGate：手上没有名词池时不判 no-content（不能拿没有的东西当罪状）', () => {
  assert.deepEqual(contentGate('你要相信自己，慢慢来', { recent: [], nouns: [] }), { pass: true })
  assert.deepEqual(contentGate('你要相信自己，慢慢来'), { pass: true })
})

test('contentGate：空句 → empty', () => {
  assert.deepEqual(contentGate('', { nouns: CONCRETE_POOL }), { pass: false, code: 'empty' })
  assert.deepEqual(contentGate('   ', { nouns: CONCRETE_POOL }), { pass: false, code: 'empty' })
  assert.deepEqual(contentGate(null), { pass: false, code: 'empty' })
})

test('contentGate：短句不判重话（短句本来就没什么可比）', () => {
  const short = '申论交'
  assert.ok(short.replace(/\s/g, '').length < SHORT_OK)
  assert.deepEqual(contentGate(short, { recent: [short], nouns: CONCRETE_POOL }), { pass: true })
})

test('hitsOf：数出来的是命中个数，不是比例', () => {
  assert.equal(hitsOf('申论和行测都还没动', CONCRETE_POOL), 2)
  assert.equal(hitsOf('你要相信自己', CONCRETE_POOL), 0)
})

test('nounsOf：从任务/话题/手机/日期里收词，去重、去停用词、有上限', () => {
  const nouns = nounsOf([
    { task: '写报告', title: '申论的审题' },
    '下午三点要交申论',
    ['行测', '行测'],
    { phone: null, app: 'B站' },
  ])
  assert.ok(nouns.includes('写报告'))
  assert.ok(nouns.includes('申论'))
  assert.ok(nouns.includes('B站'))
  assert.equal(nouns.filter((w) => w === '行测').length, 1, '重复的只留一个')
  assert.ok(!nouns.includes('什么'), '停用词不进池')
  assert.ok(nouns.length <= 60)
})

test('gateRound：第一遍就过 → 直接发', () => {
  assert.deepEqual(gateRound([{ pass: true }]), { action: 'send' })
  assert.deepEqual(gateRound([]), { action: 'send' })
})

test('gateRound：和最近重复 → 重写一次；还重复就这一轮不发', () => {
  assert.deepEqual(gateRound([{ pass: false, code: 'dup' }]), { action: 'rewrite', code: 'dup' })
  assert.deepEqual(gateRound([{ pass: false, code: 'dup' }, { pass: false, code: 'dup' }]), { action: 'skip', code: 'dup' })
})

test('gateRound：重写这一遍改对了就照发', () => {
  assert.deepEqual(gateRound([{ pass: false, code: 'dup' }, { pass: true }]), { action: 'send' })
})

test('gateRound：只是不沾具体名词 → 照发，只记软标记（判据不可靠，不许把她憋哑）', () => {
  assert.deepEqual(gateRound([{ pass: false, code: 'no-content' }]), { action: 'send', soft: 'no-content' })
  assert.deepEqual(gateRound([{ pass: false, code: 'no-content' }, { pass: false, code: 'no-content' }]), { action: 'send', soft: 'no-content' })
})

test('gateRound：空句不发（唯一一条不需要重写的）', () => {
  assert.deepEqual(gateRound([{ pass: false, code: 'empty' }]), { action: 'skip', code: 'empty' })
})

test('账本状态名是 skip-no-content：不是失败也不是没接', () => {
  assert.equal(SKIP_STATE, 'skip-no-content')
})
