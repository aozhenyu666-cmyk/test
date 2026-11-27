import 'dart:convert';

enum ActivityTier {
  neverBlock('never_block', '系统底座'),
  work('work', '专注学习/工作'),
  dual('dual', '灰区/双用途'),
  blocked('blocked', '明确娱乐/游戏');

  const ActivityTier(this.wire, this.label);
  final String wire;
  final String label;

  static ActivityTier fromWire(String? raw) {
    return ActivityTier.values.firstWhere(
      (e) => e.wire == raw,
      orElse: () => ActivityTier.dual,
    );
  }
}

class PendingRuleChange {
  const PendingRuleChange({
    required this.id,
    required this.field,
    required this.summary,
    required this.targetValue,
    required this.requestedAtUtcMs,
    required this.effectiveAtUtcMs,
  });

  final String id;
  final String field;
  final String summary;
  final Object? targetValue;
  final int requestedAtUtcMs;
  final int effectiveAtUtcMs;

  Map<String, Object?> toJson() => {
        'id': id,
        'field': field,
        'summary': summary,
        'targetValue': targetValue,
        'requestedAtUtcMs': requestedAtUtcMs,
        'effectiveAtUtcMs': effectiveAtUtcMs,
      };

  static PendingRuleChange? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final id = (raw['id'] ?? '').toString();
    final field = (raw['field'] ?? '').toString();
    if (id.isEmpty || field.isEmpty) return null;
    return PendingRuleChange(
      id: id,
      field: field,
      summary: (raw['summary'] ?? '').toString(),
      targetValue: raw['targetValue'],
      requestedAtUtcMs: _asInt(raw['requestedAtUtcMs']),
      effectiveAtUtcMs: _asInt(raw['effectiveAtUtcMs']),
    );
  }
}

class GuardRulesConfig {
  const GuardRulesConfig({
    this.dailyEntertainmentLimitMin = 60,
    this.nightCurfewEnabled = true,
    this.nightCurfewStartMin = 23 * 60 + 30,
    this.nightCurfewEndMin = 7 * 60,
    this.strictGuardArmed = false,
    this.autoStartEnabled = true,
    this.focusHardLockDefault = true,
    this.loosenCooldownHours = 12,
    this.workApps = defaultWorkApps,
    this.workTitleKeywords = defaultWorkTitleKeywords,
    this.dualApps = defaultDualApps,
    this.dualSiteKeywords = defaultDualSiteKeywords,
    this.blockedApps = defaultBlockedApps,
    this.blockedTitleKeywords = defaultBlockedTitleKeywords,
    this.pendingLoosenings = const [],
  });

  static const List<String> defaultWorkApps = [
    'code',
    'cursor',
    'windsurf',
    'antigravity',
    'idea',
    'pycharm',
    'webstorm',
    'goland',
    'rustrover',
    'devenv',
    'wt',
    'windowsterminal',
    'powershell',
    'pwsh',
    'cmd',
    'obsidian',
    'notion',
    'logseq',
    'typora',
    'winword',
    'word',
    'wps',
    'et',
    'wpp',
    'excel',
    'acrobat',
    'sumatrapdf',
    'zotero',
    'onenote',
    'anki',
    'cajviewer',
    'foxitpdfreader',
    'wpspdf',
    'feishu',
    'lark',
    'dingtalk',
  ];

  static const List<String> defaultWorkTitleKeywords = [
    '行测',
    '申论',
    '考公',
    '国考',
    '省考',
    '公考',
    '粉笔',
    '华图',
    '中公',
    '花生十三',
    '资料分析',
    '判断推理',
    '言语理解',
    '数量关系',
    '常识',
    '面试',
    '求职',
    '简历',
    'boss直聘',
    '牛客',
    '力扣',
    'leetcode',
    'github',
    'obsidian',
    'deeptutor',
    '127.0.0.1:8001',
    'stackoverflow',
    'arxiv',
    'mdn',
    'wikipedia',
    'rfc',
    'docs',
    '论文',
    '真题',
    '课程',
    '教程',
    '公开课',
    '学习',
    '笔记',
    '源码',
    '算法',
    'gemini',
    'chatgpt',
    'claude',
    'deepseek',
    'anki',
    '万世铭',
    '公考雷达',
    '粉笔职教',
  ];

  static const List<String> defaultDualApps = [
    'chrome',
    'msedge',
    'edge',
    'firefox',
    'brave',
    'vivaldi',
    'opera',
    'arc',
    'weixin',
    'wechat',
    'qq',
  ];

  static const List<String> defaultDualSiteKeywords = [
    'bilibili',
    'b站',
    '哔哩哔哩',
    'youtube',
    'zhihu',
    '知乎',
    'xiaohongshu',
    '小红书',
    'weibo',
    '微博',
    'reddit',
  ];

  static const List<String> defaultBlockedApps = [
    'steam',
    'steamwebhelper',
    'wegame',
    'epicgameslauncher',
    'leagueclient',
    'leagueclientux',
    'leagueoflegends',
    'yuanshen',
    'genshinimpact',
    'valorant',
    'cs2',
    'dota2',
    'douyin',
    'tiktok',
    'iqiyi',
    'youku',
    'qqlive',
    'txvideo',
  ];

  static const List<String> defaultBlockedTitleKeywords = [
    '抖音',
    'douyin',
    '快手',
    '斗鱼',
    '虎牙',
    'twitch',
    'netflix',
    '爱奇艺',
    '优酷',
    '腾讯视频',
    '芒果tv',
    '小游戏',
    '游戏实况',
    '鬼畜',
    '搞笑',
    '综艺',
    '热搜吃瓜',
  ];

  final int dailyEntertainmentLimitMin;
  final bool nightCurfewEnabled;
  final int nightCurfewStartMin;
  final int nightCurfewEndMin;
  final bool strictGuardArmed;
  final bool autoStartEnabled;
  final bool focusHardLockDefault;
  final int loosenCooldownHours;
  final List<String> workApps;
  final List<String> workTitleKeywords;
  final List<String> dualApps;
  final List<String> dualSiteKeywords;
  final List<String> blockedApps;
  final List<String> blockedTitleKeywords;
  final List<PendingRuleChange> pendingLoosenings;

  GuardRulesConfig copyWith({
    int? dailyEntertainmentLimitMin,
    bool? nightCurfewEnabled,
    int? nightCurfewStartMin,
    int? nightCurfewEndMin,
    bool? strictGuardArmed,
    bool? autoStartEnabled,
    bool? focusHardLockDefault,
    int? loosenCooldownHours,
    List<String>? workApps,
    List<String>? workTitleKeywords,
    List<String>? dualApps,
    List<String>? dualSiteKeywords,
    List<String>? blockedApps,
    List<String>? blockedTitleKeywords,
    List<PendingRuleChange>? pendingLoosenings,
  }) {
    return GuardRulesConfig(
      dailyEntertainmentLimitMin:
          (dailyEntertainmentLimitMin ?? this.dailyEntertainmentLimitMin)
              .clamp(0, 360),
      nightCurfewEnabled: nightCurfewEnabled ?? this.nightCurfewEnabled,
      nightCurfewStartMin:
          (nightCurfewStartMin ?? this.nightCurfewStartMin).clamp(0, 1439),
      nightCurfewEndMin:
          (nightCurfewEndMin ?? this.nightCurfewEndMin).clamp(0, 1439),
      strictGuardArmed: strictGuardArmed ?? this.strictGuardArmed,
      autoStartEnabled: autoStartEnabled ?? this.autoStartEnabled,
      focusHardLockDefault: focusHardLockDefault ?? this.focusHardLockDefault,
      loosenCooldownHours:
          (loosenCooldownHours ?? this.loosenCooldownHours).clamp(1, 48),
      workApps: List.unmodifiable(workApps ?? this.workApps),
      workTitleKeywords:
          List.unmodifiable(workTitleKeywords ?? this.workTitleKeywords),
      dualApps: List.unmodifiable(dualApps ?? this.dualApps),
      dualSiteKeywords:
          List.unmodifiable(dualSiteKeywords ?? this.dualSiteKeywords),
      blockedApps: List.unmodifiable(blockedApps ?? this.blockedApps),
      blockedTitleKeywords:
          List.unmodifiable(blockedTitleKeywords ?? this.blockedTitleKeywords),
      pendingLoosenings:
          List.unmodifiable(pendingLoosenings ?? this.pendingLoosenings),
    );
  }

  Map<String, Object?> toJson() => {
        'dailyEntertainmentLimitMin': dailyEntertainmentLimitMin,
        'nightCurfewEnabled': nightCurfewEnabled,
        'nightCurfewStartMin': nightCurfewStartMin,
        'nightCurfewEndMin': nightCurfewEndMin,
        'strictGuardArmed': strictGuardArmed,
        'autoStartEnabled': autoStartEnabled,
        'focusHardLockDefault': focusHardLockDefault,
        'loosenCooldownHours': loosenCooldownHours,
        'workApps': workApps,
        'workTitleKeywords': workTitleKeywords,
        'dualApps': dualApps,
        'dualSiteKeywords': dualSiteKeywords,
        'blockedApps': blockedApps,
        'blockedTitleKeywords': blockedTitleKeywords,
        'pendingLoosenings': pendingLoosenings.map((e) => e.toJson()).toList(),
      };

  factory GuardRulesConfig.fromJson(Map<String, Object?> raw) {
    final pendingRaw = raw['pendingLoosenings'];
    final pending = <PendingRuleChange>[];
    if (pendingRaw is List) {
      for (final item in pendingRaw) {
        final parsed = PendingRuleChange.fromJson(item);
        if (parsed != null) pending.add(parsed);
      }
    }
    return GuardRulesConfig(
      dailyEntertainmentLimitMin: _asInt(
        raw['dailyEntertainmentLimitMin'],
        fallback: 60,
      ).clamp(0, 360),
      nightCurfewEnabled: raw['nightCurfewEnabled'] != false,
      nightCurfewStartMin: _asInt(
        raw['nightCurfewStartMin'],
        fallback: 23 * 60 + 30,
      ).clamp(0, 1439),
      nightCurfewEndMin: _asInt(
        raw['nightCurfewEndMin'],
        fallback: 7 * 60,
      ).clamp(0, 1439),
      strictGuardArmed: raw['strictGuardArmed'] == true,
      autoStartEnabled: raw['autoStartEnabled'] != false,
      focusHardLockDefault: raw['focusHardLockDefault'] != false,
      loosenCooldownHours: _asInt(
        raw['loosenCooldownHours'],
        fallback: 12,
      ).clamp(1, 48),
      workApps: _asStringList(raw['workApps'], defaultWorkApps),
      workTitleKeywords:
          _asStringList(raw['workTitleKeywords'], defaultWorkTitleKeywords),
      dualApps: _asStringList(raw['dualApps'], defaultDualApps),
      dualSiteKeywords:
          _asStringList(raw['dualSiteKeywords'], defaultDualSiteKeywords),
      blockedApps: _asStringList(raw['blockedApps'], defaultBlockedApps),
      blockedTitleKeywords: _asStringList(
        raw['blockedTitleKeywords'],
        defaultBlockedTitleKeywords,
      ),
      pendingLoosenings: pending,
    );
  }
}

class StudyLease {
  const StudyLease({
    required this.id,
    required this.app,
    this.titleKeyword = '',
    required this.reason,
    required this.grantedBy,
    required this.createdAtUtcMs,
    required this.expiresAtUtcMs,
  });

  final String id;
  final String app; // exact lower-case process or '*' for emergency pass
  final String titleKeyword;
  final String reason;
  final String grantedBy; // 'ai' | 'emergency'
  final int createdAtUtcMs;
  final int expiresAtUtcMs;

  bool isActiveAt(int nowUtcMs) => nowUtcMs < expiresAtUtcMs;

  Map<String, Object?> toJson() => {
        'id': id,
        'app': app,
        'titleKeyword': titleKeyword,
        'reason': reason,
        'grantedBy': grantedBy,
        'createdAtUtcMs': createdAtUtcMs,
        'expiresAtUtcMs': expiresAtUtcMs,
      };

  static StudyLease? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final id = (raw['id'] ?? '').toString();
    final app = (raw['app'] ?? '').toString();
    if (id.isEmpty || app.isEmpty) return null;
    return StudyLease(
      id: id,
      app: app,
      titleKeyword: (raw['titleKeyword'] ?? '').toString(),
      reason: (raw['reason'] ?? '').toString(),
      grantedBy: (raw['grantedBy'] ?? 'ai').toString(),
      createdAtUtcMs: _asInt(raw['createdAtUtcMs']),
      expiresAtUtcMs: _asInt(raw['expiresAtUtcMs']),
    );
  }
}

class FocusSessionState {
  const FocusSessionState({
    this.active = false,
    this.taskName = '',
    this.startedAtUtcMs = 0,
    this.endsAtUtcMs = 0,
    this.hardLocked = true,
  });

  final bool active;
  final String taskName;
  final int startedAtUtcMs;
  final int endsAtUtcMs;
  final bool hardLocked;

  bool isActiveAt(int nowUtcMs) => active && nowUtcMs < endsAtUtcMs;

  int remainingSecondsAt(int nowUtcMs) {
    if (!isActiveAt(nowUtcMs)) return 0;
    return ((endsAtUtcMs - nowUtcMs) / 1000).ceil().clamp(0, 86400);
  }

  FocusSessionState copyWith({
    bool? active,
    String? taskName,
    int? startedAtUtcMs,
    int? endsAtUtcMs,
    bool? hardLocked,
  }) {
    return FocusSessionState(
      active: active ?? this.active,
      taskName: taskName ?? this.taskName,
      startedAtUtcMs: startedAtUtcMs ?? this.startedAtUtcMs,
      endsAtUtcMs: endsAtUtcMs ?? this.endsAtUtcMs,
      hardLocked: hardLocked ?? this.hardLocked,
    );
  }

  Map<String, Object?> toJson() => {
        'active': active,
        'taskName': taskName,
        'startedAtUtcMs': startedAtUtcMs,
        'endsAtUtcMs': endsAtUtcMs,
        'hardLocked': hardLocked,
      };

  factory FocusSessionState.fromJson(Map<String, Object?> raw) {
    return FocusSessionState(
      active: raw['active'] == true,
      taskName: (raw['taskName'] ?? '').toString(),
      startedAtUtcMs: _asInt(raw['startedAtUtcMs']),
      endsAtUtcMs: _asInt(raw['endsAtUtcMs']),
      hardLocked: raw['hardLocked'] != false,
    );
  }
}

class AiProviderConfig {
  const AiProviderConfig({
    this.preset = 'deepseek',
    this.baseUrl = 'https://api.deepseek.com/v1',
    this.model = 'deepseek-chat',
    this.enabled = false,
    this.autoJudgeDual = true,
    this.customPrompt = '',
  });

  static const Map<String, ({String label, String baseUrl, String model})>
      presets = {
    'deepseek': (
      label: 'DeepSeek',
      baseUrl: 'https://api.deepseek.com/v1',
      model: 'deepseek-chat',
    ),
    'gemini': (
      label: 'Gemini (OpenAI兼容)',
      baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
      model: 'gemini-2.5-flash',
    ),
    'openai': (
      label: 'OpenAI',
      baseUrl: 'https://api.openai.com/v1',
      model: 'gpt-4o-mini',
    ),
    'ollama': (
      label: 'Ollama 本地',
      baseUrl: 'http://127.0.0.1:11434/v1',
      model: 'qwen2.5:7b',
    ),
    'custom': (
      label: '自定义 OpenAI 兼容',
      baseUrl: '',
      model: '',
    ),
  };

  final String preset;
  final String baseUrl;
  final String model;
  final bool enabled;
  final bool autoJudgeDual;
  final String customPrompt;

  bool get isConfigured =>
      enabled && baseUrl.trim().isNotEmpty && model.trim().isNotEmpty;

  AiProviderConfig copyWith({
    String? preset,
    String? baseUrl,
    String? model,
    bool? enabled,
    bool? autoJudgeDual,
    String? customPrompt,
  }) {
    return AiProviderConfig(
      preset: preset ?? this.preset,
      baseUrl: baseUrl ?? this.baseUrl,
      model: model ?? this.model,
      enabled: enabled ?? this.enabled,
      autoJudgeDual: autoJudgeDual ?? this.autoJudgeDual,
      customPrompt: customPrompt ?? this.customPrompt,
    );
  }

  Map<String, Object?> toJson() => {
        'preset': preset,
        'baseUrl': baseUrl,
        'model': model,
        'enabled': enabled,
        'autoJudgeDual': autoJudgeDual,
        'customPrompt': customPrompt,
      };

  factory AiProviderConfig.fromJson(Map<String, Object?> raw) {
    return AiProviderConfig(
      preset: (raw['preset'] ?? 'deepseek').toString(),
      baseUrl: (raw['baseUrl'] ?? 'https://api.deepseek.com/v1').toString(),
      model: (raw['model'] ?? 'deepseek-chat').toString(),
      enabled: raw['enabled'] == true,
      autoJudgeDual: raw['autoJudgeDual'] != false,
      customPrompt: (raw['customPrompt'] ?? '').toString(),
    );
  }
}

class AiJudgmentRecord {
  const AiJudgmentRecord({
    required this.atUtcMs,
    required this.app,
    required this.title,
    required this.verdict,
    required this.leaseMinutes,
    required this.reason,
    this.actionHint = '',
    this.source = 'auto',
  });

  final int atUtcMs;
  final String app;
  final String title;
  final String verdict; // 'allow_study' | 'entertainment' | 'need_check'
  final int leaseMinutes;
  final String reason;
  final String actionHint;
  final String source; // 'auto' | 'appeal'

  Map<String, Object?> toJson() => {
        'atUtcMs': atUtcMs,
        'app': app,
        'title': title,
        'verdict': verdict,
        'leaseMinutes': leaseMinutes,
        'reason': reason,
        'actionHint': actionHint,
        'source': source,
      };

  static AiJudgmentRecord? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final app = (raw['app'] ?? '').toString();
    final verdict = (raw['verdict'] ?? '').toString();
    if (app.isEmpty || verdict.isEmpty) return null;
    return AiJudgmentRecord(
      atUtcMs: _asInt(raw['atUtcMs']),
      app: app,
      title: (raw['title'] ?? '').toString(),
      verdict: verdict,
      leaseMinutes: _asInt(raw['leaseMinutes']),
      reason: (raw['reason'] ?? '').toString(),
      actionHint: (raw['actionHint'] ?? '').toString(),
      source: (raw['source'] ?? 'auto').toString(),
    );
  }
}

class GuardEventLog {
  const GuardEventLog({
    required this.atUtcMs,
    required this.kind,
    required this.detail,
  });

  final int atUtcMs;
  final String kind;
  final String detail;

  Map<String, Object?> toJson() => {
        'atUtcMs': atUtcMs,
        'kind': kind,
        'detail': detail,
      };

  static GuardEventLog? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final kind = (raw['kind'] ?? '').toString();
    if (kind.isEmpty) return null;
    return GuardEventLog(
      atUtcMs: _asInt(raw['atUtcMs']),
      kind: kind,
      detail: (raw['detail'] ?? '').toString(),
    );
  }
}

class UsageLedger {
  const UsageLedger({
    required this.dayKey,
    this.workSeconds = 0,
    this.entertainmentSeconds = 0,
    this.dualSeconds = 0,
    this.afkSeconds = 0,
    this.perAppSeconds = const {},
    this.blocksTriggered = 0,
    this.watchdogRestarts = 0,
    this.emergencyPassesUsed = 0,
    this.aiJudgments = const [],
    this.eventLogs = const [],
  });

  final String dayKey;
  final int workSeconds;
  final int entertainmentSeconds;
  final int dualSeconds;
  final int afkSeconds;
  final Map<String, int> perAppSeconds;
  final int blocksTriggered;
  final int watchdogRestarts;
  final int emergencyPassesUsed;
  final List<AiJudgmentRecord> aiJudgments;
  final List<GuardEventLog> eventLogs;

  double get workMinutes => workSeconds / 60.0;
  double get entertainmentMinutes => entertainmentSeconds / 60.0;
  double get dualMinutes => dualSeconds / 60.0;
  double get afkMinutes => afkSeconds / 60.0;

  UsageLedger copyWith({
    String? dayKey,
    int? workSeconds,
    int? entertainmentSeconds,
    int? dualSeconds,
    int? afkSeconds,
    Map<String, int>? perAppSeconds,
    int? blocksTriggered,
    int? watchdogRestarts,
    int? emergencyPassesUsed,
    List<AiJudgmentRecord>? aiJudgments,
    List<GuardEventLog>? eventLogs,
  }) {
    return UsageLedger(
      dayKey: dayKey ?? this.dayKey,
      workSeconds: workSeconds ?? this.workSeconds,
      entertainmentSeconds: entertainmentSeconds ?? this.entertainmentSeconds,
      dualSeconds: dualSeconds ?? this.dualSeconds,
      afkSeconds: afkSeconds ?? this.afkSeconds,
      perAppSeconds: Map.unmodifiable(perAppSeconds ?? this.perAppSeconds),
      blocksTriggered: blocksTriggered ?? this.blocksTriggered,
      watchdogRestarts: watchdogRestarts ?? this.watchdogRestarts,
      emergencyPassesUsed: emergencyPassesUsed ?? this.emergencyPassesUsed,
      aiJudgments: List.unmodifiable(
        (aiJudgments ?? this.aiJudgments).take(60).toList(),
      ),
      eventLogs: List.unmodifiable(
        (eventLogs ?? this.eventLogs).take(120).toList(),
      ),
    );
  }

  Map<String, Object?> toJson() => {
        'dayKey': dayKey,
        'workSeconds': workSeconds,
        'entertainmentSeconds': entertainmentSeconds,
        'dualSeconds': dualSeconds,
        'afkSeconds': afkSeconds,
        'perAppSeconds': perAppSeconds,
        'blocksTriggered': blocksTriggered,
        'watchdogRestarts': watchdogRestarts,
        'emergencyPassesUsed': emergencyPassesUsed,
        'aiJudgments': aiJudgments.take(60).map((e) => e.toJson()).toList(),
        'eventLogs': eventLogs.take(120).map((e) => e.toJson()).toList(),
      };

  factory UsageLedger.fromJson(Map<String, Object?> raw, String fallbackDay) {
    final appMap = <String, int>{};
    final rawApps = raw['perAppSeconds'];
    if (rawApps is Map) {
      for (final entry in rawApps.entries) {
        final k = entry.key.toString();
        if (k.isNotEmpty) appMap[k] = _asInt(entry.value);
      }
    }
    final judgments = <AiJudgmentRecord>[];
    if (raw['aiJudgments'] is List) {
      for (final item in raw['aiJudgments'] as List) {
        final parsed = AiJudgmentRecord.fromJson(item);
        if (parsed != null) judgments.add(parsed);
      }
    }
    final logs = <GuardEventLog>[];
    if (raw['eventLogs'] is List) {
      for (final item in raw['eventLogs'] as List) {
        final parsed = GuardEventLog.fromJson(item);
        if (parsed != null) logs.add(parsed);
      }
    }
    return UsageLedger(
      dayKey: (raw['dayKey'] ?? fallbackDay).toString(),
      workSeconds: _asInt(raw['workSeconds']),
      entertainmentSeconds: _asInt(raw['entertainmentSeconds']),
      dualSeconds: _asInt(raw['dualSeconds']),
      afkSeconds: _asInt(raw['afkSeconds']),
      perAppSeconds: appMap,
      blocksTriggered: _asInt(raw['blocksTriggered']),
      watchdogRestarts: _asInt(raw['watchdogRestarts']),
      emergencyPassesUsed: _asInt(raw['emergencyPassesUsed']),
      aiJudgments: judgments.take(60).toList(),
      eventLogs: logs.take(120).toList(),
    );
  }

  String toCompactDigestJson() => jsonEncode({
        'day': dayKey,
        'workMin': workMinutes.toStringAsFixed(1),
        'entertainmentMin': entertainmentMinutes.toStringAsFixed(1),
        'dualMin': dualMinutes.toStringAsFixed(1),
        'blocksTriggered': blocksTriggered,
        'watchdogRestarts': watchdogRestarts,
        'emergencyPassesUsed': emergencyPassesUsed,
        'topAppsMin': (perAppSeconds.entries.toList()
              ..sort((a, b) => b.value.compareTo(a.value)))
            .take(8)
            .map((e) => {e.key: (e.value / 60.0).toStringAsFixed(1)})
            .toList(),
      });
}

class ActiveBlockState {
  const ActiveBlockState({
    required this.app,
    required this.title,
    required this.tier,
    required this.reason,
    required this.actionKind,
    required this.canRequestAiPass,
    required this.triggeredAtUtcMs,
    this.actionHint = '',
  });

  final String app;
  final String title;
  final ActivityTier tier;
  final String reason;
  final String actionKind; // 'terminateAndShield' | 'minimizeAndShield'
  final bool canRequestAiPass;
  final int triggeredAtUtcMs;
  final String actionHint;
}

class CoachMessage {
  const CoachMessage({
    required this.isUser,
    required this.speaker,
    required this.text,
    required this.atUtcMs,
  });

  final bool isUser;
  final String speaker;
  final String text;
  final int atUtcMs;
}

int _asInt(Object? raw, {int fallback = 0}) {
  if (raw is int) return raw;
  if (raw is num) return raw.toInt();
  return int.tryParse((raw ?? '').toString()) ?? fallback;
}

List<String> _asStringList(Object? raw, List<String> fallback) {
  if (raw is! List) return fallback;
  return raw
      .map((e) => e.toString().trim())
      .where((e) => e.isNotEmpty)
      .toList();
}
