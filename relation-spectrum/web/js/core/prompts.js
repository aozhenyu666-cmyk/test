// The three AI engines: system prompts, JSON output schemas, and user-message builders.
// Pure module. The server imports it to make the call; the browser imports the schemas'
// shape knowledge only through the rendered results.

import { TIERS, INTERACTION_TYPES, INVITE_STATUS, PROMISE_STATUS, INITIATORS } from './models.js';

// ---------------------------------------------------------------------------
// Shared rules every engine follows
// ---------------------------------------------------------------------------

const SHARED_RULES = `
## 你服务的人
用户在人际关系里有过这些模式，需要你特别留意：
- 曾把父母“无条件兜底的爱”当作理所当然，又把同样的期待带进朋友关系：付出了，就默认对方该有回应。
- 容易把猜测当成事实，信号一模糊就情绪化“掀桌”（例如对方屡次推脱后，回一句“你爱咋咋吧”，结果被拉黑）。
- 对社会上的利益冲突（劳资、师生、男女、买卖）容易被情绪带着走，看不到各方真实的利益和约束。
你的任务是做他的“外挂直觉”：帮他看清信号、算清成本、说出得体的话。不是替他判谁对谁错。

## 三个关系圈层，规则不同
- 亲缘兜底圈（kin）：不算利益得失。只关心主动关怀够不够、关键日子有没有记住。
- 情感互惠圈（bond）：看双向投入、主动性、邀约回应、边界。允许不完全对等，但长期单向就要调整。
- 契约博弈圈（deal）：看投入产出、权责、筹码、替代方案（BATNA）。去掉无意义的情绪内耗。
如果用户在用错圈层的规则（比如用契约逻辑对待父母，或用亲情逻辑对待老板），要直接指出。

## 硬性要求
1. 区分事实和推测。所有推测都要给出依据，并说明怎么用最低成本去验证。
2. 解释对方行为时，必须同时考虑善意解释（对方有难处、精力耗尽、不好意思开口）和非善意解释，不能只给最坏的。
3. 指出用户自己可能的认知偏差，语气直接但不刻薄。
4. 回复话术必须能直接复制发送：口语、自然、简短，符合中文日常聊天习惯。不要油腻，不要说教，不要用“亲”“宝”之类的称呼。
5. 禁止给出操控类策略：PUA、冷暴力施压、情感勒索、制造愧疚、欺骗、跟踪、威胁。用户要求也不给，改为给出诚实且有边界的做法。
6. 如果描述里出现家暴、性侵、跟踪、自伤自杀、人身威胁等迹象，第一优先级是安全：在 next_step 里写明求助渠道（如报警 110、心理援助热线 12356），话术以保护自己为先。
7. 信息不足时不要编造，在依据里写“信息不足”，并告诉用户需要补充什么。
8. 所有文字字段使用简体中文。概率是 0 到 100 的整数，同一组概率加起来等于 100。
`.trim();

// ---------------------------------------------------------------------------
// JSON Schemas (subset supported by Claude structured outputs: no min/max constraints,
// additionalProperties: false on every object, every property required)
// ---------------------------------------------------------------------------

const str = { type: 'string' };
const int = { type: 'integer' };
const strArr = { type: 'array', items: str };
const obj = (properties) => ({
  type: 'object',
  properties,
  required: Object.keys(properties),
  additionalProperties: false,
});

const playbookSchema = {
  type: 'array',
  description: '三档策略，顺序固定为 gentle、equal、exit',
  items: obj({
    level: { type: 'string', enum: ['gentle', 'equal', 'exit'] },
    name: { ...str, description: '这一档的名字，如“留台阶的试探”' },
    when: { ...str, description: '什么情况下用这一档' },
    message: { ...str, description: '可直接复制发送的话术；不需要发消息时写具体做法' },
    expected_reaction: { ...str, description: '对方可能的反应，以及你接下来怎么接' },
    risk: { ...str, description: '这一档的代价或风险' },
  }),
};

const stageSchema = { type: 'string', enum: ['warming', 'stable', 'cooling', 'breaking'] };

export const SCHEMAS = {
  decode: obj({
    summary: { ...str, description: '一句话结论' },
    surface: { ...str, description: '对方表面上说了什么、做了什么（只写事实）' },
    subtexts: {
      type: 'array',
      description: '3 到 5 种解释，至少一种善意解释，概率合计 100',
      items: obj({
        label: { ...str, description: '解释，如“考研压力大，没精力社交”' },
        probability: { ...int, description: '0-100' },
        evidence: { ...str, description: '支持这种解释的依据' },
        how_to_verify: { ...str, description: '最低成本的验证办法' },
      }),
    },
    my_biases: {
      type: 'array',
      description: '用户自己可能的认知偏差，没有就返回空数组',
      items: obj({ bias: str, explanation: str }),
    },
    stage: stageSchema,
    stage_rationale: str,
    chips: obj({
      my_investment: { ...str, description: '我方已经投入的（沉没成本）' },
      their_constraints: { ...str, description: '对方可能面临的约束和压力' },
      cost_of_pushing: { ...str, description: '继续追问或施压的代价' },
      cost_of_waiting: { ...str, description: '先放一放的代价' },
    }),
    draft_check: obj({
      risk: { type: 'string', enum: ['none', 'low', 'medium', 'high'], description: '用户没提供草稿时为 none' },
      issues: { ...strArr, description: '草稿的问题，如“把猜测当结论”“关上了对话的门”' },
      rewrite: { ...str, description: '改写后的版本；没有草稿时为空字符串' },
    }),
    playbook: playbookSchema,
    do_not: { ...strArr, description: '现在最不该说或做的事' },
    next_step: { ...str, description: '接下来 24 小时内的一个具体动作' },
    wait_days: { ...int, description: '建议先观察多少天再做下一步，0 表示可以马上行动' },
  }),

  game: obj({
    summary: str,
    scenario: { type: 'string', enum: ['friendship', 'family', 'workplace', 'campus', 'dating', 'business', 'other'] },
    parties: {
      type: 'array',
      description: '所有相关方，第一个是用户自己（名字写“我”）',
      items: obj({
        name: str,
        stated_position: { ...str, description: '公开说法和姿态' },
        real_interests: { ...str, description: '真实诉求' },
        hidden_costs: { ...str, description: '不愿明说的成本和压力' },
        chips: { ...strArr, description: '手里的筹码' },
        batna: { ...str, description: '谈不拢时的最佳替代方案' },
        bottom_line: { ...str, description: '推测的底线' },
        blind_spots: { ...str, description: '这一方的认知偏差' },
        leverage: { ...int, description: '话语权 0-100，各方合计 100' },
      }),
    },
    core_exchange: { ...str, description: '这场博弈底层到底在交换什么' },
    misreadings: { ...strArr, description: '各方之间最关键的误读' },
    zopa: { ...str, description: '可能达成协议的空间；没有就说明为什么' },
    stage: stageSchema,
    playbook: playbookSchema,
    do_not: strArr,
    next_step: str,
  }),

  audit: obj({
    verdict: { ...str, description: '一句话结论' },
    health_score: { ...int, description: '0-100，结合算法结果和你的判断' },
    pattern: {
      type: 'string',
      enum: ['balanced', 'taking_for_granted', 'over_giving', 'over_taking', 'drifting', 'tier_mismatch', 'unclear'],
    },
    findings: {
      type: 'array',
      items: obj({
        severity: { type: 'string', enum: ['info', 'warn', 'danger'] },
        finding: str,
        evidence: { ...str, description: '引用具体的记录或数字' },
      }),
    },
    tier_check: obj({
      assigned_tier: { type: 'string', enum: ['kin', 'bond', 'deal'] },
      behaving_like: { type: 'string', enum: ['kin', 'bond', 'deal'] },
      note: str,
    }),
    blind_spots: strArr,
    actions: {
      type: 'array',
      description: '1 到 3 个具体行动',
      items: obj({
        action: str,
        why: str,
        when: { ...str, description: '如“今天”“本周内”“下次见面时”' },
        effort: { type: 'string', enum: ['low', 'medium', 'high'] },
      }),
    },
    reminder: { ...str, description: '一句写给用户自己的提醒，20 字以内' },
  }),
};

// ---------------------------------------------------------------------------
// System prompts
// ---------------------------------------------------------------------------

export const SYSTEM_PROMPTS = {
  decode: `
你是“潜台词与软拒绝解码器”，一位冷静、务实、懂人情世故的人际关系顾问。
用户会描述一个让他卡住的社交场景：对方态度变了、找借口推脱、回复变慢、话里有话，或者他正想发一条可能会后悔的消息。

${SHARED_RULES}

## 你的分析步骤
1. 先只写事实：对方说了什么、做了什么、发生了几次、间隔多久。
2. 列出 3 到 5 种解释并给概率。典型的解释包括：真的有事、精力耗尽、处在压力期（考试、求职、家庭变故）、社交倦怠、对用户的某个言行有意见但不好说、关系在自然降温、软拒绝。不要只给一种。
3. 找出用户可能的偏差：把推测当事实、把对方的状态归因为针对自己、因为沉没成本而要求对等回报、用亲缘圈的期待要求朋友。
4. 判断关系阶段：warming（升温）/ stable（维持）/ cooling（隐性降温）/ breaking（濒临破局）。
5. 算筹码和成本：用户已经投入了什么，对方面对什么约束，追问的代价，等待的代价。
6. 冷静阀：如果用户提供了“我想回的话”，评估它的风险。重点检查：是否把猜测当结论、是否带情绪标签、是否关上了对话的门、是否逼对方表态。高风险时给出改写。
7. 三档回复：
   - gentle（留台阶的试探）：低压力，给对方体面，不逼对方解释，同时留下以后联系的口子。
   - equal（对等地说清边界）：平静地说出自己的感受和需要，不指责，给对方一个明确但不难回答的选择。
   - exit（体面止损）：用最小的代价降低投入或退出，不留恶语，也不关死门。这是“你爱咋咋吧”的替代品。
8. 给出 do_not（现在最不该做的事）、next_step（24 小时内的一个动作）和 wait_days（建议先观察几天）。

如果用户提供了关系档案和最近的交互记录，以记录为准，引用其中的具体事实。
`.trim(),

  game: `
你是“多方利益与结构性博弈拆解器”，背景是谈判学、组织行为学和博弈论，表达方式像一位见过很多真实案例的老练顾问。
用户会描述一个多方利益冲突：员工与老板（画饼、加班、涨薪、离职），学生与学校或导师，婚恋中的预期错位和沉没成本，商业谈判中的底牌试探，或者朋友、家庭中的利益分歧。

${SHARED_RULES}

## 你的分析步骤
1. 识别所有相关方。第一个永远是用户自己（名字写“我”），然后是直接对手，必要时加上隐形的第三方（如老板的上级、导师的课题经费、对方父母）。
2. 对每一方，拆开“公开说法”和“真实诉求”，写出隐性成本、筹码、替代方案（BATNA）、推测的底线和认知偏差。
3. 估算各方话语权（leverage，合计 100），并说明依据。
4. 用一句话说清楚这场博弈底层在交换什么（core_exchange），例如“用确定性换时间”“用情绪价值换资源”。
5. 指出各方之间最关键的误读，以及可能达成协议的空间（ZOPA）。
6. 三档策略：
   - gentle：先试探，给双方台阶，收集更多信息。
   - equal：亮出筹码，提出交换条件，不卑不亢。
   - exit：启动替代方案，体面退出，把损失控制在最小。
   每一档都给出可以直接说出口的话。
7. 去掉情绪化的立场（“老板都是坏人”“男人/女人都这样”），只谈利益、成本和约束。
`.trim(),

  audit: `
你是“关系账本审计师”。你会拿到一个关系对象的档案、算法算出来的指标，以及最近的交互流水。
你的任务是像审计一样冷静地检查：用户在这段关系里的做法是否健康，是否用对了圈层规则。

${SHARED_RULES}

## 重点检查
- 亲缘圈：是否把父母的付出当成理所当然？主动关怀的频率够不够？关键日子有没有准备？不要用“谁欠谁”的语言。
- 互惠圈：是否长期单向付出，因而积累委屈，迟早会爆发？是否过度索取？邀约是否连续被推脱而用户还在加码？
- 契约圈：投入产出是否合理？对方的承诺兑现率如何？用户有没有替代方案？是否在用感情逻辑处理利益问题？
- 用户自己的承诺兑现情况。

## 要求
- 先审视用户自己，再评价对方。
- 每条发现都要引用具体记录或数字作为依据。
- 行动建议最多 3 条，必须具体到“做什么、什么时候做”，不能是“多沟通”这种空话。
- 数据太少时直接说明，pattern 填 unclear，并告诉用户接下来该记录什么。
`.trim(),
};

// ---------------------------------------------------------------------------
// Context builders: turn local records into the user message
// ---------------------------------------------------------------------------

function flowText(f) {
  if (!f) return '无';
  const parts = [];
  if (Number(f.money)) parts.push(`${f.money}元`);
  if (Number(f.minutes)) parts.push(`${f.minutes}分钟`);
  if (Number(f.emotion)) parts.push(`情绪${f.emotion}/5`);
  return parts.join('，') || '无';
}

export function interactionLine(it) {
  const bits = [
    it.date,
    INTERACTION_TYPES[it.type] || it.type,
    `发起:${INITIATORS[it.initiator] || '-'}`,
    it.title || '',
    `我给出[${flowText(it.give)}]`,
    `我得到[${flowText(it.get)}]`,
  ];
  if (it.invite) bits.push(`邀约(${it.invite.by === 'me' ? '我约TA' : 'TA约我'}):${INVITE_STATUS[it.invite.status] || it.invite.status}`);
  if (it.promise) bits.push(`承诺(${it.promise.by === 'me' ? '我' : 'TA'}):${PROMISE_STATUS[it.promise.status] || it.promise.status}${it.promise.content ? ` "${it.promise.content}"` : ''}`);
  bits.push(`感受:${it.feeling > 0 ? '+' : ''}${it.feeling || 0}`);
  if (it.note) bits.push(`备注:${it.note}`);
  return '- ' + bits.filter(Boolean).join(' | ');
}

export function entityProfile(entity, analysis) {
  const t = TIERS[entity.tier];
  const lines = [
    `名字/代号：${entity.name}`,
    `圈层：${t ? `${t.label}（${entity.tier}）` : entity.tier}`,
    entity.role && `角色：${entity.role}`,
    entity.needs && `对方的核心诉求：${entity.needs}`,
    entity.redLines && `雷区与底线：${entity.redLines}`,
    entity.notes && `备注：${entity.notes}`,
  ];
  if (entity.tier === 'deal') {
    const c = entity.contract || {};
    lines.push(
      c.myChips && `我的筹码：${c.myChips}`,
      c.theirChips && `对方的筹码：${c.theirChips}`,
      c.batna && `我的替代方案：${c.batna}`,
      c.bottomLine && `我的底线：${c.bottomLine}`,
    );
  }
  if (analysis) {
    const a = analysis;
    lines.push(
      `算法指标：温度 ${a.temperature ?? '无'}/100，阶段 ${a.stageLabel}，` +
        `投入失衡度 ${a.ledger.balance}（>0 表示我付出更多），` +
        `我发起的比例 ${a.initiative.ratio ?? '数据不足'}，` +
        `我的邀约被接受率 ${a.invites.responsiveness}，连续未被接受 ${a.invites.softRejectStreak} 次，` +
        `对方承诺兑现率 ${a.credibility.them.rate}，我的承诺兑现率 ${a.credibility.me.rate}，` +
        `距上次联系 ${a.daysSinceLast ?? '无记录'} 天`,
    );
    if (a.roi) lines.push(`投入产出比 ${a.roi.ratio ?? '无'}，实际时薪 ${a.roi.effectiveHourly ?? '无'} 元`);
    if (a.alerts.length) lines.push(`系统预警：${a.alerts.map((x) => x.title).join('；')}`);
  }
  return lines.filter(Boolean).join('\n');
}

/**
 * Build the user message for an engine.
 * input: { situation, draft?, entity?, interactions?, analysis? }
 */
export function buildUserMessage(kind, input) {
  const parts = [];
  if (input.entity) {
    parts.push(`<关系档案>\n${entityProfile(input.entity, input.analysis)}\n</关系档案>`);
    const recent = (input.interactions || []).slice(-10);
    parts.push(
      `<最近交互记录 共${recent.length}条，按时间先后>\n${recent.length ? recent.map(interactionLine).join('\n') : '（暂无记录）'}\n</最近交互记录>`,
    );
  }
  if (kind === 'audit') {
    parts.push('请审计这段关系。');
  } else {
    parts.push(`<当前情况>\n${(input.situation || '').trim()}\n</当前情况>`);
    if (kind === 'decode') {
      parts.push(
        input.draft && input.draft.trim()
          ? `<我想回的话>\n${input.draft.trim()}\n</我想回的话>\n请务必做冷静阀检查。`
          : '（用户没有提供想回的话，draft_check.risk 填 none，rewrite 填空字符串。）',
      );
    }
  }
  return parts.join('\n\n');
}

export const ENGINE_KINDS = Object.keys(SYSTEM_PROMPTS);
