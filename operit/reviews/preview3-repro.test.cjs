'use strict';
// 复现 docs/04_对preview3的审查.md 中的 P1、P3 及覆盖范围问题。用法：P3=<解压后的 preview.3 目录> node --test reviews/preview3-repro.test.cjs
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const P=process.env.P3;
const {makeStore}=require(P+'/src/node-store.cjs'),{makeRuntime}=require(P+'/src/runtime.js'),{makeControl,decide}=require(P+'/src/control-plane.js'),{makeLive}=require(P+'/src/live-control.js');
const Observer=require(P+'/src/device-observer.js');
const NOW=1791123000000;
function env(model){
 let now=NOW;const store=makeStore(fs.mkdtempSync(path.join(os.tmpdir(),'rp-'))),clock=()=>now,r=makeRuntime(store,{},clock);
 r.event({type:'start',event_id:'s',session_id:'sess',title:'t',object:'o',goal:'g'});
 const c=makeControl(store,clock);c.configure({task_binding:{session_id:'sess',purpose:'real_task',source_ref:'x'},contact_enabled:true},r.read().core.revision,0);
 const live=makeLive(store,{model},clock);const rev=()=>{const d=store.read();return [d.core.revision,d.control_plane.revision];};
 return {store,r,live,rev,advance:ms=>now+=ms};
}
test('R1: model reply wrapped in ```json fence fails, and the user cannot retry for this question',async()=>{
 const fenced={finishReason:'stop',receivedAt:NOW,text:'```json\n'+JSON.stringify({question:'q',scaffold:'s',contribution:'c'})+'\n```'};
 let calls=0;const e=env(async()=>{calls++;return calls===1?fenced:{finishReason:'stop',receivedAt:NOW,text:JSON.stringify({question:'q2',scaffold:'s',contribution:'c'})};});
 const qid=e.r.read().core.session.current_question_id;
 const first=await e.live.coach('panel-coach:'+qid,...e.rev());
 assert.equal(first.status,'failed');
 const again=await e.live.coach('panel-coach:'+qid,...e.rev());
 assert.equal(again.duplicate,true,'same panel id returns the failed job');
 await assert.rejects(()=>e.live.coach('panel-coach-retry:'+qid,...e.rev()),{code:'COACH_ALREADY_ATTEMPTED'});
 assert.equal(calls,1);
});
test('R2: real observer always reports partial coverage, so a timed restriction can never be proposed',async()=>{
 const snap=await Observer.collect({exists:async()=>({exists:false}),readTail:async()=>{throw Error('x')},deviceState:async()=>({screen:'on',locked:false}),
   inspect:async()=>({package_name:'tv.danmaku.bili',nodes:[{text:'x'}]})},'sample');
 assert.ok(Object.values(snap.snapshot.coverage).every(x=>x.status==='partial'));
});
test('R3: one historical 小满 pause (no until) blocks contact unless the window override is used',async()=>{
 const progress=JSON.stringify({type:'USER_PROGRESS',origin:'REAL_USER',kind:'pause',ts:Math.floor(NOW/1000)-3600,id:'p1'});
 const snap=await Observer.collect({exists:async p=>({exists:/progress/.test(p)}),readTail:async()=>({content:progress,totalLines:1,startLine:1,endLine:1}),
   deviceState:async()=>({screen:'on',locked:false}),inspect:async()=>({package_name:'com.ai.assistance.operit',nodes:[{text:'x'}]})},'s2',()=>NOW);
 assert.equal(snap.snapshot.pauses.length>=1,true);
 assert.ok(snap.snapshot.pauses.every(p=>p.scope==='unknown'&&p.active===true&&p.until===undefined));
});
