// dsh-go-companion · Gemini Live 搭桥
//
// 他有 Gemini App（Pro），Live 能实时语音、看屏幕——这是鱼丸 / 鲸澜做不到的高频（DSH 一句话两三秒）。
// 但 Gemini 是封闭 App：拿不到他的设定、计划、记忆，也不会把聊过的东西交回来。这里补两头：
//   去：gemini_live 生成一段"给 Gemini 的背景"（他是谁、这次干嘛、怎么盯他、结束怎么复盘），
//       姐妹用手机工具写进剪贴板、打开 Gemini，能点就替他粘贴发出再点 Live。
//   回：连麦结束后，姐姐在后台屏打开 Gemini 读回那段对话（找【复盘】），gemini_log 存进笔记库，
//       两人之后的每一轮都看得到。
// 连麦期间：他在 Gemini 里就不发球；中途跑去娱乐 App 照拒绝模式锁（锁的界面由手机端负责）。

export const GEMINI_PACKAGES = ['com.google.android.apps.bard', 'com.google.android.googlequicksearchbox']

export const GEMINI_MODES = {
  study: '学习陪练：围绕主题提问、让他讲给你听、抽查他是不是真懂，讲错了直接指出。',
  think: '共脑：帮他把这件事想清楚——反问、摆出两三个选项、逼他给出判断和理由。',
  interrogate: '审讯：他刚才在摸鱼被抓了。先问他刚才在干嘛、为什么，再逼他说现在要做什么、几点前做完；语气硬，不接受"等会儿""差不多"。',
}

export function geminiMode(raw) {
  return GEMINI_MODES[raw] ? raw : 'think'
}

/** 给 Gemini 的背景（写给 Gemini 看，他会粘贴进 Gemini 再开 Live）。 */
export function geminiBrief({ owner = '他', topic = '', minutes = 30, mode = 'think', about = [], commitments = [], recap = null, gear = null } = {}) {
  const m = geminiMode(mode)
  const lines = [
    '【给 Gemini 的背景，读完只回一句"收到，开始吧"】',
    '接下来 ' + minutes + ' 分钟你是' + owner + '的语音搭档，他会开 Live 跟你说话。',
    '这次要做：' + (String(topic).trim() || '他手上最要紧的那件事') + '。',
    '方式：' + GEMINI_MODES[m],
  ]
  const facts = about.map((x) => String(x).trim()).filter(Boolean).slice(0, 8)
  if (facts.length) {
    lines.push('关于他（别复述，用上就行）：')
    for (const f of facts) lines.push('- ' + f.slice(0, 160))
  }
  if (commitments.length) {
    lines.push('他答应过还没做完的：')
    for (const c of commitments.slice(0, 3)) lines.push('- ' + String(c).slice(0, 160))
  }
  if (gear) lines.push('他现在在忙：' + gear + '。')
  if (recap?.summary) lines.push('上次跟你连麦的复盘：' + String(recap.summary).slice(0, 200) + (recap.next ? '；说好的下一步：' + String(recap.next).slice(0, 120) : ''))
  lines.push(
    '规则：',
    '- 每次只说一两句，口语，像连麦，不要长篇大论、不要列清单。',
    '- 你来推进：他沉默、跑题、含糊（"差不多""应该吧"）就追问到具体，把他拉回主题。',
    '- 他说想去玩、想休息、要走：先问为什么，理由站不住就不同意，告诉他还剩多少时间。',
    '- 每过 10 分钟用一句"到现在为止：……"小结一次。',
    '- 时间到或他说结束时，按这个格式说一段（别省）：【复盘】做了什么：…… 卡在哪：…… 下一步：……',
  )
  return lines.join('\n').slice(0, 1800)
}

/** 走神：同一个娱乐 App 坐够这么久，才值得把他从里面拉出来。 */
export const DRIFT_DWELL_MIN = 10
/** 一天最多拉几次（第四轮 R4-6 定的边界，不跟着节奏配置走）。 */
export const DRIFT_MAX_PER_DAY = 2
/** 拉进去审多久。 */
export const DRIFT_MINUTES = 15

/**
 * 走神强拉（第四轮 R4-6）：他标着忙时，人却在娱乐 App 里坐着——直接开一场审讯式 Gemini Live。
 * 边界（文档 L137-139）：只在白天、忙时（= 在做正事）、同一个娱乐 App 坐够 dwellMin 分钟；
 * 深夜、吃饭、放风、已经开着连麦、今天拉够两次、管控关掉，一律不拉。
 * @returns {{app, label, dwell, topic, minutes, mode}|null}
 */
export function driftPull({ phone, now = Date.now(), day = 0, gear, busyWhat = '', night = null, meal = null,
  onBreak = false, session = null, pulls = [], dwellMin = DRIFT_DWELL_MIN } = {}) {
  if (!phone?.app || !phone.fun) return null
  if (gear !== 'busy') return null
  if (night) return null
  if (meal) return null
  if (onBreak) return null
  if (session) return null
  const dwell = Math.max(0, Math.round(Number(phone.minutes) || 0))
  if (dwell < Math.max(5, Math.round(Number(dwellMin) || DRIFT_DWELL_MIN))) return null
  const today = (Array.isArray(pulls) ? pulls : []).filter((p) => Number(p?.at) >= Number(day) && Number(p?.at) <= Number(now))
  if (today.length >= DRIFT_MAX_PER_DAY) return null
  const label = String(phone.label || phone.app)
  const what = String(busyWhat || '该做的事').slice(0, 20)
  return {
    app: phone.app,
    label,
    dwell,
    // 审讯的靶子就是他刚才干的事，别让他含糊过去。
    topic: '先解释刚才为什么刷' + label + ' ' + dwell + ' 分钟，再回去接着' + what,
    minutes: DRIFT_MINUTES,
    mode: 'interrogate',
  }
}

/** 交给模型的下一步（tool 结果里）。 */
export function geminiSteps() {
  return [
    '1. mcp__phone__clipboard set=上面的背景全文',
    '2. mcp__phone__open app=Gemini（打不开再试 com.google.android.apps.bard）',
    '3. 能读屏就替他做完：mcp__phone__screen 找到输入框，mcp__phone__type 粘贴背景发出去，再点 Live 按钮；做不了就一句话告诉他"背景在剪贴板，粘贴发出去再点 Live"。',
    '4. 跟他说一句这场连麦要干嘛、多久，然后就别打扰他了。',
  ].join('\n')
}

/** 他在 Gemini 里吗。 */
export function inGemini(phone, packages = GEMINI_PACKAGES) {
  return Boolean(phone?.app && phone.screenOn !== false && packages.includes(phone.app))
}

/**
 * 每次 /plan 更新一次连麦状态（state 存在 companion/gemini.json）：
 *   inSince / lastInAt：这一段待在 Gemini 里从什么时候到什么时候（手机带来的停留分钟数校准开头）。
 *   session：gemini_live 开的那一场（topic / mode / until）。
 * 返回 { state, inside, recap, left }：
 *   recap：该把刚才那段读回来了（在 Gemini 里待了 ≥5 分钟、出来 ≥3 分钟、这一段还没复盘、一小时内）。
 *   left：开着的那一场还没到点他就出来了（出来 ≥2 分钟）。
 */
export function trackGemini(state, phone, now, packages = GEMINI_PACKAGES) {
  const s = { ...(state || {}) }
  const inside = inGemini(phone, packages)
  if (inside) {
    const minutes = Math.max(0, Number(phone.minutes) || 0)
    const continuing = Number(s.lastInAt) > 0 && now - Number(s.lastInAt) < 5 * 60_000
    s.inSince = continuing ? Number(s.inSince) || now : now - minutes * 60_000
    s.lastInAt = now
  }
  const session = s.session && Number(s.session.until) > now ? s.session : null
  if (s.session && !session) s.session = { ...s.session, ended: true }
  const stretch = Number(s.lastInAt) - Number(s.inSince)
  const out = now - Number(s.lastInAt)
  const recap = !inside && Number(s.lastInAt) > 0 && stretch >= 5 * 60_000 && out >= 3 * 60_000 && out < 60 * 60_000 &&
    Number(s.recappedFor) !== Number(s.inSince) && !session
  const left = !inside && Boolean(session) && Number(s.lastInAt) >= Number(session.startedAt) && out >= 2 * 60_000 &&
    now - (Number(s.nagAt) || 0) >= 5 * 60_000
  return { state: s, inside, recap, left, session }
}

/** 一条复盘写成 markdown（追加进笔记库 Gemini/日期.md）。 */
export function recapMarkdown(r, at) {
  const t = new Date(Number(at) || Date.now()).toISOString().replace('T', ' ').slice(0, 16) + ' UTC'
  const lines = ['', '## ' + t + (r.topic ? ' · ' + r.topic : ''), '', '- 做了什么：' + r.summary]
  if (r.stuck) lines.push('- 卡在哪：' + r.stuck)
  if (r.next) lines.push('- 下一步：' + r.next)
  for (const q of r.quotes || []) lines.push('> ' + String(q).slice(0, 200))
  return lines.join('\n') + '\n'
}

/** 拼进两人的聊天人设：最近一次连麦复盘 + 正在进行的那一场。 */
export function geminiLines(state, now = Date.now()) {
  const s = state || {}
  const lines = []
  if (s.session && Number(s.session.until) > now) {
    lines.push('他正在跟 Gemini 连麦（' + (s.session.topic || '没说主题') + '，还剩约 ' + Math.max(1, Math.round((s.session.until - now) / 60_000)) + ' 分钟）：他来找你就让他回去接着连。')
  }
  const r = Array.isArray(s.recaps) ? s.recaps[s.recaps.length - 1] : null
  if (r && now - Number(r.at) < 3 * 86_400_000) {
    lines.push('他最近一次跟 Gemini 连麦（' + new Date(Number(r.at)).toISOString().slice(5, 16).replace('T', ' ') + ' UTC' + (r.topic ? '，' + r.topic : '') + '）：' +
      String(r.summary).slice(0, 200) + (r.stuck ? '；卡在：' + String(r.stuck).slice(0, 80) : '') + (r.next ? '；下一步：' + String(r.next).slice(0, 80) : '') + '。可以接着这个话题。')
  }
  return lines
}
