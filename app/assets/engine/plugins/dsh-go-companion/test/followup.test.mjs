// C3：追问锁、裁决与待办义务。纯函数，时间全部由调用方给。
// 五条路径：满意 / 不满意 / 格式不合格 / 超时 / 超时后再触发。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  FOLLOWUP_CAP_MS, FOLLOWUP_PENALTY, OBLIGATION_TABLE, VERDICT_KINDS,
  addObligation, checkVerdict, clearObligation, closeLock, followupView, funBlockedBy,
  lockActive, lockExpired, normalizeKind, obligationOf, obligationsView, openLock, takeReask,
} from '../lib/followup.js'

const T0 = Date.parse('2026-03-10T10:00:00Z')

test('C3 裁决只认六种 kind，中英文都收', () => {
  assert.deepEqual(VERDICT_KINDS, ['morning', 'review', 'defiance', 'standby', 'gemini', 'evening'])
  assert.equal(normalizeKind('验收'), 'review')
  assert.equal(normalizeKind('晨间立项'), 'morning')
  assert.equal(normalizeKind('连麦回流'), 'gemini')
  assert.equal(normalizeKind('随便糊弄'), '')
  const bad = checkVerdict({ kind: '乱写', satisfied: true })
  assert.equal(bad.ok, false)
  assert.equal(bad.code, 'bad-kind')
})

test('C3 格式不合格按不满意处理：没 summary / 没 nextStep / 晨间没任务都过不了', () => {
  assert.equal(checkVerdict({ kind: 'review', satisfied: 'yes' }).code, 'bad-satisfied')
  assert.equal(checkVerdict({ kind: 'review', satisfied: true }).code, 'no-summary')
  assert.equal(checkVerdict({ kind: 'review', satisfied: true, summary: '做完了' }).code, 'no-next')
  const noTasks = checkVerdict({ kind: 'morning', satisfied: true, summary: '定了', nextStep: '做' })
  assert.equal(noTasks.code, 'bad-tasks')
  // 晨间立项最多 3 项，每项都要有完成物。
  const tooMany = checkVerdict({
    kind: 'morning', satisfied: true, summary: '定了', nextStep: '做',
    tasks: [1, 2, 3, 4].map((i) => ({ task: '事' + i, evidence: '证据' + i })),
  })
  assert.equal(tooMany.code, 'bad-tasks')
  const noEvidence = checkVerdict({ kind: 'morning', satisfied: true, summary: '定了', nextStep: '做', tasks: [{ task: '改简历' }] })
  assert.equal(noEvidence.code, 'bad-tasks')
})

test('C3 合格的裁决原样带出，nextStep 兼容 next_step', () => {
  const v = checkVerdict({ kind: '验收', satisfied: true, summary: '作品集做完了', next_step: '明天投三家' })
  assert.equal(v.ok, true)
  assert.equal(v.kind, 'review')
  assert.equal(v.nextStep, '明天投三家')
  const m = checkVerdict({
    kind: 'morning', satisfied: true, summary: '定下两件事', nextStep: '按计划走',
    tasks: [{ task: '改简历', evidence: '简历.pdf' }],
  })
  assert.deepEqual(m.tasks, [{ task: '改简历', evidence: '简历.pdf' }])
  // 不满意不带 summary / nextStep 也合法（只记账）。
  assert.equal(checkVerdict({ kind: 'review', satisfied: false }).ok, true)
})

test('C3 同一时刻只有一把锁；同原因重复开不续命，换原因才重新计时', () => {
  const a = openLock(null, { reason: 'review', question: '做完了吗', now: T0, capMs: FOLLOWUP_CAP_MS })
  assert.equal(a.until, T0 + FOLLOWUP_CAP_MS)
  // 10 分钟后又开同一个原因：since / until 都不动（绕不过 30 分钟上限）。
  const b = openLock(a, { reason: 'review', question: '到底做完了吗', now: T0 + 10 * 60_000, capMs: FOLLOWUP_CAP_MS })
  assert.equal(b.since, a.since)
  assert.equal(b.until, a.until)
  assert.equal(b.question, '到底做完了吗', '问题可以更新')
  // 换原因：新的一层盖上旧的，重新计时。
  const c = openLock(a, { reason: 'standby', question: '这十分钟在干嘛', now: T0 + 10 * 60_000, capMs: FOLLOWUP_CAP_MS })
  assert.equal(c.reason, 'standby')
  assert.equal(c.since, T0 + 10 * 60_000)
  assert.equal(c.until, T0 + 40 * 60_000)
})

test('C3 锁的活性与超时：到点才算超时，关锁保留原因', () => {
  const lock = openLock(null, { reason: 'defiance', question: '为什么又打开', now: T0, capMs: FOLLOWUP_CAP_MS })
  assert.equal(lockActive(lock, T0), true)
  assert.equal(lockActive(lock, T0 + FOLLOWUP_CAP_MS), false, '到点就不算还挂着')
  assert.equal(lockExpired(lock, T0 + FOLLOWUP_CAP_MS - 1), false)
  assert.equal(lockExpired(lock, T0 + FOLLOWUP_CAP_MS), true)
  const closed = closeLock(lock, { now: T0 + 60_000, by: 'verdict' })
  assert.equal(closed.until, 0)
  assert.equal(closed.closedBy, 'verdict')
  assert.equal(closed.reason, 'defiance', '原因留着给快照用')
  assert.equal(lockActive(closed, T0 + 60_000), false)
  assert.equal(lockExpired(closed, T0 + 10 * 60_000), false, '关掉的锁不会再超时')
})

test('C3 超时挂什么义务：晨间不放风、晚间不开窗口、其余只记账', () => {
  assert.equal(obligationOf('morning', T0).blocksFun, true)
  assert.equal(obligationOf('morning', T0).reask, 'locked-app')
  assert.equal(obligationOf('evening', T0).reask, 'none')
  assert.equal(obligationOf('review', T0).reask, 'none')
  for (const kind of ['defiance', 'standby', 'gemini']) {
    assert.equal(obligationOf(kind, T0).reask, 'defiance', kind + ' 下次违抗再问')
    assert.equal(obligationOf(kind, T0).blocksFun, false)
  }
  assert.equal(OBLIGATION_TABLE.morning.blocksFun, true)
  assert.equal(funBlockedBy([obligationOf('morning', T0)]), true)
  assert.equal(funBlockedBy([obligationOf('review', T0)]), false)
})

test('C3 义务只由他的动作触发再问：一条只问一次，成败都清得掉', () => {
  let list = addObligation([], obligationOf('morning', T0))
  list = addObligation(list, obligationOf('defiance', T0 + 1000))
  assert.equal(list.length, 2)

  // 打开被拦的 App：问一次；同一条不再问。
  const first = takeReask(list, 'locked-app', T0 + 2000)
  assert.equal(first.ask.kind, 'morning')
  assert.equal(takeReask(first.obligations, 'locked-app', T0 + 3000).ask, null)

  // 违抗：只问 defiance 那条。
  const second = takeReask(list, 'defiance', T0 + 4000)
  assert.equal(second.ask.kind, 'defiance')

  // 同一原因只留最新一条，不堆一摞。
  const again = addObligation(list, obligationOf('morning', T0 + 5000))
  assert.equal(again.filter((o) => o.kind === 'morning').length, 1)

  // 过关了这条义务就消掉。
  assert.equal(clearObligation(again, 'morning').some((o) => o.kind === 'morning'), false)
})

test('C3 快照：锁和义务只给手机看得懂的字段，不泄露账本细节', () => {
  const lock = openLock(null, { reason: 'review', question: '这一轮做完了吗', now: T0, capMs: FOLLOWUP_CAP_MS })
  const view = followupView(lock, T0 + 60_000)
  assert.deepEqual(Object.keys(view).sort(), ['active', 'currentQuestion', 'reason', 'since', 'until'])
  assert.equal(view.active, true)
  assert.equal(view.reason, 'review')
  assert.equal(view.currentQuestion, '这一轮做完了吗')
  // 关掉之后：active false，问题不再下发。
  const off = followupView(closeLock(lock, { now: T0 + 60_000 }), T0 + 120_000)
  assert.equal(off.active, false)
  assert.equal(off.currentQuestion, '')

  const list = [obligationOf('morning', T0)]
  const ob = obligationsView(list, T0 + 60_000)
  assert.deepEqual(Object.keys(ob[0]).sort(), ['ageMs', 'createdAt', 'kind', 'reask', 'text'])
  assert.equal(ob[0].ageMs, 60_000)
})

test('C3 超时扣分可配：默认 5 分', () => {
  assert.equal(FOLLOWUP_PENALTY, 5)
})
