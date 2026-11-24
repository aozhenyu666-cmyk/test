import 'dart:async';

import 'package:flutter/material.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/loop_channel.dart';
import '../channels/proactive_state.dart';
import 'bank_manager_page.dart';
import 'gemini_bridge_page.dart';
import 'locks_balance_page.dart';
import 'plan_modality_page.dart';
import 'records_page.dart';

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
  });

  final ProactiveChannel? channel;
  final LoopChannel? loopChannel;
  final LiveCoreAtlasTab initialTab;

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

  Future<void> _refresh() async {
    final results = await Future.wait([
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

  Future<void> _rollAndEnterLive() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final res = await _channel.liveCoreOp(const {'action': 'roll_dice'});
      final nested = _asMap(res?['liveCore']);
      final payload = _asMap(res?['payload']).isNotEmpty
          ? _asMap(res?['payload'])
          : _asMap(nested['payload']);
      final dice = _asMap(payload['dice']).isNotEmpty ? _asMap(payload['dice']) : _asMap(res?['dice']);
      final topic = '${payload['topic'] ?? '骰子触发 · 进 Gemini Live'}'.trim();
      final brief = '${payload['brief'] ?? '请直接基于当下屏幕与题源开考，禁聊体系与找地方'}'.trim();
      final voiceLine = '${payload['voiceLine'] ?? '骰子已落定，进 Live 直接开答'}'.trim();
      final wantShare = res?['wantScreenShare'] != false && (_liveCore['wantScreenShare'] != false);

      final launched = await _channel.triggerGeminiAutoLive(
        topic: topic,
        brief: brief,
        voiceLine: voiceLine,
        mode: 'instant',
        autoClickLive: true,
        requestScreenShare: wantShare,
      );
      if (!mounted) return;
      final face = dice['face'];
      final faceName = '${dice['name'] ?? ''}'.trim();
      setState(() {
        _applyLiveCoreResponse(res);
        _banner = launched
            ? '🎲 已摇到「骰$face · $faceName」，正在拉起并灌入官方 Gemini Live'
            : '🎲 已生成「骰$face · $faceName」剧本（已写入剪贴板，切去 Gemini 即可开始）';
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

  Future<void> _mute20m() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final res = await _channel.liveCoreOp(const {'action': 'mute', 'minutes': 20});
      if (!mounted) return;
      setState(() {
        _applyLiveCoreResponse(res);
        _banner = '${res?['toast'] ?? '已静音 Gemini 窥视调度 20 分钟'}';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  (StatusTone, String) _heroBadge() {
    final phase = '${_liveCore['phase'] ?? 'standby'}';
    final enabled = _liveCore['enabled'] != false;
    final muted = _liveCore['muted'] == true;
    if (!enabled) return (StatusTone.idle, '已关闭');
    if (muted || phase == 'muted' || phase == 'yielded_quiz_relief') return (StatusTone.warn, '静音泄压中');
    if (phase == 'live_active') return (StatusTone.ok, 'Live 在线');
    if (phase == 'ignited') return (StatusTone.ok, '已点火');
    if (phase.startsWith('yielded')) return (StatusTone.idle, '已让路');
    return (StatusTone.idle, '待命中');
  }

  String _phaseExplain() {
    final phase = '${_liveCore['phase'] ?? 'standby'}';
    final reason = '${_liveCore['reason'] ?? 'standby'}';
    return switch ((phase, reason)) {
      ('live_active', _) => '正在 Gemini Live 中：专注当下口头考辩，绝不重复弹扰。',
      ('yielded_quiz_relief', _) => '连刷 5 题已自动激活 20m 静音泄压，安心继续。',
      (_, 'active_study_or_quiz') => '检测到正在刷题或使用学习应用，窥视核心已自动让路。',
      (_, 'plan_modal_priority') => '今日计划模态尚未定档，优先由计划弹窗把关。',
      (_, 'sleeping') => '处于深夜睡眠守护窗，已静默让路。',
      (_, 'break') => '处于合法放风/午休窗口，放松期间不打扰。',
      (_, 'live_reengage') => '刚才 Live 中途退回，若无接力复盘会自动拉回。',
      ('muted', _) => '手动静音冷却中，期间只响应一键主动开麦。',
      _ => '白天防分心窗内若停滞/听歌空转，将用 1 颗骰子自动点火进 Live。',
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
      title: 'Live 窥视核心 · 图谱与记录',
      subtitle: const Text('极简控制面 × 四维能力全景 × 透明运行流水'),
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
    final dice = _asMap(_liveCore['dice']);
    final diceFace = (dice['face'] as num?)?.round() ?? 0;
    final diceName = '${dice['name'] ?? ''}'.trim();
    final wantShare = _liveCore['wantScreenShare'] != false;
    final autoReengage = _liveCore['autoReengage'] != false;
    final caps = _proactiveStatus?.capabilities ?? const <String, bool>{};
    final a11yReady = caps['a11y'] == true;
    final shizukuReady = caps['shizuku'] == true;
    final overlayReady = caps['overlay'] == true;
    final bridgeReady = _bridgeStatus['healthy'] == true;

    return [
      OverviewCard(
        leading: const OverviewGlyph(AppIcons.voice, tint: 3),
        title: Text(diceFace > 0 ? '最近骰$diceFace · $diceName' : 'Gemini Live 窥视总调度'),
        subtitle: Text(_phaseExplain()),
        trailing: StatusChip(key: const ValueKey('atlas-phase-chip'), tone: tone, label: badge),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        child: FilledButton.icon(
          key: const ValueKey('atlas-roll-live-btn'),
          style: FilledButton.styleFrom(
            minimumSize: const Size.fromHeight(48),
            shape: AppShape.card,
          ),
          onPressed: _busy ? null : () => unawaited(_rollAndEnterLive()),
          icon: const Icon(AppIcons.voice, size: 18),
          label: const Text('🎲 摇骰子灌料进 Live'),
        ),
      ),
      Padding(
        key: const ValueKey('atlas-relay-row'),
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
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
        title: '精简开关（只保留这 3 项）',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 14),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                FilterChip(
                  key: const ValueKey('atlas-share-chip'),
                  label: const Text('窥视共享屏幕'),
                  selected: wantShare,
                  onSelected: _busy ? null : (v) => unawaited(_updateConfig({'wantScreenShare': v})),
                ),
                FilterChip(
                  key: const ValueKey('atlas-reengage-chip'),
                  label: const Text('断线自动拉回'),
                  selected: autoReengage,
                  onSelected: _busy ? null : (v) => unawaited(_updateConfig({'autoReengage': v})),
                ),
                ActionChip(
                  key: const ValueKey('atlas-mute-chip'),
                  avatar: const Icon(AppIcons.close, size: 14),
                  label: const Text('静音 20m'),
                  onPressed: _busy ? null : () => unawaited(_mute20m()),
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
                    StatusChip(tone: shizukuReady ? StatusTone.ok : StatusTone.idle, label: shizukuReady ? 'Shizuku 就绪' : 'Shizuku 未授'),
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
