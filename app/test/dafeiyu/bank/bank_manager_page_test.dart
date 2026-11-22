import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bank_manager_page.dart';
import 'package:dsh_app/dafeiyu/ui/quiz_practice_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeBankManagerChannel extends ProactiveChannel {
  final List<Map<String, Object?>> calls = [];
  bool includeStats7d = true;

  final List<Map<String, dynamic>> banks = [
    {
      'id': 'bank-1',
      'name': '行测套卷一',
      'importedAt': 1763790000000,
      'sourceFormat': 'json',
      'enabled': true,
      'total': 5,
      'kindCounts': {'mcq': 3, 'card': 1, 'short_qa': 1},
      'seenCount': 3,
      'coveragePct': 60,
      'answered': 6,
      'correct': 5,
      'accuracyPct': 83,
      'anomalyCount': 0,
    },
  ];

  @override
  Future<Map<String, dynamic>?> reviewOp(Map<String, Object?> body) async => {
    'ok': true,
    'dueCount': 2,
    'totalCards': 8,
    'retention7d': 85,
  };

  @override
  Future<Map<String, dynamic>?> bankOp([Map<String, Object?>? body]) async {
    if (body == null) {
      return {
        'ok': true,
        'bankName': '行测套卷一',
        'bankTotal': 5,
        'wrongCount': 2,
        'config': {'requiredCount': 10, 'unlockMinutes': 5},
        'stats': {'answered': 12, 'correct': 10},
        'banks': banks,
        if (includeStats7d)
          'stats7d': {
            'todayAnswered': 4,
            'totalAnswered7d': 18,
            'accuracy7d': 83,
            'fsrsDueCount': 3,
            'wrongBookCount': 2,
            'starredCount': 5,
            'days': [
              {'day': '2026-11-16', 'label': '11-16', 'answered': 2},
              {'day': '2026-11-17', 'label': '11-17', 'answered': 3},
              {'day': '2026-11-18', 'label': '11-18', 'answered': 0},
              {'day': '2026-11-19', 'label': '11-19', 'answered': 4},
              {'day': '2026-11-20', 'label': '11-20', 'answered': 2},
              {'day': '2026-11-21', 'label': '11-21', 'answered': 3},
              {'day': '2026-11-22', 'label': '11-22', 'answered': 4},
            ],
          },
      };
    }
    calls.add(body);
    if (body['action'] == 'practice_session') {
      return {
        'ok': true,
        'questions': [
          {
            'id': 'q-sample',
            'bankId': body['bankId'] ?? 'bank-1',
            'bankName': '行测套卷一',
            'kind': (body['kind'] as String?)?.isNotEmpty == true ? body['kind'] : 'mcq',
            'type': '专项题',
            'stem': '测试专项刷题入口打开成功',
            'options': ['选项甲', '选项乙'],
            'answer': 'A',
            'explain': '甲正确',
            'images': <Object>[],
            'starred': false,
            'inWrongBook': false,
            'streakCorrect': 0,
            'dueAt': 0,
          },
        ],
      };
    }
    return {'ok': true};
  }
}

void main() {
  testWidgets('题库管理页展示 7 日刷题统计并可从 4 个快捷入口直达 QuizPracticePage', (tester) async {
    final ch = _FakeBankManagerChannel();
    await pumpPhone(tester, BankManagerPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('bank-7d-stats-card')), findsOneWidget);
    expect(find.text('4 题'), findsOneWidget);
    expect(find.text('18 题'), findsOneWidget);
    expect(find.text('3 题'), findsOneWidget);
    expect(find.text('2 / 5'), findsOneWidget);
    expect(find.text('近 7 日正确率 83%'), findsOneWidget);

    Future<void> tapQuickAndVerifyScope(String key, String expectedScope) async {
      ch.calls.clear();
      await tester.ensureVisible(find.byKey(ValueKey(key)));
      await tester.tap(find.byKey(ValueKey(key)));
      await settlePhone(tester);
      await tester.pumpAndSettle();

      expect(find.byType(QuizPracticePage), findsOneWidget);
      expect(
        ch.calls.any((c) => c['action'] == 'practice_session' && c['scope'] == expectedScope),
        isTrue,
      );
      await popPage(tester);
      await tester.pumpAndSettle();
    }

    await tapQuickAndVerifyScope('bank-quick-all', 'all');
    await tapQuickAndVerifyScope('bank-quick-wrong', 'wrong_book');
    await tapQuickAndVerifyScope('bank-quick-fsrs', 'fsrs_due');
    await tapQuickAndVerifyScope('bank-quick-starred', 'starred');
  });

  testWidgets('每套题库卡片支持「单刷这套题」与按题型（选择/卡片/小问题）直达专练，且兼容无 stats7d 旧响应', (tester) async {
    final ch = _FakeBankManagerChannel()..includeStats7d = false;
    await pumpPhone(tester, BankManagerPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('bank-7d-stats-card')), findsOneWidget);
    expect(find.text('近 7 日正确率 83%'), findsOneWidget); // fallback from stats.correct / stats.answered

    // 单刷这套题
    await tester.ensureVisible(find.byKey(const ValueKey('bank-practice-bank-1')));
    await tester.tap(find.byKey(const ValueKey('bank-practice-bank-1')));
    await settlePhone(tester);
    await tester.pumpAndSettle();
    expect(find.byType(QuizPracticePage), findsOneWidget);
    expect(
      ch.calls.any((c) => c['action'] == 'practice_session' && c['bankId'] == 'bank-1' && !c.containsKey('kind')),
      isTrue,
    );
    await popPage(tester);
    await tester.pumpAndSettle();

    // 只刷选择 / 只刷卡片 / 只刷小问题
    for (final kind in const ['mcq', 'card', 'short_qa']) {
      ch.calls.clear();
      await tester.ensureVisible(find.byKey(ValueKey('bank-practice-bank-1-$kind')));
      await tester.tap(find.byKey(ValueKey('bank-practice-bank-1-$kind')));
      await settlePhone(tester);
      await tester.pumpAndSettle();
      expect(find.byType(QuizPracticePage), findsOneWidget);
      expect(
        ch.calls.any((c) => c['action'] == 'practice_session' && c['bankId'] == 'bank-1' && c['kind'] == kind),
        isTrue,
      );
      await popPage(tester);
      await tester.pumpAndSettle();
    }
  });
}
