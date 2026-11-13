// dsh-go-companion · 预案的接线（第九轮 A 组）
//
// presets.js 是纯规则；这里把它接到真实世界：
//   - 每次 /plan 带来手机快照 → observe() 出事件 → match() 看哪条预案触发 → plan() 翻成操作 → 落手机；
//   - 局官每判一条回答，敷衍就喂一个「敷衍」事件；
//   - 严格模式由这里持有，sessionView() 给 /plan 的 session 字段用（手机侧和连麦舱是同一个槽位）；
//   - AI 工具和面板路由改预案都从这里过 proposeChange()，收紧 / 放宽的门槛在 presets.js 里。
//
// 依赖全部从 index.js 注入，这个文件不读盘、不碰 config，所以能脱机单测。
// 手机侧只认两样东西能改它的执法：session（放行名单 + 宽限）和 block / 规则快照。
// 严格模式走 session，graceMs 固定 0——「立即锁，不给宽限」。

import {
  allPresets, boutAppsOf, cancelPending, clearStrict, enterStrict, explain, historyOf, isActive, match,
  normalizeDoc, observe, plan, presetOf, proposeChange, rawOf, renew, setBoutApps, settlePending, settleSunset, strictView,
} from './presets.js'

/**
 * @param {{
 *   load: () => object, save: (doc:object) => void, record: (entry:object) => void,
 *   runningBout: () => ({id:string, kind:string, openedAt?:number}|null),
 *   ignore: () => string[], day: (now:number) => string, minute: (now:number) => number,
 *   weekday?: (now:number) => number, funToday?: (phone:object) => number,
 *   apply: {
 *     block: (app:string, until:number, why:string) => void,
 *     allLock: (until:number, why:string) => void,
 *     tool: (tool:string, until:number, topic:string, why:string) => void,
 *     plan: (until:number, why:string) => void,
 *     tighten: (why:string) => void,
 *   },
 * }} deps
 */
export function createPresetRuntime(deps) {
  const load = () => normalizeDoc(deps.load())
  const save = (d) => deps.save(d)

  /**
   * A5 热生效：插件没有推送通道，手机是拉的一方。所以改过预案之后留一个观察窗——
   * 窗内每次 /plan 都让手机 5 秒后再来问，改动最晚隔一次就问到了，不用等它下一次定时醒。
   */
  const WATCH_MS = 2 * 60_000
  const stamp = (d, now) => ({ ...d, revAt: now })
  const saveNow = (d, now) => deps.save(stamp(d, now))

  /** 条件里能读的变量。只有白名单里这几样，读不到聊天原文、读不到笔记正文。 */
  function dataOf(now, bout, phone, doc) {
    const fired = doc.obs?.firedDay === deps.day(now) && doc.obs?.fired ? doc.obs.fired : {}
    return {
      bout: bout ? { id: bout.id, kind: String(bout.kind || ''), minutes: bout.openedAt ? Math.floor((now - bout.openedAt) / 60_000) : 0 } : null,
      time: { hour: Math.floor(deps.minute(now) / 60), minute: deps.minute(now) % 60, weekday: deps.weekday ? deps.weekday(now) : null },
      today: { funMinutes: phone && deps.funToday ? deps.funToday(phone) : 0 },
      front: phone ? { app: phone.app || null, fun: phone.fun === true, minutes: Number(phone.minutes) || 0 } : null,
      fired,
    }
  }

  function noteFired(doc, now, fires) {
    const day = deps.day(now)
    const obs = doc.obs || {}
    const fired = obs.firedDay === day && obs.fired ? { ...obs.fired } : {}
    for (const f of fires) if (!f.shadow) fired[f.presetId] = (fired[f.presetId] || 0) + 1
    doc.obs = { ...obs, firedDay: day, fired }
  }

  /** 一次触发落地。影子只记账。 */
  function land(doc, fire, now, bout, phone) {
    if (fire.shadow) {
      deps.record({ state: 'preset-shadow', at: now, presetId: fire.presetId, why: fire.why, event: fire.event?.type })
      return { doc, ops: [] }
    }
    const apps = boutAppsOf(doc, bout?.id || '')
    const ops = plan(fire, { now, bout, apps, front: phone?.app || null })
    let d = doc
    for (const op of ops) {
      try {
        if (op.op === 'strict') d = enterStrict(d, op, now)
        else if (op.op === 'block') deps.apply.block(op.app, op.until, op.why)
        else if (op.op === 'all-lock') deps.apply.allLock(op.until, op.why)
        else if (op.op === 'tool') deps.apply.tool(op.tool, op.until, op.topic, op.why)
        else if (op.op === 'plan') deps.apply.plan(op.until, op.why)
        else if (op.op === 'tighten') deps.apply.tighten(op.why)
      } catch (error) {
        deps.record({ state: 'preset-error', at: now, presetId: fire.presetId, op: op.op, error: String((error && error.message) || error).slice(0, 200) })
      }
    }
    deps.record({
      state: 'preset-fire', at: now, presetId: fire.presetId, why: fire.why, event: fire.event?.type,
      ops: ops.map((o) => o.op).filter((o) => o !== 'say'), boutId: bout?.id || undefined,
    })
    return { doc: d, ops }
  }

  function runEvents(doc, events, now, bout, phone) {
    const m = match(doc, events, { now, bout, data: dataOf(now, bout, phone, doc) })
    let d = m.doc
    for (const e of m.errors) deps.record({ state: 'preset-error', at: now, presetId: e.presetId, error: String(e.error).slice(0, 200) })
    const landed = []
    for (const f of m.fires) {
      const r = land(d, f, now, bout, phone)
      d = r.doc
      landed.push({ presetId: f.presetId, shadow: f.shadow, ops: r.ops.map((o) => o.op) })
    }
    noteFired(d, now, m.fires)
    return { doc: d, fires: landed }
  }

  /**
   * 每次 /plan：先落到期的放宽，再看手机快照里发生了什么。
   * @param {object|null} phone  phone.js 清洗过的快照（app / fun / minutes）
   */
  function tick(phone, now) {
    let d = load()
    const settled = settlePending(d, now)
    d = settled.doc
    for (const id of settled.applied) deps.record({ state: 'preset-loosened', at: now, presetId: id })
    const sunset = settleSunset(d, now)
    d = sunset.doc
    for (const id of sunset.expired) deps.record({ state: 'preset-sunset', at: now, presetId: id })
    const bout = deps.runningBout()
    const apps = boutAppsOf(d, bout?.id || '')
    const o = observe(d.obs, {
      now, front: phone?.app || null, fun: phone?.fun === true, minutes: Number(phone?.minutes) || 0,
      recent: phone?.switches?.recent || null,
      bout, apps, ignore: deps.ignore(), day: deps.day(now), minute: deps.minute(now),
    })
    d.obs = { ...(d.obs || {}), ...o.obs }
    // 没有手机快照：不知道他在哪，不判切出和停留（不凭空锁）；局开始 / 结束和到点照判。
    const events = phone ? o.events : o.events.filter((e) => e.type !== '切出' && e.type !== '偏离停留')
    const r = runEvents(d, events, now, bout, phone)
    save(r.doc)
    return { fires: r.fires, strict: strictView(r.doc, now, bout?.id || null), watchUntil: (Number(r.doc.revAt) || 0) + WATCH_MS }
  }

  /** 局官判了一条敷衍。 */
  function perfunctory(now, phone = null) {
    const d = load()
    const bout = deps.runningBout()
    const r = runEvents(d, [{ type: '敷衍', at: now }], now, bout, phone)
    save(r.doc)
    return r.fires
  }

  /**
   * /plan 的 session 字段（连麦舱优先，调用方先看舱）。严格模式：放行名单 + 0 宽限 + 一句解释。
   * 放行名单为空也照发：那就是「除了局页和紧急应用什么都不行」。
   */
  function sessionView(now) {
    const bout = deps.runningBout()
    const s = strictView(load(), now, bout?.id || null)
    if (!s) return null
    return { app: s.allow[0] || undefined, allow: s.allow, until: s.until, graceMs: 0, why: s.why, kind: 'strict', by: 'preset' }
  }

  /** AI 或他把这一局直接切进严格模式（不等预案触发）。只会更严。 */
  function strictNow(now, why, by) {
    const bout = deps.runningBout()
    if (!bout) return { ok: false, text: '现在没有局，严格模式要绑在一局上。' }
    const apps = boutAppsOf(load(), bout.id)
    const allow = [...new Set([...apps.need, ...apps.allow])]
    const text = String(why || '').trim().slice(0, 40)
    const d = enterStrict(load(), {
      allow, until: now + 6 * 3_600_000, boutId: bout.id, presetId: 'manual',
      why: (text ? text + ' ' : '') + '严格模式：只剩本局应用。解除：本局结束 / 破窗',
    }, now)
    saveNow(d, now)
    deps.record({ state: 'strict-on', at: now, by, boutId: bout.id, allow })
    return { ok: true, text: allow.length ? '这一局进严格模式：只剩 ' + allow.join('、') + '。' : '这一局进严格模式：本局没声明 App，只剩局页。', allow }
  }

  /** 破窗等出口：收掉严格模式。 */
  function strictOff(now, by) {
    saveNow(clearStrict(load()), now)
    deps.record({ state: 'strict-off', at: now, by })
  }

  /** 记下一局的 App（'current' = 正在跑的那一局）。 */
  function setApps(boutId, apps, now, by) {
    const id = boutId === 'current' || !boutId ? deps.runningBout()?.id || '' : boutId
    if (!id) return { ok: false, text: '现在没有局。' }
    const r = setBoutApps(load(), id, apps)
    if (!r.ok) return { ok: false, text: '没记上。' }
    saveNow(r.doc, now)
    deps.record({ state: 'bout-apps', at: now, boutId: id, need: r.apps.need, allow: r.apps.allow, by })
    return { ok: true, text: '记下了：必需 ' + (r.apps.need.join('、') || '无') + '；允许 ' + (r.apps.allow.join('、') || '无') + '。', apps: r.apps }
  }

  /** 改一条预案。by='ai' 时放宽一律拒绝。 */
  function propose(raw, by, now) {
    const r = proposeChange(load(), { raw, by, now })
    if (r.ok && r.kind !== 'same') {
      saveNow(r.doc, now)
      deps.record({ state: 'preset-change', at: now, by, presetId: String(raw?.id || ''), kind: r.kind, pendingId: r.pendingId, effectiveAt: r.effectiveAt })
    } else if (!r.ok) {
      deps.record({ state: 'preset-refused', at: now, by, presetId: String(raw?.id || ''), code: r.code })
    }
    return { ok: r.ok, code: r.code, kind: r.kind, text: r.message, pendingId: r.pendingId, effectiveAt: r.effectiveAt }
  }

  /** 续期（日落前再给 30 天）。这一版只有他能续。 */
  function renewPreset(id, now, by) {
    const r = renew(load(), id, now, by)
    if (r.ok) {
      save(r.doc)
      deps.record({ state: 'preset-renew', at: now, by, presetId: id, sunsetAt: r.sunsetAt })
    }
    return { ok: r.ok, code: r.code, text: r.message, sunsetAt: r.sunsetAt }
  }

  function cancel(pendingId, now, by) {
    const r = cancelPending(load(), pendingId)
    if (r.ok) {
      saveNow(r.doc, now)
      deps.record({ state: 'preset-cancel', at: now, by, pendingId })
    }
    return { ok: r.ok, text: r.ok ? '撤销了。' : '没有这条待生效的放宽。' }
  }

  /** 给面板和 AI 看的清单。 */
  function list(now) {
    const d = load()
    const bout = deps.runningBout()
    return {
      presets: allPresets(d).map((p) => ({
        id: p.id, name: p.name, status: p.status, builtin: p.builtin, fixed: p.fixed, version: p.version,
        explain: explain(p), note: p.note, raw: rawOf(p), basis: p.basis || '', by: p.by || '', sunsetAt: p.sunsetAt || 0,
      })),
      pending: d.pending.map((x) => ({ id: x.id, presetId: x.presetId, effectiveAt: x.effectiveAt, raw: x.raw })),
      strict: strictView(d, now, bout?.id || null),
      bout: bout ? { id: bout.id, kind: bout.kind, apps: boutAppsOf(d, bout.id) } : null,
      fired: d.obs?.firedDay === deps.day(now) ? d.obs.fired || {} : {},
    }
  }

  return {
    tick,
    perfunctory,
    sessionView,
    strictNow,
    strictOff,
    setApps,
    propose,
    cancel,
    renew: renewPreset,
    list,
    history: (id) => historyOf(load(), id),
    isActive: (id) => isActive(load(), id),
    preset: (id) => presetOf(load(), id),
  }
}
