import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dsh_remote/dsh_remote.dart';
import 'package:flutter/foundation.dart';

import 'file_secret_store.dart';
import 'pc_sensor.dart';
import 'win32_bridge.dart';

class CompanionChatLine {
  const CompanionChatLine({
    required this.speaker,
    required this.role,
    required this.text,
    required this.at,
    this.isUser = false,
  });

  final String speaker;
  final String role; // 'companion' | 'strategist' | 'user' | 'system'
  final String text;
  final DateTime at;
  final bool isUser;
}

class SixDiceCardSpec {
  const SixDiceCardSpec({
    required this.stage,
    required this.slotId,
    required this.diceNumber,
    required this.name,
    required this.prompt,
    required this.minChars,
  });

  final int stage;
  final String slotId;
  final int diceNumber;
  final String name;
  final String prompt;
  final int minChars;

  static List<SixDiceCardSpec> forStage(int stage, {String topic = ''}) {
    final prefix = topic.trim().isEmpty ? '' : '围绕「${topic.trim()}」：';
    return switch (stage) {
      1 => [
          SixDiceCardSpec(
            stage: 1,
            slotId: 'dice_6',
            diceNumber: 6,
            name: '替代解释',
            prompt: '$prefix别急着认第一个结论：如果最顺嘴那个解释错了，还能怎么解释？',
            minChars: 10,
          ),
          SixDiceCardSpec(
            stage: 1,
            slotId: 'dice_1',
            diceNumber: 1,
            name: '事实提取',
            prompt: '$prefix不看原话，用一句说清刚才这段材料交待了哪一个硬事实？',
            minChars: 8,
          ),
        ],
      2 => [
          SixDiceCardSpec(
            stage: 2,
            slotId: 'dice_2',
            diceNumber: 2,
            name: '预判推演',
            prompt: '$prefix顺着当前这一步往下走，接下来最可能发生什么结果或坑？',
            minChars: 10,
          ),
          SixDiceCardSpec(
            stage: 2,
            slotId: 'dice_4',
            diceNumber: 4,
            name: '关键提问',
            prompt: '$prefix整段里哪一个还没弄清的未知条件，一旦变了结论就会翻盘？',
            minChars: 8,
          ),
        ],
      _ => [
          SixDiceCardSpec(
            stage: 3,
            slotId: 'dice_3',
            diceNumber: 3,
            name: '联想比较',
            prompt: '$prefix这跟你做过的哪一类旧题/旧案例最像？关键差别在哪？',
            minChars: 10,
          ),
          SixDiceCardSpec(
            stage: 3,
            slotId: 'dice_5',
            diceNumber: 5,
            name: '依据与结论',
            prompt: '$prefix你下的判断依据了哪条硬事实？如果只靠它，能稳推出什么？',
            minChars: 10,
          ),
        ],
    };
  }
}

/// Manages Host/Companion HTTP (`DshConnection`), E2E Noise_IK Relay (`RemoteAccount`),
/// dual-personality chat (`鱼丸`/`鲸澜`), Plan/Wall/Sisters/Deposit sync, Win32 PC sensor,
/// 3-min `video_slice_dice` auto pause modal, and compact Mini Capsule HUD.
class DesktopController extends ChangeNotifier {
  DesktopController({
    FileSecretStore? store,
    PcSensorService? sensor,
    Win32Bridge? win32,
  })  : _store = store ?? FileSecretStore(),
        _win32 = win32 ?? Win32Bridge(),
        sensor = sensor ?? PcSensorService(win32: win32 ?? Win32Bridge());

  final FileSecretStore _store;
  final Win32Bridge _win32;
  final PcSensorService sensor;

  static const _companionPrefix = '/dsh-go-companion/v1';

  // Connection state
  DshConnection? _httpConn;
  DshClient? _dshClient;
  RemoteAccount? _remoteAccount;
  RemoteUser? remoteUser;
  List<RemoteHost> remoteHosts = const [];
  String companionUrl = '';
  String launchToken = '';
  String remoteServerUrl = 'https://remote.deepseek-harness.com';
  String connectionStatus = '未连接（可填写手机/Host LAN 或 Tailscale 地址，或登录 Remote 中继）';
  bool connecting = false;
  bool get hasHttpCompanion => _httpConn != null;
  bool get hasDshClient => _dshClient != null;

  // Dual role chat & direct RPC sessions
  String activeRole = 'companion'; // 'companion' (鱼丸) | 'strategist' (鲸澜)
  final List<CompanionChatLine> chatLines = [];
  bool sendingChat = false;
  List<SessionSummary> sessions = const [];
  String? activeSessionId;
  final ChatTranscript transcript = ChatTranscript();
  StreamSubscription<DshEvent>? _watchSub;

  // Companion Hub data
  Map<String, Object?> statusData = const {};
  Map<String, Object?> planDayData = const {};
  Map<String, Object?> wallData = const {};
  Map<String, Object?> wallThreadData = const {};
  Map<String, Object?> sistersData = const {};
  Map<String, Object?> depositData = const {};
  Map<String, Object?> drillData = const {};

  // PC telemetry & 3-min video slice enforcement
  bool sensorRunning = false;
  bool enableAw = false;
  bool enableObsidian = false;
  bool enableStrictPlanGuard = true;
  bool enableVideoSliceTimer = false;
  int videoSliceSecondsLeft = 180;
  bool sliceModalPending = false;
  String slicePromptReason = '';
  PcTelemetrySample? latestSample;
  final List<PcTelemetrySample> historySamples = [];

  // Mini Capsule mode
  bool capsuleMode = false;

  Timer? _sensorTimer;
  int _tickCount = 0;

  Future<void> init({bool autoStartSensor = true}) async {
    final prefs = await _store.readPrefs();
    companionUrl = (prefs['companionUrl'] ?? '').toString();
    remoteServerUrl = (prefs['remoteServerUrl'] ?? remoteServerUrl).toString();
    enableAw = prefs['enableAw'] == true;
    enableObsidian = prefs['enableObsidian'] == true;
    enableStrictPlanGuard = prefs['enableStrictPlanGuard'] != false;
    sensor.activityWatchUrl = (prefs['awUrl'] ?? sensor.activityWatchUrl).toString();
    sensor.obsidianUrl = (prefs['obsidianUrl'] ?? sensor.obsidianUrl).toString();

    _remoteAccount = RemoteAccount(
      secrets: _store,
      serverUrl: remoteServerUrl,
      deviceName: 'DSH Go Fish PC (${Platform.localHostname})',
      platform: 'win32',
    );
    try {
      remoteUser = await _remoteAccount!.currentUser();
      if (remoteUser != null) {
        remoteHosts = await _remoteAccount!.listHosts();
      }
    } catch (_) {
      remoteUser = null;
      remoteHosts = const [];
    }

    if (autoStartSensor) {
      startSensorLoop();
    }
    notifyListeners();
  }

  Future<void> savePrefsPatch(Map<String, Object?> patch) async {
    final prev = await _store.readPrefs();
    prev.addAll(patch);
    await _store.savePrefs(prev);
  }

  Future<void> connectCompanionEndpoint(String url, {String token = ''}) async {
    final trimmed = url.trim().replaceAll(RegExp(r'/+$'), '');
    if (trimmed.isEmpty) {
      connectionStatus = '请输入有效的 Host/手机 LAN 或 Tailscale URL（如 http://100.x.y.z:48912）';
      notifyListeners();
      return;
    }
    connecting = true;
    notifyListeners();
    try {
      final parsed = Uri.parse(trimmed);
      final conn = DshConnection(parsed);
      if (token.trim().isNotEmpty) {
        await conn.login(token.trim());
      }
      _httpConn?.close();
      _httpConn = conn;
      companionUrl = trimmed;
      launchToken = token.trim();
      await savePrefsPatch({'companionUrl': trimmed});
      await refreshHub();
      connectionStatus = '已连接 Companion Host：$trimmed';

      // Also attempt full DshClient mux if host is a DSH Web server with token
      if (token.trim().isNotEmpty) {
        try {
          _dshClient = await DshClient.connect(baseUri: parsed, token: token.trim(), readyTimeout: const Duration(seconds: 6));
          sessions = await _dshClient!.listSessions();
          if (sessions.isNotEmpty) {
            await selectSession(sessions.first.sessionId);
          }
        } catch (_) {
          // Companion HTTP plugin endpoints still work directly without mux.
        }
      }
    } catch (e) {
      connectionStatus = '连接失败：$e';
    } finally {
      connecting = false;
      notifyListeners();
    }
  }

  Future<void> loginRemotePassword(String serverUrl, String email, String password) async {
    connecting = true;
    notifyListeners();
    try {
      remoteServerUrl = serverUrl.trim();
      await _remoteAccount?.close();
      _remoteAccount = RemoteAccount(
        secrets: _store,
        serverUrl: remoteServerUrl,
        deviceName: 'DSH Go Fish PC (${Platform.localHostname})',
        platform: 'win32',
      );
      remoteUser = await _remoteAccount!.loginWithPassword(email.trim(), password);
      await savePrefsPatch({'remoteServerUrl': remoteServerUrl});
      remoteHosts = await _remoteAccount!.listHosts();
      connectionStatus = 'Remote 已登录：${remoteUser!.account}（共 ${remoteHosts.length} 台 Host）';
    } catch (e) {
      connectionStatus = 'Remote 登录失败：$e';
    } finally {
      connecting = false;
      notifyListeners();
    }
  }

  Future<void> connectRemoteHost(RemoteHost host) async {
    if (_remoteAccount == null) return;
    connecting = true;
    notifyListeners();
    try {
      final client = await _remoteAccount!.connectHost(
        host,
        onState: (st) {
          connectionStatus = 'Noise_IK 隧道 (${host.name})：${st.phase.name}';
          notifyListeners();
        },
        confirmNewHost: (h) async => true,
      );
      _dshClient = client;
      sessions = await client.listSessions();
      if (sessions.isNotEmpty) {
        await selectSession(sessions.first.sessionId);
      }
      connectionStatus = '已通过端到端 Noise_IK 连接到 ${host.name}';
    } catch (e) {
      connectionStatus = 'Remote Host 连接失败：$e';
    } finally {
      connecting = false;
      notifyListeners();
    }
  }

  Future<void> selectSession(String sessionId) async {
    activeSessionId = sessionId;
    await _watchSub?.cancel();
    if (_dshClient == null) return;
    _watchSub = _dshClient!.watch(sessionId).listen((ev) {
      if (transcript.apply(ev)) {
        notifyListeners();
      }
    });
    notifyListeners();
  }

  Future<void> refreshHub() async {
    final conn = _httpConn;
    if (conn == null) return;
    try {
      final results = await Future.wait([
        _getJson(conn, '$_companionPrefix/status'),
        _getJson(conn, '$_companionPrefix/plan/day'),
        _postJson(conn, '$_companionPrefix/wall', {'timeZone': 'Asia/Shanghai'}),
        _getJson(conn, '$_companionPrefix/wall/thread?timeZone=Asia/Shanghai'),
        _postJson(conn, '$_companionPrefix/sisters', {'timeZone': 'Asia/Shanghai'}),
        _postJson(conn, '$_companionPrefix/deposit', {'timeZone': 'Asia/Shanghai'}),
        _getJson(conn, '$_companionPrefix/drill-op?timeZone=Asia/Shanghai'),
      ]);
      if (results[0] != null) statusData = results[0]!;
      if (results[1] != null) planDayData = results[1]!;
      if (results[2] != null) wallData = (results[2]!['wall'] as Map?)?.cast<String, Object?>() ?? results[2]!;
      if (results[3] != null) wallThreadData = (results[3]!['thread'] as Map?)?.cast<String, Object?>() ?? results[3]!;
      if (results[4] != null) sistersData = results[4]!;
      if (results[5] != null) depositData = results[5]!;
      if (results[6] != null) drillData = results[6]!;
    } catch (_) {}
    notifyListeners();
  }

  Future<void> sendSay(String text) async {
    final clean = text.trim();
    if (clean.isEmpty) return;
    chatLines.add(
      CompanionChatLine(
        speaker: '我 (PC)',
        role: 'user',
        text: clean,
        at: DateTime.now(),
        isUser: true,
      ),
    );
    sendingChat = true;
    notifyListeners();

    try {
      if (_httpConn != null) {
        final rid = 'pc-${DateTime.now().millisecondsSinceEpoch}';
        await _postJson(_httpConn!, '$_companionPrefix/say', {
          'requestId': rid,
          'role': activeRole,
          'text': clean,
          'timeZone': 'Asia/Shanghai',
        });
        final res = await _getJson(_httpConn!, '$_companionPrefix/result?requestId=$rid&waitMs=20000');
        final reply = (res?['text'] ?? '').toString();
        if (reply.isNotEmpty) {
          chatLines.add(
            CompanionChatLine(
              speaker: (res?['speaker'] ?? activeRole).toString() == 'strategist' ? '鲸澜' : '鱼丸',
              role: (res?['speaker'] ?? activeRole).toString(),
              text: reply,
              at: DateTime.now(),
            ),
          );
        } else {
          chatLines.add(
            CompanionChatLine(
              speaker: activeRole == 'strategist' ? '鲸澜' : '鱼丸',
              role: activeRole,
              text: '已收到消息，正在处理或已入账（result state: ${res?['state'] ?? 'accepted'}）。',
              at: DateTime.now(),
            ),
          );
        }
      } else if (_dshClient != null && activeSessionId != null) {
        await _dshClient!.prompt(activeSessionId!, clean, timeZone: 'Asia/Shanghai');
      } else {
        chatLines.add(
          CompanionChatLine(
            speaker: '系统',
            role: 'system',
            text: '当前未连接到 Companion Host 或 Remote Host；消息已在本地记录。请先点击右上角「连接」填写端地址或登录 Remote。',
            at: DateTime.now(),
          ),
        );
      }
    } catch (e) {
      chatLines.add(
        CompanionChatLine(speaker: '系统', role: 'system', text: '发送失败：$e', at: DateTime.now()),
      );
    } finally {
      sendingChat = false;
      notifyListeners();
    }
  }

  Future<Map<String, Object?>?> submitDeposit({
    required String department,
    required String thought,
    String report = '',
  }) async {
    final conn = _httpConn;
    if (conn == null) return {'ok': false, 'error': '未连接 Companion Host'};
    final res = await _postJson(conn, '$_companionPrefix/deposit', {
      'action': 'submit',
      'department': department,
      'report': report,
      'thought': thought,
      'timeZone': 'Asia/Shanghai',
    });
    await refreshHub();
    return res;
  }

  Future<Map<String, Object?>?> submitArtifactToWall({
    required String title,
    required String content,
    String sourcePath = '',
  }) async {
    final clean = content.trim();
    if (clean.isEmpty) return {'ok': false, 'error': '提交内容为空'};
    final summary = title.trim().isEmpty
        ? clean
        : '【PC 交付物：${title.trim()}】${sourcePath.isNotEmpty ? "\n来源：$sourcePath" : ""}\n$clean';
    if (_httpConn != null) {
      final res = await _postJson(_httpConn!, '$_companionPrefix/wall', {
        'action': 'submit',
        'text': summary.length > 4000 ? summary.substring(0, 4000) : summary,
        'source': 'pc-desktop',
        'timeZone': 'Asia/Shanghai',
      });
      await refreshHub();
      return res ?? {'ok': true};
    }
    return {'ok': false, 'error': '未连接 Companion Host（请先连接主机以入账到墙）'};
  }

  Future<Map<String, Object?>?> submitDiceAnswer(SixDiceCardSpec spec, String answer) async {
    final clean = answer.trim();
    if (clean.length < spec.minChars) {
      return {'ok': false, 'error': '该骰子动作至少需要 ${spec.minChars} 个字真实思考'};
    }
    if (_httpConn == null) {
      return {'ok': false, 'error': '未连接 Companion Host'};
    }
    final res = await _postJson(_httpConn!, '$_companionPrefix/bouts', {
      'v': 1,
      'action': 'answer',
      'slot': spec.slotId,
      'text': clean,
      'at': DateTime.now().millisecondsSinceEpoch,
    });
    await refreshHub();
    return res ?? {'ok': true};
  }

  Future<Map<String, Object?>?> submitWallAppeal({
    required int at,
    required String evidence,
  }) async {
    final conn = _httpConn;
    if (conn == null) return {'ok': false, 'error': '未连接 Companion Host'};
    final res = await _postJson(conn, '$_companionPrefix/wall/appeal', {
      'at': at,
      'evidence': evidence.trim(),
      'timeZone': 'Asia/Shanghai',
    });
    await refreshHub();
    return res;
  }

  Future<String> captureGitDiff(String repoPath) async {
    final dir = repoPath.trim().isEmpty ? Directory.current.path : repoPath.trim();
    final res = await Process.run('git', ['diff', '--stat', 'HEAD'], workingDirectory: dir);
    final diffBody = await Process.run('git', ['diff', 'HEAD'], workingDirectory: dir);
    final out = StringBuffer()
      ..writeln('# git diff HEAD @ $dir')
      ..writeln(res.stdout.toString().trim())
      ..writeln()
      ..writeln(diffBody.stdout.toString().trim());
    final text = out.toString();
    return text.length > 3500 ? '${text.substring(0, 3500)}\n... [truncated]' : text;
  }

  void startSensorLoop() {
    if (sensorRunning) return;
    sensorRunning = true;
    _sensorTimer?.cancel();
    _sensorTimer = Timer.periodic(const Duration(seconds: 5), (_) => unawaited(tickSensor()));
    unawaited(tickSensor());
    notifyListeners();
  }

  void stopSensorLoop() {
    sensorRunning = false;
    _sensorTimer?.cancel();
    _sensorTimer = null;
    notifyListeners();
  }

  void toggleVideoSliceTimer(bool enabled) {
    enableVideoSliceTimer = enabled;
    videoSliceSecondsLeft = 180;
    sliceModalPending = false;
    notifyListeners();
  }

  Future<void> triggerManualMediaPause() async {
    await _win32.sendMediaPlayPause();
    await _win32.raiseWindow();
    slicePromptReason = '已触发系统级媒体暂停，请用 1 句六骰思考检验刚才看进去了什么：';
    sliceModalPending = true;
    notifyListeners();
  }

  void dismissSliceModal() {
    sliceModalPending = false;
    videoSliceSecondsLeft = 180;
    notifyListeners();
  }

  Future<void> setCapsuleMode(bool enable) async {
    capsuleMode = await _win32.setCapsuleMode(enable);
    notifyListeners();
  }

  Future<void> tickSensor() async {
    _tickCount++;
    final sample = await sensor.pollOnce(
      checkAw: enableAw,
      checkObsidian: enableObsidian && _tickCount % 3 == 0,
    );
    latestSample = sample;
    historySamples.insert(0, sample);
    if (historySamples.length > 40) historySamples.removeLast();

    // Every 3 ticks (~15s), push PC telemetry heartbeat to companion if connected.
    if (_httpConn != null && _tickCount % 3 == 0) {
      unawaited(_postJson(_httpConn!, '$_companionPrefix/event', sample.toCompanionEvent()));
    }

    // 3-min video_slice_dice auto pause when watching video/browser learning or entertainment.
    if (enableVideoSliceTimer && !sample.isAfk && !sliceModalPending) {
      if (sample.category == PcActivityCategory.studyVideo ||
          sample.category == PcActivityCategory.entertainment) {
        videoSliceSecondsLeft = (videoSliceSecondsLeft - 5).clamp(0, 180);
        if (videoSliceSecondsLeft == 0) {
          await _win32.sendMediaPlayPause();
          await _win32.raiseWindow();
          slicePromptReason = '3 分钟视频切片到点！已自动发送媒体暂停键（${sample.app}），请先填 1 个反思锚点再继续：';
          sliceModalPending = true;
        }
      }
    }

    // Strict Plan window guard notification when entertainment is active during locked wall.
    final wallLocked = wallData['locked'] == true;
    if (enableStrictPlanGuard && wallLocked && sample.category == PcActivityCategory.entertainment && !sliceModalPending) {
      slicePromptReason = '手机端大肥鱼当前处于锁墙/专注窗口，但电脑前台正在运行「${sample.app} (${sample.title})」。';
      sliceModalPending = true;
      await _win32.raiseWindow();
    }

    notifyListeners();
  }

  Future<Map<String, Object?>?> _getJson(DshConnection conn, String path) async {
    try {
      final resp = await conn.get(path, timeout: const Duration(seconds: 6));
      if (resp.statusCode < 200 || resp.statusCode >= 300) return null;
      final decoded = jsonDecode(resp.text);
      return decoded is Map ? decoded.cast<String, Object?>() : null;
    } catch (_) {
      return null;
    }
  }

  Future<Map<String, Object?>?> _postJson(DshConnection conn, String path, Object body) async {
    try {
      final resp = await conn.postJson(path, body, timeout: const Duration(seconds: 12));
      final decoded = jsonDecode(resp.text);
      return decoded is Map ? decoded.cast<String, Object?>() : null;
    } catch (_) {
      return null;
    }
  }

  static Future<T?> runCatchingAsync<T>(Future<T> Function() fn) async {
    try {
      return await fn();
    } catch (_) {
      return null;
    }
  }

  @override
  void dispose() {
    _sensorTimer?.cancel();
    _watchSub?.cancel();
    _httpConn?.close();
    _remoteAccount?.close();
    super.dispose();
  }
}
