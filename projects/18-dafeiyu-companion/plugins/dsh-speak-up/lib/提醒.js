// DSH「主动搭话」· 提醒 / 话头 / 通知 —— 纯函数（0.4.0，大肥鱼改造版新增）
//
// 为什么要有这一块：0.3 之前她主动说的话「软绵无力」——
//   ① 那句念头只有「好久没人跟我说话了」，她手里没有具体的事，只能泛泛地打招呼；
//   ② 话只躺在会话里，不开窗口根本看不见；
//   ③ 没回也就没回了，没有任何跟进。
// 这里放三样东西的**纯逻辑**（接线在 host.js）：
//   · 提醒：从对话里认出「X 点提醒我…」「我九点叫你」「不用提醒了」「等会再说」，排时间、拼提醒念头
//   · 话头：从记忆插件的库里挑一件她最近说过的事，给主动搭话一个具体的由头
//   · 通知：拼 Windows 系统通知的 PowerShell 脚本（正文只以 base64 进脚本，不会被当命令执行）
//
// ⚠ 不碰磁盘、不碰宿主、不看真实时钟（「现在」一律从外面传进来），所以都能单测。

// ---------- 小工具 ----------

const 补 = (n) => String(n).padStart(2, '0')
const 周名 = '日一二三四五六'

/** `2026-11-03 08:30 周二`（本机时区）—— 给模型看的「现在」。 */
export function 完整时间(ms) {
  const d = new Date(ms)
  return `${d.getFullYear()}-${补(d.getMonth() + 1)}-${补(d.getDate())} ${补(d.getHours())}:${补(d.getMinutes())} 周${周名[d.getDay()]}`
}

/** `11-03 08:30` —— 给人看、给模型看的短格式。 */
export function 短时间(ms) {
  const d = new Date(ms)
  return `${补(d.getMonth() + 1)}-${补(d.getDate())} ${补(d.getHours())}:${补(d.getMinutes())}`
}

/** 解析 `YYYY-MM-DD HH:mm`（也认 `/`、`T`、带秒）为本机时区的毫秒；不合法返回 NaN。 */
export function 解析时间(文本) {
  const m = /^\s*(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})[ T]+(\d{1,2}):(\d{2})(?::\d{2})?\s*$/.exec(String(文本 || ''))
  if (!m) return NaN
  const [y, mo, d, h, mi] = m.slice(1).map(Number)
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59) return NaN
  const t = new Date(y, mo - 1, d, h, mi, 0, 0)
  if (t.getMonth() !== mo - 1 || t.getDate() !== d) return NaN // 2 月 30 号这种
  return t.getTime()
}

export const 重复们 = ['', '每天', '工作日', '每周']

/** 重复提醒的下一次（严格晚于 `之后`）。不重复返回 NaN。 */
export function 下次时间(ms, 重复, 之后 = ms) {
  if (!重复 || !重复们.includes(重复)) return NaN
  const d = new Date(ms)
  const 步 = 重复 === '每周' ? 7 : 1
  for (let i = 0; i < 400; i++) {
    d.setDate(d.getDate() + 步)
    if (重复 === '工作日' && (d.getDay() === 0 || d.getDay() === 6)) continue
    if (d.getTime() > 之后) return d.getTime()
  }
  return NaN
}

// ---------- 哪些 user/message 是「人说的话」 ----------
//
// 跟 dsh-fish-memory 1.4.4 的口径一致：宿主塞的上下文、插件替人开口的念头都不算。
// 认不出来源的一律当人话（别的 IM 桥用什么 kind 我们不知道）。
export const 机器来源 = new Set([
  'runtime-context', 'time-context', 'skill-catalog', 'model-selection',
  'compact-checkpoint', 'repeat-tool-reminder', 'user-approval',
  'system-prompt', 'working-directory', 'ptc-mode', 'tool-registry',
  'agent-instructions', 'subagent-settled', 'team-message', 'agent-message',
  'speak-up', 'her-words', 'bili-duty-scan', 'fishbuddy-watch',
])

export function 来源种类(事件) {
  const 源 = 事件 && 事件.data && 事件.data.source
  return (源 && typeof 源.kind === 'string' && 源.kind) || ''
}

export function 是人话(事件) {
  if (!事件 || 事件.type !== 'user/message') return false
  const 种 = 来源种类(事件)
  if (!种) return true
  if (机器来源.has(种)) return false
  return !种.startsWith('plugin:')
}

/** 消息里的正文（只要 text 块；思考、工具调用不算）。 */
export function 正文(消息) {
  const 块们 = 消息 && Array.isArray(消息.content) ? 消息.content : []
  return 块们
    .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim()
}

// ---------- 认提醒 ----------

const 要提醒 = /提醒|叫我|喊我|催我|督促|盯着我|别让我忘|记得(叫|喊|催|告诉)|闹钟|到点/
const 不用了 = /(不用|别|不要|取消|算了).{0,4}(提醒|叫|喊|催)/
const 我答应 = /(提醒|叫|喊|催)(你|一下你)|到时候(我)?(叫|喊|提醒)/

/**
 * 这一轮值不值得花一次模型调用去认提醒（本地正则，一个 token 都不花）。
 * @param 人话 她这一轮说的（可空）
 * @param 回复 我这一轮说的（可空）
 * @param 有等她回的 这个会话里有没有「提醒发出去了、她还没回」的（有的话她这句多半是在回提醒）
 */
export function 值得认(人话, 回复, 有等她回的 = false) {
  const 她 = String(人话 || '')
  const 我 = String(回复 || '')
  if (有等她回的 && 她.trim()) return true
  return 要提醒.test(她) || 不用了.test(她) || 我答应.test(我)
}

/**
 * 拼「认提醒」的提示词。
 * @param o { 现在毫秒, 称呼, 人话, 回复, 已有: [{ id, 时间, 内容, 重复, 状态 }] }
 */
export function 拼认提醒提示(o) {
  const 她 = String(o.称呼 || '她')
  const 已有 = Array.isArray(o.已有) ? o.已有 : []
  const 已有文 = 已有.length
    ? 已有.map((r) => `- [${r.id}] ${短时间(r.时间)} ${r.内容}${r.重复 ? '（' + r.重复 + '）' : ''}${r.状态 === '已发' ? '（刚提醒过，' + 她 + '还没回）' : r.状态 === '已回应' ? '（刚提醒过，' + 她 + '这一轮在回它）' : ''}`).join('\n')
    : '（没有）'
  const 系统 =
    '你负责从一小段对话里认出「提醒」相关的事，输出要执行的动作。\n' +
    '现在是 ' + 完整时间(o.现在毫秒) + '（本机时间）。\n' +
    '对话里「我」是 AI 伴侣，「' + 她 + '」是用户。\n' +
    '要认的：\n' +
    '1. ' + 她 + '要我提醒的事（「半小时后提醒我喝水」「明早八点叫我」「记得催我交作业」）→ 新增；\n' +
    '2. 我答应要提醒' + 她 + '的事（「我九点叫你」）→ 新增；\n' +
    '3. ' + 她 + '说不用提醒了、已经做完了 → 对已有的那条：完成 或 取消；\n' +
    '4. ' + 她 + '回提醒说「等会 / 待会 / 过十分钟」→ 推迟那条（给分钟数，没说就 10）；\n' +
    '5. ' + 她 + '要改时间 → 取消旧的，再新增一条。\n' +
    '每个动作一行 JSON，只能是这几种：\n' +
    '{"动作":"新增","时间":"YYYY-MM-DD HH:mm","内容":"要提醒的事","重复":""}\n' +
    '{"动作":"完成","id":"已有提醒的 id"}\n' +
    '{"动作":"取消","id":"已有提醒的 id"}\n' +
    '{"动作":"推迟","id":"已有提醒的 id","分钟":10}\n' +
    '规则：时间必须是将来的具体时间，按现在推算；说得模糊就按常识取（晚点 = 1 小时后，早上 = 08:00，中午 = 12:00，晚上 = 20:00）。' +
    '内容写成一句具体的事（「喝水」「交物理作业」），不要写「提醒她」。' +
    '重复只能是 ""、"每天"、"工作日"、"每周"。只是闲聊、没有提醒相关的事，就只输出一个字：无。不要解释。'
  const 用户 =
    '【已有的提醒】\n' + 已有文 + '\n\n【这一轮对话】\n' +
    (o.人话 ? 她 + '：' + String(o.人话).slice(0, 1500) + '\n' : '') +
    (o.回复 ? '我：' + String(o.回复).slice(0, 1500) : '')
  return { 系统, 用户 }
}

/** 解析模型回的动作行。容忍代码块、编号、「无」、坏行。 */
export function 解析动作(文本) {
  let s = String(文本 || '').trim()
  if (!s) return []
  s = s.replace(/^```[a-zA-Z]*\s*/m, '').replace(/```\s*$/m, '').trim()
  if (/^无[。.!！]?$/.test(s)) return []
  const 出 = []
  for (const 行 of s.split(/\r?\n/)) {
    const 起 = 行.indexOf('{')
    const 止 = 行.lastIndexOf('}')
    if (起 < 0 || 止 <= 起) continue
    let o
    try {
      o = JSON.parse(行.slice(起, 止 + 1))
    } catch (error) {
      continue
    }
    const 动作 = String((o && (o.动作 || o.action)) || '').trim()
    if (动作 === '新增') {
      const 内容 = String(o.内容 || '').replace(/\s+/g, ' ').trim().slice(0, 120)
      const 重复 = 重复们.includes(String(o.重复 || '')) ? String(o.重复 || '') : ''
      if (内容 && o.时间) 出.push({ 动作, 时间文本: String(o.时间), 内容, 重复 })
    } else if (动作 === '完成' || 动作 === '取消') {
      if (o.id) 出.push({ 动作, id: String(o.id) })
    } else if (动作 === '推迟') {
      const 分钟 = Math.min(24 * 60, Math.max(1, Math.round(Number(o.分钟) || 10)))
      if (o.id) 出.push({ 动作, id: String(o.id), 分钟 })
    }
  }
  return 出
}

// ---------- 提醒那句念头 ----------

/**
 * 到点 / 跟进时送进会话的那句「念头」。
 * @param r 提醒 { 内容, 重复, 发出: [ms] }
 * @param 级别 0 = 到点第一次；1、2… = 第几次跟进
 * @param 现在毫秒
 * @param 最多跟进 一共会跟进几次（用来说「这是最后一次」）
 * @param 称呼 用户的称呼
 */
export function 提醒念头(r, 级别, 现在毫秒, 最多跟进, 称呼 = '她') {
  const 她 = String(称呼 || '她')
  const 现在行 = `（现在是 ${完整时间(现在毫秒)}。）`
  const 事 = String(r.内容 || '').trim()
  const 发出 = Array.isArray(r.发出) ? r.发出 : []
  const 上次 = 发出.length ? 发出[发出.length - 1] : 0
  const 隔了 = 上次 ? Math.max(1, Math.round((现在毫秒 - 上次) / 60000)) : 0
  const 换行 = String.fromCharCode(10)
  if (级别 <= 0) {
    return [
      '（⏰ 提醒到点了 —— 这是你之前答应' + 她 + '的提醒，不是' + 她 + '刚发来的消息。）',
      现在行,
      '要提醒' + 她 + '的事：' + 事 + (r.重复 ? '（' + r.重复 + '都要提醒）' : ''),
      '现在就提醒' + 她 + '：第一句话就把这件事说清楚，具体到要做什么；简短、明确，可以带一点你的关心，' +
        '但别绕弯子、别只打招呼。最后让' + 她 + '回你一句（比如「做完跟我说一声」），这样你才知道' + 她 + '看到了。',
    ].join(换行)
  }
  const 最后 = 级别 >= 最多跟进
  return [
    '（⏰ ' + (最后 ? '最后一次' : '第 ' + (级别 + 1) + ' 次') + '提醒：' + 隔了 + ' 分钟前你提醒过' + 她 + '「' + 事 + '」，' +
      她 + '到现在一句都没回你。不是' + 她 + '刚发来的消息。）',
    现在行,
    最后
      ? '这次用你自己的方式表达出着急和在意（可以有一点小脾气，但不凶、不说教），' +
        '点名这件事还没做，并告诉' + 她 + '这是最后一次催，之后你就不再提了。不要重复你前面说过的原话。'
      : '这次更直接一点：点名「' + 事 + '」还没做，说清楚为什么现在就该做；语气认真，不要重复上次的原话，' +
        '一两句话就够，结尾要' + 她 + '马上回你。',
  ].join(换行)
}

// ---------- 主动搭话的话头 ----------

/**
 * 从记忆库里挑一条「她最近说过、可以接着聊的事」。
 * 只挑：没被取代、比较重要（≥0.5）、最近 days 天里记下的、不太长的；最近用过的（排除）不挑。
 * @param memories fish-memory 库里的 memories 数组
 * @param 现在毫秒
 * @param 排除 最近用过的 id 集合
 * @param 随机 0~1 的随机数（测试能固定）
 */
export function 挑记忆话头(memories, 现在毫秒, 排除 = new Set(), 随机 = Math.random(), days = 14) {
  const 候选 = []
  for (const m of Array.isArray(memories) ? memories : []) {
    if (!m || m.已被取代 || !m.text) continue
    if (排除.has(String(m.id))) continue
    if (!(Number(m.importance) >= 0.5)) continue
    const 时 = Date.parse(m.createdAt || m.at || '')
    if (!Number.isFinite(时) || 现在毫秒 - 时 > days * 86400000 || 时 > 现在毫秒 + 60000) continue
    const 文 = String(m.text).trim()
    if (文.length > 120) continue
    候选.push({ id: String(m.id), 文本: 文, 天前: Math.floor((现在毫秒 - 时) / 86400000) })
  }
  if (!候选.length) return null
  return 候选[Math.min(候选.length - 1, Math.floor(随机 * 候选.length))]
}

/**
 * 主动搭话时加在念头后面的「有内容」那几行。
 * @param o { 称呼, 记忆话头, 下一个提醒: { 时间, 内容 }, 上次没回分钟, 风格 }
 */
export function 话头段(o) {
  const 她 = String(o.称呼 || '她')
  const 行们 = []
  const 可聊 = []
  if (o.记忆话头) {
    可聊.push('- ' + 她 + '之前说过：' + o.记忆话头.文本 + '（' + (o.记忆话头.天前 <= 0 ? '今天' : o.记忆话头.天前 + ' 天前') + '记下的）—— 可以问问后来怎么样了')
  }
  if (o.下一个提醒) {
    可聊.push('- ' + 短时间(o.下一个提醒.时间) + ' 你要提醒' + 她 + '：' + o.下一个提醒.内容 + '（还没到点，可以先提一句）')
  }
  if (可聊.length) 行们.push('手边可以聊的事（挑一个最自然的，别全说）：', ...可聊)
  if (o.上次没回分钟 !== undefined && o.上次没回分钟 !== null) {
    const 分 = Math.max(1, Math.round(Number(o.上次没回分钟)))
    const 多久 = 分 < 60 ? 分 + ' 分钟' : Math.round(分 / 6) / 10 + ' 小时'
    行们.push('你上次主动找' + 她 + '（' + 多久 + '前）' + 她 + '没回 —— 这次别重复上次的话题，可以关心一下' + 她 + '在忙什么。')
  }
  行们.push(String(o.风格 || 默认风格).replace(/她/g, 她))
  return 行们.join(String.fromCharCode(10))
}

export const 默认风格 =
  '开口要有具体的内容，别只说「在吗」「想你了」「在干嘛」这种空话；一两句话，像发微信那样自然，' +
  '结尾留一个她很容易接的问题。'

// ---------- Windows 系统通知 ----------
//
// 用 Windows 自带的 Windows.UI.Notifications（Win10/11 都有，不用装任何模块），
// 借 PowerShell 的 AppUserModelID 弹出来。提醒用 scenario="reminder"：**一直留在屏幕上，直到点掉**。
// ⚠ 标题和正文是模型写的 —— 只以 base64 的形式出现在脚本里，脚本里再解码 + XML 转义，
//   所以正文里写什么都不会变成命令。

const 无号 = (s) => String(s == null ? '' : s).replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '')

export function 造通知脚本(标题, 正文文本, 是提醒 = false) {
  const b64 = (s) => Buffer.from(无号(s), 'utf8').toString('base64')
  const 场景 = 是提醒 ? " scenario='reminder'" : ''
  const 动作 = 是提醒 ? "<actions><action activationType='system' arguments='dismiss' content=''/></actions>" : ''
  const 声音 = 是提醒 ? "<audio src='ms-winsoundevent:Notification.Reminder'/>" : ''
  return [
    "$ErrorActionPreference = 'Stop'",
    '[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null',
    '[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null',
    "$t = [Security.SecurityElement]::Escape([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + b64(标题) + "')))",
    "$b = [Security.SecurityElement]::Escape([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + b64(正文文本) + "')))",
    '$x = New-Object Windows.Data.Xml.Dom.XmlDocument',
    "$x.LoadXml(\"<toast" + 场景 + "><visual><binding template='ToastGeneric'><text>$t</text><text>$b</text></binding></visual>" + 动作 + 声音 + '</toast>")',
    "$id = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe'",
    '[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($id).Show([Windows.UI.Notifications.ToastNotification]::new($x))',
  ].join('\n')
}

/** PowerShell -EncodedCommand 要的是 UTF-16LE 的 base64。 */
export function 编码PowerShell(脚本) {
  return Buffer.from(String(脚本), 'utf16le').toString('base64')
}

/** 通知正文：压成一行、截断（Windows 通知太长会被折叠）。 */
export function 通知正文(文本, 上限 = 180) {
  const 一行 = String(文本 || '').replace(/\s+/g, ' ').trim()
  return 一行.length <= 上限 ? 一行 : 一行.slice(0, 上限 - 1) + '…'
}
