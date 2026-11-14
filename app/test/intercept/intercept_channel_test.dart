// 娱乐拦截设置页的通道契约测试 (round 4 hardening).
//
// The page above this file trusts three things about `dsh/intercept`, and each of them is a way to
// lie to the person if it breaks:
//
//  1. a missing channel must be *stated*, not shown as an empty config (which would read as
//     "everything is off" on a desktop build);
//  2. the wire map is read defensively, because a version skew must degrade to a declared error;
//  3. every count is separate — 触发 / 拦住 / 放行 / 退出 / 暂停 are five different facts.
//
// Nothing here talks to Android: the channel is a MethodChannel, so the test installs a mock handler
// and asserts what the page would see.

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:dsh_app/intercept/intercept_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channelName = 'dsh/intercept';
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  void mock(Future<Object?>? Function(MethodCall call) handler) {
    messenger.setMockMethodCallHandler(const MethodChannel(channelName), (call) async => handler(call));
  }

  tearDown(() => messenger.setMockMethodCallHandler(const MethodChannel(channelName), null));

  Map<String, Object?> statusMap({
    bool enabled = true,
    List<Map<String, Object?>> targets = const [],
    int pauseUntilWall = 0,
    Map<String, Object?> counts = const {},
    List<Map<String, Object?>> receipts = const [],
    String? active,
    String? storageError,
    String? recoveryIssue,
  }) =>
      {
        'config': {
          'enabled': enabled,
          'targets': targets,
          'whitelist': <String>[],
          'pauseUntilWall': pauseUntilWall,
          'releaseMs': 600000,
          'trainingMs': 60000,
          'cooldownMs': 120000,
          'dailyMax': 3,
        },
        'counts': counts,
        'receipts': receipts,
        'active': active,
        'storageError': storageError,
        'recoveryIssue': recoveryIssue,
      };

  test('a missing host is stated, not read as an empty config', () async {
    mock((call) => Future.error(MissingPluginException()));
    final channel = InterceptChannel();
    expect(await channel.available(), isFalse);
    await expectLater(channel.status(), throwsA(isA<InterceptUnavailable>()));
  });

  test('a platform failure is a declared error, not a crash', () async {
    mock((call) => Future.error(PlatformException(code: 'boom', message: 'storage')));
    final channel = InterceptChannel();
    await expectLater(
      channel.status(),
      throwsA(isA<InterceptFailed>().having((e) => e.cause, 'cause', 'storage')),
    );
  });

  test('the config keys round-trip without loss', () async {
    mock((call) async => {
          ...statusMap(),
          'config': {
            'enabled': true,
            'targets': <Map<String, Object?>>[],
            'whitelist': ['com.example.keep'],
            'pauseUntilWall': 0,
            'releaseMs': 900000,
            'trainingMs': 30000,
            'cooldownMs': 300000,
            'dailyMax': 5,
            'lockMode': 'strict',
          },
          'effectiveLockMode': 'strict',
          'focusActive': true,
          'activeReleasesCount': 2,
        });
    final s = await InterceptChannel().status();
    expect(s.config.enabled, isTrue);
    expect(s.config.dailyMax, 5);
    expect(s.config.releaseMs, 900000);
    expect(s.config.trainingMs, 30000);
    expect(s.config.cooldownMs, 300000);
    expect(s.config.whitelist, {'com.example.keep'});
    expect(s.config.lockMode, InterceptLockMode.strict);
    expect(s.effectiveLockMode, InterceptLockMode.strict);
    expect(s.focusActive, isTrue);
    expect(s.activeReleasesCount, 2);
  });

  test('the five ledger counters stay five separate facts', () async {
    mock((call) async => statusMap(counts: {
          'triggerCount': 9,
          'blockedCount': 7,
          'userReleasedCount': 3,
          'userExitedCount': 4,
          'pausedCount': 1,
        }));
    final s = await InterceptChannel().status();
    expect(s.counts.triggerCount, 9);
    expect(s.counts.blockedCount, 7);
    expect(s.counts.userReleasedCount, 3);
    expect(s.counts.userExitedCount, 4);
    expect(s.counts.pausedCount, 1);
  });

  test('a damaged map degrades to defaults instead of throwing', () async {
    mock((call) async => {'config': 'not a map', 'counts': 7, 'receipts': 'nope'});
    final s = await InterceptChannel().status();
    expect(s.config.enabled, isFalse);
    expect(s.config.targets, isEmpty);
    expect(s.counts.triggerCount, 0);
    expect(s.receipts, isEmpty);
  });

  test('receipts carry the outcome and who released it', () async {
    // receipts() calls the channel's own op, which answers with a bare list (status() answers with
    // a map that also embeds a short list). The mock has to honour that difference.
    mock((call) async => call.method == 'receipts' ? [
          {
            'eventId': 'evt-1',
            'packageName': 'tv.danmaku.bili',
            'outcome': 'RELEASED',
            'triggeredAt': 1000,
            'endedAt': 2000,
            'reason': 'training',
            'releasedBy': 'training',
          },
          {'eventId': 'evt-2', 'packageName': 'com.ss.android.ugc.aweme', 'outcome': 'EXITED', 'triggeredAt': 1, 'endedAt': 2},
          {'not an id': true},
        ] : statusMap());
    final receipts = await InterceptChannel().receipts();
    expect(receipts, hasLength(2), reason: 'a receipt without an eventId is not a receipt');
    expect(receipts.first.outcome, 'RELEASED');
    expect(receipts.first.releasedBy, 'training');
    expect(receipts.last.outcome, 'EXITED');
  });

  test('a pause is visible as a pause, and a spent one is not', () async {
    mock((call) async => statusMap(
          pauseUntilWall: DateTime.now().add(const Duration(minutes: 10)).millisecondsSinceEpoch,
        ));
    expect((await InterceptChannel().status()).paused, isTrue);

    mock((call) async => statusMap(
          pauseUntilWall: DateTime.now().subtract(const Duration(minutes: 10)).millisecondsSinceEpoch,
        ));
    expect((await InterceptChannel().status()).paused, isFalse,
        reason: 'a pause that ran out is not a pause');
  });

  test('the kernel errors are surfaced, never swallowed', () async {
    mock((call) async => statusMap(storageError: 'disk full', recoveryIssue: 'unreadable'));
    final s = await InterceptChannel().status();
    expect(s.storageError, 'disk full');
    expect(s.recoveryIssue, 'unreadable');
  });

  test('patch sends only the keys it changes', () async {
    final sent = <Map<Object?, Object?>?>[];
    mock((call) async {
      if (call.method == 'setConfig') sent.add(call.arguments as Map<Object?, Object?>?);
      return statusMap();
    });
    await InterceptChannel().patch({'enabled': true});
    expect(sent.single, {'enabled': true},
        reason: 'a partial write must not carry keys the page did not change');
  });

  test('targets are dropped when the package name is unusable', () async {
    mock((call) async => statusMap(targets: [
          {'packageName': 'tv.danmaku.bili', 'label': 'B站'},
          {'packageName': '  ', 'label': 'blank'},
          {'label': 'no package'},
          {'packageName': 'com.x'},
        ]));
    final s = await InterceptChannel().status();
    expect(s.config.targets.map((t) => t.packageName), ['tv.danmaku.bili', 'com.x']);
    expect(s.config.targets.first.label, 'B站');
    expect(s.config.targets.last.label, 'com.x', reason: 'a missing label falls back to the package');
  });

  test('whitelist and lock recovery channel ops forward expected method names', () async {
    final calls = <String>[];
    mock((call) async {
      calls.add(call.method);
      return statusMap();
    });
    await InterceptChannel().removeWhitelist('tv.danmaku.bili');
    await InterceptChannel().clearWhitelist();
    await InterceptChannel().restoreLockNow();
    expect(calls, ['removeWhitelist', 'clearWhitelist', 'restoreLockNow']);
  });
}
