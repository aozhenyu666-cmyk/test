#!/usr/bin/env node
// Packs plugin/ into dist/sp-ai-assistant-<version>.zip (files at the zip root,
// which is what Super Productivity's "upload plugin" expects).
// No dependencies: a minimal ZIP writer on top of node:zlib.
//
//   node scripts/pack.mjs                     # uses manifest as-is
//   node scripts/pack.mjs --host api.x.com    # also allow this host for the SP proxy channel
import { readFileSync, writeFileSync, mkdirSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync } from 'node:zlib';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const srcDir = join(root, 'plugin');
const outDir = join(root, 'dist');

const manifest = JSON.parse(readFileSync(join(srcDir, 'manifest.json'), 'utf8'));
const args = process.argv.slice(2);
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--host' && args[i + 1]) {
    const h = args[++i].replace(/^https?:\/\//, '').replace(/[:/].*$/, '');
    if (!manifest.allowedHosts.includes(h)) manifest.allowedHosts.push(h);
  }
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

const files = readdirSync(srcDir)
  .sort()
  .map((name) => ({
    name,
    data:
      name === 'manifest.json'
        ? Buffer.from(JSON.stringify(manifest, null, 2) + '\n')
        : readFileSync(join(srcDir, name)),
  }));

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

mkdirSync(outDir, { recursive: true });
const out = join(outDir, `sp-ai-assistant-${manifest.version}.zip`);
writeFileSync(out, Buffer.concat([...locals, centralBuf, end]));
console.log(`packed ${files.length} files -> ${out}`);
console.log(`allowedHosts: ${manifest.allowedHosts.join(', ')}`);
