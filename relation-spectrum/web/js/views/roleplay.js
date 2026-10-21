// Rehearsal window: the AI plays the other person for up to 3 rounds, starting from a chosen reply.

import { h, toast, meter, copyText } from '../ui/dom.js';
import * as api from '../services/api.js';

const MOODS = { warm: '缓和', open: '愿意聊', guarded: '有戒备', defensive: '防御中', hostile: '有敌意' };
const MAX_ROUNDS = 3;

/**
 * openRoleplay({ opening, situation, entity?, interactions?, analysis?, onDone?(transcript, debrief) })
 */
export function openRoleplay(ctx) {
  const history = [];
  let busy = false;
  let finished = false;

  const log = h('div.rp-log');
  const input = h('textarea', { rows: 2, placeholder: '你下一句想怎么回？' }, ctx.opening || '');
  const sendBtn = h('button.btn.primary', { type: 'button', onclick: () => send() }, '发送');
  const roundLabel = h('span.small.muted');
  const footer = h('div.rp-input', input, h('div.row-between', roundLabel, sendBtn));
  const who = ctx.entity?.name || 'TA';

  const close = () => {
    overlay.remove();
    document.body.classList.remove('no-scroll');
    document.removeEventListener('keydown', onKey);
  };
  const onKey = (e) => {
    if (e.key === 'Escape') close();
  };

  const overlay = h('div.overlay', { role: 'dialog', 'aria-modal': 'true', 'aria-label': `和${who}预演` },
    h('div.sheet',
      h('div.sheet-head.row-between',
        h('div', h('h3', `和 ${who} 预演`), h('p.small.muted', 'AI 扮演对方，最多 3 回合。不会真的发出去。')),
        h('button.btn.small.ghost', { type: 'button', onclick: close }, '关闭')),
      log,
      footer,
    ),
  );

  const updateRound = () => {
    const n = history.filter((m) => m.role === 'me').length;
    roundLabel.textContent = finished ? '预演结束' : `第 ${Math.min(n + 1, MAX_ROUNDS)} / ${MAX_ROUNDS} 回合`;
  };

  function bubble(role, text) {
    return h(`div.bubble.${role}`, h('div.bubble-text', text));
  }

  async function send() {
    const text = input.value.trim();
    if (busy || finished || !text) return;
    busy = true;
    sendBtn.disabled = true;
    history.push({ role: 'me', text });
    log.append(bubble('me', text));
    input.value = '';
    const typing = h('div.bubble.them.loading', h('div.bubble-text', `${who} 正在输入…`));
    log.append(typing);
    log.scrollTop = log.scrollHeight;
    try {
      const { result: r } = await api.analyze('roleplay', {
        situation: ctx.situation,
        history,
        maxRounds: MAX_ROUNDS,
        entity: ctx.entity,
        interactions: ctx.interactions,
        analysis: ctx.analysis,
      });
      typing.remove();
      history.push({ role: 'them', text: r.reply });
      log.append(
        bubble('them', r.reply),
        h('div.rp-meta',
          h('details', h('summary', `TA 心里想 · ${MOODS[r.mood] || r.mood}`), h('p', r.inner_thought)),
          h('div.row-between.small', h('span.muted', '紧张程度'), h('b', `${r.escalation}`)),
          meter(r.escalation, r.escalation >= 61 ? 'var(--danger)' : r.escalation >= 31 ? 'var(--warn)' : 'var(--good)'),
          r.coach_tip ? h('p.coach', h('b', '教练：'), r.coach_tip) : null,
        ),
      );
      const rounds = history.filter((m) => m.role === 'me').length;
      if (rounds >= MAX_ROUNDS) {
        finished = true;
        footer.hidden = true;
        log.append(debriefCard(r.debrief));
        ctx.onDone?.(history, r.debrief);
      }
    } catch (err) {
      typing.remove();
      history.pop();
      input.value = text;
      log.append(h('p.error.small', err.message));
      toast(err.message, 'danger');
    } finally {
      busy = false;
      sendBtn.disabled = false;
      updateRound();
      log.scrollTop = log.scrollHeight;
    }
  }

  function debriefCard(d) {
    if (!d) return null;
    return h('section.card.debrief',
      h('h3', '复盘'),
      d.happened ? h('p', d.happened) : null,
      d.worked?.length ? [h('p.small', h('b', '起作用的：')), h('ul.bullets', d.worked.map((t) => h('li', t)))] : null,
      d.risky?.length ? [h('p.small', h('b', '有风险的：')), h('ul.bullets', d.risky.map((t) => h('li', t)))] : null,
      d.best_line ? [h('p.small', h('b', '最推荐的说法：')), h('blockquote.play-msg', d.best_line),
        h('button.btn.small', { type: 'button', onclick: () => copyText(d.best_line) }, '复制')] : null,
    );
  }

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) send();
  });
  document.addEventListener('keydown', onKey);
  document.body.classList.add('no-scroll');
  document.body.append(overlay);
  updateRound();
  input.focus();
  return { close };
}
