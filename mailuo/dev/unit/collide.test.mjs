import { test } from 'node:test';
import assert from 'node:assert/strict';
import { libraryDigest, normalizeCollide, relevance, suggestPauses, collideUserMessage, digestLine } from '../../www/js/collide.js';
import { makeStore } from './helpers.mjs';

const lib = [
  { id: 'q1', type: '问', title: '为什么想到一半就停', pillars: ['工作记忆有限'] },
  {
    id: 'l1',
    type: '论',
    title: '总控系统交互频次不足',
    under: 'q1',
    confidence: 85,
    keywords: ['总控', '交互频次'],
    sentinels: [
      { when: '一周打开总控少于 3 次', delta: 10 },
      { when: '每天都打开总控', delta: -15 },
    ],
  },
  { id: 'r1', type: '规', title: '想打开总控就立刻打开', under: 'l1', confidence: 90, triggers: ['想打开总控但犹豫时'] },
  { id: 'r2', type: '规', title: '低把握规', under: 'l1', confidence: 50 },
  { id: 'l2', type: '论', title: '一手系统灵活性不足', under: 'q1', confidence: 70, keywords: ['一手', '灵活性'] },
];

test('摘要行包含类型、把握、挂载和槽位', async () => {
  const s = await makeStore(lib);
  assert.equal(digestLine(s.nodes.get('l1')), 'l1 论 85% 总控系统交互频次不足｜挂 q1｜哨：+10 一周打开总控少于 3 次；-15 每天都打开总控');
  assert.equal(digestLine(s.nodes.get('r1')), 'r1 规 90% 想打开总控就立刻打开｜挂 l1｜触发：想打开总控但犹豫时');
  assert.equal(digestLine(s.nodes.get('q1')), 'q1 问 为什么想到一半就停｜支柱：工作记忆有限');
});

test('库小时全部发送；库大时只发相关节点及其上下级', async () => {
  const many = [...lib];
  for (let i = 0; i < 60; i++) many.push({ id: 'x' + i, type: '论', title: '无关的节点' + i, keywords: ['天气', '做饭'] });
  const s = await makeStore(many);
  const small = libraryDigest(await makeStore(lib), '随便');
  assert.equal(small.ids.length, 5);
  const d = libraryDigest(s, '这周我几乎每天都打开总控，交互频次上来了', { maxNodes: 6 });
  assert.ok(d.ids.includes('l1'), '命中激活词的论');
  assert.ok(d.ids.includes('q1'), '带上上级问');
  assert.ok(d.ids.includes('r1'), '带上下级规');
  assert.ok(!d.ids.some((id) => id.startsWith('x')), '无关节点不发');
  assert.ok(d.ids.length <= 6);
  assert.ok(relevance(s.nodes.get('l1'), '总控') > relevance(s.nodes.get('x1'), '总控'));
  assert.match(collideUserMessage(d, '材料'), /^节点库摘要/);
});

test('规范化：调整提案以库里真实把握为 from，暂停只保留目标下面的规', async () => {
  const s = await makeStore(lib);
  const out = normalizeCollide(
    {
      adjust: [
        { target: 'l1', from: 99, to: 55, reason: '每天都打开总控', hit: '每天都打开总控', pause_rules: ['r1', 'l2', 'nope'] },
        { target: 'q1', to: 30 }, // 问 没有把握度，丢掉
        { target: 'l2', from: 70, to: 70 }, // 没变化，丢掉
        { target: 'ghost', to: 10 }, // 不存在，丢掉
      ],
    },
    s,
  );
  assert.deepEqual(out.adjust, [{ target: 'l1', from: 85, to: 55, reason: '每天都打开总控', hit: '每天都打开总控', pause_rules: ['r1'] }]);
  assert.deepEqual(suggestPauses(s, 'l1', 55), ['r1']);
  assert.deepEqual(suggestPauses(s, 'l1', 70), []);
});

test('规范化：新节点类型、槽位、挂载规则', async () => {
  const s = await makeStore(lib);
  const out = normalizeCollide(
    {
      nodes: [
        {
          tmp_id: 't1',
          type: '规',
          title: '每晚花 1 分钟打开总控',
          under: 'l1',
          confidence: '80%',
          triggers: '睡前刷手机时、想偷懒时',
          cost: '总控又被冷落一周',
          keywords: ['总控', '晚间'],
        },
        { tmp_id: 't2', type: '论断', title: '新论', under: 'r1', sentinels: [{ when: 'A', delta: 99 }, 'B'] }, // 论 不能挂在 规 下 → 清空
        { tmp_id: 't3', type: '规', title: '挂在新论下', under: 't2' },
        { tmp_id: 't4', type: '问', title: '新问题', pillars: ['一', '二', '三', '四'], confidence: 70 },
        { title: '' },
      ],
      links: [
        { from: 't1', to: 'l2', type: '支撑', why: 'x' },
        { from: 't1', to: 'zz', type: '支撑' },
        { from: 't1', to: 'l2', type: '乱写' },
      ],
    },
    s,
  );
  const [t1, t2, t3, t4] = out.nodes;
  assert.equal(out.nodes.length, 4);
  assert.equal(t1.under, 'l1');
  assert.equal(t1.confidence, 80);
  assert.deepEqual(t1.triggers, ['睡前刷手机时', '想偷懒时']);
  assert.equal(t2.type, '论');
  assert.equal(t2.under, '', '论 不能挂在 规 下');
  assert.deepEqual(t2.sentinels, [
    { when: 'A', delta: 30 },
    { when: 'B', delta: 0 },
  ]);
  assert.equal(t3.under, 't2', '可以挂在本次新建的节点下');
  assert.equal(t4.confidence, null);
  assert.deepEqual(t4.pillars, ['一', '二', '三']);
  assert.deepEqual(out.links, [{ from: 't1', to: 'l2', type: '支撑', why: 'x' }]);
});

test('规范化：和已有节点几乎同名的标出 dupe_of', async () => {
  const s = await makeStore(lib);
  const out = normalizeCollide({ nodes: [{ tmp_id: 'a', type: '论', title: '总控系统交互频次不够' }] }, s);
  assert.equal(out.nodes[0].dupe_of, 'l1');
});
