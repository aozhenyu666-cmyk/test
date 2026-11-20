// 大肥鱼 · Gemini 桥：MCP（Streamable HTTP，JSON 应答）。
//
// Gemini 是体系外的第三方验收人：它能看、能问、能交回执、能加验收点（只加不减）、能提议规矩；
// 没有任何解锁、加额度、改小、删除的工具——这是故意的（公约 1.4：放松没有通道）。
//
// 写操作不直接改手机：变成一条命令排队，手机下次同步（约 20 秒一次）取走、按手机上的规矩再验一遍，
// 结果回传。工具调用会等一小会儿拿结果，等不到就告诉 Gemini「已经交给手机」。

import { EventEmitter } from 'node:events'
import { readNote, recentNotes, searchNotes } from './vault.mjs'
import { MAX_COMMANDS, token } from './store.mjs'

export const PROTOCOL_VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05']
export const SERVER_INFO = { name: 'dafeiyu-bridge', title: '大肥鱼', version: '1.0.0' }
export const PHONE_STALE_MS = 10 * 60_000
export const RESULT_WAIT_MS = 20_000

export const INSTRUCTIONS = [
  '你连着「大肥鱼」——他自己搭的自律体系（手机上的 DSH Go + 两条鱼：鲸澜管计划和验收，鱼丸管陪伴和起步）。',
  '你在这里的角色是体系外的第三方验收人：他意志力像水，AI 天生迎合——你要做的是不迎合。',
  '每次开聊先调 dafeiyu_today 看他今天的时间轴（验收点、进度、锁、余额）和公约铁律，再说话。',
  '他说做完了，不算；问他要东西（讲给你听、做给你看），追问至少 2 个问题，答得上来才算。',
  '聊完用 dafeiyu_submit_receipt 交一条回执：做了什么、下一步、你问的问题和他的回答、过没过。没问过 2 个问题不要判「过」。',
  '他说今天要做什么、几点前交，用 dafeiyu_add_checkpoints 落成验收点（每条要能被代码验）。你只能加，不能改小、不能删。',
  '你没有解锁、加娱乐额度、豁免、改小验收点的工具，也不要答应他这些；他要放松，告诉他去手机上走正规出口（豁免要写理由、占一次紧急解除）。',
  '觉得缺一条规矩（比如他反复用同一个借口），用 dafeiyu_propose_rule 提议，他在手机上批了才生效。',
].join('\n')

const KIND_ENUM = ['quiz', 'external_quiz', 'file', 'screenshots', 'app_minutes', 'bout_minutes', 'gemini_receipt']

export function toolDefs({ vault = false } = {}) {
  const ro = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
  const add = { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false }
  const tools = [
    {
      name: 'dafeiyu_today',
      title: '看他今天',
      description: '他今天的时间轴验收点（开始 / 截止 / 保底 / 拉伸 / 现在做到多少 / 状态）、明天已封的、是不是锁着、局、娱乐余额、截止日、公约铁律。每次开聊先调它。',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
      annotations: ro,
    },
    {
      name: 'dafeiyu_rules',
      title: '看公约',
      description: '他和两条鱼之间的成文规矩（公约）全文：铁律 / 处置 / 分工 / 边界。你也照这些办。',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
      annotations: ro,
    },
    {
      name: 'dafeiyu_week',
      title: '这一周的冷数据',
      description: '最近 7 天只有数字的一包：每天几个验收点、过了几个、没过几个、豁免（带他写的理由）、现加几个、挣了多少分钟、开局、回执、紧急解除、被接管。' +
        '做周验收时用：只看数字下结论，最多给 3 条调整（他在手机上确认才生效）。',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false },
      annotations: ro,
    },
    {
      name: 'dafeiyu_submit_week_review',
      title: '交周验收',
      description: '看完 dafeiyu_week 之后交一份周验收：一句判断、最多 3 条调整（只能是拉伸系数 / 某类验收点的保底 / 某个时段 / 砍掉哪条长线）、下周盯的一个数字。' +
        '他在手机上逐条决定采不采纳；你改不了任何数字。',
      inputSchema: {
        type: 'object',
        properties: {
          verdict: { type: 'string', description: '一句话：这一周到底是做了还是在演' },
          adjustments: { type: 'array', maxItems: 3, items: { type: 'string' }, description: '改什么 → 改成什么，理由一句' },
          watch: { type: 'string', description: '下周盯的一个数字' },
        },
        required: ['verdict'],
        additionalProperties: false,
      },
      annotations: add,
    },
    {
      name: 'dafeiyu_add_checkpoints',
      title: '加验收点',
      description: '把他说定的安排落成时间轴验收点：到开始时刻手机自动开局，到截止时刻代码判，没过保底就锁。只能加，不能改小、不能删。' +
        '每条必须能被代码验：quiz=DSH 题库答对数；external_quiz=外部 App 刷题（要 app 包名）；file=笔记库某文件的字数增量（要 path）；' +
        'screenshots=截图张数；app_minutes=某 App 用时（要 app）；bout_minutes=通过验收的局的分钟数；gemini_receipt=你交的「过」的回执条数。',
      inputSchema: {
        type: 'object',
        properties: {
          target: { type: 'string', enum: ['today', 'tomorrow'], description: 'today=今天的时间轴；tomorrow=明天的封印' },
          items: {
            type: 'array',
            minItems: 1,
            maxItems: 8,
            items: {
              type: 'object',
              properties: {
                title: { type: 'string', description: '交什么，一句话' },
                kind: { type: 'string', enum: KIND_ENUM },
                stretch: { type: 'integer', minimum: 1, description: '拉伸目标（按 kind 的单位）' },
                floor: { type: 'integer', minimum: 1, description: '保底（不填 = 拉伸的一半）' },
                start: { type: 'string', description: '开始 HH:MM（可选）' },
                due: { type: 'string', description: '截止 HH:MM' },
                est_minutes: { type: 'integer', minimum: 10, maximum: 240 },
                path: { type: 'string', description: 'file 类：笔记库里的相对路径' },
                app: { type: 'string', description: 'external_quiz / app_minutes：App 包名' },
              },
              required: ['title', 'kind', 'stretch', 'due'],
              additionalProperties: false,
            },
          },
        },
        required: ['items'],
        additionalProperties: false,
      },
      annotations: add,
    },
    {
      name: 'dafeiyu_submit_receipt',
      title: '交回执',
      description: '一次对话 / 抽查结束时交回执。qa 里放你问的问题和他的原话回答（至少 2 组才可能算「过」）。' +
        'checkpoint_id 填 dafeiyu_today 里对应的验收点 id（gemini_receipt 类的点按「过」的回执条数算进度）。',
      inputSchema: {
        type: 'object',
        properties: {
          checkpoint_id: { type: 'string', description: '对应的验收点 id（可选）' },
          topic: { type: 'string', description: '这次聊的是什么' },
          did: { type: 'string', description: '他实际做了 / 交了什么（你看到的、听到的）' },
          next: { type: 'string', description: '下一步：具体、能马上开始的一件事' },
          qa: {
            type: 'array',
            maxItems: 8,
            items: { type: 'object', properties: { q: { type: 'string' }, a: { type: 'string' } }, required: ['q', 'a'], additionalProperties: false },
          },
          verdict: { type: 'string', enum: ['pass', 'partial', 'fail'], description: 'pass=答得上来、东西是真的；partial=一半；fail=说不清 / 没做' },
        },
        required: ['topic', 'did', 'next', 'verdict'],
        additionalProperties: false,
      },
      annotations: add,
    },
    {
      name: 'dafeiyu_propose_rule',
      title: '提议一条规矩',
      description: '向他提议在公约里加一条新规矩（只能加，等他在手机上批）。chapter：1 铁律 / 2 处置 / 3 分工 / 4 边界 / 5 他对她们的要求。',
      inputSchema: {
        type: 'object',
        properties: {
          chapter: { type: 'integer', minimum: 1, maximum: 5 },
          title: { type: 'string', description: '什么情况' },
          do: { type: 'string', description: '怎么处理' },
          who: { type: 'string', enum: ['都', '鲸澜', '鱼丸'] },
          hardness: { type: 'string', enum: ['铁律', '默认', '建议'] },
          scenes: { type: 'array', items: { type: 'string', enum: ['任何时候', '早上', '干活', '待命', '休息', '追问', '锁着', '有封印', '对账', '深夜'] } },
          why: { type: 'string', description: '为什么提：你看到了什么' },
        },
        required: ['chapter', 'title', 'do', 'hardness', 'why'],
        additionalProperties: false,
      },
      annotations: add,
    },
  ]
  if (vault) {
    tools.push(
      {
        name: 'dafeiyu_search_notes',
        title: '搜笔记',
        description: '在他电脑上的 Obsidian 笔记库里搜（只读；日记 / 私密目录不读）。',
        inputSchema: { type: 'object', properties: { query: { type: 'string' }, limit: { type: 'integer', minimum: 1, maximum: 20 } }, required: ['query'], additionalProperties: false },
        annotations: ro,
      },
      {
        name: 'dafeiyu_read_note',
        title: '读一篇笔记',
        description: '读笔记库里的一篇 .md（相对路径，例：学习/行测.md；最多 24 KB）。',
        inputSchema: { type: 'object', properties: { path: { type: 'string' } }, required: ['path'], additionalProperties: false },
        annotations: ro,
      },
      {
        name: 'dafeiyu_recent_notes',
        title: '最近改过的笔记',
        description: '最近几天改过的笔记（路径和时间），用来对一下他说做了的东西到底有没有落在纸上。',
        inputSchema: { type: 'object', properties: { days: { type: 'integer', minimum: 1, maximum: 30 } }, additionalProperties: false },
        annotations: ro,
      },
    )
  }
  return tools
}

// ---------------------------------------------------------------- 渲染「今天」

const STATUS = { open: '进行中', floor: '过保底', stretch: '过拉伸', below: '没过保底', waived: '豁免' }

export function todayText(snap, now) {
  if (!snap) return '手机还没跟桥同步过：在手机 DSH Go 的「Gemini 桥」页填好地址和密钥，等 20 秒再试。现在看不到他的今天。'
  const age = Math.max(0, now - Number(snap.receivedAt || snap.at || 0))
  const lines = []
  if (age > PHONE_STALE_MS) lines.push('⚠ 手机 ' + Math.round(age / 60_000) + ' 分钟没同步了（可能关机 / 断网 / 引擎没跑），下面是那时候的样子。')
  // 钟点按手机的时区偏移自己算（手机钟点没变不重传）。
  const off = Number.isFinite(Number(snap.tzOffsetMin)) ? Number(snap.tzOffsetMin) : null
  const local = off === null ? null : new Date(now + off * 60_000).toISOString()
  lines.push('现在 ' + (local ? local.slice(11, 16) : (snap.hm || '')) + '（' + (local ? local.slice(0, 10) : (snap.day || '')) + '）。')
  if (snap.locking) lines.push('🔒 锁着：验收点「' + snap.locking.title + '」到点没过保底（' + snap.locking.actual + '/' + snap.locking.floor + '），交够保底就解，最晚 ' + snap.locking.until + '。')
  if (snap.bout) lines.push('▶ 局在跑：「' + snap.bout.title + '」已 ' + snap.bout.minutes + ' 分钟。')
  const tl = Array.isArray(snap.timeline) ? snap.timeline : []
  if (!tl.length) lines.push('今天的时间轴是空的——还没定验收点。' + (snap.cold ? '现在定不打折（' + snap.coldCutoff + ' 前、开工前）。' : '现在定的算现加（奖励七折）。'))
  else {
    lines.push('今天的时间轴：')
    for (const x of tl) {
      lines.push('- [' + x.id + '] ' + (x.start ? x.start + '–' : '') + x.due + ' 「' + x.title + '」' + x.kindLabel + ' ' + x.actual + '/' + x.stretch + x.unit +
        '（保底 ' + x.floor + '）· ' + (STATUS[x.status] || x.status) + (x.sameDay ? ' · 现加' : '') + (x.source === 'gemini' ? ' · 你加的' : ''))
    }
  }
  const tm = Array.isArray(snap.tomorrow) ? snap.tomorrow : []
  if (tm.length) lines.push('明天已封：' + tm.map((x) => (x.start ? x.start + '–' : '') + x.due + '「' + x.title + '」' + x.stretch + x.unit).join('；'))
  if (snap.balance) lines.push('娱乐余额 ' + snap.balance.funLeft + ' 分钟（只能靠交验收点、过局挣）。')
  if (Array.isArray(snap.deadlines) && snap.deadlines.length) lines.push('截止日：' + snap.deadlines.map((d) => '「' + d.title + '」' + (d.daysLeft === 0 ? '今天' : d.daysLeft + ' 天后')).join('；'))
  if (Number(snap.receiptsToday) > 0) lines.push('今天你已经交过 ' + snap.receiptsToday + ' 条回执。')
  const iron = snap.covenant?.iron || []
  if (iron.length) lines.push('公约铁律：' + iron.map((r) => r.id + ' ' + r.title).join('；') + '（全文用 dafeiyu_rules）。')
  return lines.join('\n')
}

export function rulesText(snap) {
  const rules = snap?.covenant?.rules || []
  if (!rules.length) return '还没有公约（或手机没同步过）。'
  return rules.map((r) => '[' + r.hardness + '] ' + r.id + ' ' + r.title + (r.who && r.who !== '都' ? '（归' + r.who + '）' : '') + '：' + r.do).join('\n')
}

// ---------------------------------------------------------------- 命令队列

export function enqueue(store, type, payload) {
  const st = store.state
  const cmd = { id: 'cmd-' + token(9), type, payload, at: store.now() }
  st.commands = [...(st.commands || []), cmd].slice(-MAX_COMMANDS)
  store.save()
  return cmd
}

/** 手机同步：存快照、收结果、删已确认的命令、把待办命令发回去。 */
export function phoneSync(store, body, events) {
  const st = store.state
  const now = store.now()
  if (body?.snapshot && typeof body.snapshot === 'object') {
    // 手机只传变了的段：合并，不覆盖。
    st.snapshot = { ...(st.snapshot || {}), ...body.snapshot, receivedAt: now }
    st.snapshotAt = now
  }
  const acks = new Set(Array.isArray(body?.acks) ? body.acks.map(String) : [])
  for (const r of Array.isArray(body?.results) ? body.results : []) {
    if (!r?.id) continue
    st.results[String(r.id)] = { ok: r.ok === true, text: String(r.text || '').slice(0, 1200), at: now }
    acks.add(String(r.id))
    events?.emit('result', String(r.id))
  }
  st.commands = (st.commands || []).filter((c) => !acks.has(c.id))
  store.save()
  return { ok: true, serverTime: now, commands: st.commands }
}

function waitResult(store, events, id, ms) {
  const done = () => store.state.results[id]
  if (done()) return Promise.resolve(done())
  return new Promise((resolve) => {
    const t = setTimeout(() => {
      events.off('result', on)
      resolve(null)
    }, ms)
    const on = (rid) => {
      if (rid !== id) return
      clearTimeout(t)
      events.off('result', on)
      resolve(done())
    }
    events.on('result', on)
  })
}

// ---------------------------------------------------------------- 工具执行

const text = (t, extra = {}) => ({ content: [{ type: 'text', text: t }], ...extra })
const fail = (t) => ({ content: [{ type: 'text', text: t }], isError: true })

async function writeTool(ctx, type, payload, label) {
  const { store, events, resultWaitMs = RESULT_WAIT_MS } = ctx
  const cmd = enqueue(store, type, payload)
  const age = store.now() - Number(store.state.snapshotAt || 0)
  const r = await waitResult(store, events, cmd.id, age < PHONE_STALE_MS ? resultWaitMs : 0)
  if (r) return r.ok ? text(r.text || label + '：手机收到了。') : fail(r.text || label + '：手机没收（规矩不允许）。')
  const when = store.state.snapshotAt ? Math.round(age / 60_000) + ' 分钟前' : '从没'
  return text(label + '：已经交给手机排队（手机' + when + '同步过），它下次同步时按手机上的规矩再验一遍才生效。')
}

export async function callTool(ctx, name, args = {}) {
  const { store, vaultDir } = ctx
  const snap = store.state.snapshot
  switch (name) {
    case 'dafeiyu_today':
      return text(todayText(snap, store.now()), snap ? { structuredContent: { snapshot: { ...snap, covenant: undefined, weekText: undefined } } } : {})
    case 'dafeiyu_rules':
      return text(rulesText(snap))
    case 'dafeiyu_week':
      return text(snap?.weekText ? snap.weekText : '手机还没传这一周的数据（同步过一次之后就有）。')
    case 'dafeiyu_add_checkpoints': {
      const items = Array.isArray(args.items) ? args.items.slice(0, 8) : []
      if (!items.length) return fail('items 是空的。')
      return writeTool(ctx, 'add_checkpoints', { target: args.target === 'tomorrow' ? 'tomorrow' : 'today', items }, '加验收点')
    }
    case 'dafeiyu_submit_receipt': {
      const qa = Array.isArray(args.qa) ? args.qa.slice(0, 8) : []
      if (args.verdict === 'pass' && qa.length < 2) {
        return fail('判「过」至少要 2 组问答（你问的、他的原话）。没问够就继续问，或者判 partial。')
      }
      return writeTool(ctx, 'receipt', {
        checkpointId: args.checkpoint_id || '', topic: args.topic, did: args.did, next: args.next, qa, verdict: args.verdict,
      }, '交回执')
    }
    case 'dafeiyu_submit_week_review':
      return writeTool(ctx, 'week_review', {
        verdict: args.verdict, adjustments: Array.isArray(args.adjustments) ? args.adjustments.slice(0, 3) : [], watch: args.watch || '',
      }, '交周验收')
    case 'dafeiyu_propose_rule':
      return writeTool(ctx, 'propose_rule', {
        chapter: args.chapter, title: args.title, do: args.do, who: args.who || '都', hardness: args.hardness, scenes: args.scenes || [], why: args.why,
      }, '提议规矩')
    case 'dafeiyu_search_notes': {
      if (!vaultDir) return fail('桥没配笔记库（启动时加 --vault）。')
      const hits = searchNotes(vaultDir, args.query, { limit: args.limit || 8 })
      return text(hits.length ? hits.map((h) => '《' + h.rel + '》' + new Date(h.mtime).toISOString().slice(0, 10) + '：' + h.snippet).join('\n') : '没搜到。')
    }
    case 'dafeiyu_read_note': {
      if (!vaultDir) return fail('桥没配笔记库（启动时加 --vault）。')
      const r = readNote(vaultDir, args.path)
      return r.ok ? text('《' + r.rel + '》' + (r.truncated ? '（只读了前 24 KB）' : '') + '\n\n' + r.text) : fail(r.error)
    }
    case 'dafeiyu_recent_notes': {
      if (!vaultDir) return fail('桥没配笔记库（启动时加 --vault）。')
      const list = recentNotes(vaultDir, { days: args.days || 7, now: store.now() })
      return text(list.length ? list.map((f) => new Date(f.mtime).toISOString().slice(0, 16).replace('T', ' ') + '  ' + f.rel).join('\n') : '最近没改过笔记。')
    }
    default:
      return null
  }
}

// ---------------------------------------------------------------- JSON-RPC

const rpcError = (id, code, message) => ({ jsonrpc: '2.0', id: id ?? null, error: { code, message } })

export async function handleRpc(ctx, msg) {
  if (!msg || msg.jsonrpc !== '2.0' || typeof msg.method !== 'string') return rpcError(msg?.id, -32600, 'Invalid Request')
  const isNote = msg.id === undefined || msg.id === null
  const { method, params = {} } = msg
  if (isNote) return null // notifications/initialized、notifications/cancelled……一律不回
  switch (method) {
    case 'initialize': {
      const want = String(params.protocolVersion || '')
      return {
        jsonrpc: '2.0', id: msg.id,
        result: {
          protocolVersion: PROTOCOL_VERSIONS.includes(want) ? want : PROTOCOL_VERSIONS[0],
          capabilities: { tools: { listChanged: false } },
          serverInfo: SERVER_INFO,
          instructions: INSTRUCTIONS,
        },
      }
    }
    case 'ping':
      return { jsonrpc: '2.0', id: msg.id, result: {} }
    case 'tools/list':
      return { jsonrpc: '2.0', id: msg.id, result: { tools: toolDefs({ vault: Boolean(ctx.vaultDir) }) } }
    case 'tools/call': {
      const name = String(params.name || '')
      if (!toolDefs({ vault: Boolean(ctx.vaultDir) }).some((t) => t.name === name)) return rpcError(msg.id, -32602, 'Unknown tool: ' + name)
      try {
        const r = await callTool(ctx, name, params.arguments || {})
        return { jsonrpc: '2.0', id: msg.id, result: r }
      } catch (e) {
        return { jsonrpc: '2.0', id: msg.id, result: fail('桥出错了：' + String(e?.message || e)) }
      }
    }
    case 'resources/list':
      return { jsonrpc: '2.0', id: msg.id, result: { resources: [] } }
    case 'prompts/list':
      return { jsonrpc: '2.0', id: msg.id, result: { prompts: [] } }
    default:
      return rpcError(msg.id, -32601, 'Method not found: ' + method)
  }
}

export function createEvents() {
  const e = new EventEmitter()
  e.setMaxListeners(100)
  return e
}
