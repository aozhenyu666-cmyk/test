'use strict';
// 把纯逻辑、状态文件和真机接口串起来。每个公开方法对应一个工具。
const L = require('./logic.js');

const VERSION = '0.2.0';

function failed(r) { return !r || r.success === false || r.ok === false || (r.data && r.data.success === false); }
function payload(r) { return r && typeof r === 'object' && 'data' in r ? r.data : r; }

function makeService({store, host, clock = () => Date.now()}) {
  const read = () => store.read();
  const edit = fn => store.transact(fn);

  // ---------- 思考线：写进 cognitive_core，不另建一本账 ----------
  async function threadSession() {
    let r;
    try { r = await host.callTool('cognitive_core:status', {}); } catch (e) { return {backend: 'missing', error: String(e.message || e)}; }
    if (failed(r)) return {backend: 'missing', error: (r && r.message) || 'cognitive_core 不可用'};
    const data = payload(r);
    const session = data && data.state && data.state.core ? data.state.core.session : null;
    // 0.3.0 起由认知主控台绑定"真实任务"；绑定存在时只往那条线上写，绝不写进施工记录。
    const cp = data && data.state ? data.state.control_plane : null;
    const binding = cp && cp.policy ? cp.policy.task_binding || null : null;
    return {backend: 'cognitive_core', session, binding, managed: !!cp,
      revision: data && data.state && data.state.core ? data.state.core.revision : null,
      control_revision: cp ? cp.revision || 0 : null};
  }

  // ---------- 核心对话台：任务循环 ----------
  function taskView(t) {
    const s = t.session;
    if (!s || s.status === 'COMPLETE') return null;
    const turn = s.turns && s.turns.length ? s.turns[s.turns.length - 1] : null;
    const ai = turn && turn.ai_contributions && turn.ai_contributions.length ? turn.ai_contributions[turn.ai_contributions.length - 1].text : null;
    return {session_id: s.id, title: s.title, status: s.status, activity: s.activity || 'thinking',
      question: s.next_entry ? s.next_entry.question : null, scaffold: s.next_entry ? s.next_entry.scaffold : null,
      question_id: s.current_question_id, ai_help: ai, last_answer: s.last_step ? s.last_step.user_answer : null,
      answers: (s.cognition || []).length, materials: (s.materials || []).map(m => m.title),
      return_point: s.return_point || null, real_task: writable(t).ok};
  }

  // 请后台模型围绕刚才的回答接着问。preview.3 每题只允许试一次；preview.4 起允许用户重试。
  async function coach(tag) {
    const t = await threadSession();
    if (t.backend !== 'cognitive_core' || !t.managed || !t.session) return {status: 'unavailable', note: '后台没有 AI 接续功能（需要 cognitive_continuity 0.3 以上）'};
    let r;
    try {
      r = await host.callTool('control_plane:coach', {event_id: 'zk-coach:' + t.session.current_question_id + (tag ? ':' + tag : ''),
        expected_core_revision: t.revision, expected_control_revision: t.control_revision || 0});
    } catch (e) { return {status: 'unknown', note: String(e.message || e)}; }
    if (failed(r)) {
      const code = r && r.data && r.data.code;
      const notes = {COACH_ALREADY_ATTEMPTED: '这一题 AI 已经试过一次，后台暂不允许重试（Codex 正在修）',
        MODEL_API_UNAVAILABLE: '后台调用不到模型接口', REAL_TASK_REQUIRED: '先开始一件真实的事'};
      return {status: 'failed', code: code || null, note: notes[code] || (r && r.message) || 'AI 没有接上'};
    }
    const job = payload(r);
    const st = job && (job.status || (job.job && job.job.status));
    return {status: st === 'saved' ? 'saved' : (st || 'unknown'), note: st === 'saved' ? null : 'AI 这次没有接上，可以点「再问一次」'};
  }

  function today(now, cfg) {
    const d = new Date(now + cfg.tz_offset_min * 60000);
    return d.toISOString().slice(0, 10).replace(/-/g, '');
  }

  // 只有当前思考线是用户亲自选的真实任务时才能写；旧版（没有主控绑定）照旧可写。
  function writable(t) {
    if (!t.session || t.session.status === 'COMPLETE')
      return {ok: false, reason: t.managed ? '还没有进行中的真实任务，请先在「认知主控台」开始' : '没有进行中的思考线'};
    if (!t.managed) return {ok: true};
    if (t.binding && t.binding.session_id === t.session.id && t.binding.purpose === 'real_task') return {ok: true};
    return {ok: false, reason: '当前思考线不是你选的真实任务（可能是施工记录），请先在「认知主控台」开始真实任务'};
  }

  async function threadApply(event) {
    const r = await host.callTool('cognitive_core:apply', {event_json: JSON.stringify(event)});
    if (failed(r)) {
      const msg = (r && (r.message || (r.data && r.data.code))) || '写入失败';
      throw L.err('THREAD_WRITE_FAILED', 'cognitive_core 拒绝：' + msg);
    }
    return payload(r);
  }

  async function threadStartOrAttach(reading, now) {
    const t = await threadSession();
    if (t.backend !== 'cognitive_core') return {backend: t.backend, note: '账本插件未安装或不可用，只在主控中枢本地记录', error: t.error};
    const steps = [];
    let session = t.session;
    if (t.managed) {
      const w = writable(t);
      if (!w.ok) return {backend: 'cognitive_core', note: w.reason};
    }
    try {
      if (!session || session.status === 'COMPLETE') {
        const sid = 'zk-' + now.toString(36);
        steps.push(await threadApply({type: 'start', event_id: 'zk:start:' + reading.id, session_id: sid, title: reading.title.slice(0, 200),
          object: reading.title, goal: '和伴读一起把这份材料想透，留下自己的结论和下一步'}));
        session = {id: sid, status: 'ACTIVE'};
      }
      if (session.status === 'PAUSED') return {backend: 'cognitive_core', session_id: session.id, note: '思考线处于暂缓状态，未自动接续；需要时在主控台点"接续"'};
      steps.push(await threadApply({type: 'attach_material', event_id: 'zk:mat:' + reading.id, session_id: session.id,
        material: {id: 'zk-' + reading.id, title: reading.title, text: '伴读材料：' + reading.title + '（正文由 Gemini 实时看屏，未复制进账本）',
          source: {kind: reading.ref_kind === 'web' ? 'web' : (reading.ref_kind === 'file' ? 'file' : 'user'), ref: reading.ref || '用户口头指定', device: 'android', observed_at: now},
          status: 'reported'}}));
      if (reading.ref) steps.push(await threadApply({type: 'set_return_point', event_id: 'zk:ret:' + reading.id, session_id: session.id,
        return_point: {ref: reading.ref, position: '伴读开始处'}}));
      steps.push(await threadApply({type: 'set_activity', event_id: 'zk:watch:' + reading.id, session_id: session.id, activity: 'watching'}));
      return {backend: 'cognitive_core', session_id: session.id, steps: steps.length};
    } catch (e) {
      return {backend: 'cognitive_core', session_id: session && session.id, error: String(e.message || e)};
    }
  }

  // 用户自己的一句话，作为当前问题的回答写进思考线；写不进去就先存本地，绝不丢。
  async function threadAnswer(text, eventKey) {
    const t = await threadSession();
    const w = t.backend === 'cognitive_core' ? writable(t) : {ok: false};
    if (t.backend !== 'cognitive_core' || !w.ok || t.session.status !== 'ACTIVE' || !t.session.current_question_id) {
      const reason = t.backend !== 'cognitive_core' ? '账本不可用' : (!w.ok ? w.reason : '思考线状态为 ' + t.session.status);
      edit(s => { s.pending_takeaways.push({at: clock(), text, reason}); return null; });
      return {saved: 'local', reason};
    }
    try {
      await threadApply({type: 'answer', event_id: 'zk:ans:' + eventKey, session_id: t.session.id,
        question_id: t.session.current_question_id, text, source: 'USER'});
      return {saved: 'thread', session_id: t.session.id};
    } catch (e) {
      edit(s => { s.pending_takeaways.push({at: clock(), text, reason: String(e.message || e)}); return null; });
      return {saved: 'local', reason: String(e.message || e)};
    }
  }

  // ---------- 说话：默认发通知；绑定小满会话后由她用自己的口吻说 ----------
  async function speak(fact) {
    const cfg = read().config;
    try {
      if (cfg.speak.mode === 'chat') {
        const r = await host.chatSend(cfg.speak.chat_id, '【可用事实】' + fact + '\n（请用你自己的口吻对用户说一句，不念系统字段，不调用工具。）');
        return {via: 'chat', status: failed(r) ? 'failed' : 'accepted', receipt: r};
      }
      const r = await host.notify(fact, '主控');
      return {via: 'notification', status: failed(r) ? 'failed' : 'accepted', receipt: r};
    } catch (e) { return {via: cfg.speak.mode, status: 'unknown', error: String(e.message || e)}; }
  }

  // ---------- 钥匙执行：只走已绑定的现有解锁/回锁通道，不自建冻结 ----------
  async function runRoute(kind, key, reason) {
    const route = read().config.routes[kind];
    if (!route) return {status: 'unbound'};
    try {
      const r = await host.callTool(route.tool, {...route.params, package: key.app, action: kind, minutes: key.minutes, key_id: key.id, reason});
      return {status: failed(r) ? 'failed' : 'accepted', receipt: r};
    } catch (e) { return {status: 'unknown', error: String(e.message || e)}; }
  }

  async function issueKey(result) {
    const key = result.key;
    const out = await runRoute('unlock', key, '对话门放行');
    const status = out.status === 'accepted' ? 'active' : out.status === 'unbound' ? 'unbound' : 'unknown';
    const saved = edit(s => L.setKeyStatus(s, key.id, status, 'unlock_receipt', out));
    return {...result, key: saved, executor: out,
      note: status === 'unbound' ? '解锁通道还没绑定：钥匙已记账，但手机上的限制不会自动变化' :
        status === 'unknown' ? '解锁调用结果不明，请到手机上确认是否已解开' : null};
  }

  // 切换嵌入的对话：core=核心对话，companion=小满。没登记 ID 时按标题找。
  async function switchChat({target}) {
    if (!['core', 'companion'].includes(target)) throw L.err('INVALID_EVENT', 'target 只能是 core 或 companion');
    const cfg = read().config;
    let id = target === 'companion' ? cfg.companion_chat_id : cfg.core_chat_id;
    if (!id) {
      let r;
      try { r = await host.findChat(target === 'companion' ? '小满' : '核心对话'); } catch (e) { r = null; }
      id = r && r.chat ? r.chat.id : null;
      if (!id) throw L.err('NO_CHAT', target === 'companion' ? '没找到小满的对话' : '没找到「核心对话」，请先新建一个以此为标题的对话');
    }
    const r = await host.chatSwitch(id);
    if (failed(r)) throw L.err('SWITCH_FAILED', (r && r.message) || '切换失败');
    return {chat_id: id, target};
  }

  return {
    VERSION,

    async status() {
      const now = clock(), s = read();
      return {version: VERSION, summary: L.summary(s, now), config: s.config, workflows: s.workflows,
        thread: await threadSession(), recent: s.log.slice(-15)};
    },

    async configure(patch) {
      return edit(s => { s.config = L.mergeConfig(s.config, patch); L.log(s, clock(), 'configure', {keys: Object.keys(patch)}); return s.config; });
    },

    // ---- 对话门 ----
    async gateOpen({app}) { return edit(s => L.gateOpen(s, clock(), app)); },
    async gateAnswer({gate_id, index, text}) { return edit(s => L.gateAnswer(s, clock(), gate_id, Number(index), text)); },

    async gateSubmit({gate_id, answers}) {
      const r = edit(s => L.gateSubmit(s, clock(), gate_id, answers));
      return r.status === 'granted' ? {status: 'granted', ...(await issueKey(r))} : r;
    },

    async gateDecide({gate_id, decision, minutes, note}) {
      const m = minutes === undefined || minutes === null || minutes === '' ? null : Number(minutes);
      const r = edit(s => L.gateDecide(s, clock(), gate_id, decision, Number.isInteger(m) ? m : null, 'model', note));
      return decision === 'grant' ? await issueKey(r) : r;
    },

    // 打开守门人会话并进入语音：AI 逐个问，回答经 gate_answer / gate_decide 记账。
    async gateVoice({app}) {
      const opened = edit(s => L.gateOpen(s, clock(), app));
      const cfg = read().config;
      if (opened.status !== 'asking') return opened;
      if (!cfg.gate_chat_id) return {...opened, voice: 'unbound', note: '还没绑定守门人会话（gate_chat_id），请在面板上直接回答'};
      const g = opened.gate;
      const brief = '【可用事实】用户想打开' + g.app_name + '。对话门 gate_id=' + g.id + '。按顺序问这几个问题：' +
        g.questions.map((q, i) => (i + 1) + '.' + q).join(' ') + ' 每得到一个回答就调用 zhukong:gate_answer 记下；全部答完后用 zhukong:gate_decide 决定。';
      let sent, voice;
      try { sent = await host.chatSend(cfg.gate_chat_id, brief); } catch (e) { sent = {success: false, error: String(e.message || e)}; }
      try { voice = await host.startVoice(cfg.gate_chat_id); } catch (e) { voice = {success: false, error: String(e.message || e)}; }
      return {...opened, briefing: failed(sent) ? 'failed' : 'accepted', voice};
    },

    // 每 15 分钟：过期未答的门记一次失误并延长冷却；到点的钥匙回锁并提醒回来做什么。
    async gateTick() {
      const now = clock();
      const expired = edit(s => L.expireGates(s, now));
      const due = edit(s => L.dueKeys(s, now));
      const closed = [];
      for (const key of due) {
        const out = await runRoute('lock', key, '钥匙到期');
        const status = out.status === 'accepted' || out.status === 'unbound' ? 'closed' : 'relock_unknown';
        edit(s => { L.setKeyStatus(s, key.id, status, 'lock_receipt', out); L.log(s, now, 'key_closed', {key_id: key.id, executor: out.status}); return null; });
        const said = await speak('给' + key.app_name + '的钥匙到时间了。' + (key.back_to ? '你之前说回来先做：' + key.back_to + '。' : ''));
        closed.push({key_id: key.id, app: key.app_name, executor: out.status, speak: said.status});
      }
      return {expired_gates: expired, closed_keys: closed, checked_at: now};
    },

    // ---- 伴读 ----
    async readingStart({title, ref, ref_kind}) {
      const now = clock();
      const reading = edit(s => L.readingStart(s, now, {title, ref, ref_kind}));
      const thread = await threadStartOrAttach(reading, now);
      let launch;
      try { const r = await host.startApp(read().config.reading.gemini_package); launch = failed(r) ? {status: 'failed', receipt: r} : {status: 'accepted', receipt: r}; }
      catch (e) { launch = {status: 'unknown', error: String(e.message || e)}; }
      edit(s => { const r = s.readings.find(x => x.id === reading.id); r.thread = thread; r.launch = launch; return null; });
      return {reading: {...reading, thread, launch},
        next: ['在 Gemini 里打开「伴读」Gem，点 Live', '点共享屏幕并授权', reading.ref ? '回到主控台点「打开材料」' : '切到你要看的内容',
          '看完回主控台写一句收获，点「收线」']};
    },

    async readingOpenMaterial() {
      const r = L.currentReading(read());
      if (!r) throw L.err('NO_READING', '没有进行中的伴读');
      if (!r.ref) return {status: 'no_ref', note: '这次伴读没有登记材料位置'};
      try {
        const res = r.ref_kind === 'app' ? await host.startApp(r.ref) : await host.openUri(r.ref.startsWith('/') ? 'file://' + r.ref : r.ref);
        return {status: failed(res) ? 'failed' : 'accepted', receipt: res};
      } catch (e) { return {status: 'unknown', error: String(e.message || e)}; }
    },

    async readingFinish({takeaway, next_step}) {
      const now = clock();
      const r = edit(s => L.readingFinish(s, now, takeaway, next_step));
      const text = r.takeaway + (r.next_step ? '\n下一步：' + r.next_step : '');
      const saved = await threadAnswer(text, r.id);
      return {reading: r, thread: saved};
    },

    // 和任何 AI（GPT、Claude、Gemini、ima……）聊完、下完一盘棋，都可以收一句线。
    async threadCapture({text, source}) {
      const t = typeof text === 'string' ? text.trim() : '';
      if (!t) throw L.err('INVALID_EVENT', '收线需要你自己的一句话');
      const now = clock();
      const body = (source ? '［' + String(source).slice(0, 40) + '］' : '') + t;
      edit(s => { L.log(s, now, 'thread_capture', {source: source || null}); return null; });
      return await threadAnswer(body, 'cap:' + now.toString(36));
    },

    async readingInvite() {
      const now = clock(), s = read();
      const cur = L.currentReading(s);
      if (cur && now - cur.started_at < 2 * 3600000) return {skipped: 'reading_in_progress'};
      const last = s.readings.slice().reverse().find(r => r.status === 'finished');
      const fact = '到了约定的伴读时间。' + (last ? '上次看的是《' + last.title + '》' + (last.next_step ? '，说好下一步：' + last.next_step : '') + '。' : '') + '邀请用户开始伴读。';
      return {spoke: await speak(fact)};
    },

    async threadNudge() {
      const now = clock(), s = read();
      if (!L.todayHasUnfinishedTalk(s, now)) return {skipped: 'nothing_open'};
      const open = s.readings.slice().reverse().find(r => r.status !== 'finished' && L.dayKey(r.started_at, s.config) === L.dayKey(now, s.config));
      return {spoke: await speak('今天伴读的《' + open.title + '》还没收线。问用户聊出了什么。')};
    },

    // ---- 核心对话台 ----
    async consoleState() {
      const now = clock(), st = read();
      const t = await threadSession();
      let focus = L.focusWindow(now, st.config);
      try { const r = await host.callTool('control_plane:focus_status', {}); if (!failed(r)) focus = {...focus, backend: payload(r)}; }
      catch (e) { /* 后台还没有这个接口时，只用本地时段 */ }
      return {version: VERSION, task: taskView(t), backend: t.backend, managed: !!t.managed, focus,
        summary: L.summary(st, now), heavy_apps: st.config.heavy_apps,
        chats: {core: st.config.core_chat_id, companion: st.config.companion_chat_id}};
    },

    // 用户在对话台写下的回答：原话入账，然后请 AI 接着问。
    async answer({text}) {
      const t0 = typeof text === 'string' ? text.trim() : '';
      if (!t0) throw L.err('INVALID_EVENT', '写下你自己的回答');
      const t = await threadSession();
      const w = t.backend === 'cognitive_core' ? writable(t) : {ok: false, reason: '账本不可用'};
      if (!w.ok || t.session.status !== 'ACTIVE') throw L.err('NO_TASK', w.ok ? '当前任务处于暂停，先点「我回来了」' : w.reason);
      await threadApply({type: 'answer', event_id: 'zk:answer:' + clock().toString(36), session_id: t.session.id,
        question_id: t.session.current_question_id, text: t0, source: 'USER'});
      return {saved: true, coach: await coach()};
    },

    async askAgain() { return await coach('retry:' + clock().toString(36)); },

    async pause() {
      const t = await threadSession();
      if (!t.session || t.session.status !== 'ACTIVE') throw L.err('NO_TASK', '没有进行中的任务');
      await threadApply({type: 'pause', event_id: 'zk:pause:' + clock().toString(36), session_id: t.session.id, reason: '用户在核心对话台点了歇一会儿'});
      return {paused: true};
    },

    async resume() {
      const t = await threadSession();
      if (!t.session || t.session.status !== 'PAUSED') throw L.err('NO_TASK', '当前没有暂停中的任务');
      await threadApply({type: 'resume', event_id: 'zk:resume:' + clock().toString(36), session_id: t.session.id});
      return {resumed: true};
    },

    // 开始一件真实的事：优先用后台的 begin_task；后台还没有这个接口时，请用户在认知主控台开始。
    async startTask({object, material_text, material_ref}) {
      const o = typeof object === 'string' ? object.trim() : '';
      if (!o) throw L.err('INVALID_EVENT', '写下现在要做的事');
      const t = await threadSession();
      if (t.backend !== 'cognitive_core') throw L.err('NO_BACKEND', '账本插件不可用');
      if (t.managed) {
        const fallback = {status: 'use_backend_console', route: 'toolpkg:com.community.cognitive_continuity:ui:continuity',
          note: '后台还没提供开始任务的接口（Codex 正在加）。这次请在认知主控台开始，开始后回到这里继续。'};
        let r;
        try { r = await host.callTool('cognitive_core:begin_task', {object: o, material_text: material_text || '', material_ref: material_ref || ''}); }
        catch (e) { if (/not found/i.test(String(e.message || e))) return fallback; throw e; }
        if (failed(r) && /not found/i.test((r && r.message) || '')) return fallback;
        if (failed(r)) throw L.err('START_FAILED', (r && r.message) || '开始失败');
        return {status: 'started', result: payload(r), coach: await coach()};
      }
      if (t.session && t.session.status !== 'COMPLETE') throw L.err('SESSION_IN_PROGRESS', '已有进行中的任务，先结束它');
      const sid = 'zk-' + clock().toString(36);
      await threadApply({type: 'start', event_id: 'zk:start:' + sid, session_id: sid, title: o.slice(0, 40), object: o, goal: '留下自己的理解和一个能用的下一步'});
      if (material_text && material_text.trim()) await threadApply({type: 'attach_material', event_id: 'zk:mat:' + sid, session_id: sid,
        material: {id: sid + ':m', title: '材料', text: material_text.trim(), source: {kind: 'user', ref: material_ref || 'core-console', device: 'android', observed_at: clock()}, status: 'reported'}});
      return {status: 'started', session_id: sid};
    },

    // Focus Hub 看板：今天重度 App 用时、日报开头几行。只读。
    async dashboard() {
      const now = clock(), cfg = read().config, root = cfg.dashboard.root, out = {usage: null, digest: null, errors: []};
      try {
        const f = await host.readFile(root + '/p2/usage_last.json');
        let j = JSON.parse(f.content);
        if (j && j.data) j = j.data;
        const apps = (j && Array.isArray(j.apps) ? j.apps : []).filter(a => a && typeof a.package === 'string' && typeof a.minutes === 'number');
        const heavy = new Map(cfg.heavy_apps.map(a => [a.package, a.name]));
        out.usage = {top: apps.slice().sort((a, b) => b.minutes - a.minutes).slice(0, 5).map(a => ({name: heavy.get(a.package) || a.package, minutes: Math.round(a.minutes), heavy: heavy.has(a.package)})),
          heavy_minutes: Math.round(apps.filter(a => heavy.has(a.package)).reduce((n, a) => n + a.minutes, 0))};
      } catch (e) { out.errors.push('用时：' + String(e.message || e)); }
      try {
        const f = await host.readFile(root + '/events/' + today(now, cfg) + '/digest.txt');
        out.digest = f.content.split('\n').filter(x => x.trim()).slice(0, 12);
      } catch (e) { out.errors.push('日报：' + String(e.message || e)); }
      return out;
    },

    switchChat,

    async voice({target}) {
      const sw = await switchChat({target});
      let v;
      try { v = await host.startVoice(null); } catch (e) { v = {success: false, error: String(e.message || e)}; }
      return {...sw, voice: failed(v) ? 'failed' : 'accepted', note: '已请求进入语音；是否真的出声以你听到为准'};
    },

    // ---- 工作流：默认创建为停用，真机核对后再启用 ----
    async installWorkflows({enable}) {
      const cfg = read().config;
      const on = enable === true || enable === 'true';
      const defs = [{key: 'gate_tick', name: '主控·钥匙巡检', desc: '每15分钟：收回到期钥匙、记录超时未答的对话门', tool: 'zhukong:gate_tick',
        trigger: {schedule_type: 'interval', interval_ms: '900000', repeat: 'true', enabled: 'true'}}];
      cfg.reading.invite_times.forEach(t => {
        const [h, m] = t.split(':').map(Number);
        defs.push({key: 'invite_' + t.replace(':', ''), name: '主控·伴读邀请 ' + t, desc: '每天 ' + t + ' 邀请开始伴读（伴读进行中则跳过）', tool: 'zhukong:reading_invite',
          trigger: {schedule_type: 'cron', cron_expression: m + ' ' + h + ' * * *', repeat: 'true', enabled: 'true'}});
      });
      const [nh, nm] = cfg.reading.nudge_time.split(':').map(Number);
      defs.push({key: 'nudge', name: '主控·收线 ' + cfg.reading.nudge_time, desc: '当天有伴读没收线时问一句聊出了什么', tool: 'zhukong:thread_nudge',
        trigger: {schedule_type: 'cron', cron_expression: nm + ' ' + nh + ' * * *', repeat: 'true', enabled: 'true'}});
      const results = [];
      for (const d of defs) {
        const nodes = [
          {id: 'trigger', type: 'trigger', name: d.name, triggerType: 'schedule', triggerConfig: d.trigger, position: {x: 100, y: 100}},
          {id: 'run', type: 'execute', name: '执行 ' + d.tool, actionType: d.tool, actionConfig: {}, position: {x: 450, y: 100}}];
        const connections = [{id: 'trigger-run', sourceNodeId: 'trigger', targetNodeId: 'run'}];
        const existing = read().workflows[d.key];
        try {
          const r = existing ? await host.workflowUpdate(existing, {name: d.name, description: d.desc, nodes, connections, enabled: on})
            : await host.workflowCreate(d.name, d.desc, nodes, connections, on);
          const wid = (r && (r.id || (r.data && r.data.id))) || existing;
          if (failed(r) || !wid) throw new Error((r && r.message) || '没有返回工作流 id');
          edit(s => { s.workflows[d.key] = wid; return null; });
          results.push({key: d.key, name: d.name, id: wid, action: existing ? 'updated' : 'created', enabled: on});
        } catch (e) { results.push({key: d.key, name: d.name, status: 'failed', error: String(e.message || e)}); }
      }
      return {workflows: results, note: on ? '已启用；请到工作流执行日志核对第一次真实触发' : '已创建为停用状态；核对后再用 enable=true 重新安装即可启用'};
    }
  };
}

module.exports = {makeService, VERSION};
