// 公约纯规则：解析 / 渲染往返、改严立即改松等 3 天、鱼只能提议、按场景挑进上下文。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  COOLDOWN_MS, DRAFT_RULES, addProposal, classifyCovenant, covenantBlock, covenantLines, insertRuleInText, nextIdIn,
  parseCovenant, renderCovenant, renderPendingCovenant, renderProposals, replaceRuleInText, syncCovenant,
} from '../lib/covenant.js'

const draft = [...DRAFT_RULES]

test('草稿渲染成 公约.md 再读回来一字不差', () => {
  const text = renderCovenant(draft)
  assert.match(text, /## 一、铁律/)
  assert.match(text, /### 2\.1 他说累了想歇/)
  const back = parseCovenant(text)
  assert.deepEqual(back, draft.map((r) => ({ ...r, scenes: [...r.scenes] })))
})

test('他手写的格式：多行做法、全角冒号、未知场景被忽略、读不懂的块跳过', () => {
  const rules = parseCovenant([
    '# 随便', '## 二、处置',
    '### 2.20 他打游戏超时', '- 做法：先提醒余额', '  然后叫鲸澜', '- 归谁：鱼丸', '- 硬度：建议', '- 场景：干活、月球',
    '### 2.21 没有做法的', '- 归谁：都',
    '### x.1 坏编号', '- 做法：…',
  ].join('\n'))
  assert.equal(rules.length, 1)
  assert.deepEqual(rules[0], { id: '2.20', title: '他打游戏超时', do: '先提醒余额 然后叫鲸澜', who: '鱼丸', hardness: '建议', scenes: ['干活'] })
})

test('改严立即、改松等 3 天、改回去撤销', () => {
  const a = draft
  const added = [...a, { id: '2.30', title: '新规矩', do: '做', who: '都', hardness: '默认', scenes: ['任何时候'] }]
  assert.deepEqual(Object.keys(classifyCovenant(a, added).tighten), ['2.30'])
  // 降硬度、删一条、改铁律的字 → 放松
  const down = a.map((r) => (r.id === '2.1' ? { ...r, hardness: '建议' } : r))
  assert.ok('2.1' in classifyCovenant(a, down).loosen)
  assert.equal(classifyCovenant(a, a.filter((r) => r.id !== '1.2')).loosen['1.2'], null)
  const iron = a.map((r) => (r.id === '1.4' ? { ...r, do: '可以给一点' } : r))
  assert.ok('1.4' in classifyCovenant(a, iron).loosen)
  // 默认的字改了：立即
  const wording = a.map((r) => (r.id === '2.7' ? { ...r, do: '一句话就够' } : r))
  assert.ok('2.7' in classifyCovenant(a, wording).tighten)

  const t0 = 1_800_000_000_000
  const s1 = syncCovenant({ rules: a, pending: {} }, a.filter((r) => r.id !== '1.2'), t0)
  assert.ok(s1.rules.some((r) => r.id === '1.2'), '删铁律不立即生效')
  assert.ok(s1.pending['1.2'])
  const s2 = syncCovenant({ rules: s1.rules, pending: s1.pending }, a.filter((r) => r.id !== '1.2'), t0 + COOLDOWN_MS + 1)
  assert.ok(!s2.rules.some((r) => r.id === '1.2'), '3 天后生效')
  const s3 = syncCovenant({ rules: s1.rules, pending: s1.pending }, a, t0 + 1000)
  assert.deepEqual(s3.pending, {}, '改回去就撤销')
  assert.match(renderPendingCovenant(s1.pending, a, () => 'D'), /删掉「封印只能加码」/)
})

test('在原文里替换 / 删除 / 插入一条，其余内容不动', () => {
  const text = renderCovenant(draft) + '\n我自己的备注\n'
  const r = { id: '2.30', title: '新的', do: '做法', who: '都', hardness: '默认', scenes: ['深夜'] }
  const ins = insertRuleInText(text, r)
  assert.ok(parseCovenant(ins).some((x) => x.id === '2.30'))
  assert.ok(ins.indexOf('### 2.30') < ins.indexOf('## 三、分工'), '插在第二章末尾')
  assert.match(ins, /我自己的备注/)
  const del = replaceRuleInText(ins, '2.30', null)
  assert.ok(!parseCovenant(del).some((x) => x.id === '2.30'))
  const rep = replaceRuleInText(text, '2.7', { ...draft.find((x) => x.id === '2.7'), do: '一句话' })
  assert.equal(parseCovenant(rep).find((x) => x.id === '2.7').do, '一句话')
  assert.equal(nextIdIn(draft, 2), '2.16')
})

test('鱼只能提议：要理由、每条鱼最多 3 条在等批', () => {
  let list = []
  const base = { by: 'strategist', chapter: 2, title: '他找借口', do: '点名借口', who: '都', hardness: '默认', scenes: ['深夜'], why: '连着三晚同一个借口' }
  for (let i = 0; i < 3; i++) {
    const r = addProposal(list, base, 1000 + i)
    assert.equal(r.ok, true)
    list = r.proposals
  }
  assert.equal(addProposal(list, base, 2000).error, 'too-many')
  assert.equal(addProposal([], { ...base, why: '' }, 1).error, 'no-why')
  assert.equal(addProposal(list, { ...base, by: 'companion' }, 3000).ok, true)
  assert.match(renderProposals(list), /鲸澜 · .* · 放进第二章/)
})

test('进上下文：铁律全带，默认对上场景才带，只带归自己的', () => {
  const night = covenantLines(draft, { role: 'companion', scenes: ['深夜'] })
  assert.ok(night.some((l) => l.includes('1.1 急事永远放行')))
  assert.ok(night.some((l) => l.includes('2.7 深夜还在刷')))
  assert.ok(!night.some((l) => l.includes('2.8 早上刚醒')))
  assert.ok(!night.some((l) => l.includes('3.1 鲸澜管计划')), '归鲸澜的不给鱼丸')
  assert.ok(night.findIndex((l) => l.includes('2.7')) < night.findIndex((l) => l.includes('2.2')), '对上具体场景的排在前面')
  const small = covenantLines(draft, { role: 'strategist', scenes: [], maxChars: 200 })
  assert.ok(small.join('').length <= 400)
  assert.match(covenantBlock(draft, { role: 'strategist', scenes: ['对账'] }), /按公约 x\.y/)
})
