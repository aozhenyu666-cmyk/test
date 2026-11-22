import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dsh_go_fish_pc/services/bridge_local_sync.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('discoverLocalKey prioritizes DAFEIYU_LOCAL_KEY over bridge-state.json', () async {
    final tmp = await Directory.systemTemp.createTemp('dsh_bridge_key_test_');
    addTearDown(() => tmp.delete(recursive: true));

    final stateFile =
        File('${tmp.path}${Platform.pathSeparator}.dafeiyu-bridge${Platform.pathSeparator}bridge-state.json');
    await stateFile.parent.create(recursive: true);
    await stateFile.writeAsString(jsonEncode({
      'secret': {'localKey': 'dfyl_from_file'},
    }));

    final fromEnv = await BridgeLocalSync.discoverLocalKey(
      environment: {
        'DAFEIYU_LOCAL_KEY': 'dfyl_from_env',
        'USERPROFILE': tmp.path,
      },
    );
    expect(fromEnv, 'dfyl_from_env');

    final fromFile = await BridgeLocalSync.discoverLocalKey(
      environment: {'USERPROFILE': tmp.path},
    );
    expect(fromFile, 'dfyl_from_file');

    final missing = await BridgeLocalSync.discoverLocalKey(
      environment: {'USERPROFILE': '${tmp.path}_nonexistent'},
    );
    expect(missing, '');
  });

  test('fetchToday parses tighten/relax states and degrades safely when offline or unauthorized', () async {
    var fakeNow = 1763770120000;
    Uri? seenUri;
    Map<String, String>? seenHeaders;
    var nextStatus = 200;
    var nextBody = '{}';
    var shouldThrow = false;

    final sync = BridgeLocalSync(
      keyResolver: () async => 'dfyl_secret_key',
      nowUtcMs: () => fakeNow,
      httpGet: (uri, headers) async {
        seenUri = uri;
        seenHeaders = headers;
        if (shouldThrow) {
          throw const SocketException('Connection refused');
        }
        return (statusCode: nextStatus, body: nextBody);
      },
    );

    // 1. Overdue seal locking -> tighten with real phone reason
    nextBody = jsonEncode({
      'ok': true,
      'phoneOnline': true,
      'syncedAt': fakeNow - 15000,
      'today': {
        'locking': {
          'id': 's1',
          'title': '行测资料分析30题',
          'actual': 12,
          'floor': 20,
          'until': '14:00',
        },
      },
      'verifier': {'active': false},
    });
    final lockSnap = await sync.fetchToday();
    expect(seenUri.toString(), BridgeLocalSync.defaultEndpointUrl);
    expect(seenHeaders?['Authorization'], 'Bearer dfyl_secret_key');
    expect(lockSnap.isSyncedOnline, isTrue);
    expect(lockSnap.syncAgeSecondsAt(fakeNow), 15);
    expect(lockSnap.directive, PhoneSyncDirective.tighten);
    expect(lockSnap.reason, contains('行测资料分析30题'));

    // 2. Default verification active -> tighten
    nextBody = jsonEncode({
      'ok': true,
      'phoneOnline': true,
      'syncedAt': fakeNow - 5000,
      'verifier': {'active': true, 'until': '18:00'},
    });
    final verifySnap = await sync.fetchToday();
    expect(verifySnap.directive, PhoneSyncDirective.tighten);
    expect(verifySnap.reason, contains('默认验收'));

    // 3. Evening cap hit -> tighten
    nextBody = jsonEncode({
      'ok': true,
      'phoneOnline': true,
      'syncedAt': fakeNow - 2000,
      'verifier': {
        'active': false,
        'evening': {'used': 30, 'cap': 30, 'hit': true},
      },
    });
    final eveningSnap = await sync.fetchToday();
    expect(eveningSnap.directive, PhoneSyncDirective.tighten);
    expect(eveningSnap.reason, contains('晚间娱乐封顶'));

    // 4. Night lock -> tighten
    nextBody = jsonEncode({
      'ok': true,
      'phoneOnline': true,
      'syncedAt': fakeNow - 2000,
      'verifier': {'nightLock': true},
    });
    final nightSnap = await sync.fetchToday();
    expect(nightSnap.directive, PhoneSyncDirective.tighten);
    expect(nightSnap.reason, contains('深夜锁'));

    // 5. Spend balance -> relax
    nextBody = jsonEncode({
      'ok': true,
      'phoneOnline': true,
      'syncedAt': fakeNow - 1000,
      'verifier': {
        'active': false,
        'spend': {'minutes': 20, 'until': '16:20'},
      },
    });
    final spendSnap = await sync.fetchToday();
    expect(spendSnap.directive, PhoneSyncDirective.relax);
    expect(spendSnap.reason, contains('花余额放风'));

    // 6. Emergency release -> relax
    nextBody = jsonEncode({
      'ok': true,
      'phoneOnline': true,
      'syncedAt': fakeNow - 1000,
      'verifier': {'active': true, 'emergency': true},
    });
    final emergencySnap = await sync.fetchToday();
    expect(emergencySnap.directive, PhoneSyncDirective.relax);
    expect(emergencySnap.reason, contains('紧急解除'));

    // 7. Recovery day allowed segment -> relax
    nextBody = jsonEncode({
      'ok': true,
      'phoneOnline': true,
      'syncedAt': fakeNow - 1000,
      'verifier': {'active': false, 'recovery': true},
    });
    final recoverySnap = await sync.fetchToday();
    expect(recoverySnap.directive, PhoneSyncDirective.relax);
    expect(recoverySnap.reason, contains('恢复日'));

    // 8. Bridge offline / HTTP error / invalid JSON -> deterministic offline fallback
    shouldThrow = true;
    final offlineSnap = await sync.fetchToday();
    expect(offlineSnap.bridgeOnline, isFalse);
    expect(offlineSnap.isEffective, isFalse);
    expect(offlineSnap.directive, PhoneSyncDirective.none);

    shouldThrow = false;
    nextStatus = 401;
    final unauthSnap = await sync.fetchToday();
    expect(unauthSnap.bridgeOnline, isFalse);

    nextStatus = 200;
    nextBody = 'not-json';
    final badJsonSnap = await sync.fetchToday();
    expect(badJsonSnap.bridgeOnline, isFalse);
  });

  test('fetchToday returns offline immediately when localKey is empty', () async {
    var called = false;
    final sync = BridgeLocalSync(
      keyResolver: () async => '',
      httpGet: (uri, headers) async {
        called = true;
        return (statusCode: 200, body: '{"ok":true}');
      },
    );

    final snap = await sync.fetchToday();
    expect(called, isFalse);
    expect(snap.bridgeOnline, isFalse);
    expect(snap.error, 'missing_local_key');
  });

  test('fetchToday handles timeout gracefully without throwing', () async {
    final sync = BridgeLocalSync(
      keyResolver: () async => 'dfyl_secret',
      httpGet: (uri, headers) =>
          Future<({int statusCode, String body})>.error(
        TimeoutException('timed out'),
      ),
    );

    final snap = await sync.fetchToday();
    expect(snap.bridgeOnline, isFalse);
    expect(snap.isEffective, isFalse);
  });
}
