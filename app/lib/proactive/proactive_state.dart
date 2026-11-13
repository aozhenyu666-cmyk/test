// D3: the Dart side of the proactive kernel — status, config and receipts.
//
// Same shape as lib/loop/loop_channel.dart: every field optional on the wire, so a native version
// skew shows as a missing value rather than a crash. Nothing here decides anything; the kernel and
// the scheduler own every rule, and this file only moves maps.

import 'package:flutter/services.dart';

import '../companion/bout_voice.dart';

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

  /// B5 试听：让手机用系统 TTS 念一句（`voiceTry`）。
  ///
  /// 返回 null = 真的念完了；否则是降级原因（`no-tts` / `dnd` / `muted` / `session`）。
  /// 它绕开插件的总开关（关了也能试），因为试听就是用来在开之前确认手机上能不能出声的。
  Future<String?> tryVoice() async {
    try {
      return await _methods.invokeMethod<String>('voiceTry');
    } on Exception {
      return 'no-channel';
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

  /// A3：墙的审查记录（只读）。后端 `GET /wall/thread`。
  ///
  /// 墙不聊天：这里拿到的是已经落账的提交 / 判定 / 规则变更，按时间倒序。
  /// 失败返回 null（页面如实显示「读不到」），不编一个空记录出来。
  Future<WallThread?> wallThread() async {
    try {
      final raw = await _methods.invokeMethod<Object?>('wallThread');
      if (raw is! Map) return null;
      if (raw['error'] is Map) return null;
      return WallThread.fromWire(raw['thread']);
    } on Exception {
      return null;
    }
  }

  /// 第九轮 B1：今天的计划（插件 `GET /plan/day`）。
  ///
  /// 回 `plan`（今天的计划文档）/ `min`（当前分钟）/ `recent`（最近用过的包名，给选择器排序）。
  /// 失败返回 null：页面如实显示读不到，不编一张空计划。
  Future<Map<String, Object?>?> planDay() async {
    try {
      final raw = await _methods.invokeMethod<Object?>('planDay');
      if (raw is! Map) return null;
      if (raw['error'] is Map) return null;
      return {for (final e in raw.entries) if (e.key is String) e.key as String: e.value};
    } on Exception {
      return null;
    }
  }

  /// 第九轮 B1：把今天这一份计划交上去（插件 `POST /plan/day`）。
  ///
  /// 缺「必需/允许应用」时插件回 `ok:false` + `missing`（点名是哪几局），原样带回：
  /// 校验只有插件那一处，手机不另写一份规则。
  Future<Map<String, Object?>?> planDaySet(List<Map<String, Object?>> items) async {
    try {
      final raw = await _methods.invokeMethod<Object?>('planDay', {'action': 'set', 'items': items});
      if (raw is! Map) return null;
      return {for (final e in raw.entries) if (e.key is String) e.key as String: e.value};
    } on Exception {
      return null;
    }
  }

  /// A3：申诉一条判定。后端 `POST /wall/appeal`。
  ///
  /// 只收「一条具体判定 + 新的物理证据」，每天 2 次；判还是由插件里的墙算，
  /// 所以这里既不改主意也不安慰人，只把墙回的那句话原样带回来。
  Future<WallAppeal?> wallAppeal({required int at, required String evidence, String? task}) async {
    try {
      final raw = await _methods.invokeMethod<Object?>('wallAppeal', {
        'at': at,
        'evidence': evidence,
        if (task != null) 'task': task,
      });
      if (raw is! Map) return null;
      return WallAppeal.fromWire(raw);
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

  /// 第八轮 W3：把系统分享进来的一段文字当成当前这一拍的答案（插件 `POST /bouts`）。
  ///
  /// true 表示这句已经被当前这一局接住，分享分流就不再新开对话；false 是「没有在跑的局」
  /// 或者连不上，分流照旧把文字放进新对话——分享不该因为局没开就被吞掉。
  Future<bool> shareToBout(String text) async {
    try {
      final raw = await _methods.invokeMethod<Object?>('boutsShare', {'text': text});
      return raw is Map && raw['ok'] == true;
    } on Exception {
      return false;
    }
  }

  /// 每日四部门提交（插件 `POST /deposit`）。
  /// 字数不够插件会退回并带上错误码，界面照着说，不在这里自己判。
  ///
  /// A4：白天先记一条短记录用 [text]（action=slot，一条 ≤200 字）。行为管理那一部的汇报栏
  /// 由墙按遥测生成，页面不送 `report`（送了插件也不看）。
  Future<DepositView?> deposit({String? department, String? report, String? thought, String? text, String? taskId}) async {
    try {
      final slot = text != null;
      final raw = await _methods.invokeMethod<Object?>('deposit', {
        if (slot) 'action': 'slot',
        if (!slot && department != null) 'action': 'submit',
        if (department != null) 'department': department,
        if (report != null) 'report': report,
        if (thought != null) 'thought': thought,
        if (text != null) 'text': text,
        if (taskId != null) 'taskId': taskId,
      });
      return DepositView.fromWire(raw);
    } on Exception {
      return null;
    }
  }

  /// C1：工作记忆挂载卡（插件 `POST /mount`）。
  ///
  /// [action] 为空就是只读（GET）；写操作原样透传，规矩全在插件里：
  /// 卡上每一条都得是他自己的原话、同时只有一张 open 卡、结论必须引用已知。
  /// 面板手写时 [text] 直接就是他的话（不需要 [src]，那是工具提议才要的账本行号）。
  ///
  /// 失败返回 null，页面说「读不到」，不编一张空卡出来。
  Future<MountView?> mount({
    String? action,
    String? goal,
    String? topicId,
    String? text,
    String? src,
    String? where,
    String? id,
    List<int>? from,
  }) async {
    try {
      final raw = await _methods.invokeMethod<Object?>('mount', {
        if (action != null) 'action': action,
        if (goal != null) 'goal': goal,
        if (topicId != null) 'topicId': topicId,
        if (text != null) 'text': text,
        if (src != null) 'src': src,
        if (where != null) 'where': where,
        if (id != null) 'id': id,
        if (from != null) 'from': from,
      });
      return MountView.fromWire(raw);
    } on Exception {
      return null;
    }
  }

  /// C2：四个基本操作（插件 `/drill-op`）。
  ///
  /// [answer] 为空就是只读（GET），读出的是链路该出的那个操作、今天的额度和近 14 天通过率；
  /// 给 [answer] 才算判卷。判的人是谁、什么算过，全在插件的代码里，这里只透传。
  /// [blind] 用于回想：卡得先遮住，遮不住就不算想起来。
  ///
  /// 失败返回 null，页面说「读不到」，不编一道题出来。
  Future<DrillOpView?> drillOp({String? op, String? answer, bool? blind}) async {
    try {
      final raw = await _methods.invokeMethod<Object?>('drillOp', {
        if (op != null) 'op': op,
        if (answer != null) 'answer': answer,
        if (blind != null) 'blind': blind,
      });
      return DrillOpView.fromWire(raw);
    } on Exception {
      return null;
    }
  }
}

/// 第八轮 J9：局官这一侧的端口（插件 `POST /bouts` 的四个 action）。
///
/// 只搬数据：哪个 action、带什么字段，由 [BoutVoiceLoop] 决定；这里不加判断，
/// 也不缓存「现在第几拍」——那份状态在插件的局官手里，手机再存一份就会跟它走散。
class ProactiveBouts implements BoutVoicePort {
  ProactiveBouts({MethodChannel? methods}) : _methods = methods ?? const MethodChannel('dsh/proactive');

  final MethodChannel _methods;

  Future<Map<Object?, Object?>?> _post(Map<String, Object?> body) async {
    try {
      final raw = await _methods.invokeMethod<Object?>('boutsVoice', body);
      return raw is Map ? raw : null;
    } on Exception {
      return null;
    }
  }

  @override
  Future<BoutAsk?> ask() async {
    final raw = await _post({'action': 'ask'});
    if (raw == null || raw['ok'] != true) return null;
    return BoutAsk(
      say: '${raw['say'] ?? ''}',
      stepId: '${raw['stepId'] ?? ''}',
      limitSec: (raw['limitSec'] as num?)?.toInt() ?? 0,
    );
  }

  @override
  Future<void> spoken(int at) => _post({'action': 'spoken', 'at': at});

  @override
  Future<BoutAnswer?> answer(String text) async {
    final raw = await _post({'action': 'answer', 'text': text});
    if (raw == null) return null;
    final state = '${raw['state'] ?? ''}';
    return BoutAnswer(
      accepted: raw['accepted'] == true,
      say: '${raw['say'] ?? ''}',
      nextAskAt: (raw['nextAskAt'] as num?)?.toInt() ?? 0,
      closed: state == 'closed' || state == 'escalated',
    );
  }

  @override
  Future<void> fallback(String reason) => _post({'action': 'fallback', 'reason': reason});
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
    this.drillOps = const {},
    this.planGate = false,
    this.error,
  });

  final List<SistersCard> cards;

  /// 她们记得的你（共用一份记忆）。
  final List<SistersNote> memory;

  /// 最近说过的话，最新的在下。
  final List<SistersNote> timeline;

  final SistersAudit audit;

  /// C2：操作 → 近 14 天通过率（`brief` / `predict` / `ask` / `recall`）。
  /// 没练过的操作也在表里，`asked` 是 0——没练过 ≠ 满分。
  final Map<String, DrillOpStat> drillOps;

  /// 第九轮 B1：今天还没有生效的计划。三个角色谁在场都是同一张「去定计划」。
  final bool planGate;

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
      drillOps: _stats(raw['drillOps']),
      planGate: raw['planGate'] is Map && (raw['planGate'] as Map)['need'] == true,
    );
  }

  static Map<String, DrillOpStat> _stats(Object? value) {
    final out = <String, DrillOpStat>{};
    if (value is! Map) return out;
    value.forEach((k, v) {
      final stat = DrillOpStat.fromWire(v);
      if (k is String && stat != null) out[k] = stat;
    });
    return out;
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
    this.slots = 0,
    this.duty = '',
    this.evidence = '',
    this.autoReport = false,
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

  /// A4：今天这个部记了几条日间记录（白天的短记录，晚上写汇报时对着它写）。
  final int slots;

  /// A4：这一部该记什么、证据从哪来（插件 `DEPT_DUTY` 那一张表）。
  final String duty;
  final String evidence;

  /// A4：汇报栏是不是墙自动填的（行为管理）。是的话页面不给他编辑汇报内容。
  final bool autoReport;

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
      slots: num_(raw['slots']),
      duty: str(raw['duty']),
      evidence: str(raw['evidence']),
      autoReport: raw['autoReport'] == true,
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

/// A3：审查记录里的一条气泡。只有三种：提交、判定、规则变更。
///
/// 它不是聊天消息：`who` 只有「他 / 姐妹 / 墙」，`text` 是墙的模板话或者提交的事名，
/// 没有「正在输入」也没有「她说……」。界面据此分三种排版。
class WallBubble {
  const WallBubble({
    required this.kind,
    required this.at,
    required this.who,
    required this.text,
    this.pass,
  });

  /// `submit` / `verdict` / `rule`。插件那边也是这三个，多一个就说明有人加了新气泡类型。
  final String kind;
  final int at;
  final String who;
  final String text;

  /// 判定的结论：true 过、false 不过、null 不是判定（提交和规则变更都是 null）。
  final bool? pass;

  bool get isSubmit => kind == 'submit';
  bool get isVerdict => kind == 'verdict';
  bool get isRule => kind == 'rule';

  static WallBubble? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final kind = raw['kind'];
    final text = raw['text'];
    if (kind is! String || kind.isEmpty) return null;
    return WallBubble(
      kind: kind,
      at: raw['at'] is num ? (raw['at'] as num).toInt() : 0,
      who: raw['who'] is String ? raw['who'] as String : '',
      text: text is String ? text : '',
      pass: raw['pass'] is bool ? raw['pass'] as bool : null,
    );
  }
}

/// A3：一次 `GET /wall/thread` 的结果。
class WallThread {
  const WallThread({
    this.bubbles = const [],
    this.kinds = const ['submit', 'verdict', 'rule'],
    this.appealLeft = 0,
    this.appealMax = 2,
  });

  final List<WallBubble> bubbles;

  /// 插件认的气泡类型（界面拿它做断言用，不用自己写死一份）。
  final List<String> kinds;

  /// 今天还能申诉几次。
  final int appealLeft;
  final int appealMax;

  bool get canAppeal => appealLeft > 0;
  bool get isEmpty => bubbles.isEmpty;

  static WallThread? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final bubbles = <WallBubble>[];
    final src = raw['bubbles'];
    if (src is List) {
      for (final b in src) {
        final one = WallBubble.fromWire(b);
        if (one != null) bubbles.add(one);
      }
    }
    final kinds = <String>[];
    final k = raw['kinds'];
    if (k is List) {
      for (final x in k) {
        if (x is String) kinds.add(x);
      }
    }
    return WallThread(
      bubbles: bubbles,
      kinds: kinds.isEmpty ? const ['submit', 'verdict', 'rule'] : kinds,
      appealLeft: raw['appealLeft'] is num ? (raw['appealLeft'] as num).toInt() : 0,
      appealMax: raw['appealMax'] is num ? (raw['appealMax'] as num).toInt() : 2,
    );
  }
}

/// A3：一次申诉的结果。`code` 是插件那边的申诉码，`text` 是墙的原话（模板生成，不改一个字）。
class WallAppeal {
  const WallAppeal({this.code, this.pass, this.text = '', this.left = 0, this.error});

  final String? code;
  final bool? pass;
  final String text;
  final int left;

  /// 插件退回来的错误码（`appeal:no-quota` / `appeal:no-new-evidence` / `appeal:not-verdict`）。
  final String? error;

  bool get ok => error == null;

  static WallAppeal? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final err = raw['error'];
    final a = raw['appeal'];
    final code = a is Map && a['code'] is String ? a['code'] as String : null;
    final text = a is Map && a['text'] is String ? a['text'] as String : '';
    final left = a is Map && a['left'] is num ? (a['left'] as num).toInt() : 0;
    if (err is Map) {
      final ec = err['code'];
      final msg = err['message'];
      return WallAppeal(
        error: ec is String && ec.isNotEmpty ? ec : 'error',
        text: msg is String ? msg : '',
      );
    }
    return WallAppeal(code: code, pass: a is Map && a['pass'] is bool ? a['pass'] as bool : null, text: text, left: left);
  }
}

/// C1：挂载卡上的一条（目标 / 已知 / 中间结论）。
///
/// 卡里只放他自己的话，所以这里没有「她总结了一句」这种字段——`text` 就是他说的那句。
class MountEntry {
  const MountEntry({required this.text, this.at = 0, this.src = '', this.from = const []});

  final String text;
  final int at;

  /// 他这句话在账本里的出处（面板手写的没有）。
  final String src;

  /// 只对「中间结论」有意义：由哪几条已知（下标 0 起）推出。
  final List<int> from;

  static MountEntry? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final text = raw['text'];
    if (text is! String || text.trim().isEmpty) return null;
    return MountEntry(
      text: text,
      at: raw['at'] is num ? (raw['at'] as num).toInt() : 0,
      src: raw['src'] is String ? raw['src'] as String : '',
      from: [
        if (raw['from'] is List)
          for (final v in raw['from'] as List)
            if (v is num) v.toInt(),
      ],
    );
  }
}

/// C1：一张挂载卡。
///
/// 三栏固定：目标 1 条、已知 ≤[maxGivens]、中间结论 ≤[maxSteps]。
/// `status` 只有 `open` / `parked` / `closed`（卡一点都没有时插件给 `none`）。
class MountCard {
  const MountCard({
    this.status = 'none',
    this.goal = '',
    this.givens = const [],
    this.steps = const [],
    this.maxGivens = 5,
    this.maxSteps = 5,
    this.parkedWhy = '',
    this.id = '',
    this.cards = const [],
  });

  final String status;
  final String goal;
  final List<MountEntry> givens;
  final List<MountEntry> steps;
  final int maxGivens;
  final int maxSteps;
  final String parkedWhy;
  final String id;

  /// 所有卡的一眼清单（`{id, status, goal, parkedWhy}`），停放的那几张从这里恢复。
  final List<Map<String, String>> cards;

  bool get hasCard => status != 'none';
  bool get isOpen => status == 'open';
  bool get canAddGiven => isOpen && givens.length < maxGivens;
  bool get canAddStep => isOpen && steps.length < maxSteps && givens.isNotEmpty;

  static MountCard fromWire(Object? raw) {
    if (raw is! Map) return const MountCard();
    final status = raw['status'];
    return MountCard(
      status: status is String && status.isNotEmpty ? status : 'none',
      goal: raw['goal'] is String ? raw['goal'] as String : '',
      givens: _entries(raw['givens']),
      steps: _entries(raw['steps']),
      maxGivens: raw['maxGivens'] is num ? (raw['maxGivens'] as num).toInt() : 5,
      maxSteps: raw['maxSteps'] is num ? (raw['maxSteps'] as num).toInt() : 5,
      parkedWhy: raw['parkedWhy'] is String ? raw['parkedWhy'] as String : '',
      id: raw['id'] is String ? raw['id'] as String : '',
      cards: [
        if (raw['cards'] is List)
          for (final c in raw['cards'] as List)
            if (c is Map)
              {
                'id': c['id'] is String ? c['id'] as String : '',
                'status': c['status'] is String ? c['status'] as String : '',
                'goal': c['goal'] is String ? c['goal'] as String : '',
                'parkedWhy': c['parkedWhy'] is String ? c['parkedWhy'] as String : '',
              },
      ],
    );
  }

  static List<MountEntry> _entries(Object? raw) {
    if (raw is! List) return const [];
    final out = <MountEntry>[];
    for (final one in raw) {
      final e = MountEntry.fromWire(one);
      if (e != null) out.add(e);
    }
    return out;
  }
}

/// C1：一次 `/mount` 的结果。`scaffold` 是脚手架撤除比例：他自己挂的 / 她提议才挂的。
class MountView {
  const MountView({
    this.card = const MountCard(),
    this.ask = false,
    this.pendingText,
    this.pendingSlot,
    this.pendingSrc,
    this.scaffoldUser = 0,
    this.scaffoldHer = 0,
    this.error,
    this.message = '',
  });

  final MountCard card;

  /// 断链检测亮了：这一轮她只该做一件事——把卡亮出来问「继续还是停放」。
  final bool ask;

  /// 她提议、等他点头的那一条（null 表示没有待确认的提议）。
  final String? pendingText;
  final String? pendingSlot;
  final String? pendingSrc;

  final int scaffoldUser;
  final int scaffoldHer;

  /// 插件退回来的错误码（`card-open` / `no-card` / `not-user-text` …）；null 表示正常。
  final String? error;
  final String message;

  bool get ok => error == null;
  bool get hasPending => pendingText != null && pendingText!.isNotEmpty;

  /// 主动挂载 / 提议挂载。她提议越少说明他越会自己挂；没有提议过是 null（不是 0）。
  double? get scaffoldRatio => scaffoldHer > 0 ? scaffoldUser / scaffoldHer : (scaffoldUser > 0 ? null : 0);

  static MountView? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final m = raw['mount'];
    final card = MountCard.fromWire(m);
    final err = raw['error'];
    var user = 0;
    var her = 0;
    if (m is Map && m['scaffold'] is Map) {
      final s = m['scaffold'] as Map;
      user = s['user'] is num ? (s['user'] as num).toInt() : 0;
      her = s['her'] is num ? (s['her'] as num).toInt() : 0;
    }
    if (err is Map) {
      final code = err['code'];
      final msg = err['message'];
      return MountView(
        card: card,
        scaffoldUser: user,
        scaffoldHer: her,
        error: code is String && code.isNotEmpty ? code : 'error',
        message: msg is String ? msg : '',
      );
    }
    final pending = m is Map ? m['pending'] : null;
    return MountView(
      card: card,
      ask: m is Map && m['ask'] == true,
      pendingText: pending is Map && pending['text'] is String ? pending['text'] as String : null,
      pendingSlot: pending is Map && pending['slot'] is String ? pending['slot'] as String : null,
      pendingSrc: pending is Map && pending['src'] is String ? pending['src'] as String : null,
      scaffoldUser: user,
      scaffoldHer: her,
    );
  }
}

/// C2：一次 `/drill-op` 的结果。
///
/// 不带答案只读出什么题（[next]）、今天的额度和近 14 天通过率；带答案才带 [judge]。
/// 判卷全在插件的代码里，这里不重算也不兜底：插件说没判过，界面就不能显示成过了。
class DrillOpView {
  const DrillOpView({
    this.next,
    this.name = '',
    this.ask = '',
    this.pass = '',
    this.used = 0,
    this.max = 0,
    this.stats = const {},
    this.judge,
    this.error,
    this.message = '',
  });

  /// 链路该出的那一个是哪个操作（`brief` / `predict` / `ask` / `recall`）；额度用完是 null。
  final String? next;
  final String name;

  /// 这一题要他干什么。
  final String ask;

  /// 合格线原话。界面照抄，不自己解释。
  final String pass;

  final int used;
  final int max;

  /// 操作 → 近 14 天通过率。没练过的操作也在表里，`asked` 是 0（不是满分）。
  final Map<String, DrillOpStat> stats;

  /// 刚判完那一题的结果；只读查询是 null。
  final DrillOpJudge? judge;

  /// 插件退回来的错误码（`no-quota` / `bad-op` …）；null 表示正常。
  final String? error;
  final String message;

  bool get ok => error == null;
  int get left => max - used < 0 ? 0 : max - used;

  static DrillOpView? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final op = raw['op'];
    final m = op is Map ? op : const {};
    final meta = m['meta'];
    final quota = m['quota'];
    final stats = <String, DrillOpStat>{};
    if (m['stats'] is Map) {
      (m['stats'] as Map).forEach((k, v) {
        final s = DrillOpStat.fromWire(v);
        if (k is String && s != null) stats[k] = s;
      });
    }
    final next = m['next'];
    final err = raw['error'];
    return DrillOpView(
      next: next is String && next.isNotEmpty ? next : null,
      name: meta is Map && meta['name'] is String ? meta['name'] as String : '',
      ask: meta is Map && meta['ask'] is String ? meta['ask'] as String : '',
      pass: meta is Map && meta['pass'] is String ? meta['pass'] as String : '',
      used: quota is Map && quota['used'] is num ? (quota['used'] as num).toInt() : 0,
      max: quota is Map && quota['max'] is num ? (quota['max'] as num).toInt() : 0,
      stats: stats,
      judge: DrillOpJudge.fromWire(raw['judge']),
      error: err is Map && err['code'] is String && (err['code'] as String).isNotEmpty
          ? err['code'] as String
          : null,
      message: err is Map && err['message'] is String ? err['message'] as String : '',
    );
  }
}

/// 一个操作在窗口内的通过率。没出过题 `asked` 是 0，`rate` 是 0——没练过 ≠ 满分。
class DrillOpStat {
  const DrillOpStat({this.asked = 0, this.passed = 0, this.rate = 0});

  final int asked;
  final int passed;
  final double rate;

  bool get tried => asked > 0;

  static DrillOpStat? fromWire(Object? raw) {
    if (raw is! Map) return null;
    return DrillOpStat(
      asked: raw['asked'] is num ? (raw['asked'] as num).toInt() : 0,
      passed: raw['passed'] is num ? (raw['passed'] as num).toInt() : 0,
      rate: raw['rate'] is num ? (raw['rate'] as num).toDouble() : 0,
    );
  }
}

/// 刚判完那一题。[text] 是插件给的原话（为什么过、为什么不过），界面照抄。
/// [demo] 只在**第二次**没过时才有：第一次就给示范等于替他答。
class DrillOpJudge {
  const DrillOpJudge({this.op = '', this.ok = false, this.code = '', this.text = '', this.demo = ''});

  final String op;
  final bool ok;
  final String code;
  final String text;

  /// 示范答案；第一次没过是空串（不给）。
  final String demo;

  bool get hasDemo => demo.isNotEmpty;

  static DrillOpJudge? fromWire(Object? raw) {
    if (raw is! Map) return null;
    return DrillOpJudge(
      op: raw['op'] is String ? raw['op'] as String : '',
      ok: raw['ok'] == true,
      code: raw['code'] is String ? raw['code'] as String : '',
      text: raw['text'] is String ? raw['text'] as String : '',
      demo: raw['demo'] is String ? raw['demo'] as String : '',
    );
  }
}

