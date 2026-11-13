// C1: 工作记忆挂载卡。这一页只做一件事：把卡上的东西摆给他看，让他随手补一条。
//
// 最容易出的两个错都在下面钉着：一是把「她总结的一句话」混进卡里（卡上只能是他自己的原话），
// 二是关掉 / 停着之后把人卡在死路上（该给的「开新卡」「接着推」一个都不能少）。

import 'package:dsh_app/proactive/proactive_state.dart';
import 'package:dsh_app/ui/common/list_page_row.dart';
import 'package:dsh_app/ui/phone/mount_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'harness.dart';

/// 顶替 `dsh/proactive`：不起引擎，只回答这一页会问的那一件事，并把每次调用记下来。
class FakeMount extends ProactiveChannel {
  FakeMount({this.view, this.reply});

  /// 首屏那一次 `mount()` 的结果。
  MountView? view;

  /// 每一次写操作的结果（不给就沿用 [view]）。
  MountView? reply;

  final calls = <Map<String, Object?>>[];

  @override
  Future<MountView?> mount({
    String? action,
    String? goal,
    String? topicId,
    String? text,
    String? src,
    String? where,
    String? id,
    List<int>? from,
  }) async {
    calls.add({
      'action': action,
      'goal': goal,
      'text': text,
      'src': src,
      'where': where,
      'id': id,
      'from': from,
    });
    // 首屏那次读（action 为空）看到的还是卡本身：[reply] 只顶替写操作的结果。
    return action == null ? view : (reply ?? view);
  }

  Map<String, Object?> get last => calls.last;
}

Future<FakeMount> pumpMount(WidgetTester tester, FakeMount fake) async {
  await pumpPhone(tester, MountPage(channel: fake));
  return fake;
}

ListPageRow row(WidgetTester tester, String title) =>
    tester.widget<ListPageRow>(find.widgetWithText(ListPageRow, title));

MountEntry given(String text, {String src = ''}) => MountEntry(text: text, src: src);

MountView openCard({String goal = '把第七轮做完', List<MountEntry> givens = const [], List<MountEntry> steps = const []}) =>
    MountView(
      card: MountCard(status: 'open', goal: goal, givens: givens, steps: steps, id: 'c1'),
    );

void main() {
  // 纯解析：卡号是「接着推」唯一的把手，漏读就是一个点了没反应的按钮。
  test('M0 fromWire 把卡号读进来，别的卡也带着各自的号', () {
    final card = MountCard.fromWire({
      'status': 'parked',
      'goal': '把第七轮做完',
      'id': 'c9',
      'cards': [
        {'id': 'c9', 'status': 'parked', 'goal': '把第七轮做完', 'parkedWhy': '停在第二步'},
      ],
    });
    expect(card.id, 'c9');
    expect(card.cards.single['id'], 'c9');
    expect(card.status, 'parked');
  });

  testWidgets('M1 一张卡都没有：说还没有卡，给「开一张新卡」，不给停放和关掉', (tester) async {
    await pumpMount(tester, FakeMount(view: const MountView()));
    expect(find.text('还没有卡'), findsOneWidget);
    // 空卡的目标栏不该画出来：没有目标可看。
    expect(find.text('开一张新卡'), findsOneWidget);
    expect(find.text('先停起来'), findsNothing);
    expect(find.text('关掉这张卡'), findsNothing);
    expect(row(tester, '开一张新卡').onTap, isNotNull);
  });

  testWidgets('M2 读不到卡：如实说读不到，不拿一张空卡假装「还没有」', (tester) async {
    await pumpMount(tester, FakeMount(view: null));
    expect(find.text('读不到挂载卡，稍后再试'), findsOneWidget);
    // 假装成空卡就会显示「还没有卡」，那是在替插件撒谎。
    expect(find.text('还没有卡'), findsNothing);
  });

  testWidgets('M3 开着的卡：目标 / 已知 / 结论三栏都在，出处和来源都标出来', (tester) async {
    await pumpMount(
      tester,
      FakeMount(
        view: openCard(
          givens: [given('插件单测全过', src: '128'), given('手写的那条')],
          steps: [MountEntry(text: 'C1 可以收尾', from: [0])],
        ),
      ),
    );
    expect(find.text('开着的卡'), findsOneWidget);
    expect(find.text('把第七轮做完'), findsOneWidget);
    expect(find.text('插件单测全过'), findsOneWidget);
    expect(find.text('出处：账本第 128 行'), findsOneWidget);
    expect(find.text('C1 可以收尾'), findsOneWidget);
    expect(find.text('由第 1 条已知推出'), findsOneWidget);
    // 顶部那行计数：已知 2/5、推到 1/5。
    expect(find.text('2/5 · 1/5'), findsOneWidget);
  });

  testWidgets('M4 面板上手写的已知不冒充「有出处」，如实说这是他手写的', (tester) async {
    await pumpMount(tester, FakeMount(view: openCard(givens: [given('手写的那条')])));
    expect(find.text('他手写的'), findsOneWidget);
    expect(find.textContaining('出处：账本第'), findsNothing);
  });

  testWidgets('M5 一条已知都没有时不给「补一条结论」，并说清为什么', (tester) async {
    await pumpMount(tester, FakeMount(view: openCard()));
    expect(find.text('补一条结论'), findsOneWidget);
    expect(row(tester, '补一条结论').onTap, isNull);
    expect(find.text('卡上还没有已知条件，结论无从推起。'), findsOneWidget);
  });

  testWidgets('M6 已知满了 5 条：不给再补，并说清是满了', (tester) async {
    await pumpMount(
      tester,
      FakeMount(
        view: openCard(givens: [for (var i = 0; i < 5; i++) given('已知 $i')]),
      ),
    );
    expect(row(tester, '补一条已知条件').onTap, isNull);
    expect(find.text('已知条件满了（最多 5 条），先删一条再来。'), findsOneWidget);
    // 有已知就能补结论：这两条不能一起堵死。
    expect(row(tester, '补一条结论').onTap, isNotNull);
  });

  testWidgets('M7 她提议的那一条：只给「点头」和「不要」，不点头不落卡', (tester) async {
    final fake = FakeMount(
      view: MountView(card: const MountCard(status: 'open', goal: '把第七轮做完', id: 'c1'), pendingText: '他说「别再问了」', pendingSlot: 'given', pendingSrc: '204'),
    );
    await pumpMount(tester, fake);
    expect(find.text('她提议挂一条'), findsOneWidget);
    expect(find.text('他说「别再问了」'), findsOneWidget);
    expect(find.text('已知条件'), findsOneWidget);
    expect(find.text('点头，挂上去'), findsOneWidget);
    expect(find.text('不要这条'), findsOneWidget);

    await tester.tap(find.text('点头，挂上去'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    // 写成功之后这一页会自己重读一次（那一条的 action 是 null），所以查这一次而不是最后一条。
    expect(fake.calls.map((c) => c['action']), contains('accept'));
  });

  testWidgets('M8 停着的卡：说停在哪，并给「接着推这张」（带着卡号）', (tester) async {
    final fake = FakeMount(
      view: MountView(
        card: const MountCard(status: 'parked', goal: '把第七轮做完', parkedWhy: '卡在注入预算那一步', id: 'c9'),
      ),
    );
    await pumpMount(tester, fake);
    expect(find.text('停着的卡'), findsOneWidget);
    expect(find.text('停在哪：卡在注入预算那一步'), findsOneWidget);
    expect(find.text('接着推这张'), findsOneWidget);
    expect(find.text('先停起来'), findsNothing);

    await tester.tap(find.text('接着推这张'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    // 恢复要按卡号指认是哪一张，漏了 id 就等于点了个空。同样是「这一次调用」，不是最后一条。
    final resume = fake.calls.lastWhere((c) => c['action'] == 'resume');
    expect(resume['id'], 'c9');
  });

  testWidgets('M9 关掉的卡只读：还能开新卡，但不给「接着推」也不给再关一次', (tester) async {
    await pumpMount(
      tester,
      FakeMount(view: MountView(card: const MountCard(status: 'closed', goal: '上一件事', id: 'c9'))),
    );
    expect(find.text('已关掉的卡'), findsOneWidget);
    expect(find.text('开一张新卡'), findsOneWidget);
    expect(find.text('接着推这张'), findsNothing);
    expect(find.text('关掉这张卡'), findsNothing);
    expect(find.text('先停起来'), findsNothing);
  });

  testWidgets('M10 别的卡：两张以上才列出来，已经有开着的卡时不给点（插件也会拒）', (tester) async {
    await pumpMount(
      tester,
      FakeMount(
        view: MountView(
          card: const MountCard(
            status: 'open',
            goal: '现在这件事',
            id: 'a',
            cards: [
              {'id': 'a', 'status': 'open', 'goal': '现在这件事', 'parkedWhy': ''},
              {'id': 'b', 'status': 'parked', 'goal': '上个月那件事', 'parkedWhy': '停在第二步'},
            ],
          ),
        ),
      ),
    );
    expect(find.text('别的卡'), findsOneWidget);
    expect(find.text('上个月那件事'), findsOneWidget);
    expect(find.text('停在第二步'), findsOneWidget);
    // 有开着的卡时点恢复必被拒，所以这里先不给点。
    expect(row(tester, '上个月那件事').onTap, isNull);
  });

  testWidgets('M11 浮层小卡：目标 + 计数两行；没卡时不占位，断链时亮警告色并问一句', (tester) async {
    await pumpPhone(
      tester,
      const Scaffold(
        body: MountMiniCard(
          card: MountCard(
            status: 'open',
            goal: '把第七轮做完',
            givens: [MountEntry(text: 'a'), MountEntry(text: 'b')],
            steps: [MountEntry(text: 'c', from: [0])],
          ),
        ),
      ),
    );
    expect(find.text('把第七轮做完'), findsOneWidget);
    expect(find.text('已知 2/5 · 推到 1/5'), findsOneWidget);

    // 没卡时整块不画：浮层里不能留个空壳子占着位置。
    await pumpPhone(
      tester,
      const Scaffold(body: MountMiniCard(card: MountCard())),
    );
    expect(find.textContaining('已知'), findsNothing);

    // 断链：把「对不上了」摆出来，并问他继续还是停放。
    await pumpPhone(
      tester,
      const Scaffold(body: MountMiniCard(card: MountCard(status: 'open', goal: '把第七轮做完'), ask: true)),
    );
    expect(find.text('他说的和卡上对不上：继续这张，还是先停放？'), findsOneWidget);
  });

  testWidgets('M12 插件回的错误码翻成人话，不自己编一句好听的', (tester) async {
    final fake = FakeMount(
      view: openCard(),
      reply: const MountView(error: 'card-open', message: ''),
    );
    await pumpMount(tester, fake);

    await tester.tap(find.text('关掉这张卡'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('已经有一张开着的卡，先停放或关掉它。'), findsOneWidget);
  });
}
