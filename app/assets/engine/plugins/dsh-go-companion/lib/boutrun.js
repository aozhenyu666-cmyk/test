// dsh-go-companion · 局官（第八轮 J9 文字闭环 + J4 沟通上限）
//
// 局官是局里唯一的声音：冷、短、只说协议里的话。模型（以后接）只改写措辞，
// 改写完还得过下面这几条代码校验；判定、计时、升级一概不经过模型。
//
// 一拍怎么走：
//   局官问（协议里那一步的 问）→ 他答 → 代码度量与上限 → 判过关 → 落账 → 下一拍。
// 落账三处：beats（这一拍）、verdicts（代码层的判定）、账本（bouts.js 每次写库自动追加）。
//
// 两条上限（J4 沟通硬上限，代码执行，不看模型脸色）：
//   1. 他单条超字数：局官不回应内容，只让他拆开重说。这一拍不算应答。
//   2. 话题数 ≥ 3，或 > 80 字且没有结论句：判「混乱」。局官不回答任何内容，
//      只把代码从名词簇里抽出来的 A、B、C 摆给他，让他选一件。
//      连续两次混乱 → 本局进写模式，只收手写照片（写比说慢，慢就是降速）。

import { addBeat, addVerdict, closeBout, getBout, openBout } from './bouts.js'
import {
  concreteNouns, countChars, countConcreteNouns, countQuestions, countTopics, fillerRatio, hasConclusion,
} from './capabilities.js'
import { createRunner } from './protocol.js'

/** 局官单条上限（字）。 */
export const OFFICIAL_LIMIT = 40
/** 他单条上限（字）。外出局要求更短：骑车时只能答「地点 + 下一步动作」。 */
export const HIS_LIMIT = 150
export const OUTING_LIMIT = 50
/** 这一步他说的东西超过 3 件，就算想到哪说到哪。 */
export const CHAOS_TOPICS = 3
/** 超过这么多字还没有结论句，也算混乱。 */
export const CHAOS_LONG = 80
/** 同一拍重打这么多次还不过 → 升级（敷衍的成本必须高于认真）。 */
export const RETRY_LIMIT = 3

export function hisLimitOf(protocol) {
  return protocol && protocol.id === 'outing' ? OUTING_LIMIT : HIS_LIMIT
}

/**
 * 局官这一句合不合规：≤ 40 字、最多 1 个问句、（给了对象时）要提到那个对象。
 * 协议里手写的 问 和以后模型改写出来的句子都过这一条。
 * 问句数用「最多 1」而不是「恰好 1」：协议里本来就有纯指令的拍
 * （「停。不看，列出讲了哪几点。」），硬要它带问号只会逼着人写废话。
 * 「包含局内对象」单独不做词性判断——代码认不出哪个词是对象，就要求调用方把对象传进来，
 * 传了才查。猜一个「像名词」的词当对象，等于用假标准给自己发合格证。
 * @param {string} text
 * @param {{object?:string}} [options]
 * @returns {{ok:boolean, reasons:string[]}}
 */
export function checkPhrase(text, options = {}) {
  const reasons = []
  const chars = countChars(text)
  const object = String(options.object || '').trim()
  if (!chars) reasons.push('空')
  if (chars > OFFICIAL_LIMIT) reasons.push('超过 ' + OFFICIAL_LIMIT + ' 字')
  if (countQuestions(text) > 1) reasons.push('不止一个问句')
  if (object && !String(text).includes(object)) reasons.push('没提到局内对象：' + object)
  return { ok: reasons.length === 0, reasons }
}

/**
 * 他这一条到底算什么：只是超字数，还是混乱，还是正常。
 * @returns {{overflow:boolean, chaos:boolean, metrics:object, nouns:string[], say:string}}
 */
export function judgeReply(text, options = {}) {
  const limit = Number(options.limit) || HIS_LIMIT
  const raw = String(text || '')
  const chars = countChars(raw)
  const topics = countTopics(raw)
  const conclusion = hasConclusion(raw)
  const metrics = {
    字数: chars,
    话题数: topics,
    具体名词数: countConcreteNouns(raw),
    有无结论句: conclusion,
    问句数: countQuestions(raw),
    填充词比例: Number(fillerRatio(raw).toFixed(3)),
  }
  if (chars > limit) {
    return { overflow: true, chaos: false, metrics, nouns: [], say: '超了。分成两条，先说第一条。' }
  }
  if (topics >= CHAOS_TOPICS || (chars > CHAOS_LONG && !conclusion)) {
    // 这一步的过关条件本来就要求分行交几条（学习局的「猜 3 条」「列出讲了哪几点」）：
    // 那几条天然就是多个话题，不能拿「话题数 ≥ 3」去判他混乱，否则这一拍永远过不去。
    if (options.allowList === true && Object.hasOwn(options.pass || {}, '行数')) {
      return { overflow: false, chaos: false, metrics, nouns: [], say: '' }
    }
    const nouns = concreteNouns(raw).slice(0, 3)
    // 名词抽不出来时不能发一句「你说了 N 件：。选一件。」，改成只点数量。
    const say = nouns.length
      ? '你说了 ' + topics + ' 件：' + nouns.join('、') + '。选一件。'
      : '你说了 ' + topics + ' 件。选一件。'
    return { overflow: false, chaos: true, metrics, nouns, say }
  }
  return { overflow: false, chaos: false, metrics, nouns: [], say: '' }
}

/**
 * 开一局并交给局官驱动。
 * @param {{protocol:object, db:object, home?:string, now?:function|number, openedBy?:string}} options
 */
export function createOfficial(options = {}) {
  const protocol = options.protocol
  const db = options.db || null
  const home = options.home
  const now = () => (typeof options.now === 'function' ? Number(options.now()) : Number(options.now || Date.now()))
  const budgetMs = Math.max(0, Number(protocol?.budget?.minutes || 0)) * 60_000
  const startedAt = now()
  // 预算还剩几成。0 预算（没写 预算 的协议）当作「永远剩得多」，让周期步照常走。
  const remaining = () => (budgetMs ? Math.max(0, 1 - (now() - startedAt) / budgetMs) : 1)
  const runner = createRunner(protocol, { now, remaining })

  let boutId = ''
  let chaosStreak = 0
  let writeMode = false
  let closed = false
  /** 周期步下一句该在什么时候问；0 表示不等，随时可以问。 */
  let nextAskAt = 0

  function sayNow() {
    const step = runner.current()
    return step ? step.ask : ''
  }

  function finish(reason) {
    closed = true
    if (boutId && db) closeBout(db, boutId, { reason, now: now(), home })
    // 结局由这里定，不转发 runner.state：升级是本层判的，runner 还停在 running 上，
    // 转发出去就会让调用方以为这一局还能继续答。
    return { state: reason === 'escalated' ? 'escalated' : 'closed', reason, say: '收局。' }
  }

  return {
    get boutId() {
      return boutId
    },
    get writeMode() {
      return writeMode
    },
    get chaosStreak() {
      return chaosStreak
    },

    /** 开局。已经有一局在跑（bouts 的唯一索引）就返回 busy，不硬开。 */
    start() {
      const opened = openBout(db, { protocolId: protocol.id, openedBy: String(options.openedBy || 'engine'), home, now: now() })
      if (!opened.ok) return { ok: false, error: opened.error }
      boutId = opened.id
      return { ok: true, boutId, say: sayNow() }
    },

    /**
     * J9：这一拍要念出来了。念的这段不算他的应答时间——
     * 不 hold 的话，局官一句 40 字的话念到一半，限时已经走掉一半，等于罚他听。
     */
    hold(at) {
      if (closed) return { state: 'closed' }
      return { state: runner.state, holdUntil: runner.speakHold(at === undefined ? now() : at) }
    },

    /**
     * 念完了（或退回文字、字已经到了他眼前）：应答时限从现在开始算。
     * 返回这一刻，调用方拿它当这一拍的 askedAt——beats 里的「应答延迟」也就从念完算起。
     */
    spoken(at) {
      if (closed) return { state: 'closed' }
      const when = runner.spoken(at === undefined ? now() : at)
      return { state: runner.state, at: when, stepId: (runner.current() || {}).id || '' }
    },

    /**
     * 他答了（文字）。返回局官接下来说什么。
     * accepted=false 表示这一条没被当成应答（超字数 / 混乱 / 写模式下发的文字），这一拍没往前走。
     */
    answer(text, input = {}) {
      if (closed) return { accepted: false, state: 'closed', say: '' }
      const raw = String(text || '')
      const form = String(input.form || 'text')
      // 写模式只收手写照片：文字和语音在这一局里不再算应答。
      if (writeMode && form !== 'photo') {
        return { accepted: false, state: runner.state, writeMode: true, say: '写模式：只收手写照片。' }
      }
      const limit = hisLimitOf(protocol)
      // 「这一步本来就要分几条交」：混乱判定让路，数据说话，不靠局型名字硬编码。
      const current = protocol.steps[runner.index] || {}
      const judged = judgeReply(raw, { limit, allowList: true, pass: current.pass || {} })
      if (judged.overflow || judged.chaos) {
        if (judged.chaos) {
          chaosStreak += 1
          if (chaosStreak >= 2) writeMode = true
        }
        return {
          accepted: false,
          chaos: judged.chaos,
          overflow: judged.overflow,
          metrics: judged.metrics,
          writeMode,
          say: judged.say,
        }
      }
      chaosStreak = 0
      const askedAt = runner.askedAt()
      // 这一拍的身份得在他答之前拿：answer() 会立刻前进，index 就变成下一拍了。
      const stepId = (protocol.steps[runner.index] || protocol.steps[0] || {}).id || ''
      const result = runner.answer(raw)
      const passed = runner.lastPass() === true
      // 周期步过关后「原地重问」，但那要等 每 N 分钟；这段时间局官不说话，
      // 由 due() 到点把同一句问出来。立刻再问一遍就成了复读机。
      nextAskAt = result.reason === 'repeat' ? Number(result.nextAskAt) || 0 : 0
      if (boutId && db) {
        const beat = addBeat(db, {
          boutId, stepId, askedAt, channel: String(input.channel || options.channel || 'text'),
          answer: raw, metrics: judged.metrics, verdict: passed ? 'pass' : result.reason, retries: runner.retries,
          now: now(), home,
        })
        addVerdict(db, {
          boutId, beatId: beat.ok ? beat.id : '', layer: 'code',
          result: result.state === 'closed' || result.state === 'escalated' ? result.state : (passed ? 'pass' : 'fail'),
          score: passed ? 100 : 0, quote: raw.slice(0, 200), now: now(), home,
        })
      }
      if (result.state === 'closed') return { accepted: true, ...finish('done') }
      if (result.state === 'escalated') return { accepted: true, ...finish('escalated') }
      // 同一拍重打到第 3 次还不过：按「敷衍走升级」办，不留一个能把他永远卡在这一拍的环。
      // 协议允许「不过 → 重打」，但不该允许一句口水话把限时也一起重置。
      if (runner.retries >= RETRY_LIMIT) {
        return { accepted: true, escalated: true, retries: runner.retries, ...finish('escalated') }
      }
      // 周期步过关后这一句先不说：等「每 N 分钟」到点，由 due() 问出来。
      return {
        accepted: true, state: result.state, index: runner.index,
        repeat: result.reason === 'repeat', nextAskAt,
        // 这一拍过没过。升级阶梯要它来分「答错只重打」和「敷衍走升级」——不给的话
        // 调用方只能按字数猜，那正是 reply.js 头注释要修掉的那个洞。
        passed,
        say: nextAskAt ? '' : sayNow(),
      }
    },

    /** 到点没答：走这一拍的超时出口。 */
    tick(at) {
      if (closed) return { state: 'closed', say: '' }
      const result = runner.tick(at === undefined ? now() : at)
      if (result.state === 'closed') return finish('timeout')
      if (result.state === 'escalated') return finish('escalated')
      return { ...result, say: sayNow() }
    },

    /** 他自己喊停。 */
    close(reason = 'abandoned') {
      if (closed) return { state: 'closed', reason }
      return finish(reason)
    },

    /**
     * 到点了没有。周期步在等「每 N 分钟」的那一段，由这里把同一句问出来。
     * 到点才问，早一秒都不问——这就是「每 10 分钟问做到哪了」里的「每」。
     */
    due(at) {
      if (closed || !nextAskAt) return { due: false, say: '' }
      const when = at === undefined ? now() : Number(at)
      if (when < nextAskAt) return { due: false, nextAskAt, say: '' }
      nextAskAt = 0
      runner.reask(when)
      return { due: true, askAt: when, say: sayNow() }
    },

    /** 面板要的东西：现在这一拍、剩多少、有没有在写模式。 */
    view() {
      const step = runner.current()
      return {
        boutId,
        state: runner.state,
        closed,
        index: runner.index,
        stepId: step ? step.id : '',
        ask: step ? step.ask : '',
        limitSec: step ? step.limitSec : 0,
        askedAt: runner.askedAt(),
        remaining: Number(remaining().toFixed(3)),
        chaosStreak,
        writeMode,
        nextAskAt,
      }
    },
  }
}
