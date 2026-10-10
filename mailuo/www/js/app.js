import { h, icon, brandMark, veinArt, dial, toast, sheet, confirmSheet, waiting, glyph, nodeRow, relTag } from './ui.js';
import { Store, TYPES, STATUSES, REL_TYPES, REL_HINT, REL_VIEW, findDuplicate, findAnalogies, today, parentOf } from './model.js';
import { openVault, IdbVault, FsVault, CapVault, exportZip, importZip } from './vault.js';
import { getConfig, setConfig, usage, callModel, PROMPT_SPLIT, splitUserMessage, extractJson, normalizeSplit, PROMPT_THINK, parseAnswer, PRESETS, estimateTokens } from './llm.js';
import { buildPack, MODE_QUESTIONS } from './pack.js';
import { healthCheck, traceCause, longestChain, learningPath, dueReviews, gradeReview } from './features.js';
import { loadSeed, SEED_SYSTEM } from './seed.js';

const VERSION = '0.1.0';
let store, vault;
const app = document.getElementById('app');
const session = { cands: loadDraft(), packRemoved: new Set(), lastAnswer: null, sys: localStorage.getItem('mailuo.sys') || '' };

function loadDraft() { try { return JSON.parse(localStorage.getItem('mailuo.draft') || 'null'); } catch { return null; } }
function saveDraft() { localStorage.setItem('mailuo.draft', JSON.stringify(session.cands)); }
const go = (hash) => { location.hash = hash; };
const back = () => (history.length > 1 ? history.back() : go('#/'));

function applyTheme() {
  const t = getConfig().theme;
  if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t;
  else delete document.documentElement.dataset.theme;
}

// ---------------- 启动 ----------------
async function boot() {
  applyTheme();
  vault = await openVault();
  store = new Store(vault);
  if (vault.kind === 'fs' && !(await vault.permitted())) {
    render(() => [
      topbarBrand(),
      h('div', { class: 'empty' }, veinArt(), h('h3', {}, '需要重新授权文件夹'), h('p', {}, '浏览器要求每次打开时确认对「' + vault.handle.name + '」的读写权限。'),
        h('button', { class: 'btn accent', onclick: async () => { if (await vault.ready()) { await store.load(); route(); } } }, icon('folder'), '授权并打开'),
        h('div', { class: 'mt' }), h('button', { class: 'btn ghost', onclick: async () => { localStorage.setItem('mailuo.vault', 'idb'); location.reload(); } }, '改用浏览器内置存储')),
    ]);
    return;
  }
  await vault.ready();
  await store.load();
  if (!session.sys || !store.systems.has(session.sys)) session.sys = [...store.systems.keys()][0] || '';
  window.addEventListener('hashchange', route);
  route();
  if ('serviceWorker' in navigator && location.protocol.startsWith('http') && !CapVault.available() && !/localhost|127\.0\.0\.1/.test(location.hostname)) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
  window.__mailuo = { store, vault }; // 调试 / 测试用
}

function route() {
  const [path, qs] = location.hash.replace(/^#\/?/, '').split('?');
  const params = new URLSearchParams(qs || '');
  const [name, arg] = path.split('/').map(decodeURIComponent);
  const views = { '': viewToday, tree: viewTree, capture: viewCapture, cands: viewCands, node: viewNode, ask: viewAsk, verify: viewVerify, health: viewHealth, review: viewReview, trace: viewTrace, settings: viewSettings };
  const fn = views[name] || viewToday;
  render(() => fn(arg, params), name);
  window.scrollTo(0, 0);
}

function render(build, tab = '') {
  app.innerHTML = '';
  const view = h('main', { class: 'view' });
  try { view.append(...[build()].flat(Infinity).filter(Boolean)); } catch (e) { console.error(e); view.append(h('div', { class: 'banner' }, '页面出错：' + e.message)); }
  app.append(view, tabbar(tab));
}

function tabbar(cur) {
  const t = (hash, ic, label, keys) => h('button', { class: 'tab' + (keys.includes(cur) ? ' on' : ''), onclick: () => go(hash), 'aria-label': label }, icon(ic), label);
  return h('nav', { class: 'tabbar' },
    t('#/', 'home', '今日', ['', 'review']),
    t('#/tree', 'tree', '结构', ['tree', 'node', 'trace', 'ask']),
    h('button', { class: 'tab-add', onclick: () => go('#/capture'), 'aria-label': '录入' }, icon('plus')),
    t('#/verify', 'flask', '验证', ['verify', 'health']),
    t('#/settings', 'gear', '设置', ['settings']));
}

const topbarBrand = (...right) => h('div', { class: 'topbar' }, h('div', { class: 'brand' }, brandMark(), h('span', { class: 'brand-name' }, '脉络')), h('div', { class: 'spacer' }), ...right);
const topbarBack = (title, ...right) => h('div', { class: 'topbar' },
  h('button', { class: 'iconbtn', onclick: back, 'aria-label': '返回' }, icon('back')),
  h('div', { class: 'grow', style: { fontWeight: 600 } }, title || ''), ...right);

// ---------------- 今日 ----------------
function viewToday() {
  const nodes = store.list();
  const u = usage();
  const d = u[today()] || {};
  const sent = (d.split?.sent || 0) + (d.think?.sent || 0);
  const recv = (d.split?.recv || 0) + (d.think?.recv || 0);
  const days = [...Array(7)].map((_, i) => { const x = new Date(Date.now() - (6 - i) * 864e5).toISOString().slice(0, 10); const v = u[x] || {}; return (v.split?.sent || 0) + (v.think?.sent || 0); });
  const max = Math.max(1, ...days);
  const date = new Date();
  const wk = '日一二三四五六'[date.getDay()];
  const out = [
    topbarBrand(h('button', { class: 'iconbtn', onclick: () => go('#/settings'), 'aria-label': '设置' }, icon('gear'))),
    h('h1', { class: 'display' }, greeting()),
    h('div', { class: 'sub' }, `${date.getMonth() + 1}月${date.getDate()}日 周${wk} · ${store.systems.size} 个体系 · ${nodes.length} 个节点`),
  ];
  out.push(h('div', { class: 'card ink mt2', 'data-testid': 'usage' },
    h('div', { class: 'small sub', style: { letterSpacing: '.12em' } }, '今日发送 TOKEN'),
    h('div', { class: 'meter mt' },
      h('div', { class: 'flip' }, String(sent).padStart(4, '0').split('').map((c) => h('span', {}, c))),
      h('div', { class: 'bars', title: '近 7 天' }, days.map((v, i) => h('i', { class: i === 6 ? 'today' : '', style: { height: Math.max(3, (v / max) * 38) + 'px' } })))),
    h('div', { class: 'small sub mt' }, `拆分 ${d.split?.n || 0} 次 · 推演 ${d.think?.n || 0} 次 · 收到 ${recv} token`)));

  if (!nodes.length) {
    out.push(h('div', { class: 'empty' }, veinArt(), h('h3', {}, '先建一个体系'),
      h('p', {}, '粘贴一段笔记或 AI 回复，拆成「体系 → 层级 → 节点 → 关系」。也可以先载入示例体系「思维问题」试用。'),
      h('div', { class: 'btn-row', style: { justifyContent: 'center' } },
        h('button', { class: 'btn accent', 'data-testid': 'seed', onclick: async () => { await loadSeed(store); session.sys = SEED_SYSTEM; toast('已载入「思维问题」：7 个节点'); route(); } }, icon('spark'), '载入示例体系'),
        h('button', { class: 'btn', onclick: () => go('#/capture') }, icon('plus'), '录入笔记'))));
    return out;
  }

  const due = dueReviews(store, 50).length;
  const hc = healthCheck(store);
  out.push(h('div', { class: 'tiles mt' },
    h('div', { class: 'tile', onclick: () => go('#/capture') }, icon('plus'), h('div', {}, h('div', { class: 't' }, '录入'), h('div', { class: 's' }, '粘贴 → 拆分'))),
    h('div', { class: 'tile', onclick: () => go('#/review'), 'data-testid': 'tile-review' }, h('div', { class: 'n' }, due), h('div', {}, h('div', { class: 't' }, '温故'), h('div', { class: 's' }, '张卡片到期'))),
    h('div', { class: 'tile', onclick: () => go('#/health') }, h('div', { class: 'n' }, hc.score), h('div', {}, h('div', { class: 't' }, '体检'), h('div', { class: 's' }, hc.issues.length + ' 类问题')))));

  const hyp = nodes.filter((n) => n.status === '假设').sort((a, b) => String(a.checks.at(-1)?.date || a.created).localeCompare(String(b.checks.at(-1)?.date || b.created)));
  if (hyp.length) {
    out.push(h('h2', { class: 'section' }, '待验证假设', h('span', { class: 'more', onclick: () => go('#/verify') }, '全部 ' + hyp.length)));
    out.push(h('div', { class: 'list' }, hyp.slice(0, 3).map((n) => nodeRow(n, () => go('#/node/' + n.id),
      h('button', { class: 'btn sm', onclick: (e) => { e.stopPropagation(); backfillSheet(n); } }, '回填')))));
  }
  const recent = [...nodes].sort((a, b) => String(b.updated).localeCompare(String(a.updated))).slice(0, 5);
  out.push(h('h2', { class: 'section' }, '最近更新', h('span', { class: 'more', onclick: () => go('#/tree') }, '看结构')));
  out.push(h('div', { class: 'list' }, recent.map((n) => nodeRow(n, () => go('#/node/' + n.id)))));
  return out;
}
function greeting() {
  const hr = new Date().getHours();
  return hr < 5 ? '夜深了' : hr < 11 ? '早上好' : hr < 14 ? '午安' : hr < 18 ? '下午好' : '晚上好';
}

// ---------------- 结构（树视图） ----------------
function viewTree(_, params) {
  const systems = [...store.systems.keys()];
  if (params.get('sys')) session.sys = params.get('sys');
  if (!store.systems.has(session.sys)) session.sys = systems[0] || '';
  localStorage.setItem('mailuo.sys', session.sys);
  const out = [topbarBrand(
    h('button', { class: 'iconbtn', onclick: () => newNodeSheet(session.sys, ''), 'aria-label': '新建节点' }, icon('plus')))];
  out.push(h('h1', { class: 'display' }, '结构'));
  out.push(h('div', { class: 'sys-chips' },
    systems.map((s) => h('button', { class: 'sys-chip' + (s === session.sys ? ' on' : ''), onclick: () => { session.sys = s; route(); } }, s)),
    h('button', { class: 'sys-chip', onclick: newSystemSheet }, '＋ 体系')));
  if (!session.sys) {
    out.push(h('div', { class: 'empty' }, veinArt(), h('h3', {}, '还没有体系'), h('p', {}, '新建一个体系，或在「今日」载入示例。'),
      h('button', { class: 'btn accent', onclick: newSystemSheet }, '新建体系')));
    return out;
  }
  const q = h('input', { class: 'input', placeholder: '搜索标题或激活词', type: 'search' });
  const treeBox = h('div', { class: 'tree' });
  const draw = () => { treeBox.innerHTML = ''; treeBox.append(buildTree(session.sys, q.value.trim())); };
  q.addEventListener('input', draw);
  out.push(h('div', { class: 'search' }, icon('search'), q), treeBox);
  draw();
  out.push(h('p', { class: 'small muted center mt2' }, '按住节点左侧的 ⋮⋮ 拖到另一个层级，即可改父级。'));
  return out;
}

function closedSet() { try { return new Set(JSON.parse(localStorage.getItem('mailuo.closed') || '[]')); } catch { return new Set(); } }
function toggleClosed(key) {
  const s = closedSet();
  s.has(key) ? s.delete(key) : s.add(key);
  localStorage.setItem('mailuo.closed', JSON.stringify([...s]));
}

function buildTree(system, query) {
  const sys = store.systems.get(system);
  const nodes = store.list(system).filter((n) => !query || n.title.includes(query) || n.claim.includes(query) || n.keywords.some((k) => k.includes(query)));
  const closed = closedSet();
  const levelBlock = (path, depth) => {
    const name = path ? path.split('/').at(-1) : system;
    const children = sys.levels.filter((l) => parentOf(l) === path);
    const own = nodes.filter((n) => n.parent === path);
    const count = nodes.filter((n) => n.parent === path || (path && n.parent.startsWith(path + '/'))).length + (path ? 0 : 0);
    const key = system + ':' + path;
    const isClosed = !query && closed.has(key);
    const head = h('div', { class: 'lv-head', 'data-drop': system + '|' + path, onclick: (e) => { if (e.target.closest('.lv-tools')) return; toggleClosed(key); el.classList.toggle('closed'); } },
      icon('chev', 'chev'), h('span', {}, path ? name : '体系根'), h('span', { class: 'cnt' }, path ? count : own.length),
      h('div', { class: 'lv-tools' },
        depth < 3 ? h('button', { class: 'iconbtn', title: '新建子层级', 'aria-label': '新建子层级', onclick: () => newLevelSheet(system, path) }, icon('layers')) : null,
        h('button', { class: 'iconbtn', title: '在此新建节点', 'aria-label': '在此新建节点', onclick: () => newNodeSheet(system, path) }, icon('plus')),
        path ? h('button', { class: 'iconbtn', title: '删除层级', 'aria-label': '删除层级', onclick: async () => {
          if (!(await confirmSheet('删除层级', '删除「' + name + '」及其子层级？（层级下必须没有节点）', '删除', true))) return;
          try { await store.removeLevel(system, path); route(); } catch (e) { toast(e.message); }
        } }, icon('trash')) : null));
    const body = h('div', { class: 'lv-body' },
      own.map((n) => draggableRow(n)),
      children.map((c) => levelBlock(c, depth + 1)));
    if (!own.length && !children.length) body.append(h('div', { class: 'empty-rel' }, '（空）'));
    const el = h('div', { class: 'lv' + (isClosed ? ' closed' : '') }, head, body);
    return el;
  };
  return levelBlock('', 0);
}

function draggableRow(n) {
  const grip = h('div', { class: 'grip', 'aria-label': '拖动改父级' }, icon('grip'));
  const row = nodeRow(n, (e) => { if (!e.target.closest('.grip')) go('#/node/' + n.id); });
  row.prepend(grip);
  grip.addEventListener('pointerdown', (e) => startDrag(e, n, row));
  return row;
}

function startDrag(e, n, row) {
  e.preventDefault();
  const ghost = row.cloneNode(true);
  ghost.classList.add('drag-ghost');
  document.body.append(ghost);
  row.classList.add('dragging');
  let target = null;
  const move = (ev) => {
    ghost.style.left = ev.clientX - 40 + 'px';
    ghost.style.top = ev.clientY - 24 + 'px';
    ghost.style.display = 'none';
    const under = document.elementFromPoint(ev.clientX, ev.clientY);
    ghost.style.display = '';
    const t = under?.closest('[data-drop]');
    if (t !== target) { target?.classList.remove('drop-on'); target = t; target?.classList.add('drop-on'); }
    if (ev.clientY < 60) window.scrollBy(0, -12); else if (ev.clientY > innerHeight - 100) window.scrollBy(0, 12);
  };
  const up = async () => {
    window.removeEventListener('pointermove', move);
    window.removeEventListener('pointerup', up);
    window.removeEventListener('pointercancel', up);
    ghost.remove(); row.classList.remove('dragging'); target?.classList.remove('drop-on');
    if (!target) return;
    const [sys, path] = target.dataset.drop.split('|');
    if (sys === n.system && path === n.parent) return;
    await store.moveNode(n.id, sys, path);
    toast('已移到「' + (path ? path.split('/').join(' › ') : sys) + '」');
    route();
  };
  move(e);
  window.addEventListener('pointermove', move);
  window.addEventListener('pointerup', up);
  window.addEventListener('pointercancel', up);
}

function newSystemSheet() {
  sheet('新建体系', (box, close) => {
    const inp = h('input', { class: 'input', placeholder: '例如：思维问题、行测资料分析' });
    box.append(h('div', { class: 'field' }, h('label', {}, '体系名称'), inp),
      h('button', { class: 'btn primary block', onclick: async () => {
        const s = await store.addSystem(inp.value);
        if (!s) return;
        session.sys = s.name; close(); go('#/tree?sys=' + encodeURIComponent(s.name)); route();
      } }, '创建'));
  });
}

function newLevelSheet(system, parent) {
  sheet(parent ? '在「' + parent.split('/').at(-1) + '」下新建层级' : '新建层级', (box, close) => {
    const inp = h('input', { class: 'input', placeholder: '例如：加工层' });
    box.append(h('div', { class: 'field' }, h('label', {}, '层级名称'), inp),
      h('p', { class: 'small muted' }, '层级最多 3 层。'),
      h('button', { class: 'btn primary block', onclick: async () => {
        const name = inp.value.trim().replace(/\//g, '·');
        if (!name) return;
        try { await store.addLevel(system, parent ? parent + '/' + name : name); close(); route(); } catch (e) { toast(e.message); }
      } }, '创建'));
  });
}

// ---------------- 节点编辑 ----------------
function levelOptions(system, cur) {
  const sys = store.systems.get(system);
  const sel = h('select', { class: 'input' }, h('option', { value: '' }, '（体系根）'),
    (sys?.levels || []).map((l) => h('option', { value: l, selected: l === cur }, '　'.repeat(l.split('/').length - 1) + l.split('/').at(-1))));
  sel.value = cur || '';
  return sel;
}

function nodeForm(n, { systemEditable = true } = {}) {
  const f = {};
  f.title = h('input', { class: 'input', value: n.title || '', placeholder: '短名' });
  f.claim = h('textarea', { class: 'input', rows: 2, placeholder: '一句结论' }, n.claim || '');
  f.note = h('textarea', { class: 'input', rows: 2, placeholder: '最多 3 行说明（可空）' }, n.note || '');
  f.type = h('select', { class: 'input' }, TYPES.map((t) => h('option', { value: t, selected: t === n.type }, t)));
  f.status = h('select', { class: 'input' }, STATUSES.map((t) => h('option', { value: t, selected: t === n.status }, t)));
  f.keywords = h('input', { class: 'input', value: (n.keywords || []).join('、'), placeholder: '3–5 个，用顿号或逗号分隔' });
  const confLabel = h('span', {}, (n.confidence ?? 50) + '%');
  f.confidence = h('input', { type: 'range', min: 0, max: 100, step: 5, value: n.confidence ?? 50, oninput: () => { confLabel.textContent = f.confidence.value + '%'; } });
  f.verify = h('input', { class: 'input', value: n.verify || '', placeholder: '一句话，假设类必填' });
  f.source = h('input', { class: 'input', value: n.source || '', placeholder: '自己 / AI 名称 / 资料' });
  f.system = h('select', { class: 'input', disabled: !systemEditable }, [...store.systems.keys()].map((s) => h('option', { value: s, selected: s === n.system }, s)));
  const parentWrap = h('div', {}, levelOptions(n.system, n.parent));
  f.system.addEventListener('change', () => { parentWrap.innerHTML = ''; parentWrap.append(levelOptions(f.system.value, '')); });
  const el = h('div', {},
    h('div', { class: 'field' }, h('label', {}, '标题'), f.title),
    h('div', { class: 'field' }, h('label', {}, '结论'), f.claim),
    h('div', { class: 'row' }, h('div', { class: 'field' }, h('label', {}, '类型'), f.type), h('div', { class: 'field' }, h('label', {}, '状态'), f.status)),
    h('div', { class: 'field' }, h('label', {}, '激活词'), f.keywords),
    h('div', { class: 'field' }, h('label', {}, '把握 ', confLabel), f.confidence),
    h('div', { class: 'field' }, h('label', {}, '验证方式'), f.verify),
    h('div', { class: 'row' }, h('div', { class: 'field' }, h('label', {}, '体系'), f.system), h('div', { class: 'field' }, h('label', {}, '上级层级'), parentWrap)),
    h('div', { class: 'field' }, h('label', {}, '说明'), f.note),
    h('div', { class: 'field' }, h('label', {}, '来源'), f.source));
  const read = () => ({
    title: f.title.value.trim(), claim: f.claim.value.trim(), note: f.note.value.trim().split('\n').slice(0, 3).join('\n'),
    type: f.type.value, status: f.status.value,
    keywords: f.keywords.value.split(/[,，、;；\s]+/).map((s) => s.trim()).filter(Boolean).slice(0, 5),
    confidence: +f.confidence.value, verify: f.verify.value.trim(), source: f.source.value.trim(),
    system: f.system.value, parent: parentWrap.querySelector('select').value,
  });
  return { el, read };
}

function validate(d) {
  if (!d.title) return '标题不能为空';
  if (!d.claim) return '结论不能为空';
  if (d.status === '假设' && !d.verify) return '假设类节点必须写验证方式';
  return '';
}

function newNodeSheet(system, parent) {
  if (!system) return newSystemSheet();
  sheet('新建节点', (box, close) => {
    const form = nodeForm({ system, parent, type: '论断', status: '假设', confidence: 50, source: '自己' });
    box.append(form.el, h('button', { class: 'btn primary block', onclick: async () => {
      const d = form.read();
      const err = validate(d);
      if (err) return toast(err);
      const n = await store.saveNode({ id: store.nextId(), ...d, links: [], checks: [], extra: {} });
      close(); go('#/node/' + n.id);
    } }, '保存'));
  });
}

function editNodeSheet(n) {
  sheet('编辑节点', (box, close) => {
    const form = nodeForm(n);
    box.append(form.el,
      h('button', { class: 'btn primary block', onclick: async () => {
        const d = form.read();
        const err = validate(d);
        if (err) return toast(err);
        Object.assign(n, d);
        await store.saveNode(n);
        close(); route(); toast('已保存');
      } }, '保存'),
      h('button', { class: 'btn ghost danger block mt', onclick: async () => {
        if (!(await confirmSheet('删除节点', '删除「' + n.title + '」？指向它的关系也会一并删除。', '删除', true))) return;
        await store.deleteNode(n.id); close(); go('#/tree');
      } }, icon('trash'), '删除节点'));
  });
}

// ---------------- 节点详情 + 关系视图 ----------------
function viewNode(id) {
  const n = store.nodes.get(id);
  if (!n) return [topbarBack('节点'), h('div', { class: 'empty' }, h('h3', {}, '找不到这个节点'))];
  const out = [topbarBack('', h('button', { class: 'iconbtn', onclick: () => editNodeSheet(n), 'aria-label': '编辑' }, icon('edit')))];
  out.push(h('div', { class: 'node-hero' },
    h('div', { class: 'path' }, store.pathOf(n).slice(0, -1).map((p) => h('span', {}, p))),
    h('h1', {}, n.title),
    h('div', { class: 'chips' }, h('span', { class: 'pill' }, n.type), h('span', { class: 'pill ' + n.status }, n.status), h('span', { class: 'pill outline mono' }, n.id),
      n.yamlError ? h('span', { class: 'pill accent' }, 'YAML 有误') : null)));
  out.push(h('div', { class: 'claim-big mt' }, n.claim), n.note ? h('div', { class: 'note' }, n.note) : null);

  out.push(h('div', { class: 'actions' },
    h('div', { class: 'action primary', onclick: () => go('#/ask/' + id), 'data-testid': 'ask' }, icon('send'), '提问'),
    h('div', { class: 'action', onclick: () => go('#/ask/' + id + '?mode=extend') }, icon('spark'), '延展'),
    h('div', { class: 'action', onclick: () => go('#/ask/' + id + '?mode=analogy') }, icon('merge'), '类比'),
    h('div', { class: 'action', onclick: () => go('#/trace/' + id) }, icon('branch'), '溯源')));

  out.push(h('div', { class: 'card mt2' }, h('dl', { class: 'meta-grid', style: { margin: 0 } },
    h('dt', {}, '激活词'), h('dd', {}, h('div', { class: 'chips' }, n.keywords.length ? n.keywords.map((k) => h('span', { class: 'kw' }, k)) : h('span', { class: 'small muted' }, '未填'))),
    h('dt', {}, '把握'), h('dd', {}, h('div', { class: 'verify-box' }, dial(n.confidence, 56, false),
      h('div', { class: 'vt' }, h('div', { class: 'label' }, '验证方式'), h('div', {}, n.verify || h('span', { class: 'muted' }, '—'))),
      h('button', { class: 'btn sm', onclick: () => backfillSheet(n), 'data-testid': 'backfill' }, '回填'))),
    h('dt', {}, '来源'), h('dd', { class: 'small' }, n.source || '—'),
    h('dt', {}, '更新'), h('dd', { class: 'small muted' }, (n.updated || '—') + (n.checks.length ? ' · 回填 ' + n.checks.length + ' 次' : '')))));

  if (n.checks.length) {
    out.push(h('h2', { class: 'section' }, '验证记录'));
    out.push(h('div', { class: 'list' }, n.checks.slice().reverse().map((c) => h('div', { class: 'card flat', style: { padding: '12px 14px' } },
      h('div', { class: 'small muted' }, c.date + ' · ' + (c.status || '') + (c.confidence != null ? ' · 把握 ' + c.confidence + '%' : '')), h('div', {}, c.result)))));
  }

  // 关系视图
  const rels = store.relations(id);
  out.push(h('h2', { class: 'section' }, '关系', h('span', { class: 'more', onclick: () => addRelationSheet(n) }, '＋ 添加')));
  const groups = REL_VIEW.map((g) => ({ ...g, items: rels.filter((r) => g.match(r, r.dir)) })).filter((g) => g.items.length);
  if (!groups.length) out.push(h('div', { class: 'empty-rel' }, '还没有关系。添加「导致 / 支撑 / 反例 / 对立」等，提问时 AI 才能看到上下游。'));
  for (const g of groups) {
    out.push(h('div', { class: 'rel-group' },
      h('div', { class: 'gh' }, g.label, h('small', {}, g.hint)),
      g.items.map((r) => h('div', { class: 'rel-item rel-' + r.type, onclick: () => r.other && go('#/node/' + r.otherId) },
        relTag(r.type),
        h('div', { class: 'b' }, h('div', { class: 't' }, (r.dir === 'in' ? '← ' : '→ ') + (r.other?.title || r.otherId + '（不存在）')), r.why ? h('div', { class: 'w' }, r.why) : null),
        h('button', { class: 'iconbtn', 'aria-label': '删除关系', onclick: async (e) => {
          e.stopPropagation();
          if (!(await confirmSheet('删除关系', '删除这条「' + r.type + '」关系？', '删除', true))) return;
          if (r.dir === 'out') await store.removeLink(id, r.otherId, r.type); else await store.removeLink(r.otherId, id, r.type);
          route();
        } }, icon('x'))))));
  }

  // 类比候选（零 token 规则）
  const an = findAnalogies(id, store, 3);
  if (an.length) {
    out.push(h('h2', { class: 'section' }, '结构相似', h('span', { class: 'more', onclick: () => go('#/ask/' + id + '?mode=analogy') }, '请 AI 判断')));
    out.push(h('div', { class: 'list' }, an.map((a) => nodeRow(a.node, () => go('#/node/' + a.node.id), h('span', { class: 'pill outline small' }, a.reasons[0].split('：')[0])))));
  }

  const asks = askHistory(id);
  if (asks.length) {
    out.push(h('h2', { class: 'section' }, '上次提问'));
    const a = asks[0];
    out.push(h('div', { class: 'card', onclick: () => { session.lastAnswer = a; go('#/ask/' + id + '?show=1'); }, style: { cursor: 'pointer' } },
      h('div', { class: 'small muted' }, a.date + ' · ' + a.question.slice(0, 40)),
      h('div', { class: 'serif', style: { fontWeight: 600, marginTop: '4px' } }, parseAnswer(a.raw).core || a.raw.slice(0, 60))));
  }
  return out;
}

function addRelationSheet(n, preset = {}) {
  sheet('添加关系', (box, close) => {
    let dir = 'out';
    const type = h('select', { class: 'input' }, REL_TYPES.map((t) => h('option', { value: t, selected: t === preset.type }, t + ' — ' + REL_HINT[t])));
    const why = h('input', { class: 'input', placeholder: '一句理由' });
    const q = h('input', { class: 'input', placeholder: '搜索目标节点', type: 'search' });
    let target = null;
    const listBox = h('div', { class: 'list', style: { maxHeight: '260px', overflowY: 'auto' } });
    const draw = () => {
      listBox.innerHTML = '';
      const items = store.list().filter((x) => x.id !== n.id && (!q.value || x.title.includes(q.value) || x.keywords.some((k) => k.includes(q.value)))).slice(0, 30);
      for (const x of items) listBox.append(nodeRow(x, () => { target = x; draw(); }, target?.id === x.id ? icon('check') : h('span', { class: 'small muted' }, x.system)));
    };
    q.addEventListener('input', draw);
    const seg = h('div', { class: 'seg' },
      h('button', { class: 'on', onclick: (e) => { dir = 'out'; seg.querySelectorAll('button').forEach((b) => b.classList.remove('on')); e.target.classList.add('on'); } }, '本节点 → 目标'),
      h('button', { onclick: (e) => { dir = 'in'; seg.querySelectorAll('button').forEach((b) => b.classList.remove('on')); e.target.classList.add('on'); } }, '目标 → 本节点'));
    draw();
    box.append(h('div', { class: 'field' }, h('label', {}, '方向'), seg),
      h('div', { class: 'field' }, h('label', {}, '关系类型（固定 7 种）'), type),
      h('div', { class: 'field' }, h('label', {}, '目标节点'), q), listBox,
      h('div', { class: 'field mt' }, h('label', {}, '理由'), why),
      h('button', { class: 'btn primary block', onclick: async () => {
        if (!target) return toast('先选一个目标节点');
        const ok = dir === 'out' ? await store.addLink(n.id, { type: type.value, to: target.id, why: why.value.trim() })
          : await store.addLink(target.id, { type: type.value, to: n.id, why: why.value.trim() });
        if (!ok) return toast('这条关系已存在');
        close(); route();
      } }, '添加'));
  });
}

// ---------------- 回填（假设验证） ----------------
function backfillSheet(n) {
  sheet('回填验证结果', (box, close) => {
    let status = n.status;
    const result = h('textarea', { class: 'input', rows: 3, placeholder: '这周观察到了什么？例如：记录 7 天，舒缓感后多做一步 2 次' });
    const confLabel = h('span', {}, (n.confidence ?? 50) + '%');
    const conf = h('input', { type: 'range', min: 0, max: 100, step: 5, value: n.confidence ?? 50, oninput: () => { confLabel.textContent = conf.value + '%'; } });
    const seg = h('div', { class: 'seg' }, STATUSES.map((s) => h('button', { class: s === status ? 'on' : '', onclick: (e) => {
      status = s; seg.querySelectorAll('button').forEach((b) => b.classList.remove('on')); e.target.classList.add('on');
    } }, s)));
    box.append(h('div', { class: 'card flat', style: { marginBottom: '14px' } }, h('div', { class: 'serif', style: { fontWeight: 600 } }, n.title), h('div', { class: 'small muted' }, '验证方式：' + (n.verify || '未填'))),
      h('div', { class: 'field' }, h('label', {}, '验证结果'), result),
      h('div', { class: 'field' }, h('label', {}, '状态'), seg),
      h('div', { class: 'field' }, h('label', {}, '把握 ', confLabel), conf),
      h('button', { class: 'btn primary block', 'data-testid': 'backfill-save', onclick: async () => {
        if (!result.value.trim()) return toast('写一句验证结果');
        n.checks.push({ date: today(), result: result.value.trim(), status, confidence: +conf.value });
        n.status = status; n.confidence = +conf.value;
        await store.saveNode(n);
        close(); route(); toast('已回填：' + status + ' · ' + conf.value + '%');
      } }, '保存'));
  });
}

// ---------------- 提问 / 延展 / 类比 ----------------
function askHistory(id) { try { return JSON.parse(localStorage.getItem('mailuo.asks') || '{}')[id] || []; } catch { return []; } }
function pushAsk(id, rec) {
  let all = {};
  try { all = JSON.parse(localStorage.getItem('mailuo.asks') || '{}'); } catch { /* 重建 */ }
  all[id] = [rec, ...(all[id] || [])].slice(0, 3);
  localStorage.setItem('mailuo.asks', JSON.stringify(all));
}

function viewAsk(id, params) {
  const n = store.nodes.get(id);
  if (!n) return [topbarBack('提问'), h('div', { class: 'empty' }, h('h3', {}, '找不到这个节点'))];
  let mode = params.get('mode') || 'ask';
  const useChain = params.get('chain') === '1';
  const budget = getConfig().budget || 1500;
  if (session.packFor !== id) { session.packRemoved = new Set(); session.packFor = id; }
  const q = h('textarea', { class: 'input', rows: 3, placeholder: '针对这个节点，你想问什么？', 'data-testid': 'question' }, MODE_QUESTIONS[mode] || '');
  const packBox = h('div');
  const answerBox = h('div');
  let showRaw = false;
  let chain = null;
  if (useChain) { const t = traceCause(store, id); chain = longestChain(t.up, n.title); }

  const drawPack = () => {
    const pack = buildPack(store, id, { question: q.value.trim(), mode, removed: session.packRemoved, budget, chain });
    packBox.innerHTML = '';
    const pct = Math.min(100, (pack.tokens / budget) * 100);
    packBox.append(h('div', { class: 'card' },
      h('div', { class: 'budget' }, h('span', { class: 'label' }, '打包'), h('div', { class: 'track' }, h('div', { class: 'fill' + (pack.tokens > budget ? ' over' : ''), style: { width: pct + '%' } })),
        h('span', { class: 'num', 'data-testid': 'pack-tokens' }, pack.tokens + ' / ' + budget)),
      h('div', { class: 'small muted', style: { margin: '8px 0 10px' } }, '只发这个节点的路径和一跳邻居，不发整库。点 ✕ 可手动删减，超预算时自动先砍邻居说明、再砍远层路径；节点本身永不砍。'),
      pack.items.map((it) => h('div', { class: 'pack-item ' + it.state },
        h('div', { class: 'k' }, it.kind),
        h('div', { class: 'x' }, it.state === 'short' ? it.short : it.full),
        it.fixed ? null : h('button', { class: 'tog', 'aria-label': it.state === 'removed' ? '恢复' : '删减', onclick: () => {
          session.packRemoved.has(it.key) ? session.packRemoved.delete(it.key) : session.packRemoved.add(it.key);
          drawPack();
        } }, icon(it.state === 'removed' ? 'undo' : 'x')))),
      h('button', { class: 'btn ghost sm mt', onclick: () => { showRaw = !showRaw; drawPack(); } }, icon('eye'), showRaw ? '收起全文' : '预览发送全文'),
      showRaw ? h('div', { class: 'raw mt', 'data-testid': 'pack-raw' }, PROMPT_THINK + '\n\n' + pack.text) : null));
    return pack;
  };
  q.addEventListener('input', () => drawPack());

  const send = h('button', { class: 'btn accent block', 'data-testid': 'send', onclick: async () => {
    if (!q.value.trim()) return toast('先写下问题');
    const pack = drawPack();
    answerBox.innerHTML = '';
    answerBox.append(waiting('旗舰模型推演中…'));
    send.disabled = true;
    answerBox.scrollIntoView({ behavior: 'smooth', block: 'start' });
    try {
      const res = await callModel('think', PROMPT_THINK, pack.text, { maxTokens: 2000 });
      const rec = { date: today(), mode, question: q.value.trim(), raw: res.text, sent: res.sent, recv: res.recv, packTokens: pack.tokens };
      pushAsk(id, rec);
      answerBox.innerHTML = '';
      answerBox.append(answerCard(n, rec));
    } catch (e) {
      answerBox.innerHTML = '';
      answerBox.append(h('div', { class: 'banner' }, e.message));
    } finally { send.disabled = false; }
  } }, icon('send'), '发送给旗舰模型');

  const segBtn = (m, label) => h('button', { class: m === mode ? 'on' : '', onclick: (e) => {
    mode = m; q.value = MODE_QUESTIONS[m] || ''; session.packRemoved = new Set();
    e.target.parentElement.querySelectorAll('button').forEach((b) => b.classList.remove('on')); e.target.classList.add('on');
    drawPack();
  } }, label);

  const out = [topbarBack(n.title),
    h('div', { class: 'seg' }, segBtn('ask', '提问'), segBtn('extend', '延展'), segBtn('analogy', '类比')),
    h('div', { class: 'field mt' }, q),
    h('h2', { class: 'section' }, '发送前预览'), packBox,
    h('div', { class: 'mt' }, send), answerBox];
  drawPack();
  if (params.get('show') && session.lastAnswer) answerBox.append(answerCard(n, session.lastAnswer));
  return out;
}

function answerCard(n, rec) {
  const a = parseAnswer(rec.raw);
  let raw = !a.ok;
  const box = h('div', { class: 'card answer mt2', 'data-testid': 'answer' });
  const draw = () => {
    box.innerHTML = '';
    box.append(h('div', { class: 'small muted' }, `发送 ${rec.sent ?? '?'} · 收到 ${rec.recv ?? '?'} token · 打包 ${rec.packTokens ?? '?'}`));
    if (raw) {
      box.append(h('div', { class: 'raw mt' }, rec.raw));
    } else {
      box.append(h('div', { class: 'core', 'data-testid': 'core' }, a.core));
      if (a.chain.length) box.append(h('div', { class: 'blk' }, h('div', { class: 'h' }, '结构'), h('div', { class: 'chain' }, a.chain.map((c, i) => [i ? h('span', { class: 'arr' }, '→') : null, h('span', { class: 'c' }, c)]))));
      if (a.alt) box.append(h('div', { class: 'blk' }, h('div', { class: 'h' }, '另一种可能'), a.alt));
      if (a.wrong) box.append(h('div', { class: 'blk' }, h('div', { class: 'h' }, '如果我错了'), a.wrong));
      box.append(h('div', { class: 'blk' }, h('div', { class: 'verify-box' }, dial(a.confidencePct ?? null, 64, true),
        h('div', { class: 'vt' }, h('div', { class: 'h' }, '本周验证'), a.verify || '—',
          a.verify ? h('div', {}, h('button', { class: 'btn sm mt', onclick: async () => { n.verify = a.verify; await store.saveNode(n); toast('已设为验证方式'); } }, '设为验证方式')) : null))));
      if (a.suggest.length) {
        box.append(h('div', { class: 'blk' }, h('div', { class: 'h' }, '建议新增'),
          h('div', { class: 'list' }, a.suggest.map((s) => h('div', { class: 'link-cand rel-' + s.type }, relTag(s.type), h('div', { class: 'grow' }, h('b', {}, s.title), h('div', { class: 'why' }, s.claim))))),
          h('button', { class: 'btn primary block mt', 'data-testid': 'to-cands', onclick: () => suggestionsToCands(n, a.suggest) }, '转为候选卡片，逐张确认')));
      }
    }
    box.append(h('button', { class: 'btn ghost sm mt', onclick: () => { raw = !raw; draw(); } }, raw ? '结构化显示' : '看原文'));
  };
  draw();
  return box;
}

function suggestionsToCands(origin, suggest) {
  const nodes = suggest.map((s, i) => ({
    tmp_id: 's' + (i + 1), title: s.title, type: '论断', claim: s.claim || s.title, keywords: [], status: '假设', confidence: 50,
    verify: '', merge_into: '', parent: origin.parent,
  }));
  const links = suggest.map((s, i) => ({ from: 's' + (i + 1), to: origin.id, type: s.type, why: 'AI 建议（' + today() + '）', on: true }));
  startCands({ nodes, links, system: origin.system, source: getConfig().think.label || 'AI 推演', origin: origin.id });
}

// ---------------- 录入与拆分 ----------------
function viewCapture() {
  const systems = [...store.systems.keys()];
  const sysSel = h('select', { class: 'input', 'data-testid': 'capture-sys' }, systems.map((s) => h('option', { value: s, selected: s === session.sys }, s)), h('option', { value: '__new' }, '＋ 新建体系…'));
  const newSys = h('input', { class: 'input', placeholder: '新体系名称', style: { display: systems.length ? 'none' : '' } });
  if (!systems.length) sysSel.value = '__new';
  sysSel.addEventListener('change', () => { newSys.style.display = sysSel.value === '__new' ? '' : 'none'; });
  const source = h('input', { class: 'input', value: localStorage.getItem('mailuo.lastSource') || 'Claude 回复', placeholder: '自己 / AI 名称 / 资料' });
  const draft = localStorage.getItem('mailuo.captureDraft') || '';
  const text = h('textarea', { class: 'input', rows: 10, placeholder: '粘贴一段笔记或 AI 回复…', style: { minHeight: '220px' }, 'data-testid': 'capture-text' }, draft);
  const count = h('span', { class: 'small muted' }, draft.length + ' 字');
  text.addEventListener('input', () => { count.textContent = text.value.length + ' 字 · 约 ' + estimateTokens(text.value) + ' token'; localStorage.setItem('mailuo.captureDraft', text.value); });
  const status = h('div');
  const btn = h('button', { class: 'btn accent block', 'data-testid': 'split', onclick: async () => {
    const system = sysSel.value === '__new' ? newSys.value.trim() : sysSel.value;
    if (!system) return toast('选择或新建一个体系');
    if (text.value.trim().length < 10) return toast('材料太短');
    localStorage.setItem('mailuo.lastSource', source.value.trim());
    btn.disabled = true;
    status.innerHTML = '';
    status.append(waiting('便宜模型拆分中，目标 30 秒内'));
    try {
      const existing = store.list(system).slice(0, 80);
      const res = await callModel('split', PROMPT_SPLIT, splitUserMessage(existing, text.value.trim()), { maxTokens: 3000 });
      const data = normalizeSplit(extractJson(res.text));
      if (!data.nodes.length) throw new Error('没有拆出节点，换一段材料试试');
      if (!store.systems.has(system)) await store.addSystem(system);
      session.sys = system;
      startCands({ ...data, system, source: source.value.trim() });
      localStorage.removeItem('mailuo.captureDraft');
    } catch (e) {
      status.innerHTML = '';
      status.append(h('div', { class: 'banner' }, e.message));
    } finally { btn.disabled = false; }
  } }, icon('spark'), '拆分为候选卡片');
  const out = [topbarBack('录入'), h('h1', { class: 'display' }, '录入与拆分'),
    h('p', { class: 'sub' }, '便宜模型只负责拆，节点和关系都要你逐张确认才会写入文件。'),
    session.cands ? h('div', { class: 'banner', style: { cursor: 'pointer' }, onclick: () => go('#/cands') }, icon('layers'), '有 ' + session.cands.nodes.length + ' 张候选卡片还没处理完，点此继续') : null,
    h('div', { class: 'row mt' }, h('div', { class: 'field' }, h('label', {}, '目标体系'), sysSel, newSys), h('div', { class: 'field' }, h('label', {}, '来源'), source)),
    h('div', { class: 'field' }, h('label', {}, '材料 ', count), text),
    btn, status,
    h('div', { class: 'center mt' }, h('button', { class: 'btn ghost', onclick: () => newNodeSheet(sysSel.value === '__new' ? '' : sysSel.value, '') }, '不用 AI，手动新建一个节点'))];
  return out;
}

function startCands(data) {
  const nodes = data.nodes.map((c) => {
    const dupe = c.merge_into && store.nodes.has(c.merge_into) ? { node: store.nodes.get(c.merge_into), reason: '模型判断含义相同' } : findDuplicate(c, store);
    return { ...c, parent: c.parent || '', state: c.merge_into && store.nodes.has(c.merge_into) ? 'pending' : 'pending', dupeId: dupe?.node.id || '', dupeReason: dupe?.reason || '', mergeTo: '' };
  });
  session.cands = { ...data, nodes, links: data.links.map((l) => ({ ...l, on: l.on ?? true })) };
  saveDraft();
  go('#/cands');
}

function candTitle(ref) {
  const c = session.cands.nodes.find((x) => x.tmp_id === ref);
  if (c) return c.title + (c.state === 'discarded' ? '（已丢弃）' : '');
  const n = store.nodes.get(ref) || store.byTitle(ref);
  return n ? n.title + '（已有）' : ref + '（未知）';
}

function viewCands() {
  const C = session.cands;
  if (!C) return [topbarBack('候选'), h('div', { class: 'empty' }, veinArt(), h('h3', {}, '没有待确认的候选'), h('button', { class: 'btn accent', onclick: () => go('#/capture') }, '去录入'))];
  const counts = { accepted: 0, merged: 0, discarded: 0, pending: 0 };
  for (const c of C.nodes) counts[c.state]++;
  const resolvable = (ref) => {
    const c = C.nodes.find((x) => x.tmp_id === ref);
    if (c) return c.state === 'accepted' || c.state === 'merged';
    return store.nodes.has(ref) || !!store.byTitle(ref);
  };
  const linkCount = C.links.filter((l) => l.on && resolvable(l.from) && resolvable(l.to)).length;
  const persist = () => { saveDraft(); route(); };
  const out = [topbarBack('候选卡片', h('button', { class: 'btn ghost sm', onclick: async () => {
    if (!(await confirmSheet('放弃这批候选', '全部丢弃，不写入任何文件？', '全部放弃', true))) return;
    session.cands = null; localStorage.removeItem('mailuo.draft'); go('#/capture');
  } }, '放弃'))];
  out.push(h('h1', { class: 'display' }, C.nodes.length + ' 张候选'),
    h('div', { class: 'sub' }, '写入体系「' + C.system + '」 · 已接受 ' + counts.accepted + ' · 合并 ' + counts.merged + ' · 丢弃 ' + counts.discarded + ' · 待定 ' + counts.pending),
    h('div', { class: 'btn-row mt' },
      h('button', { class: 'btn sm', onclick: () => { for (const c of C.nodes) if (c.state === 'pending' && !c.dupeId) c.state = 'accepted'; persist(); } }, icon('check'), '接受全部无重复的'),
      h('button', { class: 'btn sm', onclick: () => { for (const c of C.nodes) if (c.state === 'pending') c.state = 'accepted'; persist(); } }, '接受全部待定')));

  const list = h('div', { class: 'list mt' });
  for (const c of C.nodes) {
    const badge = { accepted: '已接受', merged: '合并到「' + (store.nodes.get(c.mergeTo)?.title || '') + '」', discarded: '已丢弃', pending: '' }[c.state];
    const dupeNode = c.dupeId ? store.nodes.get(c.dupeId) : null;
    list.append(h('div', { class: 'card cand ' + c.state, 'data-testid': 'cand' },
      badge ? h('span', { class: 'state-badge' }, badge) : null,
      h('div', { class: 'head' }, glyph(c), h('div', { class: 'title' }, c.title), dial(c.confidence, 36)),
      h('div', { class: 'chips', style: { marginTop: '8px' } }, h('span', { class: 'pill' }, c.type), h('span', { class: 'pill ' + c.status }, c.status)),
      h('div', { class: 'claim' }, c.claim),
      h('div', { class: 'chips', style: { marginBottom: '10px' } }, c.keywords.length ? c.keywords.map((k) => h('span', { class: 'kw' }, k)) : h('span', { class: 'small', style: { color: 'var(--amber)' } }, '激活词待补（点「修改」）')),
      c.status === '假设' ? h('div', { class: 'verify' }, '验证：' + (c.verify || '（未给出，接受前请补）')) : null,
      dupeNode && c.state === 'pending' ? h('div', { class: 'dupe', 'data-testid': 'dupe' }, icon('merge'), h('span', { class: 'grow' }, '可能重复：「' + dupeNode.title + '」· ' + c.dupeReason),
        h('button', { class: 'btn sm', onclick: () => { c.state = 'merged'; c.mergeTo = dupeNode.id; persist(); } }, '合并'),
        h('button', { class: 'btn sm ghost', onclick: () => { c.dupeId = ''; persist(); } }, '不是重复')) : null,
      h('div', { class: 'btn-row' },
        c.state === 'pending'
          ? [h('button', { class: 'btn sm primary', 'data-testid': 'accept', onclick: () => {
              if (c.status === '假设' && !c.verify) { toast('假设类要先补验证方式'); return editCandSheet(c, persist); }
              c.state = 'accepted'; persist();
            } }, icon('check'), '接受'),
            h('button', { class: 'btn sm', onclick: () => editCandSheet(c, persist) }, icon('edit'), '修改'),
            h('button', { class: 'btn sm', onclick: () => mergePicker(c, persist) }, icon('merge'), '合并到…'),
            h('button', { class: 'btn sm ghost danger', onclick: () => { c.state = 'discarded'; persist(); } }, '丢弃')]
          : h('button', { class: 'btn sm ghost', onclick: () => { c.state = 'pending'; c.mergeTo = ''; persist(); } }, icon('undo'), '撤销'))));
  }
  out.push(list);

  if (C.links.length) {
    out.push(h('h2', { class: 'section' }, '候选关系 · ' + C.links.length));
    out.push(h('div', { class: 'list' }, C.links.map((l) => {
      const ok = resolvable(l.from) && resolvable(l.to);
      const typeSel = h('select', { class: 'input', style: { width: 'auto', padding: '4px 8px', fontSize: '12.5px' }, onchange: () => { l.type = typeSel.value; saveDraft(); } }, REL_TYPES.map((t) => h('option', { value: t, selected: t === l.type }, t)));
      return h('div', { class: 'link-cand' + (l.on && ok ? '' : ' off') },
        h('div', { class: 'grow' }, h('div', {}, h('b', {}, candTitle(l.from)), ' ', typeSel, ' ', h('b', {}, candTitle(l.to))), l.why ? h('div', { class: 'why' }, l.why) : null,
          !ok ? h('div', { class: 'why' }, '两端节点都被接受 / 合并后才会写入') : null),
        h('button', { class: 'iconbtn', 'aria-label': '调换方向', onclick: () => { [l.from, l.to] = [l.to, l.from]; persist(); } }, icon('swap')),
        h('button', { class: 'iconbtn', 'aria-label': l.on ? '不要这条' : '恢复', onclick: () => { l.on = !l.on; persist(); } }, icon(l.on ? 'x' : 'undo')));
    })));
  }
  const n = counts.accepted + counts.merged;
  out.push(h('div', { class: 'sticky-bottom' }, h('button', { class: 'btn primary block', disabled: !n, 'data-testid': 'commit', onclick: commitCands },
    '写入 ' + counts.accepted + ' 个新节点 · 合并 ' + counts.merged + ' · ' + linkCount + ' 条关系')),
  counts.pending ? h('p', { class: 'small muted center' }, '待定的卡片不会写入，可以稍后回来处理。') : null);
  return out;
}

function editCandSheet(c, done) {
  sheet('修改候选', (box, close) => {
    if (!store.systems.has(session.cands.system)) store.ensureSystemSync(session.cands.system);
    const form = nodeForm({ ...c, system: session.cands.system, source: session.cands.source }, { systemEditable: false });
    box.append(form.el, h('button', { class: 'btn primary block', onclick: () => {
      const d = form.read();
      const err = validate(d);
      if (err) return toast(err);
      Object.assign(c, d, { state: 'accepted' });
      close(); done();
    } }, '保存并接受'));
  });
}

function mergePicker(c, done) {
  sheet('合并到已有节点', (box, close) => {
    const q = h('input', { class: 'input', type: 'search', placeholder: '搜索', value: '' });
    const list = h('div', { class: 'list mt', style: { maxHeight: '50vh', overflowY: 'auto' } });
    const draw = () => {
      list.innerHTML = '';
      for (const n of store.list().filter((x) => !q.value || x.title.includes(q.value)).slice(0, 40)) {
        list.append(nodeRow(n, () => { c.state = 'merged'; c.mergeTo = n.id; close(); done(); }, h('span', { class: 'small muted' }, n.system)));
      }
    };
    q.addEventListener('input', draw);
    draw();
    box.append(h('p', { class: 'small muted' }, '合并：激活词并入已有节点，结论不同则补进说明；关系改挂到已有节点上。'), q, list);
  });
}

async function commitCands() {
  const C = session.cands;
  const idMap = new Map();
  const touched = new Map();
  // 1) 新节点先全部放进索引，保证写双链时能找到标题
  for (const c of C.nodes.filter((x) => x.state === 'accepted')) {
    const id = store.nextId();
    const n = {
      id, title: c.title, type: c.type, system: c.system || C.system, parent: c.parent || '', keywords: c.keywords, status: c.status,
      confidence: c.confidence, verify: c.verify, source: c.source || C.source, claim: c.claim, note: c.note || '', links: [], checks: [], extra: {},
    };
    store.nodes.set(id, n);
    idMap.set(c.tmp_id, id);
    touched.set(id, n);
  }
  for (const c of C.nodes.filter((x) => x.state === 'merged')) {
    const t = store.nodes.get(c.mergeTo);
    if (!t) continue;
    idMap.set(c.tmp_id, t.id);
    t.keywords = [...new Set([...t.keywords, ...c.keywords])].slice(0, 5);
    if (c.claim && c.claim !== t.claim && !t.note.includes(c.claim) && t.note.split('\n').filter(Boolean).length < 3) t.note = (t.note ? t.note + '\n' : '') + '补充：' + c.claim;
    touched.set(t.id, t);
  }
  const resolve = (ref) => idMap.get(ref) || (store.nodes.has(ref) ? ref : store.byTitle(ref, C.system)?.id);
  let linkN = 0;
  for (const l of C.links.filter((x) => x.on)) {
    const from = resolve(l.from), to = resolve(l.to);
    if (!from || !to || from === to || !REL_TYPES.includes(l.type)) continue;
    // 来自已丢弃 / 待定卡片的关系不写
    const fromC = C.nodes.find((x) => x.tmp_id === l.from), toC = C.nodes.find((x) => x.tmp_id === l.to);
    if ((fromC && !['accepted', 'merged'].includes(fromC.state)) || (toC && !['accepted', 'merged'].includes(toC.state))) continue;
    const src = store.nodes.get(from);
    if (src.links.some((x) => x.to === to && x.type === l.type)) continue;
    src.links.push({ type: l.type, to, why: l.why });
    touched.set(from, src);
    linkN++;
  }
  for (const n of touched.values()) await store.saveNode(n);
  const left = C.nodes.filter((x) => x.state === 'pending');
  const created = [...idMap.values()];
  if (left.length) {
    session.cands = { ...C, nodes: left, links: C.links.filter((l) => left.some((c) => c.tmp_id === l.from || c.tmp_id === l.to)).map((l) => ({ ...l, from: idMap.get(l.from) || l.from, to: idMap.get(l.to) || l.to })) };
    saveDraft();
  } else { session.cands = null; localStorage.removeItem('mailuo.draft'); }
  toast('已写入 ' + touched.size + ' 个文件 · ' + linkN + ' 条关系');
  if (C.origin && store.nodes.has(C.origin)) go('#/node/' + C.origin);
  else if (created.length) go('#/tree?sys=' + encodeURIComponent(C.system));
  else go('#/');
}

// ---------------- 验证（假设列表） ----------------
function verifyTabs(cur) {
  return h('div', { class: 'seg' },
    h('button', { class: cur === 'verify' ? 'on' : '', onclick: () => go('#/verify') }, '假设'),
    h('button', { class: cur === 'health' ? 'on' : '', onclick: () => go('#/health') }, '体检'));
}

function viewVerify() {
  const hyp = store.list().filter((n) => n.status === '假设');
  const done = store.list().filter((n) => n.status !== '假设');
  const out = [topbarBrand(), h('h1', { class: 'display' }, '假设与验证'), verifyTabs('verify'),
    h('p', { class: 'sub mt' }, '假设类节点单独列出。每周做一次「验证」里写的小动作，再回来回填结果、调整状态和把握。')];
  if (!hyp.length) out.push(h('div', { class: 'empty' }, h('h3', {}, '没有待验证的假设')));
  out.push(h('div', { class: 'list mt' }, hyp.map((n) => {
    const last = n.checks.at(-1);
    return h('div', { class: 'card', 'data-testid': 'hyp' },
      h('div', { class: 'head', style: { display: 'flex', gap: '12px', alignItems: 'center' } }, dial(n.confidence, 48),
        h('div', { class: 'grow', style: { cursor: 'pointer' }, onclick: () => go('#/node/' + n.id) }, h('div', { class: 'serif', style: { fontWeight: 600, fontSize: '16px' } }, n.title), h('div', { class: 'small muted' }, n.system + (n.parent ? ' › ' + n.parent : '')))),
      h('div', { class: 'small', style: { margin: '10px 0', padding: '8px 10px', background: 'var(--amber-soft)', color: 'var(--amber)', borderRadius: '8px' } }, '验证：' + (n.verify || '未填 ⚠')),
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '8px' } },
        h('span', { class: 'small muted grow' }, last ? '上次回填 ' + last.date + '：' + last.result.slice(0, 24) : '还没回填过'),
        h('button', { class: 'btn sm primary', onclick: () => backfillSheet(n) }, '回填')));
  })));
  if (done.length) {
    out.push(h('h2', { class: 'section' }, '已验证 / 已推翻 · ' + done.length));
    out.push(h('div', { class: 'list' }, done.map((n) => nodeRow(n, () => go('#/node/' + n.id)))));
  }
  return out;
}

// ---------------- 体检 ----------------
function viewHealth(_, params) {
  const sys = params.get('sys') || '';
  const r = healthCheck(store, sys || undefined);
  const out = [topbarBrand(), h('h1', { class: 'display' }, '结构体检'), verifyTabs('health'),
    h('div', { class: 'sys-chips mt' }, h('button', { class: 'sys-chip' + (!sys ? ' on' : ''), onclick: () => go('#/health') }, '全部'),
      [...store.systems.keys()].map((s) => h('button', { class: 'sys-chip' + (s === sys ? ' on' : ''), onclick: () => go('#/health?sys=' + encodeURIComponent(s)) }, s))),
    h('div', { class: 'card score' }, dial(r.score, 92, true, '健康分'),
      h('div', {}, h('div', { class: 'serif', style: { fontSize: '18px', fontWeight: 600 } }, r.score >= 85 ? '结构清楚' : r.score >= 60 ? '有几处要补' : '故事多于证据'),
        h('div', { class: 'small muted' }, r.total + ' 个节点 · ' + r.issues.length + ' 类问题 · 不耗 token')))];
  if (!r.issues.length) out.push(h('div', { class: 'empty' }, h('h3', {}, '没有发现问题')));
  out.push(h('div', { class: 'list mt' }, r.issues.map((i) => h('div', { class: 'card issue' },
    h('div', { class: 'ih' }, h('span', { class: 'lvl ' + i.level }), i.label, h('span', { class: 'small muted', style: { marginLeft: 'auto' } }, i.nodes.length)),
    h('div', { class: 'chips' }, i.nodes.slice(0, 12).map((n) => h('span', { class: 'kw', onclick: () => go('#/node/' + n.id) }, n.title)))))));
  return out;
}

// ---------------- 温故 ----------------
function viewReview() {
  const queue = dueReviews(store, 20);
  const out = [topbarBack('温故'), h('h1', { class: 'display' }, '温故'), h('p', { class: 'sub' }, '看标题和激活词，先自己想出结论，再翻面核对。间隔 1·3·7·15·30·60 天。')];
  if (!queue.length) {
    out.push(h('div', { class: 'empty' }, veinArt(), h('h3', {}, '今天的卡片都复习完了'), h('p', {}, '明天再来。')));
    return out;
  }
  let i = 0;
  const stage = h('div');
  const draw = () => {
    stage.innerHTML = '';
    if (i >= queue.length) { stage.append(h('div', { class: 'empty' }, veinArt(), h('h3', {}, '完成 ' + queue.length + ' 张'), h('button', { class: 'btn', onclick: () => go('#/') }, '回到今日'))); return; }
    const n = queue[i];
    const flash = h('div', { class: 'flash', 'data-testid': 'flash', onclick: () => flash.classList.toggle('flipped') },
      h('div', { class: 'flash-inner' },
        h('div', { class: 'flash-face' },
          h('div', { class: 'small muted' }, (i + 1) + ' / ' + queue.length + ' · ' + n.system + (n.parent ? ' › ' + n.parent : '')),
          h('div', {}, h('div', { class: 'big' }, n.title), h('div', { class: 'chips mt' }, n.keywords.map((k) => h('span', { class: 'kw' }, k)))),
          h('div', { class: 'small muted' }, '点击翻面')),
        h('div', { class: 'flash-face back' },
          h('div', { class: 'small', style: { opacity: .6 } }, n.type + ' · ' + n.status + (n.confidence != null ? ' · 把握 ' + n.confidence + '%' : '')),
          h('div', { class: 'big' }, n.claim),
          n.status === '假设' ? h('div', { class: 'small', style: { opacity: .75 } }, '本周验证了吗？' + (n.verify || '')) : h('div'))));
    const grade = async (g) => { const d = await gradeReview(store, n.id, g); toast(d + ' 天后再见'); i++; draw(); };
    stage.append(flash, h('div', { class: 'grades' },
      h('button', { class: 'btn', onclick: () => grade('forgot') }, '忘了'),
      h('button', { class: 'btn', onclick: () => grade('fuzzy') }, '模糊'),
      h('button', { class: 'btn primary', onclick: () => grade('good') }, '记得')),
    h('div', { class: 'center mt' }, h('button', { class: 'btn ghost sm', onclick: () => go('#/node/' + n.id) }, '打开节点')));
  };
  draw();
  out.push(stage);
  return out;
}

// ---------------- 溯源 ----------------
function viewTrace(id) {
  const n = store.nodes.get(id);
  if (!n) return [topbarBack('溯源')];
  const t = traceCause(store, id);
  const rootIds = new Set(t.roots.map((r) => r.id));
  const renderTree = (tree, dir) => tree.map((c) => h('div', { class: 't-node' + (dir === 'up' && rootIds.has(c.node.id) && !c.children.length ? ' root' : '') },
    nodeRow(c.node, () => go('#/node/' + c.node.id), dir === 'up' && !c.children.length ? h('span', { class: 'pill accent' }, '根因候选') : null),
    c.why ? h('div', { class: 'why' }, (dir === 'up' ? '导致下一层：' : '因为：') + c.why) : null,
    c.children.length ? h('div', { class: 't-children' }, renderTree(c.children, dir)) : null));
  const path = learningPath(store, id);
  const out = [topbarBack('溯源'), h('h1', { class: 'display' }, '溯源'),
    h('p', { class: 'sub' }, '沿「导致」往上找根因、往下看后果；沿「前置」排学习顺序。不耗 token。'),
    h('h2', { class: 'section' }, '上游 · 根因方向'),
    t.up.length ? h('div', { class: 'trace-col' }, renderTree(t.up, 'up')) : h('div', { class: 'empty-rel' }, '没有「导致」它的节点。它本身可能就是根因，或者还缺上游。'),
    h('div', { class: 't-self' }, n.title, h('div', { class: 'small', style: { opacity: .7, fontFamily: 'var(--sans)', fontWeight: 400 } }, n.claim)),
    h('h2', { class: 'section' }, '下游 · 后果'),
    t.down.length ? h('div', { class: 'trace-col down' }, renderTree(t.down, 'down')) : h('div', { class: 'empty-rel' }, '没有它「导致」的节点。')];
  if (t.roots.length) {
    out.push(h('div', { class: 'card mt2' }, h('div', { class: 'serif', style: { fontWeight: 600 } }, '最长因果链'),
      h('div', { class: 'chain mt' }, longestChain(t.up, n.title).map((c, i) => [i ? h('span', { class: 'arr' }, '→') : null, h('span', { class: 'c' }, c)])),
      h('button', { class: 'btn primary block mt', onclick: () => go('#/ask/' + id + '?chain=1') }, icon('send'), '带上因果链提问')));
  }
  if (path.length > 1) {
    out.push(h('h2', { class: 'section' }, '学习路线 · 前置'));
    out.push(h('div', { class: 'list' }, path.map((p, i) => nodeRow(p, () => go('#/node/' + p.id), h('span', { class: 'pill outline' }, '第 ' + (i + 1) + ' 步')))));
  }
  return out;
}

// ---------------- 设置 ----------------
function viewSettings() {
  const cfg = getConfig();
  const modelCard = (role, title, desc) => {
    const c = cfg[role];
    const preset = h('select', { class: 'input' }, Object.entries(PRESETS).map(([k, p]) => h('option', { value: k, selected: k === c.preset }, p.label)));
    const format = h('select', { class: 'input' }, h('option', { value: 'openai', selected: c.format === 'openai' }, 'OpenAI 兼容'), h('option', { value: 'anthropic', selected: c.format === 'anthropic' }, 'Anthropic'));
    const base = h('input', { class: 'input', value: c.base, placeholder: 'https://…', 'data-testid': role + '-base' });
    const model = h('input', { class: 'input', value: c.model, placeholder: '模型名', 'data-testid': role + '-model' });
    const key = h('input', { class: 'input', value: c.key, type: 'password', placeholder: 'API Key（只存在本机）', autocomplete: 'off', 'data-testid': role + '-key' });
    preset.addEventListener('change', () => { const p = PRESETS[preset.value]; format.value = p.format; base.value = p.base; model.value = p.model; });
    const read = () => ({ preset: preset.value, label: PRESETS[preset.value]?.label, format: format.value, base: base.value.trim(), model: model.value.trim(), key: key.value.trim() });
    const status = h('div', { class: 'small muted mt' });
    const card = h('div', { class: 'card' },
      h('div', { class: 'serif', style: { fontWeight: 600, fontSize: '17px' } }, title), h('div', { class: 'small muted', style: { marginBottom: '12px' } }, desc),
      h('div', { class: 'row' }, h('div', { class: 'field' }, h('label', {}, '服务商'), preset), h('div', { class: 'field' }, h('label', {}, '格式'), format)),
      h('div', { class: 'field' }, h('label', {}, '地址'), base),
      h('div', { class: 'field' }, h('label', {}, '模型名'), model),
      h('div', { class: 'field' }, h('label', {}, '密钥'), key),
      h('button', { class: 'btn sm', onclick: async () => {
        save(); status.textContent = '测试中…';
        try { const r = await callModel(role, '只回复 OK', 'ping', { maxTokens: 16 }); status.textContent = '连接成功：' + r.text.slice(0, 30); } catch (e) { status.textContent = e.message; }
      } }, '测试连接'), status);
    card.read = read;
    return card;
  };
  const split = modelCard('split', '拆分模型（便宜）', '拆分笔记、打激活词、查重 · 输出严格 JSON');
  const think = modelCard('think', '推演模型（旗舰）', '提问、延展、类比判断 · 结构先行格式');
  const budget = h('input', { class: 'input', type: 'number', min: 300, max: 8000, step: 100, value: cfg.budget, 'data-testid': 'budget' });
  const theme = h('div', { class: 'seg' }, [['auto', '跟随系统'], ['light', '浅色'], ['dark', '深色']].map(([k, l]) => h('button', { class: cfg.theme === k ? 'on' : '', onclick: () => { cfg.theme = k; save(); applyTheme(); route(); } }, l)));
  function save() {
    const c = getConfig();
    c.split = split.read(); c.think = think.read(); c.budget = Math.max(300, +budget.value || 1500); c.theme = cfg.theme;
    setConfig(c);
  }
  const fileIn = h('input', { type: 'file', accept: '.zip', style: { display: 'none' }, onchange: async () => {
    const f = fileIn.files[0];
    if (!f) return;
    const n = await importZip(vault, await f.arrayBuffer());
    await store.load(); toast('已导入 ' + n + ' 个文件'); route();
  } });
  const storage = h('div', { class: 'card' },
    h('div', { class: 'serif', style: { fontWeight: 600, fontSize: '17px' } }, '数据位置'),
    h('div', { class: 'small muted', style: { marginBottom: '12px' } }, '当前：' + vault.label + ' · ' + store.nodes.size + ' 个节点。每个节点是一个 Markdown 文件，可用 Obsidian 打开同一文件夹。'),
    h('div', { class: 'btn-row' },
      vault.kind !== 'cap' ? h('button', { class: 'btn sm', onclick: async () => {
        const blob = new Blob([await exportZip(vault)], { type: 'application/zip' });
        const a = h('a', { href: URL.createObjectURL(blob), download: '脉络-obsidian-' + today() + '.zip' });
        document.body.append(a); a.click(); a.remove();
      } }, icon('download'), '导出 Obsidian 仓库') : null,
      h('button', { class: 'btn sm', onclick: () => fileIn.click() }, icon('upload'), '导入 zip'), fileIn,
      FsVault.supported() && vault.kind !== 'fs' ? h('button', { class: 'btn sm', onclick: async () => {
        try {
          const v = await FsVault.pick();
          if (store.nodes.size && await confirmSheet('复制现有数据', '把浏览器里的 ' + store.nodes.size + ' 个节点也复制到这个文件夹？', '复制')) {
            for (const p of await vault.list()) await v.write(p, await vault.read(p));
          }
          localStorage.setItem('mailuo.vault', 'fs'); location.reload();
        } catch (e) { if (e.name !== 'AbortError') toast(e.message); }
      } }, icon('folder'), '改用本地文件夹') : null,
      vault.kind === 'fs' ? h('button', { class: 'btn sm', onclick: () => { localStorage.setItem('mailuo.vault', 'idb'); location.reload(); } }, '改回浏览器存储') : null,
      h('button', { class: 'btn sm ghost', onclick: async () => { await store.load(); toast('已重新读取 ' + store.nodes.size + ' 个节点'); } }, icon('refresh'), '重新读取')));
  return [topbarBrand(), h('h1', { class: 'display' }, '设置'),
    h('h2', { class: 'section' }, '模型'), split, think,
    h('h2', { class: 'section' }, '提问'),
    h('div', { class: 'card' }, h('div', { class: 'field', style: { margin: 0 } }, h('label', {}, '打包 token 预算（默认 1500）'), budget)),
    h('h2', { class: 'section' }, '外观'), theme,
    h('h2', { class: 'section' }, '数据'), storage,
    h('div', { class: 'sticky-bottom' }, h('button', { class: 'btn primary block', 'data-testid': 'save-settings', onclick: () => { save(); toast('已保存'); } }, '保存设置')),
    h('p', { class: 'small muted center mt2' }, '脉络 v' + VERSION + ' · 密钥只保存在本机，不写入知识库文件')];
}

boot();
