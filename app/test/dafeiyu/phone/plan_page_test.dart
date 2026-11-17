import 'package:dsh_app/dafeiyu/channels/loop_channel.dart';
import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/plan_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakePlanChannel extends ProactiveChannel {
  List<Map<String, Object?>>? submitted;

  @override
  Future<Map<String, Object?>?> planDay() async => {
        'min': 540,
        'protocols': [
          {'id': 'execute', 'name': '深度执行', 'minutes': 45},
        ],
        'plan': {
          'items': [
            {
              'protocolId': 'execute',
              'title': '写周报',
              'minute': 550,
              'budget': 45,
              'need': ['md.obsidian'],
              'allow': ['md.obsidian'],
              'packet': {
                'goal': '写完第一部分',
                'first': '打开 Obsidian 写周报提纲',
                'steps': <String>[],
                'deliverable': '周报.md 新增 500 字',
                'accept': {'kind': '', 'what': ''},
              },
            },
          ],
        },
        'recent': <String>[],
        'numbers': const <String, Object?>{},
        'acceptLeftMs': 0,
        'sentence': '',
      };

  @override
  Future<Map<String, Object?>?> planDaySet(List<Map<String, Object?>> items) async {
    submitted = items;
    return {'ok': true, 'text': '计划定了：${items.length} 局。'};
  }
}

class _FakeLoopChannel extends LoopChannel {
  @override
  Future<List<LoopApp>> apps() async => [
        for (var i = 0; i < 60; i++) LoopApp('com.noise.app$i', '干扰工具 $i'),
        const LoopApp('md.obsidian', 'Obsidian'),
        const LoopApp('com.tencent.mm', '微信'),
      ];
}

void main() {
  testWidgets('PlanPage 选中验收方式后自动弹出具体内容编辑且可独立点开并提交', (tester) async {
    final ch = _FakePlanChannel();
    await pumpPhone(tester, PlanPage(channel: ch, loop: _FakeLoopChannel()));
    await settlePhone(tester);

    expect(find.text('验收方式'), findsOneWidget);
    expect(find.text('怎么验（具体内容）'), findsOneWidget);

    // 1. 点击「验收方式」选一类（证据），应自动接着弹出「怎么验」输入 sheet
    await tester.tap(find.text('验收方式'));
    await settlePhone(tester);
    await tester.tap(find.text(PlanRow.acceptNames['evidence']!));
    await settlePhone(tester);

    expect(find.text('怎么验（说清看到什么算过）'), findsOneWidget);
    await tester.enterText(find.byType(TextField).last, 'Obsidian 字数增加 500');
    await tester.tap(find.text('完成'));
    await settlePhone(tester);

    expect(find.text('Obsidian 字数增加 500'), findsOneWidget);

    // 2. 独立点击「怎么验（具体内容）」也可随时修改
    await tester.tap(find.text('怎么验（具体内容）'));
    await settlePhone(tester);
    await tester.enterText(find.byType(TextField).last, '保存周报.md 并提交截图');
    await tester.tap(find.text('完成'));
    await settlePhone(tester);

    expect(find.text('保存周报.md 并提交截图'), findsOneWidget);

    // 3. 提交计划不再陷入验收死循环
    await tester.tap(find.text('就按这一份来'));
    await settlePhone(tester);

    expect(ch.submitted, isNotNull);
    final pkt = ch.submitted!.first['packet'] as Map<String, Object?>;
    expect(pkt['accept'], {'kind': 'evidence', 'what': '保存周报.md 并提交截图'});
  });

  testWidgets('PlanPage 固定高度白名单多选弹窗支持搜索且确认按钮不被裁切失效', (tester) async {
    final ch = _FakePlanChannel();
    await pumpPhone(tester, PlanPage(channel: ch, loop: _FakeLoopChannel()));
    await settlePhone(tester);

    await tester.tap(find.text('允许应用'));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('plan-app-confirm')), findsOneWidget);
    await tester.enterText(find.byType(TextField).last, '微信');
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('plan-app-com.tencent.mm')));
    await settlePhone(tester);

    await tester.tap(find.byKey(const ValueKey('plan-app-confirm')));
    await settlePhone(tester);

    expect(find.text('Obsidian、微信'), findsOneWidget);

    await tester.tap(find.text('验收方式'));
    await settlePhone(tester);
    await tester.tap(find.text(PlanRow.acceptNames['evidence']!));
    await settlePhone(tester);
    await tester.enterText(find.byType(TextField).last, 'Obsidian 已更新并保存');
    await tester.tap(find.text('完成'));
    await settlePhone(tester);

    await tester.tap(find.text('就按这一份来'));
    await settlePhone(tester);
    expect(ch.submitted, isNotNull);
    final allow = (ch.submitted!.first['allow'] as List).cast<String>();
    expect(allow, containsAll(['md.obsidian', 'com.tencent.mm']));
  });
}
