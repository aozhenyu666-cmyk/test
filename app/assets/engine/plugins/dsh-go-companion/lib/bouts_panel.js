// dsh-go-companion · 「局」面板的数据（第八轮 W1）
//
// 视图只认 data.*，数据全在这里拼。拼的时候只读三处真相：
//   局库（bouts / beats / verdicts / escalations）——发生了什么；
//   内置协议（lib/protocols/*.json）——装了什么；
//   局官当前状态（如果这一局是本进程开的）——现在这一拍是什么。
//
// 空的地方就写空，不编造进度：面板上一个假的倒计时比没有倒计时更坏。

const CLOSED = 'closed'
/** 收局的几种结局。没收成的不能写成「完成」——账要能对上他当时干了什么。 */
const CLOSE_ZH = { done: '完成', abandoned: '收了没做完', escalated: '升级', timeout: '超时' }

function mmss(sec) {
  const s = Math.max(0, Math.round(Number(sec) || 0))
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0')
}

function hhmm(at) {
  const d = new Date(Number(at) || 0)
  if (!Number(at)) return ''
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
}

/** 各局型的分钟数合计，按从多到少。 */
export function minutesByProtocol(bouts = []) {
  const acc = new Map()
  for (const bout of bouts) {
    const opened = Number(bout.openedAt) || 0
    const closed = Number(bout.closedAt) || 0
    const mins = opened && closed ? Math.max(0, Math.round((closed - opened) / 60000)) : 0
    if (!mins) continue
    acc.set(bout.protocolId, (acc.get(bout.protocolId) || 0) + mins)
  }
  return [...acc.entries()].sort((a, b) => b[1] - a[1])
}

/**
 * 拼面板数据。
 * @param {{
 *   now?:number, bouts?:object[], todayBouts?:object[], protocols?:object[],
 *   live?:object|null, escalations?:number, slackBeats?:number, doneToday?:number
 * }} input
 */
export function buildBoutsSnapshot(input = {}) {
  const now = Number(input.now) || Date.now()
  const live = input.live || null
  const running = (input.bouts || []).find((x) => x.status !== CLOSED) || null
  const step = live && live.stepId ? live : null

  // 有上限才谈剩余。上限到点要显示 0:00（时间真到了），不能显示「—」（看着像没限时）。
  const hasLimit = Boolean(step && Number(step.limitSec) && step.askedAt)
  const leftSec = hasLimit
    ? Math.max(0, Math.round(Number(step.limitSec) - (now - Number(step.askedAt)) / 1000))
    : 0
  const percent = hasLimit
    ? Math.max(0, Math.min(100, Math.round((leftSec / Number(step.limitSec)) * 100)))
    : 0

  const open = Boolean(step) || Boolean(running)
  const now1 = step
    ? {
        headline: live.writeMode ? '写模式：这一局只收手写照片' : (live.boutId ? '进行中' : '还没开局'),
        tone: live.writeMode ? 'warn' : 'info',
        ask: step.ask || '',
        left: hasLimit ? mmss(leftSec) : '—',
        progress: step.stepId ? '这一步：' + step.stepId : '',
        percent,
        hint: live.chaosStreak >= 1 ? '上一句被判混乱了 ' + live.chaosStreak + ' 次。选一件说。' : '',
      }
    : {
        headline: running ? '进行中（不是这个进程开的）' : '现在没有局',
        tone: running ? 'info' : 'muted',
        ask: running ? '这一局由别的进程开着，点刷新同步。' : '点下面的「加一局」开一局。',
        left: '—',
        progress: '',
        percent: 0,
        hint: '',
      }

  const queueRows = (input.todayBouts || []).map((bout) => {
    const state = bout.status === CLOSED
      ? (CLOSE_ZH[bout.closeReason] || CLOSE_ZH.done)
      : (running && running.id === bout.id ? '进行中' : '待开')
    return hhmm(bout.openedAt) + ' ' + bout.protocolId + ' · ' + state
  })

  const protocolRows = (input.protocols || []).map((p) => {
    const items = Array.isArray(p) ? p : p.steps || []
    const status = p.status || (p.status === undefined && p.id ? 'builtin' : '')
    return (p.name || p.id) + ' · ' + items.length + ' 步 · ' + (status === 'builtin' ? '内置' : status || '内置')
  })

  const byProtocol = minutesByProtocol(input.bouts || [])
  const minutes = byProtocol.length
    ? byProtocol.map(([id, m]) => id + ' ' + m + ' 分').join('、')
    : '还没记上分钟'
  const totalMinutes = byProtocol.reduce((sum, [, m]) => sum + m, 0)

  return {
    now: now1,
    queue: queueRows,
    queueNote: queueRows.length ? '共 ' + queueRows.length + ' 局，完成 ' + (input.doneToday || 0) + ' 局。' : '今天还没有局。',
    protocolRows,
    protocolNote: protocolRows.length ? '共 ' + protocolRows.length + ' 个协议，全部通过加载校验。' : '一个协议都没装上。',
    books: {
      minutes: String(totalMinutes),
      minutesCaption: minutes,
      empty: String(input.emptyToday || 0),
      emptyTone: (input.emptyToday || 0) >= 3 ? 'bad' : 'muted',
      emptyCaption: '今天没交产出的局',
      slack: String(input.slackBeats || 0),
      slackCaption: '不算应答的那些回复',
      escalations: String(input.escalations || 0),
      escalationsCaption: '最近 7 天的升级',
      rows: input.bookRows || [],
    },
    // 面板动作读这几个字段，别让动作自己再猜一遍状态。
    open,
    boutId: (step && step.boutId) || (running && running.id) || '',
  }
}
