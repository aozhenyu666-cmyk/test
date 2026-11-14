import 'package:flutter/material.dart';

/// Mist Glass palette matching `app/lib/ui/theme/palette.dart` across mobile and Windows.
abstract final class MistColors {
  static const lightTop = Color(0xFFEBF1FE);
  static const lightBottom = Color(0xFFF6F7FC);
  static const darkTop = Color(0xFF1D222C);
  static const darkBottom = Color(0xFF1B1C23);

  static const ink = Color(0xFF1E232E);
  static const inkMuted = Color(0xFF5B6475);
  static const paper = Color(0xFFE6E9F0);
  static const paperMuted = Color(0xFFA3AAB8);

  static const cobalt = Color(0xFF2D5CF6);
  static const emerald = Color(0xFF34C759);
  static const amber = Color(0xFFFF9500);
  static const crimson = Color(0xFFFF3B30);
  static const fishCoral = Color(0xFFE86F52);
  static const whaleTeal = Color(0xFF1F7A8C);
}

ThemeData buildMistTheme(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: ColorScheme.fromSeed(
      seedColor: MistColors.cobalt,
      brightness: brightness,
      primary: MistColors.cobalt,
    ),
    scaffoldBackgroundColor: dark ? MistColors.darkBottom : MistColors.lightBottom,
    cardColor: dark ? const Color(0xFF232936) : Colors.white.withValues(alpha: 0.88),
    dividerColor: dark ? Colors.white12 : const Color(0xFFD6E0F5),
  );
}

class MistGlassSurface extends StatelessWidget {
  const MistGlassSurface({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(16),
    this.radius = 20,
    this.borderColor,
  });

  final Widget child;
  final EdgeInsetsGeometry padding;
  final double radius;
  final Color? borderColor;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: dark ? 0.25 : 0.05),
            blurRadius: 20,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: Material(
        color: dark ? const Color(0xFF232936).withValues(alpha: 0.90) : Colors.white.withValues(alpha: 0.85),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radius),
          side: BorderSide(
            color: borderColor ?? (dark ? Colors.white.withValues(alpha: 0.10) : const Color(0xFFCFDEF8)),
          ),
        ),
        clipBehavior: Clip.antiAlias,
        child: Padding(padding: padding, child: child),
      ),
    );
  }
}

class MistGradientBackdrop extends StatelessWidget {
  const MistGradientBackdrop({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: dark
              ? const [MistColors.darkTop, MistColors.darkBottom]
              : const [MistColors.lightTop, MistColors.lightBottom],
        ),
      ),
      child: child,
    );
  }
}
