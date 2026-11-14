// dsh-go-companion · 行为大脑的接线（K1 记录 + K2 发现 + K3 模式卡）
//
// 每次 /plan：快照 → 片段（episodes.js）。换天时：昨天的摘要定稿 → 跑探测器（patterns.js）→ 更新模式卡（cards.js）。
// 这一版只到「看见并记住」：不自动立预案、不评估实验、没有权柄——那些是 K4–K7，等真实数据攒够了再接。
// 鲸澜可以在卡上写解释（必须引用证据编号），他可以写自己的说法；立预案时可以在「依据」里写卡号。
//
// 依赖全部注入，这个文件不读盘。

import { digestOf, noteBout as epNoteBout, noteLock as epNoteLock, normalizeState, observe, rollDay } from './episodes.js'
import { detect } from './patterns.js'
import { explain as cardExplain, index as cardIndex, list as cardList, normalizeCards, note as cardNote, update as cardUpdate } from './cards.js'

/**
 * @param {{
 *   loadEpisodes: () => object, saveEpisodes: (s:object) => void,
 *   loadCards: () => object, saveCards: (c:object) => void,
 *   record: (e:object) => void,
 *   day: (now:number) => string, minute: (now:number) => number, hourOf: (ms:number) => number,
 *   backed: () => string[],
 *   patternOpts?: () => object,
 * }} deps
 */
export function createBrainRuntime(deps) {
  const loadEp = () => normalizeState(deps.loadEpisodes())
  const loadCards = () => normalizeCards(deps.loadCards())

  /** 跑一遍探测器并更新卡片。today 之前的摘要才算（今天还没过完）。 */
  function scan(state, today, now) {
    const candidates = detect({ digests: state.digests, segs: state.segs, today, opts: deps.patternOpts ? deps.patternOpts() : {} })
    const r = cardUpdate(loadCards(), candidates, { day: today, now, backed: deps.backed() })
    deps.saveCards(r.cards)
    if (r.created.length || r.changed.length) {
      deps.record({ state: 'brain-scan', at: now, candidates: candidates.length, created: r.created, changed: r.changed })
    }
    return { candidates: candidates.length, created: r.created, changed: r.changed }
  }

  /**
   * 每次 /plan。
   * @param {object|null} phone  phone.js 清洗过的快照
   * @param {{bout:{id:string}|null, night:boolean, todayFun?:number}} ctx
   */
  function tick(phone, now, ctx = {}) {
    const day = deps.day(now)
    let state = loadEp()
    const rolled = rollDay(state, { day, now, hourOf: deps.hourOf, appsOf: deps.appsOf })
    state = rolled.state
    const o = observe(state, {
      now, day, minute: deps.minute(now),
      app: phone?.app || null, fun: phone?.fun === true, minutes: Number(phone?.minutes) || 0,
      screenOn: phone ? phone.screenOn !== false : false,
      bout: ctx.bout || null, night: ctx.night === true,
      todayFun: typeof ctx.todayFun === 'number' ? ctx.todayFun : undefined,
    })
    state = o.state
    deps.saveEpisodes(state)
    let night = null
    if (rolled.finalized) {
      deps.record({ state: 'brain-digest', at: now, day: rolled.finalized.day, funMin: rolled.finalized.funMin,
        bouts: rolled.finalized.bouts.opened, self: rolled.finalized.bouts.self, bad: rolled.finalized.bouts.bad })
      night = scan(state, day, now)
    }
    return { closed: o.closed.length, opened: o.opened ? o.opened.id : null, night }
  }

  function noteLock(now) {
    deps.saveEpisodes(epNoteLock(loadEp(), now))
  }

  function noteBout(input, now) {
    deps.saveEpisodes(epNoteBout(loadEp(), { ...input, day: deps.day(now) }))
  }

  /** 给面板和鲸澜看：今天的实时摘要、最近 14 天、全部卡片、索引。 */
  function view(now) {
    const state = loadEp()
    const day = deps.day(now)
    const days = Object.keys(state.digests).sort().slice(-14)
    return {
      today: digestOf(state, day, { now, hourOf: deps.hourOf, appsOf: deps.appsOf }),
      digests: days.map((d) => state.digests[d]),
      open: state.open ? { app: state.open.app, fun: state.open.fun, start: state.open.start, cause: state.open.cause, context: state.open.context } : null,
      cards: cardList(loadCards()),
      index: cardIndex(loadCards()),
      segsKept: state.segs.length,
    }
  }

  /** 不等换天，现在就扫一遍（面板上的「现在看看」）。 */
  function scanNow(now) {
    return scan(loadEp(), deps.day(now), now)
  }

  function explain(id, input, now, by) {
    if (by !== 'strategist') return { ok: false, text: '模式卡上的解释只有鲸澜写。' }
    const r = cardExplain(loadCards(), id, input, now)
    if (r.ok) {
      deps.saveCards(r.cards)
      deps.record({ state: 'brain-explain', at: now, cardId: id, guess: r.guess, dropped: r.dropped.length ? r.dropped : undefined })
    }
    return { ok: r.ok, text: r.text + (r.dropped.length ? '（这些编号不在卡上，已去掉：' + r.dropped.join('、') + '）' : '') }
  }

  function note(id, text, now) {
    const r = cardNote(loadCards(), id, text, now)
    if (r.ok) {
      deps.saveCards(r.cards)
      deps.record({ state: 'brain-note', at: now, cardId: id })
    }
    return { ok: r.ok, text: r.ok ? '记下了，鲸澜下次会回应。' : '没有这张卡，或者内容是空的。' }
  }

  return { tick, noteLock, noteBout, view, scanNow, explain, note, index: () => cardIndex(loadCards()) }
}
