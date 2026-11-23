import 'dart:async';

import 'package:flutter/material.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/loop_channel.dart';
import '../channels/proactive_state.dart';
import 'locks_balance_page.dart';
import 'plan_modality_page.dart';
import 'plan_page.dart';
import 'seal_page.dart';
import 'strategy_horizons_page.dart';
import 'today_cards.dart';
import 'usage_analytics_page.dart';

/// 计划：原来散在三处的「今日计划（时间块）」「计划模态 / 契约台」「三层战略」收成一个入口。
///
/// 三层从近到远：今天排几局（时间块）→ 这件事怎么验收（契约）→ 它属于哪条长线（中期战役 / 长期方向）。
/// 局页的「主线」卡是这一页的摘要；别处要进计划，一律先到这里。
class PlanHubPage extends StatefulWidget {
  const PlanHubPage({super.key, this.channel, this.loopChannel});

  final ProactiveChannel? channel;
  final LoopChannel? loopChannel;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel, LoopChannel? loopChannel}) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => PlanHubPage(channel: channel, loopChannel: loopChannel)),
      );

  @override
  State<PlanHubPage> createState() => _PlanHubPageState();
}

class _PlanHubPageState extends State<PlanHubPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  Map<String, dynamic> _today = const {};
  Map<String, dynamic>? _seals;
  bool? _arbiterEnforce;
  bool _loading = true;
  int _cardRefreshTick = 0;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh(fromInit: true));
  }

  Future<void> _refresh({bool fromInit = false}) async {
    if (!fromInit && mounted) setState(() => _cardRefreshTick++);
    unawaited(_channel.sealsOp({'action': 'list'}).then((r) {
      if (mounted && r != null && r['ok'] == true) setState(() => _seals = r);
    }).catchError((_) {}));
    unawaited(_channel.arbiterEnforce().then((v) {
      if (mounted && v != null) setState(() => _arbiterEnforce = v);
    }).catchError((_) {}));
    final t = await _channel.todayStatus();
    if (!mounted) return;
    setState(() {
      _today = t ?? const {};
      _loading = false;
    });
  }

  List<Map<String, dynamic>> _list(Object? raw) =>
      raw is List ? [for (final x in raw) if (x is Map) Map<String, dynamic>.from(x)] : const [];

  Future<void> _go(Widget page) async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => page));
    if (mounted) unawaited(_refresh());
  }

  String _sealLine() {
    final s = _seals;
    if (s == null) return '到开始时刻自动开局、到截止时刻代码判、没过保底就锁；只能加码';
    final items = _list(s['items']);
    final today = items.where((x) => x['day'] == s['today']).length;
    final tomorrow = items.where((x) => x['day'] == s['tomorrow']).length;
    if (today == 0 && tomorrow == 0) return '今天还没定。10:00 前、开工前定的不打折';
    return '今天 $today 条 · 明天已封 $tomorrow 条${s['cutoffPassed'] == true ? ' · 已过 23:00' : ''}';
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final tasks = _list(_today['tasks']);
    final horizons = _today['horizons'] is Map ? _today['horizons'] as Map : const {};
    final med = _list(horizons['medium']);
    final long = _list(horizons['long']);
    final contract = _today['contract'] is Map ? Map<String, dynamic>.from(_today['contract'] as Map) : null;
    final cps = _list(contract?['checkpoints']);
    final pending = cps.where((c) => c['status'] != 'passed').length;
    final now = DateTime.now();
    final todayYmd = '${now.year}-${now.month.toString().padLeft(2, '0')}-${now.day.toString().padLeft(2, '0')}';
    final due = [
      for (final h in [...med, ...long])
        if (RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch('${h['due'] ?? ''}') && '${h['due']}'.compareTo(todayYmd) >= 0) h,
    ]..sort((a, b) => '${a['due']}'.compareTo('${b['due']}'));
    final statusMap = _today['status'] is Map ? _today['status'] as Map : null;
    final arbiterRaw =
        _seals?['arbiterEnforce'] ?? _today['arbiterEnforce'] ?? statusMap?['arbiterEnforce'] ?? _arbiterEnforce;
    final arbiterEnforce = arbiterRaw is bool ? arbiterRaw : true;
    final gaRaw = _seals?['geminiAccounting'] ?? _today['geminiAccounting'];
    final geminiSummary = gaRaw is Map ? '${gaRaw['summaryLine'] ?? ''}'.trim() : '';
    final locksRaw = _seals?['locksOverview'] ?? _today['locksOverview'];
    final locksMap = locksRaw is Map ? Map<String, dynamic>.from(locksRaw) : const <String, dynamic>{};
    final walletMap = locksMap['wallet'] is Map ? Map<String, dynamic>.from(locksMap['wallet'] as Map) : const <String, dynamic>{};
    final activeLockMap = locksMap['activeLock'] is Map ? Map<String, dynamic>.from(locksMap['activeLock'] as Map) : const <String, dynamic>{};
    final ledgerFallback = TimeLedgerView.fromWire(_today['timeLedger']);
    final availMin = walletMap['availableMin'] is num
        ? (walletMap['availableMin'] as num).round()
        : ledgerFallback?.funLeft;
    final activeLockLabel = '${activeLockMap['label'] ?? ''}'.trim();
    final locksSubtitle = availMin != null
        ? '可用余额 ${availMin}m${activeLockLabel.isNotEmpty ? ' · $activeLockLabel' : ''} · 分时花余额与 6 只手裁决'
        : '6 只手实时裁决 · 手机可用余额大盘 · 急事 3 分钟通道';

    return AppPage.list(
      title: '计划',
      subtitle: const Text('今天排几局 · 怎么验收 · 属于哪条长线'),
      actions: [GlassIconButton(tooltip: '刷新', icon: AppIcons.refresh, onPressed: () => unawaited(_refresh()))],
      children: [
        OverviewCard(
          leading: const OverviewGlyph(AppIcons.planMode, tint: 4),
          title: const Text('今天的计划'),
          subtitle: Text(
            _loading
                ? '读取中…'
                : '今日主线 ${tasks.length} 项 · ${contract == null ? '没有契约' : '契约待验 $pending 个点'} · 长线 ${med.length + long.length} 条',
          ),
          trailing: due.isEmpty
              ? null
              : StatusChip(tone: StatusTone.warn, label: '最近截止 ${'${due.first['due']}'.substring(5)}'),
          footer: Padding(
            padding: const EdgeInsets.only(left: OverviewCard.inset),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                StatusChip(
                  key: const ValueKey('plan-arbiter-chip'),
                  tone: arbiterEnforce ? StatusTone.ok : StatusTone.idle,
                  label: arbiterEnforce ? '单一裁决器执法中' : '影子模式',
                ),
                if (geminiSummary.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      Icon(AppIcons.askAi, size: 13, color: colors.textTertiary),
                      const SizedBox(width: 5),
                      Expanded(
                        child: Text(
                          geminiSummary,
                          key: const ValueKey('plan-gemini-summary'),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                        ),
                      ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ),
        NodMenuCard(
          key: const ValueKey('plan-nod-slot'),
          channel: _channel,
          refreshTick: _cardRefreshTick,
          onLocked: () => unawaited(_refresh()),
        ),
        ListPageCard(
          title: '主力计划入口',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
          children: [
            ListPageRow(
              key: const ValueKey('plan-hub-contract'),
              icon: AppIcons.verified,
              iconColor: colors.success,
              title: '计划与托管（三态工作台）',
              subtitle: contract == null ? '准备 → 构建时间盒 → 观看红线验收 · 自动哨兵与托管冲刺' : '「${contract['title'] ?? ''}」还有 $pending 个验收点',
              subtitleMaxLines: 2,
              chevron: true,
              onTap: () => unawaited(_go(PlanModalityPage(channel: _channel, loopChannel: widget.loopChannel))),
            ),
            ListPageRow(
              key: const ValueKey('plan-hub-horizons'),
              icon: AppIcons.planMode,
              iconColor: Theme.of(context).colorScheme.primary,
              title: '三层战略作战室（长期 / 中期）',
              subtitle: '长期北极星 · 中期战役 · 今日时间轴与验收锁 · Gemini 联调',
              subtitleMaxLines: 2,
              value: '${long.length} 长 · ${med.length} 中',
              chevron: true,
              onTap: () => unawaited(_go(StrategyHorizonsPage(channel: _channel))),
            ),
            ListPageRow(
              key: const ValueKey('plan-hub-seal'),
              icon: AppIcons.lock,
              iconColor: colors.warning,
              title: '封印与验收点明细',
              subtitle: _sealLine(),
              subtitleMaxLines: 2,
              chevron: true,
              onTap: () => unawaited(_go(SealPage(channel: _channel))),
            ),
          ],
        ),
        ListPageCard(
          title: '关联总控与排程明细',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
          children: [
            ListPageRow(
              key: const ValueKey('plan-hub-day'),
              icon: AppIcons.calendar,
              iconColor: colors.info,
              title: '今天：时间块排程',
              subtitle: '按时间块排今天几局，每块写清必需 / 允许的 App',
              subtitleMaxLines: 2,
              value: tasks.isEmpty ? null : '${tasks.length} 项',
              chevron: true,
              onTap: () => unawaited(_go(const PlanPage())),
            ),
            ListPageRow(
              key: const ValueKey('plan-hub-locks'),
              icon: AppIcons.balance,
              iconColor: colors.warning,
              title: '锁与余额总控台',
              subtitle: locksSubtitle,
              subtitleMaxLines: 2,
              value: availMin != null ? '${availMin}m' : null,
              chevron: true,
              onTap: () => unawaited(_go(LocksBalancePage(channel: _channel))),
            ),
            ListPageRow(
              key: const ValueKey('plan-hub-analytics'),
              icon: AppIcons.chart,
              iconColor: colors.info,
              title: 'App 使用时长统计',
              subtitle: '24h 时间切片 · 90 天趋势 · Gemini 共脑 · 晚间 AI 总报',
              subtitleMaxLines: 2,
              chevron: true,
              onTap: () => unawaited(_go(UsageAnalyticsPage(channel: _channel))),
            ),
          ],
        ),
        InboxCaptureCard(
          key: const ValueKey('plan-inbox-slot'),
          channel: _channel,
          refreshTick: _cardRefreshTick,
        ),
        if (due.isNotEmpty)
          ListPageCard(
            title: '截止日',
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
            children: [
              for (final d in due.take(5))
                ListPageRow(
                  icon: AppIcons.time,
                  title: '${d['title'] ?? ''}',
                  value: '${d['due']}',
                ),
            ],
          ),
      ],
    );
  }
}
