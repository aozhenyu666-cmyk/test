// Offline fallback for the chat / rant parser: keyword and regex heuristics.
// Returns the same shape as the AI `parse` engine, so the form-filling code is shared.
// It is deliberately conservative: it fills what it can see and flags the rest.

const SOFT_REJECT = ['改天', '下次', '再说', '最近忙', '有点忙', '比较忙', '太忙', '有点事', '有事', '看情况', '到时候再', '不一定', '再看看', '回头', '有空再', '以后再', '先不了', '算了吧', '没空'];
const VAGUE = ['嗯嗯', '哦哦', '哈哈哈', '还行', '随便', '都行', '再说吧', '…', '。。。'];
const WARM = ['谢谢', '感谢', '辛苦了', '想你', '好开心', '太好了', '爱你', '多亏', '暖'];
const FLAKE = ['来不了', '去不了', '不去了', '放鸽子', '鸽了', '临时有事', '突然有事'];
const ACCEPT = ['好啊', '好呀', '可以啊', '可以的', '行啊', '没问题', '一言为定', '不见不散', '走起'];
const INVITE = ['约', '出来', '一起去', '一起吃', '见面', '见个面', '见？', '见吗', '吃饭吗', '有空吗', '有时间吗', '周末', '聚一聚', '聚聚', '去玩'];
const PROMISE = ['下次一定', '答应', '保证', '承诺', '一定会', '肯定给', '到时候给', '年底给', '回头给'];
const NEG_FEEL = ['生气', '难过', '失落', '委屈', '烦', '无语', '心寒', '郁闷', '伤心', '憋屈', '火大', '崩溃'];
const POS_FEEL = ['开心', '感动', '温暖', '高兴', '踏实', '舒服', '放心'];

const TYPE_RULES = [
  ['conflict', ['吵架', '吵了', '骂', '翻脸', '拉黑', '冷战', '争执']],
  ['work', ['加班', '项目', '老板', '领导', '工资', '绩效', '开会', '甩锅']],
  ['deal', ['押金', '退款', '砍价', '议价', '合同', '房东', '商家', '客服', '赔偿']],
  ['gift', ['请客', '请吃', '送了', '礼物', '红包', '买单', '我付', '我请']],
  ['care', ['爸', '妈', '父母', '外婆', '奶奶', '爷爷', '外公', '生日快乐', '注意身体']],
  ['support', ['安慰', '陪聊', '陪他聊', '陪她聊', '焦虑', '倾诉', '开导']],
  ['help', ['帮忙', '帮我', '帮他', '帮她', '搬家', '接送']],
  ['invite', INVITE],
  ['meet', ['见了', '见面了', '一起吃', '聚了', '逛了']],
  ['chat', ['电话', '聊天', '语音', '视频', '聊了']],
];

const CN_NUM = { 半: 0.5, 一: 1, 两: 2, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10 };

function includesAny(text, words) {
  return words.find((w) => text.includes(w)) || null;
}

/** Split into speaker lines. "我：xxx" → me; "阿哲：xxx" → them; unlabeled → unknown. */
export function splitLines(text) {
  return String(text || '')
    .split(/\n+/)
    .map((l) => l.trim())
    .filter(Boolean)
    .map((line) => {
      const m = line.match(/^([^：:\s]{1,12})\s*[：:]\s*(.*)$/);
      if (!m) return { who: 'unknown', name: '', text: line };
      const me = /^(我|自己|me|Me|ME)$/.test(m[1]);
      return { who: me ? 'me' : 'them', name: me ? '' : m[1], text: m[2] };
    });
}

/** Total minutes mentioned: "一个多小时", "半小时", "40分钟", "2h". */
export function extractMinutes(text) {
  let total = 0;
  const re = /(\d+(?:\.\d+)?|[半一两二三四五六七八九十])\s*(?:个)?(多)?\s*(小时|钟头|分钟|h\b|min\b)/gi;
  for (const m of text.matchAll(re)) {
    const n = /\d/.test(m[1]) ? Number(m[1]) : CN_NUM[m[1]];
    if (!n) continue;
    const unit = m[3].toLowerCase();
    total += unit === '分钟' || unit === 'min' ? n : n * 60;
  }
  if (!total && /半小时/.test(text)) total = 30;
  return Math.round(total);
}

/** Money mentioned: "150元", "两百块" is not handled; digits only, which covers most chats. */
export function extractMoney(text) {
  let total = 0;
  for (const m of text.matchAll(/(\d+(?:\.\d+)?)\s*(?:元|块|rmb|RMB|￥)/g)) total += Number(m[1]);
  for (const m of text.matchAll(/[￥¥]\s*(\d+(?:\.\d+)?)/g)) total += Number(m[1]);
  return total;
}

export function parseLocal(rawText, { today } = {}) {
  const text = String(rawText || '');
  const lines = splitLines(text);
  const theirText = lines.filter((l) => l.who === 'them').map((l) => l.text).join('\n');
  const myText = lines.filter((l) => l.who === 'me').map((l) => l.text).join('\n');
  const hasSpeakers = lines.some((l) => l.who !== 'unknown');
  // Without speaker labels, treat the whole thing as my own account of what happened.
  const otherSide = hasSpeakers ? theirText : text;

  const signals = [];
  for (const l of lines) {
    const isMine = l.who === 'me';
    if (isMine) continue;
    const sr = includesAny(l.text, SOFT_REJECT);
    if (sr) signals.push({ quote: l.text, kind: 'soft_reject', meaning: `“${sr}”常见于委婉推脱，也可能真的很忙，先别下结论` });
    else if (includesAny(l.text, FLAKE)) signals.push({ quote: l.text, kind: 'soft_reject', meaning: '临时取消，原因不明' });
    else if (includesAny(l.text, PROMISE)) signals.push({ quote: l.text, kind: 'promise', meaning: '口头承诺，记下来看是否兑现' });
    else if (includesAny(l.text, WARM)) signals.push({ quote: l.text, kind: 'warm', meaning: '对方在表达感谢或亲近' });
    else if (l.who === 'them' && (l.text.length <= 3 || includesAny(l.text, VAGUE))) signals.push({ quote: l.text, kind: 'vague', meaning: '回复很短或很模糊，可能在忙，也可能不想多聊' });
  }

  // type
  let type = 'other';
  for (const [t, words] of TYPE_RULES) {
    if (includesAny(text, words)) {
      type = t;
      break;
    }
  }

  // invite
  const inviteWord = includesAny(hasSpeakers ? myText || text : text, INVITE);
  let invite = { present: false, by: 'me', status: 'accepted' };
  // A "can't make it" reply implies there was an invitation even without invite words.
  if (inviteWord || type === 'invite' || (hasSpeakers && includesAny(theirText, FLAKE))) {
    const by = hasSpeakers && !includesAny(myText, INVITE) && includesAny(theirText, INVITE) ? 'them' : 'me';
    const reply = by === 'me' ? otherSide : myText;
    let status = 'no_reply';
    if (includesAny(reply, FLAKE)) status = 'flaked';
    else if (includesAny(reply, SOFT_REJECT)) status = 'postponed';
    else if (/不去|不了|不行|拒绝/.test(reply)) status = 'declined';
    else if (includesAny(reply, ACCEPT)) status = 'accepted';
    else if (reply.trim()) status = 'accepted';
    invite = { present: true, by, status };
    if (type === 'other' || type === 'meet') type = 'invite';
  }

  // promise
  const promiseLine = lines.find((l) => includesAny(l.text, PROMISE));
  const promise = promiseLine
    ? { present: true, by: promiseLine.who === 'me' ? 'me' : 'them', status: 'pending', content: promiseLine.text.slice(0, 40) }
    : { present: false, by: 'them', status: 'pending', content: '' };

  // flows: unlabeled money/time is assumed to be mine unless the text says TA paid / helped.
  const minutes = extractMinutes(text);
  const money = extractMoney(text);
  const theyGave = /(他|她|TA|对方)\S{0,3}(请|付|买单|送|转|帮)/.test(text);
  const give = { money: theyGave ? 0 : money, minutes: theyGave ? 0 : minutes, emotion: 0 };
  const get = { money: theyGave ? money : 0, minutes: theyGave ? minutes : 0, emotion: 0 };
  if (type === 'support') give.emotion = 3;
  if (includesAny(otherSide, WARM)) get.emotion = 2;

  // feeling
  const neg = NEG_FEEL.filter((w) => text.includes(w)).length;
  const pos = POS_FEEL.filter((w) => text.includes(w)).length + (includesAny(otherSide, WARM) ? 1 : 0);
  let feeling = Math.max(-2, Math.min(2, pos - neg));
  if (!neg && !pos && signals.some((s) => s.kind === 'soft_reject')) feeling = -1;

  // initiator
  const first = lines.find((l) => l.who !== 'unknown');
  const initiator = first ? first.who : invite.present ? invite.by : 'me';

  // days ago
  let daysAgo = 0;
  if (/前天/.test(text)) daysAgo = 2;
  else if (/昨天|昨晚/.test(text)) daysAgo = 1;
  else if (/上周|上个星期/.test(text)) daysAgo = 7;

  const softCount = signals.filter((s) => s.kind === 'soft_reject').length;
  const responsiveness = !hasSpeakers ? 'unknown' : softCount >= 2 ? 'low' : softCount === 1 || signals.some((s) => s.kind === 'vague') ? 'medium' : 'high';

  const person = lines.find((l) => l.who === 'them')?.name || '';
  const firstLine = (lines[0]?.text || text).replace(/\s+/g, ' ');
  const summary = invite.present
    ? `${invite.by === 'me' ? '我约 TA' : 'TA 约我'}${{ accepted: '，TA 答应了', postponed: '，被推到以后', declined: '，被婉拒', flaked: '，TA 临时取消', no_reply: '，还没回音' }[invite.status]}`
    : firstLine.slice(0, 20);

  const situation = [
    hasSpeakers
      ? lines.map((l) => `${l.who === 'me' ? '我' : l.who === 'them' ? 'TA' : ''}${l.who === 'unknown' ? '' : '：'}${l.text}`).join('\n')
      : text.trim(),
    signals.length ? `\n值得注意的信号：${signals.map((s) => `“${s.quote}”`).join('、')}` : '',
  ].join('');

  return {
    summary,
    person_hint: person,
    type,
    days_ago: daysAgo,
    initiator,
    give,
    get,
    invite,
    promise,
    feeling,
    responsiveness,
    signals,
    situation,
    _local: true,
    _today: today || null,
  };
}
