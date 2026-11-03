import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, appendFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { createAppService } from '../lib/app-routes.js'
import { fromAppRequest } from '../lib/core/context.js'
import { BLACKLIST, formulaic } from '../lib/interactions.js'
import { makeStore } from '../lib/store.js'

const card = (text) => JSON.stringify({ kind: 'open', text })
const request = { kind: 'challenge', intent: 'gate', goals: ['12月省考行测'], task: '第3题黑点数量', cog: { state: 'ACTIVE_FLOW' } }
function service(replies, extraStore = {}) {
  const prompts = [], budgets = []
  const svc = createAppService({
    store: { listFishMemorySessions: () => [], loadFishMemories: () => [], ...extraStore },
    getConfig: () => ({ brainSessionId: 'test' }),
    brain: { ask: async (p, budget) => { prompts.push(p); budgets.push(budget); return replies.shift() ?? null } },
  })
  return { svc, prompts, budgets }
}

test('12条记忆（不因低重要性全部丢掉），素材优先，带上最近报告结论', async () => {
  const memories = Array.from({ length: 15 }, (_, i) => ({ text: `本人记录${i}：排除选项C`, importance: 0.3 }))
  assert.equal(fromAppRequest(request, memories).hidden.memories.length, 12)
  const { svc, prompts } = service([card('你上次写下「第3题选D，因为黑点递增」。\n有人说数量没变，你拿哪两格反驳？')], {
    loadFishMemories: () => memories,
    loadRecentReports: (limit) => { assert.equal(limit, 5); return [{ conclusion: '第3题选D，因为黑点递增' }] },
  })
  const r = await svc.compose(request)
  assert.equal(r.body.why, 'ok')
  assert.ok(prompts[0].includes('第3题选D，因为黑点递增'))
  assert.ok(prompts[0].includes('骰子类型：challenge'))
  assert.ok(prompts[0].includes('本人记录11'))
  assert.ok(!prompts[0].includes('本人记录12'))
})

test('模板含长素材也打回一次；仍公式化就用既有 card:null 契约', async () => {
  const { svc, prompts, budgets } = service([card('你要考12月省考行测。\n依据是什么？'), card('12月省考行测。\n下一步是什么？')])
  const r = await svc.compose(request)
  assert.equal(prompts.length, 2)
  assert.equal(r.body.card, null)
  assert.equal(r.body.why, 'formulaic')
  assert.ok(budgets[0] <= 4000 && budgets[1] <= 8000)
  assert.equal(formulaic('你留下的疑问：下一步是什么\n找一个能推翻原结论的例子。'), false)
  for (const t of BLACKLIST) assert.equal(formulaic('自己的素材'.repeat(20) + '\n' + t + '？'), true)
})

test('缺具体素材也重出一次，合格才返回；禁止凭空编造经历', async () => {
  const { svc, prompts } = service([card('你去年考了第一。举个反例。'), card('你写下「12月省考行测」。\n描述一个会用上黑点规律的题型。')])
  assert.equal((await svc.compose(request)).body.why, 'ok')
  assert.equal(prompts.length, 2)
  const bad = service([card('你去年考了第一。'), card('你上次选C。')])
  assert.equal((await bad.svc.compose(request)).body.why, 'missing-material')
})

test('旧请求和未知互动字段保留原卡片契约；无素材诚实返回', async () => {
  const old = service([JSON.stringify({ kind: 'ab', text: '黑点更多还是更少？', options: { A: '更多', B: '更少' } })])
  assert.equal((await old.svc.compose({ kind: 'future-kind' })).body.card.kind, 'ab')
  assert.equal((await service([]).svc.compose({ kind: 'recall' })).body.why, 'no-material')
})

test('报告只读最近五行，坏的尾行不会使出题失败', () => {
  const root = mkdtempSync(join(tmpdir(), 'reports-'))
  const store = makeStore(root)
  for (let i = 0; i < 7; i++) store.appendReport({ conclusion: `结论${i}` })
  assert.deepEqual(store.loadRecentReports(5).map((x) => x.conclusion), ['结论2', '结论3', '结论4', '结论5', '结论6'])
  appendFileSync(join(root, 'storages/dsh-driver-core/reports.jsonl'), '{broken\n')
  assert.deepEqual(store.loadRecentReports(5).map((x) => x.conclusion), ['结论3', '结论4', '结论5', '结论6'])
})
