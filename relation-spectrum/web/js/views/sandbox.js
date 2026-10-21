// Game sandbox: describe a stuck situation, test an impulsive draft, get subtext odds,
// chips, three ready-to-send replies, and rehearse the conversation with the AI.
// Every analysis is saved to the case library so the real outcome can be filled in later.

import { h, mount, field, segmented, toast, go } from '../ui/dom.js';
import { TIERS, SCENARIOS, newCase } from '../core/models.js';
import { mismatchLines } from '../core/calibration.js';
import * as db from '../store/db.js';
import * as api from '../services/api.js';
import { loadWorld } from './world.js';
import { renderResult } from './render-result.js';
import { importBox } from './import-box.js';
import { openRoleplay } from './roleplay.js';

const MODES = { decode: '潜台词解码', game: '多方博弈' };

const EXAMPLES = {
  decode: '我最好的朋友在考研，之前焦虑时打电话给我，我陪他聊了一个多小时。考完后我约他出去玩，到了他学校附近，他说有事来不了。之后又约了两次，他都说“最近忙，再说吧”，也不说忙什么。',
  game: '我在一家小公司做了两年，老板一直说“明年公司上市就给你期权”，但这两年工资没涨过，每周加班十几个小时。最近有猎头联系我，薪资高 30%，但我对新公司不了解。',
};
const DRAFT_EXAMPLES = {
  decode: '比如：“算了，你爱咋咋吧。”',
  game: '比如：“你们就会画饼，我不干了。”',
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
  const myGuess = h('input', { placeholder: '比如：“他就是不想跟我玩了”' });
  const draft = h('textarea', { rows: 3, placeholder: DRAFT_EXAMPLES[mode] });
  const scenarioSel = h('select', Object.entries(SCENARIOS).map(([k, v]) => h('option', { value: k, selected: k === 'other' }, k === 'other' ? '自动判断' : v)));

  const modeSeg = segmented(MODES, mode, (v) => {
    mode = v;
    situation.placeholder = EXAMPLES[mode];
    draft.placeholder = DRAFT_EXAMPLES[mode];
  });

  const importer = importBox({
    getEntityName: () => (entitySel.value ? w.entityById.get(entitySel.value)?.name : ''),
    onParsed: (p) => {
      situation.value = p.situation || situation.value;
      const hint = (p.person_hint || '').trim();
      const match = hint && w.entities.find((e) => e.name.includes(hint) || hint.includes(e.name.replace(/（.*?）/g, '')));
      if (match && !entitySel.value) entitySel.value = match.id;
      situation.focus();
    },
  });

  const out = h('div');
  const runBtn = h('button.btn.primary.block', { type: 'submit' }, '开始分析');

  const run = async (ev) => {
    ev.preventDefault();
    const text = situation.value.trim();
    if (text.length < 10) return toast('多写几句：发生了什么、对方怎么说的、你怎么想的', 'warn');
    const entity = entitySel.value ? w.entityById.get(entitySel.value) : null;
    const input = {
      situation: text,
      draft: draft.value.trim(),
      myGuess: myGuess.value.trim(),
      calibration: mismatchLines(w.cases),
    };
    if (entity) {
      input.entity = entity;
      input.interactions = [...w.byEntity.get(entity.id)].sort((a, b) => (a.date < b.date ? -1 : 1));
      input.analysis = w.analysis.get(entity.id);
    }

    runBtn.disabled = true;
    runBtn.textContent = '分析中…';
    out.replaceChildren(h('p.muted.loading', input.draft ? '先测试原话的破坏力，再拆解局面，大约 10–40 秒' : '正在拆解，大约需要 10–40 秒'));
    try {
      const { result, model, via } = await api.analyze(mode, input);
      const picked = scenarioSel.value;
      const scenario = picked !== 'other' ? picked
        : mode === 'game' && result.scenario ? result.scenario
        : entity ? scenarioFor(entity) : 'other';
      const plain = text.replace(/(^|\n)\s*(我|TA)[：:]\s*/g, ' ').replace(/\s+/g, ' ').trim();
      const record = newCase({
        title: plain.slice(0, 24) + (plain.length > 24 ? '…' : ''),
        mode,
        scenario,
        entityId: entity?.id || null,
        situation: text,
        draft: input.draft,
        myGuess: input.myGuess,
        result,
        rehearsals: [],
      });
      await db.put('cases', record); // history: every analysis is kept for outcome backfill

      const rehearse = (p) => openRoleplay({
        opening: p.message,
        situation: text,
        entity,
        interactions: input.interactions,
        analysis: input.analysis,
        onDone: async (transcript, debrief) => {
          const cur = (await db.get('cases', record.id)) || record;
          await db.put('cases', { ...cur, rehearsals: [...(cur.rehearsals || []), { at: Date.now(), level: p.level, transcript, debrief }] });
        },
      });

      out.replaceChildren(
        renderResult(mode, result, { draft: input.draft, onRehearse: rehearse }),
        h('section.card.saved',
          h('p', h('b', '已存入推演记录。'), ' 过几天回来填一下真实结局，系统会帮你校准直觉。'),
          h('div.row-wrap',
            h('button.btn.small', { type: 'button', onclick: () => go(`#/case/${record.id}`) }, '查看 / 回填结局'),
            entity ? h('button.btn.small.ghost', { type: 'button', onclick: () => go(`#/log?entity=${entity.id}`) }, '把这件事记一笔') : null,
          ),
          h('p.small.muted', `由 ${model} 生成${via === 'direct' ? ' · 浏览器直连' : ''}`),
        ),
      );
      out.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (err) {
      out.replaceChildren(h('div.card.error',
        h('p', err.message),
        err.status === 401 || /Key|key|令牌|口令|设置/.test(err.message)
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
    importer,
    h('form.card.form', { onsubmit: run },
      field('关联的人', entitySel, '关联后会自动带上 TA 的档案和最近 10 条记录'),
      field('发生了什么', situation, '写事实：对方原话、发生了几次、间隔多久，以及你的感受'),
      field('我现在的猜测（可选）', myGuess, '先写下你的第一反应。事后回填结局时，可以看看自己猜得准不准'),
      h('div.field.draft-field',
        h('span.field-label', '我本想发的话（冲动原话 · 可选）'),
        draft,
        h('span.field-hint', '情绪上头时写在这里，别直接发。AI 会先模拟 TA 读到这句的第一反应，并给出翻脸风险')),
      field('场景', scenarioSel),
      runBtn,
    ),
    out,
  );
}

function scenarioFor(entity) {
  return { kin: 'family', bond: 'friendship', deal: 'workplace' }[entity.tier] || 'other';
}
