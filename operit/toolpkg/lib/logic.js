'use strict';
// 主控中枢的纯逻辑：配置、对话门、钥匙、伴读记录。不碰设备，便于在电脑上测试。

const DAY_MS = 86400000;

function err(code, message) { const e = new Error(message); e.code = code; return e; }
const clone = x => JSON.parse(JSON.stringify(x));

// 默认值对齐现有解锁政策（UP-01 每日人工解锁 2 次、UP-05 条件放行 10 分钟回锁）。
// 放宽这些数字属于"立法"，需要用户在设置里亲自改。
function defaultConfig() {
  return {
    tz_offset_min: 480,
    heavy_apps: [
      {package: 'tv.danmaku.bili', name: 'B站'},
      {package: 'com.ss.android.ugc.aweme', name: '抖音'},
      {package: 'com.baidu.tieba', name: '贴吧'},
      {package: 'com.xingin.xhs', name: '小红书'}
    ],
    gate: {
      questions: ['现在打开它，想干什么？', '打算用多久？', '用完回来，第一件事做什么？'],
      min_answer_chars: 4,
      perfunctory: ['随便', '不知道', '没什么', '无', '嗯', '哦', '啊', '看看', '就看看', '刷刷', '随便看看', '不想说', '...', '。。。', '1', '？', '?'],
      answer_timeout_min: 3,
      key_default_minutes: 10,
      key_max_minutes: 15,
      daily_key_limit: 2,
      cooldown_steps_min: [5, 10, 20, 40]
    },
    reading: {
      gemini_package: 'com.google.android.apps.bard',
      invite_times: ['09:30', '20:00'],
      nudge_time: '21:30'
    },
    speak: {mode: 'notification', chat_id: null},
    gate_chat_id: null,
    core_chat_id: null,
    companion_chat_id: null,
    // 用户 2026-10-05 立法：三个专注时段内重度 App 需要钥匙，时段外不需要。
    focus_windows: [{start: '09:30', end: '11:30'}, {start: '14:30', end: '16:30'}, {start: '20:00', end: '22:00'}],
    dashboard: {root: '/sdcard/Download/Operit'},
    routes: {unlock: null, lock: null}
  };
}

function validTime(t) { return typeof t === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(t); }
function validRoute(r) { return r === null || (r && typeof r.tool === 'string' && r.tool.includes(':') && r.params && typeof r.params === 'object' && !Array.isArray(r.params)); }

function mergeConfig(current, patch) {
  if (!patch || typeof patch !== 'object' || Array.isArray(patch)) throw err('INVALID_CONFIG', '配置必须是 JSON 对象');
  const allowed = ['tz_offset_min', 'heavy_apps', 'gate', 'reading', 'speak', 'gate_chat_id', 'core_chat_id', 'companion_chat_id', 'focus_windows', 'dashboard', 'routes'];
  const unknown = Object.keys(patch).filter(k => !allowed.includes(k));
  if (unknown.length) throw err('INVALID_CONFIG', '不支持的配置项：' + unknown.join(','));
  const next = clone(current);
  if ('tz_offset_min' in patch) {
    if (!Number.isInteger(patch.tz_offset_min) || Math.abs(patch.tz_offset_min) > 840) throw err('INVALID_CONFIG', 'tz_offset_min 必须是 -840..840 的整数');
    next.tz_offset_min = patch.tz_offset_min;
  }
  if ('heavy_apps' in patch) {
    if (!Array.isArray(patch.heavy_apps) || patch.heavy_apps.some(a => !a || typeof a.package !== 'string' || !a.package.includes('.') || typeof a.name !== 'string' || !a.name.trim()))
      throw err('INVALID_CONFIG', 'heavy_apps 每项需要 package 与 name');
    if (patch.heavy_apps.some(a => a.package === 'com.ai.assistance.operit')) throw err('INVALID_CONFIG', 'Operit 不能进入重度名单（UP-08）');
    next.heavy_apps = clone(patch.heavy_apps);
  }
  if ('gate' in patch) {
    const g = {...next.gate, ...patch.gate};
    if (!Array.isArray(g.questions) || g.questions.length < 1 || g.questions.length > 5 || g.questions.some(q => typeof q !== 'string' || !q.trim())) throw err('INVALID_CONFIG', 'questions 需要 1~5 个问题');
    for (const k of ['min_answer_chars', 'answer_timeout_min', 'key_default_minutes', 'key_max_minutes', 'daily_key_limit'])
      if (!Number.isInteger(g[k]) || g[k] < 0) throw err('INVALID_CONFIG', k + ' 必须是非负整数');
    if (g.key_default_minutes < 1 || g.key_default_minutes > g.key_max_minutes || g.key_max_minutes > 120) throw err('INVALID_CONFIG', '钥匙时长需满足 1 ≤ 默认 ≤ 上限 ≤ 120');
    if (g.answer_timeout_min < 1 || g.answer_timeout_min > 30) throw err('INVALID_CONFIG', 'answer_timeout_min 需在 1~30');
    if (!Array.isArray(g.cooldown_steps_min) || !g.cooldown_steps_min.length || g.cooldown_steps_min.some(x => !Number.isInteger(x) || x < 1 || x > 240)) throw err('INVALID_CONFIG', 'cooldown_steps_min 需为 1~240 的整数数组');
    if (!Array.isArray(g.perfunctory) || g.perfunctory.some(x => typeof x !== 'string')) throw err('INVALID_CONFIG', 'perfunctory 需为字符串数组');
    next.gate = g;
  }
  if ('reading' in patch) {
    const r = {...next.reading, ...patch.reading};
    if (typeof r.gemini_package !== 'string' || !r.gemini_package.includes('.')) throw err('INVALID_CONFIG', 'gemini_package 无效');
    if (!Array.isArray(r.invite_times) || r.invite_times.length > 4 || r.invite_times.some(t => !validTime(t))) throw err('INVALID_CONFIG', 'invite_times 需为最多 4 个 HH:MM');
    if (!validTime(r.nudge_time)) throw err('INVALID_CONFIG', 'nudge_time 需为 HH:MM');
    next.reading = r;
  }
  if ('speak' in patch) {
    const s = {...next.speak, ...patch.speak};
    if (!['notification', 'chat'].includes(s.mode)) throw err('INVALID_CONFIG', 'speak.mode 只能是 notification 或 chat');
    if (s.mode === 'chat' && (typeof s.chat_id !== 'string' || !s.chat_id.trim())) throw err('INVALID_CONFIG', 'chat 模式需要已核实的 chat_id');
    next.speak = s;
  }
  if ('gate_chat_id' in patch) {
    if (patch.gate_chat_id !== null && (typeof patch.gate_chat_id !== 'string' || !patch.gate_chat_id.trim())) throw err('INVALID_CONFIG', 'gate_chat_id 必须是会话 ID 或 null');
    next.gate_chat_id = patch.gate_chat_id;
  }
  for (const k of ['core_chat_id', 'companion_chat_id']) if (k in patch) {
    if (patch[k] !== null && (typeof patch[k] !== 'string' || !patch[k].trim())) throw err('INVALID_CONFIG', k + ' 必须是会话 ID 或 null');
    next[k] = patch[k];
  }
  if ('focus_windows' in patch) {
    const w = patch.focus_windows;
    if (!Array.isArray(w) || w.length > 6 || w.some(x => !x || !validTime(x.start) || !validTime(x.end) || toMin(x.start) >= toMin(x.end)))
      throw err('INVALID_CONFIG', 'focus_windows 需为最多 6 段 {start,end}，且开始早于结束');
    next.focus_windows = clone(w);
  }
  if ('dashboard' in patch) {
    if (!patch.dashboard || typeof patch.dashboard.root !== 'string' || !patch.dashboard.root.startsWith('/')) throw err('INVALID_CONFIG', 'dashboard.root 需为绝对路径');
    next.dashboard = {root: patch.dashboard.root.replace(/\/+$/, '')};
  }
  if ('routes' in patch) {
    const r = {...next.routes, ...patch.routes};
    for (const k of Object.keys(r)) {
      if (!['unlock', 'lock'].includes(k)) throw err('INVALID_CONFIG', '未知路由：' + k);
      if (!validRoute(r[k])) throw err('INVALID_CONFIG', '路由需要 {tool:"包:工具", params:{}} 或 null：' + k);
    }
    next.routes = r;
  }
  return next;
}

function toMin(t) { const [h, m] = t.split(':').map(Number); return h * 60 + m; }

// 当前是否在专注时段内；不在时给出下一段的开始时间。
function focusWindow(now, cfg) {
  const windows = cfg.focus_windows || [];
  const m = localMinutes(now, cfg);
  const cur = windows.find(w => m >= toMin(w.start) && m < toMin(w.end));
  if (cur) return {active: true, start: cur.start, end: cur.end, minutes_left: toMin(cur.end) - m};
  const later = windows.filter(w => toMin(w.start) > m).sort((a, b) => toMin(a.start) - toMin(b.start))[0];
  const first = windows.slice().sort((a, b) => toMin(a.start) - toMin(b.start))[0];
  return {active: false, next: later ? later.start : (first ? first.start : null), next_is_tomorrow: !later && !!first};
}

function freshState() {
  return {format: 1, config: defaultConfig(), gates: [], keys: [], readings: [], day: null,
    cooldown_until: null, workflows: {}, pending_takeaways: [], log: []};
}

function validate(s) {
  if (!s || s.format !== 1 || !s.config || !Array.isArray(s.gates) || !Array.isArray(s.keys) || !Array.isArray(s.readings))
    throw err('INVALID_STORE', '状态文件不兼容；保留原文件，不自动重置');
  return s;
}

function dayKey(now, cfg) {
  const d = new Date(now + cfg.tz_offset_min * 60000);
  return d.toISOString().slice(0, 10);
}

function localMinutes(now, cfg) {
  const d = new Date(now + cfg.tz_offset_min * 60000);
  return d.getUTCHours() * 60 + d.getUTCMinutes();
}

function ensureDay(s, now) {
  const today = dayKey(now, s.config);
  if (!s.day || s.day.date !== today) s.day = {date: today, keys_issued: 0, misses: 0};
  return s.day;
}

function log(s, now, kind, data) {
  s.log.push({at: now, kind, ...data});
  if (s.log.length > 300) s.log.splice(0, s.log.length - 300);
}

function trimHistory(s) {
  for (const k of ['gates', 'keys', 'readings']) if (s[k].length > 200) s[k].splice(0, s[k].length - 200);
}

function appOf(s, pkg) { return s.config.heavy_apps.find(a => a.package === pkg) || null; }

function id(prefix, now, n) { return prefix + ':' + now.toString(36) + ':' + n; }

// 判断回答是否算数：够长、不是敷衍词、不与前面的回答重复。
function judgeAnswer(cfg, text, earlier) {
  const t = typeof text === 'string' ? text.trim() : '';
  if (!t) return {valid: false, reason: '没有回答'};
  const bare = t.replace(/[\s，。,.!！?？~～、]/g, '');
  if (cfg.gate.perfunctory.some(p => p && bare === p.replace(/[\s，。,.!！?？~～、]/g, ''))) return {valid: false, reason: '回答太敷衍'};
  if ([...bare].length < cfg.gate.min_answer_chars && parseMinutes(t) === null) return {valid: false, reason: '回答太短，具体一点'};
  if (earlier.some(a => a.text && a.text.trim() === t)) return {valid: false, reason: '和前一个回答一样'};
  return {valid: true, reason: null};
}

function expireGates(s, now) {
  const out = [];
  const limit = s.config.gate.answer_timeout_min * 60000;
  for (const g of s.gates) {
    if (g.status === 'asking' && now - g.opened_at > limit) {
      g.status = 'expired'; g.decided_at = now;
      out.push(miss(s, now, g, '超时未回答'));
    }
  }
  return out;
}

// 没答完或被拒绝：当天失误次数 +1，冷却按阶梯延长（"不回答就延长时间"）。
function miss(s, now, g, reason) {
  const day = ensureDay(s, now);
  day.misses += 1;
  const steps = s.config.gate.cooldown_steps_min;
  const minutes = steps[Math.min(day.misses - 1, steps.length - 1)];
  s.cooldown_until = Math.max(s.cooldown_until || 0, now + minutes * 60000);
  g.cooldown_until = s.cooldown_until;
  log(s, now, 'gate_miss', {gate_id: g.id, app: g.app, reason, cooldown_min: minutes});
  return {gate_id: g.id, app: g.app, outcome: 'miss', reason, cooldown_until: s.cooldown_until, cooldown_min: minutes};
}

function gateOpen(s, now, pkg) {
  const app = appOf(s, pkg);
  if (!app) return {status: 'not_guarded', app: pkg, note: '不在重度名单里，不需要钥匙'};
  const fw = focusWindow(now, s.config);
  if (!fw.active) return {status: 'not_in_window', app: pkg, next: fw.next, note: '现在不在专注时段，不需要钥匙'};
  expireGates(s, now);
  const day = ensureDay(s, now);
  const activeKey = s.keys.find(k => k.app === pkg && ['active', 'pending_unlock', 'unbound'].includes(k.status) && k.expires_at > now);
  if (activeKey) return {status: 'key_active', key: clone(activeKey)};
  if (s.cooldown_until && s.cooldown_until > now) return {status: 'cooldown', until: s.cooldown_until, minutes_left: Math.ceil((s.cooldown_until - now) / 60000)};
  if (day.keys_issued >= s.config.gate.daily_key_limit) return {status: 'limit', issued: day.keys_issued, limit: s.config.gate.daily_key_limit};
  const open = s.gates.find(g => g.app === pkg && g.status === 'asking');
  if (open) return {status: 'asking', gate: clone(open)};
  const g = {id: id('gate', now, s.gates.length), app: pkg, app_name: app.name, opened_at: now,
    questions: clone(s.config.gate.questions), answers: [], status: 'asking', decided_at: null, decided_by: null, key_id: null};
  s.gates.push(g); trimHistory(s);
  log(s, now, 'gate_open', {gate_id: g.id, app: pkg});
  return {status: 'asking', gate: clone(g)};
}

function findGate(s, gateId) {
  const g = s.gates.find(x => x.id === gateId);
  if (!g) throw err('NO_GATE', '找不到这次对话门：' + gateId);
  return g;
}

function gateAnswer(s, now, gateId, index, text) {
  expireGates(s, now);
  const g = findGate(s, gateId);
  if (g.status !== 'asking') throw err('GATE_CLOSED', '这次对话门已结束：' + g.status);
  if (!Number.isInteger(index) || index < 0 || index >= g.questions.length) throw err('INVALID_INDEX', '问题序号无效');
  const earlier = g.answers.filter(a => a.index !== index);
  const verdict = judgeAnswer(s.config, text, earlier);
  const entry = {index, question: g.questions[index], text: typeof text === 'string' ? text.trim() : '', at: now, ...verdict};
  const pos = g.answers.findIndex(a => a.index === index);
  if (pos >= 0) g.answers[pos] = entry; else g.answers.push(entry);
  const complete = g.questions.every((_, i) => g.answers.some(a => a.index === i && a.valid));
  if (complete) g.status = 'answered';
  const next = g.questions.findIndex((_, i) => !g.answers.some(a => a.index === i && a.valid));
  return {gate: clone(g), accepted: verdict.valid, reason: verdict.reason, next_index: next >= 0 ? next : null, complete};
}

// 用户自己报的时长（第二题）如果比默认短，就按用户说的给。支持"5分钟""十分钟""半小时""一个小时"。
const CN = {零: 0, 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9};
function cnNumber(x) {
  if (/^\d+$/.test(x)) return Number(x);
  if (x === '十') return 10;
  const m = /^([一二两三四五六七八九])?十([一二三四五六七八九])?$/.exec(x);
  if (m) return (m[1] ? CN[m[1]] : 1) * 10 + (m[2] ? CN[m[2]] : 0);
  return x.length === 1 && x in CN ? CN[x] : null;
}
function parseMinutes(text) {
  if (typeof text !== 'string') return null;
  if (/半\s*(个)?\s*小时/.test(text)) return 30;
  const m = /([0-9]+|[一二两三四五六七八九十]+)\s*(个)?\s*(分钟|分|min|小时|钟头)/.exec(text);
  if (!m) return null;
  const n = cnNumber(m[1]);
  if (n === null || n <= 0) return null;
  return /小时|钟头/.test(m[3]) ? n * 60 : n;
}
function minutesFromAnswers(g) {
  const a = g.answers.find(x => x.index === 1);
  return a ? parseMinutes(a.text) : null;
}

function gateDecide(s, now, gateId, decision, minutes, decidedBy, note) {
  expireGates(s, now);
  const g = findGate(s, gateId);
  if (!['grant', 'deny'].includes(decision)) throw err('INVALID_DECISION', 'decision 只能是 grant 或 deny');
  if (!['answered', 'asking'].includes(g.status)) throw err('GATE_CLOSED', '这次对话门已结束：' + g.status);
  g.decided_at = now; g.decided_by = decidedBy || 'rules'; g.note = note || null;
  if (decision === 'deny') { g.status = 'denied'; return {gate: clone(g), ...miss(s, now, g, note || '守门人未放行')}; }
  if (g.status !== 'answered') throw err('NOT_ANSWERED', '三个问题都认真回答后才能拿钥匙');
  const day = ensureDay(s, now);
  if (day.keys_issued >= s.config.gate.daily_key_limit) throw err('LIMIT', '今天的钥匙已用完');
  const cfg = s.config.gate;
  const asked = minutesFromAnswers(g);
  let m = Number.isInteger(minutes) ? minutes : cfg.key_default_minutes;
  if (asked && asked < m) m = asked;
  m = Math.max(1, Math.min(m, cfg.key_max_minutes));
  const back = g.answers.find(a => a.index === g.questions.length - 1);
  const key = {id: id('key', now, s.keys.length), app: g.app, app_name: g.app_name, gate_id: g.id,
    granted_at: now, minutes: m, expires_at: now + m * 60000, status: 'pending_unlock',
    back_to: back ? back.text : null, unlock_receipt: null, lock_receipt: null};
  s.keys.push(key); trimHistory(s);
  g.status = 'granted'; g.key_id = key.id;
  day.keys_issued += 1;
  log(s, now, 'key_granted', {key_id: key.id, app: g.app, minutes: m, decided_by: g.decided_by});
  return {gate: clone(g), key: clone(key)};
}

// 自动规则：回答全部有效就按默认时长放行，供面板一键提交使用。
function gateSubmit(s, now, gateId, answers) {
  if (!Array.isArray(answers)) throw err('INVALID_EVENT', 'answers 需为数组');
  let last;
  for (let i = 0; i < answers.length; i++) last = gateAnswer(s, now, gateId, i, answers[i]);
  const g = findGate(s, gateId);
  if (g.status !== 'answered') {
    const bad = g.answers.filter(a => !a.valid).map(a => (a.index + 1) + '. ' + a.reason);
    return {status: 'retry', gate: clone(g), problems: bad, last};
  }
  return {status: 'granted', ...gateDecide(s, now, gateId, 'grant', null, 'rules', '回答完整')};
}

function setKeyStatus(s, keyId, status, receiptField, receipt) {
  const k = s.keys.find(x => x.id === keyId);
  if (!k) throw err('NO_KEY', '找不到钥匙：' + keyId);
  k.status = status;
  if (receiptField) k[receiptField] = receipt;
  return clone(k);
}

function dueKeys(s, now) {
  return s.keys.filter(k => ['active', 'pending_unlock', 'unbound'].includes(k.status) && k.expires_at <= now).map(clone);
}

function readingStart(s, now, input) {
  const title = typeof input.title === 'string' ? input.title.trim() : '';
  if (!title) throw err('INVALID_EVENT', '需要材料标题');
  const kinds = ['web', 'file', 'app', 'none'];
  const refKind = input.ref_kind || (input.ref ? (/^https?:\/\//.test(input.ref) ? 'web' : (input.ref.startsWith('/') ? 'file' : 'app')) : 'none');
  if (!kinds.includes(refKind)) throw err('INVALID_EVENT', 'ref_kind 只能是 web/file/app/none');
  const open = s.readings.find(r => r.status === 'reading');
  if (open) { open.status = 'interrupted'; open.ended_at = now; }
  const r = {id: id('read', now, s.readings.length), title, ref: input.ref || null, ref_kind: refKind,
    started_at: now, ended_at: null, status: 'reading', takeaway: null, next_step: null, thread: null, launch: null};
  s.readings.push(r); trimHistory(s);
  log(s, now, 'reading_start', {reading_id: r.id, title});
  return clone(r);
}

function currentReading(s) { return s.readings.slice().reverse().find(r => r.status === 'reading') || null; }

function readingFinish(s, now, takeaway, nextStep) {
  const t = typeof takeaway === 'string' ? takeaway.trim() : '';
  if (!t) throw err('INVALID_EVENT', '收线需要你自己的一句话');
  const r = s.readings.slice().reverse().find(x => x.status === 'reading');
  if (!r) throw err('NO_READING', '当前没有进行中的伴读；可用 thread_capture 记录其它对话的收获');
  r.status = 'finished'; r.ended_at = now; r.takeaway = t; r.next_step = typeof nextStep === 'string' && nextStep.trim() ? nextStep.trim() : null;
  log(s, now, 'reading_finish', {reading_id: r.id});
  return clone(r);
}

// 某个邀请/收线时刻今天是否已经轮到（工作流有延迟，所以按"已过且未处理"判断）。
function todayHasUnfinishedTalk(s, now) {
  const today = dayKey(now, s.config);
  return s.readings.some(r => dayKey(r.started_at, s.config) === today && r.status !== 'finished');
}

function summary(s, now) {
  const day = s.day && s.day.date === dayKey(now, s.config) ? s.day : {date: dayKey(now, s.config), keys_issued: 0, misses: 0};
  return {
    focus: focusWindow(now, s.config),
    today: day,
    cooldown_minutes_left: s.cooldown_until && s.cooldown_until > now ? Math.ceil((s.cooldown_until - now) / 60000) : 0,
    keys_left_today: Math.max(0, s.config.gate.daily_key_limit - day.keys_issued),
    active_keys: s.keys.filter(k => ['active', 'pending_unlock', 'unbound'].includes(k.status) && k.expires_at > now)
      .map(k => ({id: k.id, app: k.app_name, minutes_left: Math.ceil((k.expires_at - now) / 60000), back_to: k.back_to, status: k.status})),
    asking_gates: s.gates.filter(g => g.status === 'asking').map(clone),
    reading: currentReading(s),
    executor: {unlock: !!s.config.routes.unlock, lock: !!s.config.routes.lock},
    pending_takeaways: s.pending_takeaways.length
  };
}

module.exports = {err, clone, focusWindow, defaultConfig, mergeConfig, freshState, validate, dayKey, localMinutes, ensureDay, log,
  judgeAnswer, parseMinutes, expireGates, gateOpen, gateAnswer, gateDecide, gateSubmit, setKeyStatus, dueKeys, minutesFromAnswers,
  readingStart, readingFinish, currentReading, todayHasUnfinishedTalk, summary, DAY_MS};
