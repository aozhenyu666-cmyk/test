import 'package:flutter/services.dart';

class ForegroundWindowSnapshot {
  const ForegroundWindowSnapshot({
    required this.app,
    required this.title,
    required this.idleSec,
    required this.selfActive,
  });

  final String app;
  final String title;
  final int idleSec;
  final bool selfActive;
}

/// Bridges Dart UI with Windows runner APIs (`GetForegroundWindow`, `GetLastInputInfo`,
/// `SendInput(VK_MEDIA_PLAY_PAUSE)`, and topmost mini-capsule resizing).
class Win32Bridge {
  Win32Bridge([MethodChannel? channel])
      : _channel = channel ?? const MethodChannel('dsh_go_fish_pc/win32');

  final MethodChannel _channel;

  Future<ForegroundWindowSnapshot?> getForeground() async {
    try {
      final raw = await _channel.invokeMapMethod<Object?, Object?>('getForeground');
      if (raw == null) return null;
      return ForegroundWindowSnapshot(
        app: (raw['app'] ?? '').toString(),
        title: (raw['title'] ?? '').toString(),
        idleSec: int.tryParse((raw['idleSec'] ?? 0).toString()) ?? 0,
        selfActive: raw['selfActive'] == true,
      );
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
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
      return await _channel.invokeMethod<bool>('setCapsuleMode', {'capsule': capsule}) ?? false;
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
}
