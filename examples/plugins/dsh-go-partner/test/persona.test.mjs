import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { test } from 'node:test'

import { tempHome, seedAll, defaultPersona, writeJson } from './fixtures.mjs'
import {
  buildSelfIdentityBlock,
  identityCandidates,
  loadPersona,
  personaCandidates,
  personaPromptText,
  readNames,
  roleState,
} from '../lib/persona.mjs'
import { loadConfig } from '../lib/config.mjs'
import { loadLongTermFull, memoryStats } from '../lib/memory.mjs'

test('身份块：没有任何真实字段时返回空串，绝不现编', () => {
  assert.equal(buildSelfIdentityBlock({}), '')
  assert.equal(buildSelfIdentityBlock({ selfName: '  ', ownerName: '' }), '')
})

test('身份块：只有名字时也只说名字，不编性格', () => {
  const b = buildSelfIdentityBlock({ selfName: '大肥鱼' })
  assert.match(b, /^## 自我身份/m)
  assert.match(b, /大肥鱼/)
  assert.ok(!/贪吃|嘴硬|爱面子/.test(b), '身份块不许写性格：那是人格卡正文的活')
})

test('身份块：名字 + 对方 + 别名都写进去，且标明「身份只有一份」', () => {
  const b = buildSelfIdentityBlock({ selfName: '大肥鱼', ownerName: '鱼片', aliases: ['肥鱼', '小鱼'] })
  assert.match(b, /大肥鱼/)
  assert.match(b, /鱼片/)
  assert.match(b, /肥鱼/)
  assert.match(b, /身份只有这一份/)
  assert.match(b, /别处出现另一个/, '必须写成「以这里为准」，否则两份说法打架')
})

test('人格：从 surfcore 当前角色读到 persona.md，并把身份块作为一节接上', () => {
  const home = tempHome()
  seedAll(home)
  const p = loadPersona(loadConfig())
  assert.equal(p.ok, true)
  assert.equal(p.from, 'surfcore')
  assert.match(p.text, /大肥鱼/)
  assert.match(p.body, /## 自我身份/, '身份块必须是正文里的一节')
  assert.ok(p.body.indexOf('## 自我身份') > 0, '身份块在正文之后，不是并排另起')
  assert.equal(p.names.selfName, '大肥鱼')
  assert.equal(p.names.ownerName, '鱼片', '对方名字来自 feiyu-mood config（真实字段）')
})

test('人格：明写 personaFile 时优先用它，不再去凑 surfcore', () => {
  const home = tempHome()
  seedAll(home)
  const mine = path.join(home, 'my.md')
  fs.writeFileSync(mine, '你是「自定义角色」。说话要简短。', 'utf8')
  const cfg = loadConfig()
  cfg.persona.personaFile = mine
  const p = loadPersona(cfg)
  assert.equal(p.ok, true)
  assert.equal(p.from, 'config')
  assert.equal(p.file, mine)
})

test('人格：明写但文件不存在时，退回下一个候选并说明（不是静默）', () => {
  const home = tempHome()
  seedAll(home)
  const cfg = loadConfig()
  cfg.persona.personaFile = path.join(home, 'nope.md')
  const p = loadPersona(cfg)
  assert.equal(p.ok, true)
  assert.equal(p.from, 'surfcore')
  assert.ok(p.tried.length >= 2, '试过哪些位置要能报出来')
})

test('人格：一份都没有时 ok=false + no-persona，并且不编一个人设顶上', () => {
  const home = tempHome()
  const p = loadPersona(loadConfig())
  assert.equal(p.ok, false)
  assert.equal(p.reason, 'no-persona')
  assert.equal(p.identity, '', '没名字就不编身份块')
  assert.equal(personaPromptText(p), '', '什么都不给，比给一个假人设好')
  assert.ok(p.tried.length >= 1, '至少要说清自己找过哪儿')
})

test('人格：空文件单独报 empty-persona（和数据坏了分开）', () => {
  const home = tempHome()
  fs.mkdirSync(path.join(home, 'partner'), { recursive: true })
  fs.writeFileSync(path.join(home, 'partner', 'persona.md'), '   \n\n  ', 'utf8')
  const p = loadPersona(loadConfig())
  assert.equal(p.ok, false)
  assert.equal(p.reason, 'empty-persona')
  assert.equal(p.from, 'partner')
})

test('人格：正文里已经有「## 自我身份」时不重复加一节', () => {
  const home = tempHome()
  fs.mkdirSync(path.join(home, 'partner'), { recursive: true })
  writeJson(path.join(home, 'feiyu-mood', 'config.json'), { selfName: '大肥鱼', ownerName: '鱼片' })
  fs.writeFileSync(
    path.join(home, 'partner', 'persona.md'),
    '你是「大肥鱼」。\n\n## 自我身份\n\n- 旧的说法\n\n## 聊天节奏\n\n一到三句。\n',
    'utf8',
  )
  const p = loadPersona(loadConfig())
  assert.equal(p.body.match(/## 自我身份/g).length, 1, '只许有一节')
  assert.match(p.body, /大肥鱼/, '新的身份块要替换掉旧的那一节')
  assert.match(p.body, /聊天节奏/, '别的节不许被吃掉')
})

test('人格：表达风格与话题偏好来自配置，不是模型猜的', () => {
  const home = tempHome()
  seedAll(home)
  const cfg = loadConfig()
  cfg.persona.style = '短句为主，不加表情。'
  cfg.persona.topics = ['今天吃了什么', '最近在追的番']
  const p = loadPersona(cfg)
  const t = personaPromptText(p)
  assert.match(t, /## 表达风格/)
  assert.match(t, /短句为主/)
  assert.match(t, /## 主动开口时偏爱的话题/)
  assert.match(t, /今天吃了什么/)
})

test('身份只有一份真相：候选链里 surfcore 的 character.json 就是那张卡', () => {
  const home = tempHome()
  seedAll(home)
  const cands = identityCandidates(loadConfig())
  const froms = cands.map((c) => c.from)
  assert.ok(froms.includes('surfcore'))
  assert.ok(!froms.includes('dsh-go-identity'), '不许有第二个身份来源插件')
})

test('人格候选：partner 自己的 persona.md 优先于 surfcore', () => {
  const home = tempHome()
  seedAll(home)
  fs.mkdirSync(path.join(home, 'partner'), { recursive: true })
  fs.writeFileSync(path.join(home, 'partner', 'persona.md'), '你是「本地角色」。', 'utf8')
  const cands = personaCandidates(loadConfig())
  assert.equal(cands[0].from, 'partner')
  const p = loadPersona(loadConfig())
  assert.equal(p.from, 'partner')
  assert.equal(p.names.selfName, '大肥鱼', '名字仍然从真实配置里读，不因为换了卡就丢')
})

test('角色状态：认识多久 / 多少条 全部从记忆派生', () => {
  const home = tempHome()
  seedAll(home)
  const lt = loadLongTermFull(loadConfig(), 's1')
  const st = roleState(memoryStats(lt), { value: 66, label: '开心', tone: 'good' })
  assert.equal(st.memoryCount, lt.all.length)
  assert.ok(st.firstMetAt > 0)
  assert.ok(st.daysTogether >= 19, '最早一条是 20 天前（取整后 19）')
  assert.equal(st.mood.value, 66)
})

test('名字读取：不存在的文件不影响其它来源', () => {
  const home = tempHome()
  // 只有 qq-bridge 配置
  writeJson(path.join(home, 'qq-bridge-config.json'), { 称呼: '鱼片' })
  const n = readNames()
  assert.equal(n.ownerName, '鱼片')
  assert.equal(n.selfName, '', '没有就别编')
})
