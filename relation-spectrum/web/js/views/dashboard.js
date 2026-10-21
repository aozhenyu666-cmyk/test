// Dashboard: alerts, kin care, tier overview, recent interactions.

import { h, mount, go, emptyState, relDays, fmtDate, tempColor, toast } from '../ui/dom.js';
import { TIERS, INTERACTION_TYPES } from '../core/models.js';
import { loadWorld } from './world.js';
import { loadDemoData } from '../data/demo.js';
import { calibrationStats, needsOutcome } from '../core/calibration.js';

export async function renderDashboard(root) {
  const w = await loadWorld();

  if (!w.entities.length) {
    mount(root,
      h('header.page-head', h('h1', '关系光谱'), h('p.muted', '先从最重要的人开始。')),
      emptyState('还没有任何关系对象',
        '建议先添加你的父母：填上生日和出生年份，系统会提醒你主动关心。',
        h('div.stack',
          h('button.btn.primary', { onclick: () => go('#/person/new?tier=kin') }, '添加父母或至亲'),
          h('button.btn', { onclick: () => go('#/person/new') }, '添加其他人'),
          h('button.btn.ghost', {
            onclick: async () => {
              await loadDemoData();
              toast('已载入示例数据', 'good');
              go('#/');
            },
          }, '先看看示例数据'),
        ),
      ),
    );
    return;
  }

  const alerts = w.entities
    .flatMap((e) => w.analysis.get(e.id).alerts.map((a) => ({ ...a, entity: e })))
    .filter((a) => a.level !== 'info' || a.code === 'key_date')
    .slice(0, 8);

  const kin = w.entities.filter((e) => e.tier === 'kin');
  const pendingCases = w.cases.filter((c) => needsOutcome(c));
  const calib = calibrationStats(w.cases);
  const recent = [...w.interactions].sort((a, b) => (a.date < b.date ? 1 : -1)).slice(0, 5);

  mount(root,
    h('header.page-head', h('h1', '关系光谱'), h('p.muted', todayLine())),

    alerts.length
      ? h('section.card',
          h('h3', '需要留意'),
          h('div.alerts', alerts.map((a) =>
            h(`button.alert.lvl-${a.level}`, { type: 'button', onclick: () => go(`#/person/${a.entity.id}`) },
              h('div.alert-who', a.entity.name),
              h('div.alert-title', a.title),
              h('div.alert-advice', a.advice),
            ),
          )),
        )
      : h('section.card', h('h3', '需要留意'), h('p.muted', '目前没有预警。保持节奏。')),

    pendingCases.length || calib.resolved
      ? h('button.card.calib-link', { type: 'button', onclick: () => go(pendingCases.length === 1 ? `#/case/${pendingCases[0].id}` : '#/calibration') },
          h('div.row-between', h('b', '直觉校准'), h('span.muted', '›')),
          pendingCases.length
            ? h('p.small', `有 ${pendingCases.length} 条推演过去两天以上了，回来填一下真实结局。`)
            : null,
          calib.myHitRate != null
            ? h('p.small.muted', `你的第一反应准确率 ${Math.round(calib.myHitRate * 100)}%（${calib.withGuess} 次）· ${calib.lesson}`)
            : h('p.small.muted', calib.lesson))
      : null,

    kin.length
      ? h('section.card.tier-kin',
          h('h3', '亲缘圈 · 关怀倒计时'),
          h('div.kin-list', kin.map((e) => {
            const a = w.analysis.get(e.id);
            const next = a.keyDates[0];
            return h('button.kin', { type: 'button', onclick: () => go(`#/person/${e.id}`) },
              h('div.kin-name', e.name, e.role ? h('span.muted', ` · ${e.role}`) : null),
              h('div.kin-stats',
                h('span', `上次主动关心：${relDays(a.daysSinceCare)}`),
                next ? h('span', `${next.label}：${next.next.days === 0 ? '今天' : `${next.next.days} 天后`}`) : h('span.muted', '未填关键日期'),
                a.meetingsLeft && a.meetingsLeft.meetsPerYear > 0
                  ? h('span', `按过去一年的频率，还能见约 ${a.meetingsLeft.estimate} 次`)
                  : a.meetingsLeft ? h('span', '过去一年没有记录见面') : null,
              ),
            );
          })),
        )
      : h('section.card.tier-kin', h('h3', '亲缘圈'), h('p.muted', '还没有添加父母或至亲。'),
          h('button.btn.small', { onclick: () => go('#/person/new?tier=kin') }, '添加')),

    h('div.tier-grid', Object.values(TIERS).map((t) => {
      const ents = w.entities.filter((e) => e.tier === t.id);
      const temps = ents.map((e) => w.analysis.get(e.id).temperature).filter((x) => x != null);
      const avg = temps.length ? Math.round(temps.reduce((s, x) => s + x, 0) / temps.length) : null;
      return h(`button.card.tier-card.tier-${t.id}`, { type: 'button', onclick: () => go(`#/people?tier=${t.id}`) },
        h('div.tier-label', t.label),
        h('div.tier-num', String(ents.length), h('span.muted', ' 人')),
        h('div.tier-temp', { style: { color: tempColor(avg) } }, avg == null ? '暂无温度' : `平均温度 ${avg}`),
      );
    })),

    h('section.card',
      h('div.row-between', h('h3', '最近记录'), h('button.btn.small', { onclick: () => go('#/log') }, '记一笔')),
      recent.length
        ? h('ul.timeline', recent.map((it) => {
            const e = w.entityById.get(it.entityId);
            return h('li', h('span.muted', fmtDate(it.date)), ' ',
              h('b', e ? e.name : '未知'), ' · ', INTERACTION_TYPES[it.type] || it.type,
              it.title ? ` · ${it.title}` : '');
          }))
        : h('p.muted', '还没有记录。'),
    ),
  );
}

function todayLine() {
  const d = new Date();
  const wk = '日一二三四五六'[d.getDay()];
  return `${d.getMonth() + 1}月${d.getDate()}日 星期${wk}`;
}
