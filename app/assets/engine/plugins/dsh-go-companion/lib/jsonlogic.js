// dsh-go-companion · 条件表达式（第九轮 A1）
//
// 预案的「条件」和以后验收的「规则」都用 JSON 写：AI 能写、能存、能比对，代码来算。
// 写法照 JSON Logic（jsonlogic.com）：{"运算符": [参数...]}，{"var": "路径"} 取数据。
//
// 只实现一小撮白名单运算符，**不用 eval，不认识的运算符一律算错**。
// 变量路径也走白名单：validate() 时传进来允许的根名，出现别的变量就拒绝加载——
// 这样 AI 写不出「读他的聊天原文」之类的条件，写了也过不了加载。

/** 允许的运算符。加一个就得在 apply() 里写它的语义，并在测试里补一条。 */
export const OPERATORS = Object.freeze([
  'var', '==', '!=', '<', '<=', '>', '>=', 'and', 'or', '!', 'in', 'if', '+', '-', 'max', 'min',
])

/** 嵌套深度与节点数上限：防一条条件写成几千层把引擎拖死。 */
export const MAX_DEPTH = 12
export const MAX_NODES = 200

const isPlain = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)

/** 一个节点是不是运算（恰好一个键、键在白名单里）。 */
function opOf(node) {
  if (!isPlain(node)) return null
  const keys = Object.keys(node)
  if (keys.length !== 1) return null
  return keys[0]
}

function argsOf(node, op) {
  const raw = node[op]
  return Array.isArray(raw) ? raw : [raw]
}

/** 按「a.b.c」取值；取不到返回 fallback。只走自有属性，不碰原型链。 */
function readVar(data, path, fallback) {
  if (path === '' || path === undefined || path === null) return data
  let cur = data
  for (const part of String(path).split('.')) {
    if (cur === null || cur === undefined || typeof cur !== 'object' || !Object.hasOwn(cur, part)) return fallback
    cur = cur[part]
  }
  return cur === undefined ? fallback : cur
}

const truthy = (v) => (Array.isArray(v) ? v.length > 0 : Boolean(v))

/** 宽松相等：数字与数字串按数值比（JSON Logic 的 ==），其余严格比。 */
function looseEq(a, b) {
  if (typeof a === 'number' && typeof b === 'string' && b.trim() !== '') return a === Number(b)
  if (typeof b === 'number' && typeof a === 'string' && a.trim() !== '') return b === Number(a)
  return a === b
}

function apply(node, data) {
  if (Array.isArray(node)) return node.map((n) => apply(n, data))
  const op = opOf(node)
  if (!op) return node
  if (!OPERATORS.includes(op)) throw new Error('unknown-operator:' + op)
  const args = argsOf(node, op)
  switch (op) {
    case 'var': {
      const path = apply(args[0], data)
      const fallback = args.length > 1 ? apply(args[1], data) : null
      return readVar(data, path, fallback)
    }
    case 'and': {
      let last = true
      for (const a of args) {
        last = apply(a, data)
        if (!truthy(last)) return last
      }
      return last
    }
    case 'or': {
      let last = false
      for (const a of args) {
        last = apply(a, data)
        if (truthy(last)) return last
      }
      return last
    }
    case 'if': {
      // [cond, then, cond2, then2, ..., else]
      let i = 0
      for (; i + 1 < args.length; i += 2) if (truthy(apply(args[i], data))) return apply(args[i + 1], data)
      return i < args.length ? apply(args[i], data) : null
    }
    default:
      break
  }
  const v = args.map((a) => apply(a, data))
  switch (op) {
    case '==': return looseEq(v[0], v[1])
    case '!=': return !looseEq(v[0], v[1])
    case '<': return v.length === 3 ? Number(v[0]) < Number(v[1]) && Number(v[1]) < Number(v[2]) : Number(v[0]) < Number(v[1])
    case '<=': return v.length === 3 ? Number(v[0]) <= Number(v[1]) && Number(v[1]) <= Number(v[2]) : Number(v[0]) <= Number(v[1])
    case '>': return Number(v[0]) > Number(v[1])
    case '>=': return Number(v[0]) >= Number(v[1])
    case '!': return !truthy(v[0])
    case 'in': {
      if (Array.isArray(v[1])) return v[1].some((x) => looseEq(x, v[0]))
      if (typeof v[1] === 'string') return v[1].includes(String(v[0]))
      return false
    }
    case '+': return v.reduce((s, x) => s + Number(x), 0)
    case '-': return v.length === 1 ? -Number(v[0]) : Number(v[0]) - Number(v[1])
    case 'max': return v.length ? Math.max(...v.map(Number)) : null
    case 'min': return v.length ? Math.min(...v.map(Number)) : null
    default: throw new Error('unknown-operator:' + op)
  }
}

/**
 * 算一条表达式。表达式本身有错（不认识的运算符）时抛错——调用方应先 validate()，
 * 这里抛错说明有人绕过了加载校验，宁可让这条预案不触发，也不要猜一个结果。
 */
export function evaluate(rule, data = {}) {
  return apply(rule, data)
}

/** 算成真假。出错算假，并把错带回去让调用方记账。 */
export function test(rule, data = {}) {
  if (rule === undefined || rule === null) return { ok: true, value: true }
  try {
    return { ok: true, value: truthy(apply(rule, data)) }
  } catch (error) {
    return { ok: false, value: false, error: String((error && error.message) || error) }
  }
}

/**
 * 加载时校验。
 * @param {unknown} rule
 * @param {{vars?: string[]}} [opts]  允许的变量根名（`bout.kind` 的根是 `bout`）；不给就不限制
 * @returns {{ok:boolean, errors:{code:string, at:string, message:string}[]}}
 */
export function validate(rule, opts = {}) {
  const errors = []
  const roots = Array.isArray(opts.vars) ? new Set(opts.vars) : null
  let nodes = 0
  const walk = (node, at, depth) => {
    nodes += 1
    if (nodes > MAX_NODES) {
      if (nodes === MAX_NODES + 1) errors.push({ code: 'too-large', at, message: '条件太大（超过 ' + MAX_NODES + ' 个节点）' })
      return
    }
    if (depth > MAX_DEPTH) {
      errors.push({ code: 'too-deep', at, message: '条件嵌套超过 ' + MAX_DEPTH + ' 层' })
      return
    }
    if (Array.isArray(node)) {
      node.forEach((n, i) => walk(n, at + '[' + i + ']', depth + 1))
      return
    }
    if (!isPlain(node)) return
    const keys = Object.keys(node)
    if (keys.length !== 1) {
      errors.push({ code: 'bad-node', at, message: '一个运算只能有一个键，实际有 ' + keys.length + ' 个' })
      return
    }
    const op = keys[0]
    if (!OPERATORS.includes(op)) {
      errors.push({ code: 'unknown-operator', at, message: '不认识的运算符：' + op })
      return
    }
    const args = argsOf(node, op)
    if (op === 'var') {
      const path = args[0]
      if (typeof path !== 'string' || !path) {
        errors.push({ code: 'bad-var', at, message: 'var 只能写一个非空字符串路径' })
        return
      }
      const root = path.split('.')[0]
      if (roots && !roots.has(root)) errors.push({ code: 'var-not-allowed', at, message: '条件里不能读这个变量：' + path })
      if (args.length > 1) walk(args[1], at + '.var[1]', depth + 1)
      return
    }
    args.forEach((a, i) => walk(a, at + '.' + op + '[' + i + ']', depth + 1))
  }
  if (rule !== undefined && rule !== null) walk(rule, '条件', 0)
  return { ok: errors.length === 0, errors }
}
