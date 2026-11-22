import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/health_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

/// 体检页：手机六项 + 引擎那一半；断了的点一下去修（测试模式先确认）。
class _Health extends ProactiveChannel {
  final List<Object> calls = [];
  Map<String, dynamic>? health = {
    'ok': true,
    'summary': {'ok': 5, 'warn': 1, 'error': 2, 'line': '✗ 测试模式开着、题库是空的；⚠ 今天的时间轴是空的；✓ 5 项正常'},
    'items': [
      {'id': 'phone-tick', 'group': '引擎', 'label': '手机和引擎在对表', 'level': 'ok', 'detail': '上次 0 分钟前', 'fix': ''},
      {'id': 'lock-shadow', 'group': '锁', 'label': '测试模式开着', 'level': 'error', 'detail': '锁只提示、不真锁', 'fix': 'lock-shadow'},
      {'id': 'bank', 'group': '验收', 'label': '题库是空的', 'level': 'error', 'detail': '默认模板的「一套行测」立不起来', 'fix': 'bank'},
      {'id': 'timeline', 'group': '验收', 'label': '今天的时间轴是空的', 'level': 'warn', 'detail': '', 'fix': 'seals'},
    ],
  };

  @override
  Future<ProactiveStatus?> status() async => const ProactiveStatus(
        capabilities: {'a11y': true, 'overlay': true, 'notifications': false, 'exactAlarm': true, 'battery': false, 'keeper': true},
      );

  @override
  Future<Map<String, dynamic>?> healthStatus() async => health;

  @override
  Future<bool> openCapability(String kind) async {
    calls.add('open:$kind');
    return true;
  }

  @override
  Future<bool> setCompanionConfig(Map<String, Object?> patch) async {
    calls.add(patch);
    return true;
  }
}

void main() {
  testWidgets('手机断了两项、引擎断了两项：总数 4，按组列出，每项写清', (tester) async {
    await pumpPhone(tester, HealthPage(channel: _Health()));
    await settlePhone(tester);
    expect(find.text('4 处断了'), findsOneWidget);
    expect(find.textContaining('没开：「紧急解除」和引擎常驻通知看不到'), findsOneWidget);
    expect(find.textContaining('没放行：系统会在后台把引擎掐掉'), findsOneWidget);
    expect(find.text('测试模式开着'), findsOneWidget);
    expect(find.text('题库是空的', skipOffstage: false), findsOneWidget);
  });

  testWidgets('点手机那一项去系统设置；点「测试模式开着」先确认再关', (tester) async {
    final ch = _Health();
    await pumpPhone(tester, HealthPage(channel: ch));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('health-cap-notifications')));
    await settlePhone(tester);
    expect(ch.calls.last, 'open:notifications');
    await tester.tap(find.byKey(const ValueKey('health-lock-shadow')));
    await settlePhone(tester);
    expect(find.textContaining('关了以后锁会真的落下'), findsOneWidget);
    await tester.tap(find.text('关掉，真锁'));
    await settlePhone(tester);
    expect(ch.calls.whereType<Map>().last, {'lockShadow': false});
  });

  testWidgets('引擎没应答：说清楚看通知栏', (tester) async {
    final ch = _Health()..health = null;
    await pumpPhone(tester, HealthPage(channel: ch));
    await settlePhone(tester);
    expect(find.text('引擎没应答'), findsOneWidget);
    expect(find.textContaining('看通知栏有没有引擎常驻通知'), findsOneWidget);
  });
}
