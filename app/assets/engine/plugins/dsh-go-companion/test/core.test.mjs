import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Desk } from '../lib/core.js'

function fakeAgent(overrides = {}) {
  const sent = []
  return {
    id: 'session-abc',
    status: 'idle',
    inbox: { hasPending: false },
    sent,
    followup(msg) {
      sent.push(msg)
    },
    ...overrides,
  }
}

function makeDesk({ agent = fakeAgent(), cold = false, compose, changes = [] } = {}) {
  let n = 0
  const desk = new Desk({
    resolveAgent: async () => (agent ? { agent, cold } : undefined),
    compose: compose ?? (async () => ({ text: 'prompt', prompt: 'prompt', topic: { id: 'topic:a', kind: 'unfinished' } })),
    makeMessage: (text) => ({ id: 'msg-00000001', role: 'user', content: [{ type: 'text', text }] }),
    defaultSession: () => 'session-abc',
    onChange: (r) => changes.push(r),
    now: () => 1_800_000_000_000 + n++ * 10,
  })
  return { desk, agent, changes }
}

const req = {
  v: 1,
  requestId: 'req-00000001',
  eventId: 'evt-00000001',
  kind: 'scheduler',
  payload: {},
  deadlineMs: 30_000,
}

test('回合：accepted → claimed → generated', async () => {
  const { desk, agent, changes } = makeDesk()
  const r = await desk.request(req)
  assert.equal(r.status, 202)
  assert.equal(r.body.state, 'accepted')
  assert.equal(r.body.topicId, 'topic:a')
  assert.equal(agent.sent.length, 1)
  assert.match(agent.sent[0].content[0].text, /prompt/)

  desk.onSessionEvent('session-abc', { type: 'turn/start', data: { turn: 3 } })
  desk.onSessionEvent('session-abc', { type: 'user/message', data: { id: 'msg-00000001' } })
  assert.equal(desk.result('req-00000001').body.state, 'claimed')

  desk.onSessionEvent('session-abc', {
    type: 'assistant/message',
    data: { turn: 3, message: { content: [{ type: 'text', text: '面试结果出了吗？' }] } },
  })
  desk.onSessionEvent('session-abc', { type: 'turn/end', data: { turn: 3, reason: { kind: 'completed' } } })
  const done = desk.result('req-00000001').body
  assert.equal(done.state, 'generated')
  assert.equal(done.text, '面试结果出了吗？')
  assert.equal(changes.at(-1).state, 'generated')
  assert.equal(desk.guardTool('session-abc'), undefined)
})

test('回合：冷会话也能唤醒，并标记 cold', async () => {
  const { desk } = makeDesk({ cold: true })
  const r = await desk.request(req)
  assert.equal(r.status, 202)
  assert.equal(r.body.cold, true)
})

test('回合：没有话题时拒绝，不发送', async () => {
  const { desk, agent } = makeDesk({ compose: async () => ({ error: 'no-topic' }) })
  const r = await desk.request(req)
  assert.equal(r.status, 409)
  assert.equal(r.body.error.code, 'no-topic')
  assert.equal(agent.sent.length, 0)
})

test('回合：agent 忙时拒绝', async () => {
  const { desk } = makeDesk({ agent: fakeAgent({ status: 'running' }) })
  const r = await desk.request(req)
  assert.equal(r.status, 409)
  assert.equal(r.body.error.code, 'agent-busy')
})

test('回合：同一个 requestId 重放不重复发送', async () => {
  const { desk, agent } = makeDesk()
  await desk.request(req)
  const again = await desk.request(req)
  assert.equal(again.status, 200)
  assert.equal(agent.sent.length, 1)
})

test('回合：取消后晚到的文本不交出', async () => {
  const { desk } = makeDesk()
  await desk.request(req)
  desk.cancel('req-00000001')
  desk.onSessionEvent('session-abc', { type: 'turn/start', data: { turn: 1 } })
  desk.onSessionEvent('session-abc', { type: 'assistant/message', data: { turn: 1, message: { content: [{ type: 'text', text: 'x' }] } } })
  assert.equal(desk.result('req-00000001').body.state, 'cancelled')
  assert.equal(desk.result('req-00000001').body.text, null)
})
