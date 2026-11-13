// The feature tiles: TileGrid's layout and FeatureCard's behaviour
// (lib/ui/common/tile_grid.dart).
//
// The shapes, palette and glass tint are pinned by test/tiles/feature_tile_test.dart,
// which renders goldens. This file is the part that can be checked without a
// golden — the rules a layout change silently breaks:
//
// - the grid keeps equal-width columns whatever the column count,
// - tiles in one row are as tall as the taller of them,
// - a short last row pads with blanks instead of stretching its tiles,
// - the row gap equals the column gap,
// - a card shows its title and subtitle, a disabled card neither animates nor
//   takes a tap, and a corner widget wins over a text badge.
//
//   flutter test test/tiles/tile_grid_test.dart

import 'package:dsh_app/ui/common/tile_grid.dart';
import 'package:dsh_app/ui/theme/theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Widget _grid(List<Widget> children, {int columns = 2, double spacing = 12}) => MaterialApp(
  theme: buildLightTheme(),
  home: Scaffold(
    body: TileGrid(columns: columns, spacing: spacing, children: children),
  ),
);

/// A tile of a fixed height, so the row's height is the test's to decide.
Widget _cell(String id, double height) =>
    SizedBox(key: ValueKey(id), height: height, child: Text(id));

Widget _card({bool enabled = true, String? badge, Widget? corner, VoidCallback? onTap}) => MaterialApp(
  theme: buildLightTheme(),
  home: Scaffold(
    body: Center(
      child: SizedBox(
        width: 200,
        child: FeatureCard(
          icon: Icons.auto_awesome,
          title: '自动化任务',
          subtitle: '定时提醒和周期任务',
          badge: badge,
          corner: corner,
          enabled: enabled,
          onTap: onTap,
        ),
      ),
    ),
  ),
);

void main() {
  group('TileGrid', () {
    testWidgets('two equal columns, and one row is as tall as its taller tile', (tester) async {
      await tester.pumpWidget(_grid([_cell('a', 40), _cell('b', 80)]));
      await tester.pumpAndSettle();

      final a = tester.getSize(find.byKey(const ValueKey('a')));
      final b = tester.getSize(find.byKey(const ValueKey('b')));
      expect(a.width, closeTo(b.width, 0.01), reason: '两列等宽');
      expect(a.height, 80, reason: '同一行按较高的那块对齐');
      expect(b.height, 80);
    });

    testWidgets('a short last row keeps the column width, it does not stretch', (tester) async {
      await tester.pumpWidget(_grid([_cell('a', 40), _cell('b', 40), _cell('c', 40)]));
      await tester.pumpAndSettle();

      final a = tester.getSize(find.byKey(const ValueKey('a')));
      final c = tester.getSize(find.byKey(const ValueKey('c')));
      expect(c.width, closeTo(a.width, 0.01), reason: '最后一行只剩一块，宽度不变');
    });

    testWidgets('the row gap equals the column gap', (tester) async {
      await tester.pumpWidget(_grid([_cell('a', 40), _cell('b', 40), _cell('c', 40), _cell('d', 40)]));
      await tester.pumpAndSettle();

      final a = tester.getRect(find.byKey(const ValueKey('a')));
      final b = tester.getRect(find.byKey(const ValueKey('b')));
      final c = tester.getRect(find.byKey(const ValueKey('c')));
      expect(b.left - a.right, closeTo(12, 0.01), reason: '列距');
      expect(c.top - a.bottom, closeTo(12, 0.01), reason: '行距');
    });

    testWidgets('the column count is respected', (tester) async {
      await tester.pumpWidget(_grid([_cell('a', 40), _cell('b', 40), _cell('c', 40)], columns: 3));
      await tester.pumpAndSettle();

      final a = tester.getSize(find.byKey(const ValueKey('a')));
      final c = tester.getSize(find.byKey(const ValueKey('c')));
      expect(c.width, closeTo(a.width, 0.01));
      expect(tester.getRect(find.byKey(const ValueKey('c'))).top,
          closeTo(tester.getRect(find.byKey(const ValueKey('a'))).top, 0.01), reason: '三块在同一行');
    });
  });

  group('FeatureCard', () {
    testWidgets('shows its title and subtitle', (tester) async {
      await tester.pumpWidget(_card());
      await tester.pumpAndSettle();

      expect(find.text('自动化任务'), findsOneWidget);
      expect(find.text('定时提醒和周期任务'), findsOneWidget);
    });

    testWidgets('a tap reaches the handler', (tester) async {
      var taps = 0;
      await tester.pumpWidget(_card(onTap: () => taps += 1));
      await tester.pumpAndSettle();

      await tester.tap(find.text('自动化任务'));
      await tester.pumpAndSettle();
      expect(taps, 1);
    });

    testWidgets('a disabled card fades to 45% and takes no tap', (tester) async {
      var taps = 0;
      await tester.pumpWidget(_card(enabled: false, onTap: () => taps += 1));
      await tester.pumpAndSettle();

      final fade = tester.widget<AnimatedOpacity>(find.byType(AnimatedOpacity).first);
      expect(fade.opacity, 0.45);

      await tester.tap(find.text('自动化任务'));
      await tester.pumpAndSettle();
      expect(taps, 0, reason: '不响应点击');
    });

    testWidgets('a badge sits in the corner; a corner widget wins over it', (tester) async {
      await tester.pumpWidget(_card(badge: '网页版'));
      await tester.pumpAndSettle();
      expect(find.text('网页版'), findsOneWidget);

      await tester.pumpWidget(_card(badge: '网页版', corner: const Icon(Icons.open_in_new)));
      await tester.pumpAndSettle();
      expect(find.byIcon(Icons.open_in_new), findsOneWidget);
      expect(find.text('网页版'), findsNothing, reason: '有小圆钮就不画角标');
    });
  });
}
