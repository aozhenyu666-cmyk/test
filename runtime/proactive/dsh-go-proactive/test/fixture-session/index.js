// TEST ONLY: creates one live session on request so the end-to-end test has an idle agent.
// The production plugin never creates or wakes sessions.
export const name = 'dsh-go-proactive-fixture'
export function apply(ctx) {
  ctx.inject(['webServer'], (webCtx) => {
    // Simulates the person typing into the session (a plain user message, like the composer).
    webCtx.effect(() => webCtx.webServer.register({
      kind: 'exact', path: '/fixture/user-says',
      handler: async (req, res) => {
        const denied = ctx.get('connection')?.requestRejection(req)
        if (denied !== undefined) { res.writeHead(denied); res.end(); return }
        let t = ''; for await (const c of req) t += c
        const { sessionId, text } = JSON.parse(t)
        const agent = ctx.get('agents').get(sessionId)
        agent.followup({ id: crypto.randomUUID(), role: 'user', content: [{ type: 'text', text }], source: { kind: 'user' } })
        res.writeHead(200, { 'content-type': 'application/json' }); res.end('{}')
      },
    }))
    webCtx.effect(() => webCtx.webServer.register({
      kind: 'exact', path: '/fixture/create-session',
      handler: async (req, res) => {
        const denied = ctx.get('connection')?.requestRejection(req)
        if (denied !== undefined) { res.writeHead(denied); res.end(); return }
        try {
          const sc = ctx.get('sessionController')
          const r = await sc.create({ cwd: process.env.FIXTURE_CWD || '/tmp' })
          res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(r))
        } catch (e) {
          res.writeHead(500, { 'content-type': 'application/json' }); res.end(JSON.stringify({ error: String(e?.stack ?? e) }))
        }
      },
    }))
  })
}
