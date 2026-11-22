// 题库导入第二版：真机上导不进去的那几种写法，一种一条。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildImportPreview, detectQuestionAnomalies, GEMINI_IMPORT_PROMPT_TEMPLATE, normalizeAnswer, parseBankAny, parseDelimited, splitInlineOptions } from '../lib/bank_import.js'

const KEY_AT_END = `一、常识判断
1. 下列属于扩张性财政政策的是：
A. 提高税率
B. 增加政府支出
C. 减少转移支付
D. 提高准备金率
2. 2023 年我国 GDP 约为 126 万亿元，比上年增长：
2.5 亿元是干扰项，不是题号
A．5.2%  B．3.0%  C．8.1%  D．4.5%
3、我国的根本政治制度是（ ）
A.人民代表大会制度B.政治协商制度C.民族区域自治制度D.基层群众自治制度

参考答案
1.B 2.A 3.A`

test('答案集中在最后：按题号对上；「2.5 亿元」不切成新题；同行多选项（有空格 / 没空格）都拆得开', () => {
  const r = parseBankAny(KEY_AT_END)
  assert.equal(r.ok, true, JSON.stringify(r))
  assert.equal(r.questions.length, 3)
  assert.equal(r.format, 'text+key')
  assert.deepEqual(r.questions.map((q) => q.答案), ['B', 'A', 'A'])
  assert.match(r.questions[1].题干, /2\.5 亿元是干扰项/)
  assert.deepEqual(r.questions[1].选项, ['5.2%', '3.0%', '8.1%', '4.5%'])
  assert.equal(r.questions[2].选项.length, 4)
  assert.equal(r.questions[2].选项[0], '人民代表大会制度')
  assert.equal(r.questions[0].题型, '常识判断')
  assert.match(r.questions[0].解析, /没给解析/)
  assert.equal(r.questions[0].id, 'q-1')
})

test('区间答案「1-5 ABCDA」、逐题「1．【答案】B【解析】…」', () => {
  const qs = Array.from({ length: 5 }, (_, i) => `${i + 1}. 题干${i + 1}\nA. 甲\nB. 乙\nC. 丙\nD. 丁`).join('\n')
  const r1 = parseBankAny(qs + '\n\n答案\n1-5 ABCDA')
  assert.deepEqual(r1.questions.map((q) => q.答案), ['A', 'B', 'C', 'D', 'A'])
  const r2 = parseBankAny(qs + '\n\n答案与解析\n1．【答案】B【解析】因为乙。\n2.【答案】C\n【解析】丙对。\n3. 答案：D\n4. A\n5.【答案】AB')
  assert.deepEqual(r2.questions.map((q) => q.答案), ['B', 'C', 'D', 'A', 'AB'])
  assert.equal(r2.questions[0].解析, '因为乙。')
  assert.equal(r2.questions[1].解析, '丙对。')
})

test('答案紧跟每题（老格式）照样能读；缺答案的逐题报出来', () => {
  const r = parseBankAny(`1. 题一\nA. 甲\nB. 乙\n答案：B\n解析：乙对\n\n2. 题二没答案\nA. 甲\nB. 乙\n\n3. 题三\nA. 甲\nB. 乙\n【答案】A`)
  assert.equal(r.questions.length, 2)
  assert.deepEqual(r.skipped, [{ n: 2, why: '缺答案', preview: '题二没答案' }])
  assert.equal(r.questions[0].解析, '乙对')
})

test('资料分析：「回答 101~103 题」的材料拼到这几题前面', () => {
  const r = parseBankAny(`根据以下资料，回答 101~102 题。\n2023 年某市社会消费品零售总额 5000 亿元，同比增长 8%。\n101. 2022 年该市社零总额约为：\nA. 4630 亿元\nB. 4500 亿元\n102. 增量约为：\nA. 370 亿元\nB. 400 亿元\n参考答案\n101.A 102.A`)
  assert.equal(r.questions.length, 2)
  assert.match(r.questions[0].题干, /^【材料】.*5000 亿元/)
  assert.match(r.questions[1].题干, /^【材料】/)
})

test('JSON：英文字段、选项对象 / 平铺、数字下标（0 起）、嵌套容器、缺解析也收', () => {
  const j = JSON.stringify({ data: { list: [
    { question: 'Q1', options: { A: 'x', B: 'y', C: 'z' }, answer: 1, explanation: 'because' },
    { question: 'Q2', A: 'p', B: 'q', answer: 0 },
    { stem: 'Q3', choices: ['A. m', 'B. n'], correct_answer: 'b' },
    { title: 'Q4 没答案', options: ['a', 'b'] },
  ] } })
  const r = parseBankAny(j)
  assert.equal(r.format, 'json')
  assert.deepEqual(r.questions.map((q) => q.答案), ['B', 'A', 'B'])
  assert.deepEqual(r.questions[0].选项, ['x', 'y', 'z'])
  assert.deepEqual(r.questions[2].选项, ['m', 'n'])
  assert.equal(r.questions[0].解析, 'because')
  assert.equal(r.skipped[0].why, '缺答案')
  // 老的中文格式照样认
  const old = parseBankAny(JSON.stringify({ 题库版本: 1, id: 'b', 名称: 'n', 题目: [{ 题干: '老题', 选项: ['甲', '乙'], 答案: 'A', 解析: '甲对' }] }))
  assert.equal(old.questions[0].题干, '老题')
})

test('表格：CSV（带引号、逗号、换行）/ TSV（Excel 抽出来的）按表头认列', () => {
  const csv = '题目,A,B,C,D,答案,解析\n"含,逗号的题干","甲","乙","丙","丁",C,"多行\n解析"\n第二题,一,二,三,四,a,\n'
  const r = parseBankAny(csv)
  assert.equal(r.format, 'table')
  assert.equal(r.questions.length, 2)
  assert.equal(r.questions[0].题干, '含,逗号的题干')
  assert.equal(r.questions[0].答案, 'C')
  assert.match(r.questions[0].解析, /多行/)
  assert.equal(r.questions[1].答案, 'A')
  const tsv = 'question\toptionA\toptionB\tanswer\nQ\tyes\tno\tB\n'
  assert.equal(parseBankAny(tsv).questions[0].答案, 'B')
  assert.equal(parseDelimited('a,"b""c"\n').at(0)[1], 'b"c')
})

test('答案规整 / 同行选项拆分的边角', () => {
  assert.equal(normalizeAnswer('b、d'), 'BD')
  assert.equal(normalizeAnswer('正确答案是 C'), 'C')
  assert.equal(normalizeAnswer('√'), '正确')
  assert.equal(normalizeAnswer('对', 2), 'A')
  assert.equal(normalizeAnswer(3, 4), 'C')
  assert.deepEqual(splitInlineOptions('A.甲B.乙C.丙D.丁'), ['甲', '乙', '丙', '丁'])
  assert.deepEqual(splitInlineOptions('只是一句话 A. 没有 B'), [])
})

test('什么都认不出：说清楚怎么写（含未能识别出题目格式及编号式/---标签块样例）', () => {
  const r = parseBankAny('今天天气不错')
  assert.equal(r.ok, false)
  assert.match(r.error, /未能识别出题目格式/)
  assert.match(r.error, /题号和答案/)
  assert.match(r.error, /---/)
  assert.match(r.error, /【题目】/)
  const r2 = parseBankAny('1. 题一\nA. 甲\nB. 乙\n2. 题二\nA. 甲\nB. 乙')
  assert.match(r2.error, /都缺答案/)
})

test('UTF-8 BOM 剥离 + --- 分隔标签块格式（无数字题号、含【题型】【材料】【题目】【选项】【答案】【解析】及来源标识）', () => {
  const tagText = '\uFEFF' + [
    '【题型】言语理解',
    '【材料】近年来，数字经济与实体经济加速融合，成为高质量发展的关键引擎。',
    '【题目】（2024江苏A 29）这段文字意在强调：',
    '【选项】',
    'A. 数字经济已经完全取代实体经济',
    'B. 融合发展是高质量发展的重要动力',
    'C. 实体经济不再需要数字化转型',
    'D. 经济增长只依赖技术单点突破',
    '【答案】B',
    '【解析】文段围绕数字经济与实体经济融合推动高质量发展展开，B项契合主旨。',
    '---',
    '【题型】判断推理',
    '【题目】（2025国考 72）下列选项中与前两项逻辑一致的是：',
    'A. 甲项 B. 乙项 C. 丙项 D. 丁项',
    '【答案】C',
    '【解析】逐一匹配结构，只有丙项保持同一因果链。',
  ].join('\n')

  const r = parseBankAny(tagText)
  assert.equal(r.ok, true, JSON.stringify(r))
  assert.equal(r.format, 'tag-blocks')
  assert.equal(r.questions.length, 2)
  assert.equal(r.questions[0].题型, '言语理解')
  assert.match(r.questions[0].题干, /^【材料】近年来，数字经济/)
  assert.match(r.questions[0].题干, /（2024江苏A 29）这段文字意在强调：/)
  assert.equal(r.questions[0].选项.length, 4)
  assert.equal(r.questions[0].答案, 'B')
  assert.equal(r.questions[1].题型, '判断推理')
  assert.match(r.questions[1].题干, /^（2025国考 72）/)
  assert.deepEqual(r.questions[1].选项, ['甲项', '乙项', '丙项', '丁项'])
  assert.equal(r.questions[1].答案, 'C')
})

test('buildImportPreview：生成逐题预览并标出题干过短、选项不足、答案越界、缺解析等可疑问题', () => {
  const preview = buildImportPreview({
    ok: true,
    format: 'tag-blocks',
    questions: [
      { id: 'q-1', 题型: '常识', 题干: '完整且长度足够的题干内容', 选项: ['甲', '乙', '丙'], 答案: 'B', 解析: '因为乙正确' },
      { id: 'q-2', 题型: '选择', 题干: '太短了', 选项: ['只有一个'], 答案: 'D', 解析: '（题库没给解析）参考答案：D' },
    ],
  })
  assert.equal(preview.ok, true)
  assert.equal(preview.total, 2)
  assert.equal(preview.issueCount, 1)
  assert.deepEqual(preview.items[0].issues, [])
  assert.equal(preview.items[0].index, 1)
  assert.equal(preview.items[0].type, '常识')
  assert.ok(preview.items[1].issues.some((x) => x.includes('题干过短')))
  assert.ok(preview.items[1].issues.some((x) => x.includes('选项少于 2 个')))
  assert.ok(preview.items[1].issues.some((x) => x.includes('缺解析')))

  const outOfRange = buildImportPreview([{ 题干: '长度足够的测试题干在这里', 选项: ['甲', '乙'], 答案: 'D', 解析: '有解析' }])
  assert.ok(outOfRange.items[0].issues.some((x) => x.includes('答案不在现有选项字母范围内')))
})

test('混合形态（mcq + card + short_qa）与 Markdown/JSON 代码块及图片抽取', () => {
  assert.match(GEMINI_IMPORT_PROMPT_TEMPLATE, /"mcq"/)
  assert.match(GEMINI_IMPORT_PROMPT_TEMPLATE, /"card"/)
  assert.match(GEMINI_IMPORT_PROMPT_TEMPLATE, /"short_qa"/)

  // 1) Gemini 输出带 ```json 围栏与混合形态（card 允许无答案，short_qa 允许无选项）
  const fencedJson = '已为你整理今天的收集内容：\n```json\n' + JSON.stringify({
    名称: '今日思考沉淀',
    题目: [
      {
        kind: 'card',
        题干: '【核心规律】避免把手段当成目的，先看约束边界。',
        images: [{ path: 'vault/assets/boundary.png', role: 'stem', caption: '边界图' }],
      },
      {
        kind: 'short_qa',
        题干: '总结：随机化抽题的关键在保存历史。\n小问题：如何避免只看本轮 seenIds？',
        答案: '持久化 questionStats 并在解锁后不清空。',
        解析: '解锁只清当轮进度，统计与最近题号长期保留。',
      },
      {
        kind: 'mcq',
        题干: '下列哪项属于代码可判题目？',
        选项: ['主观感悟', '四选一客观题'],
        答案: 'B',
        解析: '有标准选项与字母答案时由代码直接判对错。',
      },
    ],
  }) + '\n```'

  const jRes = parseBankAny(fencedJson)
  assert.equal(jRes.ok, true, JSON.stringify(jRes))
  assert.equal(jRes.format, 'json')
  assert.equal(jRes.questions.length, 3)
  assert.equal(jRes.questions[0].kind, 'card')
  assert.equal(jRes.questions[0].答案, '已阅读掌握')
  assert.deepEqual(jRes.questions[0].images, [{ path: 'vault/assets/boundary.png', role: 'stem', caption: '边界图' }])
  assert.equal(jRes.questions[1].kind, 'short_qa')
  assert.equal(jRes.questions[1].选项, undefined)
  assert.equal(jRes.questions[2].kind, 'mcq')

  const jPrev = buildImportPreview(jRes)
  assert.equal(jPrev.issueCount, 0, JSON.stringify(jPrev.items))

  // 2) Markdown / 标签块混合导入 + ![caption](path) 图片解析
  const mdBlocks = [
    '【展示卡】一阶逻辑与因果链拆分 ![因果图](notes/causal.png)',
    '【解析】先找触发场景，再写干预动作。',
    '---',
    '【小问题】为什么 0.7 权重能防止锁屏自评刷分？',
    '【答案】因为只有代码判 4 选 1 才给满分 1 题进度。',
    '【解析】保持验收公平。',
    '---',
    'kind: mcq',
    '【题目】看图识趋势：下列判断正确的是？',
    '【图片】![趋势图](companion/bank-assets/trend.jpg)',
    'A. 持续上升',
    'B. 先升后降',
    '【答案】A',
    '【解析】图中斜率为正。',
  ].join('\n')

  const mRes = parseBankAny(mdBlocks)
  assert.equal(mRes.ok, true, JSON.stringify(mRes))
  assert.equal(mRes.questions.length, 3)
  assert.equal(mRes.questions[0].kind, 'card')
  assert.equal(mRes.questions[0].images[0].path, 'notes/causal.png')
  assert.equal(mRes.questions[0].images[0].caption, '因果图')
  assert.equal(mRes.questions[1].kind, 'short_qa')
  assert.equal(mRes.questions[2].kind, 'mcq')
  assert.equal(mRes.questions[2].images[0].path, 'companion/bank-assets/trend.jpg')

  // 3) 选项混进题干异常检测
  const mixedUp = detectQuestionAnomalies({
    kind: 'mcq',
    题干: '以下说法正确的是？A. 第一项 B. 第二项 C. 第三项',
    答案: 'A',
    解析: '有解析',
  })
  assert.ok(mixedUp.some((x) => x.includes('疑似选项混入题干')))
  assert.ok(mixedUp.some((x) => x.includes('选择题选项少于 2 个')))
})
