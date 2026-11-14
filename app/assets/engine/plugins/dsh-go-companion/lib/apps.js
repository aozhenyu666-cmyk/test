// dsh-go-companion · 手边应用的名单（外接 APP）
//
// 原来的娱乐类是一张写死的包名表（phone.js 的 FUN）：换了手机、装了新 App、把 B 站换成别的
// 播放器，墙就看不见了。这里给他留一个口子——`config.apps = [{pkg, role, label}]`，他自己声明
// 哪个 App 算什么。同一条路数已经有先例：`config.geminiPackages` 就是这么覆盖内置清单的。
//
// role 只认四种：
//   fun        娱乐。锁着的时候要拦。
//   work       工作。局里默认可以用。
//   tool       工具。不算娱乐，也不主动拦。
//   emergency  紧急（电话、短信、地图、支付）。任何时候都不拦——施工书 P4 的出口之一。
//
// 判定顺序：他声明的 > 内置包名表 > 认不出来（认不出来的一律不当娱乐，跟 phone.js 一致）。
// 纯函数：名单由调用方给。

import { FUN_PACKAGES, isFun } from './phone.js'

export const ROLES = Object.freeze(['fun', 'work', 'tool', 'emergency'])

/** 最多声明几个。够用，又不会把一份配置写成一本通讯录。 */
export const MAX_APPS = 40

/**
 * 紧急应用：电话、短信、地图、支付。施工书的硬出口——任何锁、任何升级都不拦这些。
 * 只放真正救急的，别把社交软件放进来（微信既能付款也是娱乐，放进来自相矛盾）。
 */
const EMERGENCY = new Set([
  'com.android.dialer',
  'com.google.android.dialer',
  'com.android.phone',
  'com.samsung.android.dialer',
  'com.android.mms',
  'com.google.android.apps.messaging',
  'com.android.emergency',
  'com.google.android.apps.maps',
  'com.autonavi.minimap',
  'com.baidu.BaiduMap',
  'com.tencent.map',
  'com.eg.android.AlipayGphone',
  'com.unionpay',
])

function pkgOf(raw) {
  const s = typeof raw === 'string' ? raw.trim() : ''
  return /^[A-Za-z0-9_.]{1,160}$/.test(s) ? s : ''
}

/**
 * 洗一遍他声明的名单。坏条目直接丢，不因为一条脏数据把整份配置废掉。
 * @param {unknown} raw [{pkg, role, label}] 或 {包名: 角色}
 * @returns {{pkg:string, role:string, label:string}[]}
 */
export function cleanApps(raw) {
  const list = []
  const push = (pkg, role, label) => {
    const p = pkgOf(pkg)
    const r = String(role || '').trim()
    if (!p || !ROLES.includes(r)) return
    if (list.some((x) => x.pkg === p)) return
    const l = typeof label === 'string' ? label.trim().slice(0, 20) : ''
    list.push({ pkg: p, role: r, ...(l ? { label: l } : {}) })
  }
  if (Array.isArray(raw)) {
    for (const row of raw.slice(0, MAX_APPS * 2)) {
      if (row && typeof row === 'object') push(row.pkg ?? row.app, row.role, row.label)
    }
  } else if (raw && typeof raw === 'object') {
    // { 'com.x.y': 'fun' } 这种写法也收，手写配置时更省事。
    for (const [pkg, role] of Object.entries(raw).slice(0, MAX_APPS * 2)) push(pkg, role, '')
  }
  return list.slice(0, MAX_APPS)
}

/**
 * 这个包算什么。认不出来返回空串（调用方自己决定当什么，别默默当成娱乐）。
 * @param {{pkg:string, role:string}[]} apps cleanApps 的结果
 */
export function roleOf(apps, pkg) {
  const p = pkgOf(pkg)
  if (!p) return ''
  const hit = (Array.isArray(apps) ? apps : []).find((a) => a && a.pkg === p)
  if (hit && ROLES.includes(hit.role)) return hit.role
  if (isFun(p)) return 'fun'
  if (EMERGENCY.has(p)) return 'emergency'
  return ''
}

/** 是不是紧急应用（他自己声明成 emergency 的也算）。 */
export function isEmergency(apps, pkg) {
  return roleOf(apps, pkg) === 'emergency'
}

/** 全部紧急包：Enforcement 下发放行名单时用。 */
export function emergencyPackages(apps) {
  const out = new Set(EMERGENCY)
  for (const a of Array.isArray(apps) ? apps : []) if (a && a.role === 'emergency' && pkgOf(a.pkg)) out.add(a.pkg)
  return [...out].sort()
}

/** 他声明成娱乐的那些包（内置表之外的）。 */
export function declaredFun(apps) {
  return (Array.isArray(apps) ? apps : []).filter((a) => a && a.role === 'fun' && pkgOf(a.pkg)).map((a) => a.pkg).sort()
}

/**
 * 锁着的时候要拦哪些包。
 * @param {{pkg:string, role:string}[]} apps
 * @param {{funLocked?:boolean, allow?:string[]}} lock machine.js lockOf 的结果
 * @returns {string[]} 按包名排好序；空数组表示这一层不用拦
 */
export function blockDuringLock(apps, lock = {}) {
  if (!lock || lock.funLocked !== true) return []
  const allow = new Set((Array.isArray(lock.allow) ? lock.allow : []).map(pkgOf).filter(Boolean))
  const blocked = new Set()
  for (const pkg of FUN_PACKAGES) blocked.add(pkg)
  for (const pkg of declaredFun(apps)) blocked.add(pkg)
  for (const pkg of allow) blocked.delete(pkg)
  for (const pkg of emergencyPackages(apps)) blocked.delete(pkg)
  return [...blocked].sort()
}

/** 锁着的时候还放行哪些包：紧急应用永远在里面。 */
export function allowDuringLock(apps, lock = {}) {
  const allow = new Set((Array.isArray(lock?.allow) ? lock.allow : []).map(pkgOf).filter(Boolean))
  for (const pkg of emergencyPackages(apps)) allow.add(pkg)
  return [...allow].sort()
}

/**
 * 按他声明的名单重标一遍娱乐类（phone.js 洗完的那份对象进、出的形状不变）。
 *
 * 只动他声明过的包：没声明的保持原来的判法，所以不声明时这一层等于不存在。
 * 放在这里而不是塞进 cleanPhone，是因为 apps.js 已经 import phone.js，反向 import 会成环。
 *
 * @param {{app:string, fun:boolean, today:{app:string, fun:boolean}[]}} phone cleanPhone 的结果
 * @param {{pkg:string, role:string}[]} apps cleanApps 的结果
 */
export function remark(phone, apps) {
  if (!phone || typeof phone !== 'object') return phone
  const list = Array.isArray(apps) ? apps : []
  if (!list.length) return phone
  const funOf = (pkg, fallback) => {
    const hit = list.find((a) => a && a.pkg === pkg)
    return hit ? hit.role === 'fun' : fallback
  }
  return {
    ...phone,
    fun: phone.app ? funOf(phone.app, phone.fun) : phone.fun,
    today: (Array.isArray(phone.today) ? phone.today : []).map((t) => (
      t && typeof t === 'object' ? { ...t, fun: funOf(t.app, t.fun) } : t
    )),
  }
}

/** 说给他听的一行（设置页「手边应用」那栏用）。 */
export function appsSummary(apps) {
  const list = cleanApps(apps)
  if (!list.length) return '还没声明自己的应用，按内置的娱乐类判。'
  const zh = { fun: '娱乐', work: '工作', tool: '工具', emergency: '紧急' }
  return '声明了 ' + list.length + ' 个：' + list.map((a) => (a.label || a.pkg) + '（' + zh[a.role] + '）').join('、')
}
