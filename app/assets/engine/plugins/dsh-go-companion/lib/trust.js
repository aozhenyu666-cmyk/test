// dsh-go-companion · 信用分（两姐妹共用的一个账户）
//
// 0–100，从账本算出来（不另存，进程重启、换手机都一样）：近的事算得重，旧的慢慢淡。
// 守信加分：接住她们的球、认真回、自己开忙时、任务验收通过、跟 Gemini 连麦并复盘。
// 违抗扣分：没回就关窗、关了提醒继续玩、任务没通过、自己喊收工但没做完、球砸过去没人接。
// 分数决定松紧：低了她们更勤、更冷、不给求情的余地；高了可以痛快放他休息一会儿。

export const TRUST_BASE = 60

/** 每种事加减多少。 */
export const TRUST_WEIGHTS = {
  replied: 1,
  substantive: 2,
  ignored: -1,
  'overlay-closed-silent': -2,
  defied: -10,
  'task-done': 6,
  'task-check-failed': -4,
  'task-claim': -2,
  'gear-self': 2,
  'gemini-start': 1,
  'gemini-recap': 3,
  'break-kept': 2,
  // 默认扣 15；配置 breakglassPenalty 可改（D5），trustScore 的 options.weights 覆盖它。
  breakglass: -15,
  tamper: -100,
  // 追问锁到点没裁决（超时）：默认扣 5，配置 followupPenalty 可改（第三轮 C3）。
  'followup-timeout': -5,
}

const DAY = 86_400_000

/** 越旧越轻：一天内全算，三天内一半，一周内四分之一，再早不算。 */
function decay(age) {
  if (age < DAY) return 1
  if (age < 3 * DAY) return 0.5
  if (age < 7 * DAY) return 0.25
  return 0
}

/** 账本里的一条算成哪种事（不算的返回 null）。 */
function kindOf(e, answered) {
  switch (e?.state) {
    case 'replied': return e.replyClass === 'substantive' ? 'substantive' : 'replied'
    case 'overlay-closed': return e.chatted ? null : 'overlay-closed-silent'
    case 'defied': return 'defied'
    case 'task-done': return 'task-done'
    case 'task-check': return e.passed === false ? 'task-check-failed' : null
    case 'task-claim': return 'task-claim'
    case 'gear': return e.gear === 'busy' && e.by === 'phrase' ? 'gear-self' : null
    case 'gemini-start': return 'gemini-start'
    case 'gemini-recap': return 'gemini-recap'
    case 'break-end': return e.kept ? 'break-kept' : null
    case 'breakglass': return 'breakglass'
    case 'tamper': return 'tamper'
    case 'followup-timeout': return 'followup-timeout'
    case 'generated': return answered.has(e.requestId) ? null : 'ignored'
    default: return null
  }
}

/**
 * @param {{weights?: Record<string, number>}} [options] `weights` overrides single kinds
 *   (the companion passes `{breakglass: -config.breakglassPenalty}`).
 * @returns {{score:number, band:'low'|'mid'|'high', recent:{kind:string, delta:number, at:number}[], counts:Object<string,number>}}
 */
export function trustScore(entries, now = Date.now(), options = {}) {
  const weights = { ...TRUST_WEIGHTS, ...(options && typeof options.weights === 'object' ? options.weights : {}) }
  const all = Array.isArray(entries) ? entries : []
  // 球有没有被接：判球配上的 replied，或者之后他说过话（跟阶梯同一个口径）。
  const answered = new Set()
  let human = 0
  for (const e of all) {
    if (e?.state === 'replied' && e.requestId) answered.add(e.requestId)
    if ((e?.state === 'human' || e?.state === 'said') && Number(e.at) > human) human = Number(e.at)
  }
  let score = TRUST_BASE
  const recent = []
  const counts = {}
  for (const e of all) {
    if (!e) continue
    const at = Number(e.at) || 0
    // 刚发出去的球还没到判"没接"的时候（给他 20 分钟）。
    if (e.state === 'generated' && (now - at < 20 * 60_000 || human > at)) continue
    const kind = kindOf(e, answered)
    if (!kind) continue
    const w = decay(now - at)
    if (!w) continue
    const delta = (weights[kind] || 0) * w
    score += delta
    if (now - at < DAY) counts[kind] = (counts[kind] || 0) + 1
    recent.push({ kind, delta, at })
  }
  score = Math.max(0, Math.min(100, Math.round(score)))
  recent.sort((a, b) => b.at - a.at)
  return { score, band: score < 40 ? 'low' : score >= 75 ? 'high' : 'mid', recent: recent.slice(0, 8), counts }
}

const LABEL = {
  replied: '接了球', substantive: '认真回', ignored: '没接球', 'overlay-closed-silent': '没回就关窗', defied: '关了提醒接着玩',
  'task-done': '任务验收通过', 'task-check-failed': '任务没通过', 'task-claim': '没做完就喊收工', 'gear-self': '自己开忙时',
  'gemini-start': '去 Gemini 连麦', 'gemini-recap': '连麦复盘', 'break-kept': '休息按时回来', breakglass: '破窗', tamper: '想拆管控', 'followup-timeout': '追问锁超时',
}

export function trustLabel(kind) {
  return LABEL[kind] || kind
}

/** 拼进两人的聊天人设：分数决定语气和松紧。 */
export function trustLines(t) {
  if (!t) return []
  const why = Object.entries(t.counts).sort((a, b) => Math.abs(TRUST_WEIGHTS[b[0]] * b[1]) - Math.abs(TRUST_WEIGHTS[a[0]] * a[1]))
    .slice(0, 3).map(([k, n]) => trustLabel(k) + ' ' + n + ' 次').join('、')
  const head = '他的信用分：' + t.score + '/100' + (why ? '（今天：' + why + '）' : '') + '。'
  if (t.band === 'low') return [head + '分很低：今天他糊弄过你们。语气公事公办，不接受求情和讲条件，不给休息豁免；他说做完了一律要证据。']
  if (t.band === 'high') return [head + '分很高：他最近守信。他想休息一会儿（玩 20 分钟以内）可以痛快放行——用 grant_break，并帮他定好收心提醒。']
  return [head]
}

/** 低分时主动找他更勤（最短间隔打折），高分时稍松。 */
export function trustGap(minGapMs, band) {
  const g = Number(minGapMs) || 0
  if (band === 'low') return Math.round(g * 0.6)
  if (band === 'high') return Math.round(g * 1.2)
  return g
}
