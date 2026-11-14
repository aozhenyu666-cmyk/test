#!/usr/bin/env node
// D3 · 大肥鱼周报：按天读账本，给出频率和话术要不要调的判断材料。
//
//   node tooling/dafeiyu/weekly-report.mjs --ledger companion/ledger.jsonl \
//     [--foreground phone/foreground.jsonl] [--tz Asia/Shanghai] [--days 14]
//
// 主动消息数 / 回复率 / 平均回复间隔 / 违抗次数 / 破窗次数来自 ledger.jsonl。
// 娱乐 App 分钟数来自 D2 的前台日志（同一份导出里的 phone/foreground.jsonl）：
// 两条「前台变化」之间的时间算给前一个 App；超过 30 分钟的间隔当成灭屏/未知，不计。
// 没有前台日志时该列显示「—」，其余列照常。

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { isFun } from '../../app/assets/engine/plugins/dsh-go-companion/lib/phone.js'

const MINUTE = 60_000
const MAX_SEGMENT_MS = 30 * MINUTE
const DEFAULT_TZ = 'Asia/Shanghai'

export const COLUMNS = ['日期', '主动消息数', '回复率', '平均回复间隔', '违抗次数', '破窗次数', '娱乐 App 分钟数']

export function parseArgs(argv = process.argv.slice(2)) {
  const out = { tz: DEFAULT_TZ, days: 0 }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    const value = argv[i + 1]
    if (arg === '--ledger') { out.ledger = value; i++ }
    else if (arg === '--foreground') { out.foreground = value; i++ }
    else if (arg === '--tz') { out.tz = value; i++ }
    else if (arg === '--days') { out.days = Math.max(0, Number(value) || 0); i++ }
    else if (arg === '--help' || arg === '-h') { out.help = true }
    else throw new Error(`unknown argument: ${arg}`)
  }
  return out
}

export function readJsonl(path) {
  const rows = []
  let text = ''
  try {
    text = readFileSync(path, 'utf8')
  } catch {
    return rows
  }
  for (const line of text.split('\n')) {
    if (!line.trim()) continue
    try {
      const value = JSON.parse(line)
      if (value && typeof value === 'object') rows.push(value)
    } catch {
      // 坏行跳过：一份被截断的账本不该让整张表消失。
    }
  }
  return rows
}

/** Local calendar day (YYYY-MM-DD) for an epoch-ms instant. */
export function dayKey(at, tz = DEFAULT_TZ) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(new Date(normalizeAt(at)))
}

function normalizeAt(raw) {
  const n = Number(raw)
  if (!Number.isFinite(n) || n <= 0) return 0
  return n < 1e12 ? n * 1000 : n
}

/**
 * Per-day counts from the ledger.
 * A generated request is counted on the day it was generated; its answer counts toward that
 * same day even when he replies after midnight, so a late-night ball does not look unanswered.
 * Rows are ordered by `at` before pairing, so a ledger whose lines arrived out of order still
 * matches. A generated row without a requestId is skipped: the plugin always writes one, and
 * without it there is nothing to match a reply to.
 */
export function summarizeLedger(rows, tz = DEFAULT_TZ) {
  const days = new Map()
  const day = (key) => {
    if (!days.has(key)) days.set(key, { generated: 0, answered: 0, intervals: [], defied: 0, breakglass: 0 })
    return days.get(key)
  }
  const shots = new Map()
  const answered = new Set()
  const ordered = rows.slice().sort((a, b) => normalizeAt(a?.at) - normalizeAt(b?.at))
  for (const row of ordered) {
    const state = row?.state
    const at = normalizeAt(row?.at)
    if (!state || !at) continue
    if (state === 'generated' && row.requestId && !shots.has(String(row.requestId))) {
      const key = dayKey(at, tz)
      shots.set(String(row.requestId), { key, at })
      day(key).generated += 1
    } else if (state === 'replied' && row.requestId) {
      const id = String(row.requestId)
      const shot = shots.get(id)
      if (!shot || answered.has(id)) continue
      answered.add(id)
      const target = day(shot.key)
      target.answered += 1
      const delta = at - shot.at
      if (delta >= 0) target.intervals.push(delta)
    } else if (state === 'defied') {
      day(dayKey(at, tz)).defied += 1
    } else if (state === 'breakglass') {
      day(dayKey(at, tz)).breakglass += 1
    }
  }
  return days
}

/**
 * Entertainment minutes per day from the D2 foreground log.
 * Each gap between consecutive foreground changes is credited to the app that was in front at
 * the start. Gaps longer than 30 minutes are treated as screen-off/unknown and dropped.
 */
export function summarizeForeground(rows, tz = DEFAULT_TZ) {
  const minutes = new Map()
  const events = rows
    .map((row) => ({ at: normalizeAt(row?.at), app: typeof row?.app === 'string' ? row.app : '' }))
    .filter((row) => row.at > 0 && row.app)
    .sort((a, b) => a.at - b.at)
  for (let i = 0; i + 1 < events.length; i++) {
    const from = events[i]
    const to = events[i + 1]
    const span = to.at - from.at
    if (span <= 0 || span > MAX_SEGMENT_MS) continue
    if (!isFun(from.app)) continue
    const key = dayKey(from.at, tz)
    minutes.set(key, (minutes.get(key) || 0) + span / MINUTE)
  }
  return minutes
}

function rate(generated, answered) {
  if (!generated) return '—'
  return `${Math.round((answered / generated) * 100)}%`
}

function averageMinutes(intervals) {
  if (!intervals.length) return '—'
  return `${(intervals.reduce((a, b) => a + b, 0) / intervals.length / MINUTE).toFixed(1)} 分钟`
}

/** One aligned Markdown table; days ascending. */
export function renderTable(ledger, foreground, { tz = DEFAULT_TZ, days = 0 } = {}) {
  const all = [...new Set([...ledger.keys(), ...foreground.keys()])].sort()
  const kept = days > 0 ? all.slice(-days) : all
  const lines = [
    `| ${COLUMNS.join(' | ')} |`,
    `| ${COLUMNS.map((c, i) => (i === 0 ? '---' : '---:')).join(' | ')} |`,
  ]
  for (const key of kept) {
    const row = ledger.get(key) || { generated: 0, answered: 0, intervals: [], defied: 0, breakglass: 0 }
    const fun = foreground.get(key)
    lines.push(
      `| ${key} | ${row.generated} | ${rate(row.generated, row.answered)} | ${averageMinutes(row.intervals)} | ` +
      `${row.defied} | ${row.breakglass} | ${fun === undefined ? '—' : Math.round(fun)} |`,
    )
  }
  if (!kept.length) lines.push('| （没有数据） | 0 | — | — | — | — | — |')
  return lines.join('\n')
}

export function buildReport({ ledger, foreground }, options = {}) {
  const tz = options.tz || DEFAULT_TZ
  const ledgerDays = summarizeLedger(ledger, tz)
  const foregroundDays = summarizeForeground(foreground, tz)
  return { ledgerDays, foregroundDays, table: renderTable(ledgerDays, foregroundDays, { ...options, tz }) }
}

function main() {
  const args = parseArgs()
  if (args.help || !args.ledger) {
    console.error('usage: node tooling/dafeiyu/weekly-report.mjs --ledger <ledger.jsonl> [--foreground <foreground.jsonl>] [--tz Asia/Shanghai] [--days N]')
    process.exit(args.help ? 0 : 2)
  }
  const ledger = readJsonl(args.ledger)
  const foreground = args.foreground ? readJsonl(args.foreground) : []
  const report = buildReport({ ledger, foreground }, { tz: args.tz, days: args.days })
  console.log(report.table)
  if (!args.foreground) console.error('\n（没有 --foreground：娱乐 App 分钟数一列留空）')
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href
if (isMain) main()
