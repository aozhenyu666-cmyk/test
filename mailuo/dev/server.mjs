// 开发服务器：静态托管 www/，并在 /mock/v1/chat/completions 模拟模型接口（OpenAI 兼容）。
//   node dev/server.mjs          → http://localhost:5178
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'www');
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json', '.json': 'application/json' };
export const calls = [];

const SPLIT = {
  nodes: [
    { tmp_id: 't1', title: '第一反应即停', type: '论断', claim: '第一个念头出现后就停止加工，把它当成答案。', keywords: ['二次加工', '停止信号', '依据'], status: '假设', confidence: 60, verify: '记一周舒缓感后多做一步的次数', merge_into: '' },
    { tmp_id: 't2', title: '舒缓感', type: '概念', claim: '想到一个说得通的答案时出现的放松感，常被误当作「想完了」。', keywords: ['停止信号', '认知闭合', '情绪'], status: '假设', confidence: 50, verify: '每次停下时记录是否伴随舒缓感', merge_into: '' },
    { tmp_id: 't3', title: '写下下一问', type: '规则', claim: '停下之前，先写出下一个问题。', keywords: ['追问', '问题生成', '行动项'], status: '已验证', confidence: 70, verify: '', merge_into: '' },
  ],
  links: [
    { from: 't2', to: 't1', type: '导致', why: '舒缓感充当了停止信号' },
    { from: 't3', to: 't1', type: '反例', why: '强制下一问可以打断过早停止' },
    { from: 't1', to: 't2', type: '自造关系', why: '应被过滤' },
  ],
};

const ANSWER = `- 核心判断：真正的根因更可能是「单步成本高」，下一问没产生只是它的表现。
- 结构：单步成本高 → 舒缓感提前出现 → 第一反应停止 → 推理链断
- 另一种可能：问题生成能力本身弱，与成本无关。
- 如果我错了，最可能错在哪：把「累」误读成「没问题可问」，两者在体验上很像。
- 把握：六成
- 验证：本周把每一步写在纸上，比较多走一步的次数是否上升。
- 建议新增：
  导致｜舒缓感提前｜成本高时，舒缓感会在第一步之后就出现
  反例｜纸笔外化｜把步骤写下来后，停止点明显后移
  前置｜停止信号识别｜先能识别自己何时想停，才能干预`;

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
      const isSplit = sys.includes('结构拆分器');
      const content = isSplit ? '```json\n' + JSON.stringify(SPLIT) + '\n```' : sys.includes('只回复 OK') ? 'OK' : ANSWER;
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
