// 单元测试用的内存仓库：接口和 www/js/vault.js 里的仓库一致
import { Store, blankNode } from '../../www/js/model.js';

export class MemVault {
  constructor(files = {}) { this.files = new Map(Object.entries(files)); this.kind = 'mem'; }
  async ready() { return true; }
  async list() { return [...this.files.keys()]; }
  async read(p) { return this.files.get(p) ?? null; }
  async write(p, t) { this.files.set(p, t); }
  async remove(p) { this.files.delete(p); }
}

// 建一个带若干节点的 Store：nodes 是 blankNode 的覆盖字段数组
export async function makeStore(nodes = [], system = '测试') {
  const store = new Store(new MemVault());
  for (const o of nodes) store.nodes.set(o.id, blankNode({ system, ...o }));
  for (const o of nodes) await store.saveNode(store.nodes.get(o.id));
  return store;
}
