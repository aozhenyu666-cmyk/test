// 和「思维驾驶员」APP 配对。
//
// 插件自己注册的路由，DSH 引擎不会替它鉴权（dsh-go-proactive 在真实引擎上核实过），
// 而 APP 拿不到引擎的登录 cookie，所以这里自己管一套：
//   1. 配对码：8 位（去掉易混字符），只用一次，10 分钟有效。大肥鱼运行 bin/pair-code.mjs 生成并念给你。
//   2. APP 用配对码换一个长期令牌（32 字节随机），之后每次请求带 Authorization: Bearer <令牌>。
//   3. 只存令牌的 SHA-256，不存原文。连续猜错 5 次锁 1 分钟，防止手机上别的 APP 暴力试码。
//   4. 带 Origin 头的请求一律拒绝（浏览器发的），Host 只认 127.0.0.1 / localhost。
// 存在 $DSH_HOME/storages/dsh-driver-core/pairing.json。

import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { home } from './store.js'

const ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789' // 去掉 I L O 0 1
const CODE_TTL_MS = 10 * 60_000
const MAX_FAILS = 5
const LOCK_MS = 60_000
const MAX_TOKENS = 4

const sha = (s) => createHash('sha256').update(String(s)).digest('hex')

export function newCode(bytes = randomBytes(8)) {
  let s = ''
  for (const b of bytes) s += ALPHABET[b % ALPHABET.length]
  return `${s.slice(0, 4)}-${s.slice(4, 8)}`
}

const normCode = (c) => String(c ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '')

/** 引擎监听的端口：从 dsh 的启动参数里读，读不到就是 DSH Go 本机引擎的默认 3080。 */
export function enginePort(argv = process.argv) {
  const i = argv.indexOf('--port')
  const p = i >= 0 ? Number(argv[i + 1]) : NaN
  return Number.isInteger(p) && p > 0 && p < 65536 ? p : 3080
}

export class Pairing {
  constructor({ file = join(home(), 'storages', 'dsh-driver-core', 'pairing.json'), now = () => Date.now() } = {}) {
    this.file = file
    this.now = now
    this.fails = []
    this.lockedUntil = 0
  }

  #read() {
    try { return JSON.parse(readFileSync(this.file, 'utf8')) } catch { return {} }
  }

  #write(v) {
    mkdirSync(join(this.file, '..'), { recursive: true })
    writeFileSync(this.file + '.tmp', JSON.stringify(v, null, 2), { mode: 0o600 })
    renameSync(this.file + '.tmp', this.file)
  }

  /** 生成一个新配对码（旧的作废）。返回给人看的样子：端口:XXXX-XXXX。 */
  issueCode(port = enginePort()) {
    const code = newCode()
    const d = this.#read()
    this.#write({ ...d, code: { hash: sha(normCode(code)), expiresAt: this.now() + CODE_TTL_MS }, port })
    return `${port}:${code}`
  }

  /** APP 用配对码换令牌。成功返回 {token}；失败返回 {error, status}。 */
  redeem(code, device = '') {
    const now = this.now()
    if (now < this.lockedUntil) return { status: 429, error: 'locked' }
    const d = this.#read()
    const ok = d.code && d.code.expiresAt > now && safeEq(d.code.hash, sha(normCode(code)))
    if (!ok) {
      this.fails = [...this.fails.filter((t) => now - t < LOCK_MS), now]
      if (this.fails.length >= MAX_FAILS) { this.lockedUntil = now + LOCK_MS; this.fails = [] }
      return { status: 403, error: d.code && d.code.expiresAt <= now ? 'code-expired' : 'bad-code' }
    }
    const token = randomBytes(32).toString('hex')
    const tokens = [...(d.tokens ?? []), { hash: sha(token), device: String(device).slice(0, 60), createdAt: now }].slice(-MAX_TOKENS)
    this.#write({ ...d, code: null, tokens })
    this.fails = []
    return { token }
  }

  /** 令牌对不对。 */
  check(token) {
    if (typeof token !== 'string' || token.length < 32) return false
    const h = sha(token)
    return (this.#read().tokens ?? []).some((t) => safeEq(t.hash, h))
  }

  /** 解除所有配对。 */
  revokeAll() {
    const d = this.#read()
    this.#write({ ...d, code: null, tokens: [] })
  }
}

function safeEq(a, b) {
  const A = Buffer.from(String(a))
  const B = Buffer.from(String(b))
  return A.length === B.length && timingSafeEqual(A, B)
}

/** APP 路由的请求门：拒绝浏览器（有 Origin）、拒绝非本机 Host、要求令牌。返回 HTTP 状态码或 undefined（放行）。 */
export function appRejection(req, pairing, { needToken = true } = {}) {
  if (req.headers.origin !== undefined) return 403
  const host = String(req.headers.host ?? '').replace(/:\d+$/, '').toLowerCase()
  if (host && host !== '127.0.0.1' && host !== 'localhost' && host !== '[::1]') return 403
  if (!needToken) return undefined
  const auth = String(req.headers.authorization ?? '')
  const m = /^Bearer\s+(\S+)$/i.exec(auth)
  return m && pairing.check(m[1]) ? undefined : 401
}
