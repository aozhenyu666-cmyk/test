import { test } from 'node:test'
import assert from 'node:assert/strict'
import { visibleScopes } from '../lib/model.js'
import { extractAutoFacts } from '../lib/auto_facts.js'

test('auto_facts: 从日常对话零成本自动提取承诺与 App 规则偏好', () => {
  const f1 = extractAutoFacts('我今晚九点前要把周报第二部分写完并导出 PDF')
  assert.ok(f1.length >= 1)
  assert.equal(f1[0].kind, 'promise')
  assert.equal(f1[0].importance, 2)
  assert.match(f1[0].text, /周报第二部分/)

  const f2 = extractAutoFacts('B站我只是用来看公开课，别算纯游戏')
  assert.ok(f2.some((x) => x.kind === 'preference'))

  // 无意义口水话跳过，不污染记忆库
  assert.deepEqual(extractAutoFacts('嗯嗯好的'), [])
})

test('visibleScopes: 支持跨会话数组（鱼丸 + 鲸澜 + global 互通）', () => {
  const scopes = visibleScopes(['session:sis', 'session:big', 'global'])
  assert.ok(scopes.includes('global'))
  assert.ok(scopes.includes('session:sis'))
  assert.ok(scopes.includes('session:big'))
})
