import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bank_manager_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeBankChannel extends ProactiveChannel {
  final List<Map<String, Object?>> calls = [];
  int bankTotal = 10;
  Map<String, dynamic>? nextPreview;
  Map<String, dynamic>? nextCommit;

  @override
  Future<Map<String, dynamic>?> reviewOp(Map<String, Object?> body) async => {
        'ok': true,
        'dueCount': 2,
        'totalCards': 5,
      };

  @override
  Future<Map<String, dynamic>?> bankOp([Map<String, Object?>? body]) async {
    if (body == null) {
      return {
        'ok': true,
        'bankName': '考公真题库',
        'bankTotal': bankTotal,
        'wrongCount': 1,
        'config': {'requiredCount': 10, 'unlockMinutes': 5},
        'stats': {'answered': 20, 'correct': 18},
      };
    }
    calls.add(body);
    final action = body['action'];
    if (action == 'import-begin') {
      return {'ok': true, 'uploadId': body['uploadId']};
    }
    if (action == 'import-chunk') {
      return {'ok': true};
    }
    if ((action == 'import' || action == 'import-finish') && body['previewOnly'] == true) {
      return nextPreview ??
          {
            'ok': true,
            'format': '标签块',
            'count': 2,
            'skippedCount': 1,
            'skipped': [
              {'n': 3, 'why': '缺少题干'},
            ],
            'uploadId': body['uploadId'],
            'items': [
              {
                'index': 1,
                'type': '言语理解',
                'stem': '体系化思考的核心在于提炼可迁移的因果链。',
                'options': ['A. 摘要', 'B. 因果链', 'C. 列表', 'D. 情绪'],
                'answer': 'B',
                'analysis': '因果链可迁移复利。',
                'issues': <String>[],
              },
              {
                'index': 2,
                'type': '常识判断',
                'stem': '测试可疑题',
                'options': ['A. 甲', 'B. 乙'],
                'answer': 'D',
                'analysis': '',
                'issues': ['答案不在选项范围内', '缺解析'],
              },
            ],
          };
    }
    if (action == 'import' || (action == 'import-finish' && body['previewOnly'] == false)) {
      bankTotal += 2;
      return nextCommit ??
          {
            'ok': true,
            'importedCount': 2,
            'bankTotal': bankTotal,
            'skippedCount': 0,
            'skipped': const <Object>[],
          };
    }
    return {'ok': true};
  }
}

void main() {
  testWidgets('题库导入：小文本先预览、标红可疑题、确认后才真正入库', (tester) async {
    final ch = _FakeBankChannel();
    await pumpPhone(tester, BankManagerPage(channel: ch));
    await settlePhone(tester);

    await tester.enterText(find.byKey(const ValueKey('bank-raw-input')), '1. 题干\nA. 甲\nB. 乙\n答案：B');
    await tester.ensureVisible(find.byKey(const ValueKey('bank-import-submit')));
    await tester.tap(find.byKey(const ValueKey('bank-import-submit')));
    await settlePhone(tester);
    await tester.pumpAndSettle();

    expect(ch.calls.length, 1);
    expect(ch.calls.single['action'], 'import');
    expect(ch.calls.single['previewOnly'], isTrue);
    expect(ch.bankTotal, 10, reason: '预览阶段不能直接入库');

    expect(find.text('导入前预览'), findsOneWidget);
    expect(find.text('共 2 题 · 可疑 1 题 · 跳过 1 题'), findsOneWidget);
    expect(find.text('答案不在选项范围内'), findsOneWidget);
    expect(find.text('缺解析'), findsOneWidget);

    // 仅看可疑过滤
    await tester.tap(find.byKey(const ValueKey('bank-preview-filter-suspect')));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('bank-preview-item-1')), findsNothing);
    expect(find.byKey(const ValueKey('bank-preview-item-2')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('bank-preview-confirm')));
    await settlePhone(tester);

    expect(ch.calls.length, 2);
    expect(ch.calls.last['action'], 'import');
    expect(ch.calls.last.containsKey('previewOnly'), isFalse);
    expect(ch.bankTotal, 12);
    expect(find.textContaining('进了 2 道，现在共 12 题'), findsOneWidget);
  });

  testWidgets('题库导入：预览点取消不入库', (tester) async {
    final ch = _FakeBankChannel();
    await pumpPhone(tester, BankManagerPage(channel: ch));
    await settlePhone(tester);

    await tester.enterText(find.byKey(const ValueKey('bank-raw-input')), '1. 题干\nA. 甲\nB. 乙\n答案：A');
    await tester.ensureVisible(find.byKey(const ValueKey('bank-import-submit')));
    await tester.tap(find.byKey(const ValueKey('bank-import-submit')));
    await settlePhone(tester);
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('bank-preview-cancel')));
    await settlePhone(tester);

    expect(ch.calls.length, 1, reason: '取消后只发生过预览调用');
    expect(ch.bankTotal, 10);
  });

  testWidgets('题库导入：大文本分块先 previewOnly:true 再 previewOnly:false 入库', (tester) async {
    final ch = _FakeBankChannel();
    await pumpPhone(tester, BankManagerPage(channel: ch));
    await settlePhone(tester);

    final bigText = '题' * 5000; // UTF-8 > 10KB
    await tester.enterText(find.byKey(const ValueKey('bank-raw-input')), bigText);
    await tester.ensureVisible(find.byKey(const ValueKey('bank-import-submit')));
    await tester.tap(find.byKey(const ValueKey('bank-import-submit')));
    await settlePhone(tester);
    await tester.pumpAndSettle();

    expect(ch.calls.any((c) => c['action'] == 'import-begin'), isTrue);
    expect(ch.calls.any((c) => c['action'] == 'import-chunk'), isTrue);
    final previewFinish = ch.calls.lastWhere((c) => c['action'] == 'import-finish');
    expect(previewFinish['previewOnly'], isTrue);

    await tester.tap(find.byKey(const ValueKey('bank-preview-confirm')));
    await settlePhone(tester);

    final commitFinish = ch.calls.last;
    expect(commitFinish['action'], 'import-finish');
    expect(commitFinish['previewOnly'], isFalse);
    expect(commitFinish['uploadId'], previewFinish['uploadId']);
  });

  testWidgets('题库导入：识别失败/0 题展示 error 与样例，并支持一键填入样例', (tester) async {
    final ch = _FakeBankChannel()
      ..nextPreview = {
        'ok': false,
        'count': 0,
        'error': '未能识别出题目格式',
        'sample': '---\n【题目】示例题干\n【答案】A',
      };
    await pumpPhone(tester, BankManagerPage(channel: ch));
    await settlePhone(tester);

    await tester.enterText(find.byKey(const ValueKey('bank-raw-input')), '乱七八糟的内容');
    await tester.ensureVisible(find.byKey(const ValueKey('bank-import-submit')));
    await tester.tap(find.byKey(const ValueKey('bank-import-submit')));
    await settlePhone(tester);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('bank-import-error-dialog')), findsOneWidget);
    expect(find.text('未能识别出题目格式'), findsOneWidget);
    expect(find.text('---\n【题目】示例题干\n【答案】A'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('bank-error-use-sample')));
    await settlePhone(tester);

    final field = tester.widget<TextField>(find.byKey(const ValueKey('bank-raw-input')));
    expect(field.controller?.text, '---\n【题目】示例题干\n【答案】A');
  });
}
