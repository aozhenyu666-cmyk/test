import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/sisters_page.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakeSistersChannel extends ProactiveChannel {
  SistersView? nextView;
  int calls = 0;

  @override
  Future<SistersView?> sisters() async {
    calls++;
    return nextView;
  }

  @override
  Future<DepositView?> deposit({
    String? department,
    String? report,
    String? thought,
    String? text,
    String? taskId,
  }) async =>
      const DepositView();
}

void main() {
  testWidgets('SistersPage retries from offline state and renders Yuwan and Jinglan character cards', (tester) async {
    final ch = _FakeSistersChannel()..nextView = null;
    await pumpPhone(tester, SistersPage(channel: ch));

    expect(find.text('连不上大肥鱼，稍后再试'), findsOneWidget);
    expect(find.text('重新连接并读取'), findsOneWidget);

    ch.nextView = const SistersView(
      cards: [
        SistersCard(
          role: 'companion',
          name: '鱼丸',
          line: '贴身跟着、先接话的妹妹',
          persona: '先看你人怎么样、卡在哪，再推你动一下。',
        ),
        SistersCard(
          role: 'strategist',
          name: '鲸澜',
          line: '站在半步外看全局的姐姐',
          persona: '少说话，只抓结构、逻辑漏洞和长线方向。',
        ),
      ],
      audit: SistersAudit(promised: 3, done: 2, ratePct: 75),
    );

    await tester.tap(find.text('重新连接并读取'));
    await settlePhone(tester);

    expect(ch.calls, 2);
    expect(find.text('陪伴妹妹 · 角色卡'), findsOneWidget);
    expect(find.text('陪伴 / 在场'), findsOneWidget);
    expect(find.text('贴身跟着、先接话的妹妹'), findsOneWidget);

    await tester.tap(find.text('鲸澜'));
    await settlePhone(tester);

    expect(find.text('谋士姐姐 · 角色卡'), findsOneWidget);
    expect(find.text('正事 / 推演'), findsOneWidget);
    expect(find.text('站在半步外看全局的姐姐'), findsOneWidget);
  });

  testWidgets('SistersPage renders Memory X-Ray token savings and Staging temporary DB card', (tester) async {
    const messenger = MethodChannel('dsh/proactive');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      messenger,
      (call) async {
        if (call.method == 'v6Op') {
          return <String, Object?>{
            'v': 1,
            'ok': true,
            'explain': {
              'injectedTokens': 420,
              'budgetTokens': 900,
              'savedPct': 65,
              'totalPoolCount': 48,
              'preciseHits': [
                {'id': 'h1', 'text': '正在做粉笔判断推理削弱题', 'reasons': ['临时申报库', '近4h即时态']},
              ],
              'randomSparks': [
                {'id': 's1', 'text': '昨晚拆穿了自己低电量死皮拖延的老毛病', 'reason': '知识海随机抽卡'},
              ],
            },
            'stagingInbox': [
              {'id': 'stg-1', 'category': '刷题', 'text': '【正在刷题】粉笔削弱论证15题', 'promoted': false},
            ],
          };
        }
        return null;
      },
    );
    addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(messenger, null));

    final ch = _FakeSistersChannel()
      ..nextView = const SistersView(
        cards: [SistersCard(role: 'companion', name: '鱼丸', line: '妹妹', persona: '人设')],
        audit: SistersAudit(promised: 1, done: 1, ratePct: 100),
      );
    await pumpPhone(tester, SistersPage(channel: ch));

    expect(find.byKey(const ValueKey('sisters-memory-xray-card')), findsOneWidget);
    expect(find.textContaining('本轮注入 420 / 900 tokens（比全量省 65%）'), findsOneWidget);
    expect(find.textContaining('精准命中：正在做粉笔判断推理削弱题'), findsOneWidget);
    expect(find.byKey(const ValueKey('sisters-staging-db-card')), findsOneWidget);
    expect(find.textContaining('【正在刷题】粉笔削弱论证15题'), findsOneWidget);
  });
}
