import 'dart:async';

import 'package:flutter/foundation.dart';

import '../models/guard_models.dart';
import 'ai_judge_service.dart';
import 'file_secret_store.dart';
import 'rule_engine.dart';
import 'win32_bridge.dart';

class LatestWindowSample {
  const LatestWindowSample({
    required this.snapshot,
    required this.classification,
    required this.isAfk,
    required this.sampledAtUtcMs,
  });

  final ForegroundWindowSnapshot snapshot;
  final WindowClassification classification;
  final bool isAfk;
  final int sampledAtUtcMs;
}

class PcGuardController extends ChangeNotifier {
  PcGuardController({
    FileSecretStore? store,
    Win32Bridge? win32,
    AiJudgeService? aiJudge,
    int Function()? nowUtcMs,
    this.afkThresholdSec = 180,
  })  : store = store ?? FileSecretStore(),
        win32 = win32 ?? Win32Bridge(),
        aiJudge = aiJudge ?? AiJudgeService(),
        _nowUtcMs =
            nowUtcMs ?? (() => DateTime.now().toUtc().millisecondsSinceEpoch);

  final FileSecretStore store;
  final Win32Bridge win32;
  final AiJudgeService aiJudge;
  final int Function() _nowUtcMs;
  final int afkThresholdSec;

  static const int maxEmergencyPassesPerDay = 2;
  static const int emergencyPassMinutes = 5;

  int get currentNowUtcMs => _nowUtcMs();

  GuardRulesConfig rules = const GuardRulesConfig();
  late UsageLedger ledger = UsageLedger(dayKey: RuleEngine.beijingDayKey(_nowUtcMs()));
  FocusSessionState focusSession = const FocusSessionState();
  List<StudyLease> activeLeases = const [];
  AiProviderConfig aiConfig = const AiProviderConfig();
  String aiApiKey = '';

  LatestWindowSample? latestSample;
  ActiveBlockState? activeBlock;
  String? statusBanner;
  bool capsuleMode = false;
  bool sensorRunning = false;
  bool aiRequestBusy = false;
  bool coachBusy = false;
  final List<CoachMessage> coachMessages = [];

  Timer? _loopTimer;
  bool _tickBusy = false;
  int _lastTickUtcMs = 0;
  int _tickCount = 0;
  final Set<String> _inFlightAiKeys = <String>{};
  int _lastBlockEventUtcMs = 0;
  String _lastBlockEventKey = '';

  Future<void> init({bool autoStartLoop = true}) async {
    final now = _nowUtcMs();
    final todayKey = RuleEngine.beijingDayKey(now);

    win32.registerHostCallbacks(
      onCloseIntercepted: () {
        statusBanner = '严防守护中：窗口已隐藏到系统托盘，禁止直接关闭';
        notifyListeners();
      },
      onTrayShowMain: () {
        if (capsuleMode) {
          unawaited(setCapsuleMode(false));
        }
      },
      onToggleCapsuleFromTray: () {
        unawaited(setCapsuleMode(!capsuleMode));
      },
    );

    final rulesJson = await store.readJsonFile('rules_config.json');
    if (rulesJson.isNotEmpty) {
      rules = GuardRulesConfig.fromJson(rulesJson);
    }

    final ledgerJson = await store.readJsonFile('usage_ledger.json');
    if (ledgerJson.isNotEmpty) {
      final loaded = UsageLedger.fromJson(ledgerJson, todayKey);
      if (loaded.dayKey == todayKey) {
        ledger = loaded;
      } else {
        await store.writeJsonFile(
          'ledger_archive_${loaded.dayKey}.json',
          loaded.toJson(),
        );
        ledger = UsageLedger(dayKey: todayKey);
      }
    } else {
      ledger = UsageLedger(dayKey: todayKey);
    }

    final focusJson = await store.readJsonFile('focus_session.json');
    if (focusJson.isNotEmpty) {
      final loadedFocus = FocusSessionState.fromJson(focusJson);
      focusSession = loadedFocus.isActiveAt(now)
          ? loadedFocus
          : const FocusSessionState();
    }

    final leasesJson = await store.readJsonFile('study_leases.json');
    if (leasesJson['leases'] is List) {
      final parsed = <StudyLease>[];
      for (final raw in leasesJson['leases'] as List) {
        final lease = StudyLease.fromJson(raw);
        if (lease != null && lease.isActiveAt(now)) {
          parsed.add(lease);
        }
      }
      activeLeases = parsed;
    }

    final aiJson = await store.readJsonFile('ai_config.json');
    if (aiJson.isNotEmpty) {
      aiConfig = AiProviderConfig.fromJson(aiJson);
    }
    aiApiKey = await store.readSecret('ai_api_key') ?? '';

    await store.clearAuthorizedExitFlag();

    final watchdogRestarts = await store.consumeWatchdogRestartLog();
    if (watchdogRestarts.isNotEmpty) {
      var updatedLogs = List<GuardEventLog>.from(ledger.eventLogs);
      for (final item in watchdogRestarts) {
        updatedLogs.insert(
          0,
          GuardEventLog(
            atUtcMs: item.atUtcMs,
            kind: 'watchdog_restart',
            detail: '守护进程拦截异常终止并重启哨兵：${item.detail}',
          ),
        );
      }
      ledger = ledger.copyWith(
        watchdogRestarts: ledger.watchdogRestarts + watchdogRestarts.length,
        eventLogs: updatedLogs,
      );
      await _persistLedger();
    }

    await _syncNativeGuardState();

    if (autoStartLoop) {
      startLoop();
    }
    notifyListeners();
  }

  void startLoop() {
    if (sensorRunning) return;
    sensorRunning = true;
    _loopTimer?.cancel();
    _loopTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => unawaited(tickSensor()),
    );
    unawaited(tickSensor());
    notifyListeners();
  }

  void stopLoop() {
    sensorRunning = false;
    _loopTimer?.cancel();
    _loopTimer = null;
    notifyListeners();
  }

  Future<void> tickSensor() async {
    if (_tickBusy) return;
    _tickBusy = true;
    try {
      final now = _nowUtcMs();
      _tickCount++;

      // 1. Beijing midnight rollover
      final todayKey = RuleEngine.beijingDayKey(now);
      if (ledger.dayKey != todayKey) {
        await store.writeJsonFile(
          'ledger_archive_${ledger.dayKey}.json',
          ledger.toJson(),
        );
        ledger = UsageLedger(dayKey: todayKey);
        await _persistLedger();
      }

      // 2. Mature pending rule loosenings
      final matured = RuleEngine.applyMaturePendingChanges(rules, now);
      if (matured.appliedSummaries.isNotEmpty) {
        rules = matured.rules;
        await _persistRules();
        _appendLog(
          now,
          'rule_change',
          '冷却期结束，规则放宽已生效：${matured.appliedSummaries.join("；")}',
        );
        await _syncNativeGuardState();
      }

      // 3. Focus session completion
      if (focusSession.active && !focusSession.isActiveAt(now)) {
        final task = focusSession.taskName.isEmpty
            ? '专注时段'
            : focusSession.taskName;
        focusSession = const FocusSessionState();
        await store.writeJsonFile('focus_session.json', focusSession.toJson());
        _appendLog(now, 'focus_done', '专注锁「$task」已到期结束');
      }

      // 4. Prune expired study leases
      if (activeLeases.any((l) => !l.isActiveAt(now))) {
        activeLeases = activeLeases.where((l) => l.isActiveAt(now)).toList();
        await _persistLeases();
      }

      // 5. Poll active foreground window
      final snap = await win32.getForeground();
      if (snap == null) {
        _lastTickUtcMs = now;
        notifyListeners();
        return;
      }

      final dtSec = _lastTickUtcMs == 0
          ? 0
          : ((now - _lastTickUtcMs) / 1000).round().clamp(0, 30);
      _lastTickUtcMs = now;

      final isAfk = snap.idleSec >= afkThresholdSec;
      final cls = RuleEngine.classifyWindow(
        app: snap.app,
        title: snap.title,
        rules: rules,
      );
      latestSample = LatestWindowSample(
        snapshot: snap,
        classification: cls,
        isAfk: isAfk,
        sampledAtUtcMs: now,
      );

      // 6. Accumulate durations
      if (dtSec > 0) {
        _accumulateDuration(
          cls: cls,
          snap: snap,
          isAfk: isAfk,
          dtSec: dtSec,
          nowUtcMs: now,
        );
      }

      // 7. Heartbeat watchdog supervision & periodic persistence
      if (rules.strictGuardArmed && _tickCount % 3 == 0) {
        unawaited(
          win32.ensureWatchdog(
            stateDir: store.rootPath,
            strictArmed: true,
          ),
        );
      }
      if (_tickCount % 15 == 0) {
        await _persistLedger();
      }

      // 8. Auto-dismiss shield if user switched back to work window
      if (activeBlock != null && !snap.selfActive && cls.tier == ActivityTier.work) {
        activeBlock = null;
        await win32.setShieldTopmost(false);
      }

      // 9. Enforcement decision (skip self window)
      if (!snap.selfActive && snap.app.trim().isNotEmpty) {
        final decision = RuleEngine.evaluateEnforcement(
          app: snap.app,
          title: snap.title,
          isAfk: isAfk,
          rules: rules,
          ledger: ledger,
          focusSession: focusSession,
          activeLeases: activeLeases,
          aiConfig: aiConfig,
          aiCache: aiJudge.cacheView,
          nowUtcMs: now,
        );

        switch (decision.action) {
          case EnforcementAction.none:
            break;
          case EnforcementAction.queryAi:
            _scheduleAiJudgment(snap, cls, now);
          case EnforcementAction.terminateAndShield:
            await _triggerBlock(
              snap: snap,
              cls: cls,
              decision: decision,
              actionKind: 'terminateAndShield',
              nowUtcMs: now,
            );
          case EnforcementAction.minimizeAndShield:
            await _triggerBlock(
              snap: snap,
              cls: cls,
              decision: decision,
              actionKind: 'minimizeAndShield',
              nowUtcMs: now,
            );
        }
      }

      notifyListeners();
    } finally {
      _tickBusy = false;
    }
  }

  void _accumulateDuration({
    required WindowClassification cls,
    required ForegroundWindowSnapshot snap,
    required bool isAfk,
    required int dtSec,
    required int nowUtcMs,
  }) {
    if (isAfk) {
      ledger = ledger.copyWith(afkSeconds: ledger.afkSeconds + dtSec);
      return;
    }

    final perApp = Map<String, int>.from(ledger.perAppSeconds);
    if (cls.normalizedApp.isNotEmpty) {
      perApp[cls.normalizedApp] = (perApp[cls.normalizedApp] ?? 0) + dtSec;
    }

    var workDelta = 0;
    var entDelta = 0;
    var dualDelta = 0;

    switch (cls.tier) {
      case ActivityTier.neverBlock:
      case ActivityTier.work:
        workDelta = dtSec;
      case ActivityTier.blocked:
        entDelta = dtSec;
      case ActivityTier.dual:
        final lease = RuleEngine.findMatchingLease(
          activeLeases,
          snap.app,
          snap.title,
          nowUtcMs,
        );
        final cached = RuleEngine.findFreshCachedJudgment(
          aiJudge.cacheView,
          snap.app,
          snap.title,
          nowUtcMs,
        );
        if (lease != null || cached?.verdict == 'allow_study') {
          workDelta = dtSec;
        } else if (cached?.verdict == 'entertainment') {
          entDelta = dtSec;
        } else {
          dualDelta = dtSec;
        }
    }

    ledger = ledger.copyWith(
      workSeconds: ledger.workSeconds + workDelta,
      entertainmentSeconds: ledger.entertainmentSeconds + entDelta,
      dualSeconds: ledger.dualSeconds + dualDelta,
      perAppSeconds: perApp,
    );
  }

  void _scheduleAiJudgment(
    ForegroundWindowSnapshot snap,
    WindowClassification cls,
    int nowUtcMs,
  ) {
    final cacheKey = RuleEngine.judgmentCacheKey(snap.app, snap.title);
    if (_inFlightAiKeys.contains(cacheKey)) return;
    _inFlightAiKeys.add(cacheKey);
    aiRequestBusy = true;
    notifyListeners();

    unawaited(() async {
      try {
        final rec = await aiJudge.judgeActivity(
          config: aiConfig,
          apiKey: aiApiKey,
          app: snap.app,
          title: snap.title,
          focusSession: focusSession,
          ledger: ledger,
          nowUtcMs: _nowUtcMs(),
        );
        final updatedJudgments = [rec, ...ledger.aiJudgments];
        ledger = ledger.copyWith(aiJudgments: updatedJudgments);

        if (rec.verdict == 'allow_study') {
          final anchor = _leaseTitleAnchor(snap.title);
          final created = _nowUtcMs();
          final lease = StudyLease(
            id: 'ai-$created',
            app: cls.normalizedApp,
            titleKeyword: anchor,
            reason: rec.reason,
            grantedBy: 'ai',
            createdAtUtcMs: created,
            expiresAtUtcMs: created + rec.leaseMinutes * 60 * 1000,
          );
          activeLeases = [...activeLeases, lease];
          await _persistLeases();
          _appendLog(
            created,
            'ai_lease',
            'AI 自动批复学习通行证 ${rec.leaseMinutes} 分钟（${snap.app}）：${rec.reason}',
          );
        } else if (rec.verdict == 'entertainment') {
          _appendLog(
            _nowUtcMs(),
            'ai_lease',
            'AI 判定非学习用途（${snap.app}）：${rec.reason}',
          );
        }
        await _persistLedger();
      } catch (e) {
        statusBanner = 'AI 裁决请求失败：$e';
      } finally {
        _inFlightAiKeys.remove(cacheKey);
        aiRequestBusy = false;
        notifyListeners();
      }
    }());
  }

  Future<void> _triggerBlock({
    required ForegroundWindowSnapshot snap,
    required WindowClassification cls,
    required EnforcementDecision decision,
    required String actionKind,
    required int nowUtcMs,
  }) async {
    if (actionKind == 'terminateAndShield') {
      await win32.terminateProcess(snap.pid, cls.normalizedApp);
    } else {
      await win32.minimizeWindow(snap.hwnd);
    }
    await win32.setShieldTopmost(true);

    activeBlock = ActiveBlockState(
      app: snap.app,
      title: snap.title,
      tier: cls.tier,
      reason: decision.reason,
      actionKind: actionKind,
      canRequestAiPass: decision.canRequestAiPass,
      triggeredAtUtcMs: nowUtcMs,
      actionHint: decision.actionHint,
    );

    final eventKey = '${cls.normalizedApp}:${snap.title}';
    if (nowUtcMs - _lastBlockEventUtcMs >= 10000 || _lastBlockEventKey != eventKey) {
      _lastBlockEventUtcMs = nowUtcMs;
      _lastBlockEventKey = eventKey;
      ledger = ledger.copyWith(blocksTriggered: ledger.blocksTriggered + 1);
      _appendLog(
        nowUtcMs,
        'block',
        '[$actionKind] ${snap.app}「${snap.title}」-> ${decision.reason}',
      );
      await _persistLedger();
    }
  }

  Future<void> armStrictGuard() async {
    final now = _nowUtcMs();
    rules = rules.copyWith(strictGuardArmed: true);
    await _persistRules();
    await store.clearAuthorizedExitFlag();
    await _syncNativeGuardState();
    _appendLog(
      now,
      'rule_change',
      '已武装严防守护：拦截关闭窗口、开启 Watchdog 互保与开机自启',
    );
    await _persistLedger();
    statusBanner = '严防守护已开启：关闭窗口将隐藏至托盘，Watchdog 与改松冷却已激活';
    notifyListeners();
  }

  Future<void> startFocusSession({
    required int minutes,
    required String taskName,
    bool? hardLocked,
  }) async {
    final now = _nowUtcMs();
    final safeMin = minutes.clamp(5, 240);
    final proposedEnd = now + safeMin * 60 * 1000;
    if (focusSession.isActiveAt(now)) {
      if (proposedEnd <= focusSession.endsAtUtcMs) {
        statusBanner = '当前已有进行中的专注锁，只能延长不能缩短';
        notifyListeners();
        return;
      }
      focusSession = focusSession.copyWith(
        endsAtUtcMs: proposedEnd,
        hardLocked: focusSession.hardLocked || (hardLocked ?? true),
      );
      await store.writeJsonFile('focus_session.json', focusSession.toJson());
      _appendLog(now, 'focus_start', '延长专注锁至总剩余 $safeMin 分钟');
      await _persistLedger();
      notifyListeners();
      return;
    }
    final lock = hardLocked ?? rules.focusHardLockDefault;
    final cleanTask = taskName.trim().isEmpty ? '深度专注局' : taskName.trim();
    focusSession = FocusSessionState(
      active: true,
      taskName: cleanTask,
      startedAtUtcMs: now,
      endsAtUtcMs: proposedEnd,
      hardLocked: lock,
    );
    await store.writeJsonFile('focus_session.json', focusSession.toJson());
    _appendLog(
      now,
      'focus_start',
      '开启专注锁 $safeMin 分钟（${lock ? "铁锁禁止提前退" : "标准锁"}）：$cleanTask',
    );
    await _persistLedger();
    notifyListeners();
  }

  void clearStatusBanner() {
    if (statusBanner == null) return;
    statusBanner = null;
    notifyListeners();
  }

  Future<void> extendFocusSession(int extraMinutes) async {
    final now = _nowUtcMs();
    if (!focusSession.isActiveAt(now)) return;
    final add = extraMinutes.clamp(5, 120);
    focusSession = focusSession.copyWith(
      endsAtUtcMs: focusSession.endsAtUtcMs + add * 60 * 1000,
    );
    await store.writeJsonFile('focus_session.json', focusSession.toJson());
    _appendLog(now, 'focus_start', '专注锁延长 $add 分钟');
    await _persistLedger();
    notifyListeners();
  }

  Future<RuleEditResult> proposeRulesUpdate(GuardRulesConfig proposed) async {
    final now = _nowUtcMs();
    final result = RuleEngine.applyRuleEdit(
      current: rules,
      proposed: proposed,
      nowUtcMs: now,
    );
    rules = result.nextRules;
    await _persistRules();
    if (result.appliedImmediately.isNotEmpty) {
      _appendLog(
        now,
        'rule_change',
        '规则立即生效：${result.appliedImmediately.join("；")}',
      );
    }
    if (result.queuedLoosenings.isNotEmpty) {
      _appendLog(
        now,
        'rule_change',
        '改松请求已进入 ${rules.loosenCooldownHours} 小时冷却：${result.queuedLoosenings.join("；")}',
      );
    }
    await _persistLedger();
    await _syncNativeGuardState();
    notifyListeners();
    return result;
  }

  Future<void> cancelPendingLoosening(String id) async {
    final now = _nowUtcMs();
    final target = rules.pendingLoosenings.where((e) => e.id == id).toList();
    if (target.isEmpty) return;
    rules = rules.copyWith(
      pendingLoosenings:
          rules.pendingLoosenings.where((e) => e.id != id).toList(),
    );
    await _persistRules();
    _appendLog(now, 'rule_change', '已撤销冷却中的改松申请：${target.first.summary}');
    await _persistLedger();
    notifyListeners();
  }

  Future<({bool ok, String message})> dismissShieldAfterReturningToWork() async {
    final snap = await win32.getForeground();
    if (snap != null && !snap.selfActive && snap.app.trim().isNotEmpty) {
      final decision = RuleEngine.evaluateEnforcement(
        app: snap.app,
        title: snap.title,
        isAfk: false,
        rules: rules,
        ledger: ledger,
        focusSession: focusSession,
        activeLeases: activeLeases,
        aiConfig: aiConfig,
        aiCache: aiJudge.cacheView,
        nowUtcMs: _nowUtcMs(),
      );
      if (!decision.allowed) {
        return (
          ok: false,
          message: '前台仍是受控应用「${snap.app}」，请先关闭或切回工作软件后再返回',
        );
      }
    }
    activeBlock = null;
    await win32.setShieldTopmost(false);
    notifyListeners();
    return (ok: true, message: '已回到工作状态，拦截盾已收起');
  }

  Future<({bool ok, String message})> requestAiStudyPass({
    required String claim,
  }) async {
    final block = activeBlock;
    if (block == null) {
      return (ok: false, message: '当前没有处于拦截状态的应用');
    }
    if (!aiConfig.isConfigured) {
      return (ok: false, message: '请先在「AI 裁判」页配置可用的模型接口后再申请');
    }
    final now = _nowUtcMs();
    aiRequestBusy = true;
    notifyListeners();
    try {
      final eval = await aiJudge.evaluateStudyPassRequest(
        config: aiConfig,
        apiKey: aiApiKey,
        claim: claim,
        app: block.app,
        title: block.title,
        focusSession: focusSession,
        ledger: ledger,
        nowUtcMs: now,
      );
      if (eval.approved) {
        final lease = StudyLease(
          id: 'appeal-$now',
          app: RuleEngine.normalizeApp(block.app),
          titleKeyword: _leaseTitleAnchor(block.title),
          reason: '${claim.trim()}（AI: ${eval.reason}）',
          grantedBy: 'ai',
          createdAtUtcMs: now,
          expiresAtUtcMs: now + eval.leaseMinutes * 60 * 1000,
        );
        activeLeases = [...activeLeases, lease];
        await _persistLeases();
        _appendLog(
          now,
          'ai_lease',
          'AI 审批通过「${block.app}」${eval.leaseMinutes} 分钟通行证：${eval.reason}',
        );
        await _persistLedger();
        activeBlock = null;
        await win32.setShieldTopmost(false);
        return (
          ok: true,
          message: 'AI 已批准 ${eval.leaseMinutes} 分钟学习通行证：${eval.reason}',
        );
      }
      _appendLog(now, 'ai_lease', 'AI 拒绝学习通行证申请：${eval.reason}');
      await _persistLedger();
      return (ok: false, message: 'AI 拒绝放行：${eval.reason}');
    } catch (e) {
      return (ok: false, message: 'AI 审批请求失败：$e');
    } finally {
      aiRequestBusy = false;
      notifyListeners();
    }
  }

  Future<({bool ok, String message})> grantEmergencyPass({
    required String reason,
  }) async {
    final clean = reason.trim();
    if (clean.length < 6) {
      return (ok: false, message: '请填写至少 6 个字的具体急事原因');
    }
    if (ledger.emergencyPassesUsed >= maxEmergencyPassesPerDay) {
      return (
        ok: false,
        message: '今日应急放行次数已用完（$maxEmergencyPassesPerDay/$maxEmergencyPassesPerDay）',
      );
    }
    final now = _nowUtcMs();
    final lease = StudyLease(
      id: 'emergency-$now',
      app: '*',
      titleKeyword: '',
      reason: '急事放行：$clean',
      grantedBy: 'emergency',
      createdAtUtcMs: now,
      expiresAtUtcMs: now + emergencyPassMinutes * 60 * 1000,
    );
    activeLeases = [...activeLeases, lease];
    await _persistLeases();

    ledger = ledger.copyWith(
      emergencyPassesUsed: ledger.emergencyPassesUsed + 1,
    );
    _appendLog(
      now,
      'emergency_pass',
      '启用 $emergencyPassMinutes 分钟应急放行（今日第 ${ledger.emergencyPassesUsed}/$maxEmergencyPassesPerDay 次）：$clean',
    );
    await _persistLedger();
    activeBlock = null;
    await win32.setShieldTopmost(false);
    notifyListeners();
    return (
      ok: true,
      message: '已授予 $emergencyPassMinutes 分钟应急放行（今日第 ${ledger.emergencyPassesUsed}/$maxEmergencyPassesPerDay 次）',
    );
  }

  Future<({bool ok, String message})> completeFrictionDisarmOrExit({
    required bool alsoExit,
    required String challengeInput,
    required String expectedChallenge,
    required String reason,
    required int waitedSeconds,
  }) async {
    final now = _nowUtcMs();
    if (focusSession.isActiveAt(now) && focusSession.hardLocked) {
      final leftMin = (focusSession.remainingSecondsAt(now) / 60).ceil();
      return (
        ok: false,
        message: '当前处于专注铁锁倒计时（剩余 $leftMin 分钟），结束前禁止解除严防或退出程序',
      );
    }
    if (expectedChallenge.trim().length < 48 ||
        challengeInput.trim().toUpperCase() !=
            expectedChallenge.trim().toUpperCase()) {
      return (ok: false, message: '随机挑战码校验未通过，请逐字准确输入');
    }
    final cleanReason = reason.trim();
    if (cleanReason.length < 15) {
      return (ok: false, message: '退出/解除理由至少需要 15 个字');
    }
    if (waitedSeconds < 60) {
      return (ok: false, message: '请先完成 60 秒冷静等待（当前仅 $waitedSeconds 秒）');
    }

    await store.writeAuthorizedExitFlag(cleanReason);
    rules = rules.copyWith(strictGuardArmed: false);
    await _persistRules();
    _appendLog(
      now,
      'exit_attempt',
      '高阻力验证通过，执行${alsoExit ? "授权退出" : "解除严防"}：$cleanReason',
    );
    await _persistLedger();
    await _syncNativeGuardState();

    if (alsoExit) {
      await win32.authorizedExit();
    }
    statusBanner = alsoExit ? '已完成高阻力验证，正在退出' : '已完成高阻力验证，严防守护已解除';
    notifyListeners();
    return (
      ok: true,
      message: alsoExit ? '验证通过，正在安全退出...' : '验证通过，已解除严防守护',
    );
  }

  Future<void> saveAiConfig(AiProviderConfig config, {String? apiKey}) async {
    aiConfig = config;
    await store.writeJsonFile('ai_config.json', config.toJson());
    if (apiKey != null) {
      aiApiKey = apiKey.trim();
      await store.writeSecret('ai_api_key', aiApiKey);
    }
    aiJudge.clearCache();
    notifyListeners();
  }

  Future<({bool ok, String detail})> testAiConnection() {
    return aiJudge.testConnection(config: aiConfig, apiKey: aiApiKey);
  }

  Future<void> sendCoachMessage(String text) async {
    final clean = text.trim();
    if (clean.isEmpty || coachBusy) return;
    final now = _nowUtcMs();
    coachMessages.add(
      CoachMessage(isUser: true, speaker: '我', text: clean, atUtcMs: now),
    );
    coachBusy = true;
    notifyListeners();
    try {
      if (!aiConfig.isConfigured) {
        coachMessages.add(
          CoachMessage(
            isUser: false,
            speaker: '系统',
            text: '请先在上方填写并启用 AI 接口配置（Base URL / 模型 / API Key）。',
            atUtcMs: _nowUtcMs(),
          ),
        );
        return;
      }
      final reply = await aiJudge.chatWithCoach(
        config: aiConfig,
        apiKey: aiApiKey,
        ledger: ledger,
        rules: rules,
        focusSession: focusSession,
        history: coachMessages.sublist(0, coachMessages.length - 1),
        userMessage: clean,
        nowUtcMs: _nowUtcMs(),
      );
      coachMessages.add(
        CoachMessage(
          isUser: false,
          speaker: 'AI 参谋',
          text: reply,
          atUtcMs: _nowUtcMs(),
        ),
      );
    } catch (e) {
      coachMessages.add(
        CoachMessage(
          isUser: false,
          speaker: '系统',
          text: 'AI 请求失败：$e',
          atUtcMs: _nowUtcMs(),
        ),
      );
    } finally {
      coachBusy = false;
      notifyListeners();
    }
  }

  Future<void> runDailyAiReview() {
    return sendCoachMessage(
      '基于我今天真实的电脑使用账本，直接指出最浪费时间的地方、拦截是否起效，并给出接下来 2 小时的唯一硬动作。',
    );
  }

  Future<void> setCapsuleMode(bool enable) async {
    capsuleMode = await win32.setCapsuleMode(enable);
    notifyListeners();
  }

  Future<void> _syncNativeGuardState() async {
    await store.writeJsonFile('guard_lock.json', {
      'strictArmed': rules.strictGuardArmed,
      'updatedAtUtcMs': _nowUtcMs(),
    });
    await win32.setPreventClose(rules.strictGuardArmed);
    if (rules.strictGuardArmed && rules.autoStartEnabled) {
      await win32.setAutoStart(true);
    } else if (!rules.strictGuardArmed) {
      await win32.setAutoStart(rules.autoStartEnabled);
    }
    await win32.ensureWatchdog(
      stateDir: store.rootPath,
      strictArmed: rules.strictGuardArmed,
    );
  }

  void _appendLog(int atUtcMs, String kind, String detail) {
    final next = [
      GuardEventLog(atUtcMs: atUtcMs, kind: kind, detail: detail),
      ...ledger.eventLogs,
    ];
    ledger = ledger.copyWith(eventLogs: next);
  }

  Future<void> _persistRules() =>
      store.writeJsonFile('rules_config.json', rules.toJson());

  Future<void> _persistLedger() =>
      store.writeJsonFile('usage_ledger.json', ledger.toJson());

  Future<void> _persistLeases() => store.writeJsonFile('study_leases.json', {
        'leases': activeLeases.map((e) => e.toJson()).toList(),
      });

  static String _leaseTitleAnchor(String title) {
    final clean = title.trim().toLowerCase().replaceAll(RegExp(r'\s+'), ' ');
    if (clean.length <= 24) return clean;
    return clean.substring(0, 24);
  }

  @override
  void dispose() {
    _loopTimer?.cancel();
    super.dispose();
  }
}
