// 1.6.0 自动提取 —— 纯函数 + 真插件接线（假宿主、假模型；DSH_HOME 指到临时目录，绝不碰真数据）
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const home = mkdtempSync(join(tmpdir(), 'fish-auto-extract-'))
process.env.DSH_HOME = home
process.env.DSH_HOST_MODULES = join(home, 'empty-host-modules')
process.env.APPDATA = join(home, 'empty-appdata')

const 提 = await import('../lib/自动提取.js')
const plugin = await import('../lib/index.js')

const 等 = (ms) => new Promise((r) => setTimeout(r, ms))
async function 直到(条件, 最多毫秒 = 2000) {
  const 起 = Date.now()
  while (Date.now() - 起 < 最多毫秒) {
    if (条件()) return true
    await 等(10)
  }
  return false
}

// ---------------- 纯函数 ----------------

test('规整配置：默认值与夹取', () => {
  assert.deepEqual(提.规整配置(null), { 开: true, 每几句: 3, 闲置分钟: 10, 每次最多: 4, 对话字数上限: 6000 })
  const c = 提.规整配置({ 开: false, 每几句: '0', 闲置分钟: -5, 每次最多: 99, 对话字数上限: 10 })
  assert.equal(c.开, false)
  assert.equal(c.每几句, 1)
  assert.equal(c.闲置分钟, 0)
  assert.equal(c.每次最多, 10)
  assert.equal(c.对话字数上限, 500)
})

test('解析提取：逐行 JSON / 数组 / 代码块 / 编号 / 无 / 坏行', () => {
  assert.deepEqual(提.解析提取('无'), [])
  assert.deepEqual(提.解析提取(''), [])
  const 行 = 提.解析提取([
    '```json',
    '1. {"text":"她下周三要去面试产品经理","importance":1,"tags":["面试","工作"],"事实键":"她.求职","取代":""}',
    '- {"text":"她不吃香菜","importance":0.3}',
    '这行不是 JSON',
    '{"text":"","importance":1}',
    '{"text":"她不吃香菜","importance":0.3}',
    '```',
  ].join('\n'))
  assert.equal(行.length, 2)
  assert.deepEqual(行[0], { text: '她下周三要去面试产品经理', importance: 1, tags: ['面试', '工作'], 事实键: '她.求职' })
  assert.equal(行[1].importance, 0.5) // 0.3 → 归到 0.5 档
  const 数组 = 提.解析提取('[{"text":"a"},{"text":"b"},{"text":"c"}]', 2)
  assert.deepEqual(数组.map((x) => x.text), ['a', 'b'])
})

const 工具 = {
  是机器话: (e) => ['runtime-context', 'speak-up'].includes(e && e.data && e.data.source && e.data.source.kind),
  净化: (t) => String(t || ''),
  取文本: (p) => (p && Array.isArray(p.content) ? p.content.map((b) => b.text || '').join(' ') : ''),
}
const 她说 = (text, time, kind = 'user') => ({ type: 'user/message', time, data: { id: 'u' + time, role: 'user', content: [{ type: 'text', text }], source: { kind } } })
const 我说 = (text, time) => ({ type: 'assistant/message', time, data: { message: { role: 'assistant', content: [{ type: 'reasoning', text: '想想' }, { type: 'text', text }] } } })

test('收集新对话：只要起点之后的；机器塞的、念头都不算她说的；只取正文', () => {
  const ev = [
    她说('很早以前的话', 100),
    她说('运行环境……', 210, 'runtime-context'),
    她说('我明天要考试了', 220),
    我说('加油！', 230),
    她说('好久没人跟我说话了', 240, 'speak-up'),
    我说('', 250),
  ]
  const r = 提.收集新对话(ev, 200, 工具)
  assert.equal(r.她说了几句, 1)
  assert.deepEqual(r.行们.map((x) => x.谁 + ':' + x.文本), ['她:我明天要考试了', '我:加油！'])
  assert.equal(r.最后时间, 230)
})

test('对话成文：太长只留最后面', () => {
  const 行们 = [{ 谁: '她', 文本: 'a'.repeat(400) }, { 谁: '我', 文本: 'b'.repeat(400) }, { 谁: '她', 文本: '最后一句' }]
  const 文 = 提.对话成文(行们, '小鱼', 500)
  assert.ok(文.endsWith('小鱼：最后一句'))
  assert.ok(!文.includes('aaaa'))
})

test('拼提示：带称呼、条数上限、已记住清单', () => {
  const { 系统, 用户 } = 提.拼提示({ 称呼: '小鱼', 我叫: '大肥鱼', 对话文: '小鱼：我怕黑', 已记住: ['她养了一只猫'], 最多: 3 })
  assert.ok(系统.includes('最多 3 行'))
  assert.ok(系统.includes('大肥鱼'))
  assert.ok(用户.includes('- 她养了一只猫'))
  assert.ok(用户.includes('小鱼：我怕黑'))
})

// ---------------- 真插件接线 ----------------

const SID = 'session-auto-1'
const 配置路径 = join(home, 'fish-memory-config.json')
const 写配置 = (改) => {
  let c = {}
  try { c = JSON.parse(readFileSync(配置路径, 'utf8')) } catch (e) {}
  writeFileSync(配置路径, JSON.stringify({ ...c, ...改 }), 'utf8')
}
写配置({ 自动提取: { 每几句: 2, 闲置分钟: 0 }, ownerNames: { [SID]: '小鱼' } })

function 造agent(sid) {
  const 监听 = new Map()
  const 事件们 = []
  return {
    id: sid,
    status: 'idle',
    options: { provider: 'fixture', model: 'fixture' },
    session: { id: sid, header: {}, snapshotEvents: () => 事件们.slice() },
    ctx: {
      on: (n, cb) => {
        if (!监听.has(n)) 监听.set(n, [])
        监听.get(n).push(cb)
        return () => { const l = 监听.get(n); const i = l.indexOf(cb); if (i >= 0) l.splice(i, 1) }
      },
      systemPrompt: { section: () => () => {}, context: () => () => {} },
      tools: { register: () => () => {} },
    },
    事件们,
    结束一轮() { for (const cb of [...(监听.get('agent/turn-stopping') || [])]) cb() },
  }
}

const agent = 造agent(SID)
const 提取调用 = []
const 全局工具 = []
let 回答们 = []
const 路由 = new Map()
const 清理 = []
const ctx = {
  agents: { list: () => [agent], get: (id) => (id === SID ? agent : undefined) },
  sessions: { list: () => [], get: () => undefined },
  get: () => undefined,
  on: () => () => {},
  interval: () => () => {},
  effect: (fn) => { const c = fn(); if (typeof c === 'function') 清理.push(c) },
  webServer: { register: ({ path, handler }) => { 路由.set(path, handler); return () => {} } },
  tools: { register: (t) => { 全局工具.push(t); return () => {} } },
  llm: {
    async *stream(req) {
      // 记完一条会顺手「扩检索词」—— 那也是一次模型调用，这里给空回答、不算进提取次数
      if (String(req.system || '').includes('记忆检索的助手')) { yield { type: 'text-delta', text: '' }; return }
      提取调用.push(req)
      const 答 = 回答们.shift()
      if (答 === undefined) throw new Error('测试没准备这次回答')
      if (答 && 答.错) { yield { type: 'finish', reason: { kind: 'error', failure: { message: 答.错 } } }; return }
      yield { type: 'text-delta', text: 答 }
    },
  },
}
plugin.apply(ctx)

const 库 = () => {
  const p = join(home, 'fish-memory', SID.replace(/[^a-zA-Z0-9_-]/g, '_') + '.json')
  return existsSync(p) ? JSON.parse(readFileSync(p, 'utf8')) : { memories: [] }
}
const 账 = () => JSON.parse(readFileSync(join(home, 'fish-memory', 'auto-extract.json'), 'utf8')).会话[SID]
const 调路由 = async (body) => {
  let out
  await 路由.get('/dsh-fish-memory/auto-extract')(
    { url: '/dsh-fish-memory/auto-extract', async *[Symbol.asyncIterator]() { yield JSON.stringify(body || {}) } },
    { writeHead() {}, end(v) { out = JSON.parse(v) } },
  )
  return out
}

let 时 = Date.now() + 1000
const 下一刻 = () => (时 += 10)

test('挂上时游标设成现在：老历史不会被自动提', () => {
  const a = 账()
  assert.ok(a && a.到 > 0 && a.到 <= Date.now())
})

test('不够「每几句」只攒着、不调模型；念头和机器话不算数', async () => {
  agent.事件们.push(她说('很早以前的话（挂上之前）', Date.now() - 60000))
  agent.事件们.push(她说('我下周三要去面试产品经理，好紧张', 下一刻()))
  agent.事件们.push(我说('别紧张，你准备得很充分', 下一刻()))
  agent.事件们.push(她说('好久没人跟我说话了', 下一刻(), 'speak-up'))
  agent.结束一轮()
  await 等(50)
  assert.equal(提取调用.length, 0)
})

test('攒够了就提一次：走 remember 同一条写入路，来源标「自动提取」，游标往前挪', async () => {
  回答们.push([
    '{"text":"她下周三要去面试产品经理","importance":1,"tags":["面试","产品经理"],"事实键":"她.求职"}',
    '{"text":"她不吃香菜","importance":0.5,"tags":["香菜","忌口"]}',
  ].join('\n'))
  agent.事件们.push(她说('对了我不吃香菜哦', 下一刻()))
  const 最后 = 下一刻()
  agent.事件们.push(我说('记住啦', 最后))
  agent.结束一轮()
  assert.ok(await 直到(() => 库().memories.length === 2), '应该写进两条')
  assert.equal(提取调用.length, 1)
  const req = 提取调用[0]
  const 发去的 = req.messages[0].content[0].text
  assert.ok(发去的.includes('小鱼：我下周三要去面试产品经理'))
  assert.ok(发去的.includes('小鱼：对了我不吃香菜哦'))
  assert.ok(!发去的.includes('很早以前的话'), '挂上之前的老历史不该送去')
  assert.ok(!发去的.includes('好久没人跟我说话了'), '主动搭话的念头不是她说的')
  const ms = 库().memories
  assert.ok(ms.every((m) => m.source === '自动提取'))
  assert.equal(ms.find((m) => m.text.includes('面试')).importance, 1)
  assert.equal(ms.find((m) => m.text.includes('面试')).事实键, '她.求职')
  await 直到(() => 账().到 === 最后)
  assert.equal(账().到, 最后)
  assert.equal(账().最近.at(-1).新记, 2)
})

test('没有新的话 → 不调模型', async () => {
  agent.结束一轮()
  await 等(50)
  assert.equal(提取调用.length, 1)
})

test('同一件事又说一遍 → 强化，不重复记', async () => {
  回答们.push('{"text":"她不吃香菜","importance":0.5}')
  agent.事件们.push(她说('香菜真的不行', 下一刻()))
  agent.事件们.push(她说('一点都不能放', 下一刻()))
  agent.结束一轮()
  assert.ok(await 直到(() => 提取调用.length === 2 && (账().最近 || []).length >= 2))
  const 香菜 = 库().memories.filter((m) => m.text === '她不吃香菜')
  assert.equal(香菜.length, 1)
  assert.equal(香菜[0].reinforceCount, 1)
  assert.equal(账().最近.at(-1).强化, 1)
})

test('模型报错 → 游标不挪，下次带上同样的话重试', async () => {
  const 之前 = 账().到
  回答们.push({ 错: '429 限流' })
  agent.事件们.push(她说('我妈妈下个月生日', 下一刻()))
  agent.事件们.push(她说('六月十二号', 下一刻()))
  agent.结束一轮()
  assert.ok(await 直到(() => 提取调用.length === 3 && 账().最近.at(-1).ok === false))
  assert.equal(账().到, 之前)
  回答们.push('{"text":"她妈妈的生日是六月十二号","importance":1,"事实键":"她妈妈.生日"}')
  agent.结束一轮()
  assert.ok(await 直到(() => 库().memories.some((m) => m.text.includes('六月十二号'))))
  assert.ok(提取调用[3].messages[0].content[0].text.includes('我妈妈下个月生日'))
})

test('模型说「无」→ 什么都不写，但游标照样往前挪', async () => {
  const 条数 = 库().memories.length
  回答们.push('无')
  agent.事件们.push(她说('哈哈哈', 下一刻()))
  const 最后 = 下一刻()
  agent.事件们.push(她说('好困', 最后))
  agent.结束一轮()
  assert.ok(await 直到(() => 账().到 === 最后))
  assert.equal(库().memories.length, 条数)
})

test('闲置计时器：不够数，但闲下来也会提', async () => {
  写配置({ 自动提取: { 每几句: 5, 闲置分钟: 0.002 } }) // ≈ 120 毫秒
  回答们.push('{"text":"她最近在学吉他","importance":0.5}')
  agent.事件们.push(她说('我最近在学吉他', 下一刻()))
  agent.结束一轮()
  await 等(30)
  const 次数 = 提取调用.length
  assert.ok(await 直到(() => 库().memories.some((m) => m.text.includes('吉他'))), '闲下来后应该提了')
  assert.equal(提取调用.length, 次数 + 1)
  assert.equal(账().最近.at(-1).原因, '闲置')
})

test('会话关了记忆 → 不提；自动提取总开关关了 → 不提', async () => {
  写配置({ 自动提取: { 每几句: 1, 闲置分钟: 0 }, sessions: { [SID]: false } })
  agent.事件们.push(她说('这句不该被提', 下一刻()))
  agent.结束一轮()
  await 等(50)
  const 次数 = 提取调用.length
  写配置({ sessions: { [SID]: true }, 自动提取: { 开: false, 每几句: 1, 闲置分钟: 0 } })
  agent.结束一轮()
  await 等(50)
  assert.equal(提取调用.length, 次数)
})

test('路由：看状态、改配置、手动提一次（关着也能手动）', async () => {
  let r = await 调路由({})
  assert.equal(r.ok, true)
  assert.equal(r.配置.开, false)
  assert.ok(r.会话们[SID])
  r = await 调路由({ 配置: { 开: true, 每几句: 4 } })
  assert.equal(r.配置.开, true)
  assert.equal(r.配置.每几句, 4)
  回答们.push('{"text":"这是手动提出来的","importance":0}')
  r = await 调路由({ session: 'auto-1', 立刻: true }) // 不带前缀也认
  assert.equal(r.结果.ok, true, JSON.stringify(r.结果))
  assert.ok(库().memories.some((m) => m.text === '这是手动提出来的'))
  r = await 调路由({ session: '不存在的会话', 立刻: true })
  assert.equal(r.结果.ok, false)
})

test('remember 工具照旧能用：对外只交 { ok, message }，来源仍是「她自己记的」', async () => {
  const 工具 = 全局工具.find((t) => t && t.name === 'remember')
  assert.ok(工具, 'remember 应该注册到宿主')
  const r = await 工具.execute({ text: '她说周末想去海边', importance: 0.5 }, { agent })
  assert.deepEqual(Object.keys(r).sort(), ['message', 'ok'])
  assert.equal(r.ok, true)
  const m = 库().memories.find((x) => x.text === '她说周末想去海边')
  assert.ok(m)
  assert.equal(m.source, '她自己记的')
})

test('卸载：计时器全部撤掉、不再提', async () => {
  写配置({ 自动提取: { 开: true, 每几句: 9, 闲置分钟: 0.002 } })
  agent.事件们.push(她说('卸载前最后一句', 下一刻()))
  agent.结束一轮()
  for (const c of 清理) { try { c() } catch (e) {} }
  const 次数 = 提取调用.length
  await 等(250)
  assert.equal(提取调用.length, 次数)
})
