import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtempSync,readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createAwareness,observationOf } from '../lib/awareness.js'
import { makeStore } from '../lib/store.js'
import { AgentBrain } from '../lib/brain.js'

const NOW=1800000000000
const row=(id,kind='micro')=>({id,at:NOW,from:NOW-300000,to:NOW,kind,answers:{did:'第3题',drift:'去了B站'},apps:[{pkg:'bili',label:'B站',sec:120}],unknownSec:180})
const plan=JSON.stringify({focus:'先做第3题',pattern:'未确定',style:'coach',nextQuestions:['现在卡在哪里？']})
function service(options={}) {
 const root=mkdtempSync(join(tmpdir(),'aware-')),store=makeStore(root)
 const prompts=[],permissions=[];let now=NOW
 const brain={ask:async(p,timeout,permission)=>{prompts.push(p);permissions.push(permission);return options.reply===undefined?plan:options.reply}}
 const svc=createAwareness({store,brain,getConfig:()=>({brainSessionId:'brain'}),memories:()=>[],now:()=>now})
 return {root,store,svc,prompts,permissions,advance:()=>{now+=31000}}
}
const settle=()=>new Promise(resolve=>setImmediate(resolve))

test('observe persists, acknowledges duplicates and validates entire batch before mutation',async()=>{
 const t=service()
 assert.equal(t.svc.observe({observations:[row('a'),row('b','deep')]}).status,200)
 await settle()
 assert.deepEqual(t.store.loadObservations().map(r=>r.id),['a','b'])
 assert.equal(t.store.loadPlan().focus,'先做第3题')
 assert.equal(t.permissions[0].rememberLimit,0,'fewer than three explicit drift answers cannot write memory')
 assert.deepEqual(t.svc.observe({observations:[row('b','deep')]}).body.accepted,['b'])
 await settle();assert.equal(t.prompts.length,1,'retry cannot requeue completed digestion')
 assert.equal(t.svc.observe({observations:[row('c'),{...row('bad'),id:'../../escape'}]}).status,400)
 assert.equal(t.store.loadObservations().length,2)
 assert.equal(t.svc.observe({observations:Array.from({length:21},(_,i)=>row(String(i)))}).status,400)
 assert.equal(makeStore(t.root).loadPlan().focus,'先做第3题','plan survives restart')
})
test('pending digestion survives a busy model and coalesces offline deep records',async()=>{
 const t=service({reply:null})
 t.svc.observe({observations:[row('a','deep'),row('b','deep'),row('c','deep')]});await settle()
 assert.equal(t.store.loadDigestPending().id,'c')
 assert.equal(t.permissions[0].rememberLimit,2)
 assert.equal(await t.svc.digest(),false,'busy retries are throttled')
 t.advance();await t.svc.digest();assert.equal(t.prompts.length,2)
 assert.equal(t.store.loadPlan(),null,'unparseable/unavailable model never overwrites a plan')
 const restarted=createAwareness({store:makeStore(t.root),brain:{ask:async()=>plan},getConfig:()=>({brainSessionId:'brain'}),memories:()=>[],now:()=>NOW+61000})
 assert.equal(await restarted.digest(),true)
 assert.equal(t.store.loadDigestPending(),null)
})
test('checkin contract enforces one/four questions, fallback and actual observation context',async()=>{
 const t=service({reply:JSON.stringify({questions:['B站用了120秒，当时在找什么？']})})
 t.svc.observe({observations:[row('a')]})
 const r=await t.svc.compose({intent:'checkin_micro',mode:'supervise',observation:row('current')})
 assert.equal(r.body.source,'dsh');assert.equal(r.body.questions.length,1)
 assert.ok(t.prompts[0].includes('B站'))
 assert.ok(t.prompts[0].includes('supervise'))
 const bad=await t.svc.compose({intent:'checkin_deep',mode:'partner'})
 assert.equal(bad.body.source,'fixed');assert.equal(bad.body.questions.length,4)
})
test('bad times, impossible usage, private paths and raw extra fields are rejected or stripped',()=>{
 assert.equal(observationOf({...row('x'),to:NOW-500000},NOW),null)
 assert.equal(observationOf({...row('x'),at:NOW+120000},NOW),null)
 assert.equal(observationOf({...row('x'),apps:[{pkg:'a',sec:200},{pkg:'b',sec:200}]},NOW),null)
 const clean=observationOf({...row('x'),screenshot:'/private/secret.jpg',unknownSec:300,extra:'secret'},NOW)
 assert.equal(clean.unknownSec,180)
 assert.ok(!JSON.stringify(clean).includes('secret'))
 assert.equal(clean.hasScreenshot,false)
})
test('native remember is the sole exception, capped at two and restricted until turn end after timeout',async()=>{
 let status='idle',n=0
 const messages=[]
 const b=new AgentBrain({sessionId:()=> 'brain',findAgent:()=>({status,followup:m=>messages.push(m)}),makeMessage:text=>({id:String(++n),content:[{text}]})})
 const pending=b.ask('digest',15,{rememberLimit:2})
 b.onSessionEvent('brain',{type:'turn/start',data:{turn:7}})
 b.onSessionEvent('brain',{type:'user/message',data:messages[0]})
 assert.equal(b.guardTool('other','remember'),undefined)
 assert.equal(b.guardTool('brain','remember'),undefined)
 assert.equal(b.guardTool('brain',{name:'remember'}),undefined)
 for(const name of ['remember','run_code','shell','fs.write','forget','fish-memory.remember']) assert.equal(b.guardTool('brain',name).kind,'deny',name)
 assert.equal(await pending,null)
 assert.equal(await b.ask('another'),null,'timed-out active turn cannot lose its tool guard')
 assert.equal(b.guardTool('brain','shell').kind,'deny')
 b.onSessionEvent('brain',{type:'turn/end',data:{turn:7,reason:{kind:'completed'}}})
 assert.equal(b.guardTool('brain','shell'),undefined)
 const normal=b.ask('question',100)
 b.onSessionEvent('brain',{type:'turn/start',data:{turn:8}})
 b.onSessionEvent('brain',{type:'user/message',data:messages[1]})
 assert.equal(b.guardTool('brain','remember').kind,'deny')
 b.onAgentGone('brain');await normal
})

test('host session id aliases keep the same tool restriction and clean up on disposal',async()=>{
 let message
 const b=new AgentBrain({sessionId:()=> 'session-brain',findAgent:()=>({status:'idle',followup:m=>{message=m}}),makeMessage:text=>({id:'alias',content:[{text}]})})
 const request=b.ask('digest',100,{rememberLimit:1})
 b.onSessionEvent('brain',{type:'turn/start',data:{turn:1}})
 b.onSessionEvent('session-brain',{type:'user/message',data:message})
 assert.equal(b.guardTool('brain','shell').kind,'deny')
 assert.equal(b.guardTool('session-brain','remember'),undefined)
 assert.equal(b.guardTool('brain','remember').kind,'deny')
 b.onAgentGone('brain');assert.equal(await request,null)
 assert.equal(b.guardTool('session-brain','shell'),undefined)
})
