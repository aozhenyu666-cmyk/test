// 第四轮 R4-3：墙在 Obsidian 笔记库里的那一半。
//
// 用户要把 Obsidian 当成自己的知识库（类似 Notion），墙和两姐妹围着这个库干活：
//   大肥鱼/墙/规则.md      他能在 Obsidian 里改的规则（frontmatter 四项）
//   大肥鱼/墙/待生效.md    改松了的规则：冷却期过了才生效，期间改回去就撤销
//   大肥鱼/墙/审计/日期.md  墙每次审查的原话
//   大肥鱼/任务/日期-序号-名称.md  晨间立项的每件事一个文件，frontmatter 写状态 / 完成物 / 证据，
//                                 他可以在 Obsidian 里用 Bases 做成表格看
//
// 改规则的规矩：改严立即生效；改松要等冷却期（默认 7 天，config.rulesCooldownDays）。
// 否则松懈时打开 Obsidian 改一行就绕开了墙——这条和"今天的规则今天不放松"是一回事。

import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

export const WALL_DIR = '墙'
export const RULES_FILE = '规则.md'
export const PENDING_FILE = '待生效.md'
export const AUDIT_DIR = '审计'
export const TASK_DIR = '任务'
export const RULES_COOLDOWN_DAYS = 7

const KEY_MEALS = '吃饭时段'
const KEY_EVENING = '对账后到深夜锁前娱乐'
const KEY_SEGMENT = '一次放松最长分钟'
const KEY_QUOTA = '每周放松天数'

/** 四项规则在 config.wall 里的名字 ↔ 规则.md 里的中文名。 */
export const RULE_LABEL = {
  meals: KEY_MEALS,
  eveningOpen: KEY_EVENING,
  relaxSegmentMin: KEY_SEGMENT,
  weeklyQuota: KEY_QUOTA,
}

function hm(min) {
  const m = Math.max(0, Math.min(1440, Math.round(Number(min) || 0)))
  return String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0')
}

function parseHm(s) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(s).trim())
  if (!m) return null
  const v = Number(m[1]) * 60 + Number(m[2])
  return v >= 0 && v <= 1440 ? v : null
}

export function mealsToList(meals) {
  return (meals || []).map((m) => hm(m.from) + '-' + hm(m.to) + ' ' + String(m.name || '吃饭'))
}

/** "11:30-12:30 午饭" → {from, to, name}；格式不对返回 null。 */
export function mealOf(s) {
  const m = /^\s*(\d{1,2}:\d{2})\s*[-~—–至到]\s*(\d{1,2}:\d{2})\s*(.*)$/.exec(String(s))
  if (!m) return null
  const from = parseHm(m[1])
  const to = parseHm(m[2])
  if (from === null || to === null || from >= to) return null
  return { from, to, name: (m[3] || '吃饭').trim().slice(0, 8) || '吃饭' }
}

/** 规则.md 的全文。cur 是 wallConfig() 补全后的当前规则。 */
export function renderRules(cur) {
  return [
    '---',
    KEY_MEALS + ': ' + JSON.stringify(mealsToList(cur.meals)),
    KEY_EVENING + ': ' + (cur.eveningOpen ? '开' : '锁'),
    KEY_SEGMENT + ': ' + cur.relaxSegmentMin,
    KEY_QUOTA + ': ' + cur.weeklyQuota,
    '---',
    '',
    '# 墙的规则',
    '',
    '上面四项你可以直接在 Obsidian 里改，墙每隔几分钟读一次。',
    '',
    '- **改严立即生效**：吃饭时段缩短、对账后改成"锁"、放松时长或天数调小。',
    '- **改松要等冷却期**（默认 ' + RULES_COOLDOWN_DAYS + ' 天）：写进 `待生效.md`，到期才生效；期间改回去就撤销。',
    '- 吃饭时段写成 `"HH:MM-HH:MM 名字"`，几段都行；格式不对的那一段墙会忽略。',
    '',
  ].join('\n')
}

/** 读 frontmatter 里的四项（只认这四个键）。缺的键不算改动。 */
export function parseRules(text) {
  const s = String(text || '')
  const m = /^---\r?\n([\s\S]*?)\r?\n---/.exec(s)
  if (!m) return { ok: false, error: '规则.md 开头没有 --- 包起来的规则区' }
  const lines = m[1].split(/\r?\n/)
  const raw = {}
  for (let i = 0; i < lines.length; i++) {
    const kv = /^([^:#]+):\s*(.*)$/.exec(lines[i])
    if (!kv) continue
    const key = kv[1].trim()
    let val = kv[2].trim()
    if (!val) {
      // YAML 块列表：下面几行 "- xxx"
      const items = []
      while (i + 1 < lines.length && /^\s*-\s+/.test(lines[i + 1])) items.push(lines[++i].replace(/^\s*-\s+/, '').replace(/^["']|["']$/g, ''))
      raw[key] = items
      continue
    }
    if (val.startsWith('[')) {
      try {
        raw[key] = JSON.parse(val)
      } catch {
        raw[key] = val.slice(1, -1).split(',').map((x) => x.trim().replace(/^["']|["']$/g, '')).filter(Boolean)
      }
      continue
    }
    raw[key] = val.replace(/^["']|["']$/g, '')
  }
  const out = {}
  if (Array.isArray(raw[KEY_MEALS])) out.meals = raw[KEY_MEALS].map(mealOf).filter(Boolean).sort((a, b) => a.from - b.from)
  if (typeof raw[KEY_EVENING] === 'string') {
    if (/^(开|开放|open|true|是)$/i.test(raw[KEY_EVENING])) out.eveningOpen = true
    else if (/^(锁|关|lock|false|否)$/i.test(raw[KEY_EVENING])) out.eveningOpen = false
  }
  const seg = Math.round(Number(raw[KEY_SEGMENT]))
  if (Number.isFinite(seg)) out.relaxSegmentMin = Math.max(15, Math.min(240, seg))
  const quota = Math.round(Number(raw[KEY_QUOTA]))
  if (Number.isFinite(quota)) out.weeklyQuota = Math.max(0, Math.min(7, quota))
  return { ok: true, wall: out }
}

function openMinutes(meals) {
  const set = new Set()
  for (const m of meals || []) for (let t = m.from; t < m.to; t++) set.add(t)
  return set
}

function sameMeals(a, b) {
  return JSON.stringify(mealsToList(a)) === JSON.stringify(mealsToList(b))
}

/**
 * 文件里的规则和当前生效的比：哪些是改严（立即生效），哪些是改松（要等冷却期）。
 * 吃饭时段只要多出任何一分钟就算改松（哪怕同时砍掉了别的时段）。
 */
export function classifyRules(cur, next) {
  const tighten = {}
  const loosen = {}
  if (next.meals && !sameMeals(cur.meals, next.meals)) {
    const before = openMinutes(cur.meals)
    const added = [...openMinutes(next.meals)].some((t) => !before.has(t))
    ;(added ? loosen : tighten).meals = next.meals
  }
  if (typeof next.eveningOpen === 'boolean' && next.eveningOpen !== cur.eveningOpen) {
    ;(next.eveningOpen ? loosen : tighten).eveningOpen = next.eveningOpen
  }
  for (const k of ['relaxSegmentMin', 'weeklyQuota']) {
    if (typeof next[k] !== 'number' || next[k] === cur[k]) continue
    ;(next[k] > cur[k] ? loosen : tighten)[k] = next[k]
  }
  return { tighten, loosen }
}

/**
 * 改松的待生效表：同一项的值没变就沿用原来的起算时间，值变了重新起算；文件里改回去就从表里消失。
 * @returns {{pending:object, due:object}} due 是这次到期、该生效的那几项
 */
export function updatePending(prev, loosen, now, cooldownMs) {
  const pending = {}
  const due = {}
  for (const [k, value] of Object.entries(loosen)) {
    const old = prev && prev[k]
    const since = old && JSON.stringify(old.value) === JSON.stringify(value) ? Number(old.since) : now
    if (now - since >= cooldownMs) due[k] = value
    else pending[k] = { value, since }
  }
  return { pending, due }
}

function showValue(k, v) {
  if (k === 'meals') return mealsToList(v).join('、') || '（不留吃饭时段）'
  if (k === 'eveningOpen') return v ? '开' : '锁'
  return String(v)
}

export function renderPending(pending, cooldownMs, fmtDay = (at) => new Date(at).toISOString().slice(0, 10)) {
  const keys = Object.keys(pending || {})
  const lines = ['# 待生效的放松', '']
  if (!keys.length) {
    lines.push('现在没有。你在 规则.md 里改松的项会出现在这里，冷却期过了才生效；期间改回去就撤销。')
    return lines.join('\n') + '\n'
  }
  for (const k of keys) {
    const p = pending[k]
    lines.push('- **' + RULE_LABEL[k] + '** → ' + showValue(k, p.value) + '：' + fmtDay(Number(p.since) + cooldownMs) + ' 起生效')
  }
  lines.push('', '想撤销就把 规则.md 里那一项改回去。')
  return lines.join('\n') + '\n'
}

// ---------------------------------------------------------------- 任务笔记与审计日志

function safeName(s) {
  return String(s || '').replace(/[\\/:*?"<>|#^\[\]\n\r]+/g, ' ').trim().slice(0, 40) || '任务'
}

export function taskNotePath(kb, day, index, task) {
  return join(kb, TASK_DIR, day + '-' + (index + 1) + '-' + safeName(task) + '.md')
}

/** 任务笔记：frontmatter 是给 Obsidian Bases 看的属性，正文给人看。 */
export function renderTaskNote({ day, task, evidence, status, proof }) {
  const q = (v) => JSON.stringify(String(v || ''))
  return [
    '---',
    '状态: ' + q(status || '进行中'),
    '日期: ' + day,
    '完成物: ' + q(evidence),
    '证据: ' + q(proof),
    'tags: [大肥鱼/任务]',
    '---',
    '',
    '# ' + task,
    '',
    '完成物：' + (evidence || '（没写）'),
    '',
    proof ? '墙的核对：' + proof : '墙还没核对。',
    '',
  ].join('\n')
}

export function writeText(path, text) {
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, text)
}

export function readText(path) {
  try {
    return readFileSync(path, 'utf8')
  } catch {
    return null
  }
}

/** 墙的原话追加进当天的审计文件。when 是当地时间（如 "14:05"）。 */
export function appendAudit(kb, day, when, text) {
  const path = join(kb, WALL_DIR, AUDIT_DIR, day + '.md')
  mkdirSync(dirname(path), { recursive: true })
  const head = existsSync(path) ? '' : '# 墙的审计 · ' + day + '\n'
  appendFileSync(path, head + '\n## ' + when + '\n\n' + String(text).trim() + '\n')
  return path
}
