// 第二轮：说完轮次触发、唤醒冷却与随机窗口、防重复提问、回应三级、跨会话议程。
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { Agenda } from '../lib/core/agenda.js'
import { Driver, DIRECTIVE, PHASE } from '../lib/core/driver.js'
import { jaccard, normalizeNumbers } from '../lib/core/similar.js'
import { createService } from '../lib/index.js'

const sec = (s) => s * 1000
const BARD = 'com.google.android.apps.bard'
const hidden = { profile: { habits: ['抓到一半就下结论'] }, memories: [], history: {} }

/** 一段 Gemini 说话：从 from 秒说到 to 秒。 */
async function geminiSays(d, from, to) {
  await d.audio({ at: sec(from), assistantSpeaking: true })
  await d.audio({ at: sec(to), assistantSpeaking: false })
}

/** 开一个会话并把开场卡答掉，让后面没有待答的卡。 */
async function started(opts = {}) {
  const rows = []
  const d = new Driver({ hidden, rng: () => 0, append: (r) => rows.push(r), ...opts })
  const out = await d.start({ at: 0, topic: '行测', task: '第3题', anchors: [BARD] })
  await d.surface({ at: sec(0.5), cardId: out[0].card.id, status: 'displayed' })
  const r = await d.reply({ at: sec(1), cardId: out[0].card.id, text: '因为题干说只有一个条件成立' })
  await d.surface({ at: sec(1.2), cardId: r[0].card.id, status: 'displayed' })
  await d.reply({ at: sec(2), cardId: r[0].card.id, text: '因为第二段给了前提' })
  return { d, rows }
}

test('说完轮次：Gemini 说完 8 秒你没接 → 推一句；窗口里 Gemini 又开口就作废；说得太短不算', async () => {
  const { d, rows } = await started()
  await geminiSays(d, 10, 14)
  assert.deepEqual(await d.tick({ at: sec(21) }), [], '窗口还没到')
  const out = await d.tick({ at: sec(22) })
  assert.equal(out[0].reason, 'turn_gap')
  assert.equal(out[0].card.kind, 'say_to_live')
  assert.equal(out[0].card.intent, 'turn_gap')
  assert.equal(out[0].level, 0, '轻提醒，不升级')
  assert.ok(rows.some((r) => r.kind === 'wake' && r.reason === 'turn_gap'))

  // 窗口里 Gemini 又开口：作废
  const b = await started()
  await geminiSays(b.d, 10, 14)
  await b.d.audio({ at: sec(18), assistantSpeaking: true })
  assert.deepEqual(await b.d.tick({ at: sec(22) }), [])

  // 只说了 1 秒：不开窗口
  const c = await started()
  await geminiSays(c.d, 10, 11)
  assert.deepEqual(await c.d.tick({ at: sec(30) }), [])
})

test('唤醒冷却：同一原因 90 秒内只推一次，拒绝的也记账', async () => {
  const { d, rows } = await started()
  await geminiSays(d, 10, 14)
  const first = await d.tick({ at: sec(22) })
  await d.surface({ at: sec(22.5), cardId: first[0].card.id, status: 'displayed' })
  await geminiSays(d, 25, 40) // Gemini 接话 → 那张卡推断已完成；又说完一段
  assert.equal(d.state().summary.responded >= 1, true)
  assert.deepEqual(await d.tick({ at: sec(48) }), [], '冷却期内不再推（距上次 26 秒，过了最小间隔、没过冷却）')
  assert.ok(rows.some((r) => r.kind === 'wake_skipped' && r.why === 'cooldown'))
  await geminiSays(d, 110, 115)
  const again = await d.tick({ at: sec(123) })
  assert.equal(again[0].reason, 'turn_gap', '冷却过了可以再推')
})

test('随机插问：间隔落在 2–4 分钟里，由注入的随机数决定', async () => {
  for (const [r, due] of [[0, 120], [0.5, 180], [1, 240]]) {
    const d = new Driver({ hidden, rng: () => r })
    const out = await d.start({ at: 0, task: '第3题', anchors: [BARD] })
    await d.reply({ at: 0, cardId: out[0].card.id, text: '因为第一段说了' })
    const pending = d.state().card
    await d.reply({ at: 0, cardId: pending.id, text: '因为第二段说了' })
    // 一直有很短的对话迹象（不触发说完窗口，也不算沉默）
    let pushed = null
    for (let t = 1; t <= 250 && !pushed; t++) {
      if (t % 30 === 0) await geminiSays(d, t - 1, t - 0.5)
      const o = await d.tick({ at: sec(t) })
      if (o.length) pushed = { t, o: o[0] }
    }
    assert.equal(pushed.o.reason, 'periodic', `rng=${r}`)
    assert.equal(pushed.t, due, `rng=${r}`)
  }
})

test('防重复：连着给出依据，后面的追问和最近问过的话字面相似度都 < 0.6', async () => {
  const d = new Driver({ hidden, rng: () => 0 })
  let out = await d.start({ at: 0, task: '第3题', anchors: [BARD] })
  const shown = []
  for (let i = 0; i < 9; i++) {
    const c = out[0].card
    await d.surface({ at: sec(i * 10 + 1), cardId: c.id, status: 'displayed' })
    shown.push(c.text)
    out = await d.reply({ at: sec(i * 10 + 8), cardId: c.id, text: `因为第${i + 1}段说了前提` })
  }
  for (let i = 1; i < shown.length; i++) {
    for (let j = Math.max(0, i - 5); j < i; j++) {
      assert.ok(jaccard(shown[i], shown[j]) < 0.6, `「${shown[i]}」≈「${shown[j]}」`)
    }
  }
})

test('防重复：模型一直回同一句 → 第二次起换成模板，并记 repeat_replaced', async () => {
  const rows = []
  const same = async () => '{"kind":"one_word","text":"黑点数量怎么变？","anchor":"第3题"}'
  const d = new Driver({ hidden, brain: same, append: (r) => rows.push(r) })
  let out = await d.start({ at: 0, task: '第3题', anchors: [BARD] })
  await d.surface({ at: 500, cardId: out[0].card.id, status: 'displayed' })
  out = await d.reply({ at: sec(8), cardId: out[0].card.id, text: '因为题干说了' })
  assert.notEqual(out[0].card.text, '黑点数量怎么变？')
  assert.equal(out[0].card.source, 'template')
  assert.ok(rows.some((r) => r.kind === 'repeat_replaced'))
})

test('回应三级：点 A 只算"回"不算"想"；说出依据才算"想"；卡亮出 15 秒内 Gemini 开口算"醒"', async () => {
  const d = new Driver({ hidden })
  let out = await d.start({ at: 0, task: '第3题', anchors: [BARD] })
  await d.foreground({ at: sec(1), pkg: 'tv.danmaku.bili' })
  const pull = d.state().card
  await d.surface({ at: sec(2), cardId: pull.id, status: 'displayed' })
  out = await d.reply({ at: sec(3), cardId: pull.id, choice: 'A' })
  let s = d.state().summary
  assert.equal(s.responded, 1)
  assert.equal(s.grounded, 0, '"A" 不算思考')
  await d.surface({ at: sec(4), cardId: out[0].card.id, status: 'displayed' })
  await d.reply({ at: sec(10), cardId: out[0].card.id, text: '因为题干第二句给了条件' })
  s = d.state().summary
  assert.equal(s.grounded, 1)
  assert.ok(s.awake >= 2)

  // 只靠 Gemini 开口唤醒：卡亮出后 10 秒 Gemini 开口 → 醒
  const e = new Driver({ hidden })
  const o = await e.start({ at: 0, task: '第3题', anchors: [BARD] })
  await e.surface({ at: sec(1), cardId: o[0].card.id, status: 'displayed' })
  await e.audio({ at: sec(11), assistantSpeaking: true })
  assert.equal(e.state().summary.awake, 1)
  assert.equal(e.state().summary.responded, 0)
})

test('议程：第一次被打断后紧急结束 → 记下断点；第二次开场先问要不要接着做，答出依据就结掉', async () => {
  const saved = []
  const agenda = new Agenda([], { newId: () => 'A1' })
  const brain = async (p) => p.includes('本次意图：open')
    ? '{"kind":"open","text":"黑点数量怎么变？","anchor":"第3题·黑点数量","missing":"递增"}'
    : null
  const d1 = new Driver({ hidden, brain, agenda, saveAgenda: (x) => saved.push(structuredClone(x)) })
  const o1 = await d1.start({ at: 0, topic: '行测图形', task: '第3题', anchors: [BARD] })
  await d1.surface({ at: sec(1), cardId: o1[0].card.id, status: 'displayed' })
  await d1.foreground({ at: sec(5), pkg: 'tv.danmaku.bili' })
  await d1.stop({ at: sec(6), reason: 'call' })
  assert.equal(saved.length, 1)
  assert.equal(agenda.open().length, 1)
  assert.equal(agenda.open()[0].source, 'unresumed')
  assert.equal(agenda.open()[0].anchor, '第3题·黑点数量')

  const d2 = new Driver({ hidden, agenda })
  let out = await d2.start({ at: sec(100), topic: '行测图形', task: '第三题', anchors: [BARD] })
  const offer = out[0].card
  assert.equal(offer.intent, 'agenda')
  assert.ok(offer.text.includes('第3题·黑点数量'))
  assert.deepEqual(offer.options, { A: '从这接着做', B: '先做新的' })
  out = await d2.reply({ at: sec(103), cardId: offer.id, choice: 'A' })
  assert.ok(out[0].card.text.includes('递增'), '回到断点并追问缺的那个依据')
  assert.equal(d2.state().agendaFocus, 'A1')
  await d2.reply({ at: sec(110), cardId: out[0].card.id, text: '黑点每格递增一个' })
  assert.equal(agenda.items[0].status, 'done')
  assert.equal(d2.state().agendaFocus, null)
})

test('议程：连续 3 次选"先做新的"就不再纠缠；没把握的结论要有原话才入账', async () => {
  const agenda = new Agenda([{ id: 'X', text: '第5题：缺依据', anchor: '第5题', source: 'missing', status: 'open', lastSeenAt: 0, skips: 0 }])
  for (let i = 0; i < 3; i++) {
    const d = new Driver({ hidden, agenda })
    const out = await d.start({ at: sec(i * 1000), task: '第5题', anchors: [BARD] })
    assert.equal(out[0].card.intent, 'agenda')
    const next = await d.reply({ at: sec(i * 1000 + 2), cardId: out[0].card.id, choice: 'B' })
    assert.equal(next[0].card.intent, 'open')
  }
  assert.equal(agenda.items[0].status, 'dropped')
  const d = new Driver({ hidden, agenda })
  const out = await d.start({ at: sec(9000), task: '第5题', anchors: [BARD] })
  assert.notEqual(out[0].card.intent, 'agenda')

  const a = new Agenda()
  assert.equal(a.add({ source: 'conclusion_doubt', text: '结论待确认', quote: '好像选C', at: 1 }, { utterances: ['我觉得选B'] }), null, '原话对不上不收')
  assert.ok(a.add({ source: 'conclusion_doubt', text: '结论待确认', quote: '好像选C', at: 1 }, { utterances: ['嗯 好像选C 吧'] }))
})

test('结论里带"可能/好像"：结束时作为待确认结论入账', async () => {
  const agenda = new Agenda()
  const d = new Driver({ hidden, agenda })
  await d.start({ at: 0, task: '第8题', minutes: 1, anchors: [BARD] })
  const out = await d.tick({ at: sec(60) })
  assert.equal(d.state().phase, PHASE.WRAPPING)
  const end = await d.reply({ at: sec(70), cardId: out[0].card.id, text: '第8题可能选C，因为第二段说了前提' })
  assert.equal(end[0].type, DIRECTIVE.END)
  assert.equal(end[0].reason, 'completed')
  assert.equal(end[0].summary.grounded, 1, '带依据的结论算"想"')
  assert.ok(agenda.open().some((x) => x.source === 'conclusion_doubt' && x.quote.includes('可能选C')))
})

test('数字归一：第三题 = 第3题', () => {
  assert.equal(normalizeNumbers('第三题、第十二题'), '第3题、第12题')
  assert.equal(jaccard('停在第三题', '停在第3题'), 1)
})

test('服务层：audio 的 false 原样传进驾驶员；不是布尔就拒收；议程可查', async () => {
  const files = { config: {}, profile: {}, history: {}, agenda: [] }
  const store = {
    loadConfig: () => files.config, saveConfig: (c) => { files.config = c },
    loadProfile: () => files.profile, saveProfile: () => {}, loadHistory: () => files.history, saveHistory: (h) => { files.history = h },
    loadAgenda: () => files.agenda, saveAgenda: (a) => { files.agenda = a },
    appender: () => () => {}, loadFishMemories: () => [],
  }
  let clock = 0
  const svc = createService({ store, now: () => clock, newSessionId: () => 'S' })
  const st = await svc.start({ task: '第3题' })
  assert.equal((await svc.signal({ sessionId: 'S', kind: 'audio', assistantSpeaking: 'yes' })).status, 400)
  const r1 = await svc.signal({ sessionId: 'S', kind: 'reply', cardId: st.body.directives[0].card.id, text: '因为第一段说了' })
  await svc.signal({ sessionId: 'S', kind: 'reply', cardId: r1.body.directives[0].card.id, text: '因为第二段说了' })
  clock = sec(10); await svc.signal({ sessionId: 'S', kind: 'audio', assistantSpeaking: true })
  clock = sec(14); await svc.signal({ sessionId: 'S', kind: 'audio', assistantSpeaking: false })
  clock = sec(40); await svc.tick()
  const polled = await svc.directives('S', 3, 0)
  assert.equal(polled.body.directives.at(-1).reason, 'turn_gap', 'false 进了驾驶员，开了说完窗口')
  await svc.signal({ sessionId: 'S', kind: 'stop', reason: 't' })
  assert.ok(Array.isArray(svc.agenda()))
})
