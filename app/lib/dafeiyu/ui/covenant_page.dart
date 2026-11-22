import 'dart:async';

import 'package:flutter/material.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';

const Map<int, String> kCovenantChapters = {1: '一、铁律', 2: '二、处置', 3: '三、分工', 4: '四、边界', 5: '五、你对她们的要求'};
const List<String> kCovenantScenes = ['任何时候', '早上', '干活', '待命', '休息', '追问', '锁着', '有封印', '对账', '深夜'];
const List<String> kCovenantWho = ['都', '鲸澜', '鱼丸'];
const List<String> kCovenantHardness = ['铁律', '默认', '建议'];

StatusTone covenantTone(String h) => switch (h) {
      '铁律' => StatusTone.error,
      '默认' => StatusTone.busy,
      _ => StatusTone.idle,
    };

/// 公约：他和两条鱼之间的成文规矩。真源是笔记库里的 大肥鱼/公约.md（电脑上也能改）。
///
/// 改严（加一条、升硬度）立即生效；改松（删、降硬度、改铁律的字）等 3 天，期间能撤回。
/// 两条鱼只能提议新规矩，在这一页批了才写进去。
class CovenantPage extends StatefulWidget {
  const CovenantPage({super.key, this.channel});

  final ProactiveChannel? channel;

  @override
  State<CovenantPage> createState() => _CovenantPageState();
}

class _CovenantPageState extends State<CovenantPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  Map<String, dynamic> _data = const {};
  bool _loading = true;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    final r = await _channel.covenantOp({'action': 'list'});
    if (!mounted) return;
    setState(() {
      _loading = false;
      _failed = r == null || r['ok'] != true;
      if (!_failed) _data = r!;
    });
  }

  List<Map<String, dynamic>> _list(Object? raw) =>
      raw is List ? [for (final x in raw) if (x is Map) Map<String, dynamic>.from(x)] : const [];

  Map<String, dynamic> get _pending => _data['pending'] is Map ? Map<String, dynamic>.from(_data['pending'] as Map) : const {};

  Future<void> _op(Map<String, Object?> body, String done) async {
    final r = await _channel.covenantOp(body);
    if (!mounted) return;
    final ok = r?['ok'] == true;
    final waiting = r?['waiting'] == true;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(!ok
          ? '${r?['text'] ?? (r == null ? '连不上引擎，没改成' : '没改成（${r['error']}）')}'
          : waiting
              ? '这是放松：${_data['cooldownDays'] ?? 3} 天后才生效，期间改回去就撤销'
              : done),
    ));
    if (ok) setState(() => _data = r!);
  }

  Future<void> _edit({Map<String, dynamic>? rule, int chapter = 2}) async {
    final out = await showModalBottomSheet<Map<String, Object?>>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _RuleEditor(rule: rule, chapter: chapter),
    );
    if (out == null || !mounted) return;
    if (out['remove'] == true) {
      await _op({'action': 'remove', 'id': rule!['id']}, '删了');
      return;
    }
    await _op({'action': 'save', 'rule': out}, rule == null ? '加上了，立即生效' : '改好了');
  }

  Future<void> _decide(Map<String, dynamic> p) async {
    final rule = p['rule'] is Map ? Map<String, dynamic>.from(p['rule'] as Map) : const <String, dynamic>{};
    final names = _data['names'] is Map ? _data['names'] as Map : const {};
    final picked = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 4),
              child: Text('${rule['title']}', style: Theme.of(ctx).textTheme.titleMedium),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
              child: Text(
                '${rule['do']}\n\n${names[p['by']] ?? p['by']} 的理由：${p['why']}\n'
                '放进${kCovenantChapters[(p['chapter'] as num?)?.toInt() ?? 2]} · ${rule['hardness']} · 归${rule['who']}',
              ),
            ),
            ListTile(
              key: const ValueKey('covenant-approve'),
              leading: const Icon(AppIcons.success),
              title: const Text('批了，写进公约'),
              onTap: () => Navigator.of(ctx).pop('approve'),
            ),
            ListTile(
              key: const ValueKey('covenant-reject'),
              leading: const Icon(AppIcons.close),
              title: const Text('不批'),
              onTap: () => Navigator.of(ctx).pop('reject'),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    if (picked == null || !mounted) return;
    await _op({'action': picked, 'id': p['id']}, picked == 'approve' ? '批了，已写进公约' : '没批');
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final rules = _list(_data['rules']);
    final proposals = _list(_data['proposals']);
    final pending = _pending;
    final names = _data['names'] is Map ? _data['names'] as Map : const {};
    final iron = rules.where((r) => r['hardness'] == '铁律').length;
    final days = _data['cooldownDays'] ?? 3;

    return AppPage.list(
      title: '公约',
      subtitle: const Text('你定的规矩，权限高于两条鱼'),
      actions: [GlassIconButton(tooltip: '刷新', icon: AppIcons.refresh, onPressed: () => unawaited(_load()))],
      children: [
        OverviewCard(
          leading: const OverviewGlyph(AppIcons.instructions, tint: 3),
          title: const Text('你和两条鱼的规矩'),
          subtitle: Text(
            _loading
                ? '读取中…'
                : _failed
                    ? '引擎没应答；公约要引擎在线'
                    : '${rules.length} 条，铁律 $iron 条 · ${_data['inVault'] == true ? '笔记库 大肥鱼/公约.md，电脑上也能改' : '还没连笔记库，先存在手机里'}',
          ),
          trailing: proposals.isNotEmpty
              ? StatusChip(tone: StatusTone.warn, label: '${proposals.length} 条等你批')
              : (pending.isNotEmpty ? StatusChip(tone: StatusTone.idle, label: '${pending.length} 条待生效') : null),
        ),
        if (proposals.isNotEmpty)
          ListPageCard(
            key: const ValueKey('covenant-proposals'),
            title: '她们的提议 · 等你批',
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: [
              for (final p in proposals)
                ListPageRow(
                  key: ValueKey('covenant-proposal-${p['id']}'),
                  icon: AppIcons.question,
                  iconColor: colors.warning,
                  title: '${(p['rule'] as Map?)?['title'] ?? ''}',
                  subtitle: '${names[p['by']] ?? p['by']}：${p['why']}',
                  subtitleMaxLines: 2,
                  chevron: true,
                  onTap: () => unawaited(_decide(p)),
                ),
            ],
          ),
        if (pending.isNotEmpty)
          ListPageCard(
            key: const ValueKey('covenant-pending'),
            title: '待生效的放松（$days 天冷却）',
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: [
              for (final e in pending.entries)
                ListPageRow(
                  icon: AppIcons.waiting,
                  title: e.value is Map && (e.value as Map)['value'] == null ? '删掉 ${e.key}' : '改松 ${e.key}',
                  subtitle: '冷静 $days 天再说；想撤销就在笔记库里改回去',
                ),
            ],
          ),
        for (final ch in kCovenantChapters.keys)
          ListPageCard(
            key: ValueKey('covenant-ch-$ch'),
            title: kCovenantChapters[ch],
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: [
              for (final r in rules.where((r) => '${r['id']}'.startsWith('$ch.')))
                ListPageRow(
                  key: ValueKey('covenant-rule-${r['id']}'),
                  icon: ch == 1 ? AppIcons.lock : AppIcons.instructions,
                  iconColor: ch == 1 ? colors.danger : null,
                  title: '${r['id']} ${r['title']}',
                  subtitle: '${r['do']}',
                  subtitleMaxLines: 3,
                  badge: StatusChip(
                    tone: covenantTone('${r['hardness']}'),
                    label: r['who'] == '都' ? '${r['hardness']}' : '${r['hardness']} · ${r['who']}',
                  ),
                  chevron: true,
                  onTap: () => unawaited(_edit(rule: r)),
                ),
              ListPageRow(
                key: ValueKey('covenant-add-$ch'),
                icon: AppIcons.add,
                title: '加一条',
                subtitle: ch == 1 ? '铁律：任何时候都带着，改松要等 $days 天' : null,
                chevron: true,
                onTap: () => unawaited(_edit(chapter: ch)),
              ),
            ],
          ),
      ],
    );
  }
}

/// 改一条 / 加一条。删和降硬度会走 3 天冷却（插件判）。
class _RuleEditor extends StatefulWidget {
  const _RuleEditor({this.rule, required this.chapter});

  final Map<String, dynamic>? rule;
  final int chapter;

  @override
  State<_RuleEditor> createState() => _RuleEditorState();
}

class _RuleEditorState extends State<_RuleEditor> {
  late final TextEditingController _title = TextEditingController(text: '${widget.rule?['title'] ?? ''}');
  late final TextEditingController _do = TextEditingController(text: '${widget.rule?['do'] ?? ''}');
  late String _who = '${widget.rule?['who'] ?? '都'}';
  late String _hardness = '${widget.rule?['hardness'] ?? (widget.chapter == 1 ? '铁律' : '默认')}';
  late final Set<String> _scenes = {
    if (widget.rule?['scenes'] is List) for (final s in widget.rule!['scenes'] as List) '$s' else '任何时候',
  };
  String? _error;

  @override
  void dispose() {
    _title.dispose();
    _do.dispose();
    super.dispose();
  }

  void _submit() {
    if (_title.text.trim().isEmpty || _do.text.trim().isEmpty) {
      setState(() => _error = '要写什么情况、怎么处理');
      return;
    }
    Navigator.of(context).pop(<String, Object?>{
      if (widget.rule != null) 'id': widget.rule!['id'],
      'chapter': widget.chapter,
      'title': _title.text.trim(),
      'do': _do.text.trim(),
      'who': _who,
      'hardness': _hardness,
      'scenes': _scenes.isEmpty ? const ['任何时候'] : _scenes.toList(),
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final editing = widget.rule != null;
    final wasIron = widget.rule?['hardness'] == '铁律';
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(editing ? '改 ${widget.rule!['id']}' : '加一条（${kCovenantChapters[widget.chapter]}）', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(
                editing
                    ? (wasIron ? '这是铁律：改字、降硬度、删掉都算放松，3 天后才生效。' : '降硬度、删掉算放松，3 天后才生效；改字立即生效。')
                    : '加规矩立即生效。',
                style: TextStyle(color: wasIron ? colors.warning : colors.textTertiary),
              ),
              const SizedBox(height: 12),
              TextField(
                key: const ValueKey('covenant-title'),
                controller: _title,
                maxLength: 60,
                decoration: const InputDecoration(labelText: '什么情况', hintText: '例：他说明天再做'),
              ),
              TextField(
                key: const ValueKey('covenant-do'),
                controller: _do,
                maxLength: 400,
                minLines: 2,
                maxLines: 5,
                decoration: const InputDecoration(labelText: '她们怎么处理'),
              ),
              const SizedBox(height: 8),
              Text('硬度', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 6),
              Wrap(spacing: 8, children: [
                for (final h in kCovenantHardness)
                  ChoiceChip(
                    key: ValueKey('covenant-hard-$h'),
                    label: Text(h),
                    selected: _hardness == h,
                    onSelected: (_) => setState(() => _hardness = h),
                  ),
              ]),
              const SizedBox(height: 10),
              Text('归谁', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 6),
              Wrap(spacing: 8, children: [
                for (final w in kCovenantWho)
                  ChoiceChip(label: Text(w), selected: _who == w, onSelected: (_) => setState(() => _who = w)),
              ]),
              const SizedBox(height: 10),
              Text('什么场景带着（铁律任何时候都带）', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 6),
              Wrap(spacing: 8, runSpacing: 8, children: [
                for (final s in kCovenantScenes)
                  FilterChip(
                    label: Text(s),
                    selected: _scenes.contains(s),
                    onSelected: (on) => setState(() => on ? _scenes.add(s) : _scenes.remove(s)),
                  ),
              ]),
              if (_error != null) ...[
                const SizedBox(height: 10),
                Text(_error!, style: TextStyle(color: colors.danger)),
              ],
              const SizedBox(height: 16),
              FilledButton(key: const ValueKey('covenant-save'), onPressed: _submit, child: Text(editing ? '保存' : '加上')),
              if (editing) ...[
                const SizedBox(height: 8),
                TextButton(
                  key: const ValueKey('covenant-remove'),
                  onPressed: () => Navigator.of(context).pop(<String, Object?>{'remove': true}),
                  child: Text('删掉这条（3 天后生效）', style: TextStyle(color: colors.danger)),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
