// 2026-11-18 裁决器唯一输出：分层、额度（游戏 2:1 换娱乐）、白名单 / 自由态。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { classifyTierChanges, isBasePkg, parseTierFile, renderTierFile, tierOf } from '../lib/app_tiers.js'
import { computeQuota } from '../lib/quota.js'
import { buildLockDecision, decideApp } from '../lib/lock_decision.js'

const tf = (p, l) => tierOf(p, { label: l })
const installed = [
  { pkg: 'com.ss.android.ugc.aweme', label: '抖音' }, { pkg: 'tv.danmaku.bili', label: '哔哩哔哩' },
  { pkg: 'com.tencent.tmgp.sgame', label: '王者荣耀' }, { pkg: 'com.android.chrome', label: 'Chrome' },
  { pkg: 'com.vivo.browser', label: '浏览器' }, { pkg: 'com.quark.browser', label: '夸克' },
  { pkg: 'com.hpbr.bosszhipin', label: 'BOSS直聘' }, { pkg: 'com.baidu.netdisk', label: '百度网盘' },
  { pkg: 'com.anthropic.claude', label: 'Claude' }, { pkg: 'com.tencent.mm', label: '微信' },
]

test('分层：系统页面是底座；AI/网盘/BOSS 是工作；三个浏览器和微信是双用途；抖音娱乐、王者游戏', () => {
  for (const p of ['com.bbk.launcher2', 'com.vivo.gallery', 'com.android.providers.media.module', 'com.android.documentsui', 'com.android.intentresolver', 'moe.nb4a']) {
    assert.equal(isBasePkg(p), true, p)
  }
  assert.equal(tf('com.baidu.netdisk'), 'work')
  assert.equal(tf('com.openai.chatgpt'), 'work')
  for (const p of ['com.android.chrome', 'com.vivo.browser', 'com.quark.browser', 'com.tencent.mm']) assert.equal(tf(p), 'dual', p)
  assert.equal(tf('com.ss.android.ugc.aweme', '抖音'), 'fun')
  assert.equal(tf('com.tencent.tmgp.sgame', '王者荣耀'), 'game')
  assert.equal(tierOf('com.example.sysapp', { system: true }), 'base', '系统自带又没被认成娱乐：宁可放过')
  assert.equal(tierOf('com.android.chrome', { overrides: { 'com.android.chrome': 'fun' } }), 'fun', '他改过的分层优先')
})

test('分层文件：读写往返；往更严挪是改严，往工作挪是改松', () => {
  const md = renderTierFile({ 'com.android.chrome': 'dual', 'tv.danmaku.bili': 'fun' }, { 'tv.danmaku.bili': '哔哩哔哩' })
  assert.deepEqual(parseTierFile(md), { 'com.android.chrome': 'dual', 'tv.danmaku.bili': 'fun' })
  const c = classifyTierChanges({ 'com.android.chrome': 'dual', 'tv.danmaku.bili': 'fun' }, { 'com.android.chrome': 'fun', 'tv.danmaku.bili': 'work' })
  assert.deepEqual(c.tighten, { 'com.android.chrome': 'fun' })
  assert.deepEqual(c.loosen, { 'tv.danmaku.bili': 'work' })
})

test('额度：娱乐 120 + 挣来的；双用途扣掉通行证时间也算娱乐', () => {
  const q = computeQuota({ today: [{ app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 80 }, { app: 'com.android.chrome', minutes: 40 }],
    tierOf: tf, earnedMin: 15, passMinutes: 15 })
  assert.equal(q.funUsed, 105)
  assert.equal(q.funLeft, 135 - 105 + 30, '游戏没用：60 分钟游戏额度能换 30 分钟娱乐')
  assert.equal(q.funOut, false)
})

test('额度：娱乐超了先用游戏额度 2:1 去换，换光才算用完', () => {
  const q = computeQuota({ today: [{ app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 140 }, { app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes: 20 }], tierOf: tf })
  // 游戏剩 40 → 能换 20；娱乐超了 20 → 全部换掉，游戏额度清零
  assert.equal(q.converted, 20)
  assert.equal(q.gameLeft, 0)
  assert.equal(q.funOut, true)
  assert.equal(q.gameOut, true)
  assert.match(q.line, /已有 40 分钟游戏额度换成娱乐/)
})

const arb = (priority, extra = {}) => ({ priority, source: 'X', label: 'x', why: 'w', allow: [], ...extra })
const base = (p) => isBasePkg(p)

test('干活态：白名单 = 工作层 + 声明的 App + 通行证；浏览器要先领通行证；桌面永远放行', () => {
  const d = buildLockDecision({ arbiter: arb('P6'), work: { active: true, why: '局进行中', allow: ['md.obsidian'] }, installed, tierOf: tf,
    lease: { app: 'com.quark.browser', until: Date.now() + 600_000, goal: '查国考公告' } })
  assert.equal(d.mode, 'allowlist')
  assert.equal(decideApp(d, 'com.baidu.netdisk', base).lock, false)
  assert.equal(decideApp(d, 'md.obsidian', base).lock, false)
  assert.equal(decideApp(d, 'com.quark.browser', base).lock, false, '有通行证')
  const chrome = decideApp(d, 'com.android.chrome', base)
  assert.equal(chrome.lock, true)
  assert.equal(chrome.pass, true, '提示去领通行证')
  assert.equal(decideApp(d, 'com.ss.android.ugc.aweme', base).lock, true)
  assert.equal(decideApp(d, 'com.bbk.launcher2', base).lock, false)
  assert.equal(decideApp(d, 'io.github.ccch1mneyyy.dsh_app.fish', base).lock, false)
})

test('自由态：额度内都放行；娱乐用完锁娱乐、双用途要通行证；游戏用完单独锁游戏', () => {
  const free = buildLockDecision({ arbiter: arb('P6'), installed, tierOf: tf, quota: { funOut: false, gameOut: false, line: 'q' } })
  assert.equal(free.mode, 'free')
  assert.equal(decideApp(free, 'com.ss.android.ugc.aweme', base).lock, false)
  const out = buildLockDecision({ arbiter: arb('P6'), installed, tierOf: tf, quota: { funOut: true, gameOut: false, line: 'q' } })
  assert.equal(decideApp(out, 'com.ss.android.ugc.aweme', base).lock, true)
  assert.equal(decideApp(out, 'com.tencent.tmgp.sgame', base).lock, false, '游戏额度另算')
  assert.equal(decideApp(out, 'com.vivo.browser', base).pass, true)
  const game = buildLockDecision({ arbiter: arb('P6'), installed, tierOf: tf, quota: { funOut: false, gameOut: true, line: 'q' } })
  assert.equal(decideApp(game, 'com.tencent.tmgp.sgame', base).lock, true)
  assert.equal(decideApp(game, 'com.ss.android.ugc.aweme', base).lock, false)
})

test('自由态的临时锁（雷达 / 切换次数 / 姐妹）进 deny；过期的不算', () => {
  const now = Date.now()
  const d = buildLockDecision({ arbiter: arb('P6'), installed, tierOf: tf, now,
    radarDeny: [{ app: 'tv.danmaku.bili', until: now + 60_000, why: '连刷 12 分钟' }, { app: 'com.ss.android.ugc.aweme', until: now - 1 }] })
  assert.equal(decideApp(d, 'tv.danmaku.bili', base).why, '连刷 12 分钟')
  assert.equal(decideApp(d, 'com.ss.android.ugc.aweme', base).lock, false)
})

test('紧急放行全开；连麦舱只许 Gemini + NekoBox；深夜只剩底座', () => {
  assert.equal(buildLockDecision({ arbiter: arb('P0') }).mode, 'open')
  const g = buildLockDecision({ arbiter: arb('P1'), installed, tierOf: tf })
  assert.equal(decideApp(g, 'com.google.android.apps.bard', base).lock, false)
  assert.equal(decideApp(g, 'com.anthropic.claude', base).lock, true)
  const night = buildLockDecision({ arbiter: arb('P4'), installed, tierOf: tf })
  assert.equal(decideApp(night, 'com.baidu.netdisk', base).lock, true)
  assert.equal(decideApp(night, 'com.android.dialer', base).lock, false)
})
