import 'dart:math';

import '../models/guard_models.dart';

class WindowClassification {
  const WindowClassification({
    required this.normalizedApp,
    required this.tier,
    this.isExplicitGreyZone = false,
  });

  final String normalizedApp;
  final ActivityTier tier;
  final bool isExplicitGreyZone;
}

enum EnforcementAction {
  none,
  queryAi,
  minimizeAndShield,
  terminateAndShield,
}

class EnforcementDecision {
  const EnforcementDecision({
    required this.allowed,
    required this.action,
    required this.reason,
    this.canRequestAiPass = false,
    this.actionHint = '',
  });

  final bool allowed;
  final EnforcementAction action;
  final String reason;
  final bool canRequestAiPass;
  final String actionHint;
}

class RuleEditResult {
  const RuleEditResult({
    required this.nextRules,
    required this.appliedImmediately,
    required this.queuedLoosenings,
  });

  final GuardRulesConfig nextRules;
  final List<String> appliedImmediately;
  final List<String> queuedLoosenings;
}

class RuleEngine {
  static const int _beijingOffsetMs = 8 * 3600 * 1000;

  static const Set<String> neverBlockProcesses = {
    'dsh_go_fish_pc',
    'dsh_fish_watchdog',
    'explorer',
    'searchhost',
    'searchapp',
    'startmenuexperiencehost',
    'shellexperiencehost',
    'lockapp',
    'applicationframehost',
    'ctfmon',
    'textinputhost',
    'systemsettings',
    'sihost',
    'dwm',
    'taskmgr',
    'runtimebroker',
  };

  static const Set<String> browserProcesses = {
    'chrome',
    'msedge',
    'edge',
    'firefox',
    'brave',
    'vivaldi',
    'opera',
    'arc',
    '360chrome',
    'qqbrowser',
  };

  static const String _challengeAlphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

  static String normalizeApp(String app) {
    var lower = app.trim().toLowerCase();
    if (lower.endsWith('.exe')) {
      lower = lower.substring(0, lower.length - 4);
    }
    return lower;
  }

  static bool isNeverBlockApp(String app) {
    final norm = normalizeApp(app);
    return norm.isEmpty || neverBlockProcesses.contains(norm);
  }

  static bool isBrowserApp(String app) {
    return browserProcesses.contains(normalizeApp(app));
  }

  static String beijingDayKey(int utcMs) {
    final bj = DateTime.fromMillisecondsSinceEpoch(
      utcMs + _beijingOffsetMs,
      isUtc: true,
    );
    final m = bj.month.toString().padLeft(2, '0');
    final d = bj.day.toString().padLeft(2, '0');
    return '${bj.year}-$m-$d';
  }

  static int beijingMinuteOfDay(int utcMs) {
    final bj = DateTime.fromMillisecondsSinceEpoch(
      utcMs + _beijingOffsetMs,
      isUtc: true,
    );
    return bj.hour * 60 + bj.minute;
  }

  static String formatMinuteOfDay(int minute) {
    final m = minute.clamp(0, 1439);
    final hh = (m ~/ 60).toString().padLeft(2, '0');
    final mm = (m % 60).toString().padLeft(2, '0');
    return '$hh:$mm';
  }

  static int curfewDurationMinutes(int startMin, int endMin) {
    if (startMin == endMin) return 0;
    if (startMin < endMin) return endMin - startMin;
    return (1440 - startMin) + endMin;
  }

  static bool isInNightCurfew(GuardRulesConfig rules, int utcMs) {
    if (!rules.nightCurfewEnabled) return false;
    final start = rules.nightCurfewStartMin;
    final end = rules.nightCurfewEndMin;
    if (start == end) return false;
    final nowMin = beijingMinuteOfDay(utcMs);
    if (start < end) {
      return nowMin >= start && nowMin < end;
    }
    return nowMin >= start || nowMin < end;
  }

  static WindowClassification classifyWindow({
    required String app,
    required String title,
    required GuardRulesConfig rules,
  }) {
    final normApp = normalizeApp(app);
    if (isNeverBlockApp(normApp)) {
      return WindowClassification(
        normalizedApp: normApp,
        tier: ActivityTier.neverBlock,
      );
    }

    final workSet = rules.workApps.map(normalizeApp).toSet();
    if (workSet.contains(normApp)) {
      return WindowClassification(
        normalizedApp: normApp,
        tier: ActivityTier.work,
      );
    }

    final blockedSet = rules.blockedApps.map(normalizeApp).toSet();
    if (blockedSet.contains(normApp)) {
      return WindowClassification(
        normalizedApp: normApp,
        tier: ActivityTier.blocked,
      );
    }

    final titleLower = title.toLowerCase();
    for (final kw in rules.blockedTitleKeywords) {
      final k = kw.trim().toLowerCase();
      if (k.isNotEmpty && titleLower.contains(k)) {
        return WindowClassification(
          normalizedApp: normApp,
          tier: ActivityTier.blocked,
        );
      }
    }

    for (final kw in rules.workTitleKeywords) {
      final k = kw.trim().toLowerCase();
      if (k.isNotEmpty && titleLower.contains(k)) {
        return WindowClassification(
          normalizedApp: normApp,
          tier: ActivityTier.work,
        );
      }
    }

    for (final kw in rules.dualSiteKeywords) {
      final k = kw.trim().toLowerCase();
      if (k.isNotEmpty && titleLower.contains(k)) {
        return WindowClassification(
          normalizedApp: normApp,
          tier: ActivityTier.dual,
          isExplicitGreyZone: true,
        );
      }
    }

    return WindowClassification(
      normalizedApp: normApp,
      tier: ActivityTier.dual,
      isExplicitGreyZone: false,
    );
  }

  static StudyLease? findMatchingLease(
    List<StudyLease> leases,
    String app,
    String title,
    int nowUtcMs,
  ) {
    final normApp = normalizeApp(app);
    final titleLower = title.toLowerCase();
    for (final lease in leases) {
      if (!lease.isActiveAt(nowUtcMs)) continue;
      final leaseApp = normalizeApp(lease.app);
      if (leaseApp != '*' && leaseApp != normApp) continue;
      final kw = lease.titleKeyword.trim().toLowerCase();
      if (kw.isEmpty || titleLower.contains(kw)) {
        return lease;
      }
    }
    return null;
  }

  static AiJudgmentRecord? findFreshCachedJudgment(
    Map<String, AiJudgmentRecord> cache,
    String app,
    String title,
    int nowUtcMs, {
    int ttlMs = 10 * 60 * 1000,
  }) {
    final key = judgmentCacheKey(app, title);
    final rec = cache[key];
    if (rec == null) return null;
    if (nowUtcMs - rec.atUtcMs > ttlMs) return null;
    return rec;
  }

  static String judgmentCacheKey(String app, String title) {
    final normApp = normalizeApp(app);
    final cleanTitle = title.trim().toLowerCase().replaceAll(RegExp(r'\s+'), ' ');
    final shortTitle =
        cleanTitle.length > 80 ? cleanTitle.substring(0, 80) : cleanTitle;
    return '$normApp::$shortTitle';
  }

  static EnforcementDecision evaluateEnforcement({
    required String app,
    required String title,
    required bool isAfk,
    required GuardRulesConfig rules,
    required UsageLedger ledger,
    required FocusSessionState focusSession,
    required List<StudyLease> activeLeases,
    required AiProviderConfig aiConfig,
    required Map<String, AiJudgmentRecord> aiCache,
    required int nowUtcMs,
  }) {
    if (isAfk) {
      return const EnforcementDecision(
        allowed: true,
        action: EnforcementAction.none,
        reason: '离开键盘（AFK）',
      );
    }

    final cls = classifyWindow(app: app, title: title, rules: rules);
    if (cls.tier == ActivityTier.neverBlock || cls.tier == ActivityTier.work) {
      return const EnforcementDecision(
        allowed: true,
        action: EnforcementAction.none,
        reason: '工作或系统底座窗口',
      );
    }

    final lease = findMatchingLease(activeLeases, app, title, nowUtcMs);
    if (lease != null) {
      return EnforcementDecision(
        allowed: true,
        action: EnforcementAction.none,
        reason: '已持有效通行证：${lease.reason}',
      );
    }

    final inCurfew = isInNightCurfew(rules, nowUtcMs);
    final inFocus = focusSession.isActiveAt(nowUtcMs);
    final quotaExhausted =
        ledger.entertainmentMinutes >= rules.dailyEntertainmentLimitMin;
    final mustEnforce = inCurfew || inFocus || quotaExhausted;

    if (!mustEnforce) {
      return const EnforcementDecision(
        allowed: true,
        action: EnforcementAction.none,
        reason: '今日娱乐额度内',
      );
    }

    final triggerPrefix = inFocus
        ? '专注锁进行中（${focusSession.taskName.isEmpty ? "专注时段" : focusSession.taskName}）'
        : inCurfew
            ? '深夜宵禁时段（${formatMinuteOfDay(rules.nightCurfewStartMin)}-${formatMinuteOfDay(rules.nightCurfewEndMin)}）'
            : '今日娱乐额度已耗尽（${ledger.entertainmentMinutes.toStringAsFixed(0)}/${rules.dailyEntertainmentLimitMin} 分钟）';

    if (cls.tier == ActivityTier.blocked) {
      final isBrowser = isBrowserApp(cls.normalizedApp);
      return EnforcementDecision(
        allowed: false,
        action: isBrowser
            ? EnforcementAction.minimizeAndShield
            : EnforcementAction.terminateAndShield,
        reason: '$triggerPrefix · 禁止访问明确娱乐应用/页面「$app」',
        canRequestAiPass: false,
      );
    }

    // Dual-purpose tier
    if (!cls.isExplicitGreyZone) {
      return const EnforcementDecision(
        allowed: true,
        action: EnforcementAction.none,
        reason: '未命中高风险娱乐或灰区网站特征',
      );
    }

    final cached = findFreshCachedJudgment(aiCache, app, title, nowUtcMs);
    if (cached != null) {
      if (cached.verdict == 'allow_study') {
        return EnforcementDecision(
          allowed: true,
          action: EnforcementAction.none,
          reason: 'AI 近期判定为学习用途：${cached.reason}',
        );
      }
      return EnforcementDecision(
        allowed: false,
        action: EnforcementAction.minimizeAndShield,
        reason: '$triggerPrefix · AI 裁决非当前学习任务（${cached.reason}）',
        canRequestAiPass: true,
        actionHint: cached.actionHint,
      );
    }

    if (aiConfig.isConfigured && aiConfig.autoJudgeDual) {
      return EnforcementDecision(
        allowed: true,
        action: EnforcementAction.queryAi,
        reason: '灰区站点「$title」等待 AI 裁决是否为学习用途',
        canRequestAiPass: true,
      );
    }

    return EnforcementDecision(
      allowed: false,
      action: EnforcementAction.minimizeAndShield,
      reason: '$triggerPrefix · 灰区站点「$app」在严格模式下暂未放行（可配置 AI 自动鉴权或回到工作）',
      canRequestAiPass: aiConfig.isConfigured,
    );
  }

  static RuleEditResult applyRuleEdit({
    required GuardRulesConfig current,
    required GuardRulesConfig proposed,
    required int nowUtcMs,
  }) {
    if (!current.strictGuardArmed) {
      final next = proposed.copyWith(strictGuardArmed: false);
      return RuleEditResult(
        nextRules: next,
        appliedImmediately: const ['当前未开启严防模式，所有规则已立即生效'],
        queuedLoosenings: const [],
      );
    }

    final applied = <String>[];
    final queuedLabels = <String>[];
    final nextPending = List<PendingRuleChange>.from(current.pendingLoosenings);
    final effectiveAt =
        nowUtcMs + current.loosenCooldownHours * 3600 * 1000;

    void enqueuePending({
      required String field,
      required String summary,
      required Object? targetValue,
    }) {
      final dup = nextPending.any(
        (p) => p.field == field && p.targetValue.toString() == targetValue.toString(),
      );
      if (dup) {
        queuedLabels.add('$summary（已在冷却队列中）');
        return;
      }
      nextPending.add(
        PendingRuleChange(
          id: '$field-$nowUtcMs-${nextPending.length}',
          field: field,
          summary: summary,
          targetValue: targetValue,
          requestedAtUtcMs: nowUtcMs,
          effectiveAtUtcMs: effectiveAt,
        ),
      );
      queuedLabels.add(summary);
    }

    var nextLimit = current.dailyEntertainmentLimitMin;
    if (proposed.dailyEntertainmentLimitMin < current.dailyEntertainmentLimitMin) {
      nextLimit = proposed.dailyEntertainmentLimitMin;
      applied.add('每日娱乐额度收紧至 $nextLimit 分钟（立即生效）');
    } else if (proposed.dailyEntertainmentLimitMin >
        current.dailyEntertainmentLimitMin) {
      enqueuePending(
        field: 'dailyEntertainmentLimitMin',
        summary: '每日娱乐额度放宽至 ${proposed.dailyEntertainmentLimitMin} 分钟',
        targetValue: proposed.dailyEntertainmentLimitMin,
      );
    }

    var nextCurfewEnabled = current.nightCurfewEnabled;
    if (!current.nightCurfewEnabled && proposed.nightCurfewEnabled) {
      nextCurfewEnabled = true;
      applied.add('已开启深夜宵禁（立即生效）');
    } else if (current.nightCurfewEnabled && !proposed.nightCurfewEnabled) {
      enqueuePending(
        field: 'nightCurfewEnabled',
        summary: '关闭深夜宵禁',
        targetValue: false,
      );
    }

    var nextStart = current.nightCurfewStartMin;
    var nextEnd = current.nightCurfewEndMin;
    if (proposed.nightCurfewStartMin != current.nightCurfewStartMin ||
        proposed.nightCurfewEndMin != current.nightCurfewEndMin) {
      final curDur = curfewDurationMinutes(
        current.nightCurfewStartMin,
        current.nightCurfewEndMin,
      );
      final propDur = curfewDurationMinutes(
        proposed.nightCurfewStartMin,
        proposed.nightCurfewEndMin,
      );
      if (propDur >= curDur) {
        nextStart = proposed.nightCurfewStartMin;
        nextEnd = proposed.nightCurfewEndMin;
        applied.add(
          '宵禁时段调整为 ${formatMinuteOfDay(nextStart)}-${formatMinuteOfDay(nextEnd)}（立即生效）',
        );
      } else {
        enqueuePending(
          field: 'nightCurfewWindow',
          summary:
              '缩短宵禁时段至 ${formatMinuteOfDay(proposed.nightCurfewStartMin)}-${formatMinuteOfDay(proposed.nightCurfewEndMin)}',
          targetValue: {
            'start': proposed.nightCurfewStartMin,
            'end': proposed.nightCurfewEndMin,
          },
        );
      }
    }

    var nextHardLock = current.focusHardLockDefault;
    if (!current.focusHardLockDefault && proposed.focusHardLockDefault) {
      nextHardLock = true;
      applied.add('已默认开启专注铁锁模式（立即生效）');
    } else if (current.focusHardLockDefault && !proposed.focusHardLockDefault) {
      enqueuePending(
        field: 'focusHardLockDefault',
        summary: '关闭专注默认铁锁模式',
        targetValue: false,
      );
    }

    var nextAutoStart = current.autoStartEnabled;
    if (!current.autoStartEnabled && proposed.autoStartEnabled) {
      nextAutoStart = true;
      applied.add('已开启开机自启（立即生效）');
    } else if (current.autoStartEnabled && !proposed.autoStartEnabled) {
      enqueuePending(
        field: 'autoStartEnabled',
        summary: '关闭开机自启',
        targetValue: false,
      );
    }

    var nextCooldown = current.loosenCooldownHours;
    if (proposed.loosenCooldownHours > current.loosenCooldownHours) {
      nextCooldown = proposed.loosenCooldownHours;
      applied.add('改松冷却期延长至 $nextCooldown 小时（立即生效）');
    } else if (proposed.loosenCooldownHours < current.loosenCooldownHours) {
      enqueuePending(
        field: 'loosenCooldownHours',
        summary: '缩短改松冷却期至 ${proposed.loosenCooldownHours} 小时',
        targetValue: proposed.loosenCooldownHours,
      );
    }

    final nextWorkApps = _reconcileList(
      current: current.workApps,
      proposed: proposed.workApps,
      addingIsTighter: false,
      addField: 'add_workApp',
      removeField: 'remove_workApp',
      labelPrefix: '工作白名单应用',
      applied: applied,
      enqueuePending: enqueuePending,
    );

    final nextWorkKeywords = _reconcileList(
      current: current.workTitleKeywords,
      proposed: proposed.workTitleKeywords,
      addingIsTighter: false,
      addField: 'add_workKeyword',
      removeField: 'remove_workKeyword',
      labelPrefix: '工作标题关键词',
      applied: applied,
      enqueuePending: enqueuePending,
    );

    final nextDualApps = _reconcileList(
      current: current.dualApps,
      proposed: proposed.dualApps,
      addingIsTighter: true,
      addField: 'add_dualApp',
      removeField: 'remove_dualApp',
      labelPrefix: '双用途监控应用',
      applied: applied,
      enqueuePending: enqueuePending,
    );

    final nextDualSites = _reconcileList(
      current: current.dualSiteKeywords,
      proposed: proposed.dualSiteKeywords,
      addingIsTighter: true,
      addField: 'add_dualSiteKeyword',
      removeField: 'remove_dualSiteKeyword',
      labelPrefix: '灰区网站关键词',
      applied: applied,
      enqueuePending: enqueuePending,
    );

    final nextBlockedApps = _reconcileList(
      current: current.blockedApps,
      proposed: proposed.blockedApps,
      addingIsTighter: true,
      addField: 'add_blockedApp',
      removeField: 'remove_blockedApp',
      labelPrefix: '娱乐拦截应用',
      applied: applied,
      enqueuePending: enqueuePending,
    );

    final nextBlockedKeywords = _reconcileList(
      current: current.blockedTitleKeywords,
      proposed: proposed.blockedTitleKeywords,
      addingIsTighter: true,
      addField: 'add_blockedKeyword',
      removeField: 'remove_blockedKeyword',
      labelPrefix: '娱乐拦截关键词',
      applied: applied,
      enqueuePending: enqueuePending,
    );

    final nextRules = current.copyWith(
      dailyEntertainmentLimitMin: nextLimit,
      nightCurfewEnabled: nextCurfewEnabled,
      nightCurfewStartMin: nextStart,
      nightCurfewEndMin: nextEnd,
      focusHardLockDefault: nextHardLock,
      autoStartEnabled: nextAutoStart,
      loosenCooldownHours: nextCooldown,
      workApps: nextWorkApps,
      workTitleKeywords: nextWorkKeywords,
      dualApps: nextDualApps,
      dualSiteKeywords: nextDualSites,
      blockedApps: nextBlockedApps,
      blockedTitleKeywords: nextBlockedKeywords,
      pendingLoosenings: nextPending,
    );

    return RuleEditResult(
      nextRules: nextRules,
      appliedImmediately: applied,
      queuedLoosenings: queuedLabels,
    );
  }

  static List<String> _reconcileList({
    required List<String> current,
    required List<String> proposed,
    required bool addingIsTighter,
    required String addField,
    required String removeField,
    required String labelPrefix,
    required List<String> applied,
    required void Function({
      required String field,
      required String summary,
      required Object? targetValue,
    }) enqueuePending,
  }) {
    final curNorm = current.map((e) => e.trim().toLowerCase()).where((e) => e.isNotEmpty).toList();
    final propNorm = proposed.map((e) => e.trim().toLowerCase()).where((e) => e.isNotEmpty).toSet();
    final curSet = curNorm.toSet();

    final result = List<String>.from(curNorm);

    // Additions
    for (final item in propNorm) {
      if (!curSet.contains(item)) {
        if (addingIsTighter) {
          result.add(item);
          applied.add('新增$labelPrefix「$item」（立即生效）');
        } else {
          enqueuePending(
            field: addField,
            summary: '新增$labelPrefix「$item」',
            targetValue: item,
          );
        }
      }
    }

    // Removals
    for (final item in curNorm) {
      if (!propNorm.contains(item)) {
        if (!addingIsTighter) {
          result.remove(item);
          applied.add('移出$labelPrefix「$item」（立即生效）');
        } else {
          enqueuePending(
            field: removeField,
            summary: '移出$labelPrefix「$item」',
            targetValue: item,
          );
        }
      }
    }

    return result;
  }

  static ({GuardRulesConfig rules, List<String> appliedSummaries})
      applyMaturePendingChanges(GuardRulesConfig rules, int nowUtcMs) {
    if (rules.pendingLoosenings.isEmpty) {
      return (rules: rules, appliedSummaries: const []);
    }
    var updated = rules;
    final remaining = <PendingRuleChange>[];
    final appliedSummaries = <String>[];

    for (final item in rules.pendingLoosenings) {
      if (item.effectiveAtUtcMs > nowUtcMs) {
        remaining.add(item);
        continue;
      }
      appliedSummaries.add(item.summary);
      switch (item.field) {
        case 'dailyEntertainmentLimitMin':
          final v = int.tryParse((item.targetValue ?? '').toString());
          if (v != null) updated = updated.copyWith(dailyEntertainmentLimitMin: v);
        case 'nightCurfewEnabled':
          updated = updated.copyWith(nightCurfewEnabled: item.targetValue == true);
        case 'nightCurfewWindow':
          if (item.targetValue is Map) {
            final m = item.targetValue as Map;
            final s = int.tryParse((m['start'] ?? '').toString());
            final e = int.tryParse((m['end'] ?? '').toString());
            if (s != null && e != null) {
              updated = updated.copyWith(
                nightCurfewStartMin: s,
                nightCurfewEndMin: e,
              );
            }
          }
        case 'focusHardLockDefault':
          updated =
              updated.copyWith(focusHardLockDefault: item.targetValue == true);
        case 'autoStartEnabled':
          updated = updated.copyWith(autoStartEnabled: item.targetValue == true);
        case 'loosenCooldownHours':
          final v = int.tryParse((item.targetValue ?? '').toString());
          if (v != null) updated = updated.copyWith(loosenCooldownHours: v);
        case 'add_workApp':
          final v = (item.targetValue ?? '').toString().trim().toLowerCase();
          if (v.isNotEmpty && !updated.workApps.contains(v)) {
            updated = updated.copyWith(workApps: [...updated.workApps, v]);
          }
        case 'add_workKeyword':
          final v = (item.targetValue ?? '').toString().trim().toLowerCase();
          if (v.isNotEmpty && !updated.workTitleKeywords.contains(v)) {
            updated = updated.copyWith(
              workTitleKeywords: [...updated.workTitleKeywords, v],
            );
          }
        case 'remove_dualApp':
          final v = (item.targetValue ?? '').toString().trim().toLowerCase();
          updated = updated.copyWith(
            dualApps: updated.dualApps.where((e) => e != v).toList(),
          );
        case 'remove_dualSiteKeyword':
          final v = (item.targetValue ?? '').toString().trim().toLowerCase();
          updated = updated.copyWith(
            dualSiteKeywords:
                updated.dualSiteKeywords.where((e) => e != v).toList(),
          );
        case 'remove_blockedApp':
          final v = (item.targetValue ?? '').toString().trim().toLowerCase();
          updated = updated.copyWith(
            blockedApps: updated.blockedApps.where((e) => e != v).toList(),
          );
        case 'remove_blockedKeyword':
          final v = (item.targetValue ?? '').toString().trim().toLowerCase();
          updated = updated.copyWith(
            blockedTitleKeywords:
                updated.blockedTitleKeywords.where((e) => e != v).toList(),
          );
      }
    }

    return (
      rules: updated.copyWith(pendingLoosenings: remaining),
      appliedSummaries: appliedSummaries,
    );
  }

  static String generateFrictionChallenge(int length, {Random? rng}) {
    final random = rng ?? Random.secure();
    final buf = StringBuffer();
    for (var i = 0; i < length; i++) {
      buf.write(_challengeAlphabet[random.nextInt(_challengeAlphabet.length)]);
    }
    return buf.toString();
  }
}
