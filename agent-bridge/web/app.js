'use strict';
const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const labels = {queued:'排队中',running:'执行中',completed:'已完成',failed:'失败',cancelled:'已停止',interrupted:'已中断'};
const modes = {partners:'伙伴协作',hierarchy:'上下级派发',single:'单 Agent'};
let token = '', state = {agents:[],tasks:[],messages:[],terminals:[]}, mode = 'partners', selectedTask = '', selectedTerminal = '', detailSignature = '', pollBusy = false;
let terminalLogs = {}, toastTimer;
const presets = {
  codex:['codex','exec','--sandbox','workspace-write','--skip-git-repo-check','--output-last-message','{output}','-'],
  claude:['claude','-p','--output-format','text'],
  custom:['your-harness-command','REPLACE_WITH_NONINTERACTIVE_ARGUMENTS']
};

async function api(path, data) {
  const res = await fetch('/api/' + path, {method:data === undefined ? 'GET' : 'POST',headers:{'X-Bridge-Token':token,...(data === undefined ? {} : {'Content-Type':'application/json'})},...(data === undefined ? {} : {body:JSON.stringify(data)})});
  const payload = await res.json();
  if (!res.ok) throw new Error(payload.error || '请求失败');
  return payload;
}
function toast(message, error=false) {
  clearTimeout(toastTimer); $('toast').textContent = message; $('toast').className = 'toast' + (error ? ' error' : '');
  toastTimer = setTimeout(() => $('toast').classList.add('hidden'), error ? 8000 : 3500);
}
function safe(fn) { return async event => {try {await fn(event);} catch(error) {toast(error.message,true);}}; }
function badge(status) {return `<span class="badge ${esc(status)}">${esc(labels[status] || status)}</span>`;}
function nameOf(id) {return state.agents.find(a=>a.id===id)?.name || (id==='user' ? '你' : '已移除的 Agent');}
function empty(icon,title,body) {return `<div class="empty"><span class="empty-icon">${icon}</span><strong>${title}</strong><p>${body}</p></div>`;}
function page(name) {
  document.querySelectorAll('.page').forEach(el=>el.classList.toggle('hidden',el.id!=='page-'+name));
  document.querySelectorAll('.nav').forEach(el=>el.classList.toggle('active',el.dataset.page===name));
  $('page-title').textContent = {overview:'协作工作台',agents:'Agent 管理',terminal:'交互终端',connect:'接入指南'}[name];
}
function options(select, prefer) {
  const existing = select.value;
  const signature = JSON.stringify(state.agents.map(a=>[a.id,a.name]));
  if (select.dataset.signature === signature) return;
  select.dataset.signature = signature;
  select.innerHTML = state.agents.length ? state.agents.map(a=>`<option value="${esc(a.id)}">${esc(a.name)}</option>`).join('') : '<option value="">请先添加 Agent</option>';
  if (state.agents.some(a=>a.id===existing)) select.value=existing;
  else if (prefer && state.agents[1]) select.value=state.agents[1].id;
}
function render() {
  $('stat-agents').textContent=state.agents.length;
  $('stat-active').textContent=state.counts.active;
  $('stat-complete').textContent=state.counts.completed;
  $('task-count').textContent=state.counts.total;
  for (const id of ['task-agent-1','task-agent-2','message-to','terminal-agent']) options($(id),id==='task-agent-2');
  $('submit-task').disabled = state.agents.length < (mode === 'single' ? 1 : 2);
  $('task-list').innerHTML=state.tasks.length ? state.tasks.map(t=>`<button class="task-item ${t.id===selectedTask?'selected':''}" data-task="${esc(t.id)}"><div class="task-item-top"><strong>${esc(t.prompt)}</strong>${badge(t.status)}</div><div class="task-meta"><span>${esc(modes[t.mode])}</span><span>${t.current_turn} / ${t.max_turns} 轮</span><span>${new Date(t.created*1000).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})}</span></div><div class="task-chain">${t.agent_ids.map(id=>`<span class="mini-avatar">${esc(nameOf(id).slice(0,1))}</span><span>${esc(nameOf(id))}</span>`).join('<span>→</span>')}</div></button>`).join('') : empty('⇄','你的第一个协作任务，从这里开始','添加两位 Agent，描述任务，让它们自动接力。也可以在接入指南中添加演示伙伴。');
  $('agent-list').innerHTML=state.agents.length ? state.agents.map(a=>`<article class="panel agent-card"><div class="agent-card-top"><span class="avatar">${esc(a.name.slice(0,1))}</span><div><h2>${esc(a.name)}</h2><span class="badge ${a.busy?'running':''}">${a.busy?'正在执行':a.available?'命令可用 · 待命':'未找到命令'}</span></div></div><p>工作目录 · ${esc(a.cwd)}</p><code>${esc(JSON.stringify(a.argv))}</code><p>单轮超时 ${a.timeout} 秒 · ${a.preset==='demo'?'本地演示，不调用模型':'使用工具现有身份与权限'}</p><div class="agent-card-actions"><button class="button secondary small" data-edit="${esc(a.id)}">编辑配置</button><button class="button danger small" data-delete="${esc(a.id)}">移除</button></div></article>`).join('') : empty('◎','先接入你的第一位 Agent','点击右上方添加 Agent，选择工具模板并填写项目目录。');
  $('message-list').innerHTML=state.messages.slice(0,12).map(m=>`<div class="message"><span>${esc(nameOf(m.sender))} → ${esc(nameOf(m.recipient))}</span><p>${esc(m.body)}</p><span>${new Date(m.created*1000).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})}</span></div>`).join('');
  $('terminal-tabs').innerHTML=state.terminals.map(t=>`<button data-terminal="${esc(t.id)}" class="${selectedTerminal===t.id?'active':''}">${esc(t.name)} · ${t.alive?'在线':'已退出'}</button>`).join('');
  renderTerminalStatus();
}
async function refresh() {
  if(pollBusy) return;
  pollBusy=true;
  try {
    state=await api('state'); render();
    $('connection-status').textContent='本地服务已连接';
    if(selectedTask) await loadTask();
    if(selectedTerminal) await loadTerminal();
  } catch(error) {$('connection-status').textContent='连接中断，请检查服务'; throw error;}
  finally {pollBusy=false;}
}
async function loadTask() {
  const tid=selectedTask, detail=await api('tasks/'+tid);
  if(tid!==selectedTask) return;
  const signature=JSON.stringify(detail);
  if(signature===detailSignature) return;
  detailSignature=signature;
  const {task:t,runs}=detail;
  const opened = [...$('task-detail').querySelectorAll('details[open]')].map(el=>el.dataset.run);
  $('task-detail').classList.remove('hidden');
  $('task-detail').innerHTML=`<div class="detail-top"><h2>${esc(modes[t.mode])} · 任务详情 ${badge(t.status)}</h2><div>${['running','queued'].includes(t.status)?`<button class="button danger small" data-cancel="${esc(t.id)}">停止任务</button>`:''} <button class="button secondary small" data-close-detail="true">收起</button></div></div><p class="detail-prompt">${esc(t.prompt)}</p>${t.error?`<p class="error-text">${esc(t.error)}</p>`:''}${runs.map(r=>`<article class="run"><div class="run-header"><span class="mini-avatar">${r.turn}</span><b>${esc(r.agent_name)}</b>${badge(r.status)}</div>${r.error?`<p class="error-text">${esc(r.error)}</p>`:''}${r.result?`<pre>${esc(r.result)}</pre>`:''}<details data-run="${esc(r.id)}" ${opened.includes(r.id)?'open':''}><summary>查看运行日志${r.status==='running'?' · 持续更新中':''}</summary><pre>${esc(r.output||'等待输出…')}</pre></details></article>`).join('')}${!runs.length?'<p class="help">等待 Agent 空闲后自动执行。</p>':''}`;
}
function setMode(value) {
  mode=value;
  document.querySelectorAll('.mode').forEach(el=>el.classList.toggle('active',el.dataset.mode===value));
  $('mode-help').textContent={partners:'两位伙伴轮流处理任务，将结果自动传递给下一位。',hierarchy:'第一位作为主管安排任务，第二位执行，最后由主管验收。',single:'向一位 Agent 自动提交任务并收集结果。'}[value];
  $('lead-label').textContent=value==='hierarchy'?'主管 Agent':value==='single'?'执行 Agent':'第一位伙伴';
  $('partner-label').textContent=value==='hierarchy'?'执行 Agent':'第二位伙伴';
  $('partner-field').classList.toggle('hidden',value==='single');
  $('task-agent-2').required=value!=='single';
  document.querySelector('.pair-arrow').classList.toggle('hidden',value==='single');
  $('turns-field').classList.toggle('hidden',value!=='partners');
  $('fixed-turns').classList.toggle('hidden',value!=='hierarchy');
  $('submit-task').disabled=state.agents.length<(value==='single'?1:2);
}
function applyPreset() {
  const preset=$('agent-preset').value;
  $('agent-command').value=JSON.stringify(presets[preset],null,2);
  $('agent-name').value={codex:'Codex · 开发伙伴',claude:'Claude · 评审伙伴',custom:'DeepSeek Harness'}[preset];
}
function agentDialog(id) {
  $('agent-form').reset(); $('agent-id').value=''; $('agent-dialog-title').textContent=id?'编辑 Agent':'添加 Agent';
  const a=state.agents.find(a=>a.id===id);
  if(a) { $('agent-id').value=a.id; $('agent-preset').value=presets[a.preset]?a.preset:'custom'; $('agent-name').value=a.name; $('agent-cwd').value=a.cwd; $('agent-command').value=JSON.stringify(a.argv,null,2); $('agent-timeout').value=a.timeout; }
  else {applyPreset(); $('agent-cwd').value=state.cwd||'';}
  $('agent-dialog').showModal();
}
function renderTerminalStatus() {
  const t=state.terminals.find(t=>t.id===selectedTerminal);
  $('terminal-status').textContent=t ? !t.alive?'终端已退出':t.busy?'输入已锁定 · 确认提示框就绪后解锁':'可输入 · 发送后将再次锁定' : '尚未选择终端';
  $('terminal-ready').disabled=!t?.alive;
  $('terminal-stop').disabled=!t?.alive;
  $('terminal-input-form').querySelector('button').disabled=!t?.alive||t.busy;
}
async function loadTerminal() {
  const tid=selectedTerminal, log=terminalLogs[tid] ||= {after:0,text:''};
  const data=await api('terminals/'+tid+'?after='+log.after);
  if(tid!==selectedTerminal) return;
  for(const chunk of data.chunks) {log.after=chunk.seq; log.text=(log.text+chunk.text).slice(-200000);}
  // This is a text log, never an HTML terminal. Strip common ANSI/OSC sequences.
  const clean=log.text.replace(/\x1b\][^\x07]*(?:\x07|\x1b\\)/g,'').replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'');
  const out=$('terminal-output'), bottom=out.scrollHeight-out.scrollTop-out.clientHeight<50;
  out.textContent=clean||'终端启动中…';
  if(bottom) out.scrollTop=out.scrollHeight;
  const t=state.terminals.find(t=>t.id===tid); if(t) {t.busy=data.busy;t.alive=data.alive;}
  renderTerminalStatus();
}
document.querySelectorAll('.nav').forEach(el=>el.addEventListener('click',()=>page(el.dataset.page)));
document.querySelectorAll('.mode').forEach(el=>el.addEventListener('click',()=>setMode(el.dataset.mode)));
$('quick-agent').onclick=()=>agentDialog(); $('add-agent').onclick=()=>agentDialog();
$('close-agent').onclick=$('cancel-agent').onclick=()=>$('agent-dialog').close();
$('agent-preset').onchange=applyPreset;
$('refresh').onclick=safe(refresh);
$('agent-form').onsubmit=safe(async e=>{e.preventDefault(); let argv; try {argv=JSON.parse($('agent-command').value);} catch {throw new Error('命令格式不正确，请填写 JSON 字符串数组');} await api('agents',{id:$('agent-id').value||undefined,name:$('agent-name').value,preset:$('agent-preset').value,cwd:$('agent-cwd').value,argv,timeout:Number($('agent-timeout').value)}); $('agent-dialog').close(); await refresh(); toast('Agent 配置已保存');});
$('task-form').onsubmit=safe(async e=>{e.preventDefault(); const button=$('submit-task'); button.disabled=true; try {const t=await api('tasks',{mode,prompt:$('task-prompt').value,agent_ids:mode==='single'?[$('task-agent-1').value]:[$('task-agent-1').value,$('task-agent-2').value],max_turns:Number($('task-turns').value)}); selectedTask=t.id; detailSignature=''; await refresh(); toast('任务已提交，空闲时自动执行');} finally {button.disabled=state.agents.length<(mode==='single'?1:2);}});
$('message-form').onsubmit=safe(async e=>{e.preventDefault(); await api('messages',{to:$('message-to').value,body:$('message-body').value}); $('message-body').value=''; await refresh(); toast('消息已存入信箱；不会自动触发执行');});
$('add-demo').onclick=safe(async()=>{await api('demo',{}); await refresh(); page('overview'); toast('已添加两位演示伙伴，可直接发起任务');});
$('terminal-form').onsubmit=safe(async e=>{e.preventDefault(); let argv;try {argv=JSON.parse($('terminal-command').value);} catch {throw new Error('交互启动命令需要是 JSON 数组');} const t=await api('terminals',{agent_id:$('terminal-agent').value,argv}); selectedTerminal=t.id; await refresh(); toast('终端已启动，请等待 Agent 提示框就绪');});
$('terminal-ready').onclick=safe(async()=>{await api('terminals/'+selectedTerminal,{action:'ready'}); await refresh();});
$('terminal-stop').onclick=safe(async()=>{await api('terminals/'+selectedTerminal,{action:'stop'}); await refresh();});
$('terminal-input-form').onsubmit=safe(async e=>{e.preventDefault(); await api('terminals/'+selectedTerminal,{action:'input',text:$('terminal-input').value,enter:$('terminal-enter').checked}); $('terminal-input').value=''; await refresh();});
document.addEventListener('click',safe(async e=>{
  const el=e.target.closest('button'); if(!el) return;
  if(el.dataset.task) {selectedTask=el.dataset.task; detailSignature=''; await refresh(); $('task-detail').scrollIntoView({behavior:'smooth',block:'nearest'});}
  if(el.dataset.edit) agentDialog(el.dataset.edit);
  if(el.dataset.delete) {if(!confirm('移除这个 Agent 的接入配置？现有任务记录仍会保留。'))return; await api('agents/delete',{id:el.dataset.delete}); await refresh();}
  if(el.dataset.cancel) {await api('tasks/'+el.dataset.cancel+'/cancel',{}); toast('已发送停止请求'); await refresh();}
  if(el.dataset.closeDetail) {selectedTask=''; $('task-detail').classList.add('hidden'); render();}
  if(el.dataset.terminal) {selectedTerminal=el.dataset.terminal; $('terminal-output').textContent='正在读取终端输出…'; await refresh();}
}));
(async()=>{try {const response=await fetch('/api/bootstrap'); if(!response.ok)throw new Error('无法连接服务'); token=(await response.json()).token; await refresh(); setInterval(()=>refresh().catch(()=>{}),1500);} catch(error){toast(error.message,true);}})();
