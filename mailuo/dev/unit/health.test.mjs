import {test} from 'node:test';
import assert from 'node:assert/strict';
import {makeStore} from './helpers.mjs';
import {HEALTH_RULES,healthCheck} from '../../www/js/features.js';
const date='2026-10-18';
const hit=(key,n,s) => HEALTH_RULES.find((r) => r.key===key).test(n,s,{today:date});
const base=[{id:'q',title:'课题',type:'问'}, {id:'l',title:'判断',under:'q',confidence:80,sentinels:[{when:'出现证据',delta:10}]}, {id:'r',title:'动作',type:'规',under:'l',confidence:90}];

test('议而不决：问和高把握论须有未暂停的后代规',async () => {
  const s=await makeStore(base); assert.equal(hit('undecided',s.nodes.get('q'),s),false); assert.equal(hit('undecided',s.nodes.get('l'),s),false);
  s.nodes.get('r').paused=true; assert.equal(hit('undecided',s.nodes.get('q'),s),true); assert.equal(hit('undecided',s.nodes.get('l'),s),true);
  s.nodes.get('l').confidence=74; assert.equal(hit('undecided',s.nodes.get('l'),s),false);
});
test('师出无名：无挂载和失效目标命中，正常挂载不命中',async () => {
  const s=await makeStore(base),r=s.nodes.get('r'); assert.equal(hit('orphanRule',r,s),false);
  r.under=''; assert.equal(hit('orphanRule',r,s),true); r.under='nope'; assert.equal(hit('orphanRule',r,s),true);
});
test('地基倒挂只看最近的上级论和严格阈值',async () => {
  const s=await makeStore(base),r=s.nodes.get('r'),l=s.nodes.get('l'); assert.equal(hit('inverted',r,s),false);
  l.confidence=69; assert.equal(hit('inverted',r,s),true); l.confidence=70; assert.equal(hit('inverted',r,s),false);
  l.confidence=60; r.confidence=85; assert.equal(hit('inverted',r,s),false);
});
test('盲信无哨：零分哨也命中，有实分和 90% 以上不命中',async () => {
  const s=await makeStore(base),l=s.nodes.get('l'); assert.equal(hit('noSentinel',l,s),false);
  l.sentinels=[{when:'观察',delta:0}]; assert.equal(hit('noSentinel',l,s),true); l.sentinels=[]; assert.equal(hit('noSentinel',l,s),true);
  l.confidence=90; assert.equal(hit('noSentinel',l,s),false);
});
test('脱离实操：日期边界、暂停、最近反馈和反复卡壳',async () => {
  const s=await makeStore(base),r=s.nodes.get('r'); r.created='2026-10-10'; assert.equal(hit('stale',r,s),true);
  r.created='2026-10-11'; assert.equal(hit('stale',r,s),false); r.created='2026-10-10';
  r.checks=[{date:'2026-10-11',kind:'有效'}]; assert.equal(hit('stale',r,s),false);
  r.checks=[{date:'2026-10-10',kind:'有效'}]; assert.equal(hit('stale',r,s),true);
  r.paused=true; assert.equal(hit('stale',r,s),false);
  r.confidence=40; r.checks=[{date:'2026-10-17',kind:'卡壳'},{date:'2026-10-18',kind:'卡壳'}]; assert.equal(hit('stale',r,s),true);
});
test('断链覆盖挂载和横向关系，存在的引用不命中',async () => {
  const s=await makeStore(base),r=s.nodes.get('r'); assert.equal(hit('broken',r,s),false);
  r.under='nope'; assert.equal(hit('broken',r,s),true); r.under='l'; r.links=[{type:'支撑',to:'nope'}]; assert.equal(hit('broken',r,s),true);
});
test('可能重复排除类型不同、直接及间接上下级、已有对立关系',async () => {
  const s=await makeStore([{id:'a',title:'相同判断'},{id:'b',title:'相同判断'}]); const a=s.nodes.get('a'),b=s.nodes.get('b');
  assert.equal(hit('dupe',a,s),true); b.type='规'; assert.equal(hit('dupe',a,s),false);
  b.type='论'; b.under='a'; assert.equal(hit('dupe',a,s),false); b.under=''; a.links=[{type:'对立',to:'b'}]; assert.equal(hit('dupe',a,s),false);
});
test('激活词不足和健康分算法、等级顺序保持稳定',async () => {
  const s=await makeStore([{id:'q',title:'课题',type:'问',keywords:[]}]),n=s.nodes.get('q'); assert.equal(hit('kwCount',n,s),true);
  const result=healthCheck(s,undefined,{today:date}); assert.deepEqual(result.issues.map((i) => i.key),['undecided','kwCount']); assert.equal(result.score,30);
  n.keywords=['a','b','c']; assert.equal(hit('kwCount',n,s),false);
});
