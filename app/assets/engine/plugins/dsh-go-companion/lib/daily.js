// dsh-go-companion · 每日文件与按完成物对账（第二轮）
//
// 早上定事：鲸澜在晨间对线时把「今天的事 + 每件的完成物」写进 大肥鱼/每日/日期.md。
// 白天不催：鲸澜白天不追问进度（追人阶梯已撤）。
// 晚上对账：按早上写下的完成物，去知识库、账本和使用记录里**找证据**——找到算完成，找不到算没完成，
//   找到一半记一半。结论写进当日文件和账本，作用于**明天**的放风额度和信用分，不当场锁人。
//
// 机器状态放 companion/daily.json（按天一份），md 是写给人看的（Obsidian 里能直接翻）。
// 对账只看证据，不听说法：光说「做完了」不算。

import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { startOfDay } from './tz.js'

export const DAILY_DIR = '每日'
export const DAILY_MAX_TASKS = 6
/** 一天最多扫多少个文件找证据（别把手机扫卡）。 */
const EVIDENCE_SCAN_LIMIT = 200
const EVIDENCE_DEPTH = 4
/** 关键词命中比例到这个数就算做完（做了一半记一半）。 */
const DONE_RATIO = 0.6

/** 当地日期串 YYYY-MM-DD（按手机时区，不是进程时区）。 */
export function dayString(now, tz) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(now))
}

/** 每日文件路径：<知识库>/大肥鱼/每日/YYYY-MM-DD.md */
export function dailyPath(kbDir, now, tz) {
  return join(kbDir, DAILY_DIR, dayString(now, tz) + '.md')
}

const clean = (s, max) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim().slice(0, max)

/**
 * 校验并规整早上定的事：每件都要带一个看得见的完成物。
 * @returns {{ok:boolean, text:string, tasks?:{task:string,evidence:string}[]}}
 */
export function normalizeTasks(raw) {
  const list = (Array.isArray(raw) ? raw : [])
    .map((t) => ({ task: clean(t?.task, 120), evidence: clean(t?.evidence, 160) }))
    .filter((t) => t.task)
    .slice(0, DAILY_MAX_TASKS)
  if (!list.length) return { ok: false, text: '今天的事是空的。说一件事，也要带完成物。' }
  const noEvidence = list.filter((t) => !t.evidence)
  if (noEvidence.length) {
    return { ok: false, text: '每件事都要带一个看得见的完成物（笔记一篇、Gemini 记录、某个 App 的使用时长）：' + noEvidence.map((t) => '「' + t.task + '」').join('、') + ' 还没写。' }
  }
  return { ok: true, text: '定了 ' + list.length + ' 件事。', tasks: list }
}

/** 一天的机器状态：{day, tasks, review}。存在 stateFile('daily.json') 里，按天清。 */
export function loadDaily(raw, now, tz) {
  const day = dayString(now, tz)
  const s = raw && typeof raw === 'object' && raw.day === day ? raw : null
  return {
    day,
    tasks: Array.isArray(s?.tasks) ? s.tasks : [],
    review: s?.review && typeof s.review === 'object' ? s.review : null,
  }
}

/** 渲染 每日/日期.md（写给人看的；机器状态在 daily.json）。 */
export function renderDaily(state, names = {}) {
  const who = names.strategist || '鲸澜'
  const lines = ['# ' + state.day + ' · 今日', '', '> 早上的事和完成物、晚上的对账，都由' + who + '写。对账只看证据。', '']
  lines.push('## 今天的事（早上定）', '')
  if (!state.tasks.length) {
    lines.push('- （还没定）')
  } else {
    state.tasks.forEach((t, i) => {
      lines.push(i + 1 + '. **' + t.task + '**  ')
      lines.push('   完成物：' + t.evidence)
    })
  }
  lines.push('', '## 对账（晚上）', '')
  const r = state.review
  if (!r) {
    lines.push('- （待对账）')
  } else {
    lines.push('- 结论：做完 ' + r.done + ' 件、做了一半 ' + r.partial + ' 件、没做完 ' + r.miss + ' 件（共 ' + r.total + ' 件）。')
    if (r.quotaDelta) lines.push('- 明天放风额度：' + (r.quotaDelta > 0 ? '+' + r.quotaDelta + ' 天' : r.quotaDelta + ' 天'))
    for (const x of Array.isArray(r.results) ? r.results : []) {
      lines.push('  - ' + (x.verdict === 'done' ? '✅ ' : x.verdict === 'partial' ? '◐ ' : '✗ ') + x.task + '——' + x.why)
    }
  }
  lines.push('')
  return lines.join('\n')
}

/** 写每日文件（md）。返回 {ok, file, text}。 */
export function writeDaily(kbDir, state, names = {}) {
  const file = join(kbDir, DAILY_DIR, state.day + '.md')
  try {
    mkdirSync(join(kbDir, DAILY_DIR), { recursive: true })
    writeFileSync(file, renderDaily(state, names), 'utf8')
    return { ok: true, file, text: '写进每日文件了：' + file }
  } catch (error) {
    return { ok: false, file: '', text: '每日文件写不进去：' + String((error && error.message) || error) }
  }
}

/** 从完成物里抠关键词：去掉标点，留长度 ≥2 的词，最多 4 个。 */
export function keywordsOf(evidence) {
  const words = String(evidence || '')
    .replace(/[，。、；：""''（）()【】\[\]《》<>,.;:!?~`|\\/]+/g, ' ')
    .split(/\s+/)
    .filter((w) => w.length >= 2)
  const seen = []
  for (const w of words) if (!seen.includes(w)) seen.push(w)
  return seen.slice(0, 4)
}

/** 完成物里像是在说文件路径吗（含 / 或以 .md 结尾）。 */
function looksLikePath(evidence) {
  return /[\\/]/.test(evidence) || /\.(md|txt|pdf|docx?|png|jpe?g)$/i.test(evidence)
}

function todayStart(now, tz) {
  return startOfDay(now, tz)
}

/** 扫知识库里今天动过的 md（跳过 大肥鱼/每日 自己，免得对账文件自己给自己作证）。 */
function todaysNotes(notesDir, since) {
  const out = []
  if (!notesDir) return out
  let seen = 0
  const skip = new Set([DAILY_DIR])
  const walk = (dir, depth) => {
    if (depth > EVIDENCE_DEPTH || seen > EVIDENCE_SCAN_LIMIT) return
    let names = []
    try {
      names = readdirSync(dir)
    } catch {
      return
    }
    for (const name of names) {
      if (seen > EVIDENCE_SCAN_LIMIT) return
      if (name.startsWith('.')) continue
      const path = join(dir, name)
      let st
      try {
        st = statSync(path)
      } catch {
        continue
      }
      if (st.isDirectory()) {
        if (skip.has(name)) continue
        walk(path, depth + 1)
        continue
      }
      if (!name.endsWith('.md') || st.mtimeMs < since) continue
      seen += 1
      let text = ''
      try {
        text = readFileSync(path, 'utf8').slice(0, 20_000)
      } catch {
        continue
      }
      out.push({ path, rel: relative(notesDir, path), text })
    }
  }
  walk(notesDir, 0)
  return out
}

/**
 * 单件事情的证据核对。只看三样东西，且都要有实物：
 *   1. 知识库文件（路径直接命中，或今天改过的 md 里有这个完成物的关键词）
 *   2. 账本（今天这条 Gemini 连麦复盘的题目/小结里有这些关键词）
 *   3. 使用记录（完成物里点到的 App，今天用到了要求的时间）
 * @returns {{verdict:'done'|'partial'|'miss', why:string}}
 */
export function checkEvidence(item, ctx) {
  const evidence = String(item?.evidence || '')
  const words = keywordsOf(evidence)
  const since = todayStart(ctx.now, ctx.tz)
  const notes = ctx.notes || []

  // 1a. 路径：直接说了哪个文件。
  if (looksLikePath(evidence)) {
    const raw = evidence.replace(/[`"'《》]/g, '').split(/\s+/).find((w) => /[\\/.]/.test(w)) || ''
    const candidates = []
    if (raw) {
      candidates.push(raw) // 绝对路径直接用（Android /sdcard/...、相对路径不存在就换下一个）
      if (ctx.notesDir) candidates.push(join(ctx.notesDir, raw.replace(/^\.?[\\/]/, '')))
      for (const n of notes) if (raw && (n.rel.includes(raw) || raw.includes(n.rel))) candidates.push(n.path)
    }
    for (const p of candidates) {
      try {
        if (p && existsSync(p) && statSync(p).mtimeMs >= since) return { verdict: 'done', why: '找到了这个文件，今天改过：' + p.split(/[\\/]/).slice(-2).join('/') }
      } catch {
        // 这个候选读不到，换下一个
      }
    }
  }

  // 1b. 今天动过的笔记里有没有这些关键词。
  let hit = 0
  if (words.length) {
    for (const n of notes) {
      const text = n.text || ''
      const nHit = words.filter((w) => text.includes(w)).length
      if (nHit > hit) hit = nHit
      if (nHit / words.length >= DONE_RATIO) return { verdict: 'done', why: '今天的笔记里有「' + words.filter((w) => text.includes(w)).slice(0, 2).join('、') + '」：' + n.rel }
    }
  }

  // 2. 账本：今天的 Gemini 连麦复盘。
  const recaps = (ctx.ledger || []).filter((e) => e?.state === 'gemini-recap' && Number(e.at) >= since)
  for (const e of recaps) {
    const text = String(e.summary || '') + ' ' + String(e.topic || '')
    if (words.length && words.some((w) => text.includes(w))) return { verdict: 'done', why: '今天有一条 Gemini 复盘对得上：' + String(e.summary || '').slice(0, 40) }
  }

  // 3. 使用记录：完成物点到的 App，今天用够了要求的时间。
  const usage = Array.isArray(ctx.usage) ? ctx.usage : []
  for (const u of usage) {
    const label = String(u?.label || u?.app || '')
    if (!label || !words.some((w) => label.includes(w) || w.includes(label))) continue
    const need = Number((evidence.match(/(\d+)\s*分钟/) || [])[1]) || 1
    const got = Math.round(Number(u?.minutes) || 0)
    if (got >= need) return { verdict: 'done', why: label + ' 今天用了 ' + got + ' 分钟（要求 ' + need + '）' }
    if (got > 0) return { verdict: 'partial', why: label + ' 今天只用了 ' + got + ' 分钟（要求 ' + need + '）' }
  }

  // 关键词只命中一部分：记一半。
  if (words.length && hit > 0 && hit / words.length >= 0.3) {
    return { verdict: 'partial', why: '只找到一部分痕迹（' + hit + '/' + words.length + ' 个关键词）' }
  }
  return { verdict: 'miss', why: '知识库、账本、使用记录里都没找到能对上这个完成物的东西' }
}

/**
 * 对账：逐件核对。
 * @param {{day:string,tasks:{task:string,evidence:string}[]}} state
 * @returns {{done:number, partial:number, miss:number, total:number, results:{task:string,evidence:string,verdict:string,why:string}[], quotaDelta:number}}
 */
export function reconcile(state, ctx) {
  const tasks = Array.isArray(state?.tasks) ? state.tasks : []
  const notes = ctx.notes || todaysNotes(ctx.notesDir, todayStart(ctx.now, ctx.tz))
  const results = tasks.map((t) => {
    const r = checkEvidence(t, { ...ctx, notes })
    return { task: t.task, evidence: t.evidence, verdict: r.verdict, why: r.why }
  })
  const done = results.filter((r) => r.verdict === 'done').length
  const partial = results.filter((r) => r.verdict === 'partial').length
  const miss = results.filter((r) => r.verdict === 'miss').length
  // 作用于明天：全做完 +1 天放风额度，一件没做完 -1 天（不少于 0），一半就记一半（不加不减）。
  const total = results.length
  const quotaDelta = !total ? 0 : done === total ? 1 : done === 0 && partial === 0 ? -1 : 0
  return { done, partial, miss, total, results, quotaDelta }
}

/** 对账结论的一句人话。 */
export function reviewText(out) {
  if (!out || !out.total) return '今天早上没定事，没有可对的账。'
  const head = '对完了：做完 ' + out.done + ' 件、一半 ' + out.partial + ' 件、没做完 ' + out.miss + ' 件。'
  const tail = out.quotaDelta > 0 ? '明天多 1 天放风额度。' : out.quotaDelta < 0 ? '明天少 1 天放风额度。' : '明天额度不变。'
  return head + tail
}
