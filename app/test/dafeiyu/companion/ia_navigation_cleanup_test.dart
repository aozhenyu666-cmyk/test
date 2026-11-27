import 'package:dsh_app/dafeiyu/channels/loop_channel.dart';
import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bouts_workspace_page.dart';
import 'package:dsh_app/dafeiyu/ui/companion_hub_page.dart';
import 'package:dsh_app/dafeiyu/ui/gemini_bridge_page.dart';
import 'package:dsh_app/dafeiyu/ui/locks_balance_page.dart';
import 'package:dsh_app/dafeiyu/ui/plan_hub_page.dart';
import 'package:dsh_app/dafeiyu/ui/plan_modality_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeIaLoopChannel extends LoopChannel {
  @override
  Future<List<LoopApp>> apps() async => const [
        LoopApp('md.obsidian', 'Obsidian'),
        LoopApp('com.tencent.mm', '微信'),
      ];
}

class _FakeIaChannel extends ProactiveChannel {
  @override
  Future<Map<String, dynamic>?> boutsStatus() async => const {
        'running': null,
        'queue': <Object>[],
      };

  @override
  Future<Map<String, dynamic>?> todayStatus() async => {
        'brief': const {'line': '今日专注 40m · 娱乐 0m · 共 6 只手各司其职', 'modelsCount': 3},
        'tasks': const [
          {'id': 't1', 'title': '资料分析 20 题', 'minutes': 45, 'protocolId': 'execute'},
        ],
        'defaultAllow': const ['md.obsidian'],
        'locksOverview': {
          'winnerId': 'DAY_WORK_WALL',
          'dayWorkWallEnabled': true,
          'activeLock': const {
            'active': true,
            'priority': 'P4',
            'source': 'DAY_WORK_WALL',
            'label': '工作时段默认防分心锁',
            'why': '白天工作时段仅拦纯娱乐',
            'unlockHow': '午休 / 晚间自动放风或手动开关',
          },
          'hands': const [
            {
              'id': 'DAY_WORK_WALL',
              'name': '工作时段默认防分心锁（Option A）',
              'priority': 'P4',
              'active': true,
              'isWinner': true,
              'by': 'option-a',
              'remainingMin': 30,
              'canToggle': true,
              'duty': '底网防分心',
              'summary': '仅拦娱乐应用',
            },
          ],
          'allLocks': const [],
          'wallet': const {
            'availableMin': 25,
            'currentWindow': 'daytime',
            'currentWindowLabel': '白天工作时段（零散上限 30m）',
            'canSpendNow': true,
          },
          'scheduleSlots': const [],
          'recentActions': const [],
        },
        'horizons': const {'long': <Object>[], 'medium': <Object>[]},
      };

  @override
  Future<Map<String, dynamic>?> planContractOp([Map<String, Object?>? body]) async => const {
        'ok': true,
        'contract': null,
        'presets': <Object>[],
        'dayPlan': {
          'energyLevel': 'mid',
          'custodyPolicy': {'autoKickoffEnabled': true, 'adaptiveSplitEnabled': true},
          'carryoverItems': <Object>[],
          'dayBlocks': <Object>[],
        },
      };

  @override
  Future<Map<String, dynamic>?> sealsOp(Map<String, Object?> body) async {
    final action = body['action'];
    if (action == 'nod_card_get') return const {'ok': true, 'needed': false, 'items': <Object>[]};
    if (action == 'inbox_list') return const {'ok': true, 'entries': <Object>[]};
    return {
      'ok': true,
      'today': '2026-11-23',
      'tomorrow': '2026-11-24',
      'items': const <Object>[],
      'locksOverview': (await todayStatus())!['locksOverview'],
    };
  }

  @override
  Future<Map<String, dynamic>?> bridgeOp([Map<String, Object?>? body]) async => const {
        'v': 1,
        'ok': true,
        'configured': true,
        'enabled': true,
        'healthy': true,
        'url': 'https://example.ts.net:8443',
        'keyTail': 'demo',
        'applied': 2,
        'receiptsToday': 1,
      };

  @override
  Future<Map<String, dynamic>?> bossBrief([String? timeZone]) async => const {
        'ok': true,
        'headline': '今日节奏稳定',
        'keyGaps': <Object>[],
      };

  @override
  Future<Map<String, dynamic>?> analyticsOp([Map<String, Object?>? body]) async => const {'ok': true};

  @override
  Future<Map<String, dynamic>?> horizonsOp([Map<String, Object?>? body]) async =>
      const {'ok': true, 'horizons': {'long': <Object>[], 'medium': <Object>[]}};

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
  Future<Map<String, Object?>?> planDay() async => const {'plan': {'items': <Object>[]}};

  @override
  Future<GuardianPairingStatus?> guardianStatus() async => const GuardianPairingStatus();

  @override
  Future<Map<String, dynamic>?> phoneStorageStatus() async => const {'granted': true};

  @override
  Future<Map<String, dynamic>?> shakeDiceStatus() async => const {'enabled': true};

  @override
  Future<bool?> lockShadow() async => false;

  @override
  Future<bool?> arbiterEnforce() async => true;

  @override
  Future<List<Map<String, dynamic>>> clockList() async => const [];
}

void main() {
  testWidgets('局页 IA 收口：AppBar 仅留刷新与更多菜单，6 卡直达 PlanModalityPage / LocksBalancePage / GeminiBridgePage', (tester) async {
    final ch = _FakeIaChannel();
    final loop = _FakeIaLoopChannel();

    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: loop));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('today-more-tools-btn')), findsOneWidget);
    expect(find.byTooltip('系统明细（队列 / 协议库 / 时钟核）'), findsNothing);
    expect(find.byTooltip('常用 AI 话术剧本（点即复制）'), findsNothing);

    await tester.tap(find.byKey(const ValueKey('today-more-tools-btn')));
    await settlePhone(tester);
    expect(find.text('常用 AI 话术剧本（点即复制）'), findsOneWidget);
    expect(find.text('跨 AI 交接上下文预览'), findsOneWidget);
    expect(find.text('系统明细（队列 / 协议库 / 时钟核）'), findsOneWidget);
    expect(find.text('全部计划目录（长线 / 封印 / 排程）'), findsOneWidget);
    await tester.tapAt(const Offset(10, 10));
    await settlePhone(tester);

    for (final k in const [
      'today-capsule-plan',
      'today-capsule-locks',
      'today-secondary-strip',
      'today-capsule-dice',
      'today-capsule-atlas',
      'today-toggle-more-capsules',
    ]) {
      expect(find.byKey(ValueKey(k)), findsOneWidget, reason: '缺少入口 $k');
    }

    await tester.ensureVisible(find.byKey(const ValueKey('today-toggle-more-capsules')));
    await tester.tap(find.byKey(const ValueKey('today-toggle-more-capsules')));
    await settlePhone(tester);

    for (final k in const [
      'today-capsule-bank',
      'today-capsule-analytics',
      'today-capsule-bridge',
      'today-capsule-hub',
      'today-capsule-records',
      'today-capsule-strategy',
    ]) {
      expect(find.byKey(ValueKey(k)), findsOneWidget, reason: '展开后缺少入口 $k');
    }

    await tester.ensureVisible(find.byKey(const ValueKey('today-capsule-plan')));
    await tester.tap(find.byKey(const ValueKey('today-capsule-plan')));
    await settlePhone(tester);
    expect(find.byType(PlanModalityPage), findsOneWidget);
    expect(find.byType(PlanHubPage), findsNothing);

    expect(find.byKey(const ValueKey('plan-more-views-btn')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('plan-more-views-btn')));
    await settlePhone(tester);
    expect(find.text('三层战略作战室（长期 / 中期）'), findsOneWidget);
    expect(find.text('封印与验收点明细'), findsOneWidget);
    expect(find.text('全部计划目录'), findsOneWidget);
    await tester.tap(find.text('全部计划目录'));
    await settlePhone(tester);
    expect(find.byType(PlanHubPage), findsOneWidget);
    expect(find.textContaining('8 道锁'), findsNothing);
  });

  testWidgets('局页直达 LocksBalancePage 与 GeminiBridgePage 且全界面无 8 道锁残留', (tester) async {
    final ch = _FakeIaChannel();
    final loop = _FakeIaLoopChannel();

    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: loop));
    await settlePhone(tester);

    await tester.ensureVisible(find.byKey(const ValueKey('today-capsule-locks')));
    await tester.tap(find.byKey(const ValueKey('today-capsule-locks')));
    await settlePhone(tester);
    expect(find.byType(LocksBalancePage), findsOneWidget);
    expect(find.textContaining('6 只手'), findsWidgets);
    expect(find.textContaining('8 道锁'), findsNothing);

    Navigator.of(tester.element(find.byType(LocksBalancePage))).pop();
    await settlePhone(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('today-toggle-more-capsules')));
    await tester.tap(find.byKey(const ValueKey('today-toggle-more-capsules')));
    await settlePhone(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('today-capsule-bridge')));
    await tester.tap(find.byKey(const ValueKey('today-capsule-bridge')));
    await settlePhone(tester);
    expect(find.byType(GeminiBridgePage), findsOneWidget);
  });

  testWidgets('CompanionHubPage 保持纯设置 4 组与 6 只手口径且无 8 道锁残留', (tester) async {
    final ch = _FakeIaChannel();
    await pumpPhone(tester, CompanionHubPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('hub-group-fish')), findsOneWidget);
    expect(find.byKey(const ValueKey('hub-group-lock')), findsOneWidget);
    expect(find.byKey(const ValueKey('hub-group-data')), findsOneWidget);
    expect(find.byKey(const ValueKey('hub-group-diag')), findsOneWidget);
    expect(find.byKey(const ValueKey('hub-entry-locks')), findsNothing);
    expect(find.textContaining('8 道锁'), findsNothing);

    await tester.tap(find.byKey(const ValueKey('hub-group-fish')));
    await settlePhone(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('hub-bout-voice')));
    expect(find.byKey(const ValueKey('hub-bout-voice')), findsOneWidget);
    expect(find.textContaining('8 道锁'), findsNothing);

    Navigator.of(tester.element(find.byKey(const ValueKey('hub-bout-voice')))).pop();
    await settlePhone(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('hub-quick-toggle')));
    await tester.tap(find.byKey(const ValueKey('hub-quick-toggle')));
    await settlePhone(tester);
    await tester.ensureVisible(find.textContaining('6 只手'));
    expect(find.textContaining('6 只手'), findsWidgets);
    expect(find.textContaining('8 道锁'), findsNothing);
  });
}
