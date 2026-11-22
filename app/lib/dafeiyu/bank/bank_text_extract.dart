import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';

/// 题库文件 → 纯文本（插件那边按题号 / 答案区 / 表头再解析）。
///
/// - .docx：解开 zip 读 word/document.xml，段落换行、表格单元格之间用制表符。
/// - .xlsx：读 sharedStrings + 第一张工作表，抽成制表符分隔的表格（第一行是表头）。
/// - .json / .txt / .md / .csv / .tsv：按 UTF-8 读；乱码太多（多半是 GBK 存的）就说清楚。
/// - .pdf / .doc（老 Word）：读不了文字，叫他用 WPS 另存为 .docx / .txt。
class BankText {
  const BankText(this.text, {this.problem});

  final String text;

  /// 读不了 / 可能读错时给他看的一句话；正常时为 null。
  final String? problem;
}

BankText extractBankText(String name, Uint8List bytes) {
  final lower = name.toLowerCase();
  if (lower.endsWith('.pdf')) {
    return const BankText('', problem: 'PDF 读不出文字：用 WPS 打开后「另存为」.docx 或 .txt 再导');
  }
  if (lower.endsWith('.doc')) {
    return const BankText('', problem: '老版 .doc 读不了：用 WPS / Word 另存为 .docx 再导');
  }
  if (lower.endsWith('.docx')) return _docx(bytes);
  if (lower.endsWith('.xlsx')) return _xlsx(bytes);
  final text = utf8.decode(bytes, allowMalformed: true);
  final bad = '�'.allMatches(text).length;
  if (text.isNotEmpty && bad > 20 && bad > text.length / 100) {
    return BankText(text, problem: '这个文件不像 UTF-8（多半是 GBK 存的），中文会乱：在 Excel / 记事本里「另存为」UTF-8 再导');
  }
  return BankText(text);
}

String _unescape(String s) => s
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'")
    .replaceAllMapped(RegExp(r'&#(x?)([0-9a-fA-F]+);'), (m) {
      final code = int.tryParse(m[2]!, radix: m[1] == 'x' ? 16 : 10);
      return code == null ? '' : String.fromCharCode(code);
    })
    .replaceAll('&amp;', '&');

String? _entry(Archive zip, String path) {
  final f = zip.findFile(path);
  final b = f?.readBytes();
  return b == null ? null : utf8.decode(b, allowMalformed: true);
}

BankText _docx(Uint8List bytes) {
  try {
    final zip = ZipDecoder().decodeBytes(bytes);
    final xml = _entry(zip, 'word/document.xml');
    if (xml == null) return const BankText('', problem: '这个 .docx 里找不到正文');
    final text = xml
        .replaceAll(RegExp(r'<w:tab[^>]*/>'), '\t')
        .replaceAll(RegExp(r'<w:br[^>]*/>'), '\n')
        .replaceAll(RegExp(r'</w:tc>'), '\t')
        .replaceAll(RegExp(r'</w:p>'), '\n')
        .replaceAll(RegExp(r'<[^>]+>'), '');
    return BankText(_unescape(text).replaceAll(RegExp(r'\n{3,}'), '\n\n'));
  } on Object {
    return const BankText('', problem: '这个 .docx 打不开（文件坏了，或者其实不是 docx）');
  }
}

BankText _xlsx(Uint8List bytes) {
  try {
    final zip = ZipDecoder().decodeBytes(bytes);
    final shared = <String>[];
    final ss = _entry(zip, 'xl/sharedStrings.xml');
    if (ss != null) {
      for (final si in RegExp(r'<si>([\s\S]*?)</si>').allMatches(ss)) {
        final t = RegExp(r'<t[^>]*>([\s\S]*?)</t>').allMatches(si[1]!).map((m) => m[1]!).join();
        shared.add(_unescape(t));
      }
    }
    final sheetPath = zip.files.map((f) => f.name).where((n) => RegExp(r'^xl/worksheets/sheet\d+\.xml$').hasMatch(n)).toList()..sort();
    if (sheetPath.isEmpty) return const BankText('', problem: '这个 .xlsx 里没有工作表');
    final sheet = _entry(zip, sheetPath.first)!;
    final out = StringBuffer();
    for (final row in RegExp(r'<row[^>]*>([\s\S]*?)</row>').allMatches(sheet)) {
      final cells = <int, String>{};
      var maxCol = -1;
      for (final c in RegExp(r'<c ([^>]*?)(?:/>|>([\s\S]*?)</c>)').allMatches(row[1]!)) {
        final attrs = c[1]!;
        final ref = RegExp(r'r="([A-Z]+)\d+"').firstMatch(attrs)?[1] ?? '';
        var col = 0;
        for (final ch in ref.codeUnits) {
          col = col * 26 + (ch - 64);
        }
        col -= 1;
        if (col < 0) col = maxCol + 1;
        final body = c[2] ?? '';
        final type = RegExp(r't="(\w+)"').firstMatch(attrs)?[1] ?? '';
        String value;
        if (type == 's') {
          final i = int.tryParse(RegExp(r'<v>([\s\S]*?)</v>').firstMatch(body)?[1] ?? '');
          value = i != null && i < shared.length ? shared[i] : '';
        } else if (type == 'inlineStr') {
          value = _unescape(RegExp(r'<t[^>]*>([\s\S]*?)</t>').allMatches(body).map((m) => m[1]!).join());
        } else {
          value = _unescape(RegExp(r'<v>([\s\S]*?)</v>').firstMatch(body)?[1] ?? '');
        }
        cells[col] = value.replaceAll(RegExp(r'[\t\r\n]+'), ' ').trim();
        if (col > maxCol) maxCol = col;
      }
      if (maxCol < 0) continue;
      out.writeln([for (var i = 0; i <= maxCol; i++) cells[i] ?? ''].join('\t'));
    }
    return BankText(out.toString());
  } on Object {
    return const BankText('', problem: '这个 .xlsx 打不开（文件坏了，或者其实不是 xlsx）');
  }
}

/// 按字符切块（每块 UTF-8 不超过 ~9 KB，加上 JSON 转义也在引擎 16 KB 的上限以内）。
List<String> chunkText(String text, {int maxChars = 3000}) {
  final out = <String>[];
  var i = 0;
  while (i < text.length) {
    var end = i + maxChars > text.length ? text.length : i + maxChars;
    // 别把一个 emoji（代理对）切成两半
    if (end < text.length && end > i + 1) {
      final u = text.codeUnitAt(end - 1);
      if (u >= 0xD800 && u <= 0xDBFF) end -= 1;
    }
    out.add(text.substring(i, end));
    i = end;
  }
  return out;
}
