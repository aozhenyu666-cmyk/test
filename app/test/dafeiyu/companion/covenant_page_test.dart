import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/covenant_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

/// 公约页：按章列规矩、提议等批、加一条走 save、批提议走 approve。
class _Cov extends ProactiveChannel {
  final List<Map<String, Object?>> calls = [];
  List<Map<String, dynamic>> rules = [
    {'id': '1.2', 'title': '封印只能加码', 'do': '不接，也不替他求情', 'who': '都', 'hardness': '铁律', 'scenes': ['任何时候']},
    {'id': '2.7', 'title': '深夜还在刷', 'do': '少说话', 'who': '都', 'hardness': '默认', 'scenes': ['深夜']},
  ];
  List<Map<String, dynamic>> proposals = [
    {'id': 'cp-1', 'by': 'strategist', 'chapter': 2, 'why': '连着三晚同一个借口', 'rule': {'title': '他找同一个借口', 'do': '念原话', 'who': '鲸澜', 'hardness': '默认'}},
  ];

  Map<String, dynamic> _view({bool waiting = false}) => {
        'ok': true,
        'rules': rules,
        'pending': const {},
        'proposals': proposals,
        'cooldownDays': 3,
        'inVault': true,
        'names': const {'strategist': '鲸澜', 'companion': '鱼丸'},
        'waiting': waiting,
      };

  @override
  Future<Map<String, dynamic>?> covenantOp(Map<String, Object?> body) async {
    calls.add(body);
    if (body['action'] == 'save') {
      final r = Map<String, dynamic>.from(body['rule'] as Map);
      rules = [...rules, {...r, 'id': '2.8'}];
    }
    if (body['action'] == 'approve') {
      proposals = [];
      rules = [...rules, {'id': '2.9', 'title': '他找同一个借口', 'do': '念原话', 'who': '鲸澜', 'hardness': '默认', 'scenes': ['任何时候']}];
    }
    return _view();
  }
}

void main() {
  testWidgets('按章列出，铁律标红；有提议时顶上等批', (tester) async {
    await pumpPhone(tester, CovenantPage(channel: _Cov()));
    await settlePhone(tester);
    expect(find.text('1.2 封印只能加码'), findsOneWidget);
    expect(find.text('2.7 深夜还在刷'), findsOneWidget);
    expect(find.text('1 条等你批'), findsOneWidget);
    expect(find.textContaining('鲸澜：连着三晚同一个借口'), findsOneWidget);
  });

  testWidgets('批一条提议：发 approve，规矩出现在列表里', (tester) async {
    final ch = _Cov();
    await pumpPhone(tester, CovenantPage(channel: ch));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('covenant-proposal-cp-1')));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('covenant-approve')));
    await settlePhone(tester);
    expect(ch.calls.last, {'action': 'approve', 'id': 'cp-1'});
    expect(find.byKey(const ValueKey('covenant-proposals')), findsNothing);
    expect(find.text('2.9 他找同一个借口'), findsOneWidget);
  });

  testWidgets('在第二章加一条：发 save，带章、硬度、场景', (tester) async {
    final ch = _Cov();
    await pumpPhone(tester, CovenantPage(channel: ch));
    await settlePhone(tester);
    await tester.ensureVisible(find.byKey(const ValueKey('covenant-add-2')));
    await tester.tap(find.byKey(const ValueKey('covenant-add-2')));
    await settlePhone(tester);
    await tester.enterText(find.byKey(const ValueKey('covenant-title')), '他说明天再做');
    await tester.enterText(find.byKey(const ValueKey('covenant-do')), '问今天能做的最小一步');
    await tester.ensureVisible(find.byKey(const ValueKey('covenant-save')));
    await tester.tap(find.byKey(const ValueKey('covenant-save')));
    await settlePhone(tester);
    final save = ch.calls.where((c) => c['action'] == 'save').single;
    final rule = save['rule'] as Map;
    expect(rule['chapter'], 2);
    expect(rule['title'], '他说明天再做');
    expect(rule['hardness'], '默认');
    expect(rule['scenes'], ['任何时候']);
    expect(rule.containsKey('id'), isFalse);
  });
}
