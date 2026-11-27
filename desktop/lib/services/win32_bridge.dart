import 'package:flutter/services.dart';

class ForegroundWindowSnapshot {
  const ForegroundWindowSnapshot({
    required this.app,
    required this.title,
    required this.idleSec,
    required this.selfActive,
    this.screenLocked = false,
    this.pid = 0,
    this.hwnd = 0,
  });

  final String app;
  final String title;
  final int idleSec;
  final bool selfActive;
  final bool screenLocked;
  final int pid;
  final int hwnd;
}

/// Bridges Dart UI with Windows runner APIs for foreground window polling, AFK
/// detection, safe process termination / window minimization, topmost shield,
/// WM_CLOSE interception, registry auto-start, and companion watchdog control.
class Win32Bridge {
  Win32Bridge([MethodChannel? channel])
      : _channel = channel ?? const MethodChannel('dsh_go_fish_pc/win32');

  final MethodChannel _channel;

  void registerHostCallbacks({
    VoidCallback? onCloseIntercepted,
    VoidCallback? onTrayShowMain,
    VoidCallback? onToggleCapsuleFromTray,
  }) {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onCloseIntercepted':
          onCloseIntercepted?.call();
        case 'onTrayShowMain':
          onTrayShowMain?.call();
        case 'onToggleCapsuleFromTray':
          onToggleCapsuleFromTray?.call();
      }
      return null;
    });
  }

  Future<ForegroundWindowSnapshot?> getForeground() async {
    try {
      final raw =
          await _channel.invokeMapMethod<Object?, Object?>('getForeground');
      if (raw == null) return null;
      return ForegroundWindowSnapshot(
        app: (raw['app'] ?? '').toString(),
        title: (raw['title'] ?? '').toString(),
        idleSec: int.tryParse((raw['idleSec'] ?? 0).toString()) ?? 0,
        selfActive: raw['selfActive'] == true,
        screenLocked: raw['screenLocked'] == true,
        pid: int.tryParse((raw['pid'] ?? 0).toString()) ?? 0,
        hwnd: int.tryParse((raw['hwnd'] ?? 0).toString()) ?? 0,
      );
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  Future<bool> minimizeWindow(int hwnd) async {
    if (hwnd == 0) return false;
    try {
      return await _channel.invokeMethod<bool>('minimizeWindow', {'hwnd': hwnd}) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> terminateProcess(int pid, String expectedApp) async {
    if (pid <= 0 || expectedApp.trim().isEmpty) return false;
    try {
      return await _channel.invokeMethod<bool>('terminateProcess', {
            'pid': pid,
            'expectedApp': expectedApp,
          }) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> setShieldTopmost(bool topmost) async {
    try {
      return await _channel.invokeMethod<bool>(
            'setShieldTopmost',
            {'topmost': topmost},
          ) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> raiseWindow() async {
    try {
      return await _channel.invokeMethod<bool>('raiseWindow') ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> sendMediaPlayPause() async {
    try {
      return await _channel.invokeMethod<bool>('sendMediaPlayPause') ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> setCapsuleMode(bool capsule) async {
    try {
      return await _channel.invokeMethod<bool>(
            'setCapsuleMode',
            {'capsule': capsule},
          ) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> setPreventClose(bool prevent) async {
    try {
      return await _channel.invokeMethod<bool>(
            'setPreventClose',
            {'prevent': prevent},
          ) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> setAutoStart(bool enabled) async {
    try {
      return await _channel.invokeMethod<bool>(
            'setAutoStart',
            {'enabled': enabled},
          ) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> getAutoStart() async {
    try {
      return await _channel.invokeMethod<bool>('getAutoStart') ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> ensureWatchdog({
    required String stateDir,
    required bool strictArmed,
  }) async {
    try {
      return await _channel.invokeMethod<bool>('ensureWatchdog', {
            'stateDir': stateDir,
            'strictArmed': strictArmed,
          }) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> authorizedExit() async {
    try {
      return await _channel.invokeMethod<bool>('authorizedExit') ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }
}
