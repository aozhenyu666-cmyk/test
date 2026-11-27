// 大肥鱼 · Gemini 桥：本机互通接口（给电脑上别的程序用，比如 BrainOS 的 brain-mcp）。
//
// 只开在 127.0.0.1:8788，和 Gemini / 手机走的 8787 分开：8787 被 Tailscale Funnel 开到公网，
// Funnel 转进来的请求在本机看来也是 127.0.0.1——所以「看来源 IP 是不是本机」挡不住公网，必须换一个端口、不 funnel。
// 另外还要一把单独的本机密钥（localKey，跟手机密钥不是一把）：Authorization: Bearer <localKey>。
//
//   GET  /bridge/local/today              手机最近一次同步的「今天」（时间轴、验收、到期复习卡、V6 状态、电脑端活动）
//   GET  /bridge/local/inbox              电脑端收件箱（大肥鱼/收件箱.md）+ 手机端收件箱/卡壳/未过保底/复习摘要
//   POST /bridge/local/ingest             推复习卡（BrainOS 命题卡）/ 报复习评分 / 顺带一条回执
//   POST /bridge/local/import-questions   一批题灌进手机题库（按题干去重）
//   POST /bridge/local/pc-activity        电脑端哨兵每分钟回传工作/娱乐/锁屏时长到本机桥
//   POST /bridge/local/v6-op              安全调度手机 V6（仅限 nine_grid_roll / cadence_start / pool_ingest）
//
// 写操作和 Gemini 一样只是排一条命令，手机下次同步取走、按手机上的规矩再验一遍。
// 这里同样没有任何解锁、加额度、豁免、改小、删除的口子。

import { safeEqual } from './store.mjs'
import { enqueue, todayText, reviewText, inboxSummary, inboxText, bossBriefSummary, bossBriefText, PHONE_STALE_MS, RESULT_WAIT_MS } from './mcp.mjs'

export const LOCAL_PORT = 8788
const MAX_BODY = 1_000_000
const MAX_CARDS = 10
const MAX_RAW = 900_000

const LOOPBACK = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1'])
export const isLoopback = (addr) => LOOPBACK.has(String(addr || ''))

function readBody(req) {
  return new Promise((resolveBody, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (c) => {
      size += c.length
      if (size > MAX_BODY) {
        reject(Object.assign(new Error('body too large'), { status: 413 }))
        req.destroy()
        return
      }
      chunks.push(c)
    })
    req.on('end', () => resolveBody(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

const send = (res, status, body) => {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  res.end(JSON.stringify(body))
}

const str = (v, max) => String(v == null ? '' : v).trim().slice(0, max)

/** BrainOS / 别的本机程序推来的卡 → 手机 push_review_cards 命令的卡（字段名两种写法都认）。 */
export function cleanIngestCards(items) {
  return (Array.isArray(items) ? items : []).slice(0, MAX_CARDS).map((x) => ({
    externalId: str(x?.externalId ?? x?.external_id ?? x?.id, 80),
    topic: str(x?.topic, 40),
    q: str(x?.q ?? x?.proposition_question ?? x?.question, 400),
    a: str(x?.a ?? x?.answer ?? x?.proposition, 600),
    hint: str(x?.hint ?? x?.premise, 200),
    options: Array.isArray(x?.options) ? x.options.slice(0, 6).map((o) => str(o, 200)).filter(Boolean) : [],
    answer: str(x?.answer_letter ?? x?.answerLetter ?? '', 4),
    answerIndex: Number.isInteger(x?.answerIndex) ? x.answerIndex : Number.isInteger(x?.answer_index) ? x.answer_index : undefined,
  })).filter((x) => x.q && (x.a || x.options.length >= 2))
}

const RATING = new Set(['again', 'hard', 'good', 'easy'])
export function cleanRatings(list) {
  return (Array.isArray(list) ? list : []).slice(0, MAX_CARDS)
    .map((x) => ({ card_id: str(x?.card_id ?? x?.cardId ?? x?.id, 40), rating: RATING.has(String(x?.rating)) ? String(x.rating) : Number(x?.rating) }))
    .filter((x) => x.card_id && (RATING.has(String(x.rating)) || [1, 2, 3, 4].includes(x.rating)))
}

function waitResult(store, events, id, ms) {
  const done = () => store.state.results[id]
  if (done() || ms <= 0) return Promise.resolve(done() || null)
  return new Promise((resolve) => {
    const t = setTimeout(() => {
      events.off('result', on)
      resolve(null)
    }, ms)
    const on = (rid) => {
      if (rid !== id) return
      clearTimeout(t)
      events.off('result', on)
      resolve(done())
    }
    events.on('result', on)
  })
}

/**
 * @param {{store, events, vaultDir?, resultWaitMs?}} opts
 * @returns {(req, res) => Promise<void>}
 */
export function createLocalApi({ store, events, vaultDir = '', resultWaitMs = RESULT_WAIT_MS }) {
  const resolvedVaultDir = String(vaultDir || process.env.DAFEIYU_VAULT || '').trim()
  async function queue(req, res, url, type, payload) {
    const cmd = enqueue(store, type, payload)
    const age = store.now() - Number(store.state.snapshotAt || 0)
    if (url.searchParams.get('async') === '1') return send(res, 202, { ok: true, queued: cmd.id, phoneOnline: age < PHONE_STALE_MS })
    const r = await waitResult(store, events, cmd.id, age < PHONE_STALE_MS ? resultWaitMs : 0)
    if (r) return send(res, 200, { ok: r.ok, id: cmd.id, text: r.text })
    return send(res, 202, { ok: true, queued: cmd.id, phoneOnline: age < PHONE_STALE_MS, text: '已排队，手机下次同步时处理' })
  }

  return async function handler(req, res) {
    try {
      if (!isLoopback(req.socket?.remoteAddress)) return send(res, 403, { ok: false, error: 'loopback-only' })
      const m = /^Bearer\s+(.+)$/i.exec(String(req.headers.authorization || ''))
      if (!m || !safeEqual(m[1].trim(), store.state.secret.localKey)) return send(res, 401, { ok: false, error: 'bad-local-key' })
      const url = new URL(req.url || '/', 'http://local')
      const path = url.pathname.replace(/\/+$/, '')
      if (req.method === 'GET' && path === '/bridge/local/today') {
        const snap = store.state.snapshot
        const now = store.now()
        return send(res, 200, {
          ok: true,
          phoneOnline: Boolean(snap) && now - Number(store.state.snapshotAt || 0) < PHONE_STALE_MS,
          syncedAt: Number(store.state.snapshotAt || 0),
          todayText: todayText(snap, now),
          reviewText: reviewText(snap),
          today: snap ? {
            day: snap.day,
            timeline: snap.timeline || [],
            locking: snap.locking || null,
            custody: snap.custody || null,
            verifyLockCenter: snap.verifyLockCenter || null,
            bout: snap.bout || null,
            balance: snap.balance || null,
          } : null,
          custody: snap?.custody || null,
          verifyLockCenter: snap?.verifyLockCenter || null,
          verifier: snap?.verify || null,
          review: snap?.review || null,
          v6: snap?.v6 || null,
          pcActivity: store.state.pcActivity || null,
          health: snap?.health?.line || '',
        })
      }
      if (req.method === 'GET' && path === '/bridge/local/inbox') {
        const rawLimit = Number(url.searchParams.get('limit'))
        const limit = Number.isFinite(rawLimit) && rawLimit > 0 ? Math.min(50, Math.max(1, Math.floor(rawLimit))) : 20
        const summary = inboxSummary(store, resolvedVaultDir, { limit })
        return send(res, 200, {
          ...summary,
          inboxText: inboxText(summary),
        })
      }
      if (req.method === 'GET' && path === '/bridge/local/boss-brief') {
        const rawDays = Number(url.searchParams.get('days'))
        const days = Number.isFinite(rawDays) && rawDays > 0 ? Math.min(14, Math.max(1, Math.floor(rawDays))) : 7
        const summary = bossBriefSummary(store, resolvedVaultDir, { days })
        return send(res, 200, {
          ...summary,
          bossBriefText: bossBriefText(summary),
        })
      }
      if (req.method !== 'POST') return send(res, 404, { ok: false, error: 'not_found' })
      let body
      try {
        body = JSON.parse((await readBody(req)) || '{}')
      } catch {
        return send(res, 400, { ok: false, error: 'bad-json' })
      }
      if (path === '/bridge/local/ingest') {
        const cards = cleanIngestCards(body.items)
        const ratings = cleanRatings(body.reviewed ?? body.ratings)
        if (!cards.length && !ratings.length) return send(res, 400, { ok: false, error: 'empty', text: 'items（卡）和 reviewed（评分）都是空的' })
        const source = body.source === 'gemini_mcp' ? 'gemini_mcp' : 'brainos'
        if (!cards.length) return queue(req, res, url, 'review_ratings', { items: ratings, channel: 'brainos' })
        const receipt = body.alsoReceipt && typeof body.alsoReceipt === 'object' ? body.alsoReceipt : null
        if (ratings.length) enqueue(store, 'review_ratings', { items: ratings, channel: 'brainos' })
        return queue(req, res, url, 'push_review_cards', { source, items: cards, checkpointId: str(body.checkpoint_id ?? body.checkpointId, 40), alsoReceipt: receipt })
      }
      if (path === '/bridge/local/import-questions') {
        const raw = body.rawTextOrJson ?? body.raw
        const questions = Array.isArray(body.questions) ? body.questions.slice(0, 200) : null
        const rawStr = typeof raw === 'string' ? raw : raw && typeof raw === 'object' ? JSON.stringify(raw) : ''
        if (!rawStr && !questions?.length) return send(res, 400, { ok: false, error: 'empty', text: '要 rawTextOrJson（文本 / JSON）或 questions 数组' })
        if (rawStr.length > MAX_RAW) return send(res, 413, { ok: false, error: 'too-large', text: '一次最多约 900 KB，拆开分几次推' })
        return queue(req, res, url, 'import_questions', {
          bankId: str(body.bankId ?? body.bank_id, 48), bankName: str(body.bankName ?? body.bank_name, 60), raw: rawStr || undefined, questions: questions || undefined,
        })
      }
      if (path === '/bridge/local/pc-activity') {
        const nowTs = store.now()
        const pc = {
          at: Number(body?.at) || nowTs,
          receivedAt: nowTs,
          dayKey: str(body?.dayKey, 16),
          workMinutes: Math.max(0, Number(body?.workMinutes) || 0),
          entertainmentMinutes: Math.max(0, Number(body?.entertainmentMinutes) || 0),
          dualMinutes: Math.max(0, Number(body?.dualMinutes) || 0),
          afkMinutes: Math.max(0, Number(body?.afkMinutes) || 0),
          activeApp: str(body?.activeApp, 80),
          activeTitle: str(body?.activeTitle, 120),
          isAfk: Boolean(body?.isAfk),
          screenLocked: Boolean(body?.screenLocked),
          focusActive: Boolean(body?.focusActive),
          focusTask: str(body?.focusTask, 80),
          blocksTriggered: Math.max(0, Math.round(Number(body?.blocksTriggered) || 0)),
          strictArmed: Boolean(body?.strictArmed),
        }
        store.state.pcActivity = pc
        store.save()
        return send(res, 200, { ok: true, pcActivity: pc })
      }
      if (path === '/bridge/local/v6-op') {
        const action = str(body?.action, 40)
        if (!['nine_grid_roll', 'cadence_start', 'pool_ingest'].includes(action)) {
          return send(res, 400, { ok: false, error: 'disallowed-action', text: '仅允许 nine_grid_roll / cadence_start / pool_ingest' })
        }
        const payload = { action }
        if (action === 'cadence_start') {
          if (body?.target != null) payload.target = str(body.target, 120)
          if (Number.isFinite(Number(body?.intervalSec))) payload.intervalSec = Number(body.intervalSec)
        } else if (action === 'pool_ingest') {
          const text = str(body?.text, 800)
          if (!text) return send(res, 400, { ok: false, error: 'empty', text: 'text 不能为空' })
          payload.text = text
          payload.source = str(body?.source || 'pc_clipboard', 40)
        } else if (action === 'nine_grid_roll') {
          if (body?.autoArm === false) payload.autoArm = false
          if (body?.excludeTileId) payload.excludeTileId = str(body.excludeTileId, 48)
        }
        return queue(req, res, url, 'v6_op', payload)
      }
      return send(res, 404, { ok: false, error: 'not_found' })
    } catch (e) {
      return send(res, Number(e?.status) || 500, { ok: false, error: 'server_error' })
    }
  }
}
