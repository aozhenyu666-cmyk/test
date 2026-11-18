// dsh-go-companion · 永不锁（插件侧唯一一份）
//
// 锁机事故的根因：/plan 的 force.app 直接取「当前前台」，前台是桌面或 DSH 自己（锁页本身就是 DSH）
// 时，手机照单把桌面 / 自己锁了，锁页 → 前台变 DSH → 再锁 DSH，死循环。
// 规则：凡是下发给手机的锁目标（force.app、block 名单），先过这里；命中就不下发。
// 手机侧有同构的一份（SystemSafeAllowlist.neverLock），两边任何一边拦住都不会锁。

export const SELF_PACKAGES = Object.freeze([
  'io.github.ccch1mneyyy.dsh_app',
  'io.github.ccch1mneyyy.dsh_app.fish',
])

export const EMERGENCY_PACKAGES = Object.freeze([
  'com.android.dialer', 'com.google.android.dialer', 'com.android.phone', 'com.android.server.telecom',
  'com.android.incallui', 'com.android.mms', 'com.google.android.apps.messaging', 'com.android.contacts',
  'com.autonavi.minimap', 'com.baidu.BaiduMap', 'com.google.android.apps.maps', 'com.tencent.map',
  'com.eg.android.AlipayGphone', 'com.unionpay',
])

/** 桌面、系统界面、权限弹窗、vivo/iQOO 的控制中心与安全中心。 */
export const SYSTEM_PACKAGES = Object.freeze([
  'android', 'com.android.systemui', 'com.android.permissioncontroller', 'com.google.android.permissioncontroller',
  'com.android.packageinstaller', 'com.google.android.packageinstaller',
  'com.bbk.launcher2', 'com.vivo.launcher', 'com.vivo.upslide', 'com.vivo.globalsearch', 'com.iqoo.secure',
  'com.vivo.permissionmanager', 'com.vivo.smartmultiwindow', 'com.vivo.floatingwindow',
  'com.miui.home', 'com.android.launcher3', 'com.google.android.apps.nexuslauncher', 'com.oppo.launcher',
  'com.huawei.android.launcher', 'com.hihonor.android.launcher', 'com.sec.android.app.launcher',
  'com.realme.launcher', 'com.oneplus.launcher',
  // iQOO / vivo：相册、文件管理、Jovi 负一屏、截屏；系统选图片 / 选文件 / 分享页
  'com.vivo.gallery', 'com.android.filemanager', 'com.vivo.assistant', 'com.vivo.smartshot',
  'com.android.providers.media.module', 'com.google.android.providers.media.module',
  'com.android.documentsui', 'com.google.android.documentsui', 'com.android.intentresolver',
])

/** 连 Gemini 必须先开的代理（NekoBox 等）：锁掉它等于锁掉连麦。 */
export const PROXY_PACKAGES = Object.freeze([
  'moe.nb4a', 'io.nekohasekai.sfa', 'io.nekohasekai.sagernet', 'com.v2ray.ang', 'com.github.kr328.clash',
  'com.github.metacubex.clash.meta',
])

const ALL = new Set([...SELF_PACKAGES, ...EMERGENCY_PACKAGES, ...SYSTEM_PACKAGES, ...PROXY_PACKAGES])

export function isNeverLockPkg(pkg, extra = []) {
  const s = String(pkg || '').trim()
  if (!s) return true // 不知道前台是谁，也不锁
  if (ALL.has(s)) return true
  if (Array.isArray(extra) && extra.includes(s)) return true
  const low = s.toLowerCase()
  if (low.endsWith('.launcher') || low.endsWith('.launcher2') || low.endsWith('.launcher3')) return true
  if (/(inputmethod|keyboard|gboard)/.test(low)) return true
  return false
}
