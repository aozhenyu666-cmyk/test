import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/gemini_bridge_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

/// Gemini 桥页：没配时说清楚怎么搭；填地址和密钥 → 发 config；连着时显示在线和处理过几条。
class _Bridge extends ProactiveChannel {
  _Bridge({this.configured = false});

  bool configured;
  final List<Map<String, Object?>> calls = [];

  Map<String, dynamic> _view() => {
        'ok': true,
        'configured': configured,
        'enabled': configured,
        'url': configured ? 'https://pc.tail.ts.net' : '',
        'keyTail': configured ? 'ab12' : '',
        'healthy': configured,
        'lastOkAt': configured ? DateTime.now().millisecondsSinceEpoch - 5000 : 0,
        'lastError': '',
        'applied': configured ? 3 : 0,
        'receiptsToday': configured ? 2 : 0,
      };

  @override
  Future<Map<String, dynamic>?> bridgeOp(Map<String, Object?> body) async {
    calls.add(body);
    if (body['action'] == 'config') {
      configured = true;
      return {..._view(), 'synced': true};
    }
    return _view();
  }
}

void main() {
  testWidgets('没配：说「还没连」、写清 Gemini 不能解锁；填好保存发 config', (tester) async {
    final ch = _Bridge();
    await pumpPhone(tester, GeminiBridgePage(channel: ch));
    await settlePhone(tester);
    expect(find.text('还没连'), findsOneWidget);
    expect(find.textContaining('解锁、加娱乐额度、豁免'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('bridge-url')), 'https://pc.tail.ts.net');
    await tester.enterText(find.byKey(const ValueKey('bridge-key')), 'dfy_secret');
    await tester.ensureVisible(find.byKey(const ValueKey('bridge-save')));
    await tester.tap(find.byKey(const ValueKey('bridge-save')));
    await settlePhone(tester);
    expect(ch.calls.last, {'action': 'config', 'url': 'https://pc.tail.ts.net', 'key': 'dfy_secret'});
    expect(find.text('连着'), findsOneWidget);
    expect(find.textContaining('连上了'), findsOneWidget);
  });

  testWidgets('连着：显示在线、处理过几条、今天回执几条', (tester) async {
    await pumpPhone(tester, GeminiBridgePage(channel: _Bridge(configured: true)));
    await settlePhone(tester);
    expect(find.text('在线'), findsOneWidget);
    expect(find.textContaining('处理过 Gemini 的 3 条命令'), findsOneWidget);
    expect(find.textContaining('今天回执 2 条'), findsOneWidget);
  });
}
