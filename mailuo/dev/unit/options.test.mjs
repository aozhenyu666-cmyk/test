// 沙盘推演：路径枚举的规范化、升格成论、淘汰留档
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeStore, MemVault } from './helpers.mjs';
import { Store, blankNode } from '../../www/js/model.js';
import { normalizeOptions, optionToNode, optionsUserMessage } from '../../www/js/collide.js';

const setup = () => makeStore([
  { id: 'q', type: '问', title: '为什么想到一半就停？', claim: '想问题在第一二步就停下。', pillars: ['工作记忆有限'] },
]);

test('规范化：丢掉空标题、去掉近似重复、标出没写失败点的路径', async () => {
  const s = await setup();
  const out = normalizeOptions({
    paths: [
      { title: '直接用现成的自律 App', kind: '现成', fatal: '现成工具的提醒无法自定义到分钟级', test: '装一个试用三天', cost: '半天' },
      { title: '直接用现成的自律App', kind: '现成', fatal: '重复项应被去掉' },
      { title: '', kind: '现成' },
      { title: '自建最小原型', kind: '自建', hypothesis: '核心是打断时机而非功能数量', test: '写 200 行做一个提醒', cost: '两周' },
      { title: '买现成硬件', kind: '乱七八糟', fatal: '成本高于自制' },
    ],
  }, s, 'q');
  assert.equal(out.length, 3, '空标题和重复项被丢掉');
  assert.equal(out[0].title, '直接用现成的自律 App');
  assert.equal(out[1].noFatal, true, '没写 fatal 要标出来');
  assert.equal(out[2].kind, '其它', '不认识的 kind 归到其它');
  assert.equal(out[0].key, 'p1');
});

test('规范化：命中问下面已淘汰的路径时标记 seenBefore', async () => {
  const s = await makeStore([
    { id: 'q', type: '问', title: '课题', discarded: [{ title: '直接用现成的自律 App', fatal: '提醒粒度不够', reason: '选了自建', date: '2026-10-01' }] },
  ]);
  const [p] = normalizeOptions({ paths: [{ title: '直接用现成的自律 App', kind: '现成', fatal: 'x' }] }, s, 'q');
  assert.equal(p.seenBefore, '直接用现成的自律 App');
});

test('升格成论：fatal 变成扣分哨，test 变成加分哨', async () => {
  const s = await setup();
  const q = s.nodes.get('q');
  const [p] = normalizeOptions({
    paths: [{ title: '自建最小原型', kind: '自建', hypothesis: '核心是打断时机', fatal: '两周内做不出可用的提醒循环', test: '写 200 行跑一天' }],
  }, s, 'q');
  const n = blankNode({ ...optionToNode(p, q), id: 'n-1', system: q.system });
  assert.equal(n.type, '论');
  assert.equal(n.under, 'q', '必须挂在这个问下面');
  assert.equal(n.blindspot, '两周内做不出可用的提醒循环');
  assert.equal(n.sentinels.length, 2);
  assert.deepEqual(n.sentinels.map((x) => x.delta), [-20, 15]);
  assert.match(n.sentinels[0].when, /两周内做不出/, '扣分哨就是失败现象本身');
  assert.match(n.sentinels[1].when, /验证通过/);
  assert.equal(n.source, '沙盘推演');
  assert.equal(n.status, '假设');
});

test('没有 fatal 的路径升格后只有一条加分哨（会被体检标成盲信无哨）', async () => {
  const s = await setup();
  const [p] = normalizeOptions({ paths: [{ title: '某条路', kind: '自建', test: '试一天' }] }, s, 'q');
  const n = optionToNode(p, s.nodes.get('q'));
  assert.equal(n.sentinels.length, 1);
  assert.equal(n.sentinels[0].delta, 15);
});

test('淘汰记录写进问的 discarded，重新载入后还在', async () => {
  const s = await setup();
  const q = s.nodes.get('q');
  q.discarded = [{ title: '直接买现成的', fatal: '粒度不够', reason: '选择了「自建最小原型」', date: '2026-10-20' }];
  await s.saveNode(q);
  const s2 = new Store(s.vault);
  await s2.load();
  const back = s2.nodes.get('q');
  assert.equal(back.discarded.length, 1);
  assert.equal(back.discarded[0].title, '直接买现成的');
  assert.equal(back.discarded[0].fatal, '粒度不够');
});

test('上下文带上已淘汰的路径，避免重复推荐', async () => {
  const s = await makeStore([
    { id: 'q', type: '问', title: '课题', claim: '说明', pillars: ['约束一'], discarded: [{ title: '买现成硬件', fatal: '成本太高' }] },
    { id: 'a', type: '论', title: '已有论断', under: 'q', confidence: 60 },
  ]);
  const text = optionsUserMessage(s, 'q');
  assert.match(text, /问：课题/);
  assert.match(text, /约束一/);
  assert.match(text, /已有论断/);
  assert.match(text, /已经淘汰过的路径.*买现成硬件/);
});
