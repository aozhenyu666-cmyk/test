import 'package:dsh_app/dafeiyu/channels/loop_channel.dart';
import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/plan_modality_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeLoop extends LoopChannel {
  @override
  Future<List<LoopApp>> apps() async => const [
        LoopApp('md.obsidian', 'Obsidian'),
        LoopApp('com.tencent.mm', '微信'),
        LoopApp('tv.danmaku.bili', '哔哩哔哩'),
      ];
}

class _FakePlanModalityChannel extends ProactiveChannel {
  _FakePlanModalityChannel({
    this.legacyOnly = false,
    this.custody,
    this.custodyPolicy,
    this.adaptiveLog,
  });

  final bool legacyOnly;
  final Map<String, dynamic>? custody;
  final Map<String, dynamic>? custodyPolicy;
  final List<Map<String, dynamic>>? adaptiveLog;
  final List<Map<String, Object?>> planOps = [];
  final List<Map<String, Object?>> todayStarts = [];
  final List<Map<String, Object?>> geminiLaunches = [];
  int tickCalls = 0;

  @override
  Future<Map<String, dynamic>?> planContractOp([Map<String, Object?>? body]) async {
    if (body != null) planOps.add(body);
    if (legacyOnly) {
      return {
        'ok': true,
        'contract': null,
        'presets': const [],
      };
    }
    return {
      'ok': true,
      'contract': {
        'id': 'c-1',
        'title': '行测资料提炼契约',
        'allowedApps': ['md.obsidian'],
        'strictEscalated': false,
        'checkpoints': [
          {
            'id': 'cp-1',
            'title': '交资料分析错因卡',
            'mode': 'video_extract',
            'expectedDeliverable': '3 段因果链',
            'status': 'pending',
          },
        ],
      },
      'dayPlan': {
        'energyLevel': 'mid',
        if (custody != null) 'custody': custody,
        'custodyPolicy': custodyPolicy ?? const {'autoKickoffEnabled': true, 'adaptiveSplitEnabled': true},
        if (adaptiveLog != null) 'adaptiveLog': adaptiveLog,
        'carryoverItems': [
          {
            'id': 'y-1',
            'title': '昨日遗留：申论大作文提纲',
            'minutes': 45,
            'lockMode': 'strict',
            'deliverable': '完成三段提纲',
            'moved': false,
          },
        ],
        'dayBlocks': [
          {
            'id': 'blk-1',
            'slot': 'morning',
            'startMin': 540,
            'endMin': 585,
            'title': '行测资料分析 30 题',
            'deliverable': '完成 30 题并录错因',
            'lockMode': 'strict',
            'allowedApps': ['md.obsidian'],
            'status': 'pending',
            'isBreak': false,
          },
          {
            'id': 'blk-lunch',
            'slot': 'lunch',
            'startMin': 720,
            'endMin': 840,
            'title': '午休合法放风（12:00–14:00 自动免锁）',
            'deliverable': '14:00 准时回工位',
            'lockMode': 'gentle',
            'allowedApps': ['md.obsidian', 'com.tencent.mm'],
            'status': 'pending',
            'isBreak': true,
          },
        ],
      },
    };
  }

  @override
  Future<Map<String, dynamic>?> todayStatus() async => {
        'timeLedger': {'funLeft': 40},
        'horizons': {
          'medium': [
            {'title': '资料分析 200 题'},
          ],
        },
      };

  @override
  Future<Map<String, Object?>?> planDay() async => {
        'min': 540,
        'protocols': const [
          {'id': 'execute', 'name': '深度执行', 'minutes': 45},
        ],
        'plan': const {'items': []},
      };

  @override
  Future<Map<String, dynamic>?> todayStart({
    required String goal,
    int minutes = 45,
    bool strict = true,
    List<String>? allow,
    String protocolId = 'execute',
  }) async {
    todayStarts.add({'goal': goal, 'minutes': minutes, 'strict': strict, 'allow': allow});
    return {'ok': true};
  }

  @override
  Future<bool> tickNow() async {
    tickCalls++;
    return true;
  }

  @override
  Future<bool> launchGeminiProactive({
    String topic = '',
    String brief = '',
    String mode = 'countdown_3s',
    bool preferVoice = true,
  }) async {
    geminiLaunches.add({'topic': topic, 'brief': brief, 'mode': mode});
    return true;
  }
}

void main() {
  testWidgets('PlanModalityPage 准备→构建→观看 三态全流程交互与红线/开干/拆步/验收', (tester) async {
    String? copiedText;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copiedText = (call.arguments as Map?)?['text'] as String?;
        }
        return null;
      },
    );
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));

    final ch = _FakePlanModalityChannel();
    await pumpPhone(tester, PlanModalityPage(channel: ch, loopChannel: _FakeLoop()));
    await settlePhone(tester);

    // 1. 准备态：三页签可见 + 精力三选一 + 昨日未完结一键移入 + 拉 Gemini 挑硬骨头
    expect(find.text('1. 准备'), findsOneWidget);
    expect(find.text('2. 构建'), findsOneWidget);
    expect(find.text('3. 观看'), findsOneWidget);

    await tester.tap(find.text('冲刺'));
    await settlePhone(tester);
    expect(ch.planOps.any((op) => op['action'] == 'set_energy' && op['energyLevel'] == 'high'), isTrue);

    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('carryover-move-y-1')),
      200,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const ValueKey('carryover-move-y-1')));
    await settlePhone(tester);
    expect(ch.planOps.any((op) => op['action'] == 'move_carryover' && op['carryoverId'] == 'y-1'), isTrue);
    expect(find.text('已移入'), findsOneWidget);

    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('plan-gemini-hard-tasks-btn')),
      -200,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const ValueKey('plan-gemini-hard-tasks-btn')));
    await settlePhone(tester);
    expect(ch.geminiLaunches.length, 1);
    expect(copiedText, contains('1–3 件硬骨头'));

    // 2. 构建态：专注预估 vs 娱乐预算条 + 午休 12:00-14:00 放风标注 + 点击编辑块时长/锁强度
    await tester.tap(find.text('2. 构建'));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('plan-focus-vs-fun-bar')), findsOneWidget);
    expect(find.textContaining('娱乐余额预算 40m'), findsOneWidget);
    expect(find.text('合法放风'), findsOneWidget);
    expect(find.text('行测资料分析 30 题'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('plan-block-blk-1')));
    await settlePhone(tester);
    expect(find.text('编辑时间块'), findsOneWidget);
    final dialog = find.byType(AlertDialog);
    await tester.tap(find.descendant(of: dialog, matching: find.text('60m')));
    await settlePhone(tester);
    await tester.tap(find.descendant(of: dialog, matching: find.text('验收锁')));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('block-editor-save')));
    await settlePhone(tester);
    await tester.pump(const Duration(milliseconds: 700));
    expect(ch.planOps.any((op) => op['action'] == 'upsert_block'), isTrue);

    // 3. 观看态：24h 时间轴 + 红线指示器 + 一键开干 / 叫 Gemini 拆第一步 / 提交验收过关
    await tester.tap(find.text('3. 观看'));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('watch-lock-attribution-bar')), findsOneWidget);
    expect(find.byKey(const ValueKey('watch-active-block-card')), findsOneWidget);
    expect(find.byKey(const ValueKey('watch-24h-timeline'), skipOffstage: false), findsOneWidget);
    expect(find.byKey(const ValueKey('watch-now-redline'), skipOffstage: false), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('watch-start-block')));
    await settlePhone(tester);
    expect(ch.todayStarts.length, 1);
    expect(ch.tickCalls, greaterThanOrEqualTo(1));

    await tester.tap(find.byKey(const ValueKey('watch-gemini-step')));
    await settlePhone(tester);
    expect(ch.geminiLaunches.length, 2);

    await tester.tap(find.byKey(const ValueKey('watch-verify-block')));
    await settlePhone(tester);
    // verify 块禁止空口过关：少于12字拦截，>=12字走 submit_delivery 等待终审
    await tester.tap(find.text('提交交付送审'));
    await settlePhone(tester);
    expect(find.textContaining('至少 12 个字'), findsOneWidget);

    await tester.enterText(
      find.byKey(const ValueKey('verify-delivery-input')),
      '已完成60分钟行测资料分析真题并整理3条错因写进Obsidian',
    );
    await tester.tap(find.text('提交交付送审'));
    await settlePhone(tester);
    await tester.pump(const Duration(milliseconds: 700));
    expect(ch.planOps.any((op) => op['action'] == 'submit_delivery' && op['blockId'] == 'blk-1'), isTrue);
    expect(ch.planOps.any((op) => op['action'] == 'set_block_status' && op['status'] == 'done'), isFalse);
  });

  testWidgets('PlanModalityPage 后端返回旧字段/空数据时本地兜底不崩溃', (tester) async {
    final ch = _FakePlanModalityChannel(legacyOnly: true);
    await pumpPhone(tester, PlanModalityPage(channel: ch, loopChannel: _FakeLoop()));
    await settlePhone(tester);

    expect(find.text('1. 准备'), findsOneWidget);
    await tester.tap(find.text('2. 构建'));
    await settlePhone(tester);
    // 默认生成推荐时间盒并包含午休 12:00-14:00 放风标注
    expect(find.text('合法放风'), findsOneWidget);

    await tester.tap(find.text('3. 观看'));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('watch-24h-timeline'), skipOffstage: false), findsOneWidget);
    expect(find.byKey(const ValueKey('watch-now-redline'), skipOffstage: false), findsOneWidget);
  });

  testWidgets('PlanModalityPage 托管哨兵策略切换、托管横幅与 Re-brief 校验弹窗', (tester) async {
    final ch = _FakePlanModalityChannel(
      custodyPolicy: const {'autoKickoffEnabled': true, 'adaptiveSplitEnabled': true},
      custody: {
        'active': true,
        'id': 'cus-1',
        'title': '早间双飞轮冲刺',
        'untilHm': '11:30',
        'rebriefCount': 0,
      },
      adaptiveLog: const [
        {'splitBlockId': 'blk-1'},
      ],
    );
    await pumpPhone(tester, PlanModalityPage(channel: ch, loopChannel: _FakeLoop()));
    await settlePhone(tester);

    final policyCard = find.byKey(const ValueKey('plan-custody-policy-card'));
    await tester.scrollUntilVisible(
      policyCard,
      200,
      scrollable: find.byType(Scrollable).last,
    );
    expect(policyCard, findsOneWidget);
    expect(find.byKey(const ValueKey('plan-custody-active-banner')), findsOneWidget);
    expect(find.textContaining('早间双飞轮冲刺'), findsOneWidget);
    expect(find.textContaining('11:30'), findsAtLeastNWidgets(1));

    final kickSwitch = find.byKey(const ValueKey('plan-auto-kickoff-switch'));
    await tester.ensureVisible(kickSwitch);
    await tester.tap(kickSwitch);
    await settlePhone(tester);
    expect(
      ch.planOps.any((op) => op['action'] == 'set_custody_policy' && op['autoKickoffEnabled'] == false),
      isTrue,
    );

    final adaptSwitch = find.byKey(const ValueKey('plan-adaptive-split-switch'));
    await tester.ensureVisible(adaptSwitch);
    await tester.tap(adaptSwitch);
    await settlePhone(tester);
    expect(
      ch.planOps.any((op) => op['action'] == 'set_custody_policy' && op['adaptiveSplitEnabled'] == false),
      isTrue,
    );

    final rebriefBtn = find.byKey(const ValueKey('plan-custody-rebrief-btn'));
    await tester.ensureVisible(rebriefBtn);
    await tester.tap(rebriefBtn);
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('plan-custody-rebrief-dialog')), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('plan-custody-rebrief-reason')), '太短');
    await tester.tap(find.byKey(const ValueKey('plan-custody-rebrief-confirm')));
    await settlePhone(tester);
    await tester.pump(const Duration(milliseconds: 700));
    expect(find.textContaining('至少 6 个字'), findsOneWidget);

    await tester.enterText(
      find.byKey(const ValueKey('plan-custody-rebrief-reason')),
      '临时有线下面试冲突需改切25分钟保底推进',
    );
    await tester.tap(find.byKey(const ValueKey('plan-custody-rebrief-confirm')));
    await settlePhone(tester);
    await tester.pump(const Duration(milliseconds: 700));
    expect(
      ch.planOps.any((op) => op['action'] == 'rebrief_custody' && '${op['reason']}'.contains('线下面试')),
      isTrue,
    );
    expect(ch.tickCalls, greaterThanOrEqualTo(3));
  });
}
