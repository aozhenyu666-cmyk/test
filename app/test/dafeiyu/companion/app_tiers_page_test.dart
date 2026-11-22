import 'package:dsh_app/dafeiyu/channels/intercept_channel.dart';
import 'package:dsh_app/dafeiyu/channels/loop_channel.dart';
import 'package:dsh_app/dafeiyu/channels/proactive_state.dart';
import 'package:dsh_app/dafeiyu/ui/app_tiers_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../phone/harness.dart';

/// App 分类表：旧娱乐锁机名单并入「娱乐」并清空旧名单；改层走插件（改严立即、改松挂起）。
class _Tiers extends ProactiveChannel {
  final Map<String, String> tiers = {'tv.danmaku.bili': 'other', 'md.obsidian': 'work', 'com.bbk.launcher2': 'base'};
  final List<Map<String, Object?>> calls = [];

  @override
  Future<Map<String, dynamic>?> tiersOp(Map<String, Object?> body) async {
    calls.add(body);
    if (body['action'] == 'set') {
      tiers['${body['pkg']}'] = '${body['tier']}';
      return {'ok': true, 'applied': true, 'text': '改严，立即生效。'};
    }
    return {
      'ok': true,
      'rows': [
        for (final e in tiers.entries) {'pkg': e.key, 'label': e.key == 'tv.danmaku.bili' ? '哔哩哔哩' : '', 'tier': e.value, 'override': null, 'pendingTier': null},
      ],
    };
  }
}

class _Loop extends LoopChannel {
  @override
  Future<List<LoopApp>> apps() async => const [LoopApp('tv.danmaku.bili', '哔哩哔哩'), LoopApp('md.obsidian', 'Obsidian')];
}

class _Intercept extends InterceptChannel {
  List<InterceptTarget> targets = const [InterceptTarget(packageName: 'tv.danmaku.bili', label: '哔哩哔哩')];
  final List<Map<String, Object?>> patches = [];

  @override
  Future<InterceptStatus> status() async => InterceptStatus(
        config: InterceptConfig(targets: targets),
        counts: const InterceptCounts(),
        receipts: const [],
        active: null,
      );

  @override
  Future<InterceptStatus> patch(Map<String, Object?> changes) async {
    patches.add(changes);
    if (changes['targets'] is List) targets = const [];
    return status();
  }
}

void main() {
  testWidgets('打开分类表：旧娱乐锁机名单并入「娱乐」，旧名单清空', (tester) async {
    final ch = _Tiers();
    final ic = _Intercept();
    await pumpPhone(tester, AppTiersPage(channel: ch, loop: _Loop(), intercept: ic));
    await settlePhone(tester);
    expect(ch.calls.first, {'action': 'set', 'pkg': 'tv.danmaku.bili', 'tier': 'fun'});
    expect(ic.patches.single['enabled'], false);
    expect(find.text('娱乐 · 1'), findsOneWidget);
    expect(find.text('哔哩哔哩'), findsOneWidget);
    expect(find.textContaining('已并入「娱乐」'), findsOneWidget);
  });

  testWidgets('点一个 App 选「游戏」：发 set，系统底座不能点', (tester) async {
    final ch = _Tiers();
    await pumpPhone(tester, AppTiersPage(channel: ch, loop: _Loop(), intercept: _Intercept()..targets = const []));
    await settlePhone(tester);
    await tester.tap(find.text('md.obsidian'));
    await settlePhone(tester);
    await tester.tap(find.byKey(const ValueKey('tier-pick-game')));
    await settlePhone(tester);
    expect(ch.calls.where((c) => c['action'] == 'set').single, {'action': 'set', 'pkg': 'md.obsidian', 'tier': 'game'});
  });
}
