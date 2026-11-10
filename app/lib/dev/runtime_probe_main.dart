// Development entrypoint: exercises the native runtime end to end without the UI.
//   flutter build apk --debug --flavor x64 --target-platform android-x64 -t lib/dev/runtime_probe_main.dart
// Progress is printed to logcat (tag "flutter") and shown on screen.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';

import '../runtime/runtime_bridge.dart';

void main() => runApp(const MaterialApp(home: _Probe()));

/// 插件的回包：解析失败就当成空对象，让断言自己说话，而不是在这里抛。
Map<String, dynamic> _json(String raw) {
  try {
    final v = jsonDecode(raw);
    return v is Map<String, dynamic> ? v : <String, dynamic>{};
  } catch (_) {
    return <String, dynamic>{};
  }
}

class _Probe extends StatefulWidget {
  const _Probe();

  @override
  State<_Probe> createState() => _ProbeState();
}

class _ProbeState extends State<_Probe> {
  final _bridge = RuntimeBridge();
  final _lines = <String>[];

  void _log(String line) {
    debugPrint('[probe] $line');
    setState(() => _lines.add(line));
  }

  @override
  void initState() {
    super.initState();
    unawaited(_run());
  }

  Future<void> _run() async {
    final sw = Stopwatch()..start();
    var lastPct = -1;
    final sub = _bridge.events.listen((e) {
      switch (e) {
        case RuntimeProgressEvent():
          final pct = (e.fraction * 100).floor();
          if (pct ~/ 10 != lastPct ~/ 10) _log('extract $pct% (${sw.elapsed.inSeconds}s)');
          lastPct = pct;
        case RuntimeStatusEvent(:final status):
          _log('phase ${status.phase.name}${status.error != null ? ' error=${status.error}' : ''}');
        case RuntimeLogEvent(:final line):
          debugPrint('[dsh] $line');
        case RuntimeNoticeEvent(:final text):
          _log('notice: $text');
      }
    });
    try {
      var status = await _bridge.status();
      _log('abi=${status.abi} bundled=${status.bundledVersion} installed=${status.installedVersion}');
      if (status.installedVersion != status.bundledVersion) {
        await _bridge.install();
        _log('installed in ${sw.elapsed.inSeconds}s');
      }
      sw.reset();
      await _bridge.start();
      while (true) {
        status = await _bridge.status();
        if (status.isRunning || status.phase == RuntimePhase.error) break;
        if (sw.elapsed > const Duration(minutes: 3)) throw TimeoutException('start');
        await Future<void>.delayed(const Duration(milliseconds: 500));
      }
      if (!status.isRunning) throw StateError('runtime error: ${status.error}');
      _log('running at ${status.baseUrl} after ${sw.elapsed.inMilliseconds}ms');

      final client = await DshClient.connect(baseUri: Uri.parse(status.baseUrl!), token: status.token);
      final sessions = await client.listSessions();
      _log('sessions: ${sessions.length}');
      final created = await client.createSession(cwd: '/root/workspace');
      _log('created session ${created.sessionId}');
      final models = await client.listModels();
      _log('models: ${models.groups.map((g) => '${g.id}(${g.models.length})').join(', ')}');

      // 主动搭话：计划层说"可以说话"时，提交就必须被接下。曾经这两层不一致——
      // /plan 看不到收件箱里排着的消息，提交却一律 409 agent-busy，于是每问一次就被挡一次，
      // 一句都说不出还每分钟留一张回执。这一步在设备上守的就是这条不变量。
      final conn = DshConnection(Uri.parse(status.baseUrl!));
      try {
        final token = status.token;
        if (token == null) throw StateError('runtime is running without a token');
        await conn.login(token);
        final plan = await conn.postJson('/dsh-go-companion/v1/plan', {
          'v': 1,
          'sessionId': created.sessionId,
          'phone': {'screenOn': true, 'local': '10:00'},
        });
        final planBody = _json(plan.text);
        final speak = planBody['speak'] == true;
        final reason = '${planBody['reason']}';
        _log('COMPANION plan status=${plan.statusCode} speak=$speak reason=$reason');
        if (speak) {
          final req = await conn.postJson('/dsh-go-companion/v1/request', {
            'v': 1,
            'requestId': 'probe-${DateTime.now().millisecondsSinceEpoch}',
            'eventId': 'probe-evt-${DateTime.now().millisecondsSinceEpoch}',
            'kind': 'scheduler',
            'sessionId': created.sessionId,
            'payload': {},
            'deadlineMs': 30000,
          });
          final err = _json(req.text)['error'];
          final code = err is Map ? '${err['code']}' : null;
          _log('COMPANION request status=${req.statusCode} code=$code');
          if (req.statusCode != 202 && (code == null || code.startsWith('skip') || code == 'agent-busy')) {
            throw StateError('plan said speak but the submit was refused: $code');
          }
        }
        _log('COMPANION OK');
      } finally {
        conn.close();
      }

      // Optional real turn: a key pushed with `run-as … files/probe-key` (deleted after reading).
      final keyFile = File('/data/user/0/io.github.ccch1mneyyy.dsh_app/files/probe-key');
      if (keyFile.existsSync()) {
        final key = keyFile.readAsStringSync().trim();
        keyFile.deleteSync();
        await client.setProviderKey(key);
        _log('provider key set');
        await _turn(client, created.sessionId);
        // Restart DSH and check the session survived on disk (link2symlink path).
        await client.close();
        await _bridge.stop();
        while ((await _bridge.status()).phase != RuntimePhase.installed) {
          await Future<void>.delayed(const Duration(milliseconds: 300));
        }
        _log('stopped cleanly; restarting');
        await _bridge.start();
        while (!(status = await _bridge.status()).isRunning) {
          if (status.phase == RuntimePhase.error) throw StateError('restart failed: ${status.error}');
          await Future<void>.delayed(const Duration(milliseconds: 300));
        }
        final again = await DshClient.connect(baseUri: Uri.parse(status.baseUrl!), token: status.token);
        final after = await again.listSessions();
        final mine = after.where((s) => s.sessionId == created.sessionId).toList();
        _log(
          'after restart: ${after.length} sessions, ours present=${mine.isNotEmpty} title=${mine.firstOrNull?.projections.title}',
        );
        final transcript = ChatTranscript();
        final events = again.watch(created.sessionId).listen(transcript.apply);
        await Future<void>.delayed(const Duration(seconds: 3));
        await events.cancel();
        _log('history items after restart: ${transcript.items.length}');
        await again.close();
      } else {
        await client.close();
      }
      _log('PROBE OK');
    } catch (e, st) {
      _log('PROBE FAILED: $e');
      debugPrint('$st');
      final logs = await _bridge.logs(lines: 60);
      for (final l in logs) {
        debugPrint('[dsh-tail] $l');
      }
    } finally {
      await sub.cancel();
    }
  }

  Future<void> _turn(DshClient client, String sessionId) async {
    final transcript = ChatTranscript();
    final done = Completer<void>();
    var sawRunning = false;
    final sub = client.watch(sessionId).listen((event) async {
      transcript.apply(event);
      if (event is ApprovalRequestedEvent) {
        _log('approval requested for ${event.request.toolName}; allowing once');
        await client.respondApproval(event.request, ApprovalDecision.allowOnce);
      }
      if (transcript.running) sawRunning = true;
      if (sawRunning && !transcript.running && !done.isCompleted) done.complete();
    });
    final t = Stopwatch()..start();
    await client.prompt(sessionId, '请用 bash 工具运行 `echo hello-from-phone && uname -m`，然后用一句话告诉我结果。');
    await done.future.timeout(const Duration(minutes: 3));
    await sub.cancel();
    _log('turn finished in ${t.elapsed.inSeconds}s');
    for (final item in transcript.items) {
      switch (item) {
        case UserChatItem(:final text):
          _log('USER: ${text.length > 60 ? text.substring(0, 60) : text}');
        case AssistantChatItem(:final text):
          _log('ASSISTANT: ${text.replaceAll('\n', ' ')}');
        case ToolChatItem(:final name, :final status, :final resultText):
          _log('TOOL $name ${status.name}: ${resultText.trim().replaceAll('\n', ' | ')}');
        case NoticeChatItem(:final text):
          _log('NOTICE: $text');
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [for (final l in _lines) Text(l, style: const TextStyle(fontFamily: 'monospace', fontSize: 12))],
      ),
    ),
  );
}
