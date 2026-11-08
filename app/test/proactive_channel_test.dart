// D1: the Dart half of the proactive Agent transport — three ops, raw answers, no interpretation.

import 'dart:convert';
import 'dart:typed_data';

import 'package:dsh_app/proactive/proactive_channel.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// Records what the transport asks of the connection, and answers as the host plugin would.
class FakeTarget implements ProactiveForwardTarget {
  FakeTarget({this.status = 200, this.body = '{"state":"accepted"}', this.throwOnSend = false, this.isLocal = true});

  int status;
  String body;
  bool throwOnSend;
  final calls = <({String method, String path, Map<String, Object?>? body})>[];

  @override
  bool isLocal;

  @override
  Future<(int, String)> postJson(String path, Map<String, Object?> requestBody) async {
    calls.add((method: 'POST', path: path, body: requestBody));
    if (throwOnSend) throw const SocketLikeException();
    return (status, body);
  }

  @override
  Future<(int, String)> get(String path) async {
    calls.add((method: 'GET', path: path, body: null));
    if (throwOnSend) throw const SocketLikeException();
    return (status, body);
  }}

/// Stands in for whatever the HTTP layer throws when the socket dies.
class SocketLikeException implements Exception {
  const SocketLikeException();
}

/// Records the request line the real connection would send; no socket is opened.
class RecordingConnection extends DshConnection {
  RecordingConnection() : super(Uri.parse('http://127.0.0.1:3080'));

  final gets = <({String path, Map<String, String>? query})>[];

  @override
  Future<DshHttpResponse> get(
    String path, {
    Map<String, String>? query,
    Map<String, String>? headers,
    Duration? timeout,
  }) async {
    gets.add((path: path, query: query));
    return DshHttpResponse(200, 'application/json', Uint8List.fromList(utf8.encode('{"state":"accepted"}')));
  }
}

void main() {
  // The channel tests below reach TestDefaultBinaryMessengerBinding.instance.
  TestWidgetsFlutterBinding.ensureInitialized();

  group('wire', () {
    test('only the three ops parse, and a bad message is dropped', () {
      expect(ProactiveAgentOp.fromWire({'op': 'request', 'requestId': 'r1', 'path': '/p'})?.isPost, isTrue);
      expect(ProactiveAgentOp.fromWire({'op': 'poll', 'requestId': 'r1', 'path': '/p'})?.isPost, isFalse);
      expect(ProactiveAgentOp.fromWire({'op': 'cancel', 'requestId': 'r1', 'path': '/p'})?.isPost, isTrue);
      expect(ProactiveAgentOp.fromWire({'op': 'delete', 'requestId': 'r1', 'path': '/p'}), isNull);
      expect(ProactiveAgentOp.fromWire({'op': 'request', 'path': '/p'}), isNull);
      expect(ProactiveAgentOp.fromWire('nope'), isNull);
      expect(ProactiveAgentOp.fromWire(null), isNull);
    });

    test('the answer carries the kernel request id, the raw status and the body', () {
      expect(const ProactiveAgentAnswer('r1', 0).toWire(), {'requestId': 'r1', 'status': 0, 'body': null});
      final withBody = const ProactiveAgentAnswer('r1', 202, {'state': 'accepted'}).toWire();
      expect(withBody['status'], 202);
      expect(withBody['body'], {'state': 'accepted'});
    });
  });

  group('forward', () {
    test('no target is status 0, never an invented code', () async {
      final transport = ProactiveAgentTransport(targetOf: () => null);
      final answer = await transport.forward(
        const ProactiveAgentOp(op: 'request', requestId: 'r1', path: '/dsh-go-proactive/v1/request', body: {'v': 1}),
      );
      expect(answer.status, 0);
      expect(answer.requestId, 'r1');
    });

    test('a remote host is 404 and nothing is sent', () async {
      final target = FakeTarget(isLocal: false);
      final transport = ProactiveAgentTransport(targetOf: () => target);
      final answer = await transport.forward(
        const ProactiveAgentOp(op: 'poll', requestId: 'r2', path: '/dsh-go-proactive/v1/result?requestId=r2&waitMs=20000'),
      );
      expect(answer.status, 404);
      expect(target.calls, isEmpty);
    });

    test('request is a POST with the body, and the answer is decoded verbatim', () async {
      final target = FakeTarget(status: 202, body: '{"state":"accepted","requestId":"r3"}');
      final transport = ProactiveAgentTransport(targetOf: () => target);
      final answer = await transport.forward(
        const ProactiveAgentOp(op: 'request', requestId: 'r3', path: '/dsh-go-proactive/v1/request', body: {'v': 1, 'kind': 'app_enter'}),
      );
      expect(target.calls.single.method, 'POST');
      expect(target.calls.single.path, '/dsh-go-proactive/v1/request');
      expect(target.calls.single.body, {'v': 1, 'kind': 'app_enter'});
      expect(answer.status, 202);
      expect(answer.body, {'state': 'accepted', 'requestId': 'r3'});
    });

    test('poll is a GET with the query in the path', () async {
      final target = FakeTarget(body: '{"state":"accepted"}');
      final transport = ProactiveAgentTransport(targetOf: () => target);
      await transport.forward(
        const ProactiveAgentOp(op: 'poll', requestId: 'r4', path: '/dsh-go-proactive/v1/result?requestId=r4&waitMs=20000'),
      );
      expect(target.calls.single.method, 'GET');
      expect(target.calls.single.path, '/dsh-go-proactive/v1/result?requestId=r4&waitMs=20000');
      expect(target.calls.single.body, isNull);
    });

    test('cancel is a POST', () async {
      final target = FakeTarget();
      final transport = ProactiveAgentTransport(targetOf: () => target);
      await transport.forward(
        const ProactiveAgentOp(op: 'cancel', requestId: 'r5', path: '/dsh-go-proactive/v1/cancel', body: {'requestId': 'r5'}),
      );
      expect(target.calls.single.method, 'POST');
      expect(target.calls.single.body, {'requestId': 'r5'});
    });

    test('a transport failure is status 0, and a non-JSON body is passed through', () async {
      final broken = FakeTarget(throwOnSend: true);
      final failure = await ProactiveAgentTransport(targetOf: () => broken).forward(
        const ProactiveAgentOp(op: 'request', requestId: 'r6', path: '/dsh-go-proactive/v1/request', body: {}),
      );
      expect(failure.status, 0);

      final text = FakeTarget(status: 200, body: 'not json');
      final passthrough = await ProactiveAgentTransport(targetOf: () => text).forward(
        const ProactiveAgentOp(op: 'poll', requestId: 'r7', path: '/dsh-go-proactive/v1/result?requestId=r7'),
      );
      expect(passthrough.status, 200);
      expect(passthrough.body, 'not json');
    });

    test('a relative path is not sent as-is', () async {
      final target = FakeTarget();
      final transport = ProactiveAgentTransport(targetOf: () => target);
      await transport.forward(
        const ProactiveAgentOp(op: 'poll', requestId: 'r8', path: 'dsh-go-proactive/v1/result?requestId=r8'),
      );
      expect(target.calls.single.path, '/dsh-go-proactive/v1/result?requestId=r8');
    });
  });

  group('plugin route', () {
    test('the prefix matches the plugin contract', () {
      expect(ProactiveAgentTransport.routePrefix, '/dsh-go-proactive/v1');
      for (final op in ['request', 'result', 'cancel', 'status', 'config']) {
        expect('${ProactiveAgentTransport.routePrefix}/$op', startsWith('/dsh-go-proactive/v1/'));
      }
    });
  });

  group('connection target', () {
    test('a poll keeps its query a query, so the host never sees %3F', () async {
      final connection = RecordingConnection();
      final target = DshConnectionTarget(connection, isLocal: true);
      final (status, _) = await target.get('/dsh-go-companion/v1/result?requestId=r1&waitMs=20000');
      expect(status, 200);
      expect(connection.gets.single.path, '/dsh-go-companion/v1/result');
      expect(connection.gets.single.query, {'requestId': 'r1', 'waitMs': '20000'});
    });
  });

  group('user activity', () {
    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('dsh/proactive'),
        null,
      );
    });

    test('userActivity reaches the native side', () async {
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('dsh/proactive'),
        (call) async {
          calls.add(call.method);
          return true;
        },
      );
      await const ProactiveUserActivity().notify();
      expect(calls, ['userActivity']);
    });

    test('a missing native side does not throw', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('dsh/proactive'),
        (call) async => throw PlatformException(code: 'boom'),
      );
      await const ProactiveUserActivity().notify();
    });
  });
}
