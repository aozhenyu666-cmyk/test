// Tiny DOM helpers. Everything goes through textContent, so user data and model output
// can never inject HTML.

export function h(tag, attrs, ...children) {
  const [name, ...classes] = tag.split('.');
  const el = document.createElement(name || 'div');
  if (classes.length) el.className = classes.join(' ');
  if (attrs && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) {
    children.unshift(attrs);
    attrs = null;
  }
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className += (el.className ? ' ' : '') + v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'value') el.value = v;
    else if (k === 'checked') el.checked = Boolean(v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else el.setAttribute(k, v === true ? '' : v);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false || c === true) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export function mount(container, ...children) {
  container.replaceChildren();
  append(container, children);
}

export function toast(text, tone = 'neutral') {
  const box = document.getElementById('toasts');
  const el = h(`div.toast.tone-${tone}`, text);
  box.append(el);
  setTimeout(() => el.classList.add('out'), 2600);
  setTimeout(() => el.remove(), 3000);
}

/**
 * In-page confirmation. Native confirm() is blocked inside some viewers (it returns
 * false at once), so destructive actions ask here instead. Resolves true or false.
 */
export function ask(text, { ok = '确定', cancel = '取消', danger = false } = {}) {
  return new Promise((resolve) => {
    const done = (v) => {
      overlay.remove();
      document.removeEventListener('keydown', onKey);
      resolve(v);
    };
    const onKey = (e) => {
      if (e.key === 'Escape') done(false);
    };
    const okBtn = h(`button.btn.${danger ? 'danger-solid' : 'primary'}`, { type: 'button', onclick: () => done(true) }, ok);
    const overlay = h('div.overlay.center', { role: 'alertdialog', 'aria-modal': 'true', onclick: (e) => { if (e.target === overlay) done(false); } },
      h('div.dialog',
        h('p.dialog-text', text),
        h('div.row-wrap.dialog-actions', h('button.btn', { type: 'button', onclick: () => done(false) }, cancel), okBtn),
      ),
    );
    document.addEventListener('keydown', onKey);
    document.body.append(overlay);
    okBtn.focus();
  });
}

export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast('已复制', 'good');
  } catch {
    const ta = h('textarea', { style: { position: 'fixed', opacity: '0' } }, text);
    document.body.append(ta);
    ta.select();
    try {
      document.execCommand('copy');
      toast('已复制', 'good');
    } catch {
      toast('复制失败，请手动长按选择', 'warn');
    }
    ta.remove();
  }
}

export function go(hash) {
  if (location.hash === hash) window.dispatchEvent(new HashChangeEvent('hashchange'));
  else location.hash = hash;
}

export function fmtDate(iso) {
  if (!iso) return '';
  const [y, m, d] = iso.split('-');
  const thisYear = String(new Date().getFullYear());
  return y === thisYear ? `${Number(m)}月${Number(d)}日` : `${y}年${Number(m)}月${Number(d)}日`;
}

export function relDays(n) {
  if (n == null) return '无记录';
  if (n === 0) return '今天';
  if (n === 1) return '昨天';
  return `${n} 天前`;
}

/** Labeled form field wrapper. */
export function field(label, input, hint) {
  return h('label.field', h('span.field-label', label), input, hint ? h('span.field-hint', hint) : null);
}

/** Segmented control. options: {value: label} or [[value, label], ...] (use the array
 *  form when order matters: objects put integer-like keys first). */
export function segmented(options, value, onChange) {
  const pairs = Array.isArray(options) ? options : Object.entries(options);
  let current = value;
  const el = h('div.segmented', { role: 'radiogroup' });
  const render = () => {
    el.replaceChildren(
      ...pairs.map(([v, label]) =>
        h('button', {
          type: 'button',
          class: v === current ? 'on' : '',
          role: 'radio',
          'aria-checked': String(v === current),
          onclick: () => {
            current = v;
            render();
            onChange?.(v);
          },
        }, label),
      ),
    );
  };
  render();
  return {
    el,
    get value() {
      return current;
    },
    set value(v) {
      current = v;
      render();
    },
  };
}

export function emptyState(title, text, action) {
  return h('div.empty', h('div.empty-title', title), text ? h('p', text) : null, action || null);
}

export function tempColor(t) {
  if (t == null) return 'var(--muted)';
  if (t >= 65) return 'var(--good)';
  if (t >= 45) return 'var(--warn)';
  return 'var(--danger)';
}

/** Horizontal meter. value 0-100. */
export function meter(value, color) {
  const v = Math.max(0, Math.min(100, Number(value) || 0));
  return h('div.meter', h('div.meter-fill', { style: { width: `${v}%`, background: color || 'var(--accent)' } }));
}

/** Balance bar centered at 0: -1 (I receive more) … +1 (I give more). */
export function balanceBar(balance) {
  const b = Math.max(-1, Math.min(1, Number(balance) || 0));
  const width = Math.abs(b) * 50;
  const left = b >= 0 ? 50 : 50 - width;
  const color = Math.abs(b) > 0.45 ? 'var(--warn)' : 'var(--good)';
  return h('div.balance',
    h('div.balance-mid'),
    h('div.balance-fill', { style: { left: `${left}%`, width: `${width}%`, background: color } }),
  );
}
