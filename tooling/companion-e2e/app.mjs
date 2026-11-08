// 模拟 App：Dart 的 RPC 信封（/api/<method>）+ Kotlin 打插件路由（/dsh-go-companion/v1/...）。
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
const W = process.env.E2E_WORK
const BASE = process.env.E2E_BASE || 'http://127.0.0.1:18080'
function cookie() {
  return readFileSync(W + '/c.txt', 'utf8').split('\n').filter((l) => l.includes('dsh-auth'))
    .map((l) => { const p = l.split('\t'); return p[5] + '=' + p[6] })[0]
}
const H = () => ({ 'content-type': 'application/json', cookie: cookie(), origin: BASE })
async function json(r) { const t = await r.text(); try { return JSON.parse(t) } catch { return t } }
export async function rpc(method, args) {
  const r = await fetch(BASE + '/api/' + method, { method: 'POST', headers: H(), body: JSON.stringify({ type: 'client-request', rpcId: randomUUID(), method, payload: { args } }) })
  return { status: r.status, body: await json(r) }
}
export async function route(method, path, body) {
  const r = await fetch(BASE + '/dsh-go-companion/v1' + path, { method, headers: H(), body: body ? JSON.stringify(body) : undefined })
  return { status: r.status, body: await json(r) }
}
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
export function ledger() {
  try { return readFileSync(W + '/home/companion/ledger.jsonl', 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)) } catch { return [] }
}
/** Kotlin 的做法：POST /request 之后长轮询 /result。 */
export async function waitResult(id, tries = 8) {
  let r
  for (let i = 0; i < tries; i++) {
    r = await route('GET', '/result?requestId=' + id + '&waitMs=8000')
    if (['generated', 'failed', 'cancelled'].includes(r.body?.state)) break
  }
  return r?.body
}
export async function prompt(sid, text) {
  return rpc('session/prompt', { request: { requestId: randomUUID(), sessionId: sid, mode: 'queue', content: [{ type: 'text', text }], clientTimeZone: 'Asia/Shanghai' } })
}
