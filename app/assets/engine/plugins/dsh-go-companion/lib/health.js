// 2026-11-21 体检（纯函数）：一页看出体系哪一环断了。
//
// 他原话：「体质检测，这个是绝对要做的」「我不可能每次出了问题都要找你们重新构建」。
// 所以每一环一行：正常 / 注意 / 断了 + 一句为什么 + 去哪修（fix 是手机页面的键，手机按它跳）。
// 引擎这边能看到的在这里判；手机自己的权限（无障碍、悬浮窗、通知、闹钟、电池、保活）由手机页面补上。

const ok = (id, group, label, detail = '', fix = '') => ({ id, group, label, level: 'ok', detail, fix })
const warn = (id, group, label, detail, fix = '') => ({ id, group, label, level: 'warn', detail, fix })
const bad = (id, group, label, detail, fix = '') => ({ id, group, label, level: 'error', detail, fix })

/**
 * @param {object} f 事实（都由 index.js 读好）
 */
export function healthItems(f = {}) {
  const out = []
  const mins = (ms) => Math.round(ms / 60_000)
  // ---- 引擎和手机对表
  if (!f.lastPlanAt) out.push(warn('phone-tick', '引擎', '手机还没跟引擎对过表', '刚装 / 刚重启的话等一分钟；一直这样就是手机端没在跑（看无障碍和保活）', 'capabilities'))
  else if (f.now - f.lastPlanAt > 10 * 60_000) out.push(bad('phone-tick', '引擎', '手机 ' + mins(f.now - f.lastPlanAt) + ' 分钟没跟引擎对表', '锁的判定停在那时候了：多半是无障碍掉了或者引擎被杀过', 'capabilities'))
  else out.push(ok('phone-tick', '引擎', '手机和引擎在对表', '上次 ' + Math.max(0, mins(f.now - f.lastPlanAt)) + ' 分钟前'))
  if (f.lockShadow) out.push(bad('lock-shadow', '锁', '测试模式开着', '锁只提示、不真锁', 'lock-shadow'))
  else out.push(ok('lock-shadow', '锁', '锁是真锁（测试模式关着）'))
  if (f.hardCapped) out.push(warn('hard-cap', '锁', '今天的硬干预用满了', '余下时间只提醒，不再自动锁 / 接管（深夜锁和到点没过保底的锁照旧）'))
  if (f.emergencyLeft === 0) out.push(warn('emergency', '锁', '今天的紧急解除用完了', '真有急事：电话、短信、支付、导航永远不锁'))
  // ---- 笔记库
  if (!f.vault) out.push(bad('vault', '笔记库', '没连 Obsidian 笔记库', '公约、模板、回执、体检记录只能存在手机里，电脑那边看不到', 'vault'))
  else out.push(ok('vault', '笔记库', '笔记库连着', f.vault))
  // ---- 验收
  if (!f.bankCount) out.push(bad('bank', '验收', '题库是空的', '默认模板的「一套行测」立不起来（题库类验收点会被拒）', 'bank'))
  else out.push(ok('bank', '验收', '题库 ' + f.bankCount + ' 题'))
  if (f.verifyEnabled === false) out.push(warn('verify', '验收', '默认验收关着', '工作时段不再默认只放常用 App', 'seals'))
  else out.push(ok('verify', '验收', '默认验收开着', f.verifyActive ? '现在在验收' : '时段外 / 让路中'))
  if (f.recovery) out.push(warn('recovery', '验收', '今天是恢复日', '连续两天没过保底：只要一套 10 题', 'seals'))
  if (f.timelineToday === 0 && f.pastApplyAt) out.push(warn('timeline', '验收', '今天的时间轴是空的', '过了套模板的时刻还没有验收点（题库空？模板写坏了？）', 'seals'))
  else if (f.timelineToday > 0) out.push(ok('timeline', '验收', '今天 ' + f.timelineToday + ' 个验收点', '过了 ' + (f.timelinePassed || 0) + ' 个'))
  if (f.sealLocking) out.push(warn('seal-lock', '验收', '验收点到点没过保底，锁着', f.sealLocking, 'seals'))
  if (!f.covenantRules) out.push(warn('covenant', '规矩', '公约没读到', '两条鱼没有成文规矩可照', 'covenant'))
  else out.push(ok('covenant', '规矩', '公约 ' + f.covenantRules + ' 条', f.covenantPending ? f.covenantPending + ' 条提议等你批' : ''))
  // ---- 娱乐
  if (f.eveningHit) out.push(warn('evening', '娱乐', '今晚的娱乐用完了', '锁到明早 7 点'))
  else if (f.eveningActive) out.push(ok('evening', '娱乐', '今晚娱乐 ' + f.eveningUsed + '/' + f.eveningCap + ' 分钟'))
  out.push(ok('balance', '娱乐', '娱乐余额 ' + (f.balance ?? '—') + ' 分钟'))
  // ---- Gemini 桥（可选）
  if (f.bridgeConfigured) {
    if (f.bridgeHealthy) out.push(ok('bridge', 'Gemini', 'Gemini 桥连着'))
    else out.push(warn('bridge', 'Gemini', 'Gemini 桥断了', f.bridgeError || '电脑没开 / 桥没跑 / funnel 没开', 'bridge'))
  }
  if (f.reviewDue > 0) out.push(warn('review', '复习', '有 ' + f.reviewDue + ' 张复习卡到期', '锁屏刷题会先出它们', 'review'))
  return out
}

export function healthSummary(items) {
  const errs = items.filter((x) => x.level === 'error')
  const warns = items.filter((x) => x.level === 'warn')
  const okN = items.length - errs.length - warns.length
  return {
    ok: okN, warn: warns.length, error: errs.length,
    line: (errs.length ? '✗ ' + errs.map((x) => x.label).join('、') + '；' : '') + (warns.length ? '⚠ ' + warns.map((x) => x.label).join('、') + '；' : '') + '✓ ' + okN + ' 项正常',
  }
}

/** 给两条鱼 / Gemini 的一段话：只说有问题的，没有就一句「都正常」。 */
export function healthText(items) {
  const s = healthSummary(items)
  const lines = items.filter((x) => x.level !== 'ok').map((x) => (x.level === 'error' ? '✗ ' : '⚠ ') + x.label + (x.detail ? '：' + x.detail : ''))
  return lines.length ? '体检：' + s.line + '\n' + lines.join('\n') : '体检：' + s.ok + ' 项都正常。'
}
