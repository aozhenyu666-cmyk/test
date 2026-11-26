import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
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
              'fragmentedWindows': ['11:30-13:30'],
              'deskMoatWindows': ['09:00-11:30'],
            },
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
    expect(find.text('Live 在线免锁常驻中'), findsOneWidget);
    expect(find.text('15'), findsOneWidget);
    expect(find.text('读剪贴板喂鱼 / 吸契约'), findsOneWidget);

    await tester.tap(find.text('免发版框架'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('免发版 · 长久可调参动态规则框架'), findsOneWidget);
  });

  testWidgets('WanshimingPage renders museum stele chronicle in dark obsidian gold', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildDarkTheme(),
        home: WanshimingPage(channel: ProactiveChannel()),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text('万世铭'), findsOneWidget);
    expect(find.text('立《万世铭》与 V6 调度框架'), findsOneWidget);
  });
}
