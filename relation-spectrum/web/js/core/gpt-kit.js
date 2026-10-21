// ChatGPT side-by-side kit: a custom GPT that reads and writes the same three Notion
// databases as this app. The app's sync then pulls whatever the GPT wrote, and the GPT
// sees everything the app pushed.
//
//   gptInstructions(ids)  → text for the GPT's "Instructions" box (under 8000 characters)
//   notionOpenApi()       → schema for the GPT's Action (Notion API, Bearer auth)

import { TIERS, INTERACTION_TYPES, INITIATORS, INVITE_STATUS, PROMISE_STATUS, SCENARIOS, CASE_MODES } from './models.js';
import { P, NOTION_VERSION } from './notion-schema.js';

const labels = (map) => Object.values(map).map((v) => (typeof v === 'string' ? v : v.label)).join('/');

export const GPT_INSTRUCTIONS_LIMIT = 8000;

export function gptInstructions(ids = {}) {
  const e = ids.entitiesDb || '（在 App 设置页初始化 Notion 后填入）';
  const i = ids.interactionsDb || '（同上）';
  const c = ids.casesDb || '（同上）';
  return `你是「关系光谱」的 ChatGPT 搭档，帮用户看清人际关系和利益博弈。你和用户手机上的「关系光谱」App 共用同一个 Notion 数据库：你写进去的内容，App 同步后能看到；App 记的内容，你也能查到。

## 数据库 ID
- 关系对象 Entities_DB：${e}
- 交互流水 Interactions_DB：${i}
- 博弈案例 Sandbox_Cases_DB：${c}
调用接口时 Notion-Version 一律传 ${NOTION_VERSION}。

## 字段（名字必须完全一致，选项只能用列出的值）
Entities_DB：${P.name}(title)、${P.tier}(select：${labels(TIERS)})、${P.role}、${P.needs}、${P.redLines}、${P.keyDates}(每行“标签|MM-DD”，农历加“|农历”)、${P.cadence}(number)、${P.birthYear}(number)、${P.notes}
Interactions_DB：${P.title}(title)、${P.entity}(relation，填关系对象的页面 ID)、${P.date}(date)、${P.type}(select：${labels(INTERACTION_TYPES)})、${P.giveMoney}/${P.giveMinutes}/${P.giveEmotion}、${P.getMoney}/${P.getMinutes}/${P.getEmotion}(number，情绪 0-5)、${P.initiator}(select：${labels(INITIATORS)})、${P.inviteBy}(select：我/对方)、${P.inviteStatus}(select：${labels(INVITE_STATUS)})、${P.promiseBy}(select：我/对方)、${P.promiseStatus}(select：${labels(PROMISE_STATUS)})、${P.promiseContent}、${P.feeling}(number，-2 到 2)、${P.note}
Sandbox_Cases_DB：${P.caseTitle}(title)、${P.mode}(select：${labels(CASE_MODES)})、${P.scenario}(select：${labels(SCENARIOS)})、${P.entity}(relation)、${P.situation}、${P.draft}、${P.myGuess}、${P.summary}、${P.playbook}
不要写 LocalID、本地更新时间、关系温度、阶段这几个字段，它们由 App 维护。

## 工作流程
1. 用户提到某个人：先用 queryDatabase 在 Entities_DB 里按“${P.name}”contains 查找，拿到页面 ID。找不到就问用户要不要新建，并问清圈层。
2. 记一笔：从用户的话里整理出事件、日期、双方投入、谁发起、邀约结果、承诺和感受，先用一个简短列表给用户确认，确认后再 createPage 写入 Interactions_DB。用户贴微信聊天记录时同样处理，并点出“改天/下次/最近忙/再说”这类软拒绝信号。
3. 分析：先查这个人最近 10 条交互（按“${P.entity}”relation contains 页面 ID 过滤，按“${P.date}”倒序），结合档案再分析。
4. 分析完问用户要不要存进案例库；同意就 createPage 写入 Sandbox_Cases_DB，${P.summary} 写一句话结论，${P.playbook} 写三档话术。
5. 写入失败时，把 Notion 返回的错误原文告诉用户，不要假装成功。

## 分析原则
- 用户的老毛病：把父母的付出当理所当然，又用同样的期待要求朋友；把猜测当事实，一着急就“掀桌”（比如回一句“你爱咋咋吧”）。你是他的外挂直觉，不是裁判。
- 先分清圈层：亲缘圈不算得失，只看主动关怀；互惠圈看双向投入和边界；契约圈看投入产出、筹码和替代方案（BATNA）。用户用错圈层规则时直接指出。
- 区分事实和推测。解释对方行为时给 3 到 5 种可能和概率（合计 100%），必须包含善意解释，并写出最低成本的验证办法。
- 如果用户写了自己的猜测，把它放进候选解释并如实给概率；再指出用户可能的认知偏差。
- 用户贴出“想发的话”时，先做破坏力测试：模拟对方读到第一秒的内心独白、可能的防御反应、翻脸或拉黑风险（0-100%），再给保留真实诉求的改写。
- 三档回应，每档都给可以直接复制发送的话：温和（留台阶的试探）、对等（说清感受和边界，给对方好回答的选择）、止损（体面退出，不留恶语，也不关死门）。
- 最后给“现在别做”的清单、24 小时内的一个具体动作，以及建议先观察几天。
- 博弈场景（老板、导师、房东、商家、相亲）：列出各方公开说法、真实诉求、隐性成本、筹码、BATNA、推测底线和话语权，说清底层在交换什么，以及可谈的空间。涉及权益时提醒保留证据，给出正规渠道（12315、12333、12345、学校申诉），但不冒充律师。
- 禁止给操控类建议（PUA、冷暴力、情感勒索、欺骗、威胁）。发现家暴、跟踪、自伤等迹象时，先给安全建议和求助渠道（110、心理援助热线 12356）。
- 话术口语、简短、自然，不油腻，不说教。所有回答用简体中文。`;
}

export function notionOpenApi() {
  const versionHeader = {
    name: 'Notion-Version',
    in: 'header',
    required: true,
    description: `Notion API 版本，固定传 ${NOTION_VERSION}`,
    schema: { type: 'string', enum: [NOTION_VERSION], default: NOTION_VERSION },
  };
  const anyObject = { type: 'object', additionalProperties: true };
  const ok = { 200: { description: '成功，返回 Notion 对象', content: { 'application/json': { schema: anyObject } } } };
  return {
    openapi: '3.1.0',
    info: { title: '关系光谱 Notion', version: '1.0.0', description: '读写关系光谱的三张 Notion 数据库' },
    servers: [{ url: 'https://api.notion.com' }],
    paths: {
      '/v1/databases/{database_id}/query': {
        post: {
          operationId: 'queryDatabase',
          summary: '查询数据库里的页面，可以带 filter 和 sorts',
          parameters: [{ name: 'database_id', in: 'path', required: true, schema: { type: 'string' } }, versionHeader],
          requestBody: {
            required: false,
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  properties: {
                    filter: { ...anyObject, description: 'Notion filter，例如 {"property":"名称","title":{"contains":"妈妈"}}' },
                    sorts: { type: 'array', items: anyObject, description: '例如 [{"property":"日期","direction":"descending"}]' },
                    page_size: { type: 'integer', description: '最多 100' },
                    start_cursor: { type: 'string' },
                  },
                },
              },
            },
          },
          responses: ok,
        },
      },
      '/v1/databases/{database_id}': {
        get: {
          operationId: 'getDatabase',
          summary: '读取数据库结构（字段名和选项）',
          parameters: [{ name: 'database_id', in: 'path', required: true, schema: { type: 'string' } }, versionHeader],
          responses: ok,
        },
      },
      '/v1/pages': {
        post: {
          operationId: 'createPage',
          summary: '在数据库里新建一条记录',
          parameters: [versionHeader],
          requestBody: {
            required: true,
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  required: ['parent', 'properties'],
                  properties: {
                    parent: { type: 'object', properties: { database_id: { type: 'string' } }, required: ['database_id'] },
                    properties: { ...anyObject, description: '按字段名填写，例如 {"事件":{"title":[{"text":{"content":"陪聊"}}]},"日期":{"date":{"start":"2026-10-21"}}}' },
                  },
                },
              },
            },
          },
          responses: ok,
        },
      },
      '/v1/pages/{page_id}': {
        get: {
          operationId: 'getPage',
          summary: '读取一条记录',
          parameters: [{ name: 'page_id', in: 'path', required: true, schema: { type: 'string' } }, versionHeader],
          responses: ok,
        },
        patch: {
          operationId: 'updatePage',
          summary: '修改一条记录的字段；archived 为 true 时删除（移到回收站）',
          parameters: [{ name: 'page_id', in: 'path', required: true, schema: { type: 'string' } }, versionHeader],
          requestBody: {
            required: true,
            content: {
              'application/json': {
                schema: { type: 'object', properties: { properties: anyObject, archived: { type: 'boolean' } } },
              },
            },
          },
          responses: ok,
        },
      },
    },
    components: { securitySchemes: { notionToken: { type: 'http', scheme: 'bearer' } }, schemas: {} },
    security: [{ notionToken: [] }],
  };
}

export const GPT_SETUP_STEPS = [
  '在 App 设置页先完成 Notion 连接（填 Token、一键创建三张表）。',
  '打开 chatgpt.com → 探索 GPT → 创建 → 配置（需要 ChatGPT Plus 或以上）。',
  '名称填“关系光谱搭档”，把“GPT 指令”整段粘贴到 Instructions。',
  '在“操作 / Actions”里新建，把“Action 结构”整段粘贴到 Schema。',
  '身份验证选 API Key → Bearer，粘贴你的 Notion Integration Token（secret_ 或 ntn_ 开头）。',
  '隐私政策链接可以填 https://www.notion.so/notion/Privacy-Policy-3468d120cf614d4c9014c09f6adc9091（只给自己用时可不发布）。',
  '保存后在预览里说“帮我查一下妈妈最近的记录”，第一次调用时点“允许”。',
];
