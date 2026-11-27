import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/live_core_atlas_page.dart';
import 'package:dsh_app/dafeiyu/ui/v6_command_page.dart';
import 'package:dsh_app/dafeiyu/ui/wanshiming_page.dart';
import 'package:dsh_app/ui/theme/theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channelName = 'dsh/proactive';
  const messenger = MethodChannel(channelName);

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      messenger,
      (call) async {
        if (call.method == 'v6Op') {
          return <String, Object?>{
            'v': 1,
            'ok': true,
            'windowMode': {'label': '桌前护城河窗 · 禁谈空泛体系'},
            'telemetry': {
              'total': 16,
              'active': 15,
              'dedupeRate72h': 98,
              'bySource': {'gemini': 4, 'quiz': 8, 'obsidian': 3},
            },
            'appeals': {'day': '2026-11-26', 'total': 1, 'physio': 0},
            'nineGrid': [
              {'id': 'g0', 'kind': 'knowledge', 'title': '另有他因削弱', 'subtitle': '5m攻克', 'badge': '弹药 15'},
              {'id': 'g1', 'kind': 'grocery', 'title': '买菜 9 宫格', 'subtitle': '防折返', 'badge': '生活'},
            ],
            'steles': [
              {
                'id': 's1',
                'title': '立《万世铭》与 V6 调度框架',
                'date': '2026-11-26',
                'epoch': '丙午',
                'category': '纪元',
                'body': '双路并进。',
                'starred': true,
              },
            ],
            'framework': {
              'wakeQuizEnabled': true,
              'wakeQuizCooldownMin': 10,
              'liveKeepAliveUnlock': true,
              'liveDropGraceSec': 90,
              'fiveMinBoutSec': 300,
              'strictLockGraceSec': 45,
              'dailyAppealLimit': 3,
              'dailyPhysioRestLimit': 1,
              'poolCooldownHours': 18,
              'stubbornGateEnabled': true,
              'stubbornCooldownMin': 20,
              'fragmentedWindows': ['11:30-13:30'],
              'deskMoatWindows': ['09:00-11:30'],
            },
            'stubbornGate': {
              'required': true,
              'reason': 'missing_declaration',
              'suggestedAnchors': [
                {'id': 'k1', 'claim': '判断推理削弱论证之另有他因 15 题', 'source': 'quiz'},
              ],
            },
            'stagingCount': 2,
          };
        }
        if (call.method == 'v6LiveSignals') {
          return <String, Object?>{
            'liveOnline': true,
            'audioInComm': true,
            'micRecording': true,
            'virtualDisplayActive': true,
            'summary': '音频通话态占用 · 虚拟共享副屏在线',
          };
        }
        return null;
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(messenger, null);
  });

  testWidgets('V6CommandPage renders animated knowledge pool and Live monitor lamps cleanly', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildDarkTheme(),
        home: V6CommandPage(channel: ProactiveChannel()),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text('V6 双模态调度中枢'), findsWidgets);
    expect(find.byKey(const ValueKey('v6-stubborn-checkin-card')), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('Live 在线免锁常驻中'),
      180,
      scrollable: find.byType(Scrollable).last,
    );
    expect(find.text('Live 在线免锁常驻中'), findsOneWidget);
    expect(find.text('摄像头占用'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('读剪贴板喂鱼 / 吸契约'),
      200,
      scrollable: find.byType(Scrollable).last,
    );
    expect(find.text('15'), findsOneWidget);
    expect(find.text('读剪贴板喂鱼 / 吸契约'), findsOneWidget);

    await tester.drag(find.byType(Scrollable).last, const Offset(0, 700));
    await tester.pump();
    await tester.tap(find.text('免发版框架'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('免发版 · 长久可调参动态规则框架'), findsOneWidget);
  });

  testWidgets('WanshimingPage renders Claude bold constellation map and focuses neighbor stars cleanly', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      messenger,
      (call) async {
        if (call.method == 'v6Op') {
          return <String, Object?>{
            'v': 1,
            'ok': true,
            'steles': [
              {
                'id': 's1',
                'title': '立实证底律星点',
                'date': '2026-11-19',
                'stage': '筑基 · 底律',
                'category': '悟道',
                'body': '先查基准率再推演。',
                'links': ['s2'],
                'starred': true,
              },
              {
                'id': 's2',
                'title': 'Gemini Live 真通话护航',
                'date': '2026-11-27',
                'stage': '破壁 · 外脑',
                'category': '战役',
                'body': '用硬件语音麦与摄像头占用稳住高压交互。',
                'links': ['s1'],
                'starred': true,
              },
            ],
          };
        }
        return null;
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: buildDarkTheme(),
        home: WanshimingPage(channel: ProactiveChannel()),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text('万世铭'), findsOneWidget);
    expect(find.text('长期核心路径星图'), findsOneWidget);
    expect(find.byKey(const ValueKey('wsm-star-map')), findsOneWidget);
    expect(find.byKey(const ValueKey('wsm-focus-card')), findsOneWidget);
    expect(find.text('先查基准率再推演。'), findsOneWidget);

    // Click neighbor star to switch focus cleanly
    await tester.tap(find.byKey(const ValueKey('wsm-star-node-s2')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('用硬件语音麦与摄像头占用稳住高压交互。'), findsOneWidget);
  });

  testWidgets('V6CommandPage submits stubborn activity declaration into v6Op', (tester) async {
    final actions = <String?>[];
    String? declaredTask;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      messenger,
      (call) async {
        if (call.method == 'v6Op') {
          final args = call.arguments as Map?;
          final act = args?['action'] as String?;
          actions.add(act);
          if (act == 'declare_activity') {
            declaredTask = args?['task'] as String?;
            return <String, Object?>{
              'v': 1,
              'ok': true,
              'message': '已纳入体系并写入临时库：$declaredTask',
              'cadence': {'active': true, 'phase': 'running', 'round': 1},
            };
          }
          return <String, Object?>{
            'v': 1,
            'ok': true,
            'windowMode': {'label': '常规自由调配窗'},
            'telemetry': {'total': 16, 'active': 15, 'dedupeRate72h': 98, 'bySource': {'quiz': 15}},
            'appeals': {'day': '2026-11-27', 'total': 0, 'physio': 0},
            'stubbornGate': {'required': true, 'suggestedAnchors': const []},
            'stagingCount': 0,
            'framework': {'wakeQuizEnabled': true, 'stubbornGateEnabled': true, 'stubbornCooldownMin': 20},
          };
        }
        return null;
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: buildDarkTheme(),
        home: V6CommandPage(channel: ProactiveChannel()),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    await tester.enterText(find.byKey(const ValueKey('v6-declare-task-input')), '粉笔资料分析基期速算 15 题');
    await tester.tap(find.byKey(const ValueKey('v6-declare-submit-btn')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(actions, contains('declare_activity'));
    expect(declaredTask, '粉笔资料分析基期速算 15 题');
  });

  testWidgets('LiveCoreAtlasPage merges V6 cadence and 3-signal Live monitor lamps', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildDarkTheme(),
        home: LiveCoreAtlasPage(channel: ProactiveChannel()),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.byKey(const ValueKey('atlas-live-lamps-row')), findsOneWidget);
    expect(find.text('Live 在线免锁'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('atlas-v6-cadence-card')),
      220,
      scrollable: find.byType(Scrollable).last,
    );
    expect(find.byKey(const ValueKey('atlas-v6-cadence-start-btn')), findsOneWidget);
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('atlas-v6-hub-card')),
      220,
      scrollable: find.byType(Scrollable).last,
    );
    expect(find.byKey(const ValueKey('atlas-v6-hub-card')), findsOneWidget);
  });

  testWidgets('V6CommandPage shows desk-moat shield when mode == desk_moat and supports nine-grid roll', (tester) async {
    final actions = <String?>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      messenger,
      (call) async {
        if (call.method == 'v6Op') {
          final args = call.arguments as Map?;
          final act = args?['action'] as String?;
          actions.add(act);
          if (act == 'nine_grid_roll') {
            return <String, Object?>{
              'v': 1,
              'ok': true,
              'cadenceArmed': true,
              'roll': {
                'tile': {'id': 'g0', 'title': '另有他因削弱'},
                'diceFace': 6,
                'diceName': '替代解释',
                'diceAsk': '如果最顺嘴解释错了怎么解释？',
                'recommendedAction': 'cadence_5m',
                'target': '做对5题判断推理并排除另有他因',
              },
            };
          }
          return <String, Object?>{
            'v': 1,
            'ok': true,
            'windowMode': {'mode': 'desk_moat', 'label': '桌前护城河窗 · 禁谈空泛体系'},
            'telemetry': {'total': 16, 'active': 15, 'dedupeRate72h': 98, 'bySource': {'gemini': 4, 'quiz': 8, 'obsidian': 3}},
            'appeals': {'day': '2026-11-27', 'total': 0, 'physio': 0},
            'nineGrid': [
              {'id': 'g0', 'kind': 'knowledge', 'diceFace': 6, 'title': '另有他因削弱', 'subtitle': '5m攻克', 'badge': '弹药 15'},
            ],
            'framework': {'wakeQuizEnabled': true, 'wakeQuizCooldownMin': 10, 'liveKeepAliveUnlock': true, 'liveDropGraceSec': 15, 'fiveMinBoutSec': 300},
          };
        }
        return null;
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: buildDarkTheme(),
        home: V6CommandPage(channel: ProactiveChannel()),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    await tester.tap(find.text('免发版框架'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.byKey(const ValueKey('v6-desk-moat-shield')), findsOneWidget);

    await tester.tap(find.text('九宫格与申诉'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    await tester.tap(find.byKey(const ValueKey('v6-nine-grid-roll-btn')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(actions, contains('nine_grid_roll'));
  });

  testWidgets('V6CommandPage nine_grid_roll with flaw_spot opens flaw-spot dialog without _busy deadlock', (tester) async {
    final actions = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      messenger,
      (call) async {
        if (call.method == 'v6Op') {
          final args = call.arguments as Map?;
          final action = args?['action'] as String? ?? '';
          actions.add(action);
          if (action == 'nine_grid_roll') {
            return <String, Object?>{
              'v': 1,
              'ok': true,
              'cadenceArmed': false,
              'roll': {
                'tile': {'id': 'grid-data', 'title': '资料错题重榨'},
                'diceFace': 4,
                'diceName': '关键提问',
                'diceAsk': '哪一个未知条件变了结论会翻盘？',
                'recommendedAction': 'flaw_spot',
                'target': '资料分析找基数偷换',
              },
            };
          }
          if (action == 'flaw_draw') {
            return <String, Object?>{
              'v': 1,
              'ok': true,
              'challenge': {
                'id': 'flaw-1',
                'topic': '偷换概念',
                'stem': '测试题干',
                'steps': ['步骤一', '步骤二', '步骤三', '步骤四'],
              },
            };
          }
          if (action == 'flaw_verify') {
            final idx = args?['pickedIndex'];
            if (idx == 0) {
              return <String, Object?>{
                'v': 1,
                'ok': true,
                'correct': false,
                'explanation': '第1步不是漏洞',
                'nextChallenge': {
                  'id': 'flaw-2',
                  'topic': '强加因果',
                  'stem': '第二题干',
                  'steps': ['新一步', '新二步', '新三步', '新四步'],
                },
              };
            }
            return <String, Object?>{
              'v': 1,
              'ok': true,
              'correct': true,
              'explanation': '抓准基数偷换',
            };
          }
          return <String, Object?>{
            'v': 1,
            'ok': true,
            'windowMode': {'mode': 'general', 'label': '常规自由调配窗'},
            'telemetry': {'total': 10, 'active': 10, 'dedupeRate72h': 100, 'bySource': {'quiz': 10}},
            'appeals': {'day': '2026-11-27', 'total': 0, 'physio': 0},
            'nineGrid': [
              {'id': 'grid-data', 'kind': 'bout', 'diceFace': 4, 'title': '资料错题重榨', 'subtitle': '抓坑', 'badge': '提分'},
            ],
            'framework': {'wakeQuizEnabled': true, 'wakeQuizCooldownMin': 10, 'liveKeepAliveUnlock': true, 'liveDropGraceSec': 15, 'fiveMinBoutSec': 300},
          };
        }
        return null;
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: buildDarkTheme(),
        home: V6CommandPage(channel: ProactiveChannel(), initialTab: V6CommandTab.nineGridAndAppeal),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    await tester.tap(find.byKey(const ValueKey('v6-nine-grid-roll-btn')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(actions, containsAll(['nine_grid_roll', 'flaw_draw']));
    expect(find.textContaining('残局挑刺 · 偷换概念'), findsOneWidget);

    // Wrong pick displays inline feedback and swaps to nextChallenge
    await tester.tap(find.text('1. 步骤一'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.textContaining('没抓准：第1步不是漏洞'), findsOneWidget);
    expect(find.textContaining('残局挑刺 · 强加因果'), findsOneWidget);

    // Right pick closes dialog
    await tester.tap(find.text('2. 新二步'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.textContaining('残局挑刺 · 强加因果'), findsNothing);
  });
}
