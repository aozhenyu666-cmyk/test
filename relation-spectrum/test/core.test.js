import { test } from 'node:test';
import assert from 'node:assert/strict';

import { newEntity, newInteraction, todayISO } from '../web/js/core/models.js';
import { analyze, flowUnits, ledger, invites, nextOccurrence, previewDelta, credibility } from '../web/js/core/scoring.js';
import { SCHEMAS, SYSTEM_PROMPTS, buildUserMessage } from '../web/js/core/prompts.js';
import {
  entityToProps, pageToEntity, interactionToProps, pageToInteraction, caseToProps, pageToCase,
  entitiesDbBody, interactionsDbBody, rt,
} from '../web/js/core/notion-schema.js';
import { parseJson } from '../server/llm.js';

const NOW = new Date(2026, 9, 21, 12).getTime();
const ago = (n) => todayISO(new Date(NOW - n * 86400000));

test('flowUnits grows sub-linearly with money', () => {
  const small = flowUnits({ money: 100 }, 'bond');
  const big = flowUnits({ money: 10000 }, 'bond');
  assert.ok(big > small);
  assert.ok(big < small * 10, 'a 100x gift must not count 100x');
});

test('ledger balance is positive when I give more', () => {
  const e = newEntity({ tier: 'bond' });
  const its = [
    newInteraction({ date: ago(5), give: { minutes: 90, emotion: 4 }, get: { emotion: 1 } }),
    newInteraction({ date: ago(10), give: { money: 200 }, get: {} }),
  ];
  const l = ledger(e, its, NOW);
  assert.ok(l.balance > 0.5, `balance ${l.balance}`);
});

test('soft-reject streak counts consecutive non-accepted invites by me', () => {
  const its = [
    newInteraction({ date: ago(40), invite: { by: 'me', status: 'accepted' } }),
    newInteraction({ date: ago(30), invite: { by: 'me', status: 'flaked' } }),
    newInteraction({ date: ago(20), invite: { by: 'them', status: 'accepted' } }),
    newInteraction({ date: ago(10), invite: { by: 'me', status: 'postponed' } }),
  ];
  assert.equal(invites(its).softRejectStreak, 2);
});

test('the friend story triggers a soft-reject alert and a cooling stage', () => {
  const e = newEntity({ tier: 'bond', cadenceDays: 21 });
  const its = [
    newInteraction({ entityId: e.id, date: ago(70), type: 'support', initiator: 'them', give: { minutes: 70, emotion: 4 }, feeling: 1 }),
    newInteraction({ entityId: e.id, date: ago(30), type: 'invite', invite: { by: 'me', status: 'flaked' }, feeling: -1 }),
    newInteraction({ entityId: e.id, date: ago(18), type: 'invite', invite: { by: 'me', status: 'postponed' }, feeling: -1 }),
    newInteraction({ entityId: e.id, date: ago(6), type: 'invite', invite: { by: 'me', status: 'postponed' }, feeling: -2 }),
  ];
  const a = analyze(e, its, NOW);
  assert.ok(a.alerts.some((x) => x.code === 'soft_reject'));
  assert.ok(['cooling', 'breaking'].includes(a.stage));
  assert.ok(a.temperature >= 0 && a.temperature <= 100);
});

test('kin: care overdue and upcoming birthday', () => {
  const d = new Date(NOW + 5 * 86400000);
  const mmdd = `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const e = newEntity({ tier: 'kin', cadenceDays: 7, birthYear: 1970, keyDates: [{ label: '妈妈生日', date: mmdd }] });
  const its = [
    newInteraction({ date: ago(20), type: 'care', initiator: 'me' }),
    newInteraction({ date: ago(100), type: 'meet', initiator: 'me' }),
  ];
  const a = analyze(e, its, NOW);
  assert.ok(a.alerts.some((x) => x.code === 'care_overdue'));
  const bday = a.alerts.find((x) => x.code === 'key_date');
  assert.ok(bday && bday.title.includes('5 天'));
  assert.equal(a.meetingsLeft.meetsPerYear, 1);
  assert.equal(a.meetingsLeft.estimate, 82 - 56);
});

test('deal: broken promises lower credibility and ROI is computed', () => {
  const e = newEntity({ tier: 'deal', contract: { hourlyValue: 60 } });
  const its = [
    newInteraction({ date: ago(200), promise: { by: 'them', status: 'broken' } }),
    newInteraction({ date: ago(100), promise: { by: 'them', status: 'broken' } }),
    newInteraction({ date: ago(50), promise: { by: 'them', status: 'broken' } }),
    newInteraction({ date: ago(5), type: 'work', give: { minutes: 600 }, get: { money: 300 } }),
  ];
  const a = analyze(e, its, NOW);
  assert.equal(credibility(its).them.rate, 0.2);
  assert.ok(a.alerts.some((x) => x.code === 'low_credibility'));
  assert.equal(a.roi.effectiveHourly, 30);
  assert.ok(a.roi.ratio < 0.6);
});

test('empty relationship has no temperature and no crash', () => {
  const a = analyze(newEntity({ tier: 'bond' }), [], NOW);
  assert.equal(a.temperature, null);
  assert.equal(a.stage, null);
});

test('nextOccurrence wraps to next year', () => {
  const r = nextOccurrence('01-01', new Date(2026, 11, 31).getTime());
  assert.equal(r.days, 1);
  assert.equal(nextOccurrence('2026-10-21', NOW).days, 0);
});

test('previewDelta reports new alerts', () => {
  const e = newEntity({ tier: 'bond' });
  const base = [newInteraction({ date: ago(20), invite: { by: 'me', status: 'postponed' } })];
  const draft = newInteraction({ date: ago(0), invite: { by: 'me', status: 'declined' } });
  const d = previewDelta(e, base, draft, NOW);
  assert.ok(d.newAlerts.some((x) => x.code === 'soft_reject'));
});

// ---- prompts / schemas

function checkSchema(node, path = '$') {
  if (node.type === 'object') {
    assert.equal(node.additionalProperties, false, `${path} needs additionalProperties:false`);
    assert.deepEqual([...node.required].sort(), Object.keys(node.properties).sort(), `${path} must require every property`);
    for (const [k, v] of Object.entries(node.properties)) checkSchema(v, `${path}.${k}`);
  }
  if (node.type === 'array') checkSchema(node.items, `${path}[]`);
  for (const bad of ['minimum', 'maximum', 'minLength', 'maxLength', 'minItems', 'maxItems']) {
    assert.ok(!(bad in node), `${path} uses unsupported ${bad}`);
  }
}

test('schemas follow structured-output rules', () => {
  for (const [k, s] of Object.entries(SCHEMAS)) {
    checkSchema(s, k);
    assert.ok(SYSTEM_PROMPTS[k].length > 500);
  }
});

test('user message includes profile, last 10 records and the draft', () => {
  const e = newEntity({ name: '阿哲', tier: 'bond' });
  const its = Array.from({ length: 14 }, (_, i) => newInteraction({ date: ago(30 - i), title: `事件${i}` }));
  const msg = buildUserMessage('decode', { situation: '他又推脱了', draft: '你爱咋咋吧', entity: e, interactions: its, analysis: analyze(e, its, NOW) });
  assert.match(msg, /阿哲/);
  assert.match(msg, /共10条/);
  assert.ok(!msg.includes('事件3 '), 'older records are dropped');
  assert.match(msg, /事件13/);
  assert.match(msg, /你爱咋咋吧/);
  assert.match(msg, /冲动原话破坏力测试/);
});

test('parseJson tolerates code fences', () => {
  assert.deepEqual(parseJson('```json\n{"a":1}\n```'), { a: 1 });
  assert.throws(() => parseJson('not json'));
});

// ---- notion mapping

const asPage = (properties) => {
  // Simulate Notion's response shape: rich_text/title items carry plain_text.
  const out = {};
  for (const [k, v] of Object.entries(properties)) {
    const copy = structuredClone(v);
    for (const key of ['rich_text', 'title']) {
      if (copy[key]) copy[key] = copy[key].map((t) => ({ ...t, plain_text: t.text.content }));
    }
    if (copy.select) copy.select = { name: copy.select.name };
    out[k] = copy;
  }
  return { id: 'page-1', last_edited_time: new Date(NOW).toISOString(), properties: out };
};

test('entity round-trips through Notion properties', () => {
  const e = newEntity({
    name: '妈妈', tier: 'kin', role: '母亲', birthYear: 1970, cadenceDays: 7,
    keyDates: [{ label: '生日', date: '05-12', lunar: true }],
    contract: { myChips: 'x', theirChips: '', batna: '', bottomLine: '', hourlyValue: 80 },
  });
  const back = pageToEntity(asPage(entityToProps(e)));
  assert.equal(back.id, e.id);
  assert.equal(back.name, '妈妈');
  assert.equal(back.tier, 'kin');
  assert.equal(back.birthYear, 1970);
  assert.deepEqual(back.keyDates, e.keyDates);
  assert.equal(back.contract.hourlyValue, 80);
});

test('interaction and case round-trip', () => {
  const it = newInteraction({
    date: '2026-10-01', type: 'invite', initiator: 'me',
    give: { money: 50, minutes: 30, emotion: 2 }, invite: { by: 'me', status: 'postponed' },
    promise: { by: 'them', status: 'broken', content: '下次一定' }, feeling: -1, flags: ['soft_reject'],
  });
  const back = pageToInteraction(asPage(interactionToProps(it, 'ent-page')));
  assert.equal(back.id, it.id);
  assert.equal(back.type, 'invite');
  assert.deepEqual(back.invite, it.invite);
  assert.deepEqual(back.promise, it.promise);
  assert.equal(back.give.money, 50);
  assert.equal(back.feeling, -1);

  const c = { id: 'case_1', title: 't', mode: 'game', scenario: 'workplace', situation: 's', draft: '', result: { summary: 'x', playbook: [] } };
  const cb = pageToCase(asPage(caseToProps(c, null)));
  assert.equal(cb.mode, 'game');
  assert.deepEqual(cb.result, c.result);
});

test('rich text is chunked to Notion limits', () => {
  const r = rt('a'.repeat(4500));
  assert.equal(r.rich_text.length, 3);
  assert.ok(r.rich_text.every((t) => t.text.content.length <= 2000));
});

test('database bodies wire the relation to Entities_DB', () => {
  assert.ok(entitiesDbBody('p').properties['名称'].title);
  const ib = interactionsDbBody('p', 'ENT');
  assert.equal(ib.properties['关系对象'].relation.database_id, 'ENT');
});

test('a rejected invite does not raise the temperature', () => {
  const e = newEntity({ tier: 'bond' });
  const base = [newInteraction({ date: ago(25), type: 'meet', give: { minutes: 60 }, get: { minutes: 60 }, invite: { by: 'me', status: 'accepted' } })];
  const draft = newInteraction({ date: ago(0), type: 'invite', invite: { by: 'me', status: 'postponed' }, feeling: -1 });
  const d = previewDelta(e, base, draft, NOW);
  assert.ok(d.tempDelta <= 0, `tempDelta ${d.tempDelta}`);
});
