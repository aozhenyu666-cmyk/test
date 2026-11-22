import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { DatabaseSync } from 'node:sqlite'
import { DDL as MEMORY_DDL } from '../../dsh-go-memory/lib/store.js'
import {
  BOUT_DDL, ACTIVATIONS, CLOSE_REASONS, DELIVERABLE_FORMS, PROTOCOL_STATUS, VERDICT_LAYERS,
  addBeat, addDeliverable, addEvidence, addVerdict, closeBout, deleteBout, endEscalation, ensureBoutSchema,
  getBout, getDayPlan, listBeats, listBouts, listDeliverables, listEscalations, listEvidence, listProtocols,
  listVerdicts, migrateLegacyDayPlan, openBout, openBoutsDb, rebuildFromLedger, rebuildFromLedgerFile,
  runningBout, saveDayPlan, startEscalation, updateBout, upsertProtocol,
} from '../lib/bouts.js'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = dirname(HERE)
const NOW = Date.parse('2026-11-12T09:00:00+08:00')

/** 一个临时 home + 打开好的库；用完删目录。 */
function withDb(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-bouts-'))
  const db = openBoutsDb(join(home, 'fish-memory', 'fish-memory.sqlite'))
  try {
    assert.ok(db, '库应该能打开')
    return fn(db, home)
  } finally {
    try {
      db.close()
    } catch {}
    rmSync(home, { recursive: true, force: true })
  }
}

function ledgerLines(home) {
  const file = join(home, 'companion', 'ledger.jsonl')
  if (!existsSync(file)) return []
  return readFileSync(file, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
}

function tables(db) {
  return db.prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").all().map((r) => r.name)
}

// ── 建表与迁移机制 ─────────────────────────────────────────────

test('J1 表结构：局的八张表 DDL 与 dsh-go-memory 的迁移 DDL 逐条相同（改一处忘一处会被这里抓住）', () => {
  const missing = BOUT_DDL.filter((sql) => !MEMORY_DDL.includes(sql))
  assert.deepEqual(missing, [])
  assert.equal(BOUT_DDL.length, 15)
})

test('J1 表结构：打开库时八张表全部建出来', () => {
  withDb((db) => {
    const names = tables(db)
    for (const t of ['bouts', 'beats', 'evidence', 'deliverables', 'verdicts', 'escalations', 'protocols', 'day_plans']) {
      assert.ok(names.includes(t), '缺表 ' + t)
    }
    // 只建局自己的表：不碰记忆插件的 facts/topics/meta，谁的表谁建
    assert.equal(names.includes('meta'), false)
    assert.equal(names.includes('facts'), false)
  })
})

test('J1 表结构：没有文件路径时返回 null，不抛错', () => {
  assert.equal(openBoutsDb(''), null)
})

test('J1 表结构：ensureBoutSchema 可重复调用（每次开库全跑一遍也不炸）', () => {
  withDb((db) => {
    ensureBoutSchema(db)
    ensureBoutSchema(db)
    assert.ok(tables(db).includes('bouts'))
  })
})

test('J1 只有一个局在进行中：数据库层面拦住第二个', () => {
  withDb((db) => {
    assert.deepEqual(openBout(db, { protocolId: 'p1', now: NOW }), { ok: true, id: 'bout:' + NOW + ':1' })
    const second = openBout(db, { protocolId: 'p2', now: NOW + 1000 })
    assert.deepEqual(second, { ok: false, error: 'busy' })
    assert.equal(db.prepare('SELECT COUNT(*) AS n FROM bouts').get().n, 1)
  })
})

// ── bouts 增删查改 ────────────────────────────────────────────

test('J1 开局：写入一行并返回 id', () => {
  withDb((db) => {
    const r = openBout(db, { protocolId: 'study-video', openedBy: 'plan', plannedAt: NOW - 60_000, department: '行为管理', now: NOW })
    assert.equal(r.ok, true)
    const bout = getBout(db, r.id)
    assert.equal(bout.protocolId, 'study-video')
    assert.equal(bout.openedBy, 'plan')
    assert.equal(bout.plannedAt, NOW - 60_000)
    assert.equal(bout.openedAt, NOW)
    assert.equal(bout.status, 'running')
    assert.equal(bout.department, '行为管理')
  })
})

test('J1 开局：没有协议 id 就拒绝', () => {
  withDb((db) => {
    assert.deepEqual(openBout(db, { now: NOW }), { ok: false, error: 'no-protocol' })
    assert.deepEqual(openBout(db, { protocolId: '   ', now: NOW }), { ok: false, error: 'no-protocol' })
  })
})

test('J1 开局：没有库时拒绝而不是假装成功', () => {
  assert.deepEqual(openBout(null, { protocolId: 'p' }), { ok: false, error: 'no-db' })
})

test('J1 收局：状态、收局时间、收局原因都落下', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    assert.deepEqual(closeBout(db, id, { reason: 'done', now: NOW + 600_000 }), { ok: true })
    const bout = getBout(db, id)
    assert.equal(bout.status, 'closed')
    assert.equal(bout.closedAt, NOW + 600_000)
    assert.equal(bout.closeReason, 'done')
  })
})

test('J1 收局：重复收局被拒（不是 running 就不动）', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    closeBout(db, id, { reason: 'done', now: NOW })
    assert.deepEqual(closeBout(db, id, { reason: 'done', now: NOW + 1 }), { ok: false, error: 'not-running' })
    assert.equal(getBout(db, id).closedAt, NOW)
  })
})

test('J1 收局：不收不认识的收局原因', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    assert.deepEqual(closeBout(db, id, { reason: '随便', now: NOW }), { ok: false, error: 'bad-reason' })
    assert.equal(getBout(db, id).status, 'running')
    assert.deepEqual(CLOSE_REASONS, ['done', 'timeout', 'abandoned', 'escalated', 'replaced'])
  })
})

test('J1 收局之后下一局可以开：进行中被释放', () => {
  withDb((db) => {
    const a = openBout(db, { protocolId: 'p1', now: NOW })
    closeBout(db, a.id, { reason: 'done', now: NOW })
    const b = openBout(db, { protocolId: 'p2', now: NOW + 1 })
    assert.equal(b.ok, true)
    assert.equal(runningBout(db).protocolId, 'p2')
  })
})

test('J1 查：不存在的局返回 null，不是空对象', () => {
  withDb((db) => {
    assert.equal(getBout(db, 'bout:nope'), null)
    assert.equal(runningBout(db), null)
  })
})

test('J1 改：只能改白名单字段', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    assert.deepEqual(updateBout(db, id, { department: '统筹规划' }), { ok: true })
    assert.equal(getBout(db, id).department, '统筹规划')
    // status 不在白名单里：想用 update 绕过收局流程改状态，改不动
    assert.equal(updateBout(db, id, { status: 'closed' }).ok, false)
    assert.equal(getBout(db, id).status, 'running')
  })
})

test('J1 改：空 patch 与不存在的局都拒绝', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    assert.deepEqual(updateBout(db, id, {}), { ok: false, error: 'nothing-to-update' })
    assert.deepEqual(updateBout(db, 'bout:none', { department: 'x' }), { ok: false, error: 'not-found' })
  })
})

test('J1 删：连拍、依据、产出物、判定一起删干净', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    const beat = addBeat(db, { boutId: id, stepId: 'guess', now: NOW })
    addEvidence(db, { boutId: id, beatId: beat.id, sourceType: 'quote', ref: 'q', now: NOW })
    addDeliverable(db, { boutId: id, beatId: beat.id, form: 'text', text: 't', now: NOW })
    addVerdict(db, { boutId: id, beatId: beat.id, layer: 'code', result: 'pass', now: NOW })
    assert.deepEqual(deleteBout(db, id), { ok: true })
    assert.equal(getBout(db, id), null)
    assert.deepEqual(listBeats(db, id), [])
    assert.deepEqual(listEvidence(db, id), [])
    assert.deepEqual(listDeliverables(db, id), [])
    assert.deepEqual(listVerdicts(db, id), [])
  })
})

test('J1 删：不存在的局拒绝', () => {
  withDb((db) => {
    assert.deepEqual(deleteBout(db, 'bout:none'), { ok: false, error: 'not-found' })
  })
})

test('J1 列局：按状态过滤，进行中的排在最前', () => {
  withDb((db) => {
    const a = openBout(db, { protocolId: 'p1', now: NOW - 10_000 })
    closeBout(db, a.id, { reason: 'done', now: NOW - 9_000 })
    openBout(db, { protocolId: 'p2', now: NOW })
    const running = listBouts(db, { status: 'running' })
    assert.equal(running.length, 1)
    assert.equal(running[0].protocolId, 'p2')
    assert.equal(listBouts(db, { status: 'closed' }).length, 1)
    assert.equal(listBouts(db).length, 2)
  })
})

test('J1 列局：按天过滤只看那一天开的局', () => {
  withDb((db) => {
    const dayStart = Date.parse('2026-11-11T00:00:00+08:00')
    const a = openBout(db, { protocolId: 'yesterday', now: dayStart + 3_600_000 })
    closeBout(db, a.id, { reason: 'done', now: dayStart + 3_600_000 })
    const b = openBout(db, { protocolId: 'today', now: dayStart + 86_400_000 + 3_600_000 })
    closeBout(db, b.id, { reason: 'done', now: dayStart + 86_400_000 + 3_600_000 })
    const same = listBouts(db, { dayStart })
    assert.deepEqual(same.map((x) => x.protocolId), ['yesterday'])
    const next = listBouts(db, { dayStart: dayStart + 86_400_000 })
    assert.deepEqual(next.map((x) => x.protocolId), ['today'])
  })
})

test('J1 列局：limit 生效且被夹在 1..500', () => {
  withDb((db) => {
    for (let i = 0; i < 3; i += 1) {
      const r = openBout(db, { protocolId: 'p' + i, now: NOW + i })
      closeBout(db, r.id, { reason: 'done', now: NOW + i })
    }
    assert.equal(listBouts(db, { limit: 2 }).length, 2)
    assert.equal(listBouts(db, { limit: 0 }).length, 1)
    assert.equal(listBouts(db, { limit: 10_000 }).length, 3)
  })
})

// ── beats ───────────────────────────────────────────────────

test('J1 一拍：招式、通道、应答、重打次数都落下', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    const r = addBeat(db, { boutId: id, stepId: 'guess', channel: 'voice', answer: '三条猜测', retries: 2, now: NOW + 5 })
    assert.equal(r.ok, true)
    const [beat] = listBeats(db, id)
    assert.equal(beat.stepId, 'guess')
    assert.equal(beat.channel, 'voice')
    assert.equal(beat.answer, '三条猜测')
    assert.equal(beat.retries, 2)
    assert.equal(beat.askedAt, NOW + 5)
  })
})

test('J1 一拍：应答度量按 JSON 存、按对象取回', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    addBeat(db, { boutId: id, stepId: 'watch', metrics: { lines: 3, nouns: 2, conclusion: true }, now: NOW })
    const [beat] = listBeats(db, id)
    assert.deepEqual(beat.metrics, { lines: 3, nouns: 2, conclusion: true })
  })
})

test('J1 一拍：没有局 id 就拒绝', () => {
  withDb((db) => {
    assert.deepEqual(addBeat(db, { stepId: 'x' }), { ok: false, error: 'no-bout' })
  })
})

test('J1 一拍：按发出时间排序', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    addBeat(db, { boutId: id, stepId: 'second', now: NOW + 200 })
    addBeat(db, { boutId: id, stepId: 'first', now: NOW + 100 })
    assert.deepEqual(listBeats(db, id).map((b) => b.stepId), ['first', 'second'])
  })
})

test('J1 一拍：别的局的拍不会串进来', () => {
  withDb((db) => {
    const a = openBout(db, { protocolId: 'p1', now: NOW })
    addBeat(db, { boutId: a.id, stepId: 'only-a', now: NOW })
    closeBout(db, a.id, { reason: 'done', now: NOW })
    const b = openBout(db, { protocolId: 'p2', now: NOW })
    addBeat(db, { boutId: b.id, stepId: 'only-b', now: NOW })
    assert.deepEqual(listBeats(db, a.id).map((x) => x.stepId), ['only-a'])
    assert.deepEqual(listBeats(db, b.id).map((x) => x.stepId), ['only-b'])
  })
})

// ── evidence ────────────────────────────────────────────────

test('J1 依据：三种来源（解析 / 原文 / 他的记录）都收', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    for (const s of ['parse', 'quote', 'record']) {
      assert.equal(addEvidence(db, { boutId: id, sourceType: s, ref: 'r-' + s, now: NOW }).ok, true)
    }
    assert.deepEqual(listEvidence(db, id).map((e) => e.sourceType), ['parse', 'quote', 'record'])
  })
})

test('J1 依据：不认识的来源拒绝', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    assert.deepEqual(addEvidence(db, { boutId: id, sourceType: '猜的' }), { ok: false, error: 'bad-source-type' })
  })
})

test('J1 依据：内容哈希与引用原样保存', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    const beat = addBeat(db, { boutId: id, stepId: 'x', now: NOW })
    addEvidence(db, { boutId: id, beatId: beat.id, sourceType: 'parse', ref: '原文第 3 段', contentHash: 'abc123', now: NOW })
    const [row] = listEvidence(db, id)
    assert.equal(row.beatId, beat.id)
    assert.equal(row.ref, '原文第 3 段')
    assert.equal(row.contentHash, 'abc123')
  })
})

// ── deliverables ────────────────────────────────────────────

test('J1 产出物：五种形态都收', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    for (const form of DELIVERABLE_FORMS) {
      assert.equal(addDeliverable(db, { boutId: id, form, now: NOW }).ok, true)
    }
    assert.deepEqual(listDeliverables(db, id).map((d) => d.form), DELIVERABLE_FORMS)
  })
})

test('J1 产出物：不认识的形态拒绝', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    assert.deepEqual(addDeliverable(db, { boutId: id, form: '梦话' }), { ok: false, error: 'bad-form' })
  })
})

test('J1 产出物：外部提交带引用、语音转写带提取文本', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    addDeliverable(db, { boutId: id, form: 'external', ref: 'content://share/42', now: NOW })
    addDeliverable(db, { boutId: id, form: 'transcript', text: '刚才讲的是哈希表，因为……', now: NOW })
    const rows = listDeliverables(db, id)
    assert.equal(rows[0].ref, 'content://share/42')
    assert.equal(rows[1].text, '刚才讲的是哈希表，因为……')
  })
})

// ── verdicts ────────────────────────────────────────────────

test('J1 判定：代码 / 模型 / 墙三层都收，分数保留', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    for (const layer of VERDICT_LAYERS) {
      assert.equal(addVerdict(db, { boutId: id, layer, result: 'pass', score: 0.5, now: NOW }).ok, true)
    }
    const rows = listVerdicts(db, id)
    assert.deepEqual(rows.map((v) => v.layer), VERDICT_LAYERS)
    assert.equal(rows[0].score, 0.5)
  })
})

test('J1 判定：不认识的层、空结果都拒绝', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    assert.deepEqual(addVerdict(db, { boutId: id, layer: '直觉', result: 'pass' }), { ok: false, error: 'bad-layer' })
    assert.deepEqual(addVerdict(db, { boutId: id, layer: 'code', result: '' }), { ok: false, error: 'no-result' })
  })
})

test('J1 判定：引用原文留着，方便事后对账', () => {
  withDb((db) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    addVerdict(db, { boutId: id, layer: 'code', result: 'fail', quote: '只写了 2 行', now: NOW })
    assert.equal(listVerdicts(db, id)[0].quote, '只写了 2 行')
  })
})

// ── escalations ─────────────────────────────────────────────

test('J1 升级：开一次关一次，怎么结束的都记下', () => {
  withDb((db) => {
    const r = startEscalation(db, { reason: '30 分钟内敷衍 3 次', level: 'L3', now: NOW })
    assert.equal(r.ok, true)
    assert.deepEqual(endEscalation(db, r.id, { endedBy: '答出指令', now: NOW + 60_000 }), { ok: true })
    const [row] = listEscalations(db)
    assert.equal(row.reason, '30 分钟内敷衍 3 次')
    assert.equal(row.level, 'L3')
    assert.equal(row.startedAt, NOW)
    assert.equal(row.endedAt, NOW + 60_000)
    assert.equal(row.endedBy, '答出指令')
  })
})

test('J1 升级：重复结束被拒', () => {
  withDb((db) => {
    const { id } = startEscalation(db, { reason: 'r', now: NOW })
    endEscalation(db, id, { endedBy: '破窗', now: NOW })
    assert.deepEqual(endEscalation(db, id, { endedBy: '破窗', now: NOW + 1 }), { ok: false, error: 'not-open' })
  })
})

test('J1 升级：没原因、没结束方式都拒绝', () => {
  withDb((db) => {
    assert.deepEqual(startEscalation(db, {}), { ok: false, error: 'no-reason' })
    const { id } = startEscalation(db, { reason: 'r', now: NOW })
    assert.deepEqual(endEscalation(db, id, {}), { ok: false, error: 'no-ended-by' })
  })
})

// ── protocols ───────────────────────────────────────────────

test('J1 协议：登记已装协议，状态四档之一', () => {
  withDb((db) => {
    assert.deepEqual(PROTOCOL_STATUS, ['builtin', 'trial', 'official', 'retired'])
    assert.equal(upsertProtocol(db, { id: 'study-video', version: 1, source: 'builtin', status: 'builtin', fileHash: 'h1', now: NOW }).ok, true)
    const [p] = listProtocols(db)
    assert.equal(p.id, 'study-video')
    assert.equal(p.version, 1)
    assert.equal(p.status, 'builtin')
    assert.equal(p.fileHash, 'h1')
  })
})

test('J1 协议：同一个 id 再登记是更新，不是插两行', () => {
  withDb((db) => {
    upsertProtocol(db, { id: 'x', version: 1, status: 'trial', fileHash: 'h1', now: NOW })
    upsertProtocol(db, { id: 'x', version: 2, status: 'official', fileHash: 'h2', now: NOW + 1 })
    const rows = listProtocols(db)
    assert.equal(rows.length, 1)
    assert.equal(rows[0].version, 2)
    assert.equal(rows[0].status, 'official')
    assert.equal(rows[0].fileHash, 'h2')
  })
})

test('J1 协议：非法状态、缺 id 都拒绝', () => {
  withDb((db) => {
    assert.deepEqual(upsertProtocol(db, { id: 'x', status: '随便' }), { ok: false, error: 'bad-status' })
    assert.deepEqual(upsertProtocol(db, { status: 'trial' }), { ok: false, error: 'no-id' })
  })
})

test('J1 协议：按状态过滤能分出试用和正式', () => {
  withDb((db) => {
    upsertProtocol(db, { id: 'a', status: 'trial', now: NOW })
    upsertProtocol(db, { id: 'b', status: 'official', now: NOW })
    assert.deepEqual(listProtocols(db, { status: 'trial' }).map((p) => p.id), ['a'])
    assert.deepEqual(listProtocols(db).map((p) => p.id), ['a', 'b'])
  })
})

// ── day_plans ───────────────────────────────────────────────

test('J1 计划：一天的队列、编辑记录、生效方式往返', () => {
  withDb((db) => {
    const queue = [{ taskId: 't1', task: '看课', dept: '信息收集', minutes: 30, start: 540, end: 570 }]
    const edits = [{ at: NOW, what: '把看课往后挪 10 分钟' }]
    assert.deepEqual(ACTIVATIONS, ['confirmed', 'auto'])
    assert.equal(saveDayPlan(db, { day: '2026-11-12', template: 'a2-daily', queue, edits, activation: 'confirmed', effectiveAt: NOW, now: NOW }).ok, true)
    const plan = getDayPlan(db, '2026-11-12')
    assert.equal(plan.template, 'a2-daily')
    assert.deepEqual(plan.queue, queue)
    assert.deepEqual(plan.edits, edits)
    assert.equal(plan.activation, 'confirmed')
    assert.equal(plan.effectiveAt, NOW)
  })
})

test('J1 计划：同一天再存是覆盖（一天一份计划）', () => {
  withDb((db) => {
    saveDayPlan(db, { day: '2026-11-12', queue: [{ taskId: 't1' }], now: NOW })
    saveDayPlan(db, { day: '2026-11-12', queue: [{ taskId: 't2' }], activation: 'confirmed', now: NOW + 1 })
    assert.equal(db.prepare('SELECT COUNT(*) AS n FROM day_plans').get().n, 1)
    assert.deepEqual(getDayPlan(db, '2026-11-12').queue, [{ taskId: 't2' }])
  })
})

test('J1 计划：日期格式、生效方式不合法都拒绝', () => {
  withDb((db) => {
    assert.deepEqual(saveDayPlan(db, { day: '11月12日' }), { ok: false, error: 'bad-day' })
    assert.deepEqual(saveDayPlan(db, { day: '2026-11-12', activation: '看心情' }), { ok: false, error: 'bad-activation' })
    assert.equal(getDayPlan(db, '2026-11-12'), null)
  })
})

test('J1 计划：没有这一天返回 null', () => {
  withDb((db) => {
    assert.equal(getDayPlan(db, '2026-01-01'), null)
  })
})

// ── 第七轮 A2 时间块迁入 ─────────────────────────────────────

test('J1 迁入：A2 的 daily.json 时间块变成 day_plans 一行', () => {
  withDb((db) => {
    const raw = {
      day: '2026-11-12',
      tasks: [{ id: 't1', text: '看课' }],
      plan: {
        blocks: [
          { taskId: 't1', task: '看课', dept: '信息收集', minutes: 30, start: 540, end: 570 },
          { taskId: 't2', task: '写总结', dept: '谋划思考', minutes: 20, start: 570, end: 590 },
        ],
      },
      review: { at: NOW, text: '今天两件事' },
    }
    const r = migrateLegacyDayPlan(db, raw, { now: NOW })
    assert.deepEqual(r, { ok: true, day: '2026-11-12', blocks: 2, skipped: false })
    const plan = getDayPlan(db, '2026-11-12')
    assert.equal(plan.template, 'a2-daily')
    assert.equal(plan.queue.length, 2)
    assert.equal(plan.queue[1].dept, '谋划思考')
    assert.equal(plan.activation, 'auto')
  })
})

test('J1 迁入：当天已有计划就不覆盖，报 skipped', () => {
  withDb((db) => {
    saveDayPlan(db, { day: '2026-11-12', queue: [{ taskId: 'already' }], now: NOW })
    const r = migrateLegacyDayPlan(db, { day: '2026-11-12', plan: { blocks: [{ taskId: 'x' }] } }, { now: NOW })
    assert.equal(r.skipped, true)
    assert.deepEqual(getDayPlan(db, '2026-11-12').queue, [{ taskId: 'already' }])
  })
})

test('J1 迁入：daily.json 缺日期或日期坏了就拒绝', () => {
  withDb((db) => {
    assert.deepEqual(migrateLegacyDayPlan(db, {}, {}), { ok: false, error: 'bad-day' })
    assert.deepEqual(migrateLegacyDayPlan(db, { day: '明天' }, {}), { ok: false, error: 'bad-day' })
  })
})

// ── 账本与重建 ──────────────────────────────────────────────

test('J1 账本：每一次写入都追加一行，状态能对上是哪次操作', () => {
  withDb((db, home) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW, home })
    const beat = addBeat(db, { boutId: id, stepId: 'guess', now: NOW + 1, home })
    addVerdict(db, { boutId: id, beatId: beat.id, layer: 'code', result: 'pass', now: NOW + 2, home })
    closeBout(db, id, { reason: 'done', now: NOW + 3, home })
    const lines = ledgerLines(home)
    assert.deepEqual(lines.map((l) => l.state), ['bout-open', 'beat', 'verdict', 'bout-close'])
    assert.equal(lines[0].boutId, id)
    assert.equal(lines[0].protocolId, 'p')
    assert.equal(lines[3].reason, 'done')
  })
})

test('J1 账本：没给 home 就不写账本，但库照写（测试与离线调用不为难）', () => {
  withDb((db, home) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW })
    assert.equal(getBout(db, id).status, 'running')
    assert.deepEqual(ledgerLines(home), [])
  })
})

test('J1 重建：照着账本把局、拍、判定原样重放出来', () => {
  withDb((db, home) => {
    const { id } = openBout(db, { protocolId: 'study-video', openedBy: 'plan', department: '行为管理', now: NOW, home })
    const beat = addBeat(db, { boutId: id, stepId: 'guess', channel: 'voice', answer: '三条', metrics: { lines: 3 }, now: NOW + 1, home })
    addVerdict(db, { boutId: id, beatId: beat.id, layer: 'code', result: 'pass', score: 1, quote: '三行都在', now: NOW + 2, home })
    closeBout(db, id, { reason: 'done', now: NOW + 3, home })
    const before = { bout: getBout(db, id), beats: listBeats(db, id), verdicts: listVerdicts(db, id) }

    const r = rebuildFromLedgerFile(db, home)
    assert.equal(r.ok, true)
    assert.equal(r.replayed, 4)
    assert.deepEqual(getBout(db, id), before.bout)
    assert.deepEqual(listBeats(db, id), before.beats)
    assert.deepEqual(listVerdicts(db, id), before.verdicts)
  })
})

test('J1 重建：账本是权威——库里多出来的行会被清掉', () => {
  withDb((db, home) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW, home })
    closeBout(db, id, { reason: 'done', now: NOW + 1, home })
    // 账本里没有这一行：手工塞一条“库里多出来的”
    db.prepare("INSERT INTO bouts (id, protocol_id, opened_at, closed_at, close_reason, status, planned_at) VALUES ('stray', 'x', 1, 2, 'done', 'closed', 0)").run()
    assert.equal(listBouts(db).length, 2)
    rebuildFromLedgerFile(db, home)
    assert.deepEqual(listBouts(db).map((b) => b.id), [id])
  })
})

test('J1 重建：不认识的状态被忽略，不会当成局写进去', () => {
  withDb((db) => {
    const r = rebuildFromLedger(db, [
      { at: NOW, state: 'replied', requestId: 'r1' },
      { at: NOW, state: 'displayed', topicId: 't1' },
    ])
    assert.equal(r.ok, true)
    assert.equal(r.replayed, 0)
    assert.deepEqual(listBouts(db), [])
  })
})

test('J1 重建：删掉的局不会因为重建又冒出来', () => {
  withDb((db, home) => {
    const { id } = openBout(db, { protocolId: 'p', now: NOW, home })
    closeBout(db, id, { reason: 'abandoned', now: NOW + 1, home })
    deleteBout(db, id, { now: NOW + 2, home })
    rebuildFromLedgerFile(db, home)
    assert.equal(getBout(db, id), null)
  })
})

test('J1 重建：账本里两次开局没收局时，重建后仍然只有一个进行中的局', () => {
  withDb((db) => {
    rebuildFromLedger(db, [
      { at: NOW, state: 'bout-open', boutId: 'a', protocolId: 'p1' },
      { at: NOW + 1, state: 'bout-open', boutId: 'b', protocolId: 'p2' },
    ])
    const running = db.prepare("SELECT COUNT(*) AS n FROM bouts WHERE status = 'running'").get().n
    assert.equal(running, 1)
    assert.equal(runningBout(db).protocolId, 'p2')
  })
})

test('J1 重建：升级、协议、计划也一起重放', () => {
  withDb((db, home) => {
    const esc = startEscalation(db, { reason: '娱乐 15 分钟', level: 'L2', now: NOW, home })
    endEscalation(db, esc.id, { endedBy: '紧急应用', now: NOW + 1, home })
    upsertProtocol(db, { id: 'study-video', version: 1, status: 'builtin', fileHash: 'h', now: NOW, home })
    saveDayPlan(db, { day: '2026-11-12', queue: [{ taskId: 't1' }], now: NOW, home })
    const before = { esc: listEscalations(db), proto: listProtocols(db), plan: getDayPlan(db, '2026-11-12') }

    rebuildFromLedgerFile(db, home)
    assert.deepEqual(listEscalations(db), before.esc)
    assert.deepEqual(listProtocols(db), before.proto)
    assert.deepEqual(getDayPlan(db, '2026-11-12'), before.plan)
  })
})

test('J1 重建：没有库返回 no-db，不抛错', () => {
  assert.deepEqual(rebuildFromLedger(null, []), { ok: false, error: 'no-db' })
})

test('J1 重建：命令行脚本跑得通，输出一行 JSON', () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-bouts-cli-'))
  try {
    const db = openBoutsDb(join(home, 'fish-memory', 'fish-memory.sqlite'))
    const { id } = openBout(db, { protocolId: 'p', now: NOW, home })
    closeBout(db, id, { reason: 'done', now: NOW + 1, home })
    db.close()
    const out = execFileSync(process.execPath, [join(PLUGIN, 'lib', 'rebuild.js'), '--home', home], { encoding: 'utf8' })
      .trim()
    const result = JSON.parse(out)
    assert.equal(result.ok, true)
    assert.equal(result.replayed, 2)
    // 重建之后库还是好的：再开一次能读到那一局
    const again = openBoutsDb(join(home, 'fish-memory', 'fish-memory.sqlite'))
    assert.equal(getBout(again, id).closeReason, 'done')
    again.close()
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})
