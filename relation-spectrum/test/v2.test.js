import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { SCHEMAS, buildUserMessage } from '../web/js/core/prompts.js';
import { SEED_CASES } from '../web/js/data/seed-cases.js';
import { parseLocal, extractMinutes, extractMoney, splitLines } from '../web/js/core/parse-local.js';
import { calibrationStats, mismatchLines, needsOutcome } from '../web/js/core/calibration.js';
import { buildRequest, chatBody, readChatResponse, resolveJsonMode } from '../web/js/core/llm-request.js';
import { caseToProps, pageToCase } from '../web/js/core/notion-schema.js';
import { newCase } from '../web/js/core/models.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// ---------------------------------------------------------------- schema validator

function validate(value, schema, at = '$') {
  const errs = [];
  if (schema.enum && !schema.enum.includes(value)) errs.push(`${at}: ${JSON.stringify(value)} not in enum`);
  switch (schema.type) {
    case 'object': {
      if (typeof value !== 'object' || value === null || Array.isArray(value)) return [`${at}: not an object`];
      for (const k of schema.required || []) if (!(k in value)) errs.push(`${at}.${k}: missing`);
      for (const k of Object.keys(value)) {
        if (!schema.properties[k]) errs.push(`${at}.${k}: unexpected key`);
        else errs.push(...validate(value[k], schema.properties[k], `${at}.${k}`));
      }
      break;
    }
    case 'array':
      if (!Array.isArray(value)) return [`${at}: not an array`];
      value.forEach((v, i) => errs.push(...validate(v, schema.items, `${at}[${i}]`)));
      break;
    case 'string': if (typeof value !== 'string') errs.push(`${at}: not a string`); break;
    case 'integer': if (!Number.isInteger(value)) errs.push(`${at}: not an integer`); break;
    case 'number': if (typeof value !== 'number') errs.push(`${at}: not a number`); break;
    case 'boolean': if (typeof value !== 'boolean') errs.push(`${at}: not a boolean`); break;
  }
  return errs;
}

// ---------------------------------------------------------------- seed cases

test('there are 15 reference cases covering every required area', () => {
  assert.ok(SEED_CASES.length >= 12 && SEED_CASES.length <= 15, `count ${SEED_CASES.length}`);
  const scenarios = new Set(SEED_CASES.map((c) => c.scenario));
  for (const s of ['friendship', 'family', 'workplace', 'campus', 'business', 'dating']) assert.ok(scenarios.has(s), `missing ${s}`);
  assert.equal(new Set(SEED_CASES.map((c) => c.id)).size, SEED_CASES.length, 'ids must be unique');
});

for (const c of SEED_CASES) {
  test(`seed case "${c.id}" matches the ${c.mode} schema`, () => {
    assert.deepEqual(validate(c.result, SCHEMAS[c.mode]), []);
    const r = c.result;
    const p = r.subtexts.reduce((s, x) => s + x.probability, 0);
    assert.equal(p, 100, 'subtext probabilities sum to 100');
    assert.ok(r.my_biases.length >= 1, 'names at least one of my biases');
    assert.deepEqual(r.playbook.map((x) => x.level), ['gentle', 'equal', 'exit']);
    for (const pb of r.playbook) assert.ok(pb.message.length > 8, 'playbook message is copy-ready');
    if (c.mode === 'game') {
      assert.equal(r.parties.reduce((s, x) => s + x.leverage, 0), 100, 'leverage sums to 100');
      assert.equal(r.parties[0].name, '我');
      assert.ok(r.parties.slice(0, 2).every((x) => x.chips.length && x.batna), 'both sides have chips and a BATNA');
    } else {
      assert.ok(r.chips.my_investment && r.chips.their_constraints);
    }
    if (c.draft) {
      assert.notEqual(r.draft_check.risk, 'none');
      assert.ok(r.draft_check.blowup_risk > 0 && r.draft_check.their_first_reaction && r.draft_check.rewrite);
    } else {
      assert.equal(r.draft_check.risk, 'none');
    }
  });
}

// ---------------------------------------------------------------- local parser

test('local parser: chat log with a soft reject', () => {
  const p = parseLocal('我：周末有空吗，一起去吃火锅？\n阿哲：最近有点忙，改天吧\n我：好吧\n阿哲：嗯嗯');
  assert.deepEqual(p.invite, { present: true, by: 'me', status: 'postponed' });
  assert.equal(p.person_hint, '阿哲');
  assert.equal(p.initiator, 'me');
  assert.ok(p.signals.some((s) => s.kind === 'soft_reject' && s.quote.includes('改天')));
  assert.ok(p.signals.some((s) => s.kind === 'vague'));
  assert.ok(p.feeling < 0);
  assert.deepEqual(validate(stripPrivate(p), SCHEMAS.parse), []);
});

test('local parser: rant with time and money', () => {
  const p = parseLocal('昨天陪她打了一个多小时电话，她考研焦虑，我还给她点了150块的外卖');
  assert.equal(p.type, 'support');
  assert.equal(p.days_ago, 1);
  assert.equal(p.give.minutes, 60);
  assert.equal(p.give.money, 150);
  assert.ok(p.give.emotion >= 3);
});

test('local parser: promise and flake detection', () => {
  const a = parseLocal('老板：好好干，年底给你涨薪\n我：好的');
  assert.equal(a.promise.present, true);
  assert.equal(a.promise.by, 'them');
  const b = parseLocal('我：今晚七点见？\n小林：不好意思临时有事，来不了了');
  assert.equal(b.invite.status, 'flaked');
});

test('extractMinutes / extractMoney / splitLines', () => {
  assert.equal(extractMinutes('聊了半小时'), 30);
  assert.equal(extractMinutes('加班 3 小时，又开会 40 分钟'), 220);
  assert.equal(extractMoney('请吃饭 120元，打车 ¥35'), 155);
  assert.deepEqual(splitLines('我：在吗\n小王: 在').map((l) => l.who), ['me', 'them']);
});

function stripPrivate(p) {
  const { _local, _today, ...rest } = p;
  return rest;
}

// ---------------------------------------------------------------- calibration

const DAY = 86400000;
function resolved({ guess, truth, label, top = 'A', result = 'same', sentDraft = false, at = 1 }) {
  return newCase({
    mode: 'decode',
    myGuess: guess,
    createdAt: 0,
    result: { subtexts: [{ label: 'A', probability: 60 }, { label: 'B', probability: 40 }].map((s) => (s.label === top ? { ...s, probability: 70 } : { ...s, probability: 30 })) },
    outcome: { at, usedLevel: 'gentle', sentDraft, reaction: '', truth: { kind: truth, label }, result },
  });
}

test('calibration stats: my hit rate, AI hit rate and mistakes', () => {
  const cases = [
    resolved({ guess: '他讨厌我', truth: 'ai', label: 'A', at: 3 }),
    resolved({ guess: '他很忙', truth: 'my_guess', label: '他很忙', at: 2 }),
    resolved({ guess: '他在疏远我', truth: 'ai', label: 'B', at: 1, sentDraft: true, result: 'worse' }),
  ];
  const st = calibrationStats(cases);
  assert.equal(st.resolved, 3);
  assert.equal(st.myHitRate, 0.33);
  assert.equal(st.aiTopHitRate, 0.33); // only case 1 matched the top-probability label
  assert.equal(st.aiAnyHitRate, 0.67);
  assert.equal(st.mistakes.length, 2);
  assert.equal(st.mistakes[0].guess, '他讨厌我'); // newest first
  assert.equal(st.sentDraftWorse, 1);
  assert.match(st.lesson, /冲动原话|不到四成/);
});

test('mismatch lines feed the prompt, and pending cases are detected', () => {
  const cases = [resolved({ guess: '他讨厌我', truth: 'ai', label: '考试压力大' })];
  const lines = mismatchLines(cases);
  assert.equal(lines.length, 1);
  assert.match(lines[0], /曾以为「他讨厌我」，实际是「考试压力大」/);
  const msg = buildUserMessage('decode', { situation: '又没回', calibration: lines, myGuess: '他又讨厌我了' });
  assert.match(msg, /<我过往的误判/);
  assert.match(msg, /<我现在的猜测>/);

  const fresh = newCase({ mode: 'decode', result: { subtexts: [] }, createdAt: Date.now() });
  const old = newCase({ mode: 'decode', result: { subtexts: [] }, createdAt: Date.now() - 3 * DAY });
  assert.equal(needsOutcome(fresh), false);
  assert.equal(needsOutcome(old), true);
  assert.equal(needsOutcome({ ...old, mode: 'audit' }), false);
});

test('case outcome and guess round-trip through Notion', () => {
  const c = newCase({ title: 't', mode: 'decode', myGuess: '他讨厌我', result: { summary: 's', playbook: [] },
    outcome: { at: 1, usedLevel: 'gentle', sentDraft: false, reaction: '他道歉了', truth: { kind: 'ai', label: '考试压力' }, result: 'better' },
    rehearsals: [{ at: 2, level: 'gentle', transcript: [{ role: 'me', text: 'hi' }], debrief: null }] });
  const props = caseToProps(c, null);
  const page = { id: 'p', last_edited_time: new Date().toISOString(), properties: {} };
  for (const [k, v] of Object.entries(props)) {
    const copy = structuredClone(v);
    for (const key of ['rich_text', 'title']) if (copy[key]) copy[key] = copy[key].map((t) => ({ ...t, plain_text: t.text.content }));
    page.properties[k] = copy;
  }
  const back = pageToCase(page);
  assert.equal(back.myGuess, '他讨厌我');
  assert.deepEqual(back.outcome, c.outcome);
  assert.equal(back.rehearsals.length, 1);
  assert.match(props['真实结局'].rich_text[0].text.content, /用了温和档/);
});

// ---------------------------------------------------------------- role-play

test('role-play message tracks rounds and asks for a debrief on the last one', () => {
  const h1 = [{ role: 'me', text: '最近还好吗？' }];
  const m1 = buildUserMessage('roleplay', { situation: '朋友推脱', history: h1, maxRounds: 3 });
  assert.match(m1, /第 1\/3 回合/);
  assert.match(m1, /我：最近还好吗？/);
  assert.doesNotMatch(m1, /最后一回合/);
  const h3 = [...h1, { role: 'them', text: '还行' }, { role: 'me', text: 'a' }, { role: 'them', text: 'b' }, { role: 'me', text: 'c' }];
  const m3 = buildUserMessage('roleplay', { situation: '朋友推脱', history: h3, maxRounds: 3, entity: { name: '阿哲', tier: 'bond', redLines: '被追问' } });
  assert.match(m3, /最后一回合/);
  assert.match(m3, /雷区与底线：被追问/);
});

// ---------------------------------------------------------------- direct mode

test('GPT request: OpenAI official uses strict JSON schema', () => {
  const r = buildRequest('decode', { situation: 'x' }, { apiKey: 'sk-test', model: 'gpt-4o-mini' });
  assert.equal(r.url, 'https://api.openai.com/v1/chat/completions');
  assert.equal(r.headers.authorization, 'Bearer sk-test');
  assert.equal(r.body.model, 'gpt-4o-mini');
  assert.equal(r.body.response_format.type, 'json_schema');
  assert.equal(r.body.response_format.json_schema.strict, true);
  assert.deepEqual(r.body.response_format.json_schema.schema, SCHEMAS.decode);
  assert.equal(r.body.messages[0].role, 'system');
  assert.match(r.body.messages[0].content, /潜台词与软拒绝解码器/);
});

test('GPT request: compatible services use plain JSON with the schema in the prompt', () => {
  const r = buildRequest('game', { situation: 'x' }, { apiKey: 'k', baseUrl: 'https://api.deepseek.com/v1/', model: 'deepseek-chat' });
  assert.equal(r.url, 'https://api.deepseek.com/v1/chat/completions');
  assert.equal(r.body.response_format.type, 'json_object');
  assert.match(r.body.messages[0].content, /"core_exchange"/);
  assert.equal(resolveJsonMode({ baseUrl: 'https://openrouter.ai/api/v1', jsonMode: 'schema' }), 'schema');
  assert.equal(resolveJsonMode({ jsonMode: 'object' }), 'object');
  assert.throws(() => buildRequest('decode', {}, {}), /API Key/);
});

test('server and direct mode send the same body', () => {
  const input = { entity: { name: 'x', tier: 'kin' }, interactions: [] };
  assert.deepEqual(chatBody('audit', input, { model: 'm' }), buildRequest('audit', input, { apiKey: 'k', model: 'm' }).body);
});

test('readChatResponse handles refusal, truncation and text', () => {
  assert.throws(() => readChatResponse({ choices: [{ message: { refusal: 'no' } }] }), /拒绝/);
  assert.throws(() => readChatResponse({ choices: [{ finish_reason: 'length', message: { content: '{' } }] }), /截断/);
  const ok = readChatResponse({ model: 'gpt-x', choices: [{ finish_reason: 'stop', message: { content: '```json\n{"a":1}\n```' } }] });
  assert.deepEqual(ok, { result: { a: 1 }, model: 'gpt-x' });
});

test('the app only talks to GPT and Notion: no other AI vendor in the code', () => {
  const otherVendor = new RegExp(['anth', 'ropic|cla', 'ude'].join(''), 'i');
  const walk = (dir) => readdirSync(dir).flatMap((f) => {
    const p = path.join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
  const files = [...walk(path.join(root, 'web')), ...walk(path.join(root, 'server')), path.join(root, 'package.json'), path.join(root, '.env.example')]
    .filter((f) => /\.(js|json|html|example|webmanifest)$/.test(f));
  const hits = files.filter((f) => otherVendor.test(readFileSync(f, 'utf8'))).map((f) => path.relative(root, f));
  assert.deepEqual(hits, []);
});

// ---------------------------------------------------------------- PWA

test('service worker precaches every app file', () => {
  const web = path.join(root, 'web');
  const sw = readFileSync(path.join(web, 'sw.js'), 'utf8');
  const listed = new Set([...sw.matchAll(/'([^']+\.(?:js|css|html|webmanifest|png|svg))'/g)].map((m) => m[1]));
  const walk = (dir) => readdirSync(dir).flatMap((f) => {
    const p = path.join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : [path.relative(web, p).split(path.sep).join('/')];
  });
  const files = walk(web).filter((f) => f !== 'sw.js');
  const missing = files.filter((f) => !listed.has(f));
  assert.deepEqual(missing, [], 'add these to SHELL in web/sw.js');
  const manifest = JSON.parse(readFileSync(path.join(web, 'manifest.webmanifest'), 'utf8'));
  assert.ok(manifest.icons.some((i) => i.sizes === '512x512'));
});
