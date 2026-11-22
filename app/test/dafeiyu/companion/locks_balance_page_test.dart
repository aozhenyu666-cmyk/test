import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/locks_balance_page.dart';
import 'package:dsh_app/dafeiyu/ui/usage_analytics_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeLocksChannel extends ProactiveChannel {
  _FakeLocksChannel({
    this.verifyOverdue = false,
    this.withOverview = true,
    this.emptyFallback = false,
    this.manualBoutStrict = false,
    this.withVerifyCenter = false,
  });

  final bool verifyOverdue;
  final bool withOverview;
  final int availableMin = 45;
  final bool emptyFallback;
  final bool manualBoutStrict;
  final bool withVerifyCenter;

  bool dayWorkWallEnabled = true;
  bool boutStrictActive = false;
  int tickCalls = 0;
  final List<String> launchedPkgs = [];
  final List<Map<String, Object?>> sealsCalls = [];
  final List<Map<String, Object?>> planContractCalls = [];
  final List<Map<String, Object?>> wallBossCalls = [];
  final List<int> emergencyMinutes = [];

  Map<String, dynamic> _overview() {
    final isManualStrict = manualBoutStrict && !boutStrictActive ? true : boutStrictActive;
    final winnerId = verifyOverdue
        ? 'CONTRACT_OVERDUE_STRICT'
        : (isManualStrict
            ? 'BOUT_STRICT'
            : (dayWorkWallEnabled ? 'DAY_WORK_WALL' : ''));
    return {
        'winnerId': winnerId,
        'dayWorkWallEnabled': dayWorkWallEnabled,
        'effectiveDecision': {
          'priority': verifyOverdue ? 'P3' : (winnerId.isEmpty ? 'P6' : 'P4'),
          'source': verifyOverdue
              ? 'CONTRACT_OVERDUE_STRICT'
              : (winnerId.isEmpty ? 'AUTONOMY_RELAXED' : 'BOUT_STRICT'),
          'mode': winnerId.isEmpty ? 'free' : 'allowlist',
          'why': verifyOverdue ? '「行测 20 题」逾期未过保底' : '工作时段仅放行工作应用',
          'unlockHow': '交付验收或切换关闭',
          'until': DateTime.now().millisecondsSinceEpoch + 30 * 60000,
          'remainingMin': winnerId.isEmpty ? 0 : 30,
          'allow': [
            {'pkg': 'md.obsidian', 'label': 'Obsidian'},
            {'pkg': 'com.google.android.apps.bard', 'label': 'Gemini'},
          ],
          'deny': [],
        },
        'hands': [
          {'id': 'EMERGENCY_SUSPEND', 'name': '急事 3 分钟通行证', 'priority': 'P0', 'active': false, 'isWinner': false, 'by': 'user', 'until': 0, 'remainingMin': 0, 'canToggle': false, 'duty': '职责：突发急事 3 分钟全放行', 'summary': '待命'},
          {'id': 'NIGHT_CURFEW', 'name': '深夜睡眠 & 晚间上限锁', 'priority': 'P1', 'active': false, 'isWinner': false, 'by': 'rhythm', 'until': 0, 'remainingMin': 0, 'canToggle': false, 'duty': '职责：23:00–07:00 睡眠守护', 'summary': '00:30-07:00'},
          {'id': 'GEMINI_LIVE_CELL', 'name': 'Gemini 连麦舱', 'priority': 'P2', 'active': false, 'isWinner': false, 'by': 'gemini', 'until': 0, 'remainingMin': 0, 'canToggle': false, 'duty': '职责：连麦白名单收拢', 'summary': '待命'},
          {'id': 'CONTRACT_OVERDUE_STRICT', 'name': '验收锁（计划块/封印/主管指令）', 'priority': 'P3', 'active': verifyOverdue, 'isWinner': winnerId == 'CONTRACT_OVERDUE_STRICT', 'by': 'dayblock', 'triggerTitle': verifyOverdue ? '行测 20 题' : '', 'until': 0, 'remainingMin': verifyOverdue ? 45 : 0, 'canToggle': false, 'duty': '职责：计划验收块/封印/主管硬指令逾期 60s 严锁', 'summary': '含计划块/封印/主管硬指令逾期严锁'},
          {'id': 'BOUT_STRICT', 'name': '专注严格局（手动/预案）', 'priority': 'P4', 'active': isManualStrict, 'isWinner': winnerId == 'BOUT_STRICT', 'by': 'panel', 'until': 0, 'remainingMin': isManualStrict ? 30 : 0, 'canToggle': isManualStrict && !verifyOverdue, 'duty': '职责：手动严格局 & 计划块定时自动接管', 'summary': isManualStrict ? '手动严格局锁定中' : '未开启'},
          {'id': 'DAY_WORK_WALL', 'name': '工作时段默认防分心锁（Option A）', 'priority': 'P4', 'active': dayWorkWallEnabled && !verifyOverdue, 'isWinner': winnerId == 'DAY_WORK_WALL', 'by': 'option-a', 'until': 0, 'remainingMin': dayWorkWallEnabled ? 60 : 0, 'canToggle': true, 'duty': '职责：07:00–23:00 默认防分心护栏', 'summary': dayWorkWallEnabled ? '生效中' : '已关闭'},
        ],
        if (withVerifyCenter)
          'verifyLockCenter': {
            'totalVerify': 2,
            'passedVerify': 0,
            'overdueCount': 1,
            'pendingReviewCount': 1,
            'clearedToday': false,
            'overdueBlocks': [
              {
                'id': 'blk-overdue-1',
                'title': '资料分析 20 题硬验收',
                'deliverable': '交20题正确率与3条错因',
                'dueHm': '10:00',
                'remainingLockMin': 45,
                'lockedByBoss': true,
                'verifyStatus': 'overdue_locked',
              },
            ],
            'pendingReviewBlocks': [
              {
                'id': 'blk-pending-1',
                'title': '申论小作文提纲',
                'deliverable': '提交结构提纲与论点',
                'dueHm': '11:30',
                'verifyStatus': 'submitted',
              },
            ],
          },
        'lastIntercept': {
          'pkg': 'com.ss.android.ugc.aweme',
          'label': '抖音',
          'at': 1700000100000,
          'handId': 'DAY_WORK_WALL',
          'handName': '工作时段默认防分心锁',
          'reason': '工作时段拦截娱乐应用',
        },
        'activeLock': verifyOverdue
            ? {
                'active': true,
                'priority': 'P3',
                'source': 'CONTRACT_OVERDUE_STRICT',
                'label': '契约/封印逾期严锁',
                'why': '「行测 20 题」已到截止时刻仍未过保底',
                'unlockHow': '交付验收过保底，或写理由豁免',
                'verifyOverdue': true,
              }
            : {
                'active': true,
                'priority': 'P4',
                'source': 'BOUT_STRICT',
                'label': '专心局护航中',
                'why': '当前处于上午工作默认验收时段',
                'unlockHow': '完成本局验收 / 花余额 / 刷题临放',
                'verifyOverdue': false,
              },
        'allLocks': [
          {'id': 'EMERGENCY_SUSPEND', 'priority': 'P0', 'name': '急事 3 分钟通行证', 'active': false, 'summary': '突发急事通道', 'unlockHow': '到时自动回位'},
          {'id': 'NIGHT_CURFEW', 'priority': 'P1', 'name': '深夜睡眠 & 晚间上限锁', 'active': false, 'summary': '23:00–07:00 睡眠守护', 'unlockHow': '07:00 解除'},
          {'id': 'GEMINI_LIVE_CELL', 'priority': 'P2', 'name': 'Gemini 连麦舱', 'active': false, 'summary': '语音共脑舱', 'unlockHow': '结束连麦'},
          {'id': 'CONTRACT_OVERDUE_STRICT', 'priority': 'P3', 'name': '计划/封印逾期验收严锁', 'active': verifyOverdue, 'summary': '到点未过保底', 'unlockHow': '交付验收'},
          {'id': 'TAKEOVER_STRICT', 'priority': 'P3.5', 'name': '强制接管回位锁', 'active': false, 'summary': '强接管拉回', 'unlockHow': '完成回位动作'},
          {'id': 'BOUT_STRICT', 'priority': 'P4', 'name': '专心局 / 默认工作窗验收锁', 'active': !verifyOverdue, 'summary': '工作窗默认验收', 'unlockHow': '过验收或花余额'},
          {'id': 'CONTRACT_FLEX', 'priority': 'P5', 'name': '契约柔性护航', 'active': false, 'summary': '柔性护航', 'unlockHow': '通过检查点'},
          {'id': 'AUTONOMY_RELAXED', 'priority': 'P6', 'name': '余额门槛 & 行为雷达 / 双鱼封禁', 'active': false, 'summary': '余额与双鱼守护', 'unlockHow': '刷题挣余额'},
        ],
        'wallet': {
          'availableMin': availableMin,
          'carryInMin': 30,
          'earnedBankMin': 15,
          'earnedBankCap': 60,
          'earnedWorkMin': 0,
          'earnedTotalMin': 15,
          'penaltyMin': 0,
          'daytimeSpentMin': 10,
          'daytimeCapMin': 30,
          'daytimeRemainingMin': 20,
          'eveningSpentMin': 0,
          'eveningCapMin': 120,
          'eveningRemainingMin': 120,
          'currentWindow': 'daytime',
          'currentWindowLabel': '白天工作时段（零散上限 30m）',
          'floorPassed': false,
          'canSpendNow': !verifyOverdue && availableMin >= 5,
          'spendBlockReason': verifyOverdue ? '逾期严锁中，须先交付验收' : '',
          'nextFreeWindowText': '12:00–14:00 午休或 18:00 后',
          'activeSpendPass': null,
          'lockShadow': false,
        },
        'scheduleSlots': const [],
        'recentActions': [
          {
            'at': 1700000000000,
            'hm': '10:15',
            'actor': '鲸澜',
            'action': '临时封禁',
            'target': '拼多多',
            'reason': '工作时段高频误触，临时限制 45 分钟',
          },
        ],
      };
  }

  @override
  Future<bool> tickNow() async {
    tickCalls++;
    return true;
  }

  @override
  Future<bool> launchApp(String packageName) async {
    launchedPkgs.add(packageName);
    return true;
  }

  @override
  Future<Map<String, dynamic>?> planContractOp([Map<String, Object?>? body]) async {
    if (body != null) planContractCalls.add(Map<String, Object?>.from(body));
    return {'ok': true};
  }

  @override
  Future<Map<String, dynamic>?> wallBossChat([Map<String, Object?>? body]) async {
    if (body != null) wallBossCalls.add(Map<String, Object?>.from(body));
    return {'ok': true, 'text': '已通知主管质询室开展冷血终审'};
  }

  @override
  Future<Map<String, dynamic>?> sealsOp(Map<String, Object?> body) async {
    sealsCalls.add(body);
    if (body['action'] == 'spend') {
      return {'ok': true, 'text': '已批准 ${body['minutes']} 分钟放松窗口'};
    }
    if (body['action'] == 'toggle_day_wall') {
      dayWorkWallEnabled = body['enabled'] == true;
      return {
        'ok': true,
        'dayWorkWallEnabled': dayWorkWallEnabled,
        'text': dayWorkWallEnabled ? '已开启工作时段默认防分心锁' : '已关闭工作时段默认防分心锁',
        'locksOverview': _overview(),
      };
    }
    if (body['action'] == 'strict_off') {
      if (verifyOverdue) {
        return {'ok': false, 'error': 'hard-lock', 'text': '当前存在硬规则锁，不可直接撤回'};
      }
      boutStrictActive = false;
      return {
        'ok': true,
        'text': '已撤回当前手动严格锁',
        'locksOverview': _overview(),
      };
    }
    if (!withOverview) {
      return {'ok': true, 'items': const <Object>[]};
    }
    return {
      'ok': true,
      'locksOverview': _overview(),
    };
  }

  @override
  Future<Map<String, dynamic>?> todayStatus() async {
    if (emptyFallback) return const {'tasks': <Object>[], 'horizons': {'long': <Object>[], 'medium': <Object>[]}};
    if (!withOverview) {
      return {
        'tasks': const <Object>[],
        'lockArbiter': {
          'active': true,
          'priority': 'P4',
          'source': 'BOUT_STRICT',
          'label': '旧版兼容默认局',
          'why': '来自旧版 lockArbiter 降级字段',
          'unlockHow': '完成本局验收',
        },
        'timeLedger': {
          'funLeft': 28,
          'carry': 10,
          'earnedBank': 18,
          'earnedBout': 0,
          'earned': 18,
          'penalty': 0,
          'funUsed': 0,
          'gameLeft': 60,
        },
      };
    }
    return {
      'tasks': const <Object>[],
      'locksOverview': _overview(),
    };
  }

  @override
  Future<Map<String, Object?>?> wallStatus() async => const {'locked': true, 'why': '墙护航中'};

  @override
  Future<bool?> lockShadow() async => false;

  @override
  Future<bool> emergencySuspendStrict({int minutes = 10}) async {
    emergencyMinutes.add(minutes);
    return true;
  }

  @override
  Future<Map<String, dynamic>?> analyticsOp([Map<String, Object?>? body]) async => const {
        'ok': true,
        'buckets': {'mainlineMin': 30, 'videoStudyMin': 0, 'geminiMin': 10, 'toolTinkerMin': 0, 'funMin': 5, 'totalMin': 45},
      };

  @override
  Future<Map<String, dynamic>?> storylineOp([Map<String, Object?>? body]) async => const {'ok': true, 'entries': []};
}

void main() {
  testWidgets('LocksBalancePage 展示 8 道锁、分时余额大盘、轨迹，并支持花余额与 3 分钟急事通道', (tester) async {
    final ch = _FakeLocksChannel();
    await pumpPhone(tester, LocksBalancePage(channel: ch));
    await settlePhone(tester);

    expect(find.text('锁与余额总控'), findsWidgets);
    expect(find.text('P4 · 专心局护航中'), findsOneWidget);
    expect(find.text('45 分钟'), findsOneWidget);
    expect(find.text('10 / 30m'), findsOneWidget);
    expect(find.text('0 / 120m'), findsOneWidget);
    expect(find.text('鲸澜 · 临时封禁（拼多多）'), findsOneWidget);
    for (final prio in ['P0', 'P1', 'P2', 'P3', 'P3.5', 'P4', 'P5', 'P6']) {
      expect(find.byKey(ValueKey('lock-item-$prio')), findsOneWidget);
    }

    // 花余额放松弹窗 -> 发 spend
    await tester.tap(find.byKey(const ValueKey('locks-spend-btn')));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('locks-spend-dialog')), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('locks-spend-minutes')), '15');
    await tester.enterText(find.byKey(const ValueKey('locks-spend-reason')), '刚做完一套行测休息一会儿');
    await tester.tap(find.byKey(const ValueKey('locks-spend-submit')));
    await settlePhone(tester);
    expect(
      ch.sealsCalls.any((c) => c['action'] == 'spend' && c['minutes'] == 15 && c['reason'] == '刚做完一套行测休息一会儿'),
      isTrue,
    );

    // 急事 3 分钟通道
    await tester.tap(find.byKey(const ValueKey('locks-emergency-btn')));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('locks-emergency-dialog')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('locks-emergency-confirm')));
    await settlePhone(tester);
    expect(ch.emergencyMinutes, [3]);

    // 跳转 App 使用时长统计独立页
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('locks-open-analytics-row')),
      200,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const ValueKey('locks-open-analytics-row')));
    await settlePhone(tester);
    expect(find.byType(UsageAnalyticsPage), findsOneWidget);
  });

  testWidgets('LocksBalancePage 逾期严锁时醒目警示并拦截花零散余额', (tester) async {
    final ch = _FakeLocksChannel(verifyOverdue: true);
    await pumpPhone(tester, LocksBalancePage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('locks-overdue-warning')), findsOneWidget);
    expect(find.text('逾期严锁：不可花零散余额绕过，须先交付'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('locks-spend-btn')));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('locks-spend-dialog')), findsNothing);
    expect(ch.sealsCalls.any((c) => c['action'] == 'spend'), isFalse);
  });

  testWidgets('LocksBalancePage 在无 locksOverview 时优雅降级为 timeLedger / lockArbiter 与空壳兜底', (tester) async {
    final legacy = _FakeLocksChannel(withOverview: false);
    await pumpPhone(tester, LocksBalancePage(channel: legacy));
    await settlePhone(tester);

    expect(find.text('P4 · 旧版兼容默认局'), findsOneWidget);
    expect(find.text('28 分钟'), findsOneWidget);
    expect(find.byKey(const ValueKey('lock-item-P0')), findsOneWidget);

    final bare = _FakeLocksChannel(withOverview: false, emptyFallback: true);
    await pumpPhone(tester, LocksBalancePage(key: const ValueKey('bare-fallback'), channel: bare));
    await settlePhone(tester);
    expect(find.text('0 分钟'), findsOneWidget);
    expect(find.byKey(const ValueKey('lock-item-P6')), findsOneWidget);
  });

  testWidgets('LocksBalancePage 探针展示 6 只手、放行工作App与最近拦截，支持一键开关 Option A、撤回严格锁并立即 tickNow', (tester) async {
    final ch = _FakeLocksChannel(manualBoutStrict: true);
    ch.boutStrictActive = true;
    await pumpPhone(tester, LocksBalancePage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('locks-probe-card')), findsOneWidget);
    expect(find.text('此刻哪只手在发力 · 锁机探针'), findsOneWidget);
    for (final id in ['EMERGENCY_SUSPEND', 'NIGHT_CURFEW', 'GEMINI_LIVE_CELL', 'CONTRACT_OVERDUE_STRICT', 'BOUT_STRICT', 'DAY_WORK_WALL']) {
      expect(find.byKey(ValueKey('locks-probe-hand-$id')), findsOneWidget);
    }
    expect(find.byKey(const ValueKey('locks-probe-last-intercept')), findsOneWidget);
    expect(find.textContaining('最近拦截：抖音'), findsOneWidget);

    // 点击放行工作 App 芯片拉起 Obsidian
    await tester.tap(find.byKey(const ValueKey('locks-probe-allow-chip-md.obsidian')));
    await settlePhone(tester);
    expect(ch.launchedPkgs, ['md.obsidian']);

    // 一键撤回手动严格锁 -> sealsOp(strict_off) + tickNow()
    await tester.tap(find.byKey(const ValueKey('locks-strict-off-btn')));
    await settlePhone(tester);
    expect(ch.sealsCalls.any((c) => c['action'] == 'strict_off'), isTrue);
    expect(ch.tickCalls, 1);

    // 一键开关 Option A -> sealsOp(toggle_day_wall) + tickNow()
    await tester.tap(find.byKey(const ValueKey('locks-day-wall-switch')));
    await settlePhone(tester);
    expect(ch.sealsCalls.any((c) => c['action'] == 'toggle_day_wall' && c['enabled'] == false), isTrue);
    expect(ch.dayWorkWallEnabled, isFalse);
    expect(ch.tickCalls, 2);
  });

  testWidgets('LocksBalancePage 展示验收锁中枢、6只手职责，支持提交交付与通知主管终审', (tester) async {
    final ch = _FakeLocksChannel(verifyOverdue: true, withVerifyCenter: true);
    await pumpPhone(tester, LocksBalancePage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('locks-verify-center-card')), findsOneWidget);
    expect(find.byKey(const ValueKey('locks-verify-overdue-blk-overdue-1')), findsOneWidget);
    expect(find.textContaining('职责：计划验收块/封印/主管硬指令逾期 60s 严锁'), findsWidgets);

    // 点击提交交付解锁 → 先验 <12 字拦截，再提真交付
    await tester.ensureVisible(find.byKey(const ValueKey('locks-verify-submit-blk-overdue-1')));
    await tester.tap(find.byKey(const ValueKey('locks-verify-submit-blk-overdue-1')));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('locks-delivery-dialog')), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('locks-delivery-input')), '太短');
    await tester.tap(find.byKey(const ValueKey('locks-delivery-submit')));
    await settlePhone(tester);
    expect(find.textContaining('至少 12 个字'), findsOneWidget);

    await tester.enterText(
      find.byKey(const ValueKey('locks-delivery-input')),
      '已完成20道资料分析真题并整理3条错因写进Obsidian笔记',
    );
    await tester.tap(find.byKey(const ValueKey('locks-delivery-submit')));
    await settlePhone(tester);
    expect(
      ch.planContractCalls.any((c) => c['action'] == 'submit_delivery' && c['blockId'] == 'blk-overdue-1'),
      isTrue,
    );
    expect(ch.tickCalls, greaterThanOrEqualTo(1));

    // 点击待审块通知主管终审
    await tester.ensureVisible(find.byKey(const ValueKey('locks-verify-judge-blk-pending-1')));
    await tester.tap(find.byKey(const ValueKey('locks-verify-judge-blk-pending-1')));
    await settlePhone(tester);
    expect(
      ch.wallBossCalls.any((c) => c['action'] == 'request_judge' && c['blockId'] == 'blk-pending-1'),
      isTrue,
    );
  });
}
