// 大肥鱼 · Gemini 桥：状态存储（一个 JSON 文件，原子写）。
//
// 存什么：
//   secret    配对口令（Gemini 授权时输一次）、手机密钥（手机同步用）
//   clients   Gemini 动态注册来的 OAuth 客户端
//   codes     授权码（5 分钟、一次性）
//   access / refresh  令牌——只存哈希，不存原文
//   snapshot  手机最近一次同步上来的「今天」
//   commands  Gemini 写的、等手机取走的命令（回执 / 加验收点 / 提议规矩）
//   results   手机处理完的结果（工具调用可以等一会儿拿结果）
//
// 纯 Node 内置模块，没有第三方依赖。

import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

export const CODE_TTL_MS = 5 * 60_000
export const ACCESS_TTL_MS = 60 * 60_000
export const REFRESH_TTL_MS = 60 * 86_400_000
export const MAX_CLIENTS = 40
export const MAX_COMMANDS = 200

export const sha256 = (s) => createHash('sha256').update(String(s)).digest('hex')
export const token = (bytes = 32) => randomBytes(bytes).toString('base64url')

/** 口令：6 组 4 位的小写字母数字，好念、好抄。 */
export function newPassphrase() {
  const alphabet = 'abcdefghjkmnpqrstuvwxyz23456789'
  const raw = randomBytes(24)
  const groups = []
  for (let g = 0; g < 4; g++) {
    let s = ''
    for (let i = 0; i < 4; i++) s += alphabet[raw[g * 4 + i] % alphabet.length]
    groups.push(s)
  }
  return groups.join('-')
}

export function safeEqual(a, b) {
  const x = Buffer.from(String(a || ''))
  const y = Buffer.from(String(b || ''))
  return x.length === y.length && x.length > 0 && timingSafeEqual(x, y)
}

export function emptyState() {
  return {
    v: 1,
    secret: { passphrase: newPassphrase(), phoneKey: 'dfy_' + token(24) },
    clients: {},
    codes: {},
    access: {},
    refresh: {},
    snapshot: null,
    snapshotAt: 0,
    commands: [],
    results: {},
    failures: { at: 0, n: 0 },
  }
}

export function createStore(file, { now = () => Date.now() } = {}) {
  let state
  try {
    state = existsSync(file) ? { ...emptyState(), ...JSON.parse(readFileSync(file, 'utf8')) } : emptyState()
  } catch {
    state = emptyState()
  }
  const save = () => {
    mkdirSync(dirname(file), { recursive: true })
    const tmp = join(dirname(file), '.' + Date.now() + '.tmp')
    writeFileSync(tmp, JSON.stringify(state, null, 2), { mode: 0o600 })
    renameSync(tmp, file)
  }
  const prune = () => {
    const t = now()
    for (const [k, v] of Object.entries(state.codes)) if (v.exp < t) delete state.codes[k]
    for (const [k, v] of Object.entries(state.access)) if (v.exp < t) delete state.access[k]
    for (const [k, v] of Object.entries(state.refresh)) if (v.exp < t) delete state.refresh[k]
    for (const [k, v] of Object.entries(state.results)) if (t - v.at > 86_400_000) delete state.results[k]
    const ids = Object.keys(state.clients)
    if (ids.length > MAX_CLIENTS) {
      ids.sort((a, b) => state.clients[a].at - state.clients[b].at).slice(0, ids.length - MAX_CLIENTS).forEach((id) => delete state.clients[id])
    }
  }
  if (!existsSync(file)) save()
  return {
    get state() {
      return state
    },
    save() {
      prune()
      save()
    },
    now,
  }
}
