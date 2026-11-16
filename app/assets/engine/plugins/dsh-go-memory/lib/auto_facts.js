// dsh-go-memory · 零额外模型成本的实时对话事实自动提取器
//
// 解决「不靠对话模型记起来才调 remember()，也不每轮双倍调 LLM 烧钱」的痛点：
// 在 agent/inbox/inserted 收到每条用户真实原话后，用确定性规则抽取高价值事实并写入 global 记忆。

const RULES = [
  {
    kind: 'promise',
    importance: 2,
    re: /(?:^|[，,。\s])(我?(?:今天|今晚|明天|后天|这周|周末|待会|一会|\d{1,2}[:：]\d{2}|\d{1,2}点)[^，。！？\n]{0,12}(?:之前|前|以前)?(?:要把?|得|打算|准备)(?:做|完成|写|交|复习|投|跑|改|学|练|把)[^。！？\n]{2,45})/,
    key: '近期计划承诺',
  },
  {
    kind: 'preference',
    importance: 1,
    re: /(?:^|[，,。\s])([^，,。\n]{2,20}(?:只是用来|用来看|用来学|是工作|用来查资料|不要当娱乐|算正事|别锁|要锁掉)[^。！？\n]{0,25})/,
    key: 'App规则偏好',
  },
  {
    kind: 'fact',
    importance: 1,
    re: /(?:^|[，,。\s])(我(?:正在准备|最近在做|这几天在搞|核心目标是|卡在)[^。！？\n]{3,50})/,
    key: '当前核心状态',
  },
]

const SKIP_RE = /^(?:好的?|嗯+哦?|行|知道了?|收到|在呢|哈哈+|哦+|随你|随便|没有|不是)$/

/**
 * 从用户单条消息中抽取 0-2 条值得跨会话记住的事实。
 * @returns {{text:string, kind:string, importance:number, key:string, tags:string[]}[]}
 */
export function extractAutoFacts(userText) {
  const text = String(userText || '').replace(/\s+/g, ' ').trim()
  if (!text || text.length < 6 || text.length > 500 || SKIP_RE.test(text)) return []
  const out = []
  const seen = new Set()
  for (const r of RULES) {
    const m = r.re.exec(text)
    if (!m) continue
    const captured = String(m[1] || '').trim().slice(0, 120)
    if (captured.length < 6 || seen.has(captured)) continue
    seen.add(captured)
    out.push({
      text: '他亲口提到：' + captured,
      kind: r.kind,
      importance: r.importance,
      key: r.key + '.' + captured.slice(0, 12),
      tags: ['自动记录', r.kind],
    })
    if (out.length >= 2) break
  }
  return out
}
