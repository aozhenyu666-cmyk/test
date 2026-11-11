// A3: 墙的对话界面。它不是聊天窗——只读已落账的记录，只接受「一条判定 + 一份新证据」的申诉。
//
// 这一页最容易出的错是它偷偷变成第三个人格：多一个输入框、多一句安慰、多一个「正在输入」。
// 下面每条用例都在钉住「它只做两件事」，以及「读不到就说读不到」。

import 'package:dsh_app/proactive/proactive_state.dart';
import 'package:dsh_app/ui/common/list_page_row.dart';
import 'package:dsh_app/ui/phone/wall_page.dart';
import 'package:dsh_app/ui/theme/app_icons.dart';
import 'package:dsh_app/ui/theme/theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'harness.dart';

/// 2026-11-10 09:05 本地时间：时间戳用本地构造，页面按本地还原，跑在哪个时区都对得上。
final int at = DateTime(2026, 11, 10, 9, 5).millisecondsSinceEpoch;

/// 顶替 `dsh/proactive`：不起引擎，只回答这一页会问的三件事。
class FakeWall extends ProactiveChannel {
  FakeWall({this.thread, this.status, this.reply});

  WallThread? thread;
  Map<String, Object?>? status;
  WallAppeal? reply;

  int threadCalls = 0;
  final appeals = <({int at, String evidence, String? task})>[];

  @override
  Future<WallThread?> wallThread() async {
    threadCalls++;
    return thread;
  }

  @override
  Future<Map<String, Object?>?> wallStatus() async => status;

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
      FakeWall(thread: const WallThread(), status: {'locked': true, 'openUntil': at, 'why': '今天没交完成物'}),
    );
    expect(find.text('锁着'), findsOneWidget);
    expect(find.text('锁到 09:05'), findsOneWidget);
    expect(find.text('原因：今天没交完成物'), findsOneWidget);
    expect(tester.widget<Icon>(find.byIcon(AppIcons.lock).first).color, colorsOf(tester).warning);
  });

  testWidgets('W10 状态条：开着', (tester) async {
    await pumpWall(tester, FakeWall(thread: const WallThread(), status: {'locked': false}));
    expect(find.text('开着'), findsOneWidget);
    expect(find.textContaining('锁到'), findsNothing);
  });

  testWidgets('W11 读不到状态：说读不到，不猜「大概开着吧」', (tester) async {
    await pumpWall(tester, FakeWall(thread: const WallThread(), status: null));
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
}
