import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/week_plan_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

/// 计划页：长期 + 周一到周日 + 执行率；加一条走 add；复制给 Gemini。
class _Week extends ProactiveChannel {
  final List<Map<String, Object?>> calls = [];

  Map<String, dynamic> view({List<Map<String, dynamic>> wedExtra = const []}) => {
        'ok': true,
        'monday': '2026-11-23',
        'weekday': 1,
        'execRate': 62,
        'longText': '',
        'horizons': {
          'long': [
            {'id': 'h1', 'title': '考公行测 75'},
          ],
          'medium': <Map<String, dynamic>>[],
        },
        'days': [
          for (final (wd, name) in const [(1, '周一'), (2, '周二'), (3, '周三'), (4, '周四'), (5, '周五'), (6, '周六'), (0, '周日')])
            {
              'weekday': wd,
              'name': name,
              'applyAt': '09:30',
              'isToday': wd == 1,
              'isPast': false,
              'items': [
                if (wd == 1) {'title': '资料分析', 'kind': 'quiz', 'line': '09:00–10:30 资料分析 · 题库 30 题 · 保底 15'},
                if (wd == 3) ...wedExtra,
              ],
            },
        ],
      };

  @override
  Future<Map<String, dynamic>?> planWeekOp(Map<String, Object?> body) async {
    calls.add(body);
    switch (body['action']) {
      case 'view':
        return view();
      case 'add':
        return {
          ...view(wedExtra: [
            {'title': '申论一篇', 'kind': 'bout_minutes', 'line': '14:00–15:30 申论一篇 · 局 60 分钟 · 保底 40'},
          ]),
          'text': '加上了。周三到点会自动套上。',
        };
      case 'prompt':
        return {...view(), 'prompt': '帮我排下周的「本周计划」'};
    }
    return null;
  }
}

void main() {
  testWidgets('看本周：长期主线、执行率、今天那天标出来、空着的天说清按模板套', (tester) async {
    await pumpPhone(tester, WeekPlanPage(channel: _Week()));
    await settlePhone(tester);
    expect(find.text('考公行测 75'), findsOneWidget);
    expect(find.text('执行率 62%'), findsOneWidget);
    expect(find.text('资料分析'), findsOneWidget);
    expect(find.textContaining('· 今天', skipOffstage: false), findsOneWidget);
    expect(find.text('这天空着', skipOffstage: false), findsWidgets);
  });

  testWidgets('加一条：填好交什么、选证据，发 add，回来后那天多一条', (tester) async {
    final ch = _Week();
    await pumpPhone(tester, WeekPlanPage(channel: ch));
    await settlePhone(tester);
    await tester.scrollUntilVisible(find.byKey(const ValueKey('week-plan-add-3')), 200);
    await tester.tap(find.byKey(const ValueKey('week-plan-add-3')));
    await settlePhone(tester);
    await tester.enterText(find.byKey(const ValueKey('week-add-title')), '申论一篇');
    await tester.tap(find.byKey(const ValueKey('week-add-kind-bout_minutes')));
    await tester.enterText(find.byKey(const ValueKey('week-add-stretch')), '60');
    await tester.enterText(find.byKey(const ValueKey('week-add-floor')), '40');
    await tester.tap(find.byKey(const ValueKey('week-add-submit')));
    await settlePhone(tester);
    final add = ch.calls.firstWhere((c) => c['action'] == 'add');
    expect(add['weekday'], 3);
    final item = add['item']! as Map;
    expect(item['title'], '申论一篇');
    expect(item['kind'], 'bout_minutes');
    expect(item['stretch'], 60);
    expect(item['floor'], 40);
    expect(find.text('申论一篇', skipOffstage: false), findsOneWidget);
    expect(find.textContaining('周三到点会自动套上'), findsOneWidget);
  });

  testWidgets('加一条没写交什么：不发，提示写一句', (tester) async {
    final ch = _Week();
    await pumpPhone(tester, WeekPlanPage(channel: ch));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('week-plan-add-1')));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('week-add-submit')));
    await settlePhone(tester);
    expect(find.text('写一句交什么'), findsOneWidget);
    expect(ch.calls.where((c) => c['action'] == 'add'), isEmpty);
  });

  testWidgets('复制给 Gemini：拿 prompt 放进剪贴板', (tester) async {
    final copied = <String>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') copied.add('${(call.arguments as Map)['text']}');
      return null;
    });
    await pumpPhone(tester, WeekPlanPage(channel: _Week()));
    await settlePhone(tester);
    await tester.scrollUntilVisible(find.byKey(const ValueKey('week-plan-gemini')), 300);
    await tester.tap(find.byKey(const ValueKey('week-plan-gemini')));
    await settlePhone(tester);
    expect(copied.single, contains('本周计划'));
    expect(find.textContaining('复制好了'), findsOneWidget);
  });
}
