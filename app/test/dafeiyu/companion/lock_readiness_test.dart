import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/today_cards.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

/// 锁机体检：重装后锁落不下来的几个原因，必须顶在局页上说清楚、点一下能修。
class _FakeChannel extends ProactiveChannel {
  _FakeChannel({required this.a11y, required this.shadow});
  bool a11y;
  bool? shadow;
  final List<String> opened = [];
  final List<Map<String, Object?>> patches = [];

  @override
  Future<ProactiveStatus?> status() async => ProactiveStatus(capabilities: {'a11y': a11y});
  @override
  Future<bool?> lockShadow() async => shadow;
  @override
  Future<bool> openCapability(String kind) async {
    opened.add(kind);
    return true;
  }

  @override
  Future<bool> setCompanionConfig(Map<String, Object?> patch) async {
    patches.add(patch);
    if (patch['lockShadow'] is bool) shadow = patch['lockShadow'] as bool;
    return true;
  }
}

Widget _host(ProactiveChannel ch) => Scaffold(body: ListView(children: [LockReadinessCard(channel: ch)]));

void main() {
  testWidgets('无障碍没开 + 测试模式开着：顶上说锁落不下来，点无障碍去设置，拨开关关测试模式', (tester) async {
    final ch = _FakeChannel(a11y: false, shadow: true);
    await pumpPhone(tester, _host(ch));
    await settlePhone(tester);
    expect(find.text('锁机现在落不下来'), findsOneWidget);
    expect(find.text('无障碍没开'), findsOneWidget);
    expect(find.text('测试模式开着'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('lock-fix-a11y')));
    await settlePhone(tester);
    expect(ch.opened, ['a11y']);

    await tester.tap(find.descendant(of: find.byKey(const ValueKey('lock-fix-shadow')), matching: find.byType(Switch)));
    await settlePhone(tester);
    expect(ch.patches.single, {'lockShadow': false});
  });

  testWidgets('引擎没应答：如实说，不装作就绪', (tester) async {
    await pumpPhone(tester, _host(_FakeChannel(a11y: true, shadow: null)));
    await settlePhone(tester);
    expect(find.text('引擎没应答'), findsOneWidget);
    expect(find.text('锁机就绪'), findsNothing);
  });

  testWidgets('全部正常：只留一行「锁机就绪」', (tester) async {
    await pumpPhone(tester, _host(_FakeChannel(a11y: true, shadow: false)));
    await settlePhone(tester);
    expect(find.text('锁机就绪'), findsOneWidget);
    expect(find.text('锁机现在落不下来'), findsNothing);
  });
}
