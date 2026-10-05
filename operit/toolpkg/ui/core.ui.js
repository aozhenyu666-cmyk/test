'use strict';
// 核心对话台：主区域是和 AI 的对话（宿主 AiChat），侧边面板放任务、专注时段与钥匙、伴读、收线、今天的看板。
// 所有动作都走 zhukong 工具；面板自己不存业务状态。

const BACKEND_ROUTE = 'toolpkg:com.community.cognitive_continuity:ui:continuity';

function unwrap(r) {
  if (typeof r === 'string') { try { r = JSON.parse(r); } catch (e) { return {ok: false, message: r}; } }
  if (r && r.success === false) return {ok: false, message: r.message || '没成功'};
  return {ok: true, data: r && 'data' in r ? r.data : r};
}

function short(t, n) { return !t ? '' : (t.length > n ? t.slice(0, n) + '…' : t); }

function focusLine(f) {
  if (!f) return '';
  if (f.active) return '专注时段 ' + f.start + '–' + f.end + '，还剩 ' + f.minutes_left + ' 分钟';
  return f.next ? '现在不在专注时段，下一段' + (f.next_is_tomorrow ? '明天 ' : ' ') + f.next + ' 开始' : '没有设置专注时段';
}

function Screen(ctx) {
  const UI = ctx.UI;
  const [st, setSt] = ctx.useState('st', null);
  const [dash, setDash] = ctx.useState('dash', null);
  const [msg, setMsg] = ctx.useState('msg', '');
  const [needBackend, setNeedBackend] = ctx.useState('needBackend', false);
  const [sideOpen, setSideOpen] = ctx.useState('sideOpen', true);
  const [busy, setBusy] = ctx.useState('busy', false);
  const [answerText, setAnswerText] = ctx.useState('answerText', '');
  const [startObject, setStartObject] = ctx.useState('startObject', '');
  const [startMaterial, setStartMaterial] = ctx.useState('startMaterial', '');
  const [gate, setGate] = ctx.useState('gate', null);
  const [gateAnswers, setGateAnswers] = ctx.useState('gateAnswers', ['', '', '', '', '']);
  const [readTitle, setReadTitle] = ctx.useState('readTitle', '');
  const [readRef, setReadRef] = ctx.useState('readRef', '');
  const [takeaway, setTakeaway] = ctx.useState('takeaway', '');
  const [capText, setCapText] = ctx.useState('capText', '');
  const [capSource, setCapSource] = ctx.useState('capSource', '');
  const loaded = ctx.useRef('loaded', false);

  async function call(tool, params) {
    const r = unwrap(await ctx.callTool('zhukong:' + tool, params || {}));
    if (!r.ok) setMsg('没成功：' + r.message);
    return r;
  }
  async function refresh() {
    const r = await call('console_state');
    if (r.ok) setSt(r.data);
    const d = await call('dashboard');
    if (d.ok) setDash(d.data);
  }
  async function run(fn) {
    if (busy) return;
    setBusy(true); setMsg('');
    try { await fn(); } catch (e) { setMsg('出错了：' + (e && e.message ? e.message : String(e))); }
    finally { setBusy(false); }
  }

  const btn = (text, onClick, primary) => UI.Button({text, onClick: () => run(onClick), enabled: !busy, fillMaxWidth: !!primary});
  const field = (label, value, set, lines) => UI.TextField({label, value, onValueChange: set, fillMaxWidth: true, minLines: lines || 1});
  const title = text => UI.Text({text, style: 'titleMedium'});
  const note = text => UI.Text({text, style: 'bodySmall'});
  const card = children => UI.Card({fillMaxWidth: true}, UI.Column({padding: 14, spacing: 8}, children));

  // ---------- 现在 ----------
  function nowCard() {
    const task = st && st.task;
    const c = [title('现在')];
    if (!st) { c.push(note('正在读取…')); return card(c); }
    if (!task) {
      c.push(note('还没开始。写下现在真正要做的一件事（生活里的事，不是这套系统）。'));
      c.push(field('现在在做什么', startObject, setStartObject));
      c.push(field('材料（粘贴或链接，可以空着）', startMaterial, setStartMaterial, 2));
      c.push(btn('开始', async () => {
        if (!startObject.trim()) { setMsg('先写下要做的事'); return; }
        const isLink = /^https?:\/\//.test(startMaterial.trim());
        const r = await call('start_task', {object: startObject, material_text: isLink ? '' : startMaterial, material_ref: isLink ? startMaterial.trim() : ''});
        if (!r.ok) return;
        if (r.data.status === 'use_backend_console') { setNeedBackend(true); setMsg(r.data.note); return; }
        setStartObject(''); setStartMaterial('');
        setMsg(r.data.coach && r.data.coach.note ? r.data.coach.note : '开始了');
        await refresh();
      }, true));
      if (needBackend) c.push(btn('去认知主控台开始', async () => { await ctx.navigate(BACKEND_ROUTE, {}); }));
      return card(c);
    }
    c.push(UI.Text({text: task.title, style: 'bodyLarge', fontWeight: 'bold'}));
    if (!task.real_task) c.push(note('这条不是你亲自选的任务（可能是安装测试），回答不会写进去。请先开始一件真实的事。'));
    if (task.status === 'PAUSED') {
      c.push(note('已暂停。问题和材料都还在。'));
      c.push(btn('我回来了', async () => { const r = await call('resume_task'); if (r.ok) { setMsg('欢迎回来'); await refresh(); } }, true));
      return card(c);
    }
    if (task.question) c.push(UI.Text({text: task.question}));
    if (task.scaffold) c.push(note(task.scaffold));
    if (task.ai_help) c.push(note('AI：' + task.ai_help));
    c.push(field('我的回答', answerText, setAnswerText, 3));
    c.push(btn('保存并继续', async () => {
      if (!answerText.trim()) { setMsg('写下你自己的回答'); return; }
      const r = await call('answer', {text: answerText});
      if (!r.ok) return;
      setAnswerText('');
      setMsg(r.data.coach.status === 'saved' ? '已保存，AI 接着问了' : '已保存。' + (r.data.coach.note || ''));
      await refresh();
    }, true));
    c.push(UI.Row({spacing: 8}, [
      btn('再问一次', async () => { const r = await call('ask_again'); if (r.ok) { setMsg(r.data.status === 'saved' ? 'AI 接上了' : r.data.note); await refresh(); } }),
      btn('歇一会儿', async () => { const r = await call('pause_task'); if (r.ok) { setMsg('好，歇一会儿'); await refresh(); } })
    ]));
    c.push(note('已写回答 ' + task.answers + ' 条' + (task.materials.length ? ' · 材料：' + task.materials.join('、') : '')));
    return card(c);
  }

  // ---------- 专注时段与钥匙 ----------
  function focusCard() {
    const c = [title('专注时段')];
    if (!st) return card(c);
    const s = st.summary;
    c.push(UI.Text({text: focusLine(st.focus)}));
    c.push(note('今天钥匙剩 ' + s.keys_left_today + ' 把' + (s.cooldown_minutes_left ? '，冷却还有 ' + s.cooldown_minutes_left + ' 分钟' : '')));
    s.active_keys.forEach(k => c.push(note('· ' + k.app + ' 还能用 ' + k.minutes_left + ' 分钟' + (k.back_to ? '，回来先做：' + k.back_to : ''))));
    if (!s.executor.unlock) c.push(note('（锁定执行还没接通：钥匙只记账，不改手机限制）'));
    if (gate) {
      gate.questions.forEach((q, i) => c.push(field((i + 1) + '. ' + q, gateAnswers[i], v => { const a = gateAnswers.slice(); a[i] = v; setGateAnswers(a); })));
      c.push(btn('交上', async () => {
        const r = await call('gate_submit', {gate_id: gate.id, answers_json: JSON.stringify(gateAnswers.slice(0, gate.questions.length))});
        if (!r.ok) return;
        if (r.data.status === 'granted') { setGate(null); setMsg('钥匙给你：' + r.data.key.minutes + ' 分钟。' + (r.data.note || '')); }
        else setMsg('再具体一点：' + r.data.problems.join('；'));
        await refresh();
      }, true));
      c.push(btn('先不要了', async () => { setGate(null); setMsg('好，回去接着做'); }));
      return card(c);
    }
    if (st.focus && st.focus.active) {
      st.heavy_apps.forEach(a => c.push(btn('我想打开 ' + a.name, async () => {
        const r = await call('gate_open', {app: a.package});
        if (!r.ok) return;
        const d = r.data;
        if (d.status === 'asking') { setGate(d.gate); setGateAnswers(['', '', '', '', '']); setMsg('回答这几个问题，认真答完就给钥匙'); }
        else if (d.status === 'cooldown') setMsg('还在冷却，' + d.minutes_left + ' 分钟后再来');
        else if (d.status === 'limit') setMsg('今天的钥匙用完了（' + d.issued + '/' + d.limit + '）');
        else if (d.status === 'key_active') setMsg('这把钥匙还在用');
        else setMsg(d.note || '不需要钥匙');
      })));
    }
    return card(c);
  }

  // ---------- 伴读与收线 ----------
  function readingCard() {
    const reading = st && st.summary && st.summary.reading;
    const c = [title('伴读')];
    if (reading) {
      c.push(UI.Text({text: '正在伴读：' + reading.title}));
      c.push(btn('打开材料', async () => { const r = await call('reading_open_material'); if (r.ok) setMsg(r.data.status === 'accepted' ? '已打开' : (r.data.note || '没能打开')); }));
      c.push(field('今天聊出了什么（你自己的一句话）', takeaway, setTakeaway, 2));
      c.push(btn('收线', async () => {
        if (!takeaway.trim()) { setMsg('写下你自己的一句话'); return; }
        const r = await call('reading_finish', {takeaway});
        if (r.ok) { setTakeaway(''); setMsg(r.data.thread.saved === 'thread' ? '已写进当前任务' : '先存在本地：' + r.data.thread.reason); await refresh(); }
      }, true));
    } else {
      c.push(field('看什么', readTitle, setReadTitle));
      c.push(field('网址 / 文件路径 / App 包名（可空）', readRef, setReadRef));
      c.push(btn('开始伴读（打开 Gemini）', async () => {
        if (!readTitle.trim()) { setMsg('先写看什么'); return; }
        const r = await call('reading_start', {title: readTitle, ref: readRef});
        if (r.ok) { setMsg(r.data.next.join(' → ')); setReadTitle(''); setReadRef(''); await refresh(); }
      }, true));
    }
    return card(c);
  }

  function captureCard() {
    return card([
      title('收一句线'),
      note('和任何 AI 聊完、下完棋、做完题，写一句自己的收获。'),
      field('聊出了什么', capText, setCapText, 2),
      field('来自哪里（GPT / Gemini / ima / 象棋…）', capSource, setCapSource),
      btn('记下', async () => {
        if (!capText.trim()) { setMsg('写一句聊出了什么'); return; }
        const r = await call('thread_capture', {text: capText, source: capSource});
        if (r.ok) { setCapText(''); setMsg(r.data.saved === 'thread' ? '已写进当前任务' : '先存在本地：' + r.data.reason); await refresh(); }
      })
    ]);
  }

  // ---------- 今天（原 Focus Hub 看板）与对话切换 ----------
  function todayCard() {
    const c = [title('今天')];
    if (dash && dash.usage) {
      c.push(UI.Text({text: '重度 App 合计 ' + dash.usage.heavy_minutes + ' 分钟'}));
      dash.usage.top.forEach(a => c.push(note('· ' + a.name + ' ' + a.minutes + ' 分钟' + (a.heavy ? '（重度）' : ''))));
    }
    if (dash && dash.digest) dash.digest.slice(0, 6).forEach(l => c.push(note(short(l, 60))));
    if (dash && dash.errors && dash.errors.length) c.push(note('读不到：' + dash.errors.join('；')));
    c.push(UI.Row({spacing: 8}, [
      btn('找小满聊', async () => { const r = await call('switch_chat', {target: 'companion'}); if (r.ok) { setSideOpen(false); setMsg('已切到小满'); } }),
      btn('回到核心对话', async () => { const r = await call('switch_chat', {target: 'core'}); if (r.ok) setMsg('已切回核心对话'); })
    ]));
    c.push(btn('和小满语音', async () => { const r = await call('voice', {target: 'companion'}); if (r.ok) setMsg(r.data.note); }));
    c.push(btn('刷新', refresh));
    return card(c);
  }

  const side = UI.LazyColumn({padding: 12, spacing: 10, fillMaxSize: true}, [
    UI.Text({text: '核心对话台', style: 'titleLarge'}),
    ...(msg ? [UI.Text({text: msg, style: 'bodyMedium'})] : []),
    nowCard(), focusCard(), readingCard(), captureCard(), todayCard()
  ]);

  const q = st && st.task && st.task.status === 'ACTIVE' ? short(st.task.question, 40) : (st && st.task ? '任务已暂停' : '还没开始任务');
  const main = UI.Column({fillMaxSize: true}, [
    UI.Row({paddingHorizontal: 12, paddingVertical: 6, spacing: 8, fillMaxWidth: true}, [
      UI.Text({text: q, style: 'bodyMedium', weight: 1, maxLines: 2}),
      UI.Button({text: sideOpen ? '收起面板' : '面板', onClick: () => setSideOpen(!sideOpen)})
    ]),
    UI.AiChat({weight: 1, fillMaxWidth: true})
  ]);

  return UI.AdaptiveSidePanel({open: sideOpen, onOpenChanged: setSideOpen, side, fillMaxSize: true,
    onLoad: () => { if (!loaded.current) { loaded.current = true; return refresh(); } }}, main);
}

module.exports = {Screen, default: Screen};
