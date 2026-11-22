import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../ui/common/capsule_chip.dart';
import '../../ui/common/list_page_row.dart';
import '../../ui/common/progress_halo.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';

enum AnalyticsTab { overview, history, gemini, report }

/// DSH Go V5 · 每日统计、90 天长期趋势、Gemini 共脑时长与晚间 AI 可视化总报告大屏。
class UsageAnalyticsPage extends StatefulWidget {
  const UsageAnalyticsPage({
    super.key,
    this.channel,
    this.initialTab = AnalyticsTab.overview,
  });

  final ProactiveChannel? channel;
  final AnalyticsTab initialTab;

  static Future<void> open(
    BuildContext context, {
    ProactiveChannel? channel,
    AnalyticsTab initialTab = AnalyticsTab.overview,
  }) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => UsageAnalyticsPage(channel: channel, initialTab: initialTab),
        ),
      );

  @override
  State<UsageAnalyticsPage> createState() => _UsageAnalyticsPageState();
}

class _UsageAnalyticsPageState extends State<UsageAnalyticsPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late AnalyticsTab _tab = widget.initialTab;
  late String _day = _formatDay(DateTime.now());

  int _rangeDays = 7;
  bool _loading = true;
  bool _busy = false;
  String? _banner;

  Map<String, dynamic> _overview = const {};
  Map<String, dynamic> _storyline = const {};
  int? _selectedSliceIndex;
  Map<String, dynamic>? _selectedHeatCell;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  static String _formatDay(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  static DateTime _parseDay(String day) {
    final parts = day.split('-');
    if (parts.length == 3) {
      final y = int.tryParse(parts[0]);
      final m = int.tryParse(parts[1]);
      final d = int.tryParse(parts[2]);
      if (y != null && m != null && d != null) {
        return DateTime(y, m, d);
      }
    }
    return DateTime.now();
  }

  String get _todayStr => _formatDay(DateTime.now());

  Future<void> _refresh() async {
    if (mounted) setState(() => _loading = true);
    final results = await Future.wait<Map<String, dynamic>?>([
      _channel.analyticsOp({
        'action': 'overview',
        'day': _day,
        'rangeDays': _rangeDays,
      }).catchError((_) => null),
      _channel.storylineOp({'action': 'get', 'day': _day}).catchError((_) => null),
    ]);
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (results[0] != null) _overview = results[0]!;
      if (results[1] != null) _storyline = results[1]!;
      _selectedSliceIndex = null;
      _selectedHeatCell = null;
    });
  }

  void _shiftDay(int delta) {
    final next = _parseDay(_day).add(Duration(days: delta));
    final nextStr = _formatDay(next);
    if (nextStr.compareTo(_todayStr) > 0) return;
    setState(() => _day = nextStr);
    unawaited(_refresh());
  }

  void _jumpToday() {
    if (_day == _todayStr) return;
    setState(() => _day = _todayStr);
    unawaited(_refresh());
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

  static String _fmtDuration(int min) {
    if (min <= 0) return '0m';
    if (min < 60) return '${min}m';
    final h = min ~/ 60;
    final m = min % 60;
    return m == 0 ? '${h}h' : '${h}h ${m}m';
  }

  static String _fmtClockMin(int minuteOfDay) {
    final m = minuteOfDay.clamp(0, 1440);
    final hh = (m ~/ 60).toString().padLeft(2, '0');
    final mm = (m % 60).toString().padLeft(2, '0');
    return '$hh:$mm';
  }

  static String _deltaLabel(int delta) {
    if (delta == 0) return '持平昨日';
    final arrow = delta > 0 ? '↑' : '↓';
    final abs = delta.abs();
    return '$arrow ${abs}m vs 昨日';
  }

  Future<void> _setPullMode(String mode) async {
    setState(() {
      _busy = true;
      _storyline = {..._storyline, 'pullMode': mode};
    });
    final res = await _channel.storylineOp({'action': 'set_gemini_pull_mode', 'pullMode': mode});
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (res != null) _storyline = {..._storyline, ...res};
      _banner = switch (mode) {
        'instant' => '已切为「零秒铁血强拉」：越权直拉 Gemini Live，无延后按钮',
        'gentle' => '已切为「仅温和提醒」：仅在消息通知提示，不主动跳屏',
        _ => '已切为「3秒浮窗自动接通」：悬浮桥接窗倒计时 3s 合法豁免拉起',
      };
    });
  }

  Future<void> _triggerGeminiProactive() async {
    if (_busy) return;
    setState(() => _busy = true);
    final storyObj = _asMap(_storyline['storyline']);
    final mode = '${_storyline['pullMode'] ?? storyObj['pullMode'] ?? 'countdown_3s'}';
    final promptBlock = '${_storyline['promptBlock'] ?? ''}'.trim();
    final entries = _asListOfMaps(_storyline['entries'] ?? storyObj['entries'] ?? _storyline['timeline']);
    final brief = promptBlock.isNotEmpty
        ? promptBlock
        : (entries.isNotEmpty
            ? '【今日连续脉络】${entries.take(5).map((e) => '${e['hm'] ?? ''} ${e['summary'] ?? ''}').join('；')}'
            : '请结合我今日主线进度，进行 5 分钟高强度苏格拉底式复盘与推演。');
    final ok = await _channel.launchGeminiProactive(
      topic: '今日共脑复盘与破局',
      brief: brief,
      mode: mode,
      preferVoice: true,
    );
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = ok
          ? '⚡ 已触发 Gemini 主动唤起通道，今日脉络简报已装入剪贴板'
          : '未能拉起 Gemini（请确认已授予悬浮窗与无障碍权限）';
    });
  }

  Future<void> _generateEveningReport() async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await _channel.analyticsOp({'action': 'generate_report', 'day': _day});
    if (!mounted) return;
    final overviewMap = res?['overview'] is Map ? _asMap(res!['overview']) : null;
    final reportRaw = res?['eveningReport'] ?? res?['report'] ?? overviewMap?['eveningReport'];
    if (res != null && (reportRaw is Map || overviewMap != null)) {
      setState(() {
        _busy = false;
        if (overviewMap != null) _overview = {..._overview, ...overviewMap};
        if (reportRaw is Map) _overview = {..._overview, 'eveningReport': reportRaw};
        _banner = '✨ 已生成 $_day 晚间 AI 可视化总报告并同步 Obsidian 每日报告/';
      });
    } else {
      await _refresh();
      if (!mounted) return;
      setState(() {
        _busy = false;
        _banner = res?['ok'] == true ? '✨ 已刷新今日晚间总报告' : '暂未连上伴侣引擎，无法生成报告';
      });
    }
  }

  Future<void> _copyReportMarkdown(Map<String, dynamic> report) async {
    final md = '${report['markdown'] ?? ''}'.trim();
    final verdict = DailyVerdictView.fromWire(
      report['verdict'] ?? _overview['verdict'],
      scoreRaw: report['score'] ?? _overview['score'],
      scoreLabelRaw: report['scoreLabel'] ?? _overview['scoreLabel'],
    );
    final label = verdict?.scoreLabel ?? '投入分';
    final score = verdict?.cappedScore ?? _asInt(report['score'], 0);
    final head = verdict != null ? '硬日判：${verdict.tag} · $label $score分' : '$label $score分';
    final fallback = '# ${report['day'] ?? _day} 每日复盘总报（$head）\n\n${report['headline'] ?? ''}';
    await Clipboard.setData(ClipboardData(text: md.isNotEmpty ? md : fallback));
    if (!mounted) return;
    setState(() => _banner = '✓ 已复制报告 Markdown（含 Mermaid 图表）到剪贴板');
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);

    return AppPage.list(
      title: '每日统计与复盘中心',
      subtitle: const Text('24h 切片 · 90 天长期趋势 · Gemini 共脑 · 晚间 AI 总报'),
      actions: [
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: '刷新统计数据',
          onPressed: () => unawaited(_refresh()),
        ),
      ],
      children: [
        _buildDateStepper(theme, colors),
        const SizedBox(height: 12),
        SegmentedPill<AnalyticsTab>(
          segments: const [
            PillSegment(AnalyticsTab.overview, '今日全景'),
            PillSegment(AnalyticsTab.history, '长期趋势'),
            PillSegment(AnalyticsTab.gemini, 'Gemini 共脑'),
            PillSegment(AnalyticsTab.report, '晚间 AI 总报'),
          ],
          selected: _tab,
          onChanged: (next) => setState(() => _tab = next),
        ),
        const SizedBox(height: 14),
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
        if (_loading && _overview.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 48),
            child: Center(child: CircularProgressIndicator()),
          )
        else ...[
          if (_tab == AnalyticsTab.overview) _buildOverviewTab(theme, colors),
          if (_tab == AnalyticsTab.history) _buildHistoryTab(theme, colors),
          if (_tab == AnalyticsTab.gemini) _buildGeminiTab(theme, colors),
          if (_tab == AnalyticsTab.report) _buildReportTab(theme, colors),
        ],
      ],
    );
  }

  Widget _buildDateStepper(ThemeData theme, DshColors colors) {
    final isToday = _day == _todayStr;
    return GlassSurface(
      tier: GlassTier.card,
      borderRadius: AppShape.radiusLg,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        child: Row(
          children: [
            IconButton(
              tooltip: '前一天',
              icon: const Icon(AppIcons.pagePrevious, size: 20),
              onPressed: () => _shiftDay(-1),
            ),
            Expanded(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    _day,
                    style: AppType.tabular(theme.textTheme.titleMedium!.copyWith(fontWeight: FontWeight.w700)),
                  ),
                  Text(
                    isToday ? '今天 · 实时遥测追踪中' : '历史日档案回看',
                    style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                  ),
                ],
              ),
            ),
            CapsuleChoiceChip(
              label: '今天',
              icon: AppIcons.today,
              selected: isToday,
              onSelected: _jumpToday,
            ),
            IconButton(
              tooltip: '后一天',
              icon: Icon(
                AppIcons.pageNext,
                size: 20,
                color: isToday ? colors.textDisabled : null,
              ),
              onPressed: isToday ? null : () => _shiftDay(1),
            ),
          ],
        ),
      ),
    );
  }

  // ── Tab 1: 今日全景 ─────────────────────────────────────────
  Widget _buildOverviewTab(ThemeData theme, DshColors colors) {
    final scheme = theme.colorScheme;
    final buckets = _asMap(_overview['buckets']);
    final deltas = _asMap(_overview['deltas']);
    final geminiStats = _asMap(_overview['geminiStats']);
    final mainlineMin = _asInt(buckets['mainlineMin']);
    final videoMin = _asInt(buckets['videoStudyMin']);
    final geminiMin = _asInt(buckets['geminiMin'], _asInt(geminiStats['minutesToday']));
    final tinkerMin = _asInt(buckets['toolTinkerMin']);
    final funMin = _asInt(buckets['funMin']);
    final tinkerWarn = buckets['tinkerWarning'] == true;
    final computedTotal = mainlineMin + videoMin + geminiMin + tinkerMin + funMin;
    final totalMin = math.max(_asInt(buckets['totalMin']), computedTotal);
    final effectiveMin = mainlineMin + videoMin + geminiMin;
    final effectivePct = totalMin > 0 ? ((effectiveMin * 100) / totalMin).round() : 0;

    final slices = _asListOfMaps(_overview['timeline24h']);
    final topApps = _asListOfMaps(_overview['topApps']);
    final arbiter = _asMap(_overview['lockArbiter']);
    final timeLedger = TimeLedgerView.fromWire(_overview['timeLedger']);
    final report = _asMap(_overview['eveningReport']);
    final verdict = DailyVerdictView.fromWire(
      _overview['verdict'] ?? report['verdict'],
      scoreRaw: _overview['score'] ?? report['score'],
      scoreLabelRaw: _overview['scoreLabel'] ?? report['scoreLabel'],
    );

    final segments = <_DonutSlice>[
      _DonutSlice('主线攻坚', mainlineMin.toDouble(), colors.success),
      _DonutSlice('Gemini 共脑', geminiMin.toDouble(), colors.info),
      _DonutSlice('视频提炼', videoMin.toDouble(), scheme.primary),
      _DonutSlice('工具折腾', tinkerMin.toDouble(), colors.warning),
      _DonutSlice('纯娱乐', funMin.toDouble(), colors.danger),
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (verdict != null) ...[
          HardVerdictCard(verdict: verdict),
          const SizedBox(height: 14),
        ],
        // 1) Donut Ring + 4 KPI Cards
        GlassSurface(
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Icon(AppIcons.chart, size: 18, color: scheme.primary),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '今日时间分配全景',
                        style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                      ),
                    ),
                    if (tinkerWarn) const StatusChip(tone: StatusTone.warn, label: '折腾工具偏高'),
                  ],
                ),
                const SizedBox(height: 16),
                Center(
                  child: SizedBox(
                    width: 172,
                    height: 172,
                    child: CustomPaint(
                      painter: _UsageDonutPainter(
                        slices: segments,
                        trackColor: scheme.outlineVariant.withValues(alpha: 0.25),
                      ),
                      child: Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              _fmtDuration(totalMin),
                              style: AppType.tabular(theme.textTheme.headlineSmall!.copyWith(fontWeight: FontWeight.w700)),
                            ),
                            Text(
                              '有效主线 $effectivePct%',
                              style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: _KpiTile(
                        title: '主线攻坚',
                        value: _fmtDuration(mainlineMin),
                        deltaText: _deltaLabel(_asInt(deltas['mainlineDelta'])),
                        accent: colors.success,
                        icon: AppIcons.running,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: _KpiTile(
                        title: 'Gemini 沟通',
                        value: _fmtDuration(geminiMin),
                        deltaText: _deltaLabel(_asInt(deltas['geminiDelta'])),
                        accent: colors.info,
                        icon: AppIcons.askAi,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: _KpiTile(
                        title: '视频提炼',
                        value: _fmtDuration(videoMin),
                        deltaText: '工具折腾 ${_fmtDuration(tinkerMin)}',
                        accent: scheme.primary,
                        icon: AppIcons.video,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: _KpiTile(
                        title: '纯娱乐',
                        value: _fmtDuration(funMin),
                        deltaText: _deltaLabel(_asInt(deltas['funDelta'])),
                        accent: colors.danger,
                        icon: AppIcons.blocked,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),
        if (timeLedger != null) ...[
          TimeLedgerSummaryCard(ledger: timeLedger),
          const SizedBox(height: 14),
        ],

        // 2) 24-Hour Timeline Ribbon (06:00–24:00)
        GlassSurface(
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Icon(AppIcons.time, size: 18, color: scheme.primary),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '24 小时时间轴（06:00 – 24:00）',
                        style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                      ),
                    ),
                    Text(
                      '${slices.length} 段切片',
                      style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                _TimelineRibbon24h(
                  slices: slices,
                  selectedIndex: _selectedSliceIndex,
                  colorForBucket: (bucket) => _bucketColor(bucket, colors, scheme),
                  onSelectSlice: (idx) => setState(() => _selectedSliceIndex = idx),
                ),
                const SizedBox(height: 6),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    for (final tick in const ['06:00', '10:30', '15:00', '19:30', '24:00'])
                      Text(tick, style: AppType.tabular(theme.textTheme.labelSmall!.copyWith(color: colors.textTertiary))),
                  ],
                ),
                if (_selectedSliceIndex != null && _selectedSliceIndex! < slices.length) ...[
                  const SizedBox(height: 10),
                  _buildSliceDetail(slices[_selectedSliceIndex!], theme, colors),
                ] else if (slices.isEmpty) ...[
                  const SizedBox(height: 10),
                  Text(
                    '暂无当日时段切片（开启无障碍与引擎运行后会自动按分钟切片记录）。',
                    style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                  ),
                ],
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),

        // 3) Top Apps & Arbiter / Quiz Breakdown
        ListPageCard(
          title: '应用用时排行与护栏明细',
          children: [
            if (arbiter.isNotEmpty)
              ListPageRow(
                icon: arbiter['active'] == true ? AppIcons.lockActive : AppIcons.unlock,
                iconColor: arbiter['active'] == true ? colors.warning : colors.success,
                title: '当前锁裁决：${arbiter['label'] ?? arbiter['source'] ?? '正常'}',
                subtitle: '${arbiter['why'] ?? ''}${arbiter['unlockHow'] != null ? ' · 解锁：${arbiter['unlockHow']}' : ''}',
                subtitleMaxLines: 2,
                badge: StatusChip(
                  tone: arbiter['active'] == true ? StatusTone.warn : StatusTone.ok,
                  label: '${arbiter['priority'] ?? 'P6'}',
                ),
              ),
            if (topApps.isEmpty)
              const ListPageRow(
                icon: AppIcons.empty,
                title: '今日暂无 App 排行明细',
                subtitle: '连上伴侣引擎并累积使用后将展示每个 App 的分钟数与桶归属',
              )
            else
              for (final app in topApps.take(10))
                ListPageRow(
                  icon: app['fun'] == true ? AppIcons.blocked : AppIcons.running,
                  iconColor: app['fun'] == true ? colors.danger : _bucketColor('${app['bucket'] ?? ''}', colors, scheme),
                  title: '${app['label'] ?? app['app'] ?? '未知应用'}',
                  subtitle: _bucketName('${app['bucket'] ?? (app['fun'] == true ? 'fun' : 'mainline')}'),
                  value: '${_asInt(app['minutes'] ?? app['min'])} 分钟',
                ),
          ],
        ),
      ],
    );
  }

  Widget _buildSliceDetail(Map<String, dynamic> s, ThemeData theme, DshColors colors) {
    final start = _asInt(s['startMin']);
    final end = _asInt(s['endMin'], start + _asInt(s['durationMin']));
    final dur = _asInt(s['durationMin'], math.max(1, end - start));
    final locked = s['locked'] == true;
    return GlassSurface(
      tier: GlassTier.pill,
      borderRadius: AppShape.radiusMd,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Row(
          children: [
            Icon(
              locked ? AppIcons.lock : AppIcons.time,
              size: 16,
              color: locked ? colors.danger : theme.colorScheme.primary,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                '${_fmtClockMin(start)}–${_fmtClockMin(end)} · ${s['label'] ?? s['app'] ?? '活动'} · ${dur}m（${_bucketName('${s['bucket'] ?? ''}')}）${locked ? ' · 触发拦截' : ''}',
                style: theme.textTheme.bodySmall,
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ── Tab 2: 长期趋势 ─────────────────────────────────────────
  Widget _buildHistoryTab(ThemeData theme, DshColors colors) {
    final scheme = theme.colorScheme;
    final series = _asListOfMaps(_overview['historySeries']);
    final heatmap = _asListOfMaps(_overview['heatmap90d']);

    var sumFocus = 0;
    var sumGemini = 0;
    var sumFun = 0;
    for (final d in series) {
      sumFocus += _asInt(d['focusMin'] ?? d['mainlineMin']);
      sumGemini += _asInt(d['geminiMin']);
      sumFun += _asInt(d['funMin']);
    }
    final avgFocus = series.isNotEmpty ? (sumFocus / series.length).round() : 0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SegmentedPill<int>(
          height: 34,
          segments: const [
            PillSegment(7, '7天'),
            PillSegment(30, '30天'),
            PillSegment(90, '90天'),
          ],
          selected: _rangeDays,
          onChanged: (days) {
            setState(() => _rangeDays = days);
            unawaited(_refresh());
          },
        ),
        const SizedBox(height: 12),
        GlassSurface(
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Icon(AppIcons.sessionDetails, size: 18, color: scheme.primary),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '近 $_rangeDays 天有效主线 vs 娱乐对比柱图',
                        style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                      ),
                    ),
                    Text(
                      '日均专注 ${_fmtDuration(avgFocus)}',
                      style: theme.textTheme.labelMedium?.copyWith(color: colors.success, fontWeight: FontWeight.w600),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                Wrap(
                  spacing: 12,
                  runSpacing: 4,
                  children: [
                    Text('累计主线 ${_fmtDuration(sumFocus)}', style: theme.textTheme.bodySmall?.copyWith(color: colors.success)),
                    Text('累计 Gemini ${_fmtDuration(sumGemini)}', style: theme.textTheme.bodySmall?.copyWith(color: colors.info)),
                    Text('累计娱乐 ${_fmtDuration(sumFun)}', style: theme.textTheme.bodySmall?.copyWith(color: colors.danger)),
                  ],
                ),
                const SizedBox(height: 14),
                if (series.isEmpty)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 24),
                    child: Text(
                      '暂无多日归档序列（跨日后自动累计 365 天历史档案）。',
                      style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                      textAlign: TextAlign.center,
                    ),
                  )
                else
                  _StackedHistoryChart(
                    series: series,
                    focusColor: colors.success,
                    geminiColor: colors.info,
                    funColor: colors.danger,
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),
        GlassSurface(
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Icon(AppIcons.lastMonth, size: 18, color: scheme.primary),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '90 天专注热力图',
                        style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                      ),
                    ),
                    Text(
                      '${heatmap.length} 天记录',
                      style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                _Heatmap90dGrid(
                  cells: heatmap,
                  baseColor: colors.success,
                  emptyColor: scheme.outlineVariant.withValues(alpha: 0.22),
                  onTapCell: (cell) => setState(() => _selectedHeatCell = cell),
                ),
                const SizedBox(height: 10),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    Text('少 ', style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
                    for (var lvl = 0; lvl <= 4; lvl++) ...[
                      Container(
                        width: 11,
                        height: 11,
                        margin: const EdgeInsets.symmetric(horizontal: 2),
                        decoration: BoxDecoration(
                          color: lvl == 0
                              ? scheme.outlineVariant.withValues(alpha: 0.22)
                              : colors.success.withValues(alpha: 0.22 + lvl * 0.19),
                          borderRadius: BorderRadius.circular(3),
                        ),
                      ),
                    ],
                    Text(' 多', style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
                  ],
                ),
                if (_selectedHeatCell != null) ...[
                  const SizedBox(height: 10),
                  GlassSurface(
                    tier: GlassTier.pill,
                    borderRadius: AppShape.radiusMd,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                      child: Text(
                        '${_selectedHeatCell!['day'] ?? ''} · 专注 ${_asInt(_selectedHeatCell!['focusMin'])}m · Gemini ${_asInt(_selectedHeatCell!['geminiMin'])}m · 娱乐 ${_asInt(_selectedHeatCell!['funMin'])}m',
                        style: theme.textTheme.bodySmall,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }

  // ── Tab 3: Gemini 共脑 ──────────────────────────────────────
  Widget _buildGeminiTab(ThemeData theme, DshColors colors) {
    final scheme = theme.colorScheme;
    final geminiStats = _asMap(_overview['geminiStats']);
    final minToday = _asInt(geminiStats['minutesToday']);
    final sessions = _asInt(geminiStats['sessionsCount']);
    final recapsCount = _asInt(geminiStats['recapsCount']);
    final recentRecaps = _asListOfMaps(geminiStats['recentRecaps']);
    final storyObj = _asMap(_storyline['storyline']);
    final pullMode = '${_storyline['pullMode'] ?? storyObj['pullMode'] ?? 'countdown_3s'}';
    final storyEntries = _asListOfMaps(_storyline['entries'] ?? storyObj['entries'] ?? _storyline['timeline']);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        GlassSurface(
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Icon(AppIcons.askAi, size: 20, color: colors.info),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '今日与 Gemini 沟通 $minToday 分钟 · $sessions 场',
                        style: AppType.tabular(theme.textTheme.titleMedium!.copyWith(fontWeight: FontWeight.w700)),
                      ),
                    ),
                    StatusChip(
                      tone: minToday > 0 ? StatusTone.ok : StatusTone.idle,
                      label: '复盘 $recapsCount 条',
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  '聚合 Android 实测前台时长 + 语音连麦舱 + DSH 深度对话，自动随今日脉络带入下一场共脑。',
                  style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                ),
                const SizedBox(height: 14),
                Text('主动唤起策略（无障碍 + 悬浮焦点桥接合法豁免 BAL）', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                const SizedBox(height: 8),
                SegmentedPill<String>(
                  height: 36,
                  segments: const [
                    PillSegment('countdown_3s', '3秒浮窗自动接通'),
                    PillSegment('instant', '零秒铁血强拉'),
                    PillSegment('gentle', '仅温和提醒'),
                  ],
                  selected: const ['countdown_3s', 'instant', 'gentle'].contains(pullMode) ? pullMode : 'countdown_3s',
                  onChanged: _busy ? null : (v) => unawaited(_setPullMode(v)),
                ),
                const SizedBox(height: 14),
                FilledButton.icon(
                  style: FilledButton.styleFrom(
                    shape: AppShape.card,
                    minimumSize: const Size.fromHeight(46),
                  ),
                  onPressed: _busy ? null : () => unawaited(_triggerGeminiProactive()),
                  icon: const Icon(AppIcons.running, size: 18),
                  label: const Text('⚡ 立即越权唤起 Gemini Live（自动带入今日脉络）'),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),
        ListPageCard(
          title: '近期 Gemini【复盘】沉淀',
          children: recentRecaps.isEmpty
              ? const [
                  ListPageRow(
                    icon: AppIcons.document,
                    title: '今天还没有 Gemini 复盘记录',
                    subtitle: '在 Gemini 连麦后说出或贴回【复盘】结论，这里会自动沉淀并汇入晚间总报',
                    subtitleMaxLines: 2,
                  ),
                ]
              : [
                  for (final r in recentRecaps.take(8))
                    ListPageRow(
                      icon: AppIcons.verified,
                      iconColor: colors.info,
                      title: '${r['topic'] ?? r['title'] ?? '共脑复盘'}',
                      subtitle: '${r['summary'] ?? r['text'] ?? ''}',
                      subtitleMaxLines: 3,
                      meta: '${r['hm'] ?? ''}',
                    ),
                ],
        ),
        const SizedBox(height: 14),
        ListPageCard(
          title: '同日连续叙事流（IntradayStoryline · 姐妹与 Gemini 共享）',
          children: storyEntries.isEmpty
              ? const [
                  ListPageRow(
                    icon: AppIcons.trajectory,
                    title: '今日连续叙事流等待首条锚点',
                    subtitle: '晨间立项、开局过关、走神拦截与 Gemini 连麦会自动串联为一条完整脉络',
                    subtitleMaxLines: 2,
                  ),
                ]
              : [
                  for (final e in storyEntries)
                    ListPageRow(
                      icon: _storyIcon('${e['kind'] ?? ''}'),
                      iconColor: scheme.primary,
                      title: '${e['summary'] ?? ''}',
                      titleMaxLines: 2,
                      subtitle: '${e['actor'] ?? '系统'} · ${e['kind'] ?? ''}',
                      meta: '${e['hm'] ?? ''}',
                    ),
                ],
        ),
      ],
    );
  }

  // ── Tab 4: 晚间 AI 总报 ──────────────────────────────────────
  Widget _buildReportTab(ThemeData theme, DshColors colors) {
    final scheme = theme.colorScheme;
    final report = _asMap(_overview['eveningReport']);
    final verdict = DailyVerdictView.fromWire(
      report['verdict'] ?? _overview['verdict'],
      scoreRaw: report['score'] ?? _overview['score'],
      scoreLabelRaw: report['scoreLabel'] ?? _overview['scoreLabel'],
    );
    final hasReport = report.isNotEmpty && ('${report['headline'] ?? ''}'.isNotEmpty || report['score'] != null);
    final score = verdict != null ? verdict.cappedScore : _asInt(report['score'], 0).clamp(0, 100);
    final scoreLabel = verdict?.scoreLabel ??
        (report['scoreLabel'] is String && (report['scoreLabel'] as String).trim().isNotEmpty
            ? (report['scoreLabel'] as String).trim()
            : '投入分');
    final headline = '${report['headline'] ?? '今日尚未生成晚间 AI 可视化总报告'}';
    final completedTasks = _asListOfMaps(report['completedTasks']);
    final causal = [
      for (final x in (report['causalHighlights'] is List ? report['causalHighlights'] as List : const []))
        if (x is Map) '${x['title'] ?? x['text'] ?? x['summary'] ?? ''}' else '$x',
    ].where((s) => s.trim().isNotEmpty).toList();
    final tomorrowDraft = [
      for (final x in (report['tomorrowDraft'] is List ? report['tomorrowDraft'] as List : const []))
        if (x is Map) '${x['title'] ?? x['text'] ?? ''}' else '$x',
    ].where((s) => s.trim().isNotEmpty).toList();

    final buckets = _asMap(_overview['buckets']);
    final mainlineMin = _asInt(buckets['mainlineMin']);
    final geminiMin = _asInt(buckets['geminiMin']);
    final videoMin = _asInt(buckets['videoStudyMin']);
    final tinkerMin = _asInt(buckets['toolTinkerMin']);
    final funMin = _asInt(buckets['funMin']);
    final maxBar = [mainlineMin, geminiMin, videoMin, tinkerMin, funMin, 1].reduce(math.max);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (verdict != null) ...[
          HardVerdictCard(verdict: verdict),
          const SizedBox(height: 14),
        ],
        GlassSurface(
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
                      value: hasReport ? score / 100.0 : 0.0,
                      size: 74,
                      strokeWidth: 5,
                      color: score >= 75 ? colors.success : (score >= 50 ? scheme.primary : colors.warning),
                      child: Text(
                        hasReport ? '$score' : '--',
                        style: AppType.tabular(theme.textTheme.titleLarge!.copyWith(fontWeight: FontWeight.w700)),
                      ),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '$_day 晚间 AI 总评 · $scoreLabel',
                            style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            headline,
                            style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                            maxLines: 3,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                _CategoryBarRow(label: '主线攻坚', minutes: mainlineMin, maxMinutes: maxBar, color: colors.success),
                const SizedBox(height: 6),
                _CategoryBarRow(label: 'Gemini 共脑', minutes: geminiMin, maxMinutes: maxBar, color: colors.info),
                const SizedBox(height: 6),
                _CategoryBarRow(label: '视频提炼', minutes: videoMin, maxMinutes: maxBar, color: scheme.primary),
                const SizedBox(height: 6),
                _CategoryBarRow(label: '工具折腾', minutes: tinkerMin, maxMinutes: maxBar, color: colors.warning),
                const SizedBox(height: 6),
                _CategoryBarRow(label: '纯娱乐', minutes: funMin, maxMinutes: maxBar, color: colors.danger),
                const SizedBox(height: 14),
                Row(
                  children: [
                    Expanded(
                      child: FilledButton.icon(
                        style: FilledButton.styleFrom(shape: AppShape.card),
                        onPressed: _busy ? null : () => unawaited(_generateEveningReport()),
                        icon: const Icon(AppIcons.featured, size: 18),
                        label: const Text('✨ 立即生成 / 刷新今日总报告'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(shape: AppShape.card),
                  onPressed: () => unawaited(_copyReportMarkdown(report)),
                  icon: const Icon(AppIcons.copy, size: 18),
                  label: const Text('复制报告 Markdown'),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),
        ListPageCard(
          title: '今日完成战果与因果沉淀',
          children: [
            if (completedTasks.isEmpty && causal.isEmpty)
              const ListPageRow(
                icon: AppIcons.tip,
                title: '点击上方按钮生成本日结构化总报告',
                subtitle: '每日 21:30 亦会自动生成并写入 Obsidian《每日报告/YYYY-MM-DD.md》',
                subtitleMaxLines: 2,
              ),
            for (final task in completedTasks)
              ListPageRow(
                icon: task['passed'] == false ? AppIcons.warning : AppIcons.success,
                iconColor: task['passed'] == false ? colors.warning : colors.success,
                title: '${task['title'] ?? task['name'] ?? '已完成项'}',
                value: _asInt(task['minutes']) > 0 ? '${_asInt(task['minutes'])}m' : null,
              ),
            for (final line in causal)
              ListPageRow(
                icon: AppIcons.tip,
                iconColor: scheme.primary,
                title: line,
                titleMaxLines: 3,
              ),
          ],
        ),
        if (tomorrowDraft.isNotEmpty) ...[
          const SizedBox(height: 14),
          ListPageCard(
            title: '明日三件套草案',
            children: [
              for (final item in tomorrowDraft.take(3))
                ListPageRow(
                  icon: AppIcons.goal,
                  iconColor: colors.info,
                  title: item,
                  titleMaxLines: 2,
                  trailing: TextButton(
                    onPressed: _busy
                        ? null
                        : () async {
                            await _channel.todayTask(action: 'add', title: item, minutes: 45);
                            if (mounted) {
                              setState(() => _banner = '✓ 已将「$item」加入作战清单');
                            }
                          },
                    child: const Text('采纳'),
                  ),
                ),
            ],
          ),
        ],
      ],
    );
  }

  static Color _bucketColor(String bucket, DshColors colors, ColorScheme scheme) => switch (bucket) {
        'mainline' => colors.success,
        'gemini_cobrain' || 'gemini' => colors.info,
        'video_study' || 'video' => scheme.primary,
        'tool_tinker' || 'tinker' => colors.warning,
        'fun' => colors.danger,
        _ => scheme.primary.withValues(alpha: 0.7),
      };

  static String _bucketName(String bucket) => switch (bucket) {
        'mainline' => '主线攻坚',
        'gemini_cobrain' || 'gemini' => 'Gemini 共脑',
        'video_study' || 'video' => '视频提炼',
        'tool_tinker' || 'tinker' => '工具折腾',
        'fun' => '纯娱乐',
        _ => '日常活动',
      };

  static IconData _storyIcon(String kind) => switch (kind) {
        'gemini' || 'gemini_start' || 'gemini_recap' => AppIcons.askAi,
        'lock' || 'drift' => AppIcons.lock,
        'quiz' || 'quiz_unlock' => AppIcons.verified,
        'bout' || 'contract' => AppIcons.running,
        _ => AppIcons.trajectory,
      };
}

class _DonutSlice {
  const _DonutSlice(this.label, this.value, this.color);
  final String label;
  final double value;
  final Color color;
}

class _UsageDonutPainter extends CustomPainter {
  _UsageDonutPainter({required this.slices, required this.trackColor});

  final List<_DonutSlice> slices;
  final Color trackColor;

  @override
  void paint(Canvas canvas, Size size) {
    final total = slices.fold<double>(0, (acc, s) => acc + math.max(0, s.value));
    const stroke = 16.0;
    final rect = Offset.zero & size;
    final ring = rect.deflate(stroke / 2 + 4);
    final trackPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..color = trackColor;
    canvas.drawArc(ring, 0, math.pi * 2, false, trackPaint);
    if (total <= 0) return;

    var start = -math.pi / 2;
    const gap = 0.035;
    for (final s in slices) {
      if (s.value <= 0) continue;
      final rawSweep = (s.value / total) * math.pi * 2;
      final sweep = math.max(0.04, rawSweep - (slices.where((e) => e.value > 0).length > 1 ? gap : 0));
      final p = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.round
        ..color = s.color;
      canvas.drawArc(ring, start, sweep, false, p);
      start += rawSweep;
    }
  }

  @override
  bool shouldRepaint(_UsageDonutPainter old) => old.slices != slices || old.trackColor != trackColor;
}

class _KpiTile extends StatelessWidget {
  const _KpiTile({
    required this.title,
    required this.value,
    required this.deltaText,
    required this.accent,
    required this.icon,
  });

  final String title;
  final String value;
  final String deltaText;
  final Color accent;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    return GlassSurface(
      tier: GlassTier.pill,
      borderRadius: AppShape.radiusMd,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, size: 15, color: accent),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              value,
              style: AppType.tabular(theme.textTheme.titleMedium!.copyWith(fontWeight: FontWeight.w700, color: accent)),
            ),
            const SizedBox(height: 2),
            Text(
              deltaText,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
            ),
          ],
        ),
      ),
    );
  }
}

class _TimelineRibbon24h extends StatelessWidget {
  const _TimelineRibbon24h({
    required this.slices,
    required this.selectedIndex,
    required this.colorForBucket,
    required this.onSelectSlice,
  });

  final List<Map<String, dynamic>> slices;
  final int? selectedIndex;
  final Color Function(String bucket) colorForBucket;
  final ValueChanged<int> onSelectSlice;

  static const _dayStartMin = 360.0; // 06:00
  static const _dayEndMin = 1440.0; // 24:00
  static const _span = _dayEndMin - _dayStartMin;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        return ClipRRect(
          borderRadius: BorderRadius.circular(10),
          child: Container(
            height: 28,
            width: width,
            color: scheme.outlineVariant.withValues(alpha: 0.2),
            child: Stack(
              children: [
                for (var i = 0; i < slices.length; i++)
                  Builder(builder: (_) {
                    final s = slices[i];
                    final rawStart = (s['startMin'] as num?)?.toDouble() ?? _dayStartMin;
                    final dur = (s['durationMin'] as num?)?.toDouble() ?? 5.0;
                    final rawEnd = (s['endMin'] as num?)?.toDouble() ?? (rawStart + dur);
                    final start = rawStart.clamp(_dayStartMin, _dayEndMin);
                    final end = rawEnd.clamp(start + 1.0, _dayEndMin);
                    final left = ((start - _dayStartMin) / _span) * width;
                    final w = math.max(3.0, ((end - start) / _span) * width);
                    final selected = selectedIndex == i;
                    final color = colorForBucket('${s['bucket'] ?? ''}');
                    return Positioned(
                      left: left,
                      top: selected ? 1 : 3,
                      bottom: selected ? 1 : 3,
                      width: math.min(w, width - left),
                      child: GestureDetector(
                        onTap: () => onSelectSlice(i),
                        child: Container(
                          decoration: BoxDecoration(
                            color: color,
                            borderRadius: BorderRadius.circular(4),
                            border: selected ? Border.all(color: scheme.onSurface, width: 1.5) : null,
                          ),
                        ),
                      ),
                    );
                  }),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _StackedHistoryChart extends StatelessWidget {
  const _StackedHistoryChart({
    required this.series,
    required this.focusColor,
    required this.geminiColor,
    required this.funColor,
  });

  final List<Map<String, dynamic>> series;
  final Color focusColor;
  final Color geminiColor;
  final Color funColor;

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    var maxTotal = 60.0;
    for (final item in series) {
      final f = (item['focusMin'] as num? ?? item['mainlineMin'] as num? ?? 0).toDouble();
      final g = (item['geminiMin'] as num? ?? 0).toDouble();
      final u = (item['funMin'] as num? ?? 0).toDouble();
      maxTotal = math.max(maxTotal, f + g + u);
    }

    Widget buildBars(double barWidth) {
      return SizedBox(
        height: 112,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            for (final item in series)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 2),
                child: SizedBox(
                  width: barWidth,
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      Builder(builder: (_) {
                        final f = (item['focusMin'] as num? ?? item['mainlineMin'] as num? ?? 0).toDouble();
                        final g = (item['geminiMin'] as num? ?? 0).toDouble();
                        final u = (item['funMin'] as num? ?? 0).toDouble();
                        const maxH = 82.0;
                        final fh = (f / maxTotal) * maxH;
                        final gh = (g / maxTotal) * maxH;
                        final uh = (u / maxTotal) * maxH;
                        return ClipRRect(
                          borderRadius: BorderRadius.circular(4),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              if (uh > 0.5) Container(height: math.max(2, uh), color: funColor),
                              if (gh > 0.5) Container(height: math.max(2, gh), color: geminiColor),
                              Container(
                                height: math.max(3, fh),
                                color: focusColor.withValues(alpha: f > 0 ? 0.9 : 0.2),
                              ),
                            ],
                          ),
                        );
                      }),
                      const SizedBox(height: 4),
                      Text(
                        '${item['day'] ?? ''}'.length >= 5
                            ? '${item['day']}'.substring('${item['day']}'.length - 5)
                            : '${item['day'] ?? ''}',
                        maxLines: 1,
                        overflow: TextOverflow.clip,
                        style: TextStyle(fontSize: 9, color: colors.textTertiary),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      );
    }

    if (series.length <= 10) {
      return LayoutBuilder(
        builder: (context, c) {
          final w = ((c.maxWidth - series.length * 4) / math.max(1, series.length)).clamp(16.0, 44.0);
          return buildBars(w);
        },
      );
    }
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: buildBars(series.length > 35 ? 14.0 : 22.0),
    );
  }
}

class _Heatmap90dGrid extends StatelessWidget {
  const _Heatmap90dGrid({
    required this.cells,
    required this.baseColor,
    required this.emptyColor,
    required this.onTapCell,
  });

  final List<Map<String, dynamic>> cells;
  final Color baseColor;
  final Color emptyColor;
  final ValueChanged<Map<String, dynamic>> onTapCell;

  @override
  Widget build(BuildContext context) {
    final normalized = List<Map<String, dynamic>>.generate(
      90,
      (i) => i < cells.length ? cells[i] : const {'day': '', 'level': 0, 'focusMin': 0},
    );
    final columns = <List<Map<String, dynamic>>>[];
    for (var i = 0; i < normalized.length; i += 7) {
      columns.add(normalized.sublist(i, math.min(i + 7, normalized.length)));
    }
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final col in columns)
            Padding(
              padding: const EdgeInsets.only(right: 4),
              child: Column(
                children: [
                  for (final cell in col)
                    GestureDetector(
                      onTap: '${cell['day'] ?? ''}'.isNotEmpty ? () => onTapCell(cell) : null,
                      child: Container(
                        width: 14,
                        height: 14,
                        margin: const EdgeInsets.only(bottom: 4),
                        decoration: BoxDecoration(
                          color: _cellColor(cell['level']),
                          borderRadius: BorderRadius.circular(3.5),
                        ),
                      ),
                    ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Color _cellColor(Object? rawLevel) {
    final lvl = rawLevel is num ? rawLevel.toInt().clamp(0, 4) : 0;
    if (lvl == 0) return emptyColor;
    return baseColor.withValues(alpha: 0.22 + lvl * 0.19);
  }
}

class _CategoryBarRow extends StatelessWidget {
  const _CategoryBarRow({
    required this.label,
    required this.minutes,
    required this.maxMinutes,
    required this.color,
  });

  final String label;
  final int minutes;
  final int maxMinutes;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final ratio = (minutes / math.max(1, maxMinutes)).clamp(0.0, 1.0);
    return Row(
      children: [
        SizedBox(
          width: 78,
          child: Text(label, style: theme.textTheme.bodySmall),
        ),
        Expanded(
          child: ClipRRect(
            borderRadius: BorderRadius.circular(999),
            child: LinearProgressIndicator(
              value: ratio,
              minHeight: 8,
              backgroundColor: theme.colorScheme.outlineVariant.withValues(alpha: 0.2),
              valueColor: AlwaysStoppedAnimation<Color>(color),
            ),
          ),
        ),
        const SizedBox(width: 8),
        SizedBox(
          width: 44,
          child: Text(
            '${minutes}m',
            textAlign: TextAlign.end,
            style: AppType.tabular(theme.textTheme.bodySmall!),
          ),
        ),
      ],
    );
  }
}

/// H4：跨页面复用的统一时间账 Mist Glass 摘要卡（娱乐 / 挣来净额 / 游戏 / 通行证）。
class TimeLedgerSummaryCard extends StatelessWidget {
  const TimeLedgerSummaryCard({super.key, required this.ledger});

  final TimeLedgerView ledger;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final scheme = theme.colorScheme;
    final tone = (ledger.funOut && ledger.gameOut)
        ? StatusTone.error
        : (ledger.funOut ? StatusTone.warn : StatusTone.ok);
    final badgeLabel = (ledger.funOut && ledger.gameOut)
        ? '余额用完'
        : (ledger.funOut ? '余额用完' : '娱乐余 ${ledger.funLeft}m');

    Widget statCell({
      required String title,
      required String value,
      required String detail,
      required Color accent,
    }) =>
        Expanded(
          child: GlassSurface(
            tier: GlassTier.pill,
            borderRadius: AppShape.radiusMd,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    value,
                    style: AppType.tabular(
                      theme.textTheme.titleMedium!.copyWith(fontWeight: FontWeight.w700, color: accent),
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    detail,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary, fontSize: 10.5),
                  ),
                ],
              ),
            ),
          ),
        );

    final earnedDetail = ledger.penalty > 0
        ? '挣+${ledger.earned} · 扣-${ledger.penalty}'
        : (ledger.earnedBout > 0 || ledger.earnedBank > 0)
            ? '局+${ledger.earnedBout} · 题+${ledger.earnedBank}'
            : '无扣罚';

    return GlassSurface(
      tier: GlassTier.card,
      borderRadius: AppShape.radiusLg,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(AppIcons.time, size: 17, color: scheme.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '今日时间账（挣来制）',
                    style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ),
                StatusChip(tone: tone, label: badgeLabel),
              ],
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                statCell(
                  title: '娱乐余额',
                  value: '${ledger.funLeft}m',
                  detail: ledger.carry > 0 ? '含结转 ${ledger.carry}m' : '用 ${ledger.funUsed}m',
                  accent: ledger.funOut ? colors.danger : scheme.primary,
                ),
                const SizedBox(width: 8),
                statCell(
                  title: '今天挣来',
                  value: '+${ledger.earned}m',
                  detail: earnedDetail,
                  accent: colors.success,
                ),
                const SizedBox(width: 8),
                statCell(
                  title: '游戏还能玩',
                  value: '${ledger.gameLeft}m',
                  detail: '上限 ${ledger.gameQuota}m/天',
                  accent: ledger.gameOut ? colors.warning : colors.info,
                ),
              ],
            ),
            if (ledger.passMinutes > 0 || ledger.line.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                [
                  if (ledger.passMinutes > 0) '通行证免扣 ${ledger.passMinutes}m',
                  if (ledger.line.isNotEmpty) ledger.line,
                ].join(' · '),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// H7：跨页面复用的硬日判主结论卡（达成 / 部分 / 空转 + 投入分，空转时上限 39）。
class HardVerdictCard extends StatelessWidget {
  const HardVerdictCard({super.key, required this.verdict});

  final DailyVerdictView verdict;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final tone = verdict.isDone
        ? StatusTone.ok
        : (verdict.isIdle ? StatusTone.error : StatusTone.warn);
    final accent = verdict.isDone
        ? colors.success
        : (verdict.isIdle ? colors.danger : colors.warning);
    final icon = verdict.isDone
        ? AppIcons.success
        : (verdict.isIdle ? AppIcons.warning : AppIcons.verified);

    return GlassSurface(
      tier: GlassTier.card,
      borderRadius: AppShape.radiusLg,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(icon, size: 17, color: accent),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '硬日判：${verdict.tag}',
                    style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700, color: accent),
                  ),
                ),
                StatusChip(
                  tone: tone,
                  label: '${verdict.scoreLabel} ${verdict.cappedScore}',
                ),
              ],
            ),
            if (verdict.line.isNotEmpty || verdict.reasons.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                verdict.line.isNotEmpty ? verdict.line : verdict.reasons.join(' · '),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
