// Create the three Notion databases under a parent page.
//
//   NOTION_TOKEN=secret_xxx NOTION_PARENT_PAGE_ID=xxxx npm run notion:init
//
// The parent page must be shared with your integration (page menu → Connections).
// Prints the three database IDs; paste them into the app's settings page.
// The settings page can also do this from the browser.

import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { NOTION_VERSION, entitiesDbBody, interactionsDbBody, casesDbBody } from '../web/js/core/notion-schema.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const envFile = path.join(root, '.env');
if (existsSync(envFile)) {
  for (const line of readFileSync(envFile, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2].replace(/^['"]|['"]$/g, '');
  }
}

const token = process.env.NOTION_TOKEN;
const parent = normalizeId(process.env.NOTION_PARENT_PAGE_ID || process.argv[2] || '');

if (!token || !parent) {
  console.error('需要 NOTION_TOKEN 和 NOTION_PARENT_PAGE_ID（或作为第一个参数传入页面 ID / 链接）');
  process.exit(1);
}

async function createDb(body) {
  const res = await fetch('https://api.notion.com/v1/databases', {
    method: 'POST',
    headers: {
      authorization: `Bearer ${token}`,
      'notion-version': NOTION_VERSION,
      'content-type': 'application/json',
    },
    body: JSON.stringify(body),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(`${res.status} ${data.code}: ${data.message}`);
  return data.id;
}

/** Accept a raw ID, a dashed UUID, or a full Notion page URL. */
function normalizeId(s) {
  const m = String(s).replace(/-/g, '').match(/[0-9a-f]{32}(?!.*[0-9a-f]{32})/i);
  return m ? m[0] : '';
}

try {
  const entities = await createDb(entitiesDbBody(parent));
  const interactions = await createDb(interactionsDbBody(parent, entities));
  const cases = await createDb(casesDbBody(parent, entities));
  console.log('创建完成。把下面三个 ID 填到设置页（或写进 .env）：');
  console.log(`NOTION_ENTITIES_DB=${entities}`);
  console.log(`NOTION_INTERACTIONS_DB=${interactions}`);
  console.log(`NOTION_CASES_DB=${cases}`);
} catch (err) {
  console.error('创建失败：', err.message);
  console.error('常见原因：页面没有分享给你的 Integration，或 Token 填错。');
  process.exit(1);
}
