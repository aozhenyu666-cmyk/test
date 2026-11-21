#!/usr/bin/env node
// 大肥鱼 · Gemini 桥（PC 端）。零第三方依赖，Node >= 20。
//
//   Gemini（自定义应用 / MCP）──HTTPS──► Tailscale Funnel ──► 本机 127.0.0.1:8787（这个进程）
//   手机 DSH Go 引擎 ──HTTPS（每 ~20 秒）──► 同一个 Funnel 地址的 /bridge/sync
//
// 手机引擎只听 127.0.0.1、手机上 NekoBox 和 Tailscale 不能同时开（安卓只能有一个 VPN），
// 所以不是电脑去连手机，而是手机主动来同步：上行「今天」的快照，下行 Gemini 写的命令。
//
// 用法见 README.md。常用：
//   node server.mjs --public-url https://你的电脑名.你的tailnet.ts.net --vault "D:/Obsidian/我的库"
//   node server.mjs --show-secrets      再看一遍配对口令和手机密钥
//   node server.mjs --revoke            撤销所有 Gemini 授权（之后要在 Gemini 里重新连）
//   node server.mjs --local-port 0      不开本机互通接口（默认 127.0.0.1:8788，给 BrainOS 这类本机程序用，绝不要 funnel 它）

import { createServer as createHttpServer } from 'node:http'
import { homedir } from 'node:os'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { createStore, safeEqual } from './store.mjs'
import {
  approve, authorizePage, authServerMetadata, checkAuthorize, checkBearer, protectedResourceMetadata, register, revokeAll, tokenEndpoint,
} from './oauth.mjs'
import { createEvents, handleRpc, phoneSync, todayText } from './mcp.mjs'
import { createLocalApi, LOCAL_PORT } from './local.mjs'

const MAX_BODY = 1_000_000

function readBody(req) {
  return new Promise((resolveBody, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (c) => {
      size += c.length
      if (size > MAX_BODY) {
        reject(Object.assign(new Error('body too large'), { status: 413 }))
        req.destroy()
        return
      }
      chunks.push(c)
    })
    req.on('end', () => resolveBody(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function parseForm(raw, type) {
  if (/json/i.test(type || '')) {
    try {
      return JSON.parse(raw || '{}')
    } catch {
      return {}
    }
  }
  return Object.fromEntries(new URLSearchParams(raw || ''))
}

const send = (res, status, body, headers = {}) => {
  const isStr = typeof body === 'string'
  res.writeHead(status, {
    'content-type': isStr ? 'text/html; charset=utf-8' : 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    ...headers,
  })
  res.end(body === undefined ? '' : isStr ? body : JSON.stringify(body))
}

export function baseUrlOf(req, publicUrl) {
  if (publicUrl) return String(publicUrl).replace(/\/+$/, '')
  const host = String(req.headers['x-forwarded-host'] || req.headers.host || '127.0.0.1')
  const proto = String(req.headers['x-forwarded-proto'] || (/^(127\.|localhost)/.test(host) ? 'http' : 'https'))
  return proto + '://' + host
}

/**
 * @param {{store, events?, publicUrl?, vaultDir?, allowLocalRedirect?, log?, resultWaitMs?}} opts
 */
export function createBridge(opts) {
  const store = opts.store
  const events = opts.events || createEvents()
  const log = opts.log || (() => {})
  const ctx = { store, events, vaultDir: opts.vaultDir || '', resultWaitMs: opts.resultWaitMs }
  const regOpts = { allowLocal: Boolean(opts.allowLocalRedirect) }

  async function handler(req, res) {
    const url = new URL(req.url || '/', 'http://local')
    const path = url.pathname.replace(/\/+$/, '') || '/'
    const base = baseUrlOf(req, opts.publicUrl)
    try {
      // ---- 元数据（Gemini 用第一个有应答的地址，所以几种常见写法都答对）
      if (req.method === 'GET' && (path === '/.well-known/oauth-protected-resource' || path === '/.well-known/oauth-protected-resource/mcp')) {
        return send(res, 200, protectedResourceMetadata(base))
      }
      if (req.method === 'GET' && (path === '/.well-known/oauth-authorization-server' || path === '/.well-known/oauth-authorization-server/mcp' ||
          path === '/.well-known/openid-configuration' || path === '/.well-known/openid-configuration/mcp' || path === '/mcp/.well-known/openid-configuration')) {
        return send(res, 200, authServerMetadata(base))
      }
      if (req.method === 'POST' && path === '/register') {
        const body = parseForm(await readBody(req), req.headers['content-type'])
        const r = register(store, body, regOpts)
        log('register', r.status, r.body?.client_name || r.body?.error)
        return send(res, r.status, r.body)
      }
      if (path === '/authorize') {
        const q = req.method === 'POST'
          ? parseForm(await readBody(req), req.headers['content-type'])
          : Object.fromEntries(url.searchParams)
        const chk = checkAuthorize(store, q)
        if (!chk.ok && !chk.show && chk.redirect) {
          const u = new URL(chk.redirect)
          u.searchParams.set('error', chk.error)
          if (chk.description) u.searchParams.set('error_description', chk.description)
          if (q.state) u.searchParams.set('state', String(q.state))
          return send(res, 302, '', { location: u.toString() })
        }
        if (!chk.ok) return send(res, 400, authorizePage({ q, error: chk.error }))
        if (req.method === 'GET') return send(res, 200, authorizePage({ q, clientName: chk.client.name }))
        const r = approve(store, q, q.passphrase)
        log('authorize', r.redirectTo ? 'ok' : r.error)
        if (r.redirectTo) return send(res, 302, '', { location: r.redirectTo })
        return send(res, r.locked ? 429 : 401, authorizePage({ q, error: r.error, clientName: chk.client.name }))
      }
      if (req.method === 'POST' && path === '/token') {
        const form = parseForm(await readBody(req), req.headers['content-type'])
        const r = tokenEndpoint(store, form, req.headers)
        log('token', form.grant_type, r.status)
        return send(res, r.status, r.body)
      }
      // ---- MCP
      if (path === '/mcp') {
        if (req.method !== 'POST') return send(res, 405, { error: 'method_not_allowed' }, { allow: 'POST' })
        if (!checkBearer(store, req.headers)) {
          return send(res, 401, { error: 'invalid_token' }, {
            'www-authenticate': 'Bearer resource_metadata="' + base + '/.well-known/oauth-protected-resource", scope="dafeiyu"',
          })
        }
        let msg
        try {
          msg = JSON.parse(await readBody(req))
        } catch {
          return send(res, 400, { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } })
        }
        const list = Array.isArray(msg) ? msg : [msg]
        const out = (await Promise.all(list.map((m) => handleRpc(ctx, m)))).filter(Boolean)
        for (const m of list) if (m?.method === 'tools/call') log('tool', m.params?.name)
        if (!out.length) return send(res, 202, undefined, { 'content-type': 'application/json' })
        return send(res, 200, Array.isArray(msg) ? out : out[0])
      }
      // ---- 手机同步
      if (req.method === 'POST' && path === '/bridge/sync') {
        const m = /^Bearer\s+(.+)$/i.exec(String(req.headers.authorization || ''))
        if (!m || !safeEqual(m[1].trim(), store.state.secret.phoneKey)) return send(res, 401, { ok: false, error: 'bad-phone-key' })
        let body = {}
        try {
          body = JSON.parse((await readBody(req)) || '{}')
        } catch {
          return send(res, 400, { ok: false, error: 'bad-json' })
        }
        const r = phoneSync(store, body, events)
        return send(res, 200, r)
      }
      if (req.method === 'GET' && path === '/') {
        const snap = store.state.snapshot
        const age = snap ? Math.round((store.now() - store.state.snapshotAt) / 1000) : null
        return send(res, 200, '<!doctype html><meta charset="utf-8"><title>大肥鱼 · Gemini 桥</title><body style="font-family:system-ui;max-width:640px;margin:40px auto;padding:0 16px">' +
          '<h1>大肥鱼 · Gemini 桥</h1><p>运行中。MCP 地址：<code>' + base + '/mcp</code></p><p>手机' + (age === null ? '还没同步过' : '最后同步：' + age + ' 秒前') +
          '；待手机取走的命令 ' + (store.state.commands || []).length + ' 条；Gemini 授权 ' + Object.keys(store.state.refresh || {}).length + ' 个。</p></body>')
      }
      return send(res, 404, { error: 'not_found' })
    } catch (e) {
      log('error', String(e?.message || e))
      return send(res, Number(e?.status) || 500, { error: 'server_error' })
    }
  }
  // 本机互通接口（127.0.0.1:8788）：和 Gemini 用同一个命令队列、同一个事件，手机取走命令时两边都能拿到结果。
  const localHandler = createLocalApi({ store, events, vaultDir: opts.vaultDir, resultWaitMs: opts.resultWaitMs })
  return { handler, localHandler, ctx, events, todayText: () => todayText(store.state.snapshot, store.now()) }
}

// ---------------------------------------------------------------- 命令行

function argv(name, def = '') {
  const i = process.argv.indexOf('--' + name)
  if (i < 0) return def
  const v = process.argv[i + 1]
  return v && !v.startsWith('--') ? v : 'true'
}

async function main() {
  const dataDir = resolve(argv('data', process.env.DAFEIYU_BRIDGE_DATA || join(homedir(), '.dafeiyu-bridge')))
  const store = createStore(join(dataDir, 'bridge-state.json'))
  if (argv('revoke')) {
    revokeAll(store)
    console.log('已撤销全部 Gemini 授权。在 Gemini 里重新连一次「大肥鱼」就行。')
    return
  }
  const port = Number(argv('port', process.env.PORT || '8787'))
  const host = argv('host', '127.0.0.1')
  const publicUrl = argv('public-url', process.env.DAFEIYU_BRIDGE_URL || '')
  const vaultDir = argv('vault', process.env.DAFEIYU_VAULT || '')
  const s = store.state.secret
  if (argv('show-secrets')) {
    console.log('配对口令（Gemini 授权页输入）：' + s.passphrase)
    console.log('手机密钥（DSH Go →「Gemini 桥」页填）：' + s.phoneKey)
    console.log('本机密钥（电脑上 BrainOS 等程序调 127.0.0.1:' + LOCAL_PORT + ' 用）：' + s.localKey)
    return
  }
  const localPort = Number(argv('local-port', process.env.DAFEIYU_LOCAL_PORT || String(LOCAL_PORT)))
  const bridge = createBridge({
    store, publicUrl, vaultDir: vaultDir ? resolve(vaultDir) : '',
    allowLocalRedirect: Boolean(argv('allow-local-redirect')),
    log: (...a) => console.log(new Date().toISOString().slice(11, 19), ...a),
  })
  createHttpServer(bridge.handler).listen(port, host, () => {
    const base = publicUrl || '(没给 --public-url：按请求的 Host 推断)'
    console.log('大肥鱼 · Gemini 桥 已启动：http://' + host + ':' + port)
    console.log('公网地址：' + base + '   MCP：' + (publicUrl ? publicUrl.replace(/\/+$/, '') + '/mcp' : '<公网地址>/mcp'))
    console.log('笔记库：' + (vaultDir || '（没配，Gemini 不能读笔记；加 --vault 路径）'))
    console.log('—— 配对口令（Gemini 授权页输入）：' + s.passphrase)
    console.log('—— 手机密钥（DSH Go →「Gemini 桥」页填）：' + s.phoneKey)
    console.log('另开一个终端：tailscale funnel ' + port + '   （把本机端口开到公网 https）')
  })
  if (localPort > 0 && localPort !== port) {
    // 只听 127.0.0.1，不管 --host 给的是什么：这个口子永远不出本机。
    createHttpServer(bridge.localHandler).listen(localPort, '127.0.0.1', () => {
      console.log('本机互通接口：http://127.0.0.1:' + localPort + '/bridge/local/{today,ingest,import-questions}（不要 funnel 这个端口）')
      console.log('—— 本机密钥（Authorization: Bearer …）：' + s.localKey)
    }).on('error', (e) => console.log('本机互通接口没起来（' + String(e?.code || e?.message || e) + '），其余照常：', localPort))
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  main().catch((e) => {
    console.error(e)
    process.exit(1)
  })
}
