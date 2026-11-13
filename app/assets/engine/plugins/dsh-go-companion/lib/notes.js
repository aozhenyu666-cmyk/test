// dsh-go-companion · 知识闭环（笔记库里的 大肥鱼/我.md、大肥鱼/判断.md）+ 未完成笔记追杀
//
// 我.md   —— 他写的：这一阶段的目标、每天的硬安排、红线（什么时候必须锁我 / 必须放我）、希望她们怎么对他。
//            两人每一轮都读它的摘要，以它为准。还没有时，鲸澜起草一版（只在文件不存在时能写），他来改。
// 判断.md —— 鲸澜写的：她对他的判断和证据（几点最容易滑、哪句话说了等于没说……）。每晚对账后改一次，
//            每次最多 5 条变化。他可以直接改、删，她下次以他改过的为准。
// 写权限：她们只能写 大肥鱼/ 和 Gemini/；别的目录只读。日记 / 私密目录连读都不读。
// 冲突时：他写的 > 他最近亲口说的 > 记忆库 > 她们的推断。

import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { dayString } from './daily.js'
import { hm, ymd } from './tz.js'
import { AUDIT_DIR, WALL_DIR } from './vault_wall.js'

export const SELF_FILE = '我.md'
export const JUDGE_FILE = '判断.md'
export const KB_DIR = '大肥鱼'
/** 单行化 + 截断；裁决/任务文本统一走这里。 */
const clean = (s, max) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim().slice(0, max)
/** 不读的目录（名字里含这些就跳过）。 */
export const PRIVATE_DIRS = ['日记', 'Diary', 'diary', 'Journal', 'journal', '私密', 'Private', 'private', '.obsidian', '.trash']

/** 知识库目录：笔记库/大肥鱼；没有笔记库就放在 DSH 自己的目录里。 */
export function kbDir(notesDir, home) {
  return notesDir ? join(notesDir, KB_DIR) : join(home, 'companion', KB_DIR)
}

function readText(file, max = 4000) {
  try {
    return readFileSync(file, 'utf8').slice(0, max)
  } catch {
    return ''
  }
}

/** 我.md 的摘要（进每一轮人设）：去掉空行和 front-matter，限 1200 字。 */
export function selfDigest(dir) {
  let t = readText(join(dir, SELF_FILE), 6000)
  if (!t.trim()) return ''
  t = t.replace(/^---[\s\S]*?---\s*/, '')
  return t.split('\n').map((l) => l.trimEnd()).filter((l) => l.trim()).join('\n').slice(0, 1200)
}

/** 判断.md 的摘要（进鲸澜每一轮，妹妹只拿前几条）。 */
export function judgeDigest(dir, max = 800) {
  const t = readText(join(dir, JUDGE_FILE), 6000).replace(/^---[\s\S]*?---\s*/, '')
  return t.split('\n').filter((l) => l.trim()).join('\n').slice(0, max)
}

/** 拼进两人的聊天人设。 */
export function knowledgeLines(dir, role) {
  const self = selfDigest(dir)
  const judge = judgeDigest(dir, role === 'strategist' ? 800 : 300)
  const lines = []
  if (self) {
    lines.push('他自己写的规则（' + KB_DIR + '/' + SELF_FILE + '，以它为准；跟别的说法冲突时：他写的 > 他最近亲口说的 > 记忆 > 你们的推断）：', self)
  } else {
    lines.push('他还没写 ' + KB_DIR + '/' + SELF_FILE + '（他的目标、硬安排、红线、希望你们怎么对他）。' +
      (role === 'strategist' ? '找个他不忙的时候，用 kb_write file=self 按你知道的起草一版，跟他说一声让他改；文件已经存在就不能覆盖。' : '提醒姐姐去起草，或者让他自己写。'))
  }
  if (judge) lines.push((role === 'strategist' ? '你之前写下的判断（' : '姐姐写下的对他的判断（') + KB_DIR + '/' + JUDGE_FILE + '，他可能改过，以文件为准）：', judge)
  lines.push('笔记库的规矩：你们只能写 ' + KB_DIR + '/ 和 Gemini/；别的目录只读，绝不改；日记、私密目录不读。读屏和相册看到的东西只用来当下说一句，不记进记忆或笔记。')
  return lines
}

/**
 * 写知识库：self（我.md，只在不存在时起草）/ judge（判断.md，整份替换，每次最多 5 条变化由人设约束）。
 * @returns {{ok:boolean, text:string, file?:string}}
 */
export function kbWrite(dir, file, markdown, now = Date.now()) {
  const body = String(markdown || '').trim()
  if (!body) return { ok: false, text: '内容是空的。' }
  if (body.length > 8000) return { ok: false, text: '太长了，8000 字以内。' }
  const name = file === 'self' ? SELF_FILE : file === 'judge' ? JUDGE_FILE : ''
  if (!name) return { ok: false, text: 'file 只能是 self 或 judge。' }
  const path = join(dir, name)
  if (name === SELF_FILE && existsSync(path) && readText(path).trim()) return { ok: false, text: SELF_FILE + ' 已经是他写的了，你不能覆盖；想改哪条就跟他说。' }
  try {
    mkdirSync(dir, { recursive: true })
    const stamp = '<!-- ' + (name === SELF_FILE ? '鲸澜起草，等他改' : '鲸澜更新') + ' · ' + ymd(now) + ' ' + hm(now) + ' -->\n'
    writeFileSync(path, stamp + body + '\n', 'utf8')
    return { ok: true, text: '写好了：' + path, file: path }
  } catch (error) {
    return { ok: false, text: '写不进去：' + String((error && error.message) || error) }
  }
}

// ---------------------------------------------------------------- 未完成笔记追杀

const MARK = /#待思考|#待想|#todo|TODO|- \[ \]/i

/** 一篇笔记像不像写了一半：结尾没有句号类标点，或者最后一行是列表项 / 标题。 */
function looksUnfinished(text) {
  const t = text.trimEnd()
  if (t.length < 80) return false
  const last = t.split('\n').pop().trim()
  if (/^#{1,6}\s/.test(last) || /^[-*]\s*$/.test(last)) return true
  return !/[。！？.!?）)」』"”…]$/.test(last)
}

/**
 * 扫笔记库，找值得追问的笔记：带 #待思考 / TODO / 未勾选的待办，或者两天到两周没动、明显写了一半的。
 * 跳过 大肥鱼/、Gemini/、日记私密目录。最多扫 600 个文件、深 5 层，免得卡住。
 * @returns {{path:string, rel:string, mtime:number, why:string, excerpt:string}[]}
 */
export function scanUnfinished(notesDir, { now = Date.now(), asked = [], limit = 600 } = {}) {
  if (!notesDir) return []
  const out = []
  let seen = 0
  const skip = new Set(asked)
  const walk = (dir, depth) => {
    if (depth > 5 || seen > limit) return
    let names = []
    try {
      names = readdirSync(dir)
    } catch {
      return
    }
    for (const name of names) {
      if (seen > limit) return
      if (name.startsWith('.') || PRIVATE_DIRS.some((p) => name.includes(p))) continue
      const path = join(dir, name)
      let st
      try {
        st = statSync(path)
      } catch {
        continue
      }
      if (st.isDirectory()) {
        if (depth === 0 && (name === KB_DIR || name === 'Gemini')) continue
        walk(path, depth + 1)
        continue
      }
      if (!name.endsWith('.md')) continue
      seen += 1
      const age = now - st.mtimeMs
      if (age > 30 * 86_400_000 || skip.has(path)) continue
      const text = readText(path, 20_000)
      const marked = MARK.test(text)
      const stale = age >= 2 * 86_400_000 && age <= 14 * 86_400_000 && looksUnfinished(text)
      if (!marked && !stale) continue
      const at = marked ? text.search(MARK) : Math.max(0, text.trimEnd().length - 300)
      out.push({
        path,
        rel: relative(notesDir, path),
        mtime: st.mtimeMs,
        why: marked ? '标了待思考 / 有没做的待办' : Math.round(age / 86_400_000) + ' 天没动、像是写了一半',
        excerpt: text.slice(Math.max(0, at - 120), at + 300).replace(/\s+/g, ' ').trim(),
      })
    }
  }
  walk(notesDir, 0)
  // 带标记的优先，其次越久没动越先追。
  out.sort((a, b) => (MARK.test(b.excerpt) - MARK.test(a.excerpt)) || a.mtime - b.mtime)
  return out.slice(0, 5)
}

// ---------------------------------------------------------------- D3 全库只读检索 / 裁决同步
//
// 第三轮：知识库不只是 大肥鱼/，而是他整个 Obsidian 库（只读）。发球前取最近改过的笔记、
// 长期规划和当天任务；裁决按账本增量写回 大肥鱼/每日/。日记、私密目录仍然不读。

const VAULT_MAX_DEPTH = 6
const VAULT_MAX_FILES = 800
const VAULT_READ_BYTES = 60_000

function isPrivateName(name) {
  return name.startsWith('.') || PRIVATE_DIRS.some((p) => name.includes(p))
}

/** 走一遍笔记库，只收 .md；跳过私密目录、隐藏文件、符号链接。 */
function walkVault(root, { maxDepth = VAULT_MAX_DEPTH, maxFiles = VAULT_MAX_FILES } = {}) {
  const out = []
  let seen = 0
  const walk = (dir, depth) => {
    if (depth > maxDepth || seen >= maxFiles) return
    let entries = []
    try {
      entries = readdirSync(dir, { withFileTypes: true })
    } catch {
      return
    }
    for (const e of entries) {
      if (seen >= maxFiles) return
      if (isPrivateName(e.name)) continue
      const abs = join(dir, e.name)
      if (e.isDirectory()) {
        walk(abs, depth + 1)
        continue
      }
      if (e.isSymbolicLink() || !e.isFile() || !e.name.endsWith('.md')) continue
      let st
      try {
        st = statSync(abs)
      } catch {
        continue
      }
      seen += 1
      out.push({ path: abs, rel: relative(root, abs).split('\\').join('/'), mtime: st.mtimeMs })
    }
  }
  walk(root, 0)
  return out
}

function stripMarkdown(text, max) {
  const body = String(text || '')
    .replace(/^---[\s\S]*?---\s*/, '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/[#>*`_~]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return body.slice(0, max)
}

/** 最近改过的笔记（摘要只读，不含日记/私密）。 */
export function vaultRecentNotes(notesDir, { limit = 3, now = Date.now(), exclude = [] } = {}) {
  if (!notesDir) return []
  const skip = new Set(exclude)
  // 墙自己的审计日志也在这里写（vaultAudit）。它是系统产出，不是他写的话，
  // 混进"最近改过的"会把真笔记挤下去 —— 球就会拿一条审计记录当话题素材。
  const auditPrefix = KB_DIR + '/' + WALL_DIR + '/' + AUDIT_DIR + '/'
  return walkVault(notesDir)
    .filter((f) => !f.rel.startsWith(KB_DIR + '/每日/') && !f.rel.startsWith(auditPrefix) && f.rel !== KB_DIR + '/记忆.md' && f.rel !== KB_DIR + '/长期规划.md' && !skip.has(f.rel))
    .sort((a, b) => b.mtime - a.mtime)
    .slice(0, Math.max(1, Math.min(8, limit)))
    .map((f) => ({ rel: f.rel, mtime: f.mtime, summary: stripMarkdown(readText(f.path, VAULT_READ_BYTES), 220) }))
}

/** 全库只读搜索：文件名/路径命中优先，其次正文命中。 */
export function vaultSearch(notesDir, query, { limit = 8 } = {}) {
  const q = String(query || '').replace(/\s+/g, ' ').trim().slice(0, 80)
  if (!notesDir || !q) return []
  const needle = q.toLowerCase()
  const tokens = needle.split(' ').filter((t) => t.length >= 2)
  const out = []
  for (const f of walkVault(notesDir)) {
    const text = readText(f.path, VAULT_READ_BYTES)
    const lower = text.toLowerCase()
    const relLower = f.rel.toLowerCase()
    const pathHit = relLower.includes(needle)
    const bodyHit = lower.includes(needle)
    const tokenHit = tokens.length > 0 && tokens.every((t) => lower.includes(t))
    if (!pathHit && !bodyHit && !tokenHit) continue
    const at = bodyHit ? lower.indexOf(needle) : tokenHit ? Math.max(0, lower.indexOf(tokens[0])) : -1
    const snippet = at >= 0 ? stripMarkdown(text.slice(Math.max(0, at - 100), at + 220), 220) : stripMarkdown(text, 180)
    const count = bodyHit ? lower.split(needle).length - 1 : 1
    out.push({ rel: f.rel, mtime: f.mtime, snippet, score: (pathHit ? 5 : 0) + Math.min(10, count * 2) + (tokenHit ? 1 : 0) })
  }
  return out.sort((a, b) => b.score - a.score || b.mtime - a.mtime).slice(0, Math.max(1, Math.min(20, limit)))
}

/** 大肥鱼/长期规划.md 的摘要（没有就是空）。 */
export function longPlanDigest(notesDir, max = 1200) {
  if (!notesDir) return ''
  const file = join(notesDir, KB_DIR, '长期规划.md')
  if (!existsSync(file)) return ''
  return stripMarkdown(readText(file, 20_000), max)
}

function clockIn(now, tz) {
  try {
    return new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(now))
  } catch {
    return hm(now)
  }
}

function verdictTasks(v) {
  return (Array.isArray(v?.tasks) ? v.tasks : [])
    .map((t) => (typeof t === 'string' ? { task: t, evidence: '' } : { task: clean(t?.task, 120), evidence: clean(t?.evidence, 160), longTerm: t?.longTerm === true }))
    .filter((t) => t.task)
    .slice(0, 6)
}

/** 一次裁决追加进 大肥鱼/每日/日期.md（不覆盖早上写的任务和对账）。 */
export function appendVerdictDaily(kb, now, tz, v) {
  const day = dayString(now, tz)
  const file = join(kb, '每日', day + '.md')
  const satisfied = v?.satisfied === true
  const kind = String(v?.kind || 'verdict').slice(0, 30)
  const lines = ['', '## 裁决 ' + clockIn(now, tz) + ' · ' + kind, '', '- ' + (satisfied ? '满意' : '不满意')]
  const summary = clean(v?.summary, 600)
  const next = clean(v?.nextStep ?? v?.next_step, 300)
  if (summary) lines.push('- 结论：' + summary)
  if (next) lines.push('- 下一步：' + next)
  const tasks = verdictTasks(v)
  if (tasks.length) {
    lines.push('- 任务：')
    for (const t of tasks) lines.push('  - ' + t.task + (t.evidence ? '（完成物：' + t.evidence + '）' : ''))
  }
  try {
    mkdirSync(join(kb, '每日'), { recursive: true })
    if (!existsSync(file)) writeFileSync(file, '# ' + day + ' · 今日\n\n> 早上的事和完成物、晚上的对账、两姐妹的裁决，都写在这里。\n', 'utf8')
    appendFileSync(file, lines.join('\n') + '\n', 'utf8')
    return file
  } catch {
    return ''
  }
}

/** 涉及长期目标的裁决追加到 大肥鱼/长期规划.md。 */
export function appendLongTerm(kb, now, tz, v) {
  const file = join(kb, '长期规划.md')
  const day = dayString(now, tz)
  const summary = clean(v?.summary, 600)
  const next = clean(v?.nextStep ?? v?.next_step, 300)
  const tasks = verdictTasks(v)
  const lines = ['', '## ' + day + ' · ' + String(v?.kind || '裁决'), '']
  if (summary) lines.push('- 结论：' + summary)
  if (next) lines.push('- 下一步：' + next)
  // 整条裁决标了长期：里面的事都算长期目标，不只逐条的 longTerm。
  const longAll = v?.longTerm === true
  for (const t of tasks) if (longAll || t.longTerm) lines.push('- 长期：' + t.task + (t.evidence ? '（完成物：' + t.evidence + '）' : ''))
  try {
    mkdirSync(kb, { recursive: true })
    if (!existsSync(file)) writeFileSync(file, '# 长期规划\n\n> 鲸澜把涉及长期目标的裁决追加在这里；用户可以随时改。\n', 'utf8')
    appendFileSync(file, lines.join('\n') + '\n', 'utf8')
    return file
  } catch {
    return ''
  }
}

/**
 * 账本增量同步：只处理 state=verdict 且 at 比上次新的条目。
 * @returns {{state:{lastVerdictAt:number}, count:number}}
 */
export function syncVerdicts({ kb, entries, now = Date.now(), tz = 'Asia/Shanghai', state = {} } = {}) {
  const next = { ...(state || {}) }
  const last = Number(next.lastVerdictAt) || 0
  const rows = (Array.isArray(entries) ? entries : [])
    .filter((e) => e && e.state === 'verdict' && Number(e.at) > last)
    .sort((a, b) => Number(a.at) - Number(b.at))
  if (!kb || !rows.length) return { state: next, count: 0 }
  for (const v of rows) {
    appendVerdictDaily(kb, Number(v.at) || now, tz, v)
    const long = v.longTerm === true || verdictTasks(v).some((t) => t.longTerm)
    if (long) appendLongTerm(kb, Number(v.at) || now, tz, v)
  }
  next.lastVerdictAt = Number(rows[rows.length - 1].at) || last
  return { state: next, count: rows.length }
}
