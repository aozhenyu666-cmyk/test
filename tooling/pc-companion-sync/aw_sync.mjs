#!/usr/bin/env node
/**
 * DSH Go 大肥鱼 · PC 端 ActivityWatch 安全防误判同步入口 (Phase 3)
 *
 * 零第三方依赖：复用 sync.mjs 的桶抓取与心跳分类逻辑，叠加超时保护与优雅降级：
 * 当 PC 未开机、ActivityWatch 未启动、超时或局域网断连时，标记为 `pc_unknown`
 * 并明确 `slacking: false`，绝不将 PC 离线误判为摸鱼或触发惩罚。
 */

import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { collectAndPush } from './sync.mjs'

export const DEFAULT_AW_TIMEOUT_MS = 2000

/** 构造离线/超时时的安全兜底心跳，明确标识 pc_unknown 且绝不计为摸鱼。 */
export function buildPcUnknownHeartbeat({ now = Date.now(), reason = 'aw-offline', error = '' } = {}) {
  return {
    type: 'pc-activity',
    at: now,
    status: 'pc_unknown',
    category: 'pc_unknown',
    isAfk: false,
    slacking: false,
    afkStatus: 'unknown',
    activeApp: '',
    activeTitle: '',
    focusMinutes: 0,
    entertainmentMinutes: 0,
    switches: 0,
    reason,
    ...(error ? { detail: String(error).slice(0, 120) } : {}),
  }
}

function timedFetch(fetchFn, timeoutMs) {
  return async (url, init = {}) => {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(new Error(`AW timeout after ${timeoutMs}ms`)), timeoutMs)
    try {
      return await fetchFn(url, { ...init, signal: init.signal || controller.signal })
    } finally {
      clearTimeout(timer)
    }
  }
}

/**
 * 轮询本地 ActivityWatch 桶并在异常/超时时优雅降级为 pc_unknown。
 * @returns {Promise<{ok:true, status:'online'|'pc_unknown', payload:object, pushed:boolean, reason?:string}>}
 */
export async function syncPcActivitySafe({
  awUrl = process.env.AW_BASE_URL || 'http://127.0.0.1:5600',
  companionUrl = process.env.COMPANION_EVENT_URL || '',
  outFile = '',
  lookbackSec = 900,
  timeoutMs = DEFAULT_AW_TIMEOUT_MS,
  now = Date.now(),
  fetchFn = fetch,
} = {}) {
  const safeFetch = timedFetch(fetchFn, Math.max(100, Number(timeoutMs) || DEFAULT_AW_TIMEOUT_MS))
  try {
    const res = await collectAndPush({ awUrl, companionUrl, outFile, lookbackSec, now, fetchFn: safeFetch })
    const payload = {
      ...res.payload,
      status: 'online',
      slacking: res.payload.category === 'entertainment',
    }
    if (outFile) writeFileSync(outFile, JSON.stringify(payload, null, 2) + '\n', 'utf8')
    return { ok: true, status: 'online', payload, pushed: res.pushed, buckets: res.buckets }
  } catch (err) {
    const msg = err?.message || String(err)
    const isTimeout = err?.name === 'AbortError' || /timeout/i.test(msg)
    const reason = isTimeout ? 'aw-timeout' : 'aw-offline'
    const payload = buildPcUnknownHeartbeat({ now, reason, error: msg })
    if (outFile) writeFileSync(outFile, JSON.stringify(payload, null, 2) + '\n', 'utf8')
    let pushed = false
    if (companionUrl) {
      try {
        const pushRes = await safeFetch(companionUrl, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(payload),
        })
        pushed = Boolean(pushRes?.ok)
      } catch {
        pushed = false
      }
    }
    return { ok: true, status: 'pc_unknown', reason, payload, pushed }
  }
}

const isMain = process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))
if (isMain) {
  const args = {}
  const argv = process.argv.slice(2)
  for (let i = 0; i < argv.length; i += 1) {
    const k = argv[i]
    if (k === '--aw' && argv[i + 1]) args.awUrl = argv[++i]
    else if (k === '--target' && argv[i + 1]) args.companionUrl = argv[++i]
    else if (k === '--out' && argv[i + 1]) args.outFile = argv[++i]
    else if (k === '--timeout-ms' && argv[i + 1]) args.timeoutMs = Number(argv[++i]) || DEFAULT_AW_TIMEOUT_MS
  }
  const out = await syncPcActivitySafe(args)
  console.log(JSON.stringify(out))
}
