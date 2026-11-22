import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';
import 'seal_page.dart';
import 'strategy_horizons_page.dart';

/// 计划页：长期主线 → 本周每天交什么（大肥鱼/本周计划.md）→ 到点自动套成今天的验收点。
///
/// 规矩在引擎：本周中只加不减（没有删除）；今天那一节已经套过了，加的从下周的今天起生效。
/// 要大改就复制一段话给 Gemini，让它按格式排好下周，再贴进 Obsidian。
class WeekPlanPage extends StatefulWidget {
  const WeekPlanPage({super.key, this.channel});

  final ProactiveChannel? channel;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel}) =>
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => WeekPlanPage(channel: channel)));

  @override
  State<WeekPlanPage> createState() => _WeekPlanPageState();
}

/// 计划里能写的证据（要选 App 的那两种在验收点页加）。
const List<String> kWeekPlanKinds = ['quiz', 'bout_minutes', 'gemini_receipt', 'screenshots', 'file', 'study_cards'];

class _WeekPlanPageState extends State<WeekPlanPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  bool _loading = true;
  bool _busy = false;
  Map<String, dynamic> _data = const {};
  String? _banner;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    final r = await _channel.planWeekOp({'action': 'view'});
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (r != null && r['ok'] == true) _data = r;
    });
  }

  List<Map<String, dynamic>> _list(Object? v) =>
      v is List ? [for (final x in v) if (x is Map) Map<String, dynamic>.from(x)] : const [];

  Future<void> _add(Map<String, dynamic> day) async {
    final item = await showModalBottomSheet<Map<String, Object?>>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _AddItemSheet(dayName: '${day['name']}', isToday: day['isToday'] == true),
    );
    if (item == null || !mounted) return;
    setState(() => _busy = true);
    final r = await _channel.planWeekOp({'action': 'add', 'weekday': day['weekday'], 'item': item});
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (r != null && r['ok'] == true) _data = r;
      _banner = '${r?['text'] ?? '没连上引擎，稍后再试'}';
    });
  }

  Future<void> _copyPrompt() async {
    setState(() => _busy = true);
    final r = await _channel.planWeekOp({'action': 'prompt'});
    if (!mounted) return;
    final text = '${r?['prompt'] ?? ''}';
    if (text.isNotEmpty) await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = text.isEmpty ? '没连上引擎，稍后再试' : '复制好了：贴给 Gemini，它按格式回一份本周计划，你贴进 Obsidian 的 大肥鱼/本周计划.md。';
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final days = _list(_data['days']);
    final horizons = _data['horizons'] is Map ? Map<String, dynamic>.from(_data['horizons'] as Map) : const <String, dynamic>{};
    final longs = _list(horizons['long']);
    final meds = _list(horizons['medium']);
    final rate = _data['execRate'];
    final longText = '${_data['longText'] ?? ''}'.trim();
    return AppPage.list(
      title: '本周计划',
      subtitle: const Text('长期 → 这周每天交什么 → 到点自动套成今天的验收点'),
      actions: [GlassIconButton(tooltip: '刷新', icon: AppIcons.refresh, onPressed: () => unawaited(_load()))],
      children: [
        OverviewCard(
          key: const ValueKey('week-plan-overview'),
          leading: const OverviewGlyph(AppIcons.calendar),
          title: Text(_loading ? '读取中…' : '${_data['monday'] ?? ''} 起的这一周'),
          subtitle: const Text('笔记库 大肥鱼/本周计划.md，Obsidian 里也能改；本周中只加不减，下周没改就自动顺延'),
          trailing: rate == null
              ? null
              : StatusChip(
                  tone: (rate as num) >= 85 ? StatusTone.ok : (rate < 70 ? StatusTone.error : StatusTone.warn),
                  label: '执行率 $rate%',
                ),
        ),
        if (_banner != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Text(_banner!, key: const ValueKey('week-plan-banner'), style: TextStyle(color: colors.textTertiary)),
          ),
        ListPageCard(
          key: const ValueKey('week-plan-long'),
          title: '长期',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
          children: [
            for (final h in longs)
              ListPageRow(icon: AppIcons.planMode, title: '${h['title']}', subtitle: '长期方向'),
            for (final h in meds)
              ListPageRow(icon: AppIcons.planMode, title: '${h['title']}', subtitle: '中期战役'),
            if (longs.isEmpty && meds.isEmpty && longText.isEmpty)
              const ListPageRow(icon: AppIcons.info, title: '还没写长期方向', subtitle: '先定 1–3 个目标和截止日，这周的量才有依据'),
            if (longText.isNotEmpty)
              ListPageRow(icon: AppIcons.document, title: '长期规划.md', subtitle: longText, subtitleMaxLines: 4),
            ListPageRow(
              key: const ValueKey('week-plan-horizons'),
              icon: AppIcons.edit,
              title: '去改长期主线',
              chevron: true,
              onTap: () => unawaited(Navigator.of(context)
                  .push(MaterialPageRoute<void>(builder: (_) => StrategyHorizonsPage(channel: _channel)))
                  .then((_) => _load())),
            ),
          ],
        ),
        for (final d in days)
          ListPageCard(
            key: ValueKey('week-plan-day-${d['weekday']}'),
            title: '${d['name']}${d['applyAt'] == null ? '' : '（${d['applyAt']} 自动套）'}${d['isToday'] == true ? ' · 今天' : ''}',
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: [
              for (final it in _list(d['items']))
                ListPageRow(
                  icon: kSealKinds['${it['kind']}']?.$3 ?? AppIcons.verified,
                  title: '${it['title']}',
                  subtitle: '${it['line']}',
                  subtitleMaxLines: 2,
                ),
              if (_list(d['items']).isEmpty)
                const ListPageRow(icon: AppIcons.info, title: '这天空着', subtitle: '空着的那天按 模板.md 自动套'),
              ListPageRow(
                key: ValueKey('week-plan-add-${d['weekday']}'),
                icon: AppIcons.add,
                title: '加一条',
                chevron: true,
                onTap: _busy ? null : () => unawaited(_add(d)),
              ),
            ],
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          child: FilledButton.icon(
            key: const ValueKey('week-plan-gemini'),
            onPressed: _busy ? null : () => unawaited(_copyPrompt()),
            icon: const Icon(AppIcons.copy),
            label: const Text('复制给 Gemini：帮我排下周'),
          ),
        ),
      ],
    );
  }
}

/// 加一条：交什么 · 证据 · 拉伸 / 保底 · 开始–截止。控制器归这个底板自己，关掉时一起释放。
class _AddItemSheet extends StatefulWidget {
  const _AddItemSheet({required this.dayName, required this.isToday});

  final String dayName;
  final bool isToday;

  @override
  State<_AddItemSheet> createState() => _AddItemSheetState();
}

class _AddItemSheetState extends State<_AddItemSheet> {
  final _title = TextEditingController();
  final _stretch = TextEditingController(text: '20');
  final _floor = TextEditingController();
  final _path = TextEditingController();
  String _kind = 'quiz';
  TimeOfDay? _start;
  TimeOfDay _due = const TimeOfDay(hour: 11, minute: 0);
  String? _error;

  @override
  void dispose() {
    _title.dispose();
    _stretch.dispose();
    _floor.dispose();
    _path.dispose();
    super.dispose();
  }

  String _hm(TimeOfDay t) => '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  void _submit() {
    final stretch = int.tryParse(_stretch.text.trim()) ?? 0;
    final floor = int.tryParse(_floor.text.trim());
    final err = _title.text.trim().isEmpty
        ? '写一句交什么'
        : stretch < 1
            ? '拉伸至少 1'
            : (floor != null && (floor < 1 || floor > stretch))
                ? '保底要在 1 到拉伸之间'
                : (_kind == 'file' && _path.text.trim().isEmpty)
                    ? '写清是哪份笔记'
                    : (_start != null && (_start!.hour * 60 + _start!.minute) >= (_due.hour * 60 + _due.minute))
                        ? '开始要在截止之前'
                        : null;
    if (err != null) {
      setState(() => _error = err);
      return;
    }
    Navigator.of(context).pop(<String, Object?>{
      'title': _title.text.trim(),
      'kind': _kind,
      'stretch': stretch,
      'floor': ?floor,
      if (_start != null) 'start': _hm(_start!),
      'due': _hm(_due),
      if (_kind == 'file') 'path': _path.text.trim(),
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final unit = kSealKinds[_kind]?.$2 ?? '';
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('${widget.dayName}加一条', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(
                widget.isToday ? '今天的已经自动套过了：这一条从下周的今天起生效；今天要做去「验收点」页现加。' : '到那天自动套成验收点；本周中只加不减。',
                style: TextStyle(color: colors.textTertiary),
              ),
              const SizedBox(height: 12),
              TextField(
                key: const ValueKey('week-add-title'),
                controller: _title,
                maxLength: 40,
                decoration: const InputDecoration(labelText: '交什么', hintText: '例：资料分析一套'),
              ),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final k in kWeekPlanKinds)
                    ChoiceChip(
                      key: ValueKey('week-add-kind-$k'),
                      label: Text(kSealKinds[k]?.$1 ?? k),
                      selected: _kind == k,
                      onSelected: (_) => setState(() => _kind = k),
                    ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      key: const ValueKey('week-add-stretch'),
                      controller: _stretch,
                      keyboardType: TextInputType.number,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      decoration: InputDecoration(labelText: '拉伸（$unit）'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      key: const ValueKey('week-add-floor'),
                      controller: _floor,
                      keyboardType: TextInputType.number,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      decoration: InputDecoration(labelText: '保底（$unit）', hintText: '默认一半'),
                    ),
                  ),
                ],
              ),
              if (_kind == 'file') ...[
                const SizedBox(height: 12),
                TextField(
                  key: const ValueKey('week-add-path'),
                  controller: _path,
                  decoration: const InputDecoration(labelText: '哪份笔记', hintText: '笔记库里的路径，如 学习/申论.md'),
                ),
              ],
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      icon: const Icon(AppIcons.play),
                      label: Text(_start == null ? '几点开始（可不填）' : '${_hm(_start!)} 开始'),
                      onPressed: () async {
                        final t = await showTimePicker(context: context, initialTime: _start ?? const TimeOfDay(hour: 9, minute: 30));
                        if (t != null) setState(() => _start = t);
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: OutlinedButton.icon(
                      icon: const Icon(AppIcons.time),
                      label: Text('${_hm(_due)} 前'),
                      onPressed: () async {
                        final t = await showTimePicker(context: context, initialTime: _due);
                        if (t != null) setState(() => _due = t);
                      },
                    ),
                  ),
                ],
              ),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(_error!, key: const ValueKey('week-add-error'), style: TextStyle(color: colors.danger)),
              ],
              const SizedBox(height: 16),
              FilledButton(key: const ValueKey('week-add-submit'), onPressed: _submit, child: const Text('加进本周计划')),
            ],
          ),
        ),
      ),
    );
  }
}
