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
export const IMPORT_DIR = '导入'
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

/** 拼进两人的聊天人设。兼容 Cognitive OS 六区知识库（00_Index / 03_Thinking_Models / 04 / 05 / 06 / 02）。 */
export function knowledgeLines(dir, role, opts = {}) {
  return knowledgeBlocks(dir, role, opts).map((b) => b.text)
}

/**
 * 拆成独立小块进预算器（2026-11）：防止单一 1000+ 字大块被 fitBudget 整块丢弃。
 * 返回顺序按重要性：kb-self（12）> kb-cog（10）> kb-judge（8）> kb-rule（5）。
 */
export function knowledgeBlocks(dir, role, opts = {}) {
  const self = selfDigest(dir)
  const judge = judgeDigest(dir, role === 'strategist' ? 650 : 280)
  const blocks = []
  if (self) {
    blocks.push({
      id: 'kb-self',
      priority: 12,
      text: '他自己写的规则（' + KB_DIR + '/' + SELF_FILE + '，冲突时以它为准）：\n' + self.slice(0, 750),
    })
  } else {
    blocks.push({
      id: 'kb-self',
      priority: 12,
      text: '他还没写 ' + KB_DIR + '/' + SELF_FILE + '（目标、红线、希望怎么对他）。' +
        (role === 'strategist' ? '抽空用 kb_write file=self 起草一版让他改。' : '提醒姐姐起草或让他自己写。'),
    })
  }
  const cogDir = typeof opts?.notesDir === 'string' ? opts.notesDir.trim() : ''
  if (cogDir) {
    const cog = cognitiveOsDigest(cogDir, {
      maxPerZone: role === 'strategist' ? 4 : 2,
      limitChars: role === 'strategist' ? 750 : 380,
    })
    if (cog.present && cog.text) {
      blocks.push({
        id: 'kb-cog',
        priority: 10,
        text: 'Cognitive OS 体系（先查旧账、实然优先、侦察先于开方、不篡改 01_Inbox_Raw）：\n' + cog.text,
      })
    }
  }
  if (judge) {
    blocks.push({
      id: 'kb-judge',
      priority: 8,
      text: (role === 'strategist' ? '你之前写下的判断（' : '姐姐写下的对他的判断（') + KB_DIR + '/' + JUDGE_FILE + '）：\n' + judge,
    })
  }
  blocks.push({
    id: 'kb-rule',
    priority: 5,
    text: '笔记库边界：仅可写 ' + KB_DIR + '/、Gemini/ 与追加 01_Inbox_Raw/；其余只读，日记/私密目录不读。',
  })
  return blocks
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

function isSystemVaultFile(rel) {
  const base = rel.split('/').pop() || ''
  return rel.startsWith(KB_DIR + '/每日/') ||
    rel.startsWith(KB_DIR + '/' + WALL_DIR + '/') ||
    rel.startsWith(KB_DIR + '/' + IMPORT_DIR + '/') ||
    rel === KB_DIR + '/记忆.md' ||
    rel === KB_DIR + '/长期规划.md' ||
    rel === KB_DIR + '/' + SELF_FILE ||
    rel === KB_DIR + '/' + JUDGE_FILE ||
    rel === KB_DIR + '/思维碎记.md' ||
    rel === KB_DIR + '/AI交接快照.md' ||
    base.startsWith('_')
}

/** 最近改过的笔记（摘要只读，不含日记/私密与系统模板/墙日志）。 */
export function vaultRecentNotes(notesDir, { limit = 3, now = Date.now(), exclude = [] } = {}) {
  if (!notesDir) return []
  const skip = new Set(exclude)
  return walkVault(notesDir)
    .filter((f) => !isSystemVaultFile(f.rel) && !skip.has(f.rel))
    .sort((a, b) => b.mtime - a.mtime)
    .slice(0, Math.max(1, Math.min(8, limit)))
    .map((f) => ({ rel: f.rel, mtime: f.mtime, summary: stripMarkdown(readText(f.path, VAULT_READ_BYTES), 220) }))
}

/**
 * 从笔记库里随手翻一篇（2026-11）。她们以前只在主动发球时看「最近改过的 3 篇」，
 * 平时聊天里笔记库是空的。这里按 seed 稳定地挑一篇：一半概率挑最近两周的，一半挑更老的，
 * 像人一样偶尔想起很久以前写过的东西。系统产出（每日、审计、记忆、长期规划、模板导入）不挑；正文太短的不挑。
 * @returns {{rel:string, mtime:number, excerpt:string}|null}
 */
export function vaultPick(notesDir, { seed = 0, now = Date.now(), exclude = [] } = {}) {
  if (!notesDir) return null
  const skip = new Set(exclude)
  const files = walkVault(notesDir).filter((f) => !isSystemVaultFile(f.rel) && !skip.has(f.rel))
  if (!files.length) return null
  const fresh = files.filter((f) => now - f.mtime < 14 * 86_400_000)
  const old = files.filter((f) => now - f.mtime >= 14 * 86_400_000)
  const r = Math.abs(Math.floor(Number(seed) || 0))
  const pool = (r % 2 === 0 && fresh.length) || !old.length ? fresh.length ? fresh : files : old
  for (let i = 0; i < Math.min(pool.length, 6); i++) {
    const f = pool[(r + i * 7) % pool.length]
    const excerpt = stripMarkdown(readText(f.path, VAULT_READ_BYTES), 240)
    if (excerpt.length >= 30) return { rel: f.rel, mtime: f.mtime, excerpt }
  }
  return null
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

/** 构造标准 Obsidian YAML frontmatter。 */
export function renderFrontmatter(meta = {}) {
  const lines = ['---']
  for (const [k, v] of Object.entries(meta || {})) {
    if (v === undefined || v === null || v === '') continue
    if (Array.isArray(v)) {
      lines.push(`${k}: [${v.map((x) => `"${String(x).replace(/"/g, '\\"')}"`).join(', ')}]`)
    } else if (typeof v === 'boolean' || typeof v === 'number') {
      lines.push(`${k}: ${v}`)
    } else {
      lines.push(`${k}: "${String(v).replace(/"/g, '\\"')}"`)
    }
  }
  lines.push('---', '')
  return lines.join('\n')
}

/** 构造 Obsidian [[WikiLink]]，必要时带显示别名。 */
export function wikiLink(target, alias = '') {
  const t = String(target || '').trim().replace(/\.md$/i, '')
  if (!t) return ''
  const a = String(alias || '').trim()
  return a && a !== t ? `[[${t}|${a}]]` : `[[${t}]]`
}

/**
 * 写入一张标准 Obsidian 认知/裁决卡片（带 YAML frontmatter + [[WikiLinks]]），
 * 并把链接追加进当日日志里，保证图谱不断链。
 */
export function writeObsidianCard({ kb, folder = 'cards', slug, title, tags = [], department = '', links = [], body = '', at = Date.now(), tz = 'Asia/Shanghai' } = {}) {
  if (!kb || !title) return ''
  const day = dayString(at, tz)
  const safeSlug = String(slug || `${day}-${String(title)}`).replace(/[\\/:*?"<>|\s]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 64) || day
  const dir = join(kb, folder)
  const file = join(dir, `${safeSlug}.md`)
  const frontmatter = renderFrontmatter({
    title,
    date: day,
    department: department || undefined,
    tags: tags.length ? tags : ['dafeiyu'],
  })
  const linkLine = links.length ? `\n关联：${links.map((l) => wikiLink(l)).filter(Boolean).join(' · ')}\n` : ''
  const content = `${frontmatter}# ${title}\n\n${String(body || '').trim()}\n${linkLine}`
  try {
    mkdirSync(dir, { recursive: true })
    writeFileSync(file, content, 'utf8')
    const daily = join(kb, `${day}.md`)
    if (!existsSync(daily)) writeFileSync(daily, `# ${day}\n\n`, 'utf8')
    const ref = wikiLink(`${folder}/${safeSlug}`, title)
    const cur = readFileSync(daily, 'utf8')
    if (ref && !cur.includes(ref)) appendFileSync(daily, `- 卡片：${ref}\n`, 'utf8')
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
    const summary = clean(v?.summary, 120)
    if (summary) {
      writeObsidianCard({
        kb,
        folder: 'verdicts',
        slug: `${dayString(Number(v.at) || now, tz)}-${String(v?.boutId || v?.id || rows.indexOf(v))}`,
        title: `${String(v?.kind || '裁决')} · ${summary.slice(0, 40)}`,
        tags: ['verdict', long ? 'long-term' : 'daily'],
        department: String(v?.department || ''),
        links: ['长期规划', dayString(Number(v.at) || now, tz)],
        body: summary + (v?.nextStep || v?.next_step ? `\n\n- 下一步：${clean(v?.nextStep ?? v?.next_step, 300)}` : ''),
        at: Number(v.at) || now,
        tz,
      })
    }
  }
  next.lastVerdictAt = Number(rows[rows.length - 1].at) || last
  return { state: next, count: rows.length }
}

/**
 * Stage 3（第 5 面骰「笔记联结」）自动召回候选 Obsidian `[[WikiLink]]` 笔记。
 * 先按 stage3Note / query 做关键词检出，不足则用最近笔记补齐，去重后最多返回 limit（默认 3）条。
 * 路径缺失或为空时静默返回 []。
 */
export function candidateNotesForLinking(notesDir, { stage3Note = '', query = '', limit = 3 } = {}) {
  const cap = Math.max(1, Math.min(8, Math.round(Number(limit) || 3)))
  const rawQuery = String(query || stage3Note || '').trim()
  const keywords = rawQuery
    .replace(/[#>*`\[\]()]/g, ' ')
    .split(/[\s,，。；;、/|]+/)
    .map((s) => s.trim())
    .filter((s) => s.length >= 2)
    .slice(0, 6)
  const seen = new Set()
  const picks = []
  const pushHit = (hit) => {
    if (!hit || picks.length >= cap) return
    const rel = String(hit.rel || hit.path || '').trim()
    const key = String(rel || hit.title || '').trim()
    if (!key || seen.has(key)) return
    seen.add(key)
    const rawTitle = String(hit.title || rel.split(/[\\/]/).pop() || key).replace(/\.md$/i, '').trim()
    picks.push({
      id: key,
      title: rawTitle,
      path: rel,
      snippet: String(hit.snippet || hit.summary || hit.excerpt || '').slice(0, 140),
      wikilink: wikiLink(rawTitle),
    })
  }
  if (rawQuery) {
    for (const h of vaultSearch(notesDir, keywords.join(' ') || rawQuery, { limit: cap * 2 })) pushHit(h)
  }
  if (picks.length < cap) {
    for (const h of vaultRecentNotes(notesDir, { limit: cap * 2 })) pushHit(h)
  }
  return picks.slice(0, cap)
}

/**
 * 第六拍入网立规：写入主笔记前向 [[WikiLink]]，并双向回写反链至候选笔记文件（最多 3 篇，幂等不重复写入，跳过私密目录）。
 */
export function writeBidirectionalWikiLinks({
  notesDir,
  kb = '',
  noteTitle = '',
  noteSlug = '',
  body = '',
  candidateNotes = [],
  department = 'dept:strategize',
  at = Date.now(),
  tz = 'Asia/Shanghai',
} = {}) {
  const title = String(noteTitle || '').trim()
  if (!notesDir || !title) return { notePath: '', backlinkedFiles: [] }
  const targetKb = kb || join(notesDir, KB_DIR)
  const picks = (Array.isArray(candidateNotes) && candidateNotes.length
    ? candidateNotes
    : candidateNotesForLinking(notesDir, { query: `${title} ${body}`, limit: 3 })
  ).slice(0, 3)
  const linkTargets = picks.map((c) => c.title || c.path || c.id).filter(Boolean)
  const notePath = writeObsidianCard({
    kb: targetKb,
    folder: 'cards',
    slug: noteSlug || undefined,
    title,
    tags: ['stroke-6', 'wikilink'],
    department,
    links: linkTargets,
    body,
    at,
    tz,
  })
  const backlinkTag = wikiLink(title)
  const backlinkedFiles = []
    for (const c of picks) {
    const rawRel = String(c?.path || c?.rel || '').trim()
    if (!rawRel) continue
    if (PRIVATE_DIRS.some((p) => rawRel.includes(p))) continue
    const abs = rawRel.startsWith(notesDir) ? rawRel : join(notesDir, rawRel)
    if (!existsSync(abs)) continue
    try {
      const cur = readFileSync(abs, 'utf8')
      if (!cur.includes(backlinkTag)) {
        const prefix = cur.includes('## 双向联结') ? '' : '\n## 双向联结\n'
        appendFileSync(abs, `${prefix}- ${backlinkTag}\n`, 'utf8')
      }
      backlinkedFiles.push(abs)
    } catch {
      // skip read-only or unreadable target
    }
  }
  return { notePath, backlinkedFiles, forwardLinks: linkTargets.map((t) => wikiLink(t)) }
}

const STARTER_SELF_MD = `# 我.md（个人目标、硬安排与红线）

> 这份文件决定「鱼丸」和「鲸澜」怎么对你。**只写事实与硬规则**，你随时在 Obsidian 修改后会自动同步进她们的记忆库。

## 1. 当前阶段核心目标
- 📌 核心主线：（把你最近 1–3 个月最重要的 1~2 件事写在这里）
- 📌 每日最低交付：（例如：每天至少完成 2 局 45 分钟核心推进 / 输出 1 份可执行记录）

## 2. 每日硬作息与红线
- 📌 睡眠红线：晚上 23:30 后不开高刺激短视频/游戏，强制收工
- 📌 高危诱因：刚打开手机的前 3 分钟、切出工作软件后漫无目的点中立软件轮流刷 10 分钟
- 📌 必须锁死的软件：（写下你最容易失控的娱乐软件名称）

## 3. 对鱼丸与鲸澜的要求
- 鱼丸（妹妹）：当我卡住或摇骰子时，用轻快但具体的一句提问拉回注意力，别泛泛安慰
- 鲸澜（姐姐）：发现我在多个中立软件间来回切换凑时长时，直接戳破并把问题收窄到一个最小动作
`

const STARTER_JUDGE_MD = `# 判断.md（调度与验收偏好）

> 这里放系统与两姐妹对你的判别准则，你也可以随时增删改。

- 对「走神切换」的判断：连续在非白名单/中立软件之间高频切换超过 5 次，比单纯打开一次娱乐更值得触发六骰提问
- 对 Gemini Live 的要求：进入语音连麦前先带 1 个明确问题或六骰视角，聊完必须留下一句自己复述的结论
- 弹性豁免原则：真正必要的查资料、工具软件、紧急联系不封死，但超时必须交一句当前产出的说明
`

const STARTER_IMPORT_README_MD = `# 如何往两姐妹记忆库导入你的个人资料

把任何你想让「鱼丸」和「鲸澜」长期记住的笔记（Markdown \`.md\` 文件）放到当前目录 \`大肥鱼/导入/\` 里：

1. 每条要记住的事实写成 \`- \` 开头的列表项（最好每条 10~120 字，说清一件事）。
2. 在前面加上 \`📌 \` 的条目会作为**高优常驻核心记忆**直接进入每次对话上下文。
3. 支持你把个人经历、学习进度、错题规律、项目背景、情绪触发点分文件写（比如 \`我的项目.md\`、\`习惯规律.md\`、\`专业知识.md\`）。
4. 放好后，在 App「大肥鱼中枢 → 同步 Obsidian 记忆与模板」点一下，或者重启插件，就会自动去重写入本地 SQLite 记忆库（不会读取 \`日记/\`、\`Diary/\`、\`私密/\` 等隐私目录）。
`

const STARTER_IMPORT_MEMORY_MD = `# 个人记忆库模板（可自由复制或改写）

> 下面这些条目是给你做示范与打底的。你可以直接改掉括号里的内容，也可以在下面继续新增 \`- \` 条目。

## 个人画像与思维习惯
- 📌 我需要外置的工作记忆骨架（例如六骰提问），避免一开口就想到哪说到哪
- 我容易在「无明显任务边界的休息间隙」被本能接管注意力，越是疲惫越需要先写一句再决定要不要休息
- 和 AI 语音对话时，如果我不先给定锚点，很容易顺着 AI 的附和发散偏航

## 当前重要背景与关注主题
- 📌 我正在使用大肥鱼 + Obsidian 构建个人的行动监督与思维训练闭环
- 平时常用的深度工具是 Gemini Live 与 Obsidian，高危分散注意力场景是短视频与多软件轮切
`

/**
 * 在 kbDir（Obsidian 的 大肥鱼/ 或回退目录）生成开箱即用的个人模板数据库文件。
 * 严格幂等：只补齐缺失（或全空）的文件，绝不覆盖用户已经写过的内容。
 * @returns {{ok:boolean, kbDir:string, importDir:string, createdFiles:string[], existingFiles:string[]}}
 */
export function ensureStarterVaultFiles(kb, now = Date.now()) {
  if (!kb) return { ok: false, kbDir: '', importDir: '', createdFiles: [], existingFiles: [] }
  const importDir = join(kb, IMPORT_DIR)
  try {
    mkdirSync(importDir, { recursive: true })
  } catch {
    return { ok: false, kbDir: kb, importDir, createdFiles: [], existingFiles: [] }
  }
  const files = [
    [join(kb, SELF_FILE), STARTER_SELF_MD],
    [join(kb, JUDGE_FILE), STARTER_JUDGE_MD],
    [join(importDir, 'README_导入记忆说明.md'), STARTER_IMPORT_README_MD],
    [join(importDir, '记忆模板_个人画像与偏好.md'), STARTER_IMPORT_MEMORY_MD],
  ]
  const createdFiles = []
  const existingFiles = []
  const stamp = `<!-- 初始化时间 ${ymd(now)} -->\n`
  for (const [abs, body] of files) {
    try {
      const cur = existsSync(abs) ? readFileSync(abs, 'utf8').trim() : ''
      if (!cur) {
        writeFileSync(abs, stamp + body, 'utf8')
        createdFiles.push(abs)
      } else {
        existingFiles.push(abs)
      }
    } catch {
      // skip single file failure
    }
  }
  return { ok: true, kbDir: kb, importDir, createdFiles, existingFiles }
}

/**
 * 从 kbDir 下的 我.md、判断.md、导入/*.md、记忆*.md 提取可写入 fish-memory.sqlite 的结构化事实条目。
 * 自动跳过隐私目录、占位空行、说明文档；`📌` 或 我.md/判断.md 条目标记为高优先级常驻（pinned=1, importance=4），其余为 importance=2。
 * @returns {Array<{text:string, source:string, pinned:number, importance:number, kind:string}>}
 */
export function collectVaultMemoryFacts(kb, { limit = 160, notesDir = '' } = {}) {
  if (!kb) return []
  const sources = []
  const pushFile = (abs, priorityCore, sourcePrefix = 'obsidian') => {
    if (!existsSync(abs)) return
    const name = abs.split(/[\\/]/).pop() || ''
    if (/^README/i.test(name) || name.startsWith('_') || isPrivateName(abs)) return
    sources.push({ abs, priorityCore, name, sourcePrefix })
  }
  if (existsSync(kb)) {
    pushFile(join(kb, SELF_FILE), true)
    pushFile(join(kb, JUDGE_FILE), true)
    const importDir = join(kb, IMPORT_DIR)
    if (existsSync(importDir)) {
      try {
        for (const f of walkVault(importDir, { maxDepth: 3, maxFiles: 80 })) {
          pushFile(f.path, false)
        }
      } catch {
        // ignore
      }
    }
    try {
      for (const e of readdirSync(kb, { withFileTypes: true })) {
        if (!e.isFile() || !e.name.endsWith('.md')) continue
        if (e.name === '思维碎记.md' || (e.name.startsWith('记忆') && e.name !== '记忆.md')) {
          pushFile(join(kb, e.name), false)
        }
      }
    } catch {
      // ignore
    }
  }
  const seen = new Set()
  const out = []
  const cap = Math.max(10, Math.min(400, Number(limit) || 160))
  const pushFact = (text, source, pinned, kind) => {
    const cleanText = String(text || '').replace(/📌/g, '').replace(/\s+/g, ' ').trim().slice(0, 220)
    if (cleanText.length < 8 || /（把你最近|（写下你最/.test(cleanText)) return
    const norm = cleanText.toLowerCase()
    if (seen.has(norm) || out.length >= cap) return
    seen.add(norm)
    out.push({
      text: cleanText,
      source: String(source).slice(0, 60),
      pinned: pinned ? 1 : 0,
      importance: pinned ? 4 : 2,
      kind: kind || (pinned ? 'rule' : 'fact'),
    })
  }
  for (const src of sources) {
    const raw = readText(src.abs, 24_000).replace(/^---[\s\S]*?---\s*/, '')
    if (!raw) continue
    for (const rawLine of raw.split(/\r?\n/)) {
      if (out.length >= cap) break
      const line = rawLine.trim()
      if (!line || line.startsWith('#') || line.startsWith('>') || line.startsWith('<!--') || line.startsWith('```')) continue
      const bullet = line.match(/^(?:[-*+]|\d+[.)])\s+(.+)$/)
      if (!bullet) continue
      const hasPin = bullet[1].includes('📌')
      pushFact(bullet[1], `${src.sourcePrefix}:${src.name}`, src.priorityCore || hasPin, src.priorityCore ? 'rule' : 'fact')
    }
  }
  // 联动 Cognitive OS 六区中已验证的知识资产（不导入 02_Staging_Review 未拍板草稿，严守三层防污染）
  const cogRoot = String(notesDir || '').trim()
  if (cogRoot && existsSync(cogRoot)) {
    for (const zone of ['03_Thinking_Models', '04_People_And_Context', '05_Projects_And_Plans', '06_Decision_Ledger']) {
      const zDir = join(cogRoot, zone)
      if (!existsSync(zDir)) continue
      for (const item of readZoneCards(zDir, zone, 20)) {
        if (out.length >= cap) break
        if (item.status === 'pending_overseer' || item.status === 'template') continue
        const label = zone === '03_Thinking_Models' ? '模型' : zone === '04_People_And_Context' ? '人物' : zone === '05_Projects_And_Plans' ? '谋划' : '决策'
        if (item.summary) {
          pushFact(`[${label}·${item.title}] ${item.summary}`, `cos:${zone}`, zone === '03_Thinking_Models' || item.status === 'verified', zone === '03_Thinking_Models' ? 'model' : 'fact')
        }
      }
    }
  }
  return out
}

/** 解析 Markdown YAML Frontmatter 轻量字段（title/category/status/updated/summary）。 */
export function parseFrontmatterMeta(raw) {
  const text = String(raw || '')
  const m = text.match(/^---\r?\n([\s\S]*?)\r?\n---/)
  if (!m) return {}
  const meta = {}
  for (const line of m[1].split(/\r?\n/)) {
    const kv = line.match(/^([a-zA-Z_][a-zA-Z0-9_-]*):\s*(.+)$/)
    if (!kv) continue
    const k = kv[1].trim()
    const v = kv[2].trim().replace(/^['"]|['"]$/g, '')
    meta[k] = v
  }
  return meta
}

function readZoneCards(zoneAbsDir, zoneName, maxItems = 6) {
  if (!zoneAbsDir || !existsSync(zoneAbsDir)) return []
  const items = []
  try {
    for (const f of walkVault(zoneAbsDir, { maxDepth: 2, maxFiles: 60 })) {
      const base = f.rel.split('/').pop() || ''
      if (!base.endsWith('.md') || base.startsWith('_') || /^README/i.test(base) || isPrivateName(f.rel)) continue
      const raw = readText(f.path, 16_000)
      const meta = parseFrontmatterMeta(raw)
      if (meta.status === 'template' || meta.status === 'archived') continue
      const title = clean(meta.title || base.replace(/\.md$/i, ''), 60)
      let summary = clean(meta.summary || '', 150)
      if (!summary) {
        summary = stripMarkdown(raw, 130)
      }
      items.push({
        zone: zoneName,
        rel: `${zoneName}/${f.rel}`,
        title,
        status: clean(meta.status || 'active', 24),
        updated: clean(meta.updated || '', 20),
        summary,
        mtime: f.mtime,
      })
    }
  } catch {
    return []
  }
  items.sort((a, b) => b.mtime - a.mtime)
  return items.slice(0, maxItems)
}

export const COGNITIVE_ZONES = Object.freeze([
  '01_Inbox_Raw',
  '02_Staging_Review',
  '03_Thinking_Models',
  '04_People_And_Context',
  '05_Projects_And_Plans',
  '06_Decision_Ledger',
])

/**
 * 读取 Obsidian 里的 Cognitive OS 六区知识库结构摘要，供 大小鱼 与 外部 AI 交接上下文共用。
 */
export function cognitiveOsDigest(notesDir, { maxPerZone = 4, limitChars = 1800 } = {}) {
  const empty = { present: false, indexTitle: '', models: [], plans: [], people: [], decisions: [], pending: [], text: '' }
  if (!notesDir || !existsSync(notesDir)) return empty
  const hasZone = existsSync(join(notesDir, '03_Thinking_Models')) ||
    existsSync(join(notesDir, '02_Staging_Review')) ||
    existsSync(join(notesDir, '00_Index.md'))
  if (!hasZone) return empty

  const indexRaw = readText(join(notesDir, '00_Index.md'), 6000)
  const indexMeta = parseFrontmatterMeta(indexRaw)
  const models = readZoneCards(join(notesDir, '03_Thinking_Models'), '03_Thinking_Models', maxPerZone)
  const plans = readZoneCards(join(notesDir, '05_Projects_And_Plans'), '05_Projects_And_Plans', maxPerZone)
  const people = readZoneCards(join(notesDir, '04_People_And_Context'), '04_People_And_Context', maxPerZone)
  const decisions = readZoneCards(join(notesDir, '06_Decision_Ledger'), '06_Decision_Ledger', maxPerZone)
  const pending = readZoneCards(join(notesDir, '02_Staging_Review'), '02_Staging_Review', maxPerZone)

  const lines = []
  const pushSection = (heading, arr) => {
    if (!arr.length) return
    lines.push(heading)
    for (const it of arr) {
      lines.push(`- [${it.status}] ${it.title}${it.summary ? '：' + it.summary : ''}`)
    }
  }
  pushSection('已验证思维模型 (03_Thinking_Models):', models)
  pushSection('在推谋划与项目 (05_Projects_And_Plans):', plans)
  pushSection('人物与圈层档案 (04_People_And_Context):', people)
  pushSection('决策账本 (06_Decision_Ledger):', decisions)
  pushSection('待 Overseer 拍板草稿 (02_Staging_Review，未转正前仅供参考勿当定论):', pending)

  return {
    present: true,
    indexTitle: clean(indexMeta.title || '00_Index', 80),
    models,
    plans,
    people,
    decisions,
    pending,
    text: lines.join('\n').slice(0, limitChars),
  }
}

/** 追加一条六骰实时思考微记录到 大肥鱼/思维碎记.md，并在 Cognitive OS 存在 01_Inbox_Raw/ 时同步写入当日只增不改原始观察流。 */
export function appendDiceThought(kb, { dice = 1, name = '', thought = '' } = {}, now = Date.now(), tz = 'Asia/Shanghai', opts = {}) {
  const cleanText = clean(thought, 260)
  if (!kb || !cleanText) return ''
  let written = ''
  try {
    mkdirSync(kb, { recursive: true })
    const file = join(kb, '思维碎记.md')
    if (!existsSync(file)) {
      writeFileSync(file, '# 思维碎记（摇一摇六骰干预记录）\n\n> 每次摇骰后记下的一句真实想法，会自动同步给鱼丸与鲸澜。\n\n', 'utf8')
    }
    const day = dayString(now, tz)
    const time = hm(now, tz)
    const label = dice ? `骰${dice}·${name || '思考'}` : (name || '思考')
    appendFileSync(file, `- ${day} ${time} [${label}] ${cleanText}\n`, 'utf8')
    written = file

    const rootVault = typeof opts?.notesDir === 'string' ? opts.notesDir.trim() : ''
    const inboxDir = rootVault ? join(rootVault, '01_Inbox_Raw') : ''
    if (inboxDir && existsSync(inboxDir)) {
      const rawFile = join(inboxDir, `${day}_六骰现场碎记.md`)
      if (!existsSync(rawFile)) {
        writeFileSync(
          rawFile,
          `---\ntitle: "${day} · 手机六骰现场碎记（自动原始留档）"\ncategory: raw\nstatus: raw\ncreated: ${day}\nupdated: ${day}\ntags:\n  - 现场碎记\n  - 六骰\nsources: []\nsummary: "大肥鱼六骰悬浮窗即时捕获的现场念头与微反思原始流（只增不改）。"\n---\n\n# ${day} · 现场碎记\n\n`,
          'utf8',
        )
      }
      appendFileSync(rawFile, `- ${time} [${label}] ${cleanText}\n`, 'utf8')
    }
    return written
  } catch {
    return written
  }
}

/**
 * 生成一份 100% 自包含的「Cognitive OS × 大肥鱼」跨 AI 通用交接胶囊：
 * 既可注入 鱼丸/鲸澜 上下文，也可一键复制给 Gemini Live / Antigravity / Claude / ChatGPT 任何外部 AI 零失真接力。
 */
export function buildHandoffPacket({ kb, notesDir = '', phone = null, bout = null, memoryFacts = [], now = Date.now(), tz = 'Asia/Shanghai', writeSnapshot = true } = {}) {
  const day = dayString(now, tz)
  const time = hm(now, tz)
  const self = kb ? selfDigest(kb) : ''
  const judge = kb ? judgeDigest(kb, 700) : ''
  const cog = cognitiveOsDigest(notesDir, { maxPerZone: 5, limitChars: 1800 })
  const thoughtsRaw = kb ? readText(join(kb, '思维碎记.md'), 4000) : ''
  const recentThoughts = thoughtsRaw
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.startsWith('- '))
    .slice(-6)

  const lines = [
    `# Cognitive OS × 大肥鱼 · 跨 AI 交接上下文 (${day} ${time})`,
    '',
    '> 无论你是 **鱼丸/鲸澜（大肥鱼双姐妹）**，还是 **Gemini Live / Antigravity / ChatGPT / Claude 外部对话**：',
    '> 请严格遵守以下认知纪律与个人背景，直接接续当前现场状态，严禁输出正确废话与盲目闭环。',
    '',
    '## 1. Cognitive OS 三大底层铁律（不可违背）',
    '1. **实然优先于应然**：严禁“注意分寸/多沟通/保持自律”类正确废话；拆解高成本信号、激励机制与人性阻力。',
    '2. **侦察先于开方**：关键暗信息（筹码、底线、近期摩擦、真实动机）不明时，先抛 2–3 个观测问题或给出「若 A 则 X / 若 B 则 Y」条件分支。',
    '3. **Overseer 督导与三层防污染**：用户是最终拍板人（Overseer）；`01_Inbox_Raw/` 原始留档只增不改；待拍板判断标 `pending_overseer` 进 `02_Staging_Review/`，确认后再进 `03_Thinking_Models/`。',
    '',
    '## 2. 我的规则与红线 (`大肥鱼/我.md` & `判断.md`)',
    self || '（暂未填写 大肥鱼/我.md）',
    ...(judge ? ['', '### 判断准则', judge] : []),
    '',
    '## 3. Cognitive OS 六区知识资产',
    cog.present && cog.text ? cog.text : '（当前挂载的 Obsidian 目录暂未检测到 Cognitive OS 03_Thinking_Models/00_Index 结构，可先将 打包交接蓝图 部署至当前 Vault）',
  ]

  if (Array.isArray(memoryFacts) && memoryFacts.length) {
    lines.push('', '## 4. 长期记忆关键事实（SQLite Top 条目）')
    for (const f of memoryFacts.slice(0, 8)) {
      lines.push(`- ${clean(f, 160)}`)
    }
  }

  lines.push('', '## 5. 此刻手机现场与最新六骰思维流')
  if (bout?.title || bout?.protocolId) {
    lines.push(`- 当前正在进行的局：${bout.title || bout.protocolId}${bout.strict ? ' [严格锁]' : ''}`)
  }
  if (phone?.app) {
    lines.push(`- 最近前台应用：${phone.label || phone.app}（停留 ${Number(phone.minutes) || 0} 分钟${phone.fun ? '·娱乐' : ''}），今日屏幕累计 ${Number(phone.totalMinutes) || 0} 分钟`)
  }
  if (recentThoughts.length) {
    lines.push('- 最近六骰思考记录：', ...recentThoughts)
  } else {
    lines.push('- 最近六骰思考记录：暂无')
  }

  lines.push(
    '',
    '## 6. 给接收方 AI 的启动指令',
    '- 若是 **Gemini Live 语音切磋**：不要替我总结完，用「六骰提问（1事实 / 2推演 / 3比较 / 4关键未知 / 5依据 / 6替代解释）」每次只追问 1 个卡点，逼我自己讲透。',
    '- 若是 **深度分析 / 谋划（Antigravity / 鲸澜）**：先核对第 3 节既有模型与旧账，再给反直觉拆解与上中下三路径推演。',
  )

  const markdown = lines.join('\n').slice(0, 3800) + '\n'
  let file = ''
  if (writeSnapshot && kb) {
    try {
      mkdirSync(kb, { recursive: true })
      file = join(kb, 'AI交接快照.md')
      writeFileSync(file, markdown, 'utf8')
    } catch {
      file = ''
    }
  }
  return {
    ok: true,
    markdown,
    file,
    hasCognitiveOs: cog.present,
    modelsCount: cog.models.length,
    plansCount: cog.plans.length,
    pendingCount: cog.pending.length,
  }
}

