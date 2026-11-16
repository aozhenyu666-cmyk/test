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
export const V3_AUDIT_FIELDS = ['surface_claim', 'unverified_assumption', 'telemetry_contradiction', 'core_question_or_call']
export const ALL_AUDIT_FIELDS = [...AUDIT_FIELDS, ...V3_AUDIT_FIELDS]

const AUDIT_FULL_RE = /<strategist_audit\b[^>]*>([\s\S]*?)<\/strategist_audit>/gi
const AUDIT_OPEN_DANGLING_RE = /<strategist_audit\b[^>]*>[\s\S]*$/gi

/** Claude 式高密度思考锚点（短而锐利，展示前代码强行剥离 <strategist_audit>）。 */
export const STRATEGIST_AUDIT_DIRECTIVE =
  '回答判断或取舍前，用最小 <strategist_audit>{"surface_claim":"...","unverified_assumption":"...","telemetry_contradiction":"...","core_question_or_call":"..."}</strategist_audit> 做 4 秒心检（自动剥离，不透出）；正文像极简高手搭档那样直接给洞察、概念取舍和独立立场——少列 1-2-3 模板清单，禁止婆婆妈妈说教或无条件附和。'

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
        for (const k of ALL_AUDIT_FIELDS) {
          if (obj[k] !== undefined) out[k] = String(obj[k]).trim().slice(0, 200)
        }
        if (Object.keys(out).length > 0) return out
      }
    }
  } catch {
    // fallback to key:value line extraction below
  }
  const extracted = {}
  for (const k of ALL_AUDIT_FIELDS) {
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
 * SYS-002 反顺从与反空泛吹捧正则库：
 * 1) 拦截谋士无条件顺从/保姆安抚；
 * 2) 拦截小鱼「无具体事实支撑的空泛吹捧/舔狗句式」（需结合具体动作才允许夸）。
 */
const SYCOPHANCY_PATTERNS = [
  /(?:^|[。！？\n]\s*)(?:确实)?你说[得的]?(?:很|非常|完全|太)?对/,
  /(?:^|[。！？\n]\s*)(?:我)?完全(?:赞同|同意|支持)你的(?:看法|观点|判断|决定)/,
  /(?:^|[。！？\n]\s*)没(?:什么)?毛病[，。！\s]*(?:直接|就按)/,
  /(?:^|[。！？\n]\s*)(?:你的想法|这个思路|你分析得)(?:非常|太|很有)?(?:棒|厉害|透彻|在理)/,
  /毫无问题[，,！!。]*我们可以直接/,
  /(?:^|[。！？，,\n]\s*)我理解你的(?:感受|心情|难处|不容易)/,
  /(?:^|[。！？，,\n]\s*)这(?:确实|真的)?很?不容易/,
  /(?:^|[。！？，,\n]\s*)(?:不用|别)太?(?:苛责|自责|怪)自己/,
  /(?:^|[。！？，,\n]\s*)(?:抱抱你|给你一个拥抱|你已经(?:做得很棒|很努力)了)/,
  /(?:^|[。！？，,\n]\s*)(?:没关系的|不要紧的)[，,！!\s]*(?:慢慢来|休息|别急)/,
]

const COMPANION_FLATTERY_PATTERNS = [
  /(?:^|[。！？，,\n]\s*)(?:哥哥|你)?(?:真的)?太(?:棒|厉害|强|优秀|自律)了[！!~]*$/,
  /(?:^|[。！？，,\n]\s*)不愧是你[！!~]*$/,
  /(?:^|[。！？，,\n]\s*)(?:加油|你可以的|相信你一定行|你最棒)[！!~]*$/,
]

/** 如果句子含明显的条件反证词（但/不过/风险/前提/代价/反证），说明并非盲目顺从。 */
const COUNTER_BALANCE_RE = /(?:但是|不过|前提是|风险在于|漏洞在于|反证|代价|未必|先核验|还缺证据)/

export function detectSycophancy(raw, { role = 'strategist' } = {}) {
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
  if (role === 'companion') {
    for (const re of COMPANION_FLATTERY_PATTERNS) {
      const m = re.exec(text)
      if (m && text.length < 36) {
        return {
          sycophantic: true,
          pattern: m[0].trim(),
          reason: 'sys002-empty-flattery',
        }
      }
    }
  }
  return { sycophantic: false, pattern: '', reason: '' }
}

const PURE_FOCUS_CLAIM_RE = /(?:一直(?:在)?(?:学习|专注|干正事|工作)|(?:完全|一直|根本)?没(?:看|碰|刷|玩)(?:手机|娱乐|视频|B站|抖音|游戏)|没(?:有)?摸鱼|今天很自律|没有分心)/i
const NAMED_FUN_APP_RE = /(B站|bilibili|抖音|douyin|小红书|微博|知乎|游戏|taptap|王者|原神|今日娱乐)[^\d\n]{0,10}(\d+)\s*(?:m|分|分钟)/gi

/**
 * 对账 Block B 实时遥测，识破「用户自述一直在学习/没摸鱼」与客观 App 时长之间的矛盾。
 */
export function detectTelemetryContradiction(userClaim, telemetry = {}) {
  const claim = String(userClaim || '').trim()
  if (!claim || !PURE_FOCUS_CLAIM_RE.test(claim)) {
    return { contradicted: false, claimSummary: '', offendingApp: '', actualMinutes: 0, evidenceLine: '' }
  }
  const blockBText = typeof telemetry === 'string'
    ? telemetry
    : Array.isArray(telemetry?.lines)
      ? telemetry.lines.join('\n')
      : String(telemetry?.blockB || telemetry?.text || '')

  let actualMinutes = Number(telemetry?.funMinutesToday ?? telemetry?.entertainmentMinutes ?? telemetry?.phone?.funMinutesToday) || 0
  let offendingApp = String(telemetry?.offendingApp || (telemetry?.phone?.fun ? telemetry?.phone?.label || telemetry?.phone?.app : '') || '')

  const appDurations = Array.isArray(telemetry?.appDurations) ? telemetry.appDurations : []
  for (const item of appDurations) {
    const mins = Number(item?.minutes ?? item?.durationMin) || 0
    if ((item?.fun || item?.category === 'entertainment') && mins > actualMinutes) {
      actualMinutes = mins
      offendingApp = String(item.label || item.app || offendingApp || '娱乐应用')
    }
  }

  if (blockBText) {
    for (const m of blockBText.matchAll(NAMED_FUN_APP_RE)) {
      const mins = Number(m[2]) || 0
      if (mins > actualMinutes) {
        actualMinutes = mins
        offendingApp = m[1] === '今日娱乐' ? (offendingApp || '娱乐应用') : m[1]
      }
    }
  }

  if (actualMinutes < 10) {
    return { contradicted: false, claimSummary: '', offendingApp: '', actualMinutes: 0, evidenceLine: '' }
  }
  const appLabel = offendingApp || '娱乐应用'
  return {
    contradicted: true,
    claimSummary: claim.slice(0, 60),
    offendingApp: appLabel,
    actualMinutes: Math.round(actualMinutes),
    evidenceLine: `Block B 遥测矛盾：自述「${claim.slice(0, 30)}」，但客观记录显示 ${appLabel} 已使用 ${Math.round(actualMinutes)} 分钟`,
  }
}

export function telemetryContradictionHint(hit) {
  if (!hit?.contradicted) return ''
  return `遥测对照已识破矛盾（${hit.evidenceLine}）。严禁顺从安抚，必须直接引用 ${hit.offendingApp} ${hit.actualMinutes} 分钟的客观事实追问关键偏航点。`
}

/** 构造重写纠偏提示（命中反顺从或缺少审计块时使用）。 */
export function antiSycophancyRewriteHint(hit) {
  const phrase = hit?.pattern ? `「${hit.pattern}」` : '无条件顺从用语'
  return `检测到顺从性表达（${phrase}），违反 SYS-002：不要先夸「说得对/完全同意」，或空洞安抚，必须明确指出对方前提里哪一条未经核验、以及误判代价。请直接重写这一句。`
}
