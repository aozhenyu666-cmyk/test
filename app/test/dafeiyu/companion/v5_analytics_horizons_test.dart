import 'package:dsh_app/dafeiyu/channels/loop_channel.dart';
import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bouts_workspace_page.dart';
import 'package:dsh_app/dafeiyu/ui/companion_hub_page.dart';
import 'package:dsh_app/dafeiyu/ui/plan_modality_page.dart';
import 'package:dsh_app/dafeiyu/ui/strategy_horizons_page.dart';
import 'package:dsh_app/dafeiyu/ui/usage_analytics_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeV5LoopChannel extends LoopChannel {
  @override
  Future<List<LoopApp>> apps() async => const [
        LoopApp('md.obsidian', 'Obsidian'),
        LoopApp('com.tencent.mm', '微信'),
      ];
}

class _FakeV5Channel extends ProactiveChannel {
  final List<Map<String, Object?>> analyticsCalls = [];
  final List<Map<String, Object?>> storylineCalls = [];
  final List<Map<String, Object?>> horizonsCalls = [];
  final List<Map<String, Object?>> launchGeminiCalls = [];
  String pullMode = 'countdown_3s';
  bool reportGenerated = false;

  @override
  Future<Map<String, dynamic>?> analyticsOp([Map<String, Object?>? body]) async {
    final call = body ?? const {'action': 'overview'};
    analyticsCalls.add(call);
    if (call['action'] == 'generate_report') {
      reportGenerated = true;
      return {
        'ok': true,
        'eveningReport': _buildEveningReport(score: 91, headline: '主线推进扎实，Gemini 复盘已入账'),
      };
    }
    final range = (call['rangeDays'] as num?)?.toInt() ?? 7;
    return {
      'v': 1,
      'ok': true,
      'day': '${call['day'] ?? '2026-11-18'}',
      'buckets': {
        'mainlineMin': 135,
        'videoStudyMin': 45,
        'geminiMin': 28,
        'toolTinkerMin': 20,
        'funMin': 18,
        'totalMin': 246,
        'tinkerWarning': false,
      },
      'deltas': {
        'mainlineDelta': 25,
        'funDelta': -12,
        'geminiDelta': 10,
      },
      'geminiStats': {
        'minutesToday': 28,
        'sessionsCount': 2,
        'recapsCount': 1,
        'recentRecaps': [
          {
            'topic': '资料分析混合增长率复盘',
            'summary': '已理清十字交叉法分母基期近似条件',
            'hm': '16:20',
          },
        ],
      },
      'timeline24h': [
        {
          'startMin': 540,
          'endMin': 615,
          'durationMin': 75,
          'app': 'md.obsidian',
          'label': 'Obsidian',
          'bucket': 'mainline',
          'locked': false,
        },
        {
          'startMin': 660,
          'endMin': 688,
          'durationMin': 28,
          'app': 'com.google.android.apps.bard',
          'label': 'Gemini',
          'bucket': 'gemini_cobrain',
          'locked': false,
        },
        {
          'startMin': 860,
          'endMin': 878,
          'durationMin': 18,
          'app': 'tv.danmaku.bili',
          'label': '哔哩哔哩',
          'bucket': 'fun',
          'locked': true,
        },
      ],
      'historySeries': [
        for (var i = 0; i < range; i++)
          {
            'day': '11-${(i % 28 + 1).toString().padLeft(2, '0')}',
            'focusMin': 90 + (i % 5) * 15,
            'geminiMin': 15 + (i % 3) * 5,
            'funMin': 20 + (i % 4) * 5,
          },
      ],
      'heatmap90d': [
        for (var i = 0; i < 90; i++)
          {
            'day': '2026-11-${(i % 28 + 1).toString().padLeft(2, '0')}',
            'focusMin': 60 + (i % 4) * 30,
            'geminiMin': 15,
            'funMin': 12,
            'level': (i % 4) + 1,
          },
      ],
      'topApps': [
        {'app': 'md.obsidian', 'label': 'Obsidian', 'minutes': 135, 'bucket': 'mainline', 'fun': false},
        {'app': 'com.google.android.apps.bard', 'label': 'Gemini', 'minutes': 28, 'bucket': 'gemini_cobrain', 'fun': false},
        {'app': 'tv.danmaku.bili', 'label': '哔哩哔哩', 'minutes': 45, 'bucket': 'video_study', 'fun': false},
      ],
      'eveningReport': _buildEveningReport(
        score: reportGenerated ? 91 : 86,
        headline: reportGenerated ? '主线推进扎实，Gemini 复盘已入账' : '今日考公资料分析突破明显，晚间收尾良好',
      ),
      'lockArbiter': {
        'active': true,
        'priority': 'P3',
        'source': 'BOUT_STRICT',
        'label': '严格专注局护航',
        'why': '当前正在进行资料分析 200 题攻坚局',
        'unlockHow': '倒计时结束交墙 / 锁屏刷对 10 题临放 5 分钟',
      },
    };
  }

  Map<String, dynamic> _buildEveningReport({required int score, required String headline}) => {
        'day': '2026-11-18',
        'score': score,
        'headline': headline,
        'markdown': '# 2026-11-18 每日总报\n- 评分：$score',
        'completedTasks': [
          {'title': '资料分析基期比重 40 题', 'minutes': 60, 'passed': true},
        ],
        'causalHighlights': ['先写三段因果再刷题，正确率提升 14%'],
        'tomorrowDraft': ['行测言语理解 30 题', '申论大作文分论点提炼'],
      };

  @override
  Future<Map<String, dynamic>?> storylineOp([Map<String, Object?>? body]) async {
    final call = body ?? const {'action': 'get'};
    storylineCalls.add(call);
    if (call['action'] == 'set_gemini_pull_mode' && call['pullMode'] is String) {
      pullMode = call['pullMode'] as String;
    }
    return {
      'ok': true,
      'pullMode': pullMode,
      'promptBlock': '【今日连续脉络】09:10 立项资料分析；16:00 与 Gemini 连麦 28m 讨论增长率。',
      'entries': [
        {'hm': '09:10', 'kind': 'bout', 'actor': '鲸澜', 'summary': '立项：上午攻克资料分析基期比重'},
        {'hm': '16:00', 'kind': 'gemini', 'actor': 'Gemini', 'summary': '连麦 28m 梳理十字交叉法适用条件'},
      ],
    };
  }

  @override
  Future<bool> launchGeminiProactive({
    String topic = '',
    String brief = '',
    String mode = 'countdown_3s',
    bool preferVoice = true,
  }) async {
    launchGeminiCalls.add({
      'topic': topic,
      'brief': brief,
      'mode': mode,
      'preferVoice': preferVoice,
    });
    return true;
  }

  @override
  Future<Map<String, dynamic>?> todayStatus() async => {
        'brief': {'line': '今日专注约 180m · 娱乐 18m', 'focusMin': 180, 'funMin': 18},
        'geminiMinutesToday': 28,
        'running': null,
        'tasks': [
          {'id': 't1', 'title': '资料分析 40 题', 'minutes': 45, 'protocolId': 'study'},
        ],
        'unifiedTasks': [
          {'id': 't1', 'title': '资料分析 40 题', 'minutes': 45, 'protocolId': 'study', 'parentHorizonId': 'M-1'},
        ],
        'lockArbiter': {
          'active': true,
          'priority': 'P2',
          'source': 'CONTRACT_OVERDUE_STRICT',
          'label': '契约逾期严格锁',
          'why': '网课提炼验收点已进入严锁保护',
          'unlockHow': '提交三段因果提炼通过追问验收',
        },
        'horizons': {
          'long': [
            {
              'id': 'L-1',
              'horizon': 'long',
              'title': '2026 省考高分上岸',
              'spanHint': '长期主航道',
              'doneSteps': 4,
              'targetSteps': 10,
              'status': 'active',
            },
          ],
          'medium': [
            {
              'id': 'M-1',
              'horizon': 'medium',
              'title': '行测资料分析 200 题通关',
              'spanHint': '7 天战役',
              'doneSteps': 3,
              'targetSteps': 8,
              'status': 'active',
            },
          ],
        },
        'charts': {
          'buckets': {
            'mainlineMin': 135,
            'videoStudyMin': 45,
            'geminiMin': 28,
            'toolTinkerMin': 20,
            'funMin': 18,
            'tinkerWarning': false,
          },
          'topApps': const [],
          'completedItems': const [],
          'sevenDayTrend': const [],
        },
      };

  @override
  Future<Map<String, dynamic>?> horizonsOp([Map<String, Object?>? body]) async {
    if (body != null) horizonsCalls.add(body);
    return {
      'ok': true,
      'horizons': {
        'long': [
          {
            'id': 'L-1',
            'horizon': 'long',
            'title': '2026 省考高分上岸',
            'spanHint': '长期主航道',
            'doneSteps': 4,
            'targetSteps': 10,
            'status': 'active',
          },
        ],
        'medium': [
          {
            'id': 'M-1',
            'horizon': 'medium',
            'title': '行测资料分析 200 题通关',
            'spanHint': '7 天战役',
            'doneSteps': 3,
            'targetSteps': 8,
            'status': 'active',
          },
        ],
      },
    };
  }

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
  Future<Map<String, Object?>?> planDay() async => const {
        'min': 540,
        'protocols': [
          {'id': 'execute', 'name': '深度执行', 'minutes': 45},
        ],
        'plan': {
          'items': [
            {
              'protocolId': 'execute',
              'title': '写申论提纲',
              'minute': 560,
              'budget': 45,
              'need': ['md.obsidian'],
              'allow': ['md.obsidian'],
            },
          ],
        },
        'recent': <String>[],
        'numbers': <String, Object?>{},
        'acceptLeftMs': 0,
        'sentence': '',
      };

  @override
  Future<Map<String, dynamic>?> planContractOp([Map<String, Object?>? body]) async => {
        'ok': true,
        'contract': null,
        'presets': const [],
      };

  @override
  Future<GuardianPairingStatus?> guardianStatus() async => const GuardianPairingStatus();

  @override
  Future<Map<String, dynamic>?> shakeDiceStatus() async => const {'enabled': true, 'lastDice': 2};

  @override
  Future<Map<String, dynamic>?> phoneStorageStatus() async => const {'granted': true};
}

void main() {
  testWidgets('UsageAnalyticsPage renders all 4 tabs, 24h ribbon, 90d heatmap, Gemini pull & evening report', (tester) async {
    final ch = _FakeV5Channel();
    await pumpPhone(tester, UsageAnalyticsPage(channel: ch));
    await settlePhone(tester);

    // Tab 1: 今日全景
    expect(find.text('今日时间分配全景'), findsOneWidget);
    expect(find.text('24 小时时间轴（06:00 – 24:00）'), findsOneWidget);
    expect(find.text('3 段切片'), findsOneWidget);
    expect(find.text('Obsidian'), findsOneWidget);

    // Tab 2: 长期趋势
    await tester.tap(find.text('长期趋势'));
    await settlePhone(tester);
    expect(find.text('90 天专注热力图'), findsOneWidget);
    expect(find.text('90 天记录'), findsOneWidget);
    await tester.tap(find.text('30天'));
    await settlePhone(tester);
    expect(ch.analyticsCalls.any((c) => c['rangeDays'] == 30), isTrue);

    // Tab 3: Gemini 共脑
    await tester.tap(find.text('Gemini 共脑'));
    await settlePhone(tester);
    expect(find.text('今日与 Gemini 沟通 28 分钟 · 2 场'), findsOneWidget);
    expect(find.text('资料分析混合增长率复盘'), findsOneWidget);

    await tester.tap(find.text('零秒铁血强拉'));
    await settlePhone(tester);
    expect(
      ch.storylineCalls.any((c) => c['action'] == 'set_gemini_pull_mode' && c['pullMode'] == 'instant'),
      isTrue,
    );

    await tester.tap(find.text('⚡ 立即越权唤起 Gemini Live（自动带入今日脉络）'));
    await settlePhone(tester);
    expect(ch.launchGeminiCalls.length, 1);
    expect(ch.launchGeminiCalls.single['mode'], 'instant');
    expect(ch.launchGeminiCalls.single['preferVoice'], isTrue);

    // Tab 4: 晚间 AI 总报
    await tester.tap(find.text('晚间 AI 总报'));
    await settlePhone(tester);
    expect(find.text('86'), findsOneWidget);
    expect(find.text('今日考公资料分析突破明显，晚间收尾良好'), findsOneWidget);
    expect(find.text('明日三件套草案'), findsOneWidget);

    await tester.tap(find.text('✨ 立即生成 / 刷新今日总报告'));
    await settlePhone(tester);
    expect(ch.analyticsCalls.any((c) => c['action'] == 'generate_report'), isTrue);
    expect(find.text('91'), findsOneWidget);
    expect(find.text('主线推进扎实，Gemini 复盘已入账'), findsOneWidget);
  });

  testWidgets('StrategyHorizonsPage displays Lock Arbiter, Long/Medium horizons and pushes to today', (tester) async {
    final ch = _FakeV5Channel();
    await pumpPhone(tester, StrategyHorizonsPage(channel: ch));
    await settlePhone(tester);

    expect(find.text('三层战略作战室'), findsWidgets);
    expect(find.text('统一锁裁决 · P2 契约逾期严格锁'), findsOneWidget);
    expect(find.text('2026 省考高分上岸'), findsOneWidget);
    expect(find.text('行测资料分析 200 题通关'), findsOneWidget);
    expect(find.text('资料分析 40 题'), findsOneWidget);

    await tester.tap(find.text('⚡ 拆一局推入今日').first);
    await settlePhone(tester);
    expect(ch.horizonsCalls.any((c) => c['action'] == 'push_to_today'), isTrue);
  });

  testWidgets('CompanionHubPage：四组设置，每项只出现一次；日常功能不在中枢', (tester) async {
    final ch = _FakeV5Channel();
    await pumpPhone(tester, CompanionHubPage(channel: ch));
    await settlePhone(tester);

    for (final k in ['hub-group-fish', 'hub-group-lock', 'hub-group-data', 'hub-group-diag']) {
      expect(find.byKey(ValueKey(k)), findsOneWidget);
    }
    // 统计、计划、四部门都不在中枢了（在局 / 墙）。
    expect(find.text('📊 每日统计与晚间总报'), findsNothing);
    expect(find.text('每日四部门'), findsNothing);

    await tester.tap(find.byKey(const ValueKey('hub-group-fish')));
    await settlePhone(tester);
    expect(find.text('立即唤起 Gemini Live'), findsOneWidget);
    expect(find.text('双姐妹桌宠皮肤（.zip 导入）'), findsOneWidget);
    await popPage(tester);

    await tester.tap(find.byKey(const ValueKey('hub-group-lock')));
    await settlePhone(tester);
    expect(find.text('App 分类表'), findsOneWidget);
    await popPage(tester);

    await tester.tap(find.byKey(const ValueKey('hub-group-diag')));
    await settlePhone(tester);
    expect(find.text('一键热重启主动引擎'), findsOneWidget);
  });

  testWidgets('BoutsWorkspacePage exposes V5 Strategy & Analytics links and PlanModalityPage embeds PlanPage', (tester) async {
    final ch = _FakeV5Channel();
    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: _FakeV5LoopChannel()));
    await settlePhone(tester);

    // 两个入口改成原生的行：主线卡底部「三层战略主线」，使用情况卡底部「查看完整使用情况」。
    expect(find.text('全部计划'), findsOneWidget);
    expect(find.text('查看完整使用情况'), findsOneWidget);
    expect(find.text('● Gemini 沟通 28m'), findsOneWidget);

    await pumpPhone(tester, PlanModalityPage(channel: ch, loopChannel: _FakeV5LoopChannel()));
    await settlePhone(tester);
    expect(find.text('1. 准备'), findsOneWidget);
    expect(find.text('2. 构建'), findsOneWidget);
    expect(find.text('3. 观看'), findsOneWidget);

    await tester.tap(find.text('2. 构建'));
    await settlePhone(tester);
    await tester.scrollUntilVisible(find.text('写申论提纲'), 300, scrollable: find.byType(Scrollable).last);
    expect(find.text('写申论提纲'), findsOneWidget);
  });
}
