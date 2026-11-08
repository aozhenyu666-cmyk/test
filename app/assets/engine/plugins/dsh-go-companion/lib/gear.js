// dsh-go-companion · 档位（闲时 / 忙时）
//
// 他要的是整体高频，不是一堆"炒菜模式""做饭模式"。所以只有两档：
//   闲时（默认）—— 高频：15–20 分钟一次主动找他，聊天、观察、闹他，力度照水位走。
//   忙时（X 分钟）—— 他在做正事（学习、干活、做饭、开会）：不闲聊，每 25 分钟报到一次（问进度），
//                    娱乐 App 一律拒绝（同时开拒绝模式）；到点自动回到闲时，妹妹来迎接他。
// 温和 / 严格是脾气（persona.js 的 mode），档位是节奏和约束，两者独立。

export const BUSY_DEFAULT_MIN = 60
export const BUSY_CHORE_MIN = 40
export const BUSY_CHECK_MS = 25 * 60_000

const FREE = /^(进入|切到|切换到|回到)?闲时(模式)?$|我闲了|有空了|忙完了|干完了|做完了|收工|不忙了|下课了|下班了|放学了|吃完了/
const BUSY = /忙时(模式)?|(进入|切到|开启)忙时|开始(干活|学习|工作|写作业|复习|专注|看书|上课)|(干活|学习|工作|专注|写作业)模式|我(要|得|先)?去?(做饭|炒菜|干活|学习|写作业|复习|上课|开会|洗澡|运动|健身|跑步)|炒菜模式|做饭模式/
const CHORE = /做饭|炒菜|干活|洗澡|运动|健身|跑步|家务/

/**
 * 一句话切档："忙时 90 分钟" / "我去做饭了" / "开始学习 2 小时" → { gear:'busy', until, what }；
 * "忙完了" / "闲时" → { gear:'free' }；别的 → null。只认短句和明确说法，免得"今天上课好无聊"也切过去。
 */
export function gearSwitch(text, now = Date.now()) {
  const s = String(text == null ? '' : text).replace(/\s+/g, '')
  if (!s || s.length > 30) return null
  const at = Number(now) || Date.now()
  if (FREE.test(s)) return { gear: 'free' }
  const m = BUSY.exec(s)
  if (!m) return null
  const h = /(\d+(?:\.\d+)?)(个)?(小时|钟头)/.exec(s)
  const min = /(\d+)分钟/.exec(s)
  let ms = (CHORE.test(s) ? BUSY_CHORE_MIN : BUSY_DEFAULT_MIN) * 60_000
  if (h) ms = Number(h[1]) * 3_600_000
  else if (min) ms = Number(min[1]) * 60_000
  else if (/半(个)?(小时|钟头)/.test(s)) ms = 30 * 60_000
  ms = Math.max(10 * 60_000, Math.min(8 * 3_600_000, ms))
  const what = (m[0].replace(/^(我(要|得|先)?去?|开始|进入|切到|开启)/, '').replace(/模式$/, '') || '正事').slice(0, 20)
  return { gear: 'busy', until: at + ms, what: what === '忙时' ? '正事' : what }
}

/** 现在是哪一档：忙时到点了就是闲时。 */
export function effectiveGear(config, now = Date.now()) {
  const c = config || {}
  return c.gear === 'busy' && Number(c.gearUntil) > Number(now) ? 'busy' : 'free'
}

/** 切档写进配置的字段。忙时顺带开拒绝模式（refuseBy:'gear'，切回闲时一起关）。 */
export function applyGear(config, sw, now = Date.now()) {
  const c = { ...(config || {}) }
  const at = Number(now) || Date.now()
  if (sw.gear === 'busy') {
    c.gear = 'busy'
    c.gearUntil = sw.until
    c.gearSince = at
    c.gearWhat = sw.what || '正事'
    // 任务模式 = 她们立的忙时：有目标、要验收，他自己说"忙完了"解不开。
    c.taskGoal = sw.task ? String(sw.task.goal || c.gearWhat).slice(0, 120) : undefined
    c.taskBy = sw.task ? String(sw.task.by || 'ai') : undefined
    c.taskReason = sw.task ? String(sw.task.reason || '').slice(0, 160) || undefined : undefined
    if (!(Number(c.refuseUntil) > sw.until)) {
      c.refuseUntil = sw.until
      c.refuseBy = 'gear'
    }
  } else {
    if (c.gear === 'busy' && Number(c.gearUntil) > at) c.gearUntil = at
    c.gear = 'free'
    c.taskGoal = undefined
    c.taskBy = undefined
    c.taskReason = undefined
    if (c.refuseBy === 'gear') {
      c.refuseUntil = 0
      c.refuseBy = undefined
    }
  }
  return c
}

/** 她们立的任务还在：他自己说"忙完了"不算数。 */
export const TASK_VERIFY_MS = 2 * 3_600_000

/**
 * 她们立的任务还没验收：到点前，以及到点后两小时内（等姐姐验收；两小时是兜底，别把人锁死）。
 * 这段时间他说"忙完了"不算数，娱乐继续锁。
 */
export function aiTask(config, now = Date.now()) {
  const c = config || {}
  return c.gear === 'busy' && Boolean(c.taskBy) && Number(now) - Number(c.gearUntil) < TASK_VERIFY_MS
}

/** 拼进两人的聊天人设。 */
export function gearLines(config, now = Date.now()) {
  if (effectiveGear(config, now) !== 'busy') return []
  const left = Math.max(1, Math.round((Number(config.gearUntil) - Number(now)) / 60_000))
  if (config.taskBy) {
    return ['任务模式（' + (config.taskBy === 'strategist' ? '鲸澜' : config.taskBy === 'companion' ? '鱼丸' : '你们') + '立的）：' + (config.taskGoal || config.gearWhat) +
      '，还剩约 ' + left + ' 分钟' + (config.taskReason ? '；立的理由：' + config.taskReason : '') + '。娱乐全锁。' +
      '他说做完了：要证据（讲清做到哪 / 截图 / 你读屏看），姐姐验收合格才 end_task_mode；他求情、讲条件都不松。']
  }
  return ['他现在在忙：' + (config.gearWhat || '正事') + '，还剩约 ' + left + ' 分钟（他自己定的）。他来找你闲扯就短回一句、催他回去；正事照常聊。娱乐 App 现在一开就会被锁。']
}
