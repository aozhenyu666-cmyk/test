/**
 * 情绪模块 —— 读 dsh-feiyu-mood 的真实状态，衰减自己算，不伪造
 *
 * 上游（实测 0.8.1）：
 *   ~/.dsh/feiyu-mood/config.json  { slots:[{id,label,selfName,ownerName,sessions[]}], 模式, moodBase, moodFloor,
 *                                    decayHalfHours, injectMode, injectConfidence, injectPerDay, sigMood, sigDim, … }
 *   ~/.dsh/feiyu-mood/state.json   { version, moods:{ <slotId>: { mood, dims{7}, timeline:[{at,mood,delta,reason,conf,dims,dimsDelta}],
 *                                                     lastReason,lastConfidence,updatedAt,lastDecayAt,saidAt[],minor,kind,… } }, lastActive }
 *
 * 硬规矩：
 *   1. 七维的名字是**上游的**：happy / calm / sweet / heartache / worry / sad / jealous。
 *      以前这里写成 steady/tender 是错的（那是有空格键的英文对译，上游根本不发这两个键）。
 *   2. slot 与 moods 的键对不上时**必须报出来**，不许挑一份凑数 —— 用户会以为绑对了。
 *   3. 衰减只用于显示「按现在这个配置，还剩多少」，不改 state.json。
 */
import fs from 'node:fs'
import path from 'node:path'

import { dshHome } from './config.mjs'

// 七个维度只有一处定义 (dims.mjs)。这里先 import 再原样 export，原因有两个：
//   1. 下面自己的函数要用 DIMS / DIM_LABEL，而 `export {} from` 不会把名字带进本模块作用域；
//   2. 不破坏已有的 import 路径（views.mjs / tools.mjs / test 都从这个文件取）。
import { DIMS, DIM_LABEL, DIM_VISIBLE_MIN } from './dims.mjs'

export { DIMS, DIM_LABEL, DIM_VISIBLE_MIN }

export const moodStatePath = () => path.join(dshHome(), 'feiyu-mood', 'state.json')
export const moodConfigPath = () => path.join(dshHome(), 'feiyu-mood', 'config.json')

function readJson(p) {
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''))
  } catch {
    return null
  }
}

/** 0–100 → 五档。阈值照抄 dsh-feiyu-mood 的判定口径。 */
export function moodBand(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return { label: '不知道', icon: 'question', tone: 'muted' }
  if (n >= 80) return { label: '欢喜', icon: 'sparkle', tone: 'good' }
  if (n >= 62) return { label: '开心', icon: 'heart', tone: 'good' }
  if (n >= 42) return { label: '一般', icon: 'chat', tone: 'info' }
  if (n >= 25) return { label: '心疼', icon: 'warning', tone: 'warn' }
  return { label: '难过', icon: 'warning', tone: 'bad' }
}
/**
 * 挑出这个会话该读哪一份心情。
 * ① 有 sessionId：从 config.slots 里找 sessions 含它的那一份；
 * ② 没匹配到：取第一份；
 * ③ 一份都没有：用 state 里第一个键（并标记 bound=false）。
 * **绝不**把别的份的数字说成这一份的。
 */
export function pickSlot(cfg, state, sessionId) {
  const slots = cfg && Array.isArray(cfg.slots) ? cfg.slots : []
  const moods = state && state.moods && typeof state.moods === 'object' ? state.moods : {}
  const keys = Object.keys(moods)
  const sid = String(sessionId || '')
  let slot = null
  if (sid) {
    slot = slots.filter((s) => Array.isArray(s.sessions) && s.sessions.map(String).includes(sid))[0] || null
  }
  if (!slot && slots.length) slot = slots[0]
  if (!slot && keys.length) slot = { id: keys[0], label: '', sessions: [] }
  if (!slot) return { ok: false, reason: 'no-slot', keys, slots: slots.length }
  const id = String(slot.id || '')
  let mood = moods[id]
  let matched = Boolean(mood)
  if (!mood && keys.length === 1) {
    // 唯一一份记录：显示它，但**明确标 matched=false**，让上层去警告
    mood = moods[keys[0]]
    matched = false
  }
  if (!mood) return { ok: false, reason: 'slot-mismatch', slot, keys }
  return { ok: true, slot, mood, matched, keys }
}

/**
 * 衰减：上游 decayHalfHours（实测 12 小时）的指数衰减，从**最后一次真变化**算起。
 * 只算显示值，不写回 state.json —— 上游自己会衰减，两边都写就打架。
 */
export function decayed(mood, now, halfHours) {
  const t = now === undefined ? Date.now() : now
  const half = Math.max(0.1, Number(halfHours) || 12)
  const base = Number.isFinite(Number(mood.moodBase)) ? Number(mood.moodBase) : 70
  const cur = Number(mood.mood)
  if (!Number.isFinite(cur)) return { value: base, from: base, hours: 0, applied: false }
  const anchor = Number(mood.lastDecayAt) || Number(mood.updatedAt) || 0
  if (!anchor) return { value: cur, from: cur, hours: 0, applied: false }
  const hours = Math.max(0, (t - anchor) / 3600000)
  if (hours < 0.01) return { value: cur, from: cur, hours, applied: false }
  const k = Math.pow(0.5, hours / half)
  const value = base + (cur - base) * k
  return { value: Math.round(value * 100) / 100, from: cur, hours: Math.round(hours * 10) / 10, applied: true, halfHours: half }
}

/** 七维当前值（上游存的是 0–100 的强度，不是 0–1）。 */
export function dims(mood) {
  const d = mood && mood.dims && typeof mood.dims === 'object' ? mood.dims : {}
  return DIMS.map((k) => {
    const raw = Number(d[k])
    return { key: k, label: DIM_LABEL[k], value: Number.isFinite(raw) ? Math.round(raw * 100) / 100 : 0 }
  })
}

/** 时间线（最近的在前），字段名照上游：{at, mood, delta, reason, conf, dims, dimsDelta}。 */
export function timeline(mood, limit) {
  const n = Math.max(0, Number(limit) || 8)
  const tl = mood && Array.isArray(mood.timeline) ? mood.timeline : []
  return tl.slice(-n).reverse().map((e) => ({
    at: Number(e && e.at) || 0,
    mood: Number(e && e.mood),
    delta: Number(e && e.delta) || 0,
    reason: String((e && (e.reason || e.purpose)) || ''),
    conf: e && e.conf !== undefined ? Number(e.conf) : null,
  }))
}
/**
 * 读一份完整的心情快照。
 * @returns {{ok, reason, slot, mood, matched, current, dims, timeline, cfg, stateFile, configFile}}
 */
export function readMood(sessionId, now) {
  const stateFile = moodStatePath()
  const configFile = moodConfigPath()
  const cfg = readJson(configFile)
  const state = readJson(stateFile)
  if (!cfg && !state) return { ok: false, reason: 'no-feiyu-mood', stateFile, configFile }
  const c = cfg || {}
  if (!state) return { ok: false, reason: 'no-state', cfg: c, stateFile, configFile }
  if (!state.moods || !Object.keys(state.moods).length) return { ok: false, reason: 'no-moods', cfg: c, stateFile, configFile }
  const pick = pickSlot(c, state, sessionId)
  if (!pick.ok) return { ok: false, reason: pick.reason, cfg: c, state, stateFile, configFile, slot: pick.slot, keys: pick.keys }
  const slotName = {
    selfName: pick.slot.selfName || c.selfName || '',
    ownerName: pick.slot.ownerName || c.ownerName || '',
    label: pick.slot.label || '',
  }
  const dec = decayed({ ...pick.mood, moodBase: c.moodBase }, now, c.decayHalfHours)
  return {
    ok: true,
    reason: '',
    slot: pick.slot,
    matched: pick.matched,
    keys: pick.keys,
    mood: pick.mood,
    current: dec,
    dims: dims(pick.mood),
    timeline: timeline(pick.mood, 8),
    cfg: c,
    names: slotName,
    stateFile,
    configFile,
  }
}

/**
 * 交给 Agent 的那段情绪上下文。
 * 只在真的读了数据、且上游配置说「要告诉它」时才返回文字；否则空串。
 * 刻意**只说状态，不复述用户上一句**（那是上游的 injectMode='event' 在做的事，
 * 两边都说就成了复读机，也把她的口气定死）。
 */
export function moodContextText(snap, cfg, now) {
  if (!snap || !snap.ok) return ''
  if (!cfg || cfg.mood.enabled === false || cfg.mood.inject === false) return ''
  const mode = String(snap.cfg.injectMode || 'event')
  if (mode === 'off') return ''
  const conf = Number(snap.mood.lastConfidence)
  const gate = Number(snap.cfg.injectConfidence)
  if (Number.isFinite(conf) && Number.isFinite(gate) && conf < gate) return ''
  const band = moodBand(snap.current.value)
  const top = snap.dims.filter((d) => d.value >= DIM_VISIBLE_MIN).sort((a, b) => b.value - a.value).slice(0, 3)
  const lines = ['【这会儿的心情】' + band.label + '（' + Math.round(snap.current.value) + '/100）']
  if (top.length) lines.push('还剩着：' + top.map((d) => d.label + ' ' + Math.round(d.value)).join('、'))
  const last = snap.timeline[0]
  if (mode === 'event' && last && last.reason) lines.push('刚才那件事：' + last.reason.slice(0, 120))
  lines.push('这是底子，不是台词：别把这段念出来，也别因为它是低分就演得夸张。')
  return lines.join('\n')
}
