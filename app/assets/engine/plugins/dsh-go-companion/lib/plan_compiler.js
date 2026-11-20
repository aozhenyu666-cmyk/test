// dsh-go-companion · 计划编译器（2026-11-20 · P0）
//
// 计划（目标 + 预留节点）是蓝图，局是从节点「编译」出来的一段可执行时间。两个方向：
//   compileBoutFromNode    节点 → 今天的一局（带上交付物、上次断点、默认放行 App）
//   commitBoutOutcomeToNode 局结束 → 写回节点（按 workId 幂等：同一局写几次都只记一条、只加一次分钟）
// 另有 nodeAlert：花超预算 / 过了日子没达成 / 连着两局没过，给出调盘选项（只提示，不自动改）。
//
// 纪律：
//   - 编译器不发明放行规则：娱乐 / 游戏层的 App 一律剔掉（I13）。要边看网课边记，走契约的 video_extract，
//     那里已有「逾期 1 分钟剔除视频 App」的完整逻辑，这里不再写第二份。
//   - 纯函数：时间、分类函数都由调用方给。

import { MAX_NODE_LOG, cleanNode, findHorizon, mapHorizon } from './horizons.js'

const LOCKY_TIERS = new Set(['fun', 'game'])

/** 一局多长：节点预算平摊到每局，夹在 25–90 分钟；给了就用给的。 */
export function boutMinutesFor(node, minutes) {
  const asked = Math.round(Number(minutes))
  if (Number.isFinite(asked) && asked > 0) return Math.max(15, Math.min(180, asked))
  const per = node && node.budgetBouts > 0 ? node.budgetMinutes / node.budgetBouts : 45
  return Math.max(25, Math.min(90, Math.round(per / 5) * 5 || 45))
}

/**
 * 节点 → 今天的一局（work.json 的一条）。
 * @returns {{ok:boolean, item?:object, droppedApps?:string[], error?:string}}
 */
export function compileBoutFromNode({ horizon, node, tierOf = () => 'other', fallbackApps = [], minutes = 0, now = Date.now() } = {}) {
  if (!horizon || !node) return { ok: false, error: '没找到这个节点' }
  if (node.status === 'passed') return { ok: false, error: '这个节点已经达成了' }
  const wanted = (node.defaultApps?.length ? node.defaultApps : fallbackApps).map((p) => String(p || '').trim()).filter(Boolean)
  const droppedApps = wanted.filter((p) => LOCKY_TIERS.has(tierOf(p)))
  const apps = [...new Set(wanted.filter((p) => !LOCKY_TIERS.has(tierOf(p))))]
  const bp = String(node.lastBreakpoint || '').trim()
  const item = {
    id: `nd-${String(node.id).slice(0, 20)}-${Number(now).toString(36).slice(-5)}`,
    source: 'horizon',
    title: String(node.title).slice(0, 80),
    protocolId: 'execute',
    minutes: boutMinutesFor(node, minutes),
    apps,
    need: apps,
    allow: apps,
    parentHorizonId: horizon.id,
    links: { horizonId: horizon.id, nodeId: node.id, contractCpId: '' },
    packet: {
      goal: String(node.title).slice(0, 120),
      ...(node.deliverable ? { deliverable: node.deliverable.slice(0, 160) } : {}),
      ...(bp ? { first: ('接着上次：' + bp).slice(0, 120) } : {}),
    },
    contextBrief: {
      horizonTitle: String(horizon.title || '').slice(0, 100),
      nodeTitle: String(node.title).slice(0, 80),
      targetDate: node.targetDate || '',
      deliverable: String(node.deliverable || '').slice(0, 200),
      lastBreakpoint: bp.slice(0, 200),
    },
    strict: true,
  }
  return { ok: true, item, droppedApps }
}

/**
 * 局结束 → 写回节点。按 workId 幂等。
 * outcome: passed | done | partial | escalated | overdue_locked
 * @returns {{ok:boolean, state:object, changed:boolean, node?:object, alert?:object|null, error?:string}}
 */
export function commitBoutOutcomeToNode(stateRaw, { horizonId, nodeId, workId, outcome = 'done', minutes = 0, summary = '', breakpoint = '', day = '', now = Date.now() } = {}) {
  const h = findHorizon(stateRaw, horizonId)
  const node = h?.nodes?.find((n) => n.id === String(nodeId || ''))
  if (!h || !node) return { ok: false, state: stateRaw, changed: false, error: 'no-node' }
  const wid = String(workId || '').trim()
  if (!wid) return { ok: false, state: stateRaw, changed: false, error: 'no-work-id' }
  if (node.executionLog.some((e) => e.workId === wid)) {
    return { ok: true, state: stateRaw, changed: false, node, alert: nodeAlert(node, day) }
  }
  const mins = Math.max(0, Math.min(600, Math.round(Number(minutes) || 0)))
  let next = null
  const state = mapHorizon(stateRaw, horizonId, (hz) => ({
    ...hz,
    updatedAt: now,
    nodes: hz.nodes.map((n) => {
      if (n.id !== node.id) return n
      next = cleanNode({
        ...n,
        spentMinutes: n.spentMinutes + mins,
        status: n.status === 'planned' ? 'active' : n.status,
        lastBreakpoint: String(breakpoint || '').trim() || n.lastBreakpoint,
        executionLog: [...n.executionLog, { workId: wid, day, minutes: mins, outcome, summary, at: now }].slice(-MAX_NODE_LOG),
      })
      return next
    }),
  }))
  return { ok: true, state, changed: true, node: next, alert: nodeAlert(next, day) }
}

/**
 * 调盘预警（只提示，不自动改）：
 *   overdue    过了预留日子还没达成
 *   overBudget 花掉预算的 80% 以上还没达成
 *   stuck      最近连着两局没过（partial / escalated / overdue_locked）
 */
export function nodeAlert(node, todayYmd = '') {
  if (!node || node.status === 'passed') return null
  const options = ['cut_scope', 'extend_due_3d', 'add_bout_tomorrow']
  if (node.targetDate && todayYmd && node.targetDate < todayYmd) {
    return { kind: 'overdue', text: `过了预留日子（${node.targetDate}）还没达成`, options }
  }
  if (node.budgetMinutes > 0 && node.spentMinutes >= node.budgetMinutes * 0.8) {
    return { kind: 'overBudget', text: `已经花了 ${node.spentMinutes}/${node.budgetMinutes} 分钟，还没达成`, options }
  }
  const last2 = node.executionLog.slice(-2)
  if (last2.length === 2 && last2.every((e) => ['partial', 'escalated', 'overdue_locked'].includes(e.outcome))) {
    return { kind: 'stuck', text: '最近两局都没过，卡在哪？', options }
  }
  return null
}

/** 目标一页的总账：一共花了多少、节点达成几个、下一个该攻哪个、有几个预警。 */
export function dossierSummary(horizon, todayYmd = '') {
  const nodes = Array.isArray(horizon?.nodes) ? horizon.nodes : []
  const spent = nodes.reduce((s, n) => s + n.spentMinutes, 0)
  const budget = nodes.reduce((s, n) => s + n.budgetMinutes, 0)
  const open = nodes.filter((n) => n.status !== 'passed').sort((a, b) => (a.targetDate || '9999').localeCompare(b.targetDate || '9999'))
  const alerts = nodes.map((n) => ({ nodeId: n.id, alert: nodeAlert(n, todayYmd) })).filter((x) => x.alert)
  return {
    nodes: nodes.length,
    passed: nodes.filter((n) => n.status === 'passed').length,
    spentMinutes: spent,
    budgetMinutes: budget,
    nextNodeId: open[0]?.id || '',
    alerts,
  }
}
