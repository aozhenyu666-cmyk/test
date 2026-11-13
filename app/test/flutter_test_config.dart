// Runs around every test file under test/: real fonts first (strict: a
// missing font fails the run), so no test measures or draws the test
// engine's box font. Widget tests get the channel mocks below; a file that
// never pumps keeps its real sockets and HTTP.

import 'dart:async';

import 'package:dsh_app/l10n/l10n.dart';
import 'package:dsh_app/performance/performance_state.dart';
import 'package:dsh_app/pets/pet_sheets.dart';
import 'package:dsh_app/state/locale_state.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/english_preview.dart';
import 'support/quiet_agent_channel.dart';
import 'support/quiet_performance.dart';
import 'support/test_fonts.dart';

Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  await loadTestFonts();
  // The chat page's pet is drawn only where a test asks for it
  // (test/pets/): elsewhere a sheet decoding in a stray real-async window
  // would make screens flicker between with and without a pet.
  PetSheets.enabled = false;
  // No device facts in tests: the app reads "unavailable" at once.
  PerformanceChannel.create = QuietPerformanceChannel.new;
  // Same for the kernel's agent stream: no native end means silence, not a
  // MissingPluginException on every screen that watches the transport.
  // Through setUp rather than inline: setMockStreamHandler registers its own
  // addTearDown, which is only legal inside a test. Only where a widgets
  // binding already exists (a widget test): creating one here would hand the
  // plain tests a fake HttpClient that answers 400 to everything.
  setUp(() {
    if (_bindingOrNull == null) return;
    installQuietAgentChannel();
  });
  // Tests run in Chinese (goldens and finders were made in it); English
  // tests opt in with AppLocale.use(AppLocale.en) + an English MaterialApp.
  AppLanguageController.initial = AppLanguage.zh;
  AppLocale.use(AppLocale.zhCN);
  setUpEnglishPreview();
  await testMain();
}

/// The widgets binding, or null in a file that never pumps: `testWidgets`
/// builds it when it registers the test, before any `setUp` runs.
WidgetsBinding? get _bindingOrNull {
  try {
    return WidgetsBinding.instance;
  } on Object {
    return null;
  }
}
