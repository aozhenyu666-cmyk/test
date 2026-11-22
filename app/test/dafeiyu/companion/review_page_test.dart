import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/review_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

/// 复习页：带选项的卡 4 选 1（代码判）；没选项的先藏答案、自己判记得 / 忘了；删卡先确认。
class _Review extends ProactiveChannel {
  final List<Map<String, Object?>> calls = [];
  List<Map<String, dynamic>> due = [
    {'id': 'rc-1', 'topic': '数量', 'q': '下面哪个是质数', 'a': '7', 'options': ['4', '7'], 'pendingRecall': false},
    {'id': 'rc-2', 'topic': '言语', 'q': '首鼠两端是什么意思', 'a': '犹豫不决', 'options': <String>[], 'pendingRecall': true},
  ];

  @override
  Future<Map<String, dynamic>?> reviewOp(Map<String, Object?> body) async {
    calls.add(body);
    switch (body['action']) {
      case 'summary':
        return {'ok': true, 'dueCount': due.length, 'totalCards': 3, 'retention7d': 75};
      case 'due':
        return {'ok': true, 'items': due};
      case 'list':
        return {
          'ok': true,
          'total': 3,
          'items': [
            ...due.map((c) => {...c, 'due': 0, 'reps': 0, 'lapses': 0, 'counts': true}),
            {'id': 'rc-3', 'q': '一张没到期的卡', 'a': 'x', 'options': <String>[], 'due': DateTime.now().millisecondsSinceEpoch + 3 * 86400000, 'reps': 2, 'lapses': 1},
          ],
        };
      case 'answer':
        due = due.where((c) => c['id'] != body['id']).toList();
        return {'ok': true, 'correct': body['index'] == 1, 'text': body['index'] == 1 ? '对。' : '错了'};
      case 'rate':
        due = due.where((c) => c['id'] != body['id']).toList();
        return {'ok': true, 'text': '记下了。'};
      case 'delete':
        return {'ok': true, 'text': '删了。'};
    }
    return null;
  }
}

void main() {
  testWidgets('概览 + 第一张选择卡：点对的选项走 answer（代码判）', (tester) async {
    final ch = _Review();
    await pumpPhone(tester, ReviewPage(channel: ch));
    await settlePhone(tester);
    expect(find.text('今天到期 2 张 · 共 3 张'), findsOneWidget);
    expect(find.textContaining('75%'), findsOneWidget);
    expect(find.descendant(of: find.byKey(const ValueKey('review-current-rc-1')), matching: find.text('下面哪个是质数')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('review-opt-1')));
    await settlePhone(tester);
    expect(ch.calls.any((c) => c['action'] == 'answer' && c['id'] == 'rc-1' && c['index'] == 1), isTrue);
    expect(find.text('对。'), findsOneWidget);
    // 下一张是没选项的：答案先藏着
    expect(find.descendant(of: find.byKey(const ValueKey('review-current-rc-2')), matching: find.text('首鼠两端是什么意思')), findsOneWidget);
    expect(find.textContaining('犹豫不决'), findsNothing);
  });

  testWidgets('没选项的卡：看答案后才出记得 / 忘了；等兑现的卡提醒自判会用掉回忆', (tester) async {
    final ch = _Review()..due = [_Review().due[1]];
    await pumpPhone(tester, ReviewPage(channel: ch));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('review-remember')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('review-reveal')));
    await settlePhone(tester);
    expect(find.textContaining('答案：犹豫不决'), findsOneWidget);
    expect(find.textContaining('这次回忆就用掉了'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('review-forgot')));
    await settlePhone(tester);
    expect(ch.calls.any((c) => c['action'] == 'rate' && c['rating'] == 'again'), isTrue);
    expect(find.text('今天的到期卡刷完了。'), findsOneWidget);
  });

  testWidgets('删卡先确认', (tester) async {
    final ch = _Review();
    await pumpPhone(tester, ReviewPage(channel: ch));
    await settlePhone(tester);
    await tester.scrollUntilVisible(find.byKey(const ValueKey('review-del-rc-3')), 200);
    await tester.tap(find.byKey(const ValueKey('review-del-rc-3')));
    await settlePhone(tester);
    expect(find.text('删掉这张卡？'), findsOneWidget);
    await tester.tap(find.text('删掉'));
    await settlePhone(tester);
    expect(ch.calls.any((c) => c['action'] == 'delete' && c['id'] == 'rc-3'), isTrue);
  });
}
