// App shell: hash router + bottom navigation.

import { h, mount } from './ui/dom.js';
import { renderDashboard } from './views/dashboard.js';
import { renderPeople, renderPerson, renderPersonEdit } from './views/people.js';
import { renderLog } from './views/log.js';
import { renderSandbox } from './views/sandbox.js';
import { renderCases, renderCase, renderCalibration } from './views/cases.js';
import { renderSettings } from './views/settings.js';

const NAV = [
  { hash: '#/', label: '看板', icon: '◎', match: (p) => p === '/' },
  { hash: '#/people', label: '关系', icon: '◐', match: (p) => p.startsWith('/people') || p.startsWith('/person') },
  { hash: '#/log', label: '记一笔', icon: '＋', match: (p) => p.startsWith('/log'), primary: true },
  { hash: '#/sandbox', label: '沙盘', icon: '◇', match: (p) => p.startsWith('/sandbox') },
  { hash: '#/cases', label: '更多', icon: '≡', match: (p) => p.startsWith('/case') || p.startsWith('/settings') || p.startsWith('/calibration') },
];

const routes = [
  [/^\/$/, (root, m, q) => renderDashboard(root, q)],
  [/^\/people$/, (root, m, q) => renderPeople(root, q)],
  [/^\/person\/new$/, (root, m, q) => renderPersonEdit(root, null, q)],
  [/^\/person\/([^/]+)\/edit$/, (root, m, q) => renderPersonEdit(root, m[1], q)],
  [/^\/person\/([^/]+)$/, (root, m) => renderPerson(root, m[1])],
  [/^\/log$/, (root, m, q) => renderLog(root, q)],
  [/^\/sandbox$/, (root, m, q) => renderSandbox(root, q)],
  [/^\/cases$/, (root, m, q) => renderCasesWithSettingsLink(root, q)],
  [/^\/case\/(.+)$/, (root, m) => renderCase(root, decodeURIComponent(m[1]))],
  [/^\/calibration$/, (root) => renderCalibration(root)],
  [/^\/settings$/, (root) => renderSettings(root)],
];

async function renderCasesWithSettingsLink(root, q) {
  await renderCases(root, q);
  root.prepend(h('div.more-links', h('a.btn.small', { href: '#/settings' }, '设置 · AI / Notion / 数据')));
}

function parseHash() {
  const raw = location.hash.replace(/^#/, '') || '/';
  const [path, query = ''] = raw.split('?');
  return { path: path || '/', query: new URLSearchParams(query) };
}

const view = document.getElementById('view');
const nav = document.getElementById('nav');

function drawNav(path) {
  mount(nav, NAV.map((n) =>
    h('a', { href: n.hash, class: `${n.match(path) ? 'on' : ''} ${n.primary ? 'primary' : ''}`, 'aria-current': n.match(path) ? 'page' : null },
      h('span.nav-icon', { 'aria-hidden': 'true' }, n.icon), h('span.nav-label', n.label))));
}

async function route() {
  const { path, query } = parseHash();
  drawNav(path);
  for (const [re, fn] of routes) {
    const m = path.match(re);
    if (m) {
      try {
        await fn(view, m, query);
      } catch (err) {
        console.error(err);
        mount(view, h('div.card.error', h('p', `出错了：${err.message}`)));
      }
      window.scrollTo(0, 0);
      return;
    }
  }
  location.hash = '#/';
}

window.addEventListener('hashchange', route);
route();

// PWA: cache the app shell so it opens offline and can be added to the home screen.
if ('serviceWorker' in navigator && (location.protocol === 'https:' || ['localhost', '127.0.0.1'].includes(location.hostname))) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
