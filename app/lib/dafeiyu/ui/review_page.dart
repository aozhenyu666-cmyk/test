import 'dart:async';

import 'package:flutter/material.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';

/// 复习卡（FSRS）：刷到期卡、看全部卡、删垃圾卡。
///
/// 规矩都在插件里：带选项的卡这里 4 选 1 由代码判（算「验过」，能兑现学习制卡的第二半）；
/// 没选项的卡只能自己对答案（「记得 / 忘了」只改下次什么时候问，不算验过）。
class ReviewPage extends StatefulWidget {
  const ReviewPage({super.key, this.channel});

  final ProactiveChannel? channel;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel}) =>
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => ReviewPage(channel: channel)));

  @override
  State<ReviewPage> createState() => _ReviewPageState();
}

class _ReviewPageState extends State<ReviewPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  bool _loading = true;
  bool _busy = false;
  Map<String, dynamic> _summary = const {};
  List<Map<String, dynamic>> _due = const [];
  List<Map<String, dynamic>> _all = const [];
  int _total = 0;
  bool _revealed = false;
  String? _feedback;
  bool _feedbackOk = true;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  List<Map<String, dynamic>> _items(Map<String, dynamic>? r) =>
      r?['items'] is List ? [for (final x in r!['items'] as List) if (x is Map) Map<String, dynamic>.from(x)] : const [];

  Future<void> _load() async {
    final r = await Future.wait([
      _channel.reviewOp({'action': 'summary'}),
      _channel.reviewOp({'action': 'due', 'limit': 30}),
      _channel.reviewOp({'action': 'list'}),
    ]);
    if (!mounted) return;
    setState(() {
      _loading = false;
      _summary = r[0] ?? const {};
      _due = _items(r[1]);
      _all = _items(r[2]);
      _total = (r[2]?['total'] as num?)?.toInt() ?? _all.length;
      _revealed = false;
    });
  }

  Future<void> _send(Map<String, Object?> body) async {
    if (_busy) return;
    setState(() => _busy = true);
    final r = await _channel.reviewOp(body);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _feedback = '${r?['text'] ?? '没连上引擎，稍后再试'}';
      _feedbackOk = r?['ok'] == true && r?['correct'] != false && body['rating'] != 'again';
    });
    await _load();
  }

  Future<void> _delete(Map<String, dynamic> c) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删掉这张卡？'),
        content: Text('「${c['q']}」\n删了就不再问；笔记库里的镜像不动。'),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('留着')),
          FilledButton(onPressed: () => Navigator.of(ctx).pop(true), child: const Text('删掉')),
        ],
      ),
    );
    if (ok == true) await _send({'action': 'delete', 'id': c['id']});
  }

  String _dueText(Map<String, dynamic> c) {
    final due = (c['due'] as num?)?.toInt() ?? 0;
    final left = due - DateTime.now().millisecondsSinceEpoch;
    if (left <= 0) return '到期了';
    final h = left ~/ 3600000;
    return h >= 24 ? '${h ~/ 24} 天后问' : (h >= 1 ? '$h 小时后问' : '${left ~/ 60000 + 1} 分钟后问');
  }

  Widget _cardSurface(BuildContext context, Map<String, dynamic> c) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final options = c['options'] is List ? [for (final o in c['options'] as List) '$o'] : const <String>[];
    final topic = '${c['topic'] ?? ''}';
    return GlassSurface(
      key: ValueKey('review-current-${c['id']}'),
      tier: GlassTier.card,
      borderRadius: AppShape.radiusLg,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('${topic.isEmpty ? '' : '〔$topic〕'}还剩 ${_due.length} 张到期', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
            const SizedBox(height: 8),
            Text('${c['q']}', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            if (options.isNotEmpty)
              for (var i = 0; i < options.length; i++)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: OutlinedButton(
                    key: ValueKey('review-opt-$i'),
                    onPressed: _busy ? null : () => unawaited(_send({'action': 'answer', 'id': c['id'], 'index': i})),
                    child: Align(alignment: Alignment.centerLeft, child: Text('${String.fromCharCode(65 + i)}. ${options[i]}')),
                  ),
                )
            else if (!_revealed)
              FilledButton(
                key: const ValueKey('review-reveal'),
                onPressed: () => setState(() => _revealed = true),
                child: const Text('想好了，看答案'),
              )
            else ...[
              Text('答案：${c['a']}${'${c['hint'] ?? ''}'.isEmpty ? '' : '（前提：${c['hint']}）'}', style: theme.textTheme.bodyLarge),
              const SizedBox(height: 6),
              Text(
                c['pendingRecall'] == true
                    ? '这张在等「隔天回忆」兑现学习制卡的第二半：自己对答案不算验过，这次回忆就用掉了。想拿那一半，留给 Gemini 考。'
                    : '自己对答案只改下次什么时候问，不算验过。',
                style: TextStyle(color: c['pendingRecall'] == true ? colors.warning : colors.textTertiary),
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      key: const ValueKey('review-forgot'),
                      onPressed: _busy ? null : () => unawaited(_send({'action': 'rate', 'id': c['id'], 'rating': 'again'})),
                      child: const Text('忘了'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      key: const ValueKey('review-remember'),
                      onPressed: _busy ? null : () => unawaited(_send({'action': 'rate', 'id': c['id'], 'rating': 'good'})),
                      child: const Text('记得'),
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final due = (_summary['dueCount'] as num?)?.toInt() ?? _due.length;
    final total = (_summary['totalCards'] as num?)?.toInt() ?? _total;
    final ret = _summary['retention7d'];
    final current = _due.isEmpty ? null : _due.first;
    return AppPage.list(
      title: '复习卡',
      subtitle: const Text('按遗忘曲线到期再问：锁屏、这里、Gemini、两条鱼都会问'),
      actions: [GlassIconButton(tooltip: '刷新', icon: AppIcons.refresh, onPressed: () => unawaited(_load()))],
      children: [
        OverviewCard(
          key: const ValueKey('review-overview'),
          leading: const OverviewGlyph(AppIcons.skill),
          title: Text(_loading ? '读取中…' : '今天到期 $due 张 · 共 $total 张'),
          subtitle: Text('近 7 天隔夜回忆通过 ${ret == null ? '—' : '$ret%'}（只算创建 12 小时后的第一次）'),
          trailing: StatusChip(tone: due > 0 ? StatusTone.warn : StatusTone.ok, label: due > 0 ? '有到期' : '都刷完了'),
        ),
        if (_feedback != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Text(_feedback!, key: const ValueKey('review-feedback'), style: TextStyle(color: _feedbackOk ? colors.success : colors.danger)),
          ),
        if (current != null)
          Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 20), child: _cardSurface(context, current))
        else if (!_loading)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            child: Text(
              total == 0 ? '卡池是空的：跟鱼说「把这个记进复习卡」，或让 Gemini 聊完推卡。' : '今天的到期卡刷完了。',
              style: TextStyle(color: colors.textTertiary),
            ),
          ),
        if (_all.isNotEmpty)
          ListPageCard(
            key: const ValueKey('review-all'),
            title: '全部卡（最近 ${_all.length} / $_total 张）',
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: [
              for (final c in _all)
                ListPageRow(
                  key: ValueKey('review-row-${c['id']}'),
                  icon: (c['options'] is List && (c['options'] as List).isNotEmpty) ? AppIcons.question : AppIcons.document,
                  title: '${c['q']}',
                  subtitle: '${_dueText(c)} · 复习 ${c['reps'] ?? 0} 次 · 忘过 ${c['lapses'] ?? 0} 次${c['counts'] == true ? ' · 算学习制卡' : ''}',
                  subtitleMaxLines: 2,
                  trailing: IconButton(
                    key: ValueKey('review-del-${c['id']}'),
                    tooltip: '删掉',
                    icon: const Icon(AppIcons.delete),
                    onPressed: _busy ? null : () => unawaited(_delete(c)),
                  ),
                ),
            ],
          ),
        const SizedBox(height: 12),
      ],
    );
  }
}
