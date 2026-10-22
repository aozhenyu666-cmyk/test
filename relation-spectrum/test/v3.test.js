import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { gptInstructions, notionOpenApi, GPT_INSTRUCTIONS_LIMIT } from '../web/js/core/gpt-kit.js';
import { entitiesDbBody, interactionsDbBody, casesDbBody, caseToProps, P } from '../web/js/core/notion-schema.js';
import { SEED_CASES } from '../web/js/data/seed-cases.js';

const ids = { entitiesDb: 'e'.repeat(32), interactionsDb: 'i'.repeat(32), casesDb: 'c'.repeat(32) };

test('GPT instructions fit the 8000-character box and carry the database IDs', () => {
  const t = gptInstructions(ids);
  assert.ok(t.length < GPT_INSTRUCTIONS_LIMIT, `length ${t.length}`);
  for (const id of Object.values(ids)) assert.ok(t.includes(id));
  assert.match(t, /Notion-Version 一律传 2022-06-28/);
});

test('every field the GPT is told to write exists in the databases the app creates', () => {
  const t = gptInstructions(ids);
  const props = new Set([
    ...Object.keys(entitiesDbBody('p').properties),
    ...Object.keys(interactionsDbBody('p', 'e').properties),
    ...Object.keys(casesDbBody('p', 'e').properties),
  ]);
  const writable = [P.name, P.tier, P.role, P.needs, P.redLines, P.keyDates, P.cadence, P.birthYear, P.notes,
    P.title, P.entity, P.date, P.type, P.giveMoney, P.giveMinutes, P.giveEmotion, P.getMoney, P.getMinutes, P.getEmotion,
    P.initiator, P.inviteBy, P.inviteStatus, P.promiseBy, P.promiseStatus, P.promiseContent, P.feeling, P.note,
    P.caseTitle, P.mode, P.scenario, P.situation, P.draft, P.myGuess, P.summary, P.playbook];
  for (const name of writable) {
    assert.ok(props.has(name), `${name} missing from the database bodies`);
    assert.ok(t.includes(name), `${name} missing from the GPT instructions`);
  }
  // select options the GPT is told to use match the database options
  const tierOptions = entitiesDbBody('p').properties[P.tier].select.options.map((o) => o.name);
  for (const o of tierOptions) assert.ok(t.includes(o));
});

test('Notion Action schema has the five operations and the version header', () => {
  const s = notionOpenApi();
  assert.equal(s.openapi, '3.1.0');
  assert.equal(s.servers[0].url, 'https://api.notion.com');
  const ops = Object.values(s.paths).flatMap((p) => Object.values(p).map((o) => o.operationId)).sort();
  assert.deepEqual(ops, ['createPage', 'getDatabase', 'getPage', 'queryDatabase', 'updatePage']);
  for (const p of Object.values(s.paths)) {
    for (const o of Object.values(p)) assert.ok(o.parameters.some((x) => x.name === 'Notion-Version' && x.in === 'header'));
  }
  assert.equal(s.components.securitySchemes.notionToken.scheme, 'bearer');
});

test('the generated kit files match the in-app kit', () => {
  const schema = JSON.parse(readFileSync(new URL('../integrations/chatgpt/notion-action.openapi.json', import.meta.url), 'utf8'));
  assert.deepEqual(schema, notionOpenApi());
  const text = readFileSync(new URL('../integrations/chatgpt/instructions.txt', import.meta.url), 'utf8');
  assert.match(text, /<Entities_DB 的 ID>/);
});

test('reference cases become valid Notion pages', () => {
  for (const c of SEED_CASES) {
    const props = caseToProps({ ...c, id: `seed:${c.id}`, title: `【参考】${c.title}`, createdAt: 1, updatedAt: 1 }, null);
    assert.equal(props.LocalID.rich_text[0].text.content, `seed:${c.id}`);
    for (const v of Object.values(props)) {
      for (const t of v.rich_text || v.title || []) assert.ok(t.text.content.length <= 2000);
    }
    assert.ok(JSON.parse(props[P.resultJson].rich_text.map((t) => t.text.content).join('')).playbook.length === 3);
  }
});
