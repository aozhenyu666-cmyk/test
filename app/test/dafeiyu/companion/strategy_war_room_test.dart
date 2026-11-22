import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bank_manager_page.dart';
import 'package:dsh_app/dafeiyu/ui/companion_hub_page.dart';
import 'package:dsh_app/dafeiyu/ui/locks_balance_page.dart';
import 'package:dsh_app/dafeiyu/ui/plan_hub_page.dart';
import 'package:dsh_app/dafeiyu/ui/strategy_horizons_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeWarRoomChannel extends ProactiveChannel {
  final List<Map<String, Object?>> sealsCalls = [];
  final List<Map<String, Object?>> horizonsCalls = [];
  final List<Map<String, Object?>> geminiCalls = [];
  bool shadow = false;

  Map<String, dynamic> get _overview => {
        'activeLock': {
          'active': true,
          'priority': 'P4',
          'source': 'BOUT_STRICT',
          'label': '上午专注守护中',
          'why': '09:30–11:00 时间段验收点正在执行',
          'unlockHow': '完成本段 20 题或保底 10 题',
          'verifyOverdue': false,
        },
        'allLocks': const [],
        'wallet': {
          'availableMin': 40,
          'currentWindow': 'daytime',
          'currentWindowLabel': '白天工作时段（零散上限 30m）',
          'canSpendNow': true,
        },
        'scheduleSlots': [
          {
            'id': 'slot-1',
            'title': '资料分析 20 题时段',
            'kind': 'quiz',
            'startMinute': 570,
            'dueMinute': 660,
            'startHm': '09:30',
            'dueHm': '11:00',
            'stretch': 20,
            'floor': 10,
            'progress': 6,
            'status': 'open',
            'isCurrentSlot': true,
          },
        ],
        'recentActions': const [],
      };

  @override
  Future<Map<String, dynamic>?> todayStatus() async => {
        'tasks': [
          {'id': 't1', 'title': '申论提纲精练', 'minutes': 45, 'protocolId': 'execute'},
        ],
        'horizons': {
          'long': [
            {
              'id': 'L-1',
              'horizon': 'long',
              'title': '2026 省考高分上岸',
              'status': 'active',
              'doneSteps': 3,
              'targetSteps': 10,
              'due': '2099-03-15',
            },
          ],
          'medium': [
            {
              'id': 'M-1',
              'horizon': 'medium',
              'title': '资料分析 200 题攻坚',
              'status': 'active',
              'doneSteps': 2,
              'targetSteps': 8,
              'due': '2099-01-20',
            },
          ],
        },
        'locksOverview': _overview,
      };

  @override
  Future<Map<String, dynamic>?> horizonsOp([Map<String, Object?>? body]) async {
    if (body != null) horizonsCalls.add(body);
    return {
      'ok': true,
      'horizons': (await todayStatus())!['horizons'],
    };
  }

  @override
  Future<Map<String, dynamic>?> sealsOp(Map<String, Object?> body) async {
    sealsCalls.add(body);
    final action = body['action'];
    if (action == 'nod_card_get') return const {'ok': true, 'needed': false, 'items': <Object>[]};
    if (action == 'inbox_list') return const {'ok': true, 'entries': <Object>[]};
    if (action == 'locks_overview' || action == 'list') {
      return {
        'ok': true,
        'today': '2026-11-22',
        'tomorrow': '2026-11-23',
        'items': const <Object>[],
        'locksOverview': _overview,
      };
    }
    return {'ok': true, 'text': '已落实'};
  }

  @override
  Future<Map<String, dynamic>?> storylineOp([Map<String, Object?>? body]) async => const {
        'ok': true,
        'pullMode': 'countdown_3s',
        'promptBlock': '【今日脉络】上午推进资料分析 20 题。',
        'entries': [],
      };

  @override
  Future<bool> launchGeminiProactive({
    String topic = '',
    String brief = '',
    String mode = 'countdown_3s',
    bool preferVoice = true,
  }) async {
    geminiCalls.add({'topic': topic, 'brief': brief, 'mode': mode, 'preferVoice': preferVoice});
    return true;
  }

  @override
  Future<Map<String, Object?>?> wallStatus() async => const {'locked': false};

  @override
  Future<ProactiveStatus?> status() async => const ProactiveStatus(config: ProactiveConfig(enabled: true));

  @override
  Future<bool?> lockShadow() async => shadow;

  @override
  Future<bool?> arbiterEnforce() async => true;

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
  Future<Map<String, dynamic>?> shakeDiceStatus() async => const {'enabled': true};

  @override
  Future<Map<String, dynamic>?> analyticsOp([Map<String, Object?>? body]) async => const {'ok': true};

  @override
  Future<Map<String, dynamic>?> rotateOp(Map<String, Object?> body) async => null;

  @override
  Future<IntensityView?> intensityStatus() async => const IntensityView();

  @override
  Future<List<Map<String, dynamic>>> clockList() async => const [];

  @override
  Future<bool> setCompanionConfig(Map<String, Object?> patch) async {
    if (patch['lockShadow'] is bool) shadow = patch['lockShadow'] as bool;
    return true;
  }

  @override
  Future<Map<String, dynamic>?> bankOp([Map<String, Object?>? body]) async => const {
        'ok': true,
        'bankName': '行测题库',
        'bankTotal': 20,
        'requiredCount': 10,
        'unlockMinutes': 5,
      };

  @override
  Future<Map<String, dynamic>?> reviewOp(Map<String, Object?> body) async => const {'ok': true, 'dueCount': 0};
}

void main() {
  testWidgets('StrategyHorizonsPage 三页签切换、精力快选、时间轴此刻进行中与 Gemini 联调', (tester) async {
    final ch = _FakeWarRoomChannel();
    String? copied;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String?;
      return null;
    });

    await pumpPhone(tester, StrategyHorizonsPage(channel: ch));
    await settlePhone(tester);

    // 默认打开 Tab 2：今日 · 计划与验收锁
    expect(find.byKey(const ValueKey('strategy-tabs-pill')), findsOneWidget);
    expect(find.text('此刻进行中'), findsOneWidget);
    expect(find.text('资料分析 20 题时段'), findsOneWidget);
    expect(find.text('余额 40m'), findsOneWidget);

    // 精力快选：点击「满血 60m」
    await tester.tap(find.byKey(const ValueKey('energy-preset-60')));
    await settlePhone(tester);
    expect(ch.sealsCalls.any((c) => c['action'] == 'plan-day' && c['target'] == 'today'), isTrue);

    // Gemini 战略联调：点击「⚡ 唤起 Gemini 共脑」
    await tester.ensureVisible(find.byKey(const ValueKey('strategy-gemini-launch')));
    await tester.tap(find.byKey(const ValueKey('strategy-gemini-launch')));
    await settlePhone(tester);
    expect(copied, contains('2026 省考高分上岸'));
    expect(ch.geminiCalls.length, 1);

    // 切换到 Tab 0：长期 · 北极星 -> 叫 Gemini 审视大方向
    await tester.tap(find.text('长期 · 北极星'));
    await settlePhone(tester);
    expect(find.text('长期主航道 · 考公 / 就业'), findsOneWidget);
    expect(find.text('进行中'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('strategy-gemini-long')));
    await settlePhone(tester);
    expect(ch.geminiCalls.length, 2);
    expect(ch.geminiCalls.last['topic'], '审视长期北极星大方向');

    // 切换到 Tab 1：中期 · 战役 -> 推进 +1 & 叫 Gemini 拆本周小步
    await tester.tap(find.text('中期 · 战役'));
    await settlePhone(tester);
    expect(find.text('中期战役 · 3~21 天攻坚'), findsOneWidget);
    await tester.tap(find.text('推进 +1'));
    await settlePhone(tester);
    expect(ch.horizonsCalls.any((c) => c['bumpStep'] == true), isTrue);
    await tester.tap(find.byKey(const ValueKey('strategy-gemini-medium')));
    await settlePhone(tester);
    expect(ch.geminiCalls.length, 3);
    expect(ch.geminiCalls.last['topic'], '拆解中期战役本周小步');
  });

  testWidgets('PlanHubPage 与 CompanionHubPage 置顶高频总控入口且低频维护可折叠', (tester) async {
    final ch = _FakeWarRoomChannel();

    await pumpPhone(tester, PlanHubPage(channel: ch));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('plan-hub-horizons')), findsOneWidget);
    expect(find.byKey(const ValueKey('plan-hub-locks')), findsOneWidget);
    expect(find.byKey(const ValueKey('plan-hub-analytics')), findsOneWidget);
    expect(find.byKey(const ValueKey('plan-hub-day')), findsOneWidget);
    expect(find.byKey(const ValueKey('plan-hub-contract')), findsOneWidget);
    expect(find.text('40m'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('plan-hub-locks')));
    await settlePhone(tester);
    expect(find.byType(LocksBalancePage), findsOneWidget);
  });

  testWidgets('CompanionHubPage 置顶高频总控入口且低频维护可折叠', (tester) async {
    final ch = _FakeWarRoomChannel();
    await pumpPhone(tester, CompanionHubPage(channel: ch));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('hub-entry-locks')), findsOneWidget);
    expect(find.byKey(const ValueKey('hub-entry-strategy')), findsOneWidget);
    expect(find.byKey(const ValueKey('hub-entry-bank')), findsOneWidget);
    expect(find.byKey(const ValueKey('hub-entry-analytics')), findsOneWidget);
    expect(find.text('真锁'), findsOneWidget);

    // 折叠的低频工程维护入口默认收起，点开后展示底层诊断
    expect(find.text('底层存储与权限体检'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('hub-low-freq-toggle')));
    await settlePhone(tester);
    expect(find.text('底层存储与权限体检'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('hub-entry-bank')));
    await settlePhone(tester);
    expect(find.byType(BankManagerPage), findsOneWidget);
  });
}
