// dsh-go-companion · App 分层（2026-11-18）
//
// 四层：
//   base 系统底座：永远不锁（never_lock.js 那一层，另加 iQOO 的相册 / 文件 / 选图选文件页 / 分享页 / 相机 / 截屏）
//   work 工作：干活时直接放行
//   dual 双用途（缺口）：干活时要先跟鲸澜申请通行证（任务租约）；自由态占娱乐额度
//   fun  娱乐 / game 游戏：干活时锁；自由态吃额度（游戏单独 60 分钟，可 2:1 换成娱乐）
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
  'com.google.android.apps.bard', 'com.openai.chatgpt', 'com.anthropic.claude',
  'com.baidu.netdisk', 'com.hpbr.bosszhipin', 'md.obsidian', 'cn.wps.moffice_eng',
])

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
