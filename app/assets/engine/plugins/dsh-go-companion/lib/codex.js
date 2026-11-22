// dsh-go-companion · 法典（第九轮补充二 §3）
//
// 规则原来散在七八个地方：墙的规则表、作息设定、升级阈值、预案、立场、判例……
// 这里不新造规则，只把已有的规则**收成一张有编号的表**，分四级：
//   宪法（C）：永远成立的边界，只有他能改
//   法律（L）：长期规则（作息、最少局数、升级阈值、深夜锁），他改松走 24 小时冷却
//   法规（R）：预案，针对具体情况；鲸澜立的 30 天日落
//   判例（P）：墙对具体事件的裁决，不可改
//
// 纯函数：所有输入由调用方读好传进来。
// 宪法里还没真正做到的条目照实标「尚未执行」，不假装已经有了。

export const CODEX_VERSION = 1

/** 宪法。enforced=false 的是写下来了、代码还没做到的。 */
export const CONSTITUTION = Object.freeze([
  { id: 'C-1', text: '电话、短信、地图、支付永远可用，任何锁都锁不住。', enforced: true },
  { id: 'C-2', text: '破窗永远可用；每天次数和代价由他定。', enforced: true },
  { id: 'C-3', text: '深夜时段她们不主动说话。', enforced: true },
  { id: 'C-4', text: '不读屏幕上的文字，不读私密目录。', enforced: true },
  { id: 'C-5', text: 'AI 只能收紧，不能放宽任何规则；放宽只有他能做，而且要等冷却期。', enforced: true },
  { id: 'C-6', text: 'AI 立的每条法规 30 天后自动失效，除非续期。', enforced: true },
  { id: 'C-7', text: '不能停用哨兵。', enforced: false, why: '哨兵还没做（总命令第七项）' },
  { id: 'C-8', text: '她说的每一个数字都必须来自代码算的结果。', enforced: false, why: '模式卡和预案的数字已由代码给出；聊天里的数字还没有检查' },
  { id: 'C-9', text: '每一次锁都写明依据哪条规则、怎么解除。', enforced: false, why: '严格模式已经写明；其他锁的锁页还是默认文案（总命令第六项）' },
])

const num = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)
const hm = (min) => String(Math.floor(min / 60)).padStart(2, '0') + ':' + String(min % 60).padStart(2, '0')
const windowsText = (list) => (list || [])
  .map((w) => (Array.isArray(w) ? { from: w[0], to: w[1] } : w))
  .map((w) => hm(num(w.from)) + '–' + hm(num(w.to))).join('、') || '无'

/**
 * 法律：从设定（settings.js）和墙的吃饭时段里读出来。编号固定，跟着设定项走。
 * @param {object} settings  settingsOf(config) 的结果
 * @param {{meals?:{from:number,to:number}[]}} wall
 */
export function lawsOf(settings, wall = {}) {
  const s = settings || {}
  const w = s.windows || {}
  const e = s.escalate || {}
  const laws = [
    { id: 'L-01', name: '工作日工作时段', value: windowsText(w.workday) },
    { id: 'L-02', name: '休息日工作时段', value: windowsText(w.restday) },
    { id: 'L-03', name: '工作日最少局数', value: num(s.minBouts) + ' 局（其中思维局至少 ' + num(s.minThinking) + ' 个）' },
    { id: 'L-04', name: '深夜锁', value: '00:30–07:00 娱乐锁着', fixed: true },
    { id: 'L-05', name: '敷衍升级（E1）', value: Math.round(num(e.E1WindowMs) / 60_000) + ' 分钟内敷衍 ' + num(e.E1Count) + ' 次 → Gemini 审讯 ' + num(e.cellMin) + ' 分钟' },
    { id: 'L-06', name: '娱乐连续上限（L1）', value: '工作时段 ' + num(e.L1WorkMin) + ' 分钟、其他时段 ' + num(e.L1RestMin) + ' 分钟 → 直接锁' },
    { id: 'L-07', name: '局内没声明的应用（L2）', value: num(e.L2Min) + ' 分钟 → 核对，2 分钟不答锁' },
    { id: 'L-08', name: '每天娱乐上限（L3）', value: num(e.L3Min) + ' 分钟 → 当天剩余时间锁' },
  ]
  if (Array.isArray(wall.meals) && wall.meals.length) {
    laws.push({ id: 'L-09', name: '吃饭时段（默认锁开着时才有意义）', value: windowsText(wall.meals) })
  }
  return laws.map((l) => ({ ...l, level: '法律', source: l.fixed ? '作息' : '设定', loosen: l.fixed ? '这一轮不能改' : '他改，24 小时冷却' }))
}

/**
 * 法规：预案。内置的和新建的都在这里；鲸澜立的带日落日期。
 * @param {object[]} presets  presetwire.list().presets 的条目
 */
export function regulationsOf(presets, now) {
  return (presets || []).map((p) => {
    const sunsetAt = num(p.sunsetAt)
    return {
      id: 'R-' + p.id,
      level: '法规',
      name: p.name,
      status: p.status,
      explain: p.explain,
      by: p.builtin ? '内置' : p.by === 'ai' ? '鲸澜' : '他',
      basis: p.basis || '',
      version: num(p.version, 1),
      sunsetAt: sunsetAt || null,
      daysLeft: sunsetAt ? Math.max(0, Math.ceil((sunsetAt - num(now)) / 86_400_000)) : null,
      loosen: p.fixed ? '这一轮不能改' : '他改，2 小时冷却',
    }
  })
}

/**
 * 判例：墙的裁决（verdicts 表的墙层）+ 申诉结果（账本）。只取最近的，按时间倒序。
 * @param {{verdicts?:object[], appeals?:object[]}} input
 */
export function precedentsOf(input = {}, limit = 30) {
  const out = []
  for (const v of input.verdicts || []) {
    out.push({ id: 'P-' + String(v.id || '').slice(-8), level: '判例', at: num(v.at), kind: '裁决', result: String(v.result || ''), quote: String(v.quote || '').slice(0, 80), boutId: v.boutId || '' })
  }
  for (const a of input.appeals || []) {
    out.push({ id: 'P-A' + String(num(a.at)).slice(-8), level: '判例', at: num(a.at), kind: '申诉', result: String(a.result || a.state || ''), quote: String(a.reason || '').slice(0, 80) })
  }
  return out.sort((a, b) => b.at - a.at).slice(0, limit)
}

/**
 * 整部法典。
 * @param {{settings:object, wall?:object, presets:object[], verdicts?:object[], appeals?:object[], now:number}} input
 */
export function buildCodex(input) {
  const regulations = regulationsOf(input.presets, input.now)
  return {
    v: CODEX_VERSION,
    constitution: CONSTITUTION.map((c) => ({ ...c, level: '宪法', loosen: '只有他，7 天冷却' })),
    laws: lawsOf(input.settings, input.wall),
    regulations,
    precedents: precedentsOf({ verdicts: input.verdicts, appeals: input.appeals }),
    counts: {
      constitution: CONSTITUTION.length,
      unenforced: CONSTITUTION.filter((c) => !c.enforced).length,
      regulationsActive: regulations.filter((r) => r.status === '生效').length,
      sunsetSoon: regulations.filter((r) => r.daysLeft !== null && r.daysLeft <= 7 && r.status !== '停用').length,
    },
  }
}
