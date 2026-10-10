// 首个试用体系「思维问题」：5 个故障 + 两个竞争根因（对立）。
// 5 个故障的措辞是占位，请用 GPT 原始总结替换（节点页 → 编辑）。
export const SEED_SYSTEM = '思维问题';
export const SEED_LEVELS = ['加工层', '检验层', '输出层', '根因'];

export const SEED_NODES = [
  {
    id: 'n-0001', title: '第一反应停止', type: '论断', parent: '加工层',
    keywords: ['二次加工', '依据', '停止信号'], status: '假设', confidence: 60,
    verify: '记一周"舒缓感后多做一步"的次数', source: 'GPT 总结',
    claim: '第一反应出现后不再继续加工，把念头当答案。',
    links: [{ type: '导致', to: 'n-0002', why: '不加工则推不出 D' }],
  },
  {
    id: 'n-0002', title: '推理链断', type: '论断', parent: '加工层',
    keywords: ['推理链', '中间步骤', '演绎'], status: '假设', confidence: 55,
    verify: '每次卡住时写下 A→B→C，标出断在哪一步，记一周', source: 'GPT 总结',
    claim: '能从 A 推到 B、C，但推不出 D，链条在中途断掉。',
    links: [{ type: '导致', to: 'n-0005', why: '链断了，就落不到可执行的结论' }],
  },
  {
    id: 'n-0003', title: '依据缺失', type: '论断', parent: '检验层',
    keywords: ['证据', '可证伪', '确认偏误'], status: '假设', confidence: 50,
    verify: '每个结论旁写一条依据，统计写不出依据的比例', source: 'GPT 总结',
    claim: '结论给出了，却说不出支撑它的依据。',
    links: [{ type: '类似', to: 'n-0001', why: '都是在「够了」的感觉出现时停下' }],
  },
  {
    id: 'n-0004', title: '只看一面', type: '论断', parent: '检验层',
    keywords: ['竞争假设', '反例', '视角切换'], status: '假设', confidence: 50,
    verify: '每次下结论前强制写一个竞争解释，记录能否写出', source: 'GPT 总结',
    claim: '只沿一种解释往下想，不主动生成竞争解释或反例。',
    links: [],
  },
  {
    id: 'n-0005', title: '结论不落地', type: '论断', parent: '输出层',
    keywords: ['行动项', '验证闭环', '下一步'], status: '假设', confidence: 50,
    verify: '想完一件事后是否写出一个本周能做的小动作，记一周', source: 'GPT 总结',
    claim: '想完之后没有形成可执行的下一步，也不回头验证。',
    links: [],
  },
  {
    id: 'n-0006', title: '下一问没有产生', type: '论断', parent: '根因',
    keywords: ['追问', '问题生成', '好奇心'], status: '假设', confidence: 50,
    verify: '卡住时记录脑中是否冒出了下一个问题（有 / 无），记一周', source: '对话材料',
    claim: '思考停下，是因为没有生成下一个问题可以继续追。',
    links: [
      { type: '导致', to: 'n-0001', why: '没有下一问，第一反应就成了终点' },
      { type: '对立', to: 'n-0007', why: '与「单步成本高」竞争根因' },
    ],
  },
  {
    id: 'n-0007', title: '单步成本高、停止信号来得早', type: '论断', parent: '根因',
    keywords: ['认知负荷', '停止信号', '舒缓感'], status: '假设', confidence: 50,
    verify: '把每一步写在纸上降低单步成本，比较一周内多走一步的次数是否上升', source: '对话材料',
    claim: '每多想一步都很费力，一出现「差不多了」的舒缓感就停下。',
    links: [
      { type: '导致', to: 'n-0001', why: '成本高 → 舒缓感一来就停' },
      { type: '对立', to: 'n-0006', why: '与「下一问没有产生」竞争根因' },
    ],
  },
];

export async function loadSeed(store) {
  const s = store.ensureSystemSync(SEED_SYSTEM);
  s.desc = '首个试用体系。5 个故障来自 GPT 总结（措辞为占位，请替换为原文），两个根因互为对立，各带一条验证方式。';
  s.levels = [...SEED_LEVELS];
  for (const n of SEED_NODES) {
    if (store.nodes.has(n.id)) continue;
    // 先放进索引，保证写双链时能找到目标标题
    store.nodes.set(n.id, { ...structuredClone(n), system: SEED_SYSTEM, note: '', checks: [], extra: {} });
  }
  for (const n of SEED_NODES) await store.saveNode(store.nodes.get(n.id));
  await store.saveSystem(s);
  store.emit();
}
