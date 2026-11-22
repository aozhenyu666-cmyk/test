// Dart side of the `dsh/loop` method channel (android/.../loop/LoopPlugin.kt):
// 设置 → 手机操控 → 触发测试, the first closed loop.

import 'package:flutter/services.dart';

class LoopConfig {
  const LoopConfig({
    this.enabled = false,
    this.targetPackage,
    this.cooldownMs = 60000,
    this.timeoutMs = 60000,
    this.quietStartMinute,
    this.quietEndMinute,
    this.dailyCap = 0,
  });

  final bool enabled;
  final String? targetPackage;
  final int cooldownMs;
  final int timeoutMs;

  /// Quiet hours, minutes after midnight. [quietEndMinute] smaller than [quietStartMinute] crosses midnight.
  final int? quietStartMinute;
  final int? quietEndMinute;

  /// Maximum triggers per day; 0 = unlimited.
  final int dailyCap;

  bool get quietHoursSet => quietStartMinute != null && quietEndMinute != null && quietStartMinute != quietEndMinute;

  /// The same shape the native side sends, so an export round-trips.
  Map<String, Object?> toMap() => {
    'enabled': enabled,
    'targetPackage': targetPackage,
    'cooldownMs': cooldownMs,
    'timeoutMs': timeoutMs,
    'quietStartMinute': quietStartMinute,
    'quietEndMinute': quietEndMinute,
    'dailyCap': dailyCap,
  };

  static LoopConfig fromWire(Object? raw) {
    final m = raw is Map ? raw : const {};
    int? n(Object? v) => v is num ? v.toInt() : null;
    return LoopConfig(
      enabled: m['enabled'] == true,
      targetPackage: m['targetPackage'] is String ? m['targetPackage'] as String : null,
      cooldownMs: m['cooldownMs'] is num ? (m['cooldownMs'] as num).toInt() : 60000,
      timeoutMs: m['timeoutMs'] is num ? (m['timeoutMs'] as num).toInt() : 60000,
      quietStartMinute: n(m['quietStartMinute']),
      quietEndMinute: n(m['quietEndMinute']),
      dailyCap: n(m['dailyCap']) ?? 0,
    );
  }
}

/// One fault the native side kept: an accessibility handler, a foreground read, a surface.
class LoopFault {
  const LoopFault({required this.kind, required this.detail, required this.at});

  final String kind;
  final String detail;
  final DateTime at;

  Map<String, Object?> toMap() => {'kind': kind, 'detail': detail, 'at': at.millisecondsSinceEpoch};

  static LoopFault? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final kind = raw['kind'];
    final at = raw['at'];
    if (kind is! String || at is! num) return null;
    return LoopFault(
      kind: kind,
      detail: raw['detail'] is String ? raw['detail'] as String : '',
      at: DateTime.fromMillisecondsSinceEpoch(at.toInt()),
    );
  }
}

/// `status.diagnostics`: what the settings page needs to explain a silent loop.
class LoopDiagnosticsView {
  const LoopDiagnosticsView({
    this.faults = const {},
    this.streaks = const {},
    this.recent = const [],
    this.lastEventAt,
    this.lastVerifyAt,
    this.lastReadAt,
  });

  /// Total failures per kind since process start.
  final Map<String, int> faults;

  /// Failures in a row right now per kind.
  final Map<String, int> streaks;
  final List<LoopFault> recent;
  final DateTime? lastEventAt;
  final DateTime? lastVerifyAt;
  final DateTime? lastReadAt;

  bool get isEmpty => faults.isEmpty && lastEventAt == null && lastVerifyAt == null && lastReadAt == null;

  /// For the JSON export.
  Map<String, Object?> toMap() => {
    'faults': faults,
    'streaks': streaks,
    'recent': [for (final f in recent) f.toMap()],
    'lastEventAt': lastEventAt?.millisecondsSinceEpoch,
    'lastVerifyAt': lastVerifyAt?.millisecondsSinceEpoch,
    'lastReadAt': lastReadAt?.millisecondsSinceEpoch,
  };

  static LoopDiagnosticsView fromWire(Object? raw) {
    final m = raw is Map ? raw : const {};
    Map<String, int> counts(Object? v) {
      if (v is! Map) return const {};
      return {
        for (final e in v.entries)
          if (e.key is String && e.value is num) e.key as String: (e.value as num).toInt(),
      };
    }

    DateTime? ts(Object? v) => v is num ? DateTime.fromMillisecondsSinceEpoch(v.toInt()) : null;
    return LoopDiagnosticsView(
      faults: counts(m['faults']),
      streaks: counts(m['streaks']),
      recent: [for (final f in (m['recent'] is List ? m['recent'] as List : const [])) ?LoopFault.fromWire(f)],
      lastEventAt: ts(m['lastEventAt']),
      lastVerifyAt: ts(m['lastVerifyAt']),
      lastReadAt: ts(m['lastReadAt']),
    );
  }
}

class LoopStatus {
  const LoopStatus({
    this.config = const LoopConfig(),
    this.a11yEnabled = false,
    this.a11yConnected = false,
    this.eventsSeen = 0,
    this.readings = 0,
    this.lastForeground,
    this.storageError,
    this.recoveredFrom,
    this.insideTarget = false,
    this.triggersToday = 0,
    this.triggerDay,
    this.diagnostics = const LoopDiagnosticsView(),
  });

  final LoopConfig config;
  final bool a11yEnabled;
  final bool a11yConnected;
  final int eventsSeen;
  final int readings;
  final String? lastForeground;
  final String? storageError;

  /// Non-null when the state file was damaged and the backup was loaded instead.
  final String? recoveredFrom;
  final bool insideTarget;

  /// Triggers spent today, and which day that was.
  final int triggersToday;
  final String? triggerDay;

  final LoopDiagnosticsView diagnostics;

  static LoopStatus fromWire(Object? raw) {
    final m = raw is Map ? raw : const {};
    int n(Object? v) => v is num ? v.toInt() : 0;
    String? s(Object? v) => v is String ? v : null;
    return LoopStatus(
      config: LoopConfig.fromWire(m['config']),
      a11yEnabled: m['a11yEnabled'] == true,
      a11yConnected: m['a11yConnected'] == true,
      eventsSeen: n(m['eventsSeen']),
      readings: n(m['readings']),
      lastForeground: s(m['lastForeground']),
      storageError: s(m['storageError']),
      recoveredFrom: s(m['recoveredFrom']),
      insideTarget: m['insideTarget'] == true,
      triggersToday: n(m['triggersToday']),
      triggerDay: s(m['triggerDay']),
      diagnostics: LoopDiagnosticsView.fromWire(m['diagnostics']),
    );
  }
}

/// One receipt: the shared event record (eventId, source, timestamp, state, result, error).
class LoopEvent {
  const LoopEvent({
    required this.eventId,
    required this.source,
    required this.timestamp,
    required this.state,
    this.result,
    this.error,
    this.reason,
    this.packageName,
    this.visitId,
    this.surface,
    this.updatedAt,
  });

  final String eventId;
  final String source;
  final DateTime timestamp;

  /// CONFIRMED / TRIGGERED / SHOWN / CLOSED.
  final String state;

  /// RETURNED / BACK / DISMISSED / TIMEOUT / ABANDONED / UNAVAILABLE / SUPPRESSED / ERROR; null while open.
  final String? result;
  final String? error;
  final String? reason;
  final String? packageName;

  /// One visit = one stay in the test app; at most one trigger per visit.
  final String? visitId;

  /// "activity" or "notification" (T3's fallback surface).
  final String? surface;
  final DateTime? updatedAt;

  /// For the JSON export: the same fields the native receipt carries.
  Map<String, Object?> toMap() => {
    'eventId': eventId,
    'source': source,
    'timestamp': timestamp.millisecondsSinceEpoch,
    'state': state,
    'result': result,
    'error': error,
    'reason': reason,
    'packageName': packageName,
    'visitId': visitId,
    'surface': surface,
    'updatedAt': updatedAt?.millisecondsSinceEpoch,
  };

  static LoopEvent? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final id = raw['eventId'];
    final ts = raw['timestamp'];
    if (id is! String || ts is! num) return null;
    String? s(Object? v) => v is String ? v : null;
    final up = raw['updatedAt'];
    return LoopEvent(
      eventId: id,
      source: s(raw['source']) ?? '',
      timestamp: DateTime.fromMillisecondsSinceEpoch(ts.toInt()),
      state: s(raw['state']) ?? '',
      result: s(raw['result']),
      error: s(raw['error']),
      reason: s(raw['reason']),
      packageName: s(raw['packageName']),
      visitId: s(raw['visitId']),
      surface: s(raw['surface']),
      updatedAt: up is num ? DateTime.fromMillisecondsSinceEpoch(up.toInt()) : null,
    );
  }
}

class LoopApp {
  const LoopApp(this.package, this.label);

  final String package;
  final String label;
}

class LoopChannel {
  LoopChannel({MethodChannel? methods}) : _methods = methods ?? const MethodChannel('dsh/loop');

  final MethodChannel _methods;

  /// Null when the native side is missing or failed.
  Future<LoopStatus?> status() async {
    try {
      return LoopStatus.fromWire(await _methods.invokeMethod<Object?>('status'));
    } on Exception {
      return null;
    }
  }

  /// Only the given fields change. [clearTarget] sets the test app to none,
  /// [clearQuietHours] removes the quiet window.
  Future<LoopConfig?> setConfig({
    bool? enabled,
    String? targetPackage,
    bool clearTarget = false,
    int? cooldownMs,
    int? timeoutMs,
    int? quietStartMinute,
    int? quietEndMinute,
    bool clearQuietHours = false,
    int? dailyCap,
  }) async {
    try {
      final raw = await _methods.invokeMethod<Object?>('setConfig', {
        'enabled': ?enabled,
        if (targetPackage != null || clearTarget) 'targetPackage': targetPackage,
        'cooldownMs': ?cooldownMs,
        'timeoutMs': ?timeoutMs,
        if (clearQuietHours) ...{'quietStartMinute': null, 'quietEndMinute': null}
        else if (quietStartMinute != null || quietEndMinute != null) ...{
          'quietStartMinute': ?quietStartMinute,
          'quietEndMinute': ?quietEndMinute,
        },
        'dailyCap': ?dailyCap,
      });
      return LoopConfig.fromWire(raw);
    } on Exception {
      return null;
    }
  }

  Future<List<LoopEvent>> events({int limit = 50}) async {
    try {
      final raw = await _methods.invokeMethod<List<Object?>>('events', {'limit': limit});
      return [for (final e in raw ?? const []) ?LoopEvent.fromWire(e)];
    } on Exception {
      return const [];
    }
  }

  Future<bool> clearEvents() async {
    try {
      return await _methods.invokeMethod<bool>('clearEvents') ?? false;
    } on Exception {
      return false;
    }
  }

  Future<bool> clearDiagnostics() async {
    try {
      return await _methods.invokeMethod<bool>('clearDiagnostics') ?? false;
    } on Exception {
      return false;
    }
  }

  Future<List<LoopApp>> apps() async {
    try {
      final raw = await _methods.invokeMethod<List<Object?>>('apps');
      return [
        for (final a in raw ?? const [])
          if (a is Map && a['package'] is String) LoopApp(a['package'] as String, (a['label'] as String?) ?? a['package'] as String),
      ];
    } on Exception {
      return const [];
    }
  }
}
