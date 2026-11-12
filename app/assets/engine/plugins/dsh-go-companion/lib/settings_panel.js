// dsh-go-companion · 设定页的数据（第八轮 W4）
//
// 施工书第 9 节那几个待定项（固定工作时间、最少局数、升级阈值）就是这一页要能改的东西。
// 口径只有一处：settings.js。这一层只做三件事——把现在的设定摆出来、把表单里的字解析成
// 一套新设定、把设定渲染成笔记库里的 `大肥鱼/墙/设定.md`。
//
// 改紧立即生效、改松挂一天冷却期这件事不在这里判：那是 settings.js 的 applySettings。
// 这里只保证「解析不出来就整份不提交」，绝不把半份坏设定写进去。
//
// 阈值的键名照抄 settings.js 的 DEFAULT_SETTINGS.escalate（E1Count / E1WindowMs / L1WorkMin /
// L1RestMin / L2Min / L3Min / cellMin）——大小写不一样就等于永远读到默认值。

import { DEFAULT_SETTINGS, settingsOf } from './settings.js'

const KINDS = [['workday', '工作日'], ['restday', '休息日'], ['outing', '外出日']]
const PROTOCOLS = [['study', '学习'], ['execute', '执行'], ['design', '设计'], ['outing', '外出'], ['rest', '休息'], ['thinking', '思维']]

/**
 * 阈值字段：表单键 -> 设定键。只有时间窗在表单里填分钟、在设定里存毫秒。
 * 加一项阈值就在这里加一行，别在两边各写一遍。
 */
const LIMIT_FIELDS = [
  ['E1Count', 'E1Count', 'E1 敷衍次数', 1, 20],
  ['E1WindowMin', 'E1WindowMs', 'E1 时间窗（分钟）', 1, 240],
  ['cellMin', 'cellMin', '审讯舱起步（分钟）', 1, 120],
  ['L1WorkMin', 'L1WorkMin', 'L1 工作时段连续娱乐（分钟）', 1, 240],
  ['L1RestMin', 'L1RestMin', 'L1 非工作时段连续娱乐（分钟）', 1, 240],
  ['L2Min', 'L2Min', 'L2 在局里用没声明的应用（分钟）', 1, 120],
  ['L3Min', 'L3Min', 'L3 每天娱乐累计（分钟）', 1, 720],
]

function clock(min) {
  const m = Math.round(Number(min) || 0)
  return Math.floor(m / 60) + ':' + String(m % 60).padStart(2, '0')
}

function num(text, 名, min, max) {
  const s = String(text ?? '').trim()
  if (!s) return { error: 名 + '不能空着' }
  const n = Number(s)
  if (!Number.isFinite(n)) return { error: 名 + '要填数字' }
  if (n < min || n > max) return { error: 名 + '要在 ' + min + '–' + max + ' 之间' }
  return { value: Math.round(n) }
}

/** 时段的文字表示：`9:00-12:00，14:00-18:00`。空的表示这一段日子没有固定工作时段。 */
export function formatWindows(list) {
  return (Array.isArray(list) ? list : []).map((w) => clock(w.from) + '-' + clock(w.to)).join('，')
}

/**
 * 解析时段。接受 `9:00-12:00`、`9-12`、全角冒号和逗号、空格分隔；空串是「没有」。
 * @returns {{windows?:{from:number,to:number}[], error?:string}}
 */
export function parseWindows(text) {
  const raw = String(text ?? '').trim()
  if (!raw) return { windows: [] }
  // 「9:00 到 12:00」「9:00 — 12:00」都是人话，先归一成 9:00-12:00 再切段。
  // 切段只按逗号分号，不按空白——按空白切会把「到」切坏。
  const normalized = raw.replace(/：/g, ':').replace(/[到至~—－]/g, '-').replace(/\s*-\s*/g, '-')
  const out = []
  for (const part of normalized.split(/[,，、;；]+/).map((s) => s.trim()).filter(Boolean)) {
    const m = part.match(/^(\d{1,2})(?::(\d{1,2}))?\s*[-~—到至]\s*(\d{1,2})(?::(\d{1,2}))?$/)
    if (!m) return { error: '看不懂这一段：' + part + '（写成 9:00-12:00）' }
    const from = Number(m[1]) * 60 + Number(m[2] || 0)
    const to = Number(m[3]) * 60 + Number(m[4] || 0)
    if (from < 0 || to > 24 * 60) return { error: part + ' 超出一天的范围' }
    if (to <= from) return { error: part + ' 结束比开始早' }
    out.push({ from, to })
  }
  out.sort((a, b) => a.from - b.from)
  for (let i = 1; i < out.length; i += 1) {
    if (out[i].from < out[i - 1].to) {
      return { error: clock(out[i - 1].from) + '-' + clock(out[i - 1].to) + ' 和 ' + clock(out[i].from) + '-' + clock(out[i].to) + ' 重叠了' }
    }
  }
  return { windows: out }
}

const get = (values, k) => (values && (values['data.form.' + k] ?? values[k]))

/**
 * 作息页的表单 -> 一套新设定。任何一格解析不出来就整份退回，不做部分提交。
 * @param {object} prev 现在的设定（settingsOf 的结果）
 * @param {object} values 表单值，键是 `data.form.*`
 */
export function parseDay(prev, values = {}) {
  const base = settingsOf(prev)
  const next = { ...base, windows: { ...base.windows }, budget: { ...base.budget } }
  for (const [kind, zh] of KINDS) {
    const r = parseWindows(get(values, kind))
    if (r.error) return { error: zh + '：' + r.error }
    next.windows[kind] = r.windows
  }
  for (const [key, zh, min, max] of [['minBouts', '每天最少局数', 0, 12], ['minThinking', '最少思维局', 0, 6]]) {
    const r = num(get(values, key), zh, min, max)
    if (r.error) return { error: r.error }
    next[key] = r.value
  }
  for (const [id, zh] of PROTOCOLS) {
    const r = num(get(values, 'budget.' + id), zh + '局预算', 5, 180)
    if (r.error) return { error: r.error }
    next.budget[id] = r.value
  }
  return { next }
}

/** 阈值页的表单 -> 一套新设定。 */
export function parseLimits(prev, values = {}) {
  const base = settingsOf(prev)
  const next = { ...base, escalate: { ...base.escalate } }
  for (const [formKey, settingKey, zh, min, max] of LIMIT_FIELDS) {
    const r = num(get(values, formKey), zh, min, max)
    if (r.error) return { error: r.error }
    next.escalate[settingKey] = settingKey === 'E1WindowMs' ? r.value * 60_000 : r.value
  }
  return { next }
}

/** 现在的设定 -> 两页要显示的行，以及表单的预填值。 */
export function buildSettingsSnapshot(input = {}) {
  const src = input && typeof input === 'object' ? input : {}
  const now = Number(src.now) || 0
  const s = settingsOf(src.settings || src.config)
  const e = s.escalate || {}
  const rows = KINDS.map(([kind, zh]) => zh + '：' + (formatWindows(s.windows[kind]) || '没有固定时段'))
  const budgets = PROTOCOLS.map(([id, zh]) => zh + ' ' + (s.budget[id] || 0) + ' 分钟')

  const limits = []
  if (e.E1Count) limits.push('E1：' + Math.round((Number(e.E1WindowMs) || 0) / 60_000) + ' 分钟内敷衍 ' + e.E1Count + ' 次')
  if (e.cellMin) limits.push('审讯舱起步 ' + e.cellMin + ' 分钟，每次加 ' + (e.cellMinStep || 0) + ' 分钟')
  if (e.L1WorkMin) limits.push('L1 工作时段连续娱乐 ' + e.L1WorkMin + ' 分钟')
  if (e.L1RestMin) limits.push('L1 非工作时段连续娱乐 ' + e.L1RestMin + ' 分钟')
  if (e.L2Min) limits.push('L2 在局里用没声明的应用 ' + e.L2Min + ' 分钟')
  if (e.L3Min) limits.push('L3 每天娱乐累计 ' + e.L3Min + ' 分钟')

  const pend = src.pending && Number(src.pendingAt) > now ? Number(src.pendingAt) : 0
  const pendClock = pend ? new Date(pend).getHours() * 60 + new Date(pend).getMinutes() : 0

  const form = {
    workday: formatWindows(s.windows.workday),
    restday: formatWindows(s.windows.restday),
    outing: formatWindows(s.windows.outing),
    minBouts: String(s.minBouts),
    minThinking: String(s.minThinking),
  }
  for (const [id] of PROTOCOLS) form['budget.' + id] = String(s.budget[id] || 0)
  for (const [formKey, settingKey] of LIMIT_FIELDS) {
    form[formKey] = String(settingKey === 'E1WindowMs' ? Math.round((Number(e[settingKey]) || 0) / 60_000) : (e[settingKey] || 0))
  }

  return {
    at: now,
    rows,
    rowsNote: '改紧立即生效；改松挂一天冷却期。',
    budgets,
    budgetsNote: '每局的默认预算（分钟）。',
    limits,
    limitsNote: limits.length ? '改紧立即生效；改松挂一天冷却期。' : '还没读到阈值。',
    voice: {
      row: s.voice && s.voice.provider ? String(s.voice.provider) : '用系统 TTS',
      model: s.judgeModel ? String(s.judgeModel) : '还没选局官模型',
    },
    pending: {
      on: Boolean(pend),
      text: pend ? '有一处改松挂到 ' + clock(pendClock) + ' 才生效。' : '没有挂着的改动。',
      at: pend,
    },
    // 表单预填：都给字符串，输入框不用管类型。
    form,
  }
}

/** 笔记库里的 `设定.md`。这是他以后翻笔记时看到的同一套数。 */
export function settingsMarkdown(snapshot) {
  const s = snapshot || {}
  const lines = ['# 墙的设定', '', '## 固定工作时段', '']
  for (const row of s.rows || []) lines.push('- ' + row)
  lines.push('', '## 每天最少局数', '')
  lines.push('- ' + (s.form?.minBouts || '0') + ' 局，其中至少 ' + (s.form?.minThinking || '0') + ' 个思维局')
  lines.push('', '## 各局型默认预算', '')
  for (const row of s.budgets || []) lines.push('- ' + row)
  lines.push('', '## 升级阈值', '')
  for (const row of s.limits || []) lines.push('- ' + row)
  lines.push('', '## 声音与局官', '')
  lines.push('- 念出来：' + (s.voice?.row || '用系统 TTS'))
  lines.push('- 局官模型：' + (s.voice?.model || '还没选'))
  if (s.pending?.on) lines.push('', '> ' + s.pending.text)
  lines.push('', '> 这份文件是墙自己写的，改它不会改设定；要改设定请在「设定」页改。')
  return lines.join('\n')
}

export { DEFAULT_SETTINGS, LIMIT_FIELDS }
