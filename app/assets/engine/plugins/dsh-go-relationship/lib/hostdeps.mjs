import { existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import os from 'node:os';

const SELF_ROOT = dirname(dirname(fileURLToPath(import.meta.url)));

/**
 * `node_modules/@deepseek-ai` candidates above [start]. A plugin loaded by
 * absolute path has no profile node_modules to walk up to, so the host's own
 * tree has to be named: `dsh` runs from /opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js.
 */
function ancestors(start) {
  const out = [];
  let dir = start;
  for (let i = 0; i < 10; i++) {
    if (!dir) break;
    if (dir.endsWith('node_modules')) out.push(join(dir, '@deepseek-ai'));
    out.push(join(dir, 'node_modules', '@deepseek-ai'));
    const up = dirname(dir);
    if (up === dir) break;
    dir = up;
  }
  return out;
}

function hostRoots() {
  const out = [];
  const push = (p) => {
    if (p && !out.includes(p)) out.push(p);
  };
  const res = process.resourcesPath;
  if (res) {
    push(join(res, 'app.asar', 'dsh', 'node_modules', '@deepseek-ai'));
    push(join(res, 'app', 'dsh', 'node_modules', '@deepseek-ai'));
    push(join(res, 'dsh', 'node_modules', '@deepseek-ai'));
  }
  // The on-device runtime: the engine itself, and the writable app tree. The
  // DSH_GO_RUNTIME_ROOT variant covers direct mode, where the rootfs is not /.
  push('/opt/dsh/node_modules/@deepseek-ai');
  const runtime = process.env.DSH_GO_RUNTIME_ROOT;
  if (runtime) push(join(runtime, 'opt', 'dsh', 'node_modules', '@deepseek-ai'));
  // `dsh` is started as node …/dsh/lib/bin.js; climb from there as well.
  for (const arg of [process.argv[1], process.argv[0]]) {
    if (arg) for (const root of ancestors(dirname(arg))) push(root);
  }
  const home = process.env.DSH_HOME || join(os.homedir(), '.dsh');
  push(join(home, 'shims'));
  for (const name of ['desktop', 'web', 'headless', 'tui']) {
    push(join(home, 'profiles', name, 'node_modules', '@deepseek-ai'));
  }
  push(join(home, 'node_modules', '@deepseek-ai'));
  push(join(SELF_ROOT, 'node_modules', '@deepseek-ai'));
  return out;
}

function entryOf(root, name) {
  try {
    const dir = join(root, name);
    const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'));
    const dot = pkg.exports?.['.'];
    const rel =
      typeof dot === 'string' ? dot : dot?.require || dot?.default || dot?.import || pkg.main;
    if (!rel) return null;
    const file = join(dir, String(rel).replace(/^\.\//, ''));
    return existsSync(file) ? file : null;
  } catch {
    return null;
  }
}

/** Every `dsh-tools` entry the host offers, in resolution order. */
function defineToolEntries() {
  const out = [];
  for (const root of hostRoots()) {
    const file = entryOf(root, 'dsh-tools');
    if (file && !out.includes(file)) out.push(file);
  }
  return out;
}

function defineToolOf(mod) {
  const defineTool = mod?.defineTool ?? mod?.default?.defineTool;
  return typeof defineTool === 'function' ? defineTool : null;
}

/** Load defineTool from the host's own module tree. Returns null (never throws). */
export function loadDefineToolSync() {
  for (const file of defineToolEntries()) {
    try {
      const defineTool = defineToolOf(createRequire(file)(file));
      if (defineTool) return defineTool;
    } catch {
      // ESM-only entry, or an unusable tree: the async loader tries it next.
    }
  }
  return null;
}

/** Same, for hosts whose dsh-tools is ESM-only. Returns null (never throws). */
export async function loadDefineTool() {
  for (const file of defineToolEntries()) {
    try {
      const defineTool = defineToolOf(await import(pathToFileURL(file).href));
      if (defineTool) return defineTool;
    } catch {
      // try the next root
    }
  }
  return null;
}
