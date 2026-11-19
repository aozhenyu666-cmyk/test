import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../models/guard_models.dart';
import 'rule_engine.dart';

typedef AiTransport = Future<String> Function({
  required String url,
  required Map<String, String> headers,
  required String body,
});

class AiPassEvaluation {
  const AiPassEvaluation({
    required this.approved,
    required this.leaseMinutes,
    required this.reason,
  });

  final bool approved;
  final int leaseMinutes;
  final String reason;
}

class AiJudgeService {
  AiJudgeService({AiTransport? transport})
      : _transport = transport ?? _defaultHttpTransport;

  final AiTransport _transport;
  final Map<String, AiJudgmentRecord> _cache = {};
  int _lastAutoJudgeUtcMs = 0;

  Map<String, AiJudgmentRecord> get cacheView => Map.unmodifiable(_cache);

  void clearCache() {
    _cache.clear();
  }

  Future<AiJudgmentRecord> judgeActivity({
    required AiProviderConfig config,
    required String apiKey,
    required String app,
    required String title,
    required FocusSessionState focusSession,
    required UsageLedger ledger,
    required int nowUtcMs,
  }) async {
    final cached = RuleEngine.findFreshCachedJudgment(
      _cache,
      app,
      title,
      nowUtcMs,
    );
    if (cached != null) return cached;

    if (nowUtcMs - _lastAutoJudgeUtcMs < 15000) {
      return AiJudgmentRecord(
        atUtcMs: nowUtcMs,
        app: app,
        title: title,
        verdict: 'need_check',
        leaseMinutes: 0,
        reason: 'AI 裁决冷却中（15 秒节流）',
        source: 'auto',
      );
    }
    _lastAutoJudgeUtcMs = nowUtcMs;

    final systemPrompt = [
      '你是严谨克制的 Windows 电脑自律裁决器，只根据客观窗口信息判断是否属于真正学习/考公/求职/编程工作。',
      '遇到模糊、泛娱乐、吃瓜、鬼畜、纯消遣标题一律判为 entertainment。',
      '必须只输出一个 JSON 对象，禁止额外解释：',
      '{"verdict":"allow_study"|"entertainment","leaseMinutes":15,"reason":"20字内依据","actionHint":"给用户的短建议"}',
      if (config.customPrompt.trim().isNotEmpty)
        '用户附加规则：${config.customPrompt.trim()}',
    ].join('\n');

    final userPrompt = jsonEncode({
      'app': app,
      'windowTitle': title,
      'currentFocusTask':
          focusSession.active ? focusSession.taskName : '(非专注锁时段)',
      'todayWorkMin': ledger.workMinutes.toStringAsFixed(1),
      'todayEntertainmentMin': ledger.entertainmentMinutes.toStringAsFixed(1),
    });

    final rawContent = await _callChatCompletions(
      config: config,
      apiKey: apiKey,
      messages: [
        {'role': 'system', 'content': systemPrompt},
        {'role': 'user', 'content': userPrompt},
      ],
      temperature: 0.1,
    );

    final parsed = _extractJsonMap(rawContent);
    final rawVerdict = (parsed?['verdict'] ?? '').toString().trim();
    final isAllow = rawVerdict == 'allow_study';
    final leaseMinutes = isAllow
        ? (_parseInt(parsed?['leaseMinutes'], fallback: 20)).clamp(5, 30)
        : 0;
    final reason = (parsed?['reason'] ?? '')
        .toString()
        .trim()
        .ifEmpty(isAllow ? 'AI 判定当前内容符合学习目标' : 'AI 判定偏向娱乐消遣');
    final hint = (parsed?['actionHint'] ?? '').toString().trim();

    final record = AiJudgmentRecord(
      atUtcMs: nowUtcMs,
      app: app,
      title: title,
      verdict: isAllow ? 'allow_study' : 'entertainment',
      leaseMinutes: leaseMinutes,
      reason: reason,
      actionHint: hint,
      source: 'auto',
    );
    _cache[RuleEngine.judgmentCacheKey(app, title)] = record;
    return record;
  }

  Future<AiPassEvaluation> evaluateStudyPassRequest({
    required AiProviderConfig config,
    required String apiKey,
    required String claim,
    required String app,
    required String title,
    required FocusSessionState focusSession,
    required UsageLedger ledger,
    required int nowUtcMs,
  }) async {
    final cleanClaim = claim.trim();
    if (cleanClaim.length < 4) {
      return const AiPassEvaluation(
        approved: false,
        leaseMinutes: 0,
        reason: '申请理由过短，请写明具体学习内容与目标',
      );
    }

    final systemPrompt = [
      '你是严格但讲理的学习通行证审批官。',
      '用户正在请求在受控网站/应用上获得限时学习通行证。',
      '对照用户的申请理由、真实前台窗口标题以及今日专注/娱乐时长：',
      '1. 若窗口明显是娱乐/与理由无关/敷衍套话，坚决拒绝（approved: false）。',
      '2. 若确有明确学习/查资料/求职需求，批准 10~25 分钟（approved: true）。',
      '只输出纯 JSON：{"approved":true|false,"leaseMinutes":15,"reason":"简短明确原因"}',
    ].join('\n');

    final userPrompt = jsonEncode({
      'claim': cleanClaim,
      'actualApp': app,
      'actualWindowTitle': title,
      'focusTask': focusSession.active ? focusSession.taskName : '',
      'ledgerDigest': jsonDecode(ledger.toCompactDigestJson()),
    });

    final rawContent = await _callChatCompletions(
      config: config,
      apiKey: apiKey,
      messages: [
        {'role': 'system', 'content': systemPrompt},
        {'role': 'user', 'content': userPrompt},
      ],
      temperature: 0.1,
    );

    final parsed = _extractJsonMap(rawContent);
    final approved = parsed?['approved'] == true;
    final leaseMinutes = approved
        ? (_parseInt(parsed?['leaseMinutes'], fallback: 15)).clamp(5, 30)
        : 0;
    final reason = (parsed?['reason'] ?? '')
        .toString()
        .trim()
        .ifEmpty(approved ? '审批通过，请专注当前目标' : '理由与当前窗口证据不符，未予放行');

    _cache[RuleEngine.judgmentCacheKey(app, title)] = AiJudgmentRecord(
      atUtcMs: nowUtcMs,
      app: app,
      title: title,
      verdict: approved ? 'allow_study' : 'entertainment',
      leaseMinutes: leaseMinutes,
      reason: reason,
      source: 'appeal',
    );

    return AiPassEvaluation(
      approved: approved,
      leaseMinutes: leaseMinutes,
      reason: reason,
    );
  }

  Future<String> chatWithCoach({
    required AiProviderConfig config,
    required String apiKey,
    required UsageLedger ledger,
    required GuardRulesConfig rules,
    required FocusSessionState focusSession,
    required List<CoachMessage> history,
    required String userMessage,
    required int nowUtcMs,
  }) async {
    final recentJudgments = ledger.aiJudgments
        .take(6)
        .map((j) => '${j.app}(${j.verdict}): ${j.title} -> ${j.reason}')
        .join('\n');

    final systemPrompt = [
      '你是「大肥鱼 PC 严防哨兵」的独立 AI 参谋，直接依据本地真实账本说话，禁止空洞鸡汤和客套废话。',
      '今日真实账本摘要：${ledger.toCompactDigestJson()}',
      '当前规则：每日娱乐上限 ${rules.dailyEntertainmentLimitMin} 分钟；'
          '严防守护=${rules.strictGuardArmed}；'
          '深夜宵禁=${rules.nightCurfewEnabled ? "${RuleEngine.formatMinuteOfDay(rules.nightCurfewStartMin)}-${RuleEngine.formatMinuteOfDay(rules.nightCurfewEndMin)}" : "关闭"}；'
          '专注锁=${focusSession.isActiveAt(nowUtcMs) ? "${focusSession.taskName}(剩${focusSession.remainingSecondsAt(nowUtcMs) ~/ 60}m)" : "未开启"}。',
      if (recentJudgments.isNotEmpty) '近期 AI 裁决：\n$recentJudgments',
      if (config.customPrompt.trim().isNotEmpty)
        '用户自定义指令：${config.customPrompt.trim()}',
    ].join('\n');

    final messages = <Map<String, String>>[
      {'role': 'system', 'content': systemPrompt},
      for (final msg in history.reversed.take(10).toList().reversed)
        {'role': msg.isUser ? 'user' : 'assistant', 'content': msg.text},
      {'role': 'user', 'content': userMessage.trim()},
    ];

    final reply = await _callChatCompletions(
      config: config,
      apiKey: apiKey,
      messages: messages,
      temperature: 0.4,
    );
    return reply.trim();
  }

  Future<({bool ok, String detail})> testConnection({
    required AiProviderConfig config,
    required String apiKey,
  }) async {
    if (config.baseUrl.trim().isEmpty || config.model.trim().isEmpty) {
      return (ok: false, detail: '请填写完整的 Base URL 和模型名称');
    }
    try {
      final reply = await _callChatCompletions(
        config: config,
        apiKey: apiKey,
        messages: const [
          {'role': 'user', 'content': '请只回复 OK'},
        ],
        temperature: 0.0,
      );
      return (
        ok: true,
        detail: '连接成功（模型 ${config.model} 返回：${reply.trim().ifEmpty("OK")}）',
      );
    } catch (e) {
      return (ok: false, detail: '连接失败：$e');
    }
  }

  Future<String> _callChatCompletions({
    required AiProviderConfig config,
    required String apiKey,
    required List<Map<String, String>> messages,
    required double temperature,
  }) async {
    final base = config.baseUrl.trim().replaceAll(RegExp(r'/+$'), '');
    if (base.isEmpty) {
      throw StateError('AI Base URL 为空');
    }
    final url = base.endsWith('/chat/completions')
        ? base
        : '$base/chat/completions';
    final headers = <String, String>{
      'Content-Type': 'application/json',
      if (apiKey.trim().isNotEmpty) 'Authorization': 'Bearer ${apiKey.trim()}',
    };
    final payload = jsonEncode({
      'model': config.model.trim(),
      'messages': messages,
      'temperature': temperature,
    });

    final rawBody = await _transport(
      url: url,
      headers: headers,
      body: payload,
    );
    final decoded = jsonDecode(rawBody);
    if (decoded is Map) {
      final choices = decoded['choices'];
      if (choices is List && choices.isNotEmpty) {
        final first = choices.first;
        if (first is Map) {
          final msg = first['message'];
          if (msg is Map && msg['content'] != null) {
            return msg['content'].toString();
          }
        }
      }
      if (decoded['error'] != null) {
        throw StateError(decoded['error'].toString());
      }
    }
    throw const FormatException('AI 响应缺少 choices[0].message.content');
  }

  static Future<String> _defaultHttpTransport({
    required String url,
    required Map<String, String> headers,
    required String body,
  }) async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 10);
    try {
      final req = await client.postUrl(Uri.parse(url));
      headers.forEach(req.headers.set);
      req.add(utf8.encode(body));
      final resp = await req.close().timeout(const Duration(seconds: 20));
      final respBody = await resp.transform(utf8.decoder).join();
      if (resp.statusCode < 200 || resp.statusCode >= 300) {
        final preview =
            respBody.length > 260 ? respBody.substring(0, 260) : respBody;
        throw HttpException('HTTP ${resp.statusCode}: $preview');
      }
      return respBody;
    } finally {
      client.close(force: true);
    }
  }

  static Map<String, Object?>? _extractJsonMap(String text) {
    var cleaned = text.trim();
    final fenceMatch =
        RegExp(r'```(?:json)?\s*([\s\S]*?)```', caseSensitive: false)
            .firstMatch(cleaned);
    if (fenceMatch != null) {
      cleaned = (fenceMatch.group(1) ?? '').trim();
    }
    final start = cleaned.indexOf('{');
    final end = cleaned.lastIndexOf('}');
    if (start < 0 || end <= start) return null;
    try {
      final decoded = jsonDecode(cleaned.substring(start, end + 1));
      return decoded is Map ? decoded.cast<String, Object?>() : null;
    } catch (_) {
      return null;
    }
  }

  static int _parseInt(Object? raw, {required int fallback}) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    return int.tryParse((raw ?? '').toString()) ?? fallback;
  }
}

extension on String {
  String ifEmpty(String fallback) => isEmpty ? fallback : this;
}
