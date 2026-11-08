// D3: the Dart side of the proactive kernel — status, config and receipts.
//
// Same shape as lib/loop/loop_channel.dart: every field optional on the wire, so a native version
// skew shows as a missing value rather than a crash. Nothing here decides anything; the kernel and
// the scheduler own every rule, and this file only moves maps.

import 'package:flutter/services.dart';

/// `SchedulerConfig` as the settings page edits it. The kernel clamps on its side too.
class ProactiveConfig {
  const ProactiveConfig({
    this.enabled = false,
    this.minIntervalMs = 45 * 60000,
    this.maxIntervalMs = 180 * 60000,
    this.quietStartMinute = 23 * 60,
    this.quietEndMinute = 8 * 60,
    this.dailyMax = 6,
    this.pauseAfterIgnored = 3,
  });

  final bool enabled;

  /// The random gap between two attempts lives in [minIntervalMs, maxIntervalMs].
  final int minIntervalMs;
  final int maxIntervalMs;

  /// Quiet hours as minutes of the local day; equal values switch them off, crossing midnight is fine.
  final int quietStartMinute;
  final int quietEndMinute;
  final int dailyMax;

  /// After this many confirmed-and-ignored in a row the scheduler pauses until the person shows up.
  final int pauseAfterIgnored;

  bool get quietSet => quietStartMinute != quietEndMinute;

  ProactiveConfig copyWith({
    bool? enabled,
    int? minIntervalMs,
    int? maxIntervalMs,
    int? quietStartMinute,
    int? quietEndMinute,
    int? dailyMax,
    int? pauseAfterIgnored,
  }) => ProactiveConfig(
    enabled: enabled ?? this.enabled,
    minIntervalMs: minIntervalMs ?? this.minIntervalMs,
    maxIntervalMs: maxIntervalMs ?? this.maxIntervalMs,
    quietStartMinute: quietStartMinute ?? this.quietStartMinute,
    quietEndMinute: quietEndMinute ?? this.quietEndMinute,
    dailyMax: dailyMax ?? this.dailyMax,
    pauseAfterIgnored: pauseAfterIgnored ?? this.pauseAfterIgnored,
  );

  static ProactiveConfig fromWire(Object? raw) {
    final m = raw is Map ? raw : const {};
    int n(Object? v, int d) => v is num ? v.toInt() : d;
    return ProactiveConfig(
      enabled: m['enabled'] == true,
      minIntervalMs: n(m['minIntervalMs'], 45 * 60000),
      maxIntervalMs: n(m['maxIntervalMs'], 180 * 60000),
      quietStartMinute: n(m['quietStartMinute'], 23 * 60),
      quietEndMinute: n(m['quietEndMinute'], 8 * 60),
      dailyMax: n(m['dailyMax'], 6),
      pauseAfterIgnored: n(m['pauseAfterIgnored'], 3),
    );
  }

  Map<String, Object?> toMap() => {
    'enabled': enabled,
    'minIntervalMs': minIntervalMs,
    'maxIntervalMs': maxIntervalMs,
    'quietStartMinute': quietStartMinute,
    'quietEndMinute': quietEndMinute,
    'dailyMax': dailyMax,
    'pauseAfterIgnored': pauseAfterIgnored,
  };
}

/// One receipt of the kernel: what was asked, how far it got, and how the person's side ended.
class ProactiveReceipt {
  const ProactiveReceipt({
    required this.eventId,
    required this.kind,
    required this.stage,
    this.source,
    this.outcome,
    this.reason,
    this.text,
    this.surface,
    this.createdAt,
    this.acceptedAt,
    this.generatedAt,
    this.deliveredAt,
    this.shownAt,
    this.respondedAt,
    this.closedAt,
  });

  final String eventId;
  final String kind;
  final String stage;
  final String? source;

  /// RESPONDED / DISMISSED / IGNORED_SEEN / UNCONFIRMED / NOT_DELIVERED.
  final String? outcome;
  final String? reason;
  final String? text;

  /// activity / notification; null when nothing was shown yet.
  final String? surface;

  /// The six moments the kernel keeps apart: accepted, generated, delivered, shown, responded, closed.
  final DateTime? createdAt;
  final DateTime? acceptedAt;
  final DateTime? generatedAt;
  final DateTime? deliveredAt;
  final DateTime? shownAt;
  final DateTime? respondedAt;
  final DateTime? closedAt;

  static ProactiveReceipt? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final request = raw['request'];
    if (request is! Map) return null;
    final id = request['eventId'];
    if (id is! String) return null;
    DateTime? ts(Object? v) => v is num ? DateTime.fromMillisecondsSinceEpoch(v.toInt()) : null;
    String? s(Object? v) => v is String ? v : null;
    final content = raw['content'];
    return ProactiveReceipt(
      eventId: id,
      kind: s(request['kind']) ?? '',
      stage: s(raw['stage']) ?? '',
      source: s(request['source']),
      outcome: s(raw['outcome']),
      reason: s(raw['reason']),
      text: content is Map ? s(content['text']) : null,
      surface: s(raw['surface']),
      createdAt: ts(request['createdAt']),
      acceptedAt: ts(raw['agentAcceptedAt']),
      generatedAt: ts(raw['generatedAt']),
      deliveredAt: ts(raw['deliveredAt']),
      shownAt: ts(raw['shownAt']),
      respondedAt: ts(raw['respondedAt']),
      closedAt: ts(raw['closedAt']),
    );
  }
}

/// `status`: what holds the kernel back right now, and what it can do on this phone.
class ProactiveStatus {
  const ProactiveStatus({
    this.config = const ProactiveConfig(),
    this.holdReason,
    this.surface,
    this.wakeMode = 'OPPORTUNISTIC',
    this.alarmError,
    this.transport = false,
    this.queued = 0,
    this.storageError,
    this.recoveryIssue,
    this.activeStage,
    this.capabilities = const {},
  });

  final ProactiveConfig config;

  /// Why a pending event is not on screen yet (`dsh_task`, `dsh_prompt`, `locked`, …); null = free.
  final String? holdReason;
  final String? surface;

  /// EXACT / INEXACT / OPPORTUNISTIC — OPPORTUNISTIC means no alarm at all on this phone.
  final String wakeMode;
  final String? alarmError;

  /// Whether the Dart Agent transport is attached.
  final bool transport;
  final int queued;
  final String? storageError;
  final String? recoveryIssue;
  final String? activeStage;

  /// P7: a11y / overlay / notifications / exactAlarm / battery — what a new install still needs.
  final Map<String, bool> capabilities;

  /// True when the system refuses background wakeups, so a message only happens while DSH is open.
  bool get wakeRestricted => wakeMode == 'OPPORTUNISTIC';

  static ProactiveStatus fromWire(Object? raw) {
    final m = raw is Map ? raw : const {};
    String? s(Object? v) => v is String ? v : null;
    final gate = m['gate'];
    return ProactiveStatus(
      config: ProactiveConfig.fromWire(m['config']),
      holdReason: gate is Map ? s(gate['hold']) : null,
      surface: gate is Map ? s(gate['surface']) : null,
      wakeMode: s(m['wakeMode']) ?? 'OPPORTUNISTIC',
      alarmError: s(m['alarmError']),
      transport: m['transport'] == true,
      queued: m['queued'] is num ? (m['queued'] as num).toInt() : 0,
      storageError: s(m['storageError']),
      recoveryIssue: s(m['recoveryIssue']),
      activeStage: m['active'] is Map ? s((m['active'] as Map)['stage']) : null,
      capabilities: _bools(m['capabilities']),
    );
  }

  static Map<String, bool> _bools(Object? raw) {
    if (raw is! Map) return const {};
    return {
      for (final e in raw.entries)
        if (e.key is String && e.value is bool) e.key as String: e.value as bool,
    };
  }
}

class ProactiveChannel {
  ProactiveChannel({MethodChannel? methods}) : _methods = methods ?? const MethodChannel('dsh/proactive');

  final MethodChannel _methods;

  Future<ProactiveStatus?> status() async {
    try {
      return ProactiveStatus.fromWire(await _methods.invokeMethod<Object?>('status'));
    } on Exception {
      return null;
    }
  }

  Future<ProactiveConfig?> setConfig(ProactiveConfig config) async {
    try {
      return ProactiveConfig.fromWire(await _methods.invokeMethod<Object?>('setConfig', config.toMap()));
    } on Exception {
      return null;
    }
  }

  Future<List<ProactiveReceipt>> receipts({int limit = 50}) async {
    try {
      final raw = await _methods.invokeMethod<List<Object?>>('receipts', {'limit': limit});
      return [for (final r in raw ?? const []) ?ProactiveReceipt.fromWire(r)];
    } on Exception {
      return const [];
    }
  }

  /// "The person sent a message": the scheduler steps back for a while.
  Future<bool> userActivity() async {
    try {
      return await _methods.invokeMethod<bool>('userActivity') ?? false;
    } on Exception {
      return false;
    }
  }

  /// 「现在试一次」: one forced evaluation; bypasses quiet/daily/gap, still refuses a busy desk.
  Future<bool> tickNow() async {
    try {
      return await _methods.invokeMethod<bool>('tickNow') ?? false;
    } on Exception {
      return false;
    }
  }

  /// Opens the native focus-supervisor screen (5s pull-back, task, pause/end).
  Future<bool> openFocusControl() async {
    try {
      return await _methods.invokeMethod<bool>('focusControl') ?? false;
    } on Exception {
      return false;
    }
  }

  /// P7: opens the system page for one missing capability (a11y / overlay / notifications / …).
  Future<bool> openCapability(String kind) async {
    try {
      return await _methods.invokeMethod<bool>('openCapability', {'kind': kind}) ?? false;
    } on Exception {
      return false;
    }
  }

  /// The plugin's live sessions and the configured target (`GET /dsh-go-companion/v1/status`).
  ///
  /// Carried over the same authenticated connection D1 uses. The answer is parsed here only to
  /// display it; nothing in this class decides whether a session may be used — the plugin refuses
  /// missing, archived and busy agents itself, and this page never creates one.
  Future<ProactiveAgentSessions> agentSessions() async {
    try {
      final raw = await _methods.invokeMethod<Object?>('agentSessions');
      return ProactiveAgentSessions.fromWire(raw);
    } on Exception catch (e) {
      return ProactiveAgentSessions(error: e.runtimeType.toString());
    }
  }

  /// Changes the companion's own settings (`POST /dsh-go-companion/v1/config`): mode, gaps, caps,
  /// quiet hours. The plugin validates and clamps every field; this only forwards the patch.
  Future<bool> setCompanionConfig(Map<String, Object?> patch) async {
    try {
      final raw = await _methods.invokeMethod<Object?>('companionConfig', patch);
      return raw is Map && raw['error'] == null;
    } on Exception {
      return false;
    }
  }

  /// 应用管理页的「Gemini 互动」：开 / 关一场连麦舱（插件 `POST /session`）。
  ///
  /// 返回进行中的 session（`topic` / `minutes` / `mode` / `until` / `allow`），失败返回 null。
  Future<Map<String, Object?>?> geminiSession({
    required String action,
    String? topic,
    int minutes = 30,
    String? mode,
  }) async {
    try {
      final raw = await _methods.invokeMethod<Object?>('geminiSession', {
        'action': action,
        if (topic != null && topic.isNotEmpty) 'topic': topic,
        'minutes': minutes,
        if (mode != null && mode.isNotEmpty) 'mode': mode,
      });
      if (raw is! Map) return null;
      if (raw['error'] is Map) return null;
      final session = raw['session'];
      if (session is Map) {
        return {for (final e in session.entries) if (e.key is String) e.key as String: e.value};
      }
      // end 的成功回包是 {ok: true, session: null}：给调用方一个"成功但没有场次"的信号。
      return raw.containsKey('session') ? const <String, Object?>{} : null;
    } on Exception {
      return null;
    }
  }

  /// Chooses the target session (`POST /dsh-go-proactive/v1/config`). Null clears it.
  Future<ProactiveAgentSessions> setAgentSession(String? sessionId) async {
    try {
      final raw = await _methods.invokeMethod<Object?>('agentSessionSet', {'sessionId': sessionId});
      return ProactiveAgentSessions.fromWire(raw);
    } on Exception catch (e) {
      return ProactiveAgentSessions(error: e.runtimeType.toString());
    }
  }
}

/// The host plugin's own view: which session proactive turns go to, and which agents are alive.
///
/// Read over the same authenticated connection D1 uses, because only the plugin knows which
/// sessions exist and which are idle. Nothing here creates a session: the plugin refuses to, by
/// design (AGENT_PROTOCOL.md), and the page only picks among live ones.
class ProactiveAgentSessions {
  const ProactiveAgentSessions({
    this.sessionId,
    this.agents = const [],
    this.error,
    this.today = const {},
    this.lastTopicId,
    this.memoryExists = false,
    this.judgeProvider,
    this.judgeModel,
    this.timings = const {},
    this.config = const {},
    this.geminiSession,
  });

  /// The companion's own settings as `/status` reports them (mode, minGapMs, coach*, caps, quiet…).
  final Map<String, Object?> config;

  /// The configured target; null means "not chosen yet", and then no proactive turn happens.
  final String? sessionId;

  /// Live root agents: id and status (`idle` / `running`).
  final List<ProactiveAgentRef> agents;

  /// Why the list could not be read (no plugin, no connection, unparseable answer).
  final String? error;

  /// Today's ledger counts from dsh-go-companion: accepted / generated / replied / substantive / none.
  final Map<String, int> today;

  /// The last topic that was actually generated to (24h topic cooldown).
  final String? lastTopicId;

  /// Whether `fish-memory.sqlite` exists for the selected session.
  final bool memoryExists;

  /// P6: the separately configured judge model; null = same as the session.
  final String? judgeProvider;
  final String? judgeModel;

  /// P6: median milliseconds of the last 10 rounds — generate / judge / chat.
  final Map<String, int> timings;

  /// 进行中的「Gemini 互动」连麦舱（`/status` 的 `gemini.session`）；null = 没开。
  final Map<String, Object?>? geminiSession;

  bool get geminiRunning => geminiSession != null;

  bool get ready => error == null;

  static ProactiveAgentSessions fromWire(Object? raw) {
    if (raw is! Map) return const ProactiveAgentSessions(error: 'no_answer');
    final error = raw['error'];
    if (error is Map && error['code'] != null) {
      return ProactiveAgentSessions(error: '${error['code']}');
    }
    final agents = raw['agents'];
    final today = raw['today'];
    final memory = raw['memory'];
    final timings = raw['timings'];
    final counts = <String, int>{};
    if (today is Map) {
      for (final e in today.entries) {
        if (e.key is String && e.value is num) counts[e.key as String] = (e.value as num).toInt();
      }
    }
    final medians = <String, int>{};
    if (timings is Map) {
      for (final e in timings.entries) {
        if (e.key is String && e.value is num) medians[e.key as String] = (e.value as num).toInt();
      }
    }
    Map<String, Object?>? geminiSession;
    final gemini = raw['gemini'];
    if (gemini is Map) {
      final s = gemini['session'];
      if (s is Map) {
        geminiSession = {
          for (final e in s.entries)
            if (e.key is String) e.key as String: e.value,
        };
      }
    }
    return ProactiveAgentSessions(
      sessionId: raw['sessionId'] is String ? raw['sessionId'] as String : null,
      agents: [
        for (final a in (agents is List ? agents : const []))
          if (a is Map && a['id'] is String)
            ProactiveAgentRef(a['id'] as String, a['status'] is String ? a['status'] as String : 'unknown'),
      ],
      today: counts,
      lastTopicId: raw['lastTopicId'] is String ? raw['lastTopicId'] as String : null,
      memoryExists: memory is Map && memory['exists'] == true,
      judgeProvider: raw['judgeProvider'] is String ? raw['judgeProvider'] as String : null,
      judgeModel: raw['judgeModel'] is String ? raw['judgeModel'] as String : null,
      timings: medians,
      config: raw['config'] is Map ? Map<String, Object?>.from(raw['config'] as Map) : const {},
      geminiSession: geminiSession,
    );
  }
}

class ProactiveAgentRef {
  const ProactiveAgentRef(this.id, this.status);

  final String id;

  /// `idle` or `running`; a running agent is refused by the plugin (409 agent-busy).
  final String status;

  bool get idle => status == 'idle';
}
