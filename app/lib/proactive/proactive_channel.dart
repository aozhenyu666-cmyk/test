// D1: the Dart half of the proactive Agent transport.
//
// The kernel (loop/core) never touches the network itself: it asks Dart, which is the side that
// holds the engine's authenticated cookie, to carry one HTTP call to the dsh-go-proactive host
// plugin and hands the raw answer back. Three ops, and nothing else:
//
//   request (POST /dsh-go-proactive/v1/request)   poll (GET .../result?requestId=…&waitMs=…)
//   cancel  (POST /dsh-go-proactive/v1/cancel)
//
// Rules this file keeps, because the kernel relies on them:
//  * the answer is never interpreted — status and body go back verbatim, so one decoder decides;
//  * a transport failure is `status: 0`, never an invented HTTP code;
//  * nothing is forwarded on a remote host: the plugin only lives on this machine, so the honest
//    answer there is 404 (and the kernel turns "no plugin" into its own failure state);
//  * the plugin route is *not* under /api and is not authenticated by the engine; it needs the
//    same cookie the rest of the app uses, which is exactly what the connection carries.

import 'dart:async';
import 'dart:convert';

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/services.dart';

/// One command from the kernel: `{op, requestId, path, body?}`.
class ProactiveAgentOp {
  const ProactiveAgentOp({required this.op, required this.requestId, required this.path, this.body});

  final String op;
  final String requestId;
  final String path;
  final Map<String, Object?>? body;

  /// Null when the message is not one of the three ops — the kernel is the only sender, so a
  /// malformed message means a version mismatch, and answering it as a failure is the safe move.
  static ProactiveAgentOp? fromWire(Object? raw) {
    if (raw is! Map) return null;
    final op = raw['op'];
    final requestId = raw['requestId'];
    final path = raw['path'];
    if (op is! String || requestId is! String || path is! String) return null;
    if (op != 'request' && op != 'poll' && op != 'cancel') return null;
    final body = raw['body'];
    return ProactiveAgentOp(
      op: op,
      requestId: requestId,
      path: path,
      body: body is Map ? body.cast<String, Object?>() : null,
    );
  }

  /// The transport the kernel described: simple and easy to assert in a test.
  bool get isPost => op == 'request' || op == 'cancel';
}

/// What goes back to the kernel on `dsh/proactive` → `agentHttp`.
class ProactiveAgentAnswer {
  const ProactiveAgentAnswer(this.requestId, this.status, [this.body]);

  final String requestId;

  /// HTTP status, or 0 when the request never got an answer.
  final int status;
  final Object? body;

  Map<String, Object?> toWire() => {'requestId': requestId, 'status': status, 'body': body};
}

/// What the transport needs from the app's connection: where it points, and the two HTTP verbs.
///
/// A narrow seam instead of the whole [DshConnection], because that class owns a real
/// `HttpClient` and a cookie jar; a test that has to build one cannot say "this failed with a
/// socket error" or "this is a remote host" without a server.
abstract class ProactiveForwardTarget {
  /// The plugin ships with the runtime on this machine; a remote host has no such route.
  bool get isLocal;

  Future<(int status, String body)> postJson(String path, Map<String, Object?> body);
  Future<(int status, String body)> get(String path);
}

/// The real target: the authenticated connection of the current host.
class DshConnectionTarget implements ProactiveForwardTarget {
  const DshConnectionTarget(this.connection, {required this.isLocal});

  /// `HostConnection.client.connection` — the same cookie the rest of the app uses.
  final DshConnection connection;

  @override
  final bool isLocal;

  @override
  Future<(int, String)> postJson(String path, Map<String, Object?> body) async {
    final response = await connection.postJson(path, body);
    return (response.statusCode, response.text);
  }

  @override
  Future<(int, String)> get(String path) async {
    // op.path carries its own query (`/result?requestId=…&waitMs=…`). Handing that
    // whole string to connection.get() sends it as a *path*, and Uri.replace(path:)
    // encodes the "?" to %3F — the host then answers 404 not-found and the kernel
    // reads that as a lost agent. Split it so the query stays a query.
    final uri = Uri.parse(path);
    final response = await connection.get(uri.path, query: uri.queryParameters);
    return (response.statusCode, response.text);
  }}

/// Carries the kernel's agent commands to the host plugin. Created once, at app start.
class ProactiveAgentTransport {
  ProactiveAgentTransport({MethodChannel? methods, EventChannel? events, this.targetOf})
    : _methods = methods ?? const MethodChannel('dsh/proactive'),
      _events = events ?? const EventChannel('dsh/proactive/agent');

  final MethodChannel _methods;
  final EventChannel _events;

  /// The target to forward on; null when not connected. Tests pass their own.
  final ProactiveForwardTarget? Function()? targetOf;

  StreamSubscription<Object?>? _sub;

  /// Route prefix of the plugin (docs/AGENT_PROTOCOL.md).
  static const routePrefix = '/dsh-go-proactive/v1';

  void start() {
    if (_sub != null) return;
    _sub = _events.receiveBroadcastStream().listen(
      (raw) => unawaited(_onOp(ProactiveAgentOp.fromWire(raw))),
      onError: (Object e) {
        // A broken channel must not take the app down; the kernel sees its request time out.
        _debug('proactive agent channel error: $e');
      },
    );
  }

  Future<void> stop() async {
    await _sub?.cancel();
    _sub = null;
  }

  Future<void> _onOp(ProactiveAgentOp? op) async {
    if (op == null) return;
    final answer = await forward(op);
    try {
      await _methods.invokeMethod<void>('agentHttp', answer.toWire());
    } on PlatformException catch (e) {
      _debug('agentHttp failed for ${op.requestId}: ${e.code}');
    }
  }

  /// Runs one op and reports what happened, without interpreting the answer.
  Future<ProactiveAgentAnswer> forward(ProactiveAgentOp op) async {
    final target = targetOf?.call();
    if (target == null) return ProactiveAgentAnswer(op.requestId, 0);
    if (!target.isLocal) return ProactiveAgentAnswer(op.requestId, 404);
    final path = op.path.startsWith('/') ? op.path : '/${op.path}';
    try {
      final (status, body) = op.isPost ? await target.postJson(path, op.body ?? const {}) : await target.get(path);
      return ProactiveAgentAnswer(op.requestId, status, _decode(body));
    } catch (e) {
      // No answer at all (socket, timeout, TLS): 0, never a made-up status.
      _debug('proactive ${op.op} ${op.path} failed: $e');
      return ProactiveAgentAnswer(op.requestId, 0);
    }
  }

  Object? _decode(String body) {
    if (body.isEmpty) return null;
    try {
      return jsonDecode(body);
    } on FormatException {
      // Not JSON: pass the text through rather than losing it.
      return body;
    }
  }

  void _debug(String message) {
    assert(() {
      // ignore: avoid_print
      print('[proactive] $message');
      return true;
    }());
  }
}

/// T7/D3: the person sent a message somewhere — the scheduler should step back for a while.
class ProactiveUserActivity {
  const ProactiveUserActivity({MethodChannel? methods}) : _methods = methods ?? const MethodChannel('dsh/proactive');

  final MethodChannel _methods;

  Future<void> notify() async {
    try {
      await _methods.invokeMethod<void>('userActivity');
    } on PlatformException {
      // Nothing to recover: the scheduler simply keeps its current plan.
    }
  }
}
