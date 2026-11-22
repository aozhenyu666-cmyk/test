import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../ui/common/capsule_chip.dart';
import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/progress_halo.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';
import 'horizon_dossier_page.dart';
import 'locks_balance_page.dart';
import 'seal_page.dart';
import 'today_cards.dart';
import 'usage_analytics_page.dart';

/// DSH Go · 三层战略作战室（3 页签：长期 · 北极星 / 中期 · 战役 / 今日 · 计划与验收锁）。
class StrategyHorizonsPage extends StatefulWidget {
  const StrategyHorizonsPage({
    super.key,
    this.channel,
    this.initialTab = 2,
  });

  final ProactiveChannel? channel;
  final int initialTab;

  static Future<void> open(
    BuildContext context, {
    ProactiveChannel? channel,
    int initialTab = 2,
  }) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => StrategyHorizonsPage(channel: channel, initialTab: initialTab),
        ),
      );

  @override
  State<StrategyHorizonsPage> createState() => _StrategyHorizonsPageState();
}

class _StrategyHorizonsPageState extends State<StrategyHorizonsPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late int _tab = widget.initialTab.clamp(0, 2);

  final _longCtrl = TextEditingController();
  final _mediumCtrl = TextEditingController();
  final _todayCtrl = TextEditingController();

  bool _loading = true;
  bool _busy = false;
  bool _inboxExpanded = false;
  String? _banner;
  int _refreshTick = 0;

  Map<String, dynamic> _today = const {};
  Map<String, dynamic> _horizons = const {};
  Map<String, dynamic>? _seals;
  Map<String, dynamic>? _storyline;
  Map<String, Object?>? _wall;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _longCtrl.dispose();
    _mediumCtrl.dispose();
    _todayCtrl.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (mounted && _today.isEmpty && _horizons.isEmpty) {
      setState(() => _loading = true);
    }
    unawaited(
      _channel.sealsOp(const {'action': 'locks_overview'}).then((r) {
        if (!mounted || r == null) return;
        setState(() => _seals = _asMap(r));
      }).catchError((_) {}),
    );
    unawaited(
      _channel.storylineOp(const {'action': 'get'}).then((r) {
        if (!mounted || r == null) return;
        setState(() => _storyline = _asMap(r));
      }).catchError((_) {}),
    );
    final results = await Future.wait<Object?>([
      _channel.todayStatus().catchError((_) => null),
      _channel.horizonsOp().catchError((_) => null),
      _channel.wallStatus().catchError((_) => null),
    ]);
    if (!mounted) return;
    setState(() {
      _loading = false;
      _refreshTick++;
      if (results[0] is Map) _today = _asMap(results[0]);
      if (results[1] is Map) {
        final hResp = _asMap(results[1]);
        _horizons = hResp['horizons'] is Map ? _asMap(hResp['horizons']) : hResp;
      } else if (_today['horizons'] is Map) {
        _horizons = _asMap(_today['horizons']);
      }
      _wall = results[2] is Map<String, Object?> ? results[2] as Map<String, Object?> : null;
    });
  }

  static Map<String, dynamic> _asMap(Object? raw) {
    if (raw is Map<String, dynamic>) return raw;
    if (raw is Map) {
      return {
        for (final e in raw.entries)
          if (e.key is String) e.key as String: e.value,
      };
    }
    return const {};
  }

  static List<Map<String, dynamic>> _asListOfMaps(Object? raw) {
    if (raw is! List) return const [];
    return [
      for (final item in raw)
        if (item is Map) _asMap(item),
    ];
  }

  static int _asInt(Object? raw, [int fallback = 0]) => raw is num ? raw.round() : fallback;

  Map<String, dynamic> _resolveOverview() {
    final fromSeals = _asMap(_seals?['locksOverview']);
    if (fromSeals.isNotEmpty) return fromSeals;
    return _asMap(_today['locksOverview']);
  }

  Future<void> _addHorizon(String horizon, TextEditingController ctrl) async {
    final title = ctrl.text.trim();
    if (title.isEmpty || _busy) return;
    ctrl.clear();
    setState(() => _busy = true);
    final res = await _channel.horizonsOp({
      'action': 'upsert',
      'horizon': horizon,
      'title': title,
      'targetSteps': horizon == 'long' ? 10 : 5,
    });
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res?['ok'] == true
          ? '✓ 已写入${horizon == 'long' ? '长期主航道（同步 Obsidian 长期规划.md）' : '中期战役'}：$title'
          : '保存失败：${res?['error'] ?? '伴侣引擎暂未应答'}';
    });
  }

  Future<void> _setDue(String horizon, Map<String, dynamic> item) async {
    if (_busy) return;
    final now = DateTime.now();
    final current = DateTime.tryParse('${item['due'] ?? ''}');
    final picked = await showDatePicker(
      context: context,
      initialDate: current ?? now.add(const Duration(days: 7)),
      firstDate: DateTime(now.year - 1),
      lastDate: DateTime(now.year + 5),
      helpText: '「${item['title'] ?? ''}」的截止日',
    );
    if (picked == null || !mounted) return;
    final due =
        '${picked.year.toString().padLeft(4, '0')}-${picked.month.toString().padLeft(2, '0')}-${picked.day.toString().padLeft(2, '0')}';
    setState(() => _busy = true);
    await _channel.horizonsOp({'action': 'upsert', 'horizon': horizon, 'id': item['id'], 'due': due});
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = '✓ 「${item['title']}」截止日定在 $due';
    });
  }

  Future<void> _pickHorizonStatus(String horizon, Map<String, dynamic> item) async {
    if (_busy) return;
    final picked = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
              child: Text('调整「${item['title'] ?? ''}」状态', style: Theme.of(ctx).textTheme.titleMedium),
            ),
            ListTile(
              leading: const Icon(AppIcons.running),
              title: const Text('进行中'),
              onTap: () => Navigator.of(ctx).pop('active'),
            ),
            ListTile(
              leading: const Icon(AppIcons.paused),
              title: const Text('搁置'),
              onTap: () => Navigator.of(ctx).pop('paused'),
            ),
            ListTile(
              leading: const Icon(AppIcons.archive),
              title: const Text('归档'),
              onTap: () => Navigator.of(ctx).pop('archived'),
            ),
            ListTile(
              leading: const Icon(AppIcons.success),
              title: const Text('已达成'),
              onTap: () => Navigator.of(ctx).pop('done'),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    if (picked == null || !mounted) return;
    setState(() => _busy = true);
    await _channel.horizonsOp({
      'action': 'upsert',
      'horizon': horizon,
      'id': item['id'],
      'status': picked,
    });
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = '✓ 已将「${item['title']}」状态更新为 ${_horizonStatusLabel(picked).$1}';
    });
  }

  static (String, StatusTone) _horizonStatusLabel(Object? raw) => switch ('${raw ?? 'active'}') {
        'done' => ('已达成', StatusTone.ok),
        'archived' => ('归档', StatusTone.idle),
        'paused' || 'shelved' => ('搁置', StatusTone.warn),
        _ => ('进行中', StatusTone.busy),
      };

  static String _dueText(Object? raw) {
    final due = DateTime.tryParse('${raw ?? ''}');
    if (due == null) return '';
    final today = DateTime.now();
    final left = DateTime(due.year, due.month, due.day).difference(DateTime(today.year, today.month, today.day)).inDays;
    final ymd = '${raw ?? ''}';
    if (left < 0) return '截止 $ymd · 已过 ${-left} 天';
    if (left == 0) return '截止 $ymd · 就是今天';
    return '截止 $ymd · 还剩 $left 天';
  }

  Future<void> _bumpStep(String horizon, Map<String, dynamic> item) async {
    if (_busy) return;
    setState(() => _busy = true);
    await _channel.horizonsOp({
      'action': 'upsert',
      'horizon': horizon,
      'id': item['id'],
      'bumpStep': true,
    });
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = '✓ 「${item['title']}」进度 +1';
    });
  }

  Future<void> _pushToToday(Map<String, dynamic> item) async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await _channel.horizonsOp({
      'action': 'push_to_today',
      'id': item['id'],
      'minutes': 45,
    });
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res?['ok'] == true
          ? '⚡ 已将「${item['title']}」拆一局推入今日作战队列'
          : '推入失败：${res?['error'] ?? '引擎暂未就绪'}';
    });
  }

  Future<void> _addTodayTask() async {
    final title = _todayCtrl.text.trim();
    if (title.isEmpty || _busy) return;
    _todayCtrl.clear();
    setState(() => _busy = true);
    final res = await _channel.todayTask(action: 'add', title: title, minutes: 45);
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res?['ok'] == true ? '✓ 已加入今日作战队列：$title' : '加入失败：${res?['text'] ?? '引擎未应答'}';
    });
  }

  Future<void> _launchTodayTask(Map<String, dynamic> task) async {
    if (_busy) return;
    final title = '${task['title'] ?? task['protocolId'] ?? '推进主线'}';
    final minutes = _asInt(task['minutes'] ?? task['budget'], 45).clamp(15, 180);
    setState(() => _busy = true);
    final res = await _channel.todayStart(
      goal: title,
      minutes: minutes,
      strict: true,
      protocolId: '${task['protocolId'] ?? 'execute'}',
    );
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res?['ok'] == true ? '🚀 已开启严格专注「$title」（${minutes}m）' : '启动失败：${res?['error'] ?? '引擎未就绪'}';
    });
  }

  Future<void> _applyEnergyPreset({required int minutes, required String label}) async {
    if (_busy) return;
    setState(() => _busy = true);
    final now = DateTime.now();
    final nowMin = now.hour * 60 + now.minute;
    final dueMinute = (nowMin + minutes + 30).clamp(60, 1435);
    final floor = (minutes / 2).round().clamp(10, minutes);
    final res = await _channel.sealsOp({
      'action': 'plan-day',
      'target': 'today',
      'by': 'user',
      'seals': [
        {
          'title': '今日精力定调（$label ${minutes}m）',
          'kind': 'bout_minutes',
          'stretch': minutes,
          'floor': floor,
          'startMinute': nowMin,
          'dueMinute': dueMinute,
          'estMinutes': minutes,
        },
      ],
    });
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res?['ok'] == true
          ? '✓ 已按「$label ${minutes}m」套算并落盘今日保底验收（保底 ${floor}m）'
          : '${res?['text'] ?? res?['error'] ?? '套算未成功，请检查引擎连接'}';
    });
  }

  Future<void> _sealTomorrowQuick() async {
    if (_busy) return;
    setState(() => _busy = true);
    final medList = _asListOfMaps(_horizons['medium']);
    final baseTitle = medList.isNotEmpty ? '${medList.first['title']} · 明日主线' : '明日主线首局保底';
    final res = await _channel.sealsOp({
      'action': 'plan-day',
      'target': 'tomorrow',
      'by': 'user',
      'seals': [
        {
          'title': baseTitle,
          'kind': 'bout_minutes',
          'stretch': 45,
          'floor': 25,
          'startMinute': 9 * 60 + 30,
          'dueMinute': 11 * 60,
          'estMinutes': 60,
        },
      ],
    });
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res?['ok'] == true
          ? '✓ 已封印明日首局目标「$baseTitle」（明日 09:30–11:00）'
          : '${res?['text'] ?? res?['error'] ?? '封印未成功，请检查引擎连接'}';
    });
  }

  Future<void> _openScheduleSlot(Map<String, dynamic> slot) async {
    if (_busy) return;
    final id = '${slot['id'] ?? ''}';
    setState(() => _busy = true);
    if (id.isNotEmpty && slot['sourceKind'] != 'task') {
      final res = await _channel.sealsOp({'action': 'open', 'id': id});
      await _refresh();
      if (!mounted) return;
      setState(() {
        _busy = false;
        _banner = res?['ok'] == true
            ? '${res?['toast'] ?? '🚀 已开启时段局「${slot['title']}」'}'
            : '${res?['text'] ?? res?['error'] ?? '未能开局'}';
      });
      return;
    }
    await _launchTodayTask(slot);
  }

  Future<void> _checkScheduleSlot(Map<String, dynamic> slot) async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await _channel.sealsOp({'action': 'check'});
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res?['ok'] == true ? '✓ 已按代码证据刷新验收判定' : '暂未连上伴侣引擎';
    });
  }

  String _buildStrategyBrief({String focusHorizon = 'all'}) {
    final longList = _asListOfMaps(_horizons['long']);
    final medList = _asListOfMaps(_horizons['medium']);
    final todayTasks = _asListOfMaps(_today['unifiedTasks'] ?? _today['tasks']);
    final promptBlock = '${_storyline?['promptBlock'] ?? ''}'.trim();

    final buf = StringBuffer()..writeln('【DSH 三层战略作战上下文】');
    if (focusHorizon == 'long' || focusHorizon == 'all') {
      buf.writeln('## 长期北极星（${longList.length} 条）');
      if (longList.isEmpty) {
        buf.writeln('- 暂无');
      } else {
        for (final l in longList) {
          buf.writeln('- ${l['title']}（${_horizonStatusLabel(l['status']).$1} · ${l['doneSteps'] ?? 0}/${l['targetSteps'] ?? 10}${_dueText(l['due']).isNotEmpty ? ' · ${_dueText(l['due'])}' : ''}）');
        }
      }
    }
    if (focusHorizon == 'medium' || focusHorizon == 'all') {
      buf.writeln('## 中期战役（${medList.length} 条）');
      if (medList.isEmpty) {
        buf.writeln('- 暂无');
      } else {
        for (final m in medList) {
          buf.writeln('- ${m['title']}（${m['doneSteps'] ?? 0}/${m['targetSteps'] ?? 5}${_dueText(m['due']).isNotEmpty ? ' · ${_dueText(m['due'])}' : ''}）');
        }
      }
    }
    buf.writeln('## 今日作战队列（${todayTasks.length} 项）');
    if (todayTasks.isEmpty) {
      buf.writeln('- 暂无');
    } else {
      for (final t in todayTasks) {
        buf.writeln('- ${t['title'] ?? t['protocolId']}（${_asInt(t['minutes'] ?? t['budget'], 45)}m）');
      }
    }
    if (promptBlock.isNotEmpty) {
      buf.writeln('## 今日连续脉络与收件箱');
      buf.writeln(promptBlock);
    }
    return buf.toString().trim();
  }

  Future<void> _copyStrategyBrief() async {
    final brief = _buildStrategyBrief();
    await Clipboard.setData(ClipboardData(text: brief));
    if (!mounted) return;
    setState(() => _banner = '✓ 已复制三层战略上下文简报到剪贴板');
  }

  Future<void> _launchGeminiStrategy({
    required String topic,
    String focusHorizon = 'all',
  }) async {
    if (_busy) return;
    setState(() => _busy = true);
    final brief = _buildStrategyBrief(focusHorizon: focusHorizon);
    final pullMode = '${_storyline?['pullMode'] ?? 'countdown_3s'}';
    await Clipboard.setData(ClipboardData(text: brief));
    final ok = await _channel.launchGeminiProactive(
      topic: topic,
      brief: brief,
      mode: pullMode,
      preferVoice: true,
    );
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = ok
          ? '⚡ 已唤起 Gemini 联调「$topic」（上下文已装入剪贴板）'
          : '未能拉起 Gemini（三层战略简报已复制到剪贴板，可手动切过去粘贴）';
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);

    final longList = _asListOfMaps(_horizons['long']);
    final medList = _asListOfMaps(_horizons['medium']);
    final todayTasks = _asListOfMaps(_today['unifiedTasks'] ?? _today['tasks']);
    final overview = _resolveOverview();

    return AppPage.list(
      title: '三层战略作战室',
      subtitle: const Text('长期北极星 · 中期战役 · 今日计划与验收锁'),
      actions: [
        GlassIconButton(
          icon: AppIcons.lock,
          tooltip: '锁与余额总控',
          onPressed: () => unawaited(LocksBalancePage.open(context, channel: _channel)),
        ),
        GlassIconButton(
          icon: AppIcons.chart,
          tooltip: '查看每日统计大屏',
          onPressed: () => unawaited(UsageAnalyticsPage.open(context, channel: _channel)),
        ),
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: '刷新三层战略',
          onPressed: () => unawaited(_refresh()),
        ),
      ],
      children: [
        if (_banner != null) ...[
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
                      child: Text(_banner!, style: theme.textTheme.bodySmall?.copyWith(color: colors.info)),
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
        ],

        // 统一锁裁决器横幅（三页签顶部常驻，点击直达锁与余额总控页）
        _buildLockArbiterBanner(theme, colors, overview),

        // 三页签切换：长期 / 中期 / 今日 & 验收锁
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
          child: SegmentedPill<int>(
            key: const ValueKey('strategy-tabs-pill'),
            segments: const [
              PillSegment(0, '长期 · 北极星'),
              PillSegment(1, '中期 · 战役'),
              PillSegment(2, '今日 · 计划与验收锁'),
            ],
            selected: _tab,
            onChanged: (next) => setState(() => _tab = next),
          ),
        ),

        if (_loading && longList.isEmpty && medList.isEmpty && todayTasks.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 40),
            child: Center(child: CircularProgressIndicator()),
          )
        else ...[
          if (_tab == 0)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 20),
              child: _buildLongHorizonSection(theme, colors, longList),
            ),
          if (_tab == 1)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 20),
              child: _buildMediumCampaignSection(theme, colors, medList),
            ),
          if (_tab == 2) ..._buildTodayWarRoomTab(theme, colors, overview, longList, medList, todayTasks),
        ],
      ],
    );
  }

  Widget _buildLockArbiterBanner(ThemeData theme, DshColors colors, Map<String, dynamic> overview) {
    final ovLock = _asMap(overview['activeLock']);
    final arbiter = ovLock.isNotEmpty ? ovLock : _asMap(_today['lockArbiter']);
    final hasArbiter = arbiter.isNotEmpty;
    final wallLocked = _wall?['locked'] == true;
    final active = hasArbiter ? (arbiter['active'] == true) : wallLocked;
    final priority = '${arbiter['priority'] ?? (wallLocked ? 'P3' : 'P6')}';
    final source = '${arbiter['source'] ?? (wallLocked ? 'BOUT_STRICT' : 'AUTONOMY_RELAXED')}';
    final label = '${arbiter['label'] ?? (wallLocked ? '严格锁护航中' : '放风 / 自主自治')}';
    final why = '${arbiter['why'] ?? (_wall?['why'] ?? '由统一锁裁决器 UnifiedLockArbiter 实时判定当前优先级')}';
    final unlockHow = '${arbiter['unlockHow'] ?? (wallLocked ? '完成本局验收 / 锁屏刷对 10 题临放 5 分钟' : '正常放行')}';

    final tone = switch (priority) {
      'P0' => StatusTone.busy,
      'P1' || 'P5' => StatusTone.ok,
      'P2' || 'P3' || 'P3.5' || 'P4' => StatusTone.error,
      _ => active ? StatusTone.warn : StatusTone.ok,
    };

    return OverviewCard(
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 14),
      leading: OverviewGlyph(
        active ? AppIcons.lockActive : AppIcons.unlock,
        tint: active ? 3 : 6,
      ),
      title: Row(
        children: [
          Expanded(
            child: Text('统一锁裁决 · $priority $label', maxLines: 1, overflow: TextOverflow.ellipsis),
          ),
          const SizedBox(width: 8),
          StatusChip(tone: tone, label: source),
        ],
      ),
      subtitle: Text(why, maxLines: 2, overflow: TextOverflow.ellipsis),
      onTap: () => unawaited(LocksBalancePage.open(context, channel: _channel)),
      footer: OverviewFooter(
        tone: tone,
        text: '唯一解锁出口：$unlockHow',
      ),
      onFooterTap: () => unawaited(LocksBalancePage.open(context, channel: _channel)),
    );
  }

  // ── Tab 0: 长期北极星 ──────────────────────────────────────────
  Widget _buildLongHorizonSection(ThemeData theme, DshColors colors, List<Map<String, dynamic>> longList) {
    final scheme = theme.colorScheme;
    final charts = _asMap(_today['charts']);
    final buckets = _asMap(charts['buckets']);
    final mainlineMin = _asInt(buckets['mainlineMin']) + _asInt(buckets['videoStudyMin']) + _asInt(buckets['geminiMin']);
    final tinkerAndFun = _asInt(buckets['toolTinkerMin']) + _asInt(buckets['funMin']);
    final total = mainlineMin + tinkerAndFun;
    final mainlinePct = total > 0 ? ((mainlineMin * 100) / total).round() : 100;
    final drifting = total > 0 && tinkerAndFun > mainlineMin;

    return GlassSurface(
      tier: GlassTier.card,
      borderRadius: AppShape.radiusLg,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                ProgressHalo(
                  value: mainlinePct / 100.0,
                  size: 46,
                  strokeWidth: 4,
                  color: drifting ? colors.warning : scheme.primary,
                  child: Text(
                    '$mainlinePct%',
                    style: AppType.tabular(theme.textTheme.labelSmall!.copyWith(fontWeight: FontWeight.w700)),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '长期主航道 · 考公 / 就业',
                        style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                      ),
                      Text(
                        drifting
                            ? '⚠️ 今日折腾+娱乐(${tinkerAndFun}m)已超过主航道投入(${mainlineMin}m)'
                            : '双向同步 Obsidian《长期规划.md》· 今日主航道占比 $mainlinePct%',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: drifting ? colors.warning : colors.textTertiary,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              key: const ValueKey('strategy-gemini-long'),
              style: OutlinedButton.styleFrom(shape: AppShape.card),
              onPressed: _busy
                  ? null
                  : () => unawaited(_launchGeminiStrategy(topic: '审视长期北极星大方向', focusHorizon: 'long')),
              icon: const Icon(AppIcons.askAi, size: 17),
              label: const Text('叫 Gemini 审视大方向'),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _longCtrl,
                    decoration: const InputDecoration(
                      hintText: '新增长期北极星目标（如：2026 省考上岸 / 拿到目标 Offer）',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                    onSubmitted: (_) => unawaited(_addHorizon('long', _longCtrl)),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton.tonal(
                  onPressed: _busy ? null : () => unawaited(_addHorizon('long', _longCtrl)),
                  child: const Text('立航道'),
                ),
              ],
            ),
            const SizedBox(height: 10),
            if (longList.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                child: Text(
                  '暂无长期主航道。立下 1~2 个终局方向，防止日常琐事和折腾工具吞噬整天。',
                  style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                ),
              )
            else
              for (final item in longList) _buildHorizonCard(theme, colors, 'long', item, isNorthStar: true),
          ],
        ),
      ),
    );
  }

  // ── Tab 1: 中期战役 ────────────────────────────────────────────
  Widget _buildMediumCampaignSection(ThemeData theme, DshColors colors, List<Map<String, dynamic>> medList) {
    return GlassSurface(
      tier: GlassTier.card,
      borderRadius: AppShape.radiusLg,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(AppIcons.goal, size: 18, color: colors.info),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '中期战役 · 3~21 天攻坚',
                    style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ),
                StatusChip(
                  tone: medList.isNotEmpty ? StatusTone.ok : StatusTone.idle,
                  label: '${medList.length} 场战役',
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              '不定期弹性硬仗（如：资料分析 200 题通关 / 简历投递 15 家），随时一键拆入今日作战。',
              style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
            ),
            const SizedBox(height: 10),
            OutlinedButton.icon(
              key: const ValueKey('strategy-gemini-medium'),
              style: OutlinedButton.styleFrom(shape: AppShape.card),
              onPressed: _busy
                  ? null
                  : () => unawaited(_launchGeminiStrategy(topic: '拆解中期战役本周小步', focusHorizon: 'medium')),
              icon: const Icon(AppIcons.askAi, size: 17),
              label: const Text('叫 Gemini 拆本周小步'),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _mediumCtrl,
                    decoration: const InputDecoration(
                      hintText: '新增中期战役（如：行测资料分析 200 题通关）',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                    onSubmitted: (_) => unawaited(_addHorizon('medium', _mediumCtrl)),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton.tonal(
                  onPressed: _busy ? null : () => unawaited(_addHorizon('medium', _mediumCtrl)),
                  child: const Text('建战役'),
                ),
              ],
            ),
            const SizedBox(height: 10),
            if (medList.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                child: Text(
                  '暂无中期战役。把考公/求职主航道拆成 3~21 天的阶段硬仗吧！',
                  style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                ),
              )
            else
              for (final item in medList) _buildHorizonCard(theme, colors, 'medium', item, isNorthStar: false),
          ],
        ),
      ),
    );
  }

  Widget _buildHorizonCard(
    ThemeData theme,
    DshColors colors,
    String horizon,
    Map<String, dynamic> item, {
    required bool isNorthStar,
  }) {
    final done = _asInt(item['doneSteps'], 0);
    final target = _asInt(item['targetSteps'], isNorthStar ? 10 : 5).clamp(1, 100);
    final ratio = (done / target).clamp(0.0, 1.0);
    final isDone = item['status'] == 'done' || done >= target;
    final barColor = isDone ? colors.success : (isNorthStar ? theme.colorScheme.primary : colors.info);
    final (statusLabel, statusTone) = _horizonStatusLabel(item['status']);
    final nodeCount = item['nodes'] is List ? (item['nodes'] as List).length : 0;

    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: GlassSurface(
        key: ValueKey('horizon-card-${item['id']}'),
        tier: GlassTier.pill,
        borderRadius: AppShape.radiusMd,
        onTap: () => unawaited(HorizonDossierPage.open(context, horizonId: '${item['id']}', channel: _channel)),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(
                    isDone ? AppIcons.success : (isNorthStar ? AppIcons.planMode : AppIcons.goal),
                    size: 18,
                    color: barColor,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '${item['title'] ?? ''}',
                      style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                    ),
                  ),
                  StatusChip(
                    tone: statusTone,
                    label: statusLabel,
                    onTap: () => unawaited(_pickHorizonStatus(horizon, item)),
                  ),
                  const SizedBox(width: 6),
                  Text(
                    nodeCount > 0 ? '$done/$target 节点 ›' : '$done/$target 阶 ›',
                    style: AppType.tabular(theme.textTheme.labelMedium!.copyWith(color: barColor, fontWeight: FontWeight.w700)),
                  ),
                ],
              ),
              if (_dueText(item['due']).isNotEmpty) ...[
                const SizedBox(height: 4),
                Text(
                  _dueText(item['due']),
                  style: theme.textTheme.bodySmall?.copyWith(color: colors.warning, fontWeight: FontWeight.w700),
                ),
              ],
              if ('${item['note'] ?? ''}'.isNotEmpty || '${item['spanHint'] ?? ''}'.isNotEmpty) ...[
                const SizedBox(height: 4),
                Text(
                  '${item['spanHint'] ?? ''}${'${item['note'] ?? ''}'.isNotEmpty ? ' · ${item['note']}' : ''}',
                  style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                ),
              ],
              const SizedBox(height: 8),
              ClipRRect(
                borderRadius: BorderRadius.circular(999),
                child: LinearProgressIndicator(
                  value: ratio,
                  minHeight: 7,
                  backgroundColor: theme.colorScheme.outlineVariant.withValues(alpha: 0.2),
                  valueColor: AlwaysStoppedAnimation<Color>(barColor),
                ),
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  IconButton(
                    tooltip: '设截止日',
                    visualDensity: VisualDensity.compact,
                    onPressed: _busy ? null : () => unawaited(_setDue(horizon, item)),
                    icon: const Icon(AppIcons.calendar, size: 18),
                  ),
                  OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(
                      shape: AppShape.card,
                      visualDensity: VisualDensity.compact,
                    ),
                    onPressed: _busy ? null : () => unawaited(_bumpStep(horizon, item)),
                    icon: const Icon(AppIcons.add, size: 15),
                    label: const Text('推进 +1'),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: FilledButton.tonalIcon(
                      style: FilledButton.styleFrom(
                        shape: AppShape.card,
                        visualDensity: VisualDensity.compact,
                      ),
                      onPressed: _busy ? null : () => unawaited(_pushToToday(item)),
                      icon: const Icon(AppIcons.running, size: 15),
                      label: const Text('⚡ 拆一局推入今日'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  // ── Tab 2: 今日 · 计划与验收锁（默认进入）──────────────────────────────
  List<Widget> _buildTodayWarRoomTab(
    ThemeData theme,
    DshColors colors,
    Map<String, dynamic> overview,
    List<Map<String, dynamic>> longList,
    List<Map<String, dynamic>> medList,
    List<Map<String, dynamic>> todayTasks,
  ) {
    final wallet = _asMap(overview['wallet']);
    final ledger = TimeLedgerView.fromWire(_today['timeLedger']);
    final availableMin = wallet.isNotEmpty ? _asInt(wallet['availableMin']) : (ledger?.funLeft ?? 0);
    final windowLabel = '${wallet['currentWindowLabel'] ?? '今日验收护航时段'}';
    final scheduleSlots = _resolveScheduleSlots(overview, todayTasks);
    final isEveningSealTime = DateTime.now().hour >= 21;

    return [
      // 1) 当前时间段验收锁快捷条 + 双总控入口
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
        child: GlassSurface(
          key: const ValueKey('strategy-today-lock-strip'),
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Icon(AppIcons.balance, size: 18, color: theme.colorScheme.primary),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        windowLabel,
                        style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                      ),
                    ),
                    Text(
                      '余额 ${availableMin}m',
                      style: AppType.tabular(theme.textTheme.labelLarge!.copyWith(color: theme.colorScheme.primary)),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton.icon(
                        key: const ValueKey('strategy-open-locks'),
                        style: OutlinedButton.styleFrom(shape: AppShape.card),
                        onPressed: () => unawaited(LocksBalancePage.open(context, channel: _channel)),
                        icon: const Icon(AppIcons.lock, size: 16),
                        label: const Text('锁与余额总控'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: OutlinedButton.icon(
                        key: const ValueKey('strategy-open-analytics'),
                        style: OutlinedButton.styleFrom(shape: AppShape.card),
                        onPressed: () => unawaited(UsageAnalyticsPage.open(context, channel: _channel)),
                        icon: const Icon(AppIcons.chart, size: 16),
                        label: const Text('App 使用时长'),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),

      // 2) 早/晚 10 秒计划仪式整合（NodMenuCard + 三档精力快选 + 晚间封印明日）
      NodMenuCard(
        key: const ValueKey('strategy-nod-slot'),
        channel: _channel,
        refreshTick: _refreshTick,
        onLocked: () => unawaited(_refresh()),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: _buildEnergyRitualCard(theme, colors, isEveningSealTime: isEveningSealTime),
      ),

      // 3) 当下每个时间段的计划 & 验收点时间轴
      _buildScheduleTimelineCard(theme, colors, scheduleSlots),

      // 4) 今日作战队列 & 快速添加
      _buildTodayMappingSection(theme, colors, todayTasks),

      // 5) 长期 / 中期战略主线对齐摘要（不偏航 + 一键推入今日）
      if (longList.isNotEmpty || medList.isNotEmpty)
        _buildTodayAlignmentCard(theme, colors, longList, medList),

      // 6) 四部门速记收件箱摘要（可展开）
      _buildInboxCollapseCard(theme, colors),

      // 7) Gemini 触发器战略联调卡
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
        child: _buildGeminiStrategyCard(theme, colors),
      ),
    ];
  }

  Widget _buildEnergyRitualCard(ThemeData theme, DshColors colors, {required bool isEveningSealTime}) {
    return GlassSurface(
      key: const ValueKey('strategy-energy-card'),
      tier: GlassTier.card,
      borderRadius: AppShape.radiusLg,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(AppIcons.today, size: 18, color: colors.info),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '10 秒精力定调（按精力套算今日保底）',
                    style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                CapsuleChoiceChip(
                  key: const ValueKey('energy-preset-60'),
                  icon: AppIcons.running,
                  label: '满血 60m',
                  selected: false,
                  onSelected: () => unawaited(_applyEnergyPreset(minutes: 60, label: '满血')),
                ),
                CapsuleChoiceChip(
                  key: const ValueKey('energy-preset-30'),
                  icon: AppIcons.verified,
                  label: '轻保底 30m',
                  selected: false,
                  onSelected: () => unawaited(_applyEnergyPreset(minutes: 30, label: '轻保底')),
                ),
                CapsuleChoiceChip(
                  key: const ValueKey('energy-preset-15'),
                  icon: AppIcons.tip,
                  label: '恢复日 15m',
                  selected: false,
                  onSelected: () => unawaited(_applyEnergyPreset(minutes: 15, label: '恢复日')),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Container(
              key: const ValueKey('strategy-evening-seal'),
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: isEveningSealTime
                    ? colors.warningContainer.withValues(alpha: 0.65)
                    : theme.colorScheme.surfaceContainerLow,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Row(
                children: [
                  Icon(
                    AppIcons.scheduled,
                    size: 17,
                    color: isEveningSealTime ? colors.warning : colors.textTertiary,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      isEveningSealTime
                          ? '晚间 21:00 后 · 一键封印明日首局目标（23:00 前不打折）'
                          : '明日目标预封印（23:00 前封不打折）',
                      style: theme.textTheme.bodySmall?.copyWith(
                        fontWeight: isEveningSealTime ? FontWeight.w700 : FontWeight.w500,
                      ),
                    ),
                  ),
                  TextButton(
                    key: const ValueKey('strategy-seal-tomorrow-btn'),
                    onPressed: _busy ? null : () => unawaited(_sealTomorrowQuick()),
                    child: const Text('一键封明日'),
                  ),
                  TextButton(
                    onPressed: () => unawaited(SealPage.open(context, channel: _channel)),
                    child: const Text('细调'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  List<Map<String, dynamic>> _resolveScheduleSlots(
    Map<String, dynamic> overview,
    List<Map<String, dynamic>> todayTasks,
  ) {
    final now = DateTime.now();
    final nowMin = now.hour * 60 + now.minute;
    final baseSlots = _asListOfMaps(overview['scheduleSlots']);
    final merged = <Map<String, dynamic>>[...baseSlots];

    if (merged.isEmpty) {
      final todayStr = '${_seals?['today'] ?? ''}';
      final sealItems = _asListOfMaps(_seals?['items']);
      final todays = sealItems.where((x) => todayStr.isEmpty || x['day'] == todayStr).toList();
      for (final s in todays) {
        final start = _asInt(s['startMinute'], -1);
        final due = _asInt(s['dueMinute'], 18 * 60);
        final status = '${s['status'] ?? 'open'}';
        final isCur =
            status == 'open' && ((start >= 0 && nowMin >= start && nowMin < due) || (start < 0 && nowMin < due));
        merged.add({
          'id': s['id'],
          'title': s['title'],
          'kind': s['kind'] ?? 'quiz',
          'startMinute': start,
          'dueMinute': due,
          'startHm': start >= 0 ? sealHm(start) : '',
          'dueHm': sealHm(due),
          'stretch': s['stretch'] ?? 1,
          'floor': s['floor'] ?? 1,
          'progress': s['actual'] ?? 0,
          'status': status,
          'isCurrentSlot': isCur,
          'sourceKind': 'seal',
        });
      }
    }

    // Also merge any today task that carries an explicit time block (`startMinute` or `minute`).
    final seenTitles = {for (final x in merged) '${x['title'] ?? ''}'};
    for (final t in todayTasks) {
      final startRaw = t['startMinute'] ?? t['minute'];
      if (startRaw is! num) continue;
      final title = '${t['title'] ?? t['protocolId'] ?? ''}'.trim();
      if (title.isEmpty || seenTitles.contains(title)) continue;
      final start = startRaw.round().clamp(0, 1438);
      final dur = _asInt(t['minutes'] ?? t['budget'], 45).clamp(10, 240);
      final due = _asInt(t['dueMinute'], (start + dur).clamp(start + 1, 1439));
      merged.add({
        'id': t['id'] ?? 'task-$start',
        'title': title,
        'kind': 'bout_minutes',
        'startMinute': start,
        'dueMinute': due,
        'startHm': sealHm(start),
        'dueHm': sealHm(due),
        'stretch': dur,
        'floor': (dur / 2).round().clamp(10, dur),
        'progress': 0,
        'status': 'open',
        'isCurrentSlot': nowMin >= start && nowMin < due,
        'sourceKind': 'task',
        'minutes': dur,
        'protocolId': t['protocolId'] ?? 'execute',
      });
    }

    merged.sort((a, b) => _asInt(a['startMinute'], _asInt(a['dueMinute'], 1440))
        .compareTo(_asInt(b['startMinute'], _asInt(b['dueMinute'], 1440))));
    return merged;
  }

  Widget _buildScheduleTimelineCard(
    ThemeData theme,
    DshColors colors,
    List<Map<String, dynamic>> slots,
  ) {
    final scheme = theme.colorScheme;
    return ListPageCard(
      key: const ValueKey('strategy-schedule-card'),
      title: '今日时间段计划 & 验收点时间轴',
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      children: slots.isEmpty
          ? [
              ListPageRow(
                icon: AppIcons.time,
                title: '今日暂无时间段验收点',
                subtitle: '可使用上方 10 秒精力定调一键落盘，或前往验收点时间轴添加',
                subtitleMaxLines: 2,
                chevron: true,
                onTap: () => unawaited(SealPage.open(context, channel: _channel)),
              ),
            ]
          : [
              for (final slot in slots)
                () {
                  final isCurrent = slot['isCurrentSlot'] == true;
                  final status = '${slot['status'] ?? 'open'}';
                  final passed = status == 'floor' || status == 'stretch' || status == 'waived';
                  final startHm = '${slot['startHm'] ?? ''}';
                  final dueHm = '${slot['dueHm'] ?? ''}';
                  final span = startHm.isNotEmpty && dueHm.isNotEmpty
                      ? '$startHm–$dueHm'
                      : (dueHm.isNotEmpty ? '$dueHm 前' : '今日');
                  final progress = _asInt(slot['progress']);
                  final stretch = _asInt(slot['stretch'], 1);
                  final floor = _asInt(slot['floor'], 1);

                  return Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    child: Container(
                      key: ValueKey('schedule-slot-${slot['id']}'),
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: isCurrent
                            ? scheme.primaryContainer.withValues(alpha: 0.45)
                            : theme.colorScheme.surfaceContainerLow,
                        borderRadius: BorderRadius.circular(14),
                        border: isCurrent ? Border.all(color: scheme.primary.withValues(alpha: 0.5)) : null,
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Row(
                            children: [
                              Icon(
                                passed
                                    ? AppIcons.success
                                    : (isCurrent ? AppIcons.running : AppIcons.scheduled),
                                size: 17,
                                color: passed ? colors.success : (isCurrent ? scheme.primary : colors.textTertiary),
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  '${slot['title'] ?? '验收点'}',
                                  style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                                ),
                              ),
                              if (isCurrent)
                                const StatusChip(tone: StatusTone.busy, label: '此刻进行中')
                              else
                                StatusChip(
                                  tone: passed
                                      ? StatusTone.ok
                                      : (status == 'below' ? StatusTone.error : StatusTone.idle),
                                  label: switch (status) {
                                    'stretch' => '过拉伸',
                                    'floor' => '过保底',
                                    'waived' => '已豁免',
                                    'below' => '逾期锁着',
                                    _ => '待推进',
                                  },
                                ),
                            ],
                          ),
                          const SizedBox(height: 4),
                          Text(
                            '$span · 进度 $progress/$stretch · 保底 $floor',
                            style: AppType.tabular(theme.textTheme.bodySmall!.copyWith(color: colors.textTertiary)),
                          ),
                          if (!passed) ...[
                            const SizedBox(height: 8),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.end,
                              children: [
                                OutlinedButton(
                                  style: OutlinedButton.styleFrom(
                                    visualDensity: VisualDensity.compact,
                                    shape: AppShape.card,
                                  ),
                                  onPressed: _busy ? null : () => unawaited(_checkScheduleSlot(slot)),
                                  child: const Text('去提交验收'),
                                ),
                                const SizedBox(width: 8),
                                FilledButton.tonal(
                                  style: FilledButton.styleFrom(
                                    visualDensity: VisualDensity.compact,
                                    shape: AppShape.card,
                                  ),
                                  onPressed: _busy ? null : () => unawaited(_openScheduleSlot(slot)),
                                  child: const Text('开局'),
                                ),
                              ],
                            ),
                          ],
                        ],
                      ),
                    ),
                  );
                }(),
            ],
    );
  }

  Widget _buildTodayMappingSection(ThemeData theme, DshColors colors, List<Map<String, dynamic>> tasks) {
    return ListPageCard(
      title: '今日作战队列（映射自统一计划队列）',
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _todayCtrl,
                  decoration: const InputDecoration(
                    hintText: '直接追加一条今日作战任务（45m）',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                  onSubmitted: (_) => unawaited(_addTodayTask()),
                ),
              ),
              const SizedBox(width: 8),
              FilledButton.tonal(
                onPressed: _busy ? null : () => unawaited(_addTodayTask()),
                child: const Text('加入'),
              ),
            ],
          ),
        ),
        if (tasks.isEmpty)
          const ListPageRow(
            icon: AppIcons.queued,
            title: '今日作战队列空着',
            subtitle: '可从中期战役点击「⚡ 拆一局推入今日」，或在此直接追加任务',
            subtitleMaxLines: 2,
          )
        else
          for (final t in tasks)
            ListPageRow(
              icon: AppIcons.running,
              iconColor: theme.colorScheme.primary,
              title: '${t['title'] ?? t['protocolId'] ?? '待开干'}',
              subtitle:
                  '${_asInt(t['minutes'] ?? t['budget'], 45)} 分钟 · ${t['parentHorizonId'] != null ? '来自战役映射' : (t['source'] ?? '今日队列')}',
              trailing: FilledButton.tonal(
                onPressed: _busy ? null : () => unawaited(_launchTodayTask(t)),
                child: const Text('开干'),
              ),
            ),
      ],
    );
  }

  Widget _buildTodayAlignmentCard(
    ThemeData theme,
    DshColors colors,
    List<Map<String, dynamic>> longList,
    List<Map<String, dynamic>> medList,
  ) {
    final activeLong = longList.where((x) => x['status'] != 'archived').take(2).toList();
    final activeMed = medList.where((x) => x['status'] != 'archived').take(2).toList();
    return ListPageCard(
      key: const ValueKey('strategy-alignment-card'),
      title: '主线对齐 · 北极星与战役（不偏航）',
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      children: [
        for (final item in activeLong)
          ListPageRow(
            icon: AppIcons.planMode,
            iconColor: theme.colorScheme.primary,
            title: '${item['title'] ?? ''}',
            subtitle: '长期北极星 · ${item['doneSteps'] ?? 0}/${item['targetSteps'] ?? 10} 阶',
            onTap: () => setState(() => _tab = 0),
          ),
        for (final item in activeMed)
          ListPageRow(
            icon: AppIcons.goal,
            iconColor: colors.info,
            title: '${item['title'] ?? ''}',
            subtitle: '中期战役 · ${item['doneSteps'] ?? 0}/${item['targetSteps'] ?? 5} 阶',
            trailing: FilledButton.tonal(
              onPressed: _busy ? null : () => unawaited(_pushToToday(item)),
              child: const Text('⚡ 拆一局推入今日'),
            ),
          ),
      ],
    );
  }

  Widget _buildInboxCollapseCard(ThemeData theme, DshColors colors) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ListPageCard(
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          children: [
            ListPageRow(
              key: const ValueKey('strategy-inbox-toggle'),
              icon: AppIcons.write,
              iconColor: colors.info,
              title: '四部门速记收件箱摘要',
              subtitle: _inboxExpanded ? '点击收起速记收件箱' : '点击展开：随手记下卡壳 / 灵感，打包交 Gemini',
              trailing: Icon(_inboxExpanded ? AppIcons.collapse : AppIcons.expand, size: 18, color: colors.textTertiary),
              onTap: () => setState(() => _inboxExpanded = !_inboxExpanded),
            ),
          ],
        ),
        if (_inboxExpanded)
          InboxCaptureCard(
            key: const ValueKey('strategy-inbox-slot'),
            channel: _channel,
            refreshTick: _refreshTick,
          ),
      ],
    );
  }

  Widget _buildGeminiStrategyCard(ThemeData theme, DshColors colors) {
    return GlassSurface(
      key: const ValueKey('strategy-gemini-card'),
      tier: GlassTier.card,
      borderRadius: AppShape.radiusLg,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(AppIcons.askAi, size: 18, color: colors.info),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Gemini 战略联调（汇总三层 + 收件箱上下文）',
                    style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              '自动汇总长期北极星、中期战役、今日时间轴与收件箱脉络，一键复制或拉起 Gemini 共脑排计划、剖析卡点。',
              style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                OutlinedButton.icon(
                  key: const ValueKey('strategy-gemini-copy'),
                  style: OutlinedButton.styleFrom(shape: AppShape.card),
                  onPressed: () => unawaited(_copyStrategyBrief()),
                  icon: const Icon(AppIcons.copy, size: 16),
                  label: const Text('复制简报'),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: FilledButton.icon(
                    key: const ValueKey('strategy-gemini-launch'),
                    style: FilledButton.styleFrom(shape: AppShape.card),
                    onPressed: _busy
                        ? null
                        : () => unawaited(_launchGeminiStrategy(topic: '三层战略排计划与析卡点')),
                    icon: const Icon(AppIcons.running, size: 16),
                    label: const Text('⚡ 唤起 Gemini 共脑'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
