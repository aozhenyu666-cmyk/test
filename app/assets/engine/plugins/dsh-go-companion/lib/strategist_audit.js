// dsh-go-companion · 谋士隐式审讯与反顺从（M1.4 / SYS-002）
//
// 鲸澜在非闲聊判断型输出前先生成私有 <strategist_audit> CoT：
//   1. user_premise（用户叙事里的隐含前提）
//   2. evidence_tier（证据层级：可核验事实 / 用户自述 / AI推断 / 待核验）
//   3. falsify_path（反证路径：如果错了最可能错在哪）
//   4. cost_if_wrong（误判代价）
//
// 展示前代码强行剥离整段 <strategist_audit>...</strategist_audit>（及未闭合残段），绝不透出到 UI/语音；
// 解析出的四要素写进审计日志供复盘。同时用反顺从正则过滤器拦截无条件迎合句式。

export const AUDIT_FIELDS = ['user_premise', 'evidence_tier', 'falsify_path', 'cost_if_wrong']

const AUDIT_FULL_RE = /<strategist_audit\b[^>]*>([\s\S]*?)<\/strategist_audit>/gi
const AUDIT_OPEN_DANGLING_RE = /<strategist_audit\b[^>]*>[\s\S]*$/gi

/** 短且紧凑的谋士隐藏思考指令（进约束或高优先状态块，避免撑爆 2100 字预算）。 */
export const STRATEGIST_AUDIT_DIRECTIVE =
  '回答关键判断或规划前，先在输出开头用 <strategist_audit>{"user_premise":"...","evidence_tier":"...","falsify_path":"...","cost_if_wrong":"..."}</strategist_audit> 完成四项内部核查（展示时会被系统自动剥离）；正文只给结论与反证依据，绝不无条件附和「你说得对/没毛病」。'

function parseAuditBody(rawBody) {
  const body = String(rawBody || '').trim()
  if (!body) return null
  try {
    const start = body.indexOf('{')
    const end = body.lastIndexOf('}')
    if (start >= 0 && end > start) {
      const obj = JSON.parse(body.slice(start, end + 1))
      if (obj && typeof obj === 'object') {
        const out = {}
        for (const k of AUDIT_FIELDS) {
          if (obj[k] !== undefined) out[k] = String(obj[k]).trim().slice(0, 200)
        }
        if (Object.keys(out).length > 0) return out
      }
    }
  } catch {
    // fallback to key:value line extraction below
  }
  const extracted = {}
  for (const k of AUDIT_FIELDS) {
    const m = new RegExp(`["']?${k}["']?\\s*[:：]\\s*([^\\n]+)`, 'i').exec(body)
    if (m && m[1]) extracted[k] = m[1].replace(/^["'\s]+|["',\s]+$/g, '').slice(0, 200)
  }
  return Object.keys(extracted).length > 0 ? extracted : null
}

/**
 * 强力剥离所有 <strategist_audit> 块及残缺标签，并提取第一段可用审计 JSON（写入内部日志）。
 * @returns {{cleanText: string, hadAudit: boolean, audit: object|null}}
 */
export function stripStrategistAudit(raw) {
  const text = String(raw == null ? '' : raw)
  if (!text.includes('<strategist_audit')) {
    return { cleanText: text, hadAudit: false, audit: null }
  }
  let parsed = null
  const matched = text.matchAll(AUDIT_FULL_RE)
  for (const m of matched) {
    if (!parsed) parsed = parseAuditBody(m[1])
  }
  const cleanText = text
    .replace(AUDIT_FULL_RE, '')
    .replace(AUDIT_OPEN_DANGLING_RE, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
  return {
    cleanText,
    hadAudit: true,
    audit: parsed,
  }
}

/**
 * SYS-002 反顺从正则库：拦截「无条件赞同/安抚但没有给出边界/代价/反证」的套话开头或主句。
 */
const SYCOPHANCY_PATTERNS = [
  /(?:^|[。！？\n]\s*)(?:确实)?你说[得的]?(?:很|非常|完全|太)?对/,
  /(?:^|[。！？\n]\s*)(?:我)?完全(?:赞同|同意|支持)你的(?:看法|观点|判断|决定)/,
  /(?:^|[。！？\n]\s*)没(?:什么)?毛病[，。！\s]*(?:直接|就按)/,
  /(?:^|[。！？\n]\s*)(?:你的想法|这个思路|你分析得)(?:非常|太|很有)?(?:棒|厉害|透彻|在理)/,
  /毫无问题[，,！!。]*我们可以直接/,
]

/** 如果句子含明显的条件反证词（但/不过/风险/前提/代价/反证），说明并非盲目顺从。 */
const COUNTER_BALANCE_RE = /(?:但是|不过|前提是|风险在于|漏洞在于|反证|代价|未必|先核验|还缺证据)/

export function detectSycophancy(raw) {
  const { cleanText } = stripStrategistAudit(raw)
  const text = String(cleanText || '').trim()
  if (!text) return { sycophantic: false, pattern: '', reason: '' }
  for (const re of SYCOPHANCY_PATTERNS) {
    const m = re.exec(text)
    if (m) {
      if (COUNTER_BALANCE_RE.test(text) && text.length >= 16) {
        return { sycophantic: false, pattern: '', reason: '' }
      }
      return {
        sycophantic: true,
        pattern: m[0].trim(),
        reason: 'sys002-unconditional-agreement',
      }
    }
  }
  return { sycophantic: false, pattern: '', reason: '' }
}

/** 构造重写纠偏提示（命中反顺从或缺少审计块时使用）。 */
export function antiSycophancyRewriteHint(hit) {
  const phrase = hit?.pattern ? `「${hit.pattern}」` : '无条件顺从用语'
  return `检测到顺从性表达（${phrase}），违反 SYS-002：不要先夸「说得对/完全同意」，必须明确指出对方前提里哪一条未经核验、以及误判代价。请直接重写这一句。`
}
