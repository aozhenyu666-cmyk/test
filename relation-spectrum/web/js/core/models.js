// Shared enums, labels and record factories. Pure module: runs in the browser and in Node.

export const TIERS = {
  kin: {
    id: 'kin',
    label: '亲缘兜底圈',
    short: '亲缘',
    desc: '父母、至亲。不算谁付出多，只看主动关怀够不够。',
    defaultCadence: 7,
    // Weights for money / time / emotion when converting a flow into units.
    weights: { money: 0.6, time: 1.2, emotion: 1.2 },
  },
  bond: {
    id: 'bond',
    label: '情感互惠圈',
    short: '互惠',
    desc: '朋友、伴侣、熟人。看双向投入、主动性和邀约回应。',
    defaultCadence: 30,
    weights: { money: 0.8, time: 1.0, emotion: 1.0 },
  },
  deal: {
    id: 'deal',
    label: '契约博弈圈',
    short: '博弈',
    desc: '老板、公司、学校、商家、相亲对象。看投入产出、承诺兑现和底牌。',
    defaultCadence: 30,
    weights: { money: 1.2, time: 1.0, emotion: 0.3 },
  },
};

export const ENTITY_KINDS = { person: '个人', group: '群体', org: '机构' };

export const INTERACTION_TYPES = {
  chat: '聊天/通话',
  meet: '见面',
  care: '主动关怀',
  support: '情绪承接',
  help: '帮忙/出力',
  gift: '送礼/请客',
  invite: '邀约',
  conflict: '冲突',
  work: '工作/交付',
  deal: '交易/谈判',
  promise: '承诺',
  other: '其他',
};

// Types that count as "proactive care" when I initiate them (used for the kin tier).
export const CARE_TYPES = ['care', 'chat', 'meet', 'gift', 'help', 'support'];

export const INITIATORS = { me: '我', them: '对方', none: '不分' };

export const INVITE_STATUS = {
  accepted: '接受',
  declined: '婉拒',
  postponed: '改期/再说',
  no_reply: '没回',
  flaked: '临时爽约',
};

export const PROMISE_STATUS = { pending: '待兑现', kept: '已兑现', broken: '未兑现' };

export const STAGES = {
  warming: { label: '升温', tone: 'good' },
  stable: { label: '维持', tone: 'neutral' },
  cooling: { label: '隐性降温', tone: 'warn' },
  breaking: { label: '濒临破局', tone: 'danger' },
};

export const SCENARIOS = {
  friendship: '朋友',
  family: '家庭',
  workplace: '职场',
  campus: '校园',
  dating: '婚恋',
  business: '商业',
  other: '其他',
};

export const CASE_MODES = { decode: '潜台词解码', game: '博弈拆解', audit: '关系审计' };

export function uid(prefix = 'r') {
  const rand = Math.random().toString(36).slice(2, 8);
  return `${prefix}_${Date.now().toString(36)}${rand}`;
}

export function todayISO(now = new Date()) {
  const d = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
  return d.toISOString().slice(0, 10);
}

const emptyFlow = () => ({ money: 0, minutes: 0, emotion: 0 });

export function newEntity(partial = {}) {
  const tier = partial.tier || 'bond';
  const now = Date.now();
  return {
    id: uid('ent'),
    name: '',
    kind: 'person',
    tier,
    role: '',
    needs: '',
    redLines: '',
    notes: '',
    keyDates: [], // [{ label, date: 'MM-DD' | 'YYYY-MM-DD', lunar: boolean }]
    birthYear: null,
    cadenceDays: TIERS[tier].defaultCadence,
    contract: { myChips: '', theirChips: '', batna: '', bottomLine: '', hourlyValue: null },
    createdAt: now,
    updatedAt: now,
    deleted: false,
    _sync: null,
    ...partial,
  };
}

export function newInteraction(partial = {}) {
  const now = Date.now();
  return {
    id: uid('int'),
    entityId: '',
    date: todayISO(),
    type: 'chat',
    title: '',
    note: '',
    give: emptyFlow(),
    get: emptyFlow(),
    initiator: 'me',
    invite: null, // { by: 'me'|'them', status }
    promise: null, // { by: 'me'|'them', status, content }
    feeling: 0, // -2..2
    flags: [],
    createdAt: now,
    updatedAt: now,
    deleted: false,
    _sync: null,
    ...partial,
  };
}

export function newCase(partial = {}) {
  const now = Date.now();
  return {
    id: uid('case'),
    title: '',
    mode: 'decode',
    scenario: 'other',
    entityId: null,
    situation: '',
    draft: '',
    result: null,
    createdAt: now,
    updatedAt: now,
    deleted: false,
    _sync: null,
    ...partial,
  };
}

export const DEFAULT_PREFS = {
  moneyUnit: 100, // 100 元 ≈ 1 个投入单位起点
  hourlyValue: 50, // 我的时间价值（元/小时），用于契约圈 ROI
  lifeExpectancy: 82, // 估算父母剩余陪伴次数时用
  halfLifeDays: 60,
};
