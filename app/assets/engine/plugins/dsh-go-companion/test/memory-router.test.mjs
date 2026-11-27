import test from 'node:test'
import assert from 'node:assert/strict'
import { routeHybridMemory, MEMORY_BUDGET_DEFAULT } from '../lib/memory_router.js'

test('三层省 Token 混合记忆路由：预算硬上限 + 精准命中临时库/关键词 + 确定性随机涌现 + 节省率计算', () => {
  const now = 1_700_200_000_000
  const largeFacts = Array.from({ length: 45 }, (_, i) => ({
    id: `fact-${i}`,
    text: `第 ${i + 1} 条历史背景事实：关于行测资料分析、申论对策以及过往作息与博弈复盘的较长说明文档条目`,
    importance: i % 3 === 0 ? 3 : 2,
    pinned: i === 0,
    updatedAt: now - (i + 10) * 3_600_000,
    tags: i === 12 ? ['判断推理'] : ['通用'],
  }))

  const stagingItems = [
    {
      id: 'stg-now',
      at: now - 15 * 60_000,
      kind: 'declaration',
      category: '刷题',
      text: '【正在刷题】粉笔判断推理削弱论证 15 题，卡在另有他因与无关对比选项辨析',
      tags: ['固执申报', '刷题'],
    },
  ]

  const knowledgePool = [
    {
      id: 'k-1',
      claim: '削弱论证中“另有他因”比样本量质疑更直接切断因果链。',
      source: 'quiz',
      weight: 1.4,
      seenCount: 0,
      status: 'active',
    },
    {
      id: 'k-2',
      claim: '基期比重与现期比重差值若 a < b，去年比重更大。',
      source: 'quiz',
      weight: 1.2,
      seenCount: 1,
      status: 'active',
    },
  ]

  const res = routeHybridMemory({
    personaSummary: '鱼丸在场不端架子，鲸澜只抓结构漏洞，严禁老妈子催办。',
    stagingItems,
    sqliteFacts: largeFacts,
    episodes: [{ id: 'ep-1', text: '昨晚在桌前熬到零点差点滑去刷B站但及时踩了刹车', at: now - 12 * 3_600_000 }],
    knowledgePool,
    queryText: '削弱论证 另有他因 判断推理',
    sceneTags: ['cat:刷题'],
    recentDrawnIds: [],
    seed: 20261127,
    budgetTokens: MEMORY_BUDGET_DEFAULT,
  }, now)

  assert.ok(res.injectedTokens <= MEMORY_BUDGET_DEFAULT)
  assert.ok(res.fullPoolTokensEstimate > res.injectedTokens)
  assert.ok(res.savedPct > 40)
  assert.equal(res.explain.preciseHits[0].id, 'stg-now')
  assert.ok(res.explain.preciseHits.length <= 3)
  assert.ok(res.explain.randomSparks.length >= 1 && res.explain.randomSparks.length <= 2)
  assert.match(res.promptBlock, /【设定底稿】/)
  assert.match(res.promptBlock, /【精准上下文/)
  assert.match(res.promptBlock, /【随机涌现/)

  // Exclude already drawn IDs -> should pick different sparks
  const res2 = routeHybridMemory({
    stagingItems,
    sqliteFacts: largeFacts,
    episodes: [{ id: 'ep-1', text: '昨晚在桌前熬到零点差点滑去刷B站但及时踩了刹车', at: now - 12 * 3_600_000 }],
    knowledgePool,
    recentDrawnIds: res.drawnIds,
    seed: 20261128,
  }, now)
  assert.notDeepEqual(res2.drawnIds, res.drawnIds)
})
