// 大肥鱼认知自治中枢：把散落在设置深处的计划、四部门、姐妹、墙、护栏与桌宠收拢到一个
// 遵循 DSH Go 雾蓝玻璃语言（OverviewCard + ListPageCard + StatusChip）的总控面板。

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../channels/proactive_state.dart';
import '../../state/sessions_state.dart' show sessionsProvider;
import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/pets/pet_settings_page.dart';
import 'app_tiers_page.dart';
import 'covenant_page.dart';
import 'gemini_bridge_page.dart';
import 'health_page.dart';
import 'records_page.dart';
import 'today_cards.dart';
import 'proactive_page.dart';
import 'sisters_page.dart';
import 'usage_analytics_page.dart';
import 'wall_page.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import 'bouts_workspace_page.dart';
import 'voice_settings_page.dart';

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
  bool? _arbiterEnforce;
  Map<String, dynamic>? _rotate;
  IntensityView? _intensity;

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
    _refreshExtras();
  }

  void _refreshExtras() {
    void load<T>(Future<T> future, void Function(T value) apply) {
      unawaited(
        future.then((value) {
          if (!mounted) return;
          setState(() => apply(value));
        }).catchError((_) {}),
      );
    }

    load(_channel.phoneStorageStatus(), (v) => _phoneStorage = v);
    load(_channel.clockList(), (v) => _clockCount = v.length);
    load(_channel.shakeDiceStatus(), (v) => _shakeDice = v);
    load(_channel.analyticsOp({'action': 'overview'}), (v) {
      _analytics = v;
      _intensity ??= IntensityView.fromWire(v);
    });
    load(_channel.storylineOp({'action': 'get'}), (v) => _storyline = v);
    load(_channel.lockShadow(), (v) => _lockShadow = v);
    load(_channel.arbiterEnforce(), (v) => _arbiterEnforce = v);
    load(_channel.rotateOp({'action': 'status'}), (v) => _rotate = v);
    load(_channel.intensityStatus(), (v) {
      if (v != null) _intensity = v;
    });
    load(_channel.todayStatus(), (v) {
      _intensity ??= IntensityView.fromWire(v);
    });
  }

  /// 会话换班：插件先写交接 → App 新建对话 → 绑上去。旧对话不删。
  Future<void> _rotateSession(String role) async {
    if (_actionBusy) return;
    setState(() => _actionBusy = true);
    String message;
    try {
      final prep = await _channel.rotateOp({'action': 'prepare', 'role': role});
      if (prep?['ok'] != true) throw StateError('交接没写成');
      final id = await ref.read(sessionsProvider.notifier).create();
      final bound = await _channel.rotateOp({'action': 'bound', 'role': role, 'sessionId': id});
      message = bound?['ok'] == true ? '换好了：新对话已绑定，交接会在头几天自动带上' : '新对话建好了，但没绑上：${bound?['error'] ?? '插件没回应'}';
    } on Object catch (e) {
      message = '没换成：$e';
    }
    if (!mounted) return;
    setState(() => _actionBusy = false);
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(message)));
    _refreshExtras();
  }

  Widget _rotateRow(String role, String name) {
    final st = _rotate?[role];
    final m = st is Map ? st : const {};
    final turns = m['turns'] is num ? (m['turns'] as num).toInt() : 0;
    final days = m['days'] is num ? (m['days'] as num).toInt() : 0;
    final due = m['due'] == true;
    final bound = '${m['sessionId'] ?? ''}'.isNotEmpty;
    return ListTile(
      title: Text('$name 的对话'),
      subtitle: Text(bound ? '已聊 $turns 轮 · $days 天${due ? ' · 该换班了（${m['why'] ?? ''}）' : ''}' : '还没绑定对话'),
      trailing: TextButton(
        onPressed: _actionBusy ? null : () => unawaited(_rotateSession(role)),
        child: Text(due ? '现在换' : '换新对话'),
      ),
    );
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

  Future<void> _setArbiterEnforce(bool on) async {
    setState(() => _actionBusy = true);
    final ok = await _channel.setArbiterEnforce(on);
    if (!mounted) return;
    setState(() {
      _actionBusy = false;
      if (ok) _arbiterEnforce = on;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(
        content: Text(
          ok
              ? (on ? '已开启统一裁决器执法（永不锁/测试模式/紧急解除仍最高优先）' : '已切回裁决器影子模式（仅记录 arbiter-diff.jsonl）')
              : '暂未连上伴侣引擎，无法切换裁决器执法开关',
        ),
      ),
    );
  }

  static String _intensityLabel(String tier) => switch (tier) {
        'gentle' => '温和',
        'strict' => '严格',
        _ => '标准',
      };

  Future<void> _setIntensityTier(String nextTier) async {
    if (_actionBusy) return;
    setState(() => _actionBusy = true);
    final res = await _channel.setIntensity(nextTier);
    if (!mounted) return;
    setState(() {
      _actionBusy = false;
      if (res != null) _intensity = res;
    });
    final String msg;
    if (res == null) {
      msg = '暂未连上伴侣引擎，无法切换强度档';
    } else if (res.pendingTier != null) {
      msg = '已申请明日放宽至「${_intensityLabel(res.pendingTier!)}」档（改严立即生效，改松次日生效）';
    } else {
      msg = '已切为「${_intensityLabel(res.tier)}」档（立即生效）';
    }
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
    if (res != null) _refreshExtras();
  }

  Widget _buildIntensityCard(BuildContext context, IntensityView? intensity) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final scheme = theme.colorScheme;
    final currentTier = intensity?.tier ?? 'standard';
    final pendingTier = intensity?.pendingTier;
    final hardCapped = intensity?.hardCapped == true;

    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: GlassSurface(
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(AppIcons.configure, size: 17, color: scheme.primary),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '强度档（温和 / 标准 / 严格）',
                      style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                    ),
                  ),
                  if (intensity != null && intensity.hasAutonomyShift) ...[
                    StatusChip(
                      tone: StatusTone.ok,
                      label: '自律放宽→${_intensityLabel(intensity.resolvedEffectiveTier)}',
                    ),
                    const SizedBox(width: 6),
                  ],
                  if (pendingTier != null) ...[
                    StatusChip(
                      tone: StatusTone.warn,
                      label: '明日→${_intensityLabel(pendingTier)}',
                    ),
                  ] else if (intensity != null) ...[
                    StatusChip(
                      tone: hardCapped ? StatusTone.warn : StatusTone.ok,
                      label: hardCapped ? '硬干预已满' : '硬干预 ${intensity.hardCount}/${intensity.hardCap}',
                    ),
                  ],
                ],
              ),
              const SizedBox(height: 10),
              SegmentedPill<String>(
                key: const ValueKey('hub-intensity-pill'),
                height: 36,
                segments: const [
                  PillSegment('gentle', '温和'),
                  PillSegment('standard', '标准'),
                  PillSegment('strict', '严格'),
                ],
                selected: currentTier,
                onChanged: _actionBusy ? null : (tier) => unawaited(_setIntensityTier(tier)),
              ),
              const SizedBox(height: 8),
              Text(
                pendingTier != null
                    ? '已排队明日${intensity?.pendingEffectiveDay != null ? '（${intensity!.pendingEffectiveDay}）' : ''}放宽至「${_intensityLabel(pendingTier)}」档 · 当前仍按「${_intensityLabel(intensity?.resolvedEffectiveTier ?? currentTier)}」执行'
                    : (intensity != null && intensity.hasAutonomyShift
                        ? '14 天自律达标已自动放宽一档 · 实际按「${_intensityLabel(intensity.resolvedEffectiveTier)}」执行 · 紧急解除上限 ${intensity.emergencyDisarmDailyCap} 次'
                        : '改严立即生效，改松次日生效 · 当前每日紧急解除上限 ${intensity?.emergencyDisarmDailyCap ?? 3} 次'),
                style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
              ),
            ],
          ),
        ),
      ),
    );
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

  Future<void> _toggleShakeDice() async {
    if (_actionBusy) return;
    final current = _shakeDice?['enabled'] != false;
    setState(() => _actionBusy = true);
    try {
      final next = await _channel.setShakeDice(!current);
      if (!mounted) return;
      _refreshExtras();
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

  /// 每次 setState 都跳一下：推出去的分组页跟着重建（开关一拨，分组页立刻变）。
  final ValueNotifier<int> _tick = ValueNotifier<int>(0);

  @override
  void setState(VoidCallback fn) {
    super.setState(fn);
    _tick.value++;
  }

  @override
  void dispose() {
    _tick.dispose();
    super.dispose();
  }

  /// 分组页：和中枢共用同一份状态和同一套动作，只是分开摆。
  void _openGroup(String title, String subtitle, List<Widget> Function(BuildContext ctx) children) {
    unawaited(Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ValueListenableBuilder<int>(
          valueListenable: _tick,
          builder: (ctx, _, _) => AppPage.list(
            title: title,
            subtitle: Text(subtitle),
            actions: [
              GlassIconButton(icon: AppIcons.refresh, tooltip: '刷新状态', onPressed: () => unawaited(_refresh())),
            ],
            children: children(ctx),
          ),
        ),
      ),
    ));
  }

  static const _groupMargin = EdgeInsets.fromLTRB(16, 0, 16, 20);

  /// 两条鱼：她们是谁、记住了什么、什么时候开口、用什么声音、长什么样。
  List<Widget> _fishGroup(BuildContext ctx) {
    final colors = DshColors.of(ctx);
    final appealLeft = _sisters?.audit.done;
    final geminiStats = _analytics?['geminiStats'] is Map ? _analytics!['geminiStats'] as Map : const {};
    final geminiMinToday = geminiStats['minutesToday'] is num ? (geminiStats['minutesToday'] as num).toInt() : 0;
    final shakeOn = _shakeDice?['enabled'] != false;
    return [
      ListPageCard(
        title: '人设与记忆',
        margin: _groupMargin,
        children: [
          ListPageRow(
            icon: AppIcons.team,
            title: '姐妹与记忆',
            subtitle: '鱼丸与鲸澜的人格卡、记住的事实与基础操作通过率',
            subtitleMaxLines: 2,
            value: appealLeft != null ? '今日完成 $appealLeft' : null,
            chevron: true,
            onTap: () => _openPage(const SistersPage()),
          ),
          ListPageRow(
            key: const ValueKey('hub-covenant'),
            icon: AppIcons.instructions,
            title: '公约',
            subtitle: '你和两条鱼的成文规矩：遇到什么情况、她们怎么处理；她们只能提议，你批',
            subtitleMaxLines: 2,
            chevron: true,
            onTap: () => _openPage(const CovenantPage()),
          ),
          if (_rotate != null) ...[
            _rotateRow('companion', '鱼丸'),
            _rotateRow('strategist', '鲸澜'),
          ],
        ],
      ),
      ListPageCard(
        title: '开口与连麦',
        margin: _groupMargin,
        children: [
          ListPageRow(
            icon: AppIcons.notificationsOff,
            title: '主动搭话',
            subtitle: '她们多久主动开口一次、免打扰时段（只管说话，不管锁）',
            subtitleMaxLines: 2,
            value: _proactive == null ? null : (_proactive!.config.enabled ? '已开启' : '未开启'),
            chevron: true,
            onTap: () => _openPage(const ProactivePage()),
          ),
          ListPageRow(
            icon: AppIcons.trajectory,
            title: 'Gemini 连麦策略',
            subtitle: '3 秒浮窗自动接通 / 零秒强拉 / 仅温和提醒',
            subtitleMaxLines: 2,
            value: geminiMinToday > 0 ? '今日 ${geminiMinToday}m' : null,
            chevron: true,
            onTap: () => _openPage(UsageAnalyticsPage(channel: _channel, initialTab: AnalyticsTab.gemini)),
          ),
          ListPageRow(
            key: const ValueKey('hub-gemini-bridge'),
            icon: AppIcons.link,
            title: 'Gemini 桥（自定义应用 / MCP）',
            subtitle: 'Gemini 能看你今天的验收点、交回执、加验收点；不能解锁、不能加额度',
            subtitleMaxLines: 2,
            chevron: true,
            onTap: () => _openPage(const GeminiBridgePage()),
          ),
          ListPageRow(
            icon: AppIcons.askAi,
            title: '立即唤起 Gemini Live',
            subtitle: '自动装入今日连续脉络开聊',
            onTap: _actionBusy ? null : () => unawaited(_launchGeminiQuick()),
          ),
        ],
      ),
      ListPageCard(
        title: '声音、形象与骰子',
        margin: _groupMargin,
        children: [
          ListPageRow(
            icon: AppIcons.voice,
            title: '声线（鱼丸 / 鲸澜 / 局官）',
            subtitle: '三个角色各自的音色与试听',
            chevron: true,
            onTap: () => _openPage(const VoiceSettingsPage()),
          ),
          ListPageRow(
            icon: AppIcons.pet,
            title: '双姐妹桌宠皮肤（.zip 导入）',
            subtitle: '导入鱼丸 yuwan-girl.zip / 鲸澜 jinglan-girl.zip，发球时自动换肤',
            subtitleMaxLines: 2,
            chevron: true,
            onTap: () => _openPage(const PetSettingsPage()),
          ),
          ListPageRow(
            icon: AppIcons.overlayBall,
            iconColor: shakeOn ? colors.success : null,
            title: '摇一摇弹六骰',
            subtitle: '亮屏时轻晃两下手机弹出六骰卡片；局页「摇一骰」随时可点',
            subtitleMaxLines: 2,
            badge: StatusChip(tone: shakeOn ? StatusTone.ok : StatusTone.idle, label: shakeOn ? '监听中' : '已暂停'),
            onTap: _actionBusy ? null : () => unawaited(_toggleShakeDice()),
          ),
        ],
      ),
    ];
  }

  /// 锁：落不落得下来、落多重、锁谁放谁。
  List<Widget> _lockGroup(BuildContext ctx) {
    final colors = DshColors.of(ctx);
    return [
      LockReadinessCard(channel: _channel),
      ListPageCard(
        title: '锁怎么落',
        margin: _groupMargin,
        children: [
          ListPageRow(
            key: const ValueKey('hub-health'),
            icon: AppIcons.diagnostics,
            title: '体检：哪一环断了',
            subtitle: '无障碍、保活、测试模式、笔记库、题库、默认验收、Gemini 桥……一页看全，点一下去修',
            subtitleMaxLines: 2,
            chevron: true,
            onTap: () => _openPage(const HealthPage()),
          ),
          if (_lockShadow != null)
            ListPageRow(
              icon: AppIcons.info,
              iconColor: _lockShadow! ? colors.info : colors.success,
              title: '测试模式（锁只提示，不真锁）',
              subtitle: _lockShadow!
                  ? '现在：锁不会落下，只弹「这里本来会锁」。测完关掉，锁才真的生效'
                  : '现在：锁真的生效。被锁出不来时，下拉通知栏点「紧急解除」',
              subtitleMaxLines: 3,
              trailing: Switch.adaptive(
                value: _lockShadow!,
                onChanged: _actionBusy ? null : (v) => unawaited(_setLockShadow(v)),
              ),
            ),
          ListPageRow(
            icon: AppIcons.verified,
            title: '统一裁决器执法（arbiterEnforce）',
            subtitle: '需先收集真机 arbiter-diff.jsonl 核验后再开启',
            subtitleMaxLines: 2,
            trailing: Switch.adaptive(
              key: const ValueKey('hub-arbiter-enforce-switch'),
              value: _arbiterEnforce ?? false,
              onChanged: _actionBusy ? null : (v) => unawaited(_setArbiterEnforce(v)),
            ),
          ),
        ],
      ),
      Padding(padding: const EdgeInsets.symmetric(horizontal: 16), child: _buildIntensityCard(ctx, _intensity)),
      ListPageCard(
        title: '锁谁、放谁',
        margin: _groupMargin,
        children: [
          ListPageRow(
            key: const ValueKey('hub-app-tiers'),
            icon: AppIcons.appRules,
            title: 'App 分类表',
            subtitle: '游戏 / 娱乐 / 双用途 / 工作：锁谁、放谁都按这一张表（原「娱乐锁机」名单已并入）',
            subtitleMaxLines: 2,
            chevron: true,
            onTap: () => _openPage(const AppTiersPage()),
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
    ];
  }

  /// 数据：Obsidian 挂载、导入记忆、给外部 AI 的交接。
  List<Widget> _dataGroup(BuildContext ctx) {
    final colors = DshColors.of(ctx);
    final granted = _phoneStorage?['granted'] == true;
    return [
      ListPageCard(
        title: '笔记库与记忆',
        margin: _groupMargin,
        children: [
          ListPageRow(
            icon: AppIcons.files,
            iconColor: granted ? colors.success : colors.warning,
            title: '手机目录与 Obsidian 自动挂载',
            subtitle: granted ? '已授权全盘读取，Obsidian 库自动检索就绪' : '未挂载 /sdcard 目录，点击一键授权并热重启让姐妹读到 Obsidian 笔记',
            subtitleMaxLines: 2,
            badge: StatusChip(tone: granted ? StatusTone.ok : StatusTone.warn, label: granted ? '已挂载' : '需授权'),
            onTap: _actionBusy ? null : () => unawaited(_enableStorage()),
          ),
          ListPageRow(
            icon: AppIcons.files,
            iconColor: Theme.of(ctx).colorScheme.primary,
            title: '生成记忆模板并导入 Obsidian 数据库',
            subtitle: '在 大肥鱼/导入/ 生成模板并去重写入记忆库，让鱼丸和鲸澜马上记住你',
            subtitleMaxLines: 2,
            value: '立即导入',
            onTap: _actionBusy ? null : () => unawaited(_importMemoryNow()),
          ),
          ListPageRow(
            icon: AppIcons.copy,
            title: '复制跨 AI 交接上下文（Cognitive OS）',
            subtitle: '汇总三铁律、思维模型、我.md 与六骰碎记，一键粘贴给 Gemini / 外部 AI',
            subtitleMaxLines: 2,
            value: '一键复制',
            onTap: _actionBusy ? null : () => unawaited(_copyHandoffPacket()),
          ),
        ],
      ),
    ];
  }

  /// 诊断：卡住了怎么办、出过什么事。
  List<Widget> _diagGroup(BuildContext ctx) {
    return [
      ListPageCard(
        title: '引擎',
        margin: _groupMargin,
        children: [
          ListPageRow(
            icon: AppIcons.restart,
            title: '一键热重启主动引擎',
            subtitle: '后台长期无响应或切库后立即拉起，并自动补发中断对话',
            subtitleMaxLines: 2,
            onTap: _actionBusy ? null : () => unawaited(_restartEngineNow()),
          ),
          ListPageRow(
            icon: AppIcons.agentLoop,
            title: _actionBusy ? '正在唤醒大肥鱼…' : '立即触发一次状态巡检',
            subtitle: '主动内核对齐屏幕与节奏',
            onTap: _actionBusy ? null : () => unawaited(_triggerTick()),
          ),
          ListPageRow(
            icon: AppIcons.queued,
            title: '时钟与队列',
            subtitle: 'Cron / 高频 / 循环番茄闹钟',
            value: _clockCount > 0 ? '$_clockCount 个时钟' : null,
            chevron: true,
            onTap: () => _openPage(BoutsWorkspacePage(channel: _channel)),
          ),
        ],
      ),
      ListPageCard(
        title: '记录',
        margin: _groupMargin,
        children: [
          ListPageRow(
            icon: AppIcons.history,
            title: '执行记录',
            subtitle: '锁机与主动提醒的每一次结果；长了可以清理',
            chevron: true,
            onTap: () => unawaited(RecordsPage.open(ctx)),
          ),
        ],
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final (tone, title, subtitle, footer) = _overviewMeta();
    final locked = _wallStatus?['locked'] == true;
    final granted = _phoneStorage?['granted'] == true;

    return AppPage.list(
      title: '大肥鱼中枢',
      subtitle: const Text('设置：两条鱼 · 锁 · 数据 · 诊断（日常的事在「局」和「墙」）'),
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
        ListPageCard(
          title: '设置',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          children: [
            ListPageRow(
              key: const ValueKey('hub-group-fish'),
              icon: AppIcons.team,
              iconColor: colors.info,
              title: '两条鱼',
              subtitle: '姐妹与记忆 · 会话换班 · 主动搭话 · Gemini · 声线 · 桌宠 · 摇一摇',
              subtitleMaxLines: 2,
              value: _proactive == null ? null : (_proactive!.config.enabled ? '主动开' : '主动关'),
              chevron: true,
              onTap: () => _openGroup('两条鱼', '她们是谁、记住什么、什么时候开口', _fishGroup),
            ),
            ListPageRow(
              key: const ValueKey('hub-group-lock'),
              icon: AppIcons.lock,
              iconColor: _lockShadow == false ? colors.success : colors.warning,
              title: '锁',
              subtitle: '锁机体检 · 测试模式 · 强度档 · App 分类表 · 哨兵',
              subtitleMaxLines: 2,
              value: _lockShadow == null ? null : (_lockShadow! ? '测试模式' : '真锁'),
              chevron: true,
              onTap: () => _openGroup('锁', '落不落得下来、落多重、锁谁放谁', _lockGroup),
            ),
            ListPageRow(
              key: const ValueKey('hub-group-data'),
              icon: AppIcons.files,
              iconColor: granted ? colors.success : null,
              title: '数据',
              subtitle: 'Obsidian 挂载 · 导入记忆 · 复制交接',
              value: _phoneStorage == null ? null : (granted ? '已挂载' : '需授权'),
              chevron: true,
              onTap: () => _openGroup('数据', '笔记库、记忆与交接', _dataGroup),
            ),
            ListPageRow(
              key: const ValueKey('hub-group-diag'),
              icon: AppIcons.restart,
              title: '诊断',
              subtitle: '重启引擎 · 状态巡检 · 时钟 · 执行记录',
              chevron: true,
              onTap: () => _openGroup('诊断', '卡住了怎么办、出过什么事', _diagGroup),
            ),
          ],
        ),
      ],
    );
  }

  static String _hhmm(int ms) {
    final d = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
  }
}
