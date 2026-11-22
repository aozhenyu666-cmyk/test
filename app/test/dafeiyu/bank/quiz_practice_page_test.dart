import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/quiz_practice_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakePracticeChannel extends ProactiveChannel {
  final List<Map<String, Object?>> calls = [];
  bool returnEmpty = false;
  final Set<String> starred = {'q-2'};
  final Map<String, int> streaks = {'q-1': 1};
  final Set<String> wrongBook = {'q-1'};

  List<Map<String, dynamic>> get _sampleQuestions => [
    {
      'id': 'q-1',
      'bankId': 'bank-a',
      'bankName': '行测精选',
      'kind': 'mcq',
      'type': '数量关系',
      'stem': '去年产量 400 吨，今年增长 25%，今年多少吨？',
      'options': ['450 吨', '500 吨', '520 吨', '550 吨'],
      'answer': 'B',
      'explain': '400 × 1.25 = 500 吨',
      'images': [
        {'path': '/nonexistent/chart.png', 'role': 'stem', 'caption': '产量折线图'},
      ],
      'starred': starred.contains('q-1'),
      'inWrongBook': wrongBook.contains('q-1'),
      'streakCorrect': streaks['q-1'] ?? 0,
      'dueAt': 0,
    },
    {
      'id': 'q-2',
      'bankId': 'bank-a',
      'bankName': '行测精选',
      'kind': 'card',
      'type': '知识卡',
      'stem': '【核心规律】两期比重比较只看部分增速 a 与整体增速 b 的大小关系。',
      'options': <String>[],
      'answer': 'a > b 则上升，a < b 则下降',
      'explain': '直接比分子分母增速差即可秒杀。',
      'images': <Object>[],
      'starred': starred.contains('q-2'),
      'inWrongBook': wrongBook.contains('q-2'),
      'streakCorrect': streaks['q-2'] ?? 0,
      'dueAt': 0,
    },
    {
      'id': 'q-3',
      'bankId': 'bank-a',
      'bankName': '行测精选',
      'kind': 'short_qa',
      'type': '小问题',
      'stem': '要点：警惕偷换范围。\n思考：遇到绝对化选项第一步做什么？',
      'options': <String>[],
      'answer': '回到原文定位句核对限定条件',
      'explain': '先找主体与范围词，再判断真假。',
      'images': <Object>[],
      'starred': starred.contains('q-3'),
      'inWrongBook': wrongBook.contains('q-3'),
      'streakCorrect': streaks['q-3'] ?? 0,
      'dueAt': 0,
    },
  ];

  @override
  Future<Map<String, dynamic>?> bankOp([Map<String, Object?>? body]) async {
    if (body != null) calls.add(body);
    final action = body?['action'];
    if (action == 'practice_session') {
      if (returnEmpty) {
        return {'ok': true, 'questions': <Object>[]};
      }
      return {
        'ok': true,
        'bankId': body?['bankId'] ?? '',
        'scope': body?['scope'] ?? 'all',
        'questions': _sampleQuestions,
      };
    }
    if (action == 'toggle_star') {
      final qid = '${body?['questionId'] ?? ''}';
      final want = body?['starred'] is bool ? body!['starred'] == true : !starred.contains(qid);
      if (want) {
        starred.add(qid);
      } else {
        starred.remove(qid);
      }
      return {'ok': true, 'questionId': qid, 'starred': want};
    }
    if (action == 'submit_practice_answer') {
      final qid = '${body?['questionId'] ?? ''}';
      final ans = '${body?['answer'] ?? ''}'.trim().toUpperCase();
      final q = _sampleQuestions.firstWhere((e) => e['id'] == qid);
      final kind = '${q['kind']}';
      final bool correct;
      if (kind == 'mcq') {
        correct = ans == '${q['answer']}'.toUpperCase();
      } else {
        correct = ans == 'KNOWN' || ans == 'REMEMBER';
      }
      final wasWrong = wrongBook.contains(qid);
      final nextStreak = correct ? (streaks[qid] ?? 0) + 1 : 0;
      streaks[qid] = nextStreak;
      final nextInWrong = correct ? (wasWrong && nextStreak < 2) : true;
      if (nextInWrong) {
        wrongBook.add(qid);
      } else {
        wrongBook.remove(qid);
      }
      return {
        'ok': true,
        'questionId': qid,
        'kind': kind,
        'correct': correct,
        'selfJudged': kind != 'mcq',
        'expectedAnswer': q['answer'],
        'explain': q['explain'],
        'rating': correct ? 3 : 1,
        'streakCorrect': nextStreak,
        'inWrongBook': nextInWrong,
        'addedToWrongBook': !correct && !wasWrong,
        'graduatedFromWrongBook': correct && wasWrong && !nextInWrong,
        'scheduledDays': correct ? 3 : 0,
        'nextDueAt': 1763800000000,
      };
    }
    return {'ok': true};
  }
}

void main() {
  testWidgets('全屏刷题页：mcq/card/short_qa 流畅做题、星标收藏、错题毕业提示、答题卡跳转与战报重刷错题', (tester) async {
    final ch = _FakePracticeChannel();
    await pumpPhone(tester, QuizPracticePage(channel: ch, scope: 'all'), size: const Size(390, 900));
    await settlePhone(tester);

    // 第 1 题：MCQ + 安全图片占位 + 错题连对进度
    expect(find.byKey(const ValueKey('practice-progress-text')), findsOneWidget);
    expect(find.text('1 / 3'), findsOneWidget);
    expect(find.textContaining('产量折线图'), findsOneWidget);
    expect(find.text('错题本 · 连对 1/2'), findsOneWidget);

    // 收藏第 1 题 -> toggle_star
    await tester.tap(find.byKey(const ValueKey('practice-star-btn')));
    await settlePhone(tester);
    expect(ch.calls.any((c) => c['action'] == 'toggle_star' && c['questionId'] == 'q-1' && c['starred'] == true), isTrue);

    // 点击 B 选项答对第 1 题 -> 触发连对 2 次从错题本毕业！
    await tester.tap(find.byKey(const ValueKey('practice-mcq-opt-1')));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('practice-feedback-card')), findsOneWidget);
    expect(find.text('答对了！'), findsOneWidget);
    expect(find.textContaining('已自动从错题本毕业'), findsOneWidget);
    expect(find.textContaining('FSRS 下次复习：3 天后'), findsOneWidget);

    // 下一题 -> 第 2 题：展示卡 card
    await tester.tap(find.byKey(const ValueKey('practice-next-btn')));
    await settlePhone(tester);
    expect(find.text('2 / 3'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('practice-card-reveal')));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('practice-card-anchor-box')), findsOneWidget);
    expect(find.text('a > b 则上升，a < b 则下降'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('practice-card-known')));
    await settlePhone(tester);
    expect(find.textContaining('已掌握'), findsOneWidget);

    // 下一题 -> 第 3 题：小问题 short_qa（自评没答上）
    await tester.tap(find.byKey(const ValueKey('practice-next-btn')));
    await settlePhone(tester);
    expect(find.text('3 / 3'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('practice-qa-reveal')));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('practice-qa-answer-box')), findsOneWidget);
    expect(find.text('回到原文定位句核对限定条件'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('practice-qa-missed')));
    await settlePhone(tester);
    expect(find.textContaining('还不熟 · 已纳入错题'), findsOneWidget);
    expect(find.textContaining('10 分钟后重练'), findsOneWidget);

    // 打开底部答题卡网格并跳回第 1 题
    await tester.tap(find.byKey(const ValueKey('practice-sheet-btn')));
    await settlePhone(tester);
    await tester.pumpAndSettle();
    expect(find.text('答题卡（已答 3 / 3）'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('practice-sheet-cell-0')));
    await settlePhone(tester);
    await tester.pumpAndSettle();
    expect(find.text('1 / 3'), findsOneWidget);

    // 再次打开答题卡直接交卷看战报
    await tester.tap(find.byKey(const ValueKey('practice-sheet-btn')));
    await settlePhone(tester);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('practice-sheet-submit-btn')));
    await settlePhone(tester);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('practice-summary-view')), findsOneWidget);
    expect(find.text('本轮战报 · 正确率 67%'), findsOneWidget);
    expect(find.text('错 1 题'), findsOneWidget);
    expect(find.text('1 题'), findsNWidgets(2)); // 新加入错题 1 题 & 错题本毕业 1 题

    // 一键重刷本轮错题 -> 仅保留答错的 q-3
    await tester.tap(find.byKey(const ValueKey('practice-retry-wrong')));
    await settlePhone(tester);
    expect(find.text('1 / 1'), findsOneWidget);
    expect(find.textContaining('思考：遇到绝对化选项第一步做什么？'), findsOneWidget);
  });

  testWidgets('空题池展示友好空状态卡片', (tester) async {
    final ch = _FakePracticeChannel()..returnEmpty = true;
    await pumpPhone(tester, QuizPracticePage(channel: ch, scope: 'wrong_book'), size: const Size(390, 844));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('practice-empty-state')), findsOneWidget);
    expect(find.textContaining('错题本目前空空如也'), findsOneWidget);
  });
}
