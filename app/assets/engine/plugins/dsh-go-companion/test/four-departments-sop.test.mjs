import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

import {
  COLLECT_QUESTION_MAX_CHARS,
  DISPATCH_WINDOW_MS,
  ERR_NO_RAW_FACTS,
  JUDGE_ARCHIVE_DAYS,
  archiveJudgeFile,
  collectDepartmentSop,
  dispatchDepartmentSop,
  governDepartmentSop,
  inferDepartment,
  rolling7dCompletedCap,
  routeBoutDepartment,
  strategizeDepartmentSop,
} from '../lib/departments.js'
import { closeBout, openBout, openBoutsDb } from '../lib/bouts.js'
import { JUDGE_FILE } from '../lib/notes.js'

test('D01 dept:collect 拦截空问题与超 25 字 target_question', () => {
  const empty = collectDepartmentSop({ targetQuestion: '', rawFacts: ['fact1'] })
  assert.equal(empty.ok, false)
  assert.equal(empty.code, 'ERR_EMPTY_QUESTION')

  const longQ = '为什么今天上午第三节高等数学复习效率比上周低这么多且注意力分散呢'
  assert.ok(longQ.length > COLLECT_QUESTION_MAX_CHARS)
  const over = collectDepartmentSop({ targetQuestion: longQ, rawFacts: ['fact1'] })
  assert.equal(over.ok, false)
  assert.equal(over.code, 'ERR_QUESTION_TOO_LONG')
})

test('D02 dept:collect 无真实事实卡时拒绝建卡 ERR_NO_RAW_FACTS', () => {
  const res = collectDepartmentSop({ targetQuestion: 'B站占用多长', rawFacts: [] })
  assert.equal(res.ok, false)
  assert.equal(res.code, ERR_NO_RAW_FACTS)
})

test('D03 dept:collect 生成 ≥1 张 raw_fact_cards 并写入 大肥鱼/收集箱/', () => {
  const kb = mkdtempSync(join(tmpdir(), 'dafeiyu-collect-'))
  try {
    const res = collectDepartmentSop({
      targetQuestion: '今日数学错题集中在哪',
      rawFacts: [
        { fact: '分部积分第3题符号连续错两次', source: '错题本.md' },
        { fact: '中值定理构造辅助函数耗时 12 分钟', source: '计时器' },
      ],
      kb,
      at: Date.UTC(2026, 10, 14, 10, 0, 0),
    })
    assert.equal(res.ok, true)
    assert.equal(res.department, 'dept:collect')
    assert.equal(res.raw_fact_cards.length, 2)
    assert.ok(res.obsidianPath && existsSync(res.obsidianPath))
    assert.ok(res.obsidianPath.includes('收集箱'))
    const md = readFileSync(res.obsidianPath, 'utf8')
    assert.match(md, /分部积分第3题符号连续错两次/)
  } finally {
    rmSync(kb, { recursive: true, force: true })
  }
})

test('D04 dept:strategize 无 raw_fact_cards 且无未决问题卡时硬门禁拒单 ERR_NO_RAW_FACTS', () => {
  const res = strategizeDepartmentSop({
    rawFactCards: [],
    openQuestionCard: null,
    decision: '今天全天写卷子',
  })
  assert.equal(res.ok, false)
  assert.equal(res.code, ERR_NO_RAW_FACTS)
})

test('D05 dept:strategize 绑定 raw_fact_cards 后产出 single_point_decision', () => {
  const res = strategizeDepartmentSop({
    rawFactCards: [{ id: 'f1', fact: '近两天分部积分错题率 40%' }],
    decision: '下一局只刷 5 道分部积分边界题并写反证卡',
    falsifyPath: '若 5 题全对超过 15 分钟说明速度仍不达标',
  })
  assert.equal(res.ok, true)
  assert.equal(res.department, 'dept:strategize')
  assert.equal(res.single_point_decision.decision, '下一局只刷 5 道分部积分边界题并写反证卡')
  assert.deepEqual(res.single_point_decision.linked_card_ids, ['f1'])
})

test('D06 dept:strategize 也接受绑定有效的未决问题卡 openQuestionCard', () => {
  const res = strategizeDepartmentSop({
    openQuestionCard: { id: 'q-42', target_question: '为什么昨晚超时？' },
    decision: '先把前置核查卡补齐再开局',
  })
  assert.equal(res.ok, true)
  assert.equal(res.single_point_decision.open_question_id, 'q-42')
})

test('D07 dept:dispatch 排期窗口超过 5 分钟硬拦截 ERR_DISPATCH_WINDOW_EXPIRED', () => {
  const start = 1_700_000_000_000
  const expired = dispatchDepartmentSop({
    proposedQueue: [{ protocolId: 'study' }],
    windowStartMs: start,
    now: start + DISPATCH_WINDOW_MS + 1000,
  })
  assert.equal(expired.ok, false)
  assert.equal(expired.code, 'ERR_DISPATCH_WINDOW_EXPIRED')
})

test('D08 dept:dispatch 读取真实 SQLite bouts 表近 7 天完成局上限并拦截过载排期', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dafeiyu-disp-'))
  const db = openBoutsDb(join(dir, 'fish-memory.sqlite'))
  try {
    const now = Date.UTC(2026, 10, 14, 9, 0, 0)
    // 近 7 天真实完成 7 局（日均 1 局），另加 1 局 abandoned 不计入产能
    for (let i = 0; i < 7; i++) {
      const opened = openBout(db, { id: `b-${i}`, protocolId: 'study', now: now - (i + 1) * 86_400_000 + 1000 })
      assert.equal(opened.ok, true)
      closeBout(db, `b-${i}`, { now: now - (i + 1) * 86_400_000 + 600_000, reason: 'done' })
    }
    openBout(db, { id: 'b-abd', protocolId: 'study', now: now - 3_600_000 })
    closeBout(db, 'b-abd', { now: now - 3_000_000, reason: 'abandoned' })

    const capInfo = rolling7dCompletedCap({ db, now })
    assert.equal(capInfo.completed7d, 7)
    assert.equal(capInfo.dailyCap, 1)

    const over = dispatchDepartmentSop({
      db,
      proposedQueue: [{ protocolId: 'study' }, { protocolId: 'design' }],
      windowStartMs: now - 60_000,
      now,
    })
    assert.equal(over.ok, false)
    assert.equal(over.code, 'ERR_OVERSCHEDULED')

    const valid = dispatchDepartmentSop({
      db,
      proposedQueue: [{ protocolId: 'study' }],
      windowStartMs: now - 60_000,
      now,
    })
    assert.equal(valid.ok, true)
    assert.equal(valid.locked_plan_modality_queue.length, 1)
    assert.equal(valid.locked_plan_modality_queue[0].locked, true)
  } finally {
    db?.close?.()
    rmSync(dir, { recursive: true, force: true })
  }
})

test('D09 rolling7dCompletedCap 高完成基数按 ceil(completed/7) 动态放宽上限', () => {
  const now = 1_700_000_000_000
  const history = Array.from({ length: 21 }, (_, i) => ({
    status: 'closed',
    closeReason: 'done',
    closedAt: now - (i % 6 + 1) * 3_600_000,
  }))
  const cap = rolling7dCompletedCap({ boutsHistory: history, now })
  assert.equal(cap.completed7d, 21)
  assert.equal(cap.dailyCap, 3)
})

test('D10 dept:govern 绑定 locked_plan_modality_queue 并生成 daily_behavior_audit + 晚间幂等回写 判断.md', () => {
  const kb = mkdtempSync(join(tmpdir(), 'dafeiyu-gov-'))
  try {
    const at = Date.UTC(2026, 10, 14, 21, 30, 0)
    const queue = [{ protocolId: 'study', order: 1 }, { protocolId: 'execute', order: 2 }]
    const verdicts = [{ pass: true }, { pass: false }]
    const res1 = governDepartmentSop({
      lockedQueue: queue,
      verdicts,
      telemetry: { funMinutesToday: 18, workMinutesToday: 95 },
      kb,
      at,
    })
    assert.equal(res1.ok, true)
    assert.equal(res1.department, 'dept:govern')
    assert.equal(res1.daily_behavior_audit.adherenceRate, 0.5)
    assert.equal(res1.daily_behavior_audit.funMinutes, 18)
    assert.ok(res1.judgePath.endsWith(JUDGE_FILE))
    const text1 = readFileSync(res1.judgePath, 'utf8')
    assert.match(text1, /督办行为审计/)
    assert.match(text1, /履约率：50%/)

    // 同一天再次运行不重复追加
    governDepartmentSop({ lockedQueue: queue, verdicts, telemetry: { funMinutesToday: 18, workMinutesToday: 95 }, kb, at })
    const text2 = readFileSync(res1.judgePath, 'utf8')
    assert.equal(text1, text2)
  } finally {
    rmSync(kb, { recursive: true, force: true })
  }
})

test('D11 inferDepartment 在缺少材料/前提争议/排期冲突/执行态间准确路由', () => {
  assert.equal(inferDepartment({ text: '查一下这份资料真假' }).department, 'dept:collect')
  assert.equal(inferDepartment({ text: '这个前提和因果为什么不成立' }).department, 'dept:strategize')
  assert.equal(inferDepartment({ text: '今天排期时间不够要先做哪个' }).department, 'dept:dispatch')
  assert.equal(inferDepartment({ mainState: 'sprint', runningBout: true, text: '继续执行' }).department, 'dept:govern')
})

test('D12 routeBoutDepartment 流转部门同时更新 SQLite bouts.department 与 ledger department-switch', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dafeiyu-route-'))
  const db = openBoutsDb(join(dir, 'fish-memory.sqlite'))
  try {
    openBout(db, { id: 'b-route', protocolId: 'study', department: 'dept:collect' })
    const r = routeBoutDepartment({
      db,
      home: dir,
      boutId: 'b-route',
      currentDept: 'dept:collect',
      input: { text: '这个前提有漏洞需要反证' },
    })
    assert.equal(r.ok, true)
    assert.equal(r.changed, true)
    assert.equal(r.department, 'dept:strategize')

    const idem = routeBoutDepartment({
      db,
      home: dir,
      boutId: 'b-route',
      currentDept: 'dept:strategize',
      input: { explicitDepartment: 'dept:strategize' },
    })
    assert.equal(idem.ok, true)
    assert.equal(idem.changed, false)
  } finally {
    db?.close?.()
    rmSync(dir, { recursive: true, force: true })
  }
})

test('D13 archiveJudgeFile / governDepartmentSop：判断.md 写入前按月归档超 30 天记录，保留核心规则与近 30 天记录', () => {
  assert.equal(JUDGE_ARCHIVE_DAYS, 30)
  const kb = mkdtempSync(join(tmpdir(), 'dafeiyu-judge-arch-'))
  try {
    const judgeFile = join(kb, JUDGE_FILE)
    writeFileSync(judgeFile, [
      '# 判断',
      '',
      '- 常驻判断：连续切换超过 5 次立刻拉回主线。',
      '',
      '<!-- govern-audit:2026-09-12 -->',
      '### 2026-09-12 · 督办行为审计',
      '- 计划局数：2 | 通过：1 | 未过：1 | 履约率：50%',
      '',
      '<!-- govern-audit:2026-11-10 -->',
      '### 2026-11-10 · 督办行为审计',
      '- 计划局数：3 | 通过：3 | 未过：0 | 履约率：100%',
      '',
    ].join('\n'), 'utf8')

    const atNov22 = Date.parse('2026-11-22T21:30:00+08:00')
    const archPre = archiveJudgeFile(kb, { now: atNov22 })
    assert.equal(archPre.ok, true)
    assert.equal(archPre.changed, true)
    assert.equal(archPre.archivedCount, 1)

    const active = readFileSync(judgeFile, 'utf8')
    assert.match(active, /常驻判断：连续切换超过 5 次立刻拉回主线/)
    assert.match(active, /2026-11-10 · 督办行为审计/)
    assert.doesNotMatch(active, /2026-09-12 · 督办行为审计/)

    const septArch = join(kb, '归档', '判断-2026-09.md')
    assert.equal(existsSync(septArch), true)
    assert.match(readFileSync(septArch, 'utf8'), /2026-09-12 · 督办行为审计/)

    // 再写入 2026-11-22 审计并塞入一条 2026-10-01 历史审计验证 governDepartmentSop 自动裁剪
    const cur = readFileSync(judgeFile, 'utf8')
    writeFileSync(judgeFile, cur + '\n<!-- govern-audit:2026-10-01 -->\n### 2026-10-01 · 督办行为审计\n- 计划局数：1 | 通过：0 | 未过：1 | 履约率：0%\n', 'utf8')
    const gRes = governDepartmentSop({
      lockedQueue: [{ protocolId: 'study', order: 1 }],
      verdicts: [{ pass: true }],
      telemetry: { funMinutesToday: 10, workMinutesToday: 45 },
      kb,
      at: atNov22,
    })
    assert.equal(gRes.ok, true)
    const activeAfter = readFileSync(judgeFile, 'utf8')
    assert.doesNotMatch(activeAfter, /2026-10-01 · 督办行为审计/)
    assert.match(activeAfter, /2026-11-22 · 督办行为审计/)
    assert.match(readFileSync(join(kb, '归档', '判断-2026-10.md'), 'utf8'), /2026-10-01 · 督办行为审计/)
  } finally {
    rmSync(kb, { recursive: true, force: true })
  }
})
