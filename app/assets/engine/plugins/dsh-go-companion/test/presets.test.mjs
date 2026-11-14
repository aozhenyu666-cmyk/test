import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  BUILTINS, EVENTS, ACTIONS, LOOSEN_COOLDOWN_MS, SHADOW_MIN_MS, BOUT_END_CAP_MS,
  validatePreset, rawOf, emptyDoc, normalizeDoc, allPresets, presetOf, isActive,
  classifyChange, proposeChange, cancelPending, settlePending, historyOf,
  setBoutApps, boutAppsOf, isOff, observe, match, plan, explain, describeWhen,
  enterStrict, strictView, clearStrict, parseDuration, parseClock,
} from '../lib/presets.js'

const T0 = Date.UTC(2026, 10, 14, 2, 0, 0)
const MIN = 60_000

const custom = (over = {}) => ({
  id: 'study-drift',
  名称: '学习局偏离',
  状态: '生效',
  何时: { 事件: '切出', 窗口: '10m', 次数: 2, 仅局内: true },
  动作: [{ 严格模式: { 放行: '本局应用', 直到: '本局结束' } }],
  解除: ['本局结束'],
  ...over,
})

// ---------------------------------------------------------------- 解析

test('parseDuration：分钟默认、秒、小时、中文单位', () => {
  assert.equal(parseDuration('10m'), 10 * MIN)
  assert.equal(parseDuration('90s'), 90_000)
  assert.equal(parseDuration('2h'), 2 * 3_600_000)
  assert.equal(parseDuration('5'), 5 * MIN)
  assert.equal(parseDuration(3), 3 * MIN)
  assert.equal(parseDuration('3分钟'), 3 * MIN)
  assert.equal(parseDuration('abc'), null)
  assert.equal(parseDuration('0m'), null)
})

test('parseClock', () => {
  assert.equal(parseClock('09:30'), 570)
  assert.equal(parseClock('24:00'), null)
  assert.equal(parseClock('9点'), null)
})

// ---------------------------------------------------------------- 校验

test('内置预案全部能过校验', () => {
  for (const b of BUILTINS) assert.equal(validatePreset(b, { builtin: true }).ok, true, b.id)
})

test('全天默认锁默认停用；切出、偏离、两条阶梯默认生效', () => {
  const d = emptyDoc()
  assert.equal(isActive(d, 'default-lock'), false)
  for (const id of ['switch-storm', 'off-dwell', 'ladder-perfunctory', 'ladder-immersion', 'night-lock']) assert.equal(isActive(d, id), true, id)
})

test('新建预案合法', () => {
  const v = validatePreset(custom())
  assert.equal(v.ok, true)
  assert.equal(v.preset.when.count, 2)
  assert.equal(v.preset.when.windowMs, 10 * MIN)
  assert.equal(v.preset.when.inBout, true)
})

test('破窗永远在解除出口里', () => {
  assert.ok(validatePreset(custom()).preset.exits.includes('破窗'))
})

test('不认识的事件、动作拒绝', () => {
  assert.equal(validatePreset(custom({ 何时: { 事件: '读聊天' } })).errors[0].code, 'unknown-event')
  assert.equal(validatePreset(custom({ 动作: [{ 删库: {} }] })).errors[0].code, 'unknown-action')
})

test('只给内置的事件和动作，新建预案不能用', () => {
  assert.equal(validatePreset(custom({ 何时: { 事件: '常驻' } })).errors[0].code, 'builtin-event')
  assert.equal(validatePreset(custom({ 动作: [{ 阶梯: { 轨: '敷衍' } }] })).errors[0].code, 'builtin-action')
  assert.equal(validatePreset(custom({ 动作: [{ 墙默认锁: {} }] })).errors[0].code, 'builtin-action')
})

test('事件不认的参数拒绝', () => {
  assert.equal(validatePreset(custom({ 何时: { 事件: '局开始', 次数: 3 } })).errors[0].code, 'unknown-param')
})

test('阈值越界拒绝', () => {
  assert.equal(validatePreset(custom({ 何时: { 事件: '切出', 窗口: '200m', 次数: 2 } })).errors[0].code, 'bad-window')
  assert.equal(validatePreset(custom({ 何时: { 事件: '切出', 次数: 0 } })).errors[0].code, 'bad-count')
  assert.equal(validatePreset(custom({ 何时: { 事件: '偏离停留' } })).errors[0].code, 'bad-minutes')
  assert.equal(validatePreset(custom({ 何时: { 事件: '到点', 时刻: '25:00' } })).errors[0].code, 'bad-clock')
})

test('条件里读白名单外的变量拒绝', () => {
  const v = validatePreset(custom({ 条件: { '==': [{ var: 'chat.last' }, 'x'] } }))
  assert.equal(v.errors[0].code, 'var-not-allowed')
})

test('动作参数校验：放行、直到、说、锁、工具', () => {
  assert.equal(validatePreset(custom({ 动作: [{ 严格模式: { 放行: 'everything' } }] })).errors[0].code, 'bad-allow')
  assert.equal(validatePreset(custom({ 动作: [{ 严格模式: { 直到: '10h' } }] })).errors[0].code, 'bad-until')
  assert.equal(validatePreset(custom({ 动作: [{ 说: { 话: '' } }] })).errors[0].code, 'bad-say')
  assert.equal(validatePreset(custom({ 动作: [{ 说: { 话: '字'.repeat(41) } }] })).errors[0].code, 'bad-say')
  assert.equal(validatePreset(custom({ 动作: [{ 锁: { 应用: 'rm -rf' } }] })).errors[0].code, 'bad-app')
  assert.equal(validatePreset(custom({ 动作: [{ 开工具: { 工具: 'chatgpt' } }] })).errors[0].code, 'unknown-tool')
})

test('没有动作、没有解除、解除出口不合法都拒绝', () => {
  assert.equal(validatePreset(custom({ 动作: [] })).errors[0].code, 'no-action')
  assert.equal(validatePreset(custom({ 解除: [] })).errors[0].code, 'no-exit')
  assert.equal(validatePreset(custom({ 解除: ['求她'] })).errors[0].code, 'bad-exit')
})

test('id 和名称格式', () => {
  assert.equal(validatePreset(custom({ id: 'A B' })).errors[0].code, 'bad-id')
  assert.equal(validatePreset(custom({ 名称: '' })).errors[0].code, 'bad-name')
})

test('rawOf 往返不变', () => {
  const p = validatePreset(custom({ 条件: { '==': [{ var: 'bout.kind' }, '题局'] } })).preset
  const again = validatePreset(rawOf(p)).preset
  assert.deepEqual({ ...again }, { ...p })
})

test('每个非内置事件、动作都能被一条新建预案用上', () => {
  for (const [ev, spec] of Object.entries(EVENTS)) {
    if (spec.builtin) continue
    const when = { 事件: ev }
    if (spec.params.includes('分钟')) when['分钟'] = 1
    if (spec.params.includes('时刻')) when['时刻'] = '09:00'
    assert.equal(validatePreset(custom({ 何时: when })).ok, true, ev)
  }
  for (const [act, spec] of Object.entries(ACTIONS)) {
    if (spec.builtin) continue
    const params = act === '说' ? { 话: '回去' } : {}
    assert.equal(validatePreset(custom({ 动作: [{ [act]: params }] })).ok, true, act)
  }
})

// ---------------------------------------------------------------- 文档

test('坏文档不拖垮：normalizeDoc 补齐', () => {
  const d = normalizeDoc({ custom: 'x', pending: [1, { id: 'a' }], strict: 'bad' })
  assert.deepEqual(d.custom, {})
  assert.equal(d.pending.length, 1)
  assert.equal(d.strict, null)
})

test('坏的新建条目被跳过，内置照常', () => {
  const d = normalizeDoc({ custom: { broken: { 名称: '' } } })
  const ids = allPresets(d).map((p) => p.id)
  assert.equal(ids.includes('broken'), false)
  assert.equal(ids.length, BUILTINS.length)
})

// ---------------------------------------------------------------- 收紧 / 放宽

test('classifyChange：状态升是收紧、降是放宽', () => {
  const a = validatePreset(custom({ 状态: '影子' })).preset
  const b = validatePreset(custom({ 状态: '生效' })).preset
  assert.equal(classifyChange(a, b), 'tighten')
  assert.equal(classifyChange(b, a), 'loosen')
})

test('classifyChange：次数少、窗口长是收紧', () => {
  const base = validatePreset(custom()).preset
  assert.equal(classifyChange(base, validatePreset(custom({ 何时: { 事件: '切出', 窗口: '10m', 次数: 1, 仅局内: true } })).preset), 'tighten')
  assert.equal(classifyChange(base, validatePreset(custom({ 何时: { 事件: '切出', 窗口: '20m', 次数: 2, 仅局内: true } })).preset), 'tighten')
  assert.equal(classifyChange(base, validatePreset(custom({ 何时: { 事件: '切出', 窗口: '10m', 次数: 5, 仅局内: true } })).preset), 'loosen')
})

test('classifyChange：仅局内从 true 变 false 是收紧（范围更大）', () => {
  const base = validatePreset(custom()).preset
  const wider = validatePreset(custom({ 何时: { 事件: '切出', 窗口: '10m', 次数: 2, 仅局内: false } })).preset
  assert.equal(classifyChange(base, wider), 'tighten')
  assert.equal(classifyChange(wider, base), 'loosen')
})

test('classifyChange：改条件、删动作、加出口一律算放宽', () => {
  const base = validatePreset(custom()).preset
  assert.equal(classifyChange(base, validatePreset(custom({ 条件: { '==': [1, 1] } })).preset), 'loosen')
  assert.equal(classifyChange(base, validatePreset(custom({ 动作: [{ 记账: {} }] })).preset), 'loosen')
  assert.equal(classifyChange(base, validatePreset(custom({ 解除: ['本局结束', '到点'] })).preset), 'loosen')
})

test('AI 新建预案：写了生效也只从影子开始', () => {
  const r = proposeChange(emptyDoc(), { raw: custom({ 状态: '生效' }), by: 'ai', now: T0 })
  assert.equal(r.ok, true)
  assert.equal(presetOf(r.doc, 'study-drift').status, '影子')
})

test('他自己新建可以直接生效', () => {
  const r = proposeChange(emptyDoc(), { raw: custom({ 状态: '生效' }), by: 'user', now: T0 })
  assert.equal(presetOf(r.doc, 'study-drift').status, '生效')
})

test('AI 把影子转生效：不满 24 小时拒绝，满了可以', () => {
  let d = proposeChange(emptyDoc(), { raw: custom({ 状态: '影子' }), by: 'ai', now: T0 }).doc
  const early = proposeChange(d, { raw: custom({ 状态: '生效' }), by: 'ai', now: T0 + 3_600_000 })
  assert.equal(early.ok, false)
  assert.equal(early.code, 'shadow-too-short')
  const late = proposeChange(d, { raw: custom({ 状态: '生效' }), by: 'ai', now: T0 + SHADOW_MIN_MS })
  assert.equal(late.ok, true)
  assert.equal(presetOf(late.doc, 'study-drift').status, '生效')
})

test('他自己随时能把影子转生效', () => {
  const d = proposeChange(emptyDoc(), { raw: custom({ 状态: '影子' }), by: 'ai', now: T0 }).doc
  const r = proposeChange(d, { raw: custom({ 状态: '生效' }), by: 'user', now: T0 + 60_000 })
  assert.equal(presetOf(r.doc, 'study-drift').status, '生效')
})

test('AI 不能放宽：停用、调大次数都拒绝', () => {
  const d = proposeChange(emptyDoc(), { raw: custom(), by: 'user', now: T0 }).doc
  assert.equal(proposeChange(d, { raw: custom({ 状态: '停用' }), by: 'ai', now: T0 }).code, 'needs-user')
  assert.equal(proposeChange(d, { raw: custom({ 何时: { 事件: '切出', 窗口: '10m', 次数: 9, 仅局内: true } }), by: 'ai', now: T0 }).code, 'needs-user')
})

test('AI 能收紧，立即生效', () => {
  const d = proposeChange(emptyDoc(), { raw: custom(), by: 'user', now: T0 }).doc
  const r = proposeChange(d, { raw: custom({ 何时: { 事件: '切出', 窗口: '10m', 次数: 1, 仅局内: true } }), by: 'ai', now: T0 })
  assert.equal(r.kind, 'tighten')
  assert.equal(presetOf(r.doc, 'study-drift').when.count, 1)
})

test('他放宽：进冷却期，2 小时后才落地', () => {
  const d = proposeChange(emptyDoc(), { raw: custom(), by: 'user', now: T0 }).doc
  const r = proposeChange(d, { raw: custom({ 状态: '停用' }), by: 'user', now: T0 })
  assert.equal(r.kind, 'loosen')
  assert.equal(r.effectiveAt, T0 + LOOSEN_COOLDOWN_MS)
  assert.equal(presetOf(r.doc, 'study-drift').status, '生效', '冷却期内照旧')
  assert.equal(settlePending(r.doc, T0 + LOOSEN_COOLDOWN_MS - 1).applied.length, 0)
  const s = settlePending(r.doc, T0 + LOOSEN_COOLDOWN_MS)
  assert.deepEqual(s.applied, ['study-drift'])
  assert.equal(presetOf(s.doc, 'study-drift').status, '停用')
})

test('冷却期内可以撤销放宽', () => {
  const d = proposeChange(emptyDoc(), { raw: custom(), by: 'user', now: T0 }).doc
  const r = proposeChange(d, { raw: custom({ 状态: '停用' }), by: 'user', now: T0 })
  const c = cancelPending(r.doc, r.pendingId)
  assert.equal(c.ok, true)
  assert.equal(settlePending(c.doc, T0 + LOOSEN_COOLDOWN_MS).applied.length, 0)
})

test('收紧会作废同一条上挂着的放宽申请', () => {
  const d = proposeChange(emptyDoc(), { raw: custom(), by: 'user', now: T0 }).doc
  const loosen = proposeChange(d, { raw: custom({ 状态: '停用' }), by: 'user', now: T0 })
  const tighten = proposeChange(loosen.doc, { raw: custom({ 何时: { 事件: '切出', 窗口: '10m', 次数: 1, 仅局内: true } }), by: 'ai', now: T0 + 1 })
  assert.equal(tighten.doc.pending.length, 0)
})

test('内置：深夜锁不能改', () => {
  const r = proposeChange(emptyDoc(), { raw: { id: 'night-lock', 状态: '停用' }, by: 'user', now: T0 })
  assert.equal(r.code, 'fixed')
})

test('内置：只能改状态和阈值，动作不能换', () => {
  const r = proposeChange(emptyDoc(), {
    raw: { id: 'switch-storm', 何时: { 次数: 2 }, 动作: [{ 记账: {} }] }, by: 'ai', now: T0,
  })
  assert.equal(r.ok, true)
  const p = presetOf(r.doc, 'switch-storm')
  assert.equal(p.when.count, 2)
  assert.equal(p.when.windowMs, 10 * MIN, '没提到的阈值沿用')
  assert.equal(p.actions[0].type, '严格模式')
})

test('内置：打开全天默认锁是收紧，AI 可以直接开', () => {
  const r = proposeChange(emptyDoc(), { raw: { id: 'default-lock', 状态: '生效' }, by: 'ai', now: T0 })
  assert.equal(r.kind, 'tighten')
  assert.equal(isActive(r.doc, 'default-lock'), true)
})

test('内置：关掉阶梯是放宽，AI 不行', () => {
  assert.equal(proposeChange(emptyDoc(), { raw: { id: 'ladder-perfunctory', 状态: '停用' }, by: 'ai', now: T0 }).code, 'needs-user')
})

test('改过的阈值连续改两次，第二次基于第一次', () => {
  let d = proposeChange(emptyDoc(), { raw: { id: 'switch-storm', 何时: { 次数: 2 } }, by: 'ai', now: T0 }).doc
  d = proposeChange(d, { raw: { id: 'switch-storm', 何时: { 窗口: '15m' } }, by: 'ai', now: T0 + 1 }).doc
  const p = presetOf(d, 'switch-storm')
  assert.equal(p.when.count, 2)
  assert.equal(p.when.windowMs, 15 * MIN)
})

test('版本号递增，历史可查', () => {
  let d = proposeChange(emptyDoc(), { raw: custom({ 状态: '影子' }), by: 'user', now: T0 }).doc
  assert.equal(presetOf(d, 'study-drift').version, 1)
  d = proposeChange(d, { raw: custom({ 状态: '生效' }), by: 'user', now: T0 + 1 }).doc
  assert.equal(presetOf(d, 'study-drift').version, 2)
  const h = historyOf(d, 'study-drift')
  assert.equal(h.length, 1)
  assert.equal(h[0].raw['状态'], '影子')
})

test('没有变化：same，不写版本', () => {
  const d = proposeChange(emptyDoc(), { raw: custom(), by: 'user', now: T0 }).doc
  const r = proposeChange(d, { raw: custom(), by: 'ai', now: T0 + 1 })
  assert.equal(r.kind, 'same')
  assert.equal(presetOf(r.doc, 'study-drift').version, 1)
})

test('不合法的改动返回逐条错误', () => {
  const r = proposeChange(emptyDoc(), { raw: custom({ 动作: [] }), by: 'ai', now: T0 })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'invalid')
  assert.match(r.message, /至少要有一个动作/)
})

// ---------------------------------------------------------------- 本局的 App

test('setBoutApps 过滤非法包名、限 20 个', () => {
  const r = setBoutApps(emptyDoc(), 'b1', { need: ['com.a', 'bad name', 'com.b'], allow: Array.from({ length: 30 }, (_, i) => 'com.x' + i) })
  assert.deepEqual(r.apps.need, ['com.a', 'com.b'])
  assert.equal(r.apps.allow.length, 20)
  assert.deepEqual(boutAppsOf(r.doc, 'b1').need, ['com.a', 'com.b'])
})

test('boutApps 只留最近 30 局', () => {
  let d = emptyDoc()
  for (let i = 0; i < 35; i += 1) d = setBoutApps(d, 'b' + i, { need: ['com.a'] }).doc
  assert.equal(Object.keys(d.boutApps).length, 30)
  assert.deepEqual(boutAppsOf(d, 'b0'), { need: [], allow: [] })
})

test('isOff：声明过 App 的局，名单外就算出局', () => {
  const ctx = { apps: { need: ['com.study'], allow: ['com.dict'] }, ignore: ['io.dsh'] }
  assert.equal(isOff('com.study', ctx), false)
  assert.equal(isOff('com.dict', ctx), false)
  assert.equal(isOff('com.douyin', ctx), true)
  assert.equal(isOff('io.dsh', ctx), false, 'DSH 自己不算')
  assert.equal(isOff('com.miui.home', ctx), false, '桌面不算')
  assert.equal(isOff(null, ctx), false)
})

test('isOff：没声明的局只看是不是娱乐类', () => {
  assert.equal(isOff('com.douyin', { apps: { need: [], allow: [] }, fun: true }), true)
  assert.equal(isOff('com.notes', { apps: { need: [], allow: [] }, fun: false }), false)
})

// ---------------------------------------------------------------- 观察

const ctxOf = (over = {}) => ({
  now: T0, front: 'com.study', fun: false, minutes: 0, bout: { id: 'b1' },
  apps: { need: ['com.study'], allow: [] }, ignore: [], day: '2026-11-14', minute: 600, ...over,
})

test('observe：从允许 App 切到别的 App 出一个「切出」', () => {
  let r = observe({}, ctxOf())
  assert.equal(r.events.some((e) => e.type === '切出'), false)
  r = observe(r.obs, ctxOf({ now: T0 + 20_000, front: 'com.douyin' }))
  assert.equal(r.events.filter((e) => e.type === '切出').length, 1)
})

test('observe：一直待在同一个出局 App 里只算一次切出，但停留在涨', () => {
  let r = observe({}, ctxOf({ front: 'com.douyin' }))
  r = observe(r.obs, ctxOf({ now: T0 + 30_000, front: 'com.douyin' }))
  assert.equal(r.events.some((e) => e.type === '切出'), false)
  assert.equal(r.events.find((e) => e.type === '偏离停留').dwellMs, 30_000)
})

test('observe：出局 App 之间互切，每换一个算一次切出', () => {
  let r = observe({}, ctxOf({ front: 'com.douyin' }))
  r = observe(r.obs, ctxOf({ now: T0 + 10_000, front: 'com.bili' }))
  assert.equal(r.events.filter((e) => e.type === '切出').length, 1)
})

test('observe：手机报的已开分钟数比我们看见的更早时以手机为准', () => {
  const r = observe({}, ctxOf({ front: 'com.douyin', minutes: 3 }))
  assert.equal(r.events.find((e) => e.type === '偏离停留').dwellMs, 3 * MIN)
})

test('observe：局换了出局结束和局开始', () => {
  let r = observe({}, ctxOf({ bout: { id: 'b1' } }))
  assert.ok(r.events.some((e) => e.type === '局开始' && e.boutId === 'b1'))
  r = observe(r.obs, ctxOf({ bout: null }))
  assert.ok(r.events.some((e) => e.type === '局结束' && e.boutId === 'b1'))
})

test('observe：回到允许 App 停留清零', () => {
  let r = observe({}, ctxOf({ front: 'com.douyin' }))
  r = observe(r.obs, ctxOf({ now: T0 + 10_000, front: 'com.study' }))
  assert.equal(r.obs.offSince, 0)
  assert.equal(r.events.some((e) => e.type === '偏离停留'), false)
})

// 手机报的包名序列：两次 /plan 之间切走又切回来的那些，只看前台差集是数不到的。
test('observe：手机报的包名序列补上漏掉的切出', () => {
  const r = observe({}, ctxOf({
    front: 'com.study',
    recent: [
      { app: 'com.douyin', agoMs: 50_000 },
      { app: 'com.study', agoMs: 40_000 },
      { app: 'com.douyin', agoMs: 30_000 },
      { app: 'com.study', agoMs: 10_000 },
    ],
  }))
  const cut = r.events.filter((e) => e.type === '切出')
  assert.equal(cut.length, 2, '两次切到抖音，现在是学习 → 前台差集看不到它们')
  // 时间按手机报的来，不是全算在这一拍。
  assert.equal(cut[0].at, T0 - 50_000)
  assert.equal(cut[1].at, T0 - 30_000)
})

test('observe：同一段序列不会数两遍（水位线）', () => {
  const seq = [{ app: 'com.douyin', agoMs: 20_000 }, { app: 'com.study', agoMs: 5_000 }]
  const first = observe({}, ctxOf({ front: 'com.study', recent: seq }))
  assert.equal(first.events.filter((e) => e.type === '切出').length, 1)
  // 下一次 /plan 带回同一段（手机的时间窗是滑动的），只该出现新切换。
  const second = observe(first.obs, ctxOf({ now: T0 + 15_000, front: 'com.study', recent: seq }))
  assert.equal(second.events.filter((e) => e.type === '切出').length, 0)
})

test('observe：序列里切到允许的 App、或切到桌面，都不算切出', () => {
  const r = observe({}, ctxOf({
    front: 'com.study',
    apps: { need: ['com.study'], allow: ['com.tencent.mm'] },
    recent: [
      { app: 'com.miui.home', agoMs: 30_000 },
      { app: 'com.tencent.mm', agoMs: 20_000 },
      { app: 'com.study', agoMs: 5_000 },
    ],
  }))
  assert.equal(r.events.filter((e) => e.type === '切出').length, 0)
})

// ---------------------------------------------------------------- 匹配

const run = (doc, seq) => {
  let d = doc
  let obs = {}
  const all = []
  for (const c of seq) {
    const o = observe(obs, c)
    obs = o.obs
    const m = match(d, o.events, { now: c.now, bout: c.bout, data: {} })
    d = m.doc
    all.push(...m.fires)
  }
  return { fires: all, doc: d }
}

test('switch-storm：10 分钟切出 3 次触发严格模式', () => {
  const seq = [
    ctxOf({ now: T0 }),
    ctxOf({ now: T0 + 1 * MIN, front: 'com.douyin' }),
    ctxOf({ now: T0 + 2 * MIN }),
    ctxOf({ now: T0 + 3 * MIN, front: 'com.douyin' }),
    ctxOf({ now: T0 + 3 * MIN + 10_000 }),
    ctxOf({ now: T0 + 4 * MIN, front: 'com.bili' }),
  ]
  const { fires } = run(emptyDoc(), seq)
  const storm = fires.filter((f) => f.presetId === 'switch-storm')
  assert.equal(storm.length, 1)
  assert.equal(storm[0].actions[0].type, '严格模式')
})

test('switch-storm：间隔超过窗口不触发', () => {
  const seq = [
    ctxOf({ now: T0 }),
    ctxOf({ now: T0 + 1 * MIN, front: 'com.douyin', minutes: 0 }),
    ctxOf({ now: T0 + 1 * MIN + 5_000 }),
    ctxOf({ now: T0 + 7 * MIN, front: 'com.douyin' }),
    ctxOf({ now: T0 + 7 * MIN + 5_000 }),
    ctxOf({ now: T0 + 14 * MIN, front: 'com.douyin' }),
  ]
  const { fires } = run(emptyDoc(), seq)
  assert.equal(fires.filter((f) => f.presetId === 'switch-storm').length, 0)
})

test('off-dwell：在出局 App 里待满 1 分钟触发，同一段停留只触发一次', () => {
  const seq = [
    ctxOf({ now: T0 }),
    ctxOf({ now: T0 + 10_000, front: 'com.douyin' }),
    ctxOf({ now: T0 + 50_000, front: 'com.douyin' }),
    ctxOf({ now: T0 + 80_000, front: 'com.douyin' }),
    ctxOf({ now: T0 + 140_000, front: 'com.douyin' }),
  ]
  const { fires } = run(emptyDoc(), seq)
  assert.equal(fires.filter((f) => f.presetId === 'off-dwell').length, 1)
})

test('仅局内：没有局时不匹配', () => {
  const seq = [
    ctxOf({ now: T0, bout: null }),
    ctxOf({ now: T0 + 10_000, front: 'com.douyin', bout: null, fun: true }),
    ctxOf({ now: T0 + 200_000, front: 'com.douyin', bout: null, fun: true }),
  ]
  const { fires } = run(emptyDoc(), seq)
  assert.equal(fires.length, 0)
})

test('停用的不匹配；影子照样匹配但标 shadow', () => {
  let d = proposeChange(emptyDoc(), { raw: { id: 'off-dwell', 状态: '影子' }, by: 'user', now: T0 }).doc
  d = settlePending(d, T0 + LOOSEN_COOLDOWN_MS).doc
  assert.equal(presetOf(d, 'off-dwell').status, '影子')
  const seq = [ctxOf({ now: T0 }), ctxOf({ now: T0 + 10_000, front: 'com.douyin' }), ctxOf({ now: T0 + 80_000, front: 'com.douyin' })]
  const shadow = run(d, seq).fires.filter((f) => f.presetId === 'off-dwell')
  assert.equal(shadow.length, 1)
  assert.equal(shadow[0].shadow, true)
})

test('内置的常驻 / 巡检 / 阶梯预案不进通用匹配', () => {
  const r = match(emptyDoc(), [{ type: '常驻', at: T0 }, { type: '巡检', at: T0 }, { type: '敷衍', at: T0 }], { now: T0, bout: { id: 'b1' }, data: {} })
  assert.equal(r.fires.length, 0)
})

test('敷衍事件按窗口计数', () => {
  let d = proposeChange(emptyDoc(), {
    raw: { id: 'lazy-two', 名称: '两次敷衍就严格', 状态: '生效', 何时: { 事件: '敷衍', 窗口: '15m', 次数: 2 }, 动作: [{ 全机锁: { 时长: '5m' } }], 解除: ['到点'] },
    by: 'user', now: T0,
  }).doc
  let r = match(d, [{ type: '敷衍', at: T0 }], { now: T0, bout: null, data: {} })
  assert.equal(r.fires.length, 0)
  r = match(r.doc, [{ type: '敷衍', at: T0 + MIN }], { now: T0 + MIN, bout: null, data: {} })
  assert.equal(r.fires.length, 1)
  assert.equal(r.fires[0].presetId, 'lazy-two')
})

test('到点：每天只触发一次', () => {
  let d = proposeChange(emptyDoc(), {
    raw: { id: 'nine-plan', 名称: '九点定计划', 状态: '生效', 何时: { 事件: '到点', 时刻: '09:00' }, 动作: [{ 进入计划: {} }], 解除: ['用户确认'] },
    by: 'user', now: T0,
  }).doc
  const ev = (minute, day) => [{ type: '到点', minute, day, at: T0 }]
  let r = match(d, ev(539, 'd1'), { now: T0, bout: null, data: {} })
  assert.equal(r.fires.length, 0)
  r = match(r.doc, ev(540, 'd1'), { now: T0, bout: null, data: {} })
  assert.equal(r.fires.length, 1)
  r = match(r.doc, ev(600, 'd1'), { now: T0, bout: null, data: {} })
  assert.equal(r.fires.length, 0)
  r = match(r.doc, ev(545, 'd2'), { now: T0, bout: null, data: {} })
  assert.equal(r.fires.length, 1)
})

test('条件为假不触发；条件为真触发', () => {
  const d = proposeChange(emptyDoc(), {
    raw: custom({ 何时: { 事件: '局开始' }, 条件: { '==': [{ var: 'bout.kind' }, '题局'] }, 动作: [{ 记账: {} }] }), by: 'user', now: T0,
  }).doc
  assert.equal(match(d, [{ type: '局开始', at: T0 }], { now: T0, bout: { id: 'b' }, data: { bout: { kind: '学习局' } } }).fires.length, 0)
  assert.equal(match(d, [{ type: '局开始', at: T0 }], { now: T0, bout: { id: 'b' }, data: { bout: { kind: '题局' } } }).fires.length, 1)
})

// ---------------------------------------------------------------- 动作

test('plan：严格模式放行本局应用，绑局时 until 是上限', () => {
  const fire = { presetId: 'switch-storm', name: 'x', actions: [{ type: '严格模式', params: { 放行: '本局应用', 直到: '本局结束' } }], why: '预案「x」' }
  const ops = plan(fire, { now: T0, bout: { id: 'b1' }, apps: { need: ['com.study'], allow: ['com.dict', 'com.study'] }, front: 'com.douyin' })
  assert.equal(ops[0].op, 'strict')
  assert.deepEqual(ops[0].allow, ['com.study', 'com.dict'])
  assert.equal(ops[0].boutId, 'b1')
  assert.equal(ops[0].until, T0 + BOUT_END_CAP_MS)
})

test('plan：严格模式按时长、锁当前 App、全机锁、开工具、进计划、墙收紧', () => {
  const fire = {
    presetId: 'p', name: 'p', why: 'w', actions: [
      { type: '严格模式', params: { 放行: ['com.a'], 直到: '30m' } },
      { type: '锁', params: { 应用: '当前', 时长: '5m' } },
      { type: '全机锁', params: { 时长: '10m' } },
      { type: '开工具', params: { 工具: 'gemini', 时长: '10m' } },
      { type: '进入计划', params: {} },
      { type: '墙收紧', params: {} },
      { type: '记账', params: {} },
    ],
  }
  const ops = plan(fire, { now: T0, bout: null, apps: { need: [], allow: [] }, front: 'com.douyin' })
  assert.deepEqual(ops.map((o) => o.op), ['strict', 'block', 'all-lock', 'tool', 'plan', 'tighten', 'log'])
  assert.equal(ops[0].until, T0 + 30 * MIN)
  assert.equal(ops[0].boutId, '')
  assert.equal(ops[1].app, 'com.douyin')
  assert.equal(ops[1].until, T0 + 5 * MIN)
})

test('plan：「说」并进锁的那句解释里', () => {
  const fire = { presetId: 'p', name: 'p', why: '预案「p」', actions: [{ type: '说', params: { 话: '回去。' } }, { type: '全机锁', params: {} }] }
  const ops = plan(fire, { now: T0, bout: null, apps: { need: [], allow: [] }, front: null })
  assert.equal(ops.find((o) => o.op === 'all-lock').why, '回去。 预案「p」')
})

test('plan：锁当前 App 但前台为空时不出操作', () => {
  const fire = { presetId: 'p', name: 'p', why: 'w', actions: [{ type: '锁', params: {} }] }
  assert.equal(plan(fire, { now: T0, bout: null, apps: { need: [], allow: [] }, front: null }).length, 0)
})

// ---------------------------------------------------------------- 严格模式

test('严格模式：绑局的，局收了就结束', () => {
  const d = enterStrict(emptyDoc(), { allow: ['com.a'], until: T0 + BOUT_END_CAP_MS, boutId: 'b1', why: 'w', presetId: 'p' }, T0)
  assert.ok(strictView(d, T0 + MIN, 'b1'))
  assert.equal(strictView(d, T0 + MIN, null), null)
  assert.equal(strictView(d, T0 + MIN, 'b2'), null)
})

test('严格模式：按时长的，到点结束', () => {
  const d = enterStrict(emptyDoc(), { allow: ['com.a'], until: T0 + 10 * MIN, boutId: '', why: 'w', presetId: 'p' }, T0)
  assert.ok(strictView(d, T0 + 9 * MIN, null))
  assert.equal(strictView(d, T0 + 10 * MIN, null), null)
})

test('严格模式：再进一次只会更严（放行取交集），到期取更晚', () => {
  let d = enterStrict(emptyDoc(), { allow: ['com.a', 'com.b'], until: T0 + 10 * MIN, boutId: '', why: 'w1', presetId: 'p1' }, T0)
  d = enterStrict(d, { allow: ['com.b', 'com.c'], until: T0 + 20 * MIN, boutId: '', why: 'w2', presetId: 'p2' }, T0 + MIN)
  const s = strictView(d, T0 + 2 * MIN, null)
  assert.deepEqual(s.allow, ['com.b'])
  assert.equal(s.until, T0 + 20 * MIN)
  assert.equal(s.why, 'w2')
})

test('clearStrict', () => {
  const d = enterStrict(emptyDoc(), { allow: [], until: T0 + MIN, boutId: '', why: 'w', presetId: 'p' }, T0)
  assert.equal(strictView(clearStrict(d), T0, null), null)
})

// ---------------------------------------------------------------- 说明

test('explain：预案名、为什么、怎么解除', () => {
  const p = presetOf(emptyDoc(), 'switch-storm')
  assert.equal(explain(p), '预案「局内频繁切出」：局里10 分钟内切出 3 次。解除：本局结束 / 破窗')
})

test('describeWhen：每种事件都有一句话', () => {
  assert.match(describeWhen({ event: '偏离停留', dwellMs: MIN, inBout: true }), /待满 1 分钟/)
  assert.match(describeWhen({ event: '到点', atMin: 540 }), /09:00/)
  assert.match(describeWhen({ event: '敷衍', windowMs: 15 * MIN, count: 2 }), /15 分钟内敷衍 2 次/)
})
