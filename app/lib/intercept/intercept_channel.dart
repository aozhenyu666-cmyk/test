// 娱乐 APP 拦截 (round 4 hardening): the Dart half of the `dsh/intercept` channel.
//
// The kernel (loop/core/Intercept.kt + loop/intercept/) decides everything: what is a target,
// whether to block, how long a release lasts, what the 训练题 is, and what goes in 行为拦截账.
// This file is only a typed doorway to it, so the settings page never invents a rule.
//
// Rules this file keeps, because the settings page relies on them:
//  * a missing channel (desktop, tests, an older build) is [InterceptUnavailable], never an empty
//    config that would silently look like "everything is off";
//  * every returned map is read defensively — the kernel is the only writer, but a version skew
//    must degrade to a stated error rather than a crash;
//  * nothing is cached here: the kernel keeps the state, and a stale copy on this side would let
//    the page show a pause that has already run out.

import 'dart:async';

import 'package:flutter/services.dart';

/// The kernel's answer to `status`: config + counters + recent receipts + what is open now.
class InterceptStatus {
  const InterceptStatus({
    required this.config,
    required this.counts,
    required this.receipts,
    required this.active,
    this.storageError,
    this.recoveryIssue,
  });

  final InterceptConfig config;
  final InterceptCounts counts;
  final List<InterceptReceipt> receipts;

  /// The package currently covered by the block page, or null when nothing is intercepted.
  final String? active;

  /// The kernel could not write its own state. Never hidden: a lost receipt is a lost receipt.
  final String? storageError;

  /// The kernel found unreadable state at process start and started over.
  final String? recoveryIssue;

  bool get paused => config.pauseUntilWall > DateTime.now().millisecondsSinceEpoch;

  static InterceptStatus from(Object? raw) {
    final m = raw is Map ? raw : const {};
    return InterceptStatus(
      config: InterceptConfig.from(m['config']),
      counts: InterceptCounts.from(m['counts']),
      receipts: (m['receipts'] is List ? m['receipts'] as List : const [])
          .map(InterceptReceipt.from)
          .whereType<InterceptReceipt>()
          .toList(),
      active: m['active'] is String ? m['active'] as String : null,
      storageError: m['storageError'] is String ? m['storageError'] as String : null,
      recoveryIssue: m['recoveryIssue'] is String ? m['recoveryIssue'] as String : null,
    );
  }
}

/// One target app: the package the person blocked, and the name they see.
class InterceptTarget {
  const InterceptTarget({required this.packageName, required this.label});

  final String packageName;
  final String label;

  Map<String, Object?> toWire() => {'packageName': packageName, 'label': label};

  static InterceptTarget? from(Object? raw) {
    if (raw is! Map) return null;
    final p = raw['packageName'];
    if (p is! String || p.trim().isEmpty) return null;
    final l = raw['label'];
    return InterceptTarget(packageName: p, label: l is String && l.trim().isNotEmpty ? l : p);
  }

  @override
  bool operator ==(Object other) => other is InterceptTarget && other.packageName == packageName;

  @override
  int get hashCode => packageName.hashCode;
}

/// 鎷︽埅閰嶇疆, mirrored from `InterceptConfig.toMap()` — key for key, so a round trip loses nothing.
class InterceptConfig {
  const InterceptConfig({
    this.enabled = false,
    this.targets = const [],
    this.whitelist = const {},
    this.pauseUntilWall = 0,
    this.releaseMs = 10 * 60 * 1000,
    this.trainingMs = 60 * 1000,
    this.cooldownMs = 2 * 60 * 1000,
    this.dailyMax = 0,
  });

  final bool enabled;
  final List<InterceptTarget> targets;
  final Set<String> whitelist;
  final int pauseUntilWall;
  final int releaseMs;
  final int trainingMs;
  final int cooldownMs;

  /// Interceptions per local day; 0 means no limit.
  final int dailyMax;

  InterceptConfig copyWith({
    bool? enabled,
    List<InterceptTarget>? targets,
    Set<String>? whitelist,
    int? pauseUntilWall,
    int? releaseMs,
    int? trainingMs,
    int? cooldownMs,
    int? dailyMax,
  }) =>
      InterceptConfig(
        enabled: enabled ?? this.enabled,
        targets: targets ?? this.targets,
        whitelist: whitelist ?? this.whitelist,
        pauseUntilWall: pauseUntilWall ?? this.pauseUntilWall,
        releaseMs: releaseMs ?? this.releaseMs,
        trainingMs: trainingMs ?? this.trainingMs,
        cooldownMs: cooldownMs ?? this.cooldownMs,
        dailyMax: dailyMax ?? this.dailyMax,
      );

  Map<String, Object?> toWire() => {
        'enabled': enabled,
        'targets': targets.map((t) => t.toWire()).toList(),
        'whitelist': whitelist.toList(),
        'pauseUntilWall': pauseUntilWall,
        'releaseMs': releaseMs,
        'trainingMs': trainingMs,
        'cooldownMs': cooldownMs,
        'dailyMax': dailyMax,
      };

  static InterceptConfig from(Object? raw) {
    final m = raw is Map ? raw : const {};
    int intOf(String k, int fallback) {
      final v = m[k];
      if (v is int) return v;
      if (v is num) return v.toInt();
      return fallback;
    }

    return InterceptConfig(
      enabled: m['enabled'] == true,
      targets: (m['targets'] is List ? m['targets'] as List : const [])
          .map(InterceptTarget.from)
          .whereType<InterceptTarget>()
          .toList(),
      whitelist: (m['whitelist'] is List ? m['whitelist'] as List : const [])
          .whereType<String>()
          .toSet(),
      pauseUntilWall: intOf('pauseUntilWall', 0),
      releaseMs: intOf('releaseMs', 10 * 60 * 1000),
      trainingMs: intOf('trainingMs', 60 * 1000),
      cooldownMs: intOf('cooldownMs', 2 * 60 * 1000),
      dailyMax: intOf('dailyMax', 0),
    );
  }

  /// The 行为拦截账 counters. Separate from 主动交流账 on purpose — see ROUND4_ACK_AND_INTERCEPT.md.
  static const empty = InterceptConfig();
}

class InterceptCounts {
  const InterceptCounts({
    this.triggerCount = 0,
    this.blockedCount = 0,
    this.userReleasedCount = 0,
    this.userExitedCount = 0,
    this.pausedCount = 0,
  });

  final int triggerCount;
  final int blockedCount;
  final int userReleasedCount;
  final int userExitedCount;
  final int pausedCount;

  static InterceptCounts from(Object? raw) {
    final m = raw is Map ? raw : const {};
    int intOf(String k) {
      final v = m[k];
      if (v is int) return v;
      if (v is num) return v.toInt();
      return 0;
    }

    return InterceptCounts(
      triggerCount: intOf('triggerCount'),
      blockedCount: intOf('blockedCount'),
      userReleasedCount: intOf('userReleasedCount'),
      userExitedCount: intOf('userExitedCount'),
      pausedCount: intOf('pausedCount'),
    );
  }
}

/// One line of 行为拦截账: how one interception ended, and why.
class InterceptReceipt {
  const InterceptReceipt({
    required this.eventId,
    required this.packageName,
    required this.outcome,
    required this.triggeredAt,
    required this.endedAt,
    this.reason,
    this.releasedBy,
  });

  final String eventId;
  final String packageName;

  /// `BLOCKED` (still up / lost with the process), `RELEASED`, `EXITED`, `PAUSED`, `NOT_SHOWN`.
  final String outcome;
  final int triggeredAt;
  final int endedAt;
  final String? reason;
  final String? releasedBy;

  static InterceptReceipt? from(Object? raw) {
    if (raw is! Map) return null;
    final id = raw['eventId'];
    if (id is! String) return null;
    int intOf(String k) {
      final v = raw[k];
      if (v is int) return v;
      if (v is num) return v.toInt();
      return 0;
    }

    return InterceptReceipt(
      eventId: id,
      packageName: raw['packageName'] is String ? raw['packageName'] as String : '',
      outcome: raw['outcome'] is String ? raw['outcome'] as String : '',
      triggeredAt: intOf('triggeredAt'),
      endedAt: intOf('endedAt'),
      reason: raw['reason'] is String ? raw['reason'] as String : null,
      releasedBy: raw['releasedBy'] is String ? raw['releasedBy'] as String : null,
    );
  }
}

/// The channel is missing: desktop, a test, or a build without the Android host.
class InterceptUnavailable implements Exception {
  const InterceptUnavailable(this.reason);
  final String reason;

  @override
  String toString() => 'InterceptUnavailable: $reason';
}

/// The kernel refused the call or sent something this build cannot read.
class InterceptFailed implements Exception {
  const InterceptFailed(this.op, this.cause);
  final String op;
  final String cause;

  @override
  String toString() => 'InterceptFailed($op): $cause';
}

/// The typed doorway to `dsh/intercept`. One instance per page is fine; nothing is cached here.
class InterceptChannel {
  InterceptChannel({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel('dsh/intercept');

  final MethodChannel _channel;

  Future<T> _call<T>(String op, [Map<String, Object?>? args]) async {
    try {
      final raw = await _channel.invokeMethod<Object?>(op, args);
      return raw as T;
    } on MissingPluginException {
      throw const InterceptUnavailable('this build has no dsh/intercept host');
    } on PlatformException catch (e) {
      throw InterceptFailed(op, e.message ?? e.code);
    }
  }

  /// Whether this build has the host at all. The settings page asks once, before it draws anything.
  Future<bool> available() async {
    try {
      await _channel.invokeMethod<Object?>('status');
      return true;
    } on MissingPluginException {
      return false;
    } catch (_) {
      // Any other failure means the channel exists but the kernel is unhappy; the page will show
      // the error from status() itself rather than pretending there is no host.
      return true;
    }
  }

  Future<InterceptStatus> status() async =>
      InterceptStatus.from(await _call<Object?>('status'));

  /// A partial write: only the keys given are merged onto the kernel's current config.
  Future<InterceptStatus> setConfig(InterceptConfig config) async =>
      InterceptStatus.from(await _call<Object?>('setConfig', config.toWire()));

  /// Merge a few keys only — what every toggle on the settings page uses.
  Future<InterceptStatus> patch(Map<String, Object?> changes) async =>
      InterceptStatus.from(await _call<Object?>('setConfig', changes));

  Future<List<InterceptReceipt>> receipts({int limit = 50}) async {
    final raw = await _call<Object?>('receipts', {'limit': limit});
    return (raw is List ? raw : const [])
        .map(InterceptReceipt.from)
        .whereType<InterceptReceipt>()
        .toList();
  }

  /// Pause from the settings page (the kernel pauses everything for its default window).
  /// The channel answers with the guard's boolean, so the page re-reads status() afterwards.
  Future<bool> pause() async => await _call<Object?>('pause') == true;

  Future<InterceptStatus> resume() async =>
      InterceptStatus.from(await _call<Object?>('resume'));
}
