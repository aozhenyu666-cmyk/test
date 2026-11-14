// dsh-go-companion · 四级分层应用管控（Tier 0-3）与 Tier 1 语境豁免 / degraded_escape（M3.2）
//
// Tier 0: 永不拦截基线（计算器、相机、时钟、电话短信、设置、支付/银行、医疗健康、紧急通信）
// Tier 1: 按语境放行（相册、文件管理、微信、学习/工作软件），带 <=90 秒临时语境豁免窗
// Tier 2: 可疑游走/未知应用/浏览器（在 Tier 2 内滞留 >120 秒或 3 分钟内频繁跳切 >=4 个 App -> degraded_escape）
// Tier 3: 强管控高成瘾黑名单（短视频、游戏、纯消费型 Feed 与用户声明娱乐 App）

import { cleanApps, emergencyPackages, roleOf } from './apps.js'
import { isFun } from './phone.js'

export const TIER_EXEMPT = 0
export const TIER_CONTEXTUAL = 1
export const TIER_AMBIGUOUS = 2
export const TIER_BLACKLIST = 3

export const CONTEXT_EXEMPT_MS = 90_000
export const TIER2_WANDER_MS = 120_000
export const HOP_WINDOW_MS = 3 * 60_000
export const HOP_DISTINCT_THRESHOLD = 4

const TIER0_PREFIXES = [
  'com.android.calculator', 'com.google.android.calculator', 'com.miui.calculator',
  'com.android.camera', 'com.google.android.GoogleCamera', 'org.codeaurora.snapcam', 'com.sec.android.app.camera',
  'com.android.deskclock', 'com.google.android.deskclock', 'com.android.alarmclock',
  'com.android.settings', 'com.android.systemui',
  'com.eg.android.AlipayGphone', 'com.unionpay', 'cmb.pb', 'com.icbc', 'com.chinamworld.main',
  'com.android.providers.downloads', 'com.google.android.apps.safetyhub',
]

const TIER1_GALLERY_OR_FILES = [
  'com.miui.gallery', 'com.google.android.apps.photos', 'com.android.gallery3d',
  'com.vivo.gallery', 'com.coloros.gallery3d', 'com.huawei.photos', 'com.sec.android.gallery3d',
  'com.android.documentsui', 'com.google.android.documentsui', 'com.google.android.apps.nbu.files',
  'com.android.fileexplorer', 'com.mi.android.globalFileexplorer',
]

const TIER1_COMM_OR_WORK = [
  'com.tencent.mm', 'com.tencent.mobileqq', 'md.obsidian', 'notion.id',
  'com.alibaba.android.rimet', 'com.ss.android.lark', 'com.tencent.wework',
  'com.microsoft.office.word', 'com.wps.moffice_eng', 'cn.wps.moffice_eng',
  'com.anki.ankidroid', 'com.ichi2.anki',
]

const TIER2_BROWSER_OR_LAUNCHER = [
  'com.android.chrome', 'org.mozilla.firefox', 'com.microsoft.emmx', 'mark.via',
  'com.quark.browser', 'com.UCMobile', 'com.heytap.browser', 'com.miui.home',
  'com.sec.android.app.launcher', 'com.google.android.apps.nexuslauncher', 'com.android.launcher3',
]

const TIER3_GAMES_OR_FEED = [
  'com.miHoYo.', 'com.tencent.tmgp.', 'com.netease.', 'com.riotgames.',
  'com.bilibili.app.in', 'com.zhiliaoapp.musically', 'com.ss.android.ugc.trill',
]

const TASK_OCR_RE = /(题|代码|算法|函数|笔记|文档|报错|异常|论文|摘要|目录|第\s*\d+\s*[章页节题]|SELECT|const |function )/i
const LEISURE_FEED_RE = /(朋友圈|发现页|推荐视频|直播中|点赞\s*\d+|打赏|热搜榜|关注作者|短剧)/

function pkgStr(p) {
  return String(p || '').trim()
}

export function isGalleryOrFilePkg(pkg) {
  const s = pkgStr(pkg)
  if (!s) return false
  if (TIER1_GALLERY_OR_FILES.includes(s)) return true
  return /(gallery|photos|album|documentsui|filemanager|fileexplorer)/i.test(s)
}

/**
 * 判定单个应用属于 Tier 0–3 的哪一层。
 * @returns {{tier:number, category:string, reason:string}}
 */
export function classifyAppTier(pkg, declaredApps = []) {
  const s = pkgStr(pkg)
  if (!s) return { tier: TIER_AMBIGUOUS, category: 'unknown', reason: 'empty-package' }
  const cleanDecl = cleanApps(declaredApps)
  const declaredRole = roleOf(cleanDecl, s)
  if (declaredRole === 'emergency' || emergencyPackages(cleanDecl).includes(s)) {
    return { tier: TIER_EXEMPT, category: 'emergency-or-utility', reason: 'tier0-emergency' }
  }
  if (TIER0_PREFIXES.some((prefix) => s === prefix || s.startsWith(prefix))) {
    return { tier: TIER_EXEMPT, category: 'utility', reason: 'tier0-baseline' }
  }
  if (/(inputmethod|keyboard|gboard|sogou\.inputmethod|iflytek\.inputmethod)/i.test(s)) {
    return { tier: TIER_EXEMPT, category: 'ime', reason: 'tier0-ime' }
  }
  if (declaredRole === 'fun' || isFun(s) || TIER3_GAMES_OR_FEED.some((p) => s.startsWith(p))) {
    return { tier: TIER_BLACKLIST, category: 'fun', reason: 'tier3-blacklist' }
  }
  if (declaredRole === 'work' || declaredRole === 'tool' || TIER1_COMM_OR_WORK.includes(s)) {
    return { tier: TIER_CONTEXTUAL, category: 'work-or-comm', reason: 'tier1-contextual' }
  }
  if (isGalleryOrFilePkg(s)) {
    return { tier: TIER_CONTEXTUAL, category: 'gallery-or-files', reason: 'tier1-media-asset' }
  }
  if (TIER2_BROWSER_OR_LAUNCHER.some((p) => s === p || s.startsWith(p)) || /browser|launcher/i.test(s)) {
    return { tier: TIER_AMBIGUOUS, category: 'browser-or-launcher', reason: 'tier2-ambiguous' }
  }
  return { tier: TIER_AMBIGUOUS, category: 'other', reason: 'tier2-default' }
}

/**
 * Tier 1 语境豁免判定：
 * 用户刚截图、分享 Intent、从正事切到相册/文件管理且 <=90 秒，或屏幕文字包含题目/文档锚点时给予 90 秒豁免；
 * 超过 90 秒或画面转为纯粹刷图/娱乐 feed 时立刻收回豁免。
 */
export function evaluateContextualExemption(prevState = {}, input = {}, now = Date.now()) {
  const s = prevState && typeof prevState === 'object' ? { ...prevState } : {}
  const pkg = pkgStr(input.pkg || input.phone?.app)
  const screenText = String(input.screenText || input.phone?.screenText || '')
  const tierInfo = classifyAppTier(pkg, input.declaredApps)

  if (tierInfo.tier !== TIER_CONTEXTUAL) {
    return {
      exempt: false,
      reclaimed: Boolean(s.until && Number(s.until) > now),
      reason: 'not-tier1',
      until: 0,
      state: { pkg: '', until: 0, grantedAt: 0, reason: '' },
    }
  }

  // 一旦发现画面已是纯休闲 feed（朋友圈/推荐流等），立即回收豁免
  if (screenText && LEISURE_FEED_RE.test(screenText)) {
    return {
      exempt: false,
      reclaimed: true,
      reason: 'leisure-feed-detected',
      until: 0,
      state: { pkg, until: 0, grantedAt: 0, reason: 'leisure-feed-detected' },
    }
  }

  // 检查已有同包名豁免是否仍未超时
  if (s.pkg === pkg && Number(s.until) > now) {
    return {
      exempt: true,
      reclaimed: false,
      reason: String(s.reason || 'active-window'),
      until: Number(s.until),
      state: s,
    }
  }

  const recentScreenshot = Number(input.recentScreenshotAt) > 0 && now - Number(input.recentScreenshotAt) <= CONTEXT_EXEMPT_MS
  const recentShare = Number(input.recentShareAt) > 0 && now - Number(input.recentShareAt) <= CONTEXT_EXEMPT_MS
  const recentWork = Number(input.recentWorkAt) > 0 && now - Number(input.recentWorkAt) <= CONTEXT_EXEMPT_MS && isGalleryOrFilePkg(pkg)
  const ocrStudyMatch = Boolean(screenText && TASK_OCR_RE.test(screenText))

  if (recentScreenshot || recentShare || recentWork || ocrStudyMatch) {
    const why = recentShare ? 'share-intent' : recentScreenshot ? 'recent-screenshot' : ocrStudyMatch ? 'ocr-task-context' : 'work-switch'
    const next = { pkg, grantedAt: now, until: now + CONTEXT_EXEMPT_MS, reason: why }
    return {
      exempt: true,
      reclaimed: false,
      reason: why,
      until: next.until,
      state: next,
    }
  }

  return {
    exempt: false,
    reclaimed: s.pkg === pkg && Number(s.until) > 0 && Number(s.until) <= now,
    reason: Number(s.until) > 0 ? 'window-expired' : 'no-task-context',
    until: 0,
    state: { pkg, until: 0, grantedAt: 0, reason: 'expired-or-none' },
  }
}

/**
 * Tier 2 降级逃避检测（degraded_escape）：
 * 1) 在 Tier 2 未知/桌面/浏览器内连续滞留 > 120 秒
 * 2) 近 3 分钟内在 >= 4 个非 Tier 0 应用之间高频来回跳切
 */
export function detectDegradedEscape(state = {}, { phone, declaredApps = [], now = Date.now() } = {}) {
  const s = state && typeof state === 'object' ? { ...state } : {}
  const pkg = pkgStr(phone?.app)
  const tierInfo = classifyAppTier(pkg, declaredApps)

  let dwellPkg = String(s.dwellPkg || '')
  let dwellSince = Number(s.dwellSince) || 0
  if (pkg && tierInfo.tier === TIER_AMBIGUOUS) {
    if (dwellPkg !== pkg || !dwellSince) {
      dwellPkg = pkg
      dwellSince = now
    }
  } else {
    dwellPkg = ''
    dwellSince = 0
  }

  const dwellMs = dwellSince > 0 ? Math.max(0, now - dwellSince) : 0
  const recentStream = Array.isArray(phone?.switches?.recent60m) && phone.switches.recent60m.length
    ? phone.switches.recent60m
    : (Array.isArray(phone?.switches?.recent) ? phone.switches.recent : [])

  const in3m = recentStream.filter((x) => Number(x?.agoMs) <= HOP_WINDOW_MS)
  const distinct3m = new Set(
    in3m.map((x) => pkgStr(x.app)).filter((p) => p && classifyAppTier(p, declaredApps).tier !== TIER_EXEMPT),
  )

  if (distinct3m.size >= HOP_DISTINCT_THRESHOLD) {
    return {
      degraded: true,
      reason: 'rapid-hopping',
      distinctApps: distinct3m.size,
      dwellSeconds: Math.round(dwellMs / 1000),
      nextState: { dwellPkg, dwellSince, lastTriggerAt: now },
    }
  }

  if (tierInfo.tier === TIER_AMBIGUOUS && dwellMs > TIER2_WANDER_MS) {
    return {
      degraded: true,
      reason: 'tier2-dwell',
      distinctApps: distinct3m.size,
      dwellSeconds: Math.round(dwellMs / 1000),
      nextState: { dwellPkg, dwellSince, lastTriggerAt: now },
    }
  }

  return {
    degraded: false,
    reason: '',
    distinctApps: distinct3m.size,
    dwellSeconds: Math.round(dwellMs / 1000),
    nextState: { dwellPkg, dwellSince, lastTriggerAt: Number(s.lastTriggerAt) || 0 },
  }
}
