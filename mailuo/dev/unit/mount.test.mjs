import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeMounts, mountUserMessage } from '../../www/js/collide.js';
import { makeStore } from './helpers.mjs';

// 模拟 v1 升级上来的库：只有论和规，都没挂载
const v1 = [
  { id: 'a', type: '论', title: '第一反应停止', confidence: 60 },
  { id: 'b', type: '论', title: '推理链断', confidence: 55 },
  { id: 'r', type: '规', title: '写下下一问', confidence: 60 },
  { id: 'q0', type: '问', title: '已有的问' },
];

test('整理挂载的请求列出未挂载节点和可作父节点的问', async () => {
  const s = await makeStore(v1);
  const msg = mountUserMessage(s, '测试');
  assert.match(msg, /未挂载的节点：\na 论 60% 第一反应停止/);
  assert.match(msg, /可作为父节点）：\nq0 问 已有的问/);
});

test('校验挂载建议：新建问、挂载规则、只保留用到的问', async () => {
  const s = await makeStore(v1);
  const out = normalizeMounts({
    questions: [{ tmp_id: 'q1', title: '为什么想到一半就停', claim: '…', pillars: ['一', '二', '三', '四'] }, { tmp_id: 'q2', title: '没人用的问' }],
    mounts: [
      { id: 'a', under: 'q1', reason: '症状' },
      { id: 'b', under: 'a', reason: '链断是第一反应的后果' },
      { id: 'r', under: 'b', reason: '对症的规' },
      { id: 'a', under: 'q0' }, // 重复，忽略
      { id: 'q0', under: 'q1' }, // 不是未挂载节点，忽略
      { id: 'r', under: 'nope' }, // 父节点不存在
    ],
  }, s, '测试');
  assert.deepEqual(out.questions.map((q) => q.tmp_id), ['q1']);
  assert.deepEqual(out.questions[0].pillars, ['一', '二', '三']);
  assert.deepEqual(out.mounts.map((m) => m.id + '→' + m.under), ['a→q1', 'b→a', 'r→b']);
});

test('校验挂载建议：规不能挂在规下，问不会被挂', async () => {
  const s = await makeStore([...v1, { id: 'r2', type: '规', title: '另一条规' }]);
  const out = normalizeMounts({ mounts: [{ id: 'r2', under: 'r' }, { id: 'a', under: 'r' }] }, s, '测试');
  assert.deepEqual(out.mounts, []);
});
