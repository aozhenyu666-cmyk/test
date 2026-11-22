// 2026-11-18 当下任务卡：两条鱼用 show_card 生成（或接管时由代码生成），这里只按固定组件渲染。
// 组件：标题、目标、步骤清单（可勾）、倒计时、交付物、验收方式、提示、≤3 个白名单按钮。
// 按钮动作由插件执行（开下一局 / 连 Gemini / 打开 App），这里不执行任何生成的代码。

import 'dart:async';

import 'package:flutter/material.dart';

import '../channels/proactive_state.dart';

class TaskCardPanel extends StatefulWidget {
  const TaskCardPanel({super.key, required this.channel});

  final ProactiveChannel channel;

  @override
  State<TaskCardPanel> createState() => _TaskCardPanelState();
}

class _TaskCardPanelState extends State<TaskCardPanel> {
  Map<String, dynamic>? _card;
  bool _busy = false;
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
    _tick = Timer.periodic(const Duration(seconds: 30), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    final r = await widget.channel.cardOp({'action': 'get'});
    if (!mounted) return;
    setState(() => _card = _asMap(r?['card']));
  }

  static Map<String, dynamic>? _asMap(Object? raw) {
    if (raw is! Map) return null;
    return {
      for (final e in raw.entries)
        if (e.key is String) e.key as String: e.value,
    };
  }

  Future<void> _op(Map<String, Object?> body) async {
    if (_busy) return;
    setState(() => _busy = true);
    final r = await widget.channel.cardOp(body);
    if (!mounted) return;
    final open = r?['open'];
    final gemini = _asMap(r?['gemini']);
    if (open is String && open.isNotEmpty) {
      await widget.channel.launchApp(open);
    } else if (gemini != null) {
      await widget.channel.launchGeminiProactive(
        topic: '${gemini['topic'] ?? ''}',
        brief: '${gemini['brief'] ?? ''}',
        mode: 'instant',
        preferVoice: true,
      );
    }
    final toast = r?['toast'];
    if (toast is String && toast.isNotEmpty && mounted) {
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(toast)));
    }
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (r != null && r.containsKey('card')) _card = _asMap(r['card']);
    });
  }

  String _left(Object? until) {
    if (until is! num || until <= 0) return '';
    final ms = until.toInt() - DateTime.now().millisecondsSinceEpoch;
    if (ms <= 0) return '时间到';
    final m = (ms / 60000).ceil();
    return '还剩 $m 分钟';
  }

  @override
  Widget build(BuildContext context) {
    final card = _card;
    if (card == null) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final steps = card['steps'] is List ? card['steps'] as List : const [];
    final buttons = card['buttons'] is List ? card['buttons'] as List : const [];
    final left = _left(card['timerUntil']);
    final lines = <String>[
      if ('${card['deliverable'] ?? ''}'.isNotEmpty) '交付：${card['deliverable']}',
      if ('${card['accept'] ?? ''}'.isNotEmpty) '验收：${card['accept']}',
    ];
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 8, 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('${card['title'] ?? ''}', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
                  ),
                  if (left.isNotEmpty) Text(left, style: theme.textTheme.labelMedium),
                  IconButton(
                    tooltip: '收起这张卡',
                    visualDensity: VisualDensity.compact,
                    onPressed: _busy ? null : () => unawaited(_op({'action': 'clear'})),
                    icon: const Icon(Icons.close, size: 18),
                  ),
                ],
              ),
              if ('${card['goal'] ?? ''}'.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 2, right: 8),
                  child: Text('${card['goal']}', style: theme.textTheme.bodyMedium),
                ),
              for (var i = 0; i < steps.length; i++)
                CheckboxListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  controlAffinity: ListTileControlAffinity.leading,
                  value: steps[i] is Map && (steps[i] as Map)['done'] == true,
                  title: Text(steps[i] is Map ? '${(steps[i] as Map)['text'] ?? ''}' : '${steps[i]}'),
                  onChanged: _busy ? null : (_) => unawaited(_op({'action': 'step', 'index': i})),
                ),
              for (final l in lines)
                Padding(
                  padding: const EdgeInsets.only(top: 2, right: 8),
                  child: Text(l, style: theme.textTheme.bodySmall),
                ),
              if ('${card['tip'] ?? ''}'.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 4, right: 8),
                  child: Text('${card['tip']}', style: theme.textTheme.bodySmall?.copyWith(fontStyle: FontStyle.italic)),
                ),
              if (buttons.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Wrap(
                    spacing: 8,
                    runSpacing: 6,
                    children: [
                      for (var i = 0; i < buttons.length; i++)
                        FilledButton.tonal(
                          onPressed: _busy ? null : () => unawaited(_op({'action': 'button', 'index': i})),
                          child: Text(buttons[i] is Map ? '${(buttons[i] as Map)['label'] ?? ''}' : ''),
                        ),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
