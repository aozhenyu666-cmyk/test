// dsh-go-companion · 中文/模糊应用解析器 + 未收录娱乐/信息流/游戏启发式识别器
//
// 解决两大盲区：
//   1. 原先 open_bout / block_apps 只收完整包名，模型传「小红书」「王者」「Obsidian」必失败；
//   2. 原先只靠 phone.js 11 个硬编码包名判 fun，换成任意未收录短视频/小说/漫画/游戏/浏览器就漏检。

import { isFun } from './phone.js'
import { roleOf } from './apps.js'

/** 常见高频应用中文别名 -> 候选包名表（覆盖国内主流娱乐 / 学习 / 工具 / AI）。 */
export const APP_ALIASES = Object.freeze({
  'b站': ['tv.danmaku.bili', 'tv.danmaku.bilibilihd', 'com.bilibili.app.in'],
  'bilibili': ['tv.danmaku.bili', 'com.bilibili.app.in'],
  '哔哩哔哩': ['tv.danmaku.bili'],
  '抖音': ['com.ss.android.ugc.aweme', 'com.ss.android.ugc.aweme.lite'],
  '抖音极速版': ['com.ss.android.ugc.aweme.lite'],
  '快手': ['com.smile.gifmaker', 'com.kuaishou.nebula'],
  '快手极速版': ['com.kuaishou.nebula'],
  '小红书': ['com.xingin.xhs'],
  '微博': ['com.sina.weibo', 'com.weico.international'],
  '知乎': ['com.zhihu.android'],
  '贴吧': ['com.baidu.tieba'],
  '虎扑': ['com.hupu.games'],
  '豆瓣': ['com.douban.frodo'],
  '微信': ['com.tencent.mm'],
  'qq': ['com.tencent.mobileqq'],
  '淘宝': ['com.taobao.taobao'],
  '拼多多': ['com.xunmeng.pinduoduo'],
  '京东': ['com.jingdong.app.mall'],
  '闲鱼': ['com.taobao.idlefish'],
  '王者荣耀': ['com.tencent.tmgp.sgame'],
  '王者': ['com.tencent.tmgp.sgame'],
  '和平精英': ['com.tencent.tmgp.pubgmhd'],
  '吃鸡': ['com.tencent.tmgp.pubgmhd'],
  '原神': ['com.miHoYo.Yuanshen', 'com.miHoYo.ys.bilibili'],
  '崩铁': ['com.miHoYo.hkrpg'],
  '绝区零': ['com.miHoYo.Nap'],
  '鸣潮': ['com.kurogame.mingchao'],
  '金铲铲': ['com.tencent.jkchess'],
  '炉石': ['com.blizzard.wtcg.hearthstone'],
  'taptap': ['com.taptap'],
  '腾讯视频': ['com.tencent.qqlive'],
  '爱奇艺': ['com.qiyi.video'],
  '优酷': ['com.youku.phone'],
  '芒果tv': ['com.hunantv.imgo.activity'],
  '红果': ['com.phoenix.read'],
  '番茄小说': ['com.dragon.read'],
  '七猫': ['com.kmxs.reader'],
  '起点': ['com.qidian.QDReader'],
  'obsidian': ['md.obsidian'],
  '黑曜石': ['md.obsidian'],
  '笔记': ['md.obsidian'],
  'notion': ['notion.id'],
  'gemini': ['com.google.android.apps.bard', 'com.google.android.googlequicksearchbox'],
  'chatgpt': ['com.openai.chatgpt'],
  'claude': ['com.anthropic.claude'],
  'anki': ['com.ichi2.anki'],
  '多邻国': ['com.duolingo'],
  'duolingo': ['com.duolingo'],
  'wps': ['cn.wps.moffice_eng'],
  '飞书': ['com.ss.android.lark'],
  '钉钉': ['com.alibaba.android.rimet'],
  'chrome': ['com.android.chrome'],
  '浏览器': ['com.android.browser', 'com.android.chrome', 'com.miui.browser', 'com.heytap.browser', 'com.vivo.browser', 'com.quark.browser'],
  '夸克': ['com.quark.browser'],
  'uc': ['com.UCMobile'],
  'edge': ['com.microsoft.emmx'],
})

/** 硬编码游戏特征（纯游戏严禁通过大鱼/小鱼对话租约当场放行）。 */
export const GAME_HINT_RE = /(?:\.tmgp\.|mihoyo|hoyoverse|kurogame|blizzard|hypergryph|papegames|lilith|supercell|gamecenter|gamebox|jkchess|yuanshen|hkrpg|pubgm|sgame|codm|naraka|nikke|lolm|taptap|游戏|王者|吃鸡|原神|绝区零|崩铁|鸣潮|铲铲)/i

/** 信息流 / 短视频 / 小说漫画 / 购物 / 浏览器启发式特征。 */
export const FEED_HINT_RE = /(?:aweme|gifmaker|kuaishou|xingin\.xhs|weibo|tieba|hupu|frodo|zhihu|qqlive|qiyi|youku|hunantv|browser|emmx|ucmobile|quark|dragon\.read|qdreader|kmxs|comic|manhua|video|reel|tiktok|youtube|reddit|bilibili|小说|视频|短剧|漫画|直播|贴吧|论坛)/i

/** 双用途工具（有学习/查资料价值，仅鲸澜可批 <=20m 核验租约）。 */
export const DUAL_USE_PACKAGES = new Set([
  'tv.danmaku.bili',
  'com.xingin.xhs',
  'com.zhihu.android',
  'com.tencent.mm',
  'com.tencent.mobileqq',
  'com.android.chrome',
  'com.quark.browser',
  'com.microsoft.emmx',
  'com.miui.browser',
  'com.heytap.browser',
  'com.vivo.browser',
  'com.huawei.browser',
  'com.android.browser',
])

export function isPureGame(pkg, label = '') {
  const s = `${pkg || ''} ${label || ''}`
  return GAME_HINT_RE.test(s)
}

export function isDualUseApp(pkg) {
  return DUAL_USE_PACKAGES.has(String(pkg || '').trim())
}

/**
 * 综合判断应用分类：'emergency' | 'work' | 'game' | 'fun' | 'tool' | 'unknown'
 */
export function classifyApp(pkg, { label = '', declaredApps = [], bypass = false } = {}) {
  const p = String(pkg || '').trim()
  if (!p) return 'unknown'
  const declared = roleOf(declaredApps, p)
  if (declared) {
    if (declared === 'fun' && isPureGame(p, label)) return 'game'
    return declared
  }
  if (isPureGame(p, label)) return 'game'
  if (isFun(p) || bypass || FEED_HINT_RE.test(`${p} ${label || ''}`)) return 'fun'
  if (/(?:obsidian|anki|duolingo|wps|lark|notion|openai\.chatgpt|anthropic\.claude|apps\.bard|forest|todo|ticktick)/i.test(p)) {
    return 'work'
  }
  return 'unknown'
}

/**
 * 将中文名/简称/包名混合数组解析成干净包名数组。
 * 优先匹配手机端实时上报的 `installedApps`，再匹配 `declaredApps` 与内置 `APP_ALIASES`。
 */
export function resolveAppNames(inputs, { installedApps = [], declaredApps = [] } = {}) {
  const raw = Array.isArray(inputs) ? inputs : inputs ? [inputs] : []
  const resolved = []
  const unresolved = []
  const installed = Array.isArray(installedApps) ? installedApps : []
  const declared = Array.isArray(declaredApps) ? declaredApps : []
  const installedSet = new Set(installed.map((x) => String(x?.pkg || '').trim()).filter(Boolean))

  const push = (pkg) => {
    const p = String(pkg || '').trim()
    if (/^[A-Za-z0-9_.]{3,160}$/.test(p) && !resolved.includes(p)) resolved.push(p)
  }

  for (const item of raw.slice(0, 20)) {
    const q = String(item || '').trim()
    if (!q) continue
    if (/^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(q)) {
      push(q)
      continue
    }
    const lower = q.toLowerCase()
    let hit = false
    // 1) declaredApps label match
    for (const d of declared) {
      if (d?.label && d.label.toLowerCase().includes(lower)) {
        push(d.pkg)
        hit = true
      }
    }
    if (hit) continue
    // 2) phone installedApps label exact/substring match
    const labelHits = installed.filter((a) => String(a?.label || '').toLowerCase().includes(lower) || String(a?.pkg || '').toLowerCase().includes(lower))
    if (labelHits.length) {
      for (const h of labelHits.slice(0, 3)) push(h.pkg)
      continue
    }
    // 3) built-in alias dictionary (prefer packages actually installed if installedApps is known)
    const aliasExact = APP_ALIASES[lower]
    if (aliasExact) {
      const filtered = installedSet.size ? aliasExact.filter((p) => installedSet.has(p)) : []
      for (const p of (filtered.length ? filtered : [aliasExact[0]])) push(p)
      continue
    }
    for (const [k, pkgs] of Object.entries(APP_ALIASES)) {
      if (lower.includes(k) || k.includes(lower)) {
        const filtered = installedSet.size ? pkgs.filter((p) => installedSet.has(p)) : []
        for (const p of (filtered.length ? filtered : [pkgs[0]])) push(p)
        hit = true
      }
    }
    if (!hit) unresolved.push(q)
  }
  return { resolved: resolved.slice(0, 20), unresolved }
}
