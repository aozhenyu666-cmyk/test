import 'package:dsh_app/intercept/intercept_channel.dart';
import 'package:dsh_app/loop/loop_channel.dart';
import 'package:dsh_app/ui/phone/intercept_page.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../phone/harness.dart';

class _FakeLoopChannel extends LoopChannel {
  _FakeLoopChannel(this._apps);

  final List<LoopApp> _apps;

  @override
  Future<List<LoopApp>> apps() async => _apps;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channelName = 'dsh/intercept';
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late Map<String, Object?> wireConfig;
  late int clearWhitelistCalls;
  late int restoreLockCalls;

  Map<String, Object?> buildWireStatus() => {
        'config': wireConfig,
        'effectiveLockMode': wireConfig['lockMode'] ?? 'training',
        'focusActive': true,
        'activeReleasesCount': 1,
        'counts': {
          'triggerCount': 5,
          'blockedCount': 4,
          'userReleasedCount': 1,
          'userExitedCount': 2,
          'pausedCount': 0,
        },
        'receipts': const <Map<String, Object?>>[],
      };

  setUp(() {
    clearWhitelistCalls = 0;
    restoreLockCalls = 0;
    wireConfig = {
      'enabled': true,
      'lockMode': 'strict',
      'targets': [
        {'packageName': 'tv.danmaku.bili', 'label': '哔哩哔哩'},
      ],
      'whitelist': ['com.ss.android.ugc.aweme'],
      'pauseUntilWall': 0,
      'releaseMs': 600000,
      'trainingMs': 30000,
      'cooldownMs': 120000,
      'dailyMax': 0,
    };

    messenger.setMockMethodCallHandler(const MethodChannel(channelName), (call) async {
      switch (call.method) {
        case 'status':
          return buildWireStatus();
        case 'setConfig':
          final patch = (call.arguments as Map).cast<String, Object?>();
          wireConfig = {...wireConfig, ...patch};
          return buildWireStatus();
        case 'clearWhitelist':
          clearWhitelistCalls++;
          wireConfig = {...wireConfig, 'whitelist': <String>[]};
          return buildWireStatus();
        case 'restoreLockNow':
          restoreLockCalls++;
          wireConfig = {...wireConfig, 'pauseUntilWall': 0};
          return {...buildWireStatus(), 'activeReleasesCount': 0};
        default:
          return buildWireStatus();
      }
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(const MethodChannel(channelName), null);
  });

  testWidgets('InterceptPage enforces hold-to-confirm when relaxing strict mode and clears whitelist', (tester) async {
    final loop = _FakeLoopChannel(const [
      LoopApp('com.tencent.tmgp.sgame', '王者荣耀'),
      LoopApp('com.xingin.xhs', '小红书'),
    ]);

    await pumpPhone(
      tester,
      InterceptPage(channel: InterceptChannel(), loopChannel: loop),
    );

    expect(find.text('娱乐锁机护栏运行中'), findsOneWidget);
    expect(find.text('严格锁机'), findsWidgets);

    // Tapping '冷静训练' from strict mode opens hold-to-confirm dialog instead of immediately relaxing
    await tester.tap(find.text('冷静训练'));
    await settlePhone(tester);
    expect(find.text('放宽锁机模式'), findsOneWidget);
    expect(wireConfig['lockMode'], InterceptLockMode.strict);
    await tester.tap(find.text('取消'));
    await settlePhone(tester);
    expect(wireConfig['lockMode'], InterceptLockMode.strict);

    // One-tap recovery clears accidental whitelist and restores lock immediately
    expect(find.text('com.ss.android.ugc.aweme'), findsOneWidget);
    await tester.tap(find.text('清空全部白名单并立即恢复锁定'));
    await settlePhone(tester);
    expect(clearWhitelistCalls, 1);
    expect(restoreLockCalls, 1);
    expect((wireConfig['whitelist'] as List), isEmpty);
  });

  testWidgets('InterceptPage add-target dialog supports 1-tap presets and installed apps', (tester) async {
    final loop = _FakeLoopChannel(const [
      LoopApp('com.demo.game', '测试手游'),
    ]);

    await pumpPhone(
      tester,
      InterceptPage(channel: InterceptChannel(), loopChannel: loop),
    );

    await tester.tap(find.text('添加娱乐应用'));
    await settlePhone(tester);

    expect(find.text('常用娱乐 / 游戏一键勾选'), findsOneWidget);
    expect(find.text('测试手游'), findsOneWidget);

    await tester.tap(find.text('王者荣耀'));
    await settlePhone(tester);
    await tester.tap(find.text('测试手游'));
    await settlePhone(tester);

    expect(find.text('添加 (2)'), findsOneWidget);
    await tester.tap(find.text('添加 (2)'));
    await settlePhone(tester);

    final targets = (wireConfig['targets'] as List).cast<Map>();
    final packages = targets.map((e) => e['packageName']).toSet();
    expect(packages, containsAll(['tv.danmaku.bili', 'com.tencent.tmgp.sgame', 'com.demo.game']));
  });
}
