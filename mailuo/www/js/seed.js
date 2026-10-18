// 首个试用体系「思维问题」（V2：问 → 论 → 规 链条，槽位都填好了，供各任务做界面时当样例数据）
//   问 n-0100 为什么想到一半就停？
//     论 n-0006 下一问没有产生 ⟷ 对立 ⟷ 论 n-0007 单步成本高、停止信号来得早（两个竞争根因）
//       规 n-0101 卡住时先写下下一问（挂在 n-0006 下）
//     论 n-0001…n-0005 五个故障（措辞是占位，请用 GPT 原始总结替换）
import { blankNode } from './model.js';
export const SEED_SYSTEM = '思维问题';
export const SEED_LEVELS = ['加工层', '检验层', '输出层', '根因'];

export const SEED_NODES = [
  {
    id: 'n-0100', title: '为什么想到一半就停？', type: '问', under: '', parent: '',
    keywords: ['思考深度', '停止点', '元认知'], status: '假设', confidence: null, verify: '', source: '对话材料',
    claim: '想问题时常在第一、二步就停下，结论浅、落不了地。',
    pillars: ['工作记忆有限：同时只能抓住 3–4 个中间结论', '反馈延迟：多想一步的收益要很久后才看得到'],
    links: [],
  },
  {
    id: 'n-0001', title: '第一反应停止', type: '论', under: 'n-0100', parent: '加工层',
    keywords: ['二次加工', '依据', '停止信号'], status: '假设', confidence: 60,
    verify: '记一周"舒缓感后多做一步"的次数', source: 'GPT 总结',
    claim: '第一反应出现后不再继续加工，把念头当答案。',
    links: [{ type: '导致', to: 'n-0002', why: '不加工则推不出 D' }],
  },
  {
    id: 'n-0002', title: '推理链断', type: '论', under: 'n-0100', parent: '加工层',
    keywords: ['推理链', '中间步骤', '演绎'], status: '假设', confidence: 55,
    verify: '每次卡住时写下 A→B→C，标出断在哪一步，记一周', source: 'GPT 总结',
    claim: '能从 A 推到 B、C，但推不出 D，链条在中途断掉。',
    links: [{ type: '导致', to: 'n-0005', why: '链断了，就落不到可执行的结论' }],
  },
  {
    id: 'n-0003', title: '依据缺失', type: '论', under: 'n-0100', parent: '检验层',
    keywords: ['证据', '可证伪', '确认偏误'], status: '假设', confidence: 50,
    verify: '每个结论旁写一条依据，统计写不出依据的比例', source: 'GPT 总结',
    claim: '结论给出了，却说不出支撑它的依据。',
    links: [{ type: '类似', to: 'n-0001', why: '都是在「够了」的感觉出现时停下' }],
  },
  {
    id: 'n-0004', title: '只看一面', type: '论', under: 'n-0100', parent: '检验层',
    keywords: ['竞争假设', '反例', '视角切换'], status: '假设', confidence: 50,
    verify: '每次下结论前强制写一个竞争解释，记录能否写出', source: 'GPT 总结',
    claim: '只沿一种解释往下想，不主动生成竞争解释或反例。',
    links: [],
  },
  {
    id: 'n-0005', title: '结论不落地', type: '论', under: 'n-0100', parent: '输出层',
    keywords: ['行动项', '验证闭环', '下一步'], status: '假设', confidence: 50,
    verify: '想完一件事后是否写出一个本周能做的小动作，记一周', source: 'GPT 总结',
    claim: '想完之后没有形成可执行的下一步，也不回头验证。',
    links: [],
  },
  {
    id: 'n-0006', title: '下一问没有产生', type: '论', under: 'n-0100', parent: '根因',
    keywords: ['追问', '问题生成', '好奇心'], status: '假设', confidence: 50,
    verify: '卡住时记录脑中是否冒出了下一个问题（有 / 无），记一周', source: '对话材料',
    claim: '思考停下，是因为没有生成下一个问题可以继续追。',
    blindspot: '如果手边有现成的问题清单仍然停下，说明根因不在「有没有下一问」。',
    sentinels: [
      { when: '卡住时写下下一问后，能继续推进至少一步', delta: 10 },
      { when: '已经写出下一问，但仍然不想往下想', delta: -15 },
    ],
    links: [
      { type: '导致', to: 'n-0001', why: '没有下一问，第一反应就成了终点' },
      { type: '对立', to: 'n-0007', why: '与「单步成本高」竞争根因' },
    ],
  },
  {
    id: 'n-0007', title: '单步成本高、停止信号来得早', type: '论', under: 'n-0100', parent: '根因',
    keywords: ['认知负荷', '停止信号', '舒缓感'], status: '假设', confidence: 50,
    verify: '把每一步写在纸上降低单步成本，比较一周内多走一步的次数是否上升', source: '对话材料',
    claim: '每多想一步都很费力，一出现「差不多了」的舒缓感就停下。',
    blindspot: '精力充沛、单步很轻松时仍然早停，这条就站不住。',
    sentinels: [
      { when: '把步骤写到纸上后，多走一步的次数明显上升', delta: 10 },
      { when: '纸笔外化后停止点没有变化', delta: -15 },
    ],
    links: [
      { type: '导致', to: 'n-0001', why: '成本高 → 舒缓感一来就停' },
      { type: '对立', to: 'n-0006', why: '与「下一问没有产生」竞争根因' },
    ],
  },
];

SEED_NODES.push({
  id: 'n-0101', title: '卡住时先写下下一问', type: '规', under: 'n-0006', parent: '根因',
  keywords: ['追问', '外化', '下一步'], status: '假设', confidence: 60, verify: '', source: '对话材料',
  claim: '一觉得「差不多了」，先在纸上写下一个具体的下一问，再决定停不停。',
  triggers: ['觉得「差不多想清楚了」的那一刻', '准备关掉笔记去做别的事时'],
  cost: '第一反应直接变成结论，同样的坑下周再踩一次。',
  links: [],
});

export async function loadSeed(store) {
  const s = store.ensureSystemSync(SEED_SYSTEM);
  s.desc = '首个试用体系。5 个故障来自 GPT 总结（措辞为占位，请替换为原文），两个根因互为对立，各带一条验证方式。';
  s.levels = [...SEED_LEVELS];
  for (const n of SEED_NODES) {
    if (store.nodes.has(n.id)) continue;
    // 先放进索引，保证写双链时能找到目标标题
    store.nodes.set(n.id, blankNode({ ...structuredClone(n), system: SEED_SYSTEM }));
  }
  for (const n of SEED_NODES) await store.saveNode(store.nodes.get(n.id));
  await store.saveSystem(s);
  store.emit();
}
