import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildPack, chainPath, nodeText } from '../../www/js/pack.js';
import { parseSuggestion, parseAnswer } from '../../www/js/llm.js';
import { makeStore } from './helpers.mjs';

const lib = [
  { id: 'q', type: '问', title: '为什么会停', claim: '想到一半就停', pillars: ['工作记忆有限', '反馈延迟'] },
  {
    id: 'l',
    type: '论',
    title: '单步成本高',
    claim: '多想一步很累',
    under: 'q',
    confidence: 60,
    blindspot: '精力好时仍停',
    sentinels: [
      { when: '写纸上后走得更远', delta: 10 },
      { when: '写了也没用', delta: -15 },
    ],
    keywords: ['认知负荷'],
  },
  { id: 'r', type: '规', title: '累了先写纸上', claim: '把下一步写在纸上', under: 'l', confidence: 55, triggers: ['脑子转不动时'], cost: '链条断在第二步' },
  { id: 'x', type: '论', title: '没挂载的论', claim: '孤立' },
];

test('打包路径按挂载链；没挂载的退回「体系 › 标题」', async () => {
  const s = await makeStore(lib);
  assert.deepEqual(chainPath(s, s.nodes.get('r')), ['为什么会停', '单步成本高', '累了先写纸上']);
  assert.deepEqual(chainPath(s, s.nodes.get('x')), ['测试', '没挂载的论']);
});

test('节点正文按类型带槽位', async () => {
  const s = await makeStore(lib);
  assert.match(nodeText(s.nodes.get('q')), /破局支柱：工作记忆有限；反馈延迟/);
  const l = nodeText(s.nodes.get('l'));
  assert.match(l, /（论｜假设｜把握60%）/);
  assert.match(l, /反方盲区：精力好时仍停/);
  assert.match(l, /验证哨：\+10 写纸上后走得更远；−15 写了也没用/);
  const r = nodeText(s.nodes.get('r'));
  assert.match(r, /动作：把下一步写在纸上/);
  assert.match(r, /触发：脑子转不动时/);
  assert.match(r, /破戒代价：链条断在第二步/);
});

test('邻居含上级和下级规；规的触发场景进入激活词', async () => {
  const s = await makeStore(lib);
  const p = buildPack(s, 'l', { question: '为什么' });
  assert.match(p.text, /【路径】为什么会停 › 单步成本高/);
  assert.match(p.text, /- 上级·问｜为什么会停：想到一半就停/);
  assert.match(p.text, /- 下级·规｜累了先写纸上：把下一步写在纸上/);
  assert.match(p.text, /【激活词】[^\n]*脑子转不动时/);
});

test('预算很小时节点本身和问题永不砍', async () => {
  const s = await makeStore(lib);
  const p = buildPack(s, 'l', { question: '为什么', budget: 30 });
  const node = p.items.find((i) => i.key === 'node');
  assert.equal(node.state, 'full');
  assert.match(p.text, /问题：为什么/);
  assert.ok(
    p.items.some((i) => i.kind === '邻居' && i.state !== 'full'),
    '邻居被压缩或省略',
  );
});

test('解析 V2「建议新增」：类型、横向关系、激活词、验证哨、触发场景', () => {
  assert.deepEqual(parseSuggestion('论｜舒缓感提前｜累时更早想停｜舒缓感、认知负荷｜哨：+10 疲劳时第一步就想停'), {
    type: '论',
    rel: '',
    title: '舒缓感提前',
    claim: '累时更早想停',
    keywords: ['舒缓感', '认知负荷'],
    sentinels: [{ when: '疲劳时第一步就想停', delta: 10 }],
    triggers: [],
  });
  const r = parseSuggestion('规｜写纸上｜把下一步写下来｜外化｜触发：脑子转不动时');
  assert.equal(r.type, '规');
  assert.deepEqual(r.triggers, ['脑子转不动时']);
  const o = parseSuggestion('对立·论｜不会提问｜停下是因为不会提问｜提问');
  assert.equal(o.rel, '对立');
  assert.equal(o.type, '论');
  assert.equal(parseSuggestion('论｜A｜B｜哨：−5 看到 C').sentinels[0].delta, -5);
});

test('兼容 v1「建议新增」：关系类型在前，当作论', () => {
  const v1 = parseSuggestion('导致｜舒缓感提前｜成本高时更早想停｜舒缓感、认知负荷、停止信号');
  assert.deepEqual([v1.type, v1.rel, v1.title, v1.keywords.length], ['论', '导致', '舒缓感提前', 3]);
  const loose = parseSuggestion('纸笔外化：写下来就能走得更远（反例）');
  assert.deepEqual([loose.type, loose.rel, loose.title], ['论', '反例', '纸笔外化']);
});

test('parseAnswer 把新格式的「建议新增」解析出来', () => {
  const a = parseAnswer('- 核心判断：成本是主因\n- 建议新增：\n  规｜写纸上｜把下一步写下来｜外化、纸笔｜触发：累的时候\n  对立·论｜不会提问｜另一解释｜提问');
  assert.equal(a.core, '成本是主因');
  assert.deepEqual(
    a.suggest.map((x) => x.type + (x.rel ? '/' + x.rel : '')),
    ['规', '论/对立'],
  );
});
