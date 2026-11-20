import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/seal_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

/// 封印页：今天的进度与状态、锁着时的说明、封明天（只发 add，不存在改小 / 删除）。
class _Seals extends ProactiveChannel {
  _Seals({this.locked = false});

  final bool locked;
  final List<Map<String, Object?>> calls = [];
  final List<Map<String, dynamic>> items = [
    {'id': 's1', 'day': '2026-11-20', 'title': '资料分析', 'kind': 'quiz', 'stretch': 40, 'floor': 20, 'startMinute': 1140, 'dueMinute': 1200, 'actual': 12, 'status': 'open', 'paidMinutes': 0, 'openedAt': 0},
    {'id': 's2', 'day': '2026-11-20', 'title': '行测笔记', 'kind': 'file', 'stretch': 600, 'floor': 300, 'dueMinute': 900, 'actual': 650, 'status': 'stretch', 'paidMinutes': 25},
  ];

  Map<String, dynamic> _view() => {
        'ok': true,
        'today': '2026-11-20',
        'tomorrow': '2026-11-21',
        'minuteOfDay': 540,
        'cutoffPassed': false,
        'coldCutoffMinute': 600,
        'workStarted': false,
        'items': items,
        'locking': locked ? items.first : null,
      };

  @override
  Future<Map<String, dynamic>?> sealsOp(Map<String, Object?> body) async {
    calls.add(body);
    if (body['action'] == 'open') items.first['openedAt'] = 1;
    if (body['action'] == 'week') return {'ok': true, 'text': '# 大肥鱼 · 周冷数据\n【周验收】'};
    if (body['action'] == 'week-review') {
      return {..._view(), 'review': {'id': 'wr-1', 'by': 'paste', 'verdict': '在演', 'watch': '没过保底', 'adjustments': [{'text': '保底 10 → 15'}, {'text': '晚上不排'}]}};
    }
    if (body['action'] == 'week-decide') return _view();
    if (body['action'] == 'add') {
      final s = Map<String, dynamic>.from(body['seal'] as Map);
      items.add({...s, 'id': 's3', 'day': '2026-11-21', 'actual': 0, 'status': 'open', 'paidMinutes': 0, 'floor': s['floor'] ?? 10});
    }
    return _view();
  }
}

void main() {
  testWidgets('今天的封印：进度、档位、发出去的分钟', (tester) async {
    final ch = _Seals();
    await pumpPhone(tester, SealPage(channel: ch));
    await settlePhone(tester);
    expect(find.text('资料分析'), findsOneWidget);
    expect(find.textContaining('19:00–20:00 · 12/40题'), findsOneWidget, reason: '时间轴：开始–截止');
    expect(find.text('进行中'), findsOneWidget);
    expect(find.text('过拉伸'), findsOneWidget);
    expect(find.text('+25 分钟'), findsOneWidget);
    expect(find.textContaining('今天 2 条，过了 1 条'), findsOneWidget);
    expect(find.byKey(const ValueKey('seal-locking')), findsNothing);
  });

  testWidgets('到点没过保底：顶部写清怎么解', (tester) async {
    await pumpPhone(tester, SealPage(channel: _Seals(locked: true)));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('seal-locking')), findsOneWidget);
    expect(find.textContaining('交够 20题 就解'), findsOneWidget);
    expect(find.text('锁着'), findsWidgets);
  });

  testWidgets('封明天一条：只发 add，没有改小 / 删除的入口', (tester) async {
    final ch = _Seals();
    await pumpPhone(tester, SealPage(channel: ch));
    await settlePhone(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('seal-add-tomorrow')));
    await tester.tap(find.byKey(const ValueKey('seal-add-tomorrow')));
    await settlePhone(tester);
    await tester.enterText(find.byKey(const ValueKey('seal-title')), '言语 30 题');
    await tester.enterText(find.byKey(const ValueKey('seal-stretch')), '30');
    await tester.ensureVisible(find.byKey(const ValueKey('seal-submit')));
    await tester.tap(find.byKey(const ValueKey('seal-submit')));
    await settlePhone(tester);
    final add = ch.calls.where((c) => c['action'] == 'add').single;
    final seal = add['seal'] as Map;
    expect(seal['title'], '言语 30 题');
    expect(seal['kind'], 'quiz');
    expect(seal['stretch'], 30);
    expect(seal['target'], 'tomorrow');
    expect(seal['dueMinute'], 1080);
    expect(ch.calls.any((c) => c['action'] == 'delete' || c['action'] == 'lower'), isFalse);
    expect(find.text('言语 30 题'), findsOneWidget);
  });

  testWidgets('点一个今天的点 →「现在开这一局」：发 open；开过的不再显示这一项', (tester) async {
    final ch = _Seals();
    await pumpPhone(tester, SealPage(channel: ch));
    await settlePhone(tester);
    await tester.tap(find.text('资料分析'));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('seal-act-open')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('seal-act-open')));
    await settlePhone(tester);
    expect(ch.calls.last, {'action': 'open', 'id': 's1'});
    await tester.tap(find.text('资料分析'));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('seal-act-open')), findsNothing);
  });

  testWidgets('10:00 前没开工：加一个验收点写「不打折」，可选开始时刻', (tester) async {
    final ch = _Seals();
    await pumpPhone(tester, SealPage(channel: ch));
    await settlePhone(tester);
    expect(find.textContaining('10:00 前、开工前定的不打折'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('seal-add-today')));
    await settlePhone(tester);
    expect(find.textContaining('开工前定的不打折'), findsWidgets);
    expect(find.byKey(const ValueKey('seal-start')), findsOneWidget);
  });

  testWidgets('周验收：复制这一周的冷数据 → 发 week、进剪贴板', (tester) async {
    final ch = _Seals();
    String? copied;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String?;
      return null;
    });
    await pumpPhone(tester, SealPage(channel: ch));
    await settlePhone(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('seal-copy-week')));
    await tester.tap(find.byKey(const ValueKey('seal-copy-week')));
    await settlePhone(tester);
    expect(ch.calls.last, {'action': 'week'});
    expect(copied, contains('【周验收】'));
  });

  testWidgets('粘贴【周验收】→ 解析 → 勾第一条 → 发 week-decide 带下标', (tester) async {
    final ch = _Seals();
    await pumpPhone(tester, SealPage(channel: ch));
    await settlePhone(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('seal-paste-review')));
    await tester.tap(find.byKey(const ValueKey('seal-paste-review')));
    await settlePhone(tester);
    await tester.enterText(find.byKey(const ValueKey('week-review-text')), '【周验收】\n判断：在演\n调整1：保底 10 → 15');
    await tester.tap(find.byKey(const ValueKey('week-review-submit')));
    await settlePhone(tester);
    expect(find.text('保底 10 → 15'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('week-adj-0')));
    await settlePhone(tester);
    expect(find.text('采纳 1 条'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('week-decide')));
    await settlePhone(tester);
    expect(ch.calls.last, {'action': 'week-decide', 'id': 'wr-1', 'accepted': [0]});
  });
}
