// 大肥鱼本地记忆 · 宿主半边
//
// 一句话：手机上的 SQLite（没有 node:sqlite 时退到 JSON）是唯一权威。
// 笔记软件、PC 桥、SurfCore 都只是镜像，读写都从这里走。
//
// 做四件事：
//   1. 存 —— remember / memory_correct / memory_forget，一条一件事，带范围、重要度、钉住、过期。
//   2. 取 —— 每轮按当前这句话检索，把相关的几条拼进上下文（systemPrompt.context，
//      放对话末尾，不破坏系统提示词的前缀缓存）。
//   3. 话题 —— 还没聊完的事单独存，带状态和「下次再问」，主动回合从这里拿。
//   4. 看 —— 原生面板：搜索、点开、钉住、纠正、忘掉、话题状态、导出 Markdown。

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { openStore } from './store.js'
import { importFishMemory } from './migrate.js'
import { memoryView } from './view.js'
import { extractAutoFacts } from './auto_facts.js'
import { SHARED_DIALOG_SEEN, appendDialog, dialogLines, readDialog, trimDialog, userTextOf } from './dialog.js'
import {
  correct, exportMarkdown, forget, listTopics, markTopicAsked, dateOf, maintain, pickTopicToAsk,
  publicFact, publicTopic, recall, remember, resolveTopic, normalizeScope, scopeOfSession, search,
  startOfDay, stats, timeOf, upsertTopic,
} from './model.js'

export const name = 'dsh-go-memory'
export const inject = ['tools', 'agents', 'timer']

const OUT = {
  type: 'object',
  additionalProperties: true,
  properties: { ok: { type: 'boolean' }, text: { type: 'string' } },
}

// 普通 ctx.tools.register 要求每个工具都带 output；没有它引擎会整条拒绝。
const TOOL_OUTPUT = {
  schema: OUT,
  render: (_args, value) => [{ type: 'text', text: String((value && value.text) || '') }],
}

function home() {
  return process.env.DSH_HOME || path.join(os.homedir(), '.dsh')
}

function sessionIdOf(agent) {
  try {
    const sid = agent && agent.session && (agent.session.id || agent.session.sessionId)
    return typeof sid === 'string' ? sid : ''
  } catch {
    return ''
  }
}


function writeText(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 })
  const tmp = file + '.' + String(process.pid) + '.tmp'
  fs.writeFileSync(tmp, text, { encoding: 'utf8', mode: 0o600 })
  fs.renameSync(tmp, file)
}

export function apply(ctx) {
  let store = null
  let storeError = ''
  function db() {
    if (store) return store
    if (storeError) return null
    try {
      store = openStore({ home: home() })
      importOnce(store)
      return store
    } catch (error) {
      storeError = String((error && error.message) || error)
      console.error('[memory] 本地库打不开：' + storeError)
      return null
    }
  }

  /**
   * 第一次打开时把旧的 dsh-fish-memory 库搬进来。
   * 只搬一次：成功才写标记，失败下次还会再试。
   */
  function importOnce(s) {
    const marker = path.join(s.dir, '.imported-fish-memory')
    try {
      if (fs.existsSync(marker)) return
      const r = importFishMemory(s.doc, { home: home() })
      if (r.added > 0) s.save()
      if (r.failed === 0) {
        fs.mkdirSync(s.dir, { recursive: true, mode: 0o700 })
        fs.writeFileSync(marker, new Date().toISOString() + '\n', { encoding: 'utf8', mode: 0o600 })
      }
      if (r.added || r.skipped || r.failed) {
        console.error('[memory] 迁移旧记忆：新增 ' + String(r.added) + '，跳过 ' + String(r.skipped) + '，失败 ' + String(r.failed))
      }
    } catch (error) {
      console.error('[memory] 迁移旧记忆失败：' + String((error && error.message) || error))
    }
  }

  function persist() {
    const s = db()
    if (!s) return false
    maintain(s.doc)
    return s.save()
  }

  const disposers = []
  const lastText = new Map()   // sid -> 这一轮用户刚说的话（检索线索）
  const lastPrevText = new Map() // sid -> 上一句用户说过的话（短回复时拼进检索，防失忆）
  const dialogSeen = SHARED_DIALOG_SEEN // 跨插件共享去重窗口（防 memory 与 companion 双写）
  let dialogTrimmedAt = 0
  const mounted = new Map()    // sid -> { agent, offs }

  /** 读 companion/config.json 拿双姐妹与主会话 id，让鱼丸、鲸澜与主聊天天然互见各自记忆。 */
  function readCompanionSessionIds() {
    try {
      const cfgPath = path.join(home(), 'companion', 'config.json')
      if (!fs.existsSync(cfgPath)) return []
      const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'))
      const out = []
      if (typeof cfg?.sessionId === 'string' && cfg.sessionId) out.push(scopeOfSession(cfg.sessionId))
      if (typeof cfg?.strategistSessionId === 'string' && cfg.strategistSessionId) out.push(scopeOfSession(cfg.strategistSessionId))
      return out
    } catch {
      return []
    }
  }

  function sharedScopesFor(sid) {
    const set = new Set([scopeOfSession(sid), 'global', ...readCompanionSessionIds()])
    for (const activeSid of mounted.keys()) {
      if (activeSid) set.add(scopeOfSession(activeSid))
    }
    return [...set]
  }

  // ── 工具 ────────────────────────────────────────────────────────────────
  function scopeFor(sid, args = {}) {
    if (args.share === false) return scopeOfSession(sid)
    if (typeof args.scope === 'string' && args.scope.startsWith('workspace:')) return normalizeScope(args.scope)
    if (args.scope === 'session') return scopeOfSession(sid)
    // 默认跨会话共享入 global（解决双姐妹各自会话失忆）
    return 'global'
  }

  function makeTools(sid) {
    return [
      {
        name: 'remember',
        description:
          '把一件值得长期记住的事写进手机本地记忆（以后每轮都可能想起）。' +
          '一条只装一件事：「她身高一米七、侧睡」要分两条。该记的：他说的重要的话、约定、喜好、身体和情绪状态、' +
          '正在烦的事和进展、你们之间发生的事。生活小事（吃了什么、看了什么）以后可能拿来聊的也记，kind 写「小事」、重要度 0。' +
          '不要记你自己做过的事、不要重复记；他原话会自动留底，这里只记提炼过的结论。' +
          '会影响以后每一次对话的（称呼、忌讳、承诺、正在准备的大事）给 importance 2 或钉住，它们每轮都会带上。' +
          '记的时候安静地记，回复里不要说「记下了」「我记住了」。' +
          '同一件会变的事（身高、工作、称呼）请给同一个 key，旧值会自动作废但保留可回溯。',
        parameters: {
          type: 'object',
          properties: {
            text: { type: 'string', description: '一句话说清，最多 500 字。人称用「我」指她自己、用她对用户的称呼。' },
            kind: { type: 'string', description: 'fact 事实 / preference 偏好 / promise 承诺 / event 共同经历；也可以直接写「小事」「偏好」「承诺」。' },
            importance: { type: 'integer', description: '0 琐事、1 普通（默认）、2 重要到不能忘。' },
            key: { type: 'string', description: '同一件会变的事用同一个键，如「她.身高」「约定.复盘」。键里不要写具体值。' },
            tags: { type: 'array', items: { type: 'string' }, description: '几个关键词，方便以后检索。' },
            pinned: { type: 'boolean', description: '钉住：永不淡忘（称呼忌讳、承诺、身体禁忌才用）。' },
            triggers: { type: 'array', items: { type: 'string' }, description: '可选，Lorebook 触发词或 /regex/flags，命中对话时按需召回。' },
            sceneTags: { type: 'array', items: { type: 'string' }, description: '可选，场景标签，如 scene:video_learning / scene:commute。' },
            share: { type: 'boolean', description: 'true = 所有会话可见（默认 true 共享，传 false 才仅限本会话）。' },
            expiresInDays: { type: 'integer', description: '可选，多少天后自动过期（临时状态用）。' },
            replaces: { type: 'string', description: '可选，指认被更新的旧条目（id 或原文片段）。' },
            force: { type: 'boolean', description: '可选，用户明确要求重新记一条此前让忘掉的原话时用。' },
          },
          additionalProperties: false,
        },
        output: TOOL_OUTPUT,
        execute: (args = {}) => {
          const s = db()
          if (!s) return { ok: false, text: '本地记忆库不可用：' + (storeError || '未知错误') }
          const now = Date.now()
          const expiresAt = Number(args.expiresInDays) > 0 ? now + Math.min(3650, Number(args.expiresInDays)) * 86_400_000 : 0
          const result = remember(s.doc, {
            text: args.text,
            kind: args.kind,
            importance: args.importance,
            key: args.key,
            tags: args.tags,
            triggers: args.triggers,
            sceneTags: args.sceneTags,
            pinned: args.pinned,
            replaces: args.replaces,
            force: args.force,
            expiresAt,
            scope: scopeFor(sid, args),
            source: sid ? 'session' : 'tool',
          }, now)
          if (result.ok) persist()
          return result
        },
      },
      {
        name: 'memory_search',
        description:
          '在手机本地记忆里找相关条目。默认只找本会话 + 全局；scope=all 才跨所有会话。' +
          '用户问「你还记得…」「我们之前说过…」时先用它查，不要凭印象编。',
        parameters: {
          type: 'object',
          properties: {
            query: { type: 'string', description: '关键词或原话片段。' },
            limit: { type: 'integer', description: '最多几条，默认 10，上限 50。' },
            kind: { type: 'string', enum: ['fact', 'preference', 'promise', 'event'], description: '只看某一类。' },
            scope: { type: 'string', description: 'session（默认，本会话+全局）、global、all。' },
          },
          additionalProperties: false,
        },
        output: TOOL_OUTPUT,
        execute: (args = {}) => {
          const s = db()
          if (!s) return { ok: false, text: '本地记忆库不可用：' + (storeError || '未知错误') }
          const scope = args.scope === 'all' ? 'all' : args.scope === 'global' ? 'global' : scopeFor(sid)
          return search(s.doc, { query: args.query, limit: args.limit, kind: args.kind, scope }, Date.now())
        },
      },
      {
        name: 'memory_correct',
        description:
          '纠正一条记错的记忆。旧版本不会被覆盖，会标成「已替换」留着回溯。' +
          '用户说「不是这样」「你记错了」「现在改成…」时用它，别只口头道歉。',
        parameters: {
          type: 'object',
          properties: {
            id: { type: 'string', description: '要纠正的条目 id（memory_search 或 remember 返回的）。' },
            text: { type: 'string', description: '纠正后的内容；只改重要度/钉住时可省略。' },
            importance: { type: 'integer', description: '0/1/2。' },
            pinned: { type: 'boolean', description: '是否钉住。' },
          },
          additionalProperties: false,
        },
        output: TOOL_OUTPUT,
        execute: (args = {}) => {
          const s = db()
          if (!s) return { ok: false, text: '本地记忆库不可用：' + (storeError || '未知错误') }
          const result = correct(s.doc, args, Date.now())
          if (result.ok) persist()
          return result
        },
      },
      {
        name: 'memory_forget',
        description:
          '删掉一条记忆（用户明确说「忘掉这个」「别记这个」时）。' +
          '会留下 tombstone，同一句话以后不会被悄悄记回来；要重新记得用 remember 带 force。',
        parameters: {
          type: 'object',
          properties: {
            id: { type: 'string', description: '条目 id。' },
            match: { type: 'string', description: '没有 id 时用原文片段匹配。' },
            reason: { type: 'string', description: '原因（可选）。' },
          },
          additionalProperties: false,
        },
        output: TOOL_OUTPUT,
        execute: (args = {}) => {
          const s = db()
          if (!s) return { ok: false, text: '本地记忆库不可用：' + (storeError || '未知错误') }
          const result = forget(s.doc, args, Date.now())
          if (result.ok) persist()
          return result
        },
      },
      {
        name: 'memory_topic',
        description:
          '管「还没聊完的话题」：open 记一个、list 看、done 完成、defer 先搁着、drop 放掉。' +
          '话题和事实分开存，主动回合靠它判断该不该问。完成话题只能由用户确认或明确结果，不能自己猜。',
        parameters: {
          type: 'object',
          properties: {
            action: { type: 'string', enum: ['open', 'list', 'done', 'defer', 'drop'], description: '要做的动作。' },
            id: { type: 'string', description: '话题 id（完成/搁置/放掉时用）。' },
            title: { type: 'string', description: '话题标题；没有 id 时也按标题匹配。' },
            detail: { type: 'string', description: '补充说明。' },
            priority: { type: 'integer', description: '0/1/2，越高越先问。' },
            reason: { type: 'string', description: '关闭原因（可选）。' },
          },
          additionalProperties: false,
        },
        output: TOOL_OUTPUT,
        execute: (args = {}) => {
          const s = db()
          if (!s) return { ok: false, text: '本地记忆库不可用：' + (storeError || '未知错误') }
          const now = Date.now()
          const action = String(args.action || 'list')
          let result
          if (action === 'list') {
            result = listTopics(s.doc, { scope: scopeFor(sid), status: 'open' }, now)
          } else if (action === 'open') {
            result = upsertTopic(s.doc, { title: args.title, detail: args.detail, priority: args.priority, scope: scopeFor(sid) }, now)
          } else {
            result = resolveTopic(s.doc, { id: args.id, title: args.title, status: action, reason: args.reason }, now)
          }
          if (result.ok) persist()
          return result
        },
      },
    ]
  }

  function registerTools(tools, sid, tag) {
    if (!tools || typeof tools.register !== 'function') return 0
    let n = 0
    for (const def of makeTools(sid)) {
      try {
        const off = tools.register(def)
        if (typeof off === 'function') disposers.push(off)
        n += 1
      } catch (error) {
        console.error('[memory] 工具注册失败（' + tag + '）：' + (error && error.message))
      }
    }
    return n
  }

  const globalTools = registerTools(ctx && ctx.tools, '', 'global')
  console.error('[memory] registered ' + String(globalTools) + ' tools (global)')

  // ── 每个会话：检索用哪句话 + 注入 + 工具 ────────────────────────────────
  function attach(agent, sid) {
    const offs = []
    try {
      const off = agent.ctx.on('agent/inbox/inserted', (payload) => {
        // 只认他本人说的：主动发球的提示也是以 user 角色塞进会话的，以前它被当成检索线索，
        // 整段「这不是他说的话……」去对记忆，几乎什么都对不上。
        const txt = userTextOf(payload)
        if (!txt) return
        const prev = lastText.get(sid) || ''
        if (prev && prev !== txt && prev.length >= 6) lastPrevText.set(sid, prev)
        lastText.set(sid, txt)
        const s = db()
        if (!s) return
        try {
          appendDialog(s.dir, { sid, text: txt, at: Date.now() }, dialogSeen)
          const autoItems = extractAutoFacts(txt)
          if (autoItems.length) {
            let changed = false
            const now = Date.now()
            for (const item of autoItems) {
              const r = remember(s.doc, { ...item, scope: 'global', source: 'auto-extract' }, now)
              if (r.ok && !r.duplicate) changed = true
            }
            if (changed) persist()
          }
          if (Date.now() - dialogTrimmedAt > 6 * 3_600_000) {
            dialogTrimmedAt = Date.now()
            trimDialog(s.dir)
          }
        } catch (error) {
          console.error('[memory] 原话留底失败：' + (error && error.message))
        }
      })
      if (typeof off === 'function') offs.push(off)
    } catch {}

    try {
      const off = agent.ctx.systemPrompt.context({
        name: 'fish-memory',
        order: 900,
        interpolate: false,
        text: () => recallText(sid),
      })
      if (typeof off === 'function') offs.push(off)
    } catch (error) {
      console.error('[memory] 注入挂不上：' + (error && error.message))
    }

    try {
      if (agent.ctx.tools && typeof agent.ctx.tools.register === 'function') {
        for (const def of makeTools(sid)) {
          try {
            const off = agent.ctx.tools.register(def)
            if (typeof off === 'function') offs.push(off)
          } catch {}
        }
      }
    } catch {}
    return { agent, offs }
  }

  const recallCache = new Map() // sid -> { at, query, text, hits }

  function recallText(sid) {
    const s = db()
    if (!s) return ''
    try {
      const cur = lastText.get(sid) || ''
      const prev = cur.length <= 8 ? (lastPrevText.get(sid) || '') : ''
      const query = prev ? (cur ? cur + ' ' + prev : prev) : cur
      const cached = recallCache.get(sid)
      // 同一次请求里 text() 会被调好几遍（宿主在装配阶段会重复求值）：
      // 1.5 秒内且线索没变就复用，避免重复检索和重复落盘。
      if (cached && cached.query === query && Date.now() - cached.at < 1500) return cached.text
      const scopes = sharedScopesFor(sid)
      const ftsHits = query ? s.ftsSearch(query, { scopes, limit: 12 }) : []
      const now = Date.now()
      let dialog = []
      try {
        dialog = dialogLines(readDialog(s.dir), { query, now })
      } catch {}
      // 随机想起一条旧事：每 20 分钟换一条，同一段时间里前缀稳定，不会每轮都换话头。
      const slot = Math.floor(now / (20 * 60_000))
      const random = ((slot * 2654435761) >>> 0) % 1000 / 1000
      const picked = recall(s.doc, {
        scope: scopes,
        query,
        limit: 8,
        ftsIds: ftsHits.map((h) => h.id),
        dialog,
        random,
      }, now)
      // 召回命中会累加 hits —— 只在真的有内容时顺手落盘，避免每轮空写。
      if (picked.facts.length || picked.lorebook?.length || picked.core?.length) s.save()
      recallCache.set(sid, { at: Date.now(), query, text: picked.text })
      return picked.text
    } catch (error) {
      console.error('[memory] 检索失败：' + (error && error.message))
      return ''
    }
  }

  function unmount(v) {
    if (!v) return
    for (const off of v.offs.reverse()) {
      try { off() } catch {}
    }
  }

  function tick() {
    const s = db()
    if (s && maintain(s.doc)) s.save()
    const agents = ctx && ctx.agents && typeof ctx.agents.list === 'function' ? ctx.agents.list() || [] : []
    const seen = new Set()
    for (const agent of agents) {
      const sid = sessionIdOf(agent)
      if (!sid) continue
      seen.add(sid)
      const cur = mounted.get(sid)
      if (cur && cur.agent === agent) continue
      if (cur) {
        unmount(cur)
        mounted.delete(sid)
      }
      mounted.set(sid, attach(agent, sid))
    }
    for (const [sid, v] of mounted) {
      if (seen.has(sid)) continue
      unmount(v)
      mounted.delete(sid)
    }
  }

  try {
    const stop = ctx.interval(tick, 2_000)
    if (typeof stop === 'function') disposers.push(stop)
  } catch (error) {
    console.error('[memory] 没有定时器，会话挂载会延迟：' + (error && error.message))
  }
  tick()

  // ── 原生界面 ────────────────────────────────────────────────────────────
  try {
    if (typeof ctx.inject === 'function') {
      ctx.inject(['dshGo'], (dctx) => {
        const d = dctx.dshGo
        const opened = new Map()      // 面板当前点开的条目
        const openedTopic = new Map() // 面板当前选中的话题
        const queries = new Map()     // 每个会话上次搜的词
        const keyOf = (scope) => String((scope && scope.sessionId) || '')

        function scopeOfPanel(scope) {
          return scopeOfSession(keyOf(scope))
        }

        function snapshot(scope) {
          const s = db()
          const key = keyOf(scope)
          const paneScope = scopeOfPanel(scope)
          if (!s) {
            return {
              summary: '本地记忆库打不开：' + (storeError || '未知错误'),
              counts: { facts: 0, pinned: 0, topics: 0, today: 0 },
              listTitle: '',
              items: [],
              topics: [],
              detail: { id: '', text: '（不可用）', created: '—', importance: '—', scope: '—', clarity: '—' },
              form: { search: '', text: '', importance: '1', pinned: false, share: false, topic: '', correct: '' },
              backendNote: '检查 DSH_HOME 与存储权限。',
              backend: 'none',
            }
          }
          const now = Date.now()
          const counts = stats(s.doc, { scope: paneScope }, now)
          const query = queries.get(key) || ''
          const found = search(s.doc, { query, scope: paneScope, limit: 20 }, now)
          const id = opened.get(key) || ''
          const fact = id ? s.doc.facts.find((f) => f.id === id) : null
          const topics = listTopics(s.doc, { scope: paneScope, status: 'open' }, now).items
          const rows = found.items.map((f) => ({
            title: f.text.slice(0, 44),
            subtitle: [
              f.pinned ? '钉住' : '',
              f.importance === 2 ? '重要' : '',
              f.scope === 'global' ? '全局' : '本会话',
              f.createdAt ? dateOf(f.createdAt) : '',
            ].filter(Boolean).join(' · '),
            trailing: String(f.clarity),
            icon: f.pinned ? 'pin' : f.kind === 'promise' ? 'check' : 'book',
            tone: f.importance === 2 ? 'warn' : undefined,
            action: { id: 'memory.row', args: { id: f.id } },
          }))
          const topicRows = topics.map((t) => ({
            title: t.title.slice(0, 44),
            subtitle: (t.detail ? t.detail.slice(0, 40) + ' · ' : '') + (t.priority === 2 ? '优先' : '普通'),
            trailing: t.openedAt ? dateOf(t.openedAt) : '',
            icon: 'todo',
            action: { id: 'topic.row', args: { id: t.id } },
          }))
          const pickedTopic = openedTopic.get(key) || ''
          return {
            summary: query ? '搜「' + query + '」：' + String(found.total) + ' 条' : '最近 ' + String(counts.facts) + ' 条有效记忆',
            counts,
            listTitle: query ? '搜索结果' : '最近记住的',
            items: rows,
            topics: topicRows,
            detail: fact ? {
              id: fact.id,
              text: fact.text,
              created: dateOf(fact.createdAt) + ' ' + timeOf(fact.createdAt),
              importance: String(fact.importance),
              scope: fact.scope === 'global' ? '全局' : '本会话',
              clarity: String(Math.round(Number(publicFact(fact).clarity) * 100)) + '%',
            } : {
              id: '',
              text: '（点下面任意一条查看、钉住、纠正或忘掉）',
              created: '—',
              importance: '—',
              scope: pickedTopic ? '话题已选中' : '—',
              clarity: '—',
            },
            form: {
              search: query,
              text: '',
              importance: '1',
              pinned: false,
              share: false,
              topic: '',
              correct: fact ? fact.text : '',
              correctImportance: fact ? String(fact.importance) : '1',
            },
            backendNote: s.kind === 'sqlite'
              ? '权威库：手机本地 SQLite（' + s.dir + '）。关掉 App 也还在；笔记软件只是镜像。'
              : '正在用 JSON 兜底（' + s.dir + '）：' + (s.note || '这个运行时没有 node:sqlite') + '。',
            backend: s.kind,
          }
        }

        function formValues(event) {
          const v = event && event.value ? event.value.values || event.value : {}
          return v && typeof v === 'object' ? v : {}
        }

        function refresh(scope, dctx2) {
          try { d.refresh('memory', scope) } catch {}
          return { toast: '' }
        }

        // 只留一个输入框按钮，不再抢状态条：多个插件的状态条每 6 秒轮播，看起来像在闪。
        d.on('memory-open', () => ({ open: 'panel:memory' }))

        d.panel('memory', {
          view: memoryView,
          data: (scope, emit) => {
            // 只在面板订阅时推一次；写入/动作后由 refresh 触发。定时全量重绘会让整页闪。
            emit(snapshot(scope))
            return () => {}
          },
          actions: {
            search: (scope, event) => {
              const values = formValues(event)
              queries.set(keyOf(scope), String(values['data.form.search'] || '').trim())
              opened.delete(keyOf(scope))
              return refresh(scope)
            },
            'memory.row': (scope, event) => {
              const v = event && event.value ? event.value : {}
              if (v.id) opened.set(keyOf(scope), String(v.id))
              return refresh(scope)
            },
            'memory.back': (scope) => {
              opened.delete(keyOf(scope))
              return refresh(scope)
            },
            'memory.pin': (scope) => {
              const s = db()
              const id = opened.get(keyOf(scope))
              if (!s || !id) return { toast: '先点开一条' }
              const f = s.doc.facts.find((x) => x.id === id && x.status === 'active')
              if (!f) return { toast: '这条已经不在了' }
              f.pinned = !f.pinned
              f.updatedAt = Date.now()
              persist()
              d.refresh('memory', scope)
              return { toast: f.pinned ? '钉住了' : '取消钉住' }
            },
            'memory.forget': (scope) => {
              const s = db()
              const id = opened.get(keyOf(scope))
              if (!s || !id) return { toast: '先点开一条' }
              const result = forget(s.doc, { id, reason: 'panel' }, Date.now())
              if (result.ok) {
                persist()
                opened.delete(keyOf(scope))
                d.refresh('memory', scope)
              }
              return { toast: result.text }
            },
            'memory.correct': (scope, event) => {
              const s = db()
              const id = opened.get(keyOf(scope))
              if (!s || !id) return { toast: '先点开一条' }
              const values = formValues(event)
              const text = String(values['data.form.correct'] || '').trim()
              const imp = values['data.form.correctImportance']
              const result = correct(s.doc, {
                id,
                text: text || undefined,
                importance: imp === undefined ? undefined : Number(imp),
              }, Date.now())
              if (result.ok) {
                persist()
                opened.set(keyOf(scope), result.fact.id)
                d.refresh('memory', scope)
              }
              return { toast: result.text }
            },
            remember: (scope, event) => {
              const s = db()
              if (!s) return { toast: '本地记忆库不可用' }
              const values = formValues(event)
              const result = remember(s.doc, {
                text: values['data.form.text'],
                importance: Number(values['data.form.importance'] || 1),
                pinned: values['data.form.pinned'] === true,
                scope: values['data.form.share'] === true ? 'global' : scopeOfPanel(scope),
                source: 'panel',
              }, Date.now())
              if (result.ok) {
                persist()
                d.refresh('memory', scope)
              }
              return { toast: result.text }
            },
            'topic.open': (scope, event) => {
              const s = db()
              if (!s) return { toast: '本地记忆库不可用' }
              const values = formValues(event)
              const result = upsertTopic(s.doc, {
                title: values['data.form.topic'],
                scope: scopeOfPanel(scope),
              }, Date.now())
              if (result.ok) {
                persist()
                d.refresh('memory', scope)
              }
              return { toast: result.text }
            },
            'topic.row': (scope, event) => {
              const v = event && event.value ? event.value : {}
              if (v.id) openedTopic.set(keyOf(scope), String(v.id))
              return { toast: '已选中话题，点完成/搁置/放掉' }
            },
            'topic.done': (scope) => closeTopic(scope, 'done'),
            'topic.defer': (scope) => closeTopic(scope, 'deferred'),
            'topic.drop': (scope) => closeTopic(scope, 'dropped'),
            'memory.refresh': (scope) => refresh(scope),
            'memory.export': (scope) => {
              const s = db()
              if (!s) return { toast: '本地记忆库不可用' }
              try {
                const now = Date.now()
                const file = path.join(s.dir, 'export', 'memory-' + dateOf(now) + '-' + timeOf(now).replace(':', '') + '.md')
                writeText(file, exportMarkdown(s.doc, now))
                return { toast: '已导出到 ' + file }
              } catch (error) {
                return { toast: '导出失败：' + String((error && error.message) || error) }
              }
            },
          },
        })

        function closeTopic(scope, status) {
          const s = db()
          const id = openedTopic.get(keyOf(scope))
          if (!s || !id) return { toast: '先点一个话题' }
          const result = resolveTopic(s.doc, { id, status, reason: 'panel' }, Date.now())
          if (result.ok) {
            persist()
            openedTopic.delete(keyOf(scope))
            d.refresh('memory', scope)
          }
          return { toast: result.text }
        }
      })
    }
  } catch (error) {
    console.error('[memory] 原生面板注册失败：' + (error && error.message))
  }

  return () => {
    for (const [sid, v] of mounted) {
      unmount(v)
      mounted.delete(sid)
    }
    for (const off of disposers.reverse()) {
      try { off() } catch {}
    }
    if (store) {
      try { store.close() } catch {}
      store = null
    }
  }
}

// 给别的插件（主动回合）用：只读地看一眼「现在最该问的话题」。
export function nextTopic(ctx, sid, options = {}) {
  try {
    const store = openStore({ home: home() })
    const topic = pickTopicToAsk(store.doc, { scope: scopeOfSession(sid), ...options }, Date.now())
    store.close()
    return topic
  } catch {
    return null
  }
}

export { markTopicAsked, publicTopic, startOfDay }
