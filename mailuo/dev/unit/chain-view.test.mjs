import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeStore } from './helpers.mjs';
import { systemOutline } from '../../www/js/pack.js';

test('扁平保存迁移旧路径，保留 parent 和额外字段，不覆盖同名节点', async () => {
  const s = await makeStore([
    { id: 'q', title: '课题', type: '问' },
    { id: 'l', title: '判断', under: 'q', parent: '根因', extra: { custom: '保留' } },
    { id: 'r', title: '行动', type: '规', under: 'l' },
  ]);
  const n = s.nodes.get('l');
  await s.vault.write('测试/根因/判断.md', '旧文件');
  n.path = '测试/根因/判断.md';
  await s.saveNode(n);
  assert.equal(n.path, '测试/判断.md');
  assert.equal(await s.vault.read('测试/根因/判断.md'), null);
  assert.match(await s.vault.read(n.path), /parent: 根因/);
  assert.match(await s.vault.read(n.path), /custom: "保留"/);
  await s.saveNode({ ...n, id: 'l2', path: undefined, under: '', title: '判断' });
  assert.equal(s.nodes.get('l2').path, '测试/判断 (l2).md');
  await s.saveNode({ ...n, id: 'same-system', title: '测试', path: undefined, under: '' });
  assert.equal(s.nodes.get('same-system').path, '测试/测试 (same-system).md');
  const file = await s.vault.read('测试/测试.md');
  assert.match(file, /levels: \[\]/);
  assert.match(file, /- \[问\] \[\[课题\]\]\n  - \[论\] \[\[判断\]\]\n    - \[规\] \[\[行动\]\]/);
});

test('体系大纲按挂载链缩进，未挂载最后列出，环不造成死循环', async () => {
  const s = await makeStore([
    { id: 'q', title: '课题', type: '问' },
    { id: 'l', title: '判断', under: 'q', confidence: 60 },
    { id: 'r', title: '行动', type: '规', under: 'l' },
    { id: 'o', title: '未归档' },
    { id: 'c1', title: '环一', under: 'c2' },
    { id: 'c2', title: '环二', under: 'c1' },
  ]);
  const text = systemOutline(s, '测试');
  assert.match(text, /- \*\*\[问\] 课题\*\*/);
  assert.match(text, /\n  - \*\*\[论 60%\] 判断/);
  assert.match(text, /\n    - \*\*\[规 50%\] 行动/);
  assert.ok(text.indexOf('## 未挂载') < text.indexOf('未归档'));
  assert.equal((text.match(/环一/g) || []).length, 1);
});
