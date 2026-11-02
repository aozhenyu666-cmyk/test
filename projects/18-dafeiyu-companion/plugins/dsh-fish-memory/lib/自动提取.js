// DSH 鱼的记忆 · 自动提取（1.6.0，大肥鱼改造版新增）
//
// 解决的问题：记忆以前**只靠模型自己调 remember 工具**才会写。
//   · 模型经常忘了调（尤其聊得投入的时候）；
//   · PTC 模式下 remember 根本不在模型的工具表里 —— 那种会话一条都记不下来。
// 现在：每攒够几句她真说的话（或者聊完闲下来一会儿），插件自己拿这段对话去问一次模型
//   「哪些值得长期记住」，再走跟 remember **同一条写入路**（去重 / 强化 / 取代 / 事实键 / 每天配额都一样）。
//
// 这个文件只放**纯函数**（收集对话、拼提示词、解析回答），不碰磁盘、不碰宿主 ——
//   接线（什么时候触发、调模型、写库）在 index.js 里。这样能单独测。

/** 默认配置（loadCfg().自动提取 里没写的就用这些）。 */
export const 默认配置 = Object.freeze({
  开: true,          // 总开关
  每几句: 3,         // 攒够这么多句「她真说的话」就提一次
  闲置分钟: 10,      // 不够数，但聊完闲了这么久，也提一次（至少要有 1 句）
  每次最多: 4,       // 一次最多写几条（还受每天配额 dailyQuota 管）
  对话字数上限: 6000, // 送给模型的那段对话最多多少字（太长只留最后面）
})

const 夹 = (v, 兜, 小, 大) => {
  const n = Number(v)
  if (!Number.isFinite(n)) return 兜
  return Math.min(大, Math.max(小, n))
}

/** 把配置文件里的那一段洗成一定能用的样子。 */
export function 规整配置(原) {
  const o = 原 && typeof 原 === 'object' ? 原 : {}
  return {
    开: o.开 === undefined ? 默认配置.开 : o.开 === true || o.开 === 'true' || o.开 === 1,
    每几句: Math.round(夹(o.每几句, 默认配置.每几句, 1, 50)),
    闲置分钟: 夹(o.闲置分钟, 默认配置.闲置分钟, 0, 1440),
    每次最多: Math.round(夹(o.每次最多, 默认配置.每次最多, 1, 10)),
    对话字数上限: Math.round(夹(o.对话字数上限, 默认配置.对话字数上限, 500, 30000)),
  }
}

/** 事件的时间（毫秒）。宿主事件是 `time`，老数据可能是 `at` / ISO 字符串。 */
export function 事件时间(e) {
  if (!e) return 0
  const t = e.time !== undefined ? e.time : e.at
  if (typeof t === 'number') return t
  const p = Date.parse(String(t || ''))
  return Number.isFinite(p) ? p : 0
}

/**
 * 从会话事件里挑出「上次提取之后」的对话。
 * @param 事件们 session.snapshotEvents()
 * @param 起点 毫秒；只要比它晚的事件
 * @param 工具 { 是机器话(e) → bool, 净化(文本) → 文本, 取文本(消息) → 文本 }（从 index.js 传进来，免得循环引用）
 * @returns { 行们: [{ 谁: '她'|'我', 文本, 时间 }], 她说了几句, 最后时间 }
 */
export function 收集新对话(事件们, 起点, 工具) {
  const ev = Array.isArray(事件们) ? 事件们 : []
  const 行们 = []
  let 她说了几句 = 0
  let 最后时间 = Number(起点) || 0
  for (const e of ev) {
    const type = e && (e.type || e.kind)
    if (type !== 'user/message' && type !== 'assistant/message') continue
    const t = 事件时间(e)
    if (!(t > (Number(起点) || 0))) continue
    if (type === 'user/message') {
      if (工具.是机器话(e)) continue // 宿主塞的上下文、speak-up 那句念头 —— 都不是她说的
      const p = e.payload !== undefined ? e.payload : e.data
      const 文 = String(工具.净化(工具.取文本(p)) || '').replace(/\s+/g, ' ').trim()
      if (!文) continue
      行们.push({ 谁: '她', 文本: 文, 时间: t })
      她说了几句++
    } else {
      const m = e.data && e.data.message ? e.data.message : e.data
      // 只要正文，思考和工具调用不算
      const 块们 = m && Array.isArray(m.content) ? m.content : []
      const 文 = 块们
        .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
        .map((b) => b.text)
        .join(' ')
        .replace(/\s+/g, ' ')
        .trim()
      if (!文) continue
      行们.push({ 谁: '我', 文本: 文, 时间: t })
    }
    if (t > 最后时间) 最后时间 = t
  }
  return { 行们, 她说了几句, 最后时间 }
}

/** 把对话行拼成文字；太长就只留最后面（最近的话最要紧）。 */
export function 对话成文(行们, 称呼, 上限 = 默认配置.对话字数上限) {
  const 她 = String(称呼 || '她')
  const 全 = (行们 || []).map((r) => (r.谁 === '她' ? 她 : '我') + '：' + String(r.文本).slice(0, 1500))
  const 出 = []
  let 长 = 0
  for (let i = 全.length - 1; i >= 0; i--) {
    if (长 + 全[i].length > 上限 && 出.length) break
    出.unshift(全[i])
    长 += 全[i].length + 1
  }
  return 出.join('\n')
}

/**
 * 拼给模型的提示词。
 * @param o { 称呼, 我叫, 对话文, 已记住: [文本], 最多 }
 * @returns { 系统, 用户 }
 */
export function 拼提示(o) {
  const 她 = String(o.称呼 || '她')
  const 我叫 = String(o.我叫 || '').trim()
  const 最多 = Number(o.最多) || 默认配置.每次最多
  const 系统 =
    '你在帮「我」' + (我叫 ? '（' + 我叫 + '）' : '') + '整理长期记忆。下面是我和' + 她 + '最近的一段对话。\n' +
    '挑出**值得长期记住**的事，每件一行 JSON。\n' +
    '要记：' + 她 + '明确说出的重要信息 —— 身份、经历、身体和健康、喜欢和讨厌、计划和打算、' +
    '重要的人和关系、情绪上的大事；我们之间的约定；状态的变化（以前没有、现在有了）。\n' +
    '不记：寒暄和客套；日常流水（吃了什么、天气、几点睡）除非' + 她 + '特别强调；' +
    '我自己说的话和我做的操作；只在这一刻有用的东西；下面「已经记住的」里已有的事。\n' +
    '规则：\n' +
    '1. 一行只写一件事，一句话说清（不超过 80 字）。人称：我就写「我」，用户写「' + 她 + '」，别人直接写名字。' +
    '不要写「今天 / 昨天 / 刚才」，时间由系统记。\n' +
    '2. 每行严格是这个格式：{"text":"…","importance":0.5,"tags":["…"],"事实键":"","取代":""}\n' +
    '   · importance：1 = 约定、关系、核心状态；0.5 = 比较重要；0 = 随口提过但值得留。\n' +
    '   · tags：2~5 个以后可能用来想起它的词。\n' +
    '   · 事实键：只给「会变的那一个值」用，写成「对象.方面」（如 ' + 她 + '.职业）。键里不许写具体的值，' +
    '不许用「…相关 / …其他 / …内容」这种万能键；拿不准就留空。\n' +
    '   · 取代：如果这条是在更新「已经记住的」里某一条（状态变了），填那条的原文片段；否则留空。\n' +
    '3. 最多 ' + 最多 + ' 行，宁缺毋滥。没有值得记的，只输出一个字：无\n' +
    '4. 只输出这些行，不要解释、不要编号、不要代码块。'
  const 已记 = (o.已记住 || []).filter(Boolean).slice(0, 40)
  const 用户 =
    (已记.length ? '【已经记住的（别重复）】\n' + 已记.map((t) => '- ' + String(t).slice(0, 120)).join('\n') + '\n\n' : '') +
    '【最近的对话】\n' + String(o.对话文 || '')
  return { 系统, 用户 }
}

/**
 * 解析模型的回答。容忍：代码块包着、整个是数组、行首有编号或「- 」、「无」。
 * @returns [{ text, importance, tags, 事实键, 取代 }]（text 一定非空）
 */
export function 解析提取(文本, 最多 = 默认配置.每次最多) {
  let s = String(文本 || '').trim()
  if (!s) return []
  s = s.replace(/^```[a-zA-Z]*\s*/m, '').replace(/```\s*$/m, '').trim()
  if (/^无[。.!！]?$/.test(s)) return []
  const 对象们 = []
  // 整个是一个 JSON 数组
  if (s.startsWith('[')) {
    try {
      const arr = JSON.parse(s)
      if (Array.isArray(arr)) 对象们.push(...arr)
    } catch (error) {}
  }
  if (!对象们.length) {
    for (const 行 of s.split(/\r?\n/)) {
      const 去头 = 行.trim().replace(/^(\d+[.、)]\s*|[-*•]\s*)/, '')
      const 起 = 去头.indexOf('{')
      const 止 = 去头.lastIndexOf('}')
      if (起 < 0 || 止 <= 起) continue
      try {
        对象们.push(JSON.parse(去头.slice(起, 止 + 1)))
      } catch (error) {}
    }
  }
  const 出 = []
  const 见过 = new Set()
  for (const o of 对象们) {
    if (!o || typeof o !== 'object') continue
    const text = String(o.text || o.内容 || '').replace(/\s+/g, ' ').trim().slice(0, 200)
    if (!text || 见过.has(text)) continue
    见过.add(text)
    let imp = Number(o.importance !== undefined ? o.importance : o.重要度)
    if (!Number.isFinite(imp)) imp = 0.5
    imp = imp >= 0.75 ? 1 : imp >= 0.25 ? 0.5 : 0
    const tags = Array.isArray(o.tags) ? o.tags.map((x) => String(x).trim()).filter(Boolean).slice(0, 6) : []
    const 条 = { text, importance: imp, tags }
    const 键 = String(o.事实键 || o.key || '').trim()
    if (键) 条.事实键 = 键
    const 取代 = String(o.取代 || '').trim()
    if (取代) 条.取代 = 取代
    出.push(条)
    if (出.length >= 最多) break
  }
  return 出
}
