// 大肥鱼 · Gemini 桥：OAuth 2.1（单用户）。
//
// Gemini 的自定义应用（MCP connector）走标准流程：
//   1. 访问 /mcp 拿到 401 + WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource"
//   2. 读受保护资源元数据（RFC 9728）→ 授权服务器元数据（RFC 8414）
//   3. 动态客户端注册（RFC 7591，POST /register）——Gemini 一次注册 6 个回调地址
//      （oauth-redirect / -sandbox / -test .googleusercontent.com × /r/ 和 /a/），少收一个整批被拒，所以全收。
//   4. 浏览器打开 /authorize（PKCE S256）→ 他输一次配对口令 → 回调带 code
//   5. POST /token 换令牌；refresh_token 轮换。
//
// 这里只有一个用户（他自己），所以「同意页」就是一个口令框。口令输错 5 次，锁 15 分钟。

import { createHash } from 'node:crypto'
import { ACCESS_TTL_MS, CODE_TTL_MS, REFRESH_TTL_MS, safeEqual, sha256, token } from './store.mjs'

export const SCOPE = 'dafeiyu'
/** Google 的回调域名：生产 / 沙盒 / 测试，路径 /r/… 或 /a/…（第三方实测，见 README）。 */
export const GOOGLE_REDIRECT = /^https:\/\/oauth-redirect(?:-sandbox|-test)?\.googleusercontent\.com\/(?:r|a)\/[^\s#]*$/
const LOCAL_REDIRECT = /^http:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?\/[^\s#]*$/
export const MAX_REDIRECTS = 10
export const LOCKOUT_FAILS = 5
export const LOCKOUT_MS = 15 * 60_000

export function redirectAllowed(uri, { allowLocal = false, extra = [] } = {}) {
  const u = String(uri || '')
  if (GOOGLE_REDIRECT.test(u)) return true
  if (allowLocal && LOCAL_REDIRECT.test(u)) return true
  return extra.some((p) => (p instanceof RegExp ? p.test(u) : u === p))
}

export function protectedResourceMetadata(base) {
  return {
    resource: base + '/mcp',
    authorization_servers: [base],
    scopes_supported: [SCOPE],
    bearer_methods_supported: ['header'],
    resource_name: '大肥鱼',
  }
}

export function authServerMetadata(base) {
  return {
    issuer: base,
    authorization_endpoint: base + '/authorize',
    token_endpoint: base + '/token',
    registration_endpoint: base + '/register',
    response_types_supported: ['code'],
    grant_types_supported: ['authorization_code', 'refresh_token'],
    code_challenge_methods_supported: ['S256'],
    token_endpoint_auth_methods_supported: ['none', 'client_secret_post', 'client_secret_basic'],
    scopes_supported: [SCOPE],
    service_documentation: base + '/',
  }
}

const err = (status, error, description) => ({ status, body: { error, error_description: description } })

/** RFC 7591 动态注册。回调地址必须全部合法；数量上限 10（Gemini 要 6 个）。 */
export function register(store, body, opts = {}) {
  const st = store.state
  const uris = Array.isArray(body?.redirect_uris) ? body.redirect_uris.map(String) : []
  if (!uris.length) return err(400, 'invalid_redirect_uri', 'redirect_uris is required')
  if (uris.length > MAX_REDIRECTS) return err(400, 'invalid_redirect_uri', 'too many redirect_uris (max ' + MAX_REDIRECTS + ')')
  const bad = uris.filter((u) => !redirectAllowed(u, opts))
  if (bad.length) return err(400, 'invalid_redirect_uri', 'redirect_uri not allowed: ' + bad[0])
  const method = ['client_secret_post', 'client_secret_basic'].includes(body?.token_endpoint_auth_method) ? body.token_endpoint_auth_method : 'none'
  const clientId = 'dfy-' + token(12)
  const secret = method === 'none' ? '' : token(24)
  const now = store.now()
  st.clients[clientId] = {
    at: now,
    name: String(body?.client_name || 'Gemini').slice(0, 80),
    redirect_uris: uris,
    method,
    secretHash: secret ? sha256(secret) : '',
  }
  store.save()
  return {
    status: 201,
    body: {
      client_id: clientId,
      ...(secret ? { client_secret: secret, client_secret_expires_at: 0 } : {}),
      client_id_issued_at: Math.floor(now / 1000),
      client_name: st.clients[clientId].name,
      redirect_uris: uris,
      grant_types: ['authorization_code', 'refresh_token'],
      response_types: ['code'],
      token_endpoint_auth_method: method,
      scope: SCOPE,
    },
  }
}

/** /authorize 的参数检查（GET 显示页面、POST 提交口令都先过这一步）。 */
export function checkAuthorize(store, q) {
  const c = store.state.clients[String(q.client_id || '')]
  if (!c) return { ok: false, show: true, error: '不认识这个客户端（client_id）。在 Gemini 里删掉这个自定义应用重新加一次。' }
  const redirect = String(q.redirect_uri || '')
  if (!c.redirect_uris.includes(redirect)) return { ok: false, show: true, error: '回调地址和注册时的不一致。' }
  if (String(q.response_type || '') !== 'code') return { ok: false, redirect, error: 'unsupported_response_type' }
  if (!q.code_challenge || String(q.code_challenge_method || '') !== 'S256') return { ok: false, redirect, error: 'invalid_request', description: 'PKCE S256 required' }
  return { ok: true, client: c, redirect }
}

/** 口令对了就发授权码。返回 {redirectTo} 或 {error, locked}。 */
export function approve(store, q, passphrase) {
  const st = store.state
  const now = store.now()
  if (st.failures.n >= LOCKOUT_FAILS && now - st.failures.at < LOCKOUT_MS) {
    return { error: '口令错太多次了，' + Math.ceil((LOCKOUT_MS - (now - st.failures.at)) / 60_000) + ' 分钟后再试。', locked: true }
  }
  const chk = checkAuthorize(store, q)
  if (!chk.ok) return { error: chk.error }
  if (!safeEqual(String(passphrase || '').trim().toLowerCase(), st.secret.passphrase)) {
    st.failures = { at: now, n: (now - st.failures.at < LOCKOUT_MS ? st.failures.n : 0) + 1 }
    store.save()
    return { error: '口令不对。' }
  }
  st.failures = { at: 0, n: 0 }
  const code = token(24)
  st.codes[sha256(code)] = {
    clientId: String(q.client_id),
    redirect: chk.redirect,
    challenge: String(q.code_challenge),
    scope: SCOPE,
    exp: now + CODE_TTL_MS,
  }
  store.save()
  const url = new URL(chk.redirect)
  url.searchParams.set('code', code)
  if (q.state) url.searchParams.set('state', String(q.state))
  return { redirectTo: url.toString() }
}

function clientAuth(store, form, headers) {
  let id = String(form.client_id || '')
  let secret = String(form.client_secret || '')
  const basic = /^Basic\s+(.+)$/i.exec(String(headers?.authorization || ''))
  if (basic) {
    const [u, p] = Buffer.from(basic[1], 'base64').toString('utf8').split(':')
    id = decodeURIComponent(u || '')
    secret = decodeURIComponent(p || '')
  }
  const c = store.state.clients[id]
  if (!c) return { error: err(401, 'invalid_client', 'unknown client') }
  if (c.method !== 'none' && !safeEqual(sha256(secret), c.secretHash)) return { error: err(401, 'invalid_client', 'bad client secret') }
  return { id, client: c }
}

function issue(store, clientId, scope) {
  const st = store.state
  const now = store.now()
  const access = token(32)
  const refresh = token(32)
  st.access[sha256(access)] = { clientId, scope, exp: now + ACCESS_TTL_MS }
  st.refresh[sha256(refresh)] = { clientId, scope, exp: now + REFRESH_TTL_MS }
  store.save()
  return { status: 200, body: { access_token: access, token_type: 'Bearer', expires_in: Math.floor(ACCESS_TTL_MS / 1000), refresh_token: refresh, scope } }
}

const s256 = (verifier) => createHash('sha256').update(String(verifier)).digest('base64url')

/** POST /token：授权码换令牌（校验 PKCE）/ 刷新（轮换 refresh_token）。 */
export function tokenEndpoint(store, form, headers) {
  const st = store.state
  const auth = clientAuth(store, form, headers)
  if (auth.error) return auth.error
  const grant = String(form.grant_type || '')
  if (grant === 'authorization_code') {
    const key = sha256(String(form.code || ''))
    const c = st.codes[key]
    delete st.codes[key]
    if (!c || c.exp < store.now()) {
      store.save()
      return err(400, 'invalid_grant', 'code expired or unknown')
    }
    if (c.clientId !== auth.id || (form.redirect_uri && String(form.redirect_uri) !== c.redirect)) return err(400, 'invalid_grant', 'client or redirect mismatch')
    if (!form.code_verifier || s256(form.code_verifier) !== c.challenge) return err(400, 'invalid_grant', 'PKCE verification failed')
    return issue(store, auth.id, c.scope)
  }
  if (grant === 'refresh_token') {
    const key = sha256(String(form.refresh_token || ''))
    const r = st.refresh[key]
    if (!r || r.exp < store.now() || r.clientId !== auth.id) return err(400, 'invalid_grant', 'refresh token expired or unknown')
    delete st.refresh[key]
    return issue(store, auth.id, r.scope)
  }
  return err(400, 'unsupported_grant_type', 'grant_type must be authorization_code or refresh_token')
}

/** /mcp 的 Bearer 校验。 */
export function checkBearer(store, headers) {
  const m = /^Bearer\s+(.+)$/i.exec(String(headers?.authorization || ''))
  if (!m) return false
  const a = store.state.access[sha256(m[1].trim())]
  return Boolean(a && a.exp > store.now())
}

/** 撤销全部 Gemini 授权（命令行 --revoke）。 */
export function revokeAll(store) {
  const st = store.state
  st.access = {}
  st.refresh = {}
  st.codes = {}
  st.clients = {}
  store.save()
}

export function authorizePage({ q, error = '', clientName = 'Gemini' }) {
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
  const hidden = ['client_id', 'redirect_uri', 'response_type', 'code_challenge', 'code_challenge_method', 'state', 'scope', 'resource']
    .map((k) => (q[k] ? '<input type="hidden" name="' + k + '" value="' + esc(q[k]) + '">' : '')).join('')
  return '<!doctype html><html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>大肥鱼 · 授权 Gemini</title><style>body{font-family:system-ui,sans-serif;max-width:420px;margin:48px auto;padding:0 16px;line-height:1.6;color:#1f2933;background:#f7f8fa}' +
    'h1{font-size:20px}.card{background:#fff;border-radius:14px;padding:20px;box-shadow:0 1px 3px rgba(0,0,0,.08)}input[type=text]{width:100%;box-sizing:border-box;font-size:18px;padding:10px;border:1px solid #cbd2d9;border-radius:8px;letter-spacing:1px}' +
    'button{margin-top:14px;width:100%;font-size:16px;padding:11px;border:0;border-radius:8px;background:#2563eb;color:#fff}.err{color:#b42318}.muted{color:#616e7c;font-size:14px}</style></head><body><div class="card">' +
    '<h1>让 ' + esc(clientName) + ' 连上大肥鱼</h1>' +
    '<p class="muted">它能看你今天的验收点和公约，能交回执、加验收点、提议规矩；<b>不能解锁、不能加额度、不能改小任何东西</b>。</p>' +
    (error ? '<p class="err">' + esc(error) + '</p>' : '') +
    '<form method="post" action="/authorize">' + hidden +
    '<label>配对口令（启动桥的时候屏幕上打出来的那一串）<br><input type="text" name="passphrase" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="xxxx-xxxx-xxxx-xxxx" autofocus></label>' +
    '<button type="submit">授权</button></form></div></body></html>'
}
