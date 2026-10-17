'use strict';
const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const labels = {queued:'排队中',running:'执行中',completed:'已完成',failed:'失败',cancelled:'已停止',interrupted:'已中断'};
const modes = {partners:'伙伴协作',hierarchy:'上下级派发',single:'单 Agent'};
let token = '', state = {agents:[],tasks:[],messages:[],terminals:[]}, mode = 'partners', selectedTask = '', selectedTerminal = '', detailSignature = '', pollBusy = false;
let terminalLogs = {}, toastTimer;
let activeSession = '', xterm = null, fitAddon = null, renderedTerminal = '', terminalBusy = false, rawQueue = Promise.resolve();
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
  $('page-title').textContent = {overview:'协作工作台',agents:'Agent 管理',terminal:'交互终端',connect:'接入指南',history:'对话历史',settings:'设置与备份'}[name];
  if(name==='settings') loadSettings().catch(e=>toast(e.message,true));
  if(name==='terminal') {initTerminal(); setTimeout(()=>fitAddon?.fit(),50);}
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
  const extras=$('task-extra-agents'), extraSignature=JSON.stringify(state.agents.map(a=>[a.id,a.name]));
  if(extras.dataset.signature!==extraSignature) {const chosen=[...extras.selectedOptions].map(o=>o.value); extras.innerHTML=state.agents.map(a=>`<option value="${esc(a.id)}" ${chosen.includes(a.id)?'selected':''}>${esc(a.name)}</option>`).join(''); extras.dataset.signature=extraSignature;}
  $('session-list').innerHTML=(state.sessions||[]).map(s=>`<button class="task-item" data-session="${esc(s.id)}"><strong>${esc(s.title)}</strong><p>${new Date(s.updated*1000).toLocaleString('zh-CN')}</p></button>`).join('')||empty('◷','还没有保存的对话','发起一次任务后，对话会自动保存在这里。');
  $('submit-task').disabled = state.agents.length < (mode === 'single' ? 1 : 2);
  $('task-list').innerHTML=state.tasks.length ? state.tasks.map(t=>`<button class="task-item ${t.id===selectedTask?'selected':''}" data-task="${esc(t.id)}"><div class="task-item-top"><strong>${esc(t.prompt)}</strong>${badge(t.status)}</div><div class="task-meta"><span>${esc(modes[t.mode])}</span><span>${t.current_turn} / ${t.max_turns} 轮</span><span>${new Date(t.created*1000).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})}</span></div><div class="task-chain">${t.agent_ids.map(id=>`<span class="mini-avatar">${esc(nameOf(id).slice(0,1))}</span><span>${esc(nameOf(id))}</span>`).join('<span>→</span>')}</div></button>`).join('') : empty('⇄','你的第一个协作任务，从这里开始','添加两位 Agent，描述任务，让它们自动接力。也可以在接入指南中添加演示伙伴。');
  $('agent-list').innerHTML=state.agents.length ? state.agents.map(a=>`<article class="panel agent-card"><div class="agent-card-top"><span class="avatar">${esc(a.name.slice(0,1))}</span><div><h2>${esc(a.name)}</h2><span class="badge ${a.busy?'running':''}">${a.busy?'正在执行':a.available?'命令可用 · 待命':'未找到命令'}</span></div></div><p>工作目录 · ${esc(a.cwd)}</p><code>${esc(JSON.stringify(a.argv))}</code><p>单轮超时 ${a.timeout} 秒 · ${a.preset==='demo'?'本地演示，不调用模型':'使用工具现有身份与权限'}</p><div class="agent-card-actions"><button class="button secondary small" data-check="${esc(a.id)}">检查命令</button><button class="button secondary small" data-edit="${esc(a.id)}">编辑配置</button><button class="button danger small" data-delete="${esc(a.id)}">移除</button></div></article>`).join('') : empty('◎','先接入你的第一位 Agent','点击右上方添加 Agent，选择工具模板并填写项目目录。');
  $('message-list').innerHTML=state.messages.slice(0,12).map(m=>`<div class="message"><span>${esc(nameOf(m.sender))} → ${esc(nameOf(m.recipient))}</span><p>${esc(m.body)}</p><span>${new Date(m.created*1000).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})}</span></div>`).join('');
  $('terminal-tabs').innerHTML=state.terminals.map(t=>`<button data-terminal="${esc(t.id)}" class="${selectedTerminal===t.id?'active':''}">${esc(t.name)} · ${t.alive?'在线':'已退出'}</button>`).join('');
  renderTerminalStatus();
  filterTasks();
  $('choose-folder').disabled=!state.desktop;
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
  $('task-detail').insertAdjacentHTML('beforeend',`<div class="detail-actions"><button class="button secondary small" data-export="${esc(t.id)}">导出 Markdown</button>${!['running','queued'].includes(t.status)?`<button class="button secondary small" data-continue="${esc(t.session_id||'')}">继续对话</button><button class="button secondary small" data-rerun="${esc(t.id)}">重新运行</button>`:''}${['failed','interrupted','cancelled'].includes(t.status)?`<button class="button small" data-resume="${esc(t.id)}">续跑未完成轮次</button>`:''}</div>${t.prior_history?.length?`<p class="help">已沿用此前完成的 ${t.prior_history.length} 轮结果，本次只执行剩余轮次。</p>`:''}`);
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
  $('extra-team-field').classList.toggle('hidden',value==='single');
}
function applyPreset() {
  const preset=$('agent-preset').value;
  $('agent-command').value=JSON.stringify(presets[preset],null,2);
  $('agent-name').value={codex:'Codex · 开发伙伴',claude:'Claude · 评审伙伴',custom:'DeepSeek Harness'}[preset];
  $('agent-interactive').value=JSON.stringify([presets[preset][0]]);
}
function agentDialog(id) {
  $('agent-form').reset(); $('agent-id').value=''; $('agent-dialog-title').textContent=id?'编辑 Agent':'添加 Agent';
  const a=state.agents.find(a=>a.id===id);
  if(a) { $('agent-id').value=a.id; $('agent-preset').value=presets[a.preset]?a.preset:'custom'; $('agent-name').value=a.name; $('agent-cwd').value=a.cwd; $('agent-command').value=JSON.stringify(a.argv,null,2); $('agent-timeout').value=a.timeout; }
  else {applyPreset(); $('agent-cwd').value=state.cwd||'';}
  if(a) $('agent-interactive').value=JSON.stringify(a.interactive_argv||[a.argv[0]]);
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
  if(terminalBusy) return;
  terminalBusy=true;
  try {
  const tid=selectedTerminal, log=terminalLogs[tid] ||= {after:0,text:''};
  const data=await api('terminals/'+tid+'?after='+log.after);
  if(tid!==selectedTerminal) return;
  initTerminal();
  if(xterm && renderedTerminal!==tid) {xterm.reset();xterm.write(log.text);renderedTerminal=tid;}
  for(const chunk of data.chunks) {log.after=chunk.seq; log.text=(log.text+chunk.text).slice(-200000);xterm?.write(chunk.text);}
  // This is a text log, never an HTML terminal. Strip common ANSI/OSC sequences.
  const clean=log.text.replace(/\x1b\][^\x07]*(?:\x07|\x1b\\)/g,'').replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'');
  const out=$('terminal-output'), bottom=out.scrollHeight-out.scrollTop-out.clientHeight<50;
  out.textContent=clean||'终端启动中…';
  if(bottom) out.scrollTop=out.scrollHeight;
  const t=state.terminals.find(t=>t.id===tid); if(t) {t.busy=data.busy;t.alive=data.alive;}
  renderTerminalStatus();
  } finally {terminalBusy=false;}
}
document.querySelectorAll('.nav').forEach(el=>el.addEventListener('click',()=>page(el.dataset.page)));
document.querySelectorAll('.mode').forEach(el=>el.addEventListener('click',()=>setMode(el.dataset.mode)));
$('quick-agent').onclick=()=>agentDialog(); $('add-agent').onclick=()=>agentDialog();
$('close-agent').onclick=$('cancel-agent').onclick=()=>$('agent-dialog').close();
$('agent-preset').onchange=applyPreset;
$('refresh').onclick=safe(refresh);
$('agent-form').onsubmit=safe(async e=>{e.preventDefault(); let argv,interactive; try {argv=JSON.parse($('agent-command').value);interactive=JSON.parse($('agent-interactive').value);} catch {throw new Error('命令格式不正确，请填写 JSON 字符串数组');} await api('agents',{id:$('agent-id').value||undefined,name:$('agent-name').value,preset:$('agent-preset').value,cwd:$('agent-cwd').value,argv,interactive_argv:interactive,timeout:Number($('agent-timeout').value)}); $('agent-dialog').close(); await refresh(); toast('Agent 配置已保存');});
$('task-form').onsubmit=safe(async e=>{e.preventDefault(); const button=$('submit-task'); button.disabled=true; try {const ids=mode==='single'?[$('task-agent-1').value]:[...new Set([$('task-agent-1').value,$('task-agent-2').value,...[...$('task-extra-agents').selectedOptions].map(o=>o.value)])];const t=await api('tasks',{mode,prompt:$('task-prompt').value,agent_ids:ids,session_id:activeSession||undefined,max_turns:Number($('task-turns').value)}); selectedTask=t.id; detailSignature=''; await refresh(); toast('任务已提交，空闲时自动执行');} finally {button.disabled=state.agents.length<(mode==='single'?1:2);}});
$('message-form').onsubmit=safe(async e=>{e.preventDefault(); const triggered=$('message-trigger').checked;await api('messages',{to:$('message-to').value,body:$('message-body').value,trigger:triggered}); $('message-body').value=''; await refresh(); toast(triggered?'消息已提交给 Agent 处理':'消息已保存到信箱');});
$('add-demo').onclick=safe(async()=>{await api('demo',{}); await refresh(); page('overview'); toast('已添加两位演示伙伴，可直接发起任务');});
$('terminal-form').onsubmit=safe(async e=>{e.preventDefault(); let argv;try {argv=JSON.parse($('terminal-command').value);} catch {throw new Error('交互启动命令需要是 JSON 数组');} const t=await api('terminals',{agent_id:$('terminal-agent').value,argv}); selectedTerminal=t.id; await refresh(); toast('终端已启动，请等待 Agent 提示框就绪');});
$('terminal-ready').onclick=safe(async()=>{await api('terminals/'+selectedTerminal,{action:'ready'}); await refresh();});
$('terminal-stop').onclick=safe(async()=>{await api('terminals/'+selectedTerminal,{action:'stop'}); await refresh();});
$('terminal-input-form').onsubmit=safe(async e=>{e.preventDefault(); await api('terminals/'+selectedTerminal,{action:'input',text:$('terminal-input').value,enter:$('terminal-enter').checked,bracketed_paste:$('terminal-paste').checked}); $('terminal-input').value=''; await refresh();});
document.addEventListener('click',safe(async e=>{
  const el=e.target.closest('button'); if(!el) return;
  if(el.dataset.task) {selectedTask=el.dataset.task; detailSignature=''; await refresh(); $('task-detail').scrollIntoView({behavior:'smooth',block:'nearest'});}
  if(el.dataset.edit) agentDialog(el.dataset.edit);
  if(el.dataset.delete) {if(!confirm('移除这个 Agent 的接入配置？现有任务记录仍会保留。'))return; await api('agents/delete',{id:el.dataset.delete}); await refresh();}
  if(el.dataset.cancel) {await api('tasks/'+el.dataset.cancel+'/cancel',{}); toast('已发送停止请求'); await refresh();}
  if(el.dataset.closeDetail) {selectedTask=''; $('task-detail').classList.add('hidden'); render();}
  if(el.dataset.terminal) {selectedTerminal=el.dataset.terminal; $('terminal-output').textContent='正在读取终端输出…'; await refresh();}
  if(el.dataset.session) {const detail=await api('sessions/'+el.dataset.session);$('session-detail').innerHTML=`<h2>${esc(detail.session.title)}</h2><div class="detail-actions"><button class="button" data-continue="${esc(detail.session.id)}">继续这段对话</button></div>`+detail.tasks.map(t=>`<article class="session-turn"><b>你</b><p>${esc(t.prompt)}</p><b>协作结果 ${badge(t.status)}</b><pre>${esc(t.result||t.error||'尚未完成')}</pre><button class="button secondary small" data-open-task="${esc(t.id)}">查看轮次和日志</button></article>`).join('');}
  if(el.dataset.openTask) {selectedTask=el.dataset.openTask;detailSignature='';page('overview');await refresh();$('task-detail').scrollIntoView({behavior:'smooth'});}
  if(el.dataset.continue) {activeSession=el.dataset.continue;const s=state.sessions.find(s=>s.id===activeSession);$('session-banner').classList.remove('hidden');$('session-banner').innerHTML=`正在续接：${esc(s?.title||'已有对话')} <button class="button secondary small" data-new-session="true">改为新对话</button>`;page('overview');$('task-prompt').value='';$('task-prompt').focus();window.scrollTo({top:0,behavior:'smooth'});}
  if(el.dataset.newSession) {activeSession='';$('session-banner').classList.add('hidden');}
  if(el.dataset.resume||el.dataset.rerun) {const t=await api('tasks/'+(el.dataset.resume||el.dataset.rerun)+'/retry',{resume:!!el.dataset.resume});selectedTask=t.id;detailSignature='';await refresh();toast('任务已重新入队');}
  if(el.dataset.export) {const data=await api('tasks/'+el.dataset.export+'/export');if(state.desktop)await api('app',{action:'save-export',kind:'task',task_id:el.dataset.export});else download('AgentBridge-'+el.dataset.export+'.md',data.markdown,'text/markdown');}
  if(el.dataset.copy) {await navigator.clipboard.writeText($(el.dataset.copy).value);toast('配置已复制');}
  if(el.dataset.check) { $('check-output').textContent='正在检查命令，最多等待 10 秒…';$('check-dialog').showModal();const result=await api('agents/check',{id:el.dataset.check});$('check-output').textContent=JSON.stringify(result,null,2);}
}));
function filterTasks() {
  const query=$('task-search').value.toLowerCase();
  document.querySelectorAll('#task-list .task-item').forEach(el=>el.classList.toggle('hidden',!el.textContent.toLowerCase().includes(query)));
}
function download(name,body,type='application/json') {
  const url=URL.createObjectURL(new Blob([body],{type})); const link=document.createElement('a');link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),5000);
}
async function loadSettings() {
  const info=await api('integrations');
  $('app-info').textContent='Agent Bridge '+info.version+' · '+(state.desktop?'桌面应用':'本地服务')+'\n数据目录：'+info.data_dir;
  $('open-data').disabled=!state.desktop;
  $('mcp-json').value=JSON.stringify({mcpServers:{agent_bridge:info.mcp}},null,2);
  $('mcp-toml').value='[mcp_servers.agent_bridge]\ncommand = '+JSON.stringify(info.mcp.command)+'\nargs = '+JSON.stringify(info.mcp.args)+'\n';
}
function initTerminal() {
  if(xterm||$('page-terminal').classList.contains('hidden')) return;
  if(!window.Terminal) {$('terminal-output').classList.remove('hidden');return;}
  xterm=new Terminal({cursorBlink:true,fontFamily:'Consolas, "Microsoft YaHei", monospace',fontSize:13,scrollback:5000,allowProposedApi:false,theme:{background:'#18292c',foreground:'#d1e7de',cursor:'#81dbbd'},convertEol:false});
  fitAddon=new FitAddon.FitAddon(); xterm.loadAddon(fitAddon);xterm.open($('terminal-screen'));fitAddon.fit();
  xterm.onData(text=>{const tid=selectedTerminal;if(!state.terminals.find(t=>t.id===tid)?.alive)return;rawQueue=rawQueue.then(()=>api('terminals/'+tid,{action:'raw',text})).catch(e=>toast(e.message,true));});
  let resizeTimer; xterm.onResize(({rows,cols})=>{clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>{if(state.terminals.find(t=>t.id===selectedTerminal)?.alive)api('terminals/'+selectedTerminal,{action:'resize',rows,cols}).catch(()=>{});},150);});
  new ResizeObserver(()=>{if(!$('page-terminal').classList.contains('hidden'))fitAddon.fit();}).observe($('terminal-screen'));
  xterm.writeln('Agent Bridge · 启动一个会话，或选择历史终端。');
}
$('task-search').oninput=filterTasks;
$('close-check').onclick=()=>$('check-dialog').close();
$('choose-folder').onclick=safe(async()=>{const result=await api('app',{action:'choose-folder'});if(result.path)$('agent-cwd').value=result.path;});
$('open-data').onclick=safe(()=>api('app',{action:'open-data'}));
$('backup-config').onclick=safe(async()=>{if(state.desktop)await api('app',{action:'save-export',kind:'config'});else download('AgentBridge-config.json',JSON.stringify(await api('backup'),null,2));});
$('backup-full').onclick=safe(async()=>{if(state.desktop)await api('app',{action:'save-export',kind:'full'});else download('AgentBridge-history.json',JSON.stringify(await api('backup?history=1'),null,2));});
$('import-config').onclick=()=>$('import-file').click();
$('import-file').onchange=safe(async()=>{const file=$('import-file').files[0];if(!file)return;if(file.size>1900000)throw new Error('配置文件超过 1.9 MB，请只导出 Agent 配置');let config;try{config=JSON.parse(await file.text());}catch{throw new Error('不是有效的 JSON 配置');}const result=await api('backup/import',config);$('import-file').value='';await refresh();toast('已导入 '+result.imported+' 个 Agent 配置');});
$('discover-agents').onclick=safe(async()=>{const found=await api('agents/discover',{});let added=0;for(const a of found){if(state.agents.some(existing=>existing.preset===a.preset))continue;await api('agents',{name:a.preset==='codex'?'Codex · 开发伙伴':'Claude · 评审伙伴',preset:a.preset,cwd:state.cwd,argv:presets[a.preset],interactive_argv:[a.path],timeout:600});added++;}await refresh();toast(found.length?'检测到 '+found.length+' 个工具，新增 '+added+' 个配置':'未检测到 Codex 或 Claude 命令，请先安装或通过自定义命令添加');});
$('terminal-agent').onchange=()=>{const a=state.agents.find(a=>a.id===$('terminal-agent').value);if(a)$('terminal-command').value=JSON.stringify(a.interactive_argv||[a.argv[0]]);};
setInterval(()=>{if(selectedTerminal&&!$('page-terminal').classList.contains('hidden'))loadTerminal().catch(()=>{});},300);

(async()=>{try {const response=await fetch('/api/bootstrap'); if(!response.ok)throw new Error('无法连接服务'); token=(await response.json()).token; await refresh(); setInterval(()=>refresh().catch(()=>{}),1500);} catch(error){toast(error.message,true);}})();
