// Log an interaction, with a live preview of how it moves the relationship metrics.

import { h, mount, go, field, segmented, emptyState, toast, tempColor } from '../ui/dom.js';
import { INTERACTION_TYPES, INITIATORS, INVITE_STATUS, PROMISE_STATUS, TIERS, newInteraction, todayISO } from '../core/models.js';
import { previewDelta } from '../core/scoring.js';
import * as db from '../store/db.js';
import { loadWorld } from './world.js';

const QUICK = [
  { label: '陪聊/通话', type: 'chat', give: { minutes: 60, emotion: 3 } },
  { label: '请客吃饭', type: 'gift', give: { money: 150, minutes: 90, emotion: 1 }, get: { minutes: 90, emotion: 1 } },
  { label: '给父母打电话', type: 'care', give: { minutes: 20, emotion: 2 }, get: { emotion: 2 } },
  { label: '我约 TA 被推脱', type: 'invite', invite: { by: 'me', status: 'postponed' }, feeling: -1 },
  { label: 'TA 帮了我', type: 'help', initiator: 'them', get: { minutes: 60, emotion: 2 }, feeling: 1 },
  { label: '加班', type: 'work', give: { minutes: 180, emotion: 2 }, feeling: -1 },
];

export async function renderLog(root, params) {
  const w = await loadWorld();
  if (!w.entities.length) {
    mount(root, emptyState('先添加一个关系对象', '记录需要挂在某个人身上。', h('button.btn.primary', { onclick: () => go('#/person/new') }, '添加')));
    return;
  }

  const editId = params.get('edit');
  const existing = editId ? await db.get('interactions', editId) : null;
  const it = existing
    ? structuredClone(existing)
    : newInteraction({ entityId: params.get('entity') || w.entities[0].id });

  // ---- inputs
  const entitySel = h('select', { onchange: () => { it.entityId = entitySel.value; refresh(); } },
    Object.values(TIERS).map((t) =>
      h('optgroup', { label: t.label }, w.entities.filter((e) => e.tier === t.id).map((e) =>
        h('option', { value: e.id, selected: e.id === it.entityId }, e.name)))));
  const date = h('input', { type: 'date', value: it.date, max: todayISO(), onchange: () => { it.date = date.value || todayISO(); refresh(); } });
  const title = h('input', { value: it.title, placeholder: '一句话描述，如“陪 TA 聊考研焦虑”', oninput: () => (it.title = title.value) });
  const note = h('textarea', { rows: 2, placeholder: '细节、对方原话、你的感受', oninput: () => (it.note = note.value) }, it.note);
  const typeSel = h('select', { onchange: () => { it.type = typeSel.value; refresh(); } },
    Object.entries(INTERACTION_TYPES).map(([k, v]) => h('option', { value: k, selected: k === it.type }, v)));
  const initiator = segmented(INITIATORS, it.initiator, (v) => { it.initiator = v; refresh(); });

  const flowInputs = (side) => {
    const f = it[side];
    const num = (key, ph) => h('input', {
      type: 'number', min: 0, inputmode: 'decimal', placeholder: ph, value: f[key] || '',
      oninput: (ev) => { f[key] = Number(ev.target.value) || 0; refresh(); },
    });
    const emo = h('input', { type: 'range', min: 0, max: 5, step: 1, value: f.emotion || 0 });
    const emoLabel = h('span.emo-val', String(f.emotion || 0));
    emo.oninput = () => { f.emotion = Number(emo.value); emoLabel.textContent = emo.value; refresh(); };
    return h('div.flow',
      h('div.flow-row', field('金额（元）', num('money', '0')), field('时间（分钟）', num('minutes', '0'))),
      h('div.field', h('span.field-label', '情绪投入 0–5 ', emoLabel), emo),
    );
  };

  const inviteOn = h('input', { type: 'checkbox', checked: Boolean(it.invite) });
  const inviteBy = segmented({ me: '我约 TA', them: 'TA 约我' }, it.invite?.by || 'me', (v) => { if (it.invite) it.invite.by = v; refresh(); });
  const inviteStatus = h('select', { onchange: () => { if (it.invite) it.invite.status = inviteStatus.value; refresh(); } },
    Object.entries(INVITE_STATUS).map(([k, v]) => h('option', { value: k, selected: k === (it.invite?.status || 'accepted') }, v)));
  const inviteBox = h('div.sub', inviteBy.el, field('结果', inviteStatus));
  inviteOn.onchange = () => {
    it.invite = inviteOn.checked ? { by: inviteBy.value, status: inviteStatus.value } : null;
    inviteBox.hidden = !inviteOn.checked;
    refresh();
  };
  inviteBox.hidden = !it.invite;

  const promiseOn = h('input', { type: 'checkbox', checked: Boolean(it.promise) });
  const promiseBy = segmented({ me: '我承诺', them: 'TA 承诺' }, it.promise?.by || 'them', (v) => { if (it.promise) it.promise.by = v; refresh(); });
  const promiseStatus = h('select', { onchange: () => { if (it.promise) it.promise.status = promiseStatus.value; refresh(); } },
    Object.entries(PROMISE_STATUS).map(([k, v]) => h('option', { value: k, selected: k === (it.promise?.status || 'pending') }, v)));
  const promiseContent = h('input', { value: it.promise?.content || '', placeholder: '承诺了什么，如“年底涨薪”', oninput: () => { if (it.promise) it.promise.content = promiseContent.value; } });
  const promiseBox = h('div.sub', promiseBy.el, field('状态', promiseStatus), field('内容', promiseContent));
  promiseOn.onchange = () => {
    it.promise = promiseOn.checked ? { by: promiseBy.value, status: promiseStatus.value, content: promiseContent.value } : null;
    promiseBox.hidden = !promiseOn.checked;
    refresh();
  };
  promiseBox.hidden = !it.promise;

  const feeling = segmented([['-2', '很差'], ['-1', '不太好'], ['0', '一般'], ['1', '不错'], ['2', '很好']], String(it.feeling || 0), (v) => { it.feeling = Number(v); refresh(); });

  const giveBox = h('div');
  const getBox = h('div');
  const drawFlows = () => {
    giveBox.replaceChildren(flowInputs('give'));
    getBox.replaceChildren(flowInputs('get'));
  };
  drawFlows();

  const quick = h('div.chips-row', QUICK.map((q) =>
    h('button.chip', {
      type: 'button',
      onclick: () => {
        it.type = q.type;
        it.title = it.title || q.label;
        it.give = { money: 0, minutes: 0, emotion: 0, ...(q.give || {}) };
        it.get = { money: 0, minutes: 0, emotion: 0, ...(q.get || {}) };
        it.initiator = q.initiator || 'me';
        it.invite = q.invite ? { ...q.invite } : null;
        it.feeling = q.feeling ?? 0;
        typeSel.value = it.type;
        title.value = it.title;
        initiator.value = it.initiator;
        inviteOn.checked = Boolean(it.invite);
        inviteBox.hidden = !it.invite;
        if (it.invite) { inviteBy.value = it.invite.by; inviteStatus.value = it.invite.status; }
        feeling.value = String(it.feeling);
        drawFlows();
        refresh();
      },
    }, q.label)));

  // ---- live preview
  const preview = h('section.card.preview');
  function refresh() {
    const ent = w.entityById.get(it.entityId);
    if (!ent) return;
    const others = (w.byEntity.get(ent.id) || []).filter((x) => x.id !== it.id);
    const d = previewDelta(ent, others, { ...it, date: it.date || todayISO() }, Date.now(), w.prefs);
    const arrow = (n, unit = '') => (n == null ? '' : n > 0 ? ` ↑${n}${unit}` : n < 0 ? ` ↓${-n}${unit}` : ' 不变');
    preview.replaceChildren(
      h('h3', '记录后的变化'),
      h('div.preview-grid',
        h('div', h('span.muted', '温度'),
          h('b', { style: { color: tempColor(d.after.temperature) } }, d.after.temperature ?? '—'),
          h('span.small', arrow(d.tempDelta))),
        h('div', h('span.muted', '阶段'), h('b', d.after.stageLabel)),
        ent.tier !== 'kin'
          ? h('div', h('span.muted', '投入平衡'), h('b', String(d.after.ledger.balance)), h('span.small', arrow(d.balanceDelta)))
          : h('div', h('span.muted', '上次关心'), h('b', d.after.daysSinceCare == null ? '无' : `${d.after.daysSinceCare} 天前`)),
      ),
      d.newAlerts.length
        ? h('div.alerts', d.newAlerts.map((a) => h(`div.alert.lvl-${a.level}`, h('div.alert-title', a.title), h('div.alert-advice', a.advice))))
        : null,
    );
  }
  refresh();

  const save = async (ev) => {
    ev.preventDefault();
    const ent = w.entityById.get(it.entityId);
    const others = (w.byEntity.get(ent.id) || []).filter((x) => x.id !== it.id);
    const d = previewDelta(ent, others, it, Date.now(), w.prefs);
    it.flags = d.after.alerts.filter((a) => a.level !== 'info').map((a) => a.code);
    await db.put('interactions', it);
    await db.put('entities', ent); // bump so the Notion health score re-syncs
    toast('已记录', 'good');
    go(`#/person/${ent.id}`);
  };

  mount(root,
    h('header.page-head', h('h1', existing ? '编辑记录' : '记一笔'), h('p.muted', '记下发生了什么。不用精确，大概就行。')),
    existing ? null : h('section.card', h('h3', '快捷模板'), quick),
    h('form.card.form', { onsubmit: save },
      field('和谁', entitySel),
      h('div.flow-row', field('日期', date), field('类型', typeSel)),
      field('事件', title),
      h('div.field', h('span.field-label', '谁发起的'), initiator.el),
      h('fieldset', h('legend', '我给出的'), giveBox),
      h('fieldset', h('legend', '我得到的'), getBox),
      h('label.inline', inviteOn, '这是一次邀约'), inviteBox,
      h('label.inline', promiseOn, '涉及承诺'), promiseBox,
      h('div.field', h('span.field-label', '这次的感受'), feeling.el),
      field('备注', note),
      preview,
      h('button.btn.primary.block', { type: 'submit' }, '保存'),
    ),
  );
}
