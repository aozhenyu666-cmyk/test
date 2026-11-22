// 第六轮 D · 姐妹自建工作流的护栏
//
// 这里守的是任务书 §2.3 的五条硬规矩，和硬验收 ⑥：
//   「自建工作流调用墙相关能力被拒绝」。
// 所以重点断言的是**拒绝**，不是"能跑"：
//   ① 墙/力度/信用分/权限/规则文件一律被能力层拒（不是靠提示词劝）；
//   ② 私密目录被拒；
//   ③ 六项缺一项就被否决；
//   ④ 每周 1 条、同时在跑 ≤3；
//   ⑤ 7 天没可数效果自动停，30 天复核掉下去同样停。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  REVIEW_MS, REQUIRED_FIELDS, TRIAL_MS, WALL_CAPABILITIES, WORKFLOW_ALLOW,
  WORKFLOW_RUN_MAX, WORKFLOW_WEEK_MAX,
  normalizeWorkflow, workflowAct, workflowGuard, workflowPropose, workflowQuota,
  workflowStatus, workflowSweep,
} from '../lib/workflows.js'

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = readFileSync(join(HERE, '..', 'lib', 'workflows.js'), 'utf8')

const TZ = 'Asia/Shanghai'
const NOW = new Date(2026, 10, 10, 14, 0, 0).getTime()

/** 一条合格提议（内容随便，反正它来自姐妹的提议不来自代码）。 */
function w(extra = {}) {
  return {
    problem: '早上起来先刷半小时手机，当天的活就推不动',
    evidence: '近 7 天有 5 天首屏 app 是短视频，日均 42 分钟',
    trigger: '每天早上起床后第一次亮屏',
    ask: '先说今天第一件事是什么',
    metric: '当天 10 点前完成第一件事的天数',
    judge: '7 天里做到 4 天算有效，不到 4 天停掉',
    ...extra,
  }
}

/** 一条已经在试启用的工作流。 */
function trial(id, at) {
  return { ...w(), id, state: 'trial', at, trialAt: at, uses: [] }
}

/** 一条已经转正的工作流。 */
function running(id, at) {
  return { ...w(), id, state: 'running', at, promotedAt: at, uses: [] }
}

// ---------------------------------------------------------------- 硬验收 ⑥：碰不到墙

test('D1 墙相关能力一律被能力层拒（不是提示词劝）', () => {
  assert.ok(WALL_CAPABILITIES.length >= 10)
  for (const cap of WALL_CAPABILITIES) {
    const g = workflowGuard(cap)
    assert.equal(g.ok, false, '墙能力没被拦：' + cap)
    assert.equal(g.code, 'wall-forbidden', '拒绝码不对：' + cap + ' → ' + g.code)
  }
})

test('D2 提议里带墙能力 → 整条否决，并报出拒了哪条', () => {
  const r = normalizeWorkflow(w({ uses: ['vault_search', 'submit_verdict'] }), NOW)
  assert.equal(r.ok, false)
  assert.equal(r.code, 'capability-refused')
  assert.deepEqual(r.rejected, [{ capability: 'submit_verdict', code: 'wall-forbidden' }])
})

test('D3 白名单外的能力默认关门', () => {
  const g = workflowGuard('some_new_tool')
  assert.equal(g.ok, false)
  assert.equal(g.code, 'not-allowed')
  // 白名单里的照常过。
  for (const cap of WORKFLOW_ALLOW) assert.equal(workflowGuard(cap).ok, true, cap)
})

test('D4 私密目录被拒，普通笔记路径照常过', () => {
  for (const p of ['/data/data/com.tencent.mm/db', '/storage/emulated/0/Android/data/x', 'C:\\Users\\a\\.ssh\\id_rsa', '/sdcard/DCIM/a.jpg']) {
    const g = workflowGuard('vault_search', { path: p })
    assert.equal(g.ok, false, '私密路径没被拦：' + p)
    assert.equal(g.code, 'private-path')
  }
  assert.equal(workflowGuard('vault_search', { path: 'D:/notes/daily.md' }).ok, true)
})

test('D5 要用户手动做的动作一律否掉（不手动交题）', () => {
  for (const ask of ['每天打卡一次', '把错题拍照发我', '记得去点一下确认', '每天手动填一行']) {
    const r = normalizeWorkflow(w({ ask }), NOW)
    assert.equal(r.ok, false, '手动动作没被拦：' + ask)
    assert.equal(r.code, 'manual-action')
  }
  // 不要求他动手的照常过。
  assert.equal(normalizeWorkflow(w({ ask: '先说今天第一件事是什么' }), NOW).ok, true)
})

// ---------------------------------------------------------------- 六项缺一项就否决

test('D6 六项缺任何一项都否决，并说清缺哪项', () => {
  assert.deepEqual(REQUIRED_FIELDS, ['problem', 'evidence', 'trigger', 'ask', 'metric', 'judge'])
  for (const key of REQUIRED_FIELDS) {
    const raw = w()
    delete raw[key]
    const r = normalizeWorkflow(raw, NOW)
    assert.equal(r.ok, false, '缺 ' + key + ' 竟然过了')
    assert.equal(r.code, 'field-missing')
    assert.equal(r.missing.length, 1)
  }
  // 只有空白也算缺。
  assert.equal(normalizeWorkflow(w({ judge: '   ' }), NOW).code, 'field-missing')
})

test('D7 六项齐了才生成一条 proposed，单项超长截断', () => {
  const r = normalizeWorkflow(w({ metric: 'x'.repeat(500) }), NOW)
  assert.equal(r.ok, true)
  assert.equal(r.workflow.state, 'proposed')
  assert.equal(r.workflow.metric.length, 201) // 200 + …
  assert.equal(r.workflow.id.startsWith('w'), true)
})

// ---------------------------------------------------------------- 配额

test('D8 每周最多 1 条', () => {
  assert.equal(WORKFLOW_WEEK_MAX, 1)
  const entries = [{ v: 1, state: 'workflow-proposed', at: NOW - 3_600_000 }]
  const q = workflowQuota([], entries, NOW, TZ)
  assert.equal(q.weekUsed, 1)
  assert.equal(q.weekOk, false)
  const r = workflowPropose([], entries, w(), NOW, TZ)
  assert.equal(r.ok, false)
  assert.equal(r.code, 'week-quota')
  // 下周就又有额度了。
  assert.equal(workflowQuota([], entries, NOW + 7 * 86_400_000, TZ).weekOk, true)
})

test('D9 同时在跑 ≤3 条，满了先停再加', () => {
  assert.equal(WORKFLOW_RUN_MAX, 3)
  const list = [trial('a', NOW), trial('b', NOW), running('c', NOW)]
  const q = workflowQuota(list, [], NOW, TZ)
  assert.equal(q.running, 3)
  assert.equal(q.runningOk, false)
  const r = workflowPropose(list, [], w(), NOW, TZ)
  assert.equal(r.ok, false)
  assert.equal(r.code, 'running-quota')
  // 停掉一条就又有位置。
  assert.equal(workflowQuota([list[0], list[1]], [], NOW, TZ).runningOk, true)
})

test('D10 同一个问题不重复提', () => {
  const list = [trial('a', NOW)]
  const r = workflowPropose(list, [], w(), NOW, TZ)
  assert.equal(r.ok, false)
  assert.equal(r.code, 'duplicate')
  // 停掉的那条不算重复（可以重新提）。
  assert.equal(workflowPropose([{ ...trial('a', NOW), state: 'stopped' }], [], w(), NOW, TZ).ok, true)
})

// ---------------------------------------------------------------- 用户点「试」才启用

test('D11 不点「试」不启用；点了才进 trial，且只走一次', () => {
  const list = [normalizeWorkflow(w(), NOW).workflow]
  assert.equal(list[0].state, 'proposed')
  const r = workflowAct(list, list[0].id, 'trial', NOW + 1000)
  assert.equal(r.ok, true)
  assert.equal(r.workflow.state, 'trial')
  assert.equal(r.workflow.trialAt, NOW + 1000)
  // 已经是 trial 的不能再"启用"一次。
  assert.equal(workflowAct(r.workflows, list[0].id, 'trial', NOW + 2000).code, 'bad-state')
  // 不存在的 id。
  assert.equal(workflowAct(list, 'nope', 'trial', NOW).code, 'not-found')
})

// ---------------------------------------------------------------- 7 天 / 30 天

test('D12 试启用 7 天没有可数效果 → 自动停', () => {
  const list = [trial('a', NOW)]
  // 差 1 毫秒都不停，到点才停。
  assert.equal(workflowSweep(list, [], NOW + TRIAL_MS - 1).events.length, 0)
  const s = workflowSweep(list, [], NOW + TRIAL_MS)
  assert.equal(s.events.length, 1)
  assert.equal(s.events[0].kind, 'stopped')
  assert.equal(s.events[0].reason, 'no-effect-7d')
  assert.equal(s.next[0].state, 'stopped')
  assert.equal(s.next[0].stopReason, 'no-effect-7d')
})

test('D13 试启用期间有效果 → 转正', () => {
  const list = [trial('a', NOW)]
  const entries = [{ v: 1, state: 'workflow-effect', workflowId: 'a', count: 2, at: NOW + 86_400_000 }]
  const s = workflowSweep(list, entries, NOW + TRIAL_MS)
  assert.equal(s.events[0].kind, 'promoted')
  assert.equal(s.next[0].state, 'running')
  assert.equal(s.next[0].promotedAt, NOW + TRIAL_MS)
})

test('D14 count = 0 的效果不算效果（不数空转）', () => {
  const list = [trial('a', NOW)]
  const entries = [{ v: 1, state: 'workflow-effect', workflowId: 'a', count: 0, at: NOW + 86_400_000 }]
  assert.equal(workflowSweep(list, entries, NOW + TRIAL_MS).events[0].kind, 'stopped')
})

test('D15 别的 id 的效果不算这条的效果', () => {
  const list = [trial('a', NOW)]
  const entries = [{ v: 1, state: 'workflow-effect', workflowId: 'b', count: 3, at: NOW + 86_400_000 }]
  assert.equal(workflowSweep(list, entries, NOW + TRIAL_MS).events[0].kind, 'stopped')
})

test('D16 转正后 30 天复核：还在就续期，掉下去就停', () => {
  const list = [running('a', NOW)]
  assert.equal(workflowSweep(list, [], NOW + REVIEW_MS - 1).events.length, 0)
  const kept = workflowSweep(list, [{ v: 1, state: 'workflow-effect', workflowId: 'a', count: 1, at: NOW + 86_400_000 }], NOW + REVIEW_MS)
  assert.equal(kept.events[0].kind, 'reviewed')
  assert.equal(kept.next[0].state, 'running')
  assert.equal(kept.next[0].promotedAt, NOW + REVIEW_MS)
  const dead = workflowSweep(list, [], NOW + REVIEW_MS)
  assert.equal(dead.events[0].kind, 'stopped')
  assert.equal(dead.events[0].reason, 'no-effect-30d')
})

test('D17 没到期的原样留着，不提前处理', () => {
  const list = [trial('a', NOW), running('b', NOW), normalizeWorkflow(w({ problem: '另一个问题' }), NOW).workflow]
  const s = workflowSweep(list, [], NOW + 1000)
  assert.equal(s.events.length, 0)
  assert.equal(s.next.length, 3)
  assert.equal(s.next[0].state, 'trial')
  assert.equal(s.next[1].state, 'running')
  assert.equal(s.next[2].state, 'proposed')
})

// ---------------------------------------------------------------- 面板与自检

test('D18 面板能一眼看到配额和还剩多久到期', () => {
  const list = [trial('a', NOW), normalizeWorkflow(w({ problem: '另一个问题' }), NOW).workflow]
  const st = workflowStatus(list, [], NOW + 86_400_000, TZ)
  assert.equal(st.trial, 1)
  assert.equal(st.proposed, 1)
  assert.equal(st.quota.running, 1)
  assert.equal(st.items[0].leftMs, TRIAL_MS - 86_400_000)
  assert.equal(st.items[1].deadline, null)
  assert.ok(st.deny.includes('submit_verdict'))
  assert.ok(st.allow.includes('vault_search'))
  assert.equal(workflowStatus([], [], NOW, TZ).count, 0)
})

test('D19 这个模块不自己开调度器，也不自己落账本', () => {
  // 挂载点在 planRoute（手机闹钟问 /plan 时顺带跑），这里只许出纯函数。
  for (const bad of ['setInterval', 'setTimeout', 'appendLedger', 'cron', 'schedule_create']) {
    assert.equal(SRC.includes(bad), false, 'lib/workflows.js 里出现了不该有的东西：' + bad)
  }
})

test('D20 空输入不炸（脏配置撑得住）', () => {
  assert.equal(workflowSweep(null, null, NOW).next.length, 0)
  assert.equal(workflowSweep([null, 3, 'x'], null, NOW).next.length, 0)
  assert.equal(normalizeWorkflow(null, NOW).code, 'not-an-object')
  assert.equal(workflowGuard('').code, 'no-capability')
  assert.equal(workflowStatus(null, null, NOW, TZ).count, 0)
  assert.equal(workflowPropose(null, null, w(), NOW, TZ).ok, true)
})
