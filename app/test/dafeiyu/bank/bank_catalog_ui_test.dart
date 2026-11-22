import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bank_manager_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeCatalogChannel extends ProactiveChannel {
  final List<Map<String, Object?>> calls = [];
  List<Map<String, dynamic>> banks = [
    {
      'id': 'bank-today',
      'name': '今日收集混合卡',
      'importedAt': 1763790000000,
      'sourceFormat': 'json',
      'enabled': true,
      'total': 3,
      'kindCounts': {'mcq': 1, 'card': 1, 'short_qa': 1},
      'seenCount': 2,
      'coveragePct': 67,
      'answered': 4,
      'correct': 3,
      'accuracyPct': 75,
      'anomalyCount': 1,
    },
  ];
  List<Map<String, dynamic>> questions = [
    {
      'id': 'bank-today:q-1',
      'index': 1,
      'kind': 'card',
      'type': '知识卡',
      'stem': '【知识卡】拆解任务先定约束边界',
      'options': <String>[],
      'answer': '已阅读掌握',
      'analysis': '先列不变契约再实现',
      'images': [
        {'path': '/nonexistent/path/diagram.png', 'role': 'stem', 'caption': '边界示意图'},
      ],
      'inWrongPool': false,
      'anomalyReasons': <String>[],
      'stats': {'seenCount': 2, 'correctCount': 2, 'wrongCount': 0, 'lastSeenAt': 1763790100000},
    },
    {
      'id': 'bank-today:q-2',
      'index': 2,
      'kind': 'short_qa',
      'type': '小问题',
      'stem': '小问题：为什么解锁后不能清空 questionStats？',
      'options': <String>[],
      'answer': '保留历史记录才能防止次轮从第 1 题重出',
      'analysis': '三层优先抽题依赖 seenCount 与 lastSeenAt',
      'images': <Object>[],
      'inWrongPool': true,
      'anomalyReasons': <String>[],
      'stats': {'seenCount': 1, 'correctCount': 0, 'wrongCount': 1, 'lastSeenAt': 1763790200000},
    },
    {
      'id': 'bank-today:q-3',
      'index': 3,
      'kind': 'mcq',
      'type': '选择',
      'stem': '选项混入 A. 甲 B. 乙 C. 丙',
      'options': <String>[],
      'answer': 'A',
      'analysis': '',
      'images': <Object>[],
      'inWrongPool': false,
      'anomalyReasons': ['疑似选项混入题干', '选择题选项少于 2 个', '缺解析'],
      'stats': {'seenCount': 0, 'correctCount': 0, 'wrongCount': 0, 'lastSeenAt': 0},
    },
  ];

  @override
  Future<Map<String, dynamic>?> reviewOp(Map<String, Object?> body) async => {
        'ok': true,
        'dueCount': 1,
        'totalCards': 3,
      };

  @override
  Future<Map<String, dynamic>?> bankOp([Map<String, Object?>? body]) async {
    if (body == null) {
      final total = banks.where((b) => b['enabled'] != false).fold<int>(0, (s, b) => s + (b['total'] as int));
      return {
        'ok': true,
        'bankName': banks.isEmpty ? '未导入' : banks.first['name'],
        'bankTotal': total,
        'wrongCount': questions.where((q) => q['inWrongPool'] == true).length,
        'config': {'requiredCount': 10, 'unlockMinutes': 5},
        'stats': {'answered': 4, 'correct': 3},
        'banks': banks,
      };
    }
    calls.add(body);
    final action = body['action'];
    if (action == 'toggle_bank') {
      final id = body['bankId'];
      final en = body['enabled'] == true;
      banks = [
        for (final b in banks)
          if (b['id'] == id) {...b, 'enabled': en} else b,
      ];
      return {'ok': true, 'banks': banks};
    }
    if (action == 'get_bank') {
      return {'ok': true, 'bank': banks.first, 'questions': questions};
    }
    if (action == 'delete_question') {
      final qid = body['questionId'];
      questions = questions.where((q) => q['id'] != qid).toList();
      banks = [
        {...banks.first, 'total': questions.length},
      ];
      return {'ok': true, 'bank': banks.first};
    }
    if (action == 'delete_bank') {
      banks = [];
      questions = [];
      return {'ok': true};
    }
    if (action == 'gemini_template') {
      return {'ok': true, 'template': 'GEMINI_PROMPT_TEMPLATE_OK'};
    }
    return {'ok': true};
  }
}

void main() {
  testWidgets('多题库目录列表展示、停用开关与复制 Gemini 模板', (tester) async {
    String? clipboardText;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          clipboardText = (call.arguments as Map?)?['text'] as String?;
        }
        return null;
      },
    );

    final ch = _FakeCatalogChannel();
    await pumpPhone(tester, BankManagerPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('bank-catalog-section')), findsOneWidget);
    expect(find.text('今日收集混合卡'), findsOneWidget);
    expect(find.text('选择 1'), findsOneWidget);
    expect(find.text('展示卡 1'), findsOneWidget);
    expect(find.text('小问题 1'), findsOneWidget);
    expect(find.text('⚠ 格式异常 1 题'), findsOneWidget);
    expect(find.textContaining('已刷覆盖 67% (2/3) · 正确率 75%'), findsOneWidget);

    // 切换启用开关 -> toggle_bank
    await tester.tap(find.byKey(const ValueKey('bank-toggle-bank-today')));
    await settlePhone(tester);
    expect(ch.calls.any((c) => c['action'] == 'toggle_bank' && c['enabled'] == false), isTrue);
    expect(find.text('已停用'), findsOneWidget);

    // 复制 Gemini 混合知识卡模板
    await tester.ensureVisible(find.byKey(const ValueKey('bank-copy-gemini-template')));
    await tester.tap(find.byKey(const ValueKey('bank-copy-gemini-template')));
    await settlePhone(tester);
    expect(ch.calls.any((c) => c['action'] == 'gemini_template'), isTrue);
    expect(clipboardText, 'GEMINI_PROMPT_TEMPLATE_OK');
  });

  testWidgets('题库详情页：按全部/错题/格式异常筛选、安全图片占位、单题与整套删除', (tester) async {
    final ch = _FakeCatalogChannel();
    await pumpPhone(tester, BankManagerPage(channel: ch));
    await settlePhone(tester);

    await tester.tap(find.byKey(const ValueKey('bank-catalog-item-bank-today')));
    await settlePhone(tester);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('bank-detail-question-bank-today:q-1')), findsOneWidget);
    expect(find.textContaining('图片占位：边界示意图'), findsOneWidget);

    // 筛选错题
    await tester.tap(find.byKey(const ValueKey('bank-detail-filter-wrong')));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('bank-detail-question-bank-today:q-1')), findsNothing);
    expect(find.byKey(const ValueKey('bank-detail-question-bank-today:q-2')), findsOneWidget);

    // 筛选格式异常
    await tester.tap(find.byKey(const ValueKey('bank-detail-filter-anomaly')));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('bank-detail-question-bank-today:q-3')), findsOneWidget);
    expect(find.text('疑似选项混入题干'), findsOneWidget);

    // 删除单题
    await tester.tap(find.byKey(const ValueKey('bank-detail-delete-q-bank-today:q-3')));
    await settlePhone(tester);
    expect(ch.calls.any((c) => c['action'] == 'delete_question' && c['questionId'] == 'bank-today:q-3'), isTrue);
    expect(find.byKey(const ValueKey('bank-detail-question-bank-today:q-3')), findsNothing);

    // 删除整套题库并弹出确认框
    await tester.tap(find.byKey(const ValueKey('bank-detail-delete-bank')));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('bank-detail-confirm-delete-bank')));
    await settlePhone(tester);
    await tester.pumpAndSettle();

    expect(ch.calls.any((c) => c['action'] == 'delete_bank' && c['bankId'] == 'bank-today'), isTrue);
    expect(find.byKey(const ValueKey('bank-catalog-section')), findsOneWidget);
    expect(find.text('已导入题库列表（0 套）'), findsOneWidget);
  });
}
