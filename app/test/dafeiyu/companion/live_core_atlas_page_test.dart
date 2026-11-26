import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/live_core_atlas_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeAtlasChannel extends ProactiveChannel {
  _FakeAtlasChannel({
    this.offline = false,
    this.shizukuReady = true,
    this.shizukuRunning = true,
  });

  final bool offline;
  final bool shizukuReady;
  final bool shizukuRunning;
  final List<Map<String, Object?>?> liveCoreCalls = [];
  final List<Map<String, Object?>> autoLiveCalls = [];
  final List<String> openCapCalls = [];

  @override
  Future<bool> openCapability(String kind) async {
    openCapCalls.add(kind);
    return true;
  }

  @override
  Future<Map<String, dynamic>?> liveCoreOp([Map<String, Object?>? body]) async {
    liveCoreCalls.add(body);
    if (offline) return null;
    final action = body?['action'] ?? 'status';
    if (action == 'set_config') {
      final alwaysOn = body?['alwaysOnLive'] == true;
      final energyTier = '${body?['energyTier'] ?? 'L2'}';
      final headsetKeepalive = body?['headsetKeepalive'] != false;
      final pulseIntervalMin = (body?['pulseIntervalMin'] as num?)?.round() ?? 0;
      return {
        'v': 1,
        'ok': true,
        'enabled': true,
        'alwaysOnLive': alwaysOn,
        'keeperStrict': body?['keeperStrict'] != false,
        'wantScreenShare': true,
        'energyTier': energyTier,
        'headsetKeepalive': headsetKeepalive,
        'pulseIntervalMin': pulseIntervalMin,
        'phase': alwaysOn ? 'ignited' : 'standby',
        'reason': alwaysOn ? 'always_on_keeper' : 'standby',
        'feedPreview': const {
          'quizzes': [
            {'topic': '判断推理', 'prompt': '论证削弱之另有他因', 'mistake': '刚才答错'},
          ],
          'experiences': ['昨天主动用 Live 拆通了逻辑链条'],
          'sisterSummary': '鱼丸/鲸澜已备好 1 道刷题与记忆卡、1 条个人经历',
        },
        'toast': '已更新 Gemini 窥视核心设置',
      };
    }
    if (action == 'switch_mode') {
      final isText = body?['mode'] == 'text';
      return {
        'v': 1,
        'ok': true,
        'enabled': true,
        'alwaysOnLive': true,
        'keeperStrict': true,
        'wantScreenShare': !isText,
        'preferredMode': isText ? 'text' : 'live',
        'textGraceActive': isText,
        'phase': isText ? 'text_mode' : 'ignited',
        'reason': isText ? 'switch_to_text' : 'switch_to_live',
        'dice': const {'face': 3, 'name': '错题追击战'},
        'payload': {
          'topic': isText ? '文本沉淀：MCP落库' : '骰3·错题追击战：测试考点',
          'brief': isText ? '【Gemini 文本模式衔接与 MCP 沉淀剧本】' : '【禁聊体系死命令】直接开考',
          'voiceLine': isText ? '已切至文本模式' : '进 Live 开考',
          'autoClickLive': !isText,
          'requestScreenShare': !isText,
          'dice': const {'face': 3, 'name': '错题追击战'},
        },
        'feedPreview': const {
          'quizzes': [
            {'topic': '判断推理', 'prompt': '论证削弱之另有他因', 'mistake': '刚才答错'},
          ],
          'experiences': ['昨天主动用 Live 拆通了逻辑链条'],
          'sisterSummary': '鱼丸/鲸澜已备好 1 道刷题与记忆卡、1 条个人经历',
        },
      };
    }
    if (action == 'roll_dice') {
      return {
        'v': 1,
        'ok': true,
        'enabled': true,
        'alwaysOnLive': true,
        'keeperStrict': true,
        'autoReengage': true,
        'wantScreenShare': true,
        'phase': 'ignited',
        'reason': 'manual_dice_roll',
        'dice': {'face': 2, 'name': '笔记翻旧账', 'roleHint': '从近期笔记抽 1 条逼他口述反例'},
        'feedPreview': const {
          'quizzes': [
            {'topic': '资料分析', 'prompt': '基期比重差计算速算', 'mistake': '高频错题'},
          ],
          'experiences': ['早上已用 25m 验收锁启动'],
          'sisterSummary': '鱼丸/鲸澜已备好 1 道刷题与记忆卡、1 条个人经历',
        },
        'payload': {
          'topic': '骰2·笔记翻旧账：测试考点',
          'brief': '【禁聊体系死命令】直接开考',
          'voiceLine': '骰2笔记翻旧账，进 Live 开始',
          'dice': {'face': 2, 'name': '笔记翻旧账'},
        },
        'history': const [
          {
            'id': 'lc-1',
            'hm': '15:10',
            'kind': 'ignite',
            'badge': '手动摇骰',
            'title': '骰2 · 笔记翻旧账',
            'detail': '手动摇骰灌料',
          },
        ],
      };
    }
    if (action == 'autopilot_ignite') {
      return {
        'v': 1,
        'ok': true,
        'enabled': true,
        'alwaysOnLive': true,
        'keeperStrict': true,
        'wantScreenShare': true,
        'headsetKeepalive': true,
        'energyTier': 'L2',
        'phase': 'ignited',
        'reason': 'autopilot_ignite',
        'activeDepartment': const {'id': 'dept:dispatch', 'label': '统筹规划部', 'nextLabel': '督办审计部'},
        'payload': const {
          'topic': '全自动托管 · 2×25m 验收点',
          'brief': '【四部门自动值班：统筹规划部】直接开干',
          'voiceLine': '全自动托管已启动',
        },
        'toast': '⚡ 全自动托管已启动：自动挂好 2 个 25m 验收点 + 常驻 Live 护航',
      };
    }
    if (action == 'export_diagnostic') {
      return {
        'v': 1,
        'ok': true,
        'filename': '2026-11-25_1800_72h实证诊断包.md',
        'obsidianPath': '/vault/诊断导出/2026-11-25_1800_72h实证诊断包.md',
        'metrics': const {
          'live': {'sessionsRecorded': 4, 'avgDurationSec': 210, 'igniteCount': 6},
          'output': {'quizTotal': 18, 'sealsDone': 2, 'sealsTotal': 3},
        },
        'markdown': '# 大肥鱼 × Gemini Live 72h 实证诊断包\n- 连麦 4 次',
        'toast': '已生成 72h 实证诊断包',
      };
    }
    if (action == 'commit_relay') {
      final mode = '${body?['mode'] ?? 'checkpoint_25m'}';
      return {
        'v': 1,
        'ok': true,
        'enabled': true,
        'phase': 'standby',
        'toast': mode == 'quiz_only' ? '已切入 20m 纯考公刷题舱' : '已签下 25m 微验收点',
        'history': [
          {
            'id': 'lc-relay-1',
            'hm': '15:25',
            'kind': mode == 'quiz_only' ? 'capsule' : 'relay',
            'badge': '接力回流',
            'title': mode == 'quiz_only' ? '接力进 20m 纯刷题舱' : '签下 25m 微验收点',
            'detail': '${body?['next'] ?? '已完成交接'}',
          },
        ],
      };
    }
    return {
      'v': 1,
      'ok': true,
      'enabled': true,
      'alwaysOnLive': false,
      'keeperStrict': true,
      'autoReengage': true,
      'wantScreenShare': true,
      'muted': false,
      'phase': 'standby',
      'reason': 'standby',
      'dice': const {'face': 1, 'name': '窥屏现抓'},
      'feedPreview': const {
        'quizzes': [
          {'topic': '判断推理', 'prompt': '论证削弱之另有他因', 'mistake': '刚才答错'},
        ],
        'experiences': ['昨天主动用 Live 拆通了逻辑链条'],
        'sisterSummary': '鱼丸/鲸澜已备好 1 道刷题与记忆卡、1 条个人经历',
      },
      'atlas': const {
        'pipeline': [
          {'step': 1, 'key': 'sense', 'label': '感知现场', 'status': 'ready'},
          {'step': 2, 'key': 'dice', 'label': '骰子灌料', 'status': 'active'},
          {'step': 3, 'key': 'live', 'label': '官方 Live', 'status': 'ready'},
          {'step': 4, 'key': 'relay', 'label': '25m 接力', 'status': 'ready'},
        ],
        'pillars': [
          {
            'id': 'ignite',
            'title': '① 窥视点火（1 颗骰子）',
            'tagline': '后台整合设定/错题/笔记/屏幕摘录，一键灌进官方 Gemini Live',
            'triggerRule': '白天防分心窗空转/听歌/断线未复盘时自动点火',
            'yieldRule': '刷题/学习/睡眠/计划模态/连刷 5 题时绝对让路',
            'status': 'ready',
            'statusLabel': '待命中',
            'metricText': '今日点火 2 次 · 拉回 1 次',
            'route': 'live_core',
          },
          {
            'id': 'locks',
            'title': '② 零冲突锁仲裁（P0–P6 · 6 只手）',
            'tagline': '单一裁判裁决优先级，绝无影子锁互相打架',
            'triggerRule': '按 P0 急事 > P1 睡眠 > P2 连麦舱 > P3 逾期验收 > P4 专心局/防分心 > P6 余额 裁决',
            'yieldRule': '应急通道、电话/短信/支付/导航永不误锁',
            'status': 'active',
            'statusLabel': 'P4 生效中',
            'metricText': '当前：白天防分心窗',
            'route': 'locks',
          },
          {
            'id': 'plan',
            'title': '③ 计划模态与验收锁（25m 接力）',
            'tagline': '先定今日计划再启动验收锁；Live 聊完可 1 键签 25m 微验收点',
            'triggerRule': '未定计划时温和拉回，到点未交按节点转 P3 验收锁',
            'yieldRule': '已过保底或放风/午休时段不打扰',
            'status': 'ready',
            'statusLabel': '今日 2 个点',
            'metricText': '今日接力 1 次 · 单开舱 0 次',
            'route': 'plan',
          },
          {
            'id': 'quiz',
            'title': '④ 刷题账本与记忆回流（10 题 5 分）',
            'tagline': '刷题挣余额不被旧债吞噬，错题与 FSRS 到期卡自动灌回下次 Live',
            'triggerRule': '锁屏刷题或进入 20m 纯刷题舱直接累积正向额度',
            'yieldRule': '连刷 5 题自动触发 20m 静音泄压阀',
            'status': 'active',
            'statusLabel': '正产出中',
            'metricText': '今日刷题 12 题 · Live 18m',
            'route': 'bank',
          },
        ],
        'lockLadder': [
          {'priority': 'P0', 'name': '应急通道 & 安全底线', 'rule': '3m 应急解禁', 'active': false},
          {'priority': 'P4', 'name': '专心局 & 白天防分心窗', 'rule': '闲逛时自动点火进 Live', 'active': true},
        ],
      },
      'history': const [
        {
          'id': 'h1',
          'hm': '14:20',
          'kind': 'ignite',
          'badge': '窥视点火',
          'title': '骰1 · 窥屏现抓',
          'detail': '白天防分心窗空转点火',
        },
        {
          'id': 'h2',
          'hm': '14:35',
          'kind': 'yield',
          'badge': '自动让路',
          'title': '连刷 5 题触发静音泄压 20m',
          'detail': '裁决阶段：yielded_quiz_relief',
        },
        {
          'id': 'h3',
          'hm': '15:05',
          'kind': 'relay',
          'badge': '接力回流',
          'title': '签下 25m 微验收点',
          'detail': '判断推理 15 题',
        },
      ],
    };
  }

  @override
  Future<ProactiveStatus?> status() async {
    if (offline) return null;
    return ProactiveStatus(
      capabilities: {
        'a11y': true,
        'shizuku': shizukuReady,
        'shizukuRunning': shizukuRunning,
        'a11yAutoHeal': shizukuReady,
        'overlay': true,
      },
    );
  }

  @override
  Future<Map<String, dynamic>?> bridgeOp(Map<String, Object?> body) async {
    if (offline) return null;
    return const {'ok': true, 'healthy': true, 'configured': true};
  }

  @override
  Future<bool> triggerGeminiAutoLive({
    String topic = '',
    String brief = '',
    String voiceLine = '',
    String mode = 'instant',
    bool autoClickLive = true,
    bool requestScreenShare = false,
  }) async {
    autoLiveCalls.add({
      'topic': topic,
      'brief': brief,
      'voiceLine': voiceLine,
      'mode': mode,
      'autoClickLive': autoClickLive,
      'requestScreenShare': requestScreenShare,
    });
    return true;
  }
}

void main() {
  testWidgets('此刻操作：常驻 Live 护航总开关 + 双模一键互切 + 双鱼喂料预览 + 20m 纯刷题接力', (tester) async {
    final ch = _FakeAtlasChannel();
    await pumpPhone(tester, LiveCoreAtlasPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('atlas-phase-chip')), findsOneWidget);
    expect(find.byKey(const ValueKey('atlas-always-on-switch')), findsOneWidget);
    expect(find.byKey(const ValueKey('atlas-feed-preview-card')), findsOneWidget);
    expect(find.textContaining('论证削弱之另有他因'), findsOneWidget);
    expect(find.textContaining('昨天主动用 Live 拆通了逻辑链条'), findsOneWidget);
    expect(find.text('无障碍已开'), findsOneWidget);
    expect(find.text('Shizuku 就绪'), findsOneWidget);
    expect(find.text('Gemini 桥在线'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('atlas-always-on-switch')));
    await settlePhone(tester);
    expect(
      ch.liveCoreCalls.any((c) => c?['action'] == 'set_config' && c?['alwaysOnLive'] == true),
      isTrue,
    );

    await tester.tap(find.byKey(const ValueKey('atlas-reroll-feed-btn')));
    await settlePhone(tester);
    expect(ch.liveCoreCalls.any((c) => c?['action'] == 'roll_dice'), isTrue);
    expect(find.textContaining('基期比重差计算速算'), findsOneWidget);
    expect(ch.autoLiveCalls, isEmpty);

    await tester.tap(find.byKey(const ValueKey('atlas-roll-live-btn')));
    await settlePhone(tester);
    expect(
      ch.liveCoreCalls.any((c) => c?['action'] == 'switch_mode' && c?['mode'] == 'live'),
      isTrue,
    );
    expect(ch.autoLiveCalls.length, 1);
    expect(ch.autoLiveCalls.first['autoClickLive'], isTrue);
    expect(ch.autoLiveCalls.first['requestScreenShare'], isTrue);

    await tester.tap(find.byKey(const ValueKey('atlas-switch-text-btn')));
    await settlePhone(tester);
    expect(
      ch.liveCoreCalls.any((c) => c?['action'] == 'switch_mode' && c?['mode'] == 'text'),
      isTrue,
    );
    expect(ch.autoLiveCalls.length, 2);
    expect(ch.autoLiveCalls.last['autoClickLive'], isFalse);
    expect(ch.autoLiveCalls.last['requestScreenShare'], isFalse);

    await tester.tap(find.byKey(const ValueKey('atlas-relay-quiz-btn')));
    await settlePhone(tester);
    expect(
      ch.liveCoreCalls.any((c) => c?['action'] == 'commit_relay' && c?['mode'] == 'quiz_only'),
      isTrue,
    );
    expect(find.textContaining('20m 纯考公刷题舱'), findsWidgets);
  });

  testWidgets('功能图谱：展示 4 大支柱与 P0-P6 梯子且无 8 道锁残留', (tester) async {
    final ch = _FakeAtlasChannel();
    await pumpPhone(tester, LiveCoreAtlasPage(channel: ch, initialTab: LiveCoreAtlasTab.atlas));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('atlas-pipeline-card')), findsOneWidget);
    expect(find.byKey(const ValueKey('atlas-pillar-ignite')), findsOneWidget);
    expect(find.byKey(const ValueKey('atlas-pillar-locks')), findsOneWidget);
    expect(find.byKey(const ValueKey('atlas-pillar-plan')), findsOneWidget);
    expect(find.byKey(const ValueKey('atlas-pillar-quiz')), findsOneWidget);
    expect(find.textContaining('8 道锁'), findsNothing);

    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('atlas-ladder-card')),
      260,
      scrollable: find.byType(Scrollable).last,
    );
    expect(find.textContaining('P0–P6'), findsWidgets);
  });

  testWidgets('运行记录：展示流水并支持点火/让路/接力筛选；离线空包降级不崩溃', (tester) async {
    final ch = _FakeAtlasChannel();
    await pumpPhone(tester, LiveCoreAtlasPage(channel: ch, initialTab: LiveCoreAtlasTab.records));
    await settlePhone(tester);

    expect(find.textContaining('最近 3 条记录'), findsOneWidget);
    expect(find.textContaining('骰1 · 窥屏现抓'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('atlas-filter-yield')));
    await settlePhone(tester);
    expect(find.textContaining('最近 1 条记录'), findsOneWidget);
    expect(find.textContaining('连刷 5 题触发静音泄压 20m'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
    final offlineCh = _FakeAtlasChannel(offline: true);
    await pumpPhone(tester, LiveCoreAtlasPage(channel: offlineCh));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('atlas-roll-live-btn')), findsOneWidget);

    await tester.tap(find.text('功能图谱'));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('atlas-pillar-ignite')), findsOneWidget);
  });

  testWidgets('剪贴板直喂 Live + L0–L3 能量档切换 + Shizuku 运行中可点授权', (tester) async {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.getData') {
        return <String, dynamic>{'text': 'https://example.com/q1 削弱论证怎么找它因'};
      }
      return null;
    });
    addTearDown(() {
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null);
    });

    final ch = _FakeAtlasChannel(shizukuReady: false, shizukuRunning: true);
    await pumpPhone(tester, LiveCoreAtlasPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('atlas-whitelist-btn')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('atlas-clipboard-live-btn')));
    await settlePhone(tester);
    expect(
      ch.liveCoreCalls.any(
        (c) =>
            c?['action'] == 'switch_mode' &&
            c?['mode'] == 'live' &&
            '${c?['customFeed']}'.contains('https://example.com/q1'),
      ),
      isTrue,
    );
    expect(ch.autoLiveCalls.length, 1);

    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('atlas-energy-L0')),
      220,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const ValueKey('atlas-energy-L0')));
    await settlePhone(tester);
    expect(
      ch.liveCoreCalls.any((c) => c?['action'] == 'set_config' && c?['energyTier'] == 'L0'),
      isTrue,
    );

    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('atlas-pulse-chip')),
      180,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.tap(find.byKey(const ValueKey('atlas-pulse-chip')));
    await settlePhone(tester);
    expect(
      ch.liveCoreCalls.any((c) => c?['action'] == 'set_config' && c?['pulseIntervalMin'] == 5),
      isTrue,
    );

    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('atlas-shizuku-chip')),
      220,
      scrollable: find.byType(Scrollable).last,
    );
    expect(find.text('Shizuku 运行中(点授权)'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('atlas-shizuku-chip')));
    await tester.pump(const Duration(seconds: 1));
    await settlePhone(tester);
    expect(ch.openCapCalls, contains('shizuku'));
  });

  testWidgets('1 键全自动托管今日 + 72h 实证诊断包导出与分享 + 四部门值班展示', (tester) async {
    String? sharedName;
    String? sharedBody;
    final ch = _FakeAtlasChannel();
    await pumpPhone(
      tester,
      LiveCoreAtlasPage(
        channel: ch,
        onShareFile: (name, body) async {
          sharedName = name;
          sharedBody = body;
        },
      ),
    );
    await settlePhone(tester);

    await tester.tap(find.byKey(const ValueKey('atlas-autopilot-btn')));
    await settlePhone(tester);
    expect(ch.liveCoreCalls.any((c) => c?['action'] == 'autopilot_ignite'), isTrue);
    expect(find.byKey(const ValueKey('atlas-dept-line')), findsOneWidget);
    expect(find.textContaining('统筹规划部'), findsOneWidget);
    expect(ch.autoLiveCalls.length, 1);

    await tester.tap(find.byKey(const ValueKey('atlas-export-diag-btn')));
    await settlePhone(tester);
    expect(ch.liveCoreCalls.any((c) => c?['action'] == 'export_diagnostic'), isTrue);
    expect(find.text('72h 实证诊断包已生成'), findsOneWidget);
    expect(find.textContaining('连麦 4 次'), findsWidgets);

    await tester.tap(find.byKey(const ValueKey('atlas-diag-share-btn')));
    await settlePhone(tester);
    expect(sharedName, '2026-11-25_1800_72h实证诊断包.md');
    expect(sharedBody, contains('72h 实证诊断包'));
  });
}
