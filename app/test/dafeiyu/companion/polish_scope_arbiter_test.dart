import 'package:dsh_app/dafeiyu/channels/loop_channel.dart';
import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/bouts_workspace_page.dart';
import 'package:dsh_app/dafeiyu/ui/plan_hub_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

class _FakePolishLoopChannel extends LoopChannel {
  @override
  Future<List<LoopApp>> apps() async => const [
        LoopApp('md.obsidian', 'Obsidian'),
        LoopApp('com.fenbi.android.servant', '粉笔'),
      ];
}

class _FakePolishProactiveChannel extends ProactiveChannel {
  _FakePolishProactiveChannel({
    this.runningBout,
    this.sealsResponse,
    this.todayExtra = const {},
    this.arbiterValue,
  });

  Map<String, dynamic>? runningBout;
  Map<String, dynamic>? sealsResponse;
  Map<String, dynamic> todayExtra;
  bool? arbiterValue;

  @override
  Future<bool?> arbiterEnforce() async => arbiterValue;

  @override
  Future<Map<String, dynamic>?> sealsOp(Map<String, Object?> body) async {
    final action = body['action'];
    if (action == 'list') return sealsResponse;
    if (action == 'nod_card_get') return const {'ok': true, 'needed': false, 'items': <Object>[]};
    if (action == 'inbox_list') return const {'ok': true, 'entries': <Object>[]};
    return const {'ok': true};
  }

  @override
  Future<Map<String, dynamic>?> todayStatus() async => {
        'running': runningBout,
        'tasks': const <Object>[],
        'horizons': const {'medium': <Object>[], 'long': <Object>[]},
        ...todayExtra,
      };

  @override
  Future<Map<String, dynamic>?> boutsStatus() async => {
        'running': runningBout,
        'queue': const <Object>[],
      };

  @override
  Future<List<Map<String, dynamic>>> clockList() async => const [];

  @override
  Future<Map<String, dynamic>?> analyticsOp([Map<String, Object?>? body]) async => null;

  @override
  Future<Map<String, dynamic>?> cardOp(Map<String, Object?> body) async => null;

  @override
  Future<Map<String, dynamic>?> storylineOp([Map<String, Object?>? body]) async => null;

  @override
  Future<bool?> lockShadow() async => null;

  @override
  Future<IntensityView?> intensityStatus() async => null;

  @override
  Future<Map<String, dynamic>?> phoneStorageStatus() async => const {'granted': true};

  @override
  Future<Map<String, Object?>?> wallStatus() async => const {'locked': false};
}

void main() {
  testWidgets('BoutsWorkspacePage shows scopeLabel chip and direct sealTitle in hero card and detail page',
      (tester) async {
    final ch = _FakePolishProactiveChannel(
      runningBout: {
        'title': '资料分析速刷 45m',
        'strict': true,
        'until': DateTime.now().millisecondsSinceEpoch + 900000,
        'allow': const ['com.fenbi.android.servant', 'md.obsidian'],
        'scopeLabel': '刷题/行测',
        'sealId': 'seal-1',
        'sealTitle': '资料分析 20 题',
      },
      sealsResponse: const {
        'ok': true,
        'today': '2026-11-22',
        'tomorrow': '2026-11-23',
        'items': <Object>[],
      },
    );

    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: _FakePolishLoopChannel()));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('running-bout-scope-chip')), findsOneWidget);
    expect(find.text('自动范围：刷题/行测（免手选）'), findsOneWidget);
    expect(find.byKey(const ValueKey('running-bout-seal-label')), findsOneWidget);
    expect(find.text('本局关联验收点：资料分析 20 题'), findsOneWidget);

    await tester.tap(find.text('资料分析速刷 45m'));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('bout-detail-page')), findsOneWidget);
    expect(find.byKey(const ValueKey('bout-detail-scope-chip')), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('bout-detail-page')),
        matching: find.text('自动范围：刷题/行测（免手选）'),
      ),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('bout-detail-seal-row')), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('bout-detail-page')),
        matching: find.text('本局关联验收点：资料分析 20 题'),
      ),
      findsOneWidget,
    );
  });

  testWidgets('BoutsWorkspacePage resolves sealTitle from seals items by sealId when sealTitle is omitted',
      (tester) async {
    final ch = _FakePolishProactiveChannel(
      runningBout: {
        'title': '申论精读',
        'strict': false,
        'until': DateTime.now().millisecondsSinceEpoch + 900000,
        'allow': const ['md.obsidian'],
        'sealId': 'seal-42',
      },
      sealsResponse: const {
        'ok': true,
        'today': '2026-11-22',
        'tomorrow': '2026-11-23',
        'items': [
          {
            'id': 'seal-42',
            'day': '2026-11-22',
            'title': '大作文提纲 800 字',
            'kind': 'file',
            'dueMinute': 1200,
            'actual': 0,
            'floor': 500,
            'stretch': 800,
            'status': 'open',
          },
        ],
      },
    );

    await pumpPhone(tester, BoutsWorkspacePage(asTab: true, channel: ch, loopChannel: _FakePolishLoopChannel()));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('running-bout-scope-chip')), findsNothing);
    expect(find.text('本局关联验收点：大作文提纲 800 字'), findsOneWidget);

    await tester.tap(find.text('申论精读'));
    await settlePhone(tester);
    expect(find.byKey(const ValueKey('bout-detail-seal-row')), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const ValueKey('bout-detail-page')),
        matching: find.text('本局关联验收点：大作文提纲 800 字'),
      ),
      findsOneWidget,
    );
  });

  testWidgets('PlanHubPage renders arbiterEnforce=true chip and geminiAccounting summaryLine', (tester) async {
    final ch = _FakePolishProactiveChannel(
      sealsResponse: const {
        'ok': true,
        'today': '2026-11-22',
        'tomorrow': '2026-11-23',
        'items': <Object>[],
        'arbiterEnforce': true,
        'geminiAccounting': {
          'totalMin': 25,
          'withReceiptMin': 25,
          'unreceiptedMin': 0,
          'freeMode': true,
          'summaryLine': '今日 Gemini 共 25m · 保底达标后自由使用',
        },
      },
    );

    await pumpPhone(tester, PlanHubPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('plan-arbiter-chip')), findsOneWidget);
    expect(find.text('单一裁决器执法中'), findsOneWidget);
    expect(find.byKey(const ValueKey('plan-gemini-summary')), findsOneWidget);
    expect(find.text('今日 Gemini 共 25m · 保底达标后自由使用'), findsOneWidget);
  });

  testWidgets('PlanHubPage renders 影子模式 when arbiterEnforce=false and hides empty geminiAccounting', (tester) async {
    final ch = _FakePolishProactiveChannel(
      sealsResponse: const {
        'ok': true,
        'today': '2026-11-22',
        'tomorrow': '2026-11-23',
        'items': <Object>[],
        'arbiterEnforce': false,
        'geminiAccounting': {
          'totalMin': 0,
          'summaryLine': '',
        },
      },
    );

    await pumpPhone(tester, PlanHubPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('plan-arbiter-chip')), findsOneWidget);
    expect(find.text('影子模式'), findsOneWidget);
    expect(find.byKey(const ValueKey('plan-gemini-summary')), findsNothing);
  });

  testWidgets('PlanHubPage handles null seals/status gracefully without crashing', (tester) async {
    final ch = _FakePolishProactiveChannel(sealsResponse: null);

    await pumpPhone(tester, PlanHubPage(channel: ch));
    await settlePhone(tester);

    expect(find.byKey(const ValueKey('plan-arbiter-chip')), findsOneWidget);
    expect(find.text('单一裁决器执法中'), findsOneWidget);
    expect(find.byKey(const ValueKey('plan-gemini-summary')), findsNothing);
  });

  testWidgets('PlanHubPage falls back to status/channel arbiterEnforce=false when seals omits it', (tester) async {
    final ch = _FakePolishProactiveChannel(
      sealsResponse: const {'ok': true, 'today': '2026-11-22', 'tomorrow': '2026-11-23', 'items': <Object>[]},
      todayExtra: const {'arbiterEnforce': false},
      arbiterValue: false,
    );

    await pumpPhone(tester, PlanHubPage(channel: ch));
    await settlePhone(tester);

    expect(find.text('影子模式'), findsOneWidget);
  });
}
