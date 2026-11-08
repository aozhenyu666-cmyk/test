// 触发测试 page (lib/ui/phone/loop_test_page.dart) over a fake `dsh/loop` channel,
// plus the wire parsing in lib/loop/loop_channel.dart.

import 'package:dsh_app/loop/loop_channel.dart';
import 'package:dsh_app/ui/phone/loop_test_page.dart';
import 'package:dsh_app/ui/phone/phone_control_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'harness.dart';

class FakeLoopNative {
  Map<String, Object?> config = {'enabled': false, 'targetPackage': null, 'cooldownMs': 60000, 'timeoutMs': 60000};
  List<Map<String, Object?>> events = [];
  final calls = <MethodCall>[];

  /// Overridden per test; the defaults describe a healthy loop.
  String? storageError;
  String? recoveredFrom;
  int triggersToday = 0;
  Map<String, Object?> diagnostics = {};

  void install() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('dsh/loop'),
      (call) async {
        calls.add(call);
        switch (call.method) {
          case 'status':
            return {
              'config': config,
              'a11yEnabled': true,
              'a11yConnected': true,
              'eventsSeen': 12,
              'readings': 3,
              'lastForeground': 'com.example.test',
              'storageError': storageError,
              'recoveredFrom': recoveredFrom,
              'insideTarget': false,
              'triggersToday': triggersToday,
              'triggerDay': '2026-10-31',
              'diagnostics': diagnostics,
            };
          case 'setConfig':
            final args = (call.arguments as Map).cast<String, Object?>();
            config = {...config, ...args};
            return config;
          case 'events':
            return events;
          case 'clearEvents':
            events = [];
            return true;
          case 'clearDiagnostics':
            diagnostics = {};
            return true;
          case 'apps':
            return [
              {'package': 'com.example.test', 'label': '测试应用'},
              {'package': 'com.example.other', 'label': '别的应用'},
            ];
        }
        return null;
      },
    );
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('dsh/loop'),
        null,
      ),
    );
  }

  List<Object?> argsOf(String method) => [for (final c in calls) if (c.method == method) c.arguments];
}

Map<String, Object?> receipt(String id, String? result, {String state = 'CLOSED', String? reason}) => {
  'eventId': id,
  'source': 'A11Y_WINDOW',
  'timestamp': DateTime(2026, 10, 30, 9, 15).millisecondsSinceEpoch,
  'state': state,
  'result': result,
  'error': null,
  'reason': reason,
  'packageName': 'com.example.test',
  'visitId': 'v-$id',
  'surface': 'activity',
  'updatedAt': DateTime(2026, 10, 30, 9, 16).millisecondsSinceEpoch,
};

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('wire', () {
    test('a receipt carries the shared event fields', () {
      final e = LoopEvent.fromWire(receipt('a', 'RETURNED'))!;
      expect(e.eventId, 'a');
      expect(e.source, 'A11Y_WINDOW');
      expect(e.state, 'CLOSED');
      expect(e.result, 'RETURNED');
      expect(e.timestamp, DateTime(2026, 10, 30, 9, 15));
      expect(LoopEvent.fromWire({'eventId': 'x'}), isNull);
      expect(LoopEvent.fromWire('nope'), isNull);
    });

    test('status survives missing and odd fields', () {
      final s = LoopStatus.fromWire({'config': {'enabled': true, 'cooldownMs': 10000.0}, 'eventsSeen': 'x'});
      expect(s.config.enabled, isTrue);
      expect(s.config.cooldownMs, 10000);
      expect(s.config.targetPackage, isNull);
      expect(s.eventsSeen, 0);
      expect(LoopStatus.fromWire(null).a11yConnected, isFalse);
    });
  });

  group('page', () {
    testWidgets('shows status and receipts, newest first', (tester) async {
      final native = FakeLoopNative()
        ..events = [receipt('b', null, state: 'SHOWN'), receipt('a', 'SUPPRESSED', reason: 'cooldown')]
        ..install();
      await pumpPhone(tester, const LoopTestPage());
      expect(find.text('无障碍服务已连接'), findsOneWidget);
      expect(find.text('进行中'), findsOneWidget);
      expect(find.text('未触发'), findsOneWidget);
      expect(find.textContaining('cooldown'), findsOneWidget);
      expect(native.argsOf('events').single, {'limit': 100});
    });

    testWidgets('switch, app picker and cooldown write config', (tester) async {
      final native = FakeLoopNative()..install();
      await pumpPhone(tester, const LoopTestPage());

      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, {'enabled': true});

      await tester.tap(find.text('测试 APP'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('测试应用'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, {'targetPackage': 'com.example.test'});
      expect(find.textContaining('com.example.test'), findsWidgets);

      await tester.tap(find.text('冷却时间'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, {'cooldownMs': 300000});
      expect(find.text('300 秒'), findsOneWidget);
    });

    testWidgets('clearing receipts empties the list', (tester) async {
      final native = FakeLoopNative()
        ..events = [receipt('a', 'RETURNED')]
        ..install();
      await pumpPhone(tester, const LoopTestPage());
      expect(find.text('点了返回'), findsOneWidget);
      await tester.tap(find.text('清空回执'));
      await tester.pumpAndSettle();
      expect(native.argsOf('clearEvents'), hasLength(1));
      expect(find.text('还没有回执'), findsOneWidget);
    });

    testWidgets('quiet hours cycle through the presets and back off', (tester) async {
      final native = FakeLoopNative()..install();
      await pumpPhone(tester, const LoopTestPage());
      expect(find.text('不限'), findsWidgets);

      await tester.tap(find.text('免打扰时段'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, {'quietStartMinute': 1380, 'quietEndMinute': 480});
      expect(find.text('23:00–08:00'), findsOneWidget);

      await tester.tap(find.text('免打扰时段'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, {'quietStartMinute': 720, 'quietEndMinute': 840});
      expect(find.text('12:00–14:00'), findsOneWidget);

      await tester.tap(find.text('免打扰时段'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, {'quietStartMinute': null, 'quietEndMinute': null});
    });

    testWidgets('daily cap cycles and shows what is spent today', (tester) async {
      final native = FakeLoopNative()..triggersToday = 2;
      native.install();
      await pumpPhone(tester, const LoopTestPage());

      await tester.tap(find.text('每天上限'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, {'dailyCap': 3});
      expect(find.text('今天 2/3 次'), findsOneWidget);

      await tester.tap(find.text('每天上限'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, {'dailyCap': 5});
    });

    testWidgets('accessibility failures appear on the page and can be cleared', (tester) async {
      final native = FakeLoopNative()
        ..diagnostics = {
          'faults': {'read_foreground': 3},
          'streaks': {'read_foreground': 3},
          'recent': [
            {'kind': 'read_foreground', 'detail': 'IllegalStateException: windows', 'at': DateTime(2026, 10, 30, 9, 20).millisecondsSinceEpoch},
          ],
          'lastReadAt': DateTime(2026, 10, 30, 9, 18).millisecondsSinceEpoch,
        }
        ..install();
      await pumpPhone(tester, const LoopTestPage());
      expect(find.text('无障碍诊断'), findsOneWidget);
      expect(find.text('读前台失败'), findsOneWidget);
      expect(find.textContaining('IllegalStateException: windows'), findsOneWidget);
      expect(find.textContaining('3 次失败'), findsOneWidget);

      await tester.tap(find.text('清空诊断'));
      await tester.pumpAndSettle();
      expect(native.argsOf('clearDiagnostics'), hasLength(1));
      expect(find.text('无障碍诊断'), findsNothing);
    });

    testWidgets('a receipt closed by the notification says so', (tester) async {
      FakeLoopNative()
        ..events = [
          {
            ...receipt('n', 'RETURNED'),
            'surface': 'notification',
          },
        ]
        ..install();
      await pumpPhone(tester, const LoopTestPage());
      expect(find.text('点了返回'), findsOneWidget);
      expect(find.textContaining('通过通知'), findsOneWidget);
    });

    testWidgets('the timeout cycles through 15/30/60/120', (tester) async {
      final native = FakeLoopNative()..install();
      await pumpPhone(tester, const LoopTestPage());
      // Default 60 s is on the ladder, so the first tap goes to the next step (120).
      await tester.tap(find.text('等你回应的时间'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, {'timeoutMs': 120000});
      await tester.tap(find.text('等你回应的时间'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, {'timeoutMs': 15000});
      expect(find.text('15 秒'), findsWidgets);
    });

    testWidgets('a receipt shows the visit id, shortened', (tester) async {
      FakeLoopNative()
        ..events = [
          {
            ...receipt('v', 'RETURNED'),
            'visitId': 'abcdef0123456789',
          },
        ]
        ..install();
      await pumpPhone(tester, const LoopTestPage());
      expect(find.textContaining('访问 abcdef01'), findsOneWidget);
      expect(find.textContaining('abcdef0123456789'), findsNothing);
    });

    testWidgets('exporting receipts copies JSON with the config and events', (tester) async {
      final native = FakeLoopNative()
        ..events = [receipt('a', 'RETURNED')]
        ..install();
      // Clipboard.setData goes over the platform channel; there is no real clipboard in a widget
      // test, and Clipboard.getData would never answer. Record what the page sends instead.
      final copied = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == 'Clipboard.setData') {
            copied.add((call.arguments as Map)['text'] as String);
          }
          return null;
        },
      );
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null),
      );

      await pumpPhone(tester, const LoopTestPage());
      await tester.tap(find.text('导出回执（JSON 复制到剪贴板）'));
      await tester.pumpAndSettle();

      expect(native.argsOf('events').last, {'limit': 200});
      expect(copied, hasLength(1));
      final payload = copied.single;
      expect(payload, contains('"events"'));
      expect(payload, contains('"config"'));
      expect(payload, contains('"eventId": "a"'));
      expect(payload, contains('"diagnostics"'));
      expect(find.textContaining('已把 1 条回执复制到剪贴板'), findsOneWidget);
    });

    testWidgets('a damaged state file shows the backup recovery', (tester) async {
      FakeLoopNative()
        ..recoveredFrom = 'state.json.bak'
        ..install();
      await pumpPhone(tester, const LoopTestPage());
      expect(find.textContaining('已从备份'), findsOneWidget);    });

    testWidgets('phone control links to it', (tester) async {
      FakeLoopNative().install();
      await pumpPhone(tester, const PhoneControlScreen());
      await tester.scrollUntilVisible(find.text('触发测试'), 300, scrollable: find.byType(Scrollable).first);
      await tester.tap(find.text('触发测试'));
      await tester.pumpAndSettle();
      expect(find.byType(LoopTestPage), findsOneWidget);
    });
  });
}
