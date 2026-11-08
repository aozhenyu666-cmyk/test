import test from 'node:test';
import assert from 'node:assert/strict';

import { apply, buildTools, createEngine } from '../lib/index.js';
import { listCommitments, listThreads } from '../lib/core/db.js';
import { atLocal } from '../lib/core/time.js';

const T0 = Date.parse('2026-11-05T09:00:00+08:00');
const d = (day, hour, minute = 0) => atLocal(T0, day, hour, minute, 480);

test('关系内核：创建考试线程、记录承诺、到期跟进', () => {
  const engine = createEngine({ dbPath: ':memory:' });
  try {
    engine.onUserTurn('我明年5月要考公，从明天开始每天做20道题。', d(0, 9));
    const thread = listThreads(engine.db).find((t) => t.domain === 'exam');
    assert.ok(thread);
    assert.equal(thread.exam_date, '2027-05-15');
    assert.ok(
      listCommitments(engine.db, thread.id).some(
        (c) => c.owner === 'user' && c.status === 'pending',
      ),
    );

    assert.equal(engine.tick({ app: 'app.study', appMinutes: 40 }, d(1, 10)), null);

    const due = engine.tick({ app: 'app.wechat', appMinutes: 5 }, d(1, 20));
    assert.ok(due);
    assert.equal(due.level, 2);

    const brief = engine.newSessionBrief(d(2, 10));
    assert.match(brief.text, /考试准备/);
  } finally {
    engine.close();
  }
});

test('关系内核：解决后 7 天静默', () => {
  const engine = createEngine({ dbPath: ':memory:' });
  try {
    engine.onUserTurn('我明年5月要考公，从明天开始每天做20道题。', d(0, 9));
    const thread = listThreads(engine.db)[0];
    engine.resolveThread(thread.id, d(1, 10));
    assert.equal(engine.tick({ app: 'app.wechat', appMinutes: 5 }, d(2, 20)), null);
  } finally {
    engine.close();
  }
});

test('插件注册 7 个关系工具并可直接执行', () => {
  const engine = createEngine({ dbPath: ':memory:' });
  try {
    const defs = buildTools((def) => def, engine);
    const names = defs.map((def) => def.name).sort();
    assert.deepEqual(names, [
      'relationship_check',
      'relationship_commit',
      'relationship_correct',
      'relationship_nightly',
      'relationship_observe',
      'relationship_resolve',
      'relationship_status',
    ]);

    const status = defs.find((def) => def.name === 'relationship_status');
    assert.equal(status.execute().ok, true);

    const observe = defs.find((def) => def.name === 'relationship_observe');
    const recorded = observe.execute({
      text: '我明年5月要考公，从明天开始每天做20道题。',
    });
    assert.equal(recorded.ok, true);
    assert.ok(recorded.threadId);

    const commit = defs.find((def) => def.name === 'relationship_commit');
    const c = commit.execute({
      statement: '明天做30道',
      dueAt: '2026-11-06T12:00:00.000Z',
    });
    assert.equal(c.ok, true);
    assert.ok(c.id);

    const check = defs.find((def) => def.name === 'relationship_check');
    assert.equal(check.execute({ app: 'app.wechat', appMinutes: 5 }).ok, true);

    const nightly = defs.find((def) => def.name === 'relationship_nightly');
    assert.equal(nightly.execute().ok, true);
  } finally {
    engine.close();
  }
});

test('apply 在假宿主上注册工具并返回 disposer', () => {
  const engine = createEngine({ dbPath: ':memory:' });
  const registered = [];
  const ctx = {
    tools: {
      register(def) {
        registered.push(def);
        return () => {};
      },
    },
  };

  const dispose = apply(ctx, {}, { engine, defineTool: (def) => def });
  assert.equal(registered.length, 7);
  assert.equal(typeof dispose, 'function');
  dispose();
});
