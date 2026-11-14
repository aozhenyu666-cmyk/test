#!/usr/bin/env node
/**
 * DSH Go 大肥鱼 · PC 端 ActivityWatch 极简协同脚本 (M4.3)
 *
 * 零第三方依赖（仅用 Node >= 20 内置 fetch / fs），从本机 ActivityWatch (默认 http://127.0.0.1:5600)
 * 拉取最近窗口活跃与 AFK 事件，聚合为「活跃分类 / 是否 AFK / 当前窗口标题与专注分钟」心跳，
 * 通过局域网 / Tailscale 推送至手机端伴侣 `/dsh-go-companion/v1/event`（或写入本地 JSON 快照）。
 */

import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'

const DEFAULT_AW_URL = process.env.AW_BASE_URL || 'http://127.0.0.1:5600'
const DEFAULT_COMPANION_URL = process.env.COMPANION_EVENT_URL || ''
const DEFAULT_LOOKBACK_SEC = Number(process.env.AW_LOOKBACK_SEC || 900) || 900

const CODING_APPS = /(code|cursor|windsurf|idea|pycharm|webstorm|goland|rustrover|vim|nvim|wt|windows terminal|powershell|pwsh|iterm|alacritty|cmd)/i
const DOCS_APPS = /(obsidian|notion|logseq|typora|word|wps|acrobat|zotero|onenote|excel)/i
const VIDEO_ENTERTAINMENT = /(bilibili|youtube|douyin|tiktok|iqiyi|youku|netflix|twitch|steam|epicgames|genshin|league|valorant)/i
const BROWSER_APPS = /(chrome|msedge|edge|firefox|brave|arc|safari)/i
const LEARNING_KEYWORDS = /(docs|github|stackoverflow|arxiv|mdn|wikipedia|rfc|tutorial|course|paper|算法|源码|文档)/i

export function classifyPcActivity(app = '', title = '', afkStatus = 'not-afk') {
  if (afkStatus === 'afk') return 'afk'
  const a = String(app || '')
  const t = String(title || '')
  if (CODING_APPS.test(a)) return 'coding'
  if (DOCS_APPS.test(a)) return 'docs'
  if (VIDEO_ENTERTAINMENT.test(a) || VIDEO_ENTERTAINMENT.test(t)) {
    return LEARNING_KEYWORDS.test(t) ? 'study_video' : 'entertainment'
  }
  if (BROWSER_APPS.test(a)) {
    if (LEARNING_KEYWORDS.test(t)) return 'research'
    return 'browser'
  }
  return 'other'
}

export async function fetchBuckets(awBaseUrl = DEFAULT_AW_URL, { fetchFn = fetch } = {}) {
  const base = String(awBaseUrl || DEFAULT_AW_URL).replace(/\/+$/, '')
  const res = await fetchFn(`${base}/api/0/buckets/`)
  if (!res.ok) throw new Error(`AW buckets HTTP ${res.status}`)
  return res.json()
}

export async function fetchRecentEvents(awBaseUrl, bucketId, { lookbackSec = DEFAULT_LOOKBACK_SEC, now = Date.now(), fetchFn = fetch } = {}) {
  const base = String(awBaseUrl || DEFAULT_AW_URL).replace(/\/+$/, '')
  const endIso = new Date(now).toISOString()
  const startIso = new Date(now - lookbackSec * 1000).toISOString()
  const qs = `start=${encodeURIComponent(startIso)}&end=${encodeURIComponent(endIso)}&limit=120`
  const res = await fetchFn(`${base}/api/0/buckets/${encodeURIComponent(bucketId)}/events?${qs}`)
  if (!res.ok) throw new Error(`AW events HTTP ${res.status} (${bucketId})`)
  const rows = await res.json()
  return Array.isArray(rows) ? rows : []
}

export function summarizeHeartbeat({ windowEvents = [], afkEvents = [], now = Date.now() } = {}) {
  const latestAfk = [...afkEvents].sort((a, b) => Date.parse(b.timestamp || 0) - Date.parse(a.timestamp || 0))[0]
  const afkStatus = String(latestAfk?.data?.status || 'not-afk')
  const isAfk = afkStatus === 'afk'

  const sortedWin = [...windowEvents].sort((a, b) => Date.parse(b.timestamp || 0) - Date.parse(a.timestamp || 0))
  const latestWin = sortedWin[0]
  const activeApp = String(latestWin?.data?.app || '')
  const activeTitle = String(latestWin?.data?.title || '').slice(0, 120)

  const byCategorySec = {}
  for (const ev of sortedWin) {
    const dur = Math.max(0, Number(ev?.duration) || 0)
    const cat = classifyPcActivity(ev?.data?.app, ev?.data?.title, 'not-afk')
    byCategorySec[cat] = (byCategorySec[cat] || 0) + dur
  }

  let focusMinutes = 0
  let entertainmentMinutes = 0
  for (const [cat, sec] of Object.entries(byCategorySec)) {
    const mins = Math.round((sec / 60) * 10) / 10
    if (cat === 'coding' || cat === 'docs' || cat === 'research' || cat === 'study_video') focusMinutes += mins
    else if (cat === 'entertainment') entertainmentMinutes += mins
  }

  return {
    type: 'pc-activity',
    at: now,
    isAfk,
    afkStatus,
    activeApp,
    activeTitle,
    category: classifyPcActivity(activeApp, activeTitle, afkStatus),
    focusMinutes: Math.round(focusMinutes * 10) / 10,
    entertainmentMinutes: Math.round(entertainmentMinutes * 10) / 10,
    switches: sortedWin.length,
  }
}

export async function collectAndPush({
  awUrl = DEFAULT_AW_URL,
  companionUrl = DEFAULT_COMPANION_URL,
  outFile = '',
  lookbackSec = DEFAULT_LOOKBACK_SEC,
  now = Date.now(),
  fetchFn = fetch,
} = {}) {
  const buckets = await fetchBuckets(awUrl, { fetchFn })
  const bucketIds = Object.keys(buckets || {})
  const winBucket = bucketIds.find((id) => id.startsWith('aw-watcher-window_')) || ''
  const afkBucket = bucketIds.find((id) => id.startsWith('aw-watcher-afk_')) || ''

  const [windowEvents, afkEvents] = await Promise.all([
    winBucket ? fetchRecentEvents(awUrl, winBucket, { lookbackSec, now, fetchFn }) : Promise.resolve([]),
    afkBucket ? fetchRecentEvents(awUrl, afkBucket, { lookbackSec, now, fetchFn }) : Promise.resolve([]),
  ])

  const payload = summarizeHeartbeat({ windowEvents, afkEvents, now })

  if (outFile) {
    writeFileSync(outFile, JSON.stringify(payload, null, 2) + '\n', 'utf8')
  }

  let pushed = false
  if (companionUrl) {
    const res = await fetchFn(companionUrl, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    })
    if (!res.ok) throw new Error(`Companion push HTTP ${res.status}`)
    pushed = true
  }

  return { ok: true, payload, pushed, buckets: { winBucket, afkBucket } }
}

function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i += 1) {
    const k = argv[i]
    if (k === '--aw' && argv[i + 1]) out.awUrl = argv[++i]
    else if (k === '--target' && argv[i + 1]) out.companionUrl = argv[++i]
    else if (k === '--out' && argv[i + 1]) out.outFile = argv[++i]
    else if (k === '--window-sec' && argv[i + 1]) out.lookbackSec = Number(argv[++i]) || DEFAULT_LOOKBACK_SEC
    else if (k === '--interval-sec' && argv[i + 1]) out.intervalSec = Number(argv[++i]) || 0
  }
  return out
}

const isMain = process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))
if (isMain) {
  const args = parseArgs(process.argv.slice(2))
  const runOnce = async () => {
    try {
      const r = await collectAndPush(args)
      console.log(JSON.stringify(r))
    } catch (err) {
      console.error('[pc-companion-sync]', err?.message || String(err))
      process.exitCode = 1
    }
  }
  await runOnce()
  if (args.intervalSec > 0) {
    setInterval(runOnce, Math.max(10, args.intervalSec) * 1000)
  }
}
