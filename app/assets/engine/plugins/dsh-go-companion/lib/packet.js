// dsh-go-companion · 工作包（第九轮第三项）
//
// 计划里的每一局都要说清「做成什么算做成」。没有这一层的后果很具体：局开了他不知道第一步
// 做什么，触发器问「推到哪了」也问不出东西——因为从来没有写过标准答案。
//
// 这个模块只管两件事：
//   1. 一个工作包合不合格（第一步是不是动作、有没有交付物、验收方式属于哪一类）。
//   2. 合格的工作包怎么变成局官的问句。
//
// 判不了的事不在这里判。验收方式写「截图」不等于这里有识图能力——那是验的时候的事；
// 这里只保证他说清了要怎么验，不许空着进计划。
//
// 纯函数：应用名字表由调用方给。

/** 验收方式只有三类，优先级从高到低。 */
export const ACCEPT_KINDS = Object.freeze(['evidence', 'basis', 'judge'])

/** 判断类一天最多几个：判官模型判的东西最容易吵，多了就不值钱了。 */
export const JUDGE_MAX_PER_DAY = 2

/** 第一步的上限。再长就不是一个动作，是一段计划。 */
export const FIRST_MAX = 40

/**
 * 动词表。只收「手能马上动」的那种——「思考」「规划」不算，
 * 因为一个 2 分钟内能开始的动作必须落在一件东西上。
 */
const VERBS = [
  '打开', '点开', '关掉', '筛选', '搜', '查', '看', '读', '念', '抄', '写', '填', '改', '删',
  '投', '发', '传', '下载', '安装', '登录', '注册', '买', '付', '打', '约', '问', '记', '列',
  '算', '对', '比', '找', '翻', '拍', '截', '复', '粘', '整', '导', '跑', '画', '选', '拿', '洗',
]

/**
 * 物件表。第一步里除了应用，还可以落在桌面上的东西上——纸上、简历、笔记本。
 * 这是「含一个应用或物件」里的后半句，不能只认应用：他真会拿笔。
 */
const OBJECTS = [
  '简历', '文档', '表格', '笔记', '纸', '笔', '手机', '电脑', '书', '文件', '截图', '照片',
  '邮件', '链接', '网页', '录音', '相机', '日历', '清单', '作业', '题', '账', '发票', '快递',
]

/**
 * 内置的常见应用说法。他还没在设定里声明过任何 App 的时候（新装的机器就是这样），
 * 「打开浏览器」不能被判成「没落点」——这句话本身就是合格的。
 * 声明过的应用走 appTokens，这张表只管最常见的那些叫法。
 */
const COMMON_APPS = [
  '浏览器', '微信', '相册', '相机', '短信', '电话', '地图', '支付宝', '备忘录', '时钟',
  '计算器', '邮箱', '文件管理', '应用商店', '设置', '抖音', '快手', '哔哩', 'B站', '知乎',
  '微博', '小红书', '钉钉', 'BOSS', 'BOSS直聘', 'BOSS 直聘', '招聘', '学习通', '题库',
  '微信读书', '网易云', '播客', '网盘', '在线文档', '飞书', '腾讯会议',
]

function text(v, max) {
  return String(v ?? '').replace(/\s+/g, ' ').trim().slice(0, max)
}

/** 应用名字表：`[{pkg, label}]` → 用于「第一步里提到哪个应用」的判定。 */
function appTokens(apps) {
  const out = []
  for (const a of Array.isArray(apps) ? apps : []) {
    const label = text(a?.label, 20)
    if (label) out.push(label)
    const pkg = text(a?.pkg, 80)
    if (!pkg) continue
    const tail = pkg.split('.').pop() || ''
    // com.tencent.mm 这种，尾巴短得像乱码，别当名字用。
    if (tail.length >= 3 && !/^[a-z]{1,2}\d*$/.test(tail)) out.push(tail)
  }
  return out
}

/**
 * 第一步合格吗。
 * 规则（施工书第三项）：一个 2 分钟内能开始的物理动作；含一个动词、含一个应用或物件、≤ 40 字。
 * @returns {string} 不合格的原因；合格返回空串
 */
export function checkFirst(raw, { apps = [] } = {}) {
  const s = text(raw, 200)
  if (!s) return '第一步还没写'
  if (s.length > FIRST_MAX) return '第一步太长了（' + s.length + ' 字，最多 ' + FIRST_MAX + ' 字）'
  if (!VERBS.some((v) => s.includes(v))) return '第一步里没有一个能马上做的动作（要有个动词，比如「打开」「筛选」「投」）'
  const names = appTokens(apps)
  const hitApp = names.some((n) => s.includes(n)) || COMMON_APPS.some((n) => s.includes(n))
  const hitObj = OBJECTS.some((o) => s.includes(o))
  if (!hitApp && !hitObj) return '第一步要落在某个应用或某件东西上（比如「打开 Boss 直聘」「拿简历」）'
  return ''
}

/**
 * 把一个工作包洗成能存的样子。
 * @param {object} raw 手写或模型给的工作包
 * @param {{apps?:Array}} ctx apps 用来判第一步里提到的应用
 * @returns {{ok:boolean, packet:object|null, why:string}} 没有任何字段时 ok=false、why='empty'
 */
export function normPacket(raw, ctx = {}) {
  // 数组也是 object，但它不是一个工作包：手机那边把 items 传错位置就会这样。
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return { ok: false, packet: null, why: 'empty' }
  const goal = text(raw.goal, 60)
  const first = text(raw.first, FIRST_MAX + 40)
  const deliverable = text(raw.deliverable, 60)
  const acc = raw.accept && typeof raw.accept === 'object' ? raw.accept : null
  const kind = ACCEPT_KINDS.includes(String(acc?.kind || raw.acceptKind || ''))
    ? String(acc?.kind || raw.acceptKind)
    : ''
  const what = text(acc?.what || raw.acceptWhat, 80)
  const steps = (Array.isArray(raw.steps) ? raw.steps : [])
    .map((s) => text(s, 60))
    .filter(Boolean)
    .slice(0, 5)
  const stuck = text(raw.stuck, 80)
  if (!goal && !first && !deliverable && !kind && !what && !steps.length && !stuck) {
    return { ok: false, packet: null, why: 'empty' }
  }
  const packet = { goal, first, steps, deliverable, accept: { kind, what } }
  if (stuck) packet.stuck = stuck
  return { ok: true, packet, why: '' }
}

/**
 * 工作包缺什么。空数组表示可以进计划。
 * 只判「写了但没写全」——一格都没填的那种不算工作包，也就不挡计划
 * （草案由代码出，本来就没有工作包；挡掉它等于计划永远定不下来）。
 * @returns {string[]}
 */
export function packetProblems(packet, { apps = [] } = {}) {
  if (!packet || typeof packet !== 'object' || Array.isArray(packet)) return []
  const out = []
  if (!text(packet.first, 200)) out.push('缺第一步')
  else {
    const why = checkFirst(packet.first, { apps })
    if (why) out.push(why)
  }
  if (!text(packet.deliverable, 200)) out.push('缺交付物')
  const acc = packet.accept && typeof packet.accept === 'object' ? packet.accept : {}
  if (!ACCEPT_KINDS.includes(String(acc.kind || ''))) out.push('缺验收方式')
  else if (!text(acc.what, 200)) out.push('验收方式没写清怎么验')
  return out
}

/**
 * 计划里哪些工作包进不了计划。
 * @returns {Array<{title:string, why:string}>}
 */
export function badPackets(plan, ctx = {}) {
  const items = Array.isArray(plan?.items) ? plan.items : []
  const out = []
  for (const i of items) {
    const probs = packetProblems(i?.packet, ctx)
    if (probs.length) out.push({ title: String(i.title || i.id || i.protocolId || '这一局'), why: probs.join('、') })
  }
  return out
}

/** 缺项时给他看的那一句；不缺返回空串。 */
export function packetPrompt(plan, ctx = {}) {
  const bad = badPackets(plan, ctx)
  if (!bad.length) return ''
  return '这几个工作包还进不了计划：' + bad.map((b) => b.title + '（' + b.why + '）').join('；') +
    '。交付物和验收方式都要写，验收方式只有证据、依据、判断三类。'
}

/** 今天计划里有几个「判断」类验收。 */
export function judgeCount(plan) {
  const items = Array.isArray(plan?.items) ? plan.items : []
  return items.filter((i) => String(i?.packet?.accept?.kind || '') === 'judge').length
}

/**
 * 判断类超额了没有。超额返回那一句；没超返回空串。
 * 只拦第 JUDGE_MAX_PER_DAY 个之后新增的——已经定下来的不回头追。
 */
export function judgeOver(plan) {
  const n = judgeCount(plan)
  return n > JUDGE_MAX_PER_DAY
    ? '「判断」类验收一天最多 ' + JUDGE_MAX_PER_DAY + ' 个，今天这份有 ' + n + ' 个。'
    : ''
}

/** 验收方式那几句人话。 */
export function acceptText(accept) {
  const kind = String(accept?.kind || '')
  const what = text(accept?.what, 200)
  if (!kind) return ''
  const name = kind === 'evidence' ? '证据' : kind === 'basis' ? '依据' : kind === 'judge' ? '判断' : kind
  return name + '：' + (what || '（没写清）')
}

/**
 * 局官该问的那一句。工作包在就对着工作包问，不在就退回协议原本的问句。
 *
 * 第一步那一拍问「第一步做了吗」，后面的拍问「在第几步」，交付物一直问「几张了」——
 * 这三句是施工书点名要的，别改成别的说法。
 * @returns {string}
 */
export function askOf(packet, { first = false, stepAsk = '' } = {}) {
  if (!packet || typeof packet !== 'object') return String(stepAsk || '')
  const parts = []
  parts.push(first ? '第一步做了吗：' + text(packet.first, FIRST_MAX) : '在第几步了？')
  if (text(packet.deliverable, 60)) {
    parts.push('交付物有几张了（要 ' + text(packet.deliverable, 60) + '）')
  }
  const head = parts.filter(Boolean).join('。')
  return stepAsk ? head + '。' + String(stepAsk) : head + '。'
}

/** 开局那一下念的话：第几句都行，第一步必须在最前面。 */
export function openSay(packet) {
  if (!packet || typeof packet !== 'object') return ''
  const first = text(packet.first, FIRST_MAX)
  const deliverable = text(packet.deliverable, 60)
  if (!first) return ''
  return '第一步：' + first + '。' + (deliverable ? '这一局的交付物：' + deliverable + '。' : '')
}

/** 拼进 prompt 的几行（鲸澜看得见工作包才备得下去）。 */
export function packetLines(packet) {
  if (!packet || typeof packet !== 'object') return []
  const lines = []
  if (text(packet.goal, 60)) lines.push('目标：' + text(packet.goal, 60))
  if (text(packet.first, FIRST_MAX)) lines.push('第一步：' + text(packet.first, FIRST_MAX))
  if (Array.isArray(packet.steps) && packet.steps.length) {
    lines.push('后面：' + packet.steps.map((s) => text(s, 60)).filter(Boolean).join(' → '))
  }
  if (text(packet.deliverable, 60)) lines.push('交付物：' + text(packet.deliverable, 60))
  const acc = acceptText(packet.accept)
  if (acc) lines.push('验收：' + acc)
  if (text(packet.stuck, 80)) lines.push('卡住时：' + text(packet.stuck, 80))
  return lines
}
