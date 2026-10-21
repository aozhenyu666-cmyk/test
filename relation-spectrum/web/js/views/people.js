// People: list grouped by tier, detail page with metrics + timeline + AI audit, and the edit form.

import { h, mount, go, field, segmented, emptyState, relDays, fmtDate, tempColor, meter, balanceBar, toast, ask } from '../ui/dom.js';
import { TIERS, ENTITY_KINDS, INTERACTION_TYPES, INVITE_STATUS, PROMISE_STATUS, INITIATORS, newEntity } from '../core/models.js';
import * as db from '../store/db.js';
import * as api from '../services/api.js';
import { loadWorld } from './world.js';
import { renderAudit } from './render-result.js';
import { newCase } from '../core/models.js';

// ---------------------------------------------------------------- list

export async function renderPeople(root, params) {
  const w = await loadWorld();
  let filter = params.get('tier') || 'all';

  const listBox = h('div');
  const draw = () => {
    const tiers = filter === 'all' ? Object.values(TIERS) : [TIERS[filter]];
    listBox.replaceChildren(...tiers.map((t) => {
      const ents = w.entities.filter((e) => e.tier === t.id);
      return h(`section.card.tier-${t.id}`,
        h('h3', t.label, h('span.muted', ` · ${ents.length}`)),
        h('p.small.muted', t.desc),
        ents.length
          ? h('div.people', ents.map((e) => personRow(e, w.analysis.get(e.id))))
          : h('p.muted', '暂无'),
      );
    }));
  };

  const seg = segmented({ all: '全部', kin: '亲缘', bond: '互惠', deal: '博弈' }, filter, (v) => {
    filter = v;
    draw();
  });
  draw();

  mount(root,
    h('header.page-head.row-between', h('h1', '关系'), h('button.btn.primary', { onclick: () => go(`#/person/new${filter !== 'all' ? `?tier=${filter}` : ''}`) }, '添加')),
    seg.el,
    w.entities.length ? listBox : emptyState('还没有关系对象', '点右上角“添加”。'),
  );
}

function personRow(e, a) {
  return h('button.person', { type: 'button', onclick: () => go(`#/person/${e.id}`) },
    h('div.person-main',
      h('div.person-name', e.name, e.role ? h('span.muted', ` · ${e.role}`) : null),
      h('div.small.muted', `${a.stageLabel} · 上次联系 ${relDays(a.daysSinceLast)}`),
    ),
    h('div.person-temp', { style: { color: tempColor(a.temperature) } }, a.temperature == null ? '—' : String(a.temperature)),
    a.alerts.some((x) => x.level !== 'info') ? h('span.dot.warn', { title: '有预警' }) : null,
  );
}

// ---------------------------------------------------------------- detail

export async function renderPerson(root, id) {
  const w = await loadWorld();
  const e = w.entityById.get(id);
  if (!e) {
    mount(root, emptyState('找不到这个人', null, h('button.btn', { onclick: () => go('#/people') }, '返回列表')));
    return;
  }
  const a = w.analysis.get(id);
  const its = [...w.byEntity.get(id)].sort((x, y) => (x.date < y.date ? 1 : -1));
  const t = TIERS[e.tier];

  const auditBox = h('div');
  const auditBtn = h('button.btn', { type: 'button', onclick: () => runAudit() }, 'AI 关系审计');

  async function runAudit() {
    auditBtn.disabled = true;
    auditBtn.textContent = '审计中…';
    auditBox.replaceChildren(h('p.muted.loading', '正在扫描交互流水，大约需要 10–40 秒'));
    try {
      const chrono = [...its].reverse();
      const { result } = await api.analyze('audit', { entity: e, interactions: chrono, analysis: a });
      auditBox.replaceChildren(
        renderAudit(result),
        h('button.btn.small', {
          type: 'button',
          onclick: async () => {
            await db.put('cases', newCase({ title: `审计：${e.name}`, mode: 'audit', scenario: e.tier === 'kin' ? 'family' : e.tier === 'deal' ? 'workplace' : 'friendship', entityId: e.id, situation: '关系账本审计', result }));
            toast('已存入案例库', 'good');
          },
        }, '存入案例库'),
      );
    } catch (err) {
      auditBox.replaceChildren(h('p.error', err.message));
    } finally {
      auditBtn.disabled = false;
      auditBtn.textContent = 'AI 关系审计';
    }
  }

  mount(root,
    h(`header.page-head.person-head.tier-${e.tier}`,
      h('button.back', { type: 'button', onclick: () => history.back() }, '‹ 返回'),
      h('div.row-between',
        h('div', h('h1', e.name), h('p.muted', [t.label, e.role, ENTITY_KINDS[e.kind]].filter(Boolean).join(' · '))),
        h('div.big-temp', { style: { color: tempColor(a.temperature) } }, a.temperature == null ? '—' : String(a.temperature), h('small', '温度')),
      ),
      h('div.row-wrap',
        h('span.badge', a.stageLabel),
        h('button.btn.small.primary', { onclick: () => go(`#/log?entity=${e.id}`) }, '记一笔'),
        h('button.btn.small', { onclick: () => go(`#/sandbox?entity=${e.id}`) }, '去沙盘'),
        h('button.btn.small.ghost', { onclick: () => go(`#/person/${e.id}/edit`) }, '编辑'),
      ),
    ),

    a.alerts.length
      ? h('section.card', h('h3', '预警'),
          h('div.alerts', a.alerts.map((x) => h(`div.alert.lvl-${x.level}`, h('div.alert-title', x.title), h('div.alert-advice', x.advice)))))
      : null,

    metricsCard(e, a),

    (e.needs || e.redLines || e.notes || (e.tier === 'deal' && hasContract(e)))
      ? h('section.card', h('h3', '档案'),
          h('dl.chips',
            e.needs ? [h('dt', '核心诉求'), h('dd', e.needs)] : null,
            e.redLines ? [h('dt', '雷区与底线'), h('dd', e.redLines)] : null,
            e.tier === 'deal' && e.contract?.myChips ? [h('dt', '我的筹码'), h('dd', e.contract.myChips)] : null,
            e.tier === 'deal' && e.contract?.theirChips ? [h('dt', '对方筹码'), h('dd', e.contract.theirChips)] : null,
            e.tier === 'deal' && e.contract?.batna ? [h('dt', '我的替代方案'), h('dd', e.contract.batna)] : null,
            e.tier === 'deal' && e.contract?.bottomLine ? [h('dt', '我的底线'), h('dd', e.contract.bottomLine)] : null,
            e.notes ? [h('dt', '备注'), h('dd', e.notes)] : null,
          ))
      : null,

    h('section.card', h('div.row-between', h('h3', 'AI 审计'), auditBtn),
      h('p.small.muted', '把档案、指标和最近 10 条记录交给 AI，检查你在这段关系里的做法是否健康。'),
      auditBox),

    h('section.card',
      h('h3', `交互流水 · ${its.length}`),
      its.length
        ? h('ul.timeline.full', its.map((it) => interactionItem(it)))
        : h('p.muted', '还没有记录。'),
    ),
  );
}

function hasContract(e) {
  const c = e.contract || {};
  return c.myChips || c.theirChips || c.batna || c.bottomLine;
}

function metricsCard(e, a) {
  const rows = [];
  rows.push(h('div.metric', h('span.muted', '温度'), meter(a.temperature, tempColor(a.temperature)), h('b', a.temperature ?? '—')));

  if (e.tier === 'kin') {
    rows.push(kv('上次主动关心', relDays(a.daysSinceCare)));
    rows.push(kv('期望间隔', `每 ${e.cadenceDays} 天`));
    if (a.meetingsLeft) {
      rows.push(kv('剩余见面估算', a.meetingsLeft.meetsPerYear > 0
        ? `约 ${a.meetingsLeft.estimate} 次（${a.meetingsLeft.age} 岁，每年见 ${a.meetingsLeft.meetsPerYear} 次）`
        : `过去一年没有见面记录（${a.meetingsLeft.age} 岁）`));
    }
  } else {
    rows.push(h('div.metric', h('span.muted', '投入平衡'), balanceBar(a.ledger.balance), h('b', a.ledger.balance > 0 ? `我多 ${Math.round(a.ledger.balance * 100)}%` : a.ledger.balance < 0 ? `TA多 ${Math.round(-a.ledger.balance * 100)}%` : '持平')));
    rows.push(kv('谁更主动', a.initiative.ratio == null ? '数据不足' : `我发起 ${Math.round(a.initiative.ratio * 100)}%`));
    rows.push(kv('我的邀约', a.invites.myInvites ? `${a.invites.myAccepted}/${a.invites.myInvites} 被接受${a.invites.softRejectStreak ? `，连续 ${a.invites.softRejectStreak} 次未成` : ''}` : '暂无'));
  }
  if (a.credibility.them.resolved || a.credibility.me.resolved) {
    rows.push(kv('承诺兑现', `TA ${Math.round(a.credibility.them.rate * 100)}% · 我 ${Math.round(a.credibility.me.rate * 100)}%`));
  }
  if (a.roi) {
    rows.push(kv('投入产出比', a.roi.ratio ?? '数据不足'));
    rows.push(kv('实际时薪', a.roi.effectiveHourly != null ? `${a.roi.effectiveHourly} 元/时` : '数据不足'));
  }
  rows.push(kv('趋势', { up: '近 30 天在升温', down: '近 30 天在降温', flat: '平稳' }[a.trend.dir]));
  for (const k of a.keyDates.slice(0, 3)) {
    rows.push(kv(k.label, `${k.next.days === 0 ? '今天' : `${k.next.days} 天后`}${k.lunar ? '（农历）' : ''}`));
  }
  return h('section.card', h('h3', '指标'), h('div.metrics', rows));
}

const kv = (k, v) => h('div.kv', h('span.muted', k), h('span', String(v)));

function interactionItem(it) {
  const meta = [];
  if (it.invite) meta.push(`${it.invite.by === 'me' ? '我约' : 'TA约'}：${INVITE_STATUS[it.invite.status]}`);
  if (it.promise) meta.push(`${it.promise.by === 'me' ? '我' : 'TA'}的承诺：${PROMISE_STATUS[it.promise.status]}`);
  const g = flowShort(it.give);
  const r = flowShort(it.get);
  return h('li',
    h('div.row-between',
      h('span', h('b', INTERACTION_TYPES[it.type] || it.type), it.title ? ` · ${it.title}` : ''),
      h('span.muted.small', fmtDate(it.date)),
    ),
    h('div.small.muted', `发起：${INITIATORS[it.initiator] || '-'}${g ? ` · 我给出 ${g}` : ''}${r ? ` · 我得到 ${r}` : ''}${meta.length ? ` · ${meta.join(' · ')}` : ''}`),
    it.note ? h('div.small', it.note) : null,
    h('div.row-wrap',
      h('button.link', { type: 'button', onclick: () => go(`#/log?edit=${it.id}`) }, '编辑'),
      h('button.link.danger', {
        type: 'button',
        onclick: async () => {
          if (!(await ask('删除这条记录？', { ok: '删除', danger: true }))) return;
          await db.remove('interactions', it.id);
          toast('已删除');
          go(location.hash);
        },
      }, '删除'),
    ),
  );
}

function flowShort(f) {
  if (!f) return '';
  const p = [];
  if (Number(f.money)) p.push(`${f.money}元`);
  if (Number(f.minutes)) p.push(`${f.minutes}分钟`);
  if (Number(f.emotion)) p.push(`情绪${f.emotion}`);
  return p.join('/');
}

// ---------------------------------------------------------------- edit form

export async function renderPersonEdit(root, id, params) {
  const existing = id ? await db.get('entities', id) : null;
  const e = existing ? structuredClone(existing) : newEntity({ tier: params.get('tier') || 'bond' });
  e.contract = { myChips: '', theirChips: '', batna: '', bottomLine: '', hourlyValue: null, ...(e.contract || {}) };

  const inp = (key, attrs = {}) => h('input', { value: e[key] ?? '', ...attrs });
  const ta = (value, rows = 2) => h('textarea', { rows }, value || '');

  const name = inp('name', { placeholder: '名字或代号，如“妈妈”“A 同学”“现在的公司”', required: true });
  const role = inp('role', { placeholder: '父亲 / 大学室友 / 直属领导 / 相亲对象' });
  const kind = h('select', Object.entries(ENTITY_KINDS).map(([k, v]) => h('option', { value: k, selected: k === e.kind }, v)));
  const cadence = h('input', { type: 'number', min: 1, value: e.cadenceDays, inputmode: 'numeric' });
  const birthYear = h('input', { type: 'number', min: 1900, max: 2100, value: e.birthYear ?? '', placeholder: '如 1968', inputmode: 'numeric' });
  const needs = ta(e.needs);
  const redLines = ta(e.redLines);
  const notes = ta(e.notes, 3);
  const myChips = ta(e.contract.myChips);
  const theirChips = ta(e.contract.theirChips);
  const batna = ta(e.contract.batna);
  const bottomLine = ta(e.contract.bottomLine);
  const hourly = h('input', { type: 'number', min: 0, value: e.contract.hourlyValue ?? '', placeholder: '留空则用设置里的默认值', inputmode: 'decimal' });

  const datesBox = h('div.stack');
  const keyDates = [...(e.keyDates || [])];
  const drawDates = () => {
    datesBox.replaceChildren(
      ...keyDates.map((k, i) =>
        h('div.date-row',
          h('input', { value: k.label, placeholder: '生日', oninput: (ev) => (k.label = ev.target.value) }),
          h('input', { value: k.date, placeholder: 'MM-DD', inputmode: 'numeric', oninput: (ev) => (k.date = ev.target.value.trim()) }),
          h('label.inline', h('input', { type: 'checkbox', checked: k.lunar, onchange: (ev) => (k.lunar = ev.target.checked) }), '农历'),
          h('button.link.danger', { type: 'button', onclick: () => { keyDates.splice(i, 1); drawDates(); } }, '删'),
        )),
      h('button.btn.small', { type: 'button', onclick: () => { keyDates.push({ label: '生日', date: '', lunar: false }); drawDates(); } }, '+ 关键日期'),
    );
  };
  drawDates();

  const kinBox = h('div', field('出生年份', birthYear, '用来估算剩余陪伴次数，可不填'));
  const dealBox = h('div',
    field('我的筹码', myChips, '技能、业绩、可替代性、时间、信息'),
    field('对方的筹码', theirChips),
    field('我的替代方案（BATNA）', batna, '谈不拢时，我最好的退路是什么'),
    field('我的底线', bottomLine),
    field('我的时间价值（元/小时）', hourly),
  );
  const tierSeg = segmented({ kin: TIERS.kin.label, bond: TIERS.bond.label, deal: TIERS.deal.label }, e.tier, (v) => {
    if (!existing || Number(cadence.value) === TIERS[e.tier].defaultCadence) cadence.value = TIERS[v].defaultCadence;
    e.tier = v;
    syncTier();
  });
  const tierHint = h('p.small.muted');
  const syncTier = () => {
    kinBox.hidden = e.tier !== 'kin';
    dealBox.hidden = e.tier !== 'deal';
    tierHint.textContent = TIERS[e.tier].desc;
  };
  syncTier();

  const save = async (ev) => {
    ev.preventDefault();
    if (!name.value.trim()) return toast('请填写名字', 'warn');
    const bad = keyDates.find((k) => k.date && !/^(\d{4}-)?\d{2}-\d{2}$/.test(k.date));
    if (bad) return toast(`日期格式应为 MM-DD，如 05-12（${bad.label}）`, 'warn');
    const rec = {
      ...e,
      name: name.value.trim(),
      role: role.value.trim(),
      kind: kind.value,
      tier: tierSeg.value,
      cadenceDays: Math.max(1, Number(cadence.value) || TIERS[tierSeg.value].defaultCadence),
      birthYear: birthYear.value ? Number(birthYear.value) : null,
      needs: needs.value.trim(),
      redLines: redLines.value.trim(),
      notes: notes.value.trim(),
      keyDates: keyDates.filter((k) => k.date),
      contract: {
        myChips: myChips.value.trim(),
        theirChips: theirChips.value.trim(),
        batna: batna.value.trim(),
        bottomLine: bottomLine.value.trim(),
        hourlyValue: hourly.value ? Number(hourly.value) : null,
      },
    };
    await db.put('entities', rec);
    toast('已保存', 'good');
    go(`#/person/${rec.id}`);
  };

  mount(root,
    h('header.page-head', h('button.back', { type: 'button', onclick: () => history.back() }, '‹ 返回'), h('h1', existing ? `编辑 ${e.name}` : '添加关系对象')),
    h('form.card.form', { onsubmit: save },
      field('名字', name),
      h('div.field', h('span.field-label', '圈层'), tierSeg.el, tierHint),
      field('角色', role),
      field('类型', kind),
      field('期望联系间隔（天）', cadence, '超过这个天数没联系会提醒你'),
      h('div.field', h('span.field-label', '关键日期'), datesBox, h('span.field-hint', '生日、纪念日等，格式 MM-DD')),
      kinBox,
      field('TA 的核心诉求', needs, 'TA 最在意什么'),
      field('雷区与底线', redLines, '哪些话题或行为会触怒 TA'),
      dealBox,
      field('备注', notes),
      h('div.row-wrap',
        h('button.btn.primary', { type: 'submit' }, '保存'),
        existing ? h('button.btn.danger', {
          type: 'button',
          onclick: async () => {
            if (!(await ask(`删除 ${e.name} 及其所有记录？`, { ok: '删除', danger: true }))) return;
            for (const it of await db.interactionsOf(e.id)) await db.remove('interactions', it.id);
            await db.remove('entities', e.id);
            toast('已删除');
            go('#/people');
          },
        }, '删除') : null,
      ),
    ),
  );
}
