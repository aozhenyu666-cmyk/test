// Live end-to-end check of dsh-go-proactive against a real DSH engine (`dsh web`) whose model is
// a local Messages-API mock (test/mock-messages.mjs). Offline: nothing leaves 127.0.0.1.
//
//   node test/e2e-live.mjs <dsh-bin> <port> <mock-port> <mock-log>
//
// Checks, in order: guard, no session, real followup turn → generated text that came from the
// model, replay of the same requestId (no second model call), same eventId in flight, agent busy,
// cancel while the turn runs (late text never handed out), deadline.
import { spawn } from 'node:child_process'
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { request as httpRequest } from 'node:http'

const [dshBin, portArg, mockPortArg, mockLog] = process.argv.slice(2)
const port = Number(portArg)
const here = dirname(fileURLToPath(import.meta.url))
const pluginIndex = join(here, '..', 'lib', 'index.js')
const fixtureIndex = join(here, 'fixture-session', 'index.js')
const work = mkdtempSync(join(tmpdir(), 'proactive-e2e-'))
const patch = join(work, 'proactive.patch.yml')
writeFileSync(patch, `- insert:\n    - id: dsh-go-proactive\n      name: ${pluginIndex}\n    - id: proactive-fixture\n      name: ${fixtureIndex}\n`)

let failures = 0
const check = (ok, what) => { console.log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failures++ }
const mockCalls = () => { try { return readFileSync(mockLog, 'utf8').trim().split('\n').filter((l) => l.includes('/messages')).length } catch { return 0 } }

const child = spawn(dshBin, ['--profile', 'web', '--patch', patch, '--no-open', '--host', '127.0.0.1', '--port', String(port)], {
  env: { ...process.env, DSH_HOME: join(work, 'home'), DEEPSEEK_API_KEY: 'sk-mock-0000000000000000', DEEPSEEK_BASE_URL: `http://127.0.0.1:${mockPortArg}/anthropic`, NO_PROXY: '127.0.0.1,localhost', no_proxy: '127.0.0.1,localhost', FIXTURE_CWD: work },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let out = ''
child.stdout.on('data', (d) => { out += d })
child.stderr.on('data', (d) => { out += d })

function http(method, path, { body, cookie } = {}) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : Buffer.from(JSON.stringify(body))
    const req = httpRequest({ host: '127.0.0.1', port, method, path, headers: {
      ...(cookie ? { cookie } : {}), ...(data ? { 'content-type': 'application/json', 'content-length': data.length } : {}),
    } }, (res) => {
      let t = ''
      res.on('data', (c) => { t += c })
      res.on('end', () => { let j; try { j = JSON.parse(t) } catch { j = t } resolve({ status: res.statusCode, headers: res.headers, body: j }) })
    })
    req.on('error', reject)
    if (data) req.write(data)
    req.end()
  })
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

try {
  let token = ''
  for (let i = 0; i < 240 && !token; i++) {
    const m = out.match(/dsh web: http:\/\/127\.0\.0\.1:\d+\/\?token=([A-Za-z0-9_-]+)/)
    if (m) token = m[1]; else await sleep(500)
  }
  check(!!token, 'dsh web started and printed a launch token')
  if (!token) throw new Error(out.slice(-2000))
  const login = await http('GET', `/?token=${token}`)
  const cookie = [].concat(login.headers['set-cookie'] ?? []).find((c) => c.startsWith('dsh-auth-'))?.split(';')[0]
  check(!!cookie, 'logged in with the launch token')
  const C = { cookie }
  const P = '/dsh-go-proactive/v1'

  check((await http('GET', `${P}/status`)).status === 401, 'routes refuse requests without credentials (401)')
  const st = await http('GET', `${P}/status`, C)
  check(st.status === 200 && st.body.v === 1, 'status with cookie')

  const noSession = await http('POST', `${P}/request`, { ...C, body: { v: 1, requestId: 'req-00000001', eventId: 'evt-00000001', kind: 'app_enter', payload: {} } })
  check(noSession.status === 409 && noSession.body.error.code === 'no-session', `no configured session → 409 no-session (${noSession.status} ${noSession.body?.error?.code})`)

  const created = await http('POST', '/fixture/create-session', C)
  const sessionId = created.body.sessionId
  check(created.status === 200 && typeof sessionId === 'string', `fixture created a live session ${sessionId}`)
  const ghost = await http('POST', `${P}/request`, { ...C, body: { requestId: 'req-ghost001', eventId: 'evt-ghost001', sessionId: 'session-does-not-exist' } })
  check(ghost.status === 409 && ghost.body.error.code === 'no-agent', 'unknown session → 409 no-agent (never created or woken)')
  check((await http('POST', `${P}/config`, { ...C, body: { sessionId } })).status === 200, 'configured the target session')

  // A real turn.
  const before = mockCalls()
  const req1 = { v: 1, requestId: 'req-00000002', eventId: 'evt-00000002', kind: 'app_enter', payload: { packageName: 'com.example.test', localTime: '09:15' }, deadlineMs: 60000 }
  const a = await http('POST', `${P}/request`, { ...C, body: req1 })
  check(a.status === 202 && a.body.state === 'accepted', `request → 202 accepted (${a.status} ${a.body.state})`)
  let r = a
  for (let i = 0; i < 10 && r.body.state !== 'generated' && r.body.state !== 'failed'; i++) {
    r = await http('GET', `${P}/result?requestId=req-00000002&waitMs=10000`, C)
  }
  check(r.body.state === 'generated', `turn ended → generated (${r.body.state} ${JSON.stringify(r.body.failure)})`)
  check(typeof r.body.text === 'string' && r.body.text.startsWith('MOCK_REPLY: 你好'), `text is the model's reply: ${r.body.text}`)
  check(r.body.claimedAt !== null && Number.isInteger(r.body.turn), `claimed into turn ${r.body.turn}`)
  const sent = readFileSync(mockLog, 'utf8').trim().split('\n').slice(before).join('\n')
  check(sent.includes('dsh-go-proactive') || sent.includes('com.example.test'), 'the model request carried the event facts')

  // Replay: same requestId → same record, no second model call.
  const callsAfter = mockCalls()
  const replay = await http('POST', `${P}/request`, { ...C, body: req1 })
  check(replay.status === 200 && replay.body.state === 'generated', 'replaying the requestId reports the record (200), does not resend')
  const conflict = await http('POST', `${P}/request`, { ...C, body: { ...req1, eventId: 'evt-other0001' } })
  check(conflict.status === 409 && conflict.body.error.code === 'request-id-conflict', 'same requestId, other eventId → 409')
  await sleep(500)
  check(mockCalls() === callsAfter, 'no extra model call from the replay')

  // Busy and cancel: slow the model down via the mock's control file.
  writeFileSync(join(dirname(mockLog), 'delay'), '4000')
  const b = await http('POST', `${P}/request`, { ...C, body: { ...req1, requestId: 'req-00000003', eventId: 'evt-00000003' } })
  check(b.status === 202, 'second event accepted while idle')
  const sameEvent = await http('POST', `${P}/request`, { ...C, body: { ...req1, requestId: 'req-00000004', eventId: 'evt-00000003' } })
  check(sameEvent.status === 409 && sameEvent.body.error.code === 'event-in-flight', 'same eventId with a new requestId while in flight → 409 event-in-flight')
  await sleep(800)
  const busy = await http('POST', `${P}/request`, { ...C, body: { ...req1, requestId: 'req-00000005', eventId: 'evt-00000005' } })
  check(busy.status === 409 && ['agent-busy', 'desk-busy'].includes(busy.body.error.code), `another event while a turn runs → 409 ${busy.body.error?.code}`)
  const c = await http('POST', `${P}/cancel`, { ...C, body: { requestId: 'req-00000003' } })
  check(c.body.state === 'cancelled', 'cancel while the turn runs → cancelled')
  await sleep(5000)
  const late = await http('GET', `${P}/result?requestId=req-00000003`, C)
  check(late.body.state === 'cancelled' && late.body.text === null, 'the turn finished later but its text is never handed out')
  const counters = (await http('GET', `${P}/status`, C)).body.counters
  check(counters.lateText >= 1, `late text was seen and dropped (lateText=${counters.lateText})`)

  // The person is talking to the agent themselves: a proactive request must not queue behind it.
  writeFileSync(join(dirname(mockLog), 'delay'), '3000')
  const typed = await http('POST', '/fixture/user-says', { ...C, body: { sessionId, text: 'hello from the person' } })
  check(typed.status === 200, 'fixture: the person sends a message (agent starts a turn)')
  await sleep(500)
  const userBusy = await http('POST', `${P}/request`, { ...C, body: { ...req1, requestId: 'req-00000007', eventId: 'evt-00000007' } })
  check(userBusy.status === 409 && userBusy.body.error.code === 'agent-busy', `agent running the person's turn → 409 agent-busy (${userBusy.body.error?.code})`)
  await sleep(3500)

  // Review r3, E: the tool layer, not the prompt, stops tools in a proactive turn.
  writeFileSync(join(dirname(mockLog), 'delay'), '0')
  const deniedBefore = (await http('GET', `${P}/status`, C)).body.counters.toolDenied
  const probe = await http('POST', `${P}/request`, { ...C, body: { ...req1, requestId: 'req-00000008', eventId: 'evt-00000008', kind: 'tool_probe', payload: { marker: 'TOOL_PROBE' } } })
  check(probe.status === 202, 'tool-probe event accepted')
  let pr = probe
  for (let i = 0; i < 10 && !['generated', 'failed'].includes(pr.body.state); i++) pr = await http('GET', `${P}/result?requestId=req-00000008&waitMs=10000`, C)
  const deniedAfter = (await http('GET', `${P}/status`, C)).body.counters.toolDenied
  check(deniedAfter > deniedBefore, `the model's tool call in a proactive turn was denied at tools/pre-execute (toolDenied ${deniedBefore} → ${deniedAfter})`)
  check(pr.body.state === 'generated' && /error=true/.test(pr.body.text) && /tools are disabled in a proactive turn/.test(pr.body.text),
    `the model saw an error tool result naming the guard: ${pr.body.text}`)
  // Control: the person's own turn may still use the same tool.
  const ctl = await http('POST', '/fixture/user-says', { ...C, body: { sessionId, text: 'TOOL_PROBE from the person' } })
  check(ctl.status === 200, 'fixture: the person asks for a tool themselves')
  await sleep(4000)
  const deniedCtl = (await http('GET', `${P}/status`, C)).body.counters.toolDenied
  check(deniedCtl === deniedAfter, `the person's own turn is not restricted (toolDenied stays ${deniedCtl})`)
  // Only the newest tool result counts: the history still holds the earlier, denied, proactive one.
  const lastReq = JSON.parse(readFileSync(mockLog, 'utf8').trim().split('\n').filter((l) => l.includes('/messages')).at(-1) ?? '{}')
  const lastMsg = (JSON.parse(lastReq.body ?? '{}').messages ?? []).at(-1) ?? {}
  const lastResult = (lastMsg.content ?? []).find((b) => b.type === 'tool_result')
  check(lastResult !== undefined && lastResult.is_error !== true && !JSON.stringify(lastResult).includes('tools are disabled in a proactive turn'),
    `and its tool actually ran (is_error=${lastResult?.is_error})`)

  // Deadline: model slower than the request's deadline.
  writeFileSync(join(dirname(mockLog), 'delay'), '9000')
  const d = await http('POST', `${P}/request`, { ...C, body: { ...req1, requestId: 'req-00000006', eventId: 'evt-00000006', deadlineMs: 5000 } })
  check(d.status === 202, 'slow request accepted')
  let dr = d
  for (let i = 0; i < 4 && !['failed', 'generated'].includes(dr.body.state); i++) dr = await http('GET', `${P}/result?requestId=req-00000006&waitMs=3000`, C)
  check(dr.body.state === 'failed' && dr.body.failure?.code === 'deadline', `deadline passed → failed/deadline (${dr.body.state} ${dr.body.failure?.code})`)
  writeFileSync(join(dirname(mockLog), 'delay'), '0')
} catch (e) {
  failures++
  console.log('FAIL', e?.stack ?? e)
} finally {
  child.kill('SIGTERM')
  console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILED`)
  setTimeout(() => process.exit(failures === 0 ? 0 : 1), 500)
}
