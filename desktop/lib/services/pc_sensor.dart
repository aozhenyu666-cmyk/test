import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'win32_bridge.dart';

enum PcActivityCategory {
  coding('coding', '编程开发', true),
  docs('docs', '文档笔记', true),
  research('research', '资料检索', true),
  studyVideo('study_video', '学习视频', true),
  entertainment('entertainment', '娱乐视频/游戏', false),
  browser('browser', '网页浏览', false),
  afk('afk', '离开键盘', false),
  other('other', '其他窗口', false);

  const PcActivityCategory(this.wire, this.label, this.isFocus);
  final String wire;
  final String label;
  final bool isFocus;
}

class PcActivityClassifier {
  static final _codingApps = RegExp(
    r'(code|cursor|windsurf|idea|pycharm|webstorm|goland|rustrover|vim|nvim|wt|windows terminal|powershell|pwsh|iterm|alacritty|cmd|devenv)',
    caseSensitive: false,
  );
  static final _docsApps = RegExp(
    r'(obsidian|notion|logseq|typora|word|winword|wps|acrobat|zotero|onenote|excel|sumatrapdf)',
    caseSensitive: false,
  );
  static final _videoEnt = RegExp(
    r'(bilibili|youtube|douyin|tiktok|iqiyi|youku|netflix|twitch|steam|epicgames|genshin|league|valorant|potplayer|vlc|mpv)',
    caseSensitive: false,
  );
  static final _browserApps = RegExp(
    r'(chrome|msedge|edge|firefox|brave|arc|opera|vivaldi)',
    caseSensitive: false,
  );
  static final _learningKeywords = RegExp(
    r'(docs|github|stackoverflow|arxiv|mdn|wikipedia|rfc|tutorial|course|paper|算法|源码|文档|教程|公开课|讲座)',
    caseSensitive: false,
  );

  static PcActivityCategory classify({
    required String app,
    required String title,
    bool isAfk = false,
  }) {
    if (isAfk) return PcActivityCategory.afk;
    if (_codingApps.hasMatch(app)) return PcActivityCategory.coding;
    if (_docsApps.hasMatch(app)) return PcActivityCategory.docs;
    if (_videoEnt.hasMatch(app) || _videoEnt.hasMatch(title)) {
      return _learningKeywords.hasMatch(title)
          ? PcActivityCategory.studyVideo
          : PcActivityCategory.entertainment;
    }
    if (_browserApps.hasMatch(app)) {
      return _learningKeywords.hasMatch(title)
          ? PcActivityCategory.research
          : PcActivityCategory.browser;
    }
    return PcActivityCategory.other;
  }
}

class PcTelemetrySample {
  const PcTelemetrySample({
    required this.sampledAt,
    required this.app,
    required this.title,
    required this.idleSec,
    required this.isAfk,
    required this.category,
    required this.focusMinutes,
    required this.entertainmentMinutes,
    required this.source,
    this.obsidianNote = '',
  });

  final DateTime sampledAt;
  final String app;
  final String title;
  final int idleSec;
  final bool isAfk;
  final PcActivityCategory category;
  final double focusMinutes;
  final double entertainmentMinutes;
  final String source;
  final String obsidianNote;

  Map<String, Object?> toCompanionEvent() => {
        'type': 'pc-activity',
        'at': sampledAt.millisecondsSinceEpoch,
        'isAfk': isAfk,
        'afkStatus': isAfk ? 'afk' : 'not-afk',
        'activeApp': app,
        'activeTitle': title.length > 120 ? title.substring(0, 120) : title,
        'category': category.wire,
        'focusMinutes': (focusMinutes * 10).roundToDouble() / 10,
        'entertainmentMinutes': (entertainmentMinutes * 10).roundToDouble() / 10,
        'obsidianNote': obsidianNote,
        'source': source,
      };
}

/// Polls active Windows app/title + AFK idle seconds, optionally fuses local ActivityWatch
/// (:5600) & Obsidian Local REST (:27123), and triggers 3-min `video_slice_dice` pauses.
class PcSensorService {
  PcSensorService({
    Win32Bridge? win32,
    this.afkThresholdSec = 180,
    this.activityWatchUrl = 'http://127.0.0.1:5600',
    this.obsidianUrl = 'http://127.0.0.1:27123',
    this.obsidianToken = '',
  }) : win32 = win32 ?? Win32Bridge();

  final Win32Bridge win32;
  final int afkThresholdSec;
  String activityWatchUrl;
  String obsidianUrl;
  String obsidianToken;

  double _focusSec = 0;
  double _entSec = 0;
  DateTime? _lastSampleAt;

  Future<PcTelemetrySample> pollOnce({bool checkAw = false, bool checkObsidian = false}) async {
    final now = DateTime.now();
    final dtSec = _lastSampleAt == null
        ? 0.0
        : now.difference(_lastSampleAt!).inMilliseconds.clamp(0, 60000) / 1000.0;
    _lastSampleAt = now;

    var app = '';
    var title = '';
    var idleSec = 0;
    var source = 'win32';

    final fg = await win32.getForeground();
    if (fg != null) {
      app = fg.app;
      title = fg.title;
      idleSec = fg.idleSec;
    } else if (checkAw) {
      final aw = await probeActivityWatch();
      if (aw != null) {
        app = aw.$1;
        title = aw.$2;
        idleSec = aw.$3 ? afkThresholdSec : 0;
        source = 'activitywatch';
      }
    }

    var obsNote = '';
    if (checkObsidian) {
      obsNote = await probeObsidianActiveNote() ?? '';
    }

    final isAfk = idleSec >= afkThresholdSec;
    final category = PcActivityClassifier.classify(app: app, title: title, isAfk: isAfk);

    if (!isAfk) {
      if (category.isFocus) {
        _focusSec += dtSec;
      } else if (category == PcActivityCategory.entertainment) {
        _entSec += dtSec;
      }
    }

    return PcTelemetrySample(
      sampledAt: now,
      app: app,
      title: title,
      idleSec: idleSec,
      isAfk: isAfk,
      category: category,
      focusMinutes: _focusSec / 60.0,
      entertainmentMinutes: _entSec / 60.0,
      source: source,
      obsidianNote: obsNote,
    );
  }

  Future<(String app, String title, bool isAfk)?> probeActivityWatch() async {
    final base = activityWatchUrl.trim().replaceAll(RegExp(r'/+$'), '');
    if (base.isEmpty) return null;
    final client = HttpClient()..connectionTimeout = const Duration(milliseconds: 900);
    try {
      final req = await client.getUrl(Uri.parse('$base/api/0/buckets/'));
      final resp = await req.close().timeout(const Duration(seconds: 2));
      if (resp.statusCode != 200) return null;
      final buckets = jsonDecode(await resp.transform(utf8.decoder).join());
      if (buckets is! Map) return null;
      String? winId;
      for (final id in buckets.keys.map((e) => e.toString())) {
        if (id.contains('watcher-window')) winId = id;
      }
      if (winId == null) return null;
      final evReq = await client.getUrl(
        Uri.parse('$base/api/0/buckets/${Uri.encodeComponent(winId)}/events?limit=1'),
      );
      final evResp = await evReq.close().timeout(const Duration(seconds: 2));
      if (evResp.statusCode != 200) return null;
      final list = jsonDecode(await evResp.transform(utf8.decoder).join());
      if (list is! List || list.isEmpty) return null;
      final first = list.first as Map;
      final data = first['data'] as Map? ?? const {};
      return ((data['app'] ?? '').toString(), (data['title'] ?? '').toString(), false);
    } catch (_) {
      return null;
    } finally {
      client.close(force: true);
    }
  }

  Future<String?> probeObsidianActiveNote() async {
    final base = obsidianUrl.trim().replaceAll(RegExp(r'/+$'), '');
    if (base.isEmpty) return null;
    final client = HttpClient()
      ..connectionTimeout = const Duration(milliseconds: 900)
      ..badCertificateCallback = (_, host, port) => host == '127.0.0.1' || host == 'localhost';
    try {
      final req = await client.getUrl(Uri.parse('$base/active/'));
      if (obsidianToken.isNotEmpty) {
        req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $obsidianToken');
      }
      final resp = await req.close().timeout(const Duration(seconds: 2));
      if (resp.statusCode != 200) return null;
      final body = await resp.transform(utf8.decoder).join();
      try {
        final decoded = jsonDecode(body);
        if (decoded is Map) return (decoded['path'] ?? '').toString();
      } catch (_) {}
      return body.split('\n').first.trim();
    } catch (_) {
      return null;
    } finally {
      client.close(force: true);
    }
  }
}
