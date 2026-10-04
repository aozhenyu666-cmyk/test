'use strict';
// 主控台面板：伴读、收线、要钥匙。所有动作都走 zhukong 工具，面板本身不存状态。

function unwrap(r) {
  if (typeof r === 'string') { try { r = JSON.parse(r); } catch (e) { return {ok: false, message: r}; } }
  if (r && r.success === false) return {ok: false, message: r.message || '失败'};
  return {ok: true, data: r && 'data' in r ? r.data : r};
}

function describe(st) {
  if (!st) return '点「刷新」读取状态';
  const s = st.summary, lines = [];
  if (s.reading) lines.push('正在伴读：' + s.reading.title);
  const t = st.thread && st.thread.session;
  if (t) {
    lines.push('思考线：' + t.title + '（' + ({ACTIVE: '进行中', PAUSED: '暂缓', COMPLETE: '已结束'}[t.status] || t.status) + '）');
    if (t.next_entry && t.next_entry.question) lines.push('当前问题：' + t.next_entry.question);
  } else if (st.thread && st.thread.backend !== 'cognitive_core') lines.push('思考线：账本插件不可用，收线先存本地');
  lines.push('今天钥匙：剩 ' + s.keys_left_today + ' 把' + (s.cooldown_minutes_left ? '，冷却还有 ' + s.cooldown_minutes_left + ' 分钟' : ''));
  s.active_keys.forEach(k => lines.push('· ' + k.app + ' 还能用 ' + k.minutes_left + ' 分钟' + (k.back_to ? '，回来先做：' + k.back_to : '')));
  if (!s.executor.unlock) lines.push('（解锁通道未绑定：钥匙只记账，不改手机限制）');
  if (s.pending_takeaways) lines.push('有 ' + s.pending_takeaways + ' 条收线暂存在本地，待写入思考线');
  return lines.join('\n');
}

function Screen(ctx) {
  const UI = ctx.UI;
  const [st, setSt] = ctx.useState('st', null);
  const [msg, setMsg] = ctx.useState('msg', '');
  const [title, setTitle] = ctx.useState('title', '');
  const [ref, setRef] = ctx.useState('ref', '');
  const [takeaway, setTakeaway] = ctx.useState('takeaway', '');
  const [nextStep, setNextStep] = ctx.useState('nextStep', '');
  const [capText, setCapText] = ctx.useState('capText', '');
  const [capSource, setCapSource] = ctx.useState('capSource', '');
  const [gate, setGate] = ctx.useState('gate', null);
  const [answers, setAnswers] = ctx.useState('answers', ['', '', '', '', '']);
  const loaded = ctx.useRef('loaded', false);

  async function call(tool, params) {
    const r = unwrap(await ctx.callTool('zhukong:' + tool, params || {}));
    if (!r.ok) setMsg('没成功：' + r.message);
    return r;
  }
  async function refresh() { const r = await call('status'); if (r.ok) setSt(r.data); }
  const btn = (text, onClick) => UI.Button({text, onClick, fillMaxWidth: true});
  const field = (label, value, set, lines) => UI.TextField({label, value, onValueChange: set, fillMaxWidth: true, minLines: lines || 1});
  const setAnswer = (i, v) => { const a = answers.slice(); a[i] = v; setAnswers(a); };

  async function startReading() {
    if (!title.trim()) { setMsg('先写材料标题'); return; }
    const r = await call('reading_start', {title, ref});
    if (r.ok) { setMsg(r.data.reading.launch.status === 'accepted' ? '已打开 Gemini：' + r.data.next.join(' → ') : '没能打开 Gemini，请手动打开；伴读已登记'); await refresh(); }
  }
  async function finishReading() {
    if (!takeaway.trim()) { setMsg('收线要写你自己的一句话'); return; }
    const r = await call('reading_finish', {takeaway, next_step: nextStep});
    if (r.ok) { setTakeaway(''); setNextStep(''); setMsg(r.data.thread.saved === 'thread' ? '已写进思考线' : '先存在本地：' + r.data.thread.reason); await refresh(); }
  }
  async function capture() {
    if (!capText.trim()) { setMsg('写一句聊出了什么'); return; }
    const r = await call('thread_capture', {text: capText, source: capSource});
    if (r.ok) { setCapText(''); setMsg(r.data.saved === 'thread' ? '已写进思考线' : '先存在本地：' + r.data.reason); await refresh(); }
  }
  async function openGate(pkg) {
    const r = await call('gate_open', {app: pkg});
    if (!r.ok) return;
    const d = r.data;
    if (d.status === 'asking') { setGate(d.gate); setAnswers(['', '', '', '', '']); setMsg('回答这几个问题，认真答完就给钥匙'); }
    else if (d.status === 'cooldown') setMsg('还在冷却，' + d.minutes_left + ' 分钟后再来');
    else if (d.status === 'limit') setMsg('今天的钥匙用完了（' + d.issued + '/' + d.limit + '）');
    else if (d.status === 'key_active') setMsg('这把钥匙还有 ' + Math.ceil((d.key.expires_at - Date.now()) / 60000) + ' 分钟');
    else setMsg('它不在重度名单里，不需要钥匙');
  }
  async function submitGate() {
    const n = gate.questions.length;
    const r = await call('gate_submit', {gate_id: gate.id, answers_json: JSON.stringify(answers.slice(0, n))});
    if (!r.ok) return;
    if (r.data.status === 'granted') { setGate(null); setMsg('钥匙给你：' + r.data.key.minutes + ' 分钟。' + (r.data.note || '')); }
    else setMsg('再具体一点：' + r.data.problems.join('；'));
    await refresh();
  }
  async function voiceGate(pkg) {
    const r = await call('gate_voice', {app: pkg});
    if (r.ok) setMsg(r.data.voice === 'unbound' ? r.data.note : '守门人在语音里等你');
  }

  const apps = st ? st.config.heavy_apps : [];
  const children = [
    UI.Text({text: '主控台', style: 'titleLarge'}),
    UI.Text({text: describe(st)}),
    btn('刷新', refresh)
  ];
  if (msg) children.push(UI.Text({text: msg, style: 'bodyMedium'}));

  children.push(UI.Card({fillMaxWidth: true}, UI.Column({padding: 14, spacing: 8}, [
    UI.Text({text: '伴读', style: 'titleMedium'}),
    field('材料标题', title, setTitle),
    field('网址 / 文件路径 / App 包名（可空）', ref, setRef),
    btn('开始伴读（打开 Gemini）', startReading),
    btn('打开材料', async () => { const r = await call('reading_open_material'); if (r.ok) setMsg(r.data.status === 'accepted' ? '已打开' : (r.data.note || '没能打开')); }),
    field('我自己的收获（一句话）', takeaway, setTakeaway, 2),
    field('下一步（可空）', nextStep, setNextStep),
    btn('收线', finishReading)
  ])));

  children.push(UI.Card({fillMaxWidth: true}, UI.Column({padding: 14, spacing: 8}, [
    UI.Text({text: '收一句线（和任何 AI 聊完、下完棋、做完题）', style: 'titleMedium'}),
    field('聊出了什么', capText, setCapText, 2),
    field('来自哪里（GPT / Gemini / ima / 象棋…）', capSource, setCapSource),
    btn('记下', capture)
  ])));

  const gateChildren = [UI.Text({text: '要钥匙', style: 'titleMedium'})];
  if (gate) {
    gate.questions.forEach((q, i) => gateChildren.push(field((i + 1) + '. ' + q, answers[i], v => setAnswer(i, v), 1)));
    gateChildren.push(btn('交上', submitGate));
    gateChildren.push(btn('先不要了', () => { setGate(null); setMsg('好，回去接着做'); }));
  } else {
    apps.forEach(a => {
      gateChildren.push(btn('我想打开 ' + a.name, () => openGate(a.package)));
      if (st && st.config.gate_chat_id) gateChildren.push(btn('用语音和守门人说（' + a.name + '）', () => voiceGate(a.package)));
    });
    if (!apps.length) gateChildren.push(UI.Text({text: '先点「刷新」'}));
  }
  children.push(UI.Card({fillMaxWidth: true}, UI.Column({padding: 14, spacing: 8}, gateChildren)));

  return UI.LazyColumn({padding: 16, spacing: 12, fillMaxSize: true, onLoad: () => { if (!loaded.current) { loaded.current = true; return refresh(); } }}, children);
}

module.exports = {Screen, default: Screen};
