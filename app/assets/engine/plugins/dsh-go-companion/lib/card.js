// dsh-go-companion · 当下任务卡（2026-11-18）
//
// 借鉴 Google A2UI / Flutter GenUI：AI 只给受限的 JSON，App 用自己写好的组件渲染，不执行任何生成的代码。
// 组件只有这几样：标题、一句话目标、步骤清单（可勾）、倒计时、交付物、验收方式、提示、最多 3 个按钮。
// 按钮只能映射到白名单动作：
//   start_next_bout 开今天计划里的下一局
//   connect_gemini  摇一骰、带简报连 Gemini（会先确认 NekoBox）
//   open_app        打开一个 App（只许工作层 / 双用途层）
// 卡片 6 小时后自动过期；同一时刻只有一张。

export const CARD_ACTIONS = Object.freeze(['start_next_bout', 'connect_gemini', 'open_app'])
export const CARD_TTL_MS = 6 * 3_600_000
const cut = (s, n) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim().slice(0, n)

/**
 * @param {object} raw 模型给的卡
 * @param {object} o { now, by, appOk: (pkg)=>boolean }
 * @returns {{ok:true, card}|{ok:false, problems:string[]}}
 */
export function cleanCard(raw, { now = Date.now(), by = '', appOk = () => false } = {}) {
  const problems = []
  const title = cut(raw?.title, 30)
  if (!title) problems.push('要有标题')
  const steps = (Array.isArray(raw?.steps) ? raw.steps : []).slice(0, 6)
    .map((x) => (typeof x === 'string' ? { text: cut(x, 50), done: false } : { text: cut(x?.text, 50), done: x?.done === true }))
    .filter((x) => x.text)
  const minutes = Number(raw?.minutes)
  const timerUntil = Number.isFinite(minutes) && minutes > 0 ? now + Math.min(180, Math.round(minutes)) * 60_000 : 0
  const buttons = []
  for (const b of (Array.isArray(raw?.buttons) ? raw.buttons : []).slice(0, 3)) {
    const action = String(b?.action || '')
    if (!CARD_ACTIONS.includes(action)) { problems.push('按钮动作不在白名单：' + action); continue }
    const label = cut(b?.label, 10) || { start_next_bout: '开下一局', connect_gemini: '连 Gemini', open_app: '打开' }[action]
    if (action === 'open_app') {
      const app = String(b?.app || '').trim()
      if (!/^[A-Za-z0-9_.]+$/.test(app) || !appOk(app)) { problems.push('只能打开工作 / 双用途 App：' + app); continue }
      buttons.push({ label, action, app })
    } else {
      buttons.push({ label, action })
    }
  }
  if (problems.length && !title) return { ok: false, problems }
  return {
    ok: true,
    problems,
    card: {
      v: 1,
      title,
      goal: cut(raw?.goal, 60),
      steps,
      timerUntil,
      deliverable: cut(raw?.deliverable, 60),
      accept: cut(raw?.accept, 60),
      tip: cut(raw?.tip, 80),
      buttons,
      by: cut(by, 20),
      at: now,
    },
  }
}

export function cardLive(card, now = Date.now()) {
  return Boolean(card?.title) && now - Number(card.at || 0) < CARD_TTL_MS
}

export function toggleStep(card, index) {
  if (!card || !Array.isArray(card.steps) || !card.steps[index]) return card
  const steps = card.steps.map((s, i) => (i === index ? { ...s, done: !s.done } : s))
  return { ...card, steps }
}

/** 接管时由代码直接生成的「回位动作」卡（不经模型）。 */
export function takeoverCard({ why = '', action = '', now = Date.now() } = {}) {
  return {
    v: 1,
    title: '回位动作',
    goal: cut(why, 60),
    steps: [{ text: cut(action, 50), done: false }],
    timerUntil: now + 45 * 60_000,
    deliverable: '',
    accept: '在工作 App 里待满 3 分钟 / 开了局 / 交了完整回执',
    tip: '真有急事：下拉通知栏「紧急解除」',
    buttons: [{ label: '开下一局', action: 'start_next_bout' }, { label: '连 Gemini', action: 'connect_gemini' }],
    by: 'takeover',
    at: now,
  }
}
