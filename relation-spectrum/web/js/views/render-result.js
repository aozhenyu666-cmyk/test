// Renders AI engine results (decode / game / audit) into cards.

import { h, copyText, meter } from '../ui/dom.js';
import { STAGES, TIERS } from '../core/models.js';

const LEVELS = {
  gentle: { label: '温和 · 留台阶', cls: 'lv-gentle' },
  equal: { label: '对等 · 说清边界', cls: 'lv-equal' },
  exit: { label: '止损 · 体面退出', cls: 'lv-exit' },
};
const RISK = { none: '无草稿', low: '低风险', medium: '中等风险', high: '高风险' };
const SEVERITY = { info: '提示', warn: '注意', danger: '警告' };
const PATTERNS = {
  balanced: '基本健康',
  taking_for_granted: '把付出当理所当然',
  over_giving: '过度付出',
  over_taking: '过度索取',
  drifting: '关系在漂远',
  tier_mismatch: '用错了圈层规则',
  unclear: '数据不足',
};
const EFFORT = { low: '容易', medium: '中等', high: '需要下决心' };

const PALETTE = ['var(--c1)', 'var(--c2)', 'var(--c3)', 'var(--c4)', 'var(--c5)'];

const section = (title, ...body) => h('section.card', h('h3', title), ...body);
const list = (items) => (items?.length ? h('ul.bullets', items.map((t) => h('li', t))) : null);

function stageBadge(stage) {
  const s = STAGES[stage];
  return s ? h(`span.badge.tone-${s.tone}`, s.label) : null;
}

function playbook(items) {
  if (!items?.length) return null;
  return section('三档回应',
    h('div.playbook', items.map((p) => {
      const lv = LEVELS[p.level] || { label: p.level, cls: '' };
      return h(`article.play.${lv.cls}`,
        h('div.play-head', h('span.play-level', lv.label), h('span.play-name', p.name)),
        p.when ? h('p.play-when', `适用：${p.when}`) : null,
        h('blockquote.play-msg', p.message),
        h('div.play-actions', h('button.btn.small', { type: 'button', onclick: () => copyText(p.message) }, '复制这段话')),
        p.expected_reaction ? h('p.play-meta', h('b', '对方可能的反应：'), p.expected_reaction) : null,
        p.risk ? h('p.play-meta', h('b', '代价：'), p.risk) : null,
      );
    })),
  );
}

function footer(r) {
  return [
    r.do_not?.length ? section('现在别做', list(r.do_not)) : null,
    r.next_step
      ? h('section.card.next',
          h('h3', '下一步'),
          h('p', r.next_step),
          Number.isInteger(r.wait_days) && r.wait_days > 0 ? h('p.muted', `建议先观察 ${r.wait_days} 天再行动。`) : null)
      : null,
  ];
}

export function renderDecode(r) {
  const subs = [...(r.subtexts || [])].sort((a, b) => b.probability - a.probability);
  return h('div.result',
    h('section.card.summary',
      h('div.row-between', h('h3', '结论'), stageBadge(r.stage)),
      h('p.lead', r.summary),
      r.stage_rationale ? h('p.muted', r.stage_rationale) : null,
    ),
    r.surface ? section('事实', h('p', r.surface)) : null,
    section('潜台词概率',
      h('div.probs', subs.map((s, i) =>
        h('div.prob',
          h('div.row-between', h('span.prob-label', s.label), h('b', `${s.probability}%`)),
          meter(s.probability, PALETTE[i % PALETTE.length]),
          s.evidence ? h('p.small', h('b', '依据：'), s.evidence) : null,
          s.how_to_verify ? h('p.small', h('b', '怎么验证：'), s.how_to_verify) : null,
        ),
      )),
    ),
    r.my_biases?.length
      ? section('你可能的偏差', h('div.biases', r.my_biases.map((b) => h('div.bias', h('b', b.bias), h('p', b.explanation)))))
      : null,
    r.chips ? section('筹码与成本',
      h('dl.chips',
        h('dt', '我已投入'), h('dd', r.chips.my_investment),
        h('dt', '对方的约束'), h('dd', r.chips.their_constraints),
        h('dt', '继续追问的代价'), h('dd', r.chips.cost_of_pushing),
        h('dt', '先放一放的代价'), h('dd', r.chips.cost_of_waiting),
      )) : null,
    r.draft_check && r.draft_check.risk !== 'none'
      ? h(`section.card.valve.risk-${r.draft_check.risk}`,
          h('div.row-between', h('h3', '冷静阀'), h('span.badge', RISK[r.draft_check.risk] || r.draft_check.risk)),
          list(r.draft_check.issues),
          r.draft_check.rewrite
            ? [h('p.small', h('b', '建议改成：')), h('blockquote.play-msg', r.draft_check.rewrite),
               h('button.btn.small', { type: 'button', onclick: () => copyText(r.draft_check.rewrite) }, '复制改写')]
            : null)
      : null,
    playbook(r.playbook),
    footer(r),
  );
}

export function renderGame(r) {
  const parties = r.parties || [];
  return h('div.result',
    h('section.card.summary',
      h('div.row-between', h('h3', '结论'), stageBadge(r.stage)),
      h('p.lead', r.summary),
      r.core_exchange ? h('p', h('b', '底层交换：'), r.core_exchange) : null,
    ),
    section('话语权对比',
      h('div.probs', parties.map((p, i) =>
        h('div.prob',
          h('div.row-between', h('span.prob-label', p.name), h('b', `${p.leverage}`)),
          meter(p.leverage, PALETTE[i % PALETTE.length]),
        ),
      )),
    ),
    h('div.parties', parties.map((p) =>
      h('section.card.party',
        h('h3', p.name),
        h('dl.chips',
          h('dt', '公开说法'), h('dd', p.stated_position),
          h('dt', '真实诉求'), h('dd', p.real_interests),
          h('dt', '隐性成本'), h('dd', p.hidden_costs),
          h('dt', '筹码'), h('dd', (p.chips || []).join('；')),
          h('dt', '替代方案'), h('dd', p.batna),
          h('dt', '推测底线'), h('dd', p.bottom_line),
          h('dt', '认知偏差'), h('dd', p.blind_spots),
        ),
      ),
    )),
    r.misreadings?.length ? section('关键误读', list(r.misreadings)) : null,
    r.zopa ? section('可谈的空间', h('p', r.zopa)) : null,
    playbook(r.playbook),
    footer(r),
  );
}

export function renderAudit(r) {
  const tc = r.tier_check;
  return h('div.result',
    h('section.card.summary',
      h('div.row-between', h('h3', '审计结论'), h('span.badge', PATTERNS[r.pattern] || r.pattern)),
      h('p.lead', r.verdict),
      h('div.row-between', h('span.muted', '健康分'), h('b', String(r.health_score ?? '-'))),
      meter(r.health_score, 'var(--accent)'),
      r.reminder ? h('p.reminder', `“${r.reminder}”`) : null,
    ),
    r.findings?.length
      ? section('发现', h('div.findings', r.findings.map((f) =>
          h(`div.finding.sev-${f.severity}`,
            h('div.row-between', h('b', f.finding), h('span.badge', SEVERITY[f.severity] || f.severity)),
            h('p.small', f.evidence)))))
      : null,
    tc ? section('圈层检查',
      h('p', `你把 TA 放在「${TIERS[tc.assigned_tier]?.label || tc.assigned_tier}」，实际相处更像「${TIERS[tc.behaving_like]?.label || tc.behaving_like}」。`),
      tc.note ? h('p.muted', tc.note) : null) : null,
    r.blind_spots?.length ? section('盲区', list(r.blind_spots)) : null,
    r.actions?.length
      ? section('行动清单', h('ol.actions', r.actions.map((a) =>
          h('li', h('b', a.action), h('span.muted', ` · ${a.when} · ${EFFORT[a.effort] || a.effort}`), h('p.small', a.why)))))
      : null,
  );
}

export function renderResult(mode, result) {
  if (!result) return h('p.muted', '没有结果');
  if (mode === 'game') return renderGame(result);
  if (mode === 'audit') return renderAudit(result);
  return renderDecode(result);
}
