import 'dart:io';

import 'package:dsh_go_fish_pc/main.dart';
import 'package:dsh_go_fish_pc/services/file_secret_store.dart';
import 'package:dsh_go_fish_pc/services/pc_guard_controller.dart';
import 'package:dsh_go_fish_pc/services/win32_bridge.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class _UiFakeWin32 extends Win32Bridge {
  ForegroundWindowSnapshot? next;

  @override
  void registerHostCallbacks({
    VoidCallback? onCloseIntercepted,
    VoidCallback? onTrayShowMain,
    VoidCallback? onToggleCapsuleFromTray,
  }) {}

  @override
  Future<ForegroundWindowSnapshot?> getForeground() async => next;

  @override
  Future<bool> terminateProcess(int pid, String expectedApp) async => true;

  @override
  Future<bool> setCapsuleMode(bool capsule) async => capsule;

  @override
  Future<bool> setShieldTopmost(bool topmost) async => true;
}

void main() {
  testWidgets('DshGoFishPcApp renders 4 guard tabs, mini capsule, and unskippable block shield', (tester) async {
    tester.view.physicalSize = const Size(1400, 900);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);

    final tmp = Directory.systemTemp.createTempSync('dsh_pc_ui_test_');
    addTearDown(() => tmp.deleteSync(recursive: true));

    final fakeWin32 = _UiFakeWin32();
    final ctrl = PcGuardController(
      store: FileSecretStore(tmp),
      win32: fakeWin32,
    );
    addTearDown(ctrl.dispose);

    await tester.pumpWidget(DshGoFishPcApp(controller: ctrl, autoInit: false));
    await tester.pump();

    expect(find.text('大肥鱼 · PC 严防哨兵'), findsOneWidget);
    expect(find.text('开启电脑专注锁'), findsOneWidget);

    await tester.tap(find.text('规则与严防'));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('管控规则与分类词库'), findsOneWidget);

    await tester.tap(find.text('AI 裁判'));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('OpenAI 兼容 AI 裁判接口'), findsOneWidget);

    await tester.tap(find.text('账本与日志'));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('执法、Watchdog 与防绕过日志'), findsOneWidget);

    await tester.tap(find.text('迷你胶囊'));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('展开'), findsOneWidget);

    await tester.tap(find.text('展开'));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('大肥鱼 · PC 严防哨兵'), findsOneWidget);

    await tester.runAsync(() async {
      await ctrl.startFocusSession(minutes: 25, taskName: '行测练习');
      fakeWin32.next = const ForegroundWindowSnapshot(
        app: 'steam',
        title: 'Steam',
        idleSec: 0,
        selfActive: false,
        pid: 777,
        hwnd: 888,
      );
      await ctrl.tickSensor();
    });
    await tester.pump();

    expect(find.text('大肥鱼 PC 严防拦截盾'), findsOneWidget);
    expect(find.text('我已关闭娱乐，回到工作'), findsOneWidget);
    expect(find.text('完成反思并继续'), findsNothing);
  });
}
