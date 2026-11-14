/**
 * dsh-fish-strict —— 严格沟通的手机侧状态机与确定性检查器。
 *
 * 日常搭话由 dsh-speak-up 随机触发；这个插件只在用户明确开启严格模式后
 * 按固定间隔发一条“监督事件”给活着的会话，不掷骰子、不抢 speak-up。
 * 真正给用户看的提问走 DSH 自带的 ask_user_question：它已经有 App 外宠物气泡、
 * 通知、单选和自填，回复会回到原会话；关闭卡片不会被当成完成。
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const name = 'dsh-fish-strict';
export const inject = ['tools', 'agents', 'timer'];

const SOURCE_KIND = 'fish-strict';
const stateFile = () => path.join(process.env.DSH_HOME || path.join(os.homedir(), '.dsh'), 'fish-strict.json');

function readState() {
  try {
    const raw = JSON.parse(fs.readFileSync(stateFile(), 'utf8'));
    return raw && typeof raw === 'object' ? raw : null;
  } catch {
    return null;
  }
}

function writeState(state) {
  const file = stateFile();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(state, null, 2) + '\n', { encoding: 'utf8', mode: 0o600 });
  fs.renameSync(tmp, file);
}

function dayKey(now = Date.now()) {
  const d = new Date(now);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function minutesOf(value, fallback) {
  const m = String(value || '').match(/^(\d{1,2}):(\d{2})$/);
  return m ? Math.min(1439, Number(m[1]) * 60 + Number(m[2])) : fallback;
}

function inQuiet(now, state) {
  const d = new Date(now);
  const minutes = d.getHours() * 60 + d.getMinutes();
  const start = minutesOf(state.quietStart, 23 * 60 + 30);
  const end = minutesOf(state.quietEnd, 8 * 60 + 30);
  if (start === end) return false;
  return start < end ? minutes >= start && minutes < end : minutes >= start || minutes < end;
}

function normalize(raw = {}) {
  const now = Date.now();
  return {
    active: raw.active === true,
    task: String(raw.task || '').slice(0, 500),
    targetSession: String(raw.targetSession || '*').slice(0, 256),
    intervalMinutes: Math.max(5, Math.min(180, Math.round(Number(raw.intervalMinutes) || 15))),
    startedAt: Number(raw.startedAt) || now,
    endAt: Number(raw.endAt) || 0,
    pausedUntil: Number(raw.pausedUntil) || 0,
    nextCheckAt: Number(raw.nextCheckAt) || now + 15 * 60_000,
    quietStart: String(raw.quietStart || '23:30'),
    quietEnd: String(raw.quietEnd || '08:30'),
    maxPromptsPerDay: Math.max(1, Math.min(48, Math.round(Number(raw.maxPromptsPerDay) || 12))),
    promptsToday: Math.max(0, Number(raw.promptsToday) || 0),
    asksToday: Math.max(0, Number(raw.asksToday) || 0),
    day: String(raw.day || dayKey(now)),
    lastPromptAt: Number(raw.lastPromptAt) || 0,
    lastAskAt: Number(raw.lastAskAt) || 0,
    endedAt: Number(raw.endedAt) || 0,
    endReason: String(raw.endReason || ''),
    note: String(raw.note || '').slice(0, 500),
  };
}

function rollDay(state, now) {
  if (state.day !== dayKey(now)) {
    state.day = dayKey(now);
    state.promptsToday = 0;
    state.asksToday = 0;
  }
  return state;
}

function publicState(s) {
  if (!s) return null;
  return {
    active: s.active,
    task: s.task,
    targetSession: s.targetSession,
    intervalMinutes: s.intervalMinutes,
    nextCheckAt: s.nextCheckAt,
    promptsToday: s.promptsToday,
    maxPromptsPerDay: s.maxPromptsPerDay,
    asksToday: s.asksToday,
    pausedUntil: s.pausedUntil || null,
    endAt: s.endAt || null,
    lastPromptAt: s.lastPromptAt || null,
    lastAskAt: s.lastAskAt || null,
  };
}

function statusText(s, now = Date.now()) {
  if (!s) return '严格模式没有开启。';
  if (!s.active) return `严格模式已结束${s.endReason ? `（${s.endReason}）` : ''}。`;
  const left = s.endAt ? Math.max(0, Math.ceil((s.endAt - now) / 60_000)) : null;
  return [
    `严格模式进行中：${s.task || '（未写任务）'}`,
    `检查间隔 ${s.intervalMinutes} 分钟；今天已触发 ${s.promptsToday}/${s.maxPromptsPerDay} 次，已问 ${s.asksToday} 次`,
    left === null ? '没有设定结束时间' : `距离结束还有 ${left} 分钟`,
    s.pausedUntil > now ? `暂停到 ${new Date(s.pausedUntil).toLocaleTimeString()}` : '当前未暂停',
  ].join('；');
}

function pickAgent(ctx, target) {
  const agents = ctx && ctx.agents;
  if (!agents) return undefined;
  const idle = (a) => a && (a.status === undefined || a.status === 'idle');
  if (target && target !== '*') {
    try {
      const a = agents.get(String(target));
      return a !== undefined && idle(a) ? a : undefined;
    } catch {
      return undefined;
    }
  }
  let list = [];
  try {
    list = agents.list() || [];
  } catch {
    list = [];
  }
  const live = list.filter(idle);
  return live.length ? live[crypto.randomInt(live.length)] : undefined;
}

function message(text) {
  return {
    id: crypto.randomUUID(),
    role: 'user',
    content: [{ type: 'text', text }],
    source: { kind: SOURCE_KIND },
  };
}

function triggerText(state) {
  return [
    '（监督事件，不是用户说的话）到约定检查点了。',
    `当前任务：${state.task || '（未写）'}`,
    '先调用 mcp__phone__now 看前台应用、停留分钟、亮屏、充电；再调用 strict_status。',
    '如果任务到期、明显偏航、或在约定应用上停太久：调用 ask_user_question，只问一个问题，',
    'detail 写清任务、触发原因、当前应用和停留时间；options 至少包含：',
    '返回任务 / 说明情况或调整计划 / 限时休息 10 分钟 / 结束严格模式 / 紧急退出。',
    '如果没到点、用户在专注或已经休息：不要提问，保持安静。',
    '关闭问题卡不等于完成；只有用户明确选择或说结束后，才调用 strict_end。',
    '不要用随机搭话代替监督。',
  ].join('\n');
}

function rawTool(def) {
  return def;
}

function panelData() {
  const s = readState();
  const now = Date.now();
  const active = Boolean(s && s.active);
  return {
    summary: statusText(s, now),
    active,
    task: s && s.task ? s.task : '（未开启）',
    next: s && s.nextCheckAt ? new Date(s.nextCheckAt).toLocaleString() : '—',
    usage: s ? `${s.promptsToday || 0}/${s.maxPromptsPerDay || 0} 次触发，${s.asksToday || 0} 次提问` : '—',
    form: {
      task: s && s.task ? s.task : '',
      interval: String(s && s.intervalMinutes ? s.intervalMinutes : 15),
      end: '',
    },
  };
}

function panelView(_scope, caps = {}) {
  const en = String(caps.locale || '').startsWith('en');
  const t = (zh, english) => (en ? english : zh);
  const b = (key) => ({ $: `data.${key}` });
  return {
    v: 2,
    type: 'stack',
    title: t('严格沟通', 'Strict check-in'),
    style: { gap: 'M' },
    children: [
      { type: 'text', text: b('summary') },
      {
        type: 'keyValue',
        items: [
          [t('任务', 'Task'), b('task')],
          [t('下次检查', 'Next check'), b('next')],
          [t('今日', 'Today'), b('usage')],
        ],
      },
      {
        type: 'form',
        label: t('开始或更新', 'Start or update'),
        submit: 'start',
        submitLabel: t('开始 / 更新', 'Start / update'),
        children: [
          { type: 'textField', label: t('任务与完成条件', 'Task and done condition'), bind: 'data.form.task', required: true, maxLength: 500, multiline: true },
          { type: 'textField', label: t('检查间隔（分钟，5-180）', 'Check interval (min, 5-180)'), bind: 'data.form.interval', maxLength: 3 },
          { type: 'textField', label: t('结束时间 HH:MM（可空）', 'End time HH:MM (optional)'), bind: 'data.form.end', maxLength: 5 },
        ],
      },
      {
        type: 'actions',
        items: [
          { label: t('暂停 10 分钟', 'Pause 10 min'), action: 'pause' },
          { label: t('恢复', 'Resume'), action: 'resume' },
          { label: t('结束严格模式', 'End strict mode'), action: 'end' },
        ],
      },
      { type: 'callout', tone: 'info', text: t('关闭这个面板不会结束严格模式；只有点“结束严格模式”或明确说结束才会停止。', 'Closing this panel does not end strict mode; only the End button or an explicit “end” does.') },
    ],
  };
}

function panelActions(ctx) {
  const write = (change) => {
    const now = Date.now();
    const state = normalize({ ...(readState() || {}), ...change });
    if (change.active === false) {
      state.endedAt = now;
      state.endReason = change.endReason || 'user ended';
    }
    rollDay(state, now);
    writeState(state);
    return { refresh: true, toast: change.active === false ? '严格模式已结束' : '已更新' };
  };
  return {
    start: (_scope, event) => {
      const values = event && event.value && event.value.values;
      if (!values || typeof values !== 'object') throw new Error('缺少表单值');
      const task = String(values['data.form.task'] || '').trim();
      if (!task) throw new Error('任务不能为空');
      const interval = Math.max(5, Math.min(180, Math.round(Number(values['data.form.interval']) || 15)));
      let endAt = 0;
      const endText = String(values['data.form.end'] || '').trim();
      if (endText) {
        const m = endText.match(/^(\d{1,2}):(\d{2})$/);
        if (!m) throw new Error('结束时间请写 HH:MM');
        const d = new Date();
        d.setHours(Number(m[1]), Number(m[2]), 0, 0);
        if (d.getTime() <= Date.now()) d.setDate(d.getDate() + 1);
        endAt = d.getTime();
      }
      return write({ active: true, task, intervalMinutes: interval, endAt, nextCheckAt: Date.now() + interval * 60_000 });
    },
    pause: () => write({ pausedUntil: Date.now() + 10 * 60_000 }),
    resume: () => write({ pausedUntil: 0 }),
    end: () => write({ active: false, endReason: 'user ended in panel' }),
  };
}

export function apply(ctx, _config = {}) {
  const disposers = [];
  const tools = ctx && ctx.tools;
  if (tools && typeof tools.register === 'function') {
    const definitions = [
      rawTool({
        name: 'strict_start',
        description:
          'Start explicit strict check-in mode for a task. Creates deterministic reminders (not random dice). ' +
          'The actual question to the user must use ask_user_question so it reaches the pet bubble/notification.',
        parameters: {
          type: 'object',
          properties: {
            task: { type: 'string', description: 'The concrete task and success condition.' },
            intervalMinutes: { type: 'integer', description: 'Check interval, 5-180. Default 15.' },
            endAt: { type: 'string', description: 'Optional ISO end time.' },
            targetSession: { type: 'string', description: 'Session id, or omit/* for all live sessions.' },
            maxPromptsPerDay: { type: 'integer', description: 'Hard cap of supervision turns per day.' },
            quietStart: { type: 'string', description: 'Quiet start HH:MM.' },
            quietEnd: { type: 'string', description: 'Quiet end HH:MM.' },
          },
          additionalProperties: false,
        },
        output: { schema: STATUS_SCHEMA, render: (_a, v) => [{ type: 'text', text: v.text }] },
        execute: (args) => {
          const now = Date.now();
          const end = args.endAt ? Date.parse(args.endAt) : 0;
          if (args.endAt && !Number.isFinite(end)) return { ok: false, text: 'endAt 不是合法时间。', state: null };
          const state = normalize({
            active: true,
            task: args.task,
            targetSession: args.targetSession || '*',
            intervalMinutes: args.intervalMinutes,
            endAt: end,
            maxPromptsPerDay: args.maxPromptsPerDay,
            quietStart: args.quietStart,
            quietEnd: args.quietEnd,
            startedAt: now,
            nextCheckAt: now + Math.max(5, Number(args.intervalMinutes) || 15) * 60_000,
          });
          writeState(state);
          return { ok: true, text: statusText(state, now), state: publicState(state) };
        },
      }),
      rawTool({
        name: 'strict_status',
        description: 'Read the strict check-in state, task, interval, daily usage and pause/end state.',
        parameters: { type: 'object', properties: {}, additionalProperties: false },
        output: { schema: STATUS_SCHEMA, render: (_a, v) => [{ type: 'text', text: v.text }] },
        execute: () => {
          const state = readState();
          return { ok: Boolean(state && state.active), text: statusText(state), state: publicState(state) };
        },
      }),
      rawTool({
        name: 'strict_update',
        description:
          'Adjust the strict task, interval, end time, pause or note. Use after "说明情况/调整计划" or "休息". ' +
          'Never use this to mark a task complete.',
        parameters: {
          type: 'object',
          properties: {
            task: { type: 'string' },
            intervalMinutes: { type: 'integer' },
            endAt: { type: 'string' },
            pauseMinutes: { type: 'integer' },
            note: { type: 'string' },
          },
          additionalProperties: false,
        },
        output: { schema: STATUS_SCHEMA, render: (_a, v) => [{ type: 'text', text: v.text }] },
        execute: (args) => {
          const state = readState();
          if (!state) return { ok: false, text: '严格模式没有开启。', state: null };
          const now = Date.now();
          if (args.task !== undefined) state.task = String(args.task).slice(0, 500);
          if (args.intervalMinutes !== undefined) state.intervalMinutes = Math.max(5, Math.min(180, Math.round(Number(args.intervalMinutes) || 15)));
          if (args.endAt !== undefined) {
            const end = Date.parse(args.endAt);
            if (!Number.isFinite(end)) return { ok: false, text: 'endAt 不是合法时间。', state: publicState(state) };
            state.endAt = end;
          }
          if (args.pauseMinutes !== undefined) {
            const minutes = Number(args.pauseMinutes) || 0;
            state.pausedUntil = minutes > 0 ? now + Math.max(1, Math.min(1440, minutes)) * 60_000 : 0;
          }
          if (args.note !== undefined) state.note = String(args.note).slice(0, 500);
          rollDay(state, now);
          writeState(state);
          return { ok: true, text: statusText(state, now), state: publicState(state) };
        },
      }),
      rawTool({
        name: 'strict_end',
        description: 'End strict mode explicitly. Closing a question card must never call this; only an explicit user choice does.',
        parameters: { type: 'object', properties: { reason: { type: 'string' } }, additionalProperties: false },
        output: { schema: STATUS_SCHEMA, render: (_a, v) => [{ type: 'text', text: v.text }] },
        execute: (args) => {
          const state = readState() || normalize({ active: false });
          state.active = false;
          state.endedAt = Date.now();
          state.endReason = String(args.reason || 'user ended').slice(0, 200);
          writeState(state);
          return { ok: true, text: statusText(state), state: publicState(state) };
        },
      }),
      rawTool({
        name: 'strict_mark_ask',
        description: 'Record that ask_user_question was actually shown. Call once per strict question; never when silent.',
        parameters: { type: 'object', properties: { note: { type: 'string' } }, additionalProperties: false },
        output: { schema: STATUS_SCHEMA, render: (_a, v) => [{ type: 'text', text: v.text }] },
        execute: (args) => {
          const state = readState();
          if (!state) return { ok: false, text: '严格模式没有开启。', state: null };
          const now = Date.now();
          rollDay(state, now);
          state.asksToday += 1;
          state.lastAskAt = now;
          if (args.note) state.note = String(args.note).slice(0, 500);
          writeState(state);
          return { ok: true, text: statusText(state, now), state: publicState(state) };
        },
      }),
    ];
    let registered = 0;
    for (const def of definitions) {
      try {
        const off = tools.register(def);
        registered += 1;
        if (typeof off === 'function') disposers.push(off);
      } catch (error) {
        console.error('[strict] tool registration failed: ' + (error && error.message));
      }
    }
    console.error(`[strict] registered ${registered} tools`);
  }

  if (typeof ctx?.interval === 'function') {
    const stop = ctx.interval(() => void tick(ctx), 60_000);
    if (typeof stop === 'function') disposers.push(stop);
  } else {
    console.error('[strict] no timer service; strict checks will not fire');
  }

  // Native dsh-go surfaces: a composer capsule, a status strip and a panel.
  // The bridge discovers this package by its package.json path, even though
  // the APK loads it by absolute path rather than installing it in a profile.
  try {
    if (typeof ctx?.inject === 'function') {
      ctx.inject(['dshGo'], (dctx) => {
        const d = dctx.dshGo;
        d.on('strict-panel', () => ({ open: 'panel:strict' }));
        d.strip('strict-strip', (_scope, emit) => {
          const state = readState();
          const now = Date.now();
          const text = !state || !state.active
            ? '严格模式'
            : state.pausedUntil > now
              ? '严格 · 暂停'
              : `严格 · ${String(state.task || '').slice(0, 12)}`;
          emit(state && state.active ? { text, icon: 'clock', tone: 'warn' } : { text, icon: 'clock' });
          return () => {};
        });
        d.panel('strict', {
          view: panelView,
          data: (_scope, emit) => {
            emit(panelData());
            return () => {};
          },
          actions: panelActions(ctx),
        });
      });
    }
  } catch (error) {
    console.error('[strict] native panel registration failed: ' + (error && error.message));
  }

  return () => {
    for (const off of disposers.reverse()) {
      try { off(); } catch {}
    }
  };
}

async function tick(ctx) {
  const state = readState();
  if (!state || state.active !== true) return;
  const now = Date.now();
  if (state.endAt && now >= state.endAt) {
    state.active = false;
    state.endedAt = now;
    state.endReason = 'endAt';
    writeState(state);
    return;
  }
  if (state.pausedUntil > now || state.nextCheckAt > now) return;
  if (inQuiet(now, state)) {
    state.nextCheckAt = now + state.intervalMinutes * 60_000;
    writeState(state);
    return;
  }
  rollDay(state, now);
  if (state.promptsToday >= state.maxPromptsPerDay) {
    state.nextCheckAt = now + state.intervalMinutes * 60_000;
    writeState(state);
    return;
  }
  const agent = pickAgent(ctx, state.targetSession);
  if (!agent || typeof agent.followup !== 'function') {
    state.nextCheckAt = now + 2 * 60_000;
    writeState(state);
    return;
  }
  try {
    await agent.followup(message(triggerText(state)));
    state.promptsToday += 1;
    state.lastPromptAt = now;
    state.nextCheckAt = now + state.intervalMinutes * 60_000;
    writeState(state);
  } catch {
    state.nextCheckAt = now + 2 * 60_000;
    writeState(state);
  }
}

// DSH's tool-output schema subset wants `required` at the object level and
// rejects it beside oneOf. Keep the declared schema minimal; `state` is
// additional data for the model and is allowed by additionalProperties.
const STATUS_SCHEMA = {
  type: 'object',
  additionalProperties: true,
  properties: {
    ok: { type: 'boolean' },
    text: { type: 'string' },
  },
};
