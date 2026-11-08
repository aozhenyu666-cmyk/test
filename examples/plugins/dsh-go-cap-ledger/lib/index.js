/**
 * dsh-go-cap-ledger —— 插件能力台账的最小实现（第二件事）。
 *
 * 台账必须能区分**四个状态**，只显示插件名等于没有台账：
 *   installed      已安装   看得到 package.json 与入口文件
 *   loaded         已加载   apply() 真的跑到了（就是本文件这一段）
 *   toolRegistered 工具已注册  ctx.tools.register 返回了 disposer
 *   lastRunOk      最近一次执行  true / false（带 code + message）/ null（还没跑过）
 *
 * 它自己也是一个插件，所以**它先给自己建一条台账**，再给外部登记进来的插件建。
 * 这样面板上永远有一条「这个台账插件自己到底行不行」，不会出现「台账插件自己没台账」。
 *
 * 宿主能力只用这三个（接口以本仓库源码为准）：
 *   · ctx.tools            —— 可选。有就查「工具真的注册上了吗」，没有就把 toolRegistered 标 false。
 *   · ctx.effect           —— 可选。卸载时落盘。
 *   · ctx.dshGo            —— 可选，必须 ctx.inject(['dshGo'])。
 *
 * 纯 JS、零第三方依赖。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

import { ledgerPanel, stripData } from './views.mjs'
import {
  describeEntry, deserialize, emptyEntry, emptyLedger, entryOf, markInstalled, markLoaded,
  markToolRegistered, markRun, normalizeLedger, putEntry, serialize, summarize,
} from './ledger.mjs'

export const name = 'dsh-go-cap-ledger'

/** 本插件自己的登记名（用包名，和 package.json 一致）。 */
const SELF = 'dsh-go-cap-ledger'

// ── 工具卡与给模型的文字（工具卡只用白名单 8 个组件）─────────────────────────
const CARD_MAX_BYTES = 8 * 1024

/** 包住视图构造：DSH 在 presentationMeta 抛错或返回非无损 JSON 时会让整次调用失败。 */
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

const clamp = (s, n) => (typeof s === 'string' ? s.slice(0, n) : '')

/**
 * 台账工具的结果 → 原生卡片。**失败也要出卡**（写清哪个插件坏了、为什么）。
 * 只用工具卡白名单：stack / row / text / metric / chart / compare / steps / callout。
 */
export function ledgerCard(_args = {}, value = {}) {
  if (value === null || typeof value !== 'object' || value.ok !== true) {
    return {
      v: 1,
      type: 'stack',
      title: '台账 · 没查到',
      children: [{ type: 'callout', tone: 'warn', text: '没有这个插件的登记。检查一下名字，或者让它先跑一次。' }],
    }
  }
  const summary = value.summary ?? {}
  const plugins = Array.isArray(value.plugins) ? value.plugins : []
  const children = [
    {
      type: 'row',
      children: [
        { type: 'metric', label: '登记', value: String(summary.total ?? plugins.length), caption: '个插件' },
        { type: 'metric', label: '能用', value: String(summary.healthy ?? 0), tone: 'good', caption: '四态齐全' },
        {
          type: 'metric',
          label: '上次失败',
          value: String(summary.failing ?? 0),
          ...(Number(summary.failing) > 0 ? { tone: 'warn' } : {}),
          caption: Number(summary.failing) > 0 ? '看下面' : '都没有',
        },
      ],
    },
  ]

  const bad = plugins.filter((p) => p.healthy !== true)
  if (bad.length === 0 && plugins.length > 0) {
    children.push({ type: 'callout', tone: 'good', text: '全部插件四态齐全，最近一次也没有失败。' })
  }
  // 工具卡里没有 list —— 用一行文字列出来（最多 4 条）
  for (const p of bad.slice(0, 4)) {
    const flags = [
      p.installed === false ? '未安装' : '',
      p.loaded === false ? '未加载' : '',
      p.toolRegistered === false ? '工具未注册' : '',
      p.lastRunOk === false ? '上次失败 ' + String(p.lastRunCode ?? '') : '',
    ].filter((x) => x !== '')
    children.push({ type: 'callout', tone: p.lastRunOk === false ? 'warn' : 'info', text: clamp(String(p.plugin) + '：' + (flags.join(' · ') || '状态未知'), 160) })
  }
  if (bad.length > 4) children.push({ type: 'text', text: '还有 ' + (bad.length - 4) + ' 个有问题，点开面板看全量。', tone: 'muted' })
  if (plugins.length === 0) children.push({ type: 'callout', tone: 'info', text: '台账里还没有这个插件。' })

  return { v: 1, type: 'stack', title: '台账 · ' + (summary.healthy ?? 0) + '/' + (summary.total ?? plugins.length) + ' 能用', children }
}

/** 给模型读的文字：一行一个插件，四态 + 失败原因。 */
export function ledgerText(value = {}) {
  if (value === null || typeof value !== 'object' || value.ok !== true) {
    return 'Capability ledger: no such plugin registered.'
  }
  const summary = value.summary ?? {}
  const lines = (Array.isArray(value.plugins) ? value.plugins : []).map((p) => {
    const flags = [
      'installed=' + (p.installed === undefined ? '?' : p.installed),
      'loaded=' + (p.loaded === undefined ? '?' : p.loaded),
      'toolRegistered=' + (p.toolRegistered === undefined ? '?' : p.toolRegistered),
      'lastRunOk=' + (p.lastRunOk === undefined ? 'never-ran' : p.lastRunOk),
    ].join(' ')
    const why = p.lastRunOk === false ? ' — last failure ' + String(p.lastRunCode ?? '?') + ': ' + String(p.lastRunMessage ?? '(no reason)') : ''
    return String(p.plugin) + ': ' + flags + ', runs=' + p.runs + ', fails=' + p.fails + why
  })
  return [
    'Capability ledger: ' + (summary.healthy ?? 0) + '/' + (summary.total ?? 0) + ' plugins fully healthy, ' + (summary.failing ?? 0) + ' had a failing last run.',
    ...lines,
  ].join('\n')
}

// ── 宿主探测（不用 ctx.x 直接读：没 inject 的 service 会抛）───────────────────
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

/** 台账落盘位置。**延迟读** DSH_HOME（写成模块级常量会在 import 那一刻定死）。 */
export function ledgerPathFor(env = process.env) {
  const explicit = env?.DSH_HOME?.trim()
  const home = explicit !== undefined && explicit !== '' ? explicit : path.join(os.homedir(), '.dsh')
  return path.join(home, 'cache', 'dsh-go-cap-ledger', 'ledger.json')
}

/** 读盘上的台账。坏文件不抛，当成第一次跑。 */
export function load(file, fsImpl = fs) {
  try {
    if (typeof file !== 'string' || file === '') return emptyLedger()
    if (!fsImpl.existsSync(file)) return emptyLedger()
    return deserialize(fsImpl.readFileSync(file, 'utf8'))
  } catch {
    return emptyLedger()
  }
}

/** 原子落盘。写不进去就算了（观测本身不该变成故障源）。 */
export function save(file, ledger, fsImpl = fs) {
  if (typeof file !== 'string' || file === '') return false
  try {
    fsImpl.mkdirSync(path.dirname(file), { recursive: true })
    fsImpl.writeFileSync(file + '.tmp', serialize(ledger))
    fsImpl.renameSync(file + '.tmp', file)
    return true
  } catch {
    return false
  }
}

/**
 * @param {object} ctx cordis 上下文
 * @param {{ now?: () => number, ledgerPath?: string|null, fs?: object, registered?: string }} [options]
 *   registered：宿主里**真的注册成功**的工具名（不给就按 ctx.tools 自己探）。
 */
export function apply(ctx, options = {}) {
  const now = options.now ?? Date.now
  const fsImpl = options.fs ?? fs
  const ledgerPath = options.ledgerPath === undefined ? ledgerPathFor() : options.ledgerPath

  const ledger = load(ledgerPath, fsImpl)
  const listeners = new Set()
  let saveTimer

  const saveNow = () => {
    if (saveTimer !== undefined) {
      clearTimeout(saveTimer)
      saveTimer = undefined
    }
    return save(ledgerPath, ledger, fsImpl)
  }
  const scheduleSave = () => {
    if (typeof ledgerPath !== 'string' || ledgerPath === '') return
    if (saveTimer !== undefined) return
    saveTimer = setTimeout(() => {
      saveTimer = undefined
      save(ledgerPath, ledger, fsImpl)
    }, 1500)
    if (typeof saveTimer === 'object' && saveTimer !== null && typeof saveTimer.unref === 'function') saveTimer.unref()
  }
  const notify = () => {
    for (const push of listeners) {
      try {
        push()
      } catch (error) {
        console.error('[cap-ledger] 推送失败：' + (error instanceof Error ? error.message : String(error)))
      }
    }
  }
  const snapshot = () => ({ ledger: normalizeLedger(ledger), now: now() })

  // ── 先把**自己**登记上：台账插件自己没有台账是最难看的 ─────────────────────
  const selfEntry = entryOf(ledger, SELF)
  selfEntry.plugin = SELF
  markInstalled(selfEntry, { ok: true, at: now() })
  markLoaded(selfEntry, { at: now() })

  // 工具：台账自己注册一个查询工具，让 Agent 也能问「哪个插件坏了」。
  // **不用 defineTool**：ctx.tools.register 只要求 output.render 存在 + output.schema 是
  // 受支持的 JSON Schema 子集（dsh-tools/lib/index.js 的 register）。

  const tools = serviceOf(ctx, 'tools')
  let unregister
  let registerError
  if (tools !== undefined && typeof tools.register === 'function') {
    try {
      const disposer = tools.register({
        name: 'cap_ledger_status',
        description:
          'Report the capability ledger: for every known plugin, whether it is installed, loaded, has registered its tool, '
          + 'and how its most recent run went (with the failure code and reason). '
          + 'Use it when the user asks why a plugin or one of its tools does not work. Read-only.',
        parameters: {
          type: 'object',
          properties: {
            plugin: { type: 'string', description: 'Limit the answer to one plugin name. Omit for all.' },
          },
          additionalProperties: false,
        },
        output: {
          schema: {
            type: 'object',
            properties: {
              ok: { type: 'boolean' },
              summary: {
                type: 'object',
                properties: {
                  total: { type: 'number' },
                  healthy: { type: 'number' },
                  failing: { type: 'number' },
                },
                required: ['total', 'healthy', 'failing'],
                additionalProperties: false,
              },
              plugins: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: {
                    plugin: { type: 'string' },
                    installed: { type: 'boolean' },
                    loaded: { type: 'boolean' },
                    toolRegistered: { type: 'boolean' },
                    lastRunOk: { type: 'boolean' },
                    lastRunCode: { type: 'string' },
                    lastRunMessage: { type: 'string' },
                    runs: { type: 'number' },
                    fails: { type: 'number' },
                    healthy: { type: 'boolean' },
                  },
                  required: ['plugin', 'healthy', 'runs', 'fails'],
                  additionalProperties: false,
                },
              },
            },
            required: ['ok', 'summary', 'plugins'],
            additionalProperties: false,
          },
          render: (_args, value) => [{ type: 'text', text: ledgerText(value) }],
          presentationMeta: (args, value) => toolCard((a, v) => ledgerCard(a, v))(args, value),
        },
        timeoutMs: 5000,
        isConcurrencySafe: () => true,
        async execute(args) {
          return ledgerStatus(typeof args?.plugin === 'string' && args.plugin !== '' ? args.plugin : undefined)
        },
      })
      // 只有 register 真的回来了 disposer，才算「工具已注册」
      if (typeof disposer === 'function') unregister = disposer
    } catch (error) {
      registerError = error instanceof Error ? error.message : String(error)
      console.error('[cap-ledger] cap_ledger_status 注册失败：' + registerError)
    }
  }

  if (tools === undefined || typeof tools.register !== 'function') {
    // 宿主没有工具能力：toolRegistered 就是 false，但这不算一次执行失败
    selfEntry.toolRegistered = false
    selfEntry.updatedAt = now()
  } else {
    markToolRegistered(selfEntry, { ok: typeof unregister === 'function', at: now(), ...(registerError === undefined ? {} : { error: registerError }) })
  }
  // 注册成功不是一次「执行」，别把它记成 lastRunOk=true
  if (typeof unregister === 'function') {
    selfEntry.lastRunOk = null
    selfEntry.lastRunAt = null
    selfEntry.lastRunCode = null
    selfEntry.lastRunMessage = null
    selfEntry.runs = 0
    selfEntry.fails = 0
  }
  putEntry(ledger, selfEntry)

  /** 工具执行的实现：读台账 → 结构化结果，并记一次执行。 */
  const ledgerStatus = (only) => {
    const l = normalizeLedger(ledger)
    const names = Object.keys(l.plugins).sort().filter((n) => only === undefined || n === only)
    const sum = summarize(l)
    const plugins = names.map((n) => {
      const e = l.plugins[n]
      const d = describeEntry(e)
      return {
        plugin: n,
        ...(e.installed === null ? {} : { installed: e.installed }),
        ...(e.loaded === null ? {} : { loaded: e.loaded }),
        ...(e.toolRegistered === null ? {} : { toolRegistered: e.toolRegistered }),
        ...(e.lastRunOk === null ? {} : { lastRunOk: e.lastRunOk }),
        ...(e.lastRunCode === null ? {} : { lastRunCode: e.lastRunCode }),
        ...(e.lastRunMessage === null ? {} : { lastRunMessage: e.lastRunMessage }),
        runs: e.runs,
        fails: e.fails,
        healthy: d.healthy,
      }
    })
    const value = {
      ok: only === undefined || plugins.length > 0,
      summary: { total: only === undefined ? sum.total : plugins.length, healthy: sum.healthy, failing: sum.failing },
      plugins,
    }
    // ④ 这一次工具执行的结果记在自己名下
    markRun(selfEntry, { ok: value.ok, ...(value.ok ? {} : { code: 'E_NOT_FOUND', message: '没有名为 ' + String(only) + ' 的登记' }), at: now() })
    putEntry(ledger, selfEntry)
    scheduleSave()
    notify()
    return value
  }

  // ── 对外 API：别的插件/操作者可以登记自己 ───────────────────────────────────
  const api = {
    /** 一个插件的四态快照 */
    get: (plugin) => describeEntry(entryOf(ledger, plugin)),
    /** 登记「已安装 + 已加载」 */
    register(plugin, evidence = {}) {
      const e = { ...entryOf(ledger, plugin), plugin }
      markInstalled(e, { ok: evidence.installed !== false, at: now() })
      if (evidence.loaded !== false) markLoaded(e, { at: now() })
      if (evidence.toolRegistered !== undefined) markToolRegistered(e, { ok: evidence.toolRegistered === true, at: now(), ...(typeof evidence.error === 'string' ? { error: evidence.error } : {}) })
      putEntry(ledger, e)
      scheduleSave()
      notify()
      return describeEntry(e)
    },
    /** 记一次执行结果（成功/失败 + 失败原因） */
    record(plugin, run) {
      const e = { ...entryOf(ledger, plugin), plugin }
      markRun(e, { ...run, at: Number.isFinite(run?.at) ? run.at : now() })
      putEntry(ledger, e)
      scheduleSave()
      notify()
      return describeEntry(e)
    },
    /** 整份台账（面板用） */
    all: () => normalizeLedger(ledger),
    /** 汇总 */
    summary: () => summarize(ledger),
  }

  // ── 清理 ───────────────────────────────────────────────────────────────────
  if (typeof ctx.effect === 'function') {
    try {
      ctx.effect(() => () => {
        saveNow()
        listeners.clear()
      })
    } catch (error) {
      console.error('[cap-ledger] 清理注册失败：' + (error instanceof Error ? error.message : String(error)))
    }
  }

  // ── 原生界面：只有装了 dsh-go 桥接的主机才会进来 ────────────────────────────
  ctx.inject(['dshGo'], (bridgeCtx) => {
    // ⚠️ view / strip 的 dataFn 必须**同步**返回；用 async 包一层会把返回值变成 Promise
    //    （view 拿到 Promise → App 报「插件界面有误」；strip 拿到 Promise → 取消函数永远调不到）。
    const syncGuard = (label, fn) => (...args) => {
      try {
        return fn(...args)
      } catch (error) {
        console.error('[cap-ledger] ' + label + ' 失败：' + (error instanceof Error ? error.message : String(error)))
        return undefined
      }
    }
    const asyncGuard = (label, fn) => async (...args) => {
      try {
        return await fn(...args)
      } catch (error) {
        console.error('[cap-ledger] ' + label + ' 失败：' + (error instanceof Error ? error.message : String(error)))
        return null
      }
    }

    const watch = (push) => {
      push()
      listeners.add(push)
      return () => {
        listeners.delete(push)
      }
    }

    // 状态条：一眼看出有没有事。没变化不 emit（桥接本身限 4Hz / 32KiB）。
    bridgeCtx.dshGo.strip('ledger.strip', (scope, emit) => {
      let last = ''
      return watch(() => {
        const data = stripData({ ledger })
        const sig = JSON.stringify(data)
        if (sig === last) return
        last = sig
        emit(data)
      })
    })

    bridgeCtx.dshGo.panel('ledger', {
      view: syncGuard('ledger.view', () => ledgerPanel({ ledger, now: now() })),
      data: (scope, emit) => {
        let last = ''
        return watch(() => {
          const s = snapshot()
          const sig = JSON.stringify(s.ledger)
          if (sig === last) return
          last = sig
          emit(s)
        })
      },
      actions: {
        refresh: asyncGuard('ledger.refresh', (scope) => {
          bridgeCtx.dshGo.refresh('ledger', scope)
          return { toast: '已刷新' }
        }),
        copy: asyncGuard('ledger.copy', () => ({
          copy: JSON.stringify(normalizeLedger(ledger), null, 2),
          toast: '台账已复制',
        })),
      },
    })
  })

  return {
    ledger,
    api,
    snapshot,
    summarize: () => summarize(ledger),
    describe: () => describeEntry(entryOf(ledger, SELF)),
    save: saveNow,
    ledgerPath,
  }
}

export { SELF as SELF_PLUGIN, emptyEntry, describeEntry }
