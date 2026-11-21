// 体检：每一环一行（正常 / 注意 / 断了 + 为什么 + 去哪修）；给鱼的话只说有问题的。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { healthItems, healthSummary, healthText } from '../lib/health.js'

const NOW = 1_800_000_000_000
const GOOD = {
  now: NOW, lastPlanAt: NOW - 60_000, lockShadow: false, hardCapped: false, emergencyLeft: 2, vault: '/sdcard/库/大肥鱼', bankCount: 300,
  verifyEnabled: true, verifyActive: true, recovery: false, timelineToday: 2, timelinePassed: 1, pastApplyAt: true, sealLocking: '',
  covenantRules: 34, covenantPending: 0, eveningActive: false, balance: 40, bridgeConfigured: false, reviewDue: 0,
}

test('都好：没有「断了」和「注意」，给鱼的话一句「都正常」', () => {
  const items = healthItems(GOOD)
  assert.equal(items.filter((x) => x.level !== 'ok').length, 0)
  assert.match(healthText(items), /都正常/)
})

test('常见的坏法：手机没对表、测试模式开着、没笔记库、题库空、时间轴空、桥断了——每一项都带去哪修', () => {
  const items = healthItems({ ...GOOD, lastPlanAt: NOW - 25 * 60_000, lockShadow: true, vault: '', bankCount: 0, timelineToday: 0, bridgeConfigured: true, bridgeHealthy: false, bridgeError: '连不上（10 秒超时）', reviewDue: 3 })
  const by = Object.fromEntries(items.map((x) => [x.id, x]))
  assert.equal(by['phone-tick'].level, 'error')
  assert.match(by['phone-tick'].label, /25 分钟没跟引擎对表/)
  assert.equal(by['lock-shadow'].level, 'error')
  assert.equal(by['lock-shadow'].fix, 'lock-shadow')
  assert.equal(by.vault.level, 'error')
  assert.equal(by.bank.fix, 'bank')
  assert.equal(by.timeline.level, 'warn')
  assert.match(by.bridge.detail, /10 秒超时/)
  assert.equal(by.review.level, 'warn')
  const s = healthSummary(items)
  assert.equal(s.error, 4)
  assert.match(s.line, /^✗ 手机 25 分钟没跟引擎对表、测试模式开着、没连 Obsidian 笔记库、题库是空的；⚠ /)
  const t = healthText(items)
  assert.doesNotMatch(t, /✓ 公约/)
  assert.match(t, /✗ 测试模式开着：锁只提示、不真锁/)
})
