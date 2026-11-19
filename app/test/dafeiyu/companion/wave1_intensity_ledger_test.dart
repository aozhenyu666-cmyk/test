import 'package:dsh_app/dafeiyu/channels/loop_channel.dart';
import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bouts_workspace_page.dart';
import 'package:dsh_app/dafeiyu/ui/companion_hub_page.dart';
import 'package:dsh_app/dafeiyu/ui/usage_analytics_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeWave1LoopChannel extends LoopChannel {
  @override
  Future<List<LoopApp>> apps() async => const [
        LoopApp('md.obsidian', 'Obsidian'),
      ];
}

class _FakeWave1Channel extends ProactiveChannel {
  IntensityView intensity = const IntensityView(
    tier: 'standard',
    emergencyDisarmDailyCap: 3,
    hardCount: 2,
    hardCap: 12,
    hardLeft: 10,
  );
  final List<String> intensityChanges = [];

  static const Map<String, Object?> sharedTimeLedger = {
    'day': '2026-11-19',
    'funQuota': 120,
    'gameQuota': 60,
    'earnedBout': 30,
    'earnedBank': 5,
    'earnedBreak': 0,
    'earned': 35,
    'penalty': 10,
    'earnedNet': 25,
    'passMinutes': 12,
    'funUsed': 43,
    'gameUsed': 0,
    'dualCounted': 8,
    'converted': 0,
    'funLeft': 102,
    'gameLeft': 60,
    'funOut': false,
    'gameOut': false,
    'line': '今日娱乐已用 43 分钟、还剩 102 分钟；游戏还剩 60 分钟',
  };

  @override
  Future<IntensityView?> intensityStatus() async => intensity;

  @override
  Future<IntensityView?> setIntensity(String tier) async {
    intensityChanges.add(tier);
    if (tier == 'strict') {
      intensity = const IntensityView(
        tier: 'strict',
        emergencyDisarmDailyCap: 2,
        hardCount: 2,
        hardCap: 20,
        hardLeft: 18,
      );
    } else if (tier == 'gentle' && intensity.tier != 'gentle') {
      intensity = IntensityView(
        tier: intensity.tier,
        pendingTier: 'gentle',
        pendingEffectiveDay: '2026-11-20',
        emergencyDisarmDailyCap: intensity.emergencyDisarmDailyCap,
        hardCount: intensity.hardCount,
        hardCap: intensity.hardCap,
        hardLeft: intensity.hardLeft,
      );
    } else {
      intensity = IntensityView(
        tier: tier,
        emergencyDisarmDailyCap: 3,
        hardCount: 2,
        hardCap: 12,
        hardLeft: 10,
      );
    }
    return intensity;
  }

  @override
  Future<Map<String, dynamic>?> todayStatus() async => {
        'brief': {'line': '今日专注约 90m · 娱乐 43m', 'focusMin': 90, 'funMin': 43},
        'running': null,
        'tasks': const [],
        'timeLedger': sharedTimeLedger,
      };

  @override
  Future<Map<String, dynamic>?> analyticsOp([Map<String, Object?>? body]) async => {
        'v': 1,
        'ok': true,
        'day': '2026-11-19',
        'buckets': const {
          'mainlineMin': 90,
          'videoStudyMin': 20,
          'geminiMin': 15,
          'toolTinkerMin': 10,
          'funMin': 43,
          'totalMin': 178,
        },
        'deltas': const {'mainlineDelta': 10, 'funDelta': -5, 'geminiDelta': 5},
        'geminiStats': const {'minutesToday': 15},
        'timeline24h': const [],
        'historySeries': const [],
        'heatmap90d': const [],
        'topApps': const [],
        'timeLedger': sharedTimeLedger,
      };

  @override
  Future<Map<String, dynamic>?> storylineOp([Map<String, Object?>? body]) async => const {
        'ok': true,
        'pullMode': 'countdown_3s',
        'entries': [],
      };

  @override
  Future<Map<String, dynamic>?> boutsStatus() async => const {'running': null, 'queue': []};

  @override
  Future<List<Map<String, dynamic>>> clockList() async => const [];

  @override
  Future<Map<String, Object?>?> wallStatus() async => const {'locked': false};

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

  @override
  Future<Map<String, dynamic>?> phoneStorageStatus() async => const {'granted': true};

  @override
  Future<Map<String, dynamic>?> shakeDiceStatus() async => const {'enabled': true, 'lastDice': 0};

  @override
  Future<bool?> lockShadow() async => null;

  @override
  Future<Map<String, dynamic>?> rotateOp(Map<String, Object?> body) async => null;

  @override
  Future<Map<String, dynamic>?> cardOp(Map<String, Object?> body) async => null;
}

void main() {
  testWidgets('CompanionHubPage intensity selector tightens immediately and schedules relax for tomorrow', (tester) async {
    final ch = _FakeWave1Channel();
    await pumpPhone(tester, CompanionHubPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('hub-intensity-pill')), findsOneWidget);
    expect(find.text('强度档（温和 / 标准 / 严格）'), findsOneWidget);
    expect(find.text('硬干预 2/12'), findsOneWidget);

    // Tighten to strict -> immediate effect
    await tester.tap(find.text('严格'));
    await settlePhone(tester);
    expect(ch.intensityChanges, ['strict']);
    expect(find.text('硬干预 2/20'), findsOneWidget);

    // Relax to gentle -> pendingIntensity badge for tomorrow
    await tester.tap(find.text('温和'));
    await settlePhone(tester);
    expect(ch.intensityChanges, ['strict', 'gentle']);
    expect(find.text('明日→温和'), findsOneWidget);
  });

  testWidgets('timeLedger renders identical remaining allowance across Hub, BoutsWorkspace and UsageAnalytics', (tester) async {
    final ch = _FakeWave1Channel();

    // 中枢和局页用同一张「今日使用情况」卡（时间账并在里面）。
    await pumpPhone(tester, CompanionHubPage(channel: ch));
    await settlePhone(tester);
    expect(find.text('今日使用情况'), findsOneWidget);
    expect(find.text('102m'), findsOneWidget);
    expect(find.text('+35m'), findsOneWidget);
    expect(find.text('60m'), findsOneWidget);

    // 局页：时间账并进了「今日使用情况」一张卡（娱乐剩余 / 挣来池 / 游戏剩余），数字必须和另外两页一致。
    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: _FakeWave1LoopChannel()));
    await settlePhone(tester);
    expect(find.text('今日使用情况'), findsOneWidget);
    expect(find.text('102m'), findsOneWidget);
    expect(find.text('+35m'), findsOneWidget);
    expect(find.text('60m'), findsOneWidget);
    // 点这张卡就进完整的统计页（完整时间账在那里）。
    await tester.tap(find.text('今日使用情况'));
    await settlePhone(tester);
    expect(find.byType(UsageAnalyticsPage), findsOneWidget);
    expect(find.text('今日时间账（挣来制）'), findsOneWidget);

    await pumpPhone(tester, UsageAnalyticsPage(channel: ch));
    await settlePhone(tester);
    expect(find.text('今日时间账（挣来制）'), findsOneWidget);
    expect(find.text('娱乐余 102m'), findsOneWidget);
    expect(find.text('+35m'), findsOneWidget);
    expect(find.text('60m'), findsOneWidget);
  });
}
