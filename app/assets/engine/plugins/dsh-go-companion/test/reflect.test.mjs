// 2026-11-18 思考层。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { noteExcerpts, parseReflect, reflectDue, reflectLines, reflectMarkdown, reflectSlot, reflectUser, runReflect } from '../lib/reflect.js'

test('槽位：13:30 和 22:15 各一个，每天每槽一次', () => {
  assert.equal(reflectSlot(13 * 60 + 40), 0)
  assert.equal(reflectSlot(22 * 60 + 20), 1)
  assert.equal(reflectSlot(10 * 60), -1)
  const dayOf = () => 'd'
  assert.equal(reflectDue([], { day: 'd', slot: 0, dayOf }), true)
  assert.equal(reflectDue([{ state: 'reflect', slot: 0, stage: 'done', at: 1 }], { day: 'd', slot: 0, dayOf }), false)
  assert.equal(reflectDue([{ state: 'reflect', slot: 0, stage: 'done', at: 1 }], { day: 'd', slot: 1, dayOf }), true)
})

test('产物校验：没依据的丢；改严 App 要有合法包名和层；间隔夹在 5–30 分钟', () => {
  const raw = '好的```json\n' + JSON.stringify({
    urgent: [{ what: '国考报名还剩 3 天', why: '没开过局', evidence: '截止日：国考报名 还剩 3 天' }, { what: '空话', why: '' }],
    patterns: [{ what: '下午 3 点必滑', evidence: '近 7 天 5 天在 15 点进入接管' }],
    proposals: [
      { kind: 'tighten_app', app: 'com.vivo.browser', tier: 'fun', text: '浏览器改成娱乐', evidence: '通行证偏航 3 次' },
      { kind: 'tighten_app', app: '浏览器!!', tier: 'fun', text: '坏包名', evidence: 'x' },
    ],
    self: { companionGapMin: 2, note: '他最近接球多，勤一点' },
    diary: { companion: '芝麻今天把木屑刨了一地', strategist: '晨跑 6 公里，配速掉了' },
  }) + '\n```'
  const r = parseReflect(raw)
  assert.equal(r.ok, true)
  assert.equal(r.value.urgent.length, 1)
  assert.equal(r.dropped, 1)
  assert.equal(r.value.proposals[0].kind, 'tighten_app')
  assert.equal(r.value.proposals[1].kind, 'note', '包名不合法：降成普通建议，不执行')
  assert.equal(r.value.self.companionGapMin, 5)
  assert.equal(parseReflect('不是 json').ok, false)
  assert.equal(parseReflect('{}').ok, false)
})

test('进上下文：要紧、规律、想提的、她自己的日常（按角色）；超过 36 小时不带', () => {
  const store = { at: Date.parse('2026-11-18T05:30:00Z'), value: parseReflect(JSON.stringify({
    urgent: [{ what: 'A', evidence: 'e' }], patterns: [], proposals: [{ kind: 'plan', text: '把投简历挪到上午', evidence: 'e' }],
    diary: { companion: '芝麻', strategist: '晨跑' },
  })).value }
  const comp = reflectLines(store, 'companion', store.at + 3_600_000)
  assert.match(comp, /要紧：A/)
  assert.match(comp, /想跟他提：把投简历挪到上午/)
  assert.match(comp, /芝麻/)
  assert.match(reflectLines(store, 'strategist', store.at + 3_600_000), /晨跑/)
  assert.equal(reflectLines(store, 'companion', store.at + 40 * 3_600_000), '')
  assert.match(reflectMarkdown(store.value, { label: 'x' }), /她们自己的日常/)
})

test('笔记节选有总字数上限；材料拼进 prompt', () => {
  const ex = noteExcerpts([{ rel: 'a.md', path: 'a' }, { rel: 'b.md', path: 'b' }, { rel: 'c.md', path: 'c' }], () => 'x'.repeat(2000), 1000)
  assert.ok(ex.join('').length <= 1100)
  assert.match(reflectUser({ verdicts: ['11-17 今天：空转'], notes: ex }), /近 7 天判决[\s\S]*笔记节选/)
})

test('跑一次：走 llm.stream，模型报错就丢弃不重试', async () => {
  const ok = await runReflect({ provider: 'p', model: 'm', system: 's', user: 'u',
    stream: async function* () { yield { type: 'text-delta', text: JSON.stringify({ urgent: [{ what: 'x', evidence: 'y' }] }) } } })
  assert.equal(ok.ok, true)
  const bad = await runReflect({ provider: 'p', model: 'm', system: 's', user: 'u',
    stream: async function* () { yield { type: 'finish', reason: { kind: 'error' } } } })
  assert.equal(bad.ok, false)
})
