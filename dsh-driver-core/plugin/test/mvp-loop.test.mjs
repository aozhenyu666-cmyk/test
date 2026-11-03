// 15 分钟 MVP 闭环：主动找你 → 你回答 → 结合隐秘信息追问依据 → 你切屏 → 拦截并接回断点 → 产出一条结论
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { Driver, DIRECTIVE, PHASE } from '../lib/core/driver.js'
import { createService } from '../lib/index.js'

const T0 = 1_700_000_000_000
const sec = (s) => T0 + s * 1000
const BARD = 'com.google.android.apps.bard'
const BILI = 'tv.danmaku.bili'

const hidden = {
  profile: { goals: ['考公行测'], habits: ['抓到一半就下结论'] },
  memories: [{ text: '鱼片做图形推理时经常只看一个规律就选', tags: ['习惯'], importance: 0.8 }],
  history: {},
}

/** 假模型：按提示词里的水位和意图回一张卡，并记下它看到了什么。 */
function fakeBrain() {
  const seen = []
  const brain = async (prompt) => {
    seen.push(prompt)
    if (prompt.includes('HIGH_SPEED_SKIMMING')) {
      return '{"kind":"one_word","text":"滑过去了——你又抓到一半就下结论。选D的依据是哪个词？","anchor":"第3题·为什么选D","missing":"对称"}'
    }
    if (prompt.includes('本次意图：resume')) {
      return '{"kind":"one_word","text":"回到第3题：除了对称，还有哪个规律？","anchor":"第3题·第二个规律","missing":"数量"}'
    }
    if (prompt.includes('本次意图：conclude')) return null // 让结论卡走模板
    return '{"kind":"one_word","text":"对称之外，黑点数量怎么变？","anchor":"第3题·黑点数量","missing":"递增"}'
  }
  return { brain, seen }
}

test('MVP 闭环：从主动开场到产出结论，五个推进状态都留痕', async () => {
  const rows = []
  const { brain, seen } = fakeBrain()
  const d = new Driver({ brain, hidden, append: (r) => rows.push(r) })

  // ① 主动找你：开场就出第一问
  let out = await d.start({ at: sec(0), topic: '行测', task: '第3题图形推理', minutes: 15, anchors: [BARD] })
  assert.equal(out[0].type, DIRECTIVE.SHOW)
  const c1 = out[0].card
  await d.surface({ at: sec(1), cardId: c1.id, status: 'displayed' })

  // ② 你秒回"选D"、没依据 → 判为假专注 → 踩刹车，提示词里带上你的老毛病
  out = await d.reply({ at: sec(2.5), cardId: c1.id, text: '选D' })
  assert.equal(d.state().cog.state, 'HIGH_SPEED_SKIMMING')
  assert.ok(seen.at(-1).includes('抓到一半就下结论'), '隐秘层进了提示词')
  const c2 = out[0].card
  assert.equal(c2.source, 'llm')
  assert.ok(c2.text.includes('依据'))
  await d.surface({ at: sec(3), cardId: c2.id, status: 'displayed' })

  // ③ 你给出依据 → ADVANCED，继续追问
  out = await d.reply({ at: sec(12), cardId: c2.id, text: '因为图形是左右对称的' })
  assert.equal(d.state().cog.state, 'ACTIVE_FLOW')
  const c3 = out[0].card
  await d.surface({ at: sec(13), cardId: c3.id, status: 'displayed' })

  // ④ 你切去 B 站 → 不结案，冻结断点，温和拉回
  out = await d.foreground({ at: sec(40), pkg: BILI })
  assert.equal(d.state().phase, PHASE.INTERRUPTED_BY_SWITCH)
  assert.equal(out[0].type, DIRECTIVE.PULLBACK)
  assert.equal(out[0].level, 1)
  assert.equal(out[0].relaunch, BARD)
  assert.equal(d.state().breakpoint.id, c3.id, '断点就是没答完的那一问')
  assert.ok(out[0].card.text.includes('第3题·黑点数量'))
  assert.equal(out[0].card.kind, 'ab', '拉回卡永远是 A/B')
  const pull = out[0].card
  await d.surface({ at: sec(41), cardId: pull.id, status: 'displayed', via: 'overlay' })

  // 回到 Gemini 不等于接回：还要一条有效微反馈
  await d.foreground({ at: sec(45), pkg: BARD })
  assert.equal(d.state().phase, PHASE.INTERRUPTED_BY_SWITCH)

  // ⑤ 点了 A → RESUMED，回到断点本身继续问
  out = await d.reply({ at: sec(47), cardId: pull.id, choice: 'A' })
  assert.equal(d.state().phase, PHASE.LOCKED)
  assert.equal(d.state().level, 0)
  const c4 = out[0].card
  assert.ok(c4.text.includes('除了对称'))
  await d.surface({ at: sec(48), cardId: c4.id, status: 'displayed' })
  await d.reply({ at: sec(60), cardId: c4.id, text: '黑点数量每格加一' })

  // ⑥ 时间到 → 要一句结论 → 结案
  out = await d.tick({ at: sec(15 * 60) })
  assert.equal(d.state().phase, PHASE.WRAPPING)
  const cc = out[0].card
  assert.equal(cc.kind, 'conclusion')
  await d.surface({ at: sec(15 * 60 + 1), cardId: cc.id, status: 'displayed' })
  out = await d.reply({ at: sec(15 * 60 + 20), cardId: cc.id, text: '第3题选D，因为左右对称且黑点数量递增' })
  assert.equal(out[0].type, DIRECTIVE.END)
  assert.equal(out[0].reason, 'completed')
  assert.ok(out[0].conclusion.includes('选D'))

  const s = d.state().summary
  assert.equal(s.resumed, 1)
  assert.equal(s.interruptions, 1)
  assert.ok(s.advanced >= 4, `advanced=${s.advanced}`)
  assert.equal(s.displayedUnproven, 0)
  const steps = new Set(rows.map((r) => r.step))
  for (const k of ['GENERATED', 'DISPLAYED', 'REPLIED', 'ADVANCED', 'RESUMED']) assert.ok(steps.has(k), k)
})

test('防逃逸：连续不应答升级到 level 2；敷衍不解除；声明休息才放行，到点拉回原断点', async () => {
  const d = new Driver({ hidden })
  let out = await d.start({ at: sec(0), task: '申论第1题', anchors: [BARD] })
  const c1 = out[0].card
  await d.surface({ at: sec(1), cardId: c1.id, status: 'displayed' })

  out = await d.foreground({ at: sec(10), pkg: BILI })
  assert.equal(out[0].level, 1)
  const pull = out[0].card

  assert.deepEqual(await d.tick({ at: sec(30) }), [], '还没到应答期限')
  out = await d.tick({ at: sec(41) })
  assert.equal(out[0].type, DIRECTIVE.ESCALATE, '第 2 次不应答 → 高权限强提醒')
  assert.equal(out[0].level, 2)
  out = await d.tick({ at: sec(62) })
  assert.equal(out[0].type, DIRECTIVE.ESCALATE, 'level 2 每 20 秒重复')

  out = await d.reply({ at: sec(65), cardId: pull.id, text: '嗯' })
  assert.equal(d.state().phase, PHASE.INTERRUPTED_BY_SWITCH, '敷衍不算接回')
  assert.equal(out[0].type, DIRECTIVE.PULLBACK)
  assert.equal(out[0].level, 2)

  out = await d.pause({ at: sec(70), minutes: 5, reason: '喝水' })
  assert.equal(out[0].type, DIRECTIVE.RELEASE)
  assert.equal(d.state().phase, PHASE.PAUSED)
  assert.deepEqual(await d.tick({ at: sec(200) }), [])
  out = await d.tick({ at: sec(70 + 300) })
  assert.equal(out[0].type, DIRECTIVE.PULLBACK, '休息到点自动拉回')
  assert.equal(out[0].level, 1)
  assert.equal(d.state().breakpoint.id, c1.id, '还是原来那个断点')

  out = await d.reply({ at: sec(380), cardId: out[0].card.id, choice: 'B' })
  assert.equal(d.state().phase, PHASE.LOCKED)
  assert.equal(d.state().summary.resumed, 1)
})

test('防沉默：先给一句念给 Live 的话，Live 开口即推断完成；再沉默就降维成 A/B', async () => {
  const d = new Driver({ hidden })
  let out = await d.start({ at: sec(0), task: '第5题', anchors: [BARD] })
  await d.live({ at: sec(0), active: true })
  // 一直在和 Gemini 聊：开场卡悄悄过期，不算不应答
  for (const t of [10, 25, 40, 55]) await d.audio({ at: sec(t), assistantSpeaking: true })
  assert.deepEqual(await d.tick({ at: sec(61) }), [])
  assert.deepEqual(await d.tick({ at: sec(100) }), [])

  out = await d.tick({ at: sec(55 + 60) })
  assert.equal(out[0].card.kind, 'say_to_live', '沉默 60 秒：给一句最低成本的开口')
  assert.equal(out[0].level, 1)
  const say = out[0].card
  await d.surface({ at: sec(116), cardId: say.id, status: 'displayed', via: 'overlay' })
  await d.audio({ at: sec(125), assistantSpeaking: true })
  assert.equal(d.state().summary.advanced, 1, 'Gemini 接话 → 推断你已经开口')

  out = await d.tick({ at: sec(125 + 60) })
  assert.ok(['ab', 'read_two_words'].includes(out[0].card.kind), '再沉默：降维成原子动作')
  assert.match(out[0].card.intent, /^dice:/)
})

test('Live 被关掉也算逃逸；模型超时退回模板；紧急结束永远放行', async () => {
  const slow = () => new Promise(() => {})
  const d = new Driver({ hidden, brain: slow, cfg: { brainTimeoutMs: 20 } })
  let out = await d.start({ at: sec(0), task: '第7题', anchors: [BARD] })
  assert.equal(out[0].card.source, 'template')
  out = await d.live({ at: sec(5), active: false })
  assert.equal(out[0].type, DIRECTIVE.PULLBACK)
  assert.equal(out[0].reason, 'live_ended')
  out = await d.stop({ at: sec(6), reason: 'call' })
  assert.equal(out[0].type, DIRECTIVE.END)
  assert.equal(out[0].reason, 'stopped:call')
  assert.equal(d.state().phase, PHASE.ENDED)
})

function memStore() {
  const files = { config: {}, profile: hidden.profile, history: {} }
  return {
    files,
    loadConfig: () => files.config, saveConfig: (c) => { files.config = c },
    loadProfile: () => files.profile, saveProfile: (p) => { files.profile = p },
    loadHistory: () => files.history, saveHistory: (h) => { files.history = h },
    appender: () => () => {},
    loadFishMemories: () => hidden.memories,
  }
}

test('服务层：同一时刻只有一条主线；信号路由；长轮询拿到新指令', async () => {
  let clock = sec(0)
  const store = memStore()
  const svc = createService({ store, now: () => clock, newSessionId: () => 'S1' })
  const r = await svc.start({ topic: '行测', task: '第3题' })
  assert.equal(r.status, 201)
  assert.equal(r.body.directives.length, 1)
  assert.equal((await svc.start({})).status, 409)

  const waiting = svc.directives('S1', 1, 5_000)
  clock = sec(10)
  const s = await svc.signal({ sessionId: 'S1', kind: 'foreground', pkg: BILI })
  assert.equal(s.body.directives[0].type, DIRECTIVE.PULLBACK)
  const polled = await waiting
  assert.equal(polled.body.directives[0].type, DIRECTIVE.PULLBACK)

  assert.equal((await svc.signal({ sessionId: 'S1', kind: 'nope' })).status, 400)
  assert.equal((await svc.signal({ sessionId: 'X', kind: 'reply' })).status, 404)
  await svc.signal({ sessionId: 'S1', kind: 'stop', reason: 'test' })
  assert.equal((await svc.start({})).status, 201, '结束后可以开新主线')
  assert.equal(store.files.history.sessions, 1)
})
