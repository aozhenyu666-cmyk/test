import 'dart:io';

import 'package:dsh_go_fish_pc/main.dart';
import 'package:dsh_go_fish_pc/services/dsh_bridge.dart';
import 'package:dsh_go_fish_pc/services/file_secret_store.dart';
import 'package:dsh_go_fish_pc/services/win32_bridge.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class _UiFakeWin32 extends Win32Bridge {
  @override
  Future<bool> setCapsuleMode(bool capsule) async => capsule;
}

void main() {
  testWidgets('DshGoFishPcApp switches Mist Glass tabs and renders 6-dice card & mini capsule', (tester) async {
    tester.view.physicalSize = const Size(1400, 900);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    // Sync Directory creation avoids blocking inside FakeAsync's testWidgets zone.
    final tmp = Directory.systemTemp.createTempSync('dsh_pc_widget_test_');
    addTearDown(() => tmp.deleteSync(recursive: true));

    final ctrl = DesktopController(store: FileSecretStore(tmp), win32: _UiFakeWin32());
    addTearDown(ctrl.dispose);

    await tester.pumpWidget(DshGoFishPcApp(controller: ctrl, autoInit: false));
    await tester.pump();
    expect(find.text('DSH Go 大肥鱼 · PC'), findsOneWidget);
    expect(find.text('鱼丸 · 日常陪伴'), findsOneWidget);

    await tester.tap(find.text('中枢·六骰'));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('六骰思维防走神打卡（FWK-003）'), findsOneWidget);

    await tester.tap(find.text('墙与交付'));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('向大肥鱼交付作品 / 代码改动（交墙验真）'), findsOneWidget);

    await tester.tap(find.text('PC 哨兵'));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Windows 前台与 AFK 哨兵'), findsOneWidget);

    await tester.tap(find.text('迷你胶囊'));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('展开'), findsOneWidget);
  });
}
