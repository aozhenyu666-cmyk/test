// Unit tests for lib/core.js with a fake agent and hand-written session events
// (shapes copied from @deepseek-ai/dsh-agent-loop 0.2.1-rc.4). Run: node --test test/core.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Desk, validateRequest, assistantText, composeText } from '../lib/core.js'

function setup({ status = 'idle', throws, rejects } = {}) {
  let now = 1_000
  let n = 0
  const sent = []
  const agent = {
    id: 'session-a', status, inbox: { hasPending: false },
    followup(m) {
      if (throws) throw new Error('boom')
      sent.push(m)
      return rejects ? Promise.reject(new Error('later')) : undefined
    },
  }
  const desk = new Desk({
    findAgent: (id) => (id === 'session-a' ? agent : undefined),
    makeMessage: (text) => ({ id: `m${++n}`, role: 'user', content: [{ type: 'text', text }], source: { kind: 'dsh-go-proactive' } }),
    now: () => now,
    defaultSession: () => 'session-a',
  })
  return { desk, agent, sent, tick: (ms) => { now += ms } }
}
const req = (i, extra = {}) => ({ v: 1, requestId: `request-${i}`, eventId: `event-00${i}`, kind: 'app_enter', payload: { packageName: 'com.x' }, ...extra })
const say = (turn, text, extra = {}) => ({ type: 'assistant/message', data: { turn, step: 0, message: { content: [{ type: 'text', text }] }, ...extra } })
function runTurn(desk, msgId, turn, events) {
  desk.onSessionEvent('session-a', { type: 'turn/start', data: { turn } })
  desk.onSessionEvent('session-a', { type: 'user/message', data: { id: msgId, role: 'user' } })
  for (const e of events) desk.onSessionEvent('session-a', e)
}

test('validation', () => {
  assert.equal(validateRequest(null).error.code, 'bad-request')
  assert.equal(validateRequest({ requestId: 'x', eventId: 'event-001' }).error.code, 'bad-request-id')
  assert.equal(validateRequest({ ...req(1), v: 2 }).error.code, 'unsupported-version')
  assert.equal(validateRequest({ ...req(1), payload: { a: 1 } }).error.code, 'bad-payload')
  assert.equal(validateRequest({ ...req(1), deadlineMs: 1 }).value.deadlineMs, 5000)
  assert.equal(validateRequest({ ...req(1), deadlineMs: 10 ** 9 }).value.deadlineMs, 120000)
})

test('accepted → claimed → generated, with the last visible text of that turn', () => {
  const { desk, sent } = setup()
  const a = desk.request(req(1))
  assert.equal(a.status, 202)
  assert.equal(a.body.state, 'accepted')
  assert.equal(sent.length, 1)
  assert.ok(sent[0].content[0].text.includes('packageName=com.x'))
  runTurn(desk, sent[0].id, 4, [
    say(4, ''), // a tool-call step without text
    { type: 'assistant/message', data: { turn: 3, message: { content: [{ type: 'text', text: 'other turn' }] } } },
    say(4, '你好'),
  ])
  assert.equal(desk.result('request-1').body.state, 'claimed')
  assert.equal(desk.result('request-1').body.text, null, 'no text before the turn ends')
  desk.onSessionEvent('session-a', { type: 'turn/end', data: { turn: 4, reason: { kind: 'completed' } } })
  const r = desk.result('request-1').body
  assert.equal(r.state, 'generated')
  assert.equal(r.text, '你好')
  assert.equal(r.turn, 4)
})

test('a completed turn without text, an error, an abort, an interruption all fail', () => {
  for (const [events, code] of [
    [[], 'no-text'],
    [[say(1, 'half')], 'turn-error', 'error'],
    [[say(1, 'half')], 'turn-aborted', 'aborted'],
    [[say(1, 'half', { interrupted: true })], 'no-text'],
  ].map(([e, c, k]) => [[...e, { type: 'turn/end', data: { turn: 1, reason: { kind: k ?? 'completed', error: { message: 'x' } } } }], c])) {
    const { desk, sent } = setup()
    desk.request(req(1))
    runTurn(desk, sent[0].id, 1, events)
    const r = desk.result('request-1').body
    assert.equal(r.state, 'failed')
    assert.equal(r.failure.code, code)
    assert.equal(r.text, null)
  }
})

test('one followup per requestId and per eventId; one request at a time', () => {
  const { desk, sent } = setup()
  desk.request(req(1))
  assert.equal(desk.request(req(1)).status, 200, 'replay reports')
  assert.equal(desk.request({ ...req(1), eventId: 'event-009' }).body.error.code, 'request-id-conflict')
  assert.equal(desk.request({ ...req(2), eventId: 'event-001' }).body.error.code, 'event-in-flight')
  assert.equal(desk.request(req(3)).body.error.code, 'desk-busy')
  assert.equal(sent.length, 1)
})

test('busy, missing, archived and throwing agents are refused without sending', () => {
  assert.equal(setup({ status: 'running' }).desk.request(req(1)).body.error.code, 'agent-busy')
  const pend = setup(); pend.agent.inbox.hasPending = true
  assert.equal(pend.desk.request(req(1)).body.error.code, 'agent-busy')
  assert.equal(setup().desk.request(req(1, { sessionId: 'session-zzz' })).body.error.code, 'no-agent')
  const t = setup({ throws: true })
  assert.equal(t.desk.request(req(1)).body.error.code, 'followup-failed')
  assert.equal(t.desk.result('request-1').status, 404, 'nothing recorded')
  const noSession = new Desk({ findAgent: () => undefined, makeMessage: () => ({ id: 'm' }) })
  assert.equal(noSession.request(req(1)).body.error.code, 'no-session')
  const arch = new Desk({ findAgent: () => ({ status: 'idle', followup() {} }), makeMessage: () => ({ id: 'm' }), isArchived: () => true, defaultSession: () => 's' })
  assert.equal(arch.request(req(1)).body.error.code, 'archived')
})

test('a rejected followup promise fails the record instead of crashing the host', async () => {
  const { desk } = setup({ rejects: true })
  assert.equal(desk.request(req(1)).status, 202)
  await new Promise((r) => setImmediate(r))
  assert.equal(desk.result('request-1').body.failure.code, 'followup-rejected')
})

test('cancelled records never hand out text that arrives later', () => {
  const { desk, sent } = setup()
  desk.request(req(1))
  runTurn(desk, sent[0].id, 2, [])
  assert.equal(desk.cancel('request-1').body.state, 'cancelled')
  desk.onSessionEvent('session-a', say(2, 'late'))
  desk.onSessionEvent('session-a', { type: 'turn/end', data: { turn: 2, reason: { kind: 'completed' } } })
  const r = desk.result('request-1').body
  assert.equal(r.state, 'cancelled')
  assert.equal(r.text, null)
  assert.equal(desk.counters.lateText, 1)
  assert.equal(desk.request(req(2)).status, 202, 'the desk is free again')
})

test('deadline and agent going away fail open records', () => {
  const a = setup()
  a.desk.request(req(1))
  a.tick(45_000)
  assert.equal(a.desk.result('request-1').body.failure.code, 'deadline')
  const b = setup()
  b.desk.request(req(1))
  b.desk.onAgentGone('session-a')
  assert.equal(b.desk.result('request-1').body.failure.code, 'agent-gone')
})

test('long poll wakes on change', async () => {
  const { desk, sent } = setup()
  desk.request(req(1))
  const p = desk.wait('request-1', 5000)
  runTurn(desk, sent[0].id, 1, [])
  assert.equal((await p).body.state, 'claimed')
})

test('helpers', () => {
  assert.equal(assistantText({ message: { content: [{ type: 'reasoning', text: 'x' }, { type: 'text', text: ' a ' }, { type: 'tool-call' }] } }), 'a')
  assert.ok(composeText({ kind: 'k', payload: { a: 'b' } }, 'I').endsWith('kind=k\na=b'))
})

test('review r3 E: every tool call in a turn started by our message is denied, until that turn ends', () => {
  const { desk, sent } = setup()
  desk.request(req(1))
  assert.equal(desk.guardTool('session-a'), undefined, 'not before the turn takes our message')
  desk.onSessionEvent('session-a', { type: 'turn/start', data: { turn: 7 } })
  desk.onSessionEvent('session-a', { type: 'user/message', data: { id: sent[0].id } })
  assert.equal(desk.guardTool('session-a').kind, 'deny')
  assert.equal(desk.guardTool('session-b'), undefined, 'other sessions are untouched')
  desk.cancel('request-1')
  assert.equal(desk.guardTool('session-a').kind, 'deny', 'still our turn after a cancel or deadline')
  desk.onSessionEvent('session-a', { type: 'turn/end', data: { turn: 7, reason: { kind: 'completed' } } })
  assert.equal(desk.guardTool('session-a'), undefined, 'the next turn is the person\'s again')
  assert.equal(desk.counters.toolDenied, 2)
})

test('review r3 E: a message cancelled before it was claimed still runs tool-free when claimed', () => {
  const { desk, sent } = setup()
  desk.request(req(1))
  desk.cancel('request-1')
  desk.onSessionEvent('session-a', { type: 'turn/start', data: { turn: 2 } })
  desk.onSessionEvent('session-a', { type: 'user/message', data: { id: sent[0].id } })
  assert.equal(desk.guardTool('session-a').kind, 'deny')
  desk.onAgentGone('session-a')
  assert.equal(desk.guardTool('session-a'), undefined)
})
