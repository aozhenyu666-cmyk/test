// 开发服务器：静态托管 www/，并在 /mock/v1/chat/completions 模拟模型接口（OpenAI 兼容）。
//   node dev/server.mjs          → http://localhost:5178
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'www');
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json', '.json': 'application/json' };
export const calls = [];


// 录入 V2「碰撞引擎」的模拟输出：基于示例体系「思维问题」的节点 id
export const COLLIDE = {
  adjust: [
    { target: 'n-0006', from: 50, to: 65, reason: '材料里写下下一问之后又推进了两步', hit: '卡住时写下下一问后，能继续推进至少一步', pause_rules: [] },
    { target: 'n-0007', from: 50, to: 40, reason: '精力好的上午也在第一步就停了', hit: '', pause_rules: [] },
  ],
  nodes: [
    { tmp_id: 't1', type: '规', title: '累了先把下一步写在纸上', claim: '觉得脑子转不动时，先把下一步写在纸上再决定停不停。', under: 'n-0007',
      keywords: ['外化', '纸笔', '认知负荷'], status: '假设', confidence: 55,
      triggers: ['觉得脑子转不动的时候'], cost: '累了就停，链条永远断在第二步。' },
    { tmp_id: 't2', type: '论', title: '写下来能延后停止点', claim: '把中间结论写下来，停止点会明显后移。', under: 'n-0100',
      keywords: ['外化', '工作记忆', '停止点'], status: '假设', confidence: 60,
      blindspot: '如果问题本身信息不足，写下来也推不动。',
      sentinels: [{ when: '写下来后多走了两步以上', delta: 10 }, { when: '写下来后停止点没变', delta: -10 }] },
  ],
  links: [{ from: 't2', to: 'n-0007', type: '反例', why: '外化降低了单步成本' }],
};

// 沙盘推演的模拟输出：三条互斥路径，覆盖现成 / 最小验证 / 自建三类，各自带具体失败点
export const OPTIONS = {
  paths: [
    { title: '现成提醒工具 + 外部打断', kind: '现成', hypothesis: '停下是因为没有外部打断点，而不是不会想', fatal: '现成工具的提醒粒度只到小时级，而追问的时机在秒级', test: '装一个能用 API 的提醒工具，记一周打断成功的次数', cost: '半天' },
    { title: '纸笔追问卡', kind: '最小验证', hypothesis: '把下一问写下来就能多走一步', fatal: '写下下一问却仍然不想答，说明卡点不在「没有问题」', test: '连续七天，卡住时写下下一问，记录是否推进', cost: '一小时' },
    { title: '自建追问引擎', kind: '自建', hypothesis: '需要按知识结构自动生成下一个问题', fatal: '两周内做不出能判断「这一步值不值得追」的规则', test: '用现有模型跑十个真实卡壳片段，看生成的问题有没有用', cost: '两周' },
  ],
};

const ANSWER = `- 核心判断：真正的根因更可能是「单步成本高」，下一问没产生只是它的表现。
- 结构：单步成本高 → 舒缓感提前出现 → 第一反应停止 → 推理链断
- 另一种可能：问题生成能力本身弱，与成本无关。
- 如果我错了，最可能错在哪：把「累」误读成「没问题可问」，两者在体验上很像。
- 把握：六成
- 验证：本周把每一步写在纸上，比较多走一步的次数是否上升。
- 建议新增：
  论｜舒缓感提前｜成本高时，舒缓感会在第一步之后就出现｜舒缓感、认知负荷、停止信号｜哨：+10 疲劳时第一步之后就想停
  对立·论｜问题生成能力弱｜停下是因为不会提问，与成本无关｜提问、问题生成、好奇心｜哨：-10 手里有问题清单仍然停下
  规｜累了先写在纸上｜觉得累时，先把下一步写在纸上再决定停不停｜外化、纸笔、下一步｜触发：觉得脑子转不动的时候`;

export function startServer(port = 0) {
  const server = createServer(async (req, res) => {
    if (req.method === 'OPTIONS') { res.writeHead(204, cors()); return res.end(); }
    if (req.url.startsWith('/mock/')) {
      let body = '';
      for await (const c of req) body += c;
      const data = JSON.parse(body || '{}');
      const sys = data.messages?.[0]?.content || '';
      const user = data.messages?.[1]?.content || '';
      calls.push({ sys, user, model: data.model, auth: req.headers.authorization });
      const kwFill = sys.includes('激活词生成器')
        ? JSON.stringify(Object.fromEntries(user.split('\n').map((l) => l.split(' ')[0]).filter(Boolean).map((id, i) => [id, ['追问链', '元认知', '停止规则' + i]])))
        : null;
      // 整理挂载：把未挂载的论挂到新建的问 q1 下，规挂到第一个论下
      const mountFix = sys.includes('整理挂载') ? (() => {
        const lines = user.split('\n\n')[0].split('\n').slice(1).map((l) => l.split(' ')).filter((p) => p.length > 1);
        const firstLun = lines.find((p) => p[1] === '论')?.[0];
        return JSON.stringify({
          questions: [{ tmp_id: 'q1', title: '旧笔记要回答的问题', claim: '把 v1 的论归到一个课题下', pillars: ['精力有限', '反馈延迟'] }],
          mounts: lines.map((p) => ({ id: p[0], under: p[1] === '规' ? firstLun : 'q1', reason: '模拟' })),
        });
      })() : null;
      const content = mountFix ? mountFix : sys.includes('沙盘推演') ? JSON.stringify(OPTIONS) : sys.includes('碰撞引擎') ? JSON.stringify(COLLIDE) : kwFill ? kwFill : sys.includes('只回复 OK') ? 'OK' : ANSWER;
      await new Promise((r) => setTimeout(r, 300));
      res.writeHead(200, { 'content-type': 'application/json', ...cors() });
      return res.end(JSON.stringify({ choices: [{ message: { content } }], usage: { prompt_tokens: Math.ceil((sys.length + user.length) * 0.8), completion_tokens: Math.ceil(content.length * 0.8) } }));
    }
    if (req.url === '/mock-calls') { res.writeHead(200, { 'content-type': 'application/json' }); return res.end(JSON.stringify(calls)); }
    let p = decodeURIComponent(req.url.split('?')[0]);
    if (p.endsWith('/')) p += 'index.html';
    try {
      const buf = await readFile(join(root, p));
      res.writeHead(200, { 'content-type': TYPES[extname(p)] || 'application/octet-stream', 'cache-control': 'no-store' });
      res.end(buf);
    } catch { res.writeHead(404); res.end('not found'); }
  });
  return new Promise((r) => server.listen(port, () => r(server)));
}
function cors() { return { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': 'POST, OPTIONS' }; }

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const s = await startServer(+process.env.PORT || 5178);
  console.log('脉络 dev server: http://localhost:' + s.address().port + '  （模型地址填 http://localhost:' + s.address().port + '/mock/v1，密钥随便填）');
}
