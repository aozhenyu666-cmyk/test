// Game sandbox: describe a stuck situation, get subtext odds, chips, and three ready-to-send replies.

import { h, mount, field, segmented, toast, go } from '../ui/dom.js';
import { TIERS, SCENARIOS, newCase } from '../core/models.js';
import * as db from '../store/db.js';
import * as api from '../services/api.js';
import { loadWorld } from './world.js';
import { renderResult } from './render-result.js';

const MODES = { decode: '潜台词解码', game: '多方博弈' };

const EXAMPLES = {
  decode: '我最好的朋友在考研，之前焦虑时打电话给我，我陪他聊了一个多小时。考完后我约他出去玩，到了他学校附近，他说有事来不了。之后又约了两次，他都说“最近忙，再说吧”，也不说忙什么。',
  game: '我在一家小公司做了两年，老板一直说“明年公司上市就给你期权”，但这两年工资没涨过，每周加班十几个小时。最近有猎头联系我，薪资高 30%，但我对新公司不了解。',
};

export async function renderSandbox(root, params) {
  const w = await loadWorld();
  let mode = params.get('mode') === 'game' ? 'game' : 'decode';

  const entitySel = h('select',
    h('option', { value: '' }, '不关联（只分析这段描述）'),
    Object.values(TIERS).map((t) =>
      h('optgroup', { label: t.label }, w.entities.filter((e) => e.tier === t.id).map((e) =>
        h('option', { value: e.id, selected: e.id === params.get('entity') }, e.name)))));

  const situation = h('textarea', { rows: 6, placeholder: EXAMPLES[mode] });
  const draft = h('textarea', { rows: 3, placeholder: '比如：“算了，你爱咋咋吧。”——先放这里，让 AI 帮你看看后果' });
  const draftField = field('我想回的话（可选 · 冷静阀）', draft, '想发火的时候，先写在这里，不要直接发出去');
  const scenarioSel = h('select', Object.entries(SCENARIOS).map(([k, v]) => h('option', { value: k, selected: k === 'other' }, k === 'other' ? '自动判断' : v)));

  const modeSeg = segmented(MODES, mode, (v) => {
    mode = v;
    situation.placeholder = EXAMPLES[mode];
    draftField.hidden = mode !== 'decode';
  });
  draftField.hidden = mode !== 'decode';

  const out = h('div');
  const runBtn = h('button.btn.primary.block', { type: 'submit' }, '开始分析');

  const run = async (ev) => {
    ev.preventDefault();
    const text = situation.value.trim();
    if (text.length < 10) return toast('多写几句：发生了什么、对方怎么说的、你怎么想的', 'warn');
    const entity = entitySel.value ? w.entityById.get(entitySel.value) : null;
    const input = { situation: text, draft: mode === 'decode' ? draft.value : '' };
    if (entity) {
      input.entity = entity;
      input.interactions = [...w.byEntity.get(entity.id)].sort((a, b) => (a.date < b.date ? -1 : 1));
      input.analysis = w.analysis.get(entity.id);
    }

    runBtn.disabled = true;
    runBtn.textContent = '分析中…';
    out.replaceChildren(h('p.muted.loading', '正在拆解，大约需要 10–40 秒'));
    try {
      const { result, model } = await api.analyze(mode, input);
      const picked = scenarioSel.value;
      const scenario = picked !== 'other' ? picked
        : mode === 'game' && result.scenario ? result.scenario
        : entity ? scenarioFor(entity) : 'other';
      const record = newCase({
        title: text.slice(0, 24) + (text.length > 24 ? '…' : ''),
        mode,
        scenario,
        entityId: entity?.id || null,
        situation: text,
        draft: input.draft || '',
        result,
      });
      out.replaceChildren(
        renderResult(mode, result),
        h('div.row-wrap.result-actions',
          h('button.btn', {
            type: 'button',
            onclick: async (e2) => {
              await db.put('cases', record);
              e2.target.disabled = true;
              e2.target.textContent = '已存入案例库';
              toast('已保存', 'good');
            },
          }, '存入案例库'),
          entity ? h('button.btn.ghost', { type: 'button', onclick: () => go(`#/log?entity=${entity.id}`) }, '把这件事记一笔') : null,
          h('span.small.muted', `由 ${model} 生成`),
        ),
      );
      out.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (err) {
      out.replaceChildren(h('div.card.error',
        h('p', err.message),
        err.status === 401 || /Key|key|令牌|口令/.test(err.message)
          ? h('button.btn.small', { type: 'button', onclick: () => go('#/settings') }, '去设置')
          : null));
    } finally {
      runBtn.disabled = false;
      runBtn.textContent = '开始分析';
    }
  };

  mount(root,
    h('header.page-head', h('h1', '博弈沙盘'), h('p.muted', '卡住了、想发火了、看不懂对方了，先来这里过一遍。')),
    modeSeg.el,
    h('form.card.form', { onsubmit: run },
      field('关联的人', entitySel, '关联后会自动带上 TA 的档案和最近 10 条记录'),
      field('发生了什么', situation, '写事实：对方原话、发生了几次、间隔多久，以及你的感受'),
      draftField,
      field('场景', scenarioSel),
      runBtn,
    ),
    out,
  );
}

function scenarioFor(entity) {
  return { kin: 'family', bond: 'friendship', deal: 'workplace' }[entity.tier] || 'other';
}
