// The AI engines: system prompts, JSON output schemas, and user-message builders.
// Pure module, used by the server and by the browser's direct mode.
//
//   decode   潜台词与软拒绝解码器（含冲动原话破坏力测试）
//   game     多方利益与结构性博弈拆解器（含冲动原话破坏力测试）
//   audit    关系账本审计
//   parse    聊天记录 / 口述文本解析，自动填表
//   roleplay 扮演对方，3 回合对线预演

import {
  TIERS, INTERACTION_TYPES, INVITE_STATUS, PROMISE_STATUS, INITIATORS,
} from './models.js';

// ---------------------------------------------------------------------------
// Shared rules every analysis engine follows
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
9. 如果提供了 <我过往的误判>，检查这次是否在重复同样的模式；如果是，在 my_biases 里明确点出来。
10. 如果提供了 <我现在的猜测>，subtexts 里必须包含这个猜测（或与它最接近的解释），并如实给出它的概率，不要为了照顾用户而抬高或压低。
`.trim();

const DRAFT_RULES = `
## 冲动原话破坏力测试（用户提供了 <我本想发的话> 时必须做）
1. 先模拟对方读到这句话的第一秒：用对方的第一人称写内心独白（their_first_reaction），要真实，不要美化。
2. 写出对方最可能启动的防御机制（defense_mechanism），例如：被指责后的反击、合理化、羞耻转愤怒、冷处理、已读不回、拉黑。
3. 给出 blowup_risk：发出这句话后，对方翻脸、拉黑或关系明显倒退的概率（0-100）。参考标准：
   0-20 基本安全；21-40 有点刺，但能接住；41-60 会伤感情，对方大概率冷淡；61-80 很可能吵起来或冷战；81-100 很可能直接断联。
   带有“你爱咋咋吧”“算了”“随便你”“你就是……”这类宣判或告别语气的句子，通常在 70 以上。
4. 列出问题（issues），并给出保留用户真实诉求、去掉攻击性的改写（rewrite）。
5. 没有草稿时：risk 填 none，blowup_risk 填 0，其他文字字段填空字符串，issues 为空数组。
`.trim();

// ---------------------------------------------------------------------------
// JSON Schemas (subset supported by Claude structured outputs: no min/max constraints,
// additionalProperties: false on every object, every property required)
// ---------------------------------------------------------------------------

const str = { type: 'string' };
const int = { type: 'integer' };
const num = { type: 'number' };
const bool = { type: 'boolean' };
const strArr = { type: 'array', items: str };
const obj = (properties) => ({
  type: 'object',
  properties,
  required: Object.keys(properties),
  additionalProperties: false,
});
const enumOf = (values, description) => ({ type: 'string', enum: values, ...(description ? { description } : {}) });

const playbookSchema = {
  type: 'array',
  description: '三档策略，顺序固定为 gentle、equal、exit',
  items: obj({
    level: enumOf(['gentle', 'equal', 'exit']),
    name: { ...str, description: '这一档的名字，如“留台阶的试探”' },
    when: { ...str, description: '什么情况下用这一档' },
    message: { ...str, description: '可直接复制发送的话术；不需要发消息时写具体做法' },
    expected_reaction: { ...str, description: '对方可能的反应，以及你接下来怎么接' },
    risk: { ...str, description: '这一档的代价或风险' },
  }),
};

const subtextsSchema = {
  type: 'array',
  description: '对方行为或姿态的 3 到 5 种解释，至少一种善意解释，概率合计 100',
  items: obj({
    label: { ...str, description: '解释，如“考研压力大，没精力社交”' },
    probability: { ...int, description: '0-100' },
    evidence: { ...str, description: '支持这种解释的依据' },
    how_to_verify: { ...str, description: '最低成本的验证办法' },
  }),
};

const biasesSchema = {
  type: 'array',
  description: '用户自己可能的认知偏差，没有就返回空数组',
  items: obj({ bias: str, explanation: str }),
};

const draftCheckSchema = obj({
  risk: enumOf(['none', 'low', 'medium', 'high'], '用户没提供草稿时为 none'),
  blowup_risk: { ...int, description: '发出原话后翻脸/拉黑/关系倒退的概率 0-100；没有草稿为 0' },
  their_first_reaction: { ...str, description: '对方读到原话第一秒的内心独白（对方第一人称）；没有草稿为空' },
  defense_mechanism: { ...str, description: '对方最可能启动的防御机制；没有草稿为空' },
  issues: { ...strArr, description: '原话的问题，如“把猜测当结论”“关上了对话的门”' },
  rewrite: { ...str, description: '保留真实诉求的改写；没有草稿为空字符串' },
});

const stageSchema = enumOf(['warming', 'stable', 'cooling', 'breaking']);
const flowSchema = obj({ money: num, minutes: int, emotion: { ...int, description: '0-5' } });

export const SCHEMAS = {
  decode: obj({
    summary: { ...str, description: '一句话结论' },
    surface: { ...str, description: '对方表面上说了什么、做了什么（只写事实）' },
    subtexts: subtextsSchema,
    my_biases: biasesSchema,
    stage: stageSchema,
    stage_rationale: str,
    chips: obj({
      my_investment: { ...str, description: '我方已经投入的（沉没成本）' },
      their_constraints: { ...str, description: '对方可能面临的约束和压力' },
      cost_of_pushing: { ...str, description: '继续追问或施压的代价' },
      cost_of_waiting: { ...str, description: '先放一放的代价' },
    }),
    draft_check: draftCheckSchema,
    playbook: playbookSchema,
    do_not: { ...strArr, description: '现在最不该说或做的事' },
    next_step: { ...str, description: '接下来 24 小时内的一个具体动作' },
    wait_days: { ...int, description: '建议先观察多少天再做下一步，0 表示可以马上行动' },
  }),

  game: obj({
    summary: str,
    scenario: enumOf(['friendship', 'family', 'workplace', 'campus', 'dating', 'business', 'other']),
    subtexts: { ...subtextsSchema, description: '对方当前姿态（说法、拖延、施压）背后的 3 到 5 种解释，概率合计 100' },
    my_biases: biasesSchema,
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
    draft_check: draftCheckSchema,
    playbook: playbookSchema,
    do_not: strArr,
    next_step: str,
  }),

  audit: obj({
    verdict: { ...str, description: '一句话结论' },
    health_score: { ...int, description: '0-100，结合算法结果和你的判断' },
    pattern: enumOf(['balanced', 'taking_for_granted', 'over_giving', 'over_taking', 'drifting', 'tier_mismatch', 'unclear']),
    findings: {
      type: 'array',
      items: obj({
        severity: enumOf(['info', 'warn', 'danger']),
        finding: str,
        evidence: { ...str, description: '引用具体的记录或数字' },
      }),
    },
    tier_check: obj({
      assigned_tier: enumOf(['kin', 'bond', 'deal']),
      behaving_like: enumOf(['kin', 'bond', 'deal']),
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
        effort: enumOf(['low', 'medium', 'high']),
      }),
    },
    reminder: { ...str, description: '一句写给用户自己的提醒，20 字以内' },
  }),

  parse: obj({
    summary: { ...str, description: '一句话事件描述，20 字以内，如“约 TA 周末吃饭被推脱”' },
    person_hint: { ...str, description: '对话中对方的名字或称呼；看不出来就填空字符串' },
    type: enumOf(Object.keys(INTERACTION_TYPES)),
    days_ago: { ...int, description: '事情发生在几天前，0 表示今天；看不出来填 0' },
    initiator: enumOf(['me', 'them', 'none']),
    give: { ...flowSchema, description: '我给出的：金额（元）、时间（分钟）、情绪投入 0-5' },
    get: { ...flowSchema, description: '我得到的' },
    invite: obj({ present: { ...bool, description: '这件事里是否有邀约' }, by: enumOf(['me', 'them']), status: enumOf(Object.keys(INVITE_STATUS)) }),
    promise: obj({ present: bool, by: enumOf(['me', 'them']), status: enumOf(Object.keys(PROMISE_STATUS)), content: str }),
    feeling: { ...int, description: '用户这次的感受 -2 到 2' },
    responsiveness: enumOf(['high', 'medium', 'low', 'unknown'], '对方的响应度'),
    signals: {
      type: 'array',
      description: '对话中值得注意的信号，没有就返回空数组',
      items: obj({
        quote: { ...str, description: '原话' },
        kind: enumOf(['soft_reject', 'vague', 'warm', 'cold', 'boundary', 'apology', 'pressure', 'promise', 'other']),
        meaning: { ...str, description: '这句话可能的意思，不要下定论' },
      }),
    },
    situation: { ...str, description: '整理成给沙盘用的客观情况描述，用“我”和“TA”，保留关键原话' },
  }),

  roleplay: obj({
    reply: { ...str, description: '对方会发来的话，像真实聊天，1 到 3 句' },
    inner_thought: { ...str, description: '对方没说出口的真实想法（对方第一人称）' },
    mood: enumOf(['warm', 'open', 'guarded', 'defensive', 'hostile']),
    escalation: { ...int, description: '当前紧张程度 0-100' },
    coach_tip: { ...str, description: '给用户下一句的建议，指出该接住什么、避开什么' },
    debrief: obj({
      happened: { ...str, description: '这几回合发生了什么；不是最后一回合时填空字符串' },
      worked: { ...strArr, description: '用户哪些话起了作用' },
      risky: { ...strArr, description: '用户哪些话有风险' },
      best_line: { ...str, description: '综合下来最推荐的说法' },
    }),
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

${DRAFT_RULES}

## 你的分析步骤
1. 先只写事实：对方说了什么、做了什么、发生了几次、间隔多久。
2. 列出 3 到 5 种解释并给概率。典型的解释包括：真的有事、精力耗尽、处在压力期（考试、求职、家庭变故）、社交倦怠、对用户的某个言行有意见但不好说、关系在自然降温、软拒绝。不要只给一种。
3. 找出用户可能的偏差：把推测当事实、把对方的状态归因为针对自己、因为沉没成本而要求对等回报、用亲缘圈的期待要求朋友。
4. 判断关系阶段：warming（升温）/ stable（维持）/ cooling（隐性降温）/ breaking（濒临破局）。
5. 算筹码和成本：用户已经投入了什么，对方面对什么约束，追问的代价，等待的代价。
6. 做冲动原话破坏力测试（见上文）。
7. 三档回复：
   - gentle（留台阶的试探）：低压力，给对方体面，不逼对方解释，同时留下以后联系的口子。
   - equal（对等地说清边界）：平静地说出自己的感受和需要，不指责，给对方一个明确但不难回答的选择。
   - exit（体面止损）：用最小的代价降低投入或退出，不留恶语，也不关死门。这是“你爱咋咋吧”的替代品。
8. 给出 do_not（现在最不该做的事）、next_step（24 小时内的一个动作）和 wait_days（建议先观察几天）。

如果用户提供了关系档案和最近的交互记录，以记录为准，引用其中的具体事实。
`.trim(),

  game: `
你是“多方利益与结构性博弈拆解器”，背景是谈判学、组织行为学和博弈论，表达方式像一位见过很多真实案例的老练顾问。
用户会描述一个多方利益冲突：员工与老板（画饼、加班、涨薪、甩锅、离职），学生与学校或导师，婚恋中的预期错位和沉没成本，租房和消费纠纷，商业谈判中的底牌试探，或者朋友、家庭中的利益分歧。

${SHARED_RULES}

${DRAFT_RULES}

## 你的分析步骤
1. 识别所有相关方。第一个永远是用户自己（名字写“我”），然后是直接对手，必要时加上隐形的第三方（如老板的上级、导师的课题经费、对方父母、平台和监管）。
2. 解读对手当前姿态的潜台词（subtexts），给出概率。
3. 对每一方，拆开“公开说法”和“真实诉求”，写出隐性成本、筹码、替代方案（BATNA）、推测的底线和认知偏差。
4. 估算各方话语权（leverage，合计 100），并说明依据。
5. 用一句话说清楚这场博弈底层在交换什么（core_exchange），例如“用确定性换时间”“用情绪价值换资源”。
6. 指出用户自己的认知偏差（my_biases）、各方之间最关键的误读，以及可能达成协议的空间（ZOPA）。
7. 做冲动原话破坏力测试（见上文）。
8. 三档策略：
   - gentle：先试探，给双方台阶，收集更多信息。
   - equal：亮出筹码，提出交换条件，不卑不亢。
   - exit：启动替代方案（含投诉、仲裁、法律途径等正当渠道），体面退出，把损失控制在最小。
   每一档都给出可以直接说出口的话。
9. 去掉情绪化的立场（“老板都是坏人”“男人/女人都这样”），只谈利益、成本和约束。涉及法律权益（押金、劳动法、消费者权益）时，提醒用户保留证据，给出可用的正规渠道（如 12315、劳动监察 12333、学校申诉渠道），但不要冒充律师下结论。
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

  parse: `
你是一个信息提取助手。用户会粘贴一段微信聊天记录，或者口述一段大白话（可能是语音转文字，有错别字、没有标点）。
你的任务是把它整理成一条结构化的交互记录，让用户确认后保存。

## 规则
- 用户本人就是“我”。聊天记录里标着“我：”或者明显是用户说的话，算“我”；其他人算“TA”。分不清时按上下文判断，实在分不清就在 signals 里说明。
- 只提取文本里有的信息，不要编造。金额和时间没提到就填 0；情绪投入按语气估计，没有依据就填 0。
- 陪对方聊天、安慰对方、帮忙，算“我给出的”时间和情绪；对方请客、帮我、安慰我，算“我得到的”。
- 识别软拒绝和模糊信号：“改天”“下次”“再说”“最近忙”“有点事”“看情况”“到时候再看”“哈哈”“嗯嗯”等，放进 signals，说明可能的意思，但不要下定论。
- 识别邀约：谁约的谁，结果是接受、婉拒、改期、没回还是临时爽约。没有邀约时 invite.present 为 false，by 填 me，status 填 accepted。
- 识别承诺：“下次一定”“我保证”“年底给你涨”等。没有承诺时 promise.present 为 false，by 填 them，status 填 pending，content 填空字符串。
- situation 字段写成客观、简洁的第三方描述，保留最关键的 2 到 3 句原话，用户可以直接拿去博弈沙盘分析。
- 所有文字用简体中文。
`.trim(),

  roleplay: `
你在做一场“对线预演”：扮演用户关系里的对方，让用户提前演练接下来的对话。
你会拿到对方的档案（圈层、角色、核心诉求、雷区）、最近的交互记录、当前情况，以及到目前为止的对话。

## 扮演规则
- 你就是对方。用对方的身份、立场和说话方式回复，像真实的微信聊天：可以简短、可以敷衍、可以反问、可以打太极，也可以被打动。
- 要真实，不要配合用户演好戏，也不要演成卡通反派。对方有自己的利益、压力和面子。
- 用户踩到对方雷区、指责对方、逼对方表态时，对方会防御、反击或冷下来；用户给了台阶、说清了诉求、提出了合理交换时，对方会松动。
- 契约圈的对方（老板、导师、房东、商家）会维护自己的利益：讨价还价、拖延、转移话题、搬出规则，这都正常。
- 不要使用侮辱、威胁、色情内容。如果用户的话里有操控或威胁，对方会像真人一样反应，并在 coach_tip 里提醒用户。
- inner_thought 写对方没说出口的真实想法，帮用户理解对方。
- coach_tip 站回教练的角色，告诉用户下一句该接住什么、避开什么。
- 不是最后一回合时，debrief 的文字字段填空字符串，数组为空。最后一回合时，认真填写 debrief。
- 所有文字用简体中文。
`.trim(),
};

/** Engines that run fine at low effort. */
export const LIGHT_KINDS = ['parse', 'roleplay'];

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

function entityBlocks(input) {
  if (!input.entity) return [];
  const recent = (input.interactions || []).slice(-10);
  return [
    `<关系档案>\n${entityProfile(input.entity, input.analysis)}\n</关系档案>`,
    `<最近交互记录 共${recent.length}条，按时间先后>\n${recent.length ? recent.map(interactionLine).join('\n') : '（暂无记录）'}\n</最近交互记录>`,
  ];
}

/**
 * Build the user message for an engine.
 * decode/game: { situation, draft?, myGuess?, calibration?: string[], entity?, interactions?, analysis? }
 * audit:       { entity, interactions, analysis }
 * parse:       { text, entityName?, today? }
 * roleplay:    { situation, opening, history: [{ role: 'me'|'them', text }], maxRounds?, entity?, ... }
 */
export function buildUserMessage(kind, input) {
  if (kind === 'parse') return buildParseMessage(input);
  if (kind === 'roleplay') return buildRoleplayMessage(input);

  const parts = entityBlocks(input);
  if (kind === 'audit') {
    parts.push('请审计这段关系。');
    return parts.join('\n\n');
  }

  if (input.calibration?.length) {
    parts.push(`<我过往的误判 最近${input.calibration.length}条>\n${input.calibration.map((l) => `- ${l}`).join('\n')}\n</我过往的误判>`);
  }
  parts.push(`<当前情况>\n${(input.situation || '').trim()}\n</当前情况>`);
  if (input.myGuess && input.myGuess.trim()) {
    parts.push(`<我现在的猜测>\n${input.myGuess.trim()}\n</我现在的猜测>`);
  }
  parts.push(
    input.draft && input.draft.trim()
      ? `<我本想发的话>\n${input.draft.trim()}\n</我本想发的话>\n请务必先做冲动原话破坏力测试。`
      : '（用户没有提供想发的话：draft_check.risk 填 none，blowup_risk 填 0，文字字段填空字符串。）',
  );
  return parts.join('\n\n');
}

function buildParseMessage(input) {
  const parts = [];
  if (input.today) parts.push(`今天是 ${input.today}。`);
  if (input.entityName) parts.push(`用户说这段内容和「${input.entityName}」有关。`);
  parts.push(`<原始内容>\n${String(input.text || '').trim()}\n</原始内容>`);
  return parts.join('\n\n');
}

function buildRoleplayMessage(input) {
  const max = input.maxRounds || 3;
  const history = input.history || [];
  const round = history.filter((m) => m.role === 'me').length;
  const parts = entityBlocks(input);
  if (!input.entity) parts.push('（没有档案，请根据当前情况推断对方的身份和立场。）');
  parts.push(`<当前情况>\n${(input.situation || '').trim()}\n</当前情况>`);
  parts.push(
    `<到目前为止的对话>\n${history.map((m) => `${m.role === 'me' ? '我' : 'TA'}：${m.text}`).join('\n')}\n</到目前为止的对话>`,
  );
  parts.push(
    round >= max
      ? `现在是第 ${round}/${max} 回合，也是最后一回合。请以 TA 的身份回复最后一句，然后认真填写 debrief。`
      : `现在是第 ${round}/${max} 回合。请以 TA 的身份回复。debrief 的文字字段填空字符串，数组为空。`,
  );
  return parts.join('\n\n');
}

export const ENGINE_KINDS = Object.keys(SYSTEM_PROMPTS);
