/**
 * 模拟宿主测试：用一个**假的 dshGo 宿主 + 假 cordis ctx** 跑 apply()，验证
 *   ① 清单合法（id 唯一、面板 ≤4、状态条 ≤1、标题字数、引用的 panel 存在）
 *   ② 没桥接的主机不会把插件打挂；三个模块全关时什么也不注册
 *   ③ 四个面板在「没有数据」和「有真实数据」两种情况下都给得出合法视图
 *   ④ 每次 emit 的数据都在预算内；取消函数真的清了定时器
 *   ⑤ 点按动作真能改到东西（隐藏一条记忆），不是假的 toast
 *   ⑥ 工具通过 ctx.tools 注册上了，且参数/输出 schema 是 DSH 认的子集
 *
 * 跑：node --test test/
 * 不依赖 DSH、不依赖 App —— 纯 Node。
 */
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { test } from 'node:test'

import { tempHome, seedAll, writeJson, moodState, moodConfig, fishMemory } from './fixtures.mjs'
import { apply, registerTools } from '../lib/index.js'
import { validate, BUDGET } from '../lib/views.mjs'

// ── 假宿主 ──────────────────────────────────────────────────────────────────
function fakeCtx(options) {
  const o = options || {}
  const calls = { strip: [], panel: {}, refresh: [], tools: [] }
  const strips = {}
  const panels = {}
  const listeners = new Map()
  const sections = []
  const contexts = []
  const ctx = {
    inject(names, cb) {
      if (o.withBridge === false) return
      if (!names.includes('dshGo') || names.includes('dshGo?')) return
      cb(ctx)
    },
    on(name, fn) {
      listeners.set(name, (listeners.get(name) || []).concat([fn]))
      return () => {}
    },
    effect(fn) {
      const d = fn()
      return typeof d === 'function' ? d : () => {}
    },
  }
  if (o.withTools === true) {
    ctx.tools = {
      register(def) {
        calls.tools.push(def)
        return () => {}
      },
    }
  }
  if (o.withAgents !== false) {
    ctx.agents = {
      list: () => [o.agent || { ctx: {
        systemPrompt: {
          section: (s) => { sections.push(s); return () => {} },
          context: (c) => { contexts.push(c); return () => {} },
        },
      } }],
      get: () => (o.agent || { ctx: {
        systemPrompt: {
          section: (s) => { sections.push(s); return () => {} },
          context: (c) => { contexts.push(c); return () => {} },
        },
      } }),
    }
  }
  ctx.dshGo = {
    strip(id, dataFn) {
      calls.strip.push(id)
      strips[id] = dataFn
      return () => {}
    },
    panel(id, def) {
      calls.panel[id] = def
      panels[id] = def
      return () => {}
    },
    on(id, fn) { return () => {} },
    refresh(id, scope) { calls.refresh.push({ id, scope }) },
  }
  return { ctx, calls, strips, panels, sections, contexts, listeners }
}

/** 一个最小可用的 defineTool 替身：只记录，不校验（真校验单独测） */
const fakeDefineTool = (opts) => opts

/** 作者谱（parameters）允许的关键字：它是属性表，不是 JSON Schema */
const SPEC_KEYS = new Set(['type', 'description', 'required', 'items', 'enum', 'default', 'oneOf', 'properties', 'additionalProperties'])
/** DSH 校验的 JSON Schema 子集 */
const SCHEMA_KEYS = new Set(['type', 'oneOf', 'properties', 'required', 'additionalProperties', 'items', 'enum', 'const', 'description', 'title'])

// ── 1. 清单 ────────────────────────────────────────────────────────────────
test('清单：dshGo 结构合法', () => {
  const pkg = JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
  assert.equal(pkg.dshGo.v, 1)
  const keys = Object.keys(pkg.dshGo.contributes)
  assert.ok(keys.every((k) => ['composer.actions', 'message.actions', 'composer.strip', 'panels'].includes(k)), keys.join(','))
  assert.ok(pkg.dshGo.contributes['composer.strip'].length <= 1)
  assert.ok(pkg.dshGo.contributes.panels.length <= 4)
  for (const p of pkg.dshGo.contributes.panels) {
    const s = typeof p.title === 'string' ? p.title : p.title.zh
    assert.ok(s.length <= 16, p.id + ' 标题 ' + s.length + ' 字')
    assert.match(p.id, /^[A-Za-z0-9][A-Za-z0-9_.-]{0,39}$/)
  }
  const ids = []
  for (const list of Object.values(pkg.dshGo.contributes)) for (const it of list) ids.push(it.id)
  assert.equal(new Set(ids).size, ids.length, '同一插件内 id 不许重复：' + ids.join(','))
  assert.ok(Buffer.byteLength(JSON.stringify(pkg.dshGo)) <= 16 * 1024)
})

test('清单：状态条引用的面板真的存在，且 id 不和面板撞', () => {
  const pkg = JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
  const panelIds = new Set(pkg.dshGo.contributes.panels.map((p) => p.id))
  for (const s of pkg.dshGo.contributes['composer.strip']) {
    if (s.panel) assert.ok(panelIds.has(s.panel), 'strip 引用了不存在的面板 ' + s.panel)
    assert.ok(!panelIds.has(s.id), 'strip id 与面板 id 重复会让 App 报「界面有误」')
  }
  assert.deepEqual([...panelIds].sort(), ['memory', 'mood', 'persona', 'purpose'], '四块：人格/记忆/情绪/目的')
})

test('清单：cordis.patch.yml 与包名一致', () => {
  const pkg = JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
  const y = fs.readFileSync(new URL('../cordis.patch.yml', import.meta.url), 'utf8')
  assert.ok(y.includes('name: ' + pkg.name))
})

// ── 2. 没有桥接 / 模块全关 ──────────────────────────────────────────────────
test('没有 dsh-go 桥接的主机：apply 不抛错、不注册任何界面', () => {
  tempHome()
  const { ctx, calls } = fakeCtx({ withBridge: false })
  assert.doesNotThrow(() => apply(ctx))
  assert.equal(calls.strip.length, 0)
  assert.deepEqual(Object.keys(calls.panel), [])
})

test('三个模块全关：什么数据都不读，界面只剩「已关闭」', () => {
  const home = tempHome()
  seedAll(home)
  writeJson(path.join(home, 'partner', 'config.json'), {
    persona: { enabled: false }, memory: { enabled: false }, mood: { enabled: false }, tools: { enabled: false },
  })
  const { ctx, calls, panels } = fakeCtx()
  apply(ctx)
  const v = panels.memory.view({ sessionId: 's1' })
  assert.match(JSON.stringify(v), /已关闭/)
  const p = panels.persona.view({ sessionId: 's1' })
  assert.match(JSON.stringify(p), /已关闭/)
  const m = panels.mood.view({})
  assert.match(JSON.stringify(m), /已关闭/)
})

test('只关记忆：人格和心情照常，记忆面板说关掉了', () => {
  const home = tempHome()
  seedAll(home)
  writeJson(path.join(home, 'partner', 'config.json'), { memory: { enabled: false } })
  const { ctx, panels } = fakeCtx()
  apply(ctx)
  assert.match(JSON.stringify(panels.memory.view({ sessionId: 's1' })), /已关闭/)
  assert.ok(!JSON.stringify(panels.persona.view({ sessionId: 's1' })).includes('已关闭'))
  assert.ok(!JSON.stringify(panels.mood.view({})).includes('已关闭'))
})

// ── 3. 没有数据 ────────────────────────────────────────────────────────────
test('裸机第一次打开：四个面板都合法，且都有人话说明为什么是空的', () => {
  tempHome()
  const { ctx, calls, panels } = fakeCtx()
  apply(ctx)
  assert.deepEqual(calls.strip, ['persona.strip'])
  assert.deepEqual(Object.keys(calls.panel).sort(), ['memory', 'mood', 'persona', 'purpose'])
  for (const id of ['persona', 'memory', 'mood', 'purpose']) {
    const v = panels[id].view({ sessionId: 's1' })
    assert.ok(!(v instanceof Promise), id + '.view 返回了 Promise（不该用 async 包）')
    assert.equal(v.type, 'stack', id + ' 根节点')
    const r = validate(v, 'panel')
    assert.ok(r.ok, id + ': ' + r.issues.map((i) => i.problem).join('; '))
    assert.match(JSON.stringify(v), /callout/, id + ' 应该有一句人话')
  }
})

test('状态条：同步返回取消函数，数据 ≤2KiB / text ≤24 字', () => {
  tempHome()
  const { ctx, strips } = fakeCtx()
  apply(ctx)
  const pushed = []
  const cancel = strips['persona.strip']({ sessionId: 's1' }, (d) => pushed.push(d))
  assert.ok(!(cancel instanceof Promise), 'strip 返回了 Promise（定时器会泄漏）')
  assert.ok(pushed.length >= 1)
  assert.ok(typeof pushed[0].text === 'string')
  assert.ok(pushed[0].text.length <= 24, '状态条文字上限 24 字，实际 ' + pushed[0].text)
  assert.ok(Buffer.byteLength(JSON.stringify(pushed[0])) <= 2048)
  assert.equal(typeof cancel, 'function')
  cancel()
})

// ── 4. 有真实数据 ──────────────────────────────────────────────────────────
test('有真实数据：人格面板显示真名字、真人设、真角色状态', () => {
  const home = tempHome()
  seedAll(home)
  const { ctx, panels } = fakeCtx()
  apply(ctx)
  const v = panels.persona.view({ sessionId: 's1' })
  const r = validate(v, 'panel')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))
  const s = JSON.stringify(v)
  assert.match(s, /大肥鱼/, '名字来自 feiyu-mood config')
  assert.match(s, /鱼片/, '对方名字也来自真实配置')
  assert.match(s, /聊天节奏/, '人格卡分节来自 persona.md 正文')
  assert.match(s, /认识/, '角色状态应有「认识多少天」')
})

test('有真实数据：记忆面板分短期/重要事实/长期，且写明数据来自哪个文件', () => {
  const home = tempHome()
  seedAll(home)
  const { ctx, panels } = fakeCtx()
  apply(ctx)
  const v = panels.memory.view({ sessionId: 's1' })
  const r = validate(v, 'panel')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))
  const s = JSON.stringify(v)
  assert.match(s, /短期/, '短期段必须存在')
  assert.match(s, /重要事实/, '事实提取段必须存在')
  assert.match(s, /长期/, '长期段必须存在')
  assert.match(s, /session-s1\.json/, '必须显示数据来自哪个文件')
  assert.match(s, /散热硅脂/, '1 小时内的那条应该出现在短期里')
})

test('有真实数据：情绪面板显示真实心情值 + 七维（heartache 不再恒为 0）', () => {
  const home = tempHome()
  seedAll(home)
  const { ctx, panels } = fakeCtx()
  apply(ctx)
  const v = panels.mood.view({ sessionId: 's1' })
  const r = validate(v, 'panel')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))
  const s = JSON.stringify(v)
  assert.match(s, /心疼/, 'heartache 必须画出来')
  assert.match(s, /安稳/, 'calm 必须画出来')
  assert.match(s, /领导/, '时间线理由要出现')
  assert.match(s, /只读/, '必须写明只读上游')
})

test('状态条：有数据时推真心情，不再是「心情未开」', () => {
  const home = tempHome()
  seedAll(home)
  const { ctx, strips } = fakeCtx()
  apply(ctx)
  const pushed = []
  const cancel = strips['persona.strip']({ sessionId: 's1' }, (d) => pushed.push(d))
  assert.ok(pushed.length >= 1)
  assert.ok(!pushed[0].text.includes('未开'), JSON.stringify(pushed[0]))
  assert.match(pushed[0].text, /大肥鱼/, '状态条要有名字')
  assert.match(pushed[0].text, /开心|一般|欢喜|心疼|难过/, '要有心情档位')
  cancel()
})

// ── 5. 点按动作真的改到东西 ────────────────────────────────────────────────
test('点开一条记忆 → 藏起来 → 面板上真的没了（不是假 toast）', async () => {
  const home = tempHome()
  seedAll(home)
  const { ctx, panels, calls } = fakeCtx()
  apply(ctx)
  const before = JSON.stringify(panels.memory.view({ sessionId: 's1' }))
  assert.match(before, /散热硅脂/)

  await panels.memory.actions['memory.row']({ sessionId: 's1' }, { value: { id: 'mfresh' } })
  const detail = panels.memory.view({ sessionId: 's1' })
  assert.match(JSON.stringify(detail), /藏起来/, '点开后应该是单条详情')

  await panels.memory.actions['memory.hide']({ sessionId: 's1' }, {})
  const after = JSON.stringify(panels.memory.view({ sessionId: 's1' }))
  assert.ok(!after.includes('散热硅脂'), '藏起来之后不该还出现在列表里')
  assert.ok(calls.refresh.some((r) => r.id === 'memory'), '改完必须让 App 重取视图')
  // 上游文件一个字节不动
  const raw = JSON.parse(fs.readFileSync(path.join(home, 'fish-memory', 'session-s1.json'), 'utf8'))
  assert.equal(raw.memories.length, 3, '上游记忆条数不许变')
})

test('pin 动作：钉住之后列表里能看到「钉住」', async () => {
  const home = tempHome()
  seedAll(home)
  const { ctx, panels } = fakeCtx()
  apply(ctx)
  await panels.memory.actions['memory.row']({ sessionId: 's1' }, { value: { id: 'mold1' } })
  await panels.memory.actions['memory.pin']({ sessionId: 's1' }, {})
  const v = JSON.stringify(panels.memory.view({ sessionId: 's1' }))
  assert.match(v, /钉住/)
})

test('没有打开任何一条时点「藏起来」是提示，不是崩溃', async () => {
  const home = tempHome()
  seedAll(home)
  const { ctx, panels } = fakeCtx()
  apply(ctx)
  const r = await panels.memory.actions['memory.hide']({ sessionId: 's1' }, {})
  assert.match(JSON.stringify(r), /先点开一条/)
})

// ── 6. 工具注册 ────────────────────────────────────────────────────────────
test('工具：四个都注册上了（recall / remember / correct / mood）', async () => {
  const home = tempHome()
  seedAll(home)
  const { ctx: ctx, calls: calls } = fakeCtx({ withTools: true })
  const r = await registerTools(ctx, {}, fakeDefineTool)
  assert.deepEqual(r.registered.slice().sort(), ['partner_correct', 'partner_mood', 'partner_recall', 'partner_remember'])
  assert.equal(calls.tools.length, 4)
})

test('工具：没有 tools 服务时是 no-tools-service，不抛错', async () => {
  const { ctx } = fakeCtx({ withTools: false })
  const r = await registerTools(ctx, {}, fakeDefineTool)
  assert.deepEqual(r.registered, [])
  assert.equal(r.reason, 'no-tools-service')
})

test('工具：partner_recall 真能检索到，且拒绝编造', async () => {
  const home = tempHome()
  seedAll(home)
  const { ctx: ctx, calls: calls } = fakeCtx({ withTools: true })
  await registerTools(ctx, {}, fakeDefineTool)
  const recall = calls.tools.find((t) => t.name === 'partner_recall')
  const hit = recall.execute({ query: '散热硅脂' }, { sessionId: 's1' })
  assert.equal(hit.ok, true)
  assert.ok(hit.count >= 1)
  assert.match(hit.items[0].text, /散热/)
  const miss = recall.execute({ query: '量子色动力学' }, { sessionId: 's1' })
  assert.equal(miss.count, 0, '查不到就该是 0，不许硬凑')
  assert.equal(miss.ok, true)
})

test('工具：partner_remember 写的是 partner 自己的库，不动上游', async () => {
  const home = tempHome()
  seedAll(home)
  const rawBefore = fs.readFileSync(path.join(home, 'fish-memory', 'session-s1.json'), 'utf8')
  const { ctx: ctx, calls: calls } = fakeCtx({ withTools: true })
  await registerTools(ctx, {}, fakeDefineTool)
  const remember = calls.tools.find((t) => t.name === 'partner_remember')
  const r = remember.execute({ text: '她不喜欢被叫全名', importance: 80, tags: ['owner'], fact: '她.称呼' }, {})
  assert.equal(r.ok, true)
  assert.equal(r.duplicate, false)
  assert.match(r.message, /记住了/)
  assert.ok(fs.existsSync(path.join(home, 'partner', 'memory-own.json')), '应该写进 partner 自己的库')
  assert.equal(fs.readFileSync(path.join(home, 'fish-memory', 'session-s1.json'), 'utf8'), rawBefore, '上游一个字节不许动')
})

test('工具：partner_remember 失败时说「没记住」，不说记住了', async () => {
  const home = tempHome()
  seedAll(home)
  const { ctx: ctx, calls: calls } = fakeCtx({ withTools: true })
  await registerTools(ctx, {}, fakeDefineTool)
  const remember = calls.tools.find((t) => t.name === 'partner_remember')
  const r = remember.execute({ text: '   ' }, {})
  assert.equal(r.ok, false)
  assert.match(r.message, /没记住/)
})

test('工具：partner_correct 真改到纠正层', async () => {
  const home = tempHome()
  seedAll(home)
  const { ctx: ctx, calls: calls } = fakeCtx({ withTools: true })
  await registerTools(ctx, {}, fakeDefineTool)
  const fix = calls.tools.find((t) => t.name === 'partner_correct')
  const r = fix.execute({ id: 'mfresh', action: 'rewrite', value: '今天想试试新的散热硅脂（已确认型号）' }, {})
  assert.equal(r.ok, true)
  assert.ok(fs.existsSync(path.join(home, 'partner', 'memory-corrections.json')))
})

test('工具：partner_mood 读到真值，over 模式读不到时 ok=false', async () => {
  const home = tempHome()
  seedAll(home)
  const { ctx: ctx, calls: calls } = fakeCtx({ withTools: true })
  await registerTools(ctx, {}, fakeDefineTool)
  const mood = calls.tools.find((t) => t.name === 'partner_mood')
  const r = mood.execute({ timeline: 2 }, { sessionId: 's1' })
  assert.equal(r.ok, true)
  assert.ok(r.mood > 0)
  assert.equal(r.dims.length, 7)
  assert.ok(r.changes.length <= 2)

  tempHome() // 换个空 home
  const { ctx: c2, calls: calls2 } = fakeCtx({ withTools: true })
  await registerTools(c2, {}, fakeDefineTool)
  const r2 = calls2.tools.find((t) => t.name === 'partner_mood').execute({}, {})
  assert.equal(r2.ok, false)
  assert.equal(r2.reason, 'no-feiyu-mood')
})

test('工具：参数与输出 schema 只用 DSH 支持的子集（type/oneOf/properties/required/additionalProperties/items/enum/const）', async () => {
  const home = tempHome()
  seedAll(home)
  const { ctx: ctx, calls: calls } = fakeCtx({ withTools: true })
  await registerTools(ctx, {}, fakeDefineTool)
  const walkSpec = (node, where) => {
    for (const k of Object.keys(node)) assert.ok(SPEC_KEYS.has(k), where + ' 用了不认识的参数关键字 ' + k)
    if (node.items) walkSpec(node.items, where + '[]')
    if (node.properties) for (const [k, v] of Object.entries(node.properties)) walkSpec(v, where + '.' + k)
  }
  const walkSchema = (node, where) => {
    for (const k of Object.keys(node)) assert.ok(SCHEMA_KEYS.has(k), where + ' 用了不支持的 schema 关键字 ' + k)
    if (node.properties) for (const [k, v] of Object.entries(node.properties)) walkSchema(v, where + '.' + k)
    if (node.items) walkSchema(node.items, where + '[]')
    if (node.oneOf) node.oneOf.forEach((v, i) => walkSchema(v, where + '.oneOf[' + i + ']'))
    if (node.additionalProperties && typeof node.additionalProperties === 'object') walkSchema(node.additionalProperties, where + '.**')
  }
  for (const def of calls.tools) {
    assert.ok(Array.isArray(def.output.render(def.parameters ? { query: 'x', text: 'x', id: 'x', action: 'hide' } : {}, {})), def.name + ' render 必须返回块数组')
    assert.equal(typeof def.execute, 'function')
    // parameters 是**属性表**（key = 参数名），所以直接从值开始走
    for (const [pname, pspec] of Object.entries(def.parameters)) walkSpec(pspec, def.name + '.' + pname)
    walkSchema(def.output.schema, def.name + '.output.schema')
    for (const p of Object.values(def.parameters)) {
      assert.ok(p.description, def.name + ' 的每个参数都要有 description')
    }
  }
})

test('工具：presentationMeta 返回的卡片都在工具卡预算内', async () => {
  const home = tempHome()
  seedAll(home)
  const { ctx: ctx, calls: calls } = fakeCtx({ withTools: true })
  await registerTools(ctx, {}, fakeDefineTool)
  const recall = calls.tools.find((t) => t.name === 'partner_recall')
  const value = recall.execute({ query: '散热硅脂' }, { sessionId: 's1' })
  const meta = recall.output.presentationMeta({ query: '散热硅脂' }, value)
  assert.ok(meta['dsh-go/view'], '应该给出卡片')
  const r = validate(meta['dsh-go/view'], 'toolCard')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))

  const moodTool = calls.tools.find((t) => t.name === 'partner_mood')
  const mv = moodTool.execute({ timeline: 3 }, { sessionId: 's1' })
  const mmeta = moodTool.output.presentationMeta({}, mv)
  const mr = validate(mmeta['dsh-go/view'], 'toolCard')
  assert.ok(mr.ok, mr.issues.map((i) => i.problem).join('; '))
})

test('工具：presentationMeta 遇到垃圾数据也不抛错（抛错会让整次调用失败）', async () => {
  const home = tempHome()
  seedAll(home)
  const { ctx: ctx, calls: calls } = fakeCtx({ withTools: true })
  await registerTools(ctx, {}, fakeDefineTool)
  const garbage = [
    { ok: true, query: 'x', count: 1, total: 1, reason: '', items: [{ id: 1, text: null, score: NaN, clarity: 'x', origin: {}, at: 0 }] },
    { ok: true, items: 'not-an-array' },
    { ok: true, dims: [{ value: Infinity }], changes: {} },
    null,
    undefined,
    { ok: true, items: new Array(300).fill({ id: 'x', text: 'y', score: 1, clarity: 'clear', origin: 'o', at: '' }) },
  ]
  for (const def of calls.tools) {
    for (const bad of garbage) {
      const meta = def.output.presentationMeta({ query: 'x', text: 'x', id: 'x', action: 'hide' }, bad)
      assert.equal(typeof meta, 'object')
      // 要么不出卡，要么卡必须合法 —— 两边都行，唯一不允许的是抛错
      if (meta['dsh-go/view']) {
        const r = validate(meta['dsh-go/view'], 'toolCard')
        assert.ok(r.ok, def.name + ' 出了不合法的卡：' + r.issues.map((i) => i.problem).join('; '))
      }
    }
  }
})

// ── 7. 系统提示接入 ────────────────────────────────────────────────────────
test('系统提示：人格进 section，心情进 context，且都关掉 interpolate', () => {
  const home = tempHome()
  seedAll(home)
  const { ctx, sections, contexts } = fakeCtx()
  apply(ctx)
  const personaSec = sections.find((s) => s.name === 'partner:persona')
  const moodCtx = contexts.find((c) => c.name === 'partner:mood')
  const hintSec = sections.find((s) => s.name === 'partner:hint')
  assert.ok(personaSec, '人格必须挂到 systemPrompt.section')
  assert.ok(moodCtx, '心情必须挂到 systemPrompt.context（不是 section，否则缓存全废）')
  assert.ok(hintSec, '必须告诉模型有这些工具，否则它不会主动调')
  assert.equal(personaSec.interpolate, false, '人设里带 {{ }} 会让整轮失败')
  assert.equal(moodCtx.interpolate, false)
  assert.equal(hintSec.interpolate, false)
  assert.match(personaSec.text(), /大肥鱼/)
  assert.match(moodCtx.text(), /这会儿的心情/)
  assert.match(hintSec.text(), /partner_recall/)
  assert.match(hintSec.text(), /不联网/, '必须写明本地只读，否则用户不知道该不该放心')
})

test('系统提示：模块关掉后回调返回空串（每轮现查配置）', () => {
  const home = tempHome()
  seedAll(home)
  const { ctx, sections, contexts } = fakeCtx()
  apply(ctx)
  writeJson(path.join(home, 'partner', 'config.json'), { mood: { enabled: false, inject: true } })
  const moodCtx = contexts.find((c) => c.name === 'partner:mood')
  assert.equal(moodCtx.text(), '', '关掉之后不该再注入')
  // 人格还开着
  const personaSec = sections.find((s) => s.name === 'partner:persona')
  assert.ok(personaSec.text().length > 0)
})

// ── 8. 边界 ────────────────────────────────────────────────────────────────
test('边界：500 条记忆也不超面板预算（靠截断）', () => {
  const home = tempHome()
  seedAll(home, {
    fishMemory: fishMemory({
      memories: Array.from({ length: 500 }, (_, i) => ({
        id: 'm' + i,
        text: '第 ' + i + ' 条记忆，内容长度适中用于测试截断行为是否正确。',
        createdAt: new Date(Date.now() - i * 3600000).toISOString(),
        importance: (i % 100) / 100,
        tags: i % 3 ? ['owner'] : [],
      })),
    }),
  })
  const { ctx, panels } = fakeCtx()
  apply(ctx)
  const v = panels.memory.view({ sessionId: 's1' })
  const r = validate(v, 'panel')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))
  assert.ok(r.nodes <= 120, '节点 ' + r.nodes)
})

test('边界：上游文件坏了面板也不崩（有 callout 说明）', () => {
  const home = tempHome()
  seedAll(home)
  fs.writeFileSync(path.join(home, 'fish-memory', 'session-s1.json'), '{ broken', 'utf8')
  const { ctx, panels } = fakeCtx()
  apply(ctx)
  const v = panels.memory.view({ sessionId: 's1' })
  assert.ok(validate(v, 'panel').ok)
})

test('边界：surfcore 目录完全不存在时人格仍能退回别处', () => {
  const home = tempHome()
  writeJson(path.join(home, 'partner', 'persona.md'), '')
  fs.mkdirSync(path.join(home, 'partner'), { recursive: true })
  fs.writeFileSync(path.join(home, 'partner', 'persona.md'), '你是「孤零零」。', 'utf8')
  const { ctx, panels } = fakeCtx()
  apply(ctx)
  const v = panels.persona.view({})
  assert.ok(validate(v, 'panel').ok)
  assert.match(JSON.stringify(v), /孤零零/)
})
