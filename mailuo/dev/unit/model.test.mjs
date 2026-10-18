import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseNode, serializeNode, normalizeType, normalizeSentinels, TYPES } from '../../www/js/model.js';
import { makeStore } from './helpers.mjs';

const v1 = (type, extra = '') => `---\nid: n-0001\ntitle: 旧节点\ntype: ${type}\nsystem: 体系\nparent: 加工层\nkeywords: [a, b, c]\nstatus: 假设\nconfidence: 60\nverify: 记一周\n${extra}created: 2026-10-10\n---\n一句结论。\n`;

test('V2 只有 问 / 论 / 规 三种类型', () => {
  assert.deepEqual(TYPES, ['问', '论', '规']);
});

test('v1 类型迁移：问题→问、论断→论、规则→规、概念/案例→论（记 legacy_type）', () => {
  assert.equal(parseNode(v1('问题'), 'x.md').type, '问');
  assert.equal(parseNode(v1('论断'), 'x.md').type, '论');
  assert.equal(parseNode(v1('规则'), 'x.md').type, '规');
  const c = parseNode(v1('概念'), 'x.md');
  assert.equal(c.type, '论');
  assert.equal(c.legacy_type, '概念');
  assert.match(serializeNode(c), /^legacy_type: 概念$/m);
  assert.equal(normalizeType('乱写'), '论');
});

test('v1 的「验证方式」迁移成一条 delta=0 的验证哨，原字段保留', () => {
  const n = parseNode(v1('论断'), 'x.md');
  assert.deepEqual(n.sentinels, [{ when: '记一周', delta: 0 }]);
  assert.equal(n.verify, '记一周');
});

test('槽位字段写出再读回不丢失', () => {
  const n = parseNode(v1('论断'), 'x.md');
  Object.assign(n, {
    under: 'n-0100', blindspot: '精力充沛时仍早停就站不住', cost: '',
    sentinels: [{ when: '写下下一问后能多走一步', delta: 10 }, { when: '写了也不想往下想', delta: -15 }],
  });
  const back = parseNode(serializeNode(n), 'x.md');
  assert.equal(back.under, 'n-0100');
  assert.equal(back.blindspot, n.blindspot);
  assert.deepEqual(back.sentinels, n.sentinels);
  assert.equal(back.created, '2026-10-10');

  const r = parseNode(v1('规则', 'triggers: [觉得差不多了的时候, 准备关笔记时]\ncost: 同样的坑再踩一次\npaused: true\n'), 'r.md');
  const r2 = parseNode(serializeNode(r), 'r.md');
  assert.deepEqual(r2.triggers, ['觉得差不多了的时候', '准备关笔记时']);
  assert.equal(r2.cost, '同样的坑再踩一次');
  assert.equal(r2.paused, true);
  assert.doesNotMatch(serializeNode({ ...r2, paused: false }), /paused/);
});

test('验证哨规范化：字符串、+10、越界都能处理', () => {
  assert.deepEqual(normalizeSentinels(['看到 A', { when: '看到 B', delta: '+10' }, { when: '看到 C', delta: -99 }, { when: '', delta: 5 }]),
    [{ when: '看到 A', delta: 0 }, { when: '看到 B', delta: 10 }, { when: '看到 C', delta: -50 }]);
});

const chain = [
  { id: 'q1', type: '问', title: '为什么会停' },
  { id: 'q2', type: '问', title: '子问题', under: 'q1' },
  { id: 'l1', type: '论', title: '论一', under: 'q1', confidence: 85 },
  { id: 'l2', type: '论', title: '论二', under: 'l1', confidence: 60 },
  { id: 'r1', type: '规', title: '规一', under: 'l1', confidence: 90 },
  { id: 'r2', type: '规', title: '孤儿规' },
  { id: 'l3', type: '论', title: '挂在不存在的节点下', under: 'nope' },
];

test('链条查询：children / ancestors / descendants / chainRoots / unmounted', async () => {
  const s = await makeStore(chain);
  assert.deepEqual(s.children('q1').map((n) => n.id), ['q2', 'l1']);
  assert.deepEqual(s.children('l1').map((n) => n.id), ['l2', 'r1']);
  assert.deepEqual(s.ancestors('r1').map((n) => n.id), ['l1', 'q1']);
  assert.deepEqual(s.descendants('q1').map((n) => n.id).sort(), ['l1', 'l2', 'q2', 'r1']);
  assert.deepEqual(s.chainRoots('测试').map((n) => n.id), ['q1']);
  assert.deepEqual(s.unmounted('测试').map((n) => n.id).sort(), ['l3', 'r2']);
});

test('挂载规则：规 只能挂 论/问，问 只能挂 问，不能成环', async () => {
  const s = await makeStore(chain);
  assert.equal(s.canMount('r2', 'l1'), '');
  assert.equal(s.canMount('r2', 'q1'), '');
  assert.match(s.canMount('r2', 'r1'), /只能挂在/);
  assert.match(s.canMount('q1', 'l1'), /只能挂在/);
  assert.match(s.canMount('l1', 'l2'), /成环/);
  assert.match(s.canMount('l1', 'l1'), /自己/);
  await s.mount('r2', 'l1');
  assert.equal(s.nodes.get('r2').under, 'l1');
  assert.match([...s.vault.files.values()].find((t) => t.includes('id: r2')), /^under: l1$/m);
  await assert.rejects(() => s.mount('r2', 'r1'));
});

test('删除节点：下级上移到它的上级（类型不允许就变成未挂载）', async () => {
  const s = await makeStore(chain);
  await s.deleteNode('l1');
  assert.equal(s.nodes.get('l2').under, 'q1');
  assert.equal(s.nodes.get('r1').under, 'q1');
  await s.deleteNode('q1');
  assert.equal(s.nodes.get('q2').under, '');
  assert.equal(s.nodes.get('r1').under, '');
});

test('合并节点：挂在被合并节点下面的改挂到保留节点，槽位合并', async () => {
  const s = await makeStore([
    { id: 'l1', type: '论', title: '论一', sentinels: [{ when: 'A', delta: 5 }] },
    { id: 'l9', type: '论', title: '论一重复', blindspot: '盲区', sentinels: [{ when: 'B', delta: -5 }] },
    { id: 'r1', type: '规', title: '规一', under: 'l9' },
  ]);
  await s.mergeNodes('l1', 'l9');
  assert.equal(s.nodes.get('r1').under, 'l1');
  assert.equal(s.nodes.get('l1').blindspot, '盲区');
  assert.deepEqual(s.nodes.get('l1').sentinels.map((x) => x.when), ['A', 'B']);
});

test('代码里直接构造的不完整节点也能保存（saveNode 自动补字段）', async () => {
  const s = await makeStore();
  await s.saveNode({ id: 'x1', title: '半截节点', type: '论断', system: '测试', claim: '结论' });
  const n = s.nodes.get('x1');
  assert.equal(n.type, '论');
  assert.deepEqual(n.pillars, []);
  assert.deepEqual(n.sentinels, []);
});
