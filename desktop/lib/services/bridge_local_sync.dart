import 'dart:async';
import 'dart:convert';
import 'dart:io';

enum PhoneSyncDirective {
  none,
  tighten,
  relax,
}

class PhoneBridgeSnapshot {
  const PhoneBridgeSnapshot({
    this.bridgeOnline = false,
    this.phoneOnline = false,
    this.syncedAtUtcMs = 0,
    this.fetchedAtUtcMs = 0,
    this.directive = PhoneSyncDirective.none,
    this.reason = '',
    this.error = '',
  });

  const PhoneBridgeSnapshot.offline({
    int fetchedAtUtcMs = 0,
    String error = '',
  }) : this(
          bridgeOnline: false,
          phoneOnline: false,
          syncedAtUtcMs: 0,
          fetchedAtUtcMs: fetchedAtUtcMs,
          directive: PhoneSyncDirective.none,
          reason: '',
          error: error,
        );

  final bool bridgeOnline;
  final bool phoneOnline;
  final int syncedAtUtcMs;
  final int fetchedAtUtcMs;
  final PhoneSyncDirective directive;
  final String reason;
  final String error;

  bool get isSyncedOnline => bridgeOnline && phoneOnline;

  bool get isEffective =>
      bridgeOnline && phoneOnline && directive != PhoneSyncDirective.none;

  int syncAgeSecondsAt(int nowUtcMs) {
    final anchor = syncedAtUtcMs > 0 ? syncedAtUtcMs : fetchedAtUtcMs;
    if (anchor <= 0 || nowUtcMs < anchor) return 0;
    return ((nowUtcMs - anchor) / 1000).floor();
  }

  factory PhoneBridgeSnapshot.fromTodayJson(
    Map<String, Object?> raw, {
    required int fetchedAtUtcMs,
  }) {
    if (raw['ok'] != true) {
      return PhoneBridgeSnapshot.offline(
        fetchedAtUtcMs: fetchedAtUtcMs,
        error: 'bridge_not_ok',
      );
    }

    final todayMap = _asMap(raw['today']);
    final verifierMap = _asMap(
      raw['verifier'] ??
          raw['verify'] ??
          todayMap?['verifier'] ??
          todayMap?['verify'],
    );
    final lockingMap = _asMap(raw['locking'] ?? todayMap?['locking']);
    final verifyLockCenterMap =
        _asMap(raw['verifyLockCenter'] ?? todayMap?['verifyLockCenter']);
    final custodyMap = _asMap(raw['custody'] ?? todayMap?['custody']);
    final syncedAt = _asInt(raw['syncedAt']);

    final hasPayload = todayMap != null ||
        verifierMap != null ||
        lockingMap != null ||
        verifyLockCenterMap != null ||
        custodyMap != null;
    final phoneOnline = raw.containsKey('phoneOnline')
        ? raw['phoneOnline'] == true
        : hasPayload;

    if (!phoneOnline) {
      return PhoneBridgeSnapshot(
        bridgeOnline: true,
        phoneOnline: false,
        syncedAtUtcMs: syncedAt,
        fetchedAtUtcMs: fetchedAtUtcMs,
        directive: PhoneSyncDirective.none,
        reason: '手机暂未同步',
      );
    }

    final parsed = _resolveDirective(
      raw: raw,
      todayMap: todayMap,
      verifierMap: verifierMap,
      lockingMap: lockingMap,
      verifyLockCenterMap: verifyLockCenterMap,
      custodyMap: custodyMap,
    );

    return PhoneBridgeSnapshot(
      bridgeOnline: true,
      phoneOnline: true,
      syncedAtUtcMs: syncedAt,
      fetchedAtUtcMs: fetchedAtUtcMs,
      directive: parsed.directive,
      reason: parsed.reason,
    );
  }

  static ({PhoneSyncDirective directive, String reason}) _resolveDirective({
    required Map<String, Object?> raw,
    required Map<String, Object?>? todayMap,
    required Map<String, Object?>? verifierMap,
    required Map<String, Object?>? lockingMap,
    required Map<String, Object?>? verifyLockCenterMap,
    required Map<String, Object?>? custodyMap,
  }) {
    // 1. Explicit emergency release or balance spend pass -> relax PC block.
    final emergencyActive = verifierMap?['emergency'] == true ||
        verifierMap?['emergencyRelease'] == true ||
        verifierMap?['emergencyActive'] == true ||
        _asMap(verifierMap?['emergencyPass']) != null ||
        raw['emergency'] == true ||
        todayMap?['emergency'] == true;
    if (emergencyActive) {
      return (
        directive: PhoneSyncDirective.relax,
        reason: '手机紧急解除生效中',
      );
    }

    final spendMap = _asMap(verifierMap?['spend']);
    if (spendMap != null || verifierMap?['spend'] == true) {
      final mins = _asInt(spendMap?['minutes']);
      final until = (spendMap?['until'] ?? '').toString().trim();
      final detail = [
        if (mins > 0) '$mins 分钟',
        if (until.isNotEmpty) '至 $until',
      ].join('，');
      return (
        directive: PhoneSyncDirective.relax,
        reason: detail.isEmpty ? '手机花余额放风中' : '手机花余额放风中（$detail）',
      );
    }

    // 2. Strict phone lock states -> tighten PC enforcement with real reason.
    if (lockingMap != null) {
      final title = (lockingMap['title'] ?? '未过保底验收点').toString().trim();
      final actual = _asInt(lockingMap['actual']);
      final floor = _asInt(lockingMap['floor']);
      final until = (lockingMap['until'] ?? '').toString().trim();
      final progress = floor > 0 ? '（$actual/$floor' : '（';
      final untilText = until.isNotEmpty
          ? (floor > 0 ? '，最晚 $until）' : '最晚 $until）')
          : (floor > 0 ? '）' : '');
      final suffix = (floor > 0 || until.isNotEmpty) ? '$progress$untilText' : '';
      return (
        directive: PhoneSyncDirective.tighten,
        reason: '手机封印逾期严格锁：「$title」$suffix',
      );
    }

    final overdueCount = _asInt(verifyLockCenterMap?['overdueCount']);
    if (overdueCount > 0) {
      return (
        directive: PhoneSyncDirective.tighten,
        reason: '手机计划块 P3 验收锁逾期严锁（$overdueCount 项待交货）',
      );
    }

    if (custodyMap?['active'] == true) {
      final title = (custodyMap?['title'] ?? '托管冲刺轨').toString().trim();
      final untilHm = (custodyMap?['untilHm'] ?? '').toString().trim();
      return (
        directive: PhoneSyncDirective.tighten,
        reason: untilHm.isNotEmpty
            ? '手机托管冲刺轨护航中：「$title」（至 $untilHm）'
            : '手机托管冲刺轨护航中：「$title」',
      );
    }

    final eveningMap = _asMap(verifierMap?['evening']);
    if (eveningMap != null && eveningMap['hit'] == true) {
      final used = _asInt(eveningMap['used']);
      final cap = _asInt(eveningMap['cap']);
      final quota = cap > 0 ? '（$used/$cap 分钟）' : '';
      return (
        directive: PhoneSyncDirective.tighten,
        reason: '手机晚间娱乐封顶已用完$quota，锁至明早 07:00',
      );
    }

    final nightLocked = verifierMap?['nightLock'] == true ||
        verifierMap?['nightCurfew'] == true ||
        todayMap?['nightLock'] == true ||
        raw['nightLock'] == true;
    if (nightLocked) {
      return (
        directive: PhoneSyncDirective.tighten,
        reason: '手机深夜锁生效中',
      );
    }

    if (verifierMap?['active'] == true) {
      final until = (verifierMap?['until'] ?? '').toString().trim();
      return (
        directive: PhoneSyncDirective.tighten,
        reason: until.isNotEmpty
            ? '手机默认验收严防中（至 $until）'
            : '手机默认验收严防中',
      );
    }

    // 3. Recovery day allowed segment -> relax PC block when not locked above.
    if (verifierMap?['recovery'] == true &&
        verifierMap?['recoveryAllowed'] != false) {
      return (
        directive: PhoneSyncDirective.relax,
        reason: '手机处于恢复日允许段',
      );
    }

    return (
      directive: PhoneSyncDirective.none,
      reason: '手机在线（当前无额外锁或放风）',
    );
  }

  static Map<String, Object?>? _asMap(Object? raw) {
    if (raw is Map<String, Object?>) return raw;
    if (raw is Map) return raw.cast<String, Object?>();
    return null;
  }

  static int _asInt(Object? raw, {int fallback = 0}) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    return int.tryParse((raw ?? '').toString()) ?? fallback;
  }
}

typedef BridgeHttpGet = Future<({int statusCode, String body})> Function(
  Uri uri,
  Map<String, String> headers,
);

typedef BridgeKeyResolver = Future<String> Function();

class BridgeLocalSync {
  BridgeLocalSync({
    String endpointUrl = defaultEndpointUrl,
    BridgeHttpGet? httpGet,
    BridgeKeyResolver? keyResolver,
    int Function()? nowUtcMs,
  })  : _endpointUri = Uri.parse(endpointUrl),
        _httpGet = httpGet ?? _defaultHttpGet,
        _keyResolver = keyResolver ?? discoverLocalKey,
        _nowUtcMs =
            nowUtcMs ?? (() => DateTime.now().toUtc().millisecondsSinceEpoch);

  static const String defaultEndpointUrl =
      'http://127.0.0.1:8788/bridge/local/today';

  final Uri _endpointUri;
  final BridgeHttpGet _httpGet;
  final BridgeKeyResolver _keyResolver;
  final int Function() _nowUtcMs;

  static Future<String> discoverLocalKey({
    Map<String, String>? environment,
    String? stateFilePath,
  }) async {
    final env = environment ?? Platform.environment;
    final envKey = (env['DAFEIYU_LOCAL_KEY'] ?? '').trim();
    if (envKey.isNotEmpty) {
      return envKey;
    }

    final resolvedPath = stateFilePath ?? _defaultBridgeStatePath(env);
    if (resolvedPath.isEmpty) return '';

    final file = File(resolvedPath);
    try {
      if (!await file.exists()) return '';
      final decoded = jsonDecode(await file.readAsString());
      if (decoded is! Map) return '';
      final secret = decoded['secret'];
      if (secret is! Map) return '';
      return (secret['localKey'] ?? '').toString().trim();
    } catch (_) {
      return '';
    }
  }

  static String _defaultBridgeStatePath(Map<String, String> env) {
    final home = (env['USERPROFILE'] ?? env['HOME'] ?? '').trim();
    if (home.isEmpty) return '';
    return '$home${Platform.pathSeparator}.dafeiyu-bridge${Platform.pathSeparator}bridge-state.json';
  }

  Future<PhoneBridgeSnapshot> fetchToday() async {
    final now = _nowUtcMs();
    String localKey = '';
    try {
      localKey = (await _keyResolver()).trim();
    } catch (_) {
      return PhoneBridgeSnapshot.offline(
        fetchedAtUtcMs: now,
        error: 'key_discovery_failed',
      );
    }

    if (localKey.isEmpty) {
      return PhoneBridgeSnapshot.offline(
        fetchedAtUtcMs: now,
        error: 'missing_local_key',
      );
    }

    try {
      final resp = await _httpGet(
        _endpointUri,
        {'Authorization': 'Bearer $localKey'},
      );
      if (resp.statusCode != 200) {
        return PhoneBridgeSnapshot.offline(
          fetchedAtUtcMs: now,
          error: 'http_${resp.statusCode}',
        );
      }
      final decoded = jsonDecode(resp.body);
      if (decoded is! Map) {
        return PhoneBridgeSnapshot.offline(
          fetchedAtUtcMs: now,
          error: 'invalid_json_shape',
        );
      }
      return PhoneBridgeSnapshot.fromTodayJson(
        decoded.cast<String, Object?>(),
        fetchedAtUtcMs: now,
      );
    } catch (_) {
      // Never propagate network errors or leak localKey into logs/UI.
      return PhoneBridgeSnapshot.offline(
        fetchedAtUtcMs: now,
        error: 'bridge_unreachable',
      );
    }
  }

  static Future<({int statusCode, String body})> _defaultHttpGet(
    Uri uri,
    Map<String, String> headers,
  ) async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 2)
      ..findProxy = (_) => 'DIRECT';
    try {
      final req =
          await client.getUrl(uri).timeout(const Duration(seconds: 4));
      headers.forEach(req.headers.set);
      final resp = await req.close().timeout(const Duration(seconds: 4));
      final body = await resp
          .transform(utf8.decoder)
          .join()
          .timeout(const Duration(seconds: 4));
      return (statusCode: resp.statusCode, body: body);
    } finally {
      client.close(force: true);
    }
  }
}
