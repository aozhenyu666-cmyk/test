import { join } from 'node:path'
import { dayString } from './daily.js'
import { previewIntervals } from './fsrs.js'
import { settleDeferred } from './seal.js'
import { readLedger } from './ledger.js'
import {
  MAX_CARDS, addCards, cleanPool, deferredProgress, delayedRecall, deleteCard, dueCards, mirrorLine, rateCard, reviewSummary, reviewWeekLine,
} from './review_cards.js'
import { home, readState, startOfDay, stateFile, writeJson } from './runtime_io.js'
import { hm as zoneHm, zoneOr } from './tz.js'
import { readText as readVaultText, writeText as writeVaultText } from './vault_wall.js'
import { weekPack } from './week_pack.js'

/**
 * 复习卡（FSRS）与周包运行态（从 index.js 拆出）。
 */
export function createReviewRuntime(deps) {
  const {
    getConfig,
    vaultKb,
    loadSeals,
    saveSeals,
    sealTick,
    getLastPhone,
    record,
    recordTimeEntryNow,
    mainStateNow,
    runningBoutNow,
    toolOutput,
  } = deps

  const REVIEW_QUIZ_MAX_PER_DAY = 2
  const REVIEW_QUIZ_GAP_MS = 3 * 3_600_000
  const REVIEW_QUIZ_PENDING_MS = 40 * 60_000
  let weekCache = { at: 0, day: '', pack: null }

  const loadReviewCards = () => cleanPool(readState(stateFile('review-cards.json'), null), Date.now())
  const saveReviewCards = (pool) => writeJson(stateFile('review-cards.json'), pool)

  function mirrorCardsToVault(cards, now) {
    if (!cards.length) return
    try {
      const kb = vaultKb()
      if (!kb) return
      const tz = zoneOr(getConfig().timeZone)
      const month = dayString(now, tz).slice(0, 7)
      const path = join(kb, '复习卡', month + '.md')
      const prev = readVaultText(path) || '# ' + month + ' · 复习卡\n\n> 手机按 FSRS 到期再问（锁屏、复习页、Gemini）。格式：问 :: 答。这里只是镜像，改这里不影响手机。\n'
      writeVaultText(path, prev.replace(/\s+$/, '') + '\n\n## ' + dayString(now, tz).slice(5) + ' ' + zoneHm(now, tz) + '\n' + cards.map(mirrorLine).join('\n') + '\n')
    } catch {}
  }

  function ingestReviewCards(inputs, { source = 'app', sealId = '', now = Date.now(), max = 10, counts } = {}) {
    const tz = zoneOr(getConfig().timeZone)
    const today = dayString(now, tz)
    const list = Array.isArray(inputs) ? inputs : []
    let sid = String(sealId || '').trim()
    if (sid && !loadSeals(now, tz).items.some((x) => x.id === sid && x.day === today && x.kind === 'study_cards')) sid = ''
    const r = addCards(loadReviewCards(), list, { now, day: today, source, sealId: sid, max, counts })
    if (r.added.length) {
      saveReviewCards(r.state)
      mirrorCardsToVault(r.added, now)
      record({ state: 'review-cards-add', at: now, source, n: r.added.length, dup: r.duplicate.length, counted: r.added.filter((x) => x.counts).length })
      try {
        sealTick(now, tz, getLastPhone())
      } catch {}
    }
    const counted = r.added.filter((x) => x.counts).length
    const text = (r.added.length ? '收了 ' + r.added.length + ' 张复习卡' + (counted < r.added.length ? '（其中 ' + counted + ' 张算学习制卡；随手记的进池不算数）' : '') + '，12 小时后开始按 FSRS 问他' : '一张都没收') +
      (r.duplicate.length ? '；' + r.duplicate.length + ' 张已经有了' : '') + (r.skipped.length ? '；没收的：' + r.skipped.slice(0, 3).map((x) => '「' + x.q + '」' + x.why).join('、') : '') +
      (r.evicted.length ? '；卡池满 ' + MAX_CARDS + ' 张，挤掉了 ' + r.evicted.length + ' 张最稳的' : '')
    return { added: r.added.length, counted, duplicate: r.duplicate.length, skipped: r.skipped, text, cards: r.added }
  }

  function studyCardCounts(now, tz) {
    const today = dayString(now, tz)
    const seals = loadSeals(now, tz).items.filter((x) => x.day === today && x.kind === 'study_cards' && x.status !== 'waived').sort((a, b) => a.dueMinute - b.dueMinute)
    if (!seals.length) return {}
    const pool = loadReviewCards()
    let changed = false
    const target = seals[0].id
    const items = pool.items.map((c) => {
      if (c.sealId || !c.counts || c.day !== today) return c
      changed = true
      return { ...c, sealId: target }
    })
    if (changed) saveReviewCards({ ...pool, items })
    const out = {}
    for (const s of seals) out[s.id] = items.filter((c) => c.counts && c.sealId === s.id).length
    return out
  }

  function settleDeferredNow(now = Date.now()) {
    const tz = zoneOr(getConfig().timeZone)
    const pool = loadReviewCards()
    const before = loadSeals(now, tz)
    if (!before.items.some((x) => x.kind === 'study_cards' && x.paidMinutes > 0 && !x.deferredPaid)) return []
    const r = settleDeferred(before, (seal) => deferredProgress(pool, seal.id, now).passed, { now })
    for (const p of r.pays) {
      recordTimeEntryNow({ kind: 'earned_review', minutes: p.minutes, reason: '学习制卡「' + p.title + '」隔天复习答对 ' + p.passed + ' 张（第二半）', at: now, tz })
      record({ state: 'seal-deferred-pay', at: now, id: p.id, minutes: p.minutes, passed: p.passed })
    }
    if (r.changed) saveSeals(r.state, now, tz, { type: 'deferred', pays: r.pays })
    return r.pays
  }

  function rateReviewCardNow(id, rating, channel, now = Date.now()) {
    const r = rateCard(loadReviewCards(), id, rating, { now, channel })
    if (!r.ok) return r
    saveReviewCards(r.state)
    record({ state: 'review-card-rate', at: now, id, rating: r.card.history[r.card.history.length - 1].rating, channel, verified: r.verified })
    let pays = []
    if (r.verified && r.card.sealId) {
      try {
        pays = settleDeferredNow(now)
      } catch {}
    }
    return { ...r, pays }
  }

  function applyReviewedCards(list, channel, now) {
    const items = (Array.isArray(list) ? list : []).slice(0, 10)
    if (!items.length) return { n: 0, text: '' }
    let n = 0
    let paid = 0
    for (const x of items) {
      const r = rateReviewCardNow(String(x?.card_id ?? x?.cardId ?? x?.id ?? ''), x?.rating, channel, now)
      if (r.ok) {
        n += 1
        paid += (r.pays || []).reduce((a, p) => a + p.minutes, 0)
      }
    }
    return { n, text: n ? '复习卡排期改了 ' + n + ' 张' + (paid ? '，学习制卡第二半发了 ' + paid + ' 分钟' : '') : '' }
  }

  function lockscreenCard(now) {
    const pool = loadReviewCards()
    return dueCards(pool, now, { limit: 1, filter: (c) => c.options.length > 0 || !(c.sealId && delayedRecall(c) === '') })[0] || null
  }

  const reviewDueCount = (now = Date.now()) => dueCards(loadReviewCards(), now, { limit: MAX_CARDS }).length

  function reviewSnapshot(now = Date.now()) {
    const pool = loadReviewCards()
    if (!pool.items.length) return { dueCount: 0, totalCards: 0, retention7d: null, due: [], weakTopics: [] }
    const sum = reviewSummary(pool, now)
    let weakBank = []
    try {
      const st = readState(stateFile('bank-unlock.json'), null)
      const bank = readState(stateFile('bank.json'), null)
      const wrong = new Set(Array.isArray(st?.wrongIds) ? st.wrongIds.map(String) : [])
      const byType = {}
      for (const q of Array.isArray(bank?.题目) ? bank.题目 : []) {
        if (!wrong.has(String(q.id))) continue
        const t = String(q.题型 || '未分类')
        byType[t] = (byType[t] || 0) + 1
      }
      weakBank = Object.entries(byType).sort((a, b) => b[1] - a[1]).slice(0, 3).map(([topic, wrongN]) => ({ topic, wrong: wrongN }))
    } catch {}
    return {
      dueCount: sum.dueCount, totalCards: sum.totalCards, retention7d: sum.retention7d, reviewed7d: sum.reviewed7d,
      due: dueCards(pool, now, { limit: 10 }).map((c) => ({
        id: c.id, topic: c.topic, q: c.q.slice(0, 200), a: c.a.slice(0, 240), hint: c.hint, lapses: c.fsrs.lapses,
        options: c.options, pendingRecall: Boolean(c.sealId && delayedRecall(c) === ''),
      })),
      weakTopics: sum.weakTopics, weakBank,
    }
  }

  function reviewRoute(body) {
    const b = body && typeof body === 'object' ? body : {}
    const action = String(b.action || 'summary')
    const now = Date.now()
    const pool = loadReviewCards()
    const view = (c) => ({
      id: c.id, topic: c.topic, q: c.q, a: c.a, hint: c.hint, options: c.options, source: c.source, counts: c.counts,
      due: c.fsrs.due, reps: c.fsrs.reps, lapses: c.fsrs.lapses, state: c.fsrs.state, createdAt: c.createdAt,
      pendingRecall: Boolean(c.sealId && delayedRecall(c) === ''), next: previewIntervals(c.fsrs, now),
    })
    if (action === 'summary') return { v: 1, ok: true, ...reviewSummary(pool, now) }
    if (action === 'due') return { v: 1, ok: true, items: dueCards(pool, now, { limit: Math.max(1, Math.min(50, Number(b.limit) || 20)) }).map(view) }
    if (action === 'list') {
      const off = Math.max(0, Number(b.offset) || 0)
      const items = [...pool.items].sort((x, y) => y.createdAt - x.createdAt)
      return { v: 1, ok: true, total: items.length, items: items.slice(off, off + 50).map(view) }
    }
    if (action === 'answer' || action === 'rate') {
      const card = pool.items.find((x) => x.id === String(b.id || ''))
      if (!card) return { v: 1, ok: false, error: 'no-card', text: '这张卡不在了。' }
      let rating
      let channel
      let correct = null
      if (action === 'answer') {
        if (!card.options.length) return { v: 1, ok: false, error: 'no-options', text: '这张卡没有选项，只能自己对答案。' }
        const idx = Number.isInteger(Number(b.index)) ? Number(b.index) : -1
        correct = idx === card.answerIndex
        rating = correct ? 3 : 1
        channel = 'app_choice'
      } else {
        rating = b.rating
        channel = 'self'
      }
      const r = rateReviewCardNow(card.id, rating, channel, now)
      if (!r.ok) return { v: 1, ok: false, error: r.error }
      return {
        v: 1, ok: true, correct, verified: r.verified, card: view(r.card),
        text: (correct === false ? '错了，正确是 ' + String.fromCharCode(65 + card.answerIndex) + '：' + card.a + '。10 分钟后再问。' : correct ? '对。' : '记下了。') +
          (r.pays?.length ? ' 学习制卡第二半发了 ' + r.pays.reduce((a, p) => a + p.minutes, 0) + ' 分钟。' : ''),
      }
    }
    if (action === 'add') {
      const r = ingestReviewCards([b.card || b], { source: 'app', now, max: 1, counts: false })
      return { v: 1, ok: r.added > 0, text: r.text }
    }
    if (action === 'delete') {
      const r = deleteCard(pool, String(b.id || ''), now)
      if (r.ok) {
        saveReviewCards(r.state)
        record({ state: 'review-card-delete', at: now, id: String(b.id || '') })
      }
      return { v: 1, ok: r.ok, text: r.ok ? '删了。' : '这张卡不在了。' }
    }
    return { v: 1, ok: false, error: 'bad-action' }
  }

  const loadReviewQuiz = () => readState(stateFile('review-quiz.json'), null) || {}

  function reviewQuizCandidate(now, tz, phone) {
    if (getConfig().reviewQuiz === false || !phone?.screenOn) return null
    if (mainStateNow() !== 'standby') return null
    if (runningBoutNow()) return null
    const st = loadReviewQuiz()
    const today = dayString(now, tz)
    const n = st.day === today ? Number(st.n) || 0 : 0
    if (n >= REVIEW_QUIZ_MAX_PER_DAY || now - (Number(st.lastAt) || 0) < REVIEW_QUIZ_GAP_MS) return null
    const card = dueCards(loadReviewCards(), now, { limit: 1, filter: (c) => !(c.sealId && delayedRecall(c) === '') })[0]
    if (!card) return null
    return {
      role: 'companion', reason: 'review-quiz', outReason: 'review-quiz', priority: 5, at: now,
      note: { cardId: card.id, topic: card.topic, q: card.q, a: card.a, hint: card.hint, options: card.options },
    }
  }

  function markReviewQuiz(p, now, tz) {
    const st = loadReviewQuiz()
    const today = dayString(now, tz)
    writeJson(stateFile('review-quiz.json'), {
      day: today, n: (st.day === today ? Number(st.n) || 0 : 0) + 1, lastAt: now,
      pending: { cardId: p.note?.cardId || '', q: String(p.note?.q || '').slice(0, 200), a: String(p.note?.a || '').slice(0, 240), at: now },
    })
    record({ state: 'review-quiz', at: now, cardId: p.note?.cardId || '' })
  }

  function reviewQuizLines(now) {
    const pd = loadReviewQuiz().pending
    if (!pd?.cardId || now - Number(pd.at || 0) > REVIEW_QUIZ_PENDING_MS) return []
    return ['刚才抽问了他一张复习卡 [' + pd.cardId + ']「' + pd.q + '」（标准答案：' + pd.a + '）。他答了就判：答出要点 → rate_review_card remembered=true；答不上 / 敷衍 / 岔开 → false，并把答案告诉他。只判一次，不发分钟。']
  }

  function rateReviewCardTool() {
    return {
      name: 'rate_review_card',
      description: '你抽问了他一张到期复习卡、他答了之后用：答出要点 remembered=true，答不上 / 敷衍 / 岔开 false。只改这张卡下次什么时候再问，不发任何分钟、不算验收（你们俩抽问不算「验过」）。',
      parameters: {
        type: 'object',
        properties: {
          card_id: { type: 'string', description: '卡的 id（上下文里 [rc-…]）' },
          remembered: { type: 'boolean' },
          shaky: { type: 'boolean', description: '答出来了但磕磕绊绊（可选）' },
        },
        required: ['card_id', 'remembered'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const rating = args.remembered === true ? (args.shaky === true ? 2 : 3) : 1
        const r = rateReviewCardNow(String(args.card_id || ''), rating, 'sister', now)
        if (!r.ok) return { ok: false, text: '没找到这张卡（id 抄错了？）。' }
        const st = loadReviewQuiz()
        if (st.pending?.cardId === r.card.id) writeJson(stateFile('review-quiz.json'), { ...st, pending: null })
        const days = r.card.fsrs.scheduledDays
        return { ok: true, text: rating === 1 ? '记下了：10 分钟后锁屏会再问他这张。' : '记下了：' + days + ' 天后再问。' }
      },
    }
  }

  function captureReviewCardTool() {
    return {
      name: 'capture_review_card',
      description: '他说「把这个记进复习卡 / 这个错题记一下」时，把知识点写成复习卡（问一句、答一句），12 小时后起按遗忘曲线在锁屏和复习页问他。' +
        '一次最多 3 张；问要能单独看懂，答要短而准。聊天里记的卡进复习池，但不算「学习制卡」验收点的张数。他没说要记就别记。',
      parameters: {
        type: 'object',
        properties: {
          cards: {
            type: 'array',
            maxItems: 3,
            items: {
              type: 'object',
              properties: {
                topic: { type: 'string', description: '主题，如「资料分析」' },
                q: { type: 'string', description: '问（能单独看懂的一句）' },
                a: { type: 'string', description: '答（短而准）' },
                hint: { type: 'string', description: '成立前提 / 易错点（可选）' },
              },
              required: ['q', 'a'],
              additionalProperties: false,
            },
          },
        },
        required: ['cards'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const r = ingestReviewCards(args.cards, { source: 'dsh_chat', now: Date.now(), max: 3, counts: false })
        return { ok: r.added > 0 || r.duplicate > 0, text: r.text }
      },
    }
  }

  function weekPackNow(now = Date.now(), { fresh = false } = {}) {
    const config = getConfig()
    const tz = zoneOr(config.timeZone)
    const today = dayString(now, tz)
    if (!fresh && weekCache.pack && weekCache.day === today && now - weekCache.at < 10 * 60_000) return weekCache.pack
    let reviewLine = ''
    try {
      reviewLine = reviewWeekLine(loadReviewCards(), { from: startOfDay(now, config.timeZone) - 6 * 86_400_000, to: now + 1, now })
    } catch {}
    const pack = weekPack({ entries: readLedger(home(), 6000), seals: loadSeals(now, tz), today, dayOf: (at) => dayString(at, tz), days: 7, reviewLine })
    weekCache = { at: now, day: today, pack }
    return pack
  }

  return {
    loadReviewCards,
    saveReviewCards,
    ingestReviewCards,
    studyCardCounts,
    settleDeferredNow,
    rateReviewCardNow,
    applyReviewedCards,
    lockscreenCard,
    reviewDueCount,
    reviewSnapshot,
    reviewRoute,
    reviewQuizCandidate,
    markReviewQuiz,
    reviewQuizLines,
    rateReviewCardTool,
    captureReviewCardTool,
    weekPackNow,
  }
}
