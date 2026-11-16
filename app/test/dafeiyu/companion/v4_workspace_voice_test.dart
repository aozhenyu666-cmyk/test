import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bouts_workspace_page.dart';
import 'package:dsh_app/dafeiyu/ui/companion_hub_page.dart';
import 'package:dsh_app/dafeiyu/ui/voice_settings_page.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeV4Channel extends ProactiveChannel {
  List<Map<String, dynamic>> clocks = [
    {
      'id': 'clk_pomodoro',
      'label': '25/5 核心番茄',
      'kind': 'CYCLE_PHASE',
      'paused': false,
      'enabled': true,
      'nextFireWall': DateTime.now().millisecondsSinceEpoch + 120000,
    },
  ];
  Map<String, dynamic> bouts = {
    'running': {
      'title': '推进 v4.1 收尾',
      'strict': true,
      'until': DateTime.now().millisecondsSinceEpoch + 900000,
      'allow': ['md.obsidian'],
    },
    'queue': [],
  };
  bool storageGranted = false;
  int restartCalls = 0;
  final List<String> controlOps = [];

  @override
  Future<List<Map<String, dynamic>>> clockList() async => clocks;

  @override
  Future<Map<String, dynamic>?> clockControl(String id, String op) async {
    controlOps.add('$id:$op');
    clocks = [
      for (final c in clocks)
        if (c['id'] == id) {...c, 'paused': op == 'pause'} else c,
    ];
    return {'ok': true};
  }

  @override
  Future<Map<String, dynamic>?> boutsStatus() async => bouts;

  @override
  Future<Map<String, dynamic>?> boutsCommand(Map<String, Object?> body) async => {'ok': true, 'say': 'ok'};

  @override
  Future<Map<String, dynamic>?> phoneStorageStatus() async => {'granted': storageGranted};

  @override
  Future<Map<String, dynamic>?> enablePhoneStorage({bool restart = true}) async {
    storageGranted = true;
    return {'ok': true, 'granted': true};
  }

  @override
  Future<bool> restartEngine() async {
    restartCalls++;
    return true;
  }

  @override
  Future<Map<String, Object?>?> wallStatus() async => {'locked': false};

  @override
  Future<ProactiveStatus?> status() async => const ProactiveStatus(config: ProactiveConfig(enabled: true));

  @override
  Future<DepositView?> deposit({String? department, String? report, String? thought, String? text, String? taskId}) async =>
      const DepositView();

  @override
  Future<SistersView?> sisters() async => const SistersView();

  @override
  Future<Map<String, Object?>?> planDay() async => const {'plan': {'items': []}};

  @override
  Future<GuardianPairingStatus?> guardianStatus() async => const GuardianPairingStatus();
}

void main() {
  testWidgets('BoutsWorkspacePage renders live bout and kernel clock tabs', (tester) async {
    final ch = _FakeV4Channel();
    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch));
    await settlePhone(tester);

    expect(find.text('正在进行的局'), findsOneWidget);
    expect(find.text('推进 v4.1 收尾'), findsOneWidget);
    expect(find.text('严格锁'), findsOneWidget);

    await tester.tap(find.text('时钟与账'));
    await settlePhone(tester);

    expect(find.text('25/5 核心番茄'), findsOneWidget);
    await tester.tap(find.byTooltip('暂停'));
    await settlePhone(tester);
    expect(ch.controlOps, ['clk_pomodoro:pause']);
  });

  testWidgets('VoiceSettingsPage shows 3 independent voice roles', (tester) async {
    await pumpPhone(tester, const VoiceSettingsPage());
    await settlePhone(tester);

    expect(find.text('语音与角色声线'), findsWidgets);
    expect(find.text('鱼丸（妹妹）'), findsOneWidget);
    expect(find.text('鲸澜（姐姐）'), findsOneWidget);
    expect(find.text('局官（督战）'), findsOneWidget);
    expect(find.text('试听'), findsNWidgets(3));
  });

  testWidgets('CompanionHubPage exposes Obsidian storage mount and hot restart', (tester) async {
    final ch = _FakeV4Channel();
    await pumpPhone(tester, CompanionHubPage(channel: ch));
    await settlePhone(tester);

    await tester.scrollUntilVisible(find.text('手机目录与 Obsidian 自动挂载'), 200);
    expect(find.text('手机目录与 Obsidian 自动挂载'), findsOneWidget);
    await tester.tap(find.text('手机目录与 Obsidian 自动挂载'));
    await settlePhone(tester);
    expect(ch.storageGranted, isTrue);

    await tester.scrollUntilVisible(find.text('一键热重启主动引擎'), 200);
    await tester.tap(find.text('一键热重启主动引擎'));
    await settlePhone(tester);
    expect(ch.restartCalls, 1);
  });
}
