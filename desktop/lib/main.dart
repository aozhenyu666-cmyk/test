import 'dart:async';

import 'package:flutter/material.dart';

import 'services/dsh_bridge.dart';
import 'theme/mist_theme.dart';
import 'ui/desktop_shell.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const DshGoFishPcApp());
}

class DshGoFishPcApp extends StatefulWidget {
  const DshGoFishPcApp({super.key, this.controller, this.autoInit = true});

  final DesktopController? controller;
  final bool autoInit;

  @override
  State<DshGoFishPcApp> createState() => _DshGoFishPcAppState();
}

class _DshGoFishPcAppState extends State<DshGoFishPcApp> {
  late final DesktopController _controller;
  bool _darkMode = false;

  @override
  void initState() {
    super.initState();
    _controller = widget.controller ?? DesktopController();
    if (widget.autoInit) {
      unawaited(_controller.init());
    }
  }

  @override
  void dispose() {
    if (widget.controller == null) {
      _controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: _controller,
      builder: (context, _) {
        return MaterialApp(
          title: 'DSH Go 大肥鱼 · PC',
          debugShowCheckedModeBanner: false,
          theme: buildMistTheme(Brightness.light),
          darkTheme: buildMistTheme(Brightness.dark),
          themeMode: _darkMode ? ThemeMode.dark : ThemeMode.light,
          home: DesktopShell(
            controller: _controller,
            darkMode: _darkMode,
            onToggleDarkMode: () => setState(() => _darkMode = !_darkMode),
          ),
        );
      },
    );
  }
}
