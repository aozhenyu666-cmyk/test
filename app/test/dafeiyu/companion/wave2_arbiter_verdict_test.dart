import 'package:dsh_app/dafeiyu/channels/loop_channel.dart';
import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bouts_workspace_page.dart';
import 'package:dsh_app/dafeiyu/ui/companion_hub_page.dart';
import 'package:dsh_app/dafeiyu/ui/usage_analytics_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeWave2LoopChannel extends LoopChannel {
  @override
  Future<List<LoopApp>> apps() async => const [
        LoopApp('md.obsidian', 'Obsidian'),
      ];
}

class _FakeWave2Channel extends ProactiveChannel {
  bool arbiterOn = false;
  final List<bool> arbiterChanges = [];

  static const Map<String, Object?> emptyVerdict = {
    'tag': '空转',
    'line': '今日零局交付，刷娱乐超标',
    'reasons': ['未交付主线工作包'],
  };

  @override
  Future<bool?> arbiterEnforce() async => arbiterOn;

  @override
  Future<bool> setArbiterEnforce(bool enabled) async {
    arbiterChanges.add(enabled);
    arbiterOn = enabled;
    return true;
  }

  @override
  Future<IntensityView?> intensityStatus() async => const IntensityView(
        tier: 'standard',
        effectiveTier: 'gentle',
        autonomyShift: -1,
        emergencyDisarmDailyCap: 3,
        hardCount: 1,
        hardCap: 8,
        hardLeft: 7,
      );

  @override
  Future<Map<String, dynamic>?> todayStatus() async => {
        'brief': {'line': '今日专注 10m · 娱乐 95m', 'focusMin': 10, 'funMin': 95},
        'running': null,
        'tasks': const [],
        'verdict': emptyVerdict,
        'score': 82, // 故意给高原始分，验证客户端守住「空转 ≤ 39」
        'scoreLabel': '投入分',
      };

  @override
  Future<Map<String, dynamic>?> analyticsOp([Map<String, Object?>? body]) async => {
        'v': 1,
        'ok': true,
        'day': '2026-11-19',
        'buckets': const {
          'mainlineMin': 10,
          'videoStudyMin': 0,
          'geminiMin': 0,
          'toolTinkerMin': 25,
          'funMin': 95,
          'totalMin': 130,
        },
        'deltas': const {'mainlineDelta': -30, 'funDelta': 40, 'geminiDelta': 0},
        'geminiStats': const {'minutesToday': 0},
        'timeline24h': const [],
        'historySeries': const [],
        'heatmap90d': const [],
        'topApps': const [],
        'verdict': emptyVerdict,
        'score': 82,
        'scoreLabel': '投入分',
        'eveningReport': {
          'day': '2026-11-19',
          'score': 82,
          'scoreLabel': '投入分',
          'verdict': emptyVerdict,
          'headline': '今日硬日判为空转，明天优先落最小一局',
        },
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
  testWidgets('CompanionHubPage exposes arbiterEnforce switch (default OFF) and 14-day autonomy shift badge', (tester) async {
    final ch = _FakeWave2Channel();
    await pumpPhone(tester, CompanionHubPage(channel: ch));
    await settlePhone(tester);

    final sw = find.byKey(const ValueKey('hub-arbiter-enforce-switch'));
    expect(sw, findsOneWidget);
    expect(find.text('需先收集真机 arbiter-diff.jsonl 核验后再开启'), findsOneWidget);
    expect(tester.widget<SwitchListTile>(sw).value, isFalse);

    // 14-day autonomy shift badge
    expect(find.text('自律放宽→温和'), findsOneWidget);

    await tester.tap(sw);
    await settlePhone(tester);
    expect(ch.arbiterChanges, [true]);
    expect(tester.widget<SwitchListTile>(sw).value, isTrue);
  });

  testWidgets('Hard daily verdict leads UI and caps 投入分 <= 39 when 空转 across Hub, BoutsWorkspace and UsageAnalytics', (tester) async {
    final ch = _FakeWave2Channel();

    await pumpPhone(tester, CompanionHubPage(channel: ch));
    await settlePhone(tester);
    expect(find.text('硬日判：空转'), findsOneWidget);
    expect(find.text('投入分 39'), findsOneWidget);

    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: _FakeWave2LoopChannel()));
    await settlePhone(tester);
    expect(find.text('硬日判：空转'), findsOneWidget);
    expect(find.text('投入分 39'), findsOneWidget);

    await pumpPhone(tester, UsageAnalyticsPage(channel: ch));
    await settlePhone(tester);
    expect(find.text('硬日判：空转'), findsOneWidget);
    expect(find.text('投入分 39'), findsOneWidget);

    await tester.tap(find.text('晚间 AI 总报'));
    await settlePhone(tester);
    expect(find.text('硬日判：空转'), findsOneWidget);
    expect(find.text('39'), findsOneWidget);
  });
}
