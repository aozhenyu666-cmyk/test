/**
 * dsh-go-partner —— R4 的人格 / 记忆 / 情绪层（原生界面 + 真实数据 + 工具注册）
 *
 * 定位（守住，否则会和两个上游插件打架）：
 *   · dsh-fish-memory 负责「记」和「想」，dsh-feiyu-mood 负责「判」；
 *   · 本插件**读**它们的文件，**只写自己**的（~/.dsh/partner/*.json），
 *     一个写者原则：不替上游改 state.json / session-*.json。
 *   · 界面之外还做三件事：注册记忆/情绪工具、把人格与心情作为上下文交给 Agent、
 *     把「用户能查看和纠正记忆」做成真实动作。
 *
 * 三个模块独立开关（~/.dsh/partner/config.json）：
 *   persona / memory / mood —— 任何一个关掉，它那一块的**数据读取、系统提示、工具**全停，
 *   面板改成一句「已在配置里关掉」，而不是静默空白。
 *
 * 三条照 docs/plugins/slots.md 的硬约束：
 *   1. `ctx.inject(['dshGo'], …)` —— 写 `['dshGo']`，不要 `['dshGo?']`，也别放插件顶层 inject。
 *   2. 状态条 emit ≤ 32KiB / 4Hz，每次给**完整**一份，`null` 等于缺失。
 *   3. 回调抛错/超时(3s)/返回不合格 → 记一次违规；连续 5 次本次运行降级成网页版。
 *
 * 隐私边界：只读下文列出的那几个 JSON。**不读屏幕、窗口标题、剪贴板。**
 */

import fs from 'node:fs'
import path from 'node:path'

import { loadConfig } from './config.mjs'
import { loadPersona, personaPromptText, roleState } from './persona.mjs'
import { loadLongTermFull, memoryStats, correct, loadCorrections } from './memory.mjs'
import { readMood, moodBand, moodContextText } from './mood.mjs'
import { buildTools } from './tools.mjs'
import {
  moodStatePanel,
  memorySectionsPanel,
  memoryRowPanel,
  personaPanel,
  purposePanel,
} from './views.mjs'

/**
 * 原生设置表单 (硬化轮): 从 `export const Config = undefined` 改成真 schema。
 *
 * 上一轮的注释说「本包坚持零第三方依赖，画不出原生设置表单」—— 因果说反了。
 * DSH Go 宿主**本来就带** `@deepseek-ai/schemastery`（3.18.5）和完整的 schema 表单引擎：
 *   app/lib/ui/engine_settings/schema_form.dart —— "schemaSections lays out any plugin's namespace by itself"
 *   app/lib/ui/plugins/config/plugin_config_page.dart —— 第三方插件的配置页
 *   app/lib/state/plugin_config/plugin_config_bridge.dart —— GET/POST /dsh-go/v1/plugin-config
 * 真正挡住表单的是这里导出了 `undefined`：宿主拿不到 schema，自然画不出。
 *
 * 拿不到 schemastery 时**仍然照常加载**（跟 dsh-bili-subagent 同一套取舍：宁可少一个设置页，
 * 也不能因为一个 import 让整条插件不激活），所以这时是 undefined 而不是抛错。
 *
 * 每个字段都要 .extra('volatile', true)：宿主设置层的 settings.update() 只认 volatile 字段
 * （dsh-settings 的 volatileForm(schema)），没有这个标记写设置会被拒。
 */
export const Config = (() => {
  const z = globalThis.__dshSchemastery && typeof globalThis.__dshSchemastery.object === 'function'
    ? globalThis.__dshSchemastery
    : null
  if (!z) return undefined
  return z.object({
    persona: z.object({
      enabled: z.boolean().default(true).extra('volatile', true),
      personaFile: z.string().default('').extra('volatile', true),
      style: z.string().default('').extra('volatile', true),
      topics: z.array(z.string()).default([]).extra('volatile', true),
      identityFile: z.string().default('').extra('volatile', true),
    }),
    memory: z.object({
      enabled: z.boolean().default(true).extra('volatile', true),
      shortTermHours: z.natural().default(24).extra('volatile', true),
      recallLimit: z.natural().default(6).extra('volatile', true),
      recallFloor: z.number().default(0.12).extra('volatile', true),
      panelLimit: z.natural().default(40).extra('volatile', true),
    }),
    mood: z.object({
      enabled: z.boolean().default(true).extra('volatile', true),
      inject: z.boolean().default(true).extra('volatile', true),
      timelineLimit: z.natural().default(8).extra('volatile', true),
    }),
    tools: z.object({
      enabled: z.boolean().default(true).extra('volatile', true),
    }),
    hint: z.object({
      enabled: z.boolean().default(true).extra('volatile', true),
    }),
  })
})()

export { loadConfig } from './config.mjs'

/** 同步/异步两版异常保护：一个返回视图，一个返回取消函数，混用会把返回值变成 Promise。 */
function guards(prefix) {
  const sync = (name, fn) => (...args) => {
    try {
      return fn(...args)
    } catch (error) {
      console.error('[' + prefix + '] ' + name + ' 失败：' + (error && error.message))
      return undefined
    }
  }
  const async = (name, fn) => async (...args) => {
    try {
      return await fn(...args)
    } catch (error) {
      console.error('[' + prefix + '] ' + name + ' 失败：' + (error && error.message))
      return null
    }
  }
  return { sync, async }
}

const DISABLED = (what) => ({
  v: 1,
  type: 'stack',
  title: what + ' · 已关闭',
  children: [
    { type: 'callout', tone: 'info', text: '这个模块已经在 partner 的配置里关掉了，所以不读任何数据、也不参与对话。' },
    { type: 'text', text: '要打开：编辑 ~/.dsh/partner/config.json，把 ' + what + ' 那一项的 enabled 改成 true，然后回来刷新。', tone: 'muted' },
  ],
})

/**
 * 注册四个工具。单独导出是为了让测试能直接注入 defineTool —— 不必去解析真包名。
 * @returns {Promise<{registered: string[], reason: string}>}
 */
export async function registerTools(ctx, cfg, defineToolIn) {
  if (!ctx || !ctx.tools || typeof ctx.tools.register !== 'function') {
    return { registered: [], reason: 'no-tools-service' }
  }
  let defineTool = defineToolIn
  if (typeof defineTool !== 'function') {
    const mod = await import('@deepseek-ai/dsh-tools')
    defineTool = mod.defineTool
  }
  if (typeof defineTool !== 'function') return { registered: [], reason: 'no-defineTool' }
  const registered = []
  const disposers = []
  for (const def of buildTools(defineTool)) {
    const off = ctx.tools.register(def)
    registered.push(def.name)
    if (off !== undefined) disposers.push(off)
  }
  return { registered, reason: '', disposers }
}
/** 找到当前 agent（只为挂系统提示用；拿不到就只做插件级，不影响界面）。 */
function anyAgent(ctx) {
  try {
    if (ctx.agents && typeof ctx.agents.list === 'function') {
      const list = ctx.agents.list() || []
      return list[0] || null
    }
  } catch {}
  return null
}

function agentOf(ctx, sessionId) {
  try {
    if (!ctx.agents) return null
    if (sessionId && typeof ctx.agents.get === 'function') {
      const a = ctx.agents.get(String(sessionId))
      if (a) return a
    }
  } catch {}
  return anyAgent(ctx)
}

/** Cordis 的 effect disposer 就是**函数**本身，不是 { off() }。这里统一兜住两种。 */
function disposeOf(off) {
  if (typeof off === 'function') {
    try { off() } catch {}
  } else if (off && typeof off.off === 'function') {
    try { off.off() } catch {}
  }
}

/** 系统提示 / 上下文里放文本时一律 interpolate:false —— 记忆内容里带 {{ }} 会让整轮失败。 */
function addSection(prompt, kind, entry) {
  try {
    if (kind === 'section') return prompt.section({ interpolate: false, ...entry })
    return prompt.context({ interpolate: false, ...entry })
  } catch (error) {
    console.error('[partner] 挂系统提示失败（' + entry.name + '）：' + (error && error.message))
    return null
  }
}

export function apply(ctx, options) {
  const cfg = loadConfig()
  // 测试/嵌入场景可以把自己那份 defineTool 传进来，省掉一次必然失败的动态 import。
  const defineToolOverride = options && options.defineTool
  const g = guards('partner')
  const offs = []

  // ── 工具注册（不依赖 dshGo 桥接：没有 App 的地方也照样能记/能查）──────
  // defineTool 在 @deepseek-ai/dsh-tools 里；没有 DSH 的主机上 import 会失败，
  // 所以**动态 import + 失败就记下来**，绝不让它把整个插件带崩。
  // 用一个 promise 接住：注册是异步的（要动态 import），但 apply 必须同步返回 disposer。
  // 卸载时如果注册还没回来，等它回来再卸 —— 否则就是泄漏一个还在跑的工具。
  const pendingToolDisposers = []
  let toolsDisposed = false
  const toolsReady = (cfg.tools.enabled && (cfg.memory.enabled || cfg.mood.enabled)
    ? registerTools(ctx, cfg, defineToolOverride)
    : Promise.resolve({ registered: [], reason: cfg.tools.enabled ? 'modules-off' : 'tools-off' })
  ).then((r) => {
    if (r && Array.isArray(r.disposers)) {
      if (toolsDisposed) for (const off of r.disposers) disposeOf(off)
      else pendingToolDisposers.push(...r.disposers)
    }
    return { registered: (r && r.registered) || [], reason: (r && r.reason) || '' }
  }, (error) => {
    console.error('[partner] 工具注册失败：' + (error && error.message))
    return { registered: [], reason: 'threw: ' + (error && error.message) }
  })
  // 暴露给测试/诊断：不 await 也不影响插件启动
  apply.__toolsReady = toolsReady
  // ── 系统提示：人格（section）+ 心情（context）────────────────────────
  const agent = agentOf(ctx)
  if (agent && agent.ctx) {
    const prompt = agent.ctx.systemPrompt
    if (prompt) {
      // 人格：进**系统提示**（它不每轮变，能吃到前缀缓存）
      if (cfg.persona.enabled) {
        const off = addSection(prompt, 'section', {
          name: 'partner:persona',
          order: Number(cfg.persona.sectionOrder) || 100,
          text: () => {
            try {
              const p = loadPersona(loadConfig())
              return p.ok || p.identity ? personaPromptText(p) : ''
            } catch {
              return ''
            }
          },
        })
        if (off) offs.push(off)
      }
      // 工具提示：不告诉它「你有这些工具」，它基本不会主动调 —— 这是四个工具能不能被用到的关键。
      // 排在**最后**（order 89990）：这段几乎不变，放后面能多吃缓存。
      // 永远注册、每轮现查开关（和心情那条一个规矩）：改配置立刻生效，不用重启。
      {
        const off = addSection(prompt, 'section', {
          name: 'partner:hint',
          order: Number(cfg.hint.sectionOrder) || 89990,
          text: () => {
            try {
              const c = loadConfig()
              if (!c.hint.enabled || !c.tools.enabled) return ''
              if (!c.memory.enabled && !c.mood.enabled) return ''
              const lines = []
              if (c.memory.enabled) {
                lines.push('【记忆】你不必等被提醒：值得长期留着的事（对方的稳定偏好、约定、重要经历）可以自己记下来，用 `partner_remember`。')
                lines.push('想不起某件事、或怕自己编造共同经历时，先用 `partner_recall` 查一遍 —— 查不到就是查不到，如实说，不要补一段更像样的回忆。')
                lines.push('对方说你记错了、或者要你别再提某件事，用 `partner_correct`（藏起来 / 改写），不要口头答应却什么都不改。')
              }
              if (c.mood.enabled) lines.push('【心情】你此刻的心情是真实状态，不是台词；被问到可以说，但不要每轮都汇报。用 `partner_mood` 才能看到具体的七维和最近的变化。')
              lines.push('这些工具都只读本机的几个 JSON 文件，不联网、不上传；也不会去读屏幕、窗口标题或剪贴板。')
              return lines.join('\n')
            } catch {
              return ''
            }
          },
        })
        if (off) offs.push(off)
      }
      // 心情：进**每轮上下文**（它会变，放 section 会把整段提示词缓存打掉）
      if (cfg.mood.enabled && cfg.mood.inject) {
        const off = addSection(prompt, 'context', {
          name: 'partner:mood',
          order: Number(cfg.mood.contextOrder) || 880,
          text: () => {
            try {
              const c = loadConfig()
              if (!c.mood.enabled || !c.mood.inject) return ''
              const snap = readMood()
              return snap.ok ? moodContextText(snap, c) : ''
            } catch {
              return ''
            }
          },
        })
        if (off) offs.push(off)
      }
    }
  }
  // ── 原生界面（需要 dshGo 桥接；没有桥接的主机这一段整个不执行）──────
  // ⚠️ 必须写 ['dshGo']；写成 ['dshGo?'] 回调永远不执行，写顶层 inject 会在无桥接主机上整体挂掉
  ctx.inject(['dshGo'], (c) => {
    // 每个会话当前点开的那条记忆（面板是无状态的，所以状态放这）
    const opened = new Map()
    const keyOf = (scope) => String((scope && scope.sessionId) || '')

    const readCfg = () => loadConfig()

    // ── 人格胶囊：输入框上方一行「大肥鱼 · 开心 72」──────────────
    // 插槽 id 不能和面板 id 撞（同插件内所有插槽 id 唯一），所以叫 persona.strip
    c.dshGo.strip('persona.strip', (scope, emit) => {
      let last = ''
      const push = () => {
        const cfgNow = readCfg()
        let data
        if (!cfgNow.persona.enabled && !cfgNow.mood.enabled) {
          data = { text: '伙伴已关', icon: 'question', tone: 'muted' }
        } else {
          const p = cfgNow.persona.enabled ? loadPersona(cfgNow) : null
          const snap = cfgNow.mood.enabled ? readMood() : null
          const name = (p && p.names && p.names.selfName) || (snap && snap.ok && snap.names && snap.names.selfName) || ''
          const bits = []
          if (name) bits.push(name)
          if (snap && snap.ok) {
            const band = moodBand(snap.current.value)
            bits.push(band.label + ' ' + Math.round(snap.current.value))
            data = { text: bits.join(' · '), icon: band.icon, tone: band.tone }
          } else {
            bits.push(p && p.ok ? '人格就绪' : '没找到人格卡')
            data = { text: bits.join(' · '), icon: name ? 'pet' : 'question', tone: p && p.ok ? 'info' : 'muted' }
          }
        }
        const sig = JSON.stringify(data)
        if (sig === last) return
        last = sig
        emit(data)
      }
      push()
      const timer = setInterval(g.sync('strip.push', push), 30_000)
      return () => clearInterval(timer)
    })

    // ── 面板 1：人格详情 ────────────────────────────────────────
    c.dshGo.panel('persona', {
      view: g.sync('persona.view', (scope) => {
        const cfgNow = readCfg()
        if (!cfgNow.persona.enabled) return DISABLED('人格')
        const p = loadPersona(cfgNow)
        const lt = cfgNow.memory.enabled ? loadLongTermFull(cfgNow, scope && scope.sessionId) : { all: [], shortTerm: [], facts: [] }
        const stats = memoryStats(lt)
        const snap = cfgNow.mood.enabled ? readMood() : null
        const band = snap && snap.ok ? { ...moodBand(snap.current.value), value: snap.current.value } : null
        return personaPanel(p, roleState(stats, band))
      }),
      actions: {
        'persona.refresh': g.async('persona.refresh', (scope) => { c.dshGo.refresh('persona', scope); return { toast: '已刷新' } }),
        'persona.open': g.async('persona.open', () => ({ toast: '全文在 ~/.dsh/partner/persona.md' })),
      },
    })

    // ── 面板 2：记忆查看 / 纠正 ─────────────────────────────────
    c.dshGo.panel('memory', {
      view: g.sync('memory.view', (scope) => {
        const cfgNow = readCfg()
        if (!cfgNow.memory.enabled) return DISABLED('记忆')
        const lt = loadLongTermFull(cfgNow, scope && scope.sessionId)
        const id = opened.get(keyOf(scope))
        if (id) {
          const rec = lt.all.filter((r) => String(r.id) === id)[0]
          if (rec) return memoryRowPanel(rec, lt)
          opened.delete(keyOf(scope))
        }
        return memorySectionsPanel(lt, cfgNow)
      }),
      actions: {
        'memory.refresh': g.async('memory.refresh', (scope) => {
          opened.delete(keyOf(scope))
          c.dshGo.refresh('memory', scope)
          return { toast: '已刷新' }
        }),
        'memory.row': g.async('memory.row', (scope, event) => {
          const v = event && event.value ? event.value : {}
          if (v.id) opened.set(keyOf(scope), String(v.id))
          c.dshGo.refresh('memory', scope)
          return { toast: '' }
        }),
        'memory.hide': g.async('memory.hide', (scope, event) => {
          const id = opened.get(keyOf(scope))
          if (!id) return { toast: '先点开一条' }
          correct(id, 'hide')
          opened.delete(keyOf(scope))
          c.dshGo.refresh('memory', scope)
          return { toast: '藏起来了' }
        }),
        'memory.pin': g.async('memory.pin', (scope) => {
          const id = opened.get(keyOf(scope))
          if (!id) return { toast: '先点开一条' }
          correct(id, 'pin')
          c.dshGo.refresh('memory', scope)
          return { toast: '钉住了' }
        }),
        'memory.unpin': g.async('memory.unpin', (scope) => {
          const id = opened.get(keyOf(scope))
          if (!id) return { toast: '先点开一条' }
          correct(id, 'unpin')
          c.dshGo.refresh('memory', scope)
          return { toast: '取消钉住' }
        }),
        'memory.hidden': g.async('memory.hidden', (scope) => {
          const corr = loadCorrections()
          const n = corr.hide.length
          c.dshGo.refresh('memory', scope)
          return { toast: n ? '藏起了 ' + n + ' 条：' + corr.hide.slice(0, 2).join('、') : '没有藏起的记忆' }
        }),
        'memory.sources': g.async('memory.sources', (scope, event) => {
          const cfgNow = readCfg()
          const lt = loadLongTermFull(cfgNow, scope && scope.sessionId)
          const f1 = lt.primary && lt.primary.file ? lt.primary.file : '（没有 fish-memory 文件）'
          return { toast: ('来源：' + f1).slice(0, 40) }
        }),
      },
    })

    // ── 面板 3：情绪状态 ────────────────────────────────────────
    c.dshGo.panel('mood', {
      view: g.sync('mood.view', () => {
        const cfgNow = readCfg()
        if (!cfgNow.mood.enabled) return DISABLED('情绪')
        return moodStatePanel(readMood(), cfgNow)
      }),
      actions: {
        'mood.refresh': g.async('mood.refresh', (scope) => { c.dshGo.refresh('mood', scope); return { toast: '已刷新' } }),
        'mood.sources': g.async('mood.sources', () => ({ toast: 'state.json 在 ~/.dsh/feiyu-mood/' })),
      },
    })

    // ── 面板 4：目的（P2 未做，保留原来的「说还没做」）───────────
    c.dshGo.panel('purpose', {
      view: g.sync('purpose.view', () => {
        const root = path.join(process.env.DSH_HOME || '', 'surfcore')
        let state = null
        try {
          const cur = JSON.parse(fs.readFileSync(path.join(root, 'characters', 'current.json'), 'utf8'))
          if (cur && cur.id) state = JSON.parse(fs.readFileSync(path.join(cur.dir || path.join(root, 'characters', String(cur.id)), 'purpose-outreach.json'), 'utf8'))
        } catch {
          state = null
        }
        if (!state) {
          return {
            v: 1,
            type: 'stack',
            title: '目的',
            children: [
              { type: 'callout', tone: 'info', text: '还没做（P2）。做好之后，它会在安静时段之外、每天有限的次数里，自己找个由头来找你说话。' },
              { type: 'text', text: '在那之前，这里显示的是它的「念头」，不是定时问候。', tone: 'muted' },
            ],
          }
        }
        return purposePanel(state)
      }),
      actions: {
        'purpose.run': g.async('purpose.run', () => ({ toast: 'P2 才接；现在只能预览' })),
        'purpose.preview': g.async('purpose.preview', () => ({ toast: 'P2 才接' })),
      },
    })
  })

  // 卸载：把所有注册的东西还回去（Disposable 语义，漏一个就泄漏）
  const dispose = () => {
    toolsDisposed = true
    for (const off of pendingToolDisposers.splice(0)) disposeOf(off)
    for (const off of offs.splice(0)) disposeOf(off)
  }
  dispose.__toolsReady = toolsReady
  return dispose
}
