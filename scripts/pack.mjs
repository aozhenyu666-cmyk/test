#!/usr/bin/env node
// Builds plugin/ into dist/build/ (minified) and packs it as
// dist/sp-ai-assistant-<version>.zip (files at the zip root, which is what
// Super Productivity's "upload plugin" expects).
//
// Why minify: SP rejects an index.html larger than 100 KB (it reuses the
// manifest size limit, MAX_PLUGIN_MANIFEST_SIZE). The readable source stays in plugin/.
//
//   npm install && node scripts/pack.mjs
//   node scripts/pack.mjs --host api.x.com    # also allow this host for the SP proxy channel
import { readFileSync, writeFileSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync } from 'node:zlib';

const MAX_INDEX_HTML = 100 * 1024;

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const srcDir = join(root, 'plugin');
const outDir = join(root, 'dist');
const buildDir = join(outDir, 'build');

let minifyJs;
try {
  ({ minify: minifyJs } = await import('terser'));
} catch {
  console.error('terser is missing: run `npm install` first.');
  process.exit(1);
}

const manifest = JSON.parse(readFileSync(join(srcDir, 'manifest.json'), 'utf8'));
const args = process.argv.slice(2);
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--host' && args[i + 1]) {
    const h = args[++i].replace(/^https?:\/\//, '').replace(/[:/].*$/, '');
    if (!manifest.allowedHosts.includes(h)) manifest.allowedHosts.push(h);
  }
}

const TERSER = { compress: { passes: 2 }, mangle: true, format: { comments: false, ascii_only: false } };

function minifyCss(css) {
  return css
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\s+/g, ' ')
    .replace(/\s*([{}:;,>])\s*/g, '$1')
    .replace(/;}/g, '}')
    .trim();
}

async function buildIndexHtml(html) {
  const scripts = [];
  let out = html.replace(/<script>([\s\S]*?)<\/script>/g, (_, code) => {
    scripts.push(code);
    return `<script>@@SCRIPT${scripts.length - 1}@@</script>`;
  });
  out = out.replace(/<style>([\s\S]*?)<\/style>/g, (_, css) => `<style>${minifyCss(css)}</style>`);
  out = out.replace(/<!--[\s\S]*?-->/g, '').replace(/>\s+</g, '><').replace(/\n\s*/g, '\n');
  for (let i = 0; i < scripts.length; i++) {
    const { code } = await minifyJs(scripts[i], TERSER);
    out = out.replace(`@@SCRIPT${i}@@`, () => code);
  }
  return out;
}

rmSync(buildDir, { recursive: true, force: true });
mkdirSync(buildDir, { recursive: true });
for (const name of readdirSync(srcDir).sort()) {
  const src = join(srcDir, name);
  let data;
  if (name === 'manifest.json') data = JSON.stringify(manifest, null, 2) + '\n';
  else if (name === 'index.html') data = await buildIndexHtml(readFileSync(src, 'utf8'));
  else if (name === 'plugin.js') data = (await minifyJs(readFileSync(src, 'utf8'), { ...TERSER, parse: { bare_returns: true } })).code;
  else data = readFileSync(src);
  writeFileSync(join(buildDir, name), data);
}

const indexSize = readFileSync(join(buildDir, 'index.html')).length;
console.log(`index.html: ${(readFileSync(join(srcDir, 'index.html')).length / 1024).toFixed(1)} KB source -> ${(indexSize / 1024).toFixed(1)} KB built (SP limit ${MAX_INDEX_HTML / 1024} KB)`);
if (indexSize > MAX_INDEX_HTML) {
  console.error('index.html is still over the SP limit; SP would refuse to install the plugin.');
  process.exit(1);
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
const crc32 = (buf) => {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};

const files = readdirSync(buildDir)
  .sort()
  .map((name) => ({ name, data: readFileSync(join(buildDir, name)) }));

const locals = [];
const centrals = [];
let offset = 0;
for (const f of files) {
  const nameBuf = Buffer.from(f.name, 'utf8');
  const comp = deflateRawSync(f.data);
  const crc = crc32(f.data);
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt16LE(0x0800, 6); // UTF-8 names
  local.writeUInt16LE(8, 8); // deflate
  local.writeUInt32LE(0, 10); // time/date
  local.writeUInt32LE(crc, 14);
  local.writeUInt32LE(comp.length, 18);
  local.writeUInt32LE(f.data.length, 22);
  local.writeUInt16LE(nameBuf.length, 26);
  local.writeUInt16LE(0, 28);
  locals.push(local, nameBuf, comp);

  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE(20, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt16LE(0x0800, 8);
  central.writeUInt16LE(8, 10);
  central.writeUInt32LE(0, 12);
  central.writeUInt32LE(crc, 16);
  central.writeUInt32LE(comp.length, 20);
  central.writeUInt32LE(f.data.length, 24);
  central.writeUInt16LE(nameBuf.length, 28);
  central.writeUInt32LE(offset, 42);
  centrals.push(central, nameBuf);
  offset += local.length + nameBuf.length + comp.length;
}
const centralBuf = Buffer.concat(centrals);
const end = Buffer.alloc(22);
end.writeUInt32LE(0x06054b50, 0);
end.writeUInt16LE(files.length, 8);
end.writeUInt16LE(files.length, 10);
end.writeUInt32LE(centralBuf.length, 12);
end.writeUInt32LE(offset, 16);

const out = join(outDir, `sp-ai-assistant-${manifest.version}.zip`);
writeFileSync(out, Buffer.concat([...locals, centralBuf, end]));
console.log(`packed ${files.length} files -> ${out}`);
console.log(`allowedHosts: ${manifest.allowedHosts.join(', ')}`);
