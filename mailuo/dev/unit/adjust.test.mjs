import {test} from 'node:test';
import assert from 'node:assert/strict';
import {makeStore} from './helpers.mjs';
import {applyAdjust} from '../../www/js/model.js';
const setup=() => makeStore([{id:'l',type:'论',title:'判断',confidence:85}, {id:'r',type:'规',title:'动作',under:'l',confidence:90}, {id:'other',type:'规',title:'无关动作'}]);

test('调整按确认值生成副本与 checks，from 采用当前真实把握',async () => {
  const s=await setup(), before=structuredClone(s.nodes.get('l'));
  const [n]=applyAdjust(s,{target:'l',state:'accepted',from:99,to:65,to_final:35,reason:'新证据',pause_selected:[]},{date:'2026-10-18'});
  assert.equal(n.confidence,35); assert.deepEqual(n.checks.at(-1),{date:'2026-10-18',kind:'调整',source:'录入',from:85,to:35,result:'新证据'});
  assert.deepEqual(s.nodes.get('l'),before);
});
test('只暂停勾选且属于目标后代的规，同时写暂停记录',async () => {
  const s=await setup();
  const out=applyAdjust(s,{target:'l',state:'accepted',to_final:35,pause_selected:['r','r','other','missing']});
  assert.equal(out.length,2); assert.equal(out[1].paused,true); assert.equal(out[1].checks.at(-1).kind,'暂停');
  assert.match(out[1].checks.at(-1).result,/上游【判断】降到 35%/); assert.equal(s.nodes.get('r').paused,false);
});
test('取消暂停勾选时保留动作，已暂停的规不重复写记录',async () => {
  const s=await setup(); assert.equal(applyAdjust(s,{target:'l',state:'accepted',to:40,pause_rules:['r'],pause_selected:[]}).length,1);
  s.nodes.get('r').paused=true; assert.equal(applyAdjust(s,{target:'l',state:'accepted',to:40,pause_rules:['r']}).length,1);
});
test('驳回、待定、目标已删除和问都不能应用调整',async () => {
  const s=await setup();
  for (const state of ['discarded','pending']) assert.deepEqual(applyAdjust(s,{target:'l',state,to:20}),[]);
  assert.deepEqual(applyAdjust(s,{target:'missing',state:'accepted',to:20}),[]);
  s.nodes.get('l').type='问'; assert.deepEqual(applyAdjust(s,{target:'l',state:'accepted',to:20}),[]);
});
test('调整数值夹在 0–100，非法数值不改库',async () => {
  const s=await setup(); assert.equal(applyAdjust(s,{target:'l',state:'accepted',to:200})[0].confidence,100);
  assert.equal(applyAdjust(s,{target:'l',state:'accepted',to:-10})[0].confidence,0);
  assert.deepEqual(applyAdjust(s,{target:'l',state:'accepted',to:'oops'}),[]);
});
