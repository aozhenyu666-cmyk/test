import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/loop_channel.dart';
import '../channels/proactive_state.dart';
import 'app_picker_dialog.dart';
import 'bank_manager_page.dart';
import 'gemini_bridge_page.dart';
import 'locks_balance_page.dart';
import 'plan_modality_page.dart';
import 'records_page.dart';
import 'v6_command_page.dart';
import 'wanshiming_page.dart';

enum LiveCoreAtlasTab { action, atlas, records }

/// Gemini Live 窥视核心 · 功能图谱与运行记录：
/// 把复杂的底层感知 / 6 只手锁仲裁 / 4 源知识灌料收拢为 3 个超低认知负荷切页：
/// 1) 此刻操作：状态一眼看清 + 1 键摇骰灌料进 Live + 25m 验收点 / 20m 纯刷题接力 + 硬件就绪灯；
/// 2) 功能图谱：4 步闭环 + 4 大核心支柱（何时触发 / 何时让路）+ P0–P6 零冲突优先级梯；
/// 3) 运行记录：最近点火 / 让路 / 静音泄压 / 接力交接的可追溯明细。
class LiveCoreAtlasPage extends StatefulWidget {
  const LiveCoreAtlasPage({
    super.key,
    this.channel,
    this.loopChannel,
    this.initialTab = LiveCoreAtlasTab.action,
    this.onShareFile,
  });

  final ProactiveChannel? channel;
  final LoopChannel? loopChannel;
  final LiveCoreAtlasTab initialTab;
  final Future<void> Function(String filename, String content)? onShareFile;

  static Future<void> open(
    BuildContext context, {
    ProactiveChannel? channel,
    LoopChannel? loopChannel,
    LiveCoreAtlasTab initialTab = LiveCoreAtlasTab.action,
  }) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => LiveCoreAtlasPage(
            channel: channel,
            loopChannel: loopChannel,
            initialTab: initialTab,
          ),
        ),
      );

  @override
  State<LiveCoreAtlasPage> createState() => _LiveCoreAtlasPageState();
}

class _LiveCoreAtlasPageState extends State<LiveCoreAtlasPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late LiveCoreAtlasTab _tab = widget.initialTab;

  bool _loading = true;
  bool _busy = false;
  String? _banner;
  String _historyFilter = 'all';

  Map<String, dynamic> _liveCore = const {};
  Map<String, dynamic> _atlas = const {};
  List<Map<String, dynamic>> _history = const [];
  ProactiveStatus? _proactiveStatus;
  Map<String, dynamic> _bridgeStatus = const {};
  Map<String, dynamic> _v6State = const {};
  Map<String, dynamic> _liveSignals = const {};
  Timer? _cadenceTimer;

  static const _fallbackPipeline = <Map<String, dynamic>>[
    {'step': 1, 'key': 'sense', 'label': '感知现场', 'status': 'ready'},
    {'step': 2, 'key': 'dice', 'label': '骰子灌料', 'status': 'ready'},
    {'step': 3, 'key': 'live', 'label': '官方 Live', 'status': 'fallback'},
    {'step': 4, 'key': 'relay', 'label': '25m 接力', 'status': 'ready'},
  ];

  static const _fallbackPillars = <Map<String, dynamic>>[
    {
      'id': 'ignite',
      'title': '① 窥视点火（1 颗骰子）',
      'tagline': '后台整合设定/错题/笔记/屏幕摘录，一键灌进官方 Gemini Live',
      'triggerRule': '白天防分心窗空转/听歌/断线未复盘时自动点火',
      'yieldRule': '刷题/学习/睡眠/计划模态/连刷 5 题时绝对让路',
      'status': 'ready',
      'statusLabel': '待命中',
      'metricText': '6 面骰轮换破冰',
      'route': 'live_core',
    },
    {
      'id': 'locks',
      'title': '② 零冲突锁仲裁（P0–P6 · 6 只手）',
      'tagline': '单一裁判裁决优先级，绝无影子锁互相打架',
      'triggerRule': '按 P0 急事 > P1 睡眠 > P2 连麦舱 > P3 逾期验收 > P4 专心局/防分心 > P6 余额 裁决',
      'yieldRule': '应急通道、电话/短信/支付/导航永不误锁',
      'status': 'ready',
      'statusLabel': '待读取',
      'metricText': '严格 P0–P6 单轨裁决',
      'route': 'locks',
    },
    {
      'id': 'plan',
      'title': '③ 计划模态与验收锁（25m 接力）',
      'tagline': '先定今日计划再启动验收锁；Live 聊完可 1 键签 25m 微验收点',
      'triggerRule': '未定计划时温和拉回，到点未交按节点转 P3 验收锁',
      'yieldRule': '已过保底或放风/午休时段不打扰',
      'status': 'ready',
      'statusLabel': '待立约',
      'metricText': '先规划、再执行、到点验收',
      'route': 'plan',
    },
    {
      'id': 'quiz',
      'title': '④ 刷题账本与记忆回流（10 题 5 分）',
      'tagline': '刷题挣余额不被旧债吞噬，错题与 FSRS 到期卡自动灌回下次 Live',
      'triggerRule': '锁屏刷题或进入 20m 纯刷题舱直接累积正向额度',
      'yieldRule': '连刷 5 题自动触发 20m 静音泄压阀',
      'status': 'ready',
      'statusLabel': '就绪',
      'metricText': '正向净增不吞余额',
      'route': 'bank',
    },
  ];

  static const _fallbackLadder = <Map<String, dynamic>>[
    {'priority': 'P0', 'name': '应急通道 & 安全底线', 'rule': '3m 应急解禁、电话/短信/支付/导航永不锁', 'active': false},
    {'priority': 'P1', 'name': '睡眠守护 & 放风窗', 'rule': '23:00–07:00 睡眠锁或午休/合法放风窗（Live 让路）', 'active': false},
    {'priority': 'P2', 'name': 'Gemini 连麦共脑舱', 'rule': '语音/口头考辩专属时段，只放行共脑应用', 'active': false},
    {'priority': 'P3', 'name': '计划验收逾期严锁', 'rule': '到点未交保底转严锁（不可花零散余额绕过）', 'active': false},
    {'priority': 'P4', 'name': '专心局 & 白天防分心窗', 'rule': '常规白名单护航；闲逛发呆时自动点火进 Live', 'active': false},
    {'priority': 'P6', 'name': '余额门槛 & 雷达软提示', 'rule': '无强锁时的温和余额计量与频率约束', 'active': false},
  ];

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _cadenceTimer?.cancel();
    super.dispose();
  }

  void _syncCadenceTimer() {
    final cad = _asMap(_v6State['cadence']);
    final shouldTick = cad['active'] == true && '${cad['phase'] ?? ''}' == 'running';
    if (shouldTick && _cadenceTimer == null) {
      _cadenceTimer = Timer.periodic(const Duration(seconds: 1), (_) {
        if (mounted) setState(() {});
      });
    } else if (!shouldTick && _cadenceTimer != null) {
      _cadenceTimer?.cancel();
      _cadenceTimer = null;
    }
  }

  Map<String, dynamic> _asMap(Object? raw) {
    if (raw is Map<String, dynamic>) return raw;
    if (raw is Map) {
      return {
        for (final e in raw.entries)
          if (e.key is String) e.key as String: e.value,
      };
    }
    return const {};
  }

  List<Map<String, dynamic>> _asList(Object? raw) {
    if (raw is! List) return const [];
    return [
      for (final item in raw)
        if (item is Map) _asMap(item),
    ];
  }

  void _applyLiveCoreResponse(Map<String, dynamic>? r) {
    if (r == null) return;
    final nested = _asMap(r['liveCore']);
    _liveCore = nested.isNotEmpty ? nested : r;
    final atlasMap = _asMap(r['atlas']);
    if (atlasMap.isNotEmpty) _atlas = atlasMap;
    if (r['history'] is List) {
      _history = _asList(r['history']);
    }
  }

  Future<void> _refreshV6() async {
    final results = await Future.wait<Object?>([
      _channel.v6Op(const {'action': 'status'}).catchError((_) => null),
      _channel.v6LiveSignals().catchError((_) => null),
    ]);
    if (!mounted) return;
    setState(() {
      final v6 = results[0];
      if (v6 is Map && v6['ok'] == true) {
        _v6State = _asMap(v6);
      }
      final sig = results[1];
      if (sig is Map) {
        _liveSignals = _asMap(sig);
      } else {
        final nestedSig = _asMap(_v6State['liveSignals']);
        if (nestedSig.isNotEmpty) _liveSignals = nestedSig;
      }
      _syncCadenceTimer();
    });
  }

  Future<void> _refresh() async {
    unawaited(_refreshV6());
    final results = await Future.wait<Object?>([
      _channel.liveCoreOp(const {'action': 'atlas'}).catchError((_) => null),
      _channel.status().catchError((_) => null),
      _channel.bridgeOp(const {'action': 'status'}).catchError((_) => null),
    ]);
    if (!mounted) return;
    setState(() {
      _loading = false;
      final lc = results[0];
      if (lc is Map<String, dynamic>) {
        _applyLiveCoreResponse(lc);
      }
      final st = results[1];
      if (st is ProactiveStatus) {
        _proactiveStatus = st;
      }
      final br = results[2];
      if (br is Map<String, dynamic>) {
        _bridgeStatus = br;
      }
    });
  }

  Future<void> _startFiveMinCadence({String? presetTarget}) async {
    if (_busy) return;
    var target = (presetTarget ?? '').trim();
    if (target.isEmpty) {
      final ctrl = TextEditingController(text: '${_asMap(_v6State['cadence'])['target'] ?? ''}');
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('⚡ 开启 5 分钟严验收局'),
          content: TextField(
            controller: ctrl,
            autofocus: true,
            maxLines: 2,
            decoration: const InputDecoration(
              labelText: '这 5 分钟只死磕哪 1 个具体动作？',
              hintText: '例：做完 5 道资料分析速算并写 1 句错因',
              border: OutlineInputBorder(),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('取消')),
            FilledButton(onPressed: () => Navigator.of(ctx).pop(true), child: const Text('开锁冲刺')),
          ],
        ),
      );
      target = ctrl.text.trim();
      ctrl.dispose();
      if (ok != true || !mounted) return;
    }
    setState(() => _busy = true);
    try {
      final res = await _channel.v6Op({
        'action': 'cadence_start',
        if (target.isNotEmpty) 'target': target,
        'intervalSec': 300,
      });
      await _refresh();
      if (!mounted) return;
      setState(() {
        _banner = res?['ok'] == true
            ? '⏱️ 5 分钟严验收已开启：到点自动闹铃 + P3 真硬锁（不可敷衍跳过）'
            : '${res?['error'] ?? '启动 5 分钟严验收失败'}';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _verifyFiveMinCadence() async {
    if (_busy) return;
    final ctrl = TextEditingController();
    bool continueNext = true;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDlg) => AlertDialog(
          title: const Text('✅ 5 分钟严验收 · 交出真实产出'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: ctrl,
                maxLines: 3,
                autofocus: true,
                decoration: const InputDecoration(
                  labelText: '具体推进结果/结论（>=6字，拒绝敷衍词）',
                  hintText: '例：做完第4组基期比较，发现忘减增长量分母陷阱',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 8),
              CheckboxListTile(
                value: continueNext,
                contentPadding: EdgeInsets.zero,
                title: const Text('过关后自动续下一轮 5 分钟（满 3 轮自动刻入万世铭）', style: TextStyle(fontSize: 12.5)),
                onChanged: (v) => setDlg(() => continueNext = v == true),
              ),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('取消')),
            FilledButton(onPressed: () => Navigator.of(ctx).pop(true), child: const Text('提交过关')),
          ],
        ),
      ),
    );
    final sub = ctrl.text.trim();
    ctrl.dispose();
    if (confirmed != true || !mounted) return;
    setState(() => _busy = true);
    try {
      final res = await _channel.v6Op({
        'action': 'cadence_verify',
        'submission': sub,
        'continueNext': continueNext,
      });
      await _refresh();
      if (!mounted) return;
      setState(() {
        _banner = res?['ok'] == true
            ? '${res?['message'] ?? '验收通过'}'
            : '⚠️ ${res?['error'] ?? '提交被退回，请填写更具体的结论'}';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _stopFiveMinCadence() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await _channel.v6Op({'action': 'cadence_stop'});
      await _refresh();
      if (mounted) setState(() => _banner = '已结束当前 5 分钟严验收节奏');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _verifyFiveMinByMicroQuiz() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final res = await _channel.v6Op({'action': 'pool_sample', 'count': 8, 'markShown': true});
      final items = _asList(res?['items']);
      if (!mounted) return;
      if (items.isEmpty) {
        setState(() => _banner = '知识海暂无可用考题，请先吞剪贴板或直接提交文字结论');
        return;
      }
      int streak = 0;
      int idx = 0;
      bool passed = false;
      await showDialog<void>(
        context: context,
        barrierDismissible: true,
        builder: (ctx) => StatefulBuilder(
          builder: (ctx, setDlg) {
            final item = items[idx % items.length];
            final claim = '${item['claim'] ?? ''}';
            final isTrueAns = item['isTrue'] == true;
            final correction = '${item['correction'] ?? ''}';
            final itemId = '${item['id'] ?? ''}';
            final startedAt = DateTime.now().millisecondsSinceEpoch;
            Future<void> pick(bool userTrue) async {
              final elapsed = (DateTime.now().millisecondsSinceEpoch - startedAt).clamp(100, 120000);
              unawaited(_channel.v6Op({
                'action': 'pool_answer',
                'itemId': itemId,
                'userTrue': userTrue,
                'elapsedMs': elapsed,
              }));
              if (userTrue == isTrueAns) {
                final nextStreak = streak + 1;
                if (nextStreak >= 3) {
                  passed = true;
                  if (ctx.mounted) Navigator.of(ctx).pop();
                  return;
                }
                setDlg(() {
                  streak = nextStreak;
                  idx += 1;
                });
              } else {
                setDlg(() {
                  streak = 0;
                  idx += 1;
                });
                if (mounted) {
                  setState(() => _banner = '判错连胜归零：$correction');
                }
              }
            }

            return AlertDialog(
              title: Text('⚡ 连答 3 题硬核过关（$streak / 3）'),
              content: Text(claim, style: const TextStyle(height: 1.5)),
              actions: [
                TextButton(onPressed: () => unawaited(pick(false)), child: const Text('陷阱 / 错误')),
                FilledButton(onPressed: () => unawaited(pick(true)), child: const Text('判断正确')),
              ],
            );
          },
        ),
      );
      if (passed && mounted) {
        final ver = await _channel.v6Op({
          'action': 'cadence_verify',
          'quizPassed': true,
          'continueNext': true,
        });
        await _refresh();
        if (mounted) {
          setState(() {
            _banner = ver?['ok'] == true
                ? '${ver?['message'] ?? '连对 3 题验收通过'}'
                : '${ver?['error'] ?? '过关写入失败'}';
          });
        }
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _ingestClipboardIntoV6() async {
    if (_busy) return;
    final clip = await Clipboard.getData(Clipboard.kTextPlain);
    final raw = (clip?.text ?? '').trim();
    if (!mounted) return;
    if (raw.isEmpty) {
      setState(() => _banner = '剪贴板为空：先在 Gemini 复制话术或【今日契约】再点此吞吐');
      return;
    }
    setState(() => _busy = true);
    try {
      final res = await _channel.v6Op({'action': 'pool_ingest', 'text': raw, 'source': 'gemini'});
      await _refresh();
      if (!mounted) return;
      final kind = '${res?['kind'] ?? ''}';
      setState(() {
        if (kind == 'contract') {
          final ct = _asMap(res?['contract']);
          _banner = '📜 已直接签收【今日契约】并挂入锁调度：${ct['target'] ?? ''}';
        } else {
          final added = (res?['addedCount'] as num?)?.round() ?? 0;
          _banner = added > 0
              ? '🌊 已切片吸收 $added 条知识入鱼池（72h 自动去重防腻）'
              : '🐟 剪贴板内容已入池或已在 72h 冷却期内去重跳过';
        }
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  static const _atlasWhitelistPresets = <LoopApp>[
    LoopApp('md.obsidian', 'Obsidian'),
    LoopApp('com.tencent.mm', '微信'),
    LoopApp('com.google.android.apps.bard', 'Gemini'),
    LoopApp('com.google.android.googlequicksearchbox', 'Google / Live'),
    LoopApp('com.tencent.mobileqq', 'QQ'),
  ];

  static const _energyTierOptions = <(String, String)>[
    ('L0', 'L0 休息'),
    ('L1', 'L1 定型'),
    ('L2', 'L2 实战'),
    ('L3', 'L3 高压'),
  ];

  Future<void> _switchModeAndOpen(String mode, {String customFeed = ''}) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final cleanFeed = customFeed.trim();
      final clippedFeed = cleanFeed.length > 900 ? cleanFeed.substring(0, 900) : cleanFeed;
      final res = await _channel.liveCoreOp({
        'action': 'switch_mode',
        'mode': mode,
        if (mode == 'text') 'minutes': 15,
        if (clippedFeed.isNotEmpty) 'customFeed': clippedFeed,
      });
      final nested = _asMap(res?['liveCore']);
      final payload = _asMap(res?['payload']).isNotEmpty
          ? _asMap(res?['payload'])
          : _asMap(nested['payload']);
      final isText = mode == 'text';
      final topic = '${payload['topic'] ?? (isText ? 'Gemini 文本 · MCP 沉淀' : '回到 Gemini Live')}'.trim();
      final brief = '${payload['brief'] ?? '请直接承接当前刷题与经历继续推进'}'.trim();
      final voiceLine = '${payload['voiceLine'] ?? ''}'.trim();
      final wantShare = !isText && res?['wantScreenShare'] != false && (_liveCore['wantScreenShare'] != false);

      final launched = await _channel.triggerGeminiAutoLive(
        topic: topic,
        brief: brief,
        voiceLine: voiceLine,
        mode: 'instant',
        autoClickLive: !isText,
        requestScreenShare: wantShare,
      );
      if (!mounted) return;
      setState(() {
        _applyLiveCoreResponse(res);
        if (clippedFeed.isNotEmpty) {
          _banner = launched
              ? '📋 已将剪贴板内容封装进思维训练剧本并拉起 Gemini ${isText ? '文本' : 'Live'}'
              : '📋 剪贴板素材已写入新剧本（切去 Gemini 即可直接推演）';
        } else if (isText) {
          _banner = launched
              ? '💬 已切至 Gemini 文本模式（15 分钟沉淀保护窗 · 直接粘贴即可调 MCP）'
              : '💬 已备好文本衔接剧本并写入剪贴板（切去 Gemini 粘贴发送）';
        } else {
          _banner = launched
              ? '🎙️ 已带双鱼喂料切回官方 Gemini Live（语音 + 看屏）'
              : '🎙️ Live 剧本已进剪贴板（切去 Gemini 点 Live 即可开始）';
        }
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _feedClipboardToLive() async {
    if (_busy) return;
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final raw = (data?.text ?? '').trim();
    if (!mounted) return;
    if (raw.isEmpty) {
      setState(() {
        _banner = '剪贴板为空：先复制题干、网页链接或零散想法，再点我即可直接包成思维训练剧本喂进 Live。';
      });
      return;
    }
    await _switchModeAndOpen('live', customFeed: raw);
  }

  Future<void> _editWhitelistFromAtlas() async {
    if (_busy) return;
    final overviewRes = await _channel.sealsOp(const {'action': 'locks_overview'});
    if (!mounted) return;
    final nested = _asMap(overviewRes?['locksOverview']);
    final rawAllow = overviewRes?['defaultAllow'] ?? nested['defaultAllow'];
    final current = rawAllow is List && rawAllow.isNotEmpty
        ? [for (final p in rawAllow) if ('$p'.trim().isNotEmpty) '$p'.trim()]
        : const <String>[
            'md.obsidian',
            'com.tencent.mm',
            'com.google.android.apps.bard',
            'com.google.android.googlequicksearchbox',
          ];
    final picked = await showInstalledAppsPicker(
      context,
      title: '调整手机默认专注白名单',
      loop: widget.loopChannel ?? LoopChannel(),
      presets: _atlasWhitelistPresets,
      presetsLabel: '常用工具 / Gemini 宿主机一键勾选',
      initialSelected: current,
      allowEmpty: false,
      emptyError: '至少保留一个默认白名单应用',
      confirmLabel: '保存并立即生效',
    );
    if (picked == null || !mounted) return;
    setState(() => _busy = true);
    final pkgs = picked.map((a) => a.package).toList();
    final res = await _channel.setDefaultAllow(pkgs);
    if (!mounted) return;
    if (res != null && res['ok'] != false) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    setState(() {
      _busy = false;
      _banner = '${res?['toast'] ?? (res?['ok'] != false ? '已更新默认白名单（${pkgs.length} 个应用，立即生效）' : '白名单保存失败')}';
    });
  }

  Future<void> _tapShizukuChip({String kind = 'shizuku'}) async {
    final ok = await _channel.openCapability(kind);
    if (!mounted) return;
    setState(() {
      _banner = ok
          ? '已唤起 Shizuku 授权并自动连锁授予无障碍自愈（点「始终允许」即可全自动生效）'
          : '未检测到活跃 Shizuku 服务：请先在系统「无线调试」中启动 Shizuku';
    });
    unawaited(Future<void>.delayed(const Duration(milliseconds: 900), () {
      if (mounted) unawaited(_refresh());
    }));
  }

  Future<void> _runAutopilotIgnite() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final res = await _channel.liveCoreOp(const {'action': 'autopilot_ignite'});
      if (!mounted) return;
      final payload = _asMap(res?['payload']);
      final topic = '${payload['topic'] ?? '全自动托管 · 回到 Gemini Live'}'.trim();
      final brief = '${payload['brief'] ?? '已启动今日全自动托管 2×25m 验收点，直接开干'}'.trim();
      final voiceLine = '${payload['voiceLine'] ?? ''}'.trim();
      final wantShare = res?['wantScreenShare'] != false;
      await _channel.triggerGeminiAutoLive(
        topic: topic,
        brief: brief,
        voiceLine: voiceLine,
        mode: 'instant',
        autoClickLive: true,
        requestScreenShare: wantShare,
      );
      if (!mounted) return;
      setState(() {
        _applyLiveCoreResponse(res);
        _banner = '${res?['toast'] ?? '⚡ 全自动托管已启动：2×25m 验收点 + 常驻 Live 护航已联机'}';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _shareMarkdownFile(String filename, String content) async {
    if (widget.onShareFile != null) {
      await widget.onShareFile!(filename, content);
      return;
    }
    final tmp = await getTemporaryDirectory();
    final dir = Directory('${tmp.path}/dsh_diag');
    await dir.create(recursive: true);
    final file = File('${dir.path}/$filename');
    await file.writeAsString(content, flush: true);
    await SharePlus.instance.share(
      ShareParams(
        files: [XFile(file.path, mimeType: 'text/markdown', name: filename)],
        subject: filename,
        text: '大肥鱼 72h 实证诊断包（可直接发给 AI 分析）',
      ),
    );
  }

  Future<void> _exportDiagnosticBundle() async {
    if (_busy) return;
    setState(() => _busy = true);
    Map<String, dynamic>? res;
    try {
      final caps = _proactiveStatus?.capabilities ?? const <String, bool>{};
      res = await _channel.liveCoreOp({
        'action': 'export_diagnostic',
        'days': 3,
        'capabilities': caps,
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    if (!mounted) return;
    final markdown = '${res?['markdown'] ?? ''}'.trim();
    if (markdown.isEmpty) {
      setState(() => _banner = '引擎暂未返回诊断数据，请确认大肥鱼引擎在线后重试。');
      return;
    }
    final filename = '${res?['filename'] ?? 'dafeiyu_72h_diagnostic.md'}'.trim();
    final obsidianPath = '${res?['obsidianPath'] ?? ''}'.trim();
    final metrics = _asMap(res?['metrics']);
    final liveM = _asMap(metrics['live']);
    final outM = _asMap(metrics['output']);
    final summaryLine =
        '近 72h：连麦 ${liveM['sessionsRecorded'] ?? 0} 次（均 ${liveM['avgDurationSec'] ?? 0}s） · 点火 ${liveM['igniteCount'] ?? 0} 次 · 刷题 ${outM['quizTotal'] ?? 0} 题 · 验收 ${outM['sealsDone'] ?? 0}/${outM['sealsTotal'] ?? 0}';

    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('72h 实证诊断包已生成'),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(summaryLine, style: Theme.of(ctx).textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600)),
              if (obsidianPath.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text('已自动备份到 Obsidian：$obsidianPath', style: Theme.of(ctx).textTheme.bodySmall),
              ],
              const SizedBox(height: 10),
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: Theme.of(ctx).colorScheme.surfaceContainerHighest.withValues(alpha: 0.45),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  markdown.length > 520 ? '${markdown.substring(0, 520)}\n...' : markdown,
                  style: Theme.of(ctx).textTheme.labelSmall,
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('关闭')),
          FilledButton.tonalIcon(
            key: const ValueKey('atlas-diag-copy-btn'),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: markdown));
              if (!ctx.mounted) return;
              Navigator.of(ctx).pop();
              if (mounted) setState(() => _banner = '📋 已复制完整 72h 诊断包（直接粘贴发给 AI 即可）');
            },
            icon: const Icon(AppIcons.copy, size: 15),
            label: const Text('复制全文'),
          ),
          FilledButton.icon(
            key: const ValueKey('atlas-diag-share-btn'),
            onPressed: () async {
              Navigator.of(ctx).pop();
              try {
                await _shareMarkdownFile(filename, markdown);
                if (mounted) setState(() => _banner = '📤 已调起系统分享：$filename');
              } catch (e) {
                if (mounted) setState(() => _banner = '分享调起失败，可点「复制全文」粘贴发送：$e');
              }
            },
            icon: const Icon(AppIcons.share, size: 15),
            label: const Text('分享 .md 文件'),
          ),
        ],
      ),
    );
  }

  Future<void> _rerollFeedOnly() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final res = await _channel.liveCoreOp(const {'action': 'roll_dice'});
      if (!mounted) return;
      final nested = _asMap(res?['liveCore']);
      final payload = _asMap(res?['payload']).isNotEmpty
          ? _asMap(res?['payload'])
          : _asMap(nested['payload']);
      final dice = _asMap(payload['dice']).isNotEmpty ? _asMap(payload['dice']) : _asMap(res?['dice']);
      final face = dice['face'];
      final faceName = '${dice['name'] ?? ''}'.trim();
      setState(() {
        _applyLiveCoreResponse(res);
        _banner = face != null
            ? '🎲 已刷新双鱼喂料（骰$face · $faceName）'
            : '🎲 已刷新双鱼喂料预览';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _openCheckpointRelayDialog() async {
    final recap = _asMap(_liveCore['lastRecap']);
    final dice = _asMap(_liveCore['dice']);
    final defaultTitle = '${recap['next'] ?? ''}'.trim().isNotEmpty
        ? '${recap['next']}'
        : ('${dice['name'] ?? ''}'.trim().isNotEmpty ? '完成「${dice['name']}」25m 产出' : '完成 15 道真题或 1 张因果卡');
    final ctrl = TextEditingController(text: defaultTitle);
    String? confirmed;
    try {
      confirmed = await showDialog<String>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('签下 25 分钟接力验收点'),
          content: TextField(
            key: const ValueKey('atlas-relay-title-input'),
            controller: ctrl,
            autofocus: true,
            maxLength: 60,
            decoration: const InputDecoration(
              labelText: '25 分钟后要交出什么',
              hintText: '一句话具体动作，例如：判断推理 15 题',
              isDense: true,
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(ctx).pop(null), child: const Text('取消')),
            FilledButton(
              key: const ValueKey('atlas-relay-confirm-btn'),
              onPressed: () => Navigator.of(ctx).pop(ctrl.text.trim()),
              child: const Text('签单接力'),
            ),
          ],
        ),
      );
    } finally {
      ctrl.dispose();
    }
    if (!mounted || confirmed == null) return;
    await _commitRelay(mode: 'checkpoint_25m', minutes: 25, nextTitle: confirmed);
  }

  Future<void> _commitRelay({required String mode, required int minutes, String nextTitle = ''}) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final res = await _channel.liveCoreOp({
        'action': 'commit_relay',
        'mode': mode,
        'minutes': minutes,
        if (nextTitle.isNotEmpty) 'next': nextTitle,
      });
      if (!mounted) return;
      setState(() {
        _applyLiveCoreResponse(res);
        _banner = '${res?['toast'] ?? '已完成本轮 Live 接力'}';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _updateConfig(Map<String, Object?> patch) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final res = await _channel.liveCoreOp({'action': 'set_config', ...patch});
      if (!mounted) return;
      setState(() {
        _applyLiveCoreResponse(res);
        _banner = '${res?['toast'] ?? '已更新窥视核心设置'}';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _mute15m() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final res = await _channel.liveCoreOp(const {'action': 'mute', 'minutes': 15});
      if (!mounted) return;
      setState(() {
        _applyLiveCoreResponse(res);
        _banner = '${res?['toast'] ?? '已暂停 Live 护航 15 分钟'}';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  (StatusTone, String) _heroBadge() {
    final phase = '${_liveCore['phase'] ?? 'standby'}';
    final enabled = _liveCore['enabled'] != false;
    final muted = _liveCore['muted'] == true;
    final alwaysOn = _liveCore['alwaysOnLive'] == true;
    final textGrace = _liveCore['textGraceActive'] == true || phase == 'text_mode';
    if (!enabled) return (StatusTone.idle, '已关闭');
    if (muted || phase == 'muted' || phase == 'yielded_quiz_relief') return (StatusTone.warn, '暂停泄压中');
    if (phase == 'live_active') return (StatusTone.ok, 'Live 在线（看屏/语音）');
    if (textGrace) return (StatusTone.warn, '文本沉淀窗');
    if (phase == 'ignited') return (StatusTone.ok, '已点火拉回');
    if (alwaysOn) return (StatusTone.ok, '常驻护航中');
    if (phase.startsWith('yielded')) return (StatusTone.idle, '已让路');
    return (StatusTone.idle, '待命中');
  }

  String _phaseExplain() {
    final phase = '${_liveCore['phase'] ?? 'standby'}';
    final reason = '${_liveCore['reason'] ?? 'standby'}';
    final alwaysOn = _liveCore['alwaysOnLive'] == true;
    if (_liveCore['textGraceActive'] == true || phase == 'text_mode') {
      return 'Gemini 文本沉淀保护窗（15 分钟内不强拉回 Live）：适合调 @大肥鱼 / @BrainOS 沉淀写库。';
    }
    if (alwaysOn && reason == 'standby') {
      return '常驻 Live 护航已开启：只要未在刷题或文本保护窗内，断开或秒退将严格拉回并请求共享屏幕。';
    }
    return switch ((phase, reason)) {
      ('live_active', _) => '正在 Gemini Live 中：专注当下口头考辩与看屏盯盘，绝不重复弹扰。',
      ('yielded_quiz_relief', _) => '连刷 5 题已自动激活 20m 静音泄压，安心继续。',
      (_, 'active_study_or_quiz') => '检测到正在刷题或使用学习应用，窥视核心已自动让路。',
      (_, 'plan_modal_priority') => '今日计划模态尚未定档，优先由计划弹窗把关。',
      (_, 'sleeping') => '处于深夜睡眠守护窗，已静默让路。',
      (_, 'break') => '处于合法放风/午休窗口，放松期间不打扰。',
      (_, 'live_early_bailout') => '检测到刚进 Live 秒退：已触发严守强拉回。',
      (_, 'always_on_keeper') => '常驻 Live 护航生效中：正在持续保持与 Gemini Live 沟通。',
      (_, 'live_reengage') => '刚才 Live 中途退回，若无接力复盘会自动拉回。',
      ('muted', _) => '手动暂停护航冷却中，期间只响应一键主动切换。',
      _ => '白天防分心窗内若停滞/听歌空转，将用双鱼喂料自动点火进 Live。',
    };
  }

  void _openPillarRoute(String route) {
    switch (route) {
      case 'live_core':
        setState(() => _tab = LiveCoreAtlasTab.action);
      case 'locks':
        unawaited(LocksBalancePage.open(context, channel: _channel, loopChannel: widget.loopChannel));
      case 'plan':
        unawaited(PlanModalityPage.open(context, channel: _channel, loopChannel: widget.loopChannel));
      case 'bank':
        unawaited(BankManagerPage.open(context, channel: _channel));
      case 'bridge':
        unawaited(
          Navigator.of(context).push(
            MaterialPageRoute<void>(builder: (_) => GeminiBridgePage(channel: _channel)),
          ),
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    return AppPage.list(
      title: 'Live 脑核调度台',
      subtitle: const Text('常驻 Live 护航 · 双鱼实时喂料 · 语音⇄文本秒切'),
      actions: [
        GlassIconButton(
          tooltip: '刷新',
          icon: AppIcons.refresh,
          onPressed: _busy ? null : () => unawaited(_refresh()),
        ),
      ],
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          child: SegmentedPill<LiveCoreAtlasTab>(
            segments: const [
              PillSegment(LiveCoreAtlasTab.action, '此刻操作'),
              PillSegment(LiveCoreAtlasTab.atlas, '功能图谱'),
              PillSegment(LiveCoreAtlasTab.records, '运行记录'),
            ],
            selected: _tab,
            onChanged: (t) => setState(() => _tab = t),
          ),
        ),
        if (_banner != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: GlassSurface(
              tier: GlassTier.pill,
              borderRadius: AppShape.radiusMd,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                child: Row(
                  children: [
                    const AnimatedStatusDot(tone: StatusTone.ok, size: 7),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _banner!,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(color: colors.info),
                      ),
                    ),
                    GestureDetector(
                      onTap: () => setState(() => _banner = null),
                      child: Icon(AppIcons.close, size: 16, color: colors.textTertiary),
                    ),
                  ],
                ),
              ),
            ),
          ),
        if (_loading)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 40),
            child: Center(child: CircularProgressIndicator()),
          )
        else ...switch (_tab) {
          LiveCoreAtlasTab.action => _buildActionTab(context, colors),
          LiveCoreAtlasTab.atlas => _buildAtlasTab(context, colors),
          LiveCoreAtlasTab.records => _buildRecordsTab(context, colors),
        },
      ],
    );
  }

  List<Widget> _buildActionTab(BuildContext context, DshColors colors) {
    final theme = Theme.of(context);
    final (tone, badge) = _heroBadge();
    final alwaysOnLive = _liveCore['alwaysOnLive'] == true;
    final keeperStrict = _liveCore['keeperStrict'] != false;
    final wantShare = _liveCore['wantScreenShare'] != false;
    final earlyBailouts = (_liveCore['earlyBailoutCount'] as num?)?.round() ?? 0;
    final dice = _asMap(_liveCore['dice']);
    final diceFace = (dice['face'] as num?)?.round() ?? 0;
    final diceName = '${dice['name'] ?? ''}'.trim();
    final feed = _asMap(_liveCore['feedPreview']);
    final feedQuizzes = _asList(feed['quizzes']);
    final feedExp = (feed['experiences'] is List)
        ? [for (final e in feed['experiences'] as List) if ('$e'.trim().isNotEmpty) '$e'.trim()]
        : const <String>[];
    final feedSummary = '${feed['sisterSummary'] ?? ''}'.trim();
    final caps = _proactiveStatus?.capabilities ?? const <String, bool>{};
    final a11yReady = caps['a11y'] == true;
    final shizukuReady = caps['shizuku'] == true;
    final shizukuRunning = caps['shizukuRunning'] == true;
    final a11yAutoHeal = caps['a11yAutoHeal'] == true;
    final batteryReady = caps['battery'] == true;
    final exactAlarmReady = caps['exactAlarm'] == true;
    final overlayReady = caps['overlay'] == true;
    final bridgeReady = _bridgeStatus['healthy'] == true;
    final energyTier = '${_liveCore['energyTier'] ?? 'L2'}';
    final headsetKeepalive = _liveCore['headsetKeepalive'] != false;
    final pulseMin = (_liveCore['pulseIntervalMin'] as num?)?.round() ?? 0;
    final activeDept = _asMap(_liveCore['activeDepartment']);
    final deptLabel = '${activeDept['label'] ?? ''}'.trim();
    final nextDeptLabel = '${activeDept['nextLabel'] ?? ''}'.trim();
    final dualTrack = _asMap(_liveCore['dualTrackNext']);
    final dualLong = '${dualTrack['longTitle'] ?? ''}'.trim();
    final dualMicro = '${dualTrack['microTask'] ?? ''}'.trim();
    final liveSignals = _liveSignals.isNotEmpty ? _liveSignals : _asMap(_liveCore['liveSignals']);
    final sigOnline = liveSignals['liveOnline'] == true || '${_liveCore['phase'] ?? ''}' == 'live_active';
    final sigComm = liveSignals['audioInComm'] == true && liveSignals['micRecording'] == true;
    final sigDisplay = liveSignals['virtualDisplayActive'] == true;
    final sigShareOrUi = liveSignals['screenShareText'] == true || liveSignals['uiLiveActive'] == true;
    final cadence = _asMap(_v6State['cadence']);
    final cadActive = cadence['active'] == true;
    final cadPhase = '${cadence['phase'] ?? 'idle'}';
    final cadOverdue = cadActive && cadPhase == 'awaiting_verify';
    final cadDueMs = (cadence['dueAt'] as num?)?.toInt() ?? 0;
    final cadRemainSec = cadActive && cadPhase == 'running' && cadDueMs > 0
        ? ((cadDueMs - DateTime.now().millisecondsSinceEpoch) / 1000).ceil().clamp(0, 3600)
        : ((_v6State['cadenceRemainingSec'] as num?)?.round() ?? 0);
    final cadRound = (cadence['round'] as num?)?.round() ?? 0;
    final cadStreak = (cadence['streak'] as num?)?.round() ?? 0;
    final cadVerifiedToday = (cadence['verifiedToday'] as num?)?.round() ?? 0;
    final cadTarget = '${cadence['target'] ?? ''}'.trim();
    final v6Tel = _asMap(_v6State['telemetry']);
    final v6PoolActive = (v6Tel['active'] as num?)?.round() ?? 0;
    final v6Dedupe = (v6Tel['dedupeRate72h'] as num?)?.round() ?? 100;
    final v6Win = _asMap(_v6State['windowMode']);
    final v6WinLabel = '${v6Win['label'] ?? '动态调度窗就绪'}';

    return [
      ListPageCard(
        key: const ValueKey('atlas-always-on-card'),
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 14, 14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    const OverviewGlyph(AppIcons.voice, tint: 3),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '常驻 Gemini Live 护航',
                            style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                          ),
                          const SizedBox(height: 2),
                          StatusChip(key: const ValueKey('atlas-phase-chip'), tone: tone, label: badge),
                        ],
                      ),
                    ),
                    Switch(
                      key: const ValueKey('atlas-always-on-switch'),
                      value: alwaysOnLive,
                      onChanged: _busy
                          ? null
                          : (v) => unawaited(_updateConfig({'alwaysOnLive': v, 'keeperStrict': keeperStrict})),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  _phaseExplain() + (earlyBailouts > 0 ? '\n今日已拦截秒退 $earlyBailouts 次' : ''),
                  style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
                if (deptLabel.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text(
                    '🏛️ 自动值班：$deptLabel${nextDeptLabel.isNotEmpty ? ' → 下一跳：$nextDeptLabel' : ''}',
                    key: const ValueKey('atlas-dept-line'),
                    style: theme.textTheme.labelMedium?.copyWith(
                      color: theme.colorScheme.primary,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
                if (dualMicro.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(
                    '🎯 双轨微步：${dualLong.isNotEmpty ? '$dualLong → ' : ''}$dualMicro',
                    key: const ValueKey('atlas-dualtrack-line'),
                    style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
                const SizedBox(height: 8),
                Wrap(
                  key: const ValueKey('atlas-live-lamps-row'),
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    StatusChip(
                      tone: sigOnline ? StatusTone.ok : (alwaysOnLive ? StatusTone.warn : StatusTone.idle),
                      label: sigOnline ? 'Live 在线免锁' : (alwaysOnLive ? '断线触发 P2 锁' : 'Live 离线'),
                    ),
                    StatusChip(tone: sigComm ? StatusTone.ok : StatusTone.idle, label: sigComm ? '语音+麦通' : '语音+麦灭'),
                    StatusChip(tone: sigDisplay ? StatusTone.ok : StatusTone.idle, label: sigDisplay ? '虚拟屏在线' : '虚拟屏灭'),
                    StatusChip(tone: sigShareOrUi ? StatusTone.ok : StatusTone.idle, label: sigShareOrUi ? '共享/UI活跃' : 'UI未在前台'),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        child: Row(
          children: [
            Expanded(
              child: FilledButton.icon(
                key: const ValueKey('atlas-roll-live-btn'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                  shape: AppShape.card,
                ),
                onPressed: _busy ? null : () => unawaited(_switchModeAndOpen('live')),
                icon: const Icon(AppIcons.voice, size: 17),
                label: const Text('🎙️ 启动/回到 Live'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FilledButton.tonalIcon(
                key: const ValueKey('atlas-switch-text-btn'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                  shape: AppShape.card,
                ),
                onPressed: _busy ? null : () => unawaited(_switchModeAndOpen('text')),
                icon: const Icon(AppIcons.chat, size: 17),
                label: const Text('💬 切 Gemini 文本'),
              ),
            ),
          ],
        ),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        child: Row(
          children: [
            Expanded(
              child: FilledButton.tonalIcon(
                key: const ValueKey('atlas-clipboard-live-btn'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(44),
                  shape: AppShape.card,
                ),
                onPressed: _busy ? null : () => unawaited(_feedClipboardToLive()),
                icon: const Icon(AppIcons.copy, size: 16),
                label: const Text('📋 喂剪贴板进 Live'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FilledButton.tonalIcon(
                key: const ValueKey('atlas-whitelist-btn'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(44),
                  shape: AppShape.card,
                ),
                onPressed: _busy ? null : () => unawaited(_editWhitelistFromAtlas()),
                icon: const Icon(AppIcons.taskBlocked, size: 16),
                label: const Text('🛡️ 调手机白名单'),
              ),
            ),
          ],
        ),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        child: Row(
          children: [
            Expanded(
              child: FilledButton.tonalIcon(
                key: const ValueKey('atlas-autopilot-btn'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(44),
                  shape: AppShape.card,
                ),
                onPressed: _busy ? null : () => unawaited(_runAutopilotIgnite()),
                icon: const Icon(AppIcons.running, size: 16),
                label: const Text('⚡ 1 键托管今日'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FilledButton.tonalIcon(
                key: const ValueKey('atlas-export-diag-btn'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(44),
                  shape: AppShape.card,
                ),
                onPressed: _busy ? null : () => unawaited(_exportDiagnosticBundle()),
                icon: const Icon(AppIcons.share, size: 16),
                label: const Text('📤 导出诊断包'),
              ),
            ),
          ],
        ),
      ),
      ListPageCard(
        key: const ValueKey('atlas-feed-preview-card'),
        title: diceFace > 0 ? '两条鱼备料包（骰$diceFace · $diceName）' : '两条鱼刚备好的实时喂料',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (feedSummary.isNotEmpty)
                  Text(
                    feedSummary,
                    style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.primary, fontWeight: FontWeight.w600),
                  ),
                if (feedQuizzes.isEmpty && feedExp.isEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      '暂无近期错题与经历切片：点右侧「换一包料」或先刷 2 题，两条鱼会自动把素材装进下一轮 Live。',
                      style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                    ),
                  )
                else ...[
                  for (final q in feedQuizzes.take(2))
                    Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: Text(
                        '• 题源 [${q['topic'] ?? '考题'}]：${q['prompt'] ?? ''}${'${q['mistake'] ?? ''}'.isNotEmpty ? '（${q['mistake']}）' : ''}',
                        style: theme.textTheme.bodySmall,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  for (final ex in feedExp.take(2))
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(
                        '• 经历：$ex',
                        style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                ],
                const SizedBox(height: 8),
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    key: const ValueKey('atlas-reroll-feed-btn'),
                    onPressed: _busy ? null : () => unawaited(_rerollFeedOnly()),
                    icon: const Icon(AppIcons.refresh, size: 15),
                    label: const Text('🎲 换一包料'),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
      Padding(
        key: const ValueKey('atlas-relay-row'),
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
        child: Row(
          children: [
            Expanded(
              child: FilledButton.tonalIcon(
                key: const ValueKey('atlas-relay-checkpoint-btn'),
                style: FilledButton.styleFrom(shape: AppShape.card),
                onPressed: _busy ? null : () => unawaited(_openCheckpointRelayDialog()),
                icon: const Icon(AppIcons.planMode, size: 16),
                label: const Text('签 25m 验收点'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FilledButton.tonalIcon(
                key: const ValueKey('atlas-relay-quiz-btn'),
                style: FilledButton.styleFrom(shape: AppShape.card),
                onPressed: _busy ? null : () => unawaited(_commitRelay(mode: 'quiz_only', minutes: 20)),
                icon: const Icon(AppIcons.skill, size: 16),
                label: const Text('进 20m 刷题舱'),
              ),
            ),
          ],
        ),
      ),
      ListPageCard(
        title: '精简护航与能量挡位（Thinking Gym L0–L3）',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 14),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Wrap(
                  key: const ValueKey('atlas-energy-row'),
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final opt in _energyTierOptions)
                      ChoiceChip(
                        key: ValueKey('atlas-energy-${opt.$1}'),
                        label: Text(opt.$2),
                        selected: energyTier == opt.$1,
                        onSelected: _busy ? null : (v) => v ? unawaited(_updateConfig({'energyTier': opt.$1})) : null,
                      ),
                  ],
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    FilterChip(
                      key: const ValueKey('atlas-reengage-chip'),
                      label: const Text('严守防秒退'),
                      selected: keeperStrict,
                      onSelected: _busy ? null : (v) => unawaited(_updateConfig({'keeperStrict': v, 'autoReengage': v})),
                    ),
                    FilterChip(
                      key: const ValueKey('atlas-share-chip'),
                      label: const Text('自动请求看屏'),
                      selected: wantShare,
                      onSelected: _busy ? null : (v) => unawaited(_updateConfig({'wantScreenShare': v})),
                    ),
                    FilterChip(
                      key: const ValueKey('atlas-headset-chip'),
                      label: const Text('息屏耳机保活'),
                      selected: headsetKeepalive,
                      onSelected: _busy ? null : (v) => unawaited(_updateConfig({'headsetKeepalive': v})),
                    ),
                    FilterChip(
                      key: const ValueKey('atlas-pulse-chip'),
                      label: Text(pulseMin > 0 ? '🔔 ${pulseMin}m 声学节拍钟已开' : '🔔 5m 声学节拍钟'),
                      selected: pulseMin > 0,
                      onSelected: _busy ? null : (v) => unawaited(_updateConfig({'pulseIntervalMin': v ? 5 : 0})),
                    ),
                    ActionChip(
                      key: const ValueKey('atlas-mute-chip'),
                      avatar: const Icon(AppIcons.close, size: 14),
                      label: const Text('暂停 15m'),
                      onPressed: _busy ? null : () => unawaited(_mute15m()),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
      ListPageCard(
        key: const ValueKey('atlas-v6-cadence-card'),
        title: '⏱️ 5 分钟高频严验收（P3/P4 真锁 · 响铃震动语音）',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 14),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    StatusChip(
                      key: const ValueKey('atlas-cadence-status-chip'),
                      tone: cadOverdue
                          ? StatusTone.error
                          : (cadActive ? StatusTone.ok : StatusTone.idle),
                      label: cadOverdue
                          ? '⚠️ 到点待验收 · P3 严格锁定中'
                          : (cadActive
                              ? '第 $cadRound 轮倒计时 ${cadRemainSec ~/ 60}:${(cadRemainSec % 60).toString().padLeft(2, '0')}'
                              : '未启动（单局 5 分钟）'),
                    ),
                    const Spacer(),
                    Text(
                      '连胜 $cadStreak · 今日 $cadVerifiedToday 轮',
                      style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.primary, fontWeight: FontWeight.w600),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  cadActive && cadTarget.isNotEmpty
                      ? '当前切片：$cadTarget（满 3 轮连胜自动刻入《万世铭》）'
                      : '触发器失灵或启动瘫痪时点此开局：5 分钟倒计时全程 P4 专心锁，到点不交≥6字真实结论立即闹铃+语音+转 P3 硬锁。',
                  style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
                const SizedBox(height: 10),
                if (!cadActive)
                  FilledButton.icon(
                    key: const ValueKey('atlas-v6-cadence-start-btn'),
                    style: FilledButton.styleFrom(shape: AppShape.card),
                    onPressed: _busy ? null : () => unawaited(_startFiveMinCadence()),
                    icon: const Icon(AppIcons.automation, size: 16),
                    label: const Text('⚡ 开启 5 分钟严验收（防摸鱼）'),
                  )
                else
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      FilledButton.icon(
                        key: const ValueKey('atlas-v6-cadence-verify-btn'),
                        style: FilledButton.styleFrom(shape: AppShape.card),
                        onPressed: _busy ? null : () => unawaited(_verifyFiveMinCadence()),
                        icon: const Icon(AppIcons.completed, size: 16),
                        label: const Text('✅ 交结论过关并续下一轮'),
                      ),
                      FilledButton.tonalIcon(
                        key: const ValueKey('atlas-v6-cadence-quiz-btn'),
                        style: FilledButton.styleFrom(shape: AppShape.card),
                        onPressed: _busy ? null : () => unawaited(_verifyFiveMinByMicroQuiz()),
                        icon: const Icon(AppIcons.skill, size: 16),
                        label: const Text('⚡ 连答 3 题过关'),
                      ),
                      OutlinedButton.icon(
                        key: const ValueKey('atlas-v6-cadence-stop-btn'),
                        style: OutlinedButton.styleFrom(shape: AppShape.card),
                        onPressed: _busy ? null : () => unawaited(_stopFiveMinCadence()),
                        icon: const Icon(AppIcons.stopped, size: 16),
                        label: const Text('收局'),
                      ),
                    ],
                  ),
              ],
            ),
          ),
        ],
      ),
      ListPageCard(
        key: const ValueKey('atlas-v6-hub-card'),
        title: '🌊 V6 知识海 · 剪贴板吞吐与《万世铭》',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 14),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  '$v6WinLabel · 活水 $v6PoolActive 条 · 72h去重率 $v6Dedupe%',
                  style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    FilledButton.tonalIcon(
                      key: const ValueKey('atlas-v6-clip-ingest-btn'),
                      style: FilledButton.styleFrom(shape: AppShape.card),
                      onPressed: _busy ? null : () => unawaited(_ingestClipboardIntoV6()),
                      icon: const Icon(AppIcons.copy, size: 15),
                      label: const Text('吞剪贴板入池/签契约'),
                    ),
                    OutlinedButton.icon(
                      key: const ValueKey('atlas-v6-console-btn'),
                      style: OutlinedButton.styleFrom(shape: AppShape.card),
                      onPressed: () => unawaited(V6CommandPage.open(context, channel: _channel)),
                      icon: const Icon(AppIcons.cordis, size: 15),
                      label: const Text('9宫格 / 3刀追问 / 申诉'),
                    ),
                    OutlinedButton.icon(
                      key: const ValueKey('atlas-v6-wanshiming-btn'),
                      style: OutlinedButton.styleFrom(shape: AppShape.card),
                      onPressed: () => unawaited(WanshimingPage.open(context, channel: _channel)),
                      icon: const Icon(AppIcons.document, size: 15),
                      label: const Text('📜 万世铭碑林'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
      ListPageCard(
        key: const ValueKey('atlas-cap-row'),
        title: '底层就绪度（点未开启项直达配置）',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    GestureDetector(
                      onTap: a11yReady ? null : () => unawaited(_channel.openCapability('a11y')),
                      child: StatusChip(tone: a11yReady ? StatusTone.ok : StatusTone.warn, label: a11yReady ? '无障碍已开' : '无障碍未开'),
                    ),
                    GestureDetector(
                      key: const ValueKey('atlas-shizuku-chip'),
                      onTap: shizukuReady && a11yAutoHeal ? null : () => unawaited(_tapShizukuChip(kind: 'shizuku')),
                      child: StatusChip(
                        tone: shizukuReady ? StatusTone.ok : (shizukuRunning ? StatusTone.warn : StatusTone.idle),
                        label: shizukuReady ? 'Shizuku 就绪' : (shizukuRunning ? 'Shizuku 运行中(点授权)' : 'Shizuku 未授'),
                      ),
                    ),
                    GestureDetector(
                      key: const ValueKey('atlas-a11y-heal-chip'),
                      onTap: a11yAutoHeal ? null : () => unawaited(_tapShizukuChip(kind: 'a11yAutoHeal')),
                      child: StatusChip(
                        tone: a11yAutoHeal ? StatusTone.ok : StatusTone.warn,
                        label: a11yAutoHeal ? '无障碍自愈已开' : '无障碍自愈待授',
                      ),
                    ),
                    GestureDetector(
                      key: const ValueKey('atlas-battery-chip'),
                      onTap: batteryReady ? null : () => unawaited(_channel.openCapability('battery')),
                      child: StatusChip(
                        tone: batteryReady ? StatusTone.ok : StatusTone.warn,
                        label: batteryReady ? '电池免杀已开' : '电池免杀待开',
                      ),
                    ),
                    GestureDetector(
                      key: const ValueKey('atlas-alarm-chip'),
                      onTap: exactAlarmReady ? null : () => unawaited(_channel.openCapability('exactAlarm')),
                      child: StatusChip(
                        tone: exactAlarmReady ? StatusTone.ok : StatusTone.idle,
                        label: exactAlarmReady ? '精准闹钟已开' : '精准闹钟待开',
                      ),
                    ),
                    GestureDetector(
                      onTap: () => _openPillarRoute('bridge'),
                      child: StatusChip(tone: bridgeReady ? StatusTone.ok : StatusTone.idle, label: bridgeReady ? 'Gemini 桥在线' : 'Gemini 桥离线'),
                    ),
                    GestureDetector(
                      onTap: overlayReady ? null : () => unawaited(_channel.openCapability('overlay')),
                      child: StatusChip(tone: overlayReady ? StatusTone.ok : StatusTone.idle, label: overlayReady ? '悬浮窗就绪' : '悬浮窗待开'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  '注：系统级「共享屏幕」弹窗涉及 Android 隐私授权边界，须由本人轻点系统同意，绝不绕过安全校验。',
                  style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                ),
              ],
            ),
          ),
        ],
      ),
    ];
  }

  List<Widget> _buildAtlasTab(BuildContext context, DshColors colors) {
    final theme = Theme.of(context);
    final pipeline = _asList(_atlas['pipeline']).isNotEmpty ? _asList(_atlas['pipeline']) : _fallbackPipeline;
    final pillars = _asList(_atlas['pillars']).isNotEmpty ? _asList(_atlas['pillars']) : _fallbackPillars;
    final ladder = _asList(_atlas['lockLadder']).isNotEmpty ? _asList(_atlas['lockLadder']) : _fallbackLadder;

    return [
      ListPageCard(
        key: const ValueKey('atlas-pipeline-card'),
        title: '一条闭环链怎么转',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 14),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 10, 14, 12),
            child: Wrap(
              spacing: 6,
              runSpacing: 6,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                for (var i = 0; i < pipeline.length; i++) ...[
                  StatusChip(
                    tone: pipeline[i]['status'] == 'active'
                        ? StatusTone.ok
                        : (pipeline[i]['status'] == 'needs_setup' ? StatusTone.warn : StatusTone.idle),
                    label: '${pipeline[i]['step']}. ${pipeline[i]['label']}',
                  ),
                  if (i + 1 < pipeline.length) Icon(AppIcons.forward, size: 14, color: colors.textTertiary),
                ],
              ],
            ),
          ),
        ],
      ),
      for (final p in pillars)
        ListPageCard(
          key: ValueKey('atlas-pillar-${p['id'] ?? ''}'),
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          children: [
            Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '${p['title'] ?? ''}',
                          style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                        ),
                      ),
                      StatusChip(
                        tone: p['status'] == 'active'
                            ? StatusTone.ok
                            : (p['status'] == 'yielded' ? StatusTone.warn : StatusTone.idle),
                        label: '${p['statusLabel'] ?? '就绪'}',
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text('${p['tagline'] ?? ''}', style: theme.textTheme.bodySmall),
                  const SizedBox(height: 8),
                  Text('• 何时触发：${p['triggerRule'] ?? ''}', style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                  const SizedBox(height: 2),
                  Text('• 何时让路：${p['yieldRule'] ?? ''}', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '${p['metricText'] ?? ''}',
                          style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                        ),
                      ),
                      if ('${p['route'] ?? ''}' != 'live_core')
                        TextButton.icon(
                          key: ValueKey('atlas-jump-${p['route'] ?? ''}'),
                          onPressed: () => _openPillarRoute('${p['route'] ?? ''}'),
                          icon: const Icon(AppIcons.forward, size: 15),
                          label: const Text('前往'),
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ListPageCard(
        key: const ValueKey('atlas-ladder-card'),
        title: '锁优先级铁律（P0–P6 · 6 只手不打架）',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
        children: [
          for (final item in ladder)
            ListPageRow(
              icon: item['active'] == true ? AppIcons.lockActive : AppIcons.lock,
              iconColor: item['active'] == true ? theme.colorScheme.primary : colors.textTertiary,
              title: '${item['priority']} · ${item['name']}',
              subtitle: '${item['rule'] ?? ''}',
              subtitleMaxLines: 2,
              trailing: item['active'] == true ? const StatusChip(tone: StatusTone.ok, label: '当前发力') : null,
            ),
        ],
      ),
    ];
  }

  List<Widget> _buildRecordsTab(BuildContext context, DshColors colors) {
    final filtered = [
      for (final item in _history)
        if (_historyFilter == 'all' ||
            (_historyFilter == 'ignite' && item['kind'] == 'ignite') ||
            (_historyFilter == 'yield' && (item['kind'] == 'yield' || item['kind'] == 'mute')) ||
            (_historyFilter == 'relay' && (item['kind'] == 'relay' || item['kind'] == 'capsule')))
          item,
    ];

    return [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        child: Wrap(
          spacing: 8,
          runSpacing: 6,
          children: [
            for (final opt in const [
              ('all', '全部'),
              ('ignite', '点火'),
              ('yield', '让路'),
              ('relay', '接力复盘'),
            ])
              ChoiceChip(
                key: ValueKey('atlas-filter-${opt.$1}'),
                label: Text(opt.$2),
                selected: _historyFilter == opt.$1,
                onSelected: (_) => setState(() => _historyFilter = opt.$1),
              ),
          ],
        ),
      ),
      ListPageCard(
        key: const ValueKey('atlas-records-card'),
        title: filtered.isEmpty ? '调度流水' : '最近 ${filtered.length} 条记录',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        children: [
          if (filtered.isEmpty)
            const ListPageRow(
              icon: AppIcons.history,
              title: '今天还没有 Live 调度记录',
              subtitle: '在「此刻操作」点一次摇骰子灌料，或让系统自动探测空转后这里就会留下可追溯轨迹。',
              subtitleMaxLines: 2,
            )
          else
            for (final item in filtered)
              ListPageRow(
                icon: switch (item['kind']) {
                  'ignite' => AppIcons.running,
                  'yield' || 'mute' => AppIcons.paused,
                  'relay' || 'capsule' => AppIcons.verified,
                  _ => AppIcons.history,
                },
                iconColor: switch (item['kind']) {
                  'ignite' => Theme.of(context).colorScheme.primary,
                  'yield' || 'mute' => colors.warning,
                  'relay' || 'capsule' => colors.success,
                  _ => colors.textTertiary,
                },
                title: '${item['hm'] ?? ''} · ${item['title'] ?? ''}',
                subtitle: '${item['detail'] ?? ''}',
                subtitleMaxLines: 2,
                trailing: StatusChip(
                  tone: switch (item['kind']) {
                    'ignite' => StatusTone.ok,
                    'yield' || 'mute' => StatusTone.warn,
                    'relay' || 'capsule' => StatusTone.ok,
                    _ => StatusTone.idle,
                  },
                  label: '${item['badge'] ?? ''}',
                ),
              ),
        ],
      ),
      ListPageCard(
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
        children: [
          ListPageRow(
            key: const ValueKey('atlas-records-export-diag'),
            icon: AppIcons.share,
            title: '导出 72h 实证诊断包（发给 AI 体检）',
            subtitle: '自动汇总连麦时长、断线原因、四部门流转与误锁名单，无需手工记录',
            chevron: true,
            onTap: _busy ? null : () => unawaited(_exportDiagnosticBundle()),
          ),
          ListPageRow(
            key: const ValueKey('atlas-open-raw-records'),
            icon: AppIcons.history,
            title: '查看底层锁机与主动明细',
            subtitle: '打开完整拦截/主动提醒流水账并支持清理',
            chevron: true,
            onTap: () => unawaited(RecordsPage.open(context)),
          ),
        ],
      ),
    ];
  }
}
