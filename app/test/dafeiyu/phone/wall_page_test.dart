// A3: 墙的对话界面。它不是聊天窗——只读已落账的记录，只接受「一条判定 + 一份新证据」的申诉。
//
// 这一页最容易出的错是它偷偷变成第三个人格：多一个输入框、多一句安慰、多一个「正在输入」。
// 下面每条用例都在钉住「它只做两件事」，以及「读不到就说读不到」。

import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/ui/common/list_page_row.dart';
import 'package:dsh_app/dafeiyu/ui/wall_page.dart';
import 'package:dsh_app/ui/theme/app_icons.dart';
import 'package:dsh_app/ui/theme/theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

/// 2026-11-10 09:05 本地时间：时间戳用本地构造，页面按本地还原，跑在哪个时区都对得上。
final int at = DateTime(2026, 11, 10, 9, 5).millisecondsSinceEpoch;

/// 顶替 `dsh/proactive`：不起引擎，只回答这一页会问的三件事。
class FakeWall extends ProactiveChannel {
  FakeWall({
    this.thread,
    this.statusBody,
    this.reply,
    this.bossBriefData,
    this.todayData,
    this.wallBossChatData,
  });

  WallThread? thread;

  /// 不能叫 `status`：`ProactiveChannel.status()` 是同名方法，Dart 不允许字段和方法重名。
  Map<String, Object?>? statusBody;
  WallAppeal? reply;
  Map<String, dynamic>? bossBriefData;
  Map<String, dynamic>? todayData;
  Map<String, dynamic>? wallBossChatData;

  int threadCalls = 0;
  final appeals = <({int at, String evidence, String? task})>[];
  final geminiLaunches = <({String topic, String brief})>[];
  final bossChatCalls = <Map<String, Object?>>[];
  int tickCalls = 0;

  @override
  Future<bool> tickNow() async {
    tickCalls++;
    return true;
  }

  @override
  Future<WallThread?> wallThread() async {
    threadCalls++;
    return thread;
  }

  @override
  Future<Map<String, Object?>?> wallStatus() async => statusBody;

  @override
  Future<Map<String, dynamic>?> bossBrief() async => bossBriefData;

  @override
  Future<Map<String, dynamic>?> wallBossChat([Map<String, Object?>? body]) async {
    if (body != null) bossChatCalls.add(Map<String, Object?>.from(body));
    return wallBossChatData;
  }

  @override
  Future<Map<String, dynamic>?> todayStatus() async => todayData;

  @override
  Future<bool> launchGeminiProactive({
    String topic = '',
    String brief = '',
    String mode = 'countdown_3s',
    bool preferVoice = true,
  }) async {
    geminiLaunches.add((topic: topic, brief: brief));
    return true;
  }

  @override
  Future<WallAppeal?> wallAppeal({required int at, required String evidence, String? task}) async {
    appeals.add((at: at, evidence: evidence, task: task));
    return reply;
  }
}

WallBubble bubble(String kind, {String who = '墙', String text = '', bool? pass, int when = 0}) =>
    WallBubble(kind: kind, at: when, who: who, text: text, pass: pass);

/// 三种气泡各一条，时间都落在 [at]。
WallThread threeBubbles({int appealLeft = 2}) => WallThread(
  bubbles: [
    bubble('submit', who: '他', text: '把文档写完', when: at),
    bubble('verdict', text: '这次算数：实测 +112 行，差 188 行', pass: true, when: at),
    bubble('verdict', text: '不算。要文件，你说的是「我做了」。', pass: false, when: at + 60000),
    bubble('rule', text: '门禁从 120 分钟收紧到 90 分钟', when: at + 120000),
  ],
  appealLeft: appealLeft,
);

Future<FakeWall> pumpWall(WidgetTester tester, FakeWall wall) async {
  await pumpPhone(tester, WallPage(channel: wall));
  return wall;
}

/// 对话框和 SnackBar 都有过场；禁用动画下也要给它几帧。
Future<void> frames(WidgetTester tester, [int n = 8]) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

DshColors colorsOf(WidgetTester tester) => DshColors.of(tester.element(find.byType(WallPage)));

/// 打开申诉框、填好、提交。返回时 SnackBar 已经上屏。
Future<void> appeal(WidgetTester tester, {String task = '', required String evidence}) async {
  await tester.tap(find.text('判定 · 墙').first);
  await frames(tester);
  if (task.isNotEmpty) await tester.enterText(find.byType(TextField).at(0), task);
  await tester.enterText(find.byType(TextField).at(1), evidence);
  await tester.pump();
  await tester.tap(find.widgetWithText(FilledButton, '提交申诉'));
  await frames(tester);
}

void main() {
  testWidgets('W1 空账本：说还没有记录，不说「暂无消息」', (tester) async {
    await pumpWall(tester, FakeWall(thread: const WallThread()));
    expect(find.text('还没有审查记录。交一份完成物，墙才会说话。'), findsOneWidget);
  });

  testWidgets('W2 读不到记录：如实说读不到，不拿一个空列表假装「还没有」', (tester) async {
    await pumpWall(tester, FakeWall(thread: null));
    // 假装的空列表会显示上面那句「还没有记录」，那是在替插件撒谎。
    expect(find.text('还没有审查记录。交一份完成物，墙才会说话。'), findsNothing);
    expect(find.text('读不到墙的记录：no_answer'), findsOneWidget);
  });

  testWidgets('W3 三种气泡各画一条：提交、判定、规则变更', (tester) async {
    await pumpWall(tester, FakeWall(thread: threeBubbles()));
    expect(find.text('提交 · 他'), findsOneWidget);
    expect(find.text('判定 · 墙'), findsNWidgets(2));
    expect(find.text('规则变更 · 墙'), findsOneWidget);
    expect(find.text('把文档写完'), findsOneWidget);
  });

  testWidgets('W4 判定过了给成功色、没过给警告色（不是同一种灰）', (tester) async {
    await pumpWall(tester, FakeWall(thread: threeBubbles()));
    final colors = colorsOf(tester);
    expect(tester.widget<Icon>(find.byIcon(AppIcons.allowed).first).color, colors.success);
    expect(tester.widget<Icon>(find.byIcon(AppIcons.blocked).first).color, colors.warning);
    expect(colors.success, isNot(colors.warning));
  });

  testWidgets('W5 记录里的原话一个字不改地显示', (tester) async {
    await pumpWall(tester, FakeWall(thread: threeBubbles()));
    expect(find.text('这次算数：实测 +112 行，差 188 行'), findsOneWidget);
    expect(find.text('不算。要文件，你说的是「我做了」。'), findsOneWidget);
  });

  testWidgets('W6 时间按 hh:mm 显示', (tester) async {
    await pumpWall(tester, FakeWall(thread: threeBubbles()));
    expect(find.text('09:05'), findsWidgets);
  });

  testWidgets('W7 申诉额度：还剩几次就说几次', (tester) async {
    await pumpWall(tester, FakeWall(thread: threeBubbles(appealLeft: 2)));
    expect(find.text('今天还能申诉 2 次'), findsOneWidget);
  });

  testWidgets('W8 额度用完就说用完，不给出一个点了没用的按钮', (tester) async {
    await pumpWall(tester, FakeWall(thread: threeBubbles(appealLeft: 0)));
    expect(find.text('今天的申诉用完了'), findsOneWidget);
    expect(find.textContaining('今天还能申诉'), findsNothing);
  });

  testWidgets('W9 状态条：锁着 + 锁到几点 + 原因', (tester) async {
    await pumpWall(
      tester,
      FakeWall(thread: const WallThread(), statusBody: {'locked': true, 'openUntil': at, 'why': '今天没交完成物'}),
    );
    expect(find.text('锁着'), findsOneWidget);
    expect(find.text('锁到 09:05'), findsOneWidget);
    expect(find.text('原因：今天没交完成物'), findsOneWidget);
    expect(tester.widget<Icon>(find.byIcon(AppIcons.lock).first).color, colorsOf(tester).warning);
  });

  testWidgets('W10 状态条：开着', (tester) async {
    await pumpWall(tester, FakeWall(thread: const WallThread(), statusBody: {'locked': false}));
    expect(find.text('开着'), findsOneWidget);
    expect(find.textContaining('锁到'), findsNothing);
  });

  testWidgets('W11 读不到状态：说读不到，不猜「大概开着吧」', (tester) async {
    await pumpWall(tester, FakeWall(thread: const WallThread(), statusBody: null));
    expect(find.text('读不到墙的状态'), findsOneWidget);
    expect(find.text('开着'), findsNothing);
  });

  testWidgets('W12 点判定弹申诉框；点提交不弹（提交不可申诉）', (tester) async {
    await pumpWall(tester, FakeWall(thread: threeBubbles()));
    await tester.tap(find.text('提交 · 他'));
    await frames(tester);
    expect(find.byType(AlertDialog), findsNothing);

    await tester.tap(find.text('判定 · 墙').first);
    await frames(tester);
    expect(find.byType(AlertDialog), findsOneWidget);
    // 申诉框里不许出现「跟墙说点什么」这种输入：墙不接话。
    expect(find.byType(TextField), findsNWidgets(2));
  });

  testWidgets('W13 证据空着：提交按钮按不动；填了才按得动', (tester) async {
    await pumpWall(tester, FakeWall(thread: threeBubbles()));
    await tester.tap(find.text('判定 · 墙').first);
    await frames(tester);
    final send = find.widgetWithText(FilledButton, '提交申诉');
    expect(tester.widget<FilledButton>(send).onPressed, isNull);

    await tester.enterText(find.byType(TextField).at(1), '截图');
    await tester.pump();
    expect(tester.widget<FilledButton>(send).onPressed, isNotNull);
  });

  testWidgets('W14 申诉成功：这条判定 + 这份证据送上去，然后把记录重读一遍', (tester) async {
    final wall = FakeWall(
      thread: threeBubbles(),
      reply: const WallAppeal(code: 'file:pass', pass: true, text: '这次算数：实测 +112 行', left: 1),
    );
    await pumpWall(tester, wall);
    expect(wall.threadCalls, 1);

    await appeal(tester, task: '写文档', evidence: '截图');
    expect(wall.appeals.single.at, at);
    expect(wall.appeals.single.evidence, '截图');
    expect(wall.appeals.single.task, '写文档');
    // 判完要重新读一遍，不然界面上还是旧的判定。
    expect(wall.threadCalls, 2);
    expect(find.text('这次算数：实测 +112 行'), findsWidgets);
  });

  testWidgets('W15 申诉只带证据不带事名也行：task 为 null，不是空串', (tester) async {
    final wall = FakeWall(
      thread: threeBubbles(),
      reply: const WallAppeal(code: 'file:pass', pass: true, text: '这次算数', left: 1),
    );
    await pumpWall(tester, wall);
    await appeal(tester, evidence: '截图');
    expect(wall.appeals.single.task, isNull);
  });

  testWidgets('W16 申诉被拒 no-quota：原话是「今天的申诉用完了」，且不重读记录', (tester) async {
    final wall = FakeWall(thread: threeBubbles(), reply: const WallAppeal(error: 'appeal:no-quota'));
    await pumpWall(tester, wall);
    await appeal(tester, evidence: '截图');
    expect(find.text('今天的申诉用完了'), findsWidgets);
    expect(wall.threadCalls, 1);
  });

  testWidgets('W17 申诉被拒 no-new-evidence：说清「上次那份不算新的」', (tester) async {
    final wall = FakeWall(thread: threeBubbles(), reply: const WallAppeal(error: 'appeal:no-new-evidence'));
    await pumpWall(tester, wall);
    await appeal(tester, evidence: '截图');
    expect(find.text('申诉必须附一份新的物理证据。'), findsWidgets);
  });

  testWidgets('W18 连不上：说连不上，不说「已提交」', (tester) async {
    final wall = FakeWall(thread: threeBubbles(), reply: null);
    await pumpWall(tester, wall);
    await appeal(tester, evidence: '截图');
    expect(find.text('连不上大肥鱼，稍后再试'), findsWidgets);
  });

  testWidgets('W19 提交不在这页：明说走 submit_to_wall，不放一个点了没反应的假按钮', (tester) async {
    await pumpWall(tester, FakeWall(thread: threeBubbles()));
    expect(find.text('提交证据'), findsOneWidget);
    expect(find.text('不在这里：对鱼说「交这个」，走 submit_to_wall。'), findsOneWidget);
    // 假按钮的特征是「能点但什么都不做」；这一行的 onTap 必须是 null。
    final row = tester.widget<ListPageRow>(
      find.ancestor(of: find.text('提交证据'), matching: find.byType(ListPageRow)),
    );
    expect(row.onTap, isNull);
  });

  testWidgets('W20 老板全局审视卡片：展示后端 bossBrief 并支持复制/拉起 Gemini', (tester) async {
    String? copied;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied = (call.arguments as Map?)?['text'] as String?;
        }
        return null;
      },
    );
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));

    final wall = FakeWall(
      thread: threeBubbles(),
      statusBody: {'locked': false},
      bossBriefData: {
        'ok': true,
        'headline': '7日兑现 40% · 今日正事 30m / 娱乐 65m',
        'keyGaps': ['今日纯娱乐 65m 已反超工作 30m'],
        'bossPrompt': '【老板全局审视】先核查事实再问 3 个问题。',
      },
    );
    await pumpWall(tester, wall);
    await frames(tester);

    expect(find.byKey(const ValueKey('wall-boss-brief-card')), findsOneWidget);
    expect(find.textContaining('7日兑现 40%'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('wall-boss-copy')));
    await frames(tester);
    expect(copied, contains('老板全局审视'));

    await tester.tap(find.byKey(const ValueKey('wall-boss-launch-gemini')));
    await frames(tester);
    expect(wall.geminiLaunches.length, 1);
    expect(wall.geminiLaunches.single.brief, contains('先核查事实'));
  });

  testWidgets('W21 老板全局审视卡片：bossBrief 接口未挂载时用 _today/_status 合成兜底', (tester) async {
    final wall = FakeWall(
      thread: threeBubbles(),
      statusBody: {'locked': true, 'why': '未交主线任务'},
      bossBriefData: null,
      todayData: {
        'charts': {
          'buckets': {'mainlineMin': 25, 'funMin': 50},
        },
        'passLedger': {'emergency': {'used': 2}},
      },
    );
    await pumpWall(tester, wall);
    await frames(tester);

    expect(find.text('现场合成'), findsOneWidget);
    expect(find.textContaining('今日正事 25m / 娱乐 50m'), findsOneWidget);
  });

  testWidgets('W22 路径 B 主管质询室（未绑定态）：切到质询室展示第 3 会话隔离说明与一键新建/选已有按钮', (tester) async {
    final wall = FakeWall(
      thread: threeBubbles(),
      statusBody: {'locked': false},
      wallBossChatData: {
        'ok': true,
        'bound': false,
        'wallSessionId': null,
        'messages': <Object?>[],
      },
    );
    await pumpWall(tester, wall);
    await frames(tester);

    expect(find.byKey(const ValueKey('wall-mode-switcher')), findsOneWidget);
    await tester.tap(find.text('主管质询室'));
    await frames(tester);

    expect(find.byKey(const ValueKey('wall-boss-room-view')), findsOneWidget);
    expect(find.textContaining('第 3 个独立 AI 会话'), findsOneWidget);
    expect(find.byKey(const ValueKey('wall-boss-create-bind')), findsOneWidget);
    expect(find.byKey(const ValueKey('wall-boss-pick-session')), findsOneWidget);
  });

  testWidgets('W23 路径 B 主管质询室（已绑定态）：展示账本与记录，支持发起全局质询及文本发送', (tester) async {
    final wall = FakeWall(
      thread: threeBubbles(),
      statusBody: {'locked': true, 'why': '逾期未交'},
      wallBossChatData: {
        'ok': true,
        'bound': true,
        'wallSessionId': 'wall-boss-3',
        'agentOnline': true,
        'inFlight': false,
        'bossBrief': {
          'headline': '7日兑现 30% · 今日正事 20m / 娱乐 70m',
          'keyGaps': ['近 7 天计划/封印兑现率仅 30%'],
        },
        'messages': [
          {'id': 'u1', 'role': 'user', 'text': '我准备交材料了', 'at': at},
          {'id': 'a1', 'role': 'assistant', 'text': '把文件增量和截图拿出来，不说空话。', 'at': at + 60000},
        ],
      },
    );
    await pumpWall(tester, wall);
    await frames(tester);

    await tester.tap(find.text('主管质询室'));
    await frames(tester);

    expect(find.textContaining('已绑定独立会话 wall-boss-3'), findsOneWidget);
    expect(find.textContaining('7日兑现 30%'), findsOneWidget);
    expect(find.textContaining('把文件增量和截图拿出来'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('wall-boss-audit-now')));
    await frames(tester);
    expect(wall.bossChatCalls.any((c) => c['action'] == 'audit_now'), isTrue);

    await tester.enterText(find.byKey(const ValueKey('wall-boss-input')), '刚交完 20 道资料分析截图');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('wall-boss-send')));
    await frames(tester);
    expect(
      wall.bossChatCalls.any((c) => c['action'] == 'send' && c['text'] == '刚交完 20 道资料分析截图'),
      isTrue,
    );
  });

  testWidgets('W24 路径 B 主管质询室：展示验收终审与止损硬指令卡片，支持发起冷血终审与钉入硬指令', (tester) async {
    final wall = FakeWall(
      thread: threeBubbles(),
      statusBody: {'locked': true, 'why': '逾期未交'},
      wallBossChatData: {
        'ok': true,
        'bound': true,
        'wallSessionId': 'wall-boss-3',
        'agentOnline': true,
        'inFlight': false,
        'verifyLockCenter': {
          'totalVerify': 1,
          'passedVerify': 0,
          'overdueCount': 1,
          'pendingReviewCount': 1,
          'overdueBlocks': [
            {
              'id': 'blk-w24',
              'title': '资料分析30题',
              'dueHm': '10:00',
              'verifyStatus': 'overdue_locked',
              'submissionText': '已完成30道真题且错因已总结进笔记',
            },
          ],
          'pendingReviewBlocks': const <Object?>[],
        },
        'messages': const <Object?>[],
      },
    );
    await pumpWall(tester, wall);
    await frames(tester);

    await tester.tap(find.text('主管质询室'));
    await frames(tester);

    expect(find.byKey(const ValueKey('wall-boss-verify-card')), findsOneWidget);
    await tester.ensureVisible(find.byKey(const ValueKey('wall-boss-request-judge-blk-w24')));
    await tester.tap(find.byKey(const ValueKey('wall-boss-request-judge-blk-w24')));
    await frames(tester);
    expect(
      wall.bossChatCalls.any((c) => c['action'] == 'request_judge' && c['blockId'] == 'blk-w24'),
      isTrue,
    );

    await tester.ensureVisible(find.byKey(const ValueKey('wall-boss-issue-directive-btn')));
    await tester.tap(find.byKey(const ValueKey('wall-boss-issue-directive-btn')));
    await frames(tester);
    expect(find.byKey(const ValueKey('wall-directive-dialog')), findsOneWidget);

    await tester.enterText(find.byKey(const ValueKey('wall-directive-title')), '主管止损硬指令：补做20题');
    await tester.enterText(find.byKey(const ValueKey('wall-directive-deliverable')), '交20题错因条文与Obsidian摘要');
    await tester.tap(find.byKey(const ValueKey('wall-directive-submit')));
    await frames(tester);
    expect(
      wall.bossChatCalls.any((c) => c['action'] == 'issue_directive' && c['title'] == '主管止损硬指令：补做20题'),
      isTrue,
    );
    expect(wall.tickCalls, greaterThanOrEqualTo(1));
  });
}
