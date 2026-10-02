/* AI 参谋 — background script (runs inside the Super Productivity host while the app is open).
 *
 * 1. 离开检查：计时中切到别的 App，回来后问“这段时间在工作还是休息”，休息就把时间扣掉。
 *    （SP 安卓版在后台会继续计时并在回到前台时把整段时间记到任务上；桌面版的空闲检测依赖 Electron，手机上没有。）
 * 2. 时间轴：记录每段计时的起止时间，供“今日”页和日报使用。
 * 3. 每日节奏：到点提示“定今天的计划 / 晚间复盘”，并维护一个带提醒的「🧭 参谋」任务，
 *    让系统通知在 App 关闭时也能把你叫回来（SP 的任务提醒由安卓原生闹钟触发）。
 */
(function () {
  'use strict';

  var API = typeof PluginAPI !== 'undefined' ? PluginAPI : plugin;
  var LS = 'sp-ai-assistant:';
  var RITUAL_PREFIX = '🧭 参谋';
  var SNAPSHOT_EVERY = 30000;
  var TICK_EVERY = 60000;

  var DEFAULT_SETTINGS = {
    morningTime: '08:30',
    eveningTime: '21:30',
    awayCheck: null, // null = 自动：手机上开，桌面上关（桌面版 SP 自带空闲检测）
    awayMinutes: 10,
    ritualTask: null, // null = 自动：只在手机上维护提醒任务，避免两台设备各建一个
    openPrompt: true,
    timeline: null, // null = 自动：手机上开，桌面上关
  };

  // ------------------------------------------------------------------ utils

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function ymd(d) { d = d || new Date(); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function hm(d) { d = d || new Date(); return pad(d.getHours()) + ':' + pad(d.getMinutes()); }
  function monthKey(day) { return day.slice(0, 7); }
  function atTime(day, t) {
    var p = day.split('-'), q = String(t || '00:00').split(':');
    return new Date(+p[0], +p[1] - 1, +p[2], +q[0] || 0, +q[1] || 0, 0, 0).getTime();
  }
  function addDays(day, n) { var p = day.split('-'); return ymd(new Date(+p[0], +p[1] - 1, +p[2] + n)); }
  function esc(s) { return String(s).replace(/[<>&"']/g, function (c) { return { '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function lsGet(k, def) { try { var v = localStorage.getItem(LS + k); return v ? JSON.parse(v) : def; } catch (e) { return def; } }
  function lsSet(k, v) { try { localStorage.setItem(LS + k, JSON.stringify(v)); } catch (e) { /* ignore */ } }
  function log() { try { console.log.apply(console, ['[AI 参谋]'].concat([].slice.call(arguments))); } catch (e) { /* ignore */ } }

  function platform() { return (API.cfg && API.cfg.platform) || 'web'; }
  function isMobile() { var p = platform(); return p === 'android' || p === 'ios'; }

  function loadKey(key, def) {
    return Promise.resolve(API.loadSyncedData(key)).then(function (raw) {
      if (!raw) return def;
      try { return JSON.parse(raw); } catch (e) { return def; }
    }).catch(function () { return def; });
  }
  function saveKey(key, val) { return API.persistDataSynced(JSON.stringify(val), key); }

  function settings() {
    return loadKey('settings', {}).then(function (s) { return Object.assign({}, DEFAULT_SETTINGS, s || {}); });
  }

  function dayJournal(day) {
    return loadKey('journal-' + monthKey(day), { days: {} }).then(function (j) { return (j.days || {})[day] || {}; });
  }

  // 同一时刻只做一件事，避免并发弹两个对话框/重复建任务
  var queue = Promise.resolve();
  function serial(fn) {
    queue = queue.then(fn).catch(function (e) { log('error', e && e.message || e); });
    return queue;
  }

  // ------------------------------------------------------------------ 时间轴

  function auto(v) { return v === null || v === undefined ? isMobile() : !!v; }
  function timelineEnabled(s) { return auto(s.timeline); }

  function appendLog(day, mutate) {
    var key = 'log-' + monthKey(day);
    return loadKey(key, { days: {} }).then(function (l) {
      l.days = l.days || {};
      var d = l.days[day] = l.days[day] || { segs: [], away: [] };
      d.segs = d.segs || []; d.away = d.away || [];
      mutate(d);
      // 只保留最近 400 段，防止单月数据过大
      if (d.segs.length > 400) d.segs = d.segs.slice(-400);
      return saveKey(key, l);
    });
  }

  function closeOpenSegment(endTs) {
    var open = lsGet('open', null);
    if (!open) return Promise.resolve();
    lsSet('open', null);
    var end = Math.max(open.s, endTs);
    if (end - open.s < 30000) return Promise.resolve();
    // 跨午夜就拆成两段
    var segs = [], s = open.s;
    while (ymd(new Date(s)) !== ymd(new Date(end))) {
      var midnight = atTime(addDays(ymd(new Date(s)), 1), '00:00');
      segs.push({ s: s, e: midnight }); s = midnight;
    }
    segs.push({ s: s, e: end });
    return segs.reduce(function (p, seg) {
      return p.then(function () {
        return appendLog(ymd(new Date(seg.s)), function (d) {
          d.segs.push({ s: seg.s, e: seg.e, taskId: open.taskId, title: open.title });
        });
      });
    }, Promise.resolve());
  }

  function onCurrentTaskChange(payload) {
    var cur = payload && payload.current;
    var curId = cur ? (typeof cur === 'string' ? cur : cur.id) : null;
    var live = { taskId: curId, title: cur && cur.title || '', since: Date.now() };
    lsSet('live', curId ? live : null);
    serial(function () {
      return settings().then(function (s) {
        if (!timelineEnabled(s)) { lsSet('open', null); return; }
        return closeOpenSegment(Date.now()).then(function () {
          if (curId) lsSet('open', { s: Date.now(), taskId: curId, title: live.title });
        });
      });
    });
  }

  function heartbeat() {
    if (document.visibilityState === 'visible') lsSet('beat', Date.now());
  }

  // 进程被杀后重启：上次的开放段只能截止到最后一次心跳
  function recoverOpenSegment() {
    var open = lsGet('open', null);
    var live = lsGet('live', null);
    if (open && (!live || live.taskId !== open.taskId)) {
      return closeOpenSegment(lsGet('beat', open.s));
    }
    return Promise.resolve();
  }

  // ------------------------------------------------------------------ 离开检查

  var snapshot = null; // {at, spent: {taskId: msToday}}
  var hiddenAt = null;

  function takeSnapshot() {
    var today = ymd();
    return Promise.resolve(API.getTasks()).then(function (tasks) {
      var spent = {}, titles = {};
      (tasks || []).forEach(function (t) {
        if (t.subTaskIds && t.subTaskIds.length) return; // 父任务的时间来自子任务
        spent[t.id] = (t.timeSpentOnDay && t.timeSpentOnDay[today]) || 0;
        titles[t.id] = t.title;
      });
      snapshot = { at: Date.now(), day: today, spent: spent, titles: titles };
      lsSet('snapshot', snapshot);
    }).catch(function () { /* ignore */ });
  }

  function onHidden() {
    hiddenAt = Date.now();
    lsSet('hiddenAt', hiddenAt);
    lsSet('beat', hiddenAt);
    takeSnapshot();
  }

  function onVisible() {
    var hid = hiddenAt || lsGet('hiddenAt', null);
    hiddenAt = null;
    lsSet('hiddenAt', null);
    heartbeat();
    // 给 SP 几秒钟把后台计时同步进任务
    setTimeout(function () {
      serial(function () {
        return checkAway(hid).then(function () { return rhythm(true); });
      });
    }, 3500);
  }

  function checkAway(hid) {
    if (!hid) return Promise.resolve();
    var snap = snapshot || lsGet('snapshot', null);
    return settings().then(function (s) {
      if (!auto(s.awayCheck)) return;
      var awayMs = Date.now() - hid;
      if (awayMs < (Number(s.awayMinutes) || 10) * 60000) return;
      if (!snap || snap.day !== ymd(new Date(hid))) return;
      return Promise.resolve(API.getTasks()).then(function (tasks) {
        var today = ymd();
        // 离开期间时间涨得最多的那个任务就是一直在计时的任务
        var best = null, bestDelta = 0;
        (tasks || []).forEach(function (t) {
          if (t.subTaskIds && t.subTaskIds.length) return;
          var now = (t.timeSpentOnDay && t.timeSpentOnDay[today]) || 0;
          var delta = now - (snap.spent[t.id] || 0);
          if (delta > bestDelta) { best = t; bestDelta = delta; }
        });
        // 至少一半的离开时间被记到了任务上，才认为是在计时
        if (!best || bestDelta < awayMs * 0.5) return;
        var creditedAway = Math.min(awayMs, bestDelta);
        return askAway(best, hid, creditedAway, s);
      });
    });
  }

  function askAway(task, hid, ms, s) {
    var min = Math.round(ms / 60000);
    var html =
      '<div style="font-size:15px;line-height:1.6">' +
      '<p style="margin:0 0 8px">你离开了 <b>' + min + ' 分钟</b>（' + hm(new Date(hid)) + ' – ' + hm() + '）。</p>' +
      '<p style="margin:0 0 8px">这段时间「<b>' + esc(task.title) + '</b>」一直在计时。</p>' +
      '<p style="margin:0;opacity:.75">这段时间你是在……</p></div>';
    var LABEL_WORK = '✅ 一直在做这件事';
    var LABEL_BREAK = '☕ 在休息 / 刷手机';
    var LABEL_OTHER = '🔀 在做别的事';
    return API.openDialog({
      title: '刚才在忙什么？',
      htmlContent: html,
      buttons: [
        { label: LABEL_WORK, color: 'primary', raised: true },
        { label: LABEL_BREAK },
        { label: LABEL_OTHER },
      ],
    }).then(function (res) {
      var verdict = res === LABEL_BREAK ? 'break' : res === LABEL_OTHER ? 'other' : res === LABEL_WORK ? 'work' : 'unknown';
      var p = Promise.resolve();
      if (verdict === 'break' || verdict === 'other') p = removeTime(task.id, ms);
      return p.then(function () {
        if (!timelineEnabled(s)) return;
        return appendLog(ymd(new Date(hid)), function (d) {
          d.away.push({ s: hid, e: Date.now(), min: min, taskId: task.id, title: task.title, verdict: verdict });
        });
      }).then(function () {
        if (verdict === 'break' || verdict === 'other') {
          API.showSnack({ msg: '已从「' + task.title + '」扣除 ' + min + ' 分钟', type: 'SUCCESS' });
        }
      });
    });
  }

  function removeTime(taskId, ms) {
    var today = ymd();
    return Promise.resolve(API.getTasks()).then(function (tasks) {
      var t = (tasks || []).find(function (x) { return x.id === taskId; });
      if (!t) return;
      var tsod = Object.assign({}, t.timeSpentOnDay || {});
      tsod[today] = Math.max(0, (tsod[today] || 0) - ms);
      return API.updateTask(taskId, { timeSpentOnDay: tsod });
    });
  }

  // ------------------------------------------------------------------ 每日节奏

  function ritualTarget(s, j, now) {
    var today = ymd(now);
    var morning = atTime(today, s.morningTime), evening = atTime(today, s.eveningTime);
    if (!j.plan && now.getTime() < morning) return { title: RITUAL_PREFIX + '：定今天的计划', at: morning, kind: 'plan' };
    if (!j.review && now.getTime() < evening) return { title: RITUAL_PREFIX + '：晚间复盘', at: evening, kind: 'review' };
    if (!j.review) return null; // 晚间时间已过但还没复盘：不再挪动，避免反复打扰
    return { title: RITUAL_PREFIX + '：定今天的计划', at: atTime(addDays(today, 1), s.morningTime), kind: 'plan' };
  }

  function ensureRitualTask(s, j) {
    if (!auto(s.ritualTask)) return Promise.resolve();
    var target = ritualTarget(s, j, new Date());
    if (!target) return Promise.resolve();
    return Promise.resolve(API.getTasks()).then(function (tasks) {
      var open = (tasks || []).filter(function (t) { return !t.isDone && String(t.title).indexOf(RITUAL_PREFIX) === 0; });
      var keep = open[0];
      // 同步前两台设备可能各建了一个，多余的删掉
      var extra = open.slice(1).reduce(function (p, t) { return p.then(function () { return API.deleteTask(t.id); }); }, Promise.resolve());
      return extra.then(function () { return keep; });
    }).then(function (keep) {
      if (keep) {
        if (keep.title === target.title && keep.remindAt === target.at && keep.dueWithTime === target.at) return;
        return API.updateTask(keep.id, { title: target.title, dueWithTime: target.at, dueDay: null, hasPlannedTime: true, remindAt: target.at });
      }
      return Promise.resolve(API.addTask({ title: target.title, notes: '由 AI 参谋自动维护：到点提醒你做计划 / 复盘。可以在插件设置里关闭。' }))
        .then(function (id) {
          return API.updateTask(id, { dueWithTime: target.at, dueDay: null, hasPlannedTime: true, remindAt: target.at });
        });
    });
  }

  function countdownLine() {
    return loadKey('goals', null).then(function (g) {
      var today = ymd();
      var parts = [];
      ((g && g.goals) || []).forEach(function (goal) {
        if (!goal.targetDate || goal.status === 'done') return;
        var days = Math.round((atTime(goal.targetDate, '00:00') - atTime(today, '00:00')) / 86400000);
        if (days >= 0 && days <= 400) parts.push((goal.emoji || '') + goal.title + ' 还有 <b>' + days + '</b> 天');
      });
      return parts.slice(0, 3).join('　');
    });
  }

  function openAssistant(intent) {
    lsSet('intent', { kind: intent, at: Date.now() });
    try { API.showIndexHtmlAsView(); } catch (e) { /* ignore */ }
  }

  function rhythm(fromResume) {
    return Promise.all([settings(), dayJournal(ymd())]).then(function (r) {
      var s = r[0], j = r[1];
      return ensureRitualTask(s, j).then(function () {
        if (!s.openPrompt || document.visibilityState !== 'visible') return;
        var now = Date.now(), today = ymd();
        var morning = atTime(today, s.morningTime), evening = atTime(today, s.eveningTime);
        var prompted = lsGet('prompted', {});
        var kind = null;
        if (!j.plan && now >= morning && now < evening && prompted.plan !== today) kind = 'plan';
        else if (!j.review && now >= evening && prompted.review !== today) kind = 'review';
        if (!kind) return;
        prompted[kind] = today;
        lsSet('prompted', prompted);
        return countdownLine().then(function (cd) {
          var hour = new Date().getHours();
          var greet = hour < 11 ? '早上好 ☀️' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好 🌙';
          var body = kind === 'plan'
            ? '今天还没有定计划。花 2 分钟，参谋帮你排好今天最重要的几件事。'
            : '今天还没复盘。3 个问题、2 分钟，顺便生成今天的日报。';
          return API.openDialog({
            title: greet,
            htmlContent: '<div style="font-size:15px;line-height:1.7"><p style="margin:0 0 8px">' + body + '</p>' +
              (cd ? '<p style="margin:0;opacity:.8">' + cd + '</p>' : '') + '</div>',
            buttons: [
              { label: kind === 'plan' ? '开始定计划' : '开始复盘', color: 'primary', raised: true },
              { label: '稍后' },
            ],
          }).then(function (res) {
            if (res === '开始定计划' || res === '开始复盘') openAssistant(kind);
          });
        });
      });
    }).catch(function (e) { log('rhythm failed', e && e.message || e); void fromResume; });
  }

  // ------------------------------------------------------------------ 启动

  try {
    API.registerHeaderButton({ label: 'AI 参谋', icon: 'assistant', onClick: function () { API.showIndexHtmlAsView(); } });
  } catch (e) { log('header button not supported', e && e.message); }

  API.registerHook(API.Hooks ? API.Hooks.CURRENT_TASK_CHANGE : 'currentTaskChange', onCurrentTaskChange);

  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') onHidden(); else onVisible();
  });

  setInterval(function () {
    heartbeat();
    if (document.visibilityState === 'visible') takeSnapshot();
  }, SNAPSHOT_EVERY);

  setInterval(function () {
    if (document.visibilityState === 'visible') serial(function () { return rhythm(false); });
  }, TICK_EVERY * 10);

  // 等 SP 数据加载完再开始
  setTimeout(function () {
    heartbeat();
    takeSnapshot();
    serial(function () { return recoverOpenSegment().then(function () { return rhythm(false); }); });
  }, 6000);

  // 给开发测试用
  try { window.__spAiAssistantBg = { checkAway: checkAway, rhythm: rhythm, takeSnapshot: takeSnapshot, ritualTarget: ritualTarget }; } catch (e) { /* ignore */ }
})();
