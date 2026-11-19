import 'dart:async';

import 'package:flutter/material.dart';

import '../../l10n/l10n.dart';
import '../../ui/common/list_page_row.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/phone/action_log_page.dart' show formatLogTime;
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/intercept_channel.dart';
import '../channels/loop_channel.dart';
import '../channels/proactive_state.dart';
import 'intercept_page.dart' show interceptOutcomeLabel;
import 'proactive_page.dart' show ProactiveReceiptRow;

enum RecordsTab { lock, proactive }

/// 执行记录：锁机记录、主动记录放在一页，按页加载，长了可以清理。
///
/// 别的页面只显示最近几条，再多就点进来看；这里是唯一能删记录的地方（删的是明细，计数不动）。
class RecordsPage extends StatefulWidget {
  const RecordsPage({
    super.key,
    this.initialTab = RecordsTab.lock,
    this.intercept,
    this.proactive,
    this.loop,
  });

  final RecordsTab initialTab;
  final InterceptChannel? intercept;
  final ProactiveChannel? proactive;
  final LoopChannel? loop;

  static Future<void> open(BuildContext context, {RecordsTab tab = RecordsTab.lock}) => Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => RecordsPage(initialTab: tab)),
      );

  @override
  State<RecordsPage> createState() => _RecordsPageState();
}

class _RecordsPageState extends State<RecordsPage> {
  static const _page = 20;
  static const _max = 200;

  late final InterceptChannel _intercept = widget.intercept ?? InterceptChannel();
  late final ProactiveChannel _proactive = widget.proactive ?? ProactiveChannel();
  late final LoopChannel _loop = widget.loop ?? LoopChannel();
  late RecordsTab _tab = widget.initialTab;
  int _limit = _page;
  bool _loading = true;
  String? _error;
  List<InterceptReceipt> _lock = const [];
  List<ProactiveReceipt> _pro = const [];
  final Map<String, String> _labels = {};

  @override
  void initState() {
    super.initState();
    unawaited(_load());
    unawaited(_loadLabels());
  }

  Future<void> _loadLabels() async {
    final apps = await _loop.apps();
    if (!mounted || apps.isEmpty) return;
    setState(() {
      for (final a in apps) {
        _labels[a.package] = a.label;
      }
    });
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      if (_tab == RecordsTab.lock) {
        final r = await _intercept.receipts(limit: _limit);
        if (!mounted) return;
        setState(() => _lock = r);
      } else {
        final r = await _proactive.receipts(limit: _limit);
        if (!mounted) return;
        setState(() => _pro = r);
      }
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
    if (mounted) setState(() => _loading = false);
  }

  int get _count => _tab == RecordsTab.lock ? _lock.length : _pro.length;

  Future<void> _openCleanup() async {
    final days = await showModalBottomSheet<int>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
              child: Text(
                '清理${_tab == RecordsTab.lock ? '锁机' : '主动'}记录',
                style: Theme.of(ctx).textTheme.titleMedium,
              ),
            ),
            const Padding(
              padding: EdgeInsets.fromLTRB(24, 0, 24, 12),
              child: Text('只删明细；拦了几次、放了几次这些计数不动。删了找不回来。'),
            ),
            ListTile(
              key: const ValueKey('records-clean-7'),
              leading: const Icon(AppIcons.history),
              title: const Text('删掉 7 天前的'),
              onTap: () => Navigator.of(ctx).pop(7),
            ),
            ListTile(
              key: const ValueKey('records-clean-30'),
              leading: const Icon(AppIcons.history),
              title: const Text('删掉 30 天前的'),
              onTap: () => Navigator.of(ctx).pop(30),
            ),
            ListTile(
              key: const ValueKey('records-clean-all'),
              leading: Icon(AppIcons.delete, color: DshColors.of(ctx).danger),
              title: Text('全部清掉', style: TextStyle(color: DshColors.of(ctx).danger)),
              onTap: () => Navigator.of(ctx).pop(0),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    if (days == null || !mounted) return;
    if (days == 0) {
      final sure = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('全部清掉？'),
          content: const Text('这一类记录的明细会全部删掉，找不回来。'),
          actions: [
            TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('算了')),
            FilledButton(
              key: const ValueKey('records-clean-confirm'),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('清掉'),
            ),
          ],
        ),
      );
      if (sure != true || !mounted) return;
    }
    int? removed;
    try {
      removed = _tab == RecordsTab.lock
          ? await _intercept.clearReceipts(olderThanDays: days)
          : await _proactive.clearReceipts(olderThanDays: days);
    } catch (_) {
      removed = null;
    }
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(removed == null ? '没清成：连不上' : (removed == 0 ? '没有要删的' : '删了 $removed 条'))),
    );
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final colors = DshColors.of(context);
    return AppPage.list(
      title: '执行记录',
      subtitle: const Text('锁机和主动提醒的每一次结果；长了就清理'),
      actions: [
        GlassIconButton(
          key: const ValueKey('records-clean'),
          tooltip: '清理记录',
          icon: AppIcons.delete,
          onPressed: _loading ? null : () => unawaited(_openCleanup()),
        ),
        GlassIconButton(tooltip: '刷新', icon: AppIcons.refresh, onPressed: () => unawaited(_load())),
      ],
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          child: SegmentedPill<RecordsTab>(
            segments: const [
              PillSegment(RecordsTab.lock, '锁机记录'),
              PillSegment(RecordsTab.proactive, '主动记录'),
            ],
            selected: _tab,
            onChanged: (t) {
              setState(() {
                _tab = t;
                _limit = _page;
              });
              unawaited(_load());
            },
          ),
        ),
        ListPageCard(
          title: _loading ? '读取中…' : '最近 $_count 条',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          children: [
            if (_error != null)
              ListPageRow(icon: AppIcons.warning, iconColor: colors.warning, title: '读不到记录', subtitle: _error, subtitleMaxLines: 3)
            else if (!_loading && _count == 0)
              const ListPageRow(icon: AppIcons.history, title: '还没有记录')
            else if (_tab == RecordsTab.lock)
              for (final r in _lock)
                ListPageRow(
                  icon: AppIcons.lock,
                  title: interceptOutcomeLabel(l10n, r.outcome),
                  subtitle: [_labels[r.packageName] ?? r.packageName, if ((r.reason ?? '').isNotEmpty) r.reason!].join(' · '),
                  subtitleMaxLines: 2,
                  value: formatLogTime(DateTime.fromMillisecondsSinceEpoch(r.endedAt)),
                )
            else
              for (final r in _pro) ProactiveReceiptRow(r),
            if (!_loading && _count >= _limit && _limit < _max)
              ListPageRow(
                key: const ValueKey('records-more'),
                icon: AppIcons.expand,
                title: '再看 $_page 条',
                onTap: () {
                  setState(() => _limit = (_limit + _page).clamp(_page, _max));
                  unawaited(_load());
                },
              ),
          ],
        ),
      ],
    );
  }
}
