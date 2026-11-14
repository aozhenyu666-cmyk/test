import 'package:dsh_app/proactive/proactive_state.dart';
import 'package:dsh_app/ui/companion/companion_hub_page.dart';
import 'package:dsh_app/ui/phone/wall_page.dart';
import 'package:flutter_test/flutter_test.dart';

import '../phone/harness.dart';

class _FakeHubChannel extends ProactiveChannel {
  _FakeHubChannel({
    this.wall,
    this.proactive,
    this.dep,
    this.sistersView,
    this.plan,
  });

  final Map<String, Object?>? wall;
  final ProactiveStatus? proactive;
  final DepositView? dep;
  final SistersView? sistersView;
  final Map<String, Object?>? plan;

  int tickCalls = 0;
  int focusCalls = 0;
  GuardianPairingStatus guardian = const GuardianPairingStatus(installed: true, paired: false, sameCert: false);

  @override
  Future<GuardianPairingStatus?> guardianStatus() async => guardian;

  @override
  Future<GuardianPairingStatus?> generateGuardianPairCode() async {
    guardian = const GuardianPairingStatus(
      installed: true,
      paired: false,
      sameCert: false,
      pairCode: '482916',
      pairCodeExpiresAtWall: 9999999999999,
    );
    return guardian;
  }

  @override
  Future<Map<String, Object?>?> wallStatus() async => wall;

  @override
  Future<WallThread?> wallThread() async => const WallThread();

  @override
  Future<ProactiveStatus?> status() async => proactive;

  @override
  Future<DepositView?> deposit({String? department, String? report, String? thought, String? text, String? taskId}) async => dep;

  @override
  Future<SistersView?> sisters() async => sistersView;

  @override
  Future<Map<String, Object?>?> planDay() async => plan;

  @override
  Future<bool> tickNow() async {
    tickCalls++;
    return true;
  }

  @override
  Future<bool> openFocusControl() async {
    focusCalls++;
    return true;
  }
}

void main() {
  testWidgets('renders live companion hub status cards and badges', (tester) async {
    final ch = _FakeHubChannel(
      wall: {'locked': true, 'why': '正在专注写代码'},
      proactive: const ProactiveStatus(config: ProactiveConfig(enabled: true, dailyMax: 6)),
      dep: const DepositView(
        items: [
          DepositItem(key: 'info', label: '信息收集', done: true),
          DepositItem(key: 'plan', label: '谋划思考', done: false),
        ],
        done: 1,
        total: 2,
      ),
      sistersView: const SistersView(audit: SistersAudit(done: 3), planGate: true),
      plan: {
        'plan': {
          'items': [
            {'protocolId': 'execute', 'title': '写代码'},
          ],
        },
      },
    );

    await pumpPhone(tester, CompanionHubPage(channel: ch));

    expect(find.text('大肥鱼中枢'), findsWidgets);
    expect(find.text('专注守护 · 娱乐锁开启中'), findsOneWidget);
    expect(find.text('锁定中'), findsOneWidget);
    expect(find.text('待排局'), findsOneWidget);
    expect(find.text('1/2 段'), findsOneWidget);
    expect(find.text('今日完成 3'), findsOneWidget);
  });

  testWidgets('handles offline companion engine gracefully', (tester) async {
    final ch = _FakeHubChannel();
    await pumpPhone(tester, CompanionHubPage(channel: ch));

    expect(find.text('伴侣引擎暂未应答'), findsOneWidget);
    expect(find.text('就绪'), findsOneWidget);
  });

  testWidgets('triggers proactive tick and native focus control actions', (tester) async {
    final ch = _FakeHubChannel();
    await pumpPhone(tester, CompanionHubPage(channel: ch));

    await tester.tap(find.text('立即触发一次状态巡检'));
    await settlePhone(tester);
    expect(ch.tickCalls, 1);

    await tester.tap(find.text('专注监督'));
    await settlePhone(tester);
    expect(ch.focusCalls, 1);

    await tester.tap(find.text('大肥鱼哨兵互信配对'));
    await settlePhone(tester);
    expect(find.text('生成 6 位配对码'), findsOneWidget);
    await tester.tap(find.text('生成 6 位配对码'));
    await settlePhone(tester);
    expect(find.text('482916'), findsOneWidget);
    await tester.tap(find.text('完成'));
    await settlePhone(tester);
  });

  testWidgets('WallPage exposes top-right shortcut to CompanionHubPage', (tester) async {
    final ch = _FakeHubChannel();
    await pumpPhone(tester, WallPage(channel: ch));

    final btn = find.byTooltip('大肥鱼伴侣中枢');
    expect(btn, findsOneWidget);
    await tester.tap(btn);
    await settlePhone(tester);

    expect(find.byType(CompanionHubPage), findsOneWidget);
    expect(find.text('大肥鱼中枢'), findsWidgets);
  });
}
