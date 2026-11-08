/**
 * dsh-go-bili —— 插件生态与能力扩展的 P1：**bili-peek**
 *
 * 目标（一句话）：让 Agent 能主动调用一个 B 站信息查询工具，拿到结构化结果，
 * 在 DSH 原生界面里看到结果卡片，并且那个结果能交回给 Agent 继续分析。
 *
 * 宿主能力只用这三个（**接口以本仓库源码为准，不是猜的**）：
 *   1. ctx.tools.register(defineTool({ … }))
 *      —— @deepseek-ai/dsh-tools 0.2.1-rc.2。defineTool 编译 parameters / output.schema；
 *      registry.register 断言「output.render 必须是函数、presentationMeta 要么是函数要么不存在」，
 *      并返回 disposer（dsh-tools/lib/index.js: register / defineTool）。
 *   2. ctx.effect(() => disposer)
 *      —— cordis 4.0.5，fiber.effect()：execute 立即跑，返回的函数在 fiber 卸载时执行。
 *   3. ctx.dshGo.{strip,panel,refresh}
 *      —— dsh-go 桥接，**必须**写在 ctx.inject(['dshGo'], …) 里（不能写 'dshGo?'，也不能上顶层 inject）。
 *
 * 存在性检查先于一切：
 *   · 宿主没有 ctx.tools → 不注册工具、台账 toolRegistered=false，**不抛错**，
 *     面板/状态条照常起来（能看到「未就绪」和原因，而不是整块挂掉）。
 *   · 宿主没有 dsh-go 桥接 → ctx.inject(['dshGo']) 的回调永远不执行，插件安静地只做工具。
 *   · 宿主没有 ctx.effect → 跳过清理注册，不让插件起不来。
 *
 * 本轮明确不做：B站登录、评论、私信、动态等任何涉及账号权限的功能。
 * 本插件只读 x/web-interface/view 这一个公开接口，不带 cookie、不做写操作。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

import { defineTool } from '@deepseek-ai/dsh-tools'

import { callPeek, compact, hhmmss } from './bili.mjs'
import { peekCard, peekPanel, stripData } from './views.mjs'
import { emptyLedger, normalizeLedger, recordRun, describe } from './ledger.mjs'

export const name = 'dsh-go-bili'

/** 工具卡上限：它会被写进对话记录，必须小。 */
const CARD_MAX_BYTES = 8 * 1024
/** 面板里最多保留多少条查询记录。 */
const RECENT_MAX = 30
/** 工具声明的超时（注册表只作声明；真正的截止靠 AbortSignal.timeout）。 */
const TOOL_TIMEOUT_MS = 30_000

// ── 工具卡包装（与 docs/plugins/quickstart.md 的 toolCard 同一份行为）──────────
/**
 * 包住视图构造。DSH 在 presentationMeta 抛错、或返回非无损 JSON 时会让**整次工具调用失败**，
 * 所以这里接住异常、过一遍 JSON、超过 8 KiB 就返回 {}（不出卡片），绝不连累工具调用。
 */
export function toolCard(build, maxBytes = CARD_MAX_BYTES) {
  return (args, value) => {
    try {
      const text = JSON.stringify(build(args, value))
      if (text === undefined || Buffer.byteLength(text, 'utf8') > maxBytes) return {}
      return { 'dsh-go/view': JSON.parse(text) }
    } catch {
      return {}
    }
  }
}

// ── 输出 schema（dsh-tools 的作者 DSL：对象节点必须显式 additionalProperties）──
const pageSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    index: { type: 'number', required: true, description: '第几 P' },
    title: { type: 'string', required: true, description: '这个 P 的小标题' },
    duration: { type: 'number', description: '秒；没有就不给这个字段' },
  },
}

const statsSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    view: { type: 'number' },
    danmaku: { type: 'number' },
    reply: { type: 'number' },
    like: { type: 'number' },
    coin: { type: 'number' },
    favorite: { type: 'number' },
    share: { type: 'number' },
  },
}

export const outputSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ok: { type: 'boolean', required: true, description: '查到了 true；失败 false（失败也是正常结果，不是异常）' },
    code: { type: 'string', required: true, description: 'OK / E_INPUT / E_NO_FETCH / E_TIMEOUT / E_ABORTED / E_NETWORK / E_HTTP / E_NOT_JSON / E_BIZ / E_NO_DATA / E_INTERNAL' },
    message: { type: 'string', required: true, description: '给人看的一句话' },
    hint: { type: 'string', description: '失败时下一步怎么做' },
    source: { type: 'string', required: true, description: '数据来源接口' },
    fetchedAt: { type: 'number', required: true, description: '取到数据的时刻（epoch ms）' },
    bvid: { type: 'string' },
    aid: { type: 'number' },
    title: { type: 'string' },
    author: { type: 'string' },
    authorId: { type: 'number' },
    desc: { type: 'string', description: '简介（截断到 1000 字）' },
    cover: { type: 'string' },
    duration: { type: 'number', description: '总时长（秒）' },
    pubdate: { type: 'string', description: '发布时间（ISO 8601）' },
    tid: { type: 'number', description: '分区 id' },
    url: { type: 'string' },
    stats: statsSchema,
    pages: { type: 'array', items: pageSchema, description: '分 P（最多 20 个）' },
    currentPage: pageSchema,
  },
}

/** 给模型看的文字：模型据此继续分析，所以信息要够，但不要塞整段简介。 */
export function peekText(value = {}) {
  if (value === null || typeof value !== 'object' || value.ok !== true) {
    const code = String(value?.code ?? 'E_INTERNAL')
    const hint = typeof value?.hint === 'string' ? '\n下一步：' + value.hint : ''
    return 'Bili peek failed (' + code + '): ' + String(value?.message ?? 'no reason given') + hint
  }
  const stats = value.stats ?? {}
  return [
    String(value.title) + ' — ' + String(value.author ?? '?') + ' (' + String(value.bvid) + ')',
    'views ' + compact(stats.view) + ', likes ' + compact(stats.like) + ', danmaku ' + compact(stats.danmaku)
      + ', duration ' + hhmmss(value.duration),
    typeof value.pubdate === 'string' ? 'published ' + value.pubdate.slice(0, 10) : '',
    typeof value.desc === 'string' && value.desc.trim() !== ''
      ? 'description: ' + value.desc.trim().replace(/\s+/g, ' ').slice(0, 400)
      : '(no description)',
    Array.isArray(value.pages) && value.pages.length > 1
      ? value.pages.length + ' parts; the user is looking at P' + String(value.currentPage?.index ?? 1)
      : '',
    'url ' + String(value.url ?? ''),
    'source: ' + String(value.source ?? ''),
  ].filter((x) => x !== '').join('\n')
}

// ── 宿主探测：**不用 ctx.x 直接读**（没 inject 的 service 会抛）────────────────
/** 读一个服务；拿不到就 undefined，不抛。 */
export function serviceOf(ctx, serviceName) {
  try {
    if (typeof ctx.get === 'function') {
      const got = ctx.get(serviceName)
      if (got !== undefined && got !== null) return got
    }
  } catch {}
  try {
    const direct = ctx[serviceName]
    if (direct !== undefined && direct !== null) return direct
  } catch {}
  return undefined
}

/**
 * 台账落盘位置。**延迟读** DSH_HOME：写成模块级常量会在 import 那一刻定死，
 * 换 profile / 测试里改环境变量都不生效（partner 那份 README 里踩过这个坑）。
 */
export function ledgerPathFor(env = process.env) {
  const explicit = env?.DSH_HOME?.trim()
  const home = explicit !== undefined && explicit !== '' ? explicit : path.join(os.homedir(), '.dsh')
  return path.join(home, 'cache', 'dsh-go-bili', 'ledger.json')
}

/** 读盘上的台账 + 最近记录。**坏文件不抛**，当成第一次跑。 */
export function loadState(file, fsImpl = fs) {
  const out = { ledger: emptyLedger(), recent: [] }
  try {
    if (typeof file !== 'string' || file === '') return out
    if (!fsImpl.existsSync(file)) return out
    const raw = JSON.parse(fsImpl.readFileSync(file, 'utf8'))
    out.ledger = normalizeLedger(raw?.ledger)
    const list = Array.isArray(raw?.recent) ? raw.recent : []
    out.recent = list.filter((r) => r !== null && typeof r === 'object' && !Array.isArray(r)).slice(0, RECENT_MAX)
  } catch {
    // 读不到 / 解析不了 = 第一次跑，不是错误
  }
  return out
}

/** 落盘（原子写）。写不进去就算了 —— 不让观测本身变成故障源。 */
export function saveState(file, ledger, recent, fsImpl = fs) {
  if (typeof file !== 'string' || file === '') return false
  try {
    fsImpl.mkdirSync(path.dirname(file), { recursive: true })
    fsImpl.writeFileSync(file + '.tmp', JSON.stringify({ ledger: normalizeLedger(ledger), recent }))
    fsImpl.renameSync(file + '.tmp', file)
    return true
  } catch {
    return false
  }
}

// ── 插件本体 ────────────────────────────────────────────────────────────────
/**
 * @param {object} ctx cordis 上下文
 * @param {{ fetch?: Function, now?: () => number, ledgerPath?: string|null, timeoutMs?: number,
 *           fs?: object }} [options]
 *   全部可注入：测试不碰真网络、不碰真 home、不碰真文件系统。
 * @returns {object} 只读句柄（给测试与 cap-ledger 的调用方用）
 */
export function apply(ctx, options = {}) {
  const now = options.now ?? Date.now
  const fetchPort = options.fetch
  const fsImpl = options.fs ?? fs
  const timeoutMs = Number.isFinite(options.timeoutMs) && options.timeoutMs > 0 ? options.timeoutMs : undefined
  const ledgerPath = options.ledgerPath === undefined ? ledgerPathFor() : options.ledgerPath

  // ① 台账：installed / loaded 由「我们真的跑到这里」判定；toolRegistered 等注册结果
  const loaded = loadState(ledgerPath, fsImpl)
  const ledger = loaded.ledger
  ledger.installed = true
  ledger.loaded = true
  ledger.updatedAt = now()
  const recent = loaded.recent

  /** 状态条 / 面板的推送回调；记录一变就通知一次 */
  const listeners = new Set()
  let saveTimer

  const save = () => {
    if (saveTimer !== undefined) {
      clearTimeout(saveTimer)
      saveTimer = undefined
    }
    return saveState(ledgerPath, ledger, recent, fsImpl)
  }
  const scheduleSave = () => {
    if (typeof ledgerPath !== 'string' || ledgerPath === '') return
    if (saveTimer !== undefined) return
    // 合并短时间内的多次写入（连续查几个视频只落一次盘）
    saveTimer = setTimeout(() => {
      saveTimer = undefined
      saveState(ledgerPath, ledger, recent, fsImpl)
    }, 1500)
    if (typeof saveTimer === 'object' && saveTimer !== null && typeof saveTimer.unref === 'function') saveTimer.unref()
  }

  /** 推一次数据给所有订阅者；没有订阅者就什么也不做。 */
  const notify = () => {
    for (const push of listeners) {
      try {
        push()
      } catch (error) {
        console.error('[bili] 推送失败：' + (error instanceof Error ? error.message : String(error)))
      }
    }
  }

  /** 面板与状态条读同一份快照 —— 口径不会分叉。 */
  const snapshot = () => ({ recent: recent.slice(), ledger: normalizeLedger(ledger) })

  /** 跑一次查询并把结果记进台账。工具执行与手工排查共用这一条路。 */
  const run = async (input, signal) => {
    const result = await callPeek(input, {
      ...(fetchPort === undefined ? {} : { fetch: fetchPort }),
      ...(timeoutMs === undefined ? {} : { timeoutMs }),
      ...(signal === undefined ? {} : { signal }),
      now,
    })
    // ④ 最近一次执行结果（成功 / 失败 + 失败原因）—— 这正是台账存在的理由
    recordRun(ledger, {
      ok: result.ok === true,
      ...(typeof result.code === 'string' ? { code: result.code } : {}),
      ...(typeof result.message === 'string' ? { message: result.message } : {}),
      at: now(),
    })
    if (result.ok === true) {
      recent.unshift({
        bvid: result.bvid,
        title: result.title,
        ...(typeof result.author === 'string' ? { author: result.author } : {}),
        ...(Number.isFinite(result.stats?.view) ? { view: result.stats.view } : {}),
        ok: true,
        at: result.fetchedAt,
      })
    } else {
      recent.unshift({ bvid: String(input ?? ''), ok: false, code: result.code, at: result.fetchedAt })
    }
    while (recent.length > RECENT_MAX) recent.pop()
    scheduleSave()
    notify()
    return result
  }

  // ② 工具：先做存在性检查，宿主没有就降级（**不抛**）
  const tools = serviceOf(ctx, 'tools')
  const canRegister = tools !== undefined && typeof tools.register === 'function'
  let toolRegistered = false
  let registerError
  let unregister

  if (canRegister) {
    try {
      const definition = defineTool({
        name: 'peek_bili_video',
        description:
          'Look up the PUBLIC information of one Bilibili video: title, uploader, description, view/like/danmaku counts, '
          + 'duration, publish date and parts (分P). Input may be a BV id, an av number, a bilibili.com video URL or a '
          + 'b23.tv short link. Read-only: no login, no cookies, no comments, no private messages. '
          + 'The user already sees the result as a card, so summarise and analyse it instead of repeating every number. '
          + 'On failure the result carries ok=false plus a code and a hint: relay the hint, do not invent a video.',
        parameters: {
          id: {
            type: 'string',
            required: true,
            description: 'BV id (e.g. BV1uv411q7Mv), av number, bilibili video URL, or b23.tv short link.',
          },
        },
        output: {
          schema: outputSchema,
          render: (_args, value) => [{ type: 'text', text: peekText(value) }],
          presentationMeta: toolCard((args, value) => peekCard(args, value)),
        },
        timeoutMs: TOOL_TIMEOUT_MS,
        isConcurrencySafe: () => true,
        execute: (args, exec) => run(args?.id, exec?.signal),
      })
      const disposer = tools.register(definition)
      // 只有 register 真的回来了才算「工具已注册」
      toolRegistered = typeof disposer === 'function'
      if (toolRegistered) unregister = disposer
    } catch (error) {
      toolRegistered = false
      registerError = error instanceof Error ? error.message : String(error)
      console.error('[bili] peek_bili_video 注册失败：' + registerError)
    }
  }
  // ③ toolRegistered 只反映「注册真的成功了」；失败原因写进 lastRunMessage
  ledger.toolRegistered = toolRegistered
  if (registerError !== undefined) {
    ledger.lastRunCode = 'E_INTERNAL'
    ledger.lastRunMessage = '工具注册失败：' + registerError
  }
  ledger.updatedAt = now()

  // ④ 清理：ctx.effect 存在才用（不存在也不让插件起不来）
  if (typeof ctx.effect === 'function') {
    try {
      ctx.effect(() => () => {
        save()
        if (typeof unregister === 'function') unregister()
        listeners.clear()
      })
    } catch (error) {
      console.error('[bili] 清理注册失败：' + (error instanceof Error ? error.message : String(error)))
    }
  }

  // ⑤ 原生界面：只有装了 dsh-go 桥接的主机才会进这个回调
  ctx.inject(['dshGo'], (bridgeCtx) => {
    // ⚠️ view / strip 的 dataFn 必须**同步**返回。用 async 包一层「保护异常」会把返回值
    //    变成 Promise：view 拿到 Promise → App 报「插件界面有误」；strip 拿到 Promise
    //    → 取消函数永远调不到（定时器泄漏）。所以异常保护分同步/异步两版。
    const syncGuard = (label, fn) => (...args) => {
      try {
        return fn(...args)
      } catch (error) {
        console.error('[bili] ' + label + ' 失败：' + (error instanceof Error ? error.message : String(error)))
        return undefined
      }
    }
    const asyncGuard = (label, fn) => async (...args) => {
      try {
        return await fn(...args)
      } catch (error) {
        console.error('[bili] ' + label + ' 失败：' + (error instanceof Error ? error.message : String(error)))
        return null
      }
    }

    /** 订阅：立刻推一次，之后每次记录变化再推；返回**同步**的取消函数（不留定时器）。 */
    const watch = (push) => {
      push()
      listeners.add(push)
      return () => {
        listeners.delete(push)
      }
    }

    // 状态条：输入框上方一行。**没变化就不 emit**（桥接本身限 4Hz / 32KiB）。
    bridgeCtx.dshGo.strip('peek.strip', (scope, emit) => {
      let last = ''
      return watch(() => {
        const data = stripData(snapshot())
        const sig = JSON.stringify(data)
        if (sig === last) return
        last = sig
        emit(data)
      })
    })

    // 面板：能力台账（四态）+ 最近查的
    bridgeCtx.dshGo.panel('peek', {
      // view 必须是同步返回的视图
      view: syncGuard('peek.view', () => peekPanel(snapshot())),
      data: (scope, emit) => {
        let last = ''
        return watch(() => {
          const s = snapshot()
          const sig = JSON.stringify(s.ledger) + '|' + s.recent.length + '|' + String(s.recent[0]?.at ?? '')
          if (sig === last) return
          last = sig
          emit({ ledger: s.ledger, recent: s.recent })
        })
      },
      actions: {
        refresh: asyncGuard('peek.refresh', (scope) => {
          bridgeCtx.dshGo.refresh('peek', scope)
          return { toast: '已刷新' }
        }),
        ledger: asyncGuard('peek.ledger', () => ({
          copy: JSON.stringify(normalizeLedger(ledger), null, 2),
          toast: '台账已复制',
        })),
      },
    })
  })

  return {
    ledger,
    recent,
    snapshot,
    /** 直接跑一次查询（不经过模型）；测试和手工排查用 */
    peek: (input) => run(input, undefined),
    toolRegistered: () => toolRegistered,
    registerError: () => registerError,
    ledgerPath,
    describe: () => describe(ledger),
    save,
  }
}
