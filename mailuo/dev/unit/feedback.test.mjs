import {test} from 'node:test';
import assert from 'node:assert/strict';
import {makeStore} from './helpers.mjs';
import {blankNode} from '../../www/js/model.js';
import {applyFeedback,dueReviews} from '../../www/js/features.js';
const fixture=() => makeStore([{id:'q',title:'课题',type:'问'}, {id:'l',title:'判断',under:'q',confidence:50,sentinels:[{when:'观察',delta:5}]}, {id:'r',title:'动作',type:'规',under:'l',confidence:60}]);
test('有效与卡壳修改规和最近上级论，保持原节点不变',async () => {
  const s=await fixture();
  assert.deepEqual(applyFeedback(s,'r','有效').map((n) => n.confidence),[63,52]);
  assert.deepEqual(applyFeedback(s,'r','卡壳').map((n) => n.confidence),[55,47]);
  assert.equal(s.nodes.get('r').confidence,60); assert.equal(s.nodes.get('l').confidence,50);
});
test('反馈上下限与实际变化分值',async () => {
  const s=await fixture(); s.nodes.get('r').confidence=94; s.nodes.get('l').confidence=95;
  const up=applyFeedback(s,'r','有效'); assert.deepEqual(up.map((n) => n.confidence),[95,95]); assert.deepEqual(up.map((n) => n.checks.at(-1).delta),[1,0]);
  s.nodes.get('r').confidence=6; s.nodes.get('l').confidence=5;
  assert.deepEqual(applyFeedback(s,'r','卡壳').map((n) => n.confidence),[5,5]);
});
test('反馈记录日期来源备注，上级注明下游动作',async () => {
  const s=await fixture(),out=applyFeedback(s,'r','有效',{today:'2026-10-18',source:'温故',note:'今天实操过'});
  assert.deepEqual(out[0].checks.at(-1),{date:'2026-10-18',kind:'有效',source:'温故',delta:3,confidence:63,result:'今天实操过'});
  assert.equal(out[1].checks.at(-1).result,'下游【动作】实操有效');
});
test('没有上级论也可反馈，空把握从 50 起算，非法目标不反馈',async () => {
  const s=await fixture(); s.nodes.get('r').under='q'; s.nodes.get('r').confidence=null;
  assert.deepEqual(applyFeedback(s,'r','有效').map((n) => n.confidence),[53]);
  assert.deepEqual(applyFeedback(s,'l','有效'),[]); assert.deepEqual(applyFeedback(s,'missing','卡壳'),[]);
  for (const kind of ['oops','max','min']) assert.deepEqual(applyFeedback(s,'r',kind),[]); s.nodes.get('r').paused=true; assert.deepEqual(applyFeedback(s,'r','有效'),[]);
});
test('到期队列：未复习的规、按到期日的规、带哨论；排除暂停和未到期',async () => {
  const s=await fixture();
  for (const [id,extra] of [['r2',{}],['r3',{}],['r4',{paused:true}],['r5',{}]]) await s.saveNode(blankNode({id,title:id,type:'规',system:'测试',under:'l',...extra}));
  s.review.r={due:'2026-10-16'}; s.review.r3={due:'2026-10-15'}; s.review.r5={due:'2026-10-19'};
  const ids=dueReviews(s,20,{today:'2026-10-18'}).map((n) => n.id);
  assert.deepEqual(ids,['r2','r3','r','l']);
  assert.deepEqual(dueReviews(s,2,{today:'2026-10-18'}).map((n) => n.id),['r2','r3']);
});
