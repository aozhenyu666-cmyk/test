import 'package:dsh_app/dafeiyu/channels/loop_channel.dart';
import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bouts_workspace_page.dart';
import 'package:dsh_app/dafeiyu/ui/companion_hub_page.dart';
import 'package:dsh_app/dafeiyu/ui/voice_settings_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeLoopChannel extends LoopChannel {
  @override
  Future<List<LoopApp>> apps() async => const [
        LoopApp('md.obsidian', 'Obsidian'),
        LoopApp('com.tencent.mm', '微信'),
        LoopApp('org.mozilla.firefox', 'Firefox 浏览器'),
      ];
}

class _FakeV4Channel extends ProactiveChannel {
  List<Map<String, dynamic>> clocks = [
    {
      'id': 'clk_pomodoro',
      'label': '25/5 核心番茄',
      'kind': 'CYCLE_PHASE',
      'paused': false,
      'enabled': true,
      'nextFireWall': DateTime.now().millisecondsSinceEpoch + 120000,
    },
  ];
  Map<String, dynamic> bouts = {
    'running': {
      'title': '推进 v4.1 收尾',
      'strict': true,
      'until': DateTime.now().millisecondsSinceEpoch + 900000,
      'allow': ['md.obsidian'],
    },
    'queue': [],
  };
  Map<String, dynamic> todayData = {
    'brief': {'line': '今日专注约 30m · 娱乐 5m · 正在推进「推进 v4.1 收尾」', 'modelsCount': 3},
    'tasks': [],
    'defaultAllow': ['md.obsidian', 'com.tencent.mm'],
  };
  bool storageGranted = false;
  int restartCalls = 0;
  int tickCalls = 0;
  final List<String> controlOps = [];
  final List<Map<String, Object?>> boutCommands = [];
  final List<Map<String, Object?>> todayStarts = [];
  final List<Map<String, Object?>> todayTasks = [];

  @override
  Future<bool> tickNow() async {
    tickCalls++;
    return true;
  }

  @override
  Future<List<Map<String, dynamic>>> clockList() async => clocks;

  @override
  Future<Map<String, dynamic>?> clockControl(String id, String op) async {
    controlOps.add('$id:$op');
    clocks = [
      for (final c in clocks)
        if (c['id'] == id) {...c, 'paused': op == 'pause'} else c,
    ];
    return {'ok': true};
  }

  @override
  Future<Map<String, dynamic>?> boutsStatus() async => bouts;

  @override
  Future<Map<String, dynamic>?> todayStatus() async => {
        ...todayData,
        'running': bouts['running'],
      };

  @override
  Future<Map<String, dynamic>?> todayStart({
    required String goal,
    int minutes = 45,
    bool strict = true,
    List<String>? allow,
    String protocolId = 'execute',
  }) async {
    todayStarts.add({'goal': goal, 'minutes': minutes, 'strict': strict, 'allow': allow, 'protocolId': protocolId});
    bouts = {
      ...bouts,
      'running': {
        'title': goal,
        'strict': strict,
        'until': DateTime.now().millisecondsSinceEpoch + minutes * 60000,
        'allow': allow ?? ['md.obsidian'],
      },
    };
    return {'ok': true, 'strict': strict, 'goal': goal};
  }

  @override
  Future<Map<String, dynamic>?> todayTask({
    String action = 'add',
    String? title,
    String? id,
    int? index,
    int minutes = 45,
    List<String>? allow,
    bool top = false,
  }) async {
    todayTasks.add({'action': action, 'title': title, 'minutes': minutes});
    return {'ok': true, 'text': '已加入 Today 主线：$title'};
  }

  @override
  Future<Map<String, dynamic>?> boutsCommand(Map<String, Object?> body) async {
    boutCommands.add(body);
    return {'ok': true, 'say': 'ok'};
  }

  @override
  Future<Map<String, dynamic>?> phoneStorageStatus() async => {'granted': storageGranted};

  @override
  Future<Map<String, dynamic>?> enablePhoneStorage({bool restart = true}) async {
    storageGranted = true;
    return {'ok': true, 'granted': true};
  }

  @override
  Future<bool> restartEngine() async {
    restartCalls++;
    return true;
  }

  @override
  Future<Map<String, Object?>?> wallStatus() async => {'locked': false};

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
}

void main() {
  testWidgets('Today workbench renders overview, live strict focus, allowlist picker and system details clocks', (tester) async {
    final ch = _FakeV4Channel();
    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: _FakeLoopChannel()));
    await settlePhone(tester);

    expect(find.text('Today · 今日总揽'), findsWidgets);
    expect(find.text('此刻焦距 · 进行中'), findsOneWidget);
    expect(find.text('推进 v4.1 收尾'), findsOneWidget);
    expect(find.text('严格锁'), findsOneWidget);
    expect(find.text('摇一骰'), findsOneWidget);
    expect(find.text('执行记录'), findsOneWidget);

    // 调整运行中白名单并点确认按钮，确认命令可靠发出
    await tester.tap(find.text('调整白名单'));
    await settlePhone(tester);
    await tester.tap(find.text('Firefox 浏览器'));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('app-picker-confirm')));
    await settlePhone(tester);
    expect(ch.boutCommands.last['action'], 'allow');
    expect((ch.boutCommands.last['allow'] as List), containsAll(['md.obsidian', 'org.mozilla.firefox']));

    // 进入二级「系统明细 -> 时钟核」验证原工程能力完整保留
    await tester.tap(find.byTooltip('系统明细（队列 / 协议库 / 时钟核）'));
    await settlePhone(tester);
    expect(find.text('25/5 核心番茄'), findsOneWidget);
    await tester.tap(find.byTooltip('暂停'));
    await settlePhone(tester);
    expect(ch.controlOps, ['clk_pomodoro:pause']);
  });

  testWidgets('局详情：点此刻焦距进完整页，同一套动作（放行 / 调白名单 / 收工验收）', (tester) async {
    final ch = _FakeV4Channel();
    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: _FakeLoopChannel()));
    await settlePhone(tester);

    await tester.tap(find.text('推进 v4.1 收尾'));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('bout-detail-page')), findsOneWidget);
    expect(find.text('局详情'), findsWidgets);
    expect(find.text('分钟后到点'), findsOneWidget);
    // 放行列表显示 App 名字（从手机读到的），不是包名。
    expect(find.text('Obsidian'), findsWidgets);
    expect(find.text('md.obsidian'), findsNothing);

    await tester.tap(find.byKey(const ValueKey('bout-detail-allow')));
    await settlePhone(tester);
    await tester.tap(find.text('Firefox 浏览器'));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('app-picker-confirm')));
    await settlePhone(tester);
    expect(ch.boutCommands.last['action'], 'allow');

    await tester.ensureVisible(find.byKey(const ValueKey('bout-detail-close')));
    await tester.tap(find.byKey(const ValueKey('bout-detail-close')));
    await settlePhone(tester);
    expect(ch.boutCommands.last['action'], 'close');
  });

  testWidgets('Today workbench defaults to strict lock for one-line launch and selects installed whitelist apps', (tester) async {
    final ch = _FakeV4Channel();
    ch.bouts = {'running': null, 'queue': []};
    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: _FakeLoopChannel()));
    await settlePhone(tester);

    expect(find.text('默认严格锁'), findsOneWidget);
    expect(find.text('立即开启严格专注'), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('today-goal-input')), '完成 Today 工作台改造');
    await tester.tap(find.byKey(const ValueKey('today-pick-allow-btn')));
    await settlePhone(tester);

    await tester.tap(find.text('Firefox 浏览器'));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('app-picker-confirm')));
    await settlePhone(tester);

    await tester.tap(find.byKey(const ValueKey('today-start-btn')));
    await settlePhone(tester);

    expect(ch.todayStarts.length, 1);
    expect(ch.todayStarts.single['goal'], '完成 Today 工作台改造');
    expect(ch.todayStarts.single['strict'], isTrue);
    expect((ch.todayStarts.single['allow'] as List), containsAll(['md.obsidian', 'com.tencent.mm', 'org.mozilla.firefox']));
  });

  testWidgets('VoiceSettingsPage shows 3 independent voice roles', (tester) async {
    await pumpPhone(tester, const VoiceSettingsPage());
    await settlePhone(tester);

    expect(find.text('语音与角色声线'), findsWidgets);
    expect(find.text('鱼丸（妹妹）'), findsOneWidget);
    expect(find.text('鲸澜（姐姐）'), findsOneWidget);
    expect(find.text('局官（督战）'), findsOneWidget);
    expect(find.text('试听'), findsNWidgets(3));
  });

  testWidgets('CompanionHubPage exposes Obsidian storage mount and hot restart', (tester) async {
    final ch = _FakeV4Channel();
    await pumpPhone(tester, CompanionHubPage(channel: ch));
    await settlePhone(tester);

    await tester.tap(find.byKey(const ValueKey('hub-group-data')));
    await settlePhone(tester);
    expect(find.text('手机目录与 Obsidian 自动挂载'), findsOneWidget);
    await tester.tap(find.text('手机目录与 Obsidian 自动挂载'));
    await settlePhone(tester);
    expect(ch.storageGranted, isTrue);

    await popPage(tester);
    await tester.tap(find.byKey(const ValueKey('hub-group-diag')));
    await settlePhone(tester);
    await tester.tap(find.text('一键热重启主动引擎'));
    await settlePhone(tester);
    expect(ch.restartCalls, 1);
  });

  testWidgets('Today workbench preset chip prefills focus form and shows prompt scripts sheet', (tester) async {
    final ch = _FakeV4Channel();
    ch.bouts = {'running': null, 'queue': []};
    ch.todayData = {
      ...ch.todayData,
      'contract': {
        'id': 'c-test',
        'title': '看网课提炼因果卡',
        'strictEscalated': false,
        'checkpoints': [
          {
            'id': 'cp-1',
            'title': '交因果卡',
            'status': 'pending',
            'dueAt': DateTime.now().millisecondsSinceEpoch + 1200000,
          },
        ],
      },
    };
    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: _FakeLoopChannel()));
    await settlePhone(tester);

    expect(find.text('契约：看网课提炼因果卡'), findsOneWidget);
    expect(find.text('去验收'), findsOneWidget);

    await tester.tap(find.byTooltip('常用 AI 话术剧本（点即复制）'));
    await settlePhone(tester);
    expect(find.text('常用 AI 话术剧本表（免自己打字）'), findsOneWidget);
    await tester.tap(find.text('关闭'));
    await settlePhone(tester);

    await tester.tap(find.text('考公刷题 60m'));
    await settlePhone(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('today-start-btn')));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('today-start-btn')));
    await settlePhone(tester);

    expect(ch.todayStarts.single['goal'], '考公行测/申论专项刷题与错题复盘');
    expect(ch.todayStarts.single['minutes'], 60);
  });

  testWidgets('局页主线卡：时间轴上下一个没交的验收点（开始–截止、进度、今天过了几个）', (tester) async {
    final ch = _TimelineChannel();
    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: _FakeLoopChannel()));
    await settlePhone(tester);
    final row = find.byKey(const ValueKey('today-timeline'), skipOffstage: false);
    expect(row, findsOneWidget);
    expect(find.text('下一个：14:00–15:30「资料分析 30 题」', skipOffstage: false), findsOneWidget);
    expect(find.textContaining('4/30题 · 保底 15 · 今天 1/2 过了', skipOffstage: false), findsOneWidget);
  });

  testWidgets('Today 时长胶囊自定义 30m 开局 + 温和局升严格选 35m，均立即触发 tickNow', (tester) async {
    final ch = _FakeV4Channel();
    ch.bouts = {'running': null, 'queue': []};
    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: _FakeLoopChannel()));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('today-duration-25m')), findsOneWidget);
    expect(find.byKey(const ValueKey('today-duration-45m')), findsOneWidget);
    expect(find.byKey(const ValueKey('today-duration-90m')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('today-duration-custom')));
    await settlePhone(tester);
    await tester.enterText(find.byKey(const ValueKey('bout-duration-custom-input')), '30');
    await tester.tap(find.byKey(const ValueKey('bout-duration-confirm')));
    await settlePhone(tester);

    await tester.tap(find.byKey(const ValueKey('today-start-btn')));
    await settlePhone(tester);
    expect(ch.todayStarts.single['minutes'], 30);
    expect(ch.tickCalls, 1);

    // 切温和局再升严格并选自定义 35m
    ch.bouts = {
      'running': {'title': '温和推进中', 'strict': false, 'until': DateTime.now().millisecondsSinceEpoch + 900000, 'allow': ['md.obsidian']},
      'queue': [],
    };
    await pumpPhone(tester, BoutsWorkspacePage(key: const ValueKey('mild-to-strict'), asTab: true, channel: ch, loopChannel: _FakeLoopChannel()));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('today-bout-strict-toggle')));
    await settlePhone(tester);
    await tester.enterText(find.byKey(const ValueKey('bout-duration-custom-input')), '35');
    await tester.tap(find.byKey(const ValueKey('bout-duration-confirm')));
    await settlePhone(tester);

    expect(ch.boutCommands.last['action'], 'strict');
    expect(ch.boutCommands.last['minutes'], 35);
    expect(ch.tickCalls, 2);
    await tester.pump(const Duration(seconds: 1));
  });
}

class _TimelineChannel extends _FakeV4Channel {
  @override
  Future<Map<String, dynamic>?> sealsOp(Map<String, Object?> body) async => {
        'ok': true,
        'today': '2026-11-21',
        'locking': null,
        'items': [
          {'id': 'a', 'day': '2026-11-21', 'title': '早上的', 'kind': 'quiz', 'stretch': 10, 'floor': 5, 'dueMinute': 600, 'actual': 10, 'status': 'stretch'},
          {'id': 'b', 'day': '2026-11-21', 'title': '资料分析 30 题', 'kind': 'quiz', 'stretch': 30, 'floor': 15, 'startMinute': 840, 'dueMinute': 930, 'actual': 4, 'status': 'open'},
          {'id': 'c', 'day': '2026-11-22', 'title': '明天的', 'kind': 'quiz', 'stretch': 10, 'floor': 5, 'dueMinute': 600, 'actual': 0, 'status': 'open'},
        ],
      };
}
