// dsh-go-companion · App 分层（2026-11-18）
//
// 四层：
//   base 系统底座：永远不锁（never_lock.js 那一层，另加 iQOO 的相册 / 文件 / 选图选文件页 / 分享页 / 相机 / 截屏）
//   work 工作：干活时直接放行
//   dual 双用途（缺口）：干活时要先跟鲸澜申请通行证（任务租约）；自由态占娱乐额度
//   fun  娱乐 / game 游戏：干活时锁；自由态花余额（挣来制：验收挣分钟、跨天结转；游戏每天上限 60）
//   other 其余：干活时不在白名单就锁；自由态不管、不计额度
//
// 他在 Obsidian 的 大肥鱼/墙/App分层.md 里改：改严（往 dual / fun / game 挪）立即生效，
// 改松（往 work 挪）24 小时后生效。AI 只能改严；改松要拿他刚说过的原话。

import { isNeverLockPkg } from './never_lock.js'
import { DUAL_USE_PACKAGES, isPureGame, classifyApp } from './app_resolver.js'

export const TIERS = ['work', 'dual', 'fun', 'game']
/** 越往后越严。 */
export const TIER_RANK = { work: 0, other: 1, dual: 2, fun: 3, game: 4 }
export const TIER_LABEL = { base: '系统底座', work: '工作', dual: '双用途', fun: '娱乐', game: '游戏', other: '其他' }

/** iQOO 12 / OriginOS 的系统页面（真机核对后再补）。 */
export const BASE_EXTRA = Object.freeze([
  'com.vivo.gallery', 'com.android.filemanager', 'com.vivo.assistant', 'com.vivo.smartshot',
  'com.android.providers.media.module', 'com.google.android.providers.media.module',
  'com.android.documentsui', 'com.google.android.documentsui', 'com.android.intentresolver',
  'com.android.camera', 'com.android.settings', 'com.android.deskclock', 'com.android.calculator2',
])

export const WORK_DEFAULT = Object.freeze([
  'com.google.android.apps.bard', 'com.google.android.googlequicksearchbox', 'com.openai.chatgpt', 'com.anthropic.claude',
  'com.baidu.netdisk', 'com.hpbr.bosszhipin', 'md.obsidian', 'cn.wps.moffice_eng',
])

export const NEUTRAL_COMM_PACKAGES = Object.freeze(['com.tencent.mm', 'com.tencent.mobileqq'])

export const DEFAULT_NEUTRAL_WORK_QUOTA_MIN = 35

export const TASK_APP_SCOPES = Object.freeze({
  exam: Object.freeze({
    key: 'exam',
    label: '刷题/行测',
    packages: Object.freeze([
      'com.fenbi.android.solar', 'com.fenbi.android.leo', 'com.huatu.ztk', 'com.offcn.android',
      'com.ichi2.anki', 'com.duolingo', 'com.youdao.dict', 'com.eudic',
      'md.obsidian', 'cn.wps.moffice_eng', 'notion.id', 'com.baidu.netdisk',
      'com.google.android.apps.bard', 'com.google.android.googlequicksearchbox',
    ]),
    hintRe: /(粉笔|华图|中公|题库|刷题|行测|做题|模考|词典|翻译|单词|百词斩|墨墨|不背|anki|duolingo|dict|eudic|youdao|fenbi|huatu|offcn|obsidian|notion|wps|学习|笔记)/i,
  }),
  write: Object.freeze({
    key: 'write',
    label: '写作/笔记',
    packages: Object.freeze([
      'md.obsidian', 'cn.wps.moffice_eng', 'notion.id', 'com.baidu.netdisk',
      'com.ss.android.lark', 'com.alibaba.android.rimet',
      'com.google.android.apps.bard', 'com.google.android.googlequicksearchbox', 'com.openai.chatgpt', 'com.anthropic.claude',
    ]),
    hintRe: /(笔记|写作|申论|文档|word|obsidian|notion|wps|网盘|netdisk|语雀|石墨|飞书|钉钉|typora|flomo|markdown|doc|note)/i,
  }),
  think: Object.freeze({
    key: 'think',
    label: '深度共脑(含Gemini)',
    packages: Object.freeze([
      'com.google.android.apps.bard', 'com.google.android.googlequicksearchbox', 'com.openai.chatgpt', 'com.anthropic.claude',
      'md.obsidian', 'notion.id', 'cn.wps.moffice_eng',
    ]),
    hintRe: /(gemini|bard|chatgpt|claude|共脑|思考|脑图|xmind|obsidian|notion|flomo|笔记)/i,
  }),
  default: Object.freeze({
    key: 'default',
    label: '常规学习',
    packages: Object.freeze([
      ...WORK_DEFAULT,
      'com.ichi2.anki', 'com.duolingo', 'notion.id',
    ]),
    hintRe: /(学习|笔记|词典|题库|文档|工作|obsidian|notion|wps|anki|duolingo|netdisk|bard|chatgpt|claude)/i,
  }),
})

function detectTaskScopeKey(taskTextOrKind = '') {
  const raw = String(taskTextOrKind || '').trim()
  const low = raw.toLowerCase()
  if (low === 'exam' || /(刷题|行测|做题|真题|模考|背词|背单词|单词|题库|考研|公考|考公|国考|省考|复盘错题|anki|quiz|exam)/i.test(raw)) {
    return 'exam'
  }
  if (low === 'write' || /(写作|申论|笔记|大作文|周报|复盘|文档|草稿|论文|简历|write|essay|note|doc)/i.test(raw)) {
    return 'write'
  }
  if (low === 'think' || /(共脑|gemini|连麦|深度思考|推演|构思|讨论|头脑风暴|think|brainstorm)/i.test(raw)) {
    return 'think'
  }
  return 'default'
}

/**
 * 4.3 预设任务 App 放行范围（开局零手选）：
 * 按标题/类型归类并从 installed + WORK_DEFAULT + extra 中筛选允许包名，
 * 硬过滤掉 tierOf(p, { overrides }) 为 'fun' 或 'game' 的包名。
 */
export function resolveScopeApps(taskTextOrKind = '', { installed = [], overrides = {}, extra = [] } = {}) {
  const scopeKey = detectTaskScopeKey(taskTextOrKind)
  const scope = TASK_APP_SCOPES[scopeKey] || TASK_APP_SCOPES.default
  const labelMap = new Map()
  const matchedInstalled = []

  for (const item of Array.isArray(installed) ? installed : []) {
    const pkg = typeof item === 'string' ? item.trim() : String(item?.pkg || '').trim()
    const label = typeof item === 'object' && item ? String(item.label || '').trim() : ''
    if (!pkg) continue
    if (label) labelMap.set(pkg, label)
    const t = tierOf(pkg, { label, overrides })
    if (t === 'fun' || t === 'game') continue
    if (t === 'work' || scope.packages.includes(pkg) || scope.hintRe.test(`${pkg} ${label}`)) {
      matchedInstalled.push(pkg)
    }
  }

  const rawCandidates = [
    ...scope.packages,
    ...WORK_DEFAULT,
    ...matchedInstalled,
    ...(Array.isArray(extra) ? extra : []),
  ]

  const allow = []
  const seen = new Set()
  for (const item of rawCandidates) {
    const pkg = String(item || '').trim()
    if (!pkg || seen.has(pkg)) continue
    seen.add(pkg)
    const label = labelMap.get(pkg) || ''
    const tWithLabel = tierOf(pkg, { label, overrides })
    const tBase = tierOf(pkg, { overrides })
    if (tWithLabel === 'fun' || tWithLabel === 'game' || tBase === 'fun' || tBase === 'game') continue
    allow.push(pkg)
  }

  return { scopeKey, scopeLabel: scope.label, allow }
}

export const DUAL_DEFAULT = Object.freeze([
  'com.android.chrome', 'com.vivo.browser', 'com.quark.browser', 'com.tencent.mm', 'com.tencent.mobileqq',
  ...DUAL_USE_PACKAGES,
])

export function isBasePkg(pkg) {
  const s = String(pkg || '').trim()
  return isNeverLockPkg(s) || BASE_EXTRA.includes(s)
}

/** overrides: {pkg: 'work'|'dual'|'fun'|'game'}（他在分层文件里写的 + AI 改严的）。 */
export function tierOf(pkg, { label = '', overrides = {}, declaredApps = [], system = false } = {}) {
  const p = String(pkg || '').trim()
  if (isBasePkg(p)) return 'base'
  const o = overrides && typeof overrides === 'object' ? overrides[p] : undefined
  if (TIERS.includes(o)) return o
  if (WORK_DEFAULT.includes(p)) return 'work'
  if (isPureGame(p, label)) return 'game'
  if (DUAL_DEFAULT.includes(p)) return 'dual'
  const cat = classifyApp(p, { label, declaredApps })
  if (cat === 'game') return 'game'
  if (cat === 'fun') return 'fun'
  if (cat === 'work' || cat === 'emergency') return 'work'
  // 手机报了「系统自带」且没被认成娱乐：按底座处理，宁可放过也不锁错。
  if (system) return 'base'
  return 'other'
}

// ---------------------------------------------------------------- 分层文件

export const TIER_FILE = 'App分层.md'
const HEAD = { work: '工作', dual: '双用途', fun: '娱乐', game: '游戏' }

export function renderTierFile(overrides = {}, labels = {}) {
  const lines = [
    '# App 分层（大肥鱼）',
    '',
    '每行一个包名，后面可以写中文名。挪到更严的一层立即生效；挪到「工作」要等 24 小时。',
    '系统页面（桌面、相册、文件、选图选文件、分享、相机、输入法、NekoBox、电话支付）永远不锁，不用写在这里。',
    '',
  ]
  for (const t of TIERS) {
    lines.push('## ' + HEAD[t], '')
    const pkgs = Object.keys(overrides).filter((p) => overrides[p] === t).sort()
    if (!pkgs.length) lines.push('- （空）')
    for (const p of pkgs) lines.push('- ' + p + (labels[p] ? ' ' + labels[p] : ''))
    lines.push('')
  }
  return lines.join('\n')
}

export function parseTierFile(text) {
  const out = {}
  let cur = ''
  const back = Object.fromEntries(Object.entries(HEAD).map(([k, v]) => [v, k]))
  for (const raw of String(text || '').split(/\r?\n/)) {
    const h = raw.match(/^##\s*(.+?)\s*$/)
    if (h) {
      cur = back[h[1]] || ''
      continue
    }
    if (!cur) continue
    const m = raw.match(/^\s*[-*]\s+([A-Za-z0-9_.]+)/)
    if (m && m[1].includes('.')) out[m[1]] = cur
  }
  return out
}

/** 文件和当前生效的比：哪些改严（立即）、哪些改松（等冷却）。 */
export function classifyTierChanges(cur = {}, next = {}, tierFn = (p) => cur[p] || 'other') {
  const tighten = {}
  const loosen = {}
  for (const [p, t] of Object.entries(next)) {
    const before = cur[p] || tierFn(p)
    if (before === t) continue
    ;((TIER_RANK[t] ?? 1) > (TIER_RANK[before] ?? 1) ? tighten : loosen)[p] = t
  }
  return { tighten, loosen }
}

/** 同意的话：用来核对「他刚说过行」。 */
export const AGREE_RE = /(行|好|可以|同意|没问题|ok|OK|嗯嗯|就这样|批了)/
