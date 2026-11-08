import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  buildReport,
  dayKey,
  parseArgs,
  readJsonl,
  renderTable,
  summarizeForeground,
  summarizeLedger,
} from './weekly-report.mjs'

const TZ = 'Asia/Shanghai'
const at = (s) => Date.parse(s)

const LEDGER = [
  { requestId: 'r1', state: 'generated', at: at('2026-11-07T10:00:00+08:00') },
  { requestId: 'r1', state: 'replied', at: at('2026-11-07T10:05:00+08:00') },
  // A rally's second return must not count as a second answer or a second interval.
  { requestId: 'r1', state: 'replied', at: at('2026-11-07T10:40:00+08:00') },
  { requestId: 'r2', state: 'generated', at: at('2026-11-07T11:00:00+08:00') },
  { requestId: 'r2', state: 'none', at: at('2026-11-07T13:00:00+08:00') },
  { requestId: 'r3', state: 'generated', at: at('2026-11-07T12:00:00+08:00') },
  { requestId: 'r3', state: 'replied', at: at('2026-11-07T12:02:00+08:00') },
  { state: 'defied', at: at('2026-11-07T13:30:00+08:00') },
  { state: 'breakglass', at: at('2026-11-07T14:00:00+08:00') },
  { requestId: 'r4', state: 'generated', at: at('2026-11-08T09:00:00+08:00') },
  { requestId: 'r4', state: 'replied', at: at('2026-11-08T09:03:00+08:00') },
]

const FOREGROUND = [
  { at: at('2026-11-07T10:00:00+08:00'), app: 'tv.danmaku.bili' },
  { at: at('2026-11-07T10:05:00+08:00'), app: 'com.tencent.mm' },
  { at: at('2026-11-07T10:10:00+08:00'), app: 'tv.danmaku.bili' },
  { at: at('2026-11-07T10:22:00+08:00'), app: 'com.tencent.mm' },
  { at: at('2026-11-08T09:00:00+08:00'), app: 'tv.danmaku.bili' },
  { at: at('2026-11-08T09:04:00+08:00'), app: 'com.tencent.mm' },
]

test('按本地日期分组', () => {
  assert.equal(dayKey(at('2026-11-07T23:30:00+08:00'), TZ), '2026-11-07')
  assert.equal(dayKey(at('2026-11-07T16:30:00Z'), TZ), '2026-11-08')
})

test('账本：主动消息数、回复率、平均回复间隔、违抗、破窗', () => {
  const days = summarizeLedger(LEDGER, TZ)
  const d1 = days.get('2026-11-07')
  assert.equal(d1.generated, 3)
  assert.equal(d1.answered, 2)
  assert.deepEqual(d1.intervals, [5 * 60_000, 2 * 60_000])
  assert.equal(d1.defied, 1)
  assert.equal(d1.breakglass, 1)
  const d2 = days.get('2026-11-08')
  assert.equal(d2.generated, 1)
  assert.equal(d2.answered, 1)
  assert.deepEqual(d2.intervals, [3 * 60_000])
})

test('前台日志：娱乐类才计时，超过 30 分钟的间隔当灭屏丢掉', () => {
  const minutes = summarizeForeground([
    { at: at('2026-11-07T10:00:00+08:00'), app: 'tv.danmaku.bili' },
    { at: at('2026-11-07T10:05:00+08:00'), app: 'com.tencent.mm' },
    { at: at('2026-11-07T10:10:00+08:00'), app: 'tv.danmaku.bili' },
    { at: at('2026-11-07T10:22:00+08:00'), app: 'com.tencent.mm' },
    { at: at('2026-11-07T11:30:00+08:00'), app: 'tv.danmaku.bili' },
  ], TZ)
  // 5 + 12 分钟；10:22→11:30 是 68 分钟，灭屏，不计。
  assert.equal(minutes.get('2026-11-07'), 17)
})

test('表格：两天的四个核心数字和娱乐分钟', () => {
  const report = buildReport({ ledger: LEDGER, foreground: FOREGROUND }, { tz: TZ })
  assert.equal(
    report.table,
    '| 日期 | 主动消息数 | 回复率 | 平均回复间隔 | 违抗次数 | 破窗次数 | 娱乐 App 分钟数 |\n' +
    '| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n' +
    '| 2026-11-07 | 3 | 67% | 3.5 分钟 | 1 | 1 | 17 |\n' +
    '| 2026-11-08 | 1 | 100% | 3.0 分钟 | 0 | 0 | 4 |',
  )
})

test('没有前台日志时娱乐一列是 —，其余照常', () => {
  const table = renderTable(summarizeLedger(LEDGER, TZ), new Map(), { tz: TZ })
  assert.match(table, /\| 2026-11-07 \| 3 \| 67% \| 3\.5 分钟 \| 1 \| 1 \| — \|/)
})

test('--days 只留最近 N 天', () => {
  const report = buildReport({ ledger: LEDGER, foreground: FOREGROUND }, { tz: TZ, days: 1 })
  assert.match(report.table, /2026-11-08/)
  assert.doesNotMatch(report.table, /2026-11-07/)
})

test('参数解析', () => {
  assert.deepEqual(parseArgs(['--ledger', 'a.jsonl', '--foreground', 'b.jsonl', '--tz', 'UTC', '--days', '7']), {
    ledger: 'a.jsonl', foreground: 'b.jsonl', tz: 'UTC', days: 7,
  })
  assert.throws(() => parseArgs(['--nope']), /unknown argument/)
})

test('读账本：坏行跳过，文件不存在返回空', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dafeiyu-report-'))
  try {
    const file = join(dir, 'ledger.jsonl')
    writeFileSync(file, '{"state":"generated","at":1}\nnot json\n\n{"state":"defied","at":2}\n')
    assert.equal(readJsonl(file).length, 2)
    assert.deepEqual(readJsonl(join(dir, 'missing.jsonl')), [])
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
