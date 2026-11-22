import 'dart:math';

import 'package:dsh_go_fish_pc/models/guard_models.dart';
import 'package:dsh_go_fish_pc/services/bridge_local_sync.dart';
import 'package:dsh_go_fish_pc/services/rule_engine.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('classifyWindow handles neverBlock, civil-service work, blocked, and grey-zone sites', () {
    const rules = GuardRulesConfig();

    expect(
      RuleEngine.classifyWindow(app: 'explorer', title: '桌面', rules: rules).tier,
      ActivityTier.neverBlock,
    );
    expect(
      RuleEngine.classifyWindow(app: 'dsh_fish_watchdog', title: '', rules: rules).tier,
      ActivityTier.neverBlock,
    );
    expect(
      RuleEngine.classifyWindow(app: 'Code.exe', title: 'main.dart', rules: rules).tier,
      ActivityTier.work,
    );
    expect(
      RuleEngine.classifyWindow(
        app: 'chrome',
        title: 'Bilibili 【花生十三】行测资料分析第3讲',
        rules: rules,
      ).tier,
      ActivityTier.work,
    );
    expect(
      RuleEngine.classifyWindow(
        app: 'msedge',
        title: '抖音-记录美好生活',
        rules: rules,
      ).tier,
      ActivityTier.blocked,
    );
    expect(
      RuleEngine.classifyWindow(app: 'steam', title: 'Steam', rules: rules).tier,
      ActivityTier.blocked,
    );

    final grey = RuleEngine.classifyWindow(
      app: 'chrome',
      title: 'Bilibili 某游戏集锦',
      rules: rules,
    );
    expect(grey.tier, ActivityTier.dual);
    expect(grey.isExplicitGreyZone, isTrue);

    final neutralBrowser = RuleEngine.classifyWindow(
      app: 'chrome',
      title: '浙江省公务员考试录用系统',
      rules: rules,
    );
    expect(neutralBrowser.tier, ActivityTier.dual);
    expect(neutralBrowser.isExplicitGreyZone, isFalse);
  });

  test('Beijing time & cross-midnight night curfew evaluate accurately regardless of host TZ', () {
    // 2026-11-19 23:45 Beijing == 2026-11-19 15:45 UTC
    final lateNightUtc = DateTime.utc(2026, 11, 19, 15, 45).millisecondsSinceEpoch;
    // 2026-11-20 03:00 Beijing == 2026-11-19 19:00 UTC
    final earlyMorningUtc = DateTime.utc(2026, 11, 19, 19, 0).millisecondsSinceEpoch;
    // 2026-11-20 07:30 Beijing == 2026-11-19 23:30 UTC
    final dayTimeUtc = DateTime.utc(2026, 11, 19, 23, 30).millisecondsSinceEpoch;

    const rules = GuardRulesConfig(
      nightCurfewEnabled: true,
      nightCurfewStartMin: 23 * 60 + 30,
      nightCurfewEndMin: 7 * 60,
    );

    expect(RuleEngine.beijingDayKey(lateNightUtc), '2026-11-19');
    expect(RuleEngine.beijingDayKey(earlyMorningUtc), '2026-11-20');
    expect(RuleEngine.isInNightCurfew(rules, lateNightUtc), isTrue);
    expect(RuleEngine.isInNightCurfew(rules, earlyMorningUtc), isTrue);
    expect(RuleEngine.isInNightCurfew(rules, dayTimeUtc), isFalse);
  });

  test('evaluateEnforcement respects quota, focus lock, AI cache, and never kills browsers', () {
    final now = DateTime.utc(2026, 11, 19, 6, 0).millisecondsSinceEpoch; // 14:00 Beijing
    const rules = GuardRulesConfig(dailyEntertainmentLimitMin: 60);
    const ledgerOk = UsageLedger(dayKey: '2026-11-19', entertainmentSeconds: 10 * 60);
    const ledgerExhausted = UsageLedger(dayKey: '2026-11-19', entertainmentSeconds: 60 * 60);
    const noFocus = FocusSessionState();
    final inFocus = FocusSessionState(
      active: true,
      taskName: '行测套卷',
      startedAtUtcMs: now - 60000,
      endsAtUtcMs: now + 20 * 60000,
      hardLocked: true,
    );
    const aiOn = AiProviderConfig(enabled: true, baseUrl: 'http://x', model: 'm');

    // Within quota & no focus -> entertainment allowed
    final withinQuota = RuleEngine.evaluateEnforcement(
      app: 'steam',
      title: 'Steam',
      isAfk: false,
      rules: rules,
      ledger: ledgerOk,
      focusSession: noFocus,
      activeLeases: const [],
      aiConfig: aiOn,
      aiCache: const {},
      nowUtcMs: now,
    );
    expect(withinQuota.allowed, isTrue);

    // Exhausted quota on steam -> terminateAndShield
    final killSteam = RuleEngine.evaluateEnforcement(
      app: 'steam',
      title: 'Steam',
      isAfk: false,
      rules: rules,
      ledger: ledgerExhausted,
      focusSession: noFocus,
      activeLeases: const [],
      aiConfig: aiOn,
      aiCache: const {},
      nowUtcMs: now,
    );
    expect(killSteam.allowed, isFalse);
    expect(killSteam.action, EnforcementAction.terminateAndShield);

    // In focus on browser entertainment -> minimizeAndShield (never kill browser)
    final minBrowser = RuleEngine.evaluateEnforcement(
      app: 'chrome',
      title: '抖音网页版',
      isAfk: false,
      rules: rules,
      ledger: ledgerOk,
      focusSession: inFocus,
      activeLeases: const [],
      aiConfig: aiOn,
      aiCache: const {},
      nowUtcMs: now,
    );
    expect(minBrowser.allowed, isFalse);
    expect(minBrowser.action, EnforcementAction.minimizeAndShield);

    // Grey zone on Bilibili (ambiguous title) during focus with AI configured -> queryAi
    final queryBili = RuleEngine.evaluateEnforcement(
      app: 'chrome',
      title: 'bilibili 推荐视频合集',
      isAfk: false,
      rules: rules,
      ledger: ledgerOk,
      focusSession: inFocus,
      activeLeases: const [],
      aiConfig: aiOn,
      aiCache: const {},
      nowUtcMs: now,
    );
    expect(queryBili.action, EnforcementAction.queryAi);

    // Active emergency lease overrides enforcement
    final lease = StudyLease(
      id: 'e1',
      app: '*',
      reason: '接快递',
      grantedBy: 'emergency',
      createdAtUtcMs: now - 1000,
      expiresAtUtcMs: now + 60000,
    );
    final bypassed = RuleEngine.evaluateEnforcement(
      app: 'steam',
      title: 'Steam',
      isAfk: false,
      rules: rules,
      ledger: ledgerExhausted,
      focusSession: inFocus,
      activeLeases: [lease],
      aiConfig: aiOn,
      aiCache: const {},
      nowUtcMs: now,
    );
    expect(bypassed.allowed, isTrue);
  });

  test('applyRuleEdit enforces Pluckeye tightening-immediate / loosening-cooldown policy', () {
    final now = DateTime.utc(2026, 11, 19, 4, 0).millisecondsSinceEpoch;
    const current = GuardRulesConfig(
      strictGuardArmed: true,
      dailyEntertainmentLimitMin: 60,
      loosenCooldownHours: 12,
      nightCurfewEnabled: true,
    );

    // Tightening: reduce quota, add blocked app -> immediate; Loosening: add work app, disable curfew -> queued
    final proposed = current.copyWith(
      dailyEntertainmentLimitMin: 30,
      blockedApps: [...current.blockedApps, 'minecraft'],
      workApps: [...current.workApps, 'steam'], // sneaky bypass attempt!
      nightCurfewEnabled: false,
    );

    final res = RuleEngine.applyRuleEdit(
      current: current,
      proposed: proposed,
      nowUtcMs: now,
    );

    expect(res.nextRules.dailyEntertainmentLimitMin, 30);
    expect(res.nextRules.blockedApps, contains('minecraft'));
    // Sneaky workApp & disabling curfew must NOT take effect yet
    expect(res.nextRules.workApps, isNot(contains('steam')));
    expect(res.nextRules.nightCurfewEnabled, isTrue);
    expect(res.nextRules.pendingLoosenings.length, 2);

    // Before 12h: still queued
    final before = RuleEngine.applyMaturePendingChanges(
      res.nextRules,
      now + 11 * 3600 * 1000,
    );
    expect(before.appliedSummaries, isEmpty);
    expect(before.rules.nightCurfewEnabled, isTrue);

    // After 12h: matured and applied
    final after = RuleEngine.applyMaturePendingChanges(
      res.nextRules,
      now + 12 * 3600 * 1000,
    );
    expect(after.appliedSummaries.length, 2);
    expect(after.rules.nightCurfewEnabled, isFalse);
    expect(after.rules.workApps, contains('steam'));
    expect(after.rules.pendingLoosenings, isEmpty);
  });

  test('generateFrictionChallenge emits exact length without ambiguous characters', () {
    final c48 = RuleEngine.generateFrictionChallenge(48, rng: Random(42));
    final c80 = RuleEngine.generateFrictionChallenge(80, rng: Random(42));
    expect(c48.length, 48);
    expect(c80.length, 80);
    expect(c80.contains(RegExp(r'[01IOL]')), isFalse);
  });

  test('browsers and neverBlock/desktop-stack tools can never be terminated even if misconfigured', () {
    final now = DateTime.utc(2026, 11, 19, 6, 0).millisecondsSinceEpoch;
    // Even if someone puts chrome or obsidian into blockedApps, safety invariants hold.
    final hostileRules = const GuardRulesConfig(dailyEntertainmentLimitMin: 0).copyWith(
      blockedApps: [
        ...GuardRulesConfig.defaultBlockedApps,
        'chrome',
        'msedge',
        'firefox',
        'obsidian',
        'nekobox',
      ],
    );

    // Every desktop stack process in neverBlockProcesses is never blocked or terminated.
    for (final tool in const [
      'obsidian',
      'code',
      'cursor',
      'antigravity',
      'windowsterminal',
      'pwsh',
      'powershell',
      'cmd',
      'node',
      'python',
      'tailscale',
      'clash',
      'mihomo',
      'nekobox',
      'v2rayn',
    ]) {
      expect(RuleEngine.isNeverBlockApp(tool), isTrue, reason: tool);
      expect(RuleEngine.canTerminateProcess(tool, hostileRules), isFalse, reason: tool);
      final dec = RuleEngine.evaluateEnforcement(
        app: tool,
        title: '抖音测试标题',
        isAfk: false,
        rules: hostileRules,
        ledger: const UsageLedger(dayKey: '2026-11-19'),
        focusSession: const FocusSessionState(),
        activeLeases: const [],
        aiConfig: const AiProviderConfig(),
        aiCache: const {},
        nowUtcMs: now,
      );
      expect(dec.allowed, isTrue, reason: tool);
    }

    // Browsers must always downgrade to minimizeAndShield, never terminateAndShield.
    for (final browser in RuleEngine.browserProcesses) {
      expect(RuleEngine.canTerminateProcess(browser, hostileRules), isFalse, reason: browser);
      final dec = RuleEngine.evaluateEnforcement(
        app: browser,
        title: '抖音网页版',
        isAfk: false,
        rules: hostileRules,
        ledger: const UsageLedger(dayKey: '2026-11-19'),
        focusSession: const FocusSessionState(),
        activeLeases: const [],
        aiConfig: const AiProviderConfig(),
        aiCache: const {},
        nowUtcMs: now,
      );
      expect(dec.allowed, isFalse, reason: browser);
      expect(dec.action, EnforcementAction.minimizeAndShield, reason: browser);
    }

    // Browser learning/knowledge titles are classified as work and always allowed.
    for (final kw in const [
      'Gemini',
      'Claude',
      'ChatGPT',
      'DeepTutor',
      'http://127.0.0.1:8001/chat',
      'GitHub - repo',
      'Obsidian 笔记',
      '粉笔模考',
      '行测',
      '申论',
      '资料分析',
      '判断推理',
      '面试准备',
      '简历修改',
      '考公',
      '牛客网',
      'LeetCode',
    ]) {
      final cls = RuleEngine.classifyWindow(
        app: 'msedge',
        title: kw,
        rules: hostileRules,
      );
      expect(cls.tier, ActivityTier.work, reason: kw);
    }

    // Multi-purpose app matched only by blocked keyword also downgrades to minimizeAndShield.
    final weixinDec = RuleEngine.evaluateEnforcement(
      app: 'weixin',
      title: '抖音分享链接',
      isAfk: false,
      rules: hostileRules,
      ledger: const UsageLedger(dayKey: '2026-11-19'),
      focusSession: const FocusSessionState(),
      activeLeases: const [],
      aiConfig: const AiProviderConfig(),
      aiCache: const {},
      nowUtcMs: now,
    );
    expect(weixinDec.allowed, isFalse);
    expect(weixinDec.action, EnforcementAction.minimizeAndShield);
  });

  test('evaluateEnforcement synchronizes tightening and relaxation from local phone bridge', () {
    final now = DateTime.utc(2026, 11, 19, 6, 0).millisecondsSinceEpoch;
    const rules = GuardRulesConfig(dailyEntertainmentLimitMin: 60);
    const ledgerOk = UsageLedger(dayKey: '2026-11-19', entertainmentSeconds: 5 * 60);
    const ledgerExhausted = UsageLedger(dayKey: '2026-11-19', entertainmentSeconds: 60 * 60);

    // Phone tighten (overdue seal lock) blocks Steam even though PC quota remains.
    const phoneTight = PhoneBridgeSnapshot(
      bridgeOnline: true,
      phoneOnline: true,
      syncedAtUtcMs: 1000,
      fetchedAtUtcMs: 2000,
      directive: PhoneSyncDirective.tighten,
      reason: '手机封印逾期严格锁：「资料分析」（0/20，最晚 15:00）',
    );
    final blockedByPhone = RuleEngine.evaluateEnforcement(
      app: 'steam',
      title: 'Steam',
      isAfk: false,
      rules: rules,
      ledger: ledgerOk,
      focusSession: const FocusSessionState(),
      activeLeases: const [],
      aiConfig: const AiProviderConfig(),
      aiCache: const {},
      nowUtcMs: now,
      phoneBridge: phoneTight,
      phoneLockSyncEnabled: true,
    );
    expect(blockedByPhone.allowed, isFalse);
    expect(blockedByPhone.action, EnforcementAction.terminateAndShield);
    expect(blockedByPhone.reason, contains('手机封印逾期严格锁'));

    // Disabling voluntary toggle reverts immediately to PC independent rules.
    final ignoredWhenOff = RuleEngine.evaluateEnforcement(
      app: 'steam',
      title: 'Steam',
      isAfk: false,
      rules: rules,
      ledger: ledgerOk,
      focusSession: const FocusSessionState(),
      activeLeases: const [],
      aiConfig: const AiProviderConfig(),
      aiCache: const {},
      nowUtcMs: now,
      phoneBridge: phoneTight,
      phoneLockSyncEnabled: false,
    );
    expect(ignoredWhenOff.allowed, isTrue);

    // Phone relax (spend balance / emergency / recovery) relaxes PC block even when PC quota exhausted.
    const phoneRelax = PhoneBridgeSnapshot(
      bridgeOnline: true,
      phoneOnline: true,
      syncedAtUtcMs: 1000,
      fetchedAtUtcMs: 2000,
      directive: PhoneSyncDirective.relax,
      reason: '手机花余额放风中（20 分钟，至 15:30）',
    );
    final relaxedByPhone = RuleEngine.evaluateEnforcement(
      app: 'chrome',
      title: '抖音网页版',
      isAfk: false,
      rules: rules,
      ledger: ledgerExhausted,
      focusSession: const FocusSessionState(),
      activeLeases: const [],
      aiConfig: const AiProviderConfig(),
      aiCache: const {},
      nowUtcMs: now,
      phoneBridge: phoneRelax,
      phoneLockSyncEnabled: true,
    );
    expect(relaxedByPhone.allowed, isTrue);
    expect(relaxedByPhone.reason, contains('手机同步放行'));

    // Offline bridge falls back 100% to PC independent rules.
    const offline = PhoneBridgeSnapshot.offline();
    final offlineFallback = RuleEngine.evaluateEnforcement(
      app: 'chrome',
      title: '抖音网页版',
      isAfk: false,
      rules: rules,
      ledger: ledgerExhausted,
      focusSession: const FocusSessionState(),
      activeLeases: const [],
      aiConfig: const AiProviderConfig(),
      aiCache: const {},
      nowUtcMs: now,
      phoneBridge: offline,
      phoneLockSyncEnabled: true,
    );
    expect(offlineFallback.allowed, isFalse);
    expect(offlineFallback.action, EnforcementAction.minimizeAndShield);
  });
}
