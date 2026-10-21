// Case library: saved sandbox analyses plus built-in reference cases.

import { h, mount, go, segmented, emptyState, toast } from '../ui/dom.js';
import { CASE_MODES, SCENARIOS } from '../core/models.js';
import * as db from '../store/db.js';
import { renderResult } from './render-result.js';
import { SEED_CASES } from '../data/seed-cases.js';

export async function renderCases(root) {
  const mine = (await db.all('cases')).sort((a, b) => b.createdAt - a.createdAt);
  let tab = 'mine';
  const box = h('div');

  const row = (c, href) =>
    h('button.person', { type: 'button', onclick: () => go(href) },
      h('div.person-main',
        h('div.person-name', c.title || '未命名'),
        h('div.small.muted', `${CASE_MODES[c.mode] || c.mode} · ${SCENARIOS[c.scenario] || ''}${c.createdAt ? ` · ${new Date(c.createdAt).toLocaleDateString('zh-CN')}` : ''}`),
      ));

  const draw = () => {
    if (tab === 'mine') {
      box.replaceChildren(mine.length
        ? h('section.card', h('div.people', mine.map((c) => row(c, `#/case/${c.id}`))))
        : emptyState('还没有保存的案例', '在博弈沙盘分析完后，点“存入案例库”。', h('button.btn', { onclick: () => go('#/sandbox') }, '去沙盘')));
    } else {
      box.replaceChildren(h('section.card',
        h('p.small.muted', '内置的参考案例，展示每种场景应该怎么拆。'),
        h('div.people', SEED_CASES.map((c) => row(c, `#/case/seed:${c.id}`)))));
    }
  };
  const seg = segmented({ mine: `我的案例 · ${mine.length}`, seed: `参考案例 · ${SEED_CASES.length}` }, tab, (v) => { tab = v; draw(); });
  draw();

  mount(root, h('header.page-head', h('h1', '案例库')), seg.el, box);
}

export async function renderCase(root, id) {
  const isSeed = id.startsWith('seed:');
  const c = isSeed ? SEED_CASES.find((x) => x.id === id.slice(5)) : await db.get('cases', id);
  if (!c) {
    mount(root, emptyState('找不到这个案例', null, h('button.btn', { onclick: () => go('#/cases') }, '返回')));
    return;
  }
  mount(root,
    h('header.page-head',
      h('button.back', { type: 'button', onclick: () => history.back() }, '‹ 返回'),
      h('h1', c.title),
      h('p.muted', `${CASE_MODES[c.mode]} · ${SCENARIOS[c.scenario] || ''}`)),
    h('section.card', h('h3', '情况'), h('p.pre', c.situation),
      c.draft ? [h('h3', '当时想回的话'), h('blockquote.play-msg', c.draft)] : null),
    renderResult(c.mode, c.result),
    isSeed ? null : h('button.btn.danger', {
      type: 'button',
      onclick: async () => {
        if (!confirm('删除这个案例？')) return;
        await db.remove('cases', c.id);
        toast('已删除');
        go('#/cases');
      },
    }, '删除案例'),
  );
}
