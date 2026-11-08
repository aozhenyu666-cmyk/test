// dsh-go-proactive, host half: lets the dsh-go App hand a proactive event to a DSH agent and read
// back what the agent said. See ../README.md for the protocol and lib/core.js for the states.
//
//   POST /dsh-go-proactive/v1/request   {v, requestId, eventId, kind, payload, sessionId?, deadlineMs}
//   GET  /dsh-go-proactive/v1/result?requestId=…&waitMs=…   long poll (≤ 25 s)
//   POST /dsh-go-proactive/v1/cancel    {requestId}
//   GET  /dsh-go-proactive/v1/status    configured session, live agents, counters
//   POST /dsh-go-proactive/v1/config    {sessionId}  the session proactive turns go to
//
// Every route requires what /api requires (Connection's Host/Origin fence plus the browser
// cookie or an API credential), checked here even when the webserver also guards.
// Zero dependencies, like dsh-go-bridge: it may be loaded by absolute path.
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { Desk, SOURCE_KIND } from './core.js'

export const name = 'dsh-go-proactive'
const PREFIX = '/dsh-go-proactive/v1'
const MAX_BODY = 16 * 1024

function configPath() {
  const home = process.env.DSH_HOME || join(homedir(), '.dsh')
  return join(home, 'storages', 'dsh-go-proactive', 'config.json')
}

function readConfig() {
  try { return JSON.parse(readFileSync(configPath(), 'utf8')) } catch { return {} }
}

function writeConfig(cfg) {
  const p = configPath()
  mkdirSync(join(p, '..'), { recursive: true })
  writeFileSync(p + '.tmp', JSON.stringify(cfg))
  renameSync(p + '.tmp', p)
}

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

/** Live agent by id, accepting ids with or without the `session-` prefix (dsh-speak-up's lesson). */
function findAgent(ctx, id) {
  const agents = ctx.get?.('agents') ?? ctx.agents
  if (!agents || typeof id !== 'string') return undefined
  const ids = id.startsWith('session-') ? [id, id.slice(8)] : [id, `session-${id}`]
  for (const x of ids) {
    try { const a = agents.get(x); if (a !== undefined) return a } catch { /* not found */ }
  }
  return undefined
}

function userMessage(text) {
  return { id: randomUUID(), role: 'user', content: [{ type: 'text', text }], source: { kind: SOURCE_KIND } }
}

export function apply(ctx) {
  let config = readConfig()
  const desk = new Desk({
    findAgent: (id) => findAgent(ctx, id),
    isArchived: (id) => {
      try { return (ctx.get?.('workspaceRegistry') ?? ctx.workspaceRegistry)?.archivedSessionIds?.includes(id) === true } catch { return false }
    },
    makeMessage: userMessage,
    defaultSession: () => config.sessionId,
    instruction: typeof config.instruction === 'string' && config.instruction.length <= 1000 ? config.instruction : undefined,
  })

  ctx.on('session/event', (session, event) => {
    try { desk.onSessionEvent(session?.id, event) } catch { /* never disturb the session */ }
  })
  // Hard limit at the tool layer (review r3, E): the prompt asks for no tools; this makes sure.
  // global: the registry dispatches with the agent as carrier, which would otherwise filter us out.
  // prepend: run before any other policy, so nothing can approve a call we deny.
  ctx.on('tools/pre-execute', (exec, next) => {
    let denied
    try { denied = desk.guardTool(exec?.agent?.id ?? exec?.agent?.session?.id) } catch { denied = undefined }
    return denied ?? next()
  }, { global: true, prepend: true })
  ctx.on('agent/disposed', (agent) => {
    try { if (agent?.id) desk.onAgentGone(agent.id) } catch { /* ignore */ }
  })
  const sweeper = setInterval(() => desk.sweep(), 5_000)
  sweeper.unref?.()
  ctx.effect?.(() => () => clearInterval(sweeper))

  const rejection = (req) => {
    const connection = ctx.get?.('connection')
    return connection === undefined ? 503 : connection.requestRejection(req)
  }

  const handler = async (req, res) => {
    const denied = rejection(req)
    if (denied !== undefined) { res.writeHead(denied, { 'cache-control': 'no-store' }); res.end(); return }
    const url = new URL(req.url, 'http://local')
    const path = url.pathname.slice(PREFIX.length)
    try {
      if (req.method === 'POST' && (path === '/request' || path === '/cancel' || path === '/config')) {
        const body = await readJson(req)
        if (body.error) return send(res, body.error, { v: 1, error: { code: body.error === 415 ? 'unsupported-media-type' : body.error === 413 ? 'too-large' : 'bad-request', message: 'body' } })
        if (path === '/request') { const r = desk.request(body.value); return send(res, r.status, r.body) }
        if (path === '/cancel') { const r = desk.cancel(String(body.value?.requestId ?? '')); return send(res, r.status, r.body) }
        const sid = body.value?.sessionId
        if (sid !== null && (typeof sid !== 'string' || sid.length > 256)) return send(res, 400, { v: 1, error: { code: 'bad-session-id', message: 'sessionId' } })
        config = { ...config, sessionId: sid ?? undefined }
        writeConfig(config)
        return send(res, 200, { v: 1, sessionId: config.sessionId ?? null })
      }
      if (req.method === 'GET' && path === '/result') {
        const waitMs = Math.min(Math.max(Number(url.searchParams.get('waitMs') ?? 0) || 0, 0), 25_000)
        const r = await desk.wait(String(url.searchParams.get('requestId') ?? ''), waitMs)
        return send(res, r.status, r.body)
      }
      if (req.method === 'GET' && path === '/status') {
        const agents = ctx.get?.('agents') ?? ctx.agents
        const live = (agents?.roots?.() ?? agents?.list?.() ?? []).map((a) => ({ id: a.id, status: a.status }))
        return send(res, 200, { v: 1, sessionId: config.sessionId ?? null, agents: live, counters: desk.counters })
      }
      return send(res, 404, { v: 1, error: { code: 'not-found', message: path } })
    } catch (e) {
      return send(res, 500, { v: 1, error: { code: 'plugin-error', message: String(e?.message ?? e) } })
    }
  }

  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(() => webCtx.webServer.register({ kind: 'prefix', path: PREFIX, handler }), 'dsh-go-proactive: routes')
  })
}
