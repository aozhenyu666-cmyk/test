// The bottom navigation: 局 / 墙 / 对话 across the bottom of the shell
// (`_tabBar` in lib/ui/shell/home_shell.dart, built on SegmentedPill).
//
// Round 8 W3 moved the shell from a Drawer-only layout to these three tabs, with
// 局 as the page the app opens on. The tab bar itself is a thin wrapper, so what
// is worth pinning here is the part that can silently break:
//
// - the tab order (局 first: it is the default page),
// - the labels the bar shows, including 局 falling back to its own name when the
//   engine has no title for the current 局,
// - which value a tap reports, and that tapping the already-selected tab reports
//   nothing (otherwise every tap rebuilds the tab body),
// - that a bar with no handler is inert rather than throwing.
//
// Geometry and colour live in the golden suites; this file never renders a
// golden.
//
//   flutter test test/shell/bottom_nav_test.dart

import 'package:dsh_app/ui/common/segmented_pill.dart';
import 'package:dsh_app/ui/shell/home_shell.dart';
import 'package:dsh_app/ui/theme/theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// The bar as `_tabBar` builds it, with the engine's 局 title as [boutsTitle].
Widget _bar({
  HomeTab selected = HomeTab.bouts,
  String? boutsTitle,
  ValueChanged<HomeTab>? onChanged,
}) {
  return MaterialApp(
    theme: buildLightTheme(),
    home: Scaffold(
      bottomNavigationBar: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
        child: SegmentedPill<HomeTab>(
          segments: [
            PillSegment(HomeTab.bouts, boutsTitle ?? '局'),
            const PillSegment(HomeTab.wall, '墙'),
            const PillSegment(HomeTab.chat, '对话'),
          ],
          selected: selected,
          onChanged: onChanged,
        ),
      ),
    ),
  );
}

void main() {
  group('tabs', () {
    test('局 is the first tab, so 局 is the page the app opens on', () {
      expect(HomeTab.values, [HomeTab.bouts, HomeTab.wall, HomeTab.chat]);
    });
  });

  group('the tab bar', () {
    testWidgets('shows 局 / 墙 / 对话 in that order', (tester) async {
      await tester.pumpWidget(_bar());
      await tester.pumpAndSettle();

      expect(find.text('局'), findsOneWidget);
      expect(find.text('墙'), findsOneWidget);
      expect(find.text('对话'), findsOneWidget);

      // Left to right, matching HomeTab's order.
      final xs = ['局', '墙', '对话'].map((l) => tester.getCenter(find.text(l)).dx).toList();
      expect(xs[0], lessThan(xs[1]));
      expect(xs[1], lessThan(xs[2]));
    });

    testWidgets('an engine 局 title takes the place of 局', (tester) async {
      await tester.pumpWidget(_bar(boutsTitle: '学习局'));
      await tester.pumpAndSettle();

      expect(find.text('学习局'), findsOneWidget);
      expect(find.text('局'), findsNothing);
    });

    testWidgets('a tap reports that tab', (tester) async {
      final picked = <HomeTab>[];
      await tester.pumpWidget(_bar(onChanged: picked.add));
      await tester.pumpAndSettle();

      await tester.tap(find.text('墙'));
      await tester.pumpAndSettle();
      expect(picked, [HomeTab.wall]);

      await tester.tap(find.text('对话'));
      await tester.pumpAndSettle();
      expect(picked, [HomeTab.wall, HomeTab.chat]);
    });

    testWidgets('tapping the selected tab reports nothing', (tester) async {
      // 局 is open; tapping it again must not rebuild the page for nothing.
      var calls = 0;
      await tester.pumpWidget(_bar(selected: HomeTab.bouts, onChanged: (_) => calls += 1));
      await tester.pumpAndSettle();

      await tester.tap(find.text('局'));
      await tester.pumpAndSettle();
      expect(calls, 0);
    });

    testWidgets('without a handler the bar is inert, not broken', (tester) async {
      // `onChanged: null` is the disabled form: it must still lay out.
      await tester.pumpWidget(_bar(onChanged: null));
      await tester.pumpAndSettle();

      expect(find.text('局'), findsOneWidget);
      await tester.tap(find.text('墙'));
      await tester.pumpAndSettle();
      // Nothing to assert but that it settled: a throw here fails the test.
      expect(find.text('墙'), findsOneWidget);
    });

    testWidgets('every segment is a named button for screen readers', (tester) async {
      final handle = tester.ensureSemantics();
      await tester.pumpWidget(_bar());
      await tester.pumpAndSettle();

      for (final label in ['局', '墙', '对话']) {
        expect(find.bySemanticsLabel(label), findsOneWidget, reason: label);
      }
      handle.dispose();
    });
  });
}
