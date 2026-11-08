// dsh-go-companion · 回应分类（本地规则优先，零模型）
//
// 只回答“这一次回应值不值得继续深入”，不做心理判断：
//   substantive 有信息/有依据/在推进
//   brief       很短、没有信息（“嗯/随便/还行”）
//   avoid       明显把话题带走
//   refuse      明确拒绝继续
// 规则先跑；模型只作为以后的兜底。分类结果写进账本，供有限跟进使用。
//
// 这是 P0 的兜底规则：判球正主是大肥鱼自己（见 P1），这里只负责它连不上时不至于乱判。

// “停止/够了”不算拒绝：它们单独出现多半在说别的事（“今天停止刷视频了”
// “做够了就去背单词”），判成拒绝会把话题直接关掉。真要停会说“不想聊/别说了”。
const REFUSE = /(不想聊|别问|别管|闭嘴|烦不烦|不聊了|走开|别说了)/

// “别管这道题了，我先做第三题”是在说别插手某件事，不是拒绝这次聊天。
// 带自己的去向就不是拒绝；真拒绝不会顺带汇报下一步。
const SELF_ACTION = /(我先|我去|我这就|我马上|我要先|先做|先去)/

// 拒绝只能是短句。长句里的“别管”“别问”几乎都在讲别的事。
const REFUSE_MAX = 20

const AVOID = /(换个话题|不说这个|以后再说|改天|算了|没什么|没事了|不知道|随便吧|跳过)/
const BRIEF_ONLY =
  /^(嗯+|哦+|啊+|好+|行+|可以|收到|知道了|没事|还行|随便|是的|对|不|没有|哈哈+|呵+|哈|……|\.{2,}|ok|okay)$/i
const REASONING = /(因为|所以|但是|不过|我(觉得|想|在|已经|刚|准备)|计划|打算|今天|昨天|明天|后天|第[一二三四五六七八九十0-9]|\d)/

export function classifyReply(raw) {
  const text = String(raw == null ? '' : raw).replace(/\s+/g, ' ').trim()
  if (!text) return 'brief'
  if (REFUSE.test(text) && text.length <= REFUSE_MAX && !SELF_ACTION.test(text)) return 'refuse'
  if (text.length <= 8 && BRIEF_ONLY.test(text)) return 'brief'
  if (AVOID.test(text) && text.length < 24) return 'avoid'
  if (text.length >= 16) return 'substantive'
  if (REASONING.test(text) && text.length >= 6) return 'substantive'
  return 'brief'
}

/** 只有实质回答才继续深入；其它状态由有限跟进/冷却处理。 */
export function isSubstantive(raw) {
  return classifyReply(raw) === 'substantive'
}
