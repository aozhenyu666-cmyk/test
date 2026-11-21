import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/plan_hub_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakePlanChannel extends ProactiveChannel {
  _FakePlanChannel({this.nodNeeded = true});

  bool nodNeeded;
  final List<Map<String, Object?>> sealsCalls = [];
  final List<Map<String, Object?>> geminiLaunches = [];
  final List<Map<String, dynamic>> inboxEntries = [
    {'day': '2026-11-21', 'time': '09:15', 'tag': '灵感', 'text': '行测复盘按错误原因归类'},
  ];

  @override
  Future<Map<String, dynamic>?> todayStatus() async => const {
        'tasks': <Object>[],
        'horizons': {'medium': <Object>[], 'long': <Object>[]},
      };

  @override
  Future<Map<String, dynamic>?> sealsOp(Map<String, Object?> body) async {
    sealsCalls.add(body);
    final action = body['action'];
    if (action == 'list') {
      return {'ok': true, 'today': '2026-11-21', 'tomorrow': '2026-11-22', 'items': const <Object>[]};
    }
    if (action == 'nod_card_get') {
      if (!nodNeeded) return {'ok': true, 'needed': false, 'items': const <Object>[]};
      return {
        'ok': true,
        'needed': true,
        'items': [
          {
            'id': 'tpl-1',
            'title': '资料分析专项',
            'kind': 'quiz',
            'stretch': 40,
            'floor': 20,
            'startMinute': 540,
            'dueMinute': 660,
            'estMinutes': 90,
          },
        ],
      };
    }
    if (action == 'nod_card_confirm') {
      nodNeeded = false;
      return {'ok': true, 'text': '今日菜单已点头锁定'};
    }
    if (action == 'inbox_list') {
      return {'ok': true, 'entries': List<Map<String, dynamic>>.from(inboxEntries)};
    }
    if (action == 'inbox_add') {
      inboxEntries.insert(0, {
        'day': '2026-11-21',
        'time': '10:05',
        'tag': body['tag'],
        'text': body['text'],
      });
      return {'ok': true, 'entries': List<Map<String, dynamic>>.from(inboxEntries)};
    }
    if (action == 'inbox_bundle') {
      return {
        'ok': true,
        'markdown': '# 谋划思考包\n- [卡壳] ${inboxEntries.first['text']}',
        'inboxCount': inboxEntries.length,
        'stuckCount': 1,
      };
    }
    return {'ok': true};
  }

  @override
  Future<bool> launchGeminiProactive({
    String topic = '',
    String brief = '',
    String mode = 'countdown_3s',
    bool preferVoice = true,
  }) async {
    geminiLaunches.add({'topic': topic, 'brief': brief, 'mode': mode, 'preferVoice': preferVoice});
    return true;
  }
}

void main() {
  testWidgets('4.9 早上点头卡：展示建议菜单、+/- 与数字微调并钳制 floor<=stretch、点头锁定', (tester) async {
    final ch = _FakePlanChannel(nodNeeded: true);
    await pumpPhone(tester, PlanHubPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('nod-menu-card')), findsOneWidget);
    expect(find.text('今日菜单点头卡'), findsOneWidget);
    expect(find.text('资料分析专项'), findsOneWidget);
    expect(find.text('DSH 题库 · 09:00–11:00'), findsOneWidget);
    expect(find.text('40题'), findsOneWidget);
    expect(find.text('20题'), findsOneWidget);

    // 点 + 微调拉伸、点 - 微调保底
    await tester.tap(find.byKey(const ValueKey('nod-stretch-inc-0')));
    await settlePhone(tester);
    expect(find.text('41题'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('nod-floor-dec-0')));
    await settlePhone(tester);
    expect(find.text('19题'), findsOneWidget);

    // 直接数字输入把拉伸调到 15，保底自动钳制到 15
    await tester.tap(find.byKey(const ValueKey('nod-stretch-val-0')));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('nod-number-dialog')), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('nod-number-input')), '15');
    await tester.tap(find.byKey(const ValueKey('nod-number-save')));
    await settlePhone(tester);
    expect(find.text('15题'), findsNWidgets(2));

    // 点头锁定
    await tester.tap(find.byKey(const ValueKey('nod-card-confirm')));
    await settlePhone(tester);

    final confirmCall = ch.sealsCalls.lastWhere((c) => c['action'] == 'nod_card_confirm');
    final items = confirmCall['items'] as List;
    final edited = items.single as Map;
    expect(edited['stretch'], 15);
    expect(edited['floor'], 15);
    expect(find.byKey(const ValueKey('nod-menu-card')), findsNothing);
  });

  testWidgets('4.9 早上点头卡：今天不需要时不展示', (tester) async {
    final ch = _FakePlanChannel(nodNeeded: false);
    await pumpPhone(tester, PlanHubPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('nod-menu-card')), findsNothing);
  });

  testWidgets('4.10 四部门速记收件箱：选标签提交 + 展示近期条目 + 打包交 Gemini 复制剪贴板', (tester) async {
    final ch = _FakePlanChannel(nodNeeded: false);
    String? copiedText;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') {
        copiedText = (call.arguments as Map)['text'] as String?;
      }
      return null;
    });

    await pumpPhone(tester, PlanHubPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('inbox-capture-card')), findsOneWidget);
    expect(find.text('行测复盘按错误原因归类'), findsOneWidget);

    // 选「卡壳」标签并提交
    await tester.tap(find.byKey(const ValueKey('inbox-tag-卡壳')));
    await settlePhone(tester);
    await tester.enterText(find.byKey(const ValueKey('inbox-input')), '基期倍数与比重变化老混淆');
    await tester.tap(find.byKey(const ValueKey('inbox-submit')));
    await settlePhone(tester);

    expect(
      ch.sealsCalls.any((c) => c['action'] == 'inbox_add' && c['tag'] == '卡壳' && c['text'] == '基期倍数与比重变化老混淆'),
      isTrue,
    );
    expect(find.text('基期倍数与比重变化老混淆'), findsOneWidget);

    // 打包交 Gemini
    await tester.ensureVisible(find.byKey(const ValueKey('inbox-bundle-btn')));
    await tester.tap(find.byKey(const ValueKey('inbox-bundle-btn')));
    await settlePhone(tester);

    expect(ch.sealsCalls.any((c) => c['action'] == 'inbox_bundle'), isTrue);
    expect(copiedText, contains('谋划思考包'));
    expect(find.byKey(const ValueKey('inbox-bundle-dialog')), findsOneWidget);
    expect(find.textContaining('2 条速记 · 1 条卡壳'), findsOneWidget);
    expect(find.textContaining('dafeiyu_read_inbox'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('inbox-bundle-open-gemini')));
    await settlePhone(tester);
    expect(ch.geminiLaunches.length, 1);
    expect(ch.geminiLaunches.single['brief'], contains('谋划思考包'));
  });
}
