import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  CAPABILITIES, CLASSES, WALL_CAPABILITIES, capabilityNames, capabilityOf, hasCapability,
  isWallCapability, measure, missingCapabilityProposal,
} from '../lib/capabilities.js'

// 施工书 §3 J3 那张表，逐字抄下来。测试拿它和登记表对全集：
// 少一个（删掉了没接）和多一个（手滑加了个没人要的名字）都会红。
// 「计时」与「拍照」是别名：J2 示例协议里写的就是这两个名字，照文档抄不该失败。
const DOC_TABLE = {
  感知: ['局计时', '步骤计时', '无局计时', '前台应用', '本局内各应用时长', '切屏次数', '今日累计',
    '静止', '走路', '骑车', '亮屏', '充电',
    '计时'], // 别名 ≡ 局计时
  度量: ['字数', '话题数', '具体名词数', '有无结论句', '问句数', '应答延迟', '填充词比例', '与他近5条的重合率'],
  触发: ['语音问', '浮层', '盖屏', '通知', '震动', '锁屏问题', '二选一', '倒计时条', '留空填词', '挖词回想', '先答后看'],
  应答: ['语音答', '文字', '手写拍照', '文件', '外部提交', 'Gemini Live 复盘',
    '拍照'], // 别名 ≡ 手写拍照
  验收: ['度量判定', '照片时间', '识图提取', '文件增长', '答案比对', '原文比对', '记录比对', '模型判定'],
  变速: ['切手写', '缩短步骤', '单步限时加倍', '拉起 Gemini Live', '步骤间隔减半'],
}

const KIND_OF = { 感知: 'sense', 度量: 'metric', 触发: 'trigger', 应答: 'answer', 验收: 'verify', 变速: 'pace' }

// ── 类的形状 ────────────────────────────────────────────────

test('J3 类：六类，顺序固定', () => {
  assert.deepEqual(CLASSES, ['感知', '度量', '触发', '应答', '验收', '变速'])
})

for (const [cls, names] of Object.entries(DOC_TABLE)) {
  test(`J3 ${cls}类：登记表与施工书那张表完全一致（${names.length} 个）`, () => {
    const registered = capabilityNames().filter((n) => capabilityOf(n).class === cls).sort()
    assert.deepEqual(registered, names.slice().sort())
  })
}

test('J3 能力库：没有同一句话登记在两个类里', () => {
  const seen = new Map()
  for (const name of capabilityNames()) {
    assert.equal(seen.has(name), false, name + ' 重复登记')
    seen.set(name, capabilityOf(name).class)
  }
  assert.equal(CAPABILITIES.size, 52)
})

// ── 每个积木三条测试：在 / 属于哪类 / 执行形态 ─────────────────

for (const [cls, names] of Object.entries(DOC_TABLE)) {
  for (const name of names) {
    test(`J3 积木 ${cls}·${name}：在`, () => {
      assert.equal(hasCapability(name), true, name + ' 没登记')
    })
    test(`J3 积木 ${cls}·${name}：属于${cls}`, () => {
      assert.equal(capabilityOf(name).class, cls)
    })
    test(`J3 积木 ${cls}·${name}：执行形态 ${KIND_OF[cls]}`, () => {
      assert.equal(capabilityOf(name).kind, KIND_OF[cls])
    })
  }
}

test('J3 积木：语音问与语音答是默认通道，别的不是', () => {
  assert.equal(capabilityOf('语音问').default, true)
  assert.equal(capabilityOf('语音答').default, true)
  assert.equal(capabilityOf('浮层').default, false)
  assert.equal(capabilityOf('文字').default, false)
})

// ── 墙的能力：协议一律不许声明 ────────────────────────────────

test('J3 墙：锁 / 全机锁 / 应用锁 / 额度 / 信用分 / 规则 / 立场结算 / 扣分 / 减额度 都算墙', () => {
  for (const name of ['锁', '全机锁', '应用锁', '额度', '信用分', '规则', '立场结算', '扣分', '减额度']) {
    assert.equal(isWallCapability(name), true, name + ' 应该算墙')
  }
  assert.equal(WALL_CAPABILITIES.length, 9)
})

test('J3 墙：墙的能力不在能力库里（协议只能说，不能做）', () => {
  for (const name of WALL_CAPABILITIES) {
    assert.equal(hasCapability(name), false, name + ' 不该登记成可用能力')
  }
})

test('J3 墙：不认识的名字既不是能力也不是墙', () => {
  assert.equal(hasCapability('读心术'), false)
  assert.equal(isWallCapability('读心术'), false)
  assert.equal(capabilityOf('读心术'), null)
})

// ── 度量类：纯函数，零模型，每个三条 ──────────────────────────

const METRIC_CASES = {
  字数: [
    [{ text: '' }, 0],
    [{ text: ' 三个 字 ' }, 3], // 空白不算字
    [{ text: 'a b' }, 2],
  ],
  话题数: [
    [{ text: '' }, 0],
    [{ text: '一。二。三' }, 3],
    [{ text: '一二；三' }, 2], // 分号也是断句
  ],
  具体名词数: [
    [{ text: '' }, 0],
    [{ text: '哈希表和红黑树' }, 2], // 连接词要切开，不能整段算一个
    [{ text: '因为' }, 0], // 停用词不算
  ],
  有无结论句: [
    [{ text: '讲了缓存。所以关键是命中率' }, true],
    [{ text: '讲了缓存' }, false],
    [{ text: '所以先这样。然后回家' }, false], // 只看最后一句
  ],
  问句数: [
    [{ text: '' }, 0],
    [{ text: '什么？为什么?' }, 2], // 全角半角都算
    [{ text: '真的？' }, 1],
  ],
  应答延迟: [
    [{ askedAt: 1000, answeredAt: 6000 }, 5000],
    [{ askedAt: 6000, answeredAt: 1000 }, 0], // 时钟倒挂不产生负数
    [{}, 0],
  ],
  填充词比例: [
    [{ text: '嗯嗯嗯嗯' }, 1],
    [{ text: '哈希表是数组加链表' }, 0],
    [{ text: '然后就是那个' }, 0.5],
  ],
  与他近5条的重合率: [
    [{ text: '哈希表', recent: ['哈希表'] }, 1],
    [{ text: '你好', recent: ['再见'] }, 0],
    [{ text: '哈希表加链表', recent: ['哈希表是数组'] }, 0.25],
  ],
}

for (const [name, cases] of Object.entries(METRIC_CASES)) {
  cases.forEach(([input, want], i) => {
    test(`J3 度量 ${name} #${i + 1}`, () => {
      assert.equal(measure(name, input), want, JSON.stringify(input))
    })
  })
}

test('J3 度量：只有度量类能 measure，别的类返回 null（不装会算）', () => {
  assert.equal(measure('语音问', { text: '一' }), null)
  assert.equal(measure('盖屏', { text: '一' }), null)
  assert.equal(measure('读心术', { text: '一' }), null)
})

test('J3 度量：每个度量能力都真的挂着一个纯函数', () => {
  for (const name of DOC_TABLE['度量']) {
    assert.equal(typeof capabilityOf(name).run, 'function', name + ' 没有实现')
  }
})

// ── 缺能力提议 ──────────────────────────────────────────────

test('J3 缺能力提议：每个名字一份，带协议 id 和三条要做的活', () => {
  const p = missingCapabilityProposal(['读心术', '预知', '读心术'], 'x-needs')
  assert.equal(p.kind, 'missing-capability')
  assert.equal(p.protocolId, 'x-needs')
  assert.deepEqual(p.capabilities.map((c) => c.name), ['读心术', '预知'])
  assert.equal(p.capabilities[0].class, null)
  assert.equal(p.capabilities[0].need.length, 3)
  assert.match(p.proposal, /读心术、预知/)
})

test('J3 缺能力提议：没有名字就没有提议（不是永远返回一个空壳）', () => {
  assert.equal(missingCapabilityProposal([], 'x'), null)
  assert.equal(missingCapabilityProposal(null, 'x'), null)
})
