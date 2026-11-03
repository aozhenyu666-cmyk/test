// dsh-driver-core：DSH 宿主插件入口。手机端（DSH Go）把信号送进来，拿回要显示的卡片指令。
//
//   POST /dsh-driver-core/v1/session   {topic, task, minutes?, anchors?}   开一次锁定（同一时刻只有一条主线）
//   POST /dsh-driver-core/v1/signal    {sessionId, kind, ...}              foreground | live | audio | surface | reply | pause | stop
//   GET  /dsh-driver-core/v1/directives?sessionId=&after=&waitMs=          长轮询取指令（≤25 s）
//   GET  /dsh-driver-core/v1/state?sessionId=                              当前状态 + 推进统计
//   GET  /dsh-driver-core/v1/status                                        配置、出题会话计数、当前主线
//   POST /dsh-driver-core/v1/config    {brainSessionId?, memorySessionIds?, anchors?}
//   POST /dsh-driver-core/v1/profile   {goals?, habits?}                   你的主线目标和惯性死穴
//   GET  /dsh-driver-core/v1/agenda                                        思考议程（没解决的断点）
//   audio 信号：{assistantSpeaking:true} = Gemini 开始说话；false = 停止（说够久就开"轮到你"窗口）
//
// 以上路由要求和 /api 一样的凭据（connection.requestRejection），插件自注册的路由不会自动鉴权。
//
// 「思维驾驶员」APP 用的 /app/* 路由走配对令牌（见 lib/pairing.js、lib/app-routes.js）：
//   POST /app/pair  GET /app/ping  POST /app/compose  POST /app/report
// 在任意会话里说"驾驶员就用这个会话"，那个会话就成为出题会话。
// 零依赖 ESM。

import { randomUUID } from 'node:crypto'
import { createAppService, CLAIM_PHRASE } from './app-routes.js'
import { AgentBrain, SOURCE_KIND } from './brain.js'
import { Agenda } from './core/agenda.js'
import { Driver, PHASE } from './core/driver.js'
import { appRejection, Pairing } from './pairing.js'
import { makeStore } from './store.js'

export const name = 'dsh-driver-core'
const PREFIX = '/dsh-driver-core/v1'
const MAX_BODY = 16 * 1024

// Gemini（独立 App 与 Google App 里的 Live 都算）、ChatGPT。可在 /config 改。
export const DEFAULT_ANCHORS = ['com.google.android.apps.bard', 'com.google.android.googlequicksearchbox', 'com.openai.chatgpt']

const SIGNALS = new Set(['foreground', 'live', 'audio', 'surface', 'reply', 'pause', 'stop'])

function send(res, status, body) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' })
  res.end(JSON.stringify(body))
}

async function readJson(req) {
  if (!String(req.headers['content-type'] ?? '').startsWith('application/json')) return { error: 415 }
  let size = 0
  const chunks = []
  for await (const c of req) {
    size += c.length
    if (size > MAX_BODY) return { error: 413 }
    chunks.push(c)
  }
  try { return { value: JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}') } } catch { return { error: 400 } }
}

function findAgent(ctx, id) {
  const agents = ctx.get?.('agents') ?? ctx.agents
  if (!agents || typeof id !== 'string') return undefined
  const ids = id.startsWith('session-') ? [id, id.slice(8)] : [id, `session-${id}`]
  for (const x of ids) {
    try { const a = agents.get(x); if (a !== undefined) return a } catch { /* not found */ }
  }
  return undefined
}

const strs = (v, max = 12, len = 120) => Array.isArray(v) ? v.filter((x) => typeof x === 'string' && x.trim()).slice(0, max).map((x) => x.trim().slice(0, len)) : undefined

/** 手机和插件在同一台设备上，时钟一致；离谱的时间戳一律换成现在。 */
const atOf = (v, now) => (Number.isFinite(v) && Math.abs(v - now) <= 60_000 ? v : now)

/** 一条 user/message 的文字（我们自己发的驾驶员消息不算）。 */
export function userText(event) {
  if (event?.type !== 'user/message') return ''
  const m = event.data
  if (m?.source?.kind === SOURCE_KIND) return ''
  return Array.isArray(m?.content) ? m.content.filter((b) => b?.type === 'text' && typeof b.text === 'string').map((b) => b.text).join('') : ''
}

export function createService({ store, brain, now = () => Date.now(), newSessionId = () => randomUUID() }) {
  let config = { anchors: DEFAULT_ANCHORS, memorySessionIds: [], ...store.loadConfig() }
  let active = null // {id, driver}
  const sessions = new Map() // 结束的也留着，方便查 state
  const waiters = new Set()
  const wake = () => { for (const w of [...waiters]) w() }

  const run = async (p) => { const r = await p; if (r.length) wake(); return r }

  return {
    get config() { return config },
    get active() { return active },
    setConfig(patch) {
      const next = { ...config }
      if (patch.brainSessionId === null || typeof patch.brainSessionId === 'string') next.brainSessionId = patch.brainSessionId ?? undefined
      const mem = strs(patch.memorySessionIds, 8, 256)
      if (mem) next.memorySessionIds = mem
      const anc = strs(patch.anchors, 16, 255)
      if (anc && anc.length) next.anchors = anc
      config = next
      store.saveConfig(config)
      return config
    },
    setProfile(patch) {
      const p = store.loadProfile()
      const goals = strs(patch.goals, 6, 80)
      const habits = strs(patch.habits, 8, 80)
      const next = { ...p, ...(goals ? { goals } : {}), ...(habits ? { habits } : {}) }
      store.saveProfile(next)
      return next
    },
    agenda() { return store.loadAgenda?.() ?? [] },
    async start(body) {
      if (active && active.driver.s.phase !== PHASE.ENDED) return { status: 409, body: { v: 1, error: { code: 'session-active', message: '已有一条进行中的主线' }, sessionId: active.id } }
      const id = newSessionId()
      const driver = new Driver({
        brain: brain ? (prompt) => brain.ask(prompt) : undefined,
        hidden: { memories: store.loadFishMemories(config.memorySessionIds), profile: store.loadProfile(), history: store.loadHistory() },
        append: store.appender(id),
        saveHistory: (h) => store.saveHistory(h),
        agenda: new Agenda(store.loadAgenda?.() ?? []),
        saveAgenda: (items) => store.saveAgenda?.(items),
      })
      active = { id, driver }
      sessions.set(id, driver)
      const anchors = strs(body.anchors, 16, 255) ?? config.anchors
      const directives = await run(driver.start({
        at: now(), topic: String(body.topic ?? '').slice(0, 80), task: String(body.task ?? '').slice(0, 120),
        minutes: body.minutes, anchors,
      }))
      return { status: 201, body: { v: 1, sessionId: id, anchors, directives, state: driver.state() } }
    },
    async signal(body) {
      const driver = sessions.get(String(body.sessionId ?? ''))
      if (!driver) return { status: 404, body: { v: 1, error: { code: 'no-session', message: 'sessionId' } } }
      const kind = body.kind
      if (!SIGNALS.has(kind)) return { status: 400, body: { v: 1, error: { code: 'bad-kind', message: String(kind) } } }
      const at = atOf(body.at, now())
      let p
      switch (kind) {
        case 'foreground': p = driver.foreground({ at, pkg: String(body.pkg ?? ''), allowed: typeof body.allowed === 'boolean' ? body.allowed : undefined }); break
        case 'live': p = driver.live({ at, active: body.active === true ? true : body.active === false ? false : null }); break
        case 'audio': {
          if (typeof body.assistantSpeaking !== 'boolean') return { status: 400, body: { v: 1, error: { code: 'bad-audio', message: 'assistantSpeaking' } } }
          p = driver.audio({ at, assistantSpeaking: body.assistantSpeaking })
          break
        }
        case 'surface': p = driver.surface({ at, cardId: String(body.cardId ?? ''), status: String(body.status ?? ''), via: String(body.via ?? 'page').slice(0, 20) }); break
        case 'reply': p = driver.reply({ at, cardId: String(body.cardId ?? ''), text: String(body.text ?? '').slice(0, 500), choice: body.choice === 'A' || body.choice === 'B' ? body.choice : undefined, channel: String(body.channel ?? 'tap').slice(0, 20) }); break
        case 'pause': p = driver.pause({ at, minutes: body.minutes, reason: String(body.reason ?? '').slice(0, 80) }); break
        case 'stop': p = driver.stop({ at, reason: String(body.reason ?? 'user').slice(0, 40) }); break
      }
      const directives = await run(p)
      return { status: 200, body: { v: 1, directives, state: driver.state() } }
    },
    async tick() {
      if (active) await run(active.driver.tick({ at: now() }))
    },
    async directives(sessionId, after, waitMs) {
      const driver = sessions.get(sessionId)
      if (!driver) return { status: 404, body: { v: 1, error: { code: 'no-session', message: 'sessionId' } } }
      let list = driver.drain(after)
      if (!list.length && waitMs > 0) {
        await new Promise((resolve) => {
          const w = () => { waiters.delete(w); clearTimeout(t); resolve() }
          const t = setTimeout(w, waitMs)
          waiters.add(w)
        })
        list = driver.drain(after)
      }
      return { status: 200, body: { v: 1, directives: list, phase: driver.s.phase } }
    },
    state(sessionId) {
      const driver = sessions.get(sessionId)
      if (!driver) return { status: 404, body: { v: 1, error: { code: 'no-session', message: 'sessionId' } } }
      return { status: 200, body: { v: 1, sessionId, state: driver.state() } }
    },
  }
}

export function apply(ctx) {
  const store = makeStore()
  let svc
  const brain = new AgentBrain({
    findAgent: (id) => findAgent(ctx, id),
    makeMessage: (text) => ({ id: randomUUID(), role: 'user', content: [{ type: 'text', text }], source: { kind: SOURCE_KIND } }),
    sessionId: () => svc?.config.brainSessionId,
  })
  svc = createService({ store, brain })
  const pairing = new Pairing()
  const app = createAppService({ store, pairing, brain, getConfig: () => svc.config })

  ctx.on('session/event', (session, event) => {
    try { brain.onSessionEvent(session?.id, event) } catch { /* 不打扰会话 */ }
    // 你在某个会话里说"驾驶员就用这个会话"：记下它作为出题会话
    try {
      if (session?.id && CLAIM_PHRASE.test(userText(event)) && svc.config.brainSessionId !== session.id) svc.setConfig({ brainSessionId: session.id })
    } catch { /* ignore */ }
  })
  ctx.on('tools/pre-execute', (exec, next) => {
    let denied
    try { denied = brain.guardTool(exec?.agent?.id ?? exec?.agent?.session?.id) } catch { denied = undefined }
    return denied ?? next()
  }, { global: true, prepend: true })
  ctx.on('agent/disposed', (agent) => { try { if (agent?.id) brain.onAgentGone(agent.id) } catch { /* ignore */ } })

  const ticker = setInterval(() => { svc.tick().catch(() => {}) }, 1_000)
  ticker.unref?.()
  ctx.effect?.(() => () => clearInterval(ticker))

  const rejection = (req) => {
    const connection = ctx.get?.('connection')
    return connection === undefined ? 503 : connection.requestRejection(req)
  }

  const appHandler = async (req, res, path) => {
    const denied = appRejection(req, pairing, { needToken: path !== '/app/pair' })
    if (denied !== undefined) { res.writeHead(denied, { 'cache-control': 'no-store' }); res.end(); return }
    if (req.method === 'GET' && path === '/app/ping') { const r = app.ping(); return send(res, r.status, r.body) }
    if (req.method !== 'POST') return send(res, 405, { v: 1, error: { code: 'method' } })
    const body = await readJson(req)
    if (body.error) return send(res, body.error, { v: 1, error: { code: 'bad-body' } })
    let r
    if (path === '/app/pair') r = app.pair(body.value)
    else if (path === '/app/compose') r = await app.compose(body.value)
    else if (path === '/app/report') r = app.report(body.value)
    else r = { status: 404, body: { v: 1, error: { code: 'not-found' } } }
    return send(res, r.status, r.body)
  }

  const handler = async (req, res) => {
    const appPath = new URL(req.url, 'http://local').pathname.slice(PREFIX.length)
    if (appPath.startsWith('/app/')) {
      try { return await appHandler(req, res, appPath) } catch (e) { return send(res, 500, { v: 1, error: { code: 'plugin-error', message: String(e?.message ?? e) } }) }
    }
    const denied = rejection(req)
    if (denied !== undefined) { res.writeHead(denied, { 'cache-control': 'no-store' }); res.end(); return }
    const url = new URL(req.url, 'http://local')
    const path = url.pathname.slice(PREFIX.length)
    try {
      if (req.method === 'POST') {
        const body = await readJson(req)
        if (body.error) return send(res, body.error, { v: 1, error: { code: 'bad-body', message: String(body.error) } })
        const v = body.value ?? {}
        if (path === '/session') { const r = await svc.start(v); return send(res, r.status, r.body) }
        if (path === '/signal') { const r = await svc.signal(v); return send(res, r.status, r.body) }
        if (path === '/config') return send(res, 200, { v: 1, config: svc.setConfig(v) })
        if (path === '/profile') return send(res, 200, { v: 1, profile: svc.setProfile(v) })
      }
      if (req.method === 'GET') {
        const sid = String(url.searchParams.get('sessionId') ?? '')
        if (path === '/directives') {
          const after = Number(url.searchParams.get('after') ?? 0) || 0
          const waitMs = Math.min(Math.max(Number(url.searchParams.get('waitMs') ?? 0) || 0, 0), 25_000)
          const r = await svc.directives(sid, after, waitMs)
          return send(res, r.status, r.body)
        }
        if (path === '/state') { const r = svc.state(sid); return send(res, r.status, r.body) }
        if (path === '/agenda') return send(res, 200, { v: 1, agenda: svc.agenda() })
        if (path === '/status') {
          return send(res, 200, { v: 1, config: svc.config, active: svc.active ? { sessionId: svc.active.id, phase: svc.active.driver.s.phase } : null, brain: brain.counters })
        }
      }
      return send(res, 404, { v: 1, error: { code: 'not-found', message: path } })
    } catch (e) {
      return send(res, 500, { v: 1, error: { code: 'plugin-error', message: String(e?.message ?? e) } })
    }
  }

  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(() => webCtx.webServer.register({ kind: 'prefix', path: PREFIX, handler }), 'dsh-driver-core: routes')
  })
}
