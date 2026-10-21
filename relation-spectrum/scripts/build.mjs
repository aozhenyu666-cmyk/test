// Build helpers.
//   node scripts/build.mjs artifact   → dist/artifact/page.html (page for a claude.ai artifact)
//   node scripts/build.mjs gpt-kit    → integrations/chatgpt/* (custom GPT instructions + Action schema)
//   node scripts/build.mjs package    → dist/*.zip (web app for static hosting, full project)

import { mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { gptInstructions, notionOpenApi, GPT_SETUP_STEPS } from '../web/js/core/gpt-kit.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const { version } = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8'));
const cmd = process.argv[2];

if (cmd === 'artifact') {
  // The artifact host wraps the page in its own <html>/<head>/<body>, so the page holds
  // only its content; css/ and js/ are published next to it from web/.
  const out = path.join(root, 'dist', 'artifact');
  mkdirSync(out, { recursive: true });
  writeFileSync(path.join(out, 'page.html'), `<title>关系光谱</title>
<link rel="stylesheet" href="css/app.css">
<main id="view" class="view" aria-live="polite"></main>
<nav id="nav" class="nav" aria-label="主导航"></nav>
<div id="toasts" class="toasts" aria-live="polite"></div>
<script type="module" src="js/app.js"></script>
`);
  console.log('dist/artifact/page.html');
} else if (cmd === 'gpt-kit') {
  const out = path.join(root, 'integrations', 'chatgpt');
  mkdirSync(out, { recursive: true });
  writeFileSync(path.join(out, 'instructions.txt'), gptInstructions({
    entitiesDb: '<Entities_DB 的 ID>',
    interactionsDb: '<Interactions_DB 的 ID>',
    casesDb: '<Sandbox_Cases_DB 的 ID>',
  }) + '\n');
  writeFileSync(path.join(out, 'notion-action.openapi.json'), JSON.stringify(notionOpenApi(), null, 2) + '\n');
  writeFileSync(path.join(out, 'README.md'), `# 和 ChatGPT 并肩使用

让 ChatGPT 里的一个自定义 GPT 读写关系光谱的 Notion 数据库。App 和 GPT 用同一份数据：在 ChatGPT 里记下的事，App 同步后能看到；App 里的记录，GPT 也能查到。

最省事的做法是在 App 的「设置 → 和 ChatGPT 并肩使用」里点“复制 GPT 指令”，它会自动带上你的三张表 ID。这个目录里是同样内容的文件版（ID 是占位符，需要手动替换）。

## 步骤

${GPT_SETUP_STEPS.map((t, i) => `${i + 1}. ${t}`).join('\n')}

## 文件

- \`instructions.txt\`：粘贴到 GPT 的 Instructions，把三个 \`<…的 ID>\` 换成你的数据库 ID
- \`notion-action.openapi.json\`：粘贴到 GPT 的 Action Schema

## 常见问题

- **GPT 说找不到数据库**：Notion 里的那个父页面没有“连接”到你的 Integration，或者 ID 填错了。
- **写入时报字段错误**：数据库是用 App 的“一键创建三张表”建的吗？字段名必须和 App 建的一致。
- **App 里看不到 GPT 写的内容**：在 App 的设置页点“立即同步”。

由 \`npm run gpt:kit\` 生成，不要手动修改。
`);
  console.log('integrations/chatgpt/');
} else if (cmd === 'package') {
  const dist = path.join(root, 'dist');
  mkdirSync(dist, { recursive: true });
  const webZip = path.join(dist, `relation-spectrum-web-${version}.zip`);
  const fullZip = path.join(dist, `relation-spectrum-${version}.zip`);
  for (const f of [webZip, fullZip]) if (existsSync(f)) rmSync(f);
  // Static web app: unzip and upload the folder to any static host (Netlify, Cloudflare Pages…).
  execFileSync('zip', ['-qr', webZip, '.', '-x', '.DS_Store'], { cwd: path.join(root, 'web') });
  // Full project for a computer: npm install && npm start.
  execFileSync('zip', ['-qr', fullZip, 'relation-spectrum',
    '-x', 'relation-spectrum/node_modules/*', 'relation-spectrum/dist/*', 'relation-spectrum/.env', '*/.DS_Store'],
  { cwd: path.dirname(root) });
  console.log(path.relative(root, webZip));
  console.log(path.relative(root, fullZip));
} else {
  console.error('用法：node scripts/build.mjs artifact | gpt-kit | package');
  process.exit(1);
}
