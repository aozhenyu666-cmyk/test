// dsh-driver-core 的 Dart 中继：Kotlin 的信号 → 插件；插件的指令 → Kotlin。
//
// 和 proactive_channel.dart 同一个原则：Kotlin 不碰网络，Dart 持有引擎 cookie，替它转发。
// 插件只在本机引擎上，远程主机一律不转发。
//
// 放到 fork 的 app/lib/driver/driver_channel.dart，在 app.dart 里和 ProactiveAgentTransport 一起 start()。

import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

import '../proactive/proactive_channel.dart' show ProactiveForwardTarget;

class DriverRelay {
  DriverRelay({required this.targetOf, MethodChannel? methods})
      : _methods = methods ?? const MethodChannel('dsh/driver');

  static const prefix = '/dsh-driver-core/v1';

  final ProactiveForwardTarget? Function() targetOf;
  final MethodChannel _methods;

  String? _sessionId;
  int _after = 0;
  bool _polling = false;

  void start() {
    // Kotlin → Dart：DriverRuntime.Port.signal 用 invokeMethod('signal', map) 调过来
    _methods.setMethodCallHandler((call) async {
      if (call.method != 'signal') return null;
      final body = (call.arguments as Map).cast<String, Object?>();
      final t = targetOf();
      if (t == null || !t.isLocal) return {'status': 0};
      final (status, text) = await t.postJson('$prefix/signal', body);
      if (status == 200) _deliver(text);
      return {'status': status};
    });
  }

  /// 界面上点「开始锁定」：开一条主线，把 sessionId 和允许的应用交给 Kotlin，然后开始长轮询。
  Future<int> startSession({required String topic, required String task, int minutes = 15}) async {
    final t = targetOf();
    if (t == null || !t.isLocal) return 0;
    final (status, text) = await t.postJson('$prefix/session', {'topic': topic, 'task': task, 'minutes': minutes});
    if (status != 201) return status;
    final body = jsonDecode(text) as Map<String, Object?>;
    _sessionId = body['sessionId'] as String;
    _after = 0;
    await _methods.invokeMethod('sessionStarted', {'sessionId': _sessionId, 'anchors': body['anchors']});
    _deliver(text);
    unawaited(_poll());
    return status;
  }

  Future<void> _poll() async {
    if (_polling) return;
    _polling = true;
    try {
      while (_sessionId != null) {
        final t = targetOf();
        if (t == null || !t.isLocal) break;
        final (status, text) = await t.get('$prefix/directives?sessionId=$_sessionId&after=$_after&waitMs=20000');
        if (status != 200) {
          await Future<void>.delayed(const Duration(seconds: 2));
          continue;
        }
        final ended = _deliver(text);
        if (ended) _sessionId = null;
      }
    } finally {
      _polling = false;
    }
  }

  /// 把还没送过的指令按顺序交给 Kotlin；返回是否已经结束。
  bool _deliver(String text) {
    final body = jsonDecode(text) as Map<String, Object?>;
    final list = (body['directives'] as List?) ?? const [];
    var ended = false;
    for (final raw in list) {
      final d = (raw as Map).cast<String, Object?>();
      final seq = (d['seq'] as num?)?.toInt() ?? 0;
      if (seq <= _after) continue;
      _after = seq;
      _methods.invokeMethod('directive', d);
      if (d['type'] == 'end') ended = true;
    }
    return ended;
  }
}
