import 'dart:async';

import 'package:flutter/material.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/progress_halo.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';
import 'horizon_dossier_page.dart';
import 'usage_analytics_page.dart';

/// DSH Go V5 · 醒目独立全屏「三层战略主线作战室」：
/// 1) 统一锁裁决器横幅 (P0–P6 Lock Arbiter 与唯一解锁出口)；
/// 2) 长期主航道（北极星 · 考公 / 就业，双向镜像 长期规划.md）；
/// 3) 中期战役看板（3~21 天阶段攻坚，进度条 + 一键「⚡ 拆一局推入今日」）；
/// 4) 今日作战映射队列（直达开干）。
class StrategyHorizonsPage extends StatefulWidget {
  const StrategyHorizonsPage({super.key, this.channel});

  final ProactiveChannel? channel;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel}) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => StrategyHorizonsPage(channel: channel),
        ),
      );

  @override
  State<StrategyHorizonsPage> createState() => _StrategyHorizonsPageState();
}

class _StrategyHorizonsPageState extends State<StrategyHorizonsPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();

  final _longCtrl = TextEditingController();
  final _mediumCtrl = TextEditingController();
  final _todayCtrl = TextEditingController();

  bool _loading = true;
  bool _busy = false;
  String? _banner;

  Map<String, dynamic> _today = const {};
  Map<String, dynamic> _horizons = const {};
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
    final results = await Future.wait<Object?>([
      _channel.todayStatus().catchError((_) => null),
      _channel.horizonsOp().catchError((_) => null),
      _channel.wallStatus().catchError((_) => null),
    ]);
    if (!mounted) return;
    setState(() {
      _loading = false;
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

  /// 2026-11-18 日历：给长期 / 中期目标设截止日（YYYY-MM-DD），验收器按它提醒和判「错过」。
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
    final due = '${picked.year.toString().padLeft(4, '0')}-${picked.month.toString().padLeft(2, '0')}-${picked.day.toString().padLeft(2, '0')}';
    setState(() => _busy = true);
    await _channel.horizonsOp({'action': 'upsert', 'horizon': horizon, 'id': item['id'], 'due': due});
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = '✓ 「${item['title']}」截止日定在 $due';
    });
  }

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

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);

    final longList = _asListOfMaps(_horizons['long']);
    final medList = _asListOfMaps(_horizons['medium']);
    final todayTasks = _asListOfMaps(_today['unifiedTasks'] ?? _today['tasks']);

    return AppPage.list(
      title: '三层战略作战室',
      subtitle: const Text('长期北极星（考公/就业）→ 中期战役 → 今日作战，同源联动不偏航'),
      actions: [
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
          GlassSurface(
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
          const SizedBox(height: 12),
        ],

        // 1) Unified Lock Arbiter Status Banner (P0-P6)
        _buildLockArbiterBanner(theme, colors),
        const SizedBox(height: 16),

        if (_loading && longList.isEmpty && medList.isEmpty && todayTasks.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 40),
            child: Center(child: CircularProgressIndicator()),
          )
        else ...[
          // 2) North Star Long-Term Horizon
          _buildLongHorizonSection(theme, colors, longList),
          const SizedBox(height: 16),

          // 3) Mid-Term Campaigns
          _buildMediumCampaignSection(theme, colors, medList),
          const SizedBox(height: 16),

          // 4) Today Tactical Mapping
          _buildTodayMappingSection(theme, colors, todayTasks),
        ],
      ],
    );
  }

  Widget _buildLockArbiterBanner(ThemeData theme, DshColors colors) {
    final arbiter = _asMap(_today['lockArbiter']);
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
      'P2' || 'P3' || 'P4' => StatusTone.error,
      _ => active ? StatusTone.warn : StatusTone.ok,
    };

    return OverviewCard(
      margin: EdgeInsets.zero,
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
      footer: OverviewFooter(
        tone: tone,
        text: '唯一解锁出口：$unlockHow',
      ),
    );
  }

  Widget _buildLongHorizonSection(ThemeData theme, DshColors colors, List<Map<String, dynamic>> longList) {
    final scheme = theme.colorScheme;
    final charts = _asMap(_today['charts']);
    final buckets = _asMap(charts['buckets']);
    final mainlineMin = _asInt(buckets['mainlineMin']) + _asInt(buckets['videoStudyMin']) + _asInt(buckets['geminiMin']);
    final tinkerAndFun = _asInt(buckets['toolTinkerMin']) + _asInt(buckets['funMin']);
    final total = (mainlineMin + tinkerAndFun);
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

    final nodeCount = item['nodes'] is List ? (item['nodes'] as List).length : 0;
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: GlassSurface(
        key: ValueKey('horizon-card-${item['id']}'),
        tier: GlassTier.pill,
        borderRadius: AppShape.radiusMd,
        // 点卡片进战役档案：预留节点、每个节点干过哪几局、断点、从节点开局。
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
                    icon: const Icon(Icons.event, size: 18),
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

  Widget _buildTodayMappingSection(ThemeData theme, DshColors colors, List<Map<String, dynamic>> tasks) {
    return ListPageCard(
      title: '今日作战队列（映射自统一计划队列）',
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
            subtitle: '可点击上方中期战役的「⚡ 拆一局推入今日」，或在此直接追加任务',
            subtitleMaxLines: 2,
          )
        else
          for (final t in tasks)
            ListPageRow(
              icon: AppIcons.running,
              iconColor: theme.colorScheme.primary,
              title: '${t['title'] ?? t['protocolId'] ?? '待开干'}',
              subtitle: '${_asInt(t['minutes'] ?? t['budget'], 45)} 分钟 · ${t['parentHorizonId'] != null ? '来自战役映射' : (t['source'] ?? '今日队列')}',
              trailing: FilledButton.tonal(
                onPressed: _busy ? null : () => unawaited(_launchTodayTask(t)),
                child: const Text('开干'),
              ),
            ),
      ],
    );
  }
}
