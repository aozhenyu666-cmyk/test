import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  MAX_APPS, ROLES, allowDuringLock, appsSummary, blockDuringLock, cleanApps, declaredFun,
  emergencyPackages, isEmergency, remark, roleOf,
} from '../lib/apps.js'
import { FUN_PACKAGES, cleanPhone } from '../lib/phone.js'

test('名单洗一遍：坏条目丢掉，重复的只留第一条', () => {
  const list = cleanApps([
    { pkg: 'com.a.b', role: 'fun' },
    { pkg: 'com.a.b', role: 'work' },
    { pkg: '坏包名', role: 'fun' },
    { pkg: 'com.c.d', role: '玩' },
    null,
    'x',
  ])
  assert.equal(list.length, 1)
  assert.deepEqual(list[0], { pkg: 'com.a.b', role: 'fun' })
})

test('名单也收 {包名: 角色} 的写法', () => {
  const list = cleanApps({ 'com.x.y': 'fun', 'com.z.w': 'emergency', 'com.q.q': 'nope' })
  assert.deepEqual(list.map((a) => a.pkg).sort(), ['com.x.y', 'com.z.w'])
})

test('名单有条数上限', () => {
  const many = Array.from({ length: MAX_APPS + 20 }, (_, i) => ({ pkg: 'com.t' + i, role: 'tool' }))
  assert.equal(cleanApps(many).length, MAX_APPS)
})

test('认不出来就是空串，不默默当成娱乐', () => {
  assert.equal(roleOf([], 'com.unknown.app'), '')
  assert.equal(roleOf([], ''), '')
  assert.equal(roleOf(null, 'com.unknown.app'), '')
})

test('内置的娱乐类照样认得', () => {
  assert.equal(roleOf([], 'tv.danmaku.bili'), 'fun')
  assert.ok(FUN_PACKAGES.includes('tv.danmaku.bili'))
})

test('他声明的压过内置：把内置娱乐 App 声明成工作，就不再算娱乐', () => {
  const apps = cleanApps([{ pkg: 'tv.danmaku.bili', role: 'work' }])
  assert.equal(roleOf(apps, 'tv.danmaku.bili'), 'work')
})

test('他声明的也压过手机自己标的 fun 标记（这才是「更灵活」）', () => {
  const phone = cleanPhone({ app: 'com.my.own.player', fun: true, minutes: 12 })
  assert.equal(phone.fun, true, '没声明时听手机的')
  const apps = cleanApps([{ pkg: 'com.my.own.player', role: 'work' }])
  assert.equal(remark(phone, apps).fun, false, '声明成工作就该不算娱乐')
})

test('没声明时重标等于不动（不声明这条路径就不存在）', () => {
  const phone = cleanPhone({ app: 'com.my.own.player', fun: true, minutes: 12 })
  assert.deepEqual(remark(phone, []), phone)
  assert.deepEqual(remark(phone, null), phone)
  assert.equal(remark(null, [{ pkg: 'a.b', role: 'fun' }]), null)
})

test('重标只动声明过的包，别的照旧', () => {
  const phone = cleanPhone({
    app: 'com.my.own.player',
    today: [
      { app: 'com.my.own.player', minutes: 30 },
      { app: 'tv.danmaku.bili', minutes: 20 },
    ],
  })
  const apps = cleanApps([{ pkg: 'com.my.own.player', role: 'fun' }])
  const out = remark(phone, apps)
  assert.equal(out.today[0].fun, true, '声明成娱乐的要算进来')
  assert.equal(out.today[1].fun, true, '没声明的保持内置判法')
})

test('紧急应用：内置那几类永远不拦', () => {
  assert.equal(isEmergency([], 'com.android.dialer'), true)
  assert.equal(isEmergency([], 'com.google.android.apps.maps'), true)
  assert.equal(isEmergency([], 'com.eg.android.AlipayGphone'), true)
  assert.equal(isEmergency([], 'tv.danmaku.bili'), false)
})

test('他能自己指定紧急应用', () => {
  const apps = cleanApps([{ pkg: 'com.my.insulin', role: 'emergency' }])
  assert.equal(isEmergency(apps, 'com.my.insulin'), true)
  assert.ok(emergencyPackages(apps).includes('com.my.insulin'))
  assert.ok(emergencyPackages(apps).includes('com.android.dialer'))
})

test('锁着的时候要拦的包：内置娱乐 + 他声明的娱乐，减掉放行和紧急', () => {
  const apps = cleanApps([
    { pkg: 'com.my.own.player', role: 'fun' },
    { pkg: 'com.my.work.editor', role: 'work' },
  ])
  const list = blockDuringLock(apps, { funLocked: true, allow: [] })
  assert.ok(list.includes('tv.danmaku.bili'))
  assert.ok(list.includes('com.my.own.player'), '他声明的娱乐也要拦')
  assert.equal(list.includes('com.my.work.editor'), false)
  assert.equal(list.includes('com.android.dialer'), false, '紧急应用不许拦')
  assert.deepEqual(list, [...list].sort(), '顺序要稳，便于比对')
})

test('放行名单里的包不拦（共脑时放行 Gemini 就是这条）', () => {
  const list = blockDuringLock([], { funLocked: true, allow: ['com.example.gemini'] })
  assert.equal(list.includes('com.example.gemini'), false, '放行的包不该出现在要拦的名单里')
  const app = cleanApps([{ pkg: 'tv.danmaku.bili', role: 'fun' }])
  const list2 = blockDuringLock(app, { funLocked: true, allow: ['tv.danmaku.bili'] })
  assert.equal(list2.includes('tv.danmaku.bili'), false)
})

test('没锁就一个都不拦', () => {
  assert.deepEqual(blockDuringLock([], { funLocked: false, allow: [] }), [])
  assert.deepEqual(blockDuringLock([], {}), [])
  assert.deepEqual(blockDuringLock([], null), [])
})

test('锁着时还放行的，至少要有紧急应用', () => {
  const allow = allowDuringLock([], { funLocked: true, allow: [] })
  assert.ok(allow.includes('com.android.dialer'))
  const withApp = allowDuringLock(cleanApps([{ pkg: 'com.my.insulin', role: 'emergency' }]), { funLocked: true })
  assert.ok(withApp.includes('com.my.insulin'))
})

test('声明成娱乐的才算 declaredFun，别的角色不算', () => {
  const apps = cleanApps([
    { pkg: 'com.a.a', role: 'fun' },
    { pkg: 'com.b.b', role: 'work' },
    { pkg: 'com.c.c', role: 'emergency' },
  ])
  assert.deepEqual(declaredFun(apps), ['com.a.a'])
})

test('说给他听的那一行：没声明时说没声明', () => {
  assert.ok(appsSummary([]).includes('还没声明'))
  const line = appsSummary([{ pkg: 'com.a.a', role: 'fun', label: '某播放器' }])
  assert.ok(line.includes('某播放器'), line)
  assert.ok(line.includes('娱乐'), line)
})

test('角色只有那四种', () => {
  assert.deepEqual([...ROLES], ['fun', 'work', 'tool', 'emergency'])
})
