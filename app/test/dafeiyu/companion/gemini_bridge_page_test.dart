import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/gemini_bridge_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

/// Gemini 桥页：没配时说清楚怎么搭；填地址和密钥 → 发 config；连着时显示在线和处理过几条。
class _Bridge extends ProactiveChannel {
  _Bridge({this.configured = false, this.lastError = ''});

  bool configured;
  String lastError;
  final List<Map<String, Object?>> calls = [];
  final List<Map<String, Object?>> launches = [];

  Map<String, dynamic> _view() => {
        'ok': true,
        'configured': configured,
        'enabled': configured,
        'url': configured ? 'https://pc.tail.ts.net' : '',
        'keyTail': configured ? 'ab12' : '',
        'healthy': configured && lastError.isEmpty,
        'lastOkAt': configured ? DateTime.now().millisecondsSinceEpoch - 5000 : 0,
        'lastError': lastError,
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

  @override
  Future<Map<String, dynamic>?> todayStatus() async => {
        'geminiMinutesToday': 18,
        'charts': {
          'buckets': {'mainlineMin': 55, 'geminiMin': 18, 'funMin': 12},
        },
      };

  @override
  Future<Map<String, Object?>?> wallStatus() async => {'locked': true, 'why': '默认专注门禁'};

  @override
  Future<Map<String, dynamic>?> bossBrief() async => {
        'ok': true,
        'bossPrompt': '【老板全局审视测试】先查事实，提 3 个追问。',
      };

  @override
  Future<bool> launchGeminiProactive({
    String topic = '',
    String brief = '',
    String mode = 'countdown_3s',
    bool preferVoice = true,
  }) async {
    launches.add({'topic': topic, 'brief': brief, 'mode': mode});
    return true;
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

  testWidgets('主动触发矩阵状态 & 4 场景一键唤起 + 延后 30m 尊重用户', (tester) async {
    String? copied;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied = (call.arguments as Map?)?['text'] as String?;
        }
        return null;
      },
    );
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));

    final ch = _Bridge(configured: true);
    await pumpPhone(tester, GeminiBridgePage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('gemini-trigger-matrix-card')), findsOneWidget);
    expect(find.textContaining('急卡点 10m'), findsOneWidget);

    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('gemini-snooze-30m')),
      200,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const ValueKey('gemini-snooze-30m')));
    await settlePhone(tester);
    expect(find.textContaining('已延后'), findsOneWidget);

    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('gemini-scene-icebreak')),
      200,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const ValueKey('gemini-scene-icebreak')));
    await settlePhone(tester);
    expect(ch.launches.last['topic'], '2 分钟破冰规划');
    expect(copied, contains('2 分钟破冰规划'));

    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('gemini-scene-boss-audit')),
      200,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const ValueKey('gemini-scene-boss-audit')));
    await settlePhone(tester);
    expect(ch.launches.last['topic'], '老板全局审视质询');
    expect(copied, contains('老板全局审视测试'));
  });

  testWidgets('一键粘贴电脑桥配置自动剥离 /mcp 并解析密钥；401 报错显示排查建议', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.getData') {
          return <String, Object?>{
            'text': '电脑桥地址：https://pc.tail.ts.net:8443/mcp\n手机密钥：dfy_tok123456',
          };
        }
        return null;
      },
    );
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));

    final ch = _Bridge();
    await pumpPhone(tester, GeminiBridgePage(channel: ch));
    await settlePhone(tester);

    final pasteBtn = find.byKey(const ValueKey('bridge-paste-bundle-btn'));
    await tester.ensureVisible(pasteBtn);
    await tester.tap(pasteBtn);
    await settlePhone(tester);

    expect(
      ch.calls.any(
        (c) =>
            c['action'] == 'config' &&
            c['url'] == 'https://pc.tail.ts.net:8443' &&
            c['key'] == 'dfy_tok123456',
      ),
      isTrue,
    );
  });

  testWidgets('最近一次没连上：401 与超时错误显示可操作排查建议', (tester) async {
    final errBridge = _Bridge(configured: true, lastError: 'HTTP 401 unauthorized');
    await pumpPhone(tester, GeminiBridgePage(channel: errBridge));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('bridge-error')), findsOneWidget);
    expect(find.textContaining('排查：密钥和电脑桥不一致'), findsOneWidget);
  });
}
