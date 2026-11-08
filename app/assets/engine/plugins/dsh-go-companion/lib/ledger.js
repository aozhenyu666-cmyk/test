// dsh-go-companion · 互动账本
//
// 一行一次状态变化，requestId 贯穿；用于回答“哪一步失败”和“今天说了几次”。
// 只写完成当前功能所需的数据：不写屏幕内容、不写凭据。

import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

const MAX_BYTES = 2 * 1024 * 1024
const KEEP_LINES = 500

export function ledgerPath(home) {
  return join(home, 'companion', 'ledger.jsonl')
}

export function appendLedger(home, entry) {
  const file = ledgerPath(home)
  try {
    mkdirSync(dirname(file), { recursive: true, mode: 0o700 })
    if (existsSync(file) && statSync(file).size > MAX_BYTES) compact(file)
    appendFileSync(file, JSON.stringify(entry) + '\n', { encoding: 'utf8', mode: 0o600 })
    return true
  } catch {
    return false
  }
}

function compact(file) {
  try {
    const lines = readFileSync(file, 'utf8').split('\n').filter(Boolean)
    const kept = lines.slice(-KEEP_LINES).join('\n') + '\n'
    const tmp = file + '.' + String(process.pid) + '.tmp'
    writeFileSync(tmp, kept, { encoding: 'utf8', mode: 0o600 })
    renameSync(tmp, file)
  } catch {
    // 压缩失败不影响追加；下一轮再试。
  }
}

export function readLedger(home, limit = 100) {
  const file = ledgerPath(home)
  if (!existsSync(file)) return []
  try {
    const lines = readFileSync(file, 'utf8').split('\n').filter(Boolean)
    const out = []
    for (const line of lines.slice(-Math.max(1, limit))) {
      try {
        const v = JSON.parse(line)
        if (v && typeof v === 'object') out.push(v)
      } catch {
        // 跳过坏行
      }
    }
    return out
  } catch {
    return []
  }
}

/** 当天统计 + 最近一条话题（用于 24h 不重复）。 */
export function summarize(entries, dayStart) {
  const counts = {
    planned: 0,
    accepted: 0,
    generated: 0,
    failed: 0,
    replied: 0,
    substantive: 0,
    none: 0,
    delivered: 0,
    displayed: 0,
    dismissed: 0,
  }
  let lastTopicId = ''
  let lastAt = 0
  const TALKED = new Set(['generated', 'delivered', 'displayed', 'replied', 'substantive'])
  for (const e of entries) {
    if (Number(e.at) >= dayStart && Object.hasOwn(counts, e.state)) counts[e.state] += 1
    if (e.topicId && TALKED.has(e.state) && Number(e.at) >= lastAt) {
      lastTopicId = String(e.topicId)
      lastAt = Number(e.at) || 0
    }
  }
  return { counts, lastTopicId, lastAt }
}
