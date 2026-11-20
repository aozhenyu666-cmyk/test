import 'package:dsh_app/dafeiyu/channels/loop_channel.dart';
import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bouts_workspace_page.dart';
import 'package:dsh_app/dafeiyu/ui/horizon_dossier_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

/// 战役档案：节点、干过哪几局、断点、从节点开一局；局详情显示节点上下文。
class _Fake extends ProactiveChannel {

  final List<Map<String, Object?>> ops = [];
  final List<Map<String, Object?>> bouts = [];
  List<Map<String, dynamic>> nodes = [
    {
      'id': 'N1',
      'title': '资料分析 200 题',
      'targetDate': '2026-11-28',
      'deliverable': '正确率 ≥85%',
      'budgetBouts': 6,
      'budgetMinutes': 270,
      'spentMinutes': 230,
      'status': 'active',
      'lastBreakpoint': '做到第 85 题',
      'executionLog': [
        {'workId': 'w1', 'day': '2026-11-19', 'minutes': 45, 'outcome': 'passed', 'summary': '刷 45 题'},
      ],
    },
  ];

  Map<String, dynamic> _resp() => {
        'ok': true,
        'horizon': {'id': 'L1', 'title': '省考上岸', 'due': '2026-12-20', 'nodes': nodes},
        'summary': {
          'nodes': nodes.length,
          'passed': 0,
          'spentMinutes': nodes.isEmpty ? 0 : 230,
          'budgetMinutes': nodes.isEmpty ? 0 : 270,
          'nextNodeId': nodes.isEmpty ? '' : 'N1',
          'alerts': [
            if (nodes.isNotEmpty) {'nodeId': 'N1', 'alert': {'kind': 'overBudget', 'text': '已经花了 230/270 分钟，还没达成'}},
          ],
        },
      };

  @override
  Future<Map<String, dynamic>?> horizonsOp([Map<String, Object?>? body]) async {
    ops.add(body ?? {});
    if (body?['action'] == 'node_start') {
      return {
        ..._resp(),
        'item': {'id': 'nd-N1-x', 'title': '资料分析 200 题', 'allow': ['md.obsidian'], 'minutes': 45},
        'droppedApps': ['tv.danmaku.bili'],
      };
    }
    if (body?['action'] == 'node_upsert') {
      nodes = [...nodes, {'id': 'N2', 'title': '${(body!['node'] as Map)['title']}', 'status': 'planned', 'budgetBouts': 3, 'budgetMinutes': 135}];
    }
    return _resp();
  }

  @override
  Future<Map<String, dynamic>?> boutsCommand(Map<String, Object?> body) async {
    bouts.add(body);
    return {'ok': true};
  }
}

void main() {
  testWidgets('档案：节点卡片显示预算、断点、干过几局、调盘预警', (tester) async {
    await pumpPhone(tester, HorizonDossierPage(horizonId: 'L1', channel: _Fake()));
    await settlePhone(tester);
    expect(find.text('省考上岸'), findsWidgets);
    expect(find.text('资料分析 200 题'), findsOneWidget);
    expect(find.text('断点：做到第 85 题'), findsOneWidget);
    expect(find.text('干过 1 局'), findsOneWidget);
    expect(find.text('已经花了 230/270 分钟，还没达成'), findsOneWidget);
    expect(find.text('1 个要调盘'), findsOneWidget);
  });

  testWidgets('还没有节点的目标：不崩，提示先拆节点', (tester) async {
    await pumpPhone(tester, HorizonDossierPage(horizonId: 'L1', channel: _Fake()..nodes = []));
    await settlePhone(tester);
    expect(find.textContaining('还没有节点'), findsOneWidget);
    expect(find.byKey(const ValueKey('dossier-add-node')), findsOneWidget);
  });

  testWidgets('从节点开一局：先 node_start 编译，再用那一条开局', (tester) async {
    final ch = _Fake();
    await pumpPhone(tester, HorizonDossierPage(horizonId: 'L1', channel: ch));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('node-start-N1')));
    await settlePhone(tester);
    expect(ch.ops.last, {'action': 'node_start', 'nodeId': 'N1', 'horizonId': 'L1'});
    expect(ch.bouts.single['action'], 'open');
    expect(ch.bouts.single['id'], 'nd-N1-x');
    expect(ch.bouts.single['parentHorizonId'], 'L1');
  });

  testWidgets('加一个预留节点', (tester) async {
    final ch = _Fake();
    await pumpPhone(tester, HorizonDossierPage(horizonId: 'L1', channel: ch));
    await settlePhone(tester);
    await tester.scrollUntilVisible(find.byKey(const ValueKey('dossier-add-node')), 200, scrollable: find.byType(Scrollable).first);
    await tester.tap(find.byKey(const ValueKey('dossier-add-node')));
    await settlePhone(tester);
    await tester.enterText(find.byKey(const ValueKey('node-title-input')), '言语 100 题');
    await tester.tap(find.byKey(const ValueKey('node-save')));
    await settlePhone(tester);
    final up = ch.ops.firstWhere((o) => o['action'] == 'node_upsert');
    expect((up['node'] as Map)['title'], '言语 100 题');
    expect((up['node'] as Map)['budgetMinutes'], 135, reason: '默认 3 局 × 45 分');
  });

  testWidgets('局详情：从节点派出来的局显示节点上下文', (tester) async {
    final ch = _RunningFromNode();
    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: LoopChannel()));
    await settlePhone(tester);
    await tester.tap(find.text('速算 50 题'));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('bout-detail-node')), findsOneWidget);
    expect(find.text('基期差公式卡壳'), findsOneWidget);
    expect(find.text('错题 5 道入题库'), findsWidgets);
  });
}

class _RunningFromNode extends ProactiveChannel {
  @override
  Future<Map<String, dynamic>?> boutsStatus() async => {
        'running': {
          'title': '速算 50 题',
          'strict': true,
          'until': DateTime.now().millisecondsSinceEpoch + 20 * 60000,
          'startedAt': DateTime.now().millisecondsSinceEpoch - 25 * 60000,
          'allow': ['md.obsidian'],
          'deliverable': '错题 5 道入题库',
          'nodeContext': {'horizonTitle': '资料分析战役', 'nodeTitle': '速算 50 题', 'deliverable': '错题 5 道入题库', 'lastBreakpoint': '基期差公式卡壳'},
          'nodeLink': {'horizonId': 'M1', 'nodeId': 'N1'},
        },
        'queue': [],
      };

  @override
  Future<Map<String, dynamic>?> todayStatus() async => {'tasks': []};

  @override
  Future<List<Map<String, dynamic>>> clockList() async => const [];
}
