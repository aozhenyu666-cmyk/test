import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:dsh_app/dafeiyu/bank/bank_text_extract.dart';
import 'package:flutter_test/flutter_test.dart';

Uint8List _zip(Map<String, String> files) {
  final a = Archive();
  files.forEach((k, v) => a.add(ArchiveFile.bytes(k, utf8.encode(v))));
  return Uint8List.fromList(ZipEncoder().encode(a));
}

void main() {
  test('docx：段落换行、表格单元格用制表符、实体解码', () {
    final doc = _zip({
      'word/document.xml': '<w:document><w:body>'
          '<w:p><w:r><w:t>1. 下列属于 &lt;扩张性&gt; 政策的是</w:t></w:r></w:p>'
          '<w:p><w:r><w:t>A. 加税</w:t></w:r><w:r><w:tab/><w:t>B. 增支</w:t></w:r></w:p>'
          '<w:tbl><w:tr><w:tc><w:p><w:t>答案</w:t></w:p></w:tc><w:tc><w:p><w:t>B</w:t></w:p></w:tc></w:tr></w:tbl>'
          '</w:body></w:document>',
    });
    final r = extractBankText('真题.docx', doc);
    expect(r.problem, isNull);
    expect(r.text, contains('1. 下列属于 <扩张性> 政策的是\n'));
    expect(r.text, contains('A. 加税\tB. 增支'));
    expect(r.text, contains('答案'));
  });

  test('xlsx：sharedStrings + 第一张表 → 制表符表格（空单元格占位）', () {
    final x = _zip({
      'xl/sharedStrings.xml': '<sst><si><t>题目</t></si><si><t>A</t></si><si><t>B</t></si><si><t>答案</t></si>'
          '<si><t>第一题</t></si><si><r><t>甲</t></r><r><t>乙</t></r></si></sst>',
      'xl/worksheets/sheet1.xml': '<worksheet><sheetData>'
          '<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>'
          '<row r="2"><c r="A2" t="s"><v>4</v></c><c r="B2" t="s"><v>5</v></c><c r="D2" t="inlineStr"><is><t>B</t></is></c></row>'
          '</sheetData></worksheet>',
    });
    final r = extractBankText('题库.xlsx', x);
    expect(r.problem, isNull);
    expect(r.text.split('\n').first, '题目\tA\tB\t答案');
    expect(r.text.split('\n')[1], '第一题\t甲乙\t\tB');
  });

  test('PDF / 老 doc / 坏 docx / GBK 文本：说清楚怎么办', () {
    expect(extractBankText('a.pdf', Uint8List(4)).problem, contains('另存为'));
    expect(extractBankText('a.doc', Uint8List(4)).problem, contains('.docx'));
    expect(extractBankText('a.docx', Uint8List.fromList([1, 2, 3])).problem, anyOf(contains('打不开'), contains('找不到正文')));
    final gbkLike = Uint8List.fromList(List.generate(400, (i) => i.isEven ? 0xC4 : 0xE3));
    expect(extractBankText('a.csv', gbkLike).problem, contains('GBK'));
    expect(extractBankText('a.txt', Uint8List.fromList(utf8.encode('1. 题\n答案：A'))).problem, isNull);
  });

  test('分块：拼回去一字不差，不切断 emoji', () {
    final text = '题' * 7000 + '😀' + 'x' * 10;
    final parts = chunkText(text, maxChars: 3000);
    expect(parts.join(), text);
    for (final p in parts) {
      final last = p.codeUnitAt(p.length - 1);
      expect(last >= 0xD800 && last <= 0xDBFF, isFalse);
    }
    final edge = chunkText('${'a' * 2999}😀b', maxChars: 3000);
    expect(edge.join(), '${'a' * 2999}😀b');
    expect(edge.first.length, 2999);
  });
}
