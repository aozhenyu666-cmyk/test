/**
 * 工具定义 —— 纯函数，`defineTool` 由调用方传进来
 *
 * 为什么不在这直接 `import { defineTool } from '@deepseek-ai/dsh-tools'`：
 *   那样在没有 DSH 的主机（网页预览、CI、单元测试）上 import 就崩，连「看得见」的那半
 *   也一起挂掉。传进来就只在真有 tools 服务时才需要它。
 *
 * 四个工具各自**只做一件事**，而且都不碰别人的文件：
 *   partner_recall   —— 检索（读上游只读 + 自己那份）
 *   partner_remember —— 记一条（写 partner 自己的库）
 *   partner_correct  —— 纠正（写 partner 的纠正叠加层）
 *   partner_mood     —— 读此刻心情（只读上游 state.json）
 */
import { appendOwn, correct, loadLongTermFull, recall, readMemory, memoryStats } from './memory.mjs'
import { readMood, moodBand } from './mood.mjs'
import { recallCard, rememberCard, correctCard, moodCard, errorCard } from './views.mjs'

/** presentationMeta 的三条铁律：抛错会失败整次调用 / 必须无损 JSON / ≤ 8KiB。 */
function toolCard(build, maxBytes) {
  const cap = maxBytes || 8 * 1024
  return (args, value) => {
    try {
      const text = JSON.stringify(build(args, value))
      if (text === undefined || Buffer.byteLength(text, 'utf8') > cap) return {}
      return { 'dsh-go/view': JSON.parse(text) }
    } catch {
      return {}
    }
  }
}

const NULLABLE_STRING = { oneOf: [{ type: 'string' }, { type: 'null' }] }

/** 工具自己的 scope：谁在调，就只读谁的记忆。子代理/拿不到会话时如实说。 */
function sessionOf(exec) {
  const sid = exec && exec.sessionId ? String(exec.sessionId) : (exec && exec.session && exec.session.id ? String(exec.session.id) : '')
  return sid
}

export function buildTools(defineTool) {
  return [
    defineTool({
      name: 'partner_recall',
      description:
        'Search this session\'s long-term memory (and the partner\'s own notes) for what is known about a topic. ' +
        'Local only: reads the memory files on disk, no network, no model call. ' +
        'Use it before claiming you do not remember something. The user sees a card with the hits.',
      parameters: {
        query: { type: 'string', required: true, description: 'What to look for. Natural language or keywords.' },
        limit: { type: 'integer', description: 'How many memories to return, 1-12. Default from plugin config.' },
      },
      output: {
        schema: {
          type: 'object',
          additionalProperties: false,
          properties: {
            ok: { type: 'boolean', required: true },
            query: { type: 'string', required: true },
            count: { type: 'integer', required: true },
            total: { type: 'integer', required: true },
            reason: { type: 'string', required: true },
            items: {
              type: 'array',
              required: true,
              items: {
                type: 'object',
                additionalProperties: false,
                properties: {
                  id: { type: 'string', required: true },
                  text: { type: 'string', required: true },
                  score: { type: 'number', required: true },
                  clarity: { type: 'string', enum: ['vivid', 'clear', 'fuzzy', 'trace'], required: true },
                  origin: { type: 'string', required: true },
                  at: { type: 'string', required: true },
                },
              },
            },
          },
        },
        render: (args, value) => {
          if (!value.ok) return [{ type: 'text', text: '没查到：' + value.reason }]
          if (!value.items.length) return [{ type: 'text', text: '记忆里没有关于「' + value.query + '」的内容（本地共 ' + value.total + ' 条）。' }]
          return [{ type: 'text', text: value.items.map((x, i) => (i + 1) + '. ' + x.text).join('\n') }]
        },
        presentationMeta: toolCard((args, value) => recallCard(args, value)),
      },
      timeoutMs: 15000,
      isConcurrencySafe: () => true,
      execute: (args, exec) => {
        const cfg = (exec && exec.partnerConfig) || {}
        const lt = loadLongTermFull(cfg, sessionOf(exec))
        const hits = recall(args.query, lt.all, {
          limit: args.limit ? Number(args.limit) : Number(cfg.memory && cfg.memory.recallLimit) || 6,
          floor: Number(cfg.memory && cfg.memory.recallFloor),
        })
        return {
          ok: true,
          query: String(args.query || ''),
          count: hits.length,
          total: lt.all.length,
          reason: '',
          items: hits.map((h) => ({
            id: h.id,
            text: h.text.slice(0, 500),
            score: h.score,
            clarity: h.clarity,
            origin: h.origin,
            at: h.at ? new Date(h.at).toISOString() : '',
          })),
        }
      },
    }),

    defineTool({
      name: 'partner_remember',
      description:
        'Store one durable fact or preference in the partner\'s own memory store. ' +
        'Use it when the user states a stable preference, a promise, or something worth keeping. ' +
        'Repeating the same thing updates the existing entry instead of adding a second one. ' +
        'Do NOT store screen contents, clipboard contents, or anything the user did not say.',
      parameters: {
        text: { type: 'string', required: true, description: 'The thing to remember, in one or two sentences. Max 2000 characters.' },
        importance: { type: 'number', description: '0-100. Default 60. Use 80+ only for promises and hard preferences.' },
        tags: { type: 'array', items: { type: 'string' }, description: 'Up to 8 short tags, e.g. owner, 偏好, 约定.' },
        fact: { type: 'string', description: 'Optional stable key, e.g. \'她.工作习惯\'. Same key updates the same entry.' },
      },
      output: {
        schema: {
          type: 'object',
          additionalProperties: false,
          properties: {
            ok: { type: 'boolean', required: true },
            id: { type: 'string', required: true },
            duplicate: { type: 'boolean', required: true },
            count: { type: 'integer', required: true },
            message: { type: 'string', required: true },
            text: { type: 'string', required: true },
          },
        },
        render: (args, value) => [{ type: 'text', text: value.message }],
        presentationMeta: toolCard((args, value) => rememberCard(args, value)),
      },
      timeoutMs: 15000,
      isConcurrencySafe: () => false,
      execute: (args) => {
        try {
          const r = appendOwn({ text: args.text, importance: args.importance, tags: args.tags, fact: args.fact })
          return {
            ok: true,
            id: r.id,
            duplicate: r.duplicate,
            count: r.count,
            message: r.duplicate ? '这条之前就记着了，已经标成「又想起一次」，没有重复存。' : '记住了。',
            text: String(args.text || '').slice(0, 300),
          }
        } catch (error) {
          return { ok: false, id: '', duplicate: false, count: 0, message: '没记住：' + (error && error.message), text: '' }
        }
      },
    }),
    defineTool({
      name: 'partner_correct',
      description:
        'Fix what the partner remembers: hide a wrong memory, pin an important one, or rewrite its text. ' +
        'This writes only to the partner\'s correction layer; the upstream memory files are never modified. ' +
        'Use the id shown by partner_recall. The user must have said the memory is wrong or should be kept.',
      parameters: {
        id: { type: 'string', required: true, description: 'Memory id, as returned by partner_recall.' },
        action: { type: 'string', required: true, enum: ['hide', 'show', 'pin', 'unpin', 'rewrite', 'note'], description: 'What to do.' },
        value: { type: 'string', description: 'New text for rewrite, or a short note. Ignored for hide/show/pin/unpin.' },
      },
      output: {
        schema: {
          type: 'object',
          additionalProperties: false,
          properties: {
            ok: { type: 'boolean', required: true },
            id: { type: 'string', required: true },
            action: { type: 'string', required: true },
            message: { type: 'string', required: true },
            total: { type: 'integer', required: true },
          },
        },
        render: (args, value) => [{ type: 'text', text: value.message }],
        presentationMeta: toolCard((args, value) => correctCard(args, value)),
      },
      timeoutMs: 15000,
      isConcurrencySafe: () => false,
      execute: (args) => {
        try {
          const saved = correct(args.id, args.action, args.value)
          const verb = {
            hide: '这条藏起来了，以后检索不会带出来。',
            show: '这条又放回来了。',
            pin: '钉住了，检索时排在前面。',
            unpin: '取消钉住。',
            rewrite: '照你说的改了。原文还在上游文件里，只是这边不再用旧的。',
            note: '备注写上了。',
          }[String(args.action)] || '改好了。'
          return { ok: true, id: String(args.id), action: String(args.action), message: verb, total: saved.hide.length + saved.pin.length + Object.keys(saved.text).length + Object.keys(saved.note).length }
        } catch (error) {
          return { ok: false, id: String(args.id || ''), action: String(args.action || ''), message: '没改成功：' + (error && error.message), total: 0 }
        }
      },
    }),

    defineTool({
      name: 'partner_mood',
      description:
        'Read the partner\'s current mood from the feiyu-mood state file: a 0-100 level, seven named feelings, ' +
        'and the last few changes with their reasons. Read-only; includes the decayed value for right now.',
      parameters: {
        timeline: { type: 'integer', description: 'How many recent changes to include, 0-8. Default 3.' },
      },
      output: {
        schema: {
          type: 'object',
          additionalProperties: false,
          properties: {
            ok: { type: 'boolean', required: true },
            reason: { type: 'string', required: true },
            mood: { type: 'number', required: true },
            stored: { type: 'number', required: true },
            band: { type: 'string', required: true },
            dims: {
              type: 'array',
              required: true,
              items: {
                type: 'object',
                additionalProperties: false,
                properties: {
                  key: { type: 'string', required: true },
                  label: { type: 'string', required: true },
                  value: { type: 'number', required: true },
                },
              },
            },
            changes: {
              type: 'array',
              required: true,
              items: {
                type: 'object',
                additionalProperties: false,
                properties: {
                  at: { type: 'string', required: true },
                  delta: { type: 'number', required: true },
                  reason: { type: 'string', required: true },
                },
              },
            },
          },
        },
        render: (args, value) => {
          if (!value.ok) return [{ type: 'text', text: '读不到心情：' + value.reason }]
          const lines = ['此刻 ' + Math.round(value.mood) + '/100 · ' + value.band]
          const top = value.dims.filter((d) => d.value >= 5).sort((a, b) => b.value - a.value)
          if (top.length) lines.push('还剩着：' + top.map((d) => d.label + ' ' + Math.round(d.value)).join('、'))
          for (const c of value.changes) lines.push('· ' + (c.delta >= 0 ? '+' : '') + c.delta + ' ' + c.reason)
          return [{ type: 'text', text: lines.join('\n') }]
        },
        presentationMeta: toolCard((args, value) => moodCard(args, value)),
      },
      timeoutMs: 15000,
      isConcurrencySafe: () => true,
      execute: (args, exec) => {
        const snap = readMood(sessionOf(exec))
        if (!snap.ok) return { ok: false, reason: snap.reason, mood: 0, stored: 0, band: '', dims: [], changes: [] }
        const n = Math.max(0, Math.min(8, args.timeline === undefined ? 3 : Number(args.timeline)))
        return {
          ok: true,
          reason: snap.matched === false ? 'slot 对不上，显示的是唯一那份记录' : '',
          mood: Math.round(snap.current.value * 100) / 100,
          stored: Math.round(Number(snap.mood.mood) * 100) / 100,
          band: moodBand(snap.current.value).label,
          dims: snap.dims.map((d) => ({ key: d.key, label: d.label, value: d.value })),
          changes: snap.timeline.slice(0, n).map((c) => ({
            at: c.at ? new Date(c.at).toISOString() : '',
            delta: c.delta,
            reason: c.reason.slice(0, 200),
          })),
        }
      },
    }),
  ]
}

/** 只读探针（给测试和面板用）：不注册，只跑一遍四类读操作看会不会炸。 */
export function probeReads(cfg, sessionId) {
  const out = {}
  try { out.memory = memoryStats(loadLongTermFull(cfg, sessionId)) } catch (e) { out.memory = { error: String(e && e.message) } }
  try { const m = readMood(sessionId); out.mood = { ok: m.ok, reason: m.reason } } catch (e) { out.mood = { error: String(e && e.message) } }
  try { out.raw = { memoryReason: readMemory(sessionId).reason } } catch (e) { out.raw = { error: String(e && e.message) } }
  return out
}
