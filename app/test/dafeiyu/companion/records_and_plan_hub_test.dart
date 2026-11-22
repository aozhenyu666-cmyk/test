import 'package:dsh_app/dafeiyu/channels/intercept_channel.dart';
import 'package:dsh_app/dafeiyu/channels/loop_channel.dart';
import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/plan_hub_page.dart';
import 'package:dsh_app/dafeiyu/ui/records_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeIntercept extends InterceptChannel {
  _FakeIntercept(this.rows);
  List<InterceptReceipt> rows;
  final List<int> cleared = [];

  @override
  Future<List<InterceptReceipt>> receipts({int limit = 50}) async => rows.take(limit).toList();

  @override
  Future<int> clearReceipts({int olderThanDays = 0}) async {
    cleared.add(olderThanDays);
    final n = rows.length;
    rows = const [];
    return n;
  }
}

class _FakeLoop extends LoopChannel {
  @override
  Future<List<LoopApp>> apps() async => const [LoopApp('tv.danmaku.bili', '哔哩哔哩')];
}

class _FakeProactive extends ProactiveChannel {
  @override
  Future<Map<String, dynamic>?> todayStatus() async => {
        'tasks': [
          {'title': '行测 20 题'},
        ],
        'horizons': {
          'medium': [
            {'title': '资料分析 200 题', 'due': '2099-01-02'},
          ],
          'long': [
            {'title': '省考上岸'},
          ],
        },
      };
}

InterceptReceipt _r(int i) => InterceptReceipt(
      eventId: 'e$i',
      packageName: 'tv.danmaku.bili',
      outcome: 'EXITED',
      triggeredAt: 1_700_000_000_000 + i * 60_000,
      endedAt: 1_700_000_000_000 + i * 60_000 + 1000,
    );

void main() {
  testWidgets('执行记录：每页 20 条，再看更多；清理模态「全部清掉」要再确认一次', (tester) async {
    final ic = _FakeIntercept([for (var i = 0; i < 45; i++) _r(i)]);
    await pumpPhone(tester, RecordsPage(intercept: ic, proactive: _FakeProactive(), loop: _FakeLoop()));
    await settlePhone(tester);
    expect(find.text('最近 20 条'), findsOneWidget);
    expect(find.text('哔哩哔哩'), findsWidgets, reason: '显示 App 名字，不显示包名');

    await tester.scrollUntilVisible(find.byKey(const ValueKey('records-more')), 300, scrollable: find.byType(Scrollable).last);
    await tester.tap(find.byKey(const ValueKey('records-more')));
    await settlePhone(tester);
    expect(find.text('最近 40 条'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('records-clean')));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('records-clean-all')));
    await settlePhone(tester);
    expect(find.text('全部清掉？'), findsOneWidget);
    expect(ic.cleared, isEmpty, reason: '没确认之前不删');
    await tester.tap(find.byKey(const ValueKey('records-clean-confirm')));
    await settlePhone(tester);
    expect(ic.cleared, [0]);
    expect(find.text('还没有记录'), findsOneWidget);
  });

  testWidgets('清理 7 天前的：直接删，不用二次确认', (tester) async {
    final ic = _FakeIntercept([_r(1)]);
    await pumpPhone(tester, RecordsPage(intercept: ic, proactive: _FakeProactive(), loop: _FakeLoop()));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('records-clean')));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('records-clean-7')));
    await settlePhone(tester);
    expect(ic.cleared, [7]);
  });

  testWidgets('计划：三层从近到远一个入口；截止日列出来', (tester) async {
    await pumpPhone(tester, PlanHubPage(channel: _FakeProactive()));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('plan-hub-day')), findsOneWidget);
    expect(find.byKey(const ValueKey('plan-hub-contract')), findsOneWidget);
    expect(find.byKey(const ValueKey('plan-hub-horizons')), findsOneWidget);
    expect(find.text('1 长 · 1 中'), findsOneWidget);
    expect(find.text('资料分析 200 题'), findsOneWidget);
  });
}
