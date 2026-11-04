import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync,readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
const home=mkdtempSync(join(tmpdir(),'aware-http-'));process.env.DSH_HOME=home
const {apply}=await import('../lib/index.js')
const {Pairing}=await import('../lib/pairing.js')
test('observe uses the existing token gate; only the observation route accepts batched bodies above 16KB',async()=>{
 let handler
 const ctx={get:()=>undefined,on:()=>{},effect:()=>{},inject:(_,f)=>f({effect:g=>g(),webServer:{register:r=>{handler=r.handler}}})}
 apply(ctx)
 const call=async(path,body,headers={})=>{
   const req=Readable.from([Buffer.from(JSON.stringify(body))]);Object.assign(req,{url:'/dsh-driver-core/v1'+path,method:'POST',headers:{host:'127.0.0.1:3080','content-type':'application/json',...headers}})
   const res={status:0,text:'',writeHead(s){this.status=s},end(b){this.text=b??''}}
   await handler(req,res);return {status:res.status,body:res.text?JSON.parse(res.text):null}
 }
 const code=new Pairing().issueCode(3080).split(':')[1]
 const paired=await call('/app/pair',{code});const auth={authorization:`Bearer ${paired.body.token}`}
 const now=Date.now()
 const rows=Array.from({length:20},(_,i)=>({id:'obs-'+i,kind:'micro',at:now,from:now-300000,to:now,answers:{did:'实际回答'.repeat(125),why:'理由'.repeat(200)},apps:[]}))
 assert.equal((await call('/app/observe',{observations:rows})).status,401)
 assert.equal((await call('/app/observe',{observations:rows},{...auth,origin:'https://example.com'})).status,403)
 const r=await call('/app/observe',{observations:rows},auth)
 assert.equal(r.status,200);assert.equal(r.body.accepted.length,20)
 assert.equal((await call('/app/observe',{observations:rows},auth)).status,200)
 const stored=readFileSync(join(home,'storages/dsh-driver-core/observations.jsonl'),'utf8').trim().split('\n')
 assert.equal(stored.length,20,'HTTP replay must not duplicate records')
 assert.equal((await call('/app/report',{observations:rows},auth)).status,413,'old route size limit is preserved')
 assert.equal((await call('/app/shot',{anything:true},auth)).status,404,'no screenshot upload endpoint exists')
})
