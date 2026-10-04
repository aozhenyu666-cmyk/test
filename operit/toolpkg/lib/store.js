'use strict';
// 状态持久化：手机上用 Java 文件锁 + AtomicFile（与 cognitive_core 相同做法），电脑测试用内存。
const L = require('./logic.js');

const PKG_ID = 'com.community.zhukong';

function memoryStore(initial) {
  let doc = initial ? L.clone(initial) : L.freshState();
  return {
    read: () => L.validate(L.clone(doc)),
    transact(fn) { const d = L.validate(L.clone(doc)); const r = fn(d); doc = d; return r; },
    dir: 'memory'
  };
}

function phoneStore() {
  if (typeof Java === 'undefined') throw L.err('NO_JAVA', '缺少 Java Bridge，请核实 Operit 版本');
  const File = Java.type('java.io.File'), RAF = Java.type('java.io.RandomAccessFile');
  const AtomicFile = Java.type('android.util.AtomicFile');
  const StringClass = Java.type('java.lang.String');
  const dir = ToolPkg.getConfigDir(PKG_ID);
  File.newInstance(dir).mkdirs();
  const path = dir + '/state.json';
  const atomic = AtomicFile.newInstance(File.newInstance(path));
  function readUnlocked() {
    if (!File.newInstance(path).exists() && !File.newInstance(path + '.bak').exists()) return L.freshState();
    return L.validate(JSON.parse(String(StringClass.newInstance(atomic.readFully(), 'UTF-8').toString())));
  }
  function withLock(fn) {
    const raf = RAF.newInstance(dir + '/state.lock', 'rw'), channel = raf.getChannel();
    let lock;
    try {
      try { lock = channel.tryLock(); } catch (e) { throw L.err('BUSY', '状态正被另一个调用使用，请稍后再试'); }
      if (!lock) throw L.err('BUSY', '状态正被另一个调用使用，请稍后再试');
      return fn();
    } finally { if (lock) lock.release(); channel.close(); raf.close(); }
  }
  return {
    read: () => withLock(readUnlocked),
    transact: fn => withLock(() => {
      const doc = readUnlocked(), result = fn(doc);
      let stream = atomic.startWrite();
      try {
        stream.write(StringClass.newInstance(JSON.stringify(doc)).getBytes('UTF-8'));
        atomic.finishWrite(stream); stream = null;
      } finally { if (stream) atomic.failWrite(stream); }
      return result;
    }),
    dir
  };
}

module.exports = {PKG_ID, memoryStore, phoneStore};
