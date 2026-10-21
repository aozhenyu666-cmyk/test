// "粘贴微信对话 / 碎碎念一键解析" box, shared by the log and sandbox pages.
// Uses the AI parse engine; falls back to local heuristics when AI is unavailable.

import { h, toast } from '../ui/dom.js';
import * as api from '../services/api.js';
import { parseLocal } from '../core/parse-local.js';
import { todayISO } from '../core/models.js';

const KIND_LABEL = { soft_reject: '软拒绝', vague: '模糊', warm: '温暖', cold: '冷淡', boundary: '边界', apology: '道歉', pressure: '施压', promise: '承诺', other: '其他' };
const RESP_LABEL = { high: '高', medium: '中', low: '低', unknown: '看不出' };

/**
 * importBox({ getEntityName?: () => string, onParsed: (parsed) => void, open?: boolean })
 */
export function importBox({ getEntityName, onParsed, open = false }) {
  const text = h('textarea', {
    rows: 5,
    placeholder: '把微信聊天复制进来，或者直接说一段大白话。\n建议把自己的话标成“我：…”，对方的标成“名字：…”。',
  });
  const status = h('div.import-status');
  const btn = h('button.btn.primary.small', { type: 'button', onclick: () => run() }, '智能解析');
  const localBtn = h('button.btn.small.ghost', { type: 'button', onclick: () => run({ localOnly: true }) }, '只用本地规则');

  async function run({ localOnly = false } = {}) {
    const raw = text.value.trim();
    if (raw.length < 4) return toast('先粘贴一段对话或描述', 'warn');
    btn.disabled = true;
    btn.textContent = '解析中…';
    let parsed;
    let note = '';
    try {
      if (localOnly) throw Object.assign(new Error('local'), { local: true });
      const { result } = await api.analyze('parse', { text: raw, entityName: getEntityName?.() || '', today: todayISO() });
      parsed = result;
    } catch (err) {
      parsed = parseLocal(raw, { today: todayISO() });
      note = err.local ? '已用本地规则解析。' : `AI 暂时不可用（${err.message}），已改用本地规则解析，结果可能粗糙一些。`;
    } finally {
      btn.disabled = false;
      btn.textContent = '智能解析';
    }
    status.replaceChildren(
      h('p.small', note || 'AI 解析完成，已自动填好下面的表单，请检查后再保存。'),
      h('p.small.muted', `对方响应度：${RESP_LABEL[parsed.responsiveness] || '看不出'}`),
      parsed.signals?.length
        ? h('ul.signals', parsed.signals.map((s) =>
            h('li', h('span.badge', KIND_LABEL[s.kind] || s.kind), ' ', h('b', `“${s.quote}”`), h('span.small.muted', ` ${s.meaning}`))))
        : h('p.small.muted', '没有发现明显的信号。'),
    );
    onParsed(parsed, raw);
  }

  return h('details.card.import', open ? { open: true } : {},
    h('summary', h('b', '粘贴聊天记录 / 碎碎念，一键解析')),
    h('p.small.muted', '会提取事件、双方投入、邀约结果和软拒绝信号。没有 API Key 时自动用本地规则。'),
    text,
    h('div.row-wrap', btn, localBtn),
    status,
  );
}
