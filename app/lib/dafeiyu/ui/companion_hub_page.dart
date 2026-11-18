// 大肥鱼认知自治中枢：把散落在设置深处的计划、四部门、姐妹、墙、护栏与桌宠收拢到一个
// 遵循 DSH Go 雾蓝玻璃语言（OverviewCard + ListPageCard + StatusChip）的总控面板。

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../plugins/native/native_model.dart' show NativePanel;
import '../../plugins/native/native_state.dart' show nativeContributionsProvider, rereadNativeContributions;
import '../channels/proactive_state.dart';
import '../../state/sessions_state.dart' show currentSessionProvider;
import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/common/tile_grid.dart';
import '../../ui/pets/pet_settings_page.dart';
import 'bank_manager_page.dart';
import 'deposit_page.dart';
import 'intercept_page.dart';
import 'plan_modality_page.dart';
import 'plan_page.dart';
import 'proactive_page.dart';
import 'sisters_page.dart';
import 'strategy_horizons_page.dart';
import 'usage_analytics_page.dart';
import 'wall_page.dart';
import '../../ui/plugins/native/native_panel.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import 'bout_voice_page.dart';
import 'bouts_workspace_page.dart';
import 'voice_settings_page.dart';

enum _HubDeck { all, strategy, sisters, guardrails }

class CompanionHubPage extends ConsumerStatefulWidget {
  const CompanionHubPage({super.key, this.channel});

  final ProactiveChannel? channel;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel}) => Navigator.of(context).push(
    MaterialPageRoute<void>(builder: (_) => CompanionHubPage(channel: channel)),
  );

  @override
  ConsumerState<CompanionHubPage> createState() => _CompanionHubPageState();
}

class _CompanionHubPageState extends ConsumerState<CompanionHubPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();

  bool _loading = true;
  bool _actionBusy = false;
  _HubDeck _deck = _HubDeck.all;
  Map<String, Object?>? _wallStatus;
  ProactiveStatus? _proactive;
  DepositView? _deposit;
  SistersView? _sisters;
  Map<String, Object?>? _planDay;
  GuardianPairingStatus? _guardian;
  Map<String, dynamic>? _phoneStorage;
  Map<String, dynamic>? _shakeDice;
  Map<String, dynamic>? _analytics;
  Map<String, dynamic>? _storyline;
  int _clockCount = 0;
  bool? _lockShadow;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    if (mounted && _wallStatus == null && _proactive == null) {
      setState(() => _loading = true);
    }
    final results = await Future.wait<Object?>([
      _channel.wallStatus().catchError((_) => null),
      _channel.status().catchError((_) => null),
      _channel.deposit().catchError((_) => null),
      _channel.sisters().catchError((_) => null),
      _channel.planDay().catchError((_) => null),
      _channel.guardianStatus().catchError((_) => null),
    ]);
    if (!mounted) return;
    setState(() {
      _loading = false;
      _wallStatus = results[0] is Map<String, Object?> ? results[0] as Map<String, Object?> : null;
      _proactive = results[1] is ProactiveStatus ? results[1] as ProactiveStatus : null;
      _deposit = results[2] is DepositView ? results[2] as DepositView : null;
      _sisters = results[3] is SistersView ? results[3] as SistersView : null;
      _planDay = results[4] is Map<String, Object?> ? results[4] as Map<String, Object?> : null;
      _guardian = results[5] is GuardianPairingStatus ? results[5] as GuardianPairingStatus : null;
    });
    unawaited(_refreshExtras());
  }

  Future<void> _refreshExtras() async {
    final extras = await Future.wait<Object?>([
      _channel.phoneStorageStatus().catchError((_) => null),
      _channel.clockList().catchError((_) => const <Map<String, dynamic>>[]),
      _channel.shakeDiceStatus().catchError((_) => null),
      _channel.analyticsOp({'action': 'overview'}).catchError((_) => null),
      _channel.storylineOp({'action': 'get'}).catchError((_) => null),
      _channel.lockShadow().catchError((_) => null),
    ]);
    if (!mounted) return;
    setState(() {
      _phoneStorage = extras[0] is Map<String, dynamic> ? extras[0] as Map<String, dynamic> : null;
      _clockCount = extras[1] is List ? (extras[1] as List).length : 0;
      _shakeDice = extras[2] is Map<String, dynamic> ? extras[2] as Map<String, dynamic> : null;
      _analytics = extras[3] is Map<String, dynamic> ? extras[3] as Map<String, dynamic> : null;
      _storyline = extras[4] is Map<String, dynamic> ? extras[4] as Map<String, dynamic> : null;
      _lockShadow = extras[5] is bool ? extras[5] as bool : null;
    });
  }

  Future<void> _setLockShadow(bool on) async {
    setState(() => _actionBusy = true);
    final ok = await _channel.setCompanionConfig({'lockShadow': on});
    if (!mounted) return;
    setState(() {
      _actionBusy = false;
      if (ok) _lockShadow = on;
    });
  }

  Future<void> _launchGeminiQuick() async {
    if (_actionBusy) return;
    setState(() => _actionBusy = true);
    try {
      final mode = '${_storyline?['pullMode'] ?? 'countdown_3s'}';
      final promptBlock = '${_storyline?['promptBlock'] ?? ''}'.trim();
      final ok = await _channel.launchGeminiProactive(
        topic: '中枢直连 Gemini 共脑',
        brief: promptBlock,
        mode: mode,
        preferVoice: true,
      );
      if (!mounted) return;
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(content: Text(ok ? '⚡ 已触发 Gemini 主动唤起' : '未能拉起 Gemini，请检查悬浮窗与无障碍权限')),
      );
    } finally {
      if (mounted) setState(() => _actionBusy = false);
    }
  }

  Future<void> _rollDiceNow() async {
    if (_actionBusy) return;
    setState(() => _actionBusy = true);
    try {
      final res = await _channel.rollDiceNow();
      if (!mounted) return;
      final dice = res?['dice'];
      final name = res?['name'] ?? '';
      final ask = res?['ask'] ?? '';
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(
          content: Text(
            dice != null ? '🎲 骰$dice · $name：$ask' : '已触发六骰思维微干预',
          ),
        ),
      );
      unawaited(_refreshExtras());
    } finally {
      if (mounted) setState(() => _actionBusy = false);
    }
  }

  Future<void> _toggleShakeDice() async {
    if (_actionBusy) return;
    final current = _shakeDice?['enabled'] != false;
    setState(() => _actionBusy = true);
    try {
      final next = await _channel.setShakeDice(!current);
      if (!mounted) return;
      unawaited(_refreshExtras());
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(content: Text((next ?? !current) ? '已开启手机「摇一摇六骰子」触发器' : '已暂停摇一摇六骰触发器')),
      );
    } finally {
      if (mounted) setState(() => _actionBusy = false);
    }
  }

  Future<void> _importMemoryNow() async {
    if (_actionBusy) return;
    setState(() => _actionBusy = true);
    try {
      final res = await _channel.importMemoryNow();
      if (!mounted) return;
      final imported = res?['imported'] ?? 0;
      final total = res?['totalFacts'] ?? 0;
      final created = res?['createdFiles'] is List ? (res!['createdFiles'] as List).length : 0;
      final kbDir = res?['kbDir'] ?? '';
      final msg = res?['ok'] == true
          ? '记忆库已同步：新增 $imported 条（共 $total 条可用），新建模板 $created 份\n目录：$kbDir'
          : '请先开启伴侣引擎与存储权限后再导入记忆';
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(content: Text(msg), duration: const Duration(seconds: 5)),
      );
      unawaited(_refresh());
    } finally {
      if (mounted) setState(() => _actionBusy = false);
    }
  }

  Future<void> _copyHandoffPacket() async {
    if (_actionBusy) return;
    setState(() => _actionBusy = true);
    try {
      final res = await _channel.handoffPacket();
      if (!mounted) return;
      final md = res?['markdown'] is String ? (res!['markdown'] as String).trim() : '';
      if (res?['ok'] == true && md.isNotEmpty) {
        await Clipboard.setData(ClipboardData(text: md));
        if (!mounted) return;
        final models = res?['modelsCount'] ?? 0;
        final cos = res?['hasCognitiveOs'] == true ? '已联动六区模型($models条)' : '大肥鱼规则+手机现场';
        ScaffoldMessenger.maybeOf(context)?.showSnackBar(
          SnackBar(content: Text('已复制跨 AI 交接上下文（$cos），可直接粘贴给 Gemini / Antigravity / 任意对话')),
        );
      } else {
        ScaffoldMessenger.maybeOf(context)?.showSnackBar(
          const SnackBar(content: Text('暂未连上伴侣引擎，无法导出交接上下文')),
        );
      }
    } finally {
      if (mounted) setState(() => _actionBusy = false);
    }
  }

  Future<void> _enableStorage() async {
    setState(() => _actionBusy = true);
    final res = await _channel.enablePhoneStorage(restart: true);
    await _refresh();
    if (!mounted) return;
    setState(() => _actionBusy = false);
    final granted = res?['granted'] == true;
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(content: Text(granted ? '已启用手机存储挂载并刷新引擎（Obsidian 可读取）' : '请在系统授权页允许全盘存储访问')),
    );
  }

  Future<void> _restartEngineNow() async {
    setState(() => _actionBusy = true);
    final ok = await _channel.restartEngine();
    if (!mounted) return;
    setState(() => _actionBusy = false);
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(content: Text(ok ? '主动引擎已热重启接管' : '正在后台唤醒引擎，请稍候片刻')),
    );
  }

  Future<void> _openGuardianPairDialog() async {
    var status = _guardian ?? await _channel.guardianStatus();
    if (!mounted) return;
    if (status == null) {
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        const SnackBar(content: Text('当前平台暂不支持大肥鱼哨兵互信配对')),
      );
      return;
    }
    setState(() => _guardian = status);
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setDialogState) {
          final current = status!;
          final hasCode = current.pairCode.isNotEmpty;
          return AlertDialog(
            title: const Text('大肥鱼哨兵互信配对'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  current.sameCert
                      ? '检测到同证书安装：哨兵已免码自动建立互信绑定。'
                      : current.paired
                          ? '跨证书互信已绑定成功，哨兵可实时读取引擎健康状态。'
                          : '若哨兵与大肥鱼使用不同证书签名（如本地/CI 混装），请在下方生成 6 位配对码并填入「大肥鱼哨兵」App。',
                ),
                const SizedBox(height: 12),
                Text(
                  '安装状态：${current.installed ? "已安装哨兵" : "未检测到哨兵 App"} · 互信：${current.paired ? "已绑定" : "待配对"}',
                  style: Theme.of(dialogContext).textTheme.bodySmall,
                ),
                if (hasCode) ...[
                  const SizedBox(height: 14),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    decoration: BoxDecoration(
                      color: Theme.of(dialogContext).colorScheme.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('6 位一次性配对码（10 分钟有效）', style: Theme.of(dialogContext).textTheme.labelSmall),
                              const SizedBox(height: 4),
                              SelectableText(
                                current.pairCode,
                                style: Theme.of(dialogContext).textTheme.headlineSmall?.copyWith(
                                      fontWeight: FontWeight.w700,
                                      letterSpacing: 4,
                                    ),
                              ),
                            ],
                          ),
                        ),
                        IconButton(
                          icon: const Icon(AppIcons.copy, size: 20),
                          tooltip: '复制配对码',
                          onPressed: () {
                            unawaited(Clipboard.setData(ClipboardData(text: current.pairCode)));
                            ScaffoldMessenger.maybeOf(context)?.showSnackBar(
                              const SnackBar(content: Text('已复制 6 位配对码')),
                            );
                          },
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
            actions: [
              TextButton(
                onPressed: () async {
                  final next = await _channel.generateGuardianPairCode();
                  if (next == null) return;
                  status = next;
                  if (mounted) setState(() => _guardian = next);
                  if (dialogContext.mounted) setDialogState(() {});
                },
                child: Text(hasCode ? '刷新配对码' : '生成 6 位配对码'),
              ),
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(),
                child: const Text('完成'),
              ),
            ],
          );
        },
      ),
    );
  }

  void _openPage(Widget page) {
    Navigator.of(context)
        .push(MaterialPageRoute<void>(builder: (_) => page))
        .then((_) {
          if (mounted) unawaited(_refresh());
        });
  }

  Future<void> _openFocus() async {
    if (_actionBusy) return;
    setState(() => _actionBusy = true);
    try {
      final ok = await _channel.openFocusControl();
      if (mounted && !ok) {
        ScaffoldMessenger.maybeOf(context)?.showSnackBar(
          const SnackBar(content: Text('当前无法打开原生专注监督页面')),
        );
      }
    } finally {
      if (mounted) setState(() => _actionBusy = false);
    }
  }

  Future<void> _triggerTick() async {
    if (_actionBusy) return;
    setState(() => _actionBusy = true);
    try {
      final ok = await _channel.tickNow();
      if (!mounted) return;
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(content: Text(ok ? '已唤醒大肥鱼检查当前状态' : '当前无法触发检查，请确认引擎运行中')),
      );
      if (ok) unawaited(_refresh());
    } finally {
      if (mounted) setState(() => _actionBusy = false);
    }
  }

  (StatusTone, String, String, String) _overviewMeta() {
    final w = _wallStatus;
    final p = _proactive;
    if (_loading && w == null && p == null) {
      return (StatusTone.busy, '正在读取大肥鱼状态…', '同步计划、门禁与四部门进度', '正在连接伴侣引擎…');
    }
    if (w == null && p == null) {
      return (StatusTone.idle, '伴侣引擎暂未应答', '启动引擎后可查看今日计划、娱乐锁与四部门审计', '点击刷新重连伴侣内核');
    }
    final locked = w?['locked'] == true;
    final until = w?['openUntil'] is num ? (w!['openUntil'] as num).toInt() : 0;
    final why = w?['why'] is String ? (w!['why'] as String).trim() : '';
    final planItems = (_planDay?['plan'] is Map ? (_planDay!['plan'] as Map)['items'] : null);
    final boutCount = planItems is List ? planItems.length : 0;
    final doneDept = _deposit?.items.where((i) => i.done).length ?? 0;
    final deptText = _deposit == null ? '四部门未同步' : '四部门 $doneDept/4';
    final planText = boutCount > 0 ? '今日 $boutCount 局' : '今日尚未排局';
    final subtitle = '$planText · $deptText';
    final tone = locked
        ? StatusTone.warn
        : (p?.config.enabled ?? false)
        ? StatusTone.ok
        : StatusTone.idle;
    final title = locked ? '专注守护 · 娱乐锁开启中' : '专注守护 · 放风/待命';
    final footer = locked
        ? (until > 0 ? '放开时间 ${_hhmm(until)}${why.isEmpty ? "" : " · $why"}' : (why.isEmpty ? '娱乐应用处于门禁状态' : why))
        : (p != null
            ? (p.holdReason != null
                ? '内核暂缓主动发球：${p.holdReason}'
                : (p.config.enabled ? '主动内核运行中 · 每日上限 ${p.config.dailyMax} 次' : '门禁已放开 · 主动提醒未开'))
            : '门禁已放开');
    return (tone, title, subtitle, footer);
  }

  NativePanel? _boutsPanel() {
    final plugin = ref.watch(nativeContributionsProvider).plugin('dsh-go-companion');
    for (final panel in plugin?.panels ?? const <NativePanel>[]) {
      if (panel.id == 'bouts') return panel;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final bouts = _boutsPanel();
    final currentSession = ref.watch(currentSessionProvider);
    final (tone, title, subtitle, footer) = _overviewMeta();
    final depositDone = _deposit?.items.where((e) => e.done).length ?? 0;
    final depositTotal = _deposit?.items.length ?? 0;
    final appealLeft = _sisters?.audit.done;
    final planNeedsGate = _sisters?.planGate ?? false;
    final locked = _wallStatus?['locked'] == true;
    final planItems = (_planDay?['plan'] is Map ? (_planDay!['plan'] as Map)['items'] : null);
    final boutCount = planItems is List ? planItems.length : 0;
    final geminiStats = _analytics?['geminiStats'] is Map ? _analytics!['geminiStats'] as Map : const {};
    final geminiMinToday = geminiStats['minutesToday'] is num ? (geminiStats['minutesToday'] as num).toInt() : 0;

    return AppPage.list(
      title: '大肥鱼中枢',
      subtitle: const Text('今日计划、每日四部门、姐妹记忆与系统护栏的一站入口'),
      actions: [
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: '刷新状态',
          onPressed: () => unawaited(_refresh()),
        ),
      ],
      children: [
        OverviewCard(
          leading: OverviewGlyph(AppIcons.bot, tint: locked ? 3 : 6),
          title: Row(
            children: [
              Expanded(child: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis)),
              const SizedBox(width: 8),
              StatusChip(tone: tone, label: locked ? '锁定中' : '就绪'),
            ],
          ),
          subtitle: Text(subtitle, maxLines: 2, overflow: TextOverflow.ellipsis),
          footer: OverviewFooter(tone: tone, text: footer),
          onTap: () => unawaited(_refresh()),
          onFooterTap: () => _openPage(const WallPage()),
        ),
        if (_lockShadow != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: Card(
              child: SwitchListTile(
                title: const Text('测试模式（锁只提示，不真锁）'),
                subtitle: Text(
                  _lockShadow!
                      ? '现在：锁不会落下，只弹「这里本来会锁」。测完关掉，锁才真的生效'
                      : '现在：锁真的生效。被锁出不来时，下拉通知栏点「紧急解除」',
                ),
                value: _lockShadow!,
                onChanged: _actionBusy ? null : (v) => unawaited(_setLockShadow(v)),
              ),
            ),
          ),
        Padding(
          padding: const EdgeInsets.only(bottom: 14),
          child: SegmentedPill<_HubDeck>(
            segments: const [
              PillSegment(_HubDeck.all, '总览全部'),
              PillSegment(_HubDeck.strategy, '战略与统计'),
              PillSegment(_HubDeck.sisters, '姐妹与主动'),
              PillSegment(_HubDeck.guardrails, '护栏与底座'),
            ],
            selected: _deck,
            onChanged: (next) => setState(() => _deck = next),
          ),
        ),
        if (_deck == _HubDeck.all) ...[
          TileGrid(
            columns: 2,
            spacing: 12,
            padding: EdgeInsets.zero,
            children: [
              FeatureCard(
                icon: AppIcons.chart,
                tint: 0,
                badge: '大屏',
                title: '📊 每日统计与晚间总报',
                subtitle: '24h 切片 · 90 天热力图',
                onTap: () => _openPage(UsageAnalyticsPage(channel: _channel)),
              ),
              FeatureCard(
                icon: AppIcons.planMode,
                tint: 6,
                badge: '主航道',
                title: '🧭 三层战略作战室',
                subtitle: '长期北极星 · 中期战役',
                onTap: () => _openPage(StrategyHorizonsPage(channel: _channel)),
              ),
              FeatureCard(
                icon: AppIcons.askAi,
                tint: 7,
                badge: geminiMinToday > 0 ? '${geminiMinToday}m' : '共脑',
                title: '⚡ 主动唤起 Gemini',
                subtitle: '同日脉络带入与拉起策略',
                onTap: () => _openPage(
                  UsageAnalyticsPage(channel: _channel, initialTab: AnalyticsTab.gemini),
                ),
              ),
              FeatureCard(
                icon: AppIcons.jobs,
                tint: 4,
                badge: '契约',
                title: '📑 计划与契约台',
                subtitle: '契约共创 · 全天排局表',
                onTap: () => _openPage(PlanModalityPage(channel: _channel)),
              ),
            ],
          ),
          const SizedBox(height: 12),
        ],
        if (_deck == _HubDeck.strategy) ...[
          TileGrid(
            columns: 2,
            spacing: 12,
            padding: EdgeInsets.zero,
            children: [
              FeatureCard(
                icon: AppIcons.planMode,
                tint: 6,
                badge: '北极星',
                title: '三层战略作战室',
                subtitle: '长期航道 · 中期战役 · 一键拆入',
                onTap: () => _openPage(StrategyHorizonsPage(channel: _channel)),
              ),
              FeatureCard(
                icon: AppIcons.jobs,
                tint: 4,
                badge: '契约',
                title: '计划与契约台',
                subtitle: '契约验收 · 全天排局时间表',
                onTap: () => _openPage(PlanModalityPage(channel: _channel)),
              ),
              FeatureCard(
                icon: AppIcons.chart,
                tint: 0,
                badge: '90天',
                title: '每日统计与热力图',
                subtitle: '24h 甘特带 · 长期堆叠对比',
                onTap: () => _openPage(UsageAnalyticsPage(channel: _channel)),
              ),
              FeatureCard(
                icon: AppIcons.featured,
                tint: 2,
                badge: '21:30',
                title: '晚间 AI 可视化总报',
                subtitle: '今日战果评分 · 明日三件套',
                onTap: () => _openPage(
                  UsageAnalyticsPage(channel: _channel, initialTab: AnalyticsTab.report),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          ListPageCard(
            title: '备考题库与四部门审计',
            children: [
              ListPageRow(
                icon: AppIcons.skill,
                title: '备考题库中心',
                subtitle: '导入真题 · 锁屏答对 10 题解锁 5 分钟',
                subtitleMaxLines: 2,
                onTap: () => _openPage(BankManagerPage(channel: _channel)),
              ),
              ListPageRow(
                icon: AppIcons.calendar,
                title: '今日计划（时间块）',
                subtitle: '按时间块排定每日几局与必需/允许应用',
                subtitleMaxLines: 2,
                onTap: () => _openPage(const PlanPage()),
              ),
              ListPageRow(
                icon: AppIcons.jobs,
                title: '每日四部门',
                subtitle: '信息收集 · 谋划思考 · 行为管理 · 统筹规划',
                subtitleMaxLines: 2,
                value: depositTotal > 0 ? '$depositDone/$depositTotal 段' : null,
                onTap: () => _openPage(const DepositPage()),
              ),
            ],
          ),
        ] else if (_deck == _HubDeck.sisters) ...[
          TileGrid(
            columns: 2,
            spacing: 12,
            padding: EdgeInsets.zero,
            children: [
              FeatureCard(
                icon: AppIcons.team,
                tint: 2,
                badge: '双生',
                title: '姐妹与记忆',
                subtitle: '鱼丸 & 鲸澜人设、记忆与审计',
                onTap: () => _openPage(const SistersPage()),
              ),
              FeatureCard(
                icon: AppIcons.askAi,
                tint: 7,
                badge: '强拉',
                title: '立即唤起 Gemini Live',
                subtitle: '自动装入今日连续脉络开聊',
                onTap: _actionBusy ? null : () => unawaited(_launchGeminiQuick()),
              ),
              FeatureCard(
                icon: AppIcons.voice,
                tint: 3,
                badge: 'TTS',
                title: '姐妹与局官语音声线',
                subtitle: '三角色独立音色与试听',
                onTap: () => _openPage(const VoiceSettingsPage()),
              ),
              FeatureCard(
                icon: AppIcons.overlayBall,
                tint: 5,
                badge: '64dp',
                title: '摇一骰 · 轻思考微干预',
                subtitle: '立即触发 3D 大骰子卡片',
                onTap: _actionBusy ? null : () => unawaited(_rollDiceNow()),
              ),
            ],
          ),
          const SizedBox(height: 14),
          ListPageCard(
            title: '同日脉络、声线与桌宠',
            children: [
              ListPageRow(
                icon: AppIcons.trajectory,
                title: '同日连续叙事流 & Gemini 共脑策略',
                subtitle: '切换 3秒浮窗自动接通 / 零秒铁血强拉 / 仅温和提醒',
                subtitleMaxLines: 2,
                value: geminiMinToday > 0 ? '今日 ${geminiMinToday}m' : '打开',
                onTap: () => _openPage(
                  UsageAnalyticsPage(channel: _channel, initialTab: AnalyticsTab.gemini),
                ),
              ),
              ListPageRow(
                icon: AppIcons.pet,
                title: '双姐妹桌宠皮肤（.zip 导入）',
                subtitle: '导入鱼丸 yuwan-girl.zip / 鲸澜 jinglan-girl.zip 并联动发球自动换肤',
                subtitleMaxLines: 2,
                onTap: () => _openPage(const PetSettingsPage()),
              ),
              ListPageRow(
                icon: AppIcons.voice,
                title: '局官语音交互',
                subtitle: '局官念题与实时语音作答',
                onTap: () => _openPage(const BoutVoicePage()),
              ),
              ListPageRow(
                icon: AppIcons.notificationsOff,
                title: '主动搭话与诊断',
                subtitle: '主动提醒间隔、免打扰时段、PC 联动账本与日志导出',
                subtitleMaxLines: 2,
                value: _proactive == null ? null : (_proactive!.config.enabled ? '已开启' : '未开启'),
                onTap: () => _openPage(const ProactivePage()),
              ),
            ],
          ),
        ] else if (_deck == _HubDeck.guardrails) ...[
          ListPageCard(
            title: '模式、锁机与哨兵护栏',
            children: [
              ListPageRow(
                icon: AppIcons.blocked,
                title: '娱乐锁机',
                subtitle: '严格/联动/训练锁机模式、黑名单清单、误放行白名单一键恢复',
                subtitleMaxLines: 2,
                onTap: () => _openPage(const InterceptPage()),
              ),
              ListPageRow(
                icon: AppIcons.verified,
                iconColor: locked ? colors.warning : colors.success,
                title: '墙与申诉',
                subtitle: '控制塔冷酷审计记录、规则阈值与物理证据申诉',
                subtitleMaxLines: 2,
                value: locked ? '门禁中' : '查看',
                onTap: () => _openPage(const WallPage()),
              ),
              ListPageRow(
                icon: AppIcons.time,
                title: '专注监督',
                subtitle: '原生 5 秒离开拉回与独立守护界面（引擎断开仍生效）',
                subtitleMaxLines: 2,
                onTap: () => unawaited(_openFocus()),
              ),
              ListPageRow(
                icon: AppIcons.permissions,
                iconColor: (_guardian?.paired ?? false) ? colors.success : null,
                title: '大肥鱼哨兵互信配对',
                subtitle: '查看同签名互信状态，或生成 6 位配对码绑定独立「大肥鱼哨兵」App',
                subtitleMaxLines: 2,
                value: _guardian == null
                    ? null
                    : (_guardian!.paired
                        ? (_guardian!.sameCert ? '免码互信' : '已配对')
                        : (_guardian!.installed ? '待配对' : '未安装')),
                onTap: () => unawaited(_openGuardianPairDialog()),
              ),
            ],
          ),
          const SizedBox(height: 16),
          ListPageCard(
            title: 'Obsidian 挂载与内核底座',
            children: [
              ListPageRow(
                icon: AppIcons.queued,
                title: '局工作台与时钟内核',
                subtitle: '快速开严格专时、管理 Cron / 高频 / 循环番茄闹钟',
                subtitleMaxLines: 2,
                value: _clockCount > 0 ? '$_clockCount 个时钟' : '打开',
                onTap: () => _openPage(BoutsWorkspacePage(channel: _channel)),
              ),
              ListPageRow(
                icon: AppIcons.files,
                iconColor: (_phoneStorage?['granted'] == true) ? colors.success : colors.warning,
                title: '手机目录与 Obsidian 自动挂载',
                subtitle: (_phoneStorage?['granted'] == true)
                    ? '已授权全盘读取，Obsidian 库自动检索就绪'
                    : '未挂载 /sdcard 目录，点击一键授权并热重启让姐妹读到 Obsidian 笔记',
                subtitleMaxLines: 2,
                badge: StatusChip(
                  tone: (_phoneStorage?['granted'] == true) ? StatusTone.ok : StatusTone.warn,
                  label: (_phoneStorage?['granted'] == true) ? '已挂载' : '需授权',
                ),
                onTap: _actionBusy ? null : () => unawaited(_enableStorage()),
              ),
              ListPageRow(
                icon: AppIcons.files,
                iconColor: Theme.of(context).colorScheme.primary,
                title: '生成记忆模板并导入 Obsidian 数据库',
                subtitle: '在 大肥鱼/导入/ 生成模板数据库文件并一键去重写入记忆库，让鱼丸和鲸澜马上记住你',
                subtitleMaxLines: 2,
                value: '立即导入',
                onTap: _actionBusy ? null : () => unawaited(_importMemoryNow()),
              ),
              ListPageRow(
                icon: AppIcons.copy,
                title: '复制跨 AI 交接上下文（Cognitive OS）',
                subtitle: '汇总三铁律、03_Thinking_Models、我.md 与六骰碎记，一键粘贴给 Gemini / 外部 AI',
                subtitleMaxLines: 2,
                value: '一键复制',
                onTap: _actionBusy ? null : () => unawaited(_copyHandoffPacket()),
              ),
              ListPageRow(
                icon: AppIcons.restart,
                title: '一键热重启主动引擎',
                subtitle: '遇到后台长期无响应或切库后立即拉起接管，并自动补发中断对话',
                subtitleMaxLines: 2,
                onTap: _actionBusy ? null : () => unawaited(_restartEngineNow()),
              ),
            ],
          ),
        ] else ...[
        TileGrid(
          columns: 2,
          spacing: 12,
          padding: EdgeInsets.zero,
          children: [
            FeatureCard(
              icon: AppIcons.today,
              tint: planNeedsGate ? 4 : 1,
              badge: bouts != null ? '进局' : (boutCount > 0 ? '$boutCount 局' : '去排局'),
              title: '今日的局',
              subtitle: bouts != null ? '进入实时原生推进面' : '排定今日几局与白名单',
              onTap: () {
                if (bouts != null) {
                  _openPage(NativePanelPage(panel: bouts, sessionId: currentSession));
                } else {
                  rereadNativeContributions(ref);
                  _openPage(const PlanPage());
                }
              },
            ),
            FeatureCard(
              icon: AppIcons.voice,
              tint: 3,
              badge: '连麦',
              title: '局官语音交互',
              subtitle: '局官念题与实时语音作答',
              onTap: () => _openPage(const BoutVoicePage()),
            ),
            FeatureCard(
              icon: AppIcons.overlayBall,
              tint: 5,
              badge: _shakeDice?['lastDice'] is num && (_shakeDice!['lastDice'] as num) > 0
                  ? '骰${_shakeDice!['lastDice']}'
                  : '摇一骰',
              title: '摇一骰 · 轻思考微干预',
              subtitle: (_shakeDice?['enabled'] != false) ? '亮屏轻晃两下即出浮窗' : '手动摇一骰触发',
              onTap: _actionBusy ? null : () => unawaited(_rollDiceNow()),
            ),
            FeatureCard(
              icon: AppIcons.agentLoop,
              tint: 0,
              badge: _actionBusy ? '巡检中' : '唤醒',
              title: _actionBusy ? '正在唤醒大肥鱼…' : '立即触发一次状态巡检',
              subtitle: '主动内核对齐屏幕与节奏',
              onTap: _actionBusy ? null : () => unawaited(_triggerTick()),
            ),
          ],
        ),
        const SizedBox(height: 14),
        ListPageCard(
          children: [
            ListPageRow(
              icon: AppIcons.verified,
              iconColor: (_shakeDice?['enabled'] != false) ? colors.success : null,
              title: '摇一摇六骰悬浮监听',
              subtitle: '亮屏时轻晃两下手机自动弹出六骰思考卡片（不占权限、可复制给 Gemini）',
              subtitleMaxLines: 2,
              badge: StatusChip(
                tone: (_shakeDice?['enabled'] != false) ? StatusTone.ok : StatusTone.idle,
                label: (_shakeDice?['enabled'] != false) ? '监听中' : '已暂停',
              ),
              onTap: _actionBusy ? null : () => unawaited(_toggleShakeDice()),
            ),
          ],
        ),
        const SizedBox(height: 16),
        ListPageCard(
          title: '每日自治',
          children: [
            ListPageRow(
              icon: AppIcons.calendar,
              iconColor: planNeedsGate ? colors.warning : null,
              title: '今日计划',
              subtitle: planNeedsGate
                  ? '今日尚未排局 · 请先设置必需应用与允许应用'
                  : '一局一行：时间预算、工作包与晨间 10 分钟接单窗口',
              subtitleMaxLines: 2,
              badge: planNeedsGate ? const StatusChip(tone: StatusTone.warn, label: '待排局') : null,
              onTap: () => _openPage(const PlanPage()),
            ),
            ListPageRow(
              icon: AppIcons.jobs,
              iconColor: depositTotal > 0 && depositDone == depositTotal ? colors.success : null,
              title: '每日四部门',
              subtitle: '信息收集 · 谋划思考 · 行为管理 · 统筹规划（含白天碎片记事）',
              subtitleMaxLines: 2,
              value: depositTotal > 0 ? '$depositDone/$depositTotal 段' : null,
              onTap: () => _openPage(const DepositPage()),
            ),
            ListPageRow(
              icon: AppIcons.verified,
              iconColor: locked ? colors.warning : colors.success,
              title: '墙与申诉',
              subtitle: '控制塔冷酷审计记录、规则阈值与物理证据申诉',
              subtitleMaxLines: 2,
              value: locked ? '门禁中' : '查看',
              onTap: () => _openPage(const WallPage()),
            ),
            ListPageRow(
              icon: AppIcons.team,
              title: '姐妹与记忆',
              subtitle: '鱼丸与鲸澜双人格卡、记住的事实与基础操作通过率',
              subtitleMaxLines: 2,
              value: appealLeft != null ? '今日完成 $appealLeft' : null,
              onTap: () => _openPage(const SistersPage()),
            ),
          ],
        ),
        const SizedBox(height: 16),
        ListPageCard(
          title: '护栏与环境',
          children: [
            ListPageRow(
              icon: AppIcons.blocked,
              title: '娱乐锁机',
              subtitle: '严格/联动/训练锁机模式、黑名单清单、误放行白名单一键恢复',
              subtitleMaxLines: 2,
              onTap: () => _openPage(const InterceptPage()),
            ),
            ListPageRow(
              icon: AppIcons.time,
              title: '专注监督',
              subtitle: '原生 5 秒离开拉回与独立守护界面（引擎断开仍生效）',
              subtitleMaxLines: 2,
              onTap: () => unawaited(_openFocus()),
            ),
            ListPageRow(
              icon: AppIcons.notificationsOff,
              title: '主动搭话与诊断',
              subtitle: '主动提醒间隔、免打扰时段、PC 联动账本与日志导出',
              subtitleMaxLines: 2,
              value: _proactive == null ? null : (_proactive!.config.enabled ? '已开启' : '未开启'),
              onTap: () => _openPage(const ProactivePage()),
            ),
            ListPageRow(
              icon: AppIcons.permissions,
              iconColor: (_guardian?.paired ?? false) ? colors.success : null,
              title: '大肥鱼哨兵互信配对',
              subtitle: '查看同签名互信状态，或生成 6 位配对码绑定独立「大肥鱼哨兵」App',
              subtitleMaxLines: 2,
              value: _guardian == null
                  ? null
                  : (_guardian!.paired
                      ? (_guardian!.sameCert ? '免码互信' : '已配对')
                      : (_guardian!.installed ? '待配对' : '未安装')),
              onTap: () => unawaited(_openGuardianPairDialog()),
            ),
            ListPageRow(
              icon: AppIcons.pet,
              title: '桌面宠物',
              subtitle: '桌面悬浮形象、气泡提问与双姐妹互动样式',
              subtitleMaxLines: 2,
              onTap: () => _openPage(const PetSettingsPage()),
            ),
            ListPageRow(
              icon: AppIcons.voice,
              title: '姐妹与局官语音声线',
              subtitle: '独立配置鱼丸 / 鲸澜 / 局官音色、MiMo/OpenAI TTS 与试听',
              subtitleMaxLines: 2,
              onTap: () => _openPage(const VoiceSettingsPage()),
            ),
            ListPageRow(
              icon: AppIcons.queued,
              title: '局工作台与时钟内核',
              subtitle: '快速开严格专时、管理 Cron / 高频 / 循环番茄闹钟',
              subtitleMaxLines: 2,
              value: _clockCount > 0 ? '$_clockCount 个时钟' : '打开',
              onTap: () => _openPage(BoutsWorkspacePage(channel: _channel)),
            ),
            ListPageRow(
              icon: AppIcons.files,
              iconColor: (_phoneStorage?['granted'] == true) ? colors.success : colors.warning,
              title: '手机目录与 Obsidian 自动挂载',
              subtitle: (_phoneStorage?['granted'] == true)
                  ? '已授权全盘读取，Obsidian 库自动检索就绪'
                  : '未挂载 /sdcard 目录，点击一键授权并热重启让姐妹读到 Obsidian 笔记',
              subtitleMaxLines: 2,
              badge: StatusChip(
                tone: (_phoneStorage?['granted'] == true) ? StatusTone.ok : StatusTone.warn,
                label: (_phoneStorage?['granted'] == true) ? '已挂载' : '需授权',
              ),
              onTap: _actionBusy ? null : () => unawaited(_enableStorage()),
            ),
            ListPageRow(
              icon: AppIcons.files,
              iconColor: Theme.of(context).colorScheme.primary,
              title: '生成记忆模板并导入 Obsidian 数据库',
              subtitle: '在 大肥鱼/导入/ 生成模板数据库文件并一键去重写入记忆库，让鱼丸和鲸澜马上记住你',
              subtitleMaxLines: 2,
              value: '立即导入',
              onTap: _actionBusy ? null : () => unawaited(_importMemoryNow()),
            ),
            ListPageRow(
              icon: AppIcons.copy,
              title: '复制跨 AI 交接上下文（Cognitive OS）',
              subtitle: '汇总三铁律、03_Thinking_Models、我.md 与六骰碎记，一键粘贴给 Gemini / 外部 AI',
              subtitleMaxLines: 2,
              value: '一键复制',
              onTap: _actionBusy ? null : () => unawaited(_copyHandoffPacket()),
            ),
            ListPageRow(
              icon: AppIcons.restart,
              title: '一键热重启主动引擎',
              subtitle: '遇到后台长期无响应或切库后立即拉起接管，并自动补发中断对话',
              subtitleMaxLines: 2,
              onTap: _actionBusy ? null : () => unawaited(_restartEngineNow()),
            ),
          ],
        ),
        ],
      ],
    );
  }

  static String _hhmm(int ms) {
    final d = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
  }
}
