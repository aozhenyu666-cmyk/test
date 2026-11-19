import 'dart:convert';
import 'dart:io';
import 'dart:ui';

import 'package:dsh_go_fish_pc/services/ai_judge_service.dart';
import 'package:dsh_go_fish_pc/services/file_secret_store.dart';
import 'package:dsh_go_fish_pc/services/pc_guard_controller.dart';
import 'package:dsh_go_fish_pc/services/rule_engine.dart';
import 'package:dsh_go_fish_pc/services/win32_bridge.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeWin32Bridge extends Win32Bridge {
  final List<String> calls = [];
  ForegroundWindowSnapshot? nextForeground = const ForegroundWindowSnapshot(
    app: 'Code',
    title: 'main.dart',
    idleSec: 1,
    selfActive: false,
    pid: 1001,
    hwnd: 2001,
  );
  bool capsule = false;
  bool preventClose = false;
  bool autoStart = false;
  bool watchdogArmed = false;
  bool shieldTopmost = false;

  @override
  void registerHostCallbacks({
    VoidCallback? onCloseIntercepted,
    VoidCallback? onTrayShowMain,
    VoidCallback? onToggleCapsuleFromTray,
  }) {
    calls.add('registerHostCallbacks');
  }

  @override
  Future<ForegroundWindowSnapshot?> getForeground() async {
    calls.add('getForeground');
    return nextForeground;
  }

  @override
  Future<bool> minimizeWindow(int hwnd) async {
    calls.add('minimizeWindow:$hwnd');
    return true;
  }

  @override
  Future<bool> terminateProcess(int pid, String expectedApp) async {
    calls.add('terminateProcess:$pid:$expectedApp');
    return true;
  }

  @override
  Future<bool> setShieldTopmost(bool topmost) async {
    calls.add('setShieldTopmost:$topmost');
    shieldTopmost = topmost;
    return true;
  }

  @override
  Future<bool> setCapsuleMode(bool enable) async {
    calls.add('setCapsuleMode:$enable');
    capsule = enable;
    return capsule;
  }

  @override
  Future<bool> setPreventClose(bool prevent) async {
    calls.add('setPreventClose:$prevent');
    preventClose = prevent;
    return preventClose;
  }

  @override
  Future<bool> setAutoStart(bool enabled) async {
    calls.add('setAutoStart:$enabled');
    autoStart = enabled;
    return true;
  }

  @override
  Future<bool> ensureWatchdog({
    required String stateDir,
    required bool strictArmed,
  }) async {
    calls.add('ensureWatchdog:$strictArmed');
    watchdogArmed = strictArmed;
    return true;
  }

  @override
  Future<bool> authorizedExit() async {
    calls.add('authorizedExit');
    return true;
  }
}

void main() {
  test('PcGuardController consumes watchdog log, arms strict mode, enforces & resets topmost cleanly', () async {
    final tmp = await Directory.systemTemp.createTemp('dsh_pc_guard_test_');
    addTearDown(() => tmp.delete(recursive: true));

    // Pre-seed a watchdog restart log before init
    await File('${tmp.path}${Platform.pathSeparator}watchdog_restarts.log')
        .writeAsString('1763532000000|respawned_after_abnormal_exit\n');

    var fakeUtcMs = DateTime.utc(2026, 11, 19, 6, 0).millisecondsSinceEpoch; // 14:00 Beijing
    final fakeWin32 = _FakeWin32Bridge();
    final ctrl = PcGuardController(
      store: FileSecretStore(tmp),
      win32: fakeWin32,
      nowUtcMs: () => fakeUtcMs,
    );
    addTearDown(ctrl.dispose);

    await ctrl.init(autoStartLoop: false);
    expect(ctrl.ledger.watchdogRestarts, 1);
    expect(ctrl.ledger.eventLogs.first.kind, 'watchdog_restart');

    // Arm strict guard
    await ctrl.armStrictGuard();
    expect(ctrl.rules.strictGuardArmed, isTrue);
    expect(fakeWin32.preventClose, isTrue);
    expect(fakeWin32.watchdogArmed, isTrue);
    expect(fakeWin32.autoStart, isTrue);
    final lockFile = jsonDecode(
      await File('${tmp.path}${Platform.pathSeparator}guard_lock.json')
          .readAsString(),
    ) as Map<String, dynamic>;
    expect(lockFile['strictArmed'], isTrue);

    // Start 30m hard focus session; attempt to shorten to 10m should be blocked
    await ctrl.startFocusSession(minutes: 30, taskName: '资料分析刷题', hardLocked: true);
    expect(ctrl.focusSession.active, isTrue);
    final prevEnd = ctrl.focusSession.endsAtUtcMs;
    await ctrl.startFocusSession(minutes: 10, taskName: '偷减时长', hardLocked: false);
    expect(ctrl.focusSession.endsAtUtcMs, prevEnd);

    // Tick 1 & 2 on Code -> accumulates 10s workSeconds
    await ctrl.tickSensor();
    fakeUtcMs += 10000;
    await ctrl.tickSensor();
    expect(ctrl.ledger.workSeconds, 10);

    // Switch to steam during focus -> terminates process & raises topmost shield
    fakeUtcMs += 2000;
    fakeWin32.nextForeground = const ForegroundWindowSnapshot(
      app: 'steam',
      title: 'Steam',
      idleSec: 0,
      selfActive: false,
      pid: 4242,
      hwnd: 9999,
    );
    await ctrl.tickSensor();
    expect(ctrl.activeBlock, isNotNull);
    expect(fakeWin32.calls, contains('terminateProcess:4242:steam'));
    expect(fakeWin32.shieldTopmost, isTrue);

    // Emergency pass dismisses shield & restores NOTOPMOST
    final badEp = await ctrl.grantEmergencyPass(reason: '太短');
    expect(badEp.ok, isFalse);
    final okEp = await ctrl.grantEmergencyPass(reason: '需要临时去拿顺丰快递');
    expect(okEp.ok, isTrue);
    expect(ctrl.activeBlock, isNull);
    expect(fakeWin32.shieldTopmost, isFalse);

    // Switch to browser entertainment after emergency pass expires
    fakeUtcMs += 6 * 60 * 1000;
    fakeWin32.nextForeground = const ForegroundWindowSnapshot(
      app: 'chrome',
      title: '抖音 - 搞笑视频',
      idleSec: 0,
      selfActive: false,
      pid: 5555,
      hwnd: 8888,
    );
    await ctrl.tickSensor();
    expect(ctrl.activeBlock, isNotNull);
    expect(fakeWin32.calls, contains('minimizeWindow:8888'));
    expect(fakeWin32.calls, isNot(contains('terminateProcess:5555:chrome')));

    // Returning to Obsidian automatically clears shield and cancels HWND_TOPMOST
    fakeUtcMs += 2000;
    fakeWin32.nextForeground = const ForegroundWindowSnapshot(
      app: 'Obsidian',
      title: '复盘笔记.md',
      idleSec: 0,
      selfActive: false,
      pid: 3333,
      hwnd: 7777,
    );
    await ctrl.tickSensor();
    expect(ctrl.activeBlock, isNull);
    expect(fakeWin32.shieldTopmost, isFalse);
  });

  test('PcGuardController enforces high-friction disarm/exit and Beijing midnight rollover', () async {
    final tmp = await Directory.systemTemp.createTemp('dsh_pc_friction_test_');
    addTearDown(() => tmp.delete(recursive: true));

    // 2026-11-19 23:59 Beijing == 15:59 UTC
    var fakeUtcMs = DateTime.utc(2026, 11, 19, 15, 59).millisecondsSinceEpoch;
    final fakeWin32 = _FakeWin32Bridge();
    final aiSvc = AiJudgeService(
      transport: ({required url, required headers, required body}) async =>
          jsonEncode({
        'choices': [
          {
            'message': {
              'content': '{"approved":true,"leaseMinutes":15,"reason":"确为公开课"}'
            }
          }
        ]
      }),
    );
    final ctrl = PcGuardController(
      store: FileSecretStore(tmp),
      win32: fakeWin32,
      aiJudge: aiSvc,
      nowUtcMs: () => fakeUtcMs,
    );
    addTearDown(ctrl.dispose);

    await ctrl.init(autoStartLoop: false);
    await ctrl.armStrictGuard();
    await ctrl.startFocusSession(minutes: 20, taskName: '申论大作文', hardLocked: true);

    final challenge = RuleEngine.generateFrictionChallenge(80);
    // Refused during hard focus lock
    final blockedByFocus = await ctrl.completeFrictionDisarmOrExit(
      alsoExit: true,
      challengeInput: challenge,
      expectedChallenge: challenge,
      reason: '我已经写完了想现在立刻退出休息一下',
      waitedSeconds: 60,
    );
    expect(blockedByFocus.ok, isFalse);
    expect(blockedByFocus.message, contains('专注铁锁倒计时'));

    // Advance past focus session AND past Beijing midnight (00:05 Beijing)
    fakeUtcMs += 25 * 60 * 1000;
    await ctrl.tickSensor();
    expect(ctrl.focusSession.active, isFalse);
    expect(ctrl.ledger.dayKey, '2026-11-20');
    expect(
      await File('${tmp.path}${Platform.pathSeparator}ledger_archive_2026-11-19.json')
          .exists(),
      isTrue,
    );

    // Refused if waitedSeconds < 60
    final tooFast = await ctrl.completeFrictionDisarmOrExit(
      alsoExit: true,
      challengeInput: challenge,
      expectedChallenge: challenge,
      reason: '今天计划全部结束，需要关闭软件安装系统补丁',
      waitedSeconds: 30,
    );
    expect(tooFast.ok, isFalse);

    // Passes once all high-friction gates satisfied
    final passed = await ctrl.completeFrictionDisarmOrExit(
      alsoExit: true,
      challengeInput: challenge,
      expectedChallenge: challenge,
      reason: '今天计划全部结束，需要关闭软件安装系统补丁',
      waitedSeconds: 60,
    );
    expect(passed.ok, isTrue);
    expect(ctrl.rules.strictGuardArmed, isFalse);
    expect(fakeWin32.preventClose, isFalse);
    expect(fakeWin32.watchdogArmed, isFalse);
    expect(fakeWin32.calls, contains('authorizedExit'));
    expect(
      await File('${tmp.path}${Platform.pathSeparator}authorized_exit.flag')
          .exists(),
      isTrue,
    );
  });
}
