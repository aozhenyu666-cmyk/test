// dsh-go-companion · 手边的情况（手机现状）
//
// 观察球要有观察的东西：现在开着哪个 App、开了多久、今天娱乐类刷了多久、在不在充电。
// 这些由手机在 /request 里带过来（App 侧 NowFeed，无障碍事件喂的，不读屏幕文字）。
// 没带、或无障碍没连上，就是"不知道"——不是"没在用手机"，prompt 里一个字都不写。

/** 常见 App 的中文名：手机侧拿不到 label 时兜底。 */
const LABELS = {
  'tv.danmaku.bili': 'B站',
  'com.ss.android.ugc.aweme': '抖音',
  'com.ss.android.ugc.aweme.lite': '抖音极速版',
  'com.smile.gifmaker': '快手',
  'com.kuaishou.nebula': '快手极速版',
  'com.xingin.xhs': '小红书',
  'com.sina.weibo': '微博',
  'com.zhihu.android': '知乎',
  'com.tencent.mm': '微信',
  'com.tencent.mobileqq': 'QQ',
  'com.tencent.tmgp.sgame': '王者荣耀',
  'com.taobao.taobao': '淘宝',
  'com.tencent.qqlive': '腾讯视频',
  'com.qiyi.video': '爱奇艺',
  'com.youku.phone': '优酷',
}

/** 娱乐类：手机没标时按包名兜底判。 */
const FUN = new Set([
  'tv.danmaku.bili',
  'com.ss.android.ugc.aweme',
  'com.ss.android.ugc.aweme.lite',
  'com.smile.gifmaker',
  'com.kuaishou.nebula',
  'com.xingin.xhs',
  'com.sina.weibo',
  'com.tencent.tmgp.sgame',
  'com.tencent.qqlive',
  'com.qiyi.video',
  'com.youku.phone',
])

/** 开着同一个娱乐 App 这么久，才算"值得说一句"。 */
export const FUN_DWELL_MIN = 10
/** 今天娱乐类累计到这么多，也值得说。 */
export const FUN_TODAY_MIN = 60

function pkgOf(raw) {
  const s = typeof raw === 'string' ? raw.trim() : ''
  return /^[A-Za-z0-9_.]{1,160}$/.test(s) ? s : ''
}

function labelOf(pkg, raw) {
  const s = typeof raw === 'string' ? raw.trim().slice(0, 40) : ''
  if (s && s !== pkg) return s
  return LABELS[pkg] || pkg
}

function minutesOf(raw) {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? Math.min(24 * 60, Math.round(n)) : 0
}

function funOf(pkg, raw) {
  return raw === true || isFun(pkg)
}

/** 纯按包名判娱乐类；周报脚本用它算每天娱乐 App 分钟数。 */
export function isFun(pkg) {
  return FUN.has(pkgOf(pkg))
}

/**
 * 洗一遍手机带来的现状。认不出来就返回 null，prompt 里不写。
 *
 * @param {unknown} raw  {app, label, minutes, fun, screenOn, charging, local, today:[{app,label,minutes,fun}]}
 */
export function cleanPhone(raw) {
  if (!raw || typeof raw !== 'object') return null
  const app = pkgOf(raw.app)
  const today = []
  if (Array.isArray(raw.today)) {
    for (const t of raw.today.slice(0, 8)) {
      if (!t || typeof t !== 'object') continue
      const pkg = pkgOf(t.app)
      const minutes = minutesOf(t.minutes)
      if (!pkg || !minutes) continue
      const opens = Number.isFinite(Number(t.opens)) && Number(t.opens) > 0 ? Math.min(999, Math.round(Number(t.opens))) : undefined
      today.push({ app: pkg, label: labelOf(pkg, t.label), minutes, fun: funOf(pkg, t.fun), ...(opens ? { opens } : {}) })
    }
  }
  const local = typeof raw.local === 'string' && /^\d{2}:\d{2}$/.test(raw.local) ? raw.local : ''
  const out = {
    app,
    label: app ? labelOf(app, raw.label) : '',
    minutes: app ? minutesOf(raw.minutes) : 0,
    fun: app ? funOf(app, raw.fun) : false,
    screenOn: raw.screenOn !== false,
    charging: raw.charging === true,
    local,
    today,
    switches: cleanSwitches(raw.switches),
  }
  if (!out.app && !today.length && !out.charging && !local) return null
  return out
}

/** 走神前兆：90 秒 / 15 分钟里切了几次 App（手机每次切前台都记）。 */
function cleanSwitches(raw) {
  if (!raw || typeof raw !== 'object') return null
  const n = (v) => Math.max(0, Math.min(500, Math.round(Number(v) || 0)))
  const apps = Array.isArray(raw.apps90s) ? raw.apps90s.map((a) => String(a).slice(0, 30)).filter(Boolean).slice(0, 8) : []
  return { in90s: n(raw.in90s), apps90s: apps, in15m: n(raw.in15m), distinct15m: n(raw.distinct15m) }
}

/** 今天娱乐类一共多少分钟。 */
export function funToday(phone) {
  if (!phone) return 0
  let n = 0
  for (const t of phone.today) if (t.fun) n += t.minutes
  return n
}

/**
 * 手边的情况够不够打一个观察球：正开着娱乐 App 一阵子了，或者今天娱乐类已经刷了很久。
 * 够就让这一球优先打观察（见 index.js compose）。
 */
export function worthObserving(phone) {
  if (!phone) return false
  if (phone.fun && phone.minutes >= FUN_DWELL_MIN) return true
  return funToday(phone) >= FUN_TODAY_MIN
}

/** 拼进 prompt 的几行。只写事实，判断留给模型。 */
export function phoneLines(phone) {
  if (!phone) return []
  const facts = []
  if (phone.app) {
    const dwell = phone.minutes > 0 ? '，已经开了 ' + phone.minutes + ' 分钟' : '，刚打开'
    facts.push('现在开着：' + phone.label + dwell + (phone.fun ? '（娱乐类）' : ''))
  } else if (!phone.screenOn) {
    facts.push('现在屏幕是灭的')
  }
  const fun = phone.today.filter((t) => t.fun)
  if (fun.length) {
    const list = fun.slice(0, 3).map((t) => t.label + ' ' + t.minutes + ' 分钟').join('、')
    facts.push('今天娱乐类累计 ' + funToday(phone) + ' 分钟（' + list + '）')
  }
  // 今天整体用在哪（巡逻时判断他是不是在晃）：前 5 个，不只娱乐类。
  const top = [...phone.today].sort((a, b) => b.minutes - a.minutes).slice(0, 5).filter((t) => t.minutes > 0)
  if (top.length) facts.push('今天手机用得最多：' + top.map((t) => t.label + ' ' + t.minutes + ' 分钟' + (t.opens ? '（打开 ' + t.opens + ' 次）' : '')).join('、'))
  if (phone.charging) facts.push('手机正在充电')
  if (phone.local) facts.push('他那边现在 ' + phone.local)
  if (!facts.length) return []
  const lines = ['手边的情况（手机刚读到的事实，拿它说出你的判断，不要逐条念，也别说"我看到你的手机"）：']
  for (const f of facts) lines.push('- ' + f)
  return lines
}
