// A3：墙说的话。
//
// 墙不是第三个人格（见 wall_auditor.js 头注释），所以它不聊天、不带语气、不接话：
// 每句话都是「要求 X，实测 Y，差 Z」这种格式。判定码在 wall_auditor.js 的 `AUDIT_CODES` 里，
// 这里一个码配一句。`AUDIT_CODES` 加了新码而这里没配，`verdictVoice` 会抛——测试遍历那张表，缺一条就失败。
//
// 这个文件不调用任何模型，也不读任何外部数据：进来的是码和数字，出去的是话。

import { AUDIT_CODES } from './wall_auditor.js'

/** 申诉：每天最多几次。墙不改主意，只重算一次——所以这个数很小。 */
export const APPEAL_MAX_PER_DAY = 2

/** 一个判定码一句话。占位符是 `auditTask` 返回的 `vars` 里的字段（`signedGrew` 由这里拼）。 */
export const VOICE_TEMPLATES = Object.freeze({
  'file:pass': '通过。要求：文件增长 ≥ {min} 字节。实测：{signedGrew} 字节。',
  'file:short': '未通过。要求：文件增长 ≥ {min} 字节。实测：{signedGrew} 字节。差 {diff}。',
  'file:grew0': '未通过。要求：文件增长 ≥ {min} 字节。实测：{signedGrew} 字节。',
  'file:no-file': '未通过。要求：{path} 存在。实测：不存在。',
  'file:stale': '未通过。要求：立项后写入 {path}。实测：立项后没动过。',
  'screenshot:pass': '通过。要求：立项后新截图 ≥ 1 张。实测：{n} 张。',
  'screenshot:none': '未通过。要求：立项后新截图 ≥ 1 张。实测：0 张。',
  'app:pass': '通过。要求：{label} {need} 分钟。实测：{got} 分钟。',
  'app:short': '未通过。要求：{label} {need} 分钟。实测：{got} 分钟。差 {diff}。',
  'app:not-found': '未通过。要求：{label} {need} 分钟。实测：使用记录里没有它。',
  'app:zero': '未通过。要求：{label} {need} 分钟。实测：0 分钟。',
  'none:unverifiable': '未通过。要求：完成物是墙查得到的类型。实测：{evidence} 查不了。',
})

const signed = (n) => (Number(n) >= 0 ? '+' : '') + (Number(n) || 0)

/**
 * 判定码 → 一句话。未知的码直接抛，不返回兜底文本——
 * 兜底会让「新加了一个分支但没配话」悄悄溜到用户面前。
 */
export function verdictVoice(code, vars = {}) {
  const t = VOICE_TEMPLATES[code]
  if (!t) throw new Error('wall_voice 没有这个判定码的模板：' + code)
  const view = { ...vars, signedGrew: signed(vars.grew) }
  return t.replace(/\{(\w+)\}/g, (m, k) => (view[k] === undefined || view[k] === null ? m : String(view[k])))
}

/** 三种气泡。界面上按这个配色/排版，不让墙和提交混在一起。 */
export const BUBBLE_KINDS = Object.freeze(['submit', 'verdict', 'rule'])

/**
 * 规则变更那一句：谁改了什么、什么时候生效。
 * 改严立即生效，改松要过冷却期（vault_wall.js 已经算好 due/pending，这里只负责说）。
 */
export function ruleVoice({ who = '他', label, from, to, effect } = {}) {
  const head = '规则变更。' + who + '：' + (label || '（没说哪一项）')
  const body = from === undefined && to === undefined ? '' : '，' + show(from) + ' → ' + show(to)
  return head + body + '。' + (effect === 'pending' ? '改松，冷却期内不生效。' : '立即生效。')
}

function show(v) {
  if (v === undefined || v === null) return '（无）'
  if (v === true) return '开'
  if (v === false) return '锁'
  if (Array.isArray(v)) return v.length ? v.join('、') : '（不留）'
  return String(v)
}

/**
 * 申诉的结论。申诉不改判定，只允许「同一条判定 + 新证据」重算一次。
 * 所以这三句话都不是商量的语气。
 */
export const APPEAL_CODES = Object.freeze(['appeal:accepted', 'appeal:no-new-evidence', 'appeal:no-quota', 'appeal:not-verdict'])

export const APPEAL_VOICE = Object.freeze({
  'appeal:accepted': '重算过了。新证据成立，这一条改了。',
  'appeal:no-new-evidence': '驳回。申诉只收新的物理证据；你把上次那份又说了一遍。',
  'appeal:no-quota': '驳回。今天申诉用完了（' + APPEAL_MAX_PER_DAY + ' 次）。',
  'appeal:not-verdict': '驳回。申诉只能针对一条具体判定。',
})

export function appealVoice(code) {
  const t = APPEAL_VOICE[code]
  if (!t) throw new Error('wall_voice 没有这个申诉结论的模板：' + code)
  return t
}

/**
 * 把账本记录还原成「长得像对话的审查记录」。
 * 墙只输出判定和依据，所以气泡只有三种：提交、判定、规则变更。
 *
 * @param {object[]} entries 账本记录（readLedger 的返回）
 * @param {{days?:number, limit?:number, now?:number}} opts 只取最近若干天的
 * @returns {{kinds:string[], bubbles:{kind:string, at:number, who:string, text:string, code?:string}[]}}
 */
export function wallThread(entries, { days = 14, limit = 60, now = Date.now() } = {}) {
  const since = Number(now) - Math.max(1, days) * 86_400_000
  const out = []
  for (const e of Array.isArray(entries) ? entries : []) {
    const at = Number(e?.at) || 0
    if (!at || at < since) continue
    if (e.state === 'wall-audit') {
      // 他（或者姐妹代交）交上来的一份完成物，墙回一句判定。
      out.push({ kind: 'submit', at, who: String(e.by || '他'), text: String(e.kind || '（没说哪件事）') })
      out.push({
        kind: 'verdict', at, who: '墙',
        text: e.pass ? '通过。' : '未通过。' + (Array.isArray(e.missing) ? String(e.missing[0] || '') : ''),
        code: e.pass ? 'file:pass' : undefined,
        pass: Boolean(e.pass),
      })
      continue
    }
    if (e.state === 'verdict') {
      out.push({
        kind: 'verdict', at, who: '墙',
        text: String(e.summary || (e.satisfied ? '过。' : '不过。')), pass: Boolean(e.satisfied),
      })
      continue
    }
    if (e.state === 'rule-change') {
      out.push({ kind: 'rule', at, who: String(e.by || '他'), text: ruleVoice(e) })
    }
  }
  // 倒序：最新的在最上面（界面不用自己翻）。
  return { kinds: BUBBLE_KINDS, bubbles: out.sort((a, b) => b.at - a.at).slice(0, Math.max(1, limit)) }
}

/**
 * 申诉额度：今天还可以申诉几次。
 * 只数今天的 `appeal` 记录，不数判定本身——被判一百次也只能申诉两天两次里的那两次。
 */
export function appealLeft(entries, todayStart) {
  const n = (Array.isArray(entries) ? entries : []).filter((e) => e?.state === 'appeal' && Number(e.at) >= Number(todayStart)).length
  return Math.max(0, APPEAL_MAX_PER_DAY - n)
}

export { AUDIT_CODES }
