// 题库导入第二版：真机上导不进去的那几种写法，一种一条。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { normalizeAnswer, parseBankAny, parseDelimited, splitInlineOptions } from '../lib/bank_import.js'

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

test('什么都认不出：说清楚怎么写', () => {
  const r = parseBankAny('今天天气不错')
  assert.equal(r.ok, false)
  assert.match(r.error, /题号和答案/)
  const r2 = parseBankAny('1. 题一\nA. 甲\nB. 乙\n2. 题二\nA. 甲\nB. 乙')
  assert.match(r2.error, /都缺答案/)
})
