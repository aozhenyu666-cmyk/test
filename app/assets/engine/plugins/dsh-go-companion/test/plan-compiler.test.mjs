// 2026-11-20 P0 计划编译器：节点 → 局 → 写回节点。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { cleanHorizons } from '../lib/horizons.js'
import { boutMinutesFor, commitBoutOutcomeToNode, compileBoutFromNode, nodeAlert } from '../lib/plan_compiler.js'
import { apply } from '../lib/index.js'

const H = () => cleanHorizons({
  long: [{
    id: 'L1', title: '省考上岸', due: '2026-12-20',
    nodes: [
      { id: 'N1', title: '资料分析 200 题', targetDate: '2026-11-28', deliverable: '正确率 ≥85%', budgetBouts: 6, budgetMinutes: 270, defaultApps: ['md.obsidian', 'com.fenbi.android.servant', 'tv.danmaku.bili'], lastBreakpoint: '做到第 85 题' },
      { id: 'N2', title: '言语 100 题', status: 'passed' },
    ],
  }],
})
const tierOf = (p) => (p === 'tv.danmaku.bili' ? 'fun' : 'work')

test('有节点的目标：进度 = 达成节点 / 节点总数（自动算）', () => {
  const h = H().long[0]
  assert.equal(h.targetSteps, 2)
  assert.equal(h.doneSteps, 1)
})

test('I13 派局不越过分类表：娱乐 / 游戏 App 一律剔掉，带上交付物和断点', () => {
  const h = H().long[0]
  const r = compileBoutFromNode({ horizon: h, node: h.nodes[0], tierOf, now: 1_000_000 })
  assert.equal(r.ok, true)
  assert.deepEqual(r.item.allow, ['md.obsidian', 'com.fenbi.android.servant'])
  assert.deepEqual(r.droppedApps, ['tv.danmaku.bili'])
  assert.equal(r.item.links.nodeId, 'N1')
  assert.equal(r.item.packet.deliverable, '正确率 ≥85%')
  assert.match(r.item.packet.first, /做到第 85 题/)
  assert.equal(r.item.minutes, 45, '270 分钟 / 6 局')
  assert.equal(compileBoutFromNode({ horizon: h, node: h.nodes[1], tierOf }).ok, false, '达成的节点不再派局')
  assert.equal(boutMinutesFor({ budgetBouts: 1, budgetMinutes: 600 }), 90)
})

test('I14 写回幂等：同一局写几次只记一条、分钟只加一次；planned → active', () => {
  let s = H()
  const a = commitBoutOutcomeToNode(s, { horizonId: 'L1', nodeId: 'N1', workId: 'w1', outcome: 'passed', minutes: 40, day: '2026-11-20', breakpoint: '做到第 120 题' })
  assert.equal(a.changed, true)
  s = a.state
  const b = commitBoutOutcomeToNode(s, { horizonId: 'L1', nodeId: 'N1', workId: 'w1', outcome: 'passed', minutes: 40, day: '2026-11-20' })
  assert.equal(b.changed, false)
  const n = s.long[0].nodes[0]
  assert.equal(n.executionLog.length, 1)
  assert.equal(n.spentMinutes, 40)
  assert.equal(n.status, 'active')
  assert.equal(n.lastBreakpoint, '做到第 120 题')
})

test('调盘预警：过日子 / 超预算 / 连着两局没过', () => {
  const base = { status: 'active', targetDate: '2026-11-28', budgetMinutes: 100, spentMinutes: 0, executionLog: [] }
  assert.equal(nodeAlert(base, '2026-11-29').kind, 'overdue')
  assert.equal(nodeAlert({ ...base, spentMinutes: 85 }, '2026-11-20').kind, 'overBudget')
  assert.equal(nodeAlert({ ...base, executionLog: [{ outcome: 'partial' }, { outcome: 'escalated' }] }, '2026-11-20').kind, 'stuck')
  assert.equal(nodeAlert({ ...base, status: 'passed' }, '2026-12-30'), null)
  assert.equal(nodeAlert(base, '2026-11-20'), null)
})

// ---------------------------------------------------------------- 路由

function host() {
  const agent = { id: 's1', status: 'idle', inbox: { hasPending: false }, config: { provider: 't', model: 't' }, followup() {} }
  let handler
  apply({
    get(n) {
      if (n === 'agents') return { get: () => agent, roots: () => [agent], list: () => [agent] }
      if (n === 'connection') return { requestRejection: () => undefined }
      if (n === 'sessionController') return { resolveAgent: async () => ({ agent }) }
      if (n === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(f) { f() } }) },
    effect(f) { f() },
  })
  return handler
}
function call(h, path, body) {
  const req = Readable.from([Buffer.from(JSON.stringify(body))])
  req.method = 'POST'; req.url = '/dsh-go-companion/v1' + path; req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(h(req, res)).then(() => JSON.parse(res.text))
}
async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'plancomp-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 's1', quietStart: 12, quietEnd: 12 }))
  try { return await fn(home) } finally {
    if (old === undefined) delete process.env.DSH_HOME; else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}

test('路由：建目标 → 加节点 → 从节点派局 → 档案里看得到；写回幂等', async () => {
  await withHome(async () => {
    const h = host()
    const up = await call(h, '/horizons', { action: 'upsert', horizon: 'long', title: '省考上岸', due: '2026-12-20' })
    const hid = up.item.id
    const add = await call(h, '/horizons', { action: 'node_upsert', horizonId: hid, node: { title: '资料分析 200 题', targetDate: '2026-11-28', deliverable: '正确率 ≥85%', budgetBouts: 6, budgetMinutes: 270, spentMinutes: 9999 } })
    assert.equal(add.ok, true)
    const nid = add.node.id
    assert.equal(add.node.spentMinutes, 0, '花了多少只能由局写回，不接受直接改')
    const st = await call(h, '/horizons', { action: 'node_start', horizonId: hid, nodeId: nid })
    assert.equal(st.ok, true)
    assert.equal(st.item.links.nodeId, nid)
    assert.equal(st.horizon.nodes[0].status, 'active')
    const c1 = await call(h, '/horizons', { action: 'node_commit', horizonId: hid, nodeId: nid, workId: st.item.id, outcome: 'passed', minutes: 44 })
    const c2 = await call(h, '/horizons', { action: 'node_commit', horizonId: hid, nodeId: nid, workId: st.item.id, outcome: 'passed', minutes: 44 })
    assert.equal(c1.changed, true)
    assert.equal(c2.changed, false)
    const d = await call(h, '/horizons', { action: 'dossier', horizonId: hid })
    assert.equal(d.horizon.nodes[0].executionLog.length, 1)
    assert.equal(d.summary.spentMinutes, 44)
    assert.equal(d.summary.nextNodeId, nid)
    const ex = await call(h, '/horizons', { action: 'node_extend', horizonId: hid, nodeId: nid, days: 3 })
    assert.equal(ex.horizon.nodes[0].targetDate, '2026-12-01')
    const pass = await call(h, '/horizons', { action: 'node_status', horizonId: hid, nodeId: nid, status: 'passed' })
    assert.equal(pass.horizon.doneSteps, 1)
    assert.equal(pass.horizon.status, 'done', '唯一的节点达成 = 目标达成')
  })
})

test('真局：从节点开局 → 运行中的局带节点上下文 → 收局写回节点', async () => {
  await withHome(async (home) => {
    const h = host()
    const hid = (await call(h, '/horizons', { action: 'upsert', horizon: 'medium', title: '资料分析战役' })).item.id
    const nid = (await call(h, '/horizons', { action: 'node_upsert', horizonId: hid, node: { title: '速算 50 题', deliverable: '错题 5 道入题库', lastBreakpoint: '基期差公式卡壳' } })).node.id
    const st = await call(h, '/horizons', { action: 'node_start', horizonId: hid, nodeId: nid })
    const open = await call(h, '/bouts', { action: 'open', id: st.item.id, title: st.item.title, allow: st.item.allow, minutes: st.item.minutes, parentHorizonId: hid })
    assert.equal(open.ok, true, JSON.stringify(open))
    const getReq = Readable.from([])
    getReq.method = 'GET'; getReq.url = '/dsh-go-companion/v1/bouts'; getReq.headers = {}
    const res = { text: '', writeHead() {}, end(c) { this.text = String(c || '') } }
    await h(getReq, res)
    const running = JSON.parse(res.text).running
    assert.equal(running.nodeContext.nodeTitle, '速算 50 题')
    assert.equal(running.nodeContext.lastBreakpoint, '基期差公式卡壳')
    const close = await call(h, '/bouts', { action: 'close' })
    assert.equal(close.ok, true, JSON.stringify(close))
    const d = await call(h, '/horizons', { action: 'dossier', horizonId: hid })
    assert.equal(d.horizon.nodes[0].executionLog.length, 1, JSON.stringify(d.horizon.nodes[0]))
    assert.equal(d.horizon.nodes[0].executionLog[0].workId, st.item.id)
  })
})
