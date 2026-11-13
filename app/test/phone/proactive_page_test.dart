// D3: 设置 → 手机操控 → 主动搭话 over a fake `dsh/proactive` channel.

import 'package:dsh_app/proactive/proactive_state.dart';
import 'package:dsh_app/ui/phone/phone_control_screen.dart';
import 'package:dsh_app/ui/phone/proactive_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'harness.dart';

class FakeProactiveNative {
  Map<String, Object?> config = {
    'enabled': false,
    'minIntervalMs': 45 * 60000,
    'maxIntervalMs': 180 * 60000,
    'quietStartMinute': 23 * 60,
    'quietEndMinute': 8 * 60,
    'dailyMax': 6,
    'pauseAfterIgnored': 3,
  };
  String? hold;
  String wakeMode = 'EXACT';
  bool transport = true;
  int queued = 0;
  String? storageError;
  String? recoveryIssue;
  String? activeStage;
  List<Map<String, Object?>> receipts = [];
  /// What the plugin's /status answers: the configured target and the live agents.
  String? sessionId;
  List<Map<String, Object?>> agents = [
    {'id': 'session-a', 'status': 'idle'},
    {'id': 'session-b', 'status': 'running'},
  ];
  /// Set to make `agentSessions` answer an error map instead.
  String? sessionsError;
  final calls = <MethodCall>[];

  void install() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('dsh/proactive'),
      (call) async {
        calls.add(call);
        switch (call.method) {
          case 'status':
            return {
              'config': config,
              'gate': {'hold': hold, 'surface': null},
              'wakeMode': wakeMode,
              'alarmError': null,
              'transport': transport,
              'queued': queued,
              'storageError': storageError,
              'recoveryIssue': recoveryIssue,
              'active': activeStage == null ? null : {'stage': activeStage},
            };
          case 'setConfig':
            config = {...config, ...(call.arguments as Map).cast<String, Object?>()};
            return config;
          case 'receipts':
            return receipts;
          case 'agentSessions':
            if (sessionsError != null) {
              return {
                'error': {'code': sessionsError},
              };
            }
            return {'sessionId': sessionId, 'agents': agents};
          case 'agentSessionSet':
            sessionId = (call.arguments as Map)['sessionId'] as String?;
            return {'sessionId': sessionId, 'agents': agents};
        }
        return null;
      },
    );
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('dsh/proactive'),
        null,
      ),
    );
  }

  List<Object?> argsOf(String method) => [for (final c in calls) if (c.method == method) c.arguments];
}

Map<String, Object?> receipt({
  String id = 'e1',
  String stage = 'CLOSED',
  String? outcome = 'RESPONDED',
  String? surface = 'activity',
  int? deliveredAt,
  int? shownAt,
}) => {
  'request': {
    'eventId': id,
    'source': 'SCHEDULER',
    'kind': 'random_chat',
    'dedupeKey': 'k',
    'createdAt': DateTime(2026, 10, 30, 9).millisecondsSinceEpoch,
  },
  'stage': stage,
  'outcome': outcome,
  'reason': null,
  'content': {'text': 'hi', 'source': 'AGENT'},
  'surface': surface,
  'deliveredAt': deliveredAt,
  'shownAt': shownAt,
  'respondedAt': shownAt,
  'closedAt': DateTime(2026, 10, 30, 9, 5).millisecondsSinceEpoch,
};

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('wire', () {
    test('a receipt keeps the six moments apart', () {
      final r = ProactiveReceipt.fromWire(receipt(deliveredAt: 1000, shownAt: 2000))!;
      expect(r.eventId, 'e1');
      expect(r.kind, 'random_chat');
      expect(r.outcome, 'RESPONDED');
      expect(r.deliveredAt!.millisecondsSinceEpoch, 1000);
      expect(r.shownAt!.millisecondsSinceEpoch, 2000);
      expect(ProactiveReceipt.fromWire({'stage': 'CLOSED'}), isNull);
      expect(ProactiveReceipt.fromWire('nope'), isNull);
    });

    test('status survives missing and odd fields', () {
      final s = ProactiveStatus.fromWire({
        'config': {'enabled': true, 'dailyMax': 3.0},
        'gate': {'hold': 'dsh_task'},
        'wakeMode': 'OPPORTUNISTIC',
      });
      expect(s.config.enabled, isTrue);
      expect(s.config.dailyMax, 3);
      expect(s.holdReason, 'dsh_task');
      expect(s.wakeRestricted, isTrue);
      expect(ProactiveStatus.fromWire(null).transport, isFalse);
    });
  });

  group('page', () {
    testWidgets('shows the state, the honest wake warning and the receipts', (tester) async {
      final native = FakeProactiveNative()
        ..hold = 'dsh_task'
        ..wakeMode = 'OPPORTUNISTIC'
        ..queued = 2
        ..receipts = [receipt()];
      native.install();
      await pumpPhone(tester, const ProactivePage());
      expect(find.text('主动搭话'), findsWidgets);
      // The hold line reads "正在让路：DSH 正在操作手机"; the hint above it says the same words, so
      // match the composed line rather than the fragment.
      expect(find.textContaining('正在让路：DSH 正在操作手机'), findsOneWidget);
      expect(find.textContaining('限制了后台唤醒'), findsOneWidget);
      expect(find.textContaining('排队中：2 条'), findsOneWidget);
      // 回执是这一页最后一张卡，手机屏上要滚下去才看得见。
      final responded = find.text('你回应了');
      await tester.scrollUntilVisible(responded, 300, scrollable: find.byType(Scrollable).first);
      expect(responded, findsWidgets);
    });

    testWidgets('the switch, gaps, quiet hours and daily limit write config', (tester) async {
      // 插件不在线时这几行才由手机侧兜底（写 setConfig）；在线时它们换成大肥鱼自己的参数。
      final native = FakeProactiveNative()
        ..transport = false
        ..install();
      await pumpPhone(tester, const ProactivePage());

      // 诊断卡里还有「前台包名记录（调试）」的开关，这一张是总开关。
      await tester.tap(find.byType(Switch).first);
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, containsPair('enabled', true));

      await tester.tap(find.text('最短间隔'));
      await tester.pumpAndSettle();
      // Both intervals are typed now: the dialog opens on the current value, and whatever is
      // entered is sent as minutes x 60000. 1 is the lowest the kernel accepts.
      await tester.enterText(find.byType(TextField), '1');
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, containsPair('minIntervalMs', 1 * 60000));

      await tester.tap(find.text('最长间隔'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '45');
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig').last, containsPair('maxIntervalMs', 45 * 60000));

      await tester.tap(find.text('免打扰时段'));
      await tester.pumpAndSettle();
      // 23:00–08:00 is the first preset, so the next tap is 22:00–08:00.
      expect(native.argsOf('setConfig').last, containsPair('quietStartMinute', 22 * 60));

      await tester.tap(find.text('每天上限'));
      await tester.pumpAndSettle();
      // 6 → 10.
      expect(native.argsOf('setConfig').last, containsPair('dailyMax', 10));
    });

    testWidgets('a typed gap outside the range is refused, not sent', (tester) async {
      final native = FakeProactiveNative()
        ..transport = false
        ..install();
      await pumpPhone(tester, const ProactivePage());
      await tester.tap(find.text('最短间隔'));
      await tester.pumpAndSettle();

      final before = native.argsOf('setConfig').length;
      await tester.enterText(find.byType(TextField), '0');
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();
      // The dialog stays open with an error; nothing reached the native side.
      expect(native.argsOf('setConfig'), hasLength(before));
      expect(find.textContaining('1–10080'), findsWidgets);

      await tester.enterText(find.byType(TextField), '99999');
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();
      expect(native.argsOf('setConfig'), hasLength(before));
    });

    testWidgets('the gaps can never cross', (tester) async {
      final native = FakeProactiveNative()
        ..transport = false
        ..config = {
          ...FakeProactiveNative().config,
          'minIntervalMs': 10 * 60000,
          'maxIntervalMs': 20 * 60000,
        }
        ..install();
      await pumpPhone(tester, const ProactivePage());
      // Raising the minimum past the maximum lifts the maximum with it.
      await tester.tap(find.text('最短间隔'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '60');
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();
      final sent = native.argsOf('setConfig').last as Map;
      expect(sent['minIntervalMs'], 60 * 60000);
      expect((sent['minIntervalMs'] as int) <= (sent['maxIntervalMs'] as int), isTrue);
    });

    testWidgets('诊断卡：导出诊断和前台包名记录开关都在', (tester) async {
      FakeProactiveNative()..install();
      await pumpPhone(tester, const ProactivePage());
      final export = find.text('导出诊断');
      await tester.scrollUntilVisible(export, 300, scrollable: find.byType(Scrollable).first);
      expect(export, findsOneWidget);
      expect(find.text('前台包名记录（调试）'), findsOneWidget);
      expect(find.textContaining('打成一个 zip 存到「下载」'), findsOneWidget);
    });

    testWidgets('a missing transport is stated instead of pretending', (tester) async {
      FakeProactiveNative()
        ..transport = false
        ..install();
      await pumpPhone(tester, const ProactivePage());
      expect(find.textContaining('还没连上引擎'), findsOneWidget);
    });

    testWidgets('the target session can be picked from the live ones', (tester) async {
      final native = FakeProactiveNative()..install();
      await pumpPhone(tester, const ProactivePage());
      // Nothing chosen yet: the row says so instead of showing a fake target.
      expect(find.text('不指定'), findsWidgets);

      await tester.tap(find.text('目标会话'));
      await tester.pumpAndSettle();
      expect(find.text('session-a'), findsOneWidget);
      expect(find.text('session-b'), findsOneWidget);
      expect(find.text('空闲，可以发'), findsOneWidget);
      expect(find.textContaining('正在忙'), findsOneWidget);

      await tester.tap(find.text('session-a'));
      await tester.pumpAndSettle();
      expect(native.argsOf('agentSessionSet').last, {'sessionId': 'session-a'});
      expect(find.textContaining('发到 session-a'), findsOneWidget);
    });

    testWidgets('the target can be cleared again', (tester) async {
      final native = FakeProactiveNative()
        ..sessionId = 'session-a'
        ..install();
      await pumpPhone(tester, const ProactivePage());
      expect(find.textContaining('发到 session-a'), findsOneWidget);

      await tester.tap(find.text('目标会话'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('不指定').last);
      await tester.pumpAndSettle();
      expect(native.argsOf('agentSessionSet').last, {'sessionId': null});
    });

    testWidgets('an unreadable session list is explained, not hidden', (tester) async {
      FakeProactiveNative()
        ..sessionsError = 'no_transport'
        ..install();
      await pumpPhone(tester, const ProactivePage());
      expect(find.textContaining('读不到会话列表'), findsOneWidget);
      expect(find.textContaining('no_transport'), findsOneWidget);
    });

    testWidgets('no live session is said plainly', (tester) async {
      FakeProactiveNative()
        ..agents = []
        ..install();
      await pumpPhone(tester, const ProactivePage());
      await tester.tap(find.text('目标会话'));
      await tester.pumpAndSettle();
      expect(find.textContaining('没有活着的会话'), findsOneWidget);
    });

    testWidgets('a damaged kernel state is surfaced', (tester) async {
      FakeProactiveNative()
        ..recoveryIssue = 'state_unreadable: JsonParseException'
        ..storageError = 'IOException: disk full'
        ..install();
      await pumpPhone(tester, const ProactivePage());
      expect(find.textContaining('上次的状态读不出来'), findsOneWidget);
      expect(find.textContaining('回执保存失败'), findsOneWidget);
    });

    testWidgets('phone control links to it', (tester) async {
      FakeProactiveNative().install();
      await pumpPhone(tester, const PhoneControlScreen());
      await tester.scrollUntilVisible(find.text('主动搭话'), 300, scrollable: find.byType(Scrollable).first);
      await tester.tap(find.text('主动搭话').first);
      await tester.pumpAndSettle();
      expect(find.byType(ProactivePage), findsOneWidget);
    });
  });
}
