import 'dart:convert';
import 'dart:io';

import 'package:dsh_go_fish_pc/services/dsh_bridge.dart';
import 'package:dsh_go_fish_pc/services/file_secret_store.dart';
import 'package:dsh_go_fish_pc/services/pc_sensor.dart';
import 'package:dsh_go_fish_pc/services/win32_bridge.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeWin32Bridge extends Win32Bridge {
  final calls = <String>[];
  ForegroundWindowSnapshot? nextForeground = const ForegroundWindowSnapshot(
    app: 'chrome',
    title: 'Bilibili 操作系统教程',
    idleSec: 1,
    selfActive: false,
  );
  bool capsule = false;

  @override
  Future<ForegroundWindowSnapshot?> getForeground() async {
    calls.add('getForeground');
    return nextForeground;
  }

  @override
  Future<bool> sendMediaPlayPause() async {
    calls.add('sendMediaPlayPause');
    return true;
  }

  @override
  Future<bool> setCapsuleMode(bool enable) async {
    calls.add('setCapsuleMode:$enable');
    capsule = enable;
    return capsule;
  }

  @override
  Future<bool> raiseWindow() async {
    calls.add('raiseWindow');
    return true;
  }
}

void main() {
  test('PcActivityClassifier mirrors sync.mjs activity classification', () {
    expect(
      PcActivityClassifier.classify(app: 'Code', title: 'main.dart - repo'),
      PcActivityCategory.coding,
    );
    expect(
      PcActivityClassifier.classify(app: 'Obsidian', title: 'Daily Review'),
      PcActivityCategory.docs,
    );
    expect(
      PcActivityClassifier.classify(app: 'msedge', title: 'Rust std docs github'),
      PcActivityCategory.research,
    );
    expect(
      PcActivityClassifier.classify(app: 'chrome', title: 'Bilibili 操作系统课程教程'),
      PcActivityCategory.studyVideo,
    );
    expect(
      PcActivityClassifier.classify(app: 'chrome', title: 'Bilibili 搞笑集锦'),
      PcActivityCategory.entertainment,
    );
    expect(
      PcActivityClassifier.classify(app: 'Code', title: 'main.dart', isAfk: true),
      PcActivityCategory.afk,
    );
  });

  test('FileSecretStore persists secrets and prefs atomically', () async {
    final tmp = await Directory.systemTemp.createTemp('dsh_pc_store_test_');
    addTearDown(() => tmp.delete(recursive: true));

    final store = FileSecretStore(tmp);
    await store.write('k1', 'v1');
    expect(await store.read('k1'), 'v1');
    await store.delete('k1');
    expect(await store.read('k1'), isNull);

    await store.savePrefs({'companionUrl': 'http://100.64.0.1:48912', 'enableAw': true});
    final prefs = await store.readPrefs();
    expect(prefs['companionUrl'], 'http://100.64.0.1:48912');
    expect(prefs['enableAw'], isTrue);
  });

  test('DesktopController connects to real loopback HTTP, sends say, submits 6-dice & wall, and triggers slice pause', () async {
    final tmp = await Directory.systemTemp.createTemp('dsh_pc_ctrl_test_');
    addTearDown(() => tmp.delete(recursive: true));

    final requests = <String>[];
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));

    server.listen((req) async {
      final path = req.uri.path;
      requests.add('${req.method} $path');
      final bodyStr = await utf8.decoder.bind(req).join();
      req.response.statusCode = 200;
      req.response.headers.contentType = ContentType.json;
      if (path == '/dsh-go-companion/v1/status') {
        req.response.write(jsonEncode({'v': 1, 'mode': 'companion'}));
      } else if (path == '/dsh-go-companion/v1/plan/day') {
        req.response.write(jsonEncode({
          'plan': {
            'queue': [
              {'protocolId': 'study', 'title': '深度编译', 'minute': 600, 'budget': 45}
            ]
          }
        }));
      } else if (path == '/dsh-go-companion/v1/wall') {
        final decoded = bodyStr.isNotEmpty ? jsonDecode(bodyStr) as Map : {};
        if (decoded['action'] == 'submit') {
          req.response.write(jsonEncode({'v': 1, 'ok': true}));
        } else {
          req.response.write(jsonEncode({
            'wall': {'locked': false, 'kind': 'normal', 'why': '正常守护'}
          }));
        }
      } else if (path == '/dsh-go-companion/v1/wall/thread') {
        req.response.write(jsonEncode({'thread': {'items': []}}));
      } else if (path == '/dsh-go-companion/v1/sisters') {
        req.response.write(jsonEncode({'mode': 'normal', 'summary': '鱼丸与鲸澜在线'}));
      } else if (path == '/dsh-go-companion/v1/deposit') {
        req.response.write(jsonEncode({'departments': {'plan': true}}));
      } else if (path == '/dsh-go-companion/v1/drill-op') {
        req.response.write(jsonEncode({'remaining': 2}));
      } else if (path == '/dsh-go-companion/v1/result') {
        req.response.write(jsonEncode({'state': 'generated', 'speaker': 'strategist', 'text': '〔鲸澜〕收到'}));
      } else {
        req.response.write(jsonEncode({'v': 1, 'ok': true}));
      }
      await req.response.close();
    });

    final fakeWin32 = _FakeWin32Bridge();
    final ctrl = DesktopController(
      store: FileSecretStore(tmp),
      win32: fakeWin32,
    );
    addTearDown(ctrl.dispose);

    await ctrl.init(autoStartSensor: false);
    await ctrl.connectCompanionEndpoint('http://${server.address.address}:${server.port}');
    expect(ctrl.hasHttpCompanion, isTrue);
    expect(requests, contains('GET /dsh-go-companion/v1/status'));

    ctrl.activeRole = 'strategist';
    await ctrl.sendSay('我先把架构图写完');
    expect(ctrl.chatLines.last.speaker, '鲸澜');

    final spec = SixDiceCardSpec.forStage(1).first;
    final shortRes = await ctrl.submitDiceAnswer(spec, '太短');
    expect(shortRes?['ok'], isFalse);

    final okDice = await ctrl.submitDiceAnswer(spec, '因为缓存失效前窗口读取了旧状态而不是最新快照');
    expect(okDice?['ok'], isTrue);

    final wallRes = await ctrl.submitArtifactToWall(title: 'PR Diff', content: '+ added guard');
    expect(wallRes?['ok'], isTrue);

    // Simulate 3-min video slice countdown firing VK_MEDIA_PLAY_PAUSE
    ctrl.toggleVideoSliceTimer(true);
    ctrl.videoSliceSecondsLeft = 5;
    await ctrl.tickSensor();
    expect(ctrl.sliceModalPending, isTrue);
    expect(fakeWin32.calls, contains('sendMediaPlayPause'));
    expect(fakeWin32.calls, contains('raiseWindow'));

    await ctrl.setCapsuleMode(true);
    expect(ctrl.capsuleMode, isTrue);
  });
}
