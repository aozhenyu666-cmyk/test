// dsh-go-companion · 鱼丸的线索（第九轮第五项）
//
// 鱼丸看到的现象（「他今天说了三次累」「他又在凌晨刷 B 站」）用 sister_note 递成线索，
// 进鲸澜的待查队列。她只报现象，不下结论；核实是行为大脑的事。
//
// 一条边界，是这一层存在的理由：一天最多 5 条。线报多了就不是「看见」，是又一种催。

/** 一天最多递几条。 */
export const CLUE_MAX_PER_DAY = 5

/** 今天的空队列。 */
export function empty(day) {
  return { day: Number(day) || 0, items: [] }
}

/** 换天了就换一条空队列：昨天没核实的线索不带进今天。 */
export function ofDay(state, day) {
  const d = Number(day) || 0
  const s = state && typeof state === 'object' ? state : null
  if (!s || Number(s.day) !== d) return empty(d)
  return {
    day: d,
    items: (Array.isArray(s.items) ? s.items : [])
      .filter((x) => x && typeof x.text === 'string' && x.text)
      .slice(-CLUE_MAX_PER_DAY)
      .map((x) => ({ text: x.text, at: Number(x.at) || 0 })),
  }
}

/** 今天还能递几条。 */
export function leftOf(state, day) {
  return Math.max(0, CLUE_MAX_PER_DAY - ofDay(state, day).items.length)
}

/**
 * 递一条线索。
 * @returns {{ok:boolean, state:object, left:number, reason?:string}}
 */
export function add(state, input = {}) {
  const day = Number(input.day) || 0
  const cur = ofDay(state, day)
  const text = String(input.text || '').trim().slice(0, 200)
  if (!text) return { ok: false, state: cur, left: leftOf(cur, day), reason: 'empty' }
  if (cur.items.length >= CLUE_MAX_PER_DAY) {
    return { ok: false, state: cur, left: 0, reason: 'full' }
  }
  const next = { day, items: [...cur.items, { text, at: Number(input.at) || 0 }] }
  return { ok: true, state: next, left: leftOf(next, day) }
}

/** 给鲸澜看的那几行：只说现象，不带结论。 */
export function lines(state, day) {
  const list = ofDay(state, day).items
  if (!list.length) return []
  return ['妹妹看见的（她只报现象，核实是你的事）：', ...list.map((x) => '- ' + x.text)]
}
