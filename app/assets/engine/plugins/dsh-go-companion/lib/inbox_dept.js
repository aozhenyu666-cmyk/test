// dsh-go-companion · 四部门极简收件箱纯模块（4.10）
//
// 职责：
//   1. 安全追加/读取 Obsidian 笔记库下的 大肥鱼/收件箱.md（只追加不覆盖，防路径穿越）；
//   2. 将收件箱近期记录 + 卡壳/未过保底验收点 + 到期复习摘要打包为可直接粘贴给 Gemini 谋划思考部的交接包。

import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path'
import { dayString } from './daily.js'
import { hm } from './tz.js'

export const INBOX_FILE = '大肥鱼/收件箱.md'
export const INBOX_MAX_DAYS = 14
export const INBOX_MAX_ENTRIES = 120
export const INBOX_MAX_BYTES = 16 * 1024
export const INBOX_ARCHIVE_DIR = '归档'
const INBOX_BASENAME = '收件箱.md'
const KB_FOLDER = '大肥鱼'
const HEADER = '# 大肥鱼 · 收件箱\n\n> 四部门极简收件箱：随时记一句，晚上或打开谋划思考部时一键打包。\n'

function daysBetweenYmd(a, b) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(a || '')) || !/^\d{4}-\d{2}-\d{2}$/.test(String(b || ''))) return 0
  return Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / 86_400_000)
}

function parseAllInboxEntries(raw) {
  const entries = []
  let currentDay = ''
  for (const rawLine of String(raw || '').split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line) continue
    const dayMatch = /^##\s+(\d{4}-\d{2}-\d{2})\b/.exec(line)
    if (dayMatch) {
      currentDay = dayMatch[1]
      continue
    }
    if (line.startsWith('#') || line.startsWith('>')) continue

    let m = /^-\s+(\d{2}:\d{2})\s+\[([^\]]+)\]\s+(.+)$/.exec(line)
    if (m) {
      entries.push({ day: currentDay, time: m[1], tag: m[2].trim(), text: m[3].trim(), raw: line })
      continue
    }
    m = /^-\s+(\d{2}:\d{2})\s+(.+)$/.exec(line)
    if (m) {
      entries.push({ day: currentDay, time: m[1], tag: '随记', text: m[2].trim(), raw: line })
      continue
    }
    m = /^[-*]\s+(?:\[([^\]]+)\]\s+)?(.+)$/.exec(line)
    if (m) {
      entries.push({ day: currentDay, time: '', tag: (m[1] || '随记').trim(), text: m[2].trim(), raw: line })
    }
  }
  return entries
}

function renderInboxMarkdown(entries) {
  const out = [HEADER.trimEnd()]
  let lastDay = null
  for (const e of entries) {
    const d = e.day || ''
    if (d && d !== lastDay) {
      out.push('', `## ${d}`, '')
      lastDay = d
    } else if (lastDay === null) {
      out.push('')
      lastDay = ''
    }
    out.push(e.raw)
  }
  return out.join('\n') + '\n'
}

function archiveInboxEntries(kbFolder, archived, fallbackDay) {
  if (!archived.length) return []
  const archiveDir = join(kbFolder, INBOX_ARCHIVE_DIR)
  mkdirSync(archiveDir, { recursive: true })
  const byMonth = new Map()
  for (const e of archived) {
    const day = /^\d{4}-\d{2}-\d{2}$/.test(e.day) ? e.day : fallbackDay
    const month = day.slice(0, 7)
    if (!byMonth.has(month)) byMonth.set(month, [])
    byMonth.get(month).push({ ...e, day })
  }
  const files = []
  for (const [month, list] of byMonth.entries()) {
    const archFile = join(archiveDir, `收件箱-${month}.md`)
    const header = `# 大肥鱼 · 收件箱归档（${month}）\n\n> 从 大肥鱼/收件箱.md 自动归档的历史条目。\n`
    let cur = existsSync(archFile) ? readFileSync(archFile, 'utf8') : ''
    if (!cur.trim()) cur = header
    const dayHeaders = [...cur.matchAll(/^##\s+(\d{4}-\d{2}-\d{2})\b/gm)]
    let lastDay = dayHeaders.length ? dayHeaders[dayHeaders.length - 1][1] : ''
    const chunks = []
    for (const e of list) {
      if (e.day !== lastDay) {
        chunks.push('', `## ${e.day}`, '')
        lastDay = e.day
      }
      chunks.push(e.raw)
    }
    const needLeading = cur.endsWith('\n') ? '' : '\n'
    const out = cur + needLeading + chunks.join('\n') + '\n'
    writeFileSync(archFile, out, 'utf8')
    files.push(archFile)
  }
  return files
}

/**
 * 整理并执行 大肥鱼/收件箱.md 硬上限保护：
 * - 仅保留最近 14 天、最多 120 条、活跃正文 <= 16 KB（保证在 Gemini Bridge READ_BYTES=24KB 窗内）；
 * - 超出条目按月归档到 大肥鱼/归档/收件箱-YYYY-MM.md。
 */
export function compactInbox(
  kbDir,
  {
    now = Date.now(),
    tz = 'Asia/Shanghai',
    maxDays = INBOX_MAX_DAYS,
    maxEntries = INBOX_MAX_ENTRIES,
    maxBytes = INBOX_MAX_BYTES,
  } = {},
) {
  const pathRes = resolveInboxPath(kbDir)
  if (!pathRes.ok) return pathRes
  if (!existsSync(pathRes.file)) {
    return { ok: true, changed: false, file: pathRes.file, archivedCount: 0, keptCount: 0, activeBytes: 0, archiveFiles: [] }
  }
  let raw = ''
  try {
    raw = readFileSync(pathRes.file, 'utf8')
  } catch (error) {
    return { ok: false, error: '读取收件箱失败：' + String((error && error.message) || error) }
  }

  const all = parseAllInboxEntries(raw).map((e, idx) => ({ ...e, _idx: idx }))
  const zone = String(tz || 'Asia/Shanghai').trim() || 'Asia/Shanghai'
  const ts = Number.isFinite(Number(now)) ? Number(now) : Date.now()
  const nowDay = dayString(ts, zone)
  let newestDay = nowDay
  for (const e of all) {
    if (/^\d{4}-\d{2}-\d{2}$/.test(e.day) && e.day > newestDay) newestDay = e.day
  }
  const refDay = newestDay

  const archived = []
  let kept = []
  for (const e of all) {
    if (e.day && daysBetweenYmd(e.day, refDay) > maxDays) {
      archived.push(e)
    } else {
      kept.push(e)
    }
  }

  // 若仍超过条数或字节上限，按日期时间从旧到新依次归档
  kept.sort((a, b) => {
    const ka = `${a.day || refDay} ${a.time || '00:00'}`
    const kb = `${b.day || refDay} ${b.time || '00:00'}`
    if (ka !== kb) return ka < kb ? -1 : 1
    return a._idx - b._idx
  })

  while (kept.length > maxEntries) {
    archived.push(kept.shift())
  }

  let activeMd = renderInboxMarkdown(kept)
  while (kept.length > 1 && Buffer.byteLength(activeMd, 'utf8') > maxBytes) {
    archived.push(kept.shift())
    activeMd = renderInboxMarkdown(kept)
  }

  const activeBytes = Buffer.byteLength(raw, 'utf8')
  const nextBytes = Buffer.byteLength(activeMd, 'utf8')
  if (archived.length === 0) {
    return { ok: true, changed: false, file: pathRes.file, archivedCount: 0, keptCount: kept.length, activeBytes, archiveFiles: [] }
  }

  archived.sort((a, b) => {
    const ka = `${a.day || refDay} ${a.time || '00:00'}`
    const kb = `${b.day || refDay} ${b.time || '00:00'}`
    if (ka !== kb) return ka < kb ? -1 : 1
    return a._idx - b._idx
  })

  try {
    const archiveFiles = archiveInboxEntries(dirname(pathRes.file), archived, refDay)
    writeFileSync(pathRes.file, activeMd, 'utf8')
    return {
      ok: true,
      changed: true,
      file: pathRes.file,
      archivedCount: archived.length,
      keptCount: kept.length,
      activeBytes: nextBytes,
      archiveFiles,
    }
  } catch (error) {
    return { ok: false, error: '归档收件箱失败：' + String((error && error.message) || error) }
  }
}

function clockIn(at, tz) {
  try {
    return new Intl.DateTimeFormat('en-GB', {
      timeZone: tz || 'Asia/Shanghai',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    }).format(new Date(at))
  } catch {
    return hm(at)
  }
}

/**
 * 解析并校验收件箱绝对路径：
 * - 兼容传入笔记库根目录（notesDir）或 notes.js 的 kbDir（.../大肥鱼）；
 * - 防止路径穿越或空路径写入。
 */
export function resolveInboxPath(kbDir) {
  if (typeof kbDir !== 'string') return { ok: false, error: '笔记库路径 kbDir 必须是字符串' }
  const raw = kbDir.trim()
  if (!raw || raw.includes('\0')) return { ok: false, error: '笔记库路径 kbDir 不能为空' }
  const base = resolve(raw)
  const target = basename(base) === KB_FOLDER
    ? resolve(join(base, INBOX_BASENAME))
    : resolve(join(base, INBOX_FILE))
  const rel = relative(base, target)
  if (!rel || rel.startsWith('..') || isAbsolute(rel)) {
    return { ok: false, error: '收件箱路径越界，已拒绝写入' }
  }
  return { ok: true, base, file: target }
}

/**
 * 追加一条收件箱记录（只追加不覆盖旧内容）。
 * 格式：按日期分段 `## YYYY-MM-DD`，条目为 `- HH:mm [tag] text`。
 */
export function appendInboxEntry(kbDir, { text, tag = '随记', at = Date.now(), tz = 'Asia/Shanghai' } = {}) {
  const pathRes = resolveInboxPath(kbDir)
  if (!pathRes.ok) return pathRes

  const cleanText = String(text ?? '').replace(/\r?\n+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 1000)
  if (!cleanText) return { ok: false, error: '收件箱内容不能为空' }
  const cleanTag = String(tag || '随记').replace(/[\[\]\r\n]+/g, '').replace(/\s+/g, ' ').trim().slice(0, 24) || '随记'

  const ts = Number.isFinite(Number(at)) ? Number(at) : Date.now()
  const zone = String(tz || 'Asia/Shanghai').trim() || 'Asia/Shanghai'
  const day = dayString(ts, zone)
  const time = clockIn(ts, zone)
  const line = `- ${time} [${cleanTag}] ${cleanText}`

  try {
    mkdirSync(dirname(pathRes.file), { recursive: true })
    if (!existsSync(pathRes.file)) {
      writeFileSync(pathRes.file, `${HEADER}\n## ${day}\n\n${line}\n`, 'utf8')
      return { ok: true, file: pathRes.file, day, time, tag: cleanTag, text: cleanText, line, entry: { day, time, tag: cleanTag, text: cleanText, raw: line } }
    }

    const existing = readFileSync(pathRes.file, 'utf8')
    if (!existing.trim()) {
      writeFileSync(pathRes.file, `${HEADER}\n## ${day}\n\n${line}\n`, 'utf8')
      return { ok: true, file: pathRes.file, day, time, tag: cleanTag, text: cleanText, line, entry: { day, time, tag: cleanTag, text: cleanText, raw: line } }
    }

    const dayHeaders = [...existing.matchAll(/^##\s+(\d{4}-\d{2}-\d{2})\b/gm)]
    const lastDay = dayHeaders.length ? dayHeaders[dayHeaders.length - 1][1] : ''
    const needLeadingNewline = existing.endsWith('\n') ? '' : '\n'
    const chunk = lastDay === day
      ? `${needLeadingNewline}${line}\n`
      : `${needLeadingNewline}\n## ${day}\n\n${line}\n`
    appendFileSync(pathRes.file, chunk, 'utf8')
    const cmp = compactInbox(kbDir, { now: ts, tz: zone })
    return {
      ok: true,
      file: pathRes.file,
      day,
      time,
      tag: cleanTag,
      text: cleanText,
      line,
      entry: { day, time, tag: cleanTag, text: cleanText, raw: line },
      ...(cmp.archivedCount ? { archivedCount: cmp.archivedCount, archiveFiles: cmp.archiveFiles } : {}),
    }
  } catch (error) {
    return { ok: false, error: '写入收件箱失败：' + String((error && error.message) || error) }
  }
}

/**
 * 读取并解析收件箱近期条目：[{ day, time, tag, text, raw }]。
 */
export function readInboxEntries(kbDir, { limit = 20 } = {}) {
  const pathRes = resolveInboxPath(kbDir)
  if (!pathRes.ok || !existsSync(pathRes.file)) return []

  let raw = ''
  try {
    raw = readFileSync(pathRes.file, 'utf8')
  } catch {
    return []
  }

  const cap = Math.max(1, Math.min(200, Math.round(Number(limit) || 20)))
  return parseAllInboxEntries(raw).slice(-cap)
}

function formatStuckLine(item) {
  if (typeof item === 'string') return item.trim()
  if (!item || typeof item !== 'object') return ''
  const title = String(item.title || item.task || item.text || item.note || item.topic || '').trim()
  const why = String(item.why || item.reason || item.blocker || '').trim()
  if (!title && !why) return ''
  return why && title ? `${title}（卡点：${why}）` : (title || why)
}

function formatSealLine(seal) {
  if (typeof seal === 'string') return seal.trim()
  if (!seal || typeof seal !== 'object') return ''
  const title = String(seal.title || '未命名验收点').trim()
  const actual = Number.isFinite(Number(seal.actual)) ? Number(seal.actual) : null
  const floor = Number.isFinite(Number(seal.floor)) ? Number(seal.floor) : null
  const status = String(seal.status || '').trim()
  const progress = actual !== null && floor !== null ? `（完成 ${actual}/${floor}）` : ''
  const tag = status === 'below' ? '未过保底' : (status ? status : '待补齐')
  return `[${tag}] ${title}${progress}`
}

/**
 * 打包给 Gemini 谋划思考部的紧凑 Markdown 与结构化统计：
 * 收件箱近期记录 + 卡壳记录/未过保底验收点 + 到期复习摘要。
 */
export function buildStrategizeBundle({
  kbDir = '',
  inboxLimit = 15,
  stuckItems = [],
  overdueSeals = [],
  dueReviews = [],
  today = '',
} = {}) {
  const entries = kbDir ? readInboxEntries(kbDir, { limit: inboxLimit }) : []
  const stuckLines = (Array.isArray(stuckItems) ? stuckItems : []).map(formatStuckLine).filter(Boolean)
  const overdueLines = (Array.isArray(overdueSeals) ? overdueSeals : []).map(formatSealLine).filter(Boolean)

  let dueReviewCount = 0
  const reviewLines = []
  if (Array.isArray(dueReviews)) {
    dueReviewCount = dueReviews.length
    for (const r of dueReviews.slice(0, 10)) {
      if (typeof r === 'string') {
        if (r.trim()) reviewLines.push(r.trim())
      } else if (r && typeof r === 'object') {
        const q = String(r.q || r.stem || r.title || '').trim()
        const topic = String(r.topic || '').trim()
        const lapses = Number(r.lapses || r.fsrs?.lapses || 0)
        if (q) {
          reviewLines.push((topic ? `[${topic}] ` : '') + q.slice(0, 100) + (lapses > 0 ? `（错 ${lapses} 次）` : ''))
        }
      }
    }
  } else if (dueReviews && typeof dueReviews === 'object') {
    dueReviewCount = Math.max(0, Number(dueReviews.dueCount) || 0)
    const preview = Array.isArray(dueReviews.duePreview) ? dueReviews.duePreview : []
    for (const r of preview.slice(0, 10)) {
      const q = String(r?.q || '').trim()
      const topic = String(r?.topic || '').trim()
      const lapses = Number(r?.lapses || 0)
      if (q) reviewLines.push((topic ? `[${topic}] ` : '') + q.slice(0, 100) + (lapses > 0 ? `（错 ${lapses} 次）` : ''))
    }
    const weak = Array.isArray(dueReviews.weakTopics) ? dueReviews.weakTopics : []
    if (weak.length) {
      reviewLines.push('高频薄弱主题：' + weak.map((w) => `${w.topic}(错${w.lapses}次)`).join('、'))
    }
    if (!dueReviewCount && preview.length) dueReviewCount = preview.length
  }

  const stuckCount = stuckLines.length + overdueLines.length
  const lines = [
    `# 谋划思考部交接包${today ? `（${today}）` : ''}`,
    '',
    `## 1. 收件箱近期记录（${entries.length} 条）`,
  ]
  if (entries.length) {
    for (const e of entries) {
      const prefix = [e.day, e.time].filter(Boolean).join(' ')
      lines.push(`- ${prefix ? prefix + ' ' : ''}[${e.tag}] ${e.text}`)
    }
  } else {
    lines.push('- （暂无新记录）')
  }

  lines.push('', `## 2. 卡壳记录与未过保底验收点（${stuckCount} 条）`)
  if (stuckCount) {
    for (const s of stuckLines) lines.push(`- 卡壳：${s}`)
    for (const o of overdueLines) lines.push(`- 验收：${o}`)
  } else {
    lines.push('- （暂无卡壳或未过保底项）')
  }

  lines.push('', `## 3. 到期复习摘要（${dueReviewCount} 张到期）`)
  if (reviewLines.length) {
    for (const rl of reviewLines) lines.push(`- ${rl}`)
  } else if (dueReviewCount > 0) {
    lines.push(`- 当前共有 ${dueReviewCount} 张到期复习卡待处理`)
  } else {
    lines.push('- （当前无到期复习卡）')
  }

  lines.push(
    '',
    '> 请基于以上事实：1) 挑出最卡住的一件事拆成最小可执行动作；2) 判断明日模板保底线是否需要下调或切换恢复日。',
  )

  return {
    ok: true,
    today: String(today || ''),
    markdown: lines.join('\n'),
    inboxCount: entries.length,
    stuckCount,
    dueReviewCount,
  }
}
