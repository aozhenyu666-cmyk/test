// Case library: saved sandbox analyses (with outcome backfill), built-in reference cases,
// and the intuition-calibration page.

import { h, mount, go, segmented, emptyState, toast, meter, field, ask } from '../ui/dom.js';
import { CASE_MODES, SCENARIOS, USED_LEVELS, OUTCOME_RESULTS } from '../core/models.js';
import { calibrationStats, needsOutcome, topSubtext, truthText } from '../core/calibration.js';
import * as db from '../store/db.js';
import { renderResult } from './render-result.js';
import { openRoleplay } from './roleplay.js';
import { SEED_CASES } from '../data/seed-cases.js';

export async function renderCases(root) {
  const mine = (await db.all('cases')).sort((a, b) => b.createdAt - a.createdAt);
  let tab = 'mine';
  const box = h('div');
  const pending = mine.filter((c) => needsOutcome(c)).length;

  const row = (c, href) =>
    h('button.person', { type: 'button', onclick: () => go(href) },
      h('div.person-main',
        h('div.person-name', c.title || '未命名'),
        h('div.small.muted', `${CASE_MODES[c.mode] || c.mode} · ${SCENARIOS[c.scenario] || ''}${c.createdAt ? ` · ${new Date(c.createdAt).toLocaleDateString('zh-CN')}` : ''}`),
      ),
      c.outcome ? h('span.badge.tone-good', '已回填') : ['decode', 'game'].includes(c.mode) && c.createdAt ? h('span.badge', '待回填') : null,
    );

  const draw = () => {
    if (tab === 'mine') {
      box.replaceChildren(mine.length
        ? h('section.card', h('div.people', mine.map((c) => row(c, `#/case/${c.id}`))))
        : emptyState('还没有推演记录', '在博弈沙盘里分析一次，记录会自动保存在这里。', h('button.btn', { onclick: () => go('#/sandbox') }, '去沙盘')));
    } else {
      box.replaceChildren(h('section.card',
        h('p.small.muted', '内置的参考案例，展示每种场景应该怎么拆。'),
        h('div.people', SEED_CASES.map((c) => row({ ...c, createdAt: null }, `#/case/seed:${c.id}`)))));
    }
  };
  const seg = segmented({ mine: `我的推演 · ${mine.length}`, seed: `参考案例 · ${SEED_CASES.length}` }, tab, (v) => { tab = v; draw(); });
  draw();

  mount(root,
    h('header.page-head', h('h1', '案例库')),
    h('button.card.calib-link', { type: 'button', onclick: () => go('#/calibration') },
      h('div.row-between', h('b', '直觉校准 · 误判错题本'), h('span.muted', '›')),
      h('p.small.muted', pending ? `有 ${pending} 条推演等你回填真实结局` : '对比你的第一反应、AI 的预判和真实结局')),
    seg.el,
    box,
  );
}

export async function renderCase(root, id) {
  const isSeed = id.startsWith('seed:');
  const c = isSeed ? SEED_CASES.find((x) => x.id === id.slice(5)) : await db.get('cases', id);
  if (!c) {
    mount(root, emptyState('找不到这个案例', null, h('button.btn', { onclick: () => go('#/cases') }, '返回')));
    return;
  }
  const entity = c.entityId ? await db.get('entities', c.entityId) : null;
  const interactions = entity ? (await db.interactionsOf(entity.id)).sort((a, b) => (a.date < b.date ? -1 : 1)) : [];

  const rehearse = (p) => openRoleplay({
    opening: p.message,
    situation: c.situation,
    entity,
    interactions,
    onDone: async (transcript, debrief) => {
      if (isSeed) return;
      const cur = (await db.get('cases', c.id)) || c;
      await db.put('cases', { ...cur, rehearsals: [...(cur.rehearsals || []), { at: Date.now(), level: p.level, transcript, debrief }] });
    },
  });

  const canBackfill = !isSeed && ['decode', 'game'].includes(c.mode);

  mount(root,
    h('header.page-head',
      h('button.back', { type: 'button', onclick: () => history.back() }, '‹ 返回'),
      h('h1', c.title),
      h('p.muted', `${CASE_MODES[c.mode]} · ${SCENARIOS[c.scenario] || ''}${entity ? ` · ${entity.name}` : ''}`)),
    canBackfill ? (c.outcome ? comparisonCard(c, () => renderCase(root, id)) : outcomeForm(c, () => renderCase(root, id))) : null,
    h('section.card', h('h3', '情况'), h('p.pre', c.situation),
      c.myGuess ? [h('h3', '我当时的猜测'), h('p', c.myGuess)] : null),
    renderResult(c.mode, c.result, { draft: c.draft, onRehearse: ['decode', 'game'].includes(c.mode) ? rehearse : null }),
    c.rehearsals?.length ? rehearsalsCard(c.rehearsals) : null,
    isSeed ? null : h('button.btn.danger', {
      type: 'button',
      onclick: async () => {
        if (!(await ask('删除这条记录？', { ok: '删除', danger: true }))) return;
        await db.remove('cases', c.id);
        toast('已删除');
        go('#/cases');
      },
    }, '删除'),
  );
}

// ---------------------------------------------------------------- outcome backfill

function outcomeForm(c, done) {
  const subs = [...(c.result?.subtexts || [])].sort((a, b) => b.probability - a.probability);
  const used = segmented(USED_LEVELS, 'gentle');
  const result = segmented(OUTCOME_RESULTS, 'same');
  const reaction = h('textarea', { rows: 3, placeholder: '比如：她主动解释说家里突然出事，所以那几次没来' });
  const sentDraft = h('input', { type: 'checkbox' });
  const otherText = h('input', { placeholder: '真实原因是…' });

  let truth = null;
  const options = [
    ...(c.myGuess ? [{ kind: 'my_guess', label: c.myGuess, text: `我的猜测：${c.myGuess}` }] : []),
    ...subs.map((s) => ({ kind: 'ai', label: s.label, text: `AI：${s.label}（${s.probability}%）` })),
    { kind: 'other', label: '', text: '都不是，是别的原因' },
  ];
  const truthBox = h('div.truth-options', options.map((o, i) =>
    h('label.truth', h('input', {
      type: 'radio', name: 'truth', value: String(i),
      onchange: () => { truth = o; otherWrap.hidden = o.kind !== 'other'; },
    }), h('span', o.text))));
  const otherWrap = h('div', { hidden: true }, otherText);

  const save = async () => {
    if (!truth) return toast('选一下：真实情况更接近哪一个？', 'warn');
    const label = truth.kind === 'other' ? otherText.value.trim() || '其他原因' : truth.label;
    const outcome = {
      at: Date.now(),
      usedLevel: used.value,
      sentDraft: sentDraft.checked,
      reaction: reaction.value.trim(),
      truth: { kind: truth.kind, label },
      result: result.value,
    };
    await db.put('cases', { ...c, outcome });
    toast('已回填，错题本已更新', 'good');
    done();
  };

  return h('section.card.outcome',
    h('h3', '回填真实结局'),
    h('p.small.muted', '事情有了结果之后，花一分钟填一下。这是纠正“把怀疑当事实”最有效的办法。'),
    h('div.field', h('span.field-label', '我最后用了'), used.el),
    c.draft ? h('label.inline', sentDraft, '我还是把冲动原话发出去了') : null,
    field('对方的实际反应', reaction),
    h('div.field', h('span.field-label', '真实情况更接近'), truthBox, otherWrap),
    h('div.field', h('span.field-label', '结果'), result.el),
    h('button.btn.primary', { type: 'button', onclick: save }, '保存结局'),
  );
}

function comparisonCard(c, done) {
  const o = c.outcome;
  const top = topSubtext(c.result);
  const myRight = o.truth.kind === 'my_guess';
  const aiRight = top && o.truth.kind !== 'my_guess' && top.label === o.truth.label;
  const aiProb = (c.result?.subtexts || []).find((s) => s.label === o.truth.label)?.probability;
  const mark = (ok) => h(`span.badge.${ok ? 'tone-good' : 'tone-warn'}`, ok ? '猜中' : '没猜中');
  return h('section.card.compare',
    h('div.row-between', h('h3', '猜测 vs 预判 vs 真相'), h('button.link', { type: 'button', onclick: async () => { await db.put('cases', { ...c, outcome: null }); done(); } }, '重新填写')),
    h('div.compare-grid',
      c.myGuess ? h('div.cmp', h('span.muted.small', '我的第一反应'), h('p', c.myGuess), mark(myRight)) : null,
      top ? h('div.cmp', h('span.muted.small', `AI 的首选（${top.probability}%）`), h('p', top.label), mark(aiRight)) : null,
      h('div.cmp.truth-cell', h('span.muted.small', '真实情况'), h('p', h('b', truthText(c))),
        aiProb != null ? h('span.small.muted', `AI 给它的概率：${aiProb}%`) : null),
    ),
    o.reaction ? h('p.small', h('b', '对方的实际反应：'), o.reaction) : null,
    h('p.small', `我用了${USED_LEVELS[o.usedLevel] || ''}${o.sentDraft ? '，还发了冲动原话' : ''} · ${OUTCOME_RESULTS[o.result] || ''}`),
    !myRight && c.myGuess ? h('p.reminder', '记下这一条：我的第一反应不是事实。') : null,
  );
}

function rehearsalsCard(list) {
  return h('section.card',
    h('h3', `对线预演 · ${list.length}`),
    list.map((r) => h('details.rehearsal',
      h('summary', `${new Date(r.at).toLocaleString('zh-CN')} · ${{ gentle: '温和档', equal: '对等档', exit: '止损档' }[r.level] || ''}`),
      h('div.rp-log.static', r.transcript.map((m) => h(`div.bubble.${m.role}`, h('div.bubble-text', m.text)))),
      r.debrief?.best_line ? h('p.small', h('b', '最推荐的说法：'), r.debrief.best_line) : null,
    )),
  );
}

// ---------------------------------------------------------------- calibration page

export async function renderCalibration(root) {
  const cases = await db.all('cases');
  const st = calibrationStats(cases);
  const pending = cases.filter((c) => needsOutcome(c, Date.now(), 0)).sort((a, b) => b.createdAt - a.createdAt);
  const pct = (x) => (x == null ? '—' : `${Math.round(x * 100)}%`);

  mount(root,
    h('header.page-head',
      h('button.back', { type: 'button', onclick: () => history.back() }, '‹ 返回'),
      h('h1', '直觉校准'),
      h('p.muted', '对比你的第一反应、AI 的预判和真实结局，专治“把怀疑当事实”。')),

    h('section.card.summary',
      h('p.lead', st.lesson),
      h('div.calib-grid',
        stat('我的直觉准确率', pct(st.myHitRate), st.withGuess ? `${st.withGuess} 次有记录猜测` : '还没有'),
        stat('AI 首选命中率', pct(st.aiTopHitRate), `${st.resolved} 条已回填`),
        stat('AI 列出了真相', pct(st.aiAnyHitRate), st.aiAvgProbOnTruth != null ? `平均给了 ${st.aiAvgProbOnTruth}%` : ''),
        stat('发了冲动原话', String(st.sentDraft), st.sentDraft ? `其中 ${st.sentDraftWorse} 次变差` : ''),
      ),
      st.myHitRate != null ? [h('p.small.muted', '我的直觉准确率'), meter(st.myHitRate * 100, st.myHitRate >= 0.6 ? 'var(--good)' : 'var(--warn)')] : null,
    ),

    Object.keys(st.byLevel).length
      ? h('section.card', h('h3', '哪一档最有效'),
          h('div.metrics', Object.entries(st.byLevel).map(([lv, v]) =>
            h('div.kv', h('span', USED_LEVELS[lv] || lv), h('span', `${v.n} 次 · 变好 ${v.better} · 变差 ${v.worse}`)))))
      : null,

    h('section.card', h('h3', `误判错题本 · ${st.mistakes.length}`),
      st.mistakes.length
        ? h('ul.timeline', st.mistakes.map((m) =>
            h('li', h('button.link', { type: 'button', onclick: () => go(`#/case/${m.id}`) }, m.title),
              h('div.small', h('span.muted', '我以为：'), m.guess),
              h('div.small', h('span.muted', '实际上：'), h('b', m.truth)))))
        : h('p.muted', '还没有误判记录。推演时写下“我现在的猜测”，回填结局后，猜错的会出现在这里。')),

    pending.length
      ? h('section.card', h('h3', `待回填 · ${pending.length}`),
          h('div.people', pending.map((c) =>
            h('button.person', { type: 'button', onclick: () => go(`#/case/${c.id}`) },
              h('div.person-main', h('div.person-name', c.title), h('div.small.muted', new Date(c.createdAt).toLocaleDateString('zh-CN'))),
              h('span.badge', '去回填')))))
      : null,
  );
}

function stat(label, value, sub) {
  return h('div.calib-stat', h('span.muted.small', label), h('b', value), sub ? h('span.small.muted', sub) : null);
}
