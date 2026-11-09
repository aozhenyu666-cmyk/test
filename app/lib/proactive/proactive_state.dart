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

  /// 应用管理页的「墙」：只读地读一眼规则现在的执行状态（插件 `POST /wall`）。
  ///
  /// 返回 `locked` / `kind` / `openUntil` / `why` / `relaxRemaining` / `relaxToday` /
  /// `tightenToday` / `applyWindowOpen` 等；失败返回 null。这里只显示，不提供任何放松入口。
  Future<Map<String, Object?>?> wallStatus() async {
    try {
      final raw = await _methods.invokeMethod<Object?>('wallStatus');
      if (raw is! Map) return null;
      if (raw['error'] is Map) return null;
      final wall = raw['wall'];
      if (wall is! Map) return null;
      return {for (final e in wall.entries) if (e.key is String) e.key as String: e.value};
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

  /// 姐妹界面（插件 `POST /sisters`）：两张角色卡、她们记得的你、最近说过的话、今日审计面板。
  ///
  /// 只读。这一页没有任何能放松规则的入口——放松只能走晚上对账后的申请窗口。
  /// 连不上返回 null，页面说「连不上」，不猜也不缓存上一次的数。
  Future<SistersView?> sisters() async {
    try {
      final raw = await _methods.invokeMethod<Object?>('sisters');
      return SistersView.fromWire(raw);
    } on Exception {
      return null;
    }
  }

  /// 每日四部门提交（插件 `POST /deposit`）。
  ///
  /// [department] 为空就是读状态；给了就是交那一段，[report] / [thought] 一起传。
  /// 字数不够插件会退回并带上错误码，界面照着说，不在这里自己判。
  Future<DepositView?> deposit({String? department, String? report, String? thought}) async {
    try {
      final raw = await _methods.invokeMethod<Object?>('deposit', {
        if (department != null) 'action': 'submit',
        if (department != null) 'department': department,
        if (report != null) 'report': report,
        if (thought != null) 'thought': thought,
      });
      return DepositView.fromWire(raw);
    } on Exception {
      return null;
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

/// 一张角色卡：妹妹（`companion`）或姐姐（`strategist`）。墙不是角色卡。
class SistersCard {
  const SistersCard({required this.role, required this.name, required this.line, required this.persona});

  final String role;
  final String name;

  /// 一句话定位，卡面正面就显示这一句。
  final String line;

  /// 固定个人设定，展开后显示。
  final String persona;

  static SistersCard? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final role = raw['role'];
    if (role is! String || role.isEmpty) return null;
    String str(Object? v) => v is String ? v : '';
    final name = str(raw['name']);
    return SistersCard(
      role: role,
      name: name.isEmpty ? role : name,
      line: str(raw['line']),
      persona: str(raw['persona']),
    );
  }
}

/// 面板上的一行：「她们记得的你」或「最近说过的话」。文本由插件拼好，这里只显示。
class SistersNote {
  const SistersNote({required this.title, this.subtitle = '', this.tone = ''});

  final String title;
  final String subtitle;

  /// `good` / `warn` / `bad` / `info`；空字符串表示不染色。
  final String tone;

  static SistersNote? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final title = raw['title'];
    if (title is! String || title.trim().isEmpty) return null;
    String str(Object? v) => v is String ? v : '';
    return SistersNote(title: title, subtitle: str(raw['subtitle']), tone: str(raw['tone']));
  }
}

/// 墙的今日审计：今天立了几件、有证据完成几件，以及近 14 天完成率。
///
/// 墙只占一块灰色的面板，不做成角色卡，也不带表情。
class SistersAudit {
  const SistersAudit({
    this.day = '',
    this.promised = 0,
    this.done = 0,
    this.rateDone = 0,
    this.rateTotal = 0,
    this.ratePct = 0,
    this.rateDays = 14,
    this.text = '',
  });

  final String day;
  final int promised;
  final int done;

  /// 近 [rateDays] 天：立了多少、有证据完成多少。和墙自己报的是同一个数。
  final int rateDone;
  final int rateTotal;
  final int ratePct;
  final int rateDays;

  /// 墙今天的原话（`大肥鱼/墙/审计/<日期>.md`）；还没审过就是空。
  final String text;

  static const empty = SistersAudit();

  bool get hasToday => promised > 0 || text.isNotEmpty;
  bool get hasRate => rateTotal > 0;

  static SistersAudit fromWire(Object? raw) {
    if (raw is! Map) return empty;
    int num_(Object? v, [int fallback = 0]) => v is num ? v.round() : fallback;
    String str(Object? v) => v is String ? v : '';
    return SistersAudit(
      day: str(raw['day']),
      promised: num_(raw['promised']),
      done: num_(raw['done']),
      rateDone: num_(raw['rateDone']),
      rateTotal: num_(raw['rateTotal']),
      ratePct: num_(raw['ratePct']),
      rateDays: num_(raw['rateDays'], 14),
      text: str(raw['text']),
    );
  }
}

/// 姐妹页的全部数据（插件 `POST /sisters`）。
class SistersView {
  const SistersView({
    this.cards = const [],
    this.memory = const [],
    this.timeline = const [],
    this.audit = SistersAudit.empty,
    this.error,
  });

  final List<SistersCard> cards;

  /// 她们记得的你（共用一份记忆）。
  final List<SistersNote> memory;

  /// 最近说过的话，最新的在下。
  final List<SistersNote> timeline;

  final SistersAudit audit;

  /// 插件那边的错误码（`no_transport` / `no_answer` / `timeout` …）；null 表示正常。
  final String? error;

  bool get ok => error == null;

  static SistersView? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final err = raw['error'];
    if (err is Map) {
      final code = err['code'];
      return SistersView(error: code is String && code.isNotEmpty ? code : 'error');
    }
    final cards = <SistersCard>[];
    for (final item in _list(raw['cards'])) {
      final card = SistersCard.fromWire(item);
      if (card != null) cards.add(card);
    }
    return SistersView(
      cards: cards,
      memory: _notes(raw['memory']),
      timeline: _notes(raw['timeline']),
      audit: SistersAudit.fromWire(raw['audit']),
    );
  }

  static List<Object?> _list(Object? value) => value is List ? value : const [];

  static List<SistersNote> _notes(Object? value) {
    final out = <SistersNote>[];
    for (final item in _list(value)) {
      final note = SistersNote.fromWire(item);
      if (note != null) out.add(note);
    }
    return out;
  }
}

/// 每日四部门里的一段：信息收集 / 谋划思考 / 行为管理 / 统筹规划。
///
/// 字数下限由插件给（[minReport] / [minThought]），界面只照着显示，不自己定一套。
class DepositItem {
  const DepositItem({
    required this.key,
    required this.label,
    this.hint = '',
    this.done = false,
    this.at = 0,
    this.reportChars = 0,
    this.thoughtChars = 0,
    this.minReport = 200,
    this.minThought = 80,
  });

  final String key;
  final String label;

  /// 这一段该写什么（插件里和部门一起定义，界面不另写一份）。
  final String hint;
  final bool done;

  /// 提交时间；0 表示还没交。
  final int at;
  final int reportChars;
  final int thoughtChars;
  final int minReport;
  final int minThought;

  static DepositItem? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final key = raw['key'];
    final label = raw['label'];
    if (key is! String || key.isEmpty || label is! String) return null;
    int num_(Object? v) => v is num ? v.round() : 0;
    String str(Object? v) => v is String ? v : '';
    return DepositItem(
      key: key,
      label: label,
      hint: str(raw['hint']),
      done: raw['done'] == true,
      at: num_(raw['at']),
      reportChars: num_(raw['reportChars']),
      thoughtChars: num_(raw['thoughtChars']),
      minReport: num_(raw['minReport']) == 0 ? 200 : num_(raw['minReport']),
      minThought: num_(raw['minThought']) == 0 ? 80 : num_(raw['minThought']),
    );
  }
}

/// 今天的四部门提交状态（插件 `POST /deposit`）。
///
/// 交不齐的当天没有放风额度——这条在插件里判，界面只负责把「还缺哪几段」说清楚。
class DepositView {
  const DepositView({
    this.items = const [],
    this.done = 0,
    this.total = 4,
    this.missing = const [],
    this.missingText = '',
    this.error,
  });

  final List<DepositItem> items;
  final int done;
  final int total;
  final List<String> missing;
  final String missingText;

  /// 插件那边的错误码（`too-thin` / `bad-department` / `no_answer` …）；null 表示正常。
  final String? error;

  bool get ok => error == null;
  bool get complete => total > 0 && done >= total;

  static DepositView? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final err = raw['error'];
    final d = raw['deposit'];
    final items = <DepositItem>[];
    if (d is Map) {
      for (final item in _list(d['items'])) {
        final it = DepositItem.fromWire(item);
        if (it != null) items.add(it);
      }
    }
    final view = DepositView(
      items: items,
      done: d is Map && d['done'] is num ? (d['done'] as num).round() : items.where((i) => i.done).length,
      total: d is Map && d['total'] is num ? (d['total'] as num).round() : items.length,
      missing: [
        if (d is Map)
          for (final m in _list(d['missing']))
            if (m is String) m,
      ],
      missingText: d is Map && d['missingText'] is String ? d['missingText'] as String : '',
    );
    if (err is Map) {
      final code = err['code'];
      return DepositView(
        items: view.items,
        done: view.done,
        total: view.total,
        missing: view.missing,
        missingText: view.missingText,
        error: code is String && code.isNotEmpty ? code : 'error',
      );
    }
    return view;
  }

  static List<Object?> _list(Object? value) => value is List ? value : const [];
}
