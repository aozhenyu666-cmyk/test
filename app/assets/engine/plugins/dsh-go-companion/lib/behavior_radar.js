// dsh-go-companion · 零延迟异常行为雷达（同步运行于 /plan 链路，0ms 先行落锁，姐妹后行点破）
//
// 专门兜底大模型响应 8-20s 延迟及人设轮漏调工具的问题，覆盖用户痛点三大核心场景：
//   1. 晨间开机直接刷娱乐/游戏 -> 未立项即连刷 >= 3 分钟直接先行落锁并催排局；
//   2. 连续刷短视频/未收录信息流/浏览器 (>= 12 分钟) 或高频横跳 (15m 内切 >= 8 次) -> 自动封禁 + 降噪收紧；
//   3. 聊天连续 2 次敷衍且手头仍不在正事 App -> 触发真实 30 分钟硬件拒绝锁，不止是冷处理闭嘴。

import { classifyApp, isDualUseApp, isPureGame } from './app_resolver.js'
import { minuteOfDay as zoneMinuteOfDay } from './tz.js'

export function evalBehaviorRadar({
  phone,
  declaredApps = [],
  now = Date.now(),
  tz = 'Asia/Shanghai',
  morningPlanned = false,
  onBreak = false,
  emergencyActive = false,
  graceActive = false,
  runningBout = null,
  activeLease = null,
  funTotalToday = 0,
  penalty = null,
} = {}) {
  const out = {
    triggered: false,
    kind: '',
    reason: '',
    tempBlockApps: [],
    refuseMinutes: 0,
    forceLevel: 0,
    forceWhy: '',
    revokeLease: false,
    speaker: 'companion',
    ballReason: '',
  }
  if (!phone || !phone.app || onBreak || emergencyActive) return out

  const installedMeta = Array.isArray(phone.installedApps)
    ? phone.installedApps.find((x) => x && x.pkg === phone.app)
    : null
  const appCat = classifyApp(phone.app, {
    label: phone.label || '',
    declaredApps,
    bypass: Boolean(installedMeta?.bypass),
  })
  const mins = Number(phone.minutes) || 0
  const mod = zoneMinuteOfDay(now, tz)
  const isMorning = mod >= 5 * 60 && mod < 11 * 60
  const boutAllow = new Set([
    ...(Array.isArray(runningBout?.apps?.need) ? runningBout.apps.need : []),
    ...(Array.isArray(runningBout?.apps?.allow) ? runningBout.apps.allow : []),
  ])
  const inBoutAllow = boutAllow.has(phone.app)

  // 0) 鲸澜双用途 App 租约偏航自动吊销：读屏出现游戏/短剧/纯八卦/无聊词时直接吊销并锁回
  if (activeLease && activeLease.app === phone.app && Array.isArray(activeLease.verifyHints) && phone.screenText) {
    const textLower = String(phone.screenText).toLowerCase()
    const gameOrDrift = /(?:开始游戏|匹配中|战绩|连胜|王者荣耀|抖音精选|热门短剧|关注.*粉丝|直播间|送礼物|打赏)/.test(textLower)
    if (gameOrDrift) {
      return {
        ...out,
        triggered: true,
        kind: 'lease-drift',
        reason: '租约期读屏偏航回娱乐流',
        tempBlockApps: [phone.app],
        refuseMinutes: 30,
        forceLevel: 4,
        forceWhy: '刚才说用 ' + (phone.label || phone.app) + ' 查正事，屏幕已经滑进娱乐流，租约收回并锁定。',
        revokeLease: true,
        speaker: 'strategist',
        ballReason: 'lease-drift',
      }
    }
  }

  if (inBoutAllow || appCat === 'emergency' || appCat === 'work' || graceActive) return out

  // 1) 早起直奔游戏或娱乐 >= 3 分钟（未完成晨间立项 / 正在开局以外）
  if (isMorning && !morningPlanned && (appCat === 'game' || appCat === 'fun' || phone.fun) && mins >= 3) {
    return {
      ...out,
      triggered: true,
      kind: 'morning-first-play',
      reason: '早间未立项直奔娱乐',
      tempBlockApps: [phone.app],
      refuseMinutes: 45,
      forceLevel: 4,
      forceWhy: '早上还没定今天第一局就先开 ' + (phone.label || phone.app) + '，先锁掉，去把第一局立了。',
      speaker: 'strategist',
      ballReason: 'radar-morning-play',
    }
  }

  const hourNow = Math.floor(mod / 60)
  const isHighRiskHour = Array.isArray(penalty?.highRiskHours) && penalty.highRiskHours.includes(hourNow)
  const gameMinLimit = isHighRiskHour ? 3 : 5
  const driftMinLimit = isHighRiskHour ? 6 : 12

  // 2) 全天任何时段：纯游戏连续 >= 5 分钟（高危黑历史时段收紧至 >= 3 分钟）
  if ((appCat === 'game' || isPureGame(phone.app, phone.label)) && mins >= gameMinLimit) {
    return {
      ...out,
      triggered: true,
      kind: 'game-binge',
      reason: (isHighRiskHour ? '[高危时段加重] ' : '') + '游戏连刷已满 ' + mins + ' 分钟',
      tempBlockApps: [phone.app],
      refuseMinutes: 60,
      forceLevel: 4,
      forceWhy: (phone.label || phone.app) + ' 已经连玩 ' + mins + ' 分钟，先强制停下。',
      speaker: 'companion',
      ballReason: 'radar-game-binge',
    }
  }

  // 3) 短视频 / 未收录信息流 / 系统浏览器连刷 >= 12 分钟（高危时段 >= 6 分钟）
  const in15m = Number(phone.switches?.in15m) || 0
  const distinct15m = Number(phone.switches?.distinct15m) || 0
  const isFeedOrFun = appCat === 'fun' || phone.fun || Boolean(installedMeta?.bypass)
  const driftLong = isFeedOrFun && mins >= driftMinLimit && !(activeLease && activeLease.app === phone.app)

  // 3.1) 中立 App 碎片游走摩擦模态（优先于单点强锁）：无局且 15m 内在 >=4 个非工作应用间切 >=8 次、每次 <10 分钟，
  // 仅冷却最近游走的应用 15 分钟，绝不误杀基础/系统/工作应用
  if (!driftLong && !runningBout && phone.screenOn && in15m >= 8 && distinct15m >= 4 && mins < 10 && !inBoutAllow && !(activeLease && activeLease.app === phone.app)) {
    const recentPkgs = (Array.isArray(phone.switches?.recent) ? phone.switches.recent : [])
      .map((r) => String(r?.app || ''))
      .filter(Boolean)
    const hopped = []
    for (const p of [...new Set([phone.app, ...recentPkgs])]) {
      const cat = classifyApp(p, { declaredApps })
      if (cat === 'emergency' || cat === 'work' || p.includes('dsh_app') || p.includes('bard') || p.includes('googlequicksearchbox')) continue
      if (!hopped.includes(p)) hopped.push(p)
      if (hopped.length >= 3) break
    }
    if (hopped.length >= 2) {
      return {
        ...out,
        triggered: true,
        kind: 'neutral-hop',
        reason: '15 分钟在 ' + distinct15m + ' 个中立应用间来回切换 ' + in15m + ' 次碎片游走',
        tempBlockApps: hopped,
        refuseMinutes: 15,
        forceLevel: 3,
        forceWhy: '15 分钟在多个中立 App 间来回切了 ' + in15m + ' 次，先冷却这几个游走应用 15 分钟（正常工具与工作照旧；真要用请向我申报用途与时长）。',
        speaker: 'strategist',
        ballReason: 'radar-neutral-hop',
      }
    }
  }

  // 3.2) 其余连刷信息流或局内/少数应用高频横跳
  const highBounce = in15m >= 8 && !inBoutAllow && isDualUseApp(phone.app)
  if (driftLong || highBounce) {
    return {
      ...out,
      triggered: true,
      kind: driftLong ? 'feed-drift' : 'restless-bounce',
      reason: driftLong ? '长时刷信息流/浏览器' : '15 分钟高频横跳 ' + in15m + ' 次',
      tempBlockApps: [phone.app],
      refuseMinutes: 30,
      forceLevel: 4,
      forceWhy: driftLong
        ? (phone.label || phone.app) + ' 已经连续占了 ' + mins + ' 分钟，先暂时封住。'
        : '15 分钟切了 ' + in15m + ' 次 App 在游走，先收手定一局。',
      speaker: 'companion',
      ballReason: 'radar-drift',
    }
  }

  // 4) 基于真实 UsageStats 的全天娱乐总时长兜底：无局时今日累计娱乐已满 60 分钟且仍停留在娱乐/游戏
  const totalFun = Math.max(Number(funTotalToday) || 0, (phone.today || []).filter((t) => t?.fun).reduce((s, t) => s + (Number(t.minutes) || 0), 0))
  if (!runningBout && (appCat === 'game' || appCat === 'fun' || phone.fun) && totalFun >= 60) {
    return {
      ...out,
      triggered: true,
      kind: 'daily-fun-budget',
      reason: '今日娱乐累计已达 ' + totalFun + ' 分钟',
      tempBlockApps: [phone.app],
      refuseMinutes: 45,
      forceLevel: 4,
      forceWhy: '今天娱乐应用累计已经用了 ' + totalFun + ' 分钟，先暂时停下，定一局再继续。',
      speaker: 'strategist',
      ballReason: 'radar-fun-budget',
    }
  }

  // 5) 上午亮屏未定计划/未开局的轻量主动唤起（不锁机，只做一定触发器让姐妹主动开启模态/定计划）
  if (phone.screenOn && !runningBout && !morningPlanned && isMorning && (mins >= 6 || in15m >= 5)) {
    return {
      ...out,
      triggered: true,
      kind: 'unplanned-nudge',
      reason: '上午亮屏尚未立项开局',
      tempBlockApps: [],
      refuseMinutes: 0,
      forceLevel: 0,
      forceWhy: '',
      speaker: 'strategist',
      ballReason: 'radar-unplanned',
    }
  }

  return out
}
