// Notion database definitions and record <-> page mappers.
// Pure module, shared by the browser sync client and the Node init script.
// Uses Notion API version 2022-06-28 (database_id based endpoints).

import {
  TIERS, ENTITY_KINDS, INTERACTION_TYPES, INITIATORS, INVITE_STATUS, PROMISE_STATUS,
  STAGES, SCENARIOS, CASE_MODES,
} from './models.js';

export const NOTION_VERSION = '2022-06-28';

export const DB_TITLES = {
  entities: 'Entities_DB · 关系对象',
  interactions: 'Interactions_DB · 交互流水',
  cases: 'Sandbox_Cases_DB · 博弈案例',
};

// ---------- helpers ----------

const COLORS = ['orange', 'green', 'blue', 'purple', 'pink', 'yellow', 'red', 'gray', 'brown', 'default'];

const labelsOf = (map) => Object.values(map).map((v) => (typeof v === 'string' ? v : v.label));
const options = (map) => ({ options: labelsOf(map).map((name, i) => ({ name, color: COLORS[i % COLORS.length] })) });
const keyOfLabel = (map, label, fallback) => {
  for (const [k, v] of Object.entries(map)) if ((typeof v === 'string' ? v : v.label) === label) return k;
  return fallback;
};
const labelOfKey = (map, key) => {
  const v = map[key];
  return v == null ? null : typeof v === 'string' ? v : v.label;
};

/** Notion caps a text object at 2000 chars and a rich_text array at 100 objects. */
export function rt(text) {
  const s = String(text ?? '');
  const chunks = [];
  for (let i = 0; i < s.length && chunks.length < 100; i += 2000) chunks.push(s.slice(i, i + 2000));
  return { rich_text: chunks.map((content) => ({ type: 'text', text: { content } })) };
}
const title = (text) => ({ title: [{ type: 'text', text: { content: String(text || '未命名').slice(0, 2000) } }] });
const num = (n) => ({ number: n === '' || n == null || Number.isNaN(Number(n)) ? null : Number(n) });
const sel = (name) => ({ select: name ? { name } : null });
const date = (iso) => ({ date: iso ? { start: iso } : null });
const tsDate = (ts) => date(ts ? new Date(ts).toISOString() : null);

const readText = (p) => (p?.rich_text || p?.title || []).map((t) => t.plain_text ?? t.text?.content ?? '').join('');
const readNum = (p) => (p?.number ?? null);
const readSel = (p) => p?.select?.name ?? null;
const readDate = (p) => p?.date?.start ?? null;
const readRel = (p) => p?.relation?.[0]?.id ?? null;
const readMulti = (p) => (p?.multi_select || []).map((o) => o.name);

// ---------- property names ----------

export const P = {
  localId: 'LocalID',
  updatedAt: '本地更新时间',
  // entities
  name: '名称',
  tier: '圈层',
  kind: '类型',
  role: '角色',
  needs: '核心诉求',
  redLines: '雷区与底线',
  keyDates: '关键日期',
  nextKeyDate: '最近的关键日期',
  health: '关系温度',
  stage: '阶段',
  cadence: '期望联系间隔(天)',
  birthYear: '出生年份',
  myChips: '我的筹码',
  theirChips: '对方筹码',
  batna: '替代方案BATNA',
  bottomLine: '我的底线',
  hourlyValue: '时间价值(元/时)',
  notes: '备注',
  // interactions
  title: '事件',
  entity: '关系对象',
  date: '日期',
  type: '交互类型',
  giveMoney: '我给出-金额',
  giveMinutes: '我给出-分钟',
  giveEmotion: '我给出-情绪',
  getMoney: '我得到-金额',
  getMinutes: '我得到-分钟',
  getEmotion: '我得到-情绪',
  initiator: '发起方',
  inviteBy: '邀约方',
  inviteStatus: '邀约结果',
  promiseBy: '承诺方',
  promiseStatus: '承诺状态',
  promiseContent: '承诺内容',
  feeling: '感受',
  flags: 'AI预警',
  note: '备注',
  // cases
  caseTitle: '案例',
  mode: '模式',
  scenario: '场景',
  situation: '情况描述',
  draft: '我想回的话',
  summary: '结论',
  playbook: '三档话术',
  resultJson: '完整结果JSON',
  createdAt: '创建时间',
};

const WHO = { me: '我', them: '对方' };

// ---------- create-database bodies ----------

export function entitiesDbBody(parentPageId) {
  return {
    parent: { type: 'page_id', page_id: parentPageId },
    title: [{ type: 'text', text: { content: DB_TITLES.entities } }],
    properties: {
      [P.name]: { title: {} },
      [P.tier]: { select: options(TIERS) },
      [P.kind]: { select: options(ENTITY_KINDS) },
      [P.role]: { rich_text: {} },
      [P.needs]: { rich_text: {} },
      [P.redLines]: { rich_text: {} },
      [P.keyDates]: { rich_text: {} },
      [P.nextKeyDate]: { date: {} },
      [P.health]: { number: { format: 'number' } },
      [P.stage]: { select: options(STAGES) },
      [P.cadence]: { number: { format: 'number' } },
      [P.birthYear]: { number: { format: 'number' } },
      [P.myChips]: { rich_text: {} },
      [P.theirChips]: { rich_text: {} },
      [P.batna]: { rich_text: {} },
      [P.bottomLine]: { rich_text: {} },
      [P.hourlyValue]: { number: { format: 'number' } },
      [P.notes]: { rich_text: {} },
      [P.localId]: { rich_text: {} },
      [P.updatedAt]: { date: {} },
    },
  };
}

export function interactionsDbBody(parentPageId, entitiesDbId) {
  return {
    parent: { type: 'page_id', page_id: parentPageId },
    title: [{ type: 'text', text: { content: DB_TITLES.interactions } }],
    properties: {
      [P.title]: { title: {} },
      [P.entity]: { relation: { database_id: entitiesDbId, single_property: {} } },
      [P.date]: { date: {} },
      [P.type]: { select: options(INTERACTION_TYPES) },
      [P.giveMoney]: { number: { format: 'number' } },
      [P.giveMinutes]: { number: { format: 'number' } },
      [P.giveEmotion]: { number: { format: 'number' } },
      [P.getMoney]: { number: { format: 'number' } },
      [P.getMinutes]: { number: { format: 'number' } },
      [P.getEmotion]: { number: { format: 'number' } },
      [P.initiator]: { select: options(INITIATORS) },
      [P.inviteBy]: { select: options(WHO) },
      [P.inviteStatus]: { select: options(INVITE_STATUS) },
      [P.promiseBy]: { select: options(WHO) },
      [P.promiseStatus]: { select: options(PROMISE_STATUS) },
      [P.promiseContent]: { rich_text: {} },
      [P.feeling]: { number: { format: 'number' } },
      [P.flags]: { multi_select: { options: [] } },
      [P.note]: { rich_text: {} },
      [P.localId]: { rich_text: {} },
      [P.updatedAt]: { date: {} },
    },
  };
}

export function casesDbBody(parentPageId, entitiesDbId) {
  return {
    parent: { type: 'page_id', page_id: parentPageId },
    title: [{ type: 'text', text: { content: DB_TITLES.cases } }],
    properties: {
      [P.caseTitle]: { title: {} },
      [P.mode]: { select: options(CASE_MODES) },
      [P.scenario]: { select: options(SCENARIOS) },
      [P.entity]: { relation: { database_id: entitiesDbId, single_property: {} } },
      [P.situation]: { rich_text: {} },
      [P.draft]: { rich_text: {} },
      [P.summary]: { rich_text: {} },
      [P.playbook]: { rich_text: {} },
      [P.resultJson]: { rich_text: {} },
      [P.createdAt]: { date: {} },
      [P.localId]: { rich_text: {} },
      [P.updatedAt]: { date: {} },
    },
  };
}

// ---------- record -> Notion properties ----------

const keyDatesText = (list) =>
  (list || []).map((k) => `${k.label}|${k.date}${k.lunar ? '|农历' : ''}`).join('\n');

const parseKeyDates = (text) =>
  String(text || '')
    .split(/\n|；|;/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [label, d, lunar] = line.split('|').map((s) => s.trim());
      return { label: label || '纪念日', date: d || '', lunar: lunar === '农历' };
    })
    .filter((k) => /^(\d{4}-)?\d{2}-\d{2}$/.test(k.date));

/** analysis is optional; when given, its temperature/stage/next key date are written too. */
export function entityToProps(e, analysis) {
  const c = e.contract || {};
  const nextKey = analysis?.keyDates?.[0]?.next?.ts;
  return {
    [P.name]: title(e.name),
    [P.tier]: sel(labelOfKey(TIERS, e.tier)),
    [P.kind]: sel(labelOfKey(ENTITY_KINDS, e.kind)),
    [P.role]: rt(e.role),
    [P.needs]: rt(e.needs),
    [P.redLines]: rt(e.redLines),
    [P.keyDates]: rt(keyDatesText(e.keyDates)),
    [P.nextKeyDate]: date(nextKey ? localISODate(nextKey) : null),
    [P.health]: num(analysis?.temperature),
    [P.stage]: sel(analysis?.stage ? labelOfKey(STAGES, analysis.stage) : null),
    [P.cadence]: num(e.cadenceDays),
    [P.birthYear]: num(e.birthYear),
    [P.myChips]: rt(c.myChips),
    [P.theirChips]: rt(c.theirChips),
    [P.batna]: rt(c.batna),
    [P.bottomLine]: rt(c.bottomLine),
    [P.hourlyValue]: num(c.hourlyValue),
    [P.notes]: rt(e.notes),
    [P.localId]: rt(e.id),
    [P.updatedAt]: tsDate(e.updatedAt),
  };
}

export function interactionToProps(it, entityPageId) {
  return {
    [P.title]: title(it.title || INTERACTION_TYPES[it.type] || '交互'),
    [P.entity]: { relation: entityPageId ? [{ id: entityPageId }] : [] },
    [P.date]: date(it.date),
    [P.type]: sel(labelOfKey(INTERACTION_TYPES, it.type)),
    [P.giveMoney]: num(it.give?.money),
    [P.giveMinutes]: num(it.give?.minutes),
    [P.giveEmotion]: num(it.give?.emotion),
    [P.getMoney]: num(it.get?.money),
    [P.getMinutes]: num(it.get?.minutes),
    [P.getEmotion]: num(it.get?.emotion),
    [P.initiator]: sel(labelOfKey(INITIATORS, it.initiator)),
    [P.inviteBy]: sel(it.invite ? WHO[it.invite.by] : null),
    [P.inviteStatus]: sel(it.invite ? labelOfKey(INVITE_STATUS, it.invite.status) : null),
    [P.promiseBy]: sel(it.promise ? WHO[it.promise.by] : null),
    [P.promiseStatus]: sel(it.promise ? labelOfKey(PROMISE_STATUS, it.promise.status) : null),
    [P.promiseContent]: rt(it.promise?.content),
    [P.feeling]: num(it.feeling),
    [P.flags]: { multi_select: (it.flags || []).slice(0, 20).map((name) => ({ name: String(name).slice(0, 100).replace(/,/g, '，') })) },
    [P.note]: rt(it.note),
    [P.localId]: rt(it.id),
    [P.updatedAt]: tsDate(it.updatedAt),
  };
}

export function caseToProps(c, entityPageId) {
  const r = c.result || {};
  const playbook = (r.playbook || [])
    .map((p) => `【${p.name || p.level}】${p.message}`)
    .join('\n\n');
  return {
    [P.caseTitle]: title(c.title),
    [P.mode]: sel(labelOfKey(CASE_MODES, c.mode)),
    [P.scenario]: sel(labelOfKey(SCENARIOS, c.scenario)),
    [P.entity]: { relation: entityPageId ? [{ id: entityPageId }] : [] },
    [P.situation]: rt(c.situation),
    [P.draft]: rt(c.draft),
    [P.summary]: rt(r.summary || r.verdict || ''),
    [P.playbook]: rt(playbook),
    [P.resultJson]: rt(c.result ? JSON.stringify(c.result) : ''),
    [P.createdAt]: tsDate(c.createdAt),
    [P.localId]: rt(c.id),
    [P.updatedAt]: tsDate(c.updatedAt),
  };
}

// ---------- Notion page -> record fields ----------
// Returned objects hold only the synced fields; the sync layer merges them into local records.

export function pageToEntity(page) {
  const p = page.properties || {};
  return {
    id: readText(p[P.localId]) || null,
    name: readText(p[P.name]),
    tier: keyOfLabel(TIERS, readSel(p[P.tier]), 'bond'),
    kind: keyOfLabel(ENTITY_KINDS, readSel(p[P.kind]), 'person'),
    role: readText(p[P.role]),
    needs: readText(p[P.needs]),
    redLines: readText(p[P.redLines]),
    keyDates: parseKeyDates(readText(p[P.keyDates])),
    cadenceDays: readNum(p[P.cadence]),
    birthYear: readNum(p[P.birthYear]),
    contract: {
      myChips: readText(p[P.myChips]),
      theirChips: readText(p[P.theirChips]),
      batna: readText(p[P.batna]),
      bottomLine: readText(p[P.bottomLine]),
      hourlyValue: readNum(p[P.hourlyValue]),
    },
    notes: readText(p[P.notes]),
  };
}

export function pageToInteraction(page) {
  const p = page.properties || {};
  const inviteStatus = keyOfLabel(INVITE_STATUS, readSel(p[P.inviteStatus]), null);
  const inviteBy = keyOfLabel(WHO, readSel(p[P.inviteBy]), null);
  const promiseStatus = keyOfLabel(PROMISE_STATUS, readSel(p[P.promiseStatus]), null);
  const promiseBy = keyOfLabel(WHO, readSel(p[P.promiseBy]), null);
  return {
    id: readText(p[P.localId]) || null,
    entityPageId: readRel(p[P.entity]),
    title: readText(p[P.title]),
    date: (readDate(p[P.date]) || '').slice(0, 10) || null,
    type: keyOfLabel(INTERACTION_TYPES, readSel(p[P.type]), 'other'),
    give: { money: readNum(p[P.giveMoney]) || 0, minutes: readNum(p[P.giveMinutes]) || 0, emotion: readNum(p[P.giveEmotion]) || 0 },
    get: { money: readNum(p[P.getMoney]) || 0, minutes: readNum(p[P.getMinutes]) || 0, emotion: readNum(p[P.getEmotion]) || 0 },
    initiator: keyOfLabel(INITIATORS, readSel(p[P.initiator]), 'none'),
    invite: inviteStatus && inviteBy ? { by: inviteBy, status: inviteStatus } : null,
    promise: promiseStatus && promiseBy ? { by: promiseBy, status: promiseStatus, content: readText(p[P.promiseContent]) } : null,
    feeling: readNum(p[P.feeling]) || 0,
    flags: readMulti(p[P.flags]),
    note: readText(p[P.note]),
  };
}

export function pageToCase(page) {
  const p = page.properties || {};
  let result = null;
  try {
    const raw = readText(p[P.resultJson]);
    result = raw ? JSON.parse(raw) : null;
  } catch {
    result = null;
  }
  return {
    id: readText(p[P.localId]) || null,
    entityPageId: readRel(p[P.entity]),
    title: readText(p[P.caseTitle]),
    mode: keyOfLabel(CASE_MODES, readSel(p[P.mode]), 'decode'),
    scenario: keyOfLabel(SCENARIOS, readSel(p[P.scenario]), 'other'),
    situation: readText(p[P.situation]),
    draft: readText(p[P.draft]),
    result,
  };
}

function localISODate(ts) {
  const d = new Date(ts);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
