import 'dart:convert';
import 'dart:io';

import 'package:dsh_go_fish_pc/models/guard_models.dart';
import 'package:dsh_go_fish_pc/services/ai_judge_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('AiJudgeService parses fenced JSON, caches 10min, and throttles uncached bursts', () async {
    var calls = 0;
    String? lastUrl;
    String? lastAuth;

    final svc = AiJudgeService(
      transport: ({required url, required headers, required body}) async {
        calls++;
        lastUrl = url;
        lastAuth = headers['Authorization'];
        return jsonEncode({
          'choices': [
            {
              'message': {
                'content': '```json\n{"verdict":"allow_study","leaseMinutes":25,"reason":"行测系统课讲解","actionHint":"记完笔记即关闭"}\n```',
              }
            }
          ]
        });
      },
    );

    const cfg = AiProviderConfig(
      preset: 'deepseek',
      baseUrl: 'https://api.deepseek.com/v1/',
      model: 'deepseek-chat',
      enabled: true,
    );
    final t0 = DateTime.utc(2026, 11, 19, 6, 0).millisecondsSinceEpoch;

    final r1 = await svc.judgeActivity(
      config: cfg,
      apiKey: 'sk-test-123',
      app: 'chrome',
      title: 'bilibili 数量关系专项课',
      focusSession: const FocusSessionState(active: true, taskName: '行测刷题'),
      ledger: const UsageLedger(dayKey: '2026-11-19'),
      nowUtcMs: t0,
    );

    expect(calls, 1);
    expect(lastUrl, 'https://api.deepseek.com/v1/chat/completions');
    expect(lastAuth, 'Bearer sk-test-123');
    expect(r1.verdict, 'allow_study');
    expect(r1.leaseMinutes, 25);

    // Same window within 10min hits cache
    final r2 = await svc.judgeActivity(
      config: cfg,
      apiKey: 'sk-test-123',
      app: 'chrome',
      title: 'bilibili 数量关系专项课',
      focusSession: const FocusSessionState(),
      ledger: const UsageLedger(dayKey: '2026-11-19'),
      nowUtcMs: t0 + 60000,
    );
    expect(calls, 1);
    expect(r2.verdict, 'allow_study');

    // Different window within 15s hits throttle
    final r3 = await svc.judgeActivity(
      config: cfg,
      apiKey: 'sk-test-123',
      app: 'chrome',
      title: 'zhihu 摸鱼问题',
      focusSession: const FocusSessionState(),
      ledger: const UsageLedger(dayKey: '2026-11-19'),
      nowUtcMs: t0 + 5000,
    );
    expect(calls, 1);
    expect(r3.verdict, 'need_check');
  });

  test('AiJudgeService evaluates study pass appeal and injects real ledger into coach chat', () async {
    String? lastUserBody;
    var mode = 'approve';

    final svc = AiJudgeService(
      transport: ({required url, required headers, required body}) async {
        lastUserBody = body;
        final content = mode == 'approve'
            ? '{"approved":true,"leaseMinutes":15,"reason":"确为备考视频"}'
            : '你今天娱乐 42 分钟且被拦截 3 次，先把行测错题清完。';
        return jsonEncode({
          'choices': [
            {
              'message': {'content': content}
            }
          ]
        });
      },
    );

    const cfg = AiProviderConfig(
      enabled: true,
      baseUrl: 'https://api.example.com/v1',
      model: 'gpt-4o-mini',
    );
    final now = DateTime.utc(2026, 11, 19, 8, 0).millisecondsSinceEpoch;

    final tooShort = await svc.evaluateStudyPassRequest(
      config: cfg,
      apiKey: 'k',
      claim: '看下',
      app: 'chrome',
      title: 'bilibili',
      focusSession: const FocusSessionState(),
      ledger: const UsageLedger(dayKey: '2026-11-19'),
      nowUtcMs: now,
    );
    expect(tooShort.approved, isFalse);

    final ok = await svc.evaluateStudyPassRequest(
      config: cfg,
      apiKey: 'k',
      claim: '看花生十三资料分析速算例题',
      app: 'chrome',
      title: 'bilibili 资料分析',
      focusSession: const FocusSessionState(active: true, taskName: '行测复习'),
      ledger: const UsageLedger(dayKey: '2026-11-19', workSeconds: 1800),
      nowUtcMs: now,
    );
    expect(ok.approved, isTrue);
    expect(ok.leaseMinutes, 15);

    mode = 'chat';
    final reply = await svc.chatWithCoach(
      config: cfg,
      apiKey: 'k',
      ledger: const UsageLedger(
        dayKey: '2026-11-19',
        workSeconds: 3600,
        entertainmentSeconds: 2520,
        blocksTriggered: 3,
        watchdogRestarts: 1,
        perAppSeconds: {'code': 3600, 'steam': 2520},
      ),
      rules: const GuardRulesConfig(strictGuardArmed: true),
      focusSession: const FocusSessionState(),
      history: const [],
      userMessage: '复盘今天',
      nowUtcMs: now,
    );
    expect(reply, contains('娱乐 42 分钟'));
    expect(lastUserBody, contains('watchdogRestarts'));
    expect(lastUserBody, contains('blocksTriggered'));
  });

  test('AiJudgeService deterministically falls back when key missing, offline, timed out, or non-JSON without leaking key', () async {
    const cfg = AiProviderConfig(
      preset: 'deepseek',
      baseUrl: 'https://api.deepseek.com/v1',
      model: 'deepseek-chat',
      enabled: true,
    );
    final t0 = DateTime.utc(2026, 11, 19, 6, 0).millisecondsSinceEpoch;

    // 1. Missing API key -> skips transport & falls back cleanly
    var calls = 0;
    final noKeySvc = AiJudgeService(
      transport: ({required url, required headers, required body}) async {
        calls++;
        return '{}';
      },
    );
    final noKeyRec = await noKeySvc.judgeActivity(
      config: cfg,
      apiKey: '',
      app: 'chrome',
      title: 'bilibili 视频',
      focusSession: const FocusSessionState(active: true, taskName: '行测'),
      ledger: const UsageLedger(dayKey: '2026-11-19'),
      nowUtcMs: t0,
    );
    expect(calls, 0);
    expect(noKeyRec.verdict, 'need_check');
    expect(noKeyRec.source, 'fallback');

    final noKeyPass = await noKeySvc.evaluateStudyPassRequest(
      config: cfg,
      apiKey: '',
      claim: '我要看行测教学视频',
      app: 'chrome',
      title: 'bilibili',
      focusSession: const FocusSessionState(),
      ledger: const UsageLedger(dayKey: '2026-11-19'),
      nowUtcMs: t0,
    );
    expect(noKeyPass.approved, isFalse);

    // 2. Network error -> falls back & redacts secret key in testConnection
    const secret = 'sk-super-secret-xyz';
    final offlineSvc = AiJudgeService(
      transport: ({required url, required headers, required body}) async {
        throw HttpException('401 Unauthorized Bearer $secret');
      },
    );
    final netRec = await offlineSvc.judgeActivity(
      config: cfg,
      apiKey: secret,
      app: 'chrome',
      title: 'youtube',
      focusSession: const FocusSessionState(),
      ledger: const UsageLedger(dayKey: '2026-11-19'),
      nowUtcMs: t0,
    );
    expect(netRec.verdict, 'need_check');
    expect(netRec.source, 'fallback');
    expect(netRec.reason.contains(secret), isFalse);

    final conn = await offlineSvc.testConnection(config: cfg, apiKey: secret);
    expect(conn.ok, isFalse);
    expect(conn.detail.contains(secret), isFalse);

    // 3. Timeout <= 8s enforced -> deterministic fallback
    expect(AiJudgeService.maxRequestTimeout.inSeconds, lessThanOrEqualTo(8));
    final slowSvc = AiJudgeService(
      requestTimeout: const Duration(milliseconds: 30),
      transport: ({required url, required headers, required body}) async {
        await Future<void>.delayed(const Duration(milliseconds: 200));
        return '{}';
      },
    );
    final timeoutRec = await slowSvc.judgeActivity(
      config: cfg,
      apiKey: secret,
      app: 'chrome',
      title: 'zhihu',
      focusSession: const FocusSessionState(),
      ledger: const UsageLedger(dayKey: '2026-11-19'),
      nowUtcMs: t0,
    );
    expect(timeoutRec.verdict, 'need_check');
    expect(timeoutRec.source, 'fallback');

    // 4. Non-JSON response -> deterministic fallback
    final badJsonSvc = AiJudgeService(
      transport: ({required url, required headers, required body}) async {
        return jsonEncode({
          'choices': [
            {
              'message': {'content': '抱歉，我无法判断这个页面是不是学习。'}
            }
          ]
        });
      },
    );
    final badJsonRec = await badJsonSvc.judgeActivity(
      config: cfg,
      apiKey: secret,
      app: 'chrome',
      title: 'bilibili 混剪',
      focusSession: const FocusSessionState(),
      ledger: const UsageLedger(dayKey: '2026-11-19'),
      nowUtcMs: t0,
    );
    expect(badJsonRec.verdict, 'need_check');
    expect(badJsonRec.source, 'fallback');

    final badJsonPass = await badJsonSvc.evaluateStudyPassRequest(
      config: cfg,
      apiKey: secret,
      claim: '我要看资料分析讲解视频',
      app: 'chrome',
      title: 'bilibili',
      focusSession: const FocusSessionState(),
      ledger: const UsageLedger(dayKey: '2026-11-19'),
      nowUtcMs: t0,
    );
    expect(badJsonPass.approved, isFalse);
  });
}
