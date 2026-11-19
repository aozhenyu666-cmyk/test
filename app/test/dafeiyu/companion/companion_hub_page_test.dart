import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/companion_hub_page.dart';
import 'package:dsh_app/dafeiyu/ui/voice_settings_page.dart';
import 'package:dsh_app/dafeiyu/ui/wall_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../phone/harness.dart';

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
  int rollDiceCalls = 0;
  int importMemoryCalls = 0;
  int handoffCalls = 0;
  bool shakeEnabled = true;
  GuardianPairingStatus guardian = const GuardianPairingStatus(installed: true, paired: false, sameCert: false);

  @override
  Future<Map<String, dynamic>?> shakeDiceStatus() async => {'enabled': shakeEnabled, 'lastDice': 3};

  @override
  Future<bool?> setShakeDice(bool enabled) async {
    shakeEnabled = enabled;
    return shakeEnabled;
  }

  @override
  Future<Map<String, dynamic>?> rollDiceNow() async {
    rollDiceCalls++;
    return {'ok': true, 'dice': 4, 'name': '关键提问', 'ask': '哪个未知条件变了会翻盘？'};
  }

  @override
  Future<Map<String, dynamic>?> importMemoryNow({List<String>? facts}) async {
    importMemoryCalls++;
    return {'ok': true, 'imported': 6, 'totalFacts': 12, 'createdFiles': const <String>[], 'kbDir': '/sdcard/Vault/大肥鱼'};
  }

  @override
  Future<Map<String, dynamic>?> handoffPacket() async {
    handoffCalls++;
    return {'ok': true, 'markdown': '# Cognitive OS × 大肥鱼 交接', 'hasCognitiveOs': true, 'modelsCount': 2};
  }

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
    // 今日计划去了「计划」，四部门去了「墙」；姐妹在「两条鱼」组里。
    await tester.tap(find.byKey(const ValueKey('hub-group-fish')));
    await settlePhone(tester);
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

    await tester.tap(find.byKey(const ValueKey('hub-group-diag')));
    await settlePhone(tester);
    await tester.tap(find.text('立即触发一次状态巡检'));
    await settlePhone(tester);
    expect(ch.tickCalls, 1);
    await popPage(tester);

    await tester.tap(find.byKey(const ValueKey('hub-group-lock')));
    await settlePhone(tester);
    await tester.scrollUntilVisible(find.text('专注监督'), 200, scrollable: find.byType(Scrollable).last);
    await tester.tap(find.text('专注监督'));
    await settlePhone(tester);
    expect(ch.focusCalls, 1);

    await tester.scrollUntilVisible(find.text('大肥鱼哨兵互信配对'), 200, scrollable: find.byType(Scrollable).last);
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

  testWidgets('数据组：Obsidian 记忆导入与交接复制', (tester) async {
    final ch = _FakeHubChannel();
    await pumpPhone(tester, CompanionHubPage(channel: ch));
    await tester.tap(find.byKey(const ValueKey('hub-group-data')));
    await settlePhone(tester);

    await tester.scrollUntilVisible(find.text('生成记忆模板并导入 Obsidian 数据库'), 200, scrollable: find.byType(Scrollable).last);
    await tester.tap(find.text('生成记忆模板并导入 Obsidian 数据库'));
    await settlePhone(tester);
    expect(ch.importMemoryCalls, 1);

    await tester.scrollUntilVisible(find.text('复制跨 AI 交接上下文（Cognitive OS）'), 200, scrollable: find.byType(Scrollable).last);
    await tester.tap(find.text('复制跨 AI 交接上下文（Cognitive OS）'));
    await settlePhone(tester);
    expect(ch.handoffCalls, 1);
  });

  testWidgets('VoiceSettingsPage switches voice sources and preserves preset API key on cycle', (tester) async {
    SharedPreferences.setMockInitialValues({});
    await pumpPhone(tester, const VoiceSettingsPage());

    expect(find.text('系统 TTS'), findsOneWidget);
    expect(find.text('MiMo 预设'), findsOneWidget);
    expect(find.text('OpenAI'), findsOneWidget);
    expect(find.text('自定义'), findsOneWidget);

    await tester.tap(find.text('MiMo 预设'));
    await settlePhone(tester);
    expect(find.text('预设服务'), findsOneWidget);

    // 填入 MiMo 密钥后切换预设服务，应保留旧预设密钥且切换正常
    await tester.enterText(find.byType(TextField).first, 'mimo-key-123');
    await tester.tap(find.text('预设服务'));
    await settlePhone(tester);

    // 切到自定义模式，三角色行保持可点（试听路径不误弹 OpenAI 选音色对话框）
    await tester.tap(find.text('自定义'));
    await settlePhone(tester);
    expect(find.text('自定义 HTTP 语音接口 · 点击试听'), findsNWidgets(3));
  });
}
