/**
 * dsh-go-bili-peek 的纯逻辑层：输入解析 + 公开信息查询 + 错误码。
 *
 * 三条硬约束（都是为了「可测试、不静默」）：
 *   1. **网络走可注入的 fetch 端口** —— 测试用假 fetch 打桩，不需要联网；
 *      宿主没有 fetch 时报 E_NO_FETCH，而不是抛个 TypeError 就完事。
 *   2. **每个失败都有错误码**（见 CODES）—— 网络失败 / 超时 / 非 JSON /
 *      HTTP 错 / B站业务码错 / 输入不合法，一个都不许静默变成空结果。
 *   3. **零第三方依赖、不读本地账号状态** —— 只读公开的
 *      x/web-interface/view 接口，不登录、不碰 cookies、不做任何写操作。
 *
 * 本文件不 import 任何东西，纯 JS，可以在手机上直接跑。
 */

/** 错误码 → 人话。渲染卡片、给模型的文字、状态条都用这一份。 */
export const CODES = {
  OK: '查到了',
  E_INPUT: '输入的 BV 号 / 链接认不出来',
  E_NO_FETCH: '这个宿主没有 fetch（缺依赖）',
  E_TIMEOUT: '请求超时',
  E_ABORTED: '调用被取消',
  E_NETWORK: '网络不通',
  E_HTTP: 'B站返回了非 2xx',
  E_NOT_JSON: 'B站返回的不是 JSON（可能被网络中间层拦了）',
  E_BIZ: 'B站接口报了业务错误（视频不存在等）',
  E_NO_DATA: 'B站返回里没有视频数据',
  E_INTERNAL: '插件内部错误',
}

/** 错误码 → 下一步提示。**空着就等于让用户卡死，每条都必须有。** */
export const HINTS = {
  E_INPUT: 'BV 号形如 BV1xx411c7mD（BV + 10 位字母数字），也可以直接给视频链接。',
  E_NO_FETCH: '宿主需要 Node 18+ 的全局 fetch；手机本机引擎自带，SSH 主机请升级 DSH。',
  E_TIMEOUT: 'B站有点慢或者网络在抖，过一会儿再试一次。',
  E_ABORTED: '这次调用被取消了，没有拿到结果。',
  E_NETWORK: '检查一下这台机器的网络；B站需要能连 api.bilibili.com。',
  E_HTTP: '多半是网络中间层 / 代理拦了请求，换个网络再试。',
  E_NOT_JSON: '可能连到了需要登录的网关或运营商劫持页；换个网络再试。',
  E_BIZ: '确认 BV 号没写错；视频可能已删除、私有或仅限特定地区。',
  E_NO_DATA: '接口通了但没给数据，稍后重试。',
  E_INTERNAL: '这是插件的 bug，请把错误信息连同 BV 号一起反馈。',
}

const SOURCE = 'api.bilibili.com/x/web-interface/view'
const VIEW_URL = 'https://api.bilibili.com/x/web-interface/view'

/** 一个带错误码的失败。execute 把它变成 ok:false 的结果（不是抛给模型的裸异常）。 */
export class BiliError extends Error {
  /**
   * @param {string} code CODES 里的键
   * @param {string} [detail] 附在码后面的细节（原始报错、HTTP 状态、B站 message）
   */
  constructor(code, detail = '') {
    const known = Object.hasOwn(CODES, code)
    const label = CODES[code] ?? code
    super(detail === '' ? label : label + '：' + detail)
    this.name = 'BiliError'
    this.code = known ? code : 'E_INTERNAL'
    this.detail = String(detail)
  }
}

// ── 输入解析 ────────────────────────────────────────────────────────────────

// BV 号的 **尾巴是大小写敏感**的（BV1uv411q7Mv ≠ BV1UV411Q7MV），所以只把前缀归一，尾巴原样保留。
const BV_RE = /[Bb][Vv][0-9A-Za-z]{10}/
const AV_RE = /(?:^|[^0-9A-Za-z])(?:av|AV)(\d{1,18})(?:[^0-9]|$)/

/**
 * 从「BV 号 / 视频链接 / av 号 / 短链」里认出要查什么。**不联网**。
 * @param {unknown} input 用户 / 模型给的原始输入
 * @returns {{ kind: 'bvid'|'aid'|'short', value: string|number, raw: string, page?: number }}
 * @throws {BiliError} E_INPUT
 */
export function parseInput(input) {
  const raw = typeof input === 'string' ? input.trim() : ''
  if (raw === '') throw new BiliError('E_INPUT', '输入是空的')

  // 短链要联网跟随跳转，交给 peek() 处理；这里只认出来
  if (/^(?:https?:\/\/)?b23\.tv\//i.test(raw)) {
    return { kind: 'short', value: /^https?:\/\//i.test(raw) ? raw : 'https://' + raw, raw }
  }

  const bv = raw.match(BV_RE)
  if (bv !== null) {
    const value = 'BV' + bv[0].slice(2)
    const page = readPageParam(raw)
    return page === undefined ? { kind: 'bvid', value, raw } : { kind: 'bvid', value, raw, page }
  }

  const av = raw.match(AV_RE)
  if (av !== null) return { kind: 'aid', value: Number(av[1]), raw }

  // 链接里可能只有 ?aid=
  const aid = raw.match(/[?&]aid=(\d{1,18})/)
  if (aid !== null) return { kind: 'aid', value: Number(aid[1]), raw }

  throw new BiliError('E_INPUT', '认不出「' + raw.slice(0, 80) + '」')
}

function readPageParam(raw) {
  const m = raw.match(/[?&]p=(\d{1,3})/)
  if (m === null) return undefined
  const n = Number(m[1])
  return Number.isInteger(n) && n >= 1 ? n : undefined
}

// ── 结果规整 ────────────────────────────────────────────────────────────────

const isObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v)
const str = (v) => (typeof v === 'string' && v !== '' ? v : undefined)
const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : undefined)

/** epoch 秒 → ISO 字符串；不是正数就不给这个字段（**不写 null**：结果必须是可声明的 JSON）。 */
function isoOf(seconds) {
  const n = num(seconds)
  return n === undefined || n <= 0 ? undefined : new Date(n * 1000).toISOString()
}

/**
 * 把 x/web-interface/view 的 data 压成插件自己的结构（只留展示要用的字段）。
 * @param {unknown} data 接口返回的 data
 * @param {{ fetchedAt?: number, requestedPage?: number }} [meta]
 * @returns {object} ok:true 的结构化结果
 * @throws {BiliError} E_NO_DATA
 */
export function normalizeView(data, meta = {}) {
  if (!isObject(data)) throw new BiliError('E_NO_DATA', 'data 不是对象')
  const bvid = str(data.bvid)
  const title = str(data.title)
  if (bvid === undefined) throw new BiliError('E_NO_DATA', '缺少 bvid')
  if (title === undefined) throw new BiliError('E_NO_DATA', '缺少 title')

  const owner = isObject(data.owner) ? data.owner : {}
  const stat = isObject(data.stat) ? data.stat : {}
  const stats = {}
  for (const k of ['view', 'danmaku', 'reply', 'like', 'coin', 'favorite', 'share']) {
    const v = num(stat[k])
    if (v !== undefined) stats[k] = v
  }

  // 分 P：只留前 20 个，避免卡片 / 面板超预算
  const pages = Array.isArray(data.pages)
    ? data.pages.slice(0, 20).flatMap((p, i) => {
      if (!isObject(p)) return []
      const pageTitle = str(p.part) ?? 'P' + (i + 1)
      const duration = num(p.duration)
      const index = num(p.page) ?? i + 1
      return [duration === undefined
        ? { index, title: pageTitle.slice(0, 80) }
        : { index, title: pageTitle.slice(0, 80), duration }]
    })
    : []

  const requestedPage = num(meta.requestedPage)
  const currentPage = pages.length > 0 && requestedPage !== undefined
    ? pages.find((p) => p.index === requestedPage) ?? pages[0]
    : undefined

  const out = {
    ok: true,
    code: 'OK',
    message: CODES.OK,
    bvid,
    title: title.slice(0, 200),
    url: 'https://www.bilibili.com/video/' + bvid,
    stats,
    fetchedAt: num(meta.fetchedAt) ?? Date.now(),
    source: SOURCE,
  }
  const aid = num(data.aid)
  if (aid !== undefined) out.aid = aid
  const author = str(owner.name)
  if (author !== undefined) out.author = author.slice(0, 80)
  const mid = num(owner.mid)
  if (mid !== undefined) out.authorId = mid
  const desc = str(data.desc)
  if (desc !== undefined) out.desc = desc.slice(0, 1000)
  const pic = str(data.pic)
  if (pic !== undefined) out.cover = normalizeCover(pic)
  const duration = num(data.duration)
  if (duration !== undefined) out.duration = duration
  const pubdate = isoOf(data.pubdate)
  if (pubdate !== undefined) out.pubdate = pubdate
  const tid = num(data.tid)
  if (tid !== undefined) out.tid = tid
  if (pages.length > 0) out.pages = pages
  if (currentPage !== undefined) out.currentPage = currentPage
  return out
}

/** 封面统一成 https（B站老数据还有 http 的；明文图源在 App 侧会被拒）。 */
function normalizeCover(pic) {
  if (pic.startsWith('//')) return 'https:' + pic
  if (pic.startsWith('http://')) return 'https://' + pic.slice('http://'.length)
  return pic
}

// ── 网络 ────────────────────────────────────────────────────────────────────

export const DEFAULT_TIMEOUT_MS = 12000

/** 拿一个可用的 fetch，没有就报 E_NO_FETCH —— 缺依赖必须说出来。 */
export function resolveFetch(port) {
  if (typeof port === 'function') return port
  if (typeof globalThis.fetch === 'function') return globalThis.fetch.bind(globalThis)
  throw new BiliError('E_NO_FETCH', 'globalThis.fetch 不可用，也没有注入 fetch 端口')
}

/**
 * 查一个 BV / av / 短链的公开信息。
 *
 * @param {unknown} input BV 号、av 号、视频链接或 b23.tv 短链
 * @param {{ fetch?: Function, timeoutMs?: number, signal?: AbortSignal, now?: () => number }} [options]
 *   fetch 是**注入端口**：测试打桩靠它；宿主缺 fetch 时也能显式传入。
 * @returns {Promise<object>} ok:true 的结构化结果；失败抛 BiliError（由 callPeek 转成 ok:false）
 * @throws {BiliError}
 */
export async function peek(input, options = {}) {
  const fetchImpl = resolveFetch(options.fetch)
  const now = options.now ?? Date.now
  const timeoutMs = Number.isFinite(options.timeoutMs) && options.timeoutMs > 0
    ? options.timeoutMs
    : DEFAULT_TIMEOUT_MS

  let target = parseInput(input)

  if (target.kind === 'short') {
    // 短链：跟随跳转，从最终 URL 里再认一次。只认 http(s)，防止被指到奇怪的地方。
    const finalUrl = await resolveShortLink(target.value, { fetch: fetchImpl, timeoutMs, signal: options.signal })
    target = parseInput(finalUrl)
    if (target.kind === 'short') throw new BiliError('E_INPUT', '短链跳转后仍然没有 BV 号')
  }

  const query = target.kind === 'bvid'
    ? 'bvid=' + encodeURIComponent(String(target.value))
    : 'aid=' + String(target.value)
  const body = await getJson(VIEW_URL + '?' + query, { fetch: fetchImpl, timeoutMs, signal: options.signal })

  const code = isObject(body) ? num(body.code) : undefined
  if (code === undefined) throw new BiliError('E_NOT_JSON', '返回里没有 code 字段，不像 B站的接口')
  if (code !== 0) throw new BiliError('E_BIZ', ('code=' + code + ' ' + (str(body.message) ?? '')).trim())

  return normalizeView(body.data, { fetchedAt: now(), requestedPage: target.page })
}

async function resolveShortLink(shortUrl, { fetch: fetchImpl, timeoutMs, signal }) {
  const composed = composeSignal(signal, timeoutMs)
  let res
  try {
    res = await fetchImpl(shortUrl, { redirect: 'follow', signal: composed.signal })
  } catch (error) {
    throw classifyThrow(error, signal)
  }
  const finalUrl = isObject(res) ? str(res.url) : undefined
  if (finalUrl === undefined) throw new BiliError('E_NETWORK', '短链没有返回最终地址')
  if (!/^https?:\/\//i.test(finalUrl)) throw new BiliError('E_INPUT', '短链跳到了非网页地址：' + finalUrl.slice(0, 60))
  return finalUrl
}

/** 合并「调用方取消」与「本次超时」两个信号。 */
function composeSignal(signal, timeoutMs) {
  const timeout = AbortSignal.timeout(timeoutMs)
  if (signal === undefined || signal === null) return { signal: timeout, timeout }
  return { signal: AbortSignal.any([signal, timeout]), timeout }
}

/** 把一个抛出来的东西变成带错误码的 BiliError。**不许让它裸奔。** */
function classifyThrow(error, callerSignal) {
  if (error instanceof BiliError) return error
  const name = error?.name
  if (name === 'AbortError' || name === 'TimeoutError') {
    if (callerSignal != null && callerSignal.aborted === true) return new BiliError('E_ABORTED', '调用方取消了')
    return new BiliError('E_TIMEOUT', error?.message ?? '超时')
  }
  return new BiliError('E_NETWORK', error?.message ?? String(error))
}

/**
 * GET 一个 JSON。五类失败各有码：取消 / 超时 / 网络 / HTTP / 非 JSON。
 * @returns {Promise<unknown>}
 */
export async function getJson(url, { fetch: fetchImpl, timeoutMs, signal }) {
  const composed = composeSignal(signal, timeoutMs)
  let res
  try {
    res = await fetchImpl(url, {
      signal: composed.signal,
      headers: {
        // B站会拒掉没有 UA 的请求；Referer 也照浏览器带上。不带 cookie，不登录。
        'user-agent': 'Mozilla/5.0 (Linux; Android 14) dsh-go-bili-peek/0.1',
        referer: 'https://www.bilibili.com/',
        accept: 'application/json',
      },
    })
  } catch (error) {
    throw classifyThrow(error, signal)
  }

  if (!isObject(res)) throw new BiliError('E_NETWORK', 'fetch 返回的不是 Response')

  const status = num(res.status)
  if (res.ok === false && status !== undefined) throw new BiliError('E_HTTP', 'HTTP ' + status)
  if (res.ok === false) throw new BiliError('E_HTTP', 'HTTP 非 2xx')

  let text
  try {
    text = typeof res.text === 'function' ? await res.text() : JSON.stringify(res)
  } catch (error) {
    throw classifyThrow(error, signal)
  }
  // 超时可能落在读完 headers 之后
  if (composed.timeout.aborted && !(signal != null && signal.aborted === true)) {
    throw new BiliError('E_TIMEOUT', '超过 ' + timeoutMs + ' ms')
  }
  if (typeof text !== 'string') throw new BiliError('E_NOT_JSON', '响应体不是文本')

  try {
    return JSON.parse(text)
  } catch {
    const head = text.trim().slice(0, 60).replace(/\s+/g, ' ')
    throw new BiliError('E_NOT_JSON', head === '' ? '响应体是空的' : '拿到的是「' + head + '」')
  }
}

/** 把任何失败变成统一的结果形状（供工具 execute 用）。 */
export function failureOf(error, now = Date.now()) {
  const bili = error instanceof BiliError ? error : new BiliError('E_INTERNAL', error?.message ?? String(error))
  return {
    ok: false,
    code: bili.code,
    message: bili.message,
    hint: HINTS[bili.code] ?? HINTS.E_INTERNAL,
    fetchedAt: now,
    source: SOURCE,
  }
}

/** peek() 的「永不抛」包装：成功和失败都是 ok 字段明确的结果。 */
export async function callPeek(input, options = {}) {
  const now = options.now ?? Date.now
  try {
    return await peek(input, options)
  } catch (error) {
    return failureOf(error, now())
  }
}

/** 把播放量这类大数压成 12.3万 / 1.2亿 —— 卡片和状态条用。 */
export function compact(n) {
  const v = num(n)
  if (v === undefined) return '?'
  if (v >= 100000000) return trim(v / 100000000) + '亿'
  if (v >= 10000) return trim(v / 10000) + '万'
  return String(v)
}

function trim(x) {
  const r = Math.round(x * 10) / 10
  return Number.isInteger(r) ? String(r) : r.toFixed(1)
}

/** 秒 → 12:34 / 1:02:03 */
export function hhmmss(seconds) {
  const v = num(seconds)
  if (v === undefined || v < 0) return '?'
  const s = Math.round(v)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const pad = (x) => String(x).padStart(2, '0')
  return h > 0 ? h + ':' + pad(m) + ':' + pad(sec) : m + ':' + pad(sec)
}
