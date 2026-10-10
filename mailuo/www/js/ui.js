// 小工具：h() 生成元素、图标、弹层、提示、表盘
export function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'html') el.innerHTML = v;
    else if (k in el && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  append(el, kids);
  return el;
}
function append(el, kids) {
  for (const k of kids.flat(Infinity)) {
    if (k == null || k === false) continue;
    el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  }
}

// 线性图标（Lucide 风格，MIT）
const P = {
  home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/><path d="M10 21v-6h4v6"/>',
  tree: '<circle cx="6" cy="5" r="2"/><circle cx="6" cy="19" r="2"/><circle cx="18" cy="12" r="2"/><path d="M6 7v10"/><path d="M6 12h3a3 3 0 0 0 3-3V8a3 3 0 0 1 3-3"/><path d="M12 12h4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  flask: '<path d="M9 3h6"/><path d="M10 3v6L4.5 18.5A1.7 1.7 0 0 0 6 21h12a1.7 1.7 0 0 0 1.5-2.5L14 9V3"/><path d="M7 15h10"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
  back: '<path d="M15 18l-6-6 6-6"/>',
  chev: '<path d="M6 9l6 6 6-6"/>',
  right: '<path d="M9 18l6-6-6-6"/>',
  send: '<path d="M12 19V5"/><path d="M5 12l7-7 7 7"/>',
  spark: '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/><path d="M19 17l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7z"/>',
  branch: '<circle cx="6" cy="6" r="2"/><circle cx="6" cy="18" r="2"/><circle cx="18" cy="8" r="2"/><path d="M6 8v8"/><path d="M18 10c0 4-6 3-11 7"/>',
  link: '<path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/>',
  edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
  trash: '<path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M19 6l-1 14H6L5 6"/>',
  x: '<path d="M18 6 6 18M6 6l12 12"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  grip: '<circle cx="9" cy="6" r="1"/><circle cx="15" cy="6" r="1"/><circle cx="9" cy="12" r="1"/><circle cx="15" cy="12" r="1"/><circle cx="9" cy="18" r="1"/><circle cx="15" cy="18" r="1"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/>',
  layers: '<path d="M12 2 2 7l10 5 10-5z"/><path d="m2 17 10 5 10-5"/><path d="m2 12 10 5 10-5"/>',
  book: '<path d="M2 4h7a3 3 0 0 1 3 3v14a2 2 0 0 0-2-2H2z"/><path d="M22 4h-7a3 3 0 0 0-3 3v14a2 2 0 0 1 2-2h8z"/>',
  pulse: '<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>',
  compass: '<circle cx="12" cy="12" r="9"/><path d="m16 8-2 6-6 2 2-6z"/>',
  merge: '<circle cx="6" cy="6" r="2"/><circle cx="6" cy="18" r="2"/><circle cx="18" cy="18" r="2"/><path d="M6 8v8"/><path d="M6 8c0 6 6 10 10 10"/>',
  swap: '<path d="M7 4 3 8l4 4"/><path d="M3 8h14"/><path d="m17 20 4-4-4-4"/><path d="M21 16H7"/>',
  download: '<path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 21h14"/>',
  upload: '<path d="M12 21V9"/><path d="m7 14 5-5 5 5"/><path d="M5 3h14"/>',
  folder: '<path d="M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  refresh: '<path d="M21 12a9 9 0 1 1-2.6-6.4L21 8"/><path d="M21 3v5h-5"/>',
  eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
  undo: '<path d="M9 14 4 9l5-5"/><path d="M4 9h11a5 5 0 0 1 0 10h-3"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  target: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1"/>',
};
export function icon(name, cls = '') {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  s.setAttribute('class', 'i ' + cls);
  s.innerHTML = P[name] || '';
  return s;
}

// 品牌标：一条主脉分出侧脉（叶脉 / 水墨枝）
export function brandMark() {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 32 32');
  s.setAttribute('class', 'brand-mark');
  s.innerHTML = '<g fill="none" stroke="currentColor" stroke-linecap="round" stroke-width="2"><path d="M6 27C12 21 18 13 26 5"/><path d="M12 20.5c-3-.5-5-2.5-6-5.5" stroke-width="1.6"/><path d="M16.5 15.5c1-3 .8-6-.5-8.5" stroke-width="1.6"/><path d="M20.5 11c3 .3 5.5 1.6 7 3.6" stroke-width="1.6"/></g><circle cx="26" cy="5" r="2.2" fill="var(--accent)"/>';
  return s;
}

export function veinArt() {
  const d = document.createElement('div');
  d.className = 'art';
  d.innerHTML = '<svg viewBox="0 0 180 120" width="180" height="120" fill="none" stroke="currentColor" stroke-linecap="round"><path class="vein-path" d="M14 110C50 88 92 58 168 12" stroke-width="2.4"/><path class="vein-path" d="M58 84c-14-2-26-12-32-28" stroke-width="1.6"/><path class="vein-path" d="M96 60c6-14 6-30-2-44" stroke-width="1.6"/><path class="vein-path" d="M128 38c16 0 30 6 40 18" stroke-width="1.6"/><circle cx="168" cy="12" r="5" fill="var(--accent)" stroke="none"/></svg>';
  return d;
}

// 把握表盘：60 格刻度，已达刻度用墨色，指针用朱砂
export function dial(value, size = 44, big = false, label = '把握') {
  const v = value == null ? null : Math.max(0, Math.min(100, value));
  const r = size / 2, ticks = 40;
  let t = '';
  for (let i = 0; i < ticks; i++) {
    const a = (i / ticks) * 2 * Math.PI - Math.PI / 2;
    const major = i % 10 === 0;
    const r1 = r - 2, r2 = r - (major ? (big ? 9 : 6) : (big ? 5 : 4));
    const on = v != null && i / ticks < v / 100;
    t += `<line x1="${r + r1 * Math.cos(a)}" y1="${r + r1 * Math.sin(a)}" x2="${r + r2 * Math.cos(a)}" y2="${r + r2 * Math.sin(a)}" stroke="${on ? 'var(--ink)' : 'var(--line-2)'}" stroke-width="${major ? 1.8 : 1.1}" stroke-linecap="round"/>`;
  }
  if (v != null) {
    const a = (v / 100) * 2 * Math.PI - Math.PI / 2;
    // Mondaine 式红色「秒针」头：落在当前刻度上的一个朱砂点
    const rr = r - (big ? 5 : 3.5);
    t += `<circle cx="${r + rr * Math.cos(a)}" cy="${r + rr * Math.sin(a)}" r="${big ? 3.6 : 2.4}" fill="var(--accent)"/>`;
  }
  const el = h('div', { class: 'dial' + (big ? ' lg' : ''), style: { width: size + 'px', height: size + 'px' }, title: v == null ? '未填' + label : label + ' ' + v + (label === '把握' ? '%' : '') });
  el.innerHTML = `<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">${t}</svg>`;
  el.append(h('div', { class: 'v' }, big ? h('div', {}, v == null ? '—' : v + (label === '把握' ? '%' : ''), h('small', {}, label)) : v == null ? '—' : v));
  return el;
}

export function toast(msg, ms = 2200) {
  document.querySelector('.toast')?.remove();
  const t = h('div', { class: 'toast', role: 'status' }, msg);
  document.body.append(t);
  setTimeout(() => t.remove(), ms);
}

export function sheet(title, build) {
  const scrim = h('div', { class: 'scrim' });
  const box = h('div', { class: 'sheet', role: 'dialog', 'aria-label': title }, h('div', { class: 'grab' }), title ? h('h3', {}, title) : null);
  const close = () => { scrim.remove(); box.remove(); };
  scrim.addEventListener('click', close);
  build(box, close);
  document.body.append(scrim, box);
  box.querySelector('input,textarea')?.focus({ preventScroll: true });
  return close;
}

export function confirmSheet(title, msg, okText = '确定', danger = false) {
  return new Promise((resolve) => {
    sheet(title, (box, close) => {
      box.append(h('p', { class: 'sub' }, msg), h('div', { class: 'btn-row mt' },
        h('button', { class: 'btn grow', onclick: () => { close(); resolve(false); } }, '取消'),
        h('button', { class: 'btn grow ' + (danger ? 'accent' : 'primary'), onclick: () => { close(); resolve(true); } }, okText)));
    });
  });
}

export function waiting(label) {
  const el = h('div', { class: 'waiting' },
    h('div', { class: 'clocks' }, [0, 1, 2, 3].map(() => h('div', { class: 'clock' }, h('i'), h('i')))),
    h('div', { class: 'elapsed' }, '0.0s'), h('div', { class: 'small' }, label));
  const t0 = performance.now();
  const timer = setInterval(() => {
    if (!el.isConnected) return clearInterval(timer);
    el.querySelector('.elapsed').textContent = ((performance.now() - t0) / 1000).toFixed(1) + 's';
  }, 100);
  return el;
}

export const TYPE_GLYPH = { 概念: '概', 论断: '论', 规则: '规', 问题: '问', 案例: '例' };
export function glyph(n) { return h('div', { class: 'glyph ' + (n.status || ''), title: n.type + ' · ' + n.status }, TYPE_GLYPH[n.type] || '节'); }

export function nodeRow(n, onclick, extra) {
  return h('div', { class: 'node-row', 'data-id': n.id, onclick },
    glyph(n),
    h('div', { class: 'body' }, h('div', { class: 'title' }, n.title), h('div', { class: 'claim' }, n.claim)),
    extra || (n.confidence != null ? dial(n.confidence, 34) : null));
}

export function relTag(type) { return h('span', { class: 'rel-tag rel-' + type }, type); }
