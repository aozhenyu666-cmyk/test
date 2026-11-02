// 0.4.0 提醒 / 跟进 / 通知 / 话头 —— 纯函数 + 假宿主整条跑一遍
//
// ⚠ 不碰真 DSH、不碰你的会话：DSH_HOME 指到临时目录，agent / 模型都是假的。
// 跑法： node tests/提醒.mjs

import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const 家 = mkdtempSync(join(tmpdir(), 'speakup-remind-'))
process.env.DSH_HOME = 家

const 提 = await import(new URL('../lib/提醒.js', import.meta.url).href)
const 插件 = await import(new URL('../lib/host.js', import.meta.url).href)

let 挂 = 0
const 报 = (标签, 过, 详情) => { if (!过) 挂++; console.log(`${过 ? 'PASS' : 'FAIL'}  ${标签}${详情 ? '  :: ' + 详情 : ''}`) }
const 等 = (ms) => new Promise((r) => setTimeout(r, ms))
const 补 = (n) => String(n).padStart(2, '0')
const 写时间 = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${补(d.getMonth() + 1)}-${补(d.getDate())} ${补(d.getHours())}:${补(d.getMinutes())}` }

// ================= 纯函数 =================

{
  const t = 提.解析时间('2026-11-03 08:30')
  const d = new Date(t)
  报('解析时间：本机时区的 年月日时分', d.getFullYear() === 2026 && d.getMonth() === 10 && d.getDate() === 3 && d.getHours() === 8 && d.getMinutes() === 30)
  报('解析时间：2 月 30 号 / 乱写 → NaN', Number.isNaN(提.解析时间('2026-02-30 08:00')) && Number.isNaN(提.解析时间('明天早上')))
  const 周五 = 提.解析时间('2026-11-06 09:00') // 2026-11-06 是周五
  报('下次时间：每天 → +1 天', 提.下次时间(周五, '每天') === 提.解析时间('2026-11-07 09:00'))
  报('下次时间：工作日 → 跳过周末到周一', 提.下次时间(周五, '工作日') === 提.解析时间('2026-11-09 09:00'))
  报('下次时间：每周 → +7 天', 提.下次时间(周五, '每周') === 提.解析时间('2026-11-13 09:00'))
  报('下次时间：不重复 → NaN', Number.isNaN(提.下次时间(周五, '')))
  报('下次时间：严格晚于「之后」（错过好几天的每天提醒，直接跳到将来）',
    提.下次时间(周五, '每天', 提.解析时间('2026-11-10 10:00')) === 提.解析时间('2026-11-11 09:00'))
}

{
  报('值得认：她说「半小时后提醒我喝水」', 提.值得认('半小时后提醒我喝水', ''))
  报('值得认：她说「明早八点叫我起床」', 提.值得认('明早八点叫我起床', ''))
  报('值得认：她说「不用提醒我了」', 提.值得认('不用提醒我了', ''))
  报('值得认：我答应「九点我提醒你」', 提.值得认('', '好呀，九点我提醒你'))
  报('值得认：闲聊不花钱', !提.值得认('今天天气不错', '是呀，适合出去走走'))
  报('值得认：有等她回的提醒 → 她说什么都要认（「等会」）', 提.值得认('等会', '', true))
}

{
  const 动作 = 提.解析动作([
    '```',
    '{"动作":"新增","时间":"2026-11-03 08:30","内容":"吃药","重复":"每天"}',
    '{"动作":"新增","时间":"2026-11-03 09:00","内容":"","重复":""}',
    '{"动作":"推迟","id":"r3","分钟":0}',
    '{"动作":"完成","id":"r1"}',
    '{"动作":"乱来","id":"r9"}',
    '不是 JSON',
    '{"动作":"新增","时间":"2026-11-03 10:00","内容":"开会","重复":"每月"}',
    '```',
  ].join('\n'))
  报('解析动作：新增 / 推迟（没给分钟或给 0 → 10）/ 完成；空内容、未知动作、坏行丢掉；不认识的重复当不重复',
    动作.length === 4 && 动作[0].重复 === '每天' && 动作[1].动作 === '推迟' && 动作[1].分钟 === 10 &&
    动作[2].动作 === '完成' && 动作[3].内容 === '开会' && 动作[3].重复 === '', JSON.stringify(动作))
  报('解析动作：「无」→ 空', 提.解析动作('无').length === 0)
}

{
  const 现在 = 提.解析时间('2026-11-03 08:30')
  const r = { 内容: '吃药', 重复: '每天', 发出: [] }
  const 一 = 提.提醒念头(r, 0, 现在, 2, '小鱼')
  报('提醒念头·第一次：标明不是她发的、事情具体、要她回一句',
    一.includes('⏰ 提醒到点了') && 一.includes('吃药') && 一.includes('每天都要提醒') && 一.includes('回你一句') && 一.includes('小鱼'), 一)
  const r2 = { 内容: '吃药', 发出: [现在 - 15 * 60000] }
  const 二 = 提.提醒念头(r2, 1, 现在, 2, '小鱼')
  报('提醒念头·第 2 次：说清隔了多久、更直接', 二.includes('第 2 次') && 二.includes('15 分钟前') && 二.includes('更直接'), 二)
  const 三 = 提.提醒念头(r2, 2, 现在, 2, '小鱼')
  报('提醒念头·最后一次：着急、说明是最后一次', 三.includes('最后一次') && 三.includes('不再提'), 三)
}

{
  const 现在 = Date.parse('2026-11-03T08:00:00Z')
  const 天 = 86400000
  const 库 = [
    { id: 'a', text: '她下周三要去面试产品经理', importance: 1, createdAt: new Date(现在 - 2 * 天).toISOString() },
    { id: 'b', text: '她不吃香菜', importance: 0, createdAt: new Date(现在 - 天).toISOString() },
    { id: 'c', text: '很久以前的事', importance: 1, createdAt: new Date(现在 - 40 * 天).toISOString() },
    { id: 'd', text: '被取代的', importance: 1, createdAt: new Date(现在).toISOString(), 已被取代: { 被谁: 'x' } },
  ]
  const 挑 = 提.挑记忆话头(库, 现在, new Set(), 0)
  报('挑记忆话头：只挑最近、重要、没被取代的', 挑 && 挑.id === 'a' && 挑.天前 === 2, JSON.stringify(挑))
  报('挑记忆话头：用过的不再挑', 提.挑记忆话头(库, 现在, new Set(['a']), 0) === null)
  const 段 = 提.话头段({ 称呼: '小鱼', 记忆话头: 挑, 下一个提醒: { 时间: 现在 + 3600000, 内容: '吃药' }, 上次没回分钟: 180 })
  报('话头段：记忆、快到点的提醒、上次没回、风格都在，称呼换成「小鱼」',
    段.includes('小鱼之前说过：她下周三要去面试产品经理') && 段.includes('吃药') && 段.includes('3 小时前') &&
    段.includes('别只说「在吗」') && 段.includes('小鱼很容易接'), 段)
  报('话头段：什么都没有时只剩风格那一句', 提.话头段({ 称呼: '小鱼' }).split('\n').length === 1)
}

{
  const 坏 = "'); Remove-Item C:\\ -Recurse; ('$(calc)`\"<x>"
  const 脚本 = 提.造通知脚本('⏰ 大肥鱼', 坏, true)
  报('通知脚本：标题/正文只以 base64 出现，原文进不了脚本', !脚本.includes('Remove-Item') && !脚本.includes('calc') && !脚本.includes('大肥鱼'))
  报('通知脚本：提醒用 scenario=reminder（一直留在屏幕上）+ 关闭按钮', 脚本.includes("scenario='reminder'") && 脚本.includes("arguments='dismiss'"))
  报('通知脚本：普通搭话不常驻', !提.造通知脚本('a', 'b', false).includes('scenario'))
  const 解 = Buffer.from(提.编码PowerShell('Write-Host 你好'), 'base64').toString('utf16le')
  报('EncodedCommand 是 UTF-16LE base64', 解 === 'Write-Host 你好')
  报('通知正文：压成一行并截断', 提.通知正文('a\n\nb ' + 'x'.repeat(300), 20).length === 20 && 提.通知正文('a\n\nb').startsWith('a b'))
}

{
  报('是人话：宿主上下文 / 主动搭话念头 / plugin:* 都不算', !提.是人话({ type: 'user/message', data: { source: { kind: 'runtime-context' } } }) &&
    !提.是人话({ type: 'user/message', data: { source: { kind: 'speak-up' } } }) &&
    !提.是人话({ type: 'user/message', data: { source: { kind: 'plugin:x' } } }) &&
    提.是人话({ type: 'user/message', data: { source: { kind: 'user' } } }) &&
    提.是人话({ type: 'user/message', data: {} }))
}

// ================= 假宿主整条跑 =================

const SID = 'session-甲甲甲甲0001'
mkdirSync(家, { recursive: true })
writeFileSync(join(家, 'fish-memory-config.json'), JSON.stringify({ ownerNames: { [SID]: '小鱼' } }), 'utf8')

const 路由 = new Map()
const 事件 = new Map()
const 停表们 = []
const 清理 = []
const 模型调用 = []
let 回答们 = []

const 甲 = {
  id: SID, status: 'idle', session: { id: SID }, options: { provider: 'fx', model: 'fx' },
  收到的: [],
  async followup(m) { this.收到的.push(m) },
}
const 假ctx = {
  agents: { get: (id) => (id === SID ? 甲 : undefined), list: () => [甲] },
  sessions: { flush: async () => true, get: (id) => (id === SID ? { id: SID } : undefined) },
  webServer: { register: (r) => { 路由.set(r.path, r.handler); return () => {} } },
  interval: (cb, ms) => { 停表们.push({ cb, ms }); return () => {} },
  logger: { info: () => {}, warn: () => {} },
  effect: (fn) => { const c = fn(); if (typeof c === 'function') 清理.push(c) },
  on: (n, cb) => { if (!事件.has(n)) 事件.set(n, []); 事件.get(n).push(cb); return () => {} },
  llm: {
    async *stream(req) {
      模型调用.push(req)
      const 答 = 回答们.shift()
      yield { type: 'text-delta', text: typeof 答 === 'function' ? 答(req) : (答 || '无') }
    },
  },
  get: (n) => {
    if (n === 'sessions') return { list: () => [{ id: SID }], get: (id) => (id === SID ? { id: SID } : undefined) }
    if (n === 'sessionTitle') return { get: () => ({ title: '大肥鱼' }) }
    if (n === 'sessionController') return { inspect: async () => ({ events: [] }) }
    return undefined
  },
}
插件.apply(假ctx)

const 调 = async (路径, body) => {
  let 体
  await 路由.get(路径)({ [Symbol.asyncIterator]: async function* () { yield Buffer.from(JSON.stringify(body || {})) } },
    { writeHead() {}, end(s) { 体 = JSON.parse(s) } })
  return 体
}
const 发事件 = (e) => (事件.get('session/event') || []).forEach((cb) => cb({ id: SID }, { time: Date.now(), ...e }))
const 她说 = (text) => 发事件({ type: 'user/message', data: { role: 'user', content: [{ type: 'text', text }], source: { kind: 'user' } } })
const 我说 = (text) => 发事件({ type: 'assistant/message', data: { message: { role: 'assistant', content: [{ type: 'text', text }] } } })
const 一轮 = async (人话, 回复, 我们起的 = false) => {
  发事件({ type: 'turn/start', data: { turn: 1 } })
  if (我们起的) 发事件({ type: 'user/message', data: { role: 'user', content: [{ type: 'text', text: '（念头）' }], source: { kind: 'speak-up' } } })
  if (人话) 她说(人话)
  if (回复) 我说(回复)
  发事件({ type: 'turn/end', data: { turn: 1 } })
  await 等(30)
}
const 库 = () => JSON.parse(readFileSync(join(家, 'speak-up-reminders.json'), 'utf8'))
const 改提醒 = (id, 改) => { const k = 库(); Object.assign(k.提醒们.find((r) => r.id === id), 改); writeFileSync(join(家, 'speak-up-reminders.json'), JSON.stringify(k), 'utf8') }
const 查提醒 = async () => { await 停表们.find((t) => t.ms === 30000).cb(); await 等(30) }
const 最后一句 = () => { const m = 甲.收到的.at(-1); return m ? m.content[0].text : '' }

await 调('/dsh-speak-up/save', { 目标会话: [SID], 唤醒后等待秒: 0, 提醒跟进分钟: 1, 提醒最多跟进: 2, 安静起: 0, 安静止: 0 })

报('查提醒的表挂上了（30 秒）', 停表们.some((t) => t.ms === 30000))

// 1. 闲聊不花钱
await 一轮('今天天气不错', '是呀')
报('闲聊那一轮：不调模型', 模型调用.length === 0)

// 2. 她要提醒 → 记下
const 半小时后 = Date.now() + 30 * 60000
回答们.push(`{"动作":"新增","时间":"${写时间(半小时后)}","内容":"喝水","重复":""}`)
await 一轮('半小时后提醒我喝水', '好，到时候叫你')
let r1 = 库().提醒们.find((r) => r.内容 === '喝水')
报('「半小时后提醒我喝水」→ 记下一条待发提醒', 模型调用.length === 1 && r1 && r1.状态 === '待发' && r1.sid === SID, JSON.stringify(r1))
报('认提醒的提示词里有「现在」和称呼「小鱼」', 模型调用[0].system.includes('现在是 ') && 模型调用[0].messages[0].content[0].text.includes('小鱼：半小时后提醒我喝水'))

// 同样的话再说一遍不重复记
回答们.push(`{"动作":"新增","时间":"${写时间(半小时后)}","内容":"喝水","重复":""}`)
await 一轮('记得提醒我喝水哦', '')
报('同一件事、同一个时间 → 不重复记', 库().提醒们.filter((r) => r.内容 === '喝水').length === 1)

// 3. 还没到点 → 不发
await 查提醒()
报('还没到点 → 不发', 甲.收到的.length === 0)

// 4. 到点（用「现在就发」）→ 送提醒念头
let st = await 调('/dsh-speak-up/reminders', { 动作: '立刻', id: r1.id })
r1 = 库().提醒们.find((r) => r.id === r1.id)
报('到点：送出一句提醒念头（⏰、事情、称呼）', 最后一句().includes('⏰ 提醒到点了') && 最后一句().includes('喝水') && 最后一句().includes('小鱼'), 最后一句())
报('到点：消息来源仍是 speak-up（记忆插件不会把它当她说的话）', 甲.收到的.at(-1).source.kind === 'speak-up')
报('到点：状态变成「已发」，记了发出时间', r1.状态 === '已发' && r1.发出.length === 1 && r1.级别 === 0)

// 5. 她那一轮说的话 → 弹通知（这台不是 Windows → 交给网页弹）
await 一轮('', '小鱼，该喝水啦！喝完跟我说一声～', true)
let 通 = await 调('/dsh-speak-up/notify', { after: 0 })
const 那条通知 = 通.通知.at(-1)
报('提醒那一轮说完 → 记一条通知（标题带 ⏰、正文是她说的话）',
  那条通知 && 那条通知.种类 === '提醒' && 那条通知.标题.includes('⏰') && 那条通知.标题.includes('大肥鱼') && 那条通知.正文.includes('该喝水啦'), JSON.stringify(那条通知))
await 等(30)
通 = await 调('/dsh-speak-up/notify', { after: 0 })
报('非 Windows：系统通知弹不出 → 标成交给网页弹', 通.通知.at(-1).网页 === true && 通.通知.at(-1).系统 === false, JSON.stringify(通.通知.at(-1)))
报('我们自己那句提醒回复不会被当成新提醒去认（不调模型）', 模型调用.length === 2)

// 6. 没回 → 跟进两次 → 不再催
改提醒(r1.id, { 发出: [Date.now() - 2 * 60000] })
await 查提醒()
报('没回 → 第 2 次提醒（更直接）', 最后一句().includes('第 2 次') && 库().提醒们.find((r) => r.id === r1.id).级别 === 1, 最后一句())
改提醒(r1.id, { 发出: [Date.now() - 4 * 60000, Date.now() - 2 * 60000] })
await 查提醒()
报('还没回 → 最后一次', 最后一句().includes('最后一次') && 库().提醒们.find((r) => r.id === r1.id).级别 === 2, 最后一句())
const 条数 = 甲.收到的.length
改提醒(r1.id, { 发出: [Date.now() - 6 * 60000, Date.now() - 4 * 60000, Date.now() - 2 * 60000] })
await 查提醒()
报('催满了 → 不再发，状态「没回应」', 甲.收到的.length === 条数 && 库().提醒们.find((r) => r.id === r1.id).状态 === '没回应')

// 7. 发出去她回了「等会」→ 算回了；模型说推迟 → 重新排
st = await 调('/dsh-speak-up/reminders', { 动作: '新增', 时间: 写时间(Date.now()), 内容: '交物理作业' })
const r2 = 库().提醒们.find((r) => r.内容 === '交物理作业')
报('设置页手动加一条', r2 && r2.状态 === '待发', st.说)
await 查提醒()
报('到点发出', 库().提醒们.find((r) => r.id === r2.id).状态 === '已发')
回答们.push((req) => {
  const 文 = req.messages[0].content[0].text
  return 文.includes(r2.id) && 文.includes('这一轮在回它') ? `{"动作":"推迟","id":"${r2.id}","分钟":10}` : '无'
})
const 调用前 = 模型调用.length
await 一轮('等会，我在吃饭', '好，那你先吃')
const r2后 = 库().提醒们.find((r) => r.id === r2.id)
报('她回「等会」→ 认得出是在回提醒，推迟 10 分钟（不带「提醒」两个字也认）',
  模型调用.length === 调用前 + 1 && r2后.状态 === '待发' && Math.abs(r2后.时间 - (Date.now() + 10 * 60000)) < 5000, JSON.stringify(r2后))

// 8. 她回了别的（没推迟）→ 停止跟进
改提醒(r2.id, { 时间: Date.now() - 1000 })
await 查提醒()
回答们.push('{"动作":"完成","id":"' + r2.id + '"}')
await 一轮('交啦交啦', '真棒！')
报('她说做完了 → 完成，不再催', 库().提醒们.find((r) => r.id === r2.id).状态 === '完成')

// 9. 每天的提醒：发完排下一次
await 调('/dsh-speak-up/reminders', { 动作: '新增', 时间: 写时间(Date.now()), 内容: '吃维生素', 重复: '每天' })
await 查提醒()
const 维 = 库().提醒们.filter((r) => r.内容 === '吃维生素')
报('每天的提醒：这次发了，明天的已经排上', 维.length === 2 && 维.some((r) => r.状态 === '已发') &&
  维.some((r) => r.状态 === '待发' && r.时间 > Date.now() + 23 * 3600000), JSON.stringify(维.map((r) => [r.状态, r.时间])))

// 10. 取消
const 下一条 = 维.find((r) => r.状态 === '待发')
回答们.push(`{"动作":"取消","id":"${下一条.id}"}`)
await 一轮('维生素以后不用提醒我了', '好的')
报('「以后不用提醒了」→ 取消', 库().提醒们.find((r) => r.id === 下一条.id).状态 === '已取消')

// 11. 我答应的也认
回答们.push(`{"动作":"新增","时间":"${写时间(Date.now() + 3600000)}","内容":"出门带伞","重复":""}`)
await 一轮('一会儿可能下雨', '那一小时后我提醒你出门带伞')
报('我自己答应「一小时后提醒你」→ 也记下', 库().提醒们.some((r) => r.内容 === '出门带伞' && r.状态 === '待发'))

// 12. 主动搭话带话头
mkdirSync(join(家, 'fish-memory'), { recursive: true })
writeFileSync(join(家, 'fish-memory', SID.replace(/[^a-zA-Z0-9_-]/g, '_') + '.json'), JSON.stringify({
  memories: [{ id: 'm1', text: '她下周三要去面试产品经理', importance: 1, createdAt: new Date(Date.now() - 2 * 86400000).toISOString() }],
}), 'utf8')
await 一轮('', '在忙吗？', true) // 我们起头、她没回
await 调('/dsh-speak-up/roll', { 模式: '真发' })
const 搭话 = 最后一句()
报('主动搭话：念头里有记忆话头', 搭话.includes('手边可以聊的事') && 搭话.includes('下周三要去面试产品经理'), 搭话)
报('主动搭话：快到点的提醒也在（出门带伞）', 搭话.includes('出门带伞'))
报('主动搭话：上次没回 → 提醒换个话题', 搭话.includes('没回'))
报('主动搭话：要求有具体内容、别说空话', 搭话.includes('别只说「在吗」'))
await 调('/dsh-speak-up/roll', { 模式: '真发' })
报('同一条记忆话头不会接连用两次', !最后一句().includes('下周三要去面试产品经理'))
await 调('/dsh-speak-up/save', { 搭话要有内容: false })
await 调('/dsh-speak-up/roll', { 模式: '真发' })
报('「搭话要有内容」关掉 → 回到原来那句念头', !最后一句().includes('手边可以聊的事') && !最后一句().includes('别只说'))

// 13. 开关
await 调('/dsh-speak-up/save', { 桌面通知: false })
const 测 = await 调('/dsh-speak-up/test-notify', {})
报('「桌面通知」关掉 → 不发通知', 测.说.includes('关着'))
await 调('/dsh-speak-up/save', { 桌面通知: true })
const 测2 = await 调('/dsh-speak-up/test-notify', { 提醒: true })
报('测试通知能发', 测2.说.includes('发了'))

await 调('/dsh-speak-up/save', { 提醒开关: false })
const 前 = 模型调用.length
await 一轮('十分钟后提醒我关火', '好')
报('「提醒开关」关掉 → 不认提醒', 模型调用.length === 前)
await 调('/dsh-speak-up/reminders', { 动作: '新增', 时间: 写时间(Date.now()), 内容: '关火' })
const 发前 = 甲.收到的.length
await 查提醒()
报('「提醒开关」关掉 → 到点也不发', 甲.收到的.length === 发前)

// 14. 状态里能看到提醒
st = await 调('/dsh-speak-up/state', {})
报('设置页状态里带提醒列表', Array.isArray(st.提醒们) && st.提醒们.length >= 5)
报('默认值：提醒开、跟进间隔、最多 2 次、通知开、搭话要有内容开', (() => {
  const 默认 = JSON.parse(JSON.stringify(st.配置))
  return typeof 默认.提醒跟进分钟 === 'number' && 默认.提醒最多跟进 === 2
})())

for (const c of 清理) { try { c() } catch (e) {} }
console.log(`\n${挂 === 0 ? '全部通过 ✅' : `有 ${挂} 条没过 ❌`}`)
try { rmSync(家, { recursive: true, force: true }) } catch (e) {}
process.exitCode = 挂 === 0 ? 0 : 1
