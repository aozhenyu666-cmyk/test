// 给「思维驾驶员」APP 用的路由（前缀 /dsh-driver-core/v1/app），用配对令牌鉴权，不用引擎 cookie。
//
//   POST /app/pair     {code, device?}   配对码换令牌（不需要令牌）           → {token, version}
//   GET  /app/ping                       令牌是否有效、出题会话在不在、记忆几条 → {ok, brain, memories}
//   POST /app/compose  {…ComposeRequest}  出一题：三层上下文 + fish-memory → 大肥鱼回合 → {card|null, why}
//   POST /app/report   {…锁定结束报告}     存进 reports.jsonl，大肥鱼以后能回顾
//
// 出题拿不到（会话不在、正忙、超时、回的不合格）就回 card:null，APP 自己用模板，不会卡住。

import { INTERACTIONS, formulaic, materialsOf, citesMaterial } from './interactions.js'
import { fromAppRequest } from './core/context.js'
import { driverPrompt, parseCard } from './core/prompt.js'

export const VERSION = 1

/** 在任意会话里说这句话，那个会话就成为驾驶员的出题会话。 */
export const CLAIM_PHRASE = /驾驶员(就)?用这个(会话|对话)/

export function createAppService({ store, pairing, brain, getConfig, now = () => Date.now(), composeTimeoutMs = 8_000 }) {
  const memories = () => {
    const cfg = getConfig()
    const ids = cfg.memorySessionIds?.length ? cfg.memorySessionIds : (store.listFishMemorySessions?.() ?? [])
    return store.loadFishMemories(ids)
  }

  return {
    pair(body) {
      const r = pairing.redeem(body?.code, body?.device)
      return r.token ? { status: 200, body: { v: VERSION, token: r.token } } : { status: r.status, body: { v: VERSION, error: { code: r.error } } }
    },

    ping() {
      const cfg = getConfig()
      return { status: 200, body: { v: VERSION, ok: true, brain: cfg.brainSessionId ? 'configured' : 'none', memories: memories().length } }
    },

    async compose(body) {
      if (!brain || !getConfig().brainSessionId) return { status: 200, body: { v: VERSION, card: null, why: 'no-brain-session' } }
      const ctx = fromAppRequest(body ?? {}, memories())
      const kind = typeof body?.kind === 'string' && Object.hasOwn(INTERACTIONS, body.kind) ? body.kind : null
      if (kind) {
        ctx.steering.interactionKind = kind
        ctx.steering.interactionRule = INTERACTIONS[kind]
        const reports = store.loadRecentReports?.(5) ?? []
        ctx.hidden.conclusions = reports.map((r) => String(r.conclusion).slice(0, 100))
        ctx.hidden.materials = materialsOf(ctx, reports)
        // No personal facts available: let the app state that honestly in its local fallback.
        if (!ctx.hidden.materials.length) return { status: 200, body: { v: VERSION, card: null, why: 'no-material' } }
      }
      const started = performance.now()
      let why = 'unparseable'
      for (let attempt = 0; attempt < 2; attempt++) {
        const remaining = composeTimeoutMs - (performance.now() - started)
        if (remaining <= 0) break
        let prompt = driverPrompt(ctx, { intent: String(body?.intent ?? 'probe').slice(0, 20) })
        if (attempt) prompt += '\n上一题公式化或未引用素材，已退回。请按同一种互动重出，逐字引用素材，换一个具体动作。'
        const raw = await brain.ask(prompt, attempt === 0 && kind ? Math.max(1, Math.floor(remaining / 2)) : Math.floor(remaining))
        if (raw == null) return { status: 200, body: { v: VERSION, card: null, why: 'unavailable' } }
        const card = parseCard(raw, { state: ctx.steering.state, maxText: kind ? 240 : 80 })
        if (!card) return { status: 200, body: { v: VERSION, card: null, why: 'unparseable' } }
        if (formulaic(card.text)) { why = 'formulaic'; continue }
        if (kind && !citesMaterial(card.text.split('\n')[0], ctx.hidden.materials)) { why = 'missing-material'; continue }
        return { status: 200, body: { v: VERSION, card, why: 'ok' } }
      }
      // Existing card:null fallback contract: the app rolls another interaction locally.
      return { status: 200, body: { v: VERSION, card: null, why } }
    },

    report(body) {
      const s = body?.summary ?? {}
      const row = {
        at: now(),
        endedAt: Number(body?.endedAt) || now(),
        topic: String(body?.topic ?? '').slice(0, 80),
        task: String(body?.task ?? '').slice(0, 120),
        reason: String(body?.reason ?? '').slice(0, 40),
        conclusion: body?.conclusion ? String(body.conclusion).slice(0, 300) : null,
        summary: {
          grounded: num(s.grounded), responded: num(s.responded), awake: num(s.awake),
          interruptions: num(s.interruptions), resumed: num(s.resumed), generated: num(s.generated),
        },
        unresolved: Array.isArray(body?.unresolved) ? body.unresolved.filter((x) => typeof x === 'string').slice(0, 8).map((x) => x.slice(0, 80)) : [],
      }
      store.appendReport?.(row)
      return { status: 200, body: { v: VERSION, ok: true } }
    },
  }
}

const num = (v) => (Number.isFinite(v) ? v : 0)
