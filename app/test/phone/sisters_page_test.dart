import 'package:dsh_app/proactive/proactive_state.dart';
import 'package:dsh_app/ui/phone/sisters_page.dart';
import 'package:flutter_test/flutter_test.dart';

import 'harness.dart';

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
}
